package com.omnichat.util

import android.content.res.AssetManager
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous host boundary used by bundled document plugins. */
interface JsDocumentRuntime {
    fun parse(pluginSource: String, runtimeSource: String, input: JsDocumentInput): String
    fun close()
}

/** Reads only app-bundled JavaScript assets. */
internal fun interface JsDocumentAssetSource {
    fun read(path: String): String
}

/**
 * Production facade for the synchronous QuickJS wrapper.
 *
 * The only production construction path accepts Android's [AssetManager]. Source and runtime
 * factories are deliberately owned by the internal coordinator so production callers cannot
 * inject arbitrary JavaScript or an arbitrary runtime through this class's constructor.
 */
class BundledQuickJsDocumentRuntime(
    assetManager: AssetManager
) : AutoCloseable {
    private val coordinator = JsDocumentRuntimeCoordinator(
        assetSource = JsDocumentAssetSource { path ->
            assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        runtimeFactory = { AndroidQuickJsDocumentRuntime() }
    )

    /** Parse one bundled plugin synchronously and return its validated typed result. */
    fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult =
        coordinator.parse(pluginAsset, input)

    override fun close() {
        coordinator.close()
    }
}

/**
 * Internal coordinator used by the Android facade and JVM tests.
 *
 * A single shared, bounded executor is used for all parses owned by this coordinator. The
 * bounded handoff queue absorbs the short worker handoff window without allowing an unbounded
 * backlog. A timed-out task remains registered until its
 * worker finally unwinds; if native evaluate ignores interruption, it is an orphan and consumes
 * the configured orphan budget. This is intentionally a cooperative timeout, not a native hard
 * kill.
 */
internal class JsDocumentRuntimeCoordinator(
    private val assetSource: JsDocumentAssetSource,
    private val runtimeFactory: () -> JsDocumentRuntime,
    private val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxConcurrentTasks: Int = DEFAULT_MAX_CONCURRENT_TASKS,
    private val maxOrphanTasks: Int = DEFAULT_MAX_ORPHAN_TASKS
) : AutoCloseable {
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val taskIds = AtomicLong(0)
    private val activeTasks = LinkedHashSet<ActiveTask>()
    private var orphanTaskCount = 0
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        maxConcurrentTasks,
        maxConcurrentTasks,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxConcurrentTasks),
        { runnable ->
            Thread(
                runnable,
                "quickjs-document-${taskIds.incrementAndGet()}"
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    init {
        require(maxInputBytes > 0) { "maxInputBytes must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxConcurrentTasks > 0) { "maxConcurrentTasks must be positive" }
        require(maxOrphanTasks >= 0) { "maxOrphanTasks must not be negative" }
    }

    /**
     * Parse one selected bundled plugin synchronously.
     *
     * Input bytes are snapshotted while the parse is registered. [close] and registration use the
     * same lock, so after close returns no new task can be accepted or start. Close is a
     * non-blocking cancellation request for already-running native work; a non-cooperative native
     * call may finish later on its daemon worker, and its task remains registered until then.
     */
    fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult {
        val inputSnapshot: JsDocumentInput
        val task: ActiveTask
        val future: Future<DocumentParseResult>

        synchronized(stateLock) {
            check(!closed.get()) { "JavaScript document runtime is closed" }
            validateAssetPath(pluginAsset)
            validateInput(input)
            inputSnapshot = input.copy(bytes = input.bytes.copyOf())

            if (orphanTaskCount > 0 &&
                (maxOrphanTasks == 0 || orphanTaskCount >= maxOrphanTasks)
            ) {
                throw temporarilyUnavailable(
                    "A previous timed-out document plugin is still unwinding"
                )
            }
            if (activeTasks.size >= maxConcurrentTasks) {
                throw temporarilyUnavailable(
                    "The document JavaScript runtime has reached its active task limit"
                )
            }

            task = ActiveTask()
            activeTasks += task
            future = try {
                val submitted = FutureTask(
                    Callable { runTask(task, pluginAsset, inputSnapshot) }
                )
                task.future = submitted
                executor.execute(submitted)
                submitted
            } catch (error: RejectedExecutionException) {
                activeTasks.remove(task)
                throw temporarilyUnavailable(
                    "The document JavaScript runtime could not accept another task",
                    error
                )
            } catch (error: Throwable) {
                activeTasks.remove(task)
                throw DocumentParseException(
                    DocumentParseErrorCategory.RuntimeUnavailable,
                    "Document plugin worker could not be started",
                    error
                )
            }
        }

        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            requestCancellation(task)
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginTimeout,
                "Document plugin exceeded the ${timeoutMillis}ms deadline",
                error
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            requestCancellation(task)
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginTimeout,
                "Document plugin execution was interrupted",
                error
            )
        } catch (error: CancellationException) {
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginTimeout,
                "Document plugin execution was cancelled",
                error
            )
        } catch (error: ExecutionException) {
            throw unwrapExecutionException(error)
        }
    }

    /**
     * Marks the coordinator closed and requests cancellation of the current task snapshot.
     * Already-running non-cooperative native work is not forcibly terminated and is not removed
     * from the active registry until its worker finally closes its runtime.
     */
    override fun close() {
        synchronized(stateLock) {
            if (closed.getAndSet(true)) return
            val tasks = activeTasks.toList()
            tasks.forEach { requestCancellationLocked(it) }
            executor.shutdownNow()
        }
    }

    private fun runTask(
        task: ActiveTask,
        pluginAsset: String,
        input: JsDocumentInput
    ): DocumentParseResult {
        synchronized(stateLock) {
            if (task.cancelledBeforeStart) {
                finishTaskLocked(task)
                throw CancellationException("Document plugin task was cancelled before start")
            }
            task.started = true
        }

        var runtime: JsDocumentRuntime? = null
        return try {
            runtime = try {
                runtimeFactory()
            } catch (error: Throwable) {
                throw DocumentParseException(
                    DocumentParseErrorCategory.RuntimeUnavailable,
                    "JavaScript runtime could not be created",
                    error
                )
            }
            try {
                val runtimeSource = loadAsset(RUNTIME_ASSET)
                val pluginSource = loadAsset(pluginAsset)
                val json = runtime.parse(
                    pluginSource = pluginSource,
                    runtimeSource = runtimeSource,
                    input = input
                )
                validateOutputSize(json)
                parsePluginResult(json)
            } catch (error: DocumentParseException) {
                throw error
            } catch (error: CancellationException) {
                throw DocumentParseException(
                    DocumentParseErrorCategory.PluginTimeout,
                    "Document plugin execution was cancelled",
                    error
                )
            } catch (error: Throwable) {
                throw classifyRuntimeFailure(error)
            }
        } finally {
            try {
                runtime?.close()
            } finally {
                synchronized(stateLock) {
                    finishTaskLocked(task)
                }
            }
        }
    }

    private fun requestCancellation(task: ActiveTask) {
        synchronized(stateLock) {
            requestCancellationLocked(task)
        }
    }

    private fun requestCancellationLocked(task: ActiveTask) {
        if (task.completed) return
        task.cancellationRequested = true
        task.future?.cancel(true)
        if (task.started) {
            if (!task.orphan) {
                task.orphan = true
                orphanTaskCount += 1
            }
        } else {
            task.cancelledBeforeStart = true
            executor.remove(task.future)
            finishTaskLocked(task)
        }
    }

    private fun finishTaskLocked(task: ActiveTask) {
        if (task.completed) return
        task.completed = true
        activeTasks.remove(task)
        if (task.orphan) {
            orphanTaskCount -= 1
        }
    }

    private fun loadAsset(path: String): String = try {
        assetSource.read(path)
    } catch (error: Throwable) {
        throw DocumentParseException(
            DocumentParseErrorCategory.PluginLoadFailed,
            "Bundled document plugin asset could not be loaded",
            error
        )
    }

    private fun validateInput(input: JsDocumentInput) {
        if (input.bytes.size > maxInputBytes) {
            throw DocumentParseException(
                DocumentParseErrorCategory.FileTooLarge,
                "Document input exceeds the configured byte limit"
            )
        }
    }

    private fun validateOutputSize(json: String) {
        if (json.toByteArray(Charsets.UTF_8).size > maxOutputBytes) {
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginMemoryLimit,
                "Document plugin output exceeds the configured byte limit"
            )
        }
    }

    private fun parsePluginResult(json: String): DocumentParseResult {
        val root = try {
            JSONObject(json)
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.MalformedPluginResult,
                "Document plugin did not return a JSON object",
                error
            )
        }

        if (root.opt("format") !is String || root.getString("format").isBlank()) {
            throw malformed("Document plugin result format must be a non-empty string")
        }
        if (root.opt("text") !is String) {
            throw malformed("Document plugin result text must be a string")
        }
        if (root.opt("warnings") !is JSONArray) {
            throw malformed("Document plugin result warnings must be an array")
        }

        val text = root.getString("text")
        val warningsJson = root.getJSONArray("warnings")
        val warnings = ArrayList<String>(warningsJson.length())
        for (index in 0 until warningsJson.length()) {
            if (warningsJson.opt(index) !is String) {
                throw malformed("Document plugin result warnings must contain only strings")
            }
            warnings += warningsJson.getString(index)
        }
        if (text.isBlank()) {
            throw DocumentParseException(
                DocumentParseErrorCategory.NoExtractableText,
                "Document plugin returned no extractable text"
            )
        }
        return DocumentParseResult(text = text, warnings = warnings)
    }

    private fun classifyRuntimeFailure(error: Throwable): DocumentParseException {
        val message = error.message.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")
        val isQuickJsResourceLimit = message in QUICKJS_RESOURCE_LIMIT_MESSAGES
        return DocumentParseException(
            if (isQuickJsResourceLimit) {
                DocumentParseErrorCategory.PluginMemoryLimit
            } else {
                DocumentParseErrorCategory.ParseFailed
            },
            if (isQuickJsResourceLimit) {
                "Document plugin exceeded a QuickJS resource limit"
            } else {
                "Document plugin execution failed"
            },
            error
        )
    }

    private fun malformed(message: String): DocumentParseException = DocumentParseException(
        DocumentParseErrorCategory.MalformedPluginResult,
        message
    )

    private fun unwrapExecutionException(error: ExecutionException): DocumentParseException {
        val cause = error.cause
        return when (cause) {
            is DocumentParseException -> cause
            else -> classifyRuntimeFailure(cause ?: error)
        }
    }

    private fun temporarilyUnavailable(message: String, cause: Throwable? = null) =
        DocumentParseException(
            DocumentParseErrorCategory.RuntimeUnavailable,
            message,
            cause
        )

    private class ActiveTask {
        var future: FutureTask<*>? = null
        var started = false
        var cancellationRequested = false
        var cancelledBeforeStart = false
        var orphan = false
        var completed = false
    }

    private companion object {
        const val RUNTIME_ASSET = "document_plugins/runtime.js"
        // Base64 adds 4/3 input bytes, while the decoded Uint8Array and the evaluated script also
        // coexist briefly. Four MiB keeps the bridge well below the 32 MiB QuickJS cap.
        const val DEFAULT_MAX_INPUT_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_MAX_CONCURRENT_TASKS = 1
        const val DEFAULT_MAX_ORPHAN_TASKS = 1
        val QUICKJS_RESOURCE_LIMIT_MESSAGES = setOf(
            "out of memory",
            "internalerror: out of memory",
            "stack overflow",
            "internalerror: stack overflow",
            "maximum call stack size exceeded",
            "internalerror: maximum call stack size exceeded"
        )

        fun validateAssetPath(path: String) {
            if (!path.startsWith("document_plugins/") ||
                !path.endsWith(".js") ||
                path.contains("..") ||
                path.contains('\\')
            ) {
                throw DocumentParseException(
                    DocumentParseErrorCategory.PluginLoadFailed,
                    "Document plugins must be selected from bundled document_plugins/*.js assets"
                )
            }
        }
    }
}

