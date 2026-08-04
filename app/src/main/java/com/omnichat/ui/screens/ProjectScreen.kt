package com.omnichat.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnichat.data.Project
import com.omnichat.data.ProjectKnowledge
import com.omnichat.ui.theme.LocalSidebarColors
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.viewmodel.ChatViewModel
import com.omnichat.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * 项目列表侧边栏面板。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectSidebarPanel(
    viewModel: ChatViewModel,
    onSessionSelected: () -> Unit,
    onClose: () -> Unit,
    onProjectDetail: (Project) -> Unit = {}
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val selectedProjectId by viewModel.selectedProjectId.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()

    val uiSettings = LocalUISettings.current
    val sidebarColors = LocalSidebarColors.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    val scope = rememberCoroutineScope()

    // 弹窗状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTargetProject by remember { mutableStateOf<Project?>(null) }
    var renameTargetProject by remember { mutableStateOf<Project?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(sidebarColors.background)
            .imePadding()
    ) {
        // ── 标题区域 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(sidebarColors.activeBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = sidebarColors.onActiveBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText("sidebar.project.title", R.string.sidebar_title),
                    fontSize = (15 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily,
                    color = sidebarColors.onBackground
                )
                Text(
                    text = "项目空间",
                    fontSize = (10 * fs).sp,
                    color = sidebarColors.onBackground.copy(alpha = 0.5f),
                    fontFamily = resolvedFontFamily
                )
            }
            // 返回主侧边栏
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = sidebarColors.onBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        HorizontalDivider(
            color = sidebarColors.onBackground.copy(alpha = 0.06f),
            thickness = 0.5.dp
        )

        // ── 项目列表 ────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(bottom = 8.dp, top = 8.dp)
        ) {
            items(projects, key = { "project_${it.id}" }) { project ->
                val isActive = project.id == selectedProjectId

                ProjectListItem(
                    project = project,
                    isActive = isActive,
                    fs = fs,
                    resolvedFontFamily = resolvedFontFamily,
                    sidebarColors = sidebarColors,
                    cornerRadius = cornerRadius,
                    onClick = {
                        viewModel.selectProject(project.id)
                        onProjectDetail(project)
                    },
                    onRename = {
                        renameTargetProject = project
                        renameText = project.name
                    },
                    onDelete = {
                        deleteTargetProject = project
                    }
                )
            }

            if (projects.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无项目，点击下方按钮创建",
                            fontSize = (12 * fs).sp,
                            color = sidebarColors.onBackground.copy(alpha = 0.45f),
                            fontFamily = resolvedFontFamily
                        )
                    }
                }
            }
        }

        // ── 底部：新建项目按钮 ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(cornerRadius.coerceIn(6.dp, 14.dp)),
                color = sidebarColors.activeBackground.copy(alpha = 0.15f),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = sidebarColors.onBackground.copy(alpha = 0.75f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "新建项目",
                        fontSize = (13 * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = sidebarColors.onBackground.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }

    // ── 创建项目弹窗 ─────────────────────────────────────────────────────
    if (showCreateDialog) {
        var projectName by remember { mutableStateOf("") }
        var projectDesc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(cornerRadius),
            icon = { Icon(Icons.Default.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("创建新项目", fontFamily = resolvedFontFamily) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("项目名称", fontFamily = resolvedFontFamily) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape((cornerRadius.value * 0.6f).coerceAtLeast(4f).dp)
                    )
                    OutlinedTextField(
                        value = projectDesc,
                        onValueChange = { projectDesc = it },
                        label = { Text("项目描述（可选）", fontFamily = resolvedFontFamily) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape((cornerRadius.value * 0.6f).coerceAtLeast(4f).dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createProject(projectName.trim(), projectDesc.trim())
                        showCreateDialog = false
                    },
                    enabled = projectName.isNotBlank(),
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("创建", fontFamily = resolvedFontFamily) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateDialog = false },
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("取消", fontFamily = resolvedFontFamily) }
            }
        )
    }

    // ── 删除项目弹窗 ─────────────────────────────────────────────────────
    deleteTargetProject?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTargetProject = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(cornerRadius),
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除项目", fontFamily = resolvedFontFamily) },
            text = {
                Text(
                    text = "确定要删除项目「${project.name}」吗？\n\n所有项目会话、知识文件和项目记忆将被永久删除，此操作不可撤销。",
                    fontSize = (14 * fs).sp,
                    lineHeight = (20 * fs).sp,
                    fontFamily = resolvedFontFamily
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteProject(project.id); deleteTargetProject = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("删除", fontFamily = resolvedFontFamily) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTargetProject = null },
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("取消", fontFamily = resolvedFontFamily) }
            }
        )
    }

    // ── 重命名项目弹窗 ───────────────────────────────────────────────────
    renameTargetProject?.let { project ->
        AlertDialog(
            onDismissRequest = { renameTargetProject = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(cornerRadius),
            title = { Text("重命名项目", fontFamily = resolvedFontFamily) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("项目名称", fontFamily = resolvedFontFamily) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape((cornerRadius.value * 0.6f).coerceAtLeast(4f).dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.repository.updateProjectDetails(project.id, renameText.trim(), project.description)
                        }
                        renameTargetProject = null
                    },
                    enabled = renameText.isNotBlank(),
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("确认", fontFamily = resolvedFontFamily) }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameTargetProject = null },
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("取消", fontFamily = resolvedFontFamily) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectListItem(
    project: Project,
    isActive: Boolean,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    sidebarColors: com.omnichat.ui.theme.SidebarColors,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) sidebarColors.activeBackground else Color.Transparent,
        animationSpec = tween(200),
        label = "projectBg"
    )
    val textColor = if (isActive) sidebarColors.onActiveBackground else sidebarColors.onBackground

    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius.coerceIn(4.dp, 16.dp)))
            .combinedClickable(onClick = onClick, onLongClick = {}),
        color = bgColor,
        shape = RoundedCornerShape(cornerRadius.coerceIn(4.dp, 16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = if (isActive) sidebarColors.onActiveBackground else sidebarColors.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    fontSize = (13.5f * fs).sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = resolvedFontFamily,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (project.description.isNotBlank()) {
                    Text(
                        text = project.description,
                        fontSize = (10 * fs).sp,
                        color = textColor.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
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
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(cornerRadius.coerceIn(8.dp, 16.dp)),
                    offset = DpOffset(x = (-80).dp, y = 0.dp),
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("重命名", fontSize = (13 * fs).sp)
                            }
                        },
                        onClick = { onRename(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text("删除", fontSize = (13 * fs).sp, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        onClick = { onDelete(); showMenu = false }
                    )
                }
            }
        }
    }
}