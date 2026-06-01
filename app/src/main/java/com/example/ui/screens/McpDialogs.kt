package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.data.McpServer
import com.example.mcp.McpTool
import com.example.mcp.McpViewModel
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily

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
                        text = stringResource(R.string.mcp_dialog_tools_title, serverName),
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
                        Text(stringResource(R.string.mcp_dialog_no_tools), fontFamily = resolvedFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    text = stringResource(R.string.mcp_dialog_import_title),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.mcp_dialog_import_desc),
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
                        { Text(stringResource(R.string.mcp_dialog_invalid_json), fontFamily = resolvedFontFamily) }
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
                    ) { Text(stringResource(R.string.mcp_dialog_cancel), fontFamily = resolvedFontFamily) }

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
                    ) { Text(stringResource(R.string.mcp_dialog_confirm_import), fontFamily = resolvedFontFamily) }
                }
            }
        }
    }
}

// ── 添加/编辑对话框 ───────────────────────────────────────────────────────

@Composable
fun McpServerEditDialog(
    server: McpServer?,
    mcpWorkDir: String = "",
    mcpViewModel: McpViewModel = viewModel(),
    onDismiss: () -> Unit,
    onSave: (McpServer) -> Unit
) {
    val isEdit = server != null

    var name by remember { mutableStateOf(server?.name ?: "") }
    var runtime by remember { mutableStateOf(server?.runtime ?: "node") }
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
                    text = if (isEdit) stringResource(R.string.mcp_dialog_edit_title) else stringResource(R.string.mcp_dialog_add_title),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 服务名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_dialog_service_name), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(stringResource(R.string.mcp_dialog_service_name_hint), fontFamily = resolvedFontFamily) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = resolvedFontFamily)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 运行时选择
                Text(
                    text = stringResource(R.string.mcp_dialog_runtime),
                    fontSize = (13 * fs).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                RuntimeSelector(selected = runtime, resolvedFontFamily = resolvedFontFamily, onSelect = { runtime = it })
                Spacer(modifier = Modifier.height(12.dp))

                // 运行时说明
                RuntimeHint(
                    runtime = runtime,
                    resolvedFontFamily = resolvedFontFamily,
                    mcpWorkDir = mcpWorkDir
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 命令/入口
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(commandLabel(runtime), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(commandPlaceholder(runtime), fontFamily = resolvedFontFamily) },
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
                    label = { Text(stringResource(R.string.mcp_dialog_params), fontFamily = resolvedFontFamily) },
                    placeholder = { Text("[\"--port\", \"3000\"]", fontFamily = resolvedFontFamily) },
                    isError = argsError,
                    supportingText = if (argsError) {
                        { Text(stringResource(R.string.mcp_dialog_invalid_json_array), fontFamily = resolvedFontFamily) }
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
                        Text(
                            text = if (runtime == "remote_http") stringResource(R.string.mcp_dialog_custom_headers)
                                   else stringResource(R.string.mcp_dialog_env_vars),
                            fontFamily = resolvedFontFamily
                        )
                    },
                    placeholder = {
                        Text(
                            text = if (runtime == "remote_http") "{\"Authorization\": \"Bearer token\", \"X-Api-Key\": \"xxx\"}"
                                   else "{\"API_KEY\": \"your-key\"}",
                            fontFamily = resolvedFontFamily
                        )
                    },
                    isError = envError,
                    supportingText = if (envError) {
                        { Text(stringResource(R.string.mcp_dialog_invalid_json_object), fontFamily = resolvedFontFamily) }
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
                        text = stringResource(R.string.mcp_dialog_auto_start),
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
                    ) { Text(stringResource(R.string.mcp_dialog_cancel), fontFamily = resolvedFontFamily) }

                    Button(
                        onClick = {
                            val saved = McpServer(
                                id = server?.id ?: 0,
                                name = name.trim(),
                                runtime = runtime,
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
                    ) { Text(if (isEdit) stringResource(R.string.action_save) else stringResource(R.string.action_add), fontFamily = resolvedFontFamily) }
                }
            }
        }
    }
}

// ── 运行时选择器 ──────────────────────────────────────────────────────────

