package com.example.ui.components

/**
 * Result of parsing Markdown into locked (stable) and active (streaming) chunks.
 */
data class ChunkParseResult(
    /**
     * Chunks that are considered "finished" and will not change further.
     */
    val lockedChunks: List<String>,
    /**
     * The remaining part of the text that is still being streamed.
     */
    val activeChunk: String
)

/**
 * Parses a streaming Markdown string into stable chunks.
 * This helps avoid full re-rendering of long Markdown documents during streaming.
 */
class MarkdownChunkParser {

    companion object {
        private val NUMBERED_LIST_REGEX = Regex("^\\d+\\.\\s.*")
    }

    /**
     * Splits [fullText] into a list of stable chunks and one active chunk.
     */
    fun parse(fullText: String): ChunkParseResult {
        if (fullText.isEmpty()) {
            return ChunkParseResult(emptyList(), "")
        }

        val lockedChunks = mutableListOf<String>()
        var remaining = fullText

        while (true) {
            val boundary = findChunkBoundary(remaining)
            if (boundary == -1 || boundary == 0) break

            lockedChunks.add(remaining.substring(0, boundary))
            remaining = remaining.substring(boundary)
        }

        return ChunkParseResult(lockedChunks, remaining)
    }

    /**
     * Finds the boundary of the first "lockable" block in [text].
     * Returns the index after the boundary, or -1 if no boundary found.
     */
    private fun findChunkBoundary(text: String): Int {
        if (text.isEmpty()) return -1

        // Look for common block boundaries
        // 1. Code blocks (must be closed)
        if (text.startsWith("```")) {
            return findCodeBlockEnd(text)
        }

        // 2. Tables (must be closed or followed by a blank line)
        if (text.startsWith("|")) {
            return findTableEnd(text)
        }

        // 3. Headings (lockable after first newline)
        if (text.startsWith("#")) {
            val newline = text.indexOf('\n')
            return if (newline != -1) newline + 1 else -1
        }

        // 4. Lists (lockable after non-list line or blank line)
        if (text.startsWith("- ") || text.startsWith("* ") || text.matches(NUMBERED_LIST_REGEX)) {
            return findListEnd(text)
        }

        // 5. Double newline (paragraph end)
        val doubleNewline = text.indexOf("\n\n")
        if (doubleNewline != -1) {
            return doubleNewline + 2
        }

        return -1
    }

    private fun findCodeBlockEnd(text: String): Int {
        // Find the opening ``` newline
        val firstNewline = text.indexOf('\n')
        if (firstNewline == -1) return -1

        // Search for closing ``` starting from after the first newline
        val closingIndex = text.indexOf("```", firstNewline + 1)
        if (closingIndex == -1) return -1

        // Ensure it's a full line or end of text
        val afterClosing = closingIndex + 3
        return if (afterClosing >= text.length || text[afterClosing] == '\n') {
            if (afterClosing < text.length) afterClosing + 1 else afterClosing
        } else {
            // Not a real closing tag, maybe ````
            -1
        }
    }

    private fun findTableEnd(text: String): Int {
        val lines = text.split('\n')
        if (lines.size < 3) return -1

        // Very basic table check: needs header line and separator line
        if (!lines[0].trim().startsWith("|") || !lines[1].trim().startsWith("|")) return -1
        if (!lines[1].contains("-")) return -1

        var currentIndex = 0
        var foundEnd = false
        
        for (line in lines) {
            if (line.trim().startsWith("|")) {
                currentIndex += line.length + 1
            } else {
                foundEnd = true
                break
            }
        }

        // If we found a non-table line, we can lock the table
        // But we need to be careful not to exceed the original text length
        val resultIndex = if (foundEnd) currentIndex else -1
        return if (resultIndex > text.length) text.length else resultIndex
    }

    private fun findListEnd(text: String): Int {
        val lines = text.split('\n')
        var currentIndex = 0
        var foundEnd = false

        for (line in lines) {
            val trimmed = line.trimStart()
            val isListItem = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches(NUMBERED_LIST_REGEX)
            val isContinuation = line.startsWith("  ") || line.startsWith("\t")

            if (isListItem || isContinuation) {
                currentIndex += line.length + 1
            } else {
                foundEnd = true
                break
            }
        }

        val resultIndex = if (foundEnd) currentIndex else -1
        return if (resultIndex > text.length) text.length else resultIndex
    }
}
