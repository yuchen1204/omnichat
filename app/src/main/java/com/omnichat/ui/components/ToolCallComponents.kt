package com.omnichat.ui.components

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.omnichat.R
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.uiText
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

fun com.omnichat.data.Message.toUIModel() = UIModelToolMessage(role, content, toolCallId, toolCallsJson, timestamp)

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
        "adjust_ui", "color_scheme" -> Icons.Default.Settings
        else -> Icons.Default.Build
    }
}

fun formatToolCallSummary(context: Context, name: String, args: JSONObject): String {
    return when (name) {
        "file_list" -> {
            val path = args.optString("path").ifEmpty { context.getString(R.string.tool_call_root_directory) }
            context.getString(R.string.tool_call_browsed_dir, path)
        }
        "file_read" -> {
            val path = args.optString("path")
            context.getString(R.string.tool_call_read_file, path)
        }
        "file_write" -> {
            val path = args.optString("path")
            context.getString(R.string.tool_call_wrote_file, path)
        }
        "file_append" -> {
            val path = args.optString("path")
            context.getString(R.string.tool_call_appended_file, path)
        }
        "file_delete" -> {
            val path = args.optString("path")
            context.getString(R.string.tool_call_deleted_file, path)
        }
        "file_info" -> {
            val path = args.optString("path")
            context.getString(R.string.tool_call_file_info, path)
        }
        "file_search" -> {
            val directory = args.optString("directory").ifEmpty { context.getString(R.string.tool_call_root_directory) }
            val namePattern = args.optString("namePattern")
            val contentQuery = args.optString("contentQuery")
            val details = mutableListOf<String>()
            if (namePattern.isNotEmpty()) details.add(context.getString(R.string.tool_call_filename_match, namePattern))
            if (contentQuery.isNotEmpty()) details.add(context.getString(R.string.tool_call_content_match, contentQuery))
            context.getString(R.string.tool_call_searched_dir, directory) + (if (details.isNotEmpty()) " (${details.joinToString(", ")})" else "")
        }
        "search_memory" -> {
            val query = args.optString("query")
            context.getString(R.string.tool_call_searched_memory, query)
        }
        "adjust_ui" -> context.getString(R.string.tool_call_adjusted_ui)
        "color_scheme" -> {
            when (args.optString("action")) {
                "save" -> context.getString(R.string.tool_call_saved_scheme, args.optString("name"))
                "apply" -> context.getString(R.string.tool_call_applied_scheme, args.optString("schemeId"))
                "delete" -> context.getString(R.string.tool_call_deleted_scheme, args.optString("schemeId"))
                "list" -> context.getString(R.string.tool_call_listed_schemes)
                else -> context.getString(R.string.tool_call_scheme_action)
            }
        }
        "get_current_time" -> context.getString(R.string.tool_call_got_time)
        "get_ui_capabilities" -> context.getString(R.string.tool_call_got_ui_caps)
        "reset_ui_to_default" -> context.getString(R.string.tool_call_reset_ui)
        "set_ui_texts" -> context.getString(R.string.tool_call_updated_texts)
        "list_ui_texts" -> context.getString(R.string.tool_call_listed_texts)
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
                path != null -> context.getString(R.string.tool_call_path_action, path)
                query != null -> context.getString(R.string.tool_call_queried, query)
                url != null -> context.getString(R.string.tool_call_visited_url, url)
                cmd != null -> context.getString(R.string.tool_call_executed_cmd, cmd)
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
                        context.getString(R.string.tool_call_params, params.joinToString(", "))
                    } else {
                        context.getString(R.string.tool_call_executed)
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
    val context = LocalContext.current

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
                        uiText("chat.tools_used_count", "Used %d tools").format(totalCount)
                    } else {
                        uiText("chat.tool_used", "Tool used")
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
                            formatToolCallSummary(context, info.name, info.arguments)
                        } else {
                            context.getString(R.string.tool_call_tool_id, msg.toolCallId?.take(8) ?: "unknown")
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
                                text = context.getString(R.string.tool_call_called_tool, name, summaryText),
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
                            formatToolCallSummary(context, info.name, info.arguments)
                        } else {
                            context.getString(R.string.tool_call_tool_id, msg.toolCallId ?: "unknown")
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
                                    text = context.getString(R.string.tool_call_tool_name, name),
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
                                        text = if (showArgs) context.getString(R.string.tool_call_collapse_args) else context.getString(R.string.tool_call_expand_args),
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
                                    text = if (showResults) context.getString(R.string.tool_call_collapse_result) else context.getString(R.string.tool_call_expand_result),
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
