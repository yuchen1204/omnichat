package com.omnichat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.omnichat.data.Message
import com.omnichat.ui.presentation.ToolCallInfo
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.uiText
import org.json.JSONObject

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
    messages: List<Message>,
    lookup: Map<String, ToolCallInfo>,
    modifier: Modifier = Modifier
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacing = uiSettings.spacingMultiplier
    val fontFamily = resolveFontFamily(uiSettings.fontFamily)
    val context = LocalContext.current
    val totalCount = messages.size
    val firstMessage = messages.firstOrNull()
    val firstInfo = firstMessage?.toolCallId?.let(lookup::get)
    val collapsedSummary = firstInfo?.let {
        formatToolCallSummary(context, it.name, it.arguments)
    } ?: context.getString(R.string.tool_call_tool_id, firstMessage?.toolCallId?.take(8) ?: "unknown")
    var isExpanded by remember { mutableStateOf(false) }
    val outerShape = RoundedCornerShape(uiSettings.cornerRadiusDp.dp)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isExpanded) 0.35f else 0.55f),
        shape = outerShape,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = if (isExpanded) 1.dp else 0.dp,
        modifier = modifier
            .then(if (isExpanded) Modifier.fillMaxWidth() else Modifier.wrapContentWidth().widthIn(max = 360.dp))
            .padding(vertical = 3.dp * spacing)
    ) {
        Column(modifier = Modifier.padding((if (isExpanded) 12.dp else 8.dp) * spacing)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = if (isExpanded) 2.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = firstInfo?.let { getToolIcon(it.name) } ?: Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isExpanded) 17.dp else 16.dp)
                )
                Spacer(modifier = Modifier.width(7.dp * spacing))
                if (isExpanded) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (totalCount > 1) {
                                uiText("chat.tools_used_count", "Used %d tools").format(totalCount)
                            } else {
                                uiText("chat.tool_used", "Tool used")
                            },
                            fontSize = (13f * fs).sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = fontFamily
                        )
                        Text(
                            text = collapsedSummary,
                            fontSize = (10.5f * fs).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = fontFamily
                        )
                    }
                } else {
                    Text(
                        text = collapsedSummary,
                        fontSize = (11.5f * fs).sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = fontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    if (totalCount > 1) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = totalCount.toString(),
                                fontSize = (10f * fs).sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp * spacing),
                    verticalArrangement = Arrangement.spacedBy(8.dp * spacing)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), thickness = 0.5.dp)
                    messages.forEach { msg ->
                        val info = lookup[msg.toolCallId]
                        val name = info?.name ?: "unknown"
                        val summary = info?.let { formatToolCallSummary(context, it.name, it.arguments) }
                            ?: context.getString(R.string.tool_call_tool_id, msg.toolCallId ?: "unknown")
                        var showArgs by remember(msg.toolCallId) { mutableStateOf(false) }
                        var showResult by remember(msg.toolCallId) { mutableStateOf(false) }

                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                            shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 4).coerceAtLeast(4).dp),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp * spacing)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = getToolIcon(name),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = context.getString(R.string.tool_call_tool_name, name),
                                        fontSize = (12.5f * fs).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = msg.toolCallId?.take(8) ?: "unknown",
                                        fontSize = (9f * fs).sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp * spacing))
                                Text(
                                    text = summary,
                                    fontSize = (11.5f * fs).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = fontFamily
                                )

                                if (info != null && info.arguments.length() > 0) {
                                    Spacer(modifier = Modifier.height(6.dp * spacing))
                                    ToolDetailAction(
                                        label = if (showArgs) context.getString(R.string.tool_call_collapse_args) else context.getString(R.string.tool_call_expand_args),
                                        expanded = showArgs,
                                        fontSize = fs,
                                        onClick = { showArgs = !showArgs }
                                    )
                                    if (showArgs) {
                                        DetailText(
                                            text = info.arguments.toString(2),
                                            fontSize = fs,
                                            fontFamily = FontFamily.Monospace,
                                            maxHeight = 150.dp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp * spacing))
                                ToolDetailAction(
                                    label = if (showResult) context.getString(R.string.tool_call_collapse_result) else context.getString(R.string.tool_call_expand_result),
                                    expanded = showResult,
                                    fontSize = fs,
                                    onClick = { showResult = !showResult }
                                )
                                if (showResult) {
                                    DetailText(
                                        text = msg.content,
                                        fontSize = fs,
                                        fontFamily = FontFamily.Monospace,
                                        maxHeight = 180.dp
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

@Composable
private fun ToolDetailAction(
    label: String,
    expanded: Boolean,
    fontSize: Float,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = (10.5f * fontSize).sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun DetailText(
    text: String,
    fontSize: Float,
    fontFamily: FontFamily,
    maxHeight: androidx.compose.ui.unit.Dp
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .heightIn(max = maxHeight)
    ) {
        Text(
            text = text,
            fontSize = (10.5f * fontSize).sp,
            fontFamily = fontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}
