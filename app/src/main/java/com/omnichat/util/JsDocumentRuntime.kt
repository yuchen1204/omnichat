package com.omnichat.util

import android.content.res.AssetManager
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * The public constructor accepts Android's [AssetManager], not arbitrary JavaScript. The
 * internal source constructor exists only for JVM tests. Every parse creates a new runtime on
 * its own single-thread executor; the runtime context is therefore created, evaluated, and
 * closed on the same thread.
 */
class BundledQuickJsDocumentRuntime internal constructor(
    private val assetSource: JsDocumentAssetSource,
    private val runtimeFactory: () -> JsDocumentRuntime = { AndroidQuickJsDocumentRuntime() },
    private val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val executorIds = AtomicLong(0)
    private val activeExecutors = ConcurrentHashMap.newKeySet<ExecutorService>()

    init {
        require(maxInputBytes > 0) { "maxInputBytes must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    constructor(assetManager: AssetManager) : this(
        assetSource = JsDocumentAssetSource { path ->
            assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    )

    /** Parse one bundled plugin synchronously and return its validated typed result. */
    fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult {
        check(!closed.get()) { "JavaScript document runtime is closed" }
        validateAssetPath(pluginAsset)
        validateInput(input)

        val parseExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "quickjs-document-${executorIds.incrementAndGet()}"
            ).apply { isDaemon = true }
        }
        activeExecutors += parseExecutor

        val future: Future<DocumentParseResult> = try {
            parseExecutor.submit<DocumentParseResult> {
                val runtime = try {
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
                        input = input.copy(bytes = input.bytes.copyOf())
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
                } finally {
                    runtime.close()
                }
            }
        } catch (error: Throwable) {
            parseExecutor.shutdownNow()
            activeExecutors -= parseExecutor
            throw DocumentParseException(
                DocumentParseErrorCategory.RuntimeUnavailable,
                "Document plugin worker could not be started",
                error
            )
        }

        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginTimeout,
                "Document plugin exceeded the ${timeoutMillis}ms deadline",
                error
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginTimeout,
                "Document plugin execution was interrupted",
                error
            )
        } catch (error: ExecutionException) {
            throw unwrapExecutionException(error)
        } finally {
            // QuickJS itself is closed by the worker's finally block. Interrupt is cooperative:
            // if native evaluate() does not return immediately, its worker will close the context
            // when evaluation unwinds; this is not presented as a hard interruption guarantee.
            parseExecutor.shutdownNow()
            activeExecutors -= parseExecutor
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeExecutors.forEach { it.shutdownNow() }
            activeExecutors.clear()
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
        val message = error.message.orEmpty().lowercase()
        val isResourceLimit = "memory" in message ||
            "out of memory" in message ||
            "stack" in message ||
            "resource limit" in message
        return DocumentParseException(
            if (isResourceLimit) {
                DocumentParseErrorCategory.PluginMemoryLimit
            } else {
                DocumentParseErrorCategory.ParseFailed
            },
            if (isResourceLimit) {
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

    private companion object {
        const val RUNTIME_ASSET = "document_plugins/runtime.js"
        const val DEFAULT_MAX_INPUT_BYTES = 20 * 1024 * 1024
        const val DEFAULT_MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

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
    private val context: QuickJSContext

    init {
        QuickJSLoader.init()
        context = QuickJSContext.create()
        // These APIs are exposed by wrapper-android:3.2.3. They are hard engine caps; the
        // Kotlin input/output and executor deadline guards remain necessary as well.
        context.setMemoryLimit(MEMORY_LIMIT_BYTES)
        context.setMaxStackSize(STACK_LIMIT_BYTES)
    }

    override fun parse(
        pluginSource: String,
        runtimeSource: String,
        input: JsDocumentInput
    ): String {
        val encodedBytes = Base64.getEncoder().encodeToString(input.bytes.copyOf())
        val script = buildEvaluationScript(
            runtimeSource = runtimeSource,
            pluginSource = pluginSource,
            name = input.name,
            mimeType = input.mimeType,
            base64Bytes = encodedBytes
        )
        return context.evaluate(script) as? String
            ?: throw IllegalStateException("Document plugin did not return a JSON string")
    }

    override fun close() {
        context.close()
    }

    private fun buildEvaluationScript(
        runtimeSource: String,
        pluginSource: String,
        name: String,
        mimeType: String,
        base64Bytes: String
    ): String {
        val runtimeLiteral = JSONObject.quote(runtimeSource)
        val pluginLiteral = JSONObject.quote(pluginSource)
        val nameLiteral = JSONObject.quote(name)
        val mimeTypeLiteral = JSONObject.quote(mimeType)
        val bytesLiteral = JSONObject.quote(base64Bytes)
        return """
            (function() {
              const __runtimeSource = $runtimeLiteral;
              const __pluginSource = $pluginLiteral;
              const __name = $nameLiteral;
              const __mimeType = $mimeTypeLiteral;
              const __base64 = $bytesLiteral;
              function __decodeBase64(value) {
                const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                const result = [];
                let buffer = 0;
                let bits = 0;
                for (let i = 0; i < value.length; i++) {
                  const code = alphabet.indexOf(value[i]);
                  if (code < 0) continue;
                  buffer = (buffer << 6) | code;
                  bits += 6;
                  if (bits >= 8) {
                    bits -= 8;
                    result.push((buffer >> bits) & 255);
                  }
                }
                return new Uint8Array(result);
              }
              const __pluginFactory = new Function(
                __runtimeSource + "\\n" + __pluginSource +
                "\\nif (typeof parseDocument !== 'function') {" +
                " throw new Error('bundled plugin must define synchronous parseDocument'); }" +
                "\\nreturn parseDocument;"
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
              JSON.stringify(__result);
            })()
        """.trimIndent()
    }

    private companion object {
        const val MEMORY_LIMIT_BYTES = 32 * 1024 * 1024
        const val STACK_LIMIT_BYTES = 512 * 1024
    }
}
