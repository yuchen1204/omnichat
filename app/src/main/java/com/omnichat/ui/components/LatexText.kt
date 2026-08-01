package com.omnichat.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * Represents a LaTeX math expression found in text.
 */
data class LatexBlock(
    val expression: String,
    val isDisplay: Boolean,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Parser for finding LaTeX math expressions in text.
 * Supports both $$...$$ (display math) and $...$ (inline math).
 */
object LatexParser {

    fun findBlocks(text: String): List<LatexBlock> {
        val blocks = mutableListOf<LatexBlock>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] == '$') {
                i += 2
                continue
            }
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$') {
                val end = text.indexOf("$$", i + 2)
                if (end != -1) {
                    blocks.add(LatexBlock(text.substring(i + 2, end).trim(), true, i, end + 2))
                    i = end + 2; continue
                }
                i += 2; continue
            }
            if (text[i] == '$') {
                val end = text.indexOf('$', i + 1)
                if (end != -1 && end > i + 1) {
                    if (end + 1 < text.length && text[end + 1] == '$') {
                        i = end + 1; continue
                    }
                    blocks.add(LatexBlock(text.substring(i + 1, end), false, i, end + 1))
                    i = end + 1; continue
                }
            }
            i++
        }
        return blocks
    }

    /** Check if text has complete LaTeX math (both open and close delimiters). */
    fun hasLatex(text: String): Boolean {
        return findBlocks(text).isNotEmpty()
    }

    /**
     * Check if text has what looks like a LaTeX expression that is still being
     * streamed (unclosed delimiters). Returns true only if the text is worth
     * checking for streaming incompleteness.
     */
    fun hasIncompleteLatex(text: String): Boolean {
        if (text.contains("$\\") || text.contains("\\\\")) return false
        // Track whether we have an unclosed LaTeX delimiter
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] == '$') {
                i += 2; continue
            }
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$') {
                // $$...$$ display math: check if closed
                val end = text.indexOf("$$", i + 2)
                if (end == -1) return true // unclosed display math
                i = end + 2; continue
            }
            if (text[i] == '$') {
                // $...$ inline math: check if closed
                val end = text.indexOf('$', i + 1)
                if (end == -1) return true // unclosed inline math
                i = end + 1; continue
            }
            i++
        }
        return false
    }

    /** Escape text for embedding in a JavaScript string. */
    fun escapeForJs(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("'", "\\'")

    /**
     * Splits text into segments around LaTeX blocks.
     * Each segment is either a plain text string or a LatexBlock.
     */
    fun splitByLatex(text: String): List<Any> {
        val blocks = findBlocks(text)
        if (blocks.isEmpty()) return listOf(text)

        val segments = mutableListOf<Any>()
        var lastEnd = 0

        for (block in blocks) {
            if (block.startIndex > lastEnd) {
                segments.add(text.substring(lastEnd, block.startIndex))
            }
            segments.add(block)
            lastEnd = block.endIndex
        }

        if (lastEnd < text.length) {
            segments.add(text.substring(lastEnd))
        }

        return segments
    }
}

/**
 * Converts a subset of Markdown to HTML. Used inside the WebView-based
 * renderer so we can combine Markdown with KaTeX-rendered LaTeX.
 *
 * Handles: headings, bold, italic, code blocks, inline code, ordered/unordered
 * lists, links, images, horizontal rules, blockquotes, paragraphs.
 */
object SimpleMarkdownToHtml {

