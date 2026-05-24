package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Session
import com.example.data.WorkspaceSession
import com.example.ui.theme.LocalCustomColors
import com.example.ui.theme.LocalSidebarColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionSidebarPanel(
    viewModel: ChatViewModel,
    workspaceViewModel: WorkspaceViewModel,
    onSessionSelected: () -> Unit,
    onWorkspaceSelected: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()

    val workspaceSessions by workspaceViewModel.workspaceSessions.collectAsStateWithLifecycle()
    val activeWorkspaceId by workspaceViewModel.selectedWorkspaceId.collectAsStateWithLifecycle()
    val workspaceError by workspaceViewModel.errorMessage.collectAsStateWithLifecycle()

    val uiSettings = LocalUISettings.current
    val sidebarColors = LocalSidebarColors.current
    val customColors = LocalCustomColors.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp

    val newSessionDefaultTitle = uiText("sidebar.new_session_default", "新会话")

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(workspaceError) {
        workspaceError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            workspaceViewModel.clearErrorMessage()
        }
    }

    // 弹窗状态
    var deleteTargetSession by remember { mutableStateOf<Session?>(null) }
    var deleteTargetWorkspace by remember { mutableStateOf<WorkspaceSession?>(null) }
    var renameTargetSession by remember { mutableStateOf<Session?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(sidebarColors.background)
    ) {
        // ── 顶部渐变 Header ───────────────────────────────────────────────
        SidebarHeader(
            fs = fs,
            resolvedFontFamily = resolvedFontFamily,
            sidebarColors = sidebarColors,
            cornerRadius = cornerRadius,
            onNewSession = {
                viewModel.createNewSession(newSessionDefaultTitle)
                onSessionSelected()
            }
        )

        // ── 列表区域 ───────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            // ── 对话列表分区标题 ────────────────────────────────────────
            item {
                SectionHeader(
                    label = uiText("sidebar.chat_sessions", "对话"),
                    fs = fs,
                    sidebarColors = sidebarColors,
                    actionIcon = null,
                    onAction = {}
                )
            }

            if (sessions.isEmpty()) {
                item {
                    EmptyHint(
                        text = uiText("sidebar.no_sessions", "还没有对话，点击右上角新建"),
                        fs = fs,
                        sidebarColors = sidebarColors
                    )
                }
            } else {
                items(sessions, key = { "session_${it.id}" }) { session ->
                    val isActive = session.id == activeSessionId
                    var showItemMenu by remember { mutableStateOf(false) }

                    SessionListItem(
                        title = session.title,
                        subtitle = null,
                        icon = Icons.Default.ChatBubbleOutline,
                        isActive = isActive,
                        statusDot = null,
                        fs = fs,
                        resolvedFontFamily = resolvedFontFamily,
                        sidebarColors = sidebarColors,
                        cornerRadius = cornerRadius,
                        testTag = "session_item_${session.id}",
                        onClick = {
                            viewModel.selectSession(session.id)
                            onSessionSelected()
                        },
                        onLongClick = { showItemMenu = true },
                        trailingContent = {
                            Box {
                                IconButton(
                                    onClick = { showItemMenu = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = null,
                                        tint = if (isActive) sidebarColors.onActiveBackground.copy(alpha = 0.6f)
                                               else sidebarColors.onBackground.copy(alpha = 0.4f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showItemMenu,
                                    onDismissRequest = { showItemMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    offset = DpOffset(x = (-80).dp, y = 0.dp),
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(uiText("sidebar.286eb70c", "重命名"), fontSize = (13 * fs).sp)
                                            }
                                        },
                                        onClick = {
                                            renameTargetSession = session
                                            renameText = session.title
                                            showItemMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Delete, null,
                                                    modifier = Modifier.size(15.dp),
                                                    tint = MaterialTheme.colorScheme.error)
                                                Spacer(Modifier.width(8.dp))
                                                Text(uiText("sidebar.7c7e3e24", "删除"), fontSize = (13 * fs).sp,
                                                    color = MaterialTheme.colorScheme.error)
                                            }
                                        },
                                        onClick = {
                                            deleteTargetSession = session
                                            showItemMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }

            // ── 工作区分区标题 ──────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    label = uiText("sidebar.workspaces", "多智能体工作区"),
                    fs = fs,
                    sidebarColors = sidebarColors,
                    actionIcon = Icons.Default.Add,
                    onAction = {
                        scope.launch {
                            val newId = workspaceViewModel.createWorkspaceSession()
                            if (newId > 0) {
                                workspaceViewModel.selectWorkspaceSession(newId)
                                onWorkspaceSelected()
                            }
                        }
                    }
                )
            }

            if (workspaceSessions.isEmpty()) {
                item {
                    EmptyHint(
                        text = uiText("sidebar.no_workspace", "点击 + 新建工作区，让多个 AI 协同完成任务"),
                        fs = fs,
                        sidebarColors = sidebarColors
                    )
                }
            } else {
                items(workspaceSessions, key = { "workspace_${it.id}" }) { ws ->
                    val isActive = ws.id == activeWorkspaceId

                    SessionListItem(
                        title = ws.title,
                        subtitle = formatRelativeTime(ws.lastActiveAt),
                        icon = Icons.Default.Hub,
                        isActive = isActive,
                        statusDot = if (ws.isActive) customColors.success else null,
                        fs = fs,
                        resolvedFontFamily = resolvedFontFamily,
                        sidebarColors = sidebarColors,
                        cornerRadius = cornerRadius,
                        testTag = "workspace_item_${ws.id}",
                        onClick = {
                            workspaceViewModel.selectWorkspaceSession(ws.id)
                            onWorkspaceSelected()
                        },
                        onLongClick = { deleteTargetWorkspace = ws },
                        trailingContent = {
                            IconButton(
                                onClick = { deleteTargetWorkspace = ws },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = if (isActive) sidebarColors.onActiveBackground.copy(alpha = 0.5f)
                                           else sidebarColors.onBackground.copy(alpha = 0.3f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        // ── 底部 Footer ────────────────────────────────────────────────
        SidebarFooter(
            modelConfigs = modelConfigs,
            fs = fs,
            resolvedFontFamily = resolvedFontFamily,
            sidebarColors = sidebarColors,
            cornerRadius = cornerRadius,
            onSettingsClick = onSettingsClick,
            context = context
        )
    }

    // ── 弹窗 ─────────────────────────────────────────────────────────
    deleteTargetSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTargetSession = null },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(uiText("dialog.delete.session.title", "删除会话")) },
            text = {
                Text(
                    text = uiText("dialog.delete.session.body", "确定要删除「%s」吗？\n该会话的所有消息记录将被永久清除，无法恢复。").format(session.title),
                    fontSize = (14 * fs).sp,
                    lineHeight = (20 * fs).sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteSession(session.id); deleteTargetSession = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(uiText("action.delete", "删除")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetSession = null }) { Text(uiText("action.cancel", "取消")) }
            }
        )
    }

    renameTargetSession?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTargetSession = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(uiText("sidebar.cf3487b0", "重命名会话")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(uiText("sidebar.e4ceeffb", "会话名称")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.renameSession(session.id, renameText); renameTargetSession = null },
                    enabled = renameText.isNotBlank()
                ) { Text(uiText("sidebar.d5e6dfc3", "确认")) }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetSession = null }) { Text(uiText("sidebar.335fc2b7", "取消")) }
            }
        )
    }

    deleteTargetWorkspace?.let { ws ->
        AlertDialog(
            onDismissRequest = { deleteTargetWorkspace = null },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(uiText("dialog.delete.workspace.title", "删除工作区")) },
            text = {
                Text(
                    text = uiText("dialog.delete.workspace.body", "确定要删除工作区「%s」吗？\n该工作区内的所有协作对话记录及实例元数据将被永久级联删除，无法恢复。").format(ws.title),
                    fontSize = (14 * fs).sp,
                    lineHeight = (20 * fs).sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { workspaceViewModel.deleteWorkspaceSession(ws.id); deleteTargetWorkspace = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(uiText("action.delete", "删除")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetWorkspace = null }) { Text(uiText("action.cancel", "取消")) }
            }
        )
    }
}

// ── 顶部 Header ──────────────────────────────────────────────────────────────

@Composable
private fun SidebarHeader(
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    sidebarColors: com.example.ui.theme.SidebarColors,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onNewSession: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        sidebarColors.activeBackground.copy(alpha = 0.35f),
                        sidebarColors.background
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon / brand dot
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(sidebarColors.activeBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = sidebarColors.onActiveBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText("sidebar.title", "OmniChat"),
                    fontSize = (15 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily,
                    color = sidebarColors.onBackground
                )
                Text(
                    text = uiText("sidebar.subtitle", "多模型 · 长效记忆 · 多智能体"),
                    fontSize = (10 * fs).sp,
                    color = sidebarColors.onBackground.copy(alpha = 0.5f),
                    fontFamily = resolvedFontFamily
                )
            }
            // 新建会话按钮
            IconButton(
                onClick = onNewSession,
                modifier = Modifier
                    .size(34.dp)
                    .background(sidebarColors.activeBackground.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .testTag("add_session_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = uiText("sidebar.aa75d46c", "新建会话"),
                    tint = sidebarColors.onBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    HorizontalDivider(
        color = sidebarColors.onBackground.copy(alpha = 0.06f),
        thickness = 0.5.dp
    )
}

// ── 分区标题 ────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    label: String,
    fs: Float,
    sidebarColors: com.example.ui.theme.SidebarColors,
    actionIcon: ImageVector?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label.uppercase(),
            fontSize = (10 * fs).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = sidebarColors.onBackground.copy(alpha = 0.45f)
        )
        if (actionIcon != null) {
            IconButton(
                onClick = onAction,
                modifier = Modifier
                    .size(22.dp)
                    .background(sidebarColors.activeBackground.copy(alpha = 0.25f), CircleShape)
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    tint = sidebarColors.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

// ── 通用列表条目 ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListItem(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    isActive: Boolean,
    statusDot: Color?,       // null = 不显示; non-null = 状态点颜色
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    sidebarColors: com.example.ui.theme.SidebarColors,
    cornerRadius: androidx.compose.ui.unit.Dp,
    testTag: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailingContent: @Composable () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) sidebarColors.activeBackground else Color.Transparent,
        animationSpec = tween(200),
        label = "itemBg"
    )
    val elevation by animateDpAsState(
        targetValue = if (isActive) 1.dp else 0.dp,
        animationSpec = tween(200),
        label = "itemElevation"
    )
    val textColor = if (isActive) sidebarColors.onActiveBackground else sidebarColors.onBackground
    val iconTint = if (isActive) sidebarColors.onActiveBackground else sidebarColors.onBackground.copy(alpha = 0.55f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(cornerRadius.coerceIn(4.dp, 16.dp)))
            .clip(RoundedCornerShape(cornerRadius.coerceIn(4.dp, 16.dp)))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag(testTag),
        color = bgColor,
        shape = RoundedCornerShape(cornerRadius.coerceIn(4.dp, 16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                // 状态点（工作区运行中）
                if (statusDot != null) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(statusDot)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))

            // 文字区
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = (13.5f * fs).sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = resolvedFontFamily,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = (10 * fs).sp,
                        color = textColor.copy(alpha = if (isActive) 0.65f else 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 尾部操作
            trailingContent()
        }
    }
}

// ── 空状态提示 ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyHint(
    text: String,
    fs: Float,
    sidebarColors: com.example.ui.theme.SidebarColors
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = (11 * fs).sp,
            color = sidebarColors.onBackground.copy(alpha = 0.35f),
            lineHeight = (16 * fs).sp
        )
    }
}

// ── 底部 Footer ──────────────────────────────────────────────────────────────

@Composable
private fun SidebarFooter(
    modelConfigs: List<com.example.data.ModelConfig>,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    sidebarColors: com.example.ui.theme.SidebarColors,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onSettingsClick: () -> Unit,
    context: android.content.Context
) {
    val customColors = LocalCustomColors.current
    val defaultProvider = modelConfigs.find { it.isDefaultProvider }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        HorizontalDivider(
            color = sidebarColors.onBackground.copy(alpha = 0.07f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 模型状态卡片
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius.coerceIn(6.dp, 14.dp))),
            color = sidebarColors.activeBackground.copy(alpha = 0.12f),
            shape = RoundedCornerShape(cornerRadius.coerceIn(6.dp, 14.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态点
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (defaultProvider != null) customColors.success
                            else MaterialTheme.colorScheme.error
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = defaultProvider?.name ?: uiText("sidebar.not_set", "未配置模型"),
                        fontSize = (12 * fs).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = resolvedFontFamily,
                        color = sidebarColors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (defaultProvider != null) {
                        Text(
                            text = defaultProvider.selectedModelId.ifEmpty { "未选择模型" },
                            fontSize = (10 * fs).sp,
                            color = sidebarColors.onBackground.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 底部按钮行：设置 + 恢复 UI
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 设置按钮（主要）
            Surface(
                onClick = onSettingsClick,
                shape = RoundedCornerShape(cornerRadius.coerceIn(6.dp, 14.dp)),
                color = Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = uiText("sidebar.settings", "设置"),
                        tint = sidebarColors.onBackground.copy(alpha = 0.75f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = uiText("sidebar.settings", "设置"),
                        fontSize = (13 * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = sidebarColors.onBackground.copy(alpha = 0.85f)
                    )
                }
            }

            // 恢复默认 UI 小按钮
            val settingsViewModel: com.example.ui.viewmodel.SettingsViewModel = viewModel()
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        val db = com.example.data.AppDatabase.getDatabase(context.applicationContext)
                        com.example.data.AppRepository(db).upsertUISettings(com.example.data.UISettings())
                    }
                },
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(cornerRadius.coerceIn(6.dp, 14.dp)))
                    .background(sidebarColors.activeBackground.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = uiText("sidebar.7f62f6d8", "恢复默认配色"),
                    tint = sidebarColors.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── 时间格式化 ───────────────────────────────────────────────────────────────

fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return "刚刚"
    val diffSeconds = diff / 1000
    if (diffSeconds < 60) return "刚刚"
    val diffMinutes = diffSeconds / 60
    if (diffMinutes < 60) return "${diffMinutes}分钟前"
    val diffHours = diffMinutes / 60
    if (diffHours < 24) return "${diffHours}小时前"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
