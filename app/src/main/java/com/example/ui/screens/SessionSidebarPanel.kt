package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Session
import com.example.ui.theme.LocalSidebarColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionSidebarPanel(
    viewModel: ChatViewModel,
    onSessionSelected: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    
    val uiSettings = LocalUISettings.current
    val sidebarColors = LocalSidebarColors.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    
    val newSessionDefaultTitle = uiText("sidebar.new_session_default", "新会话")
    val notSetLabel = uiText("sidebar.not_set", "未设置")

    // 删除确认对话框
    var deleteTargetSession by remember { mutableStateOf<Session?>(null) }
    // 重命名对话框
    var renameTargetSession by remember { mutableStateOf<Session?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(sidebarColors.background)
            .padding(16.dp)
    ) {
        // 标题 + 新建按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = uiText("sidebar.title", "对话列表"),
                fontSize = (18 * fs).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = resolvedFontFamily,
                color = sidebarColors.onBackground
            )
            IconButton(
                onClick = {
                    viewModel.createNewSession(newSessionDefaultTitle)
                    onSessionSelected()
                },
                modifier = Modifier
                    .size(36.dp)
                    .background(sidebarColors.activeBackground, RoundedCornerShape(8.dp * uiSettings.spacingMultiplier))
                    .testTag("add_session_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = uiText("sidebar.aa75d46c", "新建会话"),
                    tint = sidebarColors.onActiveBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Sessions List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                val isActive = session.id == activeSessionId
                var showItemMenu by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isActive) sidebarColors.activeBackground
                                else Color.Transparent
                            )
                            .combinedClickable(
                                onClick = {
                                    viewModel.selectSession(session.id)
                                    onSessionSelected()
                                },
                                onLongClick = { showItemMenu = true }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("session_item_${session.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = uiText("sidebar.4b7aa659", "对话"),
                            tint = if (isActive) sidebarColors.onActiveBackground
                                   else sidebarColors.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = session.title,
                            fontSize = (14 * fs).sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = resolvedFontFamily,
                            color = if (isActive) sidebarColors.onActiveBackground
                                    else sidebarColors.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        // 更多操作按钮（始终显示），菜单锚定在此按钮上
                        Box {
                            IconButton(
                                onClick = { showItemMenu = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = uiText("sidebar.69defa5f", "更多操作"),
                                    tint = if (isActive) sidebarColors.onActiveBackground.copy(alpha = 0.6f)
                                           else sidebarColors.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // 长按 / 点击 ⋮ 弹出的操作菜单，锚定在 ⋮ 按钮右下角
                            DropdownMenu(
                                expanded = showItemMenu,
                                onDismissRequest = { showItemMenu = false },
                                containerColor = MaterialTheme.colorScheme.surface,
                                offset = DpOffset(x = (-100).dp, y = 0.dp),
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Edit, contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurface)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(uiText("sidebar.286eb70c", "重命名"), fontSize = (14 * fs).sp)
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
                                            Icon(Icons.Default.Delete, contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(uiText("sidebar.7c7e3e24", "删除"), fontSize = (14 * fs).sp,
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
                }
            }
        }

        // ── 设置按钮与状态 ────────────────────────────────────────────
        val defaultProvider = modelConfigs.find { it.isDefaultProvider }
        val activeProviderName = defaultProvider?.name ?: notSetLabel
        val activeModelId = defaultProvider?.selectedModelId ?: notSetLabel

        HorizontalDivider(
            color = sidebarColors.onBackground.copy(alpha = 0.15f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (defaultProvider != null) com.example.ui.theme.LocalCustomColors.current.success else MaterialTheme.colorScheme.error)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeProviderName,
                    fontSize = (11 * fs).sp,
                    color = sidebarColors.onBackground.copy(alpha = 0.6f),
                    maxLines = 1
                )
                Text(
                    text = activeModelId,
                    fontSize = (10 * fs).sp,
                    color = sidebarColors.onBackground.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 设置按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onSettingsClick,
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = uiText("sidebar.settings", "设置"),
                        tint = sidebarColors.onBackground.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = uiText("sidebar.settings", "设置"),
                        fontSize = (14 * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = sidebarColors.onBackground
                    )
                }
            }
            
            // 恢复默认 UI 按钮
            val settingsViewModel: com.example.ui.viewmodel.SettingsViewModel = viewModel()
            val coroutineScope = rememberCoroutineScope()
            val currentContext = LocalContext.current
            IconButton(
                onClick = { 
                    coroutineScope.launch {
                        val db = com.example.data.AppDatabase.getDatabase(currentContext.applicationContext)
                        com.example.data.AppRepository(db).upsertUISettings(com.example.data.UISettings())
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = uiText("sidebar.7f62f6d8", "恢复默认配色"),
                    tint = sidebarColors.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    deleteTargetSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTargetSession = null },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(uiText("dialog.delete.session.title", "删除会话")) },
            text = {
                Text(
                    text = uiText(
                        "dialog.delete.session.body",
                        "确定要删除「%s」吗？\n该会话的所有消息记录将被永久清除，无法恢复。"
                    ).format(session.title),
                    fontSize = (14 * fs).sp,
                    lineHeight = (20 * fs).sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(session.id)
                        deleteTargetSession = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(uiText("action.delete", "删除"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetSession = null }) {
                    Text(uiText("action.cancel", "取消"))
                }
            }
        )
    }

    // ── 重命名对话框 ──────────────────────────────────────────────────
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
                    onClick = {
                        viewModel.renameSession(session.id, renameText)
                        renameTargetSession = null
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(uiText("sidebar.d5e6dfc3", "确认"))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetSession = null }) {
                    Text(uiText("sidebar.335fc2b7", "取消"))
                }
            }
        )
    }
}
