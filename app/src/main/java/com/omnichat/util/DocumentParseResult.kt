package com.omnichat.util

/** The text and non-fatal diagnostics returned by a document plugin. */
data class DocumentParseResult(
    val text: String,
    val warnings: List<String> = emptyList()
)

enum class DocumentParseErrorCategory {
    UnsupportedFormat,
    FileTooLarge,
    UnreadableInput,
    RuntimeUnavailable,
    PluginLoadFailed,
    PluginTimeout,
    PluginMemoryLimit,
    MalformedPluginResult,
    ParseFailed,
    NoExtractableText
}

class DocumentParseException(
    val category: DocumentParseErrorCategory,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Host input visible to a plugin. The byte array is copied before crossing the JS boundary. */
data class JsDocumentInput(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)
