package com.omnichat.data

/**
 * Project-owned resource and tool-context limits.
 *
 * Byte limits use binary MiB/KiB units. Text limits use Kotlin character counts,
 * which keeps tool responses bounded even when the source document is much larger.
 */
object ProjectContentLimits {
    const val MAX_KNOWLEDGE_BYTES_PER_PROJECT: Long = 10L * 1024 * 1024
    const val MAX_PROJECT_MEMORY_BYTES: Long = 128L * 1024
    const val MAX_TOOL_TEXT_CHARS: Int = 100_000
    const val MAX_IMAGE_DATA_URL_BYTES: Long = 5L * 1024 * 1024

    private const val TEXT_TRUNCATION_NOTICE =
        "\n\n[Output truncated because it exceeds the 100,000-character limit.]"
    const val MEMORY_TRUNCATION_NOTICE =
        "\n\n[Project memory is truncated because it exceeds the 128 KiB read limit.]"

    fun truncateToolText(text: String): String {
        if (text.length <= MAX_TOOL_TEXT_CHARS) return text
        return text.take(MAX_TOOL_TEXT_CHARS - TEXT_TRUNCATION_NOTICE.length) + TEXT_TRUNCATION_NOTICE
    }

    fun knowledgeLimitError(remainingBytes: Long): String =
        "Project knowledge asset limit is 10 MiB; only ${remainingBytes.coerceAtLeast(0)} bytes remain."

    fun memoryLimitError(): String =
        "Project memory exceeds the 128 KiB limit. Reduce its size before saving."

    fun imageDataUrlLimitError(): String =
        "Image is too large to return as a data URL (maximum 5 MiB)."
}
