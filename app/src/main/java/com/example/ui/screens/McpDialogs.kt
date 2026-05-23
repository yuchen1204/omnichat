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
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.uiText

// ── 工具列表弹窗 ──────────────────────────────────────────────────────────

@Composable
fun McpToolsDialog(
    serverName: String,
    tools: List<McpTool>,
    onDismiss: () -> Unit
) {
    val fs = LocalUISettings.current.fontSizeScale
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiText("mcp.dialog.tools_title", "%s 的工具").format(serverName),
                        fontSize = (16 * fs).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (tools.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(uiText("mcp.dialog.2df0bd31", "暂无工具"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val fs = LocalUISettings.current.fontSizeScale
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
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
    val fs = LocalUISettings.current.fontSizeScale

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxSize()
            ) {
                Text(
                    text = uiText("mcp.dialog.ea4cb678", "导入 MCP 配置 (JSON)"),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = uiText("mcp.dialog.79180a54", "粘贴标准的 mcpServers JSON 配置。导入后将自动添加并尝试启动服务。"),
                    fontSize = (12 * fs).sp,
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
                        { Text(uiText("mcp.dialog.23cc670c", "无效的 JSON 格式或缺少 mcpServers 字段")) }
                    } else null
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) { Text(uiText("mcp.dialog.e972261b", "取消")) }
                    
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
                        enabled = jsonText.isNotBlank()
                    ) { Text(uiText("mcp.dialog.521cea1b", "确认导入")) }
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

    val fs = LocalUISettings.current.fontSizeScale

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题
                Text(
                    text = if (isEdit) uiText("mcp.dialog.edit_title", "编辑 MCP 服务") else uiText("mcp.dialog.add_title", "添加 MCP 服务"),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 服务名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(uiText("mcp.dialog.a9c7eb71", "服务名称")) },
                    placeholder = { Text(uiText("mcp.dialog.53eae47f", "例如：文件系统服务")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 运行时选择
                Text(
                    text = uiText("mcp.dialog.8436d4b3", "运行时"),
                    fontSize = (13 * fs).sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                RuntimeSelector(selected = runtime, onSelect = { runtime = it })
                Spacer(modifier = Modifier.height(12.dp))

                // 运行时说明
                RuntimeHint(
                    runtime = runtime,
                    mcpWorkDir = mcpWorkDir
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 命令/入口
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(commandLabel(runtime)) },
                    placeholder = { Text(commandPlaceholder(runtime)) },
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
                    label = { Text(uiText("mcp.dialog.4eee8fef", "参数 (JSON 数组)")) },
                    placeholder = { Text("[\"--port\", \"3000\"]") },
                    isError = argsError,
                    supportingText = if (argsError) {
                        { Text(uiText("mcp.dialog.invalid_json_array", "请输入合法的 JSON 数组，例如 [\"arg1\", \"arg2\"]")) }
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
                        Text(if (runtime == "remote_http") uiText("mcp.dialog.custom_headers", "自定义请求头 (JSON 对象)") else uiText("mcp.dialog.env_vars", "环境变量 (JSON 对象)"))
                    },
                    placeholder = {
                        Text(
                            if (runtime == "remote_http")
                                "{\"Authorization\": \"Bearer token\", \"X-Api-Key\": \"xxx\"}"
                            else
                                "{\"API_KEY\": \"your-key\"}"
                        )
                    },
                    isError = envError,
                    supportingText = if (envError) {
                        { Text(uiText("mcp.dialog.invalid_json_object", "请输入合法的 JSON 对象，例如 {\"KEY\": \"value\"}")) }
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
                        text = uiText("mcp.dialog.500fbcfe", "启动时自动运行"),
                        fontSize = (14 * fs).sp,
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
                        modifier = Modifier.weight(1f)
                    ) { Text(uiText("mcp.dialog.e972261b", "取消")) }

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
                        enabled = name.isNotBlank() && command.isNotBlank() && !argsError && !envError
                    ) { Text(if (isEdit) uiText("action.save", "保存") else uiText("action.add", "添加")) }
                }
            }
        }
    }
}

// ── 运行时选择器 ──────────────────────────────────────────────────────────

