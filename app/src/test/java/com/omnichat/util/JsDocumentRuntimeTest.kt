package com.omnichat.util

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    fun repeatedBackToBackParsesDoNotHitTransientRuntimeUnavailable() {
        val created = AtomicInteger()
        val adapter = JsDocumentRuntimeCoordinator(
            assetSource = assetSource(),
            runtimeFactory = {
                created.incrementAndGet()
                RecordingRuntime("""{"format":"txt","text":"ok","warnings":[]}""")
            },
            maxConcurrentTasks = 1,
            maxOrphanTasks = 1
        )

        try {
            repeat(500) {
                assertEquals(
                    DocumentParseResult("ok"),
                    adapter.parse("document_plugins/test.js", input())
                )
            }
        } finally {
            adapter.close()
        }

        assertEquals(500, created.get())
    }

    @Test
    fun concurrentSubmissionsWithinConfiguredLimitCompleteWithoutRejection() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val created = AtomicInteger()
        val adapter = JsDocumentRuntimeCoordinator(
            assetSource = assetSource(),
            runtimeFactory = {
                created.incrementAndGet()
                CoordinatedRuntime(started, release)
            },
            maxConcurrentTasks = 2,
            maxOrphanTasks = 1
        )
        val callers = Executors.newFixedThreadPool(2)

        try {
            val parses = (1..2).map {
                callers.submit<DocumentParseResult> {
                    adapter.parse("document_plugins/test.js", input())
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            release.countDown()
            parses.forEach { parse ->
                assertEquals(DocumentParseResult("concurrent"), parse.get(1, TimeUnit.SECONDS))
            }
            assertEquals(2, created.get())
        } finally {
            release.countDown()
            adapter.close()
            callers.shutdownNow()
        }
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
    fun nonStringWarningMemberIsMalformedPluginResult() {
        val error = parseFailure("""{"format":"txt","text":"hello","warnings":[7]}""")

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
        val adapter = JsDocumentRuntimeCoordinator(
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
        val adapter = JsDocumentRuntimeCoordinator(
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
        val adapter = JsDocumentRuntimeCoordinator(
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
        val adapter = JsDocumentRuntimeCoordinator(
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
    fun nonCooperativeTimeoutConsumesOrphanBudgetUntilWorkerFinallyCloses() {
        val orphan = NonCooperativeRuntime()
        val replacement = RecordingRuntime("""{"format":"txt","text":"replacement","warnings":[]}""")
        val created = AtomicInteger()
        val adapter = JsDocumentRuntimeCoordinator(
            assetSource = assetSource(),
            runtimeFactory = {
                if (created.getAndIncrement() == 0) orphan else replacement
            },
            timeoutMillis = 50,
            maxConcurrentTasks = 1,
            maxOrphanTasks = 1
        )

        val timeout = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should time out")
        } catch (exception: DocumentParseException) {
            exception
        }
        assertEquals(DocumentParseErrorCategory.PluginTimeout, timeout.category)
        assertTrue(orphan.started.await(1, TimeUnit.SECONDS))

        val rejectedWhileOrphaned = try {
            adapter.parse("document_plugins/test.js", input())
            throw AssertionError("parse should be rejected while orphan budget is exhausted")
        } catch (exception: DocumentParseException) {
            exception
        }
        assertEquals(DocumentParseErrorCategory.RuntimeUnavailable, rejectedWhileOrphaned.category)
        assertEquals(1, created.get())

        orphan.release()
        assertTrue(orphan.closed.await(1, TimeUnit.SECONDS))
        assertEquals(
            DocumentParseResult("replacement"),
            adapter.parse("document_plugins/test.js", input())
        )
        assertEquals(1, orphan.closeCount.get())
        assertEquals(1, replacement.closeCount.get())
        adapter.close()
    }

    @Test
    fun closeReturnsAsCancellationRequestAndPreventsParseAfterClose() {
        val runtime = NonCooperativeRuntime()
        val adapter = bundledAdapter(runtime, timeoutMillis = 5_000)
        val parseError = AtomicReference<Throwable?>()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val parseFuture = caller.submit {
                try {
                    adapter.parse("document_plugins/test.js", input())
                } catch (error: Throwable) {
                    parseError.set(error)
                }
            }
            assertTrue(runtime.started.await(1, TimeUnit.SECONDS))
            adapter.close()
            runtime.release()
            parseFuture.get(1, TimeUnit.SECONDS)

            try {
                adapter.parse("document_plugins/test.js", input())
                throw AssertionError("parse after close should fail")
            } catch (error: IllegalStateException) {
                assertEquals("JavaScript document runtime is closed", error.message)
            }
            assertTrue(parseError.get() is DocumentParseException)
        } finally {
            runtime.release()
            assertTrue(runtime.closed.await(1, TimeUnit.SECONDS))
            caller.shutdownNow()
        }
    }

    @Test
    fun parseSnapshotsInputBytesBeforeCallerCanMutateThem() {
        val runtime = SnapshotBlockingRuntime()
        val adapter = bundledAdapter(runtime, timeoutMillis = 5_000)
        val originalBytes = byteArrayOf(1, 2, 3)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val future = caller.submit<DocumentParseResult> {
                adapter.parse("document_plugins/test.js", input(bytes = originalBytes))
            }
            assertTrue(runtime.started.await(1, TimeUnit.SECONDS))
            originalBytes[0] = 99
            runtime.release()
            assertEquals(DocumentParseResult("snapshot"), future.get(1, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3), runtime.seenBytes)
        } finally {
            runtime.release()
            caller.shutdownNow()
        }
    }

    @Test
    fun defaultInputLimitAcceptsFourMiBAndRejectsOneByteOver() {
        val created = AtomicInteger()
        val adapter = JsDocumentRuntimeCoordinator(
            assetSource = assetSource(),
            runtimeFactory = {
                created.incrementAndGet()
                RecordingRuntime("""{"format":"txt","text":"ok","warnings":[]}""")
            }
        )
        val exactLimit = ByteArray(4 * 1024 * 1024)
        assertEquals(
            DocumentParseResult("ok"),
            adapter.parse("document_plugins/test.js", input(bytes = exactLimit))
        )
        val tooLarge = try {
            adapter.parse("document_plugins/test.js", input(bytes = ByteArray(exactLimit.size + 1)))
            throw AssertionError("input over the default limit should fail")
        } catch (exception: DocumentParseException) {
            exception
        }
        assertEquals(DocumentParseErrorCategory.FileTooLarge, tooLarge.category)
        assertEquals(1, created.get())
    }

    @Test
    fun resourceWordsInArbitraryPluginMessageRemainParseFailed() {
        val error = parseFailureWith(
            IOException("memory pressure in document")
        )

        assertEquals(DocumentParseErrorCategory.ParseFailed, error.category)
    }

    @Test
    fun exactQuickJsResourceLimitMessageMapsToMemoryLimit() {
        val error = parseFailureWith(IllegalStateException("out of memory"))

        assertEquals(DocumentParseErrorCategory.PluginMemoryLimit, error.category)
    }

    @Test
    fun bundledAdapterDoesNotExposeRawScriptEntryPointOrTestSeams() {
        val publicMethods = BundledQuickJsDocumentRuntime::class.java.methods.map { it.name }.toSet()
        val constructors = BundledQuickJsDocumentRuntime::class.java.declaredConstructors

        assertFalse(publicMethods.contains("parseRaw"))
        assertTrue(publicMethods.contains("parse"))
        assertTrue(constructors.all { constructor ->
            constructor.parameterTypes.contentEquals(arrayOf(android.content.res.AssetManager::class.java))
        })
        assertTrue(constructors.none { constructor ->
            constructor.parameterTypes.any { parameter ->
                parameter.name.contains("JsDocumentAssetSource") ||
                    parameter.name.contains("Function")
            }
        })
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

    private fun parseFailureWith(failure: Throwable): DocumentParseException {
        val runtime = RecordingRuntime(failure = failure)
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
    ): JsDocumentRuntimeCoordinator = JsDocumentRuntimeCoordinator(
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

    private class NonCooperativeRuntime : JsDocumentRuntime {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        val closeCount = AtomicInteger()

        override fun parse(
            pluginSource: String,
            runtimeSource: String,
            input: JsDocumentInput
        ): String {
            started.countDown()
            var released = false
            while (!released) {
                try {
                    releaseLatch.await()
                    released = true
                } catch (_: InterruptedException) {
                    // A native evaluate() may ignore Java interruption and keep running.
                }
            }
            return """{"format":"txt","text":"released","warnings":[]}"""
        }

        fun release() {
            releaseLatch.countDown()
        }

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }

    private class CoordinatedRuntime(
        private val started: CountDownLatch,
        private val release: CountDownLatch
    ) : JsDocumentRuntime {
        override fun parse(
            pluginSource: String,
            runtimeSource: String,
            input: JsDocumentInput
        ): String {
            started.countDown()
            release.await()
            return """{"format":"txt","text":"concurrent","warnings":[]}"""
        }

        override fun close() = Unit
    }

    private class SnapshotBlockingRuntime : JsDocumentRuntime {
        val started = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        var seenBytes: ByteArray = byteArrayOf()

        override fun parse(
            pluginSource: String,
            runtimeSource: String,
            input: JsDocumentInput
        ): String {
            seenBytes = input.bytes.copyOf()
            started.countDown()
            releaseLatch.await()
            return """{"format":"txt","text":"snapshot","warnings":[]}"""
        }

        fun release() {
            releaseLatch.countDown()
        }

        override fun close() = Unit
    }

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
