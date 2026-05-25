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
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.ui.unit.Dp
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
// 把 LLM 输出里的 create_agents / assign_task / continue_conversation /
// peer_message tool call 渲染成结构化卡片，让用户一眼看清 Orchestrator
// 当前在做什么编排动作，而不是把工具参数吞进普通文本。
//
// 输入：assistant 消息的 toolCallsJson（OpenAI 标准 tool_calls 数组）
// ═══════════════════════════════════════════════════════════════════════════════

private val ORCHESTRATION_TOOLS = setOf(
    "create_agents", "assign_task", "continue_conversation", "peer_message"
)

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
        "create_agents" -> CreateAgentsCard(call.args, fs, resolvedFontFamily)
        "assign_task" -> AssignTaskCard(call.args, fs, resolvedFontFamily)
        "continue_conversation" -> ContinueConversationCard(call.args, fs, resolvedFontFamily)
        "peer_message" -> PeerMessageCard(call.args, fs, resolvedFontFamily, call.from)
        else -> GenericToolCallCard(call.name, call.args, fs, resolvedFontFamily)
    }
}

// ── create_agents ──────────────────────────────────────────────────────────────

@Composable
private fun CreateAgentsCard(args: JSONObject, fs: Float, fontFamily: FontFamily) {
    val taskMode = args.optString("taskMode", "claim")
    val agentsArr = args.optJSONArray("agents") ?: JSONArray()
    val agents = (0 until agentsArr.length()).mapNotNull { agentsArr.optJSONObject(it) }
    val hasDependencies = agents.any { (it.optJSONArray("dependsOn")?.length() ?: 0) > 0 }
    val effectiveMode = when {
        hasDependencies -> "dependsOn"
        else -> taskMode
    }

    val (modeLabel, modeColor) = when (effectiveMode) {
        "direct" -> uiText("workspace.tool.mode.direct", "Direct 模式") to MaterialTheme.colorScheme.tertiary
        "dependsOn" -> uiText("workspace.tool.mode.depends", "依赖链") to MaterialTheme.colorScheme.secondary
        else -> uiText("workspace.tool.mode.claim", "Claim 模式") to MaterialTheme.colorScheme.primary
    }

    ToolCallSurface(
        icon = Icons.Default.Hub,
        iconTint = MaterialTheme.colorScheme.primary,
        title = uiText("workspace.tool.create_agents", "创建 Agent 团队"),
        subtitle = uiText("workspace.tool.create_agents.count", "%d 个角色").format(agents.size),
        badge = modeLabel,
        badgeColor = modeColor,
        fs = fs,
        fontFamily = fontFamily,
    ) {
        for ((index, agent) in agents.withIndex()) {
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            AgentSpecRow(agent, fs, fontFamily)
        }
    }
}