    fun convert(markdown: String): String {
        var html = markdown

        // Escape HTML entities first
        html = html
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Code blocks (```) - must be done before other inline processing
        html = html.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            val langClass = if (lang.isNotEmpty()) " class=\"lang-$lang\"" else ""
            "<pre><code$langClass>$code</code></pre>"
        }

        // Horizontal rules
        html = html.replace(Regex("^\\s*---\\s*$", RegexOption.MULTILINE), "<hr>")

        // Headings
        html = html.replace(Regex("^##### (.+)$", RegexOption.MULTILINE)) { "<h5>${it.groupValues[1]}</h5>" }
        html = html.replace(Regex("^#### (.+)$", RegexOption.MULTILINE)) { "<h4>${it.groupValues[1]}</h4>" }
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE)) { "<h2>${it.groupValues[1]}</h2>" }
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE)) { "<h1>${it.groupValues[1]}</h1>" }

        // Blockquotes
        html = html.replace(Regex("^> (.+)$", RegexOption.MULTILINE)) { "<blockquote>${it.groupValues[1]}</blockquote>" }

        // Images
        html = html.replace(Regex("!\\[([^]]*)\\]\\(([^)]+)\\)")) { "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">" }

        // Links
        html = html.replace(Regex("\\[([^]]+)\\]\\(([^)]+)\\)")) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }

        // Bold + italic
        html = html.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*")) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("\\*(.+?)\\*")) { "<em>${it.groupValues[1]}</em>" }

        // Inline code
        html = html.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }

        // Ordered lists
        html = html.replace(Regex("(?:^\\d+\\.\\s.*\\n?)+", RegexOption.MULTILINE)) { match ->
            val items = match.value.split("\n").filter { it.trim().isNotEmpty() }
            val lis = items.joinToString("\n") { item ->
                val content = item.replace(Regex("^\\d+\\.\\s"), "")
                "  <li>$content</li>"
            }
            "<ol>\n$lis\n</ol>"
        }

        // Unordered lists
        html = html.replace(Regex("(?:^[-*]\\s.*\\n?)+", RegexOption.MULTILINE)) { match ->
            val items = match.value.split("\n").filter { it.trim().isNotEmpty() }
            val lis = items.joinToString("\n") { item ->
                val content = item.replace(Regex("^[-*]\\s"), "")
                "  <li>$content</li>"
            }
            "<ul>\n$lis\n</ul>"
        }

        // Paragraphs: wrap consecutive non-empty lines not already in a block element
        val lines = html.split("\n")
        val result = StringBuilder()
        var inParagraph = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (inParagraph) { result.append("</p>\n"); inParagraph = false }
                else result.append('\n')
                continue
            }
            val startsBlock = trimmed.startsWith("<h") || trimmed.startsWith("<pre") ||
                trimmed.startsWith("<ol") || trimmed.startsWith("<ul") ||
                trimmed.startsWith("<li") || trimmed.startsWith("<hr") ||
                trimmed.startsWith("<blockquote") || trimmed.startsWith("</ol") ||
                trimmed.startsWith("</ul") || trimmed.startsWith("</li") ||
                trimmed.startsWith("<img")
            if (startsBlock) {
                if (inParagraph) { result.append("</p>\n"); inParagraph = false }
                result.append(line).append('\n')
            } else {
                if (!inParagraph) { result.append("<p>"); inParagraph = true }
                else result.append("<br>\n")
                result.append(line)
            }
        }
        if (inParagraph) result.append("</p>\n")

        return result.toString()
    }
}

/**
 * Renders Markdown text with LaTeX math support in a WebView using KaTeX.
 *
 * The HTML template embeds:
 * - KaTeX CSS/JS for math rendering
 * - A simple markdown-to-HTML converter
 * - KaTeX auto-render to process math delimiters
 */
private const val MARKDOWN_LATEX_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0">
<link rel="stylesheet" href="katex.min.css">
<script src="katex.min.js"></script>
<script src="auto-render.min.js"></script>
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{background:transparent;min-height:100%}
body{font-size:__FONT_SIZE__px;color:__TEXT_COLOR__;font-family:__FONT_FAMILY__;line-height:__LINE_HEIGHT__;padding:0;overflow-x:hidden}
body ::selection{background:__HIGHLIGHT_BG__;color:__HIGHLIGHT_TEXT__}
p{margin:0.3em 0;line-height:__LINE_HEIGHT__}
h1{font-size:1.6em;margin:0.5em 0 0.3em}
h2{font-size:1.4em;margin:0.5em 0 0.3em}
h3{font-size:1.2em;margin:0.4em 0 0.2em}
h4{font-size:1.1em;margin:0.4em 0 0.2em}
h5{font-size:1em;margin:0.3em 0 0.2em}
pre{background:__CODE_BG__;border-radius:6px;padding:10px;margin:0.5em 0;overflow-x:auto;font-size:0.85em}
code{background:__CODE_BG__;border-radius:3px;padding:1px 4px;font-size:0.85em;font-family:monospace}
pre code{background:none;padding:0;border-radius:0}
blockquote{border-left:3px solid __BLOCKQUOTE_COLOR__;padding:0.2em 0 0.2em 1em;margin:0.5em 0;opacity:0.85}
ul,ol{padding-left:1.5em;margin:0.3em 0}
li{margin:0.15em 0}
hr{border:none;border-top:1px solid __HR_COLOR__;margin:0.6em 0}
a{color:__LINK_COLOR__;text-decoration:none}
a:hover{text-decoration:underline}
img{max-width:100%;border-radius:4px;margin:0.3em 0}
.katex{font-size:1.05em}
.katex-display{margin:0.4em 0}
</style>
</head>
<body>
<div id="content">__CONTENT__</div>
<script>
var content = __CONTENT_JSON__;
try {
  var html = renderMarkdown(content);
  document.getElementById('content').innerHTML = html;
  renderMathInElement(document.body, {
    delimiters: [
      {left: '$$', right: '$$', display: true},
      {left: '$', right: '$', display: false}
    ],
    throwOnError: false,
    errorColor: '#CC0000'
  });
  // Notify host of content height changes
  var observer = new ResizeObserver(function() {
    var h = document.body.scrollHeight;
    // Android will handle layout via WRAP_CONTENT
  });
  observer.observe(document.body);
} catch(e) {
  document.getElementById('content').textContent = 'Render error: ' + e.message;
}

function renderMarkdown(text) {
  // Simple markdown to HTML converter
  var html = text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  // Code blocks
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, function(_, lang, code) {
    return '<pre><code' + (lang ? ' class="lang-'+lang+'"' : '') + '>' + code + '</code></pre>';
  });

  // Horizontal rules
  html = html.replace(/^---+$/gm, '<hr>');

  // Headings
  html = html.replace(/^##### (.+)$/gm, '<h5>$1</h5>');
  html = html.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');

  // Blockquotes
  html = html.replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>');

  // Images
  html = html.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1">');

  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');

  // Bold+italic, bold, italic
  html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

  // Ordered lists
  html = html.replace(/(?:^\d+\.\s.*\n?)+/gm, function(match) {
    var items = match.trim().split('\n');
    var lis = items.map(function(item) {
      return '  <li>' + item.replace(/^\d+\.\s+/, '') + '</li>';
    }).join('\n');
    return '<ol>\n' + lis + '\n</ol>';
  });

  // Unordered lists
  html = html.replace(/(?:^[-*]\s.*\n?)+/gm, function(match) {
    var items = match.trim().split('\n');
    var lis = items.map(function(item) {
      return '  <li>' + item.replace(/^[-*]\s+/, '') + '</li>';
    }).join('\n');
    return '<ul>\n' + lis + '\n</ul>';
  });

  // Wrap paragraphs
  var lines = html.split('\n');
  var result = [];
  var inP = false;
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line === '') {
      if (inP) { result.push('</p>'); inP = false; }
      continue;
    }
    var isBlock = /^<(h[1-5]|pre|ol|ul|li|hr|blockquote|img)/.test(line) ||
      /^<\/(ol|ul|li)>/.test(line);
    if (isBlock) {
      if (inP) { result.push('</p>'); inP = false; }
      result.push(line);
    } else {
      if (!inP) { result.push('<p>'); inP = true; }
      else result.push('<br>');
      result.push(line);
    }
  }
  if (inP) result.push('</p>');

  return result.join('\n');
}
</script>
</body>
</html>
"""

/**
 * Composable that renders Markdown text with LaTeX math support using
 * a WebView with KaTeX.
 *
 * This is used as a fallback renderer when the text contains LaTeX
 * expressions. For plain markdown (no LaTeX), the existing MarkdownText
 * composable is used for better performance.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LatexMarkdownWebView(
    markdown: String,
    textColor: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
    fontFamily: String = "sans-serif",
    highlightBg: Color = Color(0x1A000000),
    highlightText: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val fontSizePx = with(density) { fontSize.toPx() }
    val lineHeightVal = with(density) { lineHeight.toPx() }
    val lineHeightRatio = if (fontSizePx > 0) lineHeightVal / fontSizePx else 1.5

    val colorHex = colorToHex(textColor)
    val highlightBgHex = colorToHex(highlightBg)
    val highlightTextHex = colorToHex(if (highlightText == Color.Unspecified) textColor else highlightText)
    val codeBgHex = colorToHex(highlightBg.copy(alpha = 0.5f))
    val blockquoteColor = highlightBgHex
    val hrColor = colorToHex(textColor.copy(alpha = 0.2f))
    val linkColor = if (textColor == Color.Unspecified) "#1565C0" else colorToHex(textColor)

    val contentJson = remember(markdown) {
        LatexParser.escapeForJs(markdown)
    }

    val html = remember(contentJson, fontSizePx, colorHex, lineHeightRatio) {
        MARKDOWN_LATEX_HTML
            .replace("__CONTENT__", contentJson)
            .replace("__CONTENT_JSON__", "\"" + contentJson + "\"")
            .replace("__FONT_SIZE__", fontSizePx.toInt().coerceAtLeast(8).toString())
            .replace("__LINE_HEIGHT__", lineHeightRatio.toString())
            .replace("__TEXT_COLOR__", colorHex)
            .replace("__FONT_FAMILY__", fontFamily)
            .replace("__HIGHLIGHT_BG__", highlightBgHex)
            .replace("__HIGHLIGHT_TEXT__", highlightTextHex)
            .replace("__CODE_BG__", codeBgHex)
            .replace("__BLOCKQUOTE_COLOR__", blockquoteColor)
            .replace("__HR_COLOR__", hrColor)
            .replace("__LINK_COLOR__", linkColor)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.apply {
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    domStorageEnabled = true
                }
                setInitialScale(100)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Re-adjust WebView height after content renders
                    }
                }
                loadDataWithBaseURL(
                    "file:///android_asset/katex/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = modifier
    )
}

/**
 * Converts a Compose Color to a hex string for use in HTML/CSS.
 */
private fun colorToHex(color: Color): String {
    if (color == Color.Unspecified) return "#000000"
    val r = (color.red * 255).toInt().coerceIn(0, 255)
    val g = (color.green * 255).toInt().coerceIn(0, 255)
    val b = (color.blue * 255).toInt().coerceIn(0, 255)
    val a = (color.alpha * 255).toInt().coerceIn(0, 255)
    return if (a < 255) {
        String.format("#%02X%02X%02X%02X", r, g, b, a)
    } else {
        String.format("#%02X%02X%02X", r, g, b)
    }
}

/**
 * Smart composable that renders Markdown text, using the WebView+KaTeX renderer
 * when LaTeX is detected, and the standard MarkdownText composable otherwise.
 *
 * This is the main entry point for rendering finished (non-streaming) messages.
 */
@Composable
fun SmartMarkdownText(
    markdown: String,
    style: TextStyle,
    syntaxHighlightColor: Color,
    syntaxHighlightTextColor: Color,
    modifier: Modifier = Modifier
) {
    val hasLatex = remember(markdown) {
        LatexParser.hasLatex(markdown)
    }

    if (hasLatex) {
        LatexMarkdownWebView(
            markdown = markdown,
            textColor = style.color ?: Color.Unspecified,
            fontSize = style.fontSize,
            lineHeight = style.lineHeight,
            fontFamily = "sans-serif",
            highlightBg = syntaxHighlightColor,
            highlightText = syntaxHighlightTextColor,
            modifier = modifier
        )
    } else {
        MarkdownText(
            markdown = markdown,
            style = style,
            syntaxHighlightColor = syntaxHighlightColor,
            syntaxHighlightTextColor = syntaxHighlightTextColor,
            modifier = modifier
        )
    }
}

/**
 * Composable that renders a single LaTeX expression using KaTeX in a WebView.
 *
 * @param expression The LaTeX expression (without delimiters)
 * @param isDisplay Whether to render as display math (block) or inline math
 * @param textColor The text color
 * @param fontSize The font size
 * @param modifier Modifier for the composable
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LatexView(
    expression: String,
    isDisplay: Boolean,
    textColor: Color = Color.Unspecified,
    fontSize: TextUnit = 15.sp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val fontSizePx = with(density) { fontSize.toPx() }
    val colorHex = colorToHex(textColor)

    val escapedExpr = remember(expression) { LatexParser.escapeForJs(expression) }

    val html = remember(escapedExpr, isDisplay, fontSizePx, colorHex) {
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width,initial-scale=1.0">
        <link rel="stylesheet" href="katex.min.css">
        <script src="katex.min.js"></script>
        <style>
        *{margin:0;padding:0;box-sizing:border-box}
        html,body{background:transparent;display:inline-flex;align-items:center;justify-content:flex-start;min-height:1.2em}
        body{font-size:${fontSizePx.toInt().coerceAtLeast(8)}px;color:$colorHex}
        .katex{font-size:1em}
        </style>
        </head>
        <body>
        <div id="m"></div>
        <script>
        try{katex.render("$escapedExpr",document.getElementById('m'),{displayMode:${isDisplay},throwOnError:false,maxSize:10})}
        catch(e){document.getElementById('m').textContent="$escapedExpr"}
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.apply {
                    builtInZoomControls = false
                    displayZoomControls = false
                }
                setInitialScale(100)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {}
                }
                loadDataWithBaseURL(
                    "file:///android_asset/katex/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = modifier
    )
}