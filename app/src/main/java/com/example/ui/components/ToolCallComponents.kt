package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

// Adapts message types across Chat and Workspace sessions
data class UIModelToolMessage(
    val role: String,
    val content: String,
    val toolCallId: String?,
    val toolCallsJson: String?,
    val timestamp: Long
)

fun com.example.data.Message.toUIModel() = UIModelToolMessage(role, content, toolCallId, toolCallsJson, timestamp)
fun com.example.workspace.AgentMessage.toUIModel() = UIModelToolMessage(role, content, toolCallId, toolCallsJson, timestamp)

data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

// Helper to pre-scan all messages and build toolCallId lookup map
fun buildToolCallLookup(messages: List<UIModelToolMessage>): Map<String, ToolCallInfo> {
    val lookup = mutableMapOf<String, ToolCallInfo>()
    messages.forEach { msg ->
        if (msg.role == "assistant" && !msg.toolCallsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(msg.toolCallsJson)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val function = item.optJSONObject("function") ?: continue
                    val name = function.optString("name")
                    val argsStr = function.optString("arguments", "{}")
                    val args = try {
                        JSONObject(argsStr)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        lookup[id] = ToolCallInfo(id, name, args)
                    }
                }
            } catch (_: Exception) {}
        }
    }
    return lookup
}

fun getToolIcon(name: String): ImageVector {
    return when (name) {
        "file_list" -> Icons.Default.Folder
        "file_read" -> Icons.Default.Code
        "file_write", "file_append" -> Icons.Default.Edit
        "file_delete" -> Icons.Default.Delete
        "file_search" -> Icons.Default.Search
        "search_memory" -> Icons.Default.Storage
        "adjust_ui", "adjust_font", "apply_color_scheme", "save_color_scheme", "delete_color_scheme" -> Icons.Default.Settings
        else -> Icons.Default.Build
    }
}

fun formatToolCallSummary(name: String, args: JSONObject): String {
    return when (name) {
        "file_list" -> {
            val path = args.optString("path").ifEmpty { "根目录" }
            "浏览了目录: $path"
        }
        "file_read" -> {
            val path = args.optString("path")
            "读取了文件: $path"
        }
        "file_write" -> {
            val path = args.optString("path")
            "写入了文件: $path"
        }
        "file_append" -> {
            val path = args.optString("path")
            "追加了文件: $path"
        }
        "file_delete" -> {
            val path = args.optString("path")
            "删除了文件/目录: $path"
        }
        "file_info" -> {
            val path = args.optString("path")
            "查看了路径信息: $path"
        }
        "file_search" -> {
            val directory = args.optString("directory").ifEmpty { "根目录" }
            val namePattern = args.optString("namePattern")
            val contentQuery = args.optString("contentQuery")
            val details = mutableListOf<String>()
            if (namePattern.isNotEmpty()) details.add("文件名匹配: $namePattern")
            if (contentQuery.isNotEmpty()) details.add("内容匹配: $contentQuery")
            "搜索了目录 $directory" + (if (details.isNotEmpty()) " (${details.joinToString(", ")})" else "")
        }
        "search_memory" -> {
            val query = args.optString("query")
            "检索了记忆库: \"$query\""
        }
        "adjust_ui" -> "修改了界面 UI 样式配置"
        "adjust_font" -> "调整了界面字体和字号"
        "apply_color_scheme" -> "应用了配色方案: ${args.optString("schemeId")}"
        "save_color_scheme" -> "保存了配色方案: ${args.optString("name")}"
        "delete_color_scheme" -> "删除了配色方案: ${args.optString("schemeId")}"
        "create_agents" -> {
            val agents = args.optJSONArray("agents")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            } ?: emptyList()
            "创建了 Sub-Agent 团队: ${agents.joinToString(", ")}"
        }
        "assign_task" -> {
            val to = args.optString("to")
            "向 $to 分配了新任务"
        }
        "continue_conversation" -> {
            val to = args.optString("to")
            "与 $to 继续对话"
        }
        "peer_message" -> {
            val to = args.optString("to")
            "向 $to 发送了 Agent 间协作消息"
        }
        "get_current_time" -> "获取了当前系统时间"
        "get_ui_capabilities" -> "获取了 UI 配置参数范围"
        "reset_ui_to_default" -> "重置了 UI 界面配色为默认"
        "reset_font_to_default" -> "重置了字体设置为默认"
        "list_color_schemes" -> "列出了已保存的配色方案"
        "set_ui_texts" -> "更新了 UI 界面自定义文本"
        "list_ui_texts" -> "查询了 UI 文字标签列表"
        else -> {
            val path = args.optString("path").takeIf { it.isNotEmpty() }
                ?: args.optString("file").takeIf { it.isNotEmpty() }
                ?: args.optString("filePath").takeIf { it.isNotEmpty() }
                ?: args.optString("dir").takeIf { it.isNotEmpty() }
                ?: args.optString("directory").takeIf { it.isNotEmpty() }
            
            val query = args.optString("query").takeIf { it.isNotEmpty() }
            val url = args.optString("url").takeIf { it.isNotEmpty() }
            val cmd = args.optString("command").takeIf { it.isNotEmpty() }
            
            when {
                path != null -> "对路径 \"$path\" 执行了操作"
                query != null -> "检索了: \"$query\""
                url != null -> "访问了 URL: $url"
                cmd != null -> "执行了系统命令: $cmd"
                else -> {
                    val keys = args.keys()
                    val params = mutableListOf<String>()
                    var count = 0
                    while (keys.hasNext() && count < 2) {
                        val k = keys.next()
                        val v = args.opt(k)?.toString() ?: ""
                        if (v.isNotEmpty() && v != "{}") {
                            val shortVal = if (v.length > 15) v.take(15) + "..." else v
                            params.add("$k=$shortVal")
                            count++
                        }
                    }
                    if (params.isNotEmpty()) {
                        "参数: ${params.joinToString(", ")}"
                    } else {
                        "执行了该工具"
                    }
                }
            }
        }
    }
}

