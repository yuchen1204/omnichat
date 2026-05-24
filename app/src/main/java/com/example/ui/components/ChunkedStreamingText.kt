package com.example.ui.components

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
 */
@Composable
fun ChunkedStreamingText(
    text: String,
    textColor: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
    fontFamily: FontFamily = FontFamily.Default,
    modifier: Modifier = Modifier
) {
    val parser = remember { MarkdownChunkParser() }
    val highlightBg = MaterialTheme.colorScheme.surfaceVariant
    val highlightText = MaterialTheme.colorScheme.onSurfaceVariant
    
    // Parse the text into chunks
    val result = remember(text) { parser.parse(text) }

    Column(modifier = modifier) {
        // 1. Render locked chunks
        for (chunk in result.lockedChunks) {
            key(chunk) {
                MarkdownText(
                    markdown = chunk,
                    style = TextStyle(
                        color = textColor,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontFamily = fontFamily
                    ),
                    syntaxHighlightColor = highlightBg,
                    syntaxHighlightTextColor = highlightText,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Render the active chunk
        if (result.activeChunk.isNotEmpty()) {
            val isActiveChunkComplex = remember(result.activeChunk) {
                result.activeChunk.contains("```") || result.activeChunk.contains("|")
            }

            if (isActiveChunkComplex) {
                Text(
                    text = result.activeChunk,
                    color = textColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MarkdownText(
                    markdown = result.activeChunk,
                    style = TextStyle(
                        color = textColor,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontFamily = fontFamily
                    ),
                    syntaxHighlightColor = highlightBg,
                    syntaxHighlightTextColor = highlightText,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
