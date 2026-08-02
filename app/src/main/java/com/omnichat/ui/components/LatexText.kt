package com.omnichat.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil

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
     * Check if text has potential LaTeX math (has $ signs, even if incomplete).
     * Used for finished messages: if there are $ signs, try the WebView renderer
     * which handles rendering errors gracefully.
     */
    fun hasPotentialLatex(text: String): Boolean {
        if (text.contains("$$") || text.contains('$')) {
            // Also check for typical LaTeX patterns after $ signs
            return true
        }
        return false
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
 * - A simple markdown-to-HTML converter (with math block protection)
 * - KaTeX auto-render to process math delimiters
 *
 * CRITICAL: The markdown converter must PROTECT math blocks ($...$, $$...$$)
 * before processing, then restore them, to avoid * and ** patterns inside
 * LaTeX expressions being corrupted by bold/italic conversion.
 */
private const val MARKDOWN_LATEX_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<link rel="stylesheet" href="katex.min.css">
<script src="katex.min.js"></script>
<script src="auto-render.min.js"></script>
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body,#content{background:transparent;width:100%;max-width:100%;height:auto;min-height:0}
body{font-size:__FONT_SIZE__px;color:__TEXT_COLOR__;font-family:__FONT_FAMILY__;line-height:__LINE_HEIGHT__;padding:0;word-wrap:break-word;overflow-wrap:break-word;overflow-x:visible}
body ::selection{background:__HIGHLIGHT_BG__;color:__HIGHLIGHT_TEXT__}
p{margin:0.3em 0;line-height:__LINE_HEIGHT__}
h1{font-size:1.6em;margin:0.5em 0 0.3em}
h2{font-size:1.4em;margin:0.5em 0 0.3em}
h3{font-size:1.2em;margin:0.4em 0 0.2em}
h4{font-size:1.1em;margin:0.4em 0 0.2em}
h5{font-size:1em;margin:0.3em 0 0.2em}
pre{background:__CODE_BG__;border-radius:6px;padding:10px;margin:0.5em 0;overflow-x:auto;font-size:0.85em;max-width:100%}
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
/* Keep every display formula at its intended KaTeX size.  The dedicated
 * scroll content gets an explicit measured width in JavaScript, because the
 * generated KaTeX node's visual overflow is not always scrollable in Android
 * WebView by itself. */
.math-display-scroll{display:block;width:100%;max-width:100%;margin:0.4em 0;overflow-x:scroll;overflow-y:hidden;touch-action:pan-x;-webkit-overflow-scrolling:touch;overscroll-behavior-x:contain;scrollbar-width:thin}
.math-display-content{display:block;min-width:100%}
.math-display-content .katex-display{display:block;width:100%;max-width:none;margin:0;overflow:visible;text-align:center;padding:0 .15em .35em}
.math-display-content .katex{white-space:nowrap}
.math-display-scroll.is-scrollable{border-bottom:1px solid rgba(128,128,128,.28);padding-bottom:.1em}
.math-display-scroll.is-scrollable::after{content:'\2194\FE0E \5DE6 \53F3 \6ED1 \52A8 \67E5 \770B';display:block;position:sticky;left:0;width:max-content;margin:.1em auto 0;font-size:.72em;opacity:.62;white-space:nowrap}
/* Normal inline math remains inline.  The fallback wrapper also prevents a
 * very long inline expression from being clipped by the WebView viewport. */
.inline-math{display:inline-block;max-width:100%;overflow-x:auto;overflow-y:hidden;vertical-align:middle;-webkit-overflow-scrolling:touch}
.inline-math .katex{white-space:nowrap}
</style>
</head>
<body>
<div id="content"></div>
<script>
var contentElement = document.getElementById('content');
var heightReportPending = false;
// The Android touch bridge marks a drag that began inside a scrollable formula.
// This lets the native layer reserve only that horizontal gesture for math while
// ordinary horizontal swipes remain available to the app drawer.
var activeMathScrollContainer = null;

function closestMathScrollContainer(node) {
  while (node && node !== document.body) {
    if (node.classList && (
      node.classList.contains('math-display-scroll') ||
      node.classList.contains('inline-math')
    )) return node;
    node = node.parentNode;
  }
  return null;
}

function setActiveMathScrollContainer(node) {
  var container = closestMathScrollContainer(node);
  var canScroll = container && container.scrollWidth > container.clientWidth + 1;
  activeMathScrollContainer = canScroll ? container : null;
  if (window.AndroidMathTouch && window.AndroidMathTouch.setFormulaTouch) {
    window.AndroidMathTouch.setFormulaTouch(!!activeMathScrollContainer);
  }
}

document.addEventListener('touchstart', function(event) {
  setActiveMathScrollContainer(event.target);
}, true);
document.addEventListener('touchend', function() {
  activeMathScrollContainer = null;
  if (window.AndroidMathTouch && window.AndroidMathTouch.setFormulaTouch) {
    window.AndroidMathTouch.setFormulaTouch(false);
  }
}, true);
document.addEventListener('touchcancel', function() {
  activeMathScrollContainer = null;
  if (window.AndroidMathTouch && window.AndroidMathTouch.setFormulaTouch) {
    window.AndroidMathTouch.setFormulaTouch(false);
  }
}, true);

// Native WebView touch handling calls this after it has claimed a horizontal
// formula drag. It is deliberately independent from browser scrolling, which
// Android WebView can otherwise lose to the surrounding Compose hierarchy.
window.scrollActiveMath = function(deltaX) {
  if (!activeMathScrollContainer) return;
  activeMathScrollContainer.scrollLeft += Number(deltaX) || 0;
};
window.clearActiveMathScroll = function() {
  activeMathScrollContainer = null;
};

function reportContentHeight() {
  heightReportPending = false;
  var cssHeight = Math.max(
    contentElement.scrollHeight,
    document.body.scrollHeight,
    document.documentElement.scrollHeight,
    1
  );
  var devicePixelRatio = window.devicePixelRatio || 1;
  if (window.AndroidContentHeight && window.AndroidContentHeight.report) {
    window.AndroidContentHeight.report(cssHeight, devicePixelRatio);
  }
}

function scheduleContentHeightReport() {
  if (heightReportPending) return;
  heightReportPending = true;
  requestAnimationFrame(function() {
    reportContentHeight();
    // KaTeX and WebView layout can settle one frame after innerHTML changes.
    setTimeout(reportContentHeight, 32);
  });
}

function updateMarkdown(text) {
  try {
    contentElement.innerHTML = renderMarkdown(text);
    // Preserve the intended KaTeX size. Long display expressions receive a
    // real horizontally scrollable content width instead of being shrunk.
    requestAnimationFrame(function() {
      prepareDisplayMathScroll();
      scheduleContentHeightReport();
    });
  } catch(e) {
    contentElement.textContent = text;
  }
  scheduleContentHeightReport();
}

if (window.ResizeObserver) {
  new ResizeObserver(scheduleContentHeightReport).observe(contentElement);
}
window.updateMarkdown = updateMarkdown;
updateMarkdown('');

function isEscapedDollar(text, index) {
  var slashCount = 0;
  for (var i = index - 1; i >= 0 && text.charAt(i) === '\\'; i--) slashCount++;
  return (slashCount % 2) === 1;
}

function isInlineMathOpen(text, index) {
  var previous = index > 0 ? text.charAt(index - 1) : '';
  var next = text.charAt(index + 1);
  // A leading $ followed by a digit is normally currency, not TeX.  Do not
  // impose a whitespace rule on the preceding character: Chinese prose and
  // punctuation are valid immediately before inline math.
  return next && !/\s|\d|\$/.test(next) && !/\d/.test(previous);
}

function isInlineMathClose(text, index) {
  var previous = text.charAt(index - 1);
  var next = index + 1 < text.length ? text.charAt(index + 1) : '';
  // Do not consume the first $ of $$ or a dollar embedded in a number.
  return previous && !/\s/.test(previous) && !/[\d$]/.test(next);
}

function renderMath(expression, displayMode) {
  var rendered = katex.renderToString(expression, {
    displayMode: displayMode,
    throwOnError: false,
    errorColor: '#CC0000'
  });
  return displayMode
    ? '<span class="math-display-scroll"><span class="math-display-content">' + rendered + '</span></span>'
    : '<span class="inline-math">' + rendered + '</span>';
}

function prepareDisplayMathScroll() {
  var containers = contentElement.querySelectorAll('.math-display-scroll');
  for (var i = 0; i < containers.length; i++) {
    var container = containers[i];
    var content = container.querySelector('.math-display-content');
    var formula = container.querySelector('.katex');
    if (!content || !formula || container.clientWidth <= 0) continue;

    // KaTeX visually overflows its parent. Give the direct child its measured
    // width so Android WebView exposes a genuine horizontal scroll range.
    var availableWidth = container.clientWidth;
    var naturalWidth = Math.ceil(formula.getBoundingClientRect().width + 8);
    var contentWidth = Math.max(availableWidth, naturalWidth);
    content.style.width = contentWidth + 'px';
    var canScroll = contentWidth > availableWidth + 1;
    container.classList.toggle('is-scrollable', canScroll);

    if (!canScroll) continue;

    // WebView can otherwise hand the gesture to the surrounding Compose
    // vertical list before CSS overflow sees it. Explicitly drag scroll only
    // when the gesture is horizontal, leaving normal vertical chat scrolling
    // untouched.
    (function(target) {
      var startX = 0;
      var startY = 0;
      var startScrollLeft = 0;
      var horizontalDrag = false;
      target.ontouchstart = function(event) {
        var touch = event.touches && event.touches[0];
        if (!touch) return;
        startX = touch.clientX;
        startY = touch.clientY;
        startScrollLeft = target.scrollLeft;
        horizontalDrag = false;
      };
      target.ontouchmove = function(event) {
        var touch = event.touches && event.touches[0];
        if (!touch) return;
        var dx = touch.clientX - startX;
        var dy = touch.clientY - startY;
        if (!horizontalDrag && Math.abs(dx) > 6 && Math.abs(dx) > Math.abs(dy)) {
          horizontalDrag = true;
        }
        if (horizontalDrag) {
          target.scrollLeft = startScrollLeft - dx;
          event.preventDefault();
        }
      };
    })(container);
  }
}

function isInsideCode(text, index) {
  // Do not parse a delimiter inside inline code or a fenced code block.  This
  // works on the original markdown before it is converted to HTML.
  var before = text.substring(0, index);
  var fenceCount = (before.match(/```/g) || []).length;
  if ((fenceCount % 2) === 1) return true;
  var lineStart = before.lastIndexOf('\n') + 1;
  var line = before.substring(lineStart);
  return ((line.match(/`/g) || []).length % 2) === 1;
}

function findClosingDelimiter(text, start, delimiter) {
  for (var cursor = start; cursor < text.length; cursor++) {
    if (text.charAt(cursor) !== delimiter.charAt(0) || isEscapedDollar(text, cursor)) continue;
    if (delimiter === '$$') {
      if (text.charAt(cursor + 1) === '$') return cursor;
    } else if (delimiter === '$') {
      if (isInlineMathClose(text, cursor)) return cursor;
    } else if (text.substr(cursor, delimiter.length) === delimiter) {
      return cursor;
    }
  }
  return -1;
}

function protectMath(text) {
  var placeholders = [];
  var output = '';
  var i = 0;

  while (i < text.length) {
    if (isInsideCode(text, i)) {
      output += text.charAt(i++);
      continue;
    }

    var delimiter = null;
    var closingDelimiter = null;
    var isDisplay = false;

    if (text.substr(i, 2) === '$$' && !isEscapedDollar(text, i)) {
      delimiter = '$$';
      closingDelimiter = '$$';
      isDisplay = true;
    } else if (text.charAt(i) === '$' && !isEscapedDollar(text, i) && isInlineMathOpen(text, i)) {
      delimiter = '$';
      closingDelimiter = '$';
    } else if (text.substr(i, 2) === '\\[') {
      delimiter = '\\[';
      closingDelimiter = '\\]';
      isDisplay = true;
    } else if (text.substr(i, 2) === '\\(') {
      delimiter = '\\(';
      closingDelimiter = '\\)';
    }

    if (!delimiter) {
      output += text.charAt(i++);
      continue;
    }

    var expressionStart = i + delimiter.length;
    var end = findClosingDelimiter(text, expressionStart, closingDelimiter);
    if (end === -1 || (!isDisplay && /[\r\n]/.test(text.substring(expressionStart, end)))) {
      output += text.charAt(i++);
      continue;
    }

    var expression = text.substring(expressionStart, end);
    var index = placeholders.length;
    try {
      placeholders.push(renderMath(expression, isDisplay));
    } catch (e) {
      placeholders.push(text.substring(i, end + closingDelimiter.length));
    }
    output += '\x00MATH' + index + '\x00';
    i = end + closingDelimiter.length;
  }

  return { text: output, placeholders: placeholders };
}

function renderMarkdown(text) {
  // STEP 1: Tokenize TeX delimiters before Markdown processing. This keeps
  // Markdown from consuming LaTeX underscores or asterisks and supports
  // $...$, $$...$$, \(...\), and \[...\].
  var math = protectMath(text);
  var placeholders = math.placeholders;
  var protectedText = math.text;

  // STEP 2: Convert markdown to HTML (safe now - math blocks are protected)
  var html = protectedText
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, function(_, lang, code) {
    return '<pre><code' + (lang ? ' class="lang-'+lang+'"' : '') + '>' + code + '</code></pre>';
  });
  html = html.replace(/^---+$/gm, '<hr>');
  html = html.replace(/^##### (.+)$/gm, '<h5>$1</h5>');
  html = html.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');
  html = html.replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>');
  html = html.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1">');
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');

  // Bold+italic, bold, italic - safe because math blocks are placeholders
  html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

  html = html.replace(/(?:^\d+\.\s.*\n?)+/gm, function(match) {
    var items = match.trim().split('\n');
    var lis = items.map(function(item) {
      return '  <li>' + item.replace(/^\d+\.\s+/, '') + '</li>';
    }).join('\n');
    return '<ol>\n' + lis + '\n</ol>';
  });

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

  html = result.join('\n');

  // STEP 3: Restore the KaTeX HTML after Markdown conversion.
  html = html.replace(/\x00MATH(\d+)\x00/g, function(_, idx) {
    return placeholders[parseInt(idx, 10)];
  });

  return html;
}
</script>
</body>
</html>
"""

/**
 * Composable that renders Markdown and LaTeX in one persistent WebView.
 *
 * The document is loaded once, then streaming updates call JavaScript directly
 * so Markdown and KaTeX can be re-rendered without a blank page between tokens.
 */
private class MarkdownHeightBridge(
    private val onHeightChanged: (Int) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHeightPx = 0

    @JavascriptInterface
    fun report(cssHeight: Double, devicePixelRatio: Double) {
        val heightPx = ceil((cssHeight * devicePixelRatio).coerceAtLeast(1.0)).toInt()
        if (heightPx == lastHeightPx) return
        lastHeightPx = heightPx
        mainHandler.post { onHeightChanged(heightPx) }
    }
}

/** Receives the DOM hit-test result for the gesture that just began. */
private class MarkdownMathTouchBridge(
    private val onFormulaTouchChanged: (Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setFormulaTouch(isScrollableFormula: Boolean) {
        mainHandler.post { onFormulaTouchChanged(isScrollableFormula) }
    }
}

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

    // WebView CSS pixels are already density-independent. Compose's toPx() returns
    // physical pixels, which inflated every WebView-rendered assistant message by
    // the device density. Use SP values and retain only the accessibility font scale.
    val fontSizeCssPx = if (fontSize.isSpecified) fontSize.value * density.fontScale else 15f
    val lineHeightCssPx = if (lineHeight.isSpecified) lineHeight.value * density.fontScale else 22f
    val lineHeightRatio = if (fontSizeCssPx > 0f) lineHeightCssPx / fontSizeCssPx else 1.5f

    val colorHex = colorToHex(textColor)
    val highlightBgHex = colorToHex(highlightBg)
    val highlightTextHex = colorToHex(if (highlightText == Color.Unspecified) textColor else highlightText)
    val codeBgHex = colorToHex(highlightBg.copy(alpha = 0.5f))
    val blockquoteColor = highlightBgHex
    val hrColor = colorToHex(textColor.copy(alpha = 0.2f))
    val linkColor = if (textColor == Color.Unspecified) "#1565C0" else colorToHex(textColor)

    // Keep one WebView document alive during streaming. Reloading the whole HTML
    // document for every token causes a blank/old measured height while KaTeX and
    // the page are loading again.
    val contentJson = remember(markdown) {
        JSONObject.quote(markdown)
    }
    val latestContentJson by rememberUpdatedState(contentJson)

    val html = remember(
        fontSizeCssPx,
        colorHex,
        lineHeightRatio,
        fontFamily,
        highlightBgHex,
        highlightTextHex,
        codeBgHex,
        blockquoteColor,
        hrColor,
        linkColor
    ) {
        MARKDOWN_LATEX_HTML
            .replace("__FONT_SIZE__", fontSizeCssPx.toInt().coerceAtLeast(8).toString())
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

    key(html) {
        var pageReady by remember { mutableStateOf(false) }
        var contentHeightPx by remember { mutableIntStateOf(1) }
        val latestHeightHandler = rememberUpdatedState<(Int) -> Unit> { heightPx ->
            if (heightPx != contentHeightPx) contentHeightPx = heightPx
        }
        val heightBridge = remember {
            MarkdownHeightBridge { heightPx -> latestHeightHandler.value(heightPx) }
        }
        val contentHeight = with(density) { contentHeightPx.toDp() }

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
                        domStorageEnabled = true
                        textZoom = 100
                        loadWithOverviewMode = false
                        useWideViewPort = false
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    // Gesture regions are intentionally separated:
                    // * Horizontal drags that BEGIN on a scrollable formula stay in this
                    //   WebView and scroll only that formula.
                    // * Horizontal drags elsewhere are left to ModalNavigationDrawer.
                    // * Vertical drags are always returned to the chat list.
                    var downX = 0f
                    var downY = 0f
                    var lastX = 0f
                    var gestureDirectionResolved = false
                    var formulaTouchStarted = false
                    var handlingFormulaHorizontalDrag = false
                    fun requestParentTouchOwnership(disallow: Boolean) {
                        var currentParent = parent
                        while (currentParent != null) {
                            currentParent.requestDisallowInterceptTouchEvent(disallow)
                            currentParent = currentParent.parent
                        }
                    }
                    val mathTouchBridge = MarkdownMathTouchBridge { startedInScrollableFormula ->
                        formulaTouchStarted = startedInScrollableFormula
                        // The browser supplies this immediately after ACTION_DOWN. Reserve
                        // only a genuine formula touch before the drawer sees ACTION_MOVE.
                        if (startedInScrollableFormula && !gestureDirectionResolved) {
                            requestParentTouchOwnership(true)
                        }
                    }
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                lastX = event.x
                                gestureDirectionResolved = false
                                formulaTouchStarted = false
                                handlingFormulaHorizontalDrag = false
                                // Do not block the drawer for normal message text.
                                requestParentTouchOwnership(false)
                                false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - downX
                                val dy = event.y - downY
                                if (!gestureDirectionResolved && (abs(dx) > 8f || abs(dy) > 8f)) {
                                    gestureDirectionResolved = true
                                    handlingFormulaHorizontalDrag =
                                        formulaTouchStarted && abs(dx) > abs(dy)
                                    // A vertical formula drag belongs to the chat. A horizontal
                                    // non-formula drag belongs to the app's sidebar drawer.
                                    requestParentTouchOwnership(handlingFormulaHorizontalDrag)
                                }
                                if (handlingFormulaHorizontalDrag) {
                                    val deltaX = lastX - event.x
                                    if (deltaX != 0f) {
                                        (view as WebView).evaluateJavascript(
                                            "window.scrollActiveMath(${deltaX.toDouble()});",
                                            null
                                        )
                                    }
                                    lastX = event.x
                                    // Consume only the selected formula's horizontal drag so it
                                    // cannot open/close the sidebar at the same time.
                                    true
                                } else {
                                    lastX = event.x
                                    false
                                }
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                (view as WebView).evaluateJavascript("window.clearActiveMathScroll();", null)
                                requestParentTouchOwnership(false)
                                false
                            }
                            else -> false
                        }
                    }
                    addJavascriptInterface(heightBridge, "AndroidContentHeight")
                    addJavascriptInterface(mathTouchBridge, "AndroidMathTouch")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                            view?.evaluateJavascript(
                                "window.updateMarkdown($latestContentJson);",
                                null
                            )
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
            update = { view ->
                if (pageReady) {
                    // Update only the document body; keep KaTeX/CSS/WebView alive.
                    (view as WebView).evaluateJavascript(
                        "window.updateMarkdown($contentJson);",
                        null
                    )
                }
            },
            modifier = modifier.height(contentHeight)
        )
    }
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
 * Smart composable that renders Markdown text using a WebView with KaTeX
 * for LaTeX math rendering and Markdown formatting.
 *
 * Unlike the previous version that conditionally switched between MarkdownText
 * and LatexMarkdownWebView, this now always uses the WebView renderer.
 * This ensures consistent rendering regardless of whether the text contains
 * LaTeX math, eliminating size discrepancies between the two renderers.
 *
 * The WebView's renderMarkdown() JavaScript function handles all standard
 * Markdown features, and KaTeX auto-render processes any LaTeX math found
 * within $...$ or $$...$$ delimiters.
 */
@Composable
fun SmartMarkdownText(
    markdown: String,
    style: TextStyle,
    syntaxHighlightColor: Color,
    syntaxHighlightTextColor: Color,
    modifier: Modifier = Modifier
) {
    LatexMarkdownWebView(
        markdown = markdown,
        textColor = style.color ?: Color.Unspecified,
        fontSize = style.fontSize,
        lineHeight = style.lineHeight,
        fontFamily = "sans-serif",
        highlightBg = syntaxHighlightColor,
        highlightText = syntaxHighlightTextColor,
        modifier = modifier.fillMaxWidth()
    )
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

    // CSS px are density-independent in Android WebView. Passing Compose's physical
    // pixel value here multiplies the rendered text by the screen density (e.g. 3x
    // on xxhdpi devices), so keep the SP value and apply only the user's font scale.
    val fontSizeCssPx = if (fontSize.isSpecified) fontSize.value * density.fontScale else 15f
    val colorHex = colorToHex(textColor)

    val escapedExpr = remember(expression) { LatexParser.escapeForJs(expression) }

    val html = remember(escapedExpr, isDisplay, fontSizeCssPx, colorHex) {
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
        body{font-size:${fontSizeCssPx.toInt().coerceAtLeast(8)}px;color:$colorHex}
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
                    textZoom = 100
                }
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