@Composable
fun RuntimeSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("node", "Node.js", "内嵌 Node.js"),
        Triple("python", "Python", "内嵌 Python"),
        Triple("remote_http", "远程 HTTP", "远程 MCP 服务")
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
                label = { Text(label, fontSize = (11 * fs).sp) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun RuntimeHint(
    runtime: String,
    mcpWorkDir: String = ""
) {
    val nodeHint = uiText(
        "mcp.dialog.node_hint",
        "使用 app 内嵌的 Node.js 运行时执行 JS MCP server，无需设备安装 Node.js。\n内置脚本已自动部署到：\n%s\ncommand 填文件名（如 mcp_filesystem.js）或绝对路径均可。"
    ).format(mcpWorkDir)
    val pythonHint = uiText(
        "mcp.dialog.python_hint",
        "使用 app 内嵌的 Python 运行时执行 Python MCP server，无需设备安装 Python"
    )
    val remoteHttpHint = uiText(
        "mcp.dialog.remote_http_hint",
        "连接到远程 MCP server。本应用遵循 MCP HTTP 传输标准（SSE + POST）。\ncommand 请填写 SSE 端点 URL。\n自定义请求头字段可填写需要附加的 HTTP 请求头，例如认证 Token。"
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
    val examples = listOf(
        McpServer(
            name = "文件系统",
            runtime = "node",
            command = "mcp_filesystem.js",
            args = "[\"/sdcard\"]",
            env = "{}"
        ),
        McpServer(
            name = "HTTP Fetch",
            runtime = "node",
            command = "mcp_fetch.js",
            args = "[]",
            env = "{}"
        ),
        McpServer(
            name = "远程示例",
            runtime = "remote_http",
            command = "https://mcp-server-example.vercel.app/sse",
            args = "[]",
            env = "{}"
        )
    )
    val fs = LocalUISettings.current.fontSizeScale
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        examples.forEach { example ->
            OutlinedButton(
                onClick = { onAdd(example) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                RuntimeBadge(runtime = example.runtime)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(example.name, fontSize = (13 * fs).sp, fontWeight = FontWeight.Medium)
                    Text(
                        example.command,
                        fontSize = (10 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────

fun commandLabel(runtime: String) = when (runtime) {
    "node" -> "JS 入口文件路径"
    "python" -> "Python 脚本路径"
    "remote_http" -> "SSE 端点 URL"
    else -> "命令"
}

fun commandPlaceholder(runtime: String) = when (runtime) {
    "node" -> "mcp_filesystem.js  （或绝对路径）"
    "python" -> "/sdcard/OmniChat/mcp/my_server.py"
    "remote_http" -> "https://example.com/mcp/sse"
    else -> "命令或路径"
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
    pythonStatus: String,
    onDismiss: () -> Unit
) {
    val fs = LocalUISettings.current.fontSizeScale
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        uiText("mcp.dialog.runtime_details_title", "运行时状态详情"),
                        fontSize = (17 * fs).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Node.js 状态
                RuntimeInfoSection(
                    title = uiText("mcp.dialog.node_title", "内嵌 Node.js"),
                    isReady = isNodeAvailable,
                    statusText = if (isNodeAvailable) uiText("mcp.dialog.node_ok", "libnode.so 已加载，Node.js 可用")
                                 else uiText("mcp.dialog.node_missing", "libnode.so 未找到"),
                    instructions = if (!isNodeAvailable) uiText("mcp.dialog.node_instructions", "请将 libnode.so 放入 app/src/main/jniLibs/<ABI>/ 目录并重新编译。") else null
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Python 状态
                RuntimeInfoSection(
                    title = uiText("mcp.dialog.python_title", "内嵌 Python"),
                    isReady = isPythonReady,
                    statusText = pythonStatus,
                    instructions = if (!isPythonReady) uiText("mcp.dialog.python_instructions", "请准备 stdlib.zip 和 .so 文件并重新编译。") else null
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 远程 HTTP 状态
                RuntimeInfoSection(
                    title = uiText("mcp.dialog.remote_http_title", "远程 HTTP MCP"),
                    isReady = true,
                    statusText = uiText("mcp.dialog.remote_http_status", "支持通过 HTTP/HTTPS 连接远程 MCP 服务。"),
                    instructions = uiText("mcp.dialog.remote_http_instructions", "远程服务需要遵循 MCP HTTP 传输标准，支持 SSE 端点和 JSON-RPC POST 请求。")
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(uiText("mcp.dialog.c0fd3fa0", "知道了")) }
            }
        }
    }
}

@Composable
fun RuntimeInfoSection(
    title: String,
    isReady: Boolean,
    statusText: String,
    instructions: String?
) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val color = if (isReady) successColor else MaterialTheme.colorScheme.error
    val bgColor = if (isReady) color.copy(alpha = 0.08f)
                  else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
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
                if (isReady) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                title,
                fontSize = (14 * fs).sp,
                fontWeight = FontWeight.SemiBold,
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
                uiText("mcp.dialog.install_steps", "安装步骤："),
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
