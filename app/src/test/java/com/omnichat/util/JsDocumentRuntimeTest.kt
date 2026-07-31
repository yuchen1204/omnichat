package com.omnichat.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsDocumentRuntimeTest {
    @Test
    fun parsesValidPluginJsonAndClosesRuntimeExactlyOnce() {
        val runtime = RecordingRuntime("""{"format":"txt","text":"hello","warnings":["trimmed"]}""")
        val adapter = bundledAdapter(runtime)

        val result = adapter.parse("document_plugins/test.js", input())

        assertEquals(DocumentParseResult("hello", listOf("trimmed")), result)
        assertEquals(1, runtime.closeCount.get())
        assertEquals("// trusted runtime asset", runtime.runtimeSource)
        assertEquals("function parseDocument(input) { return input; }", runtime.pluginSource)
        assertEquals("report.txt", runtime.input?.name)
        assertEquals("text/plain", runtime.input?.mimeType)
        assertArrayEquals(byteArrayOf(0, 1, -1), runtime.input?.bytes)
    }

    @Test
    fun missingFormatIsMalformedPluginResult() {
        val error = parseFailure("""{"text":"hello","warnings":[]}""")

        assertEquals(DocumentParseErrorCategory.MalformedPluginResult, error.category)
    }

    @Test
    fun nonStringTextIsMalformedPluginResult() {
        val error = parseFailure("""{"format":"txt","text":42,"warnings":[]}""")

        assertEquals(DocumentParseErrorCategory.MalformedPluginResult, error.category)
    }

    @Test
    fun nonArrayWarningsIsMalformedPluginResult() {
        val error = parseFailure("""{"format":"txt","text":"hello","warnings":"warning"}""")

        assertEquals(DocumentParseErrorCategory.MalformedPluginResult, error.category)
    }

    @Test
    fun outputAboveLimitIsPluginMemoryLimit() {
        val runtime = RecordingRuntime("""{"format":"txt","text":"123456789","warnings":[]}""")
        val adapter = bundledAdapter(runtime, maxOutputBytes = 8)

        val error = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.PluginMemoryLimit, error.category)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun runtimePluginExceptionMapsToParseFailed() {
        val runtime = RecordingRuntime(failure = IllegalStateException("plugin threw"))
        val adapter = bundledAdapter(runtime)

        val error = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.ParseFailed, error.category)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun unsupportedBinaryInputCategoryIsPreserved() {
        val runtime = RecordingRuntime(
            failure = DocumentParseException(
                DocumentParseErrorCategory.UnsupportedFormat,
                "plugin does not support this binary"
            )
        )
        val adapter = bundledAdapter(runtime)

        val error = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.UnsupportedFormat, error.category)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun inputAboveLimitFailsBeforeRuntimeCreation() {
        val created = AtomicInteger()
        val adapter = BundledQuickJsDocumentRuntime(
            assetSource = assetSource(),
            runtimeFactory = {
                created.incrementAndGet()
                RecordingRuntime("""{"format":"txt","text":"unused","warnings":[]}""")
            },
            maxInputBytes = 2
        )

        val error = try {
            adapter.parse("document_plugins/test.js", input(bytes = byteArrayOf(1, 2, 3)))
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.FileTooLarge, error.category)
        assertEquals(0, created.get())
    }

    @Test
    fun emptyTextMapsToNoExtractableText() {
        val error = parseFailure("""{"format":"txt","text":"  \n","warnings":[]}""")

        assertEquals(DocumentParseErrorCategory.NoExtractableText, error.category)
    }

    @Test
    fun assetLoadFailureMapsToPluginLoadFailedAndStillClosesRuntime() {
        val runtime = RecordingRuntime("""{"format":"txt","text":"unused","warnings":[]}""")
        val adapter = BundledQuickJsDocumentRuntime(
            assetSource = JsDocumentAssetSource { throw IllegalArgumentException("missing asset") },
            runtimeFactory = { runtime }
        )

        val error = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.PluginLoadFailed, error.category)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun invalidPluginAssetIsRejectedBeforeRuntimeCreation() {
        val created = AtomicInteger()
        val adapter = BundledQuickJsDocumentRuntime(
            assetSource = assetSource(),
            runtimeFactory = {
                created.incrementAndGet()
                RecordingRuntime("""{"format":"txt","text":"unused","warnings":[]}""")
            }
        )

        val error = try {
            adapter.parse("external.js", input())
            throw AssertionError("parse should reject an external asset")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.PluginLoadFailed, error.category)
        assertEquals(0, created.get())
    }

    @Test
    fun runtimeCreationFailureMapsToRuntimeUnavailable() {
        val adapter = BundledQuickJsDocumentRuntime(
            assetSource = assetSource(),
            runtimeFactory = { throw IllegalStateException("native unavailable") }
        )

        val error = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.RuntimeUnavailable, error.category)
    }

    @Test
    fun timeoutCancelsWorkerAndClosesRuntimeWhenEvaluationRespondsToInterrupt() {
        val runtime = BlockingRuntime()
        val adapter = bundledAdapter(runtime, timeoutMillis = 100)

        val error = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should time out")
        } catch (exception: DocumentParseException) {
            exception
        }

        assertEquals(DocumentParseErrorCategory.PluginTimeout, error.category)
        assertTrue(runtime.interrupted.await(1, TimeUnit.SECONDS))
        assertTrue(runtime.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun bundledAdapterDoesNotExposeRawScriptEntryPoint() {
        val methods = BundledQuickJsDocumentRuntime::class.java.methods.map { it.name }.toSet()

        assertFalse(methods.contains("parseRaw"))
        assertTrue(methods.contains("parse"))
    }

    private fun parseFailure(json: String): DocumentParseException {
        val runtime = RecordingRuntime(json)
        return try {
            bundledAdapter(runtime).parse("document_plugins/test.js", input())
            throw AssertionError("parse should fail")
        } catch (exception: DocumentParseException) {
            assertEquals(1, runtime.closeCount.get())
            exception
        }
    }

    private fun bundledAdapter(
        runtime: JsDocumentRuntime,
        maxOutputBytes: Int = 1024,
        timeoutMillis: Long = 5_000
    ): BundledQuickJsDocumentRuntime = BundledQuickJsDocumentRuntime(
        assetSource = assetSource(),
        runtimeFactory = { runtime },
        maxOutputBytes = maxOutputBytes,
        timeoutMillis = timeoutMillis
    )

    private fun assetSource(): JsDocumentAssetSource = JsDocumentAssetSource { path ->
        when (path) {
            "document_plugins/runtime.js" -> "// trusted runtime asset"
            "document_plugins/test.js" -> "function parseDocument(input) { return input; }"
            else -> error("unexpected asset: $path")
        }
    }

    private fun input(bytes: ByteArray = byteArrayOf(0, 1, -1)) = JsDocumentInput(
        name = "report.txt",
        mimeType = "text/plain",
        bytes = bytes
    )

    private class BlockingRuntime : JsDocumentRuntime {
        val interrupted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val neverReleased = CountDownLatch(1)

        override fun parse(
            pluginSource: String,
            runtimeSource: String,
            input: JsDocumentInput
        ): String {
            try {
                neverReleased.await()
                error("unreachable")
            } catch (error: InterruptedException) {
                interrupted.countDown()
                throw error
            }
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class RecordingRuntime(
        private val result: String? = null,
        private val failure: Throwable? = null
    ) : JsDocumentRuntime {
        val closeCount = AtomicInteger()
        var runtimeSource: String? = null
        var pluginSource: String? = null
        var input: JsDocumentInput? = null

        override fun parse(
            pluginSource: String,
            runtimeSource: String,
            input: JsDocumentInput
        ): String {
            this.pluginSource = pluginSource
            this.runtimeSource = runtimeSource
            this.input = input.copy(bytes = input.bytes.copyOf())
            failure?.let { throw it }
            return result ?: error("missing recording result")
        }

        override fun close() {
            assertEquals(0, closeCount.getAndIncrement())
        }
    }
}