@Composable
fun RuntimeSelector(
    selected: String,
    resolvedFontFamily: FontFamily,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        Triple("node", stringResource(R.string.mcp_dialog_runtime_node), stringResource(R.string.mcp_dialog_runtime_node_desc)),
        Triple("python", stringResource(R.string.mcp_dialog_runtime_python), stringResource(R.string.mcp_dialog_runtime_python_desc)),
        Triple("remote_http", stringResource(R.string.mcp_dialog_runtime_remote_http), stringResource(R.string.mcp_dialog_runtime_remote_http_desc))
    )
    val fs = LocalUISettings.current.fontSizeScale
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label, _) ->
            val isSelected = selected == value
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(value) },
                label = { Text(label, fontSize = (11 * fs).sp, fontFamily = resolvedFontFamily) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun RuntimeHint(
    runtime: String,
    resolvedFontFamily: FontFamily,
    mcpWorkDir: String = ""
) {
    val nodeHint = stringResource(
        R.string.mcp_dialog_node_hint,
        mcpWorkDir
    )
    val pythonHint = stringResource(
        R.string.mcp_dialog_python_hint
    )
    val remoteHttpHint = stringResource(
        R.string.mcp_dialog_remote_http_hint
    )

    val (icon, text, isError) = when (runtime) {
        "node" -> Triple(Icons.Default.Info, nodeHint, false)
        "python" -> Triple(Icons.Default.Info, pythonHint, false)
        "remote_http" -> Triple(Icons.Default.Info, remoteHttpHint, false)
        else -> Triple(Icons.Default.Info, "", false)
    }
    if (text.isNotBlank()) {
        val colorScheme = MaterialTheme.colorScheme
        val containerColor = if (isError) colorScheme.errorContainer.copy(alpha = 0.4f)
                            else colorScheme.surfaceVariant.copy(alpha = 0.6f)
        val contentColor = if (isError) colorScheme.onErrorContainer
                          else colorScheme.onSurfaceVariant
        val fs = LocalUISettings.current.fontSizeScale

        Surface(
            color = containerColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(14.dp).padding(top = 1.dp),
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    fontSize = (11 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor,
                    lineHeight = (16 * fs).sp
                )
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
            name = stringResource(R.string.mcp_example_fetch),
            runtime = "node",
            command = "mcp_fetch.js",
            args = "[]",
            env = "{}"
        ),
        McpServer(
            name = stringResource(R.string.mcp_example_remote),
            runtime = "remote_http",
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
                RuntimeBadge(runtime = example.runtime)
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

@Composable
fun commandLabel(runtime: String) = when (runtime) {
    "node" -> stringResource(R.string.mcp_dialog_command_label_node)
    "python" -> stringResource(R.string.mcp_dialog_command_label_python)
    "remote_http" -> stringResource(R.string.mcp_dialog_command_label_remote_http)
    else -> stringResource(R.string.mcp_dialog_command_label_default)
}

@Composable
fun commandPlaceholder(runtime: String) = when (runtime) {
    "node" -> stringResource(R.string.mcp_dialog_command_placeholder_node)
    "python" -> stringResource(R.string.mcp_dialog_command_placeholder_python)
    "remote_http" -> stringResource(R.string.mcp_dialog_command_placeholder_remote_http)
    else -> stringResource(R.string.mcp_dialog_command_placeholder_default)
}

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

// ── 运行时信息弹窗 ────────────────────────────────────────────────────────

@Composable
fun RuntimeInfoDialog(
    isNodeAvailable: Boolean,
    isPythonReady: Boolean,
    isNodeEnabled: Boolean,
    isPythonEnabled: Boolean,
    pythonStatus: String,
    onDismiss: () -> Unit
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mcp_dialog_runtime_details_title),
                        fontSize = (17 * fs).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = resolvedFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Node.js 状态
                RuntimeInfoSection(
                    title = stringResource(R.string.mcp_dialog_node_title),
                    isReady = isNodeAvailable,
                    isEnabled = isNodeEnabled,
                    statusText = if (!isNodeEnabled) stringResource(R.string.mcp_dialog_node_disabled)
                                 else if (isNodeAvailable) stringResource(R.string.mcp_dialog_node_ok)
                                 else stringResource(R.string.mcp_dialog_node_missing),
                    instructions = if (!isNodeAvailable && isNodeEnabled) stringResource(R.string.mcp_dialog_node_instructions) else null,
                    resolvedFontFamily = resolvedFontFamily
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Python 状态
                RuntimeInfoSection(
                    title = stringResource(R.string.mcp_dialog_python_title),
                    isReady = isPythonReady,
                    isEnabled = isPythonEnabled,
                    statusText = if (!isPythonEnabled) stringResource(R.string.mcp_dialog_python_disabled) else pythonStatus,
                    instructions = if (!isPythonReady && isPythonEnabled) stringResource(R.string.mcp_dialog_python_instructions) else null,
                    resolvedFontFamily = resolvedFontFamily
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 远程 HTTP 状态
                RuntimeInfoSection(
                    title = stringResource(R.string.mcp_dialog_remote_http_title),
                    isReady = true,
                    statusText = stringResource(R.string.mcp_dialog_remote_http_status),
                    instructions = stringResource(R.string.mcp_dialog_remote_http_instructions),
                    resolvedFontFamily = resolvedFontFamily
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) { Text(stringResource(R.string.mcp_dialog_got_it), fontFamily = resolvedFontFamily) }
            }
        }
    }
}

@Composable
fun RuntimeInfoSection(
    title: String,
    isReady: Boolean,
    isEnabled: Boolean = true,
    statusText: String,
    instructions: String?,
    resolvedFontFamily: FontFamily
) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val color = when {
        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        isReady -> successColor
        else -> MaterialTheme.colorScheme.error
    }
    val bgColor = when {
        !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        isReady -> color.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
    }
    val fs = LocalUISettings.current.fontSizeScale

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    !isEnabled -> Icons.Default.Close
                    isReady -> Icons.Default.Check
                    else -> Icons.Default.Warning
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                title,
                fontSize = (14 * fs).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = resolvedFontFamily,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            statusText,
            fontSize = (12 * fs).sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = (16 * fs).sp
        )
        if (instructions != null) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = color.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.mcp_dialog_install_steps),
                fontSize = (11 * fs).sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                instructions,
                fontSize = (11 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                lineHeight = (16 * fs).sp
            )
        }
    }
}
