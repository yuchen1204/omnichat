package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
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
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText

@Composable
fun McpConfigScreen(
    mcpViewModel: McpViewModel = viewModel(),
    settingsViewModel: com.example.ui.viewmodel.SettingsViewModel = viewModel()
) {
    val servers by mcpViewModel.mcpServers.collectAsStateWithLifecycle()
    val serverStates by mcpViewModel.serverStates.collectAsStateWithLifecycle()
    val allTools by mcpViewModel.allTools.collectAsStateWithLifecycle()
    val currentUiSettings by settingsViewModel.uiSettings.collectAsStateWithLifecycle()

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
            isNodeEnabled = currentUiSettings?.isNodeEnabled ?: true,
            isPythonEnabled = currentUiSettings?.isPythonEnabled ?: true,
            onToggleNode = { enabled ->
                currentUiSettings?.copy(isNodeEnabled = enabled, updatedAt = System.currentTimeMillis())?.let {
                    settingsViewModel.updateUISettings(it)
                }
            },
            onTogglePython = { enabled ->
                currentUiSettings?.copy(isPythonEnabled = enabled, updatedAt = System.currentTimeMillis())?.let {
                    settingsViewModel.updateUISettings(it)
                }
            },
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

                StatChip(label = uiText("mcp.stat.servers", "服务"), value = "${servers.size}", color = MaterialTheme.colorScheme.primary)
                StatChip(label = uiText("mcp.stat.running_servers", "运行中"), value = "$runningCount", color = com.example.ui.theme.LocalCustomColors.current.success)
                StatChip(label = uiText("mcp.stat.tools_count", "工具"), value = "$toolCount", color = com.example.ui.theme.LocalCustomColors.current.warning)

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
                    Text(uiText("mcp.d42727b5", "添加服务"), fontSize = (13 * LocalUISettings.current.fontSizeScale).sp)
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
                        fontSize = (18 * LocalUISettings.current.fontSizeScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiText("mcp.empty.desc", "点击右上角「添加服务」配置 Node.js 或 Python MCP server"),
                        fontSize = (13 * LocalUISettings.current.fontSizeScale).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // 快速添加示例
                    Text(
                        text = uiText("mcp.examples.title", "常用示例"),
                        fontSize = (12 * LocalUISettings.current.fontSizeScale).sp,
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

                // ── 内置工具分组管理 ────────────────────────
                item {
                    McpBuiltinGroupsCard(
                        enabledGroups = currentUiSettings?.enabledMcpGroups ?: "core,ui_appearance,efficiency,memory",
                        onToggleGroup = { group, enabled ->
                            val current = currentUiSettings ?: com.example.data.UISettings()
                            val groups = current.enabledMcpGroups.split(",").toMutableSet()
                            if (enabled) groups.add(group) else groups.remove(group)
                            settingsViewModel.updateUISettings(current.copy(
                                enabledMcpGroups = groups.sorted().joinToString(","),
                                updatedAt = System.currentTimeMillis()
                            ))
                        }
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
            isNodeEnabled = currentUiSettings?.isNodeEnabled ?: true,
            isPythonEnabled = currentUiSettings?.isPythonEnabled ?: true,
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
        val serverName = if (serverId == -1L) uiText("mcp.builtin.title", "内置工具")
                         else servers.find { it.id == serverId }?.name ?: uiText("mcp.unknown", "未知")
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

    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale

    val statusColor = when (status) {
        McpServerStatus.RUNNING -> com.example.ui.theme.LocalCustomColors.current.success
        McpServerStatus.STARTING -> com.example.ui.theme.LocalCustomColors.current.warning
        McpServerStatus.ERROR -> MaterialTheme.colorScheme.error
        McpServerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val statusLabel = when (status) {
        McpServerStatus.RUNNING -> uiText("mcp.status.running", "运行中")
        McpServerStatus.STARTING -> uiText("mcp.status.starting", "启动中")
        McpServerStatus.ERROR -> uiText("mcp.status.error", "错误")
        McpServerStatus.STOPPED -> uiText("mcp.status.stopped", "已停止")
    }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)),
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
                        fontSize = (15 * fs).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = server.command,
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
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
                        fontSize = (11 * fs).sp,
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
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(8, 16).dp)) {
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
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 4,
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
                        Text(uiText("mcp.tools_count_label", "%d 个工具").format(toolCount), fontSize = (11 * fs).sp)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 启用/禁用开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (server.isEnabled) uiText("mcp.enabled", "已启用") else uiText("mcp.disabled", "已禁用"),
                        fontSize = (11 * fs).sp,
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
            shape = RoundedCornerShape(uiSettings.cornerRadiusDp.dp),
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(uiText("mcp.203904cd", "删除 MCP 服务")) },
            text = { Text(uiText("mcp.delete.confirm_body", "确定要删除「%s」吗？该服务将被停止并从配置中移除。").format(server.name)) },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) { Text(uiText("mcp.cd8498ff", "删除")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) { Text(uiText("mcp.40ebbe7b", "取消")) }
            }
        )
    }
}