@Composable
fun ToolGroupCard(
    messages: List<UIModelToolMessage>,
    allMessages: List<UIModelToolMessage>,
    modifier: Modifier = Modifier
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacingMultiplier = uiSettings.spacingMultiplier
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    val lookup = remember(allMessages) { buildToolCallLookup(allMessages) }
    val totalCount = messages.size
    
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(uiSettings.cornerRadiusDp.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp * spacingMultiplier)
    ) {
        Column(
            modifier = Modifier.padding(12.dp * spacingMultiplier)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (totalCount > 1) {
                        uiText("chat.tools_used_count", "已调用 %d 个工具").format(totalCount)
                    } else {
                        uiText("chat.tool_used", "已调用工具")
                    },
                    fontSize = (13 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Summaries list (visible when collapsed or expanded, but in different details)
            AnimatedVisibility(
                visible = !isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    messages.forEach { msg ->
                        val info = lookup[msg.toolCallId]
                        val name = info?.name ?: "unknown"
                        val summaryText = if (info != null) {
                            formatToolCallSummary(info.name, info.arguments)
                        } else {
                            "工具 ID: ${msg.toolCallId?.take(8) ?: "unknown"}"
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = getToolIcon(name),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "调用工具 $name: $summaryText",
                                fontSize = (11.5f * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = resolvedFontFamily
                            )
                        }
                    }
                }
            }

            // Detailed view when expanded
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp * spacingMultiplier)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)

                    messages.forEachIndexed { index, msg ->
                        val info = lookup[msg.toolCallId]
                        val name = info?.name ?: "unknown"
                        val summaryText = if (info != null) {
                            formatToolCallSummary(info.name, info.arguments)
                        } else {
                            "工具 ID: ${msg.toolCallId ?: "unknown"}"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape((uiSettings.cornerRadiusDp - 4).coerceAtLeast(4).dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(10.dp)
                        ) {
                            // Single Tool Header
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = getToolIcon(name),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "调用工具 $name",
                                    fontSize = (12.5f * fs).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "ID: ${msg.toolCallId?.take(8) ?: "unknown"}",
                                    fontSize = (9 * fs).sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = summaryText,
                                fontSize = (12 * fs).sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = resolvedFontFamily
                            )

                            // Collapsible Args
                            if (info != null && info.arguments.length() > 0) {
                                var showArgs by remember { mutableStateOf(false) }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .clickable { showArgs = !showArgs }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showArgs) "收起输入参数" else "查看输入参数",
                                        fontSize = (10.5f * fs).sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = if (showArgs) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                AnimatedVisibility(visible = showArgs) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = info.arguments.toString(2),
                                            fontSize = (10.5f * fs).sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }

                            // Collapsible Results
                            var showResults by remember { mutableStateOf(false) }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .clickable { showResults = !showResults }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showResults) "收起执行结果" else "查看执行结果",
                                    fontSize = (10.5f * fs).sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = if (showResults) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            AnimatedVisibility(visible = showResults) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = msg.content,
                                        fontSize = (10.5f * fs).sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
