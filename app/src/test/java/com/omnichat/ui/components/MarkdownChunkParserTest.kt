package com.omnichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownChunkParserTest {

    @Test
    fun `incremental parse keeps stable prefix and caches its length`() {
        val parser = MarkdownChunkParser()
        val first = parser.parse("# Title\n\nFirst paragraph\n\n")
        val fullText = "# Title\n\nFirst paragraph\n\nSecond paragraph"

        val incremental = parser.parseIncremental(fullText, first)
        val complete = parser.parse(fullText)

        assertEquals(complete, incremental)
        assertEquals(
            incremental.lockedChunks.sumOf { it.length },
            incremental.lockedTextLength
        )
    }

    @Test
    fun `parser only returns an active chunk for an unfinished paragraph`() {
        val result = MarkdownChunkParser().parse("A paragraph still streaming")

        assertEquals(emptyList<String>(), result.lockedChunks)
        assertEquals("A paragraph still streaming", result.activeChunk)
        assertEquals(0, result.lockedTextLength)
    }
}
