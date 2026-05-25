package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ChunkedStreamingText
import com.example.ui.theme.LocalCustomColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.workspace.AgentMessage

// ═══════════════════════════════════════════════════════════════════════════════
// 消息气泡组件
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 单条消息气泡。
 *
 * 根据消息角色（user/assistant/system/tool）渲染不同样式。
 * assistant 消息支持 Markdown 渲染。
 */
@Composable
fun AgentBubbleMessage(message: AgentMessage) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacingMultiplier = uiSettings.spacingMultiplier
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val isTool = message.role == "tool"

    when {
        isSystem -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    dev.jeziellago.compose.markdowntext.MarkdownText(
                        markdown = message.content,
                        style = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = (11 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Text(
                        text = formatTimestamp(message.timestamp),
                        fontSize = (9 * fs).sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        isTool -> {
            var isExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { isExpanded = !isExpanded },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiText("workspace.tool.called", "工具执行结果 (ID: %s)").format(message.toolCallId?.take(8) ?: "unknown"),
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatTimestamp(message.timestamp),
                                fontSize = (9 * fs).sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(0.95f)
                        ) {
                            Text(
                                text = message.content,
                                fontSize = (11 * fs).sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
        else -> {
            // 来自主控 Agent 的注入消息（Sub-Agent 视角）
            val isOrchestratorInjected = message.source == "orchestrator" && isUser && !message.isIntervention
            // 来自子 Agent 的任务报告（Orchestrator 视角）
            val isSubAgentReport = message.source == "subagent"

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = if (isUser && !isOrchestratorInjected) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                // 检测 assistant 消息是否包含编排 tool call（决定 Column 宽度和渲染分支）
                val isAssistant = message.role == "assistant"
                val isOrchestrationCall = isAssistant && hasOrchestrationToolCalls(message.toolCallsJson)

                Column(
                    horizontalAlignment = if (isUser && !isOrchestratorInjected) Alignment.End else Alignment.Start,
                    modifier = if (isOrchestrationCall) Modifier.fillMaxWidth() else Modifier
                ) {
                    // 标签行
                    if (message.isIntervention) {
                        Text(
                            text = uiText("workspace.intervention.label", "【用户干预】"),
                            fontSize = (10 * fs).sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    } else if (isOrchestratorInjected) {
                        Text(
                            text = uiText("workspace.orchestrator.injected", "来自主控 Agent"),
                            fontSize = (10 * fs).sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    // 消息气泡
                    val bubbleColor = when {
                        isUser && !isOrchestratorInjected -> MaterialTheme.colorScheme.primary
                        isOrchestratorInjected -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val textColor = when {
                        isUser && !isOrchestratorInjected -> MaterialTheme.colorScheme.onPrimary
                        isOrchestratorInjected -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    // 渲染分支：编排 tool call → 全宽卡片；普通 → 气泡
                    if (isOrchestrationCall) {
                        OrchestrationToolCallList(
                            assistantText = message.content,
                            toolCallsJson = message.toolCallsJson ?: "[]",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                        )
                    } else {
                        Surface(
                            color = bubbleColor,
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isUser && !isOrchestratorInjected) 16.dp else 4.dp,
                                bottomEnd = if (isUser && !isOrchestratorInjected) 4.dp else 16.dp
                            ),
                            tonalElevation = 1.dp,
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Column {
                                if (isSubAgentReport) {
                                    // 子 Agent 报告：格式化显示而非原始 XML
                                    FormattedSubAgentReport(
                                        content = message.content,
                                        fs = fs,
                                        resolvedFontFamily = resolvedFontFamily,
                                        textColor = textColor
                                    )
                                } else {
                                    dev.jeziellago.compose.markdowntext.MarkdownText(
                                        markdown = message.content,
                                        style = androidx.compose.ui.text.TextStyle(
                                            color = textColor,
                                            fontSize = (14.5f * fs).sp,
                                            lineHeight = (21 * fs).sp,
                                            fontFamily = resolvedFontFamily
                                        ),
                                        syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                                        syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp, 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = formatTimestamp(message.timestamp),
                        fontSize = (9 * fs).sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * 流式消息气泡。
 *
 * 用于 Agent 正在输出时显示，一个持续增长的气泡。
 */
@Composable
fun StreamingBubble(text: String) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp, 8.dp)) {
                ChunkedStreamingText(
                    text = text,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (14.5f * fs).sp,
                    lineHeight = (21 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

/**
 * 格式化子 Agent 报告。
 *
 * 解析 task-notification XML 并渲染为美观的卡片样式，
 * 而非显示原始 XML 标签。
 */
@Composable
private fun FormattedSubAgentReport(
    content: String,
    fs: Float,
    resolvedFontFamily: FontFamily,
    textColor: Color,
) {
    // 尝试解析 task-notification XML
    val parsed = parseTaskNotification(content)

    if (parsed != null) {
        // 格式化为卡片
        Column(modifier = Modifier.padding(12.dp, 8.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (parsed.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (parsed.success) LocalCustomColors.current.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = parsed.agentName,
                    fontSize = (13 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.weight(1f))
                if (parsed.durationMs > 0) {
                    Text(
                        text = formatDuration(parsed.durationMs),
                        fontSize = (10 * fs).sp,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = resolvedFontFamily
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 结果内容
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = parsed.result,
                    fontSize = (12 * fs).sp,
                    color = textColor,
                    fontFamily = resolvedFontFamily,
                    lineHeight = (17 * fs).sp,
                    modifier = Modifier.padding(8.dp),
                    maxLines = 20,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // 统计信息
            if (parsed.totalTokens > 0 || parsed.toolUses > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (parsed.totalTokens > 0) {
                        Text(
                            text = "${parsed.totalTokens} tokens",
                            fontSize = (10 * fs).sp,
                            color = textColor.copy(alpha = 0.5f),
                            fontFamily = resolvedFontFamily
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (parsed.toolUses > 0) {
                        Text(
                            text = "${parsed.toolUses} 次工具调用",
                            fontSize = (10 * fs).sp,
                            color = textColor.copy(alpha = 0.5f),
                            fontFamily = resolvedFontFamily
                        )
                    }
                }
            }
        }
    } else {
        // 解析失败，回退到 Markdown 渲染
        dev.jeziellago.compose.markdowntext.MarkdownText(
            markdown = content,
            style = androidx.compose.ui.text.TextStyle(
                color = textColor,
                fontSize = (14.5f * fs).sp,
                lineHeight = (21 * fs).sp,
                fontFamily = resolvedFontFamily
            ),
            modifier = Modifier.padding(12.dp, 8.dp)
        )
    }
}

// ── 辅助数据和函数 ──

private data class TaskNotificationParsed(
    val agentName: String,
    val success: Boolean,
    val result: String,
    val totalTokens: Int,
    val toolUses: Int,
    val durationMs: Long,
)

private fun parseTaskNotification(content: String): TaskNotificationParsed? {
    // 支持多个 <task-notification> 块
    val notifications = mutableListOf<TaskNotificationParsed>()
    val pattern = """<task-notification>(.*?)</task-notification>""".toRegex(RegexOption.DOT_MATCHES_ALL)

    for (match in pattern.findAll(content)) {
        val block = match.groupValues[1]
        val agentId = extractXmlValue(block, "task-id") ?: continue
        val status = extractXmlValue(block, "status") ?: "completed"
        val result = extractXmlValue(block, "result") ?: ""
        val totalTokens = extractXmlValue(block, "total_tokens")?.toIntOrNull() ?: 0
        val toolUses = extractXmlValue(block, "tool_uses")?.toIntOrNull() ?: 0
        val durationMs = extractXmlValue(block, "duration_ms")?.toLongOrNull() ?: 0L

        notifications.add(
            TaskNotificationParsed(
                agentName = agentId,
                success = status == "completed",
                result = result.trim(),
                totalTokens = totalTokens,
                toolUses = toolUses,
                durationMs = durationMs,
            )
        )
    }

    return notifications.firstOrNull()
}

private fun extractXmlValue(xml: String, tag: String): String? {
    val pattern = """<$tag>(.*?)</$tag>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    return pattern.find(xml)?.groupValues?.get(1)?.trim()
}

private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }
}
