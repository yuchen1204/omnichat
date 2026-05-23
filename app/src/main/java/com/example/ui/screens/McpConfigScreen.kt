package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.ui.theme.uiText

@Composable
fun McpConfigScreen(
    mcpViewModel: McpViewModel = viewModel()
) {
    val servers by mcpViewModel.mcpServers.collectAsStateWithLifecycle()
    val serverStates by mcpViewModel.serverStates.collectAsStateWithLifecycle()
    val allTools by mcpViewModel.allTools.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<McpServer?>(null) }
    var showToolsFor by remember { mutableStateOf<Long?>(null) }
    var showRuntimeInfo by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val runningCount = serverStates.values.count { it.status == McpServerStatus.RUNNING }
                val toolCount = allTools.size

                StatChip(label = "服务", value = "${servers.size}", color = MaterialTheme.colorScheme.primary)
                StatChip(label = "运行中", value = "$runningCount", color = com.example.ui.theme.LocalCustomColors.current.success)
                StatChip(label = "工具", value = "$toolCount", color = com.example.ui.theme.LocalCustomColors.current.warning)

                Spacer(modifier = Modifier.weight(1f))

                // 导入按钮
                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = uiText("mcp.b03db521", "导入 JSON"),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // 添加按钮
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(uiText("mcp.d42727b5", "添加服务"), fontSize = 13.sp)
                }
            }
        }

        if (servers.isEmpty()) {
            // 空状态
            Column(modifier = Modifier.fillMaxSize()) {
                // 内置工具卡片始终显示
                val builtinTools = allTools.filter { it.serverId == -1L }
                BuiltinToolsCard(
                    tools = builtinTools,
                    onShowTools = { showToolsFor = -1L },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                        text = uiText("mcp.empty.title", "暂无 MCP 服务"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiText("mcp.empty.desc", "点击右上角「添加服务」配置 Node.js 或 Python MCP server"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // 快速添加示例
                    Text(
                        text = uiText("mcp.examples.title", "常用示例"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    McpExampleChips { example ->
                        mcpViewModel.addServer(example)
                    }
                }
                } // end Box
            } // end Column (empty state)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 内置工具卡片（始终显示在顶部）────────────────────────
                item {
                    val builtinTools = allTools.filter { it.serverId == -1L }
                    BuiltinToolsCard(
                        tools = builtinTools,
                        onShowTools = { showToolsFor = -1L }
                    )
                }

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
                        Text(uiText("mcp.df5b1865", "添加 MCP 服务"))
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

    // ── 导入 JSON 对话框 ────────────────────────────────────────────────
    if (showImportDialog) {
        McpImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                mcpViewModel.importConfigJson(json)
                showImportDialog = false
            }
        )
    }

    // ── 添加/编辑对话框 ───────────────────────────────────────────────────
    if (showAddDialog) {
        McpServerEditDialog(
            server = null,
            mcpWorkDir = mcpViewModel.mcpWorkDir,
            mcpViewModel = mcpViewModel,
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
            mcpViewModel = mcpViewModel,
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
        val serverName = if (serverId == -1L) "内置工具"
                         else servers.find { it.id == serverId }?.name ?: "未知"
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
    val status = state?.status ?: McpServerStatus.STOPPED
    val toolCount = state?.tools?.size ?: 0
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface

    val statusColor = when (status) {
        McpServerStatus.RUNNING -> com.example.ui.theme.LocalCustomColors.current.success
        McpServerStatus.STARTING -> com.example.ui.theme.LocalCustomColors.current.warning
        McpServerStatus.ERROR -> MaterialTheme.colorScheme.error
        McpServerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
            containerColor = surface
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
                        color = onSurface,
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
                            contentDescription = uiText("mcp.2ba645d8", "更多"),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                        DropdownMenuItem(
                            text = { Text(uiText("mcp.67aac8d1", "编辑配置")) },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(uiText("mcp.5bd26de1", "重启服务")) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onRestart() }
                        )
                        DropdownMenuItem(
                            text = { Text(uiText("mcp.cd8498ff", "删除"), color = MaterialTheme.colorScheme.error) },
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
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(uiText("mcp.203904cd", "删除 MCP 服务")) },
            text = { Text("确定要删除「${server.name}」吗？该服务将被停止并从配置中移除。") },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(uiText("mcp.cd8498ff", "删除")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(uiText("mcp.40ebbe7b", "取消")) }
            }
        )
    }
}

// ── 运行时徽章 ────────────────────────────────────────────────────────────

@Composable
fun RuntimeBadge(runtime: String) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val primaryColor = MaterialTheme.colorScheme.primary
    val warningColor = com.example.ui.theme.LocalCustomColors.current.warning
    
    val (label, color) = when (runtime) {
        "node" -> "Node" to successColor
        "python" -> "Py" to primaryColor
        "remote_http" -> "HTTP" to MaterialTheme.colorScheme.secondary
        else -> runtime.take(4).uppercase() to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val finalColor = if (runtime == "remote_http") MaterialTheme.colorScheme.secondary else color
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



// ── 运行时状态栏 ──────────────────────────────────────────────────────────

@Composable
private fun RuntimeStatusBar(
    isNodeAvailable: Boolean,
    isPythonReady: Boolean,
    onInfoClick: () -> Unit
) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val warningColor = com.example.ui.theme.LocalCustomColors.current.warning
    
    val anyReady = isNodeAvailable || isPythonReady
    val barColor = when {
        allRuntimesReady(isNodeAvailable, isPythonReady) -> successColor.copy(alpha = 0.12f)
        !anyReady -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else -> warningColor.copy(alpha = 0.12f)
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 内嵌运行时
            RuntimeChip(label = "Node", isReady = isNodeAvailable)
            RuntimeChip(label = "Python", isReady = isPythonReady)
            
            Text(
                text = uiText("mcp.84e89d6f", "支持远程 HTTP MCP"),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

private fun allRuntimesReady(node: Boolean, py: Boolean): Boolean {
    return node && py
}

@Composable
private fun RuntimeChip(
    label: String,
    isReady: Boolean
) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val color = if (isReady) successColor else MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}



// ── 内置工具卡片 ──────────────────────────────────────────────────────────

@Composable
private fun BuiltinToolsCard(
    tools: List<McpTool>,
    onShowTools: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText("mcp.builtin.title", "内置工具"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
                Text(
                    text = "${tools.size} 个工具 · 始终可用",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 状态点
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(com.example.ui.theme.LocalCustomColors.current.success)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = uiText("mcp.builtin.status", "运行中"),
                    fontSize = 11.sp,
                    color = com.example.ui.theme.LocalCustomColors.current.success,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 查看工具按钮
            TextButton(
                onClick = onShowTools,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = uiText("mcp.view.tools", "查看工具"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
