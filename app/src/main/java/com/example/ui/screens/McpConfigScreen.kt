package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.McpServer
import com.example.mcp.McpServerStatus
import com.example.mcp.McpTool
import com.example.mcp.McpViewModel

@Composable
fun McpConfigScreen(
    mcpViewModel: McpViewModel = viewModel()
) {
    val servers by mcpViewModel.mcpServers.collectAsStateWithLifecycle()
    val serverStates by mcpViewModel.serverStates.collectAsStateWithLifecycle()
    val allTools by mcpViewModel.allTools.collectAsStateWithLifecycle()
    val isDark = isSystemInDarkTheme()

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<McpServer?>(null) }
    var showToolsFor by remember { mutableStateOf<Long?>(null) }
    var showRuntimeInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── 运行时状态栏 ──────────────────────────────────────────────────
        RuntimeStatusBar(
            isNodeAvailable = mcpViewModel.isNodeRuntimeAvailable,
            isPythonReady = mcpViewModel.isPythonRuntimeReady,
            onInfoClick = { showRuntimeInfo = true }
        )

        // ── 顶部统计栏 ────────────────────────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val runningCount = serverStates.values.count { it.status == McpServerStatus.RUNNING }
                val toolCount = allTools.size

                StatChip(label = "服务", value = "${servers.size}", color = MaterialTheme.colorScheme.primary)
                StatChip(label = "运行中", value = "$runningCount", color = Color(0xFF34C759))
                StatChip(label = "工具", value = "$toolCount", color = Color(0xFFFF9500))

                Spacer(modifier = Modifier.weight(1f))

                // 添加按钮
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加服务", fontSize = 13.sp)
                }
            }
        }

        if (servers.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无 MCP 服务",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右上角「添加服务」配置 Node.js 或 Python MCP server",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // 快速添加示例
                    Text(
                        text = "常用示例",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    McpExampleChips { example ->
                        mcpViewModel.addServer(example)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    val state = serverStates[server.id]
                    McpServerCard(
                        server = server,
                        state = state,
                        onEdit = { editTarget = server },
                        onDelete = { mcpViewModel.deleteServer(server) },
                        onToggle = { mcpViewModel.toggleServer(server) },
                        onRestart = { mcpViewModel.restartServer(server) },
                        onShowTools = { showToolsFor = server.id }
                    )
                }

                // 底部添加按钮
                item {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加 MCP 服务")
                    }
                }
            }
        }
    }

    // ── 运行时信息弹窗 ────────────────────────────────────────────────────
    if (showRuntimeInfo) {
        RuntimeInfoDialog(
            isNodeAvailable = mcpViewModel.isNodeRuntimeAvailable,
            isPythonReady = mcpViewModel.isPythonRuntimeReady,
            pythonStatus = mcpViewModel.pythonRuntimeStatus,
            onDismiss = { showRuntimeInfo = false }
        )
    }

    // ── 添加/编辑对话框 ───────────────────────────────────────────────────
    if (showAddDialog) {
        McpServerEditDialog(
            server = null,
            mcpWorkDir = mcpViewModel.mcpWorkDir,
            onDismiss = { showAddDialog = false },
            onSave = { server ->
                mcpViewModel.addServer(server)
                showAddDialog = false
            }
        )
    }

    editTarget?.let { server ->
        McpServerEditDialog(
            server = server,
            mcpWorkDir = mcpViewModel.mcpWorkDir,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                mcpViewModel.updateServer(updated)
                editTarget = null
            }
        )
    }

    // ── 工具列表弹窗 ──────────────────────────────────────────────────────
    showToolsFor?.let { serverId ->
        val tools = allTools.filter { it.serverId == serverId }
        val serverName = servers.find { it.id == serverId }?.name ?: "未知"
        McpToolsDialog(
            serverName = serverName,
            tools = tools,
            onDismiss = { showToolsFor = null }
        )
    }
}

// ── 服务卡片 ──────────────────────────────────────────────────────────────

@Composable
private fun McpServerCard(
    server: McpServer,
    state: com.example.mcp.McpServerState?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onShowTools: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val status = state?.status ?: McpServerStatus.STOPPED
    val toolCount = state?.tools?.size ?: 0

    val statusColor = when (status) {
        McpServerStatus.RUNNING -> Color(0xFF34C759)
        McpServerStatus.STARTING -> Color(0xFFFF9500)
        McpServerStatus.ERROR -> Color(0xFFFF3B30)
        McpServerStatus.STOPPED -> Color(0xFF8E8E93)
    }
    val statusLabel = when (status) {
        McpServerStatus.RUNNING -> "运行中"
        McpServerStatus.STARTING -> "启动中"
        McpServerStatus.ERROR -> "错误"
        McpServerStatus.STOPPED -> "已停止"
    }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF1C1C1E) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 运行时图标
                RuntimeBadge(runtime = server.runtime)
                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = server.command,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // 状态指示点
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // 更多菜单
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑配置") },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("重启服务") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onRestart() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { showMenu = false; showDeleteConfirm = true }
                        )
                    }
                }
            }

            // 错误信息
            if (status == McpServerStatus.ERROR && state?.errorMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = state.errorMessage,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
                thickness = 0.5.dp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 底部操作行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 工具数量按钮
                if (status == McpServerStatus.RUNNING && toolCount > 0) {
                    OutlinedButton(
                        onClick = onShowTools,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.List, null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("$toolCount 个工具", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 启用/禁用开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (server.isEnabled) "已启用" else "已禁用",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = server.isEnabled,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.height(24.dp).width(44.dp),
                        thumbContent = null
                    )
                }
            }
        }
    }

    // 删除确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除 MCP 服务") },
            text = { Text("确定要删除「${server.name}」吗？该服务将被停止并从配置中移除。") },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ── 运行时徽章 ────────────────────────────────────────────────────────────

