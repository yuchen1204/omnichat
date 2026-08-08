package com.omnichat.ui.components

data class ParsedMessageContent(
    val thinking: String?,
    val mainBody: String,
    val isThinkingFinished: Boolean
)

private val openingThinkingTag = Regex(
    "<\\s*(think|thinking|analysis|reasoning)(?:\\s+[^<>]*?)?\\s*>",
    RegexOption.IGNORE_CASE
)
private val closingThinkingTag = Regex(
    "<\\s*/\\s*(think|thinking|analysis|reasoning)\\s*>",
    RegexOption.IGNORE_CASE
)
private val thinkingTagNames = listOf("think", "thinking", "analysis", "reasoning")

fun parseMessageContent(content: String): ParsedMessageContent {
    val thinkingParts = mutableListOf<String>()
    val bodyParts = mutableListOf<String>()
    var cursor = 0
    var thinkingStart: MatchResult? = null
    var unfinishedThinking = false

    while (cursor < content.length) {
        if (thinkingStart == null) {
            val opening = openingThinkingTag.find(content, cursor)
            val partialStart = partialThinkingTagStart(content, cursor)
            if (opening == null || (partialStart != null && partialStart < opening.range.first)) {
                bodyParts += content.substring(cursor, partialStart ?: content.length)
                unfinishedThinking = partialStart != null
                cursor = content.length
                break
            }
            bodyParts += content.substring(cursor, opening.range.first)
            thinkingStart = opening
            cursor = opening.range.last + 1
        } else {
            val closing = closingThinkingTag.find(content, cursor)
            val partialEnd = partialClosingThinkingTag(content, cursor)
            if (closing == null || (partialEnd != null && partialEnd < closing.range.first)) {
                thinkingParts += content.substring(thinkingStart.range.last + 1, partialEnd ?: content.length)
                unfinishedThinking = true
                cursor = content.length
                break
            }
            thinkingParts += content.substring(thinkingStart.range.last + 1, closing.range.first)
            cursor = closing.range.last + 1
            thinkingStart = null
        }
    }

    if (thinkingStart != null && cursor == content.length && !unfinishedThinking) {
        thinkingParts += content.substring(thinkingStart.range.last + 1)
        unfinishedThinking = true
    }

    return ParsedMessageContent(
        thinking = thinkingParts.joinToString("\n").trim().ifEmpty { null },
        mainBody = bodyParts.joinToString("").trim(),
        isThinkingFinished = !unfinishedThinking
    )
}

private fun partialThinkingTagStart(content: String, fromIndex: Int): Int? =
    partialTagStart(content, fromIndex, closing = false)

private fun partialClosingThinkingTag(content: String, fromIndex: Int): Int? =
    partialTagStart(content, fromIndex, closing = true)

private fun partialTagStart(content: String, fromIndex: Int, closing: Boolean): Int? {
    val start = content.lastIndexOf('<')
    if (start < fromIndex || start < 0 || content.indexOf('>', start) >= 0) return null
    val suffix = content.substring(start)
    val prefix = if (closing) "</" else "<"
    if (!suffix.startsWith(prefix, ignoreCase = true)) return null
    val afterPrefix = suffix.substring(prefix.length).trimStart()
    return if (thinkingTagNames.any { it.startsWith(afterPrefix, ignoreCase = true) || afterPrefix.startsWith(it, ignoreCase = true) }) start else null
}
