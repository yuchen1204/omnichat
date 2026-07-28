package com.omnichat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * A component that renders Markdown in chunks to optimize streaming performance.
 *
 * Stable chunks keep their Markdown formatting. The currently growing chunk uses a
 * lightweight Text fallback once it becomes large, preventing repeated full
 * Markdown parsing for a long paragraph while tokens are arriving.
 */
private const val LIVE_MARKDOWN_MAX_CHARS = 800

private class StreamingMarkdownCache {
    private val parser = MarkdownChunkParser()
    private var previousText = ""
    private var previousResult = ChunkParseResult(emptyList(), "")

    fun parse(text: String): ChunkParseResult {
        val result = if (text.startsWith(previousText)) {
            parser.parseIncremental(text, previousResult)
        } else {
            parser.parse(text)
        }
        previousText = text
        previousResult = result
        return result
    }
}

@Composable
fun ChunkedStreamingText(
    text: String,
    textColor: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
    fontFamily: FontFamily = FontFamily.Default,
    modifier: Modifier = Modifier
) {
    val cache = remember { StreamingMarkdownCache() }
    val highlightBg = MaterialTheme.colorScheme.surfaceVariant
    val highlightText = MaterialTheme.colorScheme.onSurfaceVariant
    val markdownStyle = remember(textColor, fontSize, lineHeight, fontFamily) {
        TextStyle(
            color = textColor,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily
        )
    }

    // Incremental parse: reuse previously locked chunks and only re-parse the tail.
    // The cache is deliberately not Compose state: parsing is derived from [text],
    // and writing state during composition would schedule an extra recomposition.
    val result = remember(text) { cache.parse(text) }

    Column(modifier = modifier) {
        // 1. Render locked chunks. Their index is stable because chunks are append-only.
        result.lockedChunks.forEachIndexed { index, chunk ->
            key(index) {
                MarkdownText(
                    markdown = chunk,
                    style = markdownStyle,
                    syntaxHighlightColor = highlightBg,
                    syntaxHighlightTextColor = highlightText,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Render the active chunk. Large / structurally incomplete Markdown is
        // temporarily plain text; the final persisted message still renders fully
        // formatted Markdown after streaming ends.
        if (result.activeChunk.isNotEmpty()) {
            val usePlainText = remember(result.activeChunk) {
                result.activeChunk.length > LIVE_MARKDOWN_MAX_CHARS ||
                    result.activeChunk.contains("```") ||
                    result.activeChunk.contains("|")
            }

            if (usePlainText) {
                Text(
                    text = result.activeChunk,
                    style = markdownStyle,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MarkdownText(
                    markdown = result.activeChunk,
                    style = markdownStyle,
                    syntaxHighlightColor = highlightBg,
                    syntaxHighlightTextColor = highlightText,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
