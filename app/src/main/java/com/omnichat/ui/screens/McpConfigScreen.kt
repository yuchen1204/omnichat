package com.omnichat.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnichat.data.McpServer
import com.omnichat.mcp.McpServerStatus
import com.omnichat.mcp.McpTool
import com.omnichat.mcp.McpViewModel
import com.omnichat.R
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.theme.LocalUISettings

@Composable
fun McpConfigScreen(
    mcpViewModel: McpViewModel = viewModel(),
    settingsViewModel: com.omnichat.ui.viewmodel.SettingsViewModel = viewModel()
) {
    val servers by mcpViewModel.mcpServers.collectAsStateWithLifecycle()
    val serverStates by mcpViewModel.serverStates.collectAsStateWithLifecycle()
    val allTools by mcpViewModel.allTools.collectAsStateWithLifecycle()
    val currentUiSettings by settingsViewModel.uiSettings.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<McpServer?>(null) }
    var showToolsFor by remember { mutableStateOf<Long?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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

                StatChip(label = uiText("mcp.stat.servers", R.string.mcp_stat_servers), value = "${servers.size}", color = MaterialTheme.colorScheme.primary)
                StatChip(label = uiText("mcp.stat.running.servers", R.string.mcp_stat_running_servers), value = "$runningCount", color = com.omnichat.ui.theme.LocalCustomColors.current.success)
                StatChip(label = uiText("mcp.stat.tools.count", R.string.mcp_stat_tools_count), value = "$toolCount", color = com.omnichat.ui.theme.LocalCustomColors.current.warning)

                Spacer(modifier = Modifier.weight(1f))

                // 导入按钮
                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = uiText("mcp.b03db521", R.string.mcp_import_json),
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
                    Text(uiText("mcp.d42727b5", R.string.mcp_add_service), fontSize = (13 * LocalUISettings.current.fontSizeScale).sp)
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
                        text = uiText("mcp.empty.title", R.string.mcp_empty_title),
                        fontSize = (18 * LocalUISettings.current.fontSizeScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiText("mcp.empty.desc", R.string.mcp_empty_desc),
                        fontSize = (13 * LocalUISettings.current.fontSizeScale).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // 快速添加示例
                    Text(
                        text = uiText("mcp.examples.title", R.string.mcp_examples_title),
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
                            val current = currentUiSettings ?: com.omnichat.data.UISettings()
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
                        Text(uiText("mcp.df5b1865", R.string.mcp_add_dialog_title))
                    }
                }
            }
        }
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
        val serverName = if (serverId == -1L) uiText("mcp.builtin.title", R.string.mcp_builtin_title)
                         else servers.find { it.id == serverId }?.name ?: uiText("mcp.unknown", R.string.mcp_unknown)
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
    state: com.omnichat.mcp.McpServerState?,
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
        McpServerStatus.RUNNING -> com.omnichat.ui.theme.LocalCustomColors.current.success
        McpServerStatus.STARTING -> com.omnichat.ui.theme.LocalCustomColors.current.warning
        McpServerStatus.ERROR -> MaterialTheme.colorScheme.error
        McpServerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val statusLabel = when (status) {
        McpServerStatus.RUNNING -> uiText("mcp.status.running", R.string.mcp_status_running)
        McpServerStatus.STARTING -> uiText("mcp.status.starting", R.string.mcp_status_starting)
        McpServerStatus.ERROR -> uiText("mcp.status.error", R.string.mcp_status_error)
        McpServerStatus.STOPPED -> uiText("mcp.status.stopped", R.string.mcp_status_stopped)
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
                RuntimeBadge()
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
                            contentDescription = uiText("mcp.2ba645d8", R.string.mcp_more),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(8, 16).dp)) {
                        DropdownMenuItem(
                            text = { Text(uiText("mcp.67aac8d1", R.string.mcp_edit_config)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(uiText("mcp.5bd26de1", R.string.mcp_restart_service)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; onRestart() }
                        )
                        DropdownMenuItem(
                            text = { Text(uiText("mcp.cd8498ff", R.string.mcp_delete), color = MaterialTheme.colorScheme.error) },
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
                        Text(uiText("mcp.tools.count.label", R.string.mcp_tools_count_label).format(toolCount), fontSize = (11 * fs).sp)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 启用/禁用开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (server.isEnabled) uiText("mcp.enabled", R.string.mcp_enabled) else uiText("mcp.disabled", R.string.mcp_disabled),
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
            title = { Text(uiText("mcp.203904cd", R.string.mcp_delete_title)) },
            text = { Text(uiText("mcp.delete.confirm.body", R.string.mcp_delete_confirm_body).format(server.name)) },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) { Text(uiText("mcp.cd8498ff", R.string.mcp_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) { Text(uiText("mcp.40ebbe7b", R.string.mcp_cancel)) }
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
        "memory" to uiText("mcp.group.memory", R.string.mcp_group_memory),
        "ui_appearance" to uiText("mcp.group.ui.appearance", R.string.mcp_group_ui_appearance),
        "efficiency" to uiText("mcp.group.efficiency", R.string.mcp_group_efficiency),
        "ui_text" to uiText("mcp.group.ui.text", R.string.mcp_group_ui_text),
        "files" to uiText("mcp.group.files", R.string.mcp_group_files),
        "documents" to uiText("mcp.group.documents", R.string.mcp_group_documents)
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
                text = uiText("mcp.builtin.groups.title", R.string.mcp_builtin_groups_title),
                fontSize = (14 * fs).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiText("mcp.builtin.groups.desc", R.string.mcp_builtin_groups_desc),
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
fun RuntimeBadge() {
    val fs = LocalUISettings.current.fontSizeScale
    val color = MaterialTheme.colorScheme.secondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "HTTP",
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
                    text = uiText("mcp.builtin.title", R.string.mcp_builtin_title),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
                Text(
                    text = uiText("mcp.builtin.desc", R.string.mcp_builtin_desc).format(tools.size),
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
                        .background(com.omnichat.ui.theme.LocalCustomColors.current.success)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = uiText("mcp.builtin.status", R.string.mcp_builtin_status),
                    fontSize = (11 * fs).sp,
                    color = com.omnichat.ui.theme.LocalCustomColors.current.success,
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
                    text = uiText("mcp.view.tools", R.string.mcp_view_tools),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