@Composable
private fun McpBuiltinGroupsCard(
    enabledGroups: String,
    onToggleGroup: (String, Boolean) -> Unit
) {
    val fs = LocalUISettings.current.fontSizeScale
    val groups = listOf(
        "memory" to uiText("mcp.group.memory", "长效记忆"),
        "ui_appearance" to uiText("mcp.group.ui_appearance", "界面外观"),
        "efficiency" to uiText("mcp.group.efficiency", "效率提醒"),
        "ui_text" to uiText("mcp.group.ui_text", "界面文案"),
        "files" to uiText("mcp.group.files", "文件管理"),
        "documents" to uiText("mcp.group.documents", "文档创作")
    )
    val enabledSet = enabledGroups.split(",").toSet()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = uiText("mcp.builtin_groups.title", "内置工具组权限控制"),
                fontSize = (14 * fs).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiText("mcp.builtin_groups.desc", "AI 可以主动开启或关闭这些功能。 core 组始终开启。"),
                fontSize = (11 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 使用 FlowRow 效果的布局
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.chunked(2).forEach { rowGroups ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowGroups.forEach { (id, label) ->
                            val isEnabled = id in enabledSet
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleGroup(id, !isEnabled) },
                                color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) 
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = BorderStroke(
                                    0.5.dp, 
                                    if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) 
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = (12 * fs).sp,
                                        fontWeight = if (isEnabled) FontWeight.Medium else FontWeight.Normal,
                                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Checkbox(
                                        checked = isEnabled,
                                        onCheckedChange = { onToggleGroup(id, it) },
                                        modifier = Modifier.size(20.dp).scale(0.7f)
                                    )
                                }
                            }
                        }
                        if (rowGroups.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ── 运行时徽章 ────────────────────────────────────────────────────────────

@Composable
fun RuntimeBadge(runtime: String) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val primaryColor = MaterialTheme.colorScheme.primary
    val warningColor = com.example.ui.theme.LocalCustomColors.current.warning
    val fs = LocalUISettings.current.fontSizeScale
    
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
            fontSize = (10 * fs).sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── 统计 Chip ─────────────────────────────────────────────────────────────

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    val fs = LocalUISettings.current.fontSizeScale
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
            fontSize = (12 * fs).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}



// ── 运行时状态栏 ──────────────────────────────────────────────────────────

@Composable
private fun RuntimeStatusBar(
    isNodeAvailable: Boolean,
    isPythonReady: Boolean,
    isNodeEnabled: Boolean,
    isPythonEnabled: Boolean,
    onToggleNode: (Boolean) -> Unit,
    onTogglePython: (Boolean) -> Unit,
    onInfoClick: () -> Unit
) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val warningColor = com.example.ui.theme.LocalCustomColors.current.warning
    
    val anyEnabled = isNodeEnabled || isPythonEnabled
    val anyReady = (isNodeEnabled && isNodeAvailable) || (isPythonEnabled && isPythonReady)
    
    val barColor = when {
        allRuntimesReady(isNodeAvailable, isPythonReady, isNodeEnabled, isPythonEnabled) -> successColor.copy(alpha = 0.12f)
        !anyEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        !anyReady -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else -> warningColor.copy(alpha = 0.12f)
    }

    Surface(
        color = barColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Node.js 开关 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeChip(label = "Node", isReady = isNodeAvailable && isNodeEnabled, isEnabled = isNodeEnabled)
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isNodeEnabled,
                    onCheckedChange = onToggleNode,
                    modifier = Modifier.scale(0.6f).height(20.dp).width(34.dp)
                )
            }

            // Python 开关 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeChip(label = "Python", isReady = isPythonReady && isPythonEnabled, isEnabled = isPythonEnabled)
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isPythonEnabled,
                    onCheckedChange = onTogglePython,
                    modifier = Modifier.scale(0.6f).height(20.dp).width(34.dp)
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onInfoClick, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun allRuntimesReady(node: Boolean, py: Boolean, nodeEnabled: Boolean, pyEnabled: Boolean): Boolean {
    val nodeOk = !nodeEnabled || node
    val pyOk = !pyEnabled || py
    return nodeOk && pyOk && (nodeEnabled || pyEnabled)
}

@Composable
private fun RuntimeChip(
    label: String,
    isReady: Boolean,
    isEnabled: Boolean
) {
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val color = when {
        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        isReady -> successColor
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    }
    val fs = LocalUISettings.current.fontSizeScale
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
            fontSize = (10 * fs).sp,
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
    val fs = LocalUISettings.current.fontSizeScale
    
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
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
                Text(
                    text = uiText("mcp.builtin.desc", "%d 个工具 · 始终可用").format(tools.size),
                    fontSize = (11 * fs).sp,
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
                    fontSize = (11 * fs).sp,
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
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