/** Adapter around wrapper-android 3.2.3. All QuickJS operations share one worker thread. */
private class AndroidQuickJsDocumentRuntime : JsDocumentRuntime {
    private val context: QuickJSContext = createConfiguredContext()

    override fun parse(
        pluginSource: String,
        runtimeSource: String,
        input: JsDocumentInput
    ): String {
        val copiedBytes = input.bytes.copyOf()
        val encodedBytes = Base64.getEncoder().encodeToString(copiedBytes)
        context.getGlobalObject().setProperty("__documentName", input.name)
        context.getGlobalObject().setProperty("__documentMimeType", input.mimeType)
        context.getGlobalObject().setProperty("__documentBase64", encodedBytes)
        val script = buildEvaluationScript(
            runtimeSource = runtimeSource,
            pluginSource = pluginSource
        )
        return context.evaluate(script) as? String
            ?: throw IllegalStateException("Document plugin did not return a JSON string")
    }

    override fun close() {
        context.close()
    }

    private fun buildEvaluationScript(
        runtimeSource: String,
        pluginSource: String
    ): String {
        val runtimeLiteral = JSONObject.quote(runtimeSource)
        val pluginLiteral = JSONObject.quote(pluginSource)
        return """
            (function() {
              const __runtimeSource = $runtimeLiteral;
              const __pluginSource = $pluginLiteral;
              const __name = globalThis.__documentName;
              const __mimeType = globalThis.__documentMimeType;
              const __base64 = globalThis.__documentBase64;
              delete globalThis.__documentName;
              delete globalThis.__documentMimeType;
              delete globalThis.__documentBase64;
              function __decodeBase64(value) {
                const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                const padding = value.endsWith("==") ? 2 : (value.endsWith("=") ? 1 : 0);
                const output = new Uint8Array(Math.floor(value.length * 3 / 4) - padding);
                let buffer = 0;
                let bits = 0;
                let outputIndex = 0;
                for (let i = 0; i < value.length; i++) {
                  const code = alphabet.indexOf(value[i]);
                  if (code < 0) continue;
                  buffer = (buffer << 6) | code;
                  bits += 6;
                  if (bits >= 8) {
                    bits -= 8;
                    output[outputIndex++] = (buffer >> bits) & 255;
                  }
                }
                return output;
              }
              const __pluginFactory = new Function(
                __runtimeSource + "\n" + __pluginSource +
                "\nif (typeof parseDocument !== 'function') {" +
                " throw new Error('bundled plugin must define synchronous parseDocument'); }" +
                "\nreturn parseDocument;"
              );
              const __parseDocument = __pluginFactory();
              const __result = __parseDocument({
                name: __name,
                mimeType: __mimeType,
                bytes: __decodeBase64(__base64)
              });
              if (__result && typeof __result.then === "function") {
                throw new Error("document plugin must return synchronously, not a Promise");
              }
              return JSON.stringify(__result);
            })()
        """.trimIndent()
    }

    private companion object {
        const val MEMORY_LIMIT_BYTES = 32 * 1024 * 1024
        const val STACK_LIMIT_BYTES = 512 * 1024

        fun createConfiguredContext(): QuickJSContext {
            QuickJSLoader.init()
            val createdContext = QuickJSContext.create()
            return try {
                createdContext.setMemoryLimit(MEMORY_LIMIT_BYTES)
                createdContext.setMaxStackSize(STACK_LIMIT_BYTES)
                createdContext
            } catch (error: Throwable) {
                try {
                    createdContext.close()
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                throw error
            }
        }
    }
}