@Composable
private fun RuntimeBadge(runtime: String) {
    val (label, color) = when (runtime) {
        "node" -> "Node" to Color(0xFF68A063)
        "python" -> "Py" to Color(0xFF3572A5)
        "npx" -> "npx" to Color(0xFFCB3837)
        "uvx" -> "uvx" to Color(0xFF2B5B84)
        else -> runtime.take(3) to Color(0xFF8E8E93)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── 统计 Chip ─────────────────────────────────────────────────────────────

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$value $label",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── 工具列表弹窗 ──────────────────────────────────────────────────────────

@Composable
private fun McpToolsDialog(
    serverName: String,
    tools: List<McpTool>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$serverName 的工具",
                        fontSize = 16.sp,
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
                        Text("暂无工具", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun McpToolItem(tool: McpTool) {
    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
            .padding(10.dp)
    ) {
        Text(
            text = tool.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        if (tool.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = tool.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── 添加/编辑对话框 ───────────────────────────────────────────────────────

@Composable
private fun McpServerEditDialog(
    server: McpServer?,
    mcpWorkDir: String = "",
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

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题
                Text(
                    text = if (isEdit) "编辑 MCP 服务" else "添加 MCP 服务",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 服务名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("服务名称") },
                    placeholder = { Text("例如：文件系统服务") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 运行时选择
                Text(
                    text = "运行时",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                RuntimeSelector(selected = runtime, onSelect = { runtime = it })
                Spacer(modifier = Modifier.height(12.dp))

                // 运行时说明
                RuntimeHint(runtime = runtime, mcpWorkDir = mcpWorkDir)
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
                    label = { Text("参数 (JSON 数组)") },
                    placeholder = { Text("[\"--port\", \"3000\"]") },
                    isError = argsError,
                    supportingText = if (argsError) {
                        { Text("请输入合法的 JSON 数组，例如 [\"arg1\", \"arg2\"]") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 环境变量（JSON 对象）
                OutlinedTextField(
                    value = env,
                    onValueChange = {
                        env = it
                        envError = !isValidJsonObject(it)
                    },
                    label = { Text("环境变量 (JSON 对象)") },
                    placeholder = { Text("{\"API_KEY\": \"your-key\"}") },
                    isError = envError,
                    supportingText = if (envError) {
                        { Text("请输入合法的 JSON 对象，例如 {\"KEY\": \"value\"}") }
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
                        text = "启动时自动运行",
                        fontSize = 14.sp,
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
                    ) { Text("取消") }

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
                    ) { Text(if (isEdit) "保存" else "添加") }
                }
            }
        }
    }
}

// ── 运行时选择器 ──────────────────────────────────────────────────────────

@Composable
private fun RuntimeSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("node", "Node.js", "内嵌 Node.js 运行时"),
        Triple("python", "Python", "内嵌 Python 运行时"),
        Triple("npx", "npx", "系统 npx 命令"),
        Triple("uvx", "uvx", "系统 uvx 命令")
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label, _) ->
            val isSelected = selected == value
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(value) },
                label = { Text(label, fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RuntimeHint(runtime: String, mcpWorkDir: String = "") {
    val (icon, text) = when (runtime) {
        "node" -> Icons.Default.Info to
            "使用 app 内嵌的 Node.js 运行时执行 JS MCP server，无需设备安装 Node.js。\n" +
            "内置脚本已自动部署到：\n$mcpWorkDir\n" +
            "command 填文件名（如 mcp_filesystem.js）或绝对路径均可。"
        "python" -> Icons.Default.Info to "使用 app 内嵌的 Python 运行时（Chaquopy）执行 Python MCP server，无需设备安装 Python"
        "npx" -> Icons.Default.Warning to "需要设备已安装 Node.js 并可通过 PATH 访问 npx 命令。普通 Android 设备通常不具备此环境，建议在 Termux 或 root 环境下使用，或改用内嵌 Node.js 运行时。"
        "uvx" -> Icons.Default.Warning to "需要设备已安装 uv 工具链并可通过 PATH 访问 uvx 命令。普通 Android 设备通常不具备此环境，建议在 Termux 或 root 环境下使用，或改用内嵌 Python 运行时。"
        else -> Icons.Default.Info to ""
    }
    if (text.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(14.dp).padding(top = 1.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ── 快速示例 Chips ────────────────────────────────────────────────────────

@Composable
private fun McpExampleChips(onAdd: (McpServer) -> Unit) {
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
            name = "Python 脚本",
            runtime = "python",
            command = "/sdcard/OmniChat/mcp/my_server.py",
            args = "[]",
            env = "{}"
        )
    )
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
                    Text(example.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        example.command,
                        fontSize = 10.sp,
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

private fun commandLabel(runtime: String) = when (runtime) {
    "node" -> "JS 入口文件路径"
    "python" -> "Python 脚本路径"
    "npx" -> "npm 包名"
    "uvx" -> "Python 包名"
    else -> "命令"
}

private fun commandPlaceholder(runtime: String) = when (runtime) {
    "node" -> "mcp_filesystem.js  （或绝对路径）"
    "python" -> "/sdcard/OmniChat/mcp/my_server.py"
    "npx" -> "@modelcontextprotocol/server-filesystem"
    "uvx" -> "mcp-server-fetch"
    else -> "命令或路径"
}

private fun isValidJsonArray(s: String): Boolean {
    return try {
        org.json.JSONArray(s.trim())
        true
    } catch (e: Exception) {
        false
    }
}

private fun isValidJsonObject(s: String): Boolean {
    return try {
        org.json.JSONObject(s.trim())
        true
    } catch (e: Exception) {
        false
    }
}

// ── 运行时状态栏 ──────────────────────────────────────────────────────────

@Composable
private fun RuntimeStatusBar(
    isNodeAvailable: Boolean,
    isPythonReady: Boolean,
    onInfoClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val allReady = isNodeAvailable && isPythonReady
    val barColor = when {
        allReady -> Color(0xFF34C759).copy(alpha = 0.12f)
        !isNodeAvailable && !isPythonReady -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else -> Color(0xFFFF9500).copy(alpha = 0.12f)
    }

    Surface(
        color = barColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onInfoClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Node.js 状态
            RuntimeChip(
                label = "Node.js",
                isReady = isNodeAvailable,
                readyText = "已就绪",
                notReadyText = "未加载"
            )
            // Python 状态
            RuntimeChip(
                label = "Python",
                isReady = isPythonReady,
                readyText = "已就绪",
                notReadyText = "未就绪"
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "点击查看详情",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun RuntimeChip(
    label: String,
    isReady: Boolean,
    readyText: String,
    notReadyText: String
) {
    val color = if (isReady) Color(0xFF34C759) else Color(0xFFFF3B30)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Text(
            text = "$label: ${if (isReady) readyText else notReadyText}",
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── 运行时信息弹窗 ────────────────────────────────────────────────────────

@Composable
private fun RuntimeInfoDialog(
    isNodeAvailable: Boolean,
    isPythonReady: Boolean,
    pythonStatus: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
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
                        "内嵌运行时状态",
                        fontSize = 17.sp,
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
                    title = "Node.js 运行时",
                    isReady = isNodeAvailable,
                    statusText = if (isNodeAvailable) "libnode.so 已加载，Node.js 可用"
                                 else "libnode.so 未找到",
                    instructions = if (!isNodeAvailable) """
1. 前往 https://github.com/nodejs-mobile/nodejs-mobile/releases
2. 下载最新的 nodejs-mobile-v*.zip
3. 解压，将各 ABI 的 libnode.so 放入：
   app/src/main/jniLibs/arm64-v8a/libnode.so
   app/src/main/jniLibs/x86_64/libnode.so
4. 将 include/ 目录复制到：
   app/src/main/cpp/node_include/
5. 重新编译 App
                    """.trimIndent() else null
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Python 状态
                RuntimeInfoSection(
                    title = "Python 运行时",
                    isReady = isPythonReady,
                    statusText = pythonStatus,
                    instructions = if (!isPythonReady) """
官方 Android 包下载地址（python.org）：
https://www.python.org/downloads/android/

arm64 设备下载：
python-3.14.5-aarch64-linux-android.tar.gz

x86_64 设备下载：
python-3.14.5-x86_64-linux-android.tar.gz

解压后操作：
1. 将 prefix/lib/*.so 放入：
   app/src/main/jniLibs/<ABI>/
   (libpython3.14.so, libssl_python.so, libcrypto_python.so)

2. 打包标准库（在 prefix/lib/ 目录执行）：
   zip -r stdlib.zip python3.14/

3. 预装 mcp 包（可选）：
   pip install --target=python3.14/site-packages mcp httpx anyio
   zip -r stdlib.zip python3.14/

4. 将 stdlib.zip 放入：
   app/src/main/assets/python/stdlib.zip

5. 重新编译 App（首次运行自动解压，约需 5-10 秒）
                    """.trimIndent() else null
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun RuntimeInfoSection(
    title: String,
    isReady: Boolean,
    statusText: String,
    instructions: String?
) {
    val isDark = isSystemInDarkTheme()
    val color = if (isReady) Color(0xFF34C759) else Color(0xFFFF3B30)
    val bgColor = if (isReady) color.copy(alpha = 0.08f)
                  else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)

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
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            statusText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 16.sp
        )
        if (instructions != null) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = color.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "安装步骤：",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                instructions,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}
