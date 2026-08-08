package com.omnichat.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin facade that reads a document from a content URI or File and dispatches it to the
 * JavaScript document runtime.
 *
 * Supported formats: PDF (.pdf, application/pdf) and DOCX (.docx,
 * application/vnd.openxmlformats-officedocument.wordprocessingml.document).
 *
 * Input is capped at [MAX_INPUT_BYTES] (4 MiB) to match the production runtime default.
 * The underlying runtime additionally validates its own configurable limits.
 * Document text or bytes are never logged.
 */
class JsDocumentReader(
    private val context: Context,
    private val runtimeFactory: () -> JsDocumentParseRuntime = {
        BundledQuickJsDocumentRuntime(context.assets)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Read the document at [uri] and return its parsed text and warnings.
     *
     * The URI content is read on [ioDispatcher]. The file format is resolved from
     * the display-name extension and the ContentResolver MIME type. Only PDF and DOCX
     * are accepted; all other formats produce [DocumentParseErrorCategory.UnsupportedFormat].
     *
     * Supports both [content://] and [file://] URIs. For [file://] URIs the MIME type
     * is inferred from the file extension.
     */
    suspend fun parse(uri: Uri): DocumentParseResult = withContext(ioDispatcher) {
        val fileName = getFileName(uri)
        val mimeType = try {
            context.contentResolver.getType(uri).orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // ContentResolver may not resolve file:// URIs; fall back to extension.
            ""
        }
        val pluginAsset = resolvePluginAsset(fileName, mimeType)
        val bytes = readBytes(uri)
        val input = JsDocumentInput(fileName, mimeType, bytes)

        val runtime = try {
            runtimeFactory()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.RuntimeUnavailable,
                "JavaScript document runtime could not be created",
                error
            )
        }
        try {
            runtime.parse(pluginAsset, input).also(::validateResult)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.ParseFailed,
                "Document parse failed",
                error
            )
        } finally {
            try {
                runtime.close()
            } catch (closeError: Throwable) {
                // Do not replace the parse result/error with a cleanup failure.
            }
        }
    }

    /** Edit a DOCX file and return the rewritten OOXML bytes. */
    suspend fun edit(file: File, operation: JsDocumentEditOperation, oldText: String? = null, content: String): DocumentEditResult = withContext(ioDispatcher) {
        val fileName = file.name
        val mimeType = mimeTypeForExtension(fileName)
        if (!fileName.lowercase().endsWith(".docx")) throw DocumentParseException(DocumentParseErrorCategory.UnsupportedFormat, "Only DOCX files can be edited")
        val bytes = readFileBytes(file)
        val runtime = try {
            runtimeFactory()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.RuntimeUnavailable,
                "JavaScript document runtime could not be created",
                error
            )
        }
        try {
            runtime.editDocument(DOCX_EDITOR_PLUGIN, JsDocumentEditInput(fileName, mimeType, bytes, operation, oldText, content))
        } finally {
            try { runtime.close() } catch (_: Throwable) { }
        }
    }

    /**
     * Read the document at [file] and return its parsed text and warnings.
     *
     * This is a convenience overload for [File]-based access, used by tool callers
     * (e.g., [DocumentReadTool]) that have already resolved a file path. It bypasses
     * the ContentResolver and reads the file directly.
     */
    suspend fun parse(file: File): DocumentParseResult = withContext(ioDispatcher) {
        val fileName = file.name
        val mimeType = mimeTypeForExtension(fileName)
        val pluginAsset = resolvePluginAsset(fileName, mimeType)
        val bytes = readFileBytes(file)

        val input = JsDocumentInput(fileName, mimeType, bytes)
        val runtime = try {
            runtimeFactory()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.RuntimeUnavailable,
                "JavaScript document runtime could not be created",
                error
            )
        }
        try {
            runtime.parse(pluginAsset, input).also(::validateResult)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.ParseFailed,
                "Document parse failed",
                error
            )
        } finally {
            try {
                runtime.close()
            } catch (closeError: Throwable) {
                // Do not replace the parse result/error with a cleanup failure.
            }
        }
    }

    /**
     * Return the display name of the document at [uri]. Falls back to
     * [Uri.getLastPathSegment] when the ContentResolver cursor is unavailable,
     * and to "unknown_file" when both are empty.
     */
    fun getFileName(uri: Uri): String {
        var name = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex).orEmpty()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A provider that cannot answer metadata should not prevent path fallback.
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "unknown_file"
        }
        return name
    }

    // ---- Private helpers ----

    private enum class Format { PDF, DOCX, UNSUPPORTED }

    private fun resolvePluginAsset(fileName: String, mimeType: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val extensionKind = when (extension) {
            "pdf" -> Format.PDF
            "docx" -> Format.DOCX
            "" -> null
            else -> Format.UNSUPPORTED
        }
        val mimeKind = when (mimeType.substringBefore(';').trim().lowercase()) {
            PDF_MIME -> Format.PDF
            DOCX_MIME -> Format.DOCX
            "" -> null
            else -> Format.UNSUPPORTED
        }

        if (extensionKind == Format.UNSUPPORTED || mimeKind == Format.UNSUPPORTED ||
            (extensionKind != null && mimeKind != null && extensionKind != mimeKind)
        ) {
            throw unsupportedFormat(fileName)
        }

        return when (extensionKind ?: mimeKind) {
            Format.PDF -> PDF_PLUGIN
            Format.DOCX -> DOCX_PLUGIN
            else -> throw unsupportedFormat(fileName)
        }
    }

    private fun unsupportedFormat(fileName: String) = DocumentParseException(
        DocumentParseErrorCategory.UnsupportedFormat,
        "Unsupported document format"
    )

    private fun validateResult(result: DocumentParseResult) {
        val outputBytes = result.text.toByteArray(Charsets.UTF_8).size.toLong() +
            result.warnings.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        if (outputBytes > MAX_OUTPUT_BYTES.toLong()) {
            throw DocumentParseException(
                DocumentParseErrorCategory.PluginMemoryLimit,
                "Document plugin output exceeds the configured byte limit"
            )
        }
        if (result.text.isBlank() && result.warnings.none { it.isNotBlank() }) {
            throw DocumentParseException(
                DocumentParseErrorCategory.NoExtractableText,
                "Document plugin returned no extractable text"
            )
        }
    }

    /**
     * Read at most [MAX_INPUT_BYTES] + 1 bytes from the content URI. The extra probe byte
     * distinguishes an input exactly at the limit from one that exceeds it.
     */
    private fun readBytes(uri: Uri): ByteArray {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.UnreadableInput,
                "Document input could not be opened",
                error
            )
        } ?: throw DocumentParseException(
            DocumentParseErrorCategory.UnreadableInput,
            "Document input could not be opened"
        )

        return try {
            input.use { stream ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream(4096)
                var total = 0
                var emptyReads = 0
                val probeLimit = MAX_INPUT_BYTES + 1
                while (total < probeLimit) {
                    val requested = minOf(buffer.size, probeLimit - total)
                    val count = stream.read(buffer, 0, requested)
                    if (count < 0) break
                    if (count == 0) {
                        if (++emptyReads >= MAX_CONSECUTIVE_EMPTY_READS) {
                            throw DocumentParseException(
                                DocumentParseErrorCategory.UnreadableInput,
                                "Document input made no progress while being read"
                            )
                        }
                        continue
                    }
                    emptyReads = 0
                    output.write(buffer, 0, count)
                    total += count
                }
                if (total > MAX_INPUT_BYTES) {
                    throw DocumentParseException(
                        DocumentParseErrorCategory.FileTooLarge,
                        "Document exceeds the configured input byte limit"
                    )
                }
                output.toByteArray()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.UnreadableInput,
                "Document input could not be read",
                error
            )
        }
    }

    /**
     * Read at most [MAX_INPUT_BYTES] + 1 bytes from a [File]. The extra probe byte
     * distinguishes an input exactly at the limit from one that exceeds it.
     */
    private fun readFileBytes(file: File): ByteArray {
        if (!file.exists()) {
            throw DocumentParseException(
                DocumentParseErrorCategory.UnreadableInput,
                "Document file does not exist"
            )
        }
        if (file.length() > MAX_INPUT_BYTES) {
            throw DocumentParseException(
                DocumentParseErrorCategory.FileTooLarge,
                "Document exceeds the configured input byte limit"
            )
        }
        return try {
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream(4096)
                var total = 0
                var emptyReads = 0
                val probeLimit = MAX_INPUT_BYTES + 1
                while (total < probeLimit) {
                    val requested = minOf(buffer.size, probeLimit - total)
                    val count = stream.read(buffer, 0, requested)
                    if (count < 0) break
                    if (count == 0) {
                        if (++emptyReads >= MAX_CONSECUTIVE_EMPTY_READS) {
                            throw DocumentParseException(
                                DocumentParseErrorCategory.UnreadableInput,
                                "Document input made no progress while being read"
                            )
                        }
                        continue
                    }
                    emptyReads = 0
                    output.write(buffer, 0, count)
                    total += count
                }
                if (total > MAX_INPUT_BYTES) {
                    throw DocumentParseException(
                        DocumentParseErrorCategory.FileTooLarge,
                        "Document exceeds the configured input byte limit"
                    )
                }
                output.toByteArray()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Throwable) {
            throw DocumentParseException(
                DocumentParseErrorCategory.UnreadableInput,
                "Document input could not be read",
                error
            )
        }
    }

    private fun mimeTypeForExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> PDF_MIME
            "docx" -> DOCX_MIME
            else -> ""
        }
    }

    companion object {
        /** Maximum input bytes to read from a content URI (4 MiB, matching the runtime default). */
        const val MAX_INPUT_BYTES = 4 * 1024 * 1024

        /** Maximum output bytes expected from the runtime (4 MiB, matching the runtime default). */
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024

        private const val MAX_CONSECUTIVE_EMPTY_READS = 3
        private const val PDF_MIME = "application/pdf"
        private const val PDF_PLUGIN = "document_plugins/pdf-reader.js"
        private const val DOCX_PLUGIN = "document_plugins/docx-reader.js"
        private const val DOCX_EDITOR_PLUGIN = "document_plugins/docx-editor.js"
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}