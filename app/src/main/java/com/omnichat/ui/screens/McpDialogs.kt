package com.omnichat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.omnichat.data.McpServer
import com.omnichat.mcp.McpTool
import com.omnichat.mcp.McpViewModel
import com.omnichat.R
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.uiText

// ── 工具列表弹窗 ──────────────────────────────────────────────────────────

@Composable
fun McpToolsDialog(
    serverName: String,
    tools: List<McpTool>,
    onDismiss: () -> Unit
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiText("mcp.dialog.tools.title", R.string.mcp_dialog_tools_title).format(serverName),
                        fontSize = (16 * fs).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = resolvedFontFamily,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(8.dp))

                if (tools.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(uiText("mcp.dialog.2df0bd31", R.string.mcp_dialog_no_tools), fontFamily = resolvedFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tools) { tool ->
                            McpToolItem(tool)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun McpToolItem(tool: McpTool) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = (uiSettings.cornerRadiusDp * 0.7f).coerceAtLeast(6f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp)
    ) {
        Text(
            text = tool.name,
            fontSize = (13 * fs).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        if (tool.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = tool.description,
                fontSize = (12 * fs).sp,
                fontFamily = resolvedFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun McpImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxSize()
            ) {
                Text(
                    text = uiText("mcp.dialog.ea4cb678", R.string.mcp_dialog_import_title),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = uiText("mcp.dialog.79180a54", R.string.mcp_dialog_import_desc),
                    fontSize = (12 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { 
                        jsonText = it
                        isError = false
                    },
                    placeholder = { 
                        Text(
                            "{\n  \"mcpServers\": {\n    \"example\": {\n      \"command\": \"https://example.com/mcp/sse\",\n      \"args\": []\n    }\n  }\n}",
                            fontSize = (11 * fs).sp
                        ) 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (11 * fs).sp
                    ),
                    isError = isError,
                    supportingText = if (isError) {
                        { Text(uiText("mcp.dialog.23cc670c", R.string.mcp_dialog_invalid_json), fontFamily = resolvedFontFamily) }
                    } else null
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                    ) { Text(uiText("mcp.dialog.e972261b", R.string.mcp_dialog_cancel), fontFamily = resolvedFontFamily) }

                    Button(
                        onClick = {
                            try {
                                val obj = org.json.JSONObject(jsonText)
                                if (obj.has("mcpServers")) {
                                    onImport(jsonText)
                                } else {
                                    isError = true
                                }
                            } catch (e: Exception) {
                                isError = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = jsonText.isNotBlank(),
                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                    ) { Text(uiText("mcp.dialog.521cea1b", R.string.mcp_dialog_confirm_import), fontFamily = resolvedFontFamily) }
                }
            }
        }
    }
}

// ── 添加/编辑对话框 ───────────────────────────────────────────────────────

@Composable
fun McpServerEditDialog(
    server: McpServer?,
    mcpViewModel: McpViewModel = viewModel(),
    onDismiss: () -> Unit,
    onSave: (McpServer) -> Unit
) {
    val isEdit = server != null

    var name by remember { mutableStateOf(server?.name ?: "") }
    var command by remember { mutableStateOf(server?.command ?: "") }
    var args by remember { mutableStateOf(server?.args ?: "[]") }
    var env by remember { mutableStateOf(server?.env ?: "{}") }
    var isEnabled by remember { mutableStateOf(server?.isEnabled ?: true) }

    var argsError by remember { mutableStateOf(false) }
    var envError by remember { mutableStateOf(false) }

    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题
                Text(
                    text = if (isEdit) uiText("mcp.dialog.edit.title", R.string.mcp_dialog_edit_title) else uiText("mcp.dialog.add.title", R.string.mcp_dialog_add_title),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 服务名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(uiText("mcp.dialog.a9c7eb71", R.string.mcp_dialog_service_name), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(uiText("mcp.dialog.53eae47f", R.string.mcp_dialog_service_name_hint), fontFamily = resolvedFontFamily) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = resolvedFontFamily)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 命令/入口
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(uiText("mcp.dialog.command.label.remote.http", R.string.mcp_dialog_command_label_remote_http), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(uiText("mcp.dialog.command.placeholder.remote.http", R.string.mcp_dialog_command_placeholder_remote_http), fontFamily = resolvedFontFamily) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 参数（JSON 数组）
                OutlinedTextField(
                    value = args,
                    onValueChange = {
                        args = it
                        argsError = !isValidJsonArray(it)
                    },
                    label = { Text(uiText("mcp.dialog.4eee8fef", R.string.mcp_dialog_params), fontFamily = resolvedFontFamily) },
                    placeholder = { Text("[\"--port\", \"3000\"]", fontFamily = resolvedFontFamily) },
                    isError = argsError,
                    supportingText = if (argsError) {
                        { Text(uiText("mcp.dialog.invalid.json.array", R.string.mcp_dialog_invalid_json_array), fontFamily = resolvedFontFamily) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 环境变量（JSON 对象）/ 远程 HTTP 请求头
                OutlinedTextField(
                    value = env,
                    onValueChange = {
                        env = it
                        envError = !isValidJsonObject(it)
                    },
                    label = {
                        Text(uiText("mcp.dialog.custom.headers", R.string.mcp_dialog_custom_headers), fontFamily = resolvedFontFamily)
                    },
                    placeholder = {
                        Text("{\"Authorization\": \"Bearer token\", \"X-Api-Key\": \"xxx\"}", fontFamily = resolvedFontFamily)
                    },
                    isError = envError,
                    supportingText = if (envError) {
                        { Text(uiText("mcp.dialog.invalid.json.object", R.string.mcp_dialog_invalid_json_object), fontFamily = resolvedFontFamily) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 启用开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiText("mcp.dialog.500fbcfe", R.string.mcp_dialog_auto_start),
                        fontSize = (14 * fs).sp,
                        fontFamily = resolvedFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                    ) { Text(uiText("mcp.dialog.e972261b", R.string.mcp_dialog_cancel), fontFamily = resolvedFontFamily) }

                    Button(
                        onClick = {
                            val saved = McpServer(
                                id = server?.id ?: 0,
                                name = name.trim(),
                                command = command.trim(),
                                args = args.trim().ifBlank { "[]" },
                                env = env.trim().ifBlank { "{}" },
                                isEnabled = isEnabled,
                                createdAt = server?.createdAt ?: System.currentTimeMillis()
                            )
                            onSave(saved)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && command.isNotBlank() && !argsError && !envError,
                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                    ) { Text(if (isEdit) uiText("action.save", R.string.action_save) else uiText("action.add", R.string.action_add), fontFamily = resolvedFontFamily) }
                }
            }
        }
    }
}

// ── 快速示例 Chips ────────────────────────────────────────────────────────

@Composable
fun McpExampleChips(onAdd: (McpServer) -> Unit) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    val examples = listOf(
        McpServer(
            name = uiText("mcp.example.remote", R.string.mcp_example_remote),
            command = "https://mcp-server-example.vercel.app/sse",
            args = "[]",
            env = "{}"
        )
    )
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        examples.forEach { example ->
            OutlinedButton(
                onClick = { onAdd(example) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(cornerRadius),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                RuntimeBadge()
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(example.name, fontSize = (13 * fs).sp, fontWeight = FontWeight.Medium, fontFamily = resolvedFontFamily)
                    Text(
                        example.command,
                        fontSize = (10 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────

fun isValidJsonArray(s: String): Boolean {
    return try {
        org.json.JSONArray(s.trim())
        true
    } catch (e: Exception) {
        false
    }
}

fun isValidJsonObject(s: String): Boolean {
    return try {
        org.json.JSONObject(s.trim())
        true
    } catch (e: Exception) {
        false
    }
}
