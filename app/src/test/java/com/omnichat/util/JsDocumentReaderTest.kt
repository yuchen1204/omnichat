package com.omnichat.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [34])
class JsDocumentReaderTest {

    private lateinit var fakeRuntime: FakeBundledRuntime
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver

    @Before
    fun setUp() {
        fakeRuntime = FakeBundledRuntime()
        context = mock(Context::class.java)
        resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
    }

    @Test
    fun `dispatches PDF by file extension`() = runTest {
        val uri = Uri.parse("content://test/report.pdf")
        val cursor = fakeCursor("report.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("pdf text extracted")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("pdf text extracted", result.text)
        assertEquals("document_plugins/pdf-reader.js", fakeRuntime.lastPluginAsset)
    }

    @Test
    fun `dispatches DOCX by file extension`() = runTest {
        val uri = Uri.parse("content://test/doc.docx")
        val cursor = fakeCursor("doc.docx")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(DOCX_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("docx text content")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("docx text content", result.text)
        assertEquals("document_plugins/docx-reader.js", fakeRuntime.lastPluginAsset)
    }

    @Test
    fun `dispatches PDF by MIME type when extension is ambiguous`() = runTest {
        val uri = Uri.parse("content://test/file")
        val cursor = fakeCursor("file")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("Application/PDF; charset=binary")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("pdf content")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("pdf content", result.text)
        assertEquals("document_plugins/pdf-reader.js", fakeRuntime.lastPluginAsset)
    }

    @Test
    fun `dispatches DOCX by MIME type when extension is ambiguous`() = runTest {
        val uri = Uri.parse("content://test/file")
        val cursor = fakeCursor("file")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(DOCX_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("docx content")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("docx content", result.text)
        assertEquals("document_plugins/docx-reader.js", fakeRuntime.lastPluginAsset)
    }

    @Test
    fun `runtime receives document metadata and bytes but never the original URI`() = runTest {
        val uri = Uri.parse("content://private/report.pdf")
        val cursor = fakeCursor("report.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("text")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        reader.parse(uri)

        val input = fakeRuntime.lastInput
        assertEquals("report.pdf", input?.name)
        assertEquals("application/pdf", input?.mimeType)
        assertEquals(PDF_MAGIC_BYTES.toList(), input?.bytes?.toList())
        assertFalse(input?.name?.contains(uri.toString()) == true)
    }

    @Test
    fun `rejects TXT file as unsupported format`() = runTest {
        val uri = Uri.parse("content://test/notes.txt")
        val cursor = fakeCursor("notes.txt")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("text/plain")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnsupportedFormat, error.category)
    }

    @Test
    fun `rejects extension and MIME mismatch before opening stream`() = runTest {
        val uri = Uri.parse("content://test/report.pdf")
        val cursor = fakeCursor("report.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnsupportedFormat, error.category)
        verify(resolver, org.mockito.Mockito.never()).openInputStream(uri)
    }

    @Test
    fun `rejects PPT file as unsupported format`() = runTest {
        val uri = Uri.parse("content://test/presentation.ppt")
        val cursor = fakeCursor("presentation.ppt")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/vnd.ms-powerpoint")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnsupportedFormat, error.category)
    }

    @Test
    fun `rejects unknown MIME type as unsupported format`() = runTest {
        val uri = Uri.parse("content://test/data.xyz")
        val cursor = fakeCursor("data.xyz")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/octet-stream")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnsupportedFormat, error.category)
    }

    @Test
    fun `rejects file with no extension and no MIME type`() = runTest {
        val uri = Uri.parse("content://test/unknown")
        val cursor = fakeCursor("unknown")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn(null)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnsupportedFormat, error.category)
    }

    @Test
    fun `getFileName returns display name from ContentResolver`() {
        val uri = Uri.parse("content://test/document.pdf")
        val cursor = fakeCursor("my-document.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val name = reader.getFileName(uri)

        assertEquals("my-document.pdf", name)
    }

    @Test
    fun `getFileName falls back to lastPathSegment when display name is unavailable`() {
        val uri = Uri.parse("content://test/fallback.docx")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(null)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val name = reader.getFileName(uri)

        assertEquals("fallback.docx", name)
    }

    @Test
    fun `getFileName falls back to unknown_file when both name and path segment are empty`() {
        val uri = Uri.parse("content://test/")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(null)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val name = reader.getFileName(uri)

        assertEquals("unknown_file", name)
    }

    @Test
    fun `rejects input exceeding 4 MiB limit`() = runTest {
        val uri = Uri.parse("content://test/large.pdf")
        val cursor = fakeCursor("large.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        val largeBytes = ByteArray(JsDocumentReader.MAX_INPUT_BYTES + 1)
        val stream = CloseTrackingInputStream(largeBytes)
        `when`(resolver.openInputStream(uri)).thenReturn(stream)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.FileTooLarge, error.category)
        assertTrue(stream.closed)
        assertEquals(JsDocumentReader.MAX_INPUT_BYTES + 1, stream.bytesRead)
    }

    @Test
    fun `accepts input exactly at 4 MiB limit`() = runTest {
        val uri = Uri.parse("content://test/exact.pdf")
        val cursor = fakeCursor("exact.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        val exactBytes = ByteArray(JsDocumentReader.MAX_INPUT_BYTES)
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(exactBytes))
        fakeRuntime.nextResult = DocumentParseResult("exact limit content")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("exact limit content", result.text)
    }

    @Test
    fun `stream is closed after successful parse`() = runTest {
        val uri = Uri.parse("content://test/report.pdf")
        val cursor = fakeCursor("report.pdf")
        val stream = CloseTrackingInputStream(PDF_MAGIC_BYTES)
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(stream)
        fakeRuntime.nextResult = DocumentParseResult("text")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        reader.parse(uri)

        assertTrue(stream.closed)
    }

    @Test
    fun `runtime DocumentParseException is propagated as-is`() = runTest {
        val uri = Uri.parse("content://test/doc.pdf")
        val cursor = fakeCursor("doc.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextError = DocumentParseException(
            DocumentParseErrorCategory.ParseFailed,
            "plugin error"
        )

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.ParseFailed, error.category)
        assertTrue(error.message!!.contains("plugin error"))
    }

    @Test
    fun `runtime exception maps to DocumentParseException`() = runTest {
        val uri = Uri.parse("content://test/doc.pdf")
        val cursor = fakeCursor("doc.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextError = IllegalStateException("unexpected")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.ParseFailed, error.category)
    }

    @Test
    fun `missing ContentResolver stream maps to UnreadableInput`() = runTest {
        val uri = Uri.parse("content://test/missing.pdf")
        val cursor = fakeCursor("missing.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(null)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnreadableInput, error.category)
    }

    @Test
    fun `resolver getType is called for MIME resolution`() = runTest {
        val uri = Uri.parse("content://test/report.pdf")
        val cursor = fakeCursor("report.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("text")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        reader.parse(uri)

        verify(resolver).getType(uri)
    }

    @Test
    fun `close is called on runtime after parse`() = runTest {
        val uri = Uri.parse("content://test/report.pdf")
        val cursor = fakeCursor("report.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("text")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        reader.parse(uri)

        assertTrue(fakeRuntime.closed)
    }

    @Test
    fun `close is called on runtime when parse throws`() = runTest {
        val uri = Uri.parse("content://test/doc.pdf")
        val cursor = fakeCursor("doc.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextError = DocumentParseException(
            DocumentParseErrorCategory.ParseFailed,
            "fail"
        )

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        try {
            reader.parse(uri)
        } catch (_: DocumentParseException) {
            // expected
        }

        assertTrue(fakeRuntime.closed)
    }

    @Test
    fun `DOCX dispatches without query call when MIME is explicit`() = runTest {
        val uri = Uri.parse("content://test/file")
        `when`(resolver.getType(uri)).thenReturn(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(DOCX_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("docx content")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("docx content", result.text)
        assertEquals("document_plugins/docx-reader.js", fakeRuntime.lastPluginAsset)
    }

    @Test
    fun `normalizes uppercase extension before dispatch`() = runTest {
        val uri = Uri.parse("content://test/report.pdf")
        val cursor = fakeCursor("REPORT.PDF")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("pdf text")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        reader.parse(uri)

        assertEquals("document_plugins/pdf-reader.js", fakeRuntime.lastPluginAsset)
        assertEquals("REPORT.PDF", fakeRuntime.lastInput?.name)
    }

    @Test
    fun `blank plugin text without meaningful warning maps to NoExtractableText`() = runTest {
        val uri = Uri.parse("content://test/empty.pdf")
        val cursor = fakeCursor("empty.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("  \n", listOf(" ", ""))

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.NoExtractableText, error.category)
        assertTrue(fakeRuntime.closed)
    }

    @Test
    fun `blank plugin text with meaningful warning is returned`() = runTest {
        val uri = Uri.parse("content://test/image-only.pdf")
        val cursor = fakeCursor("image-only.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("", listOf("Page 1 appears to be image-only"))

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("", result.text)
        assertEquals(1, result.warnings.size)
        assertTrue(fakeRuntime.closed)
    }

    @Test
    fun `plugin output over limit maps to PluginMemoryLimit`() = runTest {
        val uri = Uri.parse("content://test/large-output.pdf")
        val cursor = fakeCursor("large-output.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("x".repeat(JsDocumentReader.MAX_OUTPUT_BYTES + 1))

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.PluginMemoryLimit, error.category)
        assertTrue(fakeRuntime.closed)
    }

    @Test
    fun `runtime factory failure maps to RuntimeUnavailable`() = runTest {
        val uri = Uri.parse("content://test/runtime.pdf")
        val cursor = fakeCursor("runtime.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))

        val reader = JsDocumentReader(
            context,
            { throw IllegalStateException("runtime init failed") },
            Dispatchers.Unconfined
        )
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.RuntimeUnavailable, error.category)
    }

    @Test
    fun `runtime close failure does not replace successful parse`() = runTest {
        val uri = Uri.parse("content://test/close.pdf")
        val cursor = fakeCursor("close.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(PDF_MAGIC_BYTES))
        fakeRuntime.nextResult = DocumentParseResult("text")
        fakeRuntime.closeError = IllegalStateException("close failed")

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val result = reader.parse(uri)

        assertEquals("text", result.text)
        assertTrue(fakeRuntime.closed)
    }

    @Test
    fun `input read failure maps to UnreadableInput and closes stream`() = runTest {
        val uri = Uri.parse("content://test/read-failure.pdf")
        val cursor = fakeCursor("read-failure.pdf")
        val stream = FailingInputStream()
        `when`(resolver.query(uri, null, null, null, null)).thenReturn(cursor)
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        `when`(resolver.openInputStream(uri)).thenReturn(stream)

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)
        val error = try {
            reader.parse(uri)
            throw AssertionError("Expected DocumentParseException")
        } catch (e: DocumentParseException) {
            e
        }

        assertEquals(DocumentParseErrorCategory.UnreadableInput, error.category)
        assertTrue(stream.closed)
    }

    @Test
    fun `metadata query failure falls back to URI path segment`() {
        val uri = Uri.parse("content://test/fallback.pdf")
        `when`(resolver.query(uri, null, null, null, null)).thenThrow(IllegalStateException("provider error"))

        val reader = JsDocumentReader(context, { fakeRuntime }, Dispatchers.Unconfined)

        assertEquals("fallback.pdf", reader.getFileName(uri))
    }

    // --- Helper utilities ---

    companion object {
        val PDF_MAGIC_BYTES = byteArrayOf(37, 80, 68, 70) // %PDF
        val DOCX_MAGIC_BYTES = byteArrayOf(80, 75, 3, 4) // PK\x03\x04 (ZIP)
    }

    private fun fakeCursor(displayName: String): Cursor {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getString(0)).thenReturn(displayName)
        return cursor
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var closed = false
        var bytesRead = 0

        override fun read(): Int {
            check(!closed) { "stream is closed" }
            val value = delegate.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "stream is closed" }
            val count = delegate.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class FailingInputStream : InputStream() {
        var closed = false

        override fun read(): Int {
            throw IOException("read failed")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throw IOException("read failed")
        }

        override fun close() {
            closed = true
        }
    }

    /** Controlled test seam; production code still constructs the AssetManager-backed runtime. */
    private class FakeBundledRuntime : JsDocumentParseRuntime {
        var lastPluginAsset: String? = null
        var lastInput: JsDocumentInput? = null
        var nextResult: DocumentParseResult? = null
        var nextError: Throwable? = null
        var closeError: Throwable? = null
        var closed = false

        override fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult {
            lastPluginAsset = pluginAsset
            lastInput = input
            if (nextError != null) {
                throw nextError!!
            }
            return nextResult ?: throw DocumentParseException(
                DocumentParseErrorCategory.ParseFailed,
                "No test result configured"
            )
        }

        override fun close() {
            closed = true
            closeError?.let { throw it }
        }
    }
}