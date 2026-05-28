package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════════════
// 编排工具调用卡片（Orchestration Tool Call Card）
//
// 把 LLM 输出里的 agent（Claude Code-style SubAgent）tool call
// 渲染成结构化卡片，让用户一眼看清编排动作。
//
// 输入：assistant 消息的 toolCallsJson（OpenAI 标准 tool_calls 数组）
// ═══════════════════════════════════════════════════════════════════════════════

private val ORCHESTRATION_TOOLS = setOf("agent")

/**
 * 判断给定 toolCallsJson 是否含有任何编排工具调用。
 *
 * 用于 [AgentBubbleMessage] 决定是否要切换到结构化卡片渲染模式。
 */
fun hasOrchestrationToolCalls(toolCallsJson: String?): Boolean {
    if (toolCallsJson.isNullOrBlank()) return false
    return try {
        val arr = JSONArray(toolCallsJson)
        (0 until arr.length()).any { i ->
            val name = arr.optJSONObject(i)
                ?.optJSONObject("function")
                ?.optString("name") ?: ""
            name in ORCHESTRATION_TOOLS
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * 编排工具调用列表渲染。
 *
 * 把 toolCallsJson 拆成多个卡片，依次渲染。assistantText（可选）作为
 * 工具调用前的前置说明，用 markdown 渲染成淡色文本。
 */
@Composable
fun OrchestrationToolCallList(
    assistantText: String,
    toolCallsJson: String,
    modifier: Modifier = Modifier,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    val toolCalls = remember(toolCallsJson) { parseToolCalls(toolCallsJson) }

    Column(modifier = modifier) {
        // 前置说明文本
        if (assistantText.isNotBlank()) {
            dev.jeziellago.compose.markdowntext.MarkdownText(
                markdown = assistantText,
                style = androidx.compose.ui.text.TextStyle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (13.5f * fs).sp,
                    lineHeight = (20 * fs).sp,
                    fontFamily = resolvedFontFamily
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        toolCalls.forEachIndexed { index, call ->
            if (index > 0) Spacer(modifier = Modifier.height(6.dp))
            OrchestrationToolCallCard(call = call)
        }
    }
}

/**
 * 单个编排工具调用卡片。
 */
@Composable
private fun OrchestrationToolCallCard(call: ParsedToolCall) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    when (call.name) {
        "agent" -> AgentToolCard(call.args, fs, resolvedFontFamily)
        else -> GenericToolCallCard(call.name, call.args, fs, resolvedFontFamily)
    }
}

// ── agent (Claude Code-style SubAgent delegation) ─────────────────────────────

@Composable
private fun AgentToolCard(args: JSONObject, fs: Float, fontFamily: FontFamily) {
    val description = args.optString("description", "")
    val prompt = args.optString("prompt", "")
    val model = args.optString("model", "")

    ToolCallSurface(
        icon = Icons.Default.Hub,
        iconTint = MaterialTheme.colorScheme.primary,
        title = uiText("workspace.tool.agent", "委派子 Agent"),
        subtitle = description.takeIf { it.isNotBlank() },
        badge = model.takeIf { it.isNotBlank() },
        badgeColor = MaterialTheme.colorScheme.tertiary,
        fs = fs,
        fontFamily = fontFamily,
    ) {
        if (prompt.isNotBlank()) {
            ExpandableLongText(
                text = prompt,
                previewLines = 4,
                fs = fs,
                fontFamily = fontFamily,
            )
        }
    }
}

// ── 兜底：普通 MCP 工具调用 ────────────────────────────────────────────────────

@Composable
private fun GenericToolCallCard(name: String, args: JSONObject, fs: Float, fontFamily: FontFamily) {
    var expanded by remember { mutableStateOf(false) }

    ToolCallSurface(
        icon = Icons.Default.Build,
        iconTint = MaterialTheme.colorScheme.outline,
        title = uiText("workspace.tool.generic", "工具调用"),
        subtitle = name,
        badge = null,
        badgeColor = null,
        fs = fs,
        fontFamily = fontFamily,
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(
                text = if (expanded)
                    uiText("workspace.tool.args.hide", "收起参数")
                else
                    uiText("workspace.tool.args.show", "查看参数"),
                fontSize = (11 * fs).sp,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = fontFamily
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                text = args.toString(2),
                fontSize = (11 * fs).sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ── 通用卡片骨架 + 折叠文本 ────────────────────────────────────────────────────

@Composable
private fun ToolCallSurface(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String?,
    badge: String?,
    badgeColor: Color?,
    fs: Float,
    fontFamily: FontFamily,
    accentColorHex: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = accentColorHex?.let { parseColor(it) } ?: iconTint
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.4f)),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // 左侧 4dp 强调色边
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent.copy(alpha = 0.8f))
                )
                Column(modifier = Modifier.padding(10.dp)) {
                    // 头部
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = title,
                            fontSize = (12 * fs).sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = fontFamily
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = subtitle,
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontFamily = fontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        if (!badge.isNullOrBlank() && badgeColor != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = badgeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = badge,
                                    fontSize = (10 * fs).sp,
                                    fontWeight = FontWeight.Medium,
                                    color = badgeColor,
                                    fontFamily = fontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(content = content)
                }
            }
        }
    }
}

@Composable
private fun ExpandableLongText(
    text: String,
    previewLines: Int,
    fs: Float,
    fontFamily: FontFamily,
) {
    if (text.isBlank()) return
    val approximateLines = text.count { it == '\n' } + 1
    val isLong = approximateLines > previewLines || text.length > 240
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = text,
        fontSize = (12 * fs).sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = fontFamily,
        lineHeight = (17 * fs).sp,
        maxLines = if (!isLong || expanded) Int.MAX_VALUE else previewLines,
        overflow = TextOverflow.Ellipsis
    )
    if (isLong) {
        Spacer(modifier = Modifier.height(2.dp))
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.height(18.dp)
        ) {
            Text(
                text = if (expanded)
                    uiText("workspace.tool.collapse", "收起")
                else
                    uiText("workspace.tool.expand", "展开全文"),
                fontSize = (10.5f * fs).sp,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = fontFamily
            )
        }
    }
}

// ── 数据解析 ────────────────────────────────────────────────────────────────────

private data class ParsedToolCall(
    val name: String,
    val args: JSONObject,
)

private fun parseToolCalls(toolCallsJson: String): List<ParsedToolCall> {
    return try {
        val arr = JSONArray(toolCallsJson)
        (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val function = item.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val argsStr = function.optString("arguments", "{}")
            val args = try {
                JSONObject(argsStr)
            } catch (_: Exception) {
                JSONObject().apply { put("_raw", argsStr) }
            }
            ParsedToolCall(name = name, args = args)
        }
    } catch (_: Exception) {
        emptyList()
    }
}