@Composable
private fun AgentSpecRow(agent: JSONObject, fs: Float, fontFamily: FontFamily) {
    val name = agent.optString("name")
    val role = agent.optString("role")
    val systemPrompt = agent.optString("systemPrompt")
    val dependsOnArr = agent.optJSONArray("dependsOn")
    val dependsOn = (0 until (dependsOnArr?.length() ?: 0))
        .mapNotNull { dependsOnArr?.optString(it) }
        .filter { it.isNotBlank() }

    val agentColorHex = LocalAgentColorLookup.current(name)
    val agentColor = agentColorHex?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary

    Column {
        // 名字行
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentColorDot(agentColorHex ?: "#888888", size = 8.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                fontSize = (13 * fs).sp,
                fontWeight = FontWeight.Bold,
                color = agentColor,
                fontFamily = fontFamily
            )
        }

        // 角色描述
        if (role.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = role,
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = fontFamily,
                lineHeight = (17 * fs).sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 依赖链
        if (dependsOn.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = uiText("workspace.tool.depends_on", "依赖：%s").format(dependsOn.joinToString(" → ")),
                    fontSize = (10.5f * fs).sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = fontFamily
                )
            }
        }

        // systemPrompt 折叠
        if (systemPrompt.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.height(18.dp)
            ) {
                Text(
                    text = if (expanded)
                        uiText("workspace.tool.prompt.hide", "收起系统提示")
                    else
                        uiText("workspace.tool.prompt.show", "查看系统提示"),
                    fontSize = (10.5f * fs).sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = fontFamily
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = systemPrompt,
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = fontFamily,
                        lineHeight = (15 * fs).sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

// ── assign_task ────────────────────────────────────────────────────────────────

@Composable
private fun AssignTaskCard(args: JSONObject, fs: Float, fontFamily: FontFamily) {
    val to = args.optString("to")
    val task = args.optString("task")
    val context = args.optString("context")

    val agentColorHex = LocalAgentColorLookup.current(to)

    ToolCallSurface(
        icon = Icons.Default.AssignmentInd,
        iconTint = MaterialTheme.colorScheme.tertiary,
        title = uiText("workspace.tool.assign_task", "分配任务"),
        subtitle = null,
        badge = null,
        badgeColor = null,
        fs = fs,
        fontFamily = fontFamily,
        accentColorHex = agentColorHex,
    ) {
        // 目标 Agent
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = uiText("workspace.tool.target", "→ "),
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = fontFamily
            )
            if (agentColorHex != null) {
                AgentColorDot(agentColorHex, size = 8.dp)
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = to,
                fontSize = (12.5f * fs).sp,
                fontWeight = FontWeight.Bold,
                color = agentColorHex?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary,
                fontFamily = fontFamily
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 任务详情（可折叠，长任务默认折叠）
        ExpandableLongText(
            text = task,
            previewLines = 5,
            fs = fs,
            fontFamily = fontFamily,
        )

        if (context.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = uiText("workspace.tool.context", "上下文"),
                        fontSize = (10 * fs).sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = fontFamily
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = context,
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = fontFamily,
                        lineHeight = (15 * fs).sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── continue_conversation ──────────────────────────────────────────────────────

@Composable
private fun ContinueConversationCard(args: JSONObject, fs: Float, fontFamily: FontFamily) {
    val to = args.optString("to")
    val message = args.optString("message")
    val agentColorHex = LocalAgentColorLookup.current(to)

    ToolCallSurface(
        icon = Icons.Default.Forum,
        iconTint = MaterialTheme.colorScheme.secondary,
        title = uiText("workspace.tool.continue", "继续对话"),
        subtitle = to.takeIf { it.isNotBlank() },
        badge = null,
        badgeColor = null,
        fs = fs,
        fontFamily = fontFamily,
        accentColorHex = agentColorHex,
    ) {
        ExpandableLongText(
            text = message,
            previewLines = 5,
            fs = fs,
            fontFamily = fontFamily,
        )
    }
}

// ── peer_message ───────────────────────────────────────────────────────────────

@Composable
private fun PeerMessageCard(args: JSONObject, fs: Float, fontFamily: FontFamily, from: String?) {
    val to = args.optString("to")
    val message = args.optString("message")
    val summary = args.optString("summary")

    val isBroadcast = to == "*"
    val targetColorHex = if (isBroadcast) null else LocalAgentColorLookup.current(to)

    ToolCallSurface(
        icon = if (isBroadcast) Icons.Default.Campaign else Icons.AutoMirrored.Filled.Chat,
        iconTint = MaterialTheme.colorScheme.tertiary,
        title = if (isBroadcast)
            uiText("workspace.tool.broadcast", "广播消息")
        else
            uiText("workspace.tool.peer", "Agent 间消息"),
        subtitle = when {
            isBroadcast -> uiText("workspace.tool.broadcast.target", "→ 全员")
            from != null && from.isNotBlank() -> "$from → $to"
            else -> "→ $to"
        },
        badge = summary.takeIf { it.isNotBlank() },
        badgeColor = MaterialTheme.colorScheme.tertiary,
        fs = fs,
        fontFamily = fontFamily,
        accentColorHex = targetColorHex,
    ) {
        ExpandableLongText(
            text = message,
            previewLines = 4,
            fs = fs,
            fontFamily = fontFamily,
        )
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
    /** 仅 peer_message 卡片需要，从消息上下文推断 */
    val from: String? = null,
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
