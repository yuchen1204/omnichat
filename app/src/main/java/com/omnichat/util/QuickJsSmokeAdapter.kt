package com.omnichat.util

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small runtime-only probe for the embedded QuickJS wrapper.
 *
 * This is deliberately not a document parser. It verifies the host boundary used by
 * later document plugins: a copied binary input can be made visible as a Uint8Array,
 * a script can return JSON, and the context is destroyed on the same thread it created.
 */
class QuickJsSmokeAdapter(
    private val contextFactory: QuickJsSmokeContextFactory = QuickJsSmokeContextFactory {
        AndroidQuickJsSmokeContext()
    }
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "quickjs-smoke").apply { isDaemon = true }
    }

    fun probe(bytes: ByteArray, script: String = SMOKE_SCRIPT): QuickJsSmokeResult {
        check(!closed.get()) { "QuickJS smoke adapter is closed" }
        val future: Future<QuickJsSmokeResult> = executor.submit<QuickJsSmokeResult> {
            val context = contextFactory.create()
            try {
                context.setInput(bytes.copyOf())
                parseResult(context.evaluate(script))
            } finally {
                context.close()
            }
        }

        return try {
            future.get()
        } catch (error: ExecutionException) {
            val cause = error.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("QuickJS smoke probe failed", cause)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }

    private fun parseResult(json: String): QuickJsSmokeResult {
        val normalized = json.trim()
        val match = RESULT_PATTERN.matchEntire(normalized)
            ?: throw IllegalStateException(
                "QuickJS smoke result was not the expected JSON object: $normalized"
            )
        val ok = match.groups[1]?.value == "true"
        val length = match.groups[2]?.value?.toIntOrNull()
            ?: throw IllegalStateException("QuickJS smoke result length was not an integer")
        check(ok) { "QuickJS smoke script returned ok=false" }
        check(length >= 0) { "QuickJS smoke result length was negative" }
        return QuickJsSmokeResult(ok = ok, length = length)
    }

    private class AndroidQuickJsSmokeContext : QuickJsSmokeContext {
        private val context: QuickJSContext

        init {
            QuickJSLoader.init()
            context = QuickJSContext.create()
        }

        override fun setInput(bytes: ByteArray) {
            context.getGlobalObject().setProperty("bytes", bytes.copyOf())
        }

        override fun evaluate(script: String): String = context.evaluate(script) as? String
            ?: throw IllegalStateException("QuickJS smoke script did not return a string")

        override fun close() {
            context.close()
        }
    }

    private companion object {
        const val SMOKE_SCRIPT =
            "JSON.stringify({ok:true, length:new Uint8Array(bytes).byteLength})"
        val RESULT_PATTERN = Regex("\\{\\\"ok\\\":(true|false),\\\"length\\\":([0-9]+)\\}")
    }
}

data class QuickJsSmokeResult(
    val ok: Boolean,
    val length: Int
)

fun interface QuickJsSmokeContextFactory {
    fun create(): QuickJsSmokeContext
}

interface QuickJsSmokeContext {
    fun setInput(bytes: ByteArray)
    fun evaluate(script: String): String
    fun close()
}
