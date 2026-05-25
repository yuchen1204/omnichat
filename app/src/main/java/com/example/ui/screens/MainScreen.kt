package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mcp.McpViewModel
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

import com.example.ui.viewmodel.WorkspaceViewModel
import com.example.mcp.AskUserManager
import com.example.mcp.McpPermissionManager
import com.example.mcp.PermissionResult
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChatViewModel,
    workspaceViewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf("chat") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val mcpViewModel: McpViewModel = viewModel()

    // 强制触发 McpViewModel 初始化，确保 MCP 服务随应用启动自动运行
    LaunchedEffect(Unit) {
        val _unused = mcpViewModel.runtimeManager
    }
    
    val askUserRequests by AskUserManager.requests.collectAsStateWithLifecycle()
    val activeAskRequest = askUserRequests.firstOrNull()

    if (activeAskRequest != null) {
        AskUserDialog(
            request = activeAskRequest,
            onRespond = { response ->
                AskUserManager.respond(activeAskRequest.id, response)
            }
        )
    }

    val permissionRequest by McpPermissionManager.permissionRequestFlow.collectAsStateWithLifecycle()
    if (permissionRequest != null) {
        val uiSettingsPerm = LocalUISettings.current
        val fsPerm = uiSettingsPerm.fontSizeScale
        val cornerPerm = uiSettingsPerm.cornerRadiusDp.dp
        val resolvedFontFamilyPerm = resolveFontFamily(uiSettingsPerm.fontFamily)
        Dialog(
            onDismissRequest = { permissionRequest?.onResult?.invoke(PermissionResult.DENY) },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(cornerPerm),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // 图标 + 标题
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape((cornerPerm.value * 0.6f).dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = uiText("dialog.permission.title", "文件访问权限请求"),
                                fontSize = (16 * fsPerm).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = resolvedFontFamilyPerm,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = uiText("dialog.permission.subtitle", "MCP 工具请求"),
                                fontSize = (11 * fsPerm).sp,
                                fontFamily = resolvedFontFamilyPerm,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // 路径展示
                    Text(
                        text = uiText("dialog.permission.desc", "AI 助手想要访问沙盒外的文件："),
                        fontSize = (13 * fsPerm).sp,
                        fontFamily = resolvedFontFamilyPerm,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape((cornerPerm.value * 0.5f).coerceAtLeast(4f).dp)
                    ) {
                        Text(
                            text = permissionRequest?.path ?: "",
                            fontSize = (12 * fsPerm).sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            maxLines = 4,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 操作按钮 - 垂直排列，从最宽松到最严格
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { permissionRequest?.onResult?.invoke(PermissionResult.ALLOW_ALWAYS) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(uiText("dialog.permission.allow_always", "始终允许"), fontFamily = resolvedFontFamilyPerm, fontSize = (13 * fsPerm).sp)
                        }
                        OutlinedButton(
                            onClick = { permissionRequest?.onResult?.invoke(PermissionResult.ALLOW_ONCE) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(uiText("dialog.permission.allow_once", "允许一次"), fontFamily = resolvedFontFamilyPerm, fontSize = (13 * fsPerm).sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = { permissionRequest?.onResult?.invoke(PermissionResult.DONT_ASK_AGAIN) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp)
                            ) {
                                Text(uiText("dialog.permission.dont_ask", "不再询问"), fontFamily = resolvedFontFamilyPerm, fontSize = (12 * fsPerm).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = { permissionRequest?.onResult?.invoke(PermissionResult.DENY) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp)
                            ) {
                                Text(uiText("dialog.permission.deny", "拒绝"), fontFamily = resolvedFontFamilyPerm, fontSize = (12 * fsPerm).sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier

    val sidebarColors = com.example.ui.theme.LocalSidebarColors.current

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = sidebarColors.background,
                drawerShape = RoundedCornerShape(topEnd = uiSettings.cornerRadiusDp.dp, bottomEnd = uiSettings.cornerRadiusDp.dp)
            ) {
                SessionSidebarPanel(
                    viewModel = viewModel,
                    workspaceViewModel = workspaceViewModel,
                    onSessionSelected = {
                        currentTab = "chat"
                        scope.launch { drawerState.close() }
                    },
                    onWorkspaceSelected = {
                        currentTab = "workspace"
                        scope.launch { drawerState.close() }
                    },
                    onSettingsClick = {
                        currentTab = "settings"
                        scope.launch { drawerState.close() }
                    }
                )
            }
        },
        modifier = modifier
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                MainTopAppBar(
                    currentTab = currentTab,
                    viewModel = viewModel,
                    workspaceViewModel = workspaceViewModel,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp * (spacingMultiplier - 1f)) // 应用 AI 间距调整
                    .consumeWindowInsets(paddingValues)
            ) {
                when (currentTab) {
                    "chat" -> ChatView(viewModel)
                    "workspace" -> WorkspaceScreen(workspaceViewModel = workspaceViewModel)
                    "settings" -> SettingsView(viewModel, mcpViewModel, workspaceViewModel)
                }
            }
        }
    }
}

@Composable
fun SettingsView(
    viewModel: ChatViewModel,
    mcpViewModel: McpViewModel,
    workspaceViewModel: WorkspaceViewModel
) {
    var selectedSubTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        uiText("tab.settings.models", "模型配置"),
        uiText("tab.settings.mcp", "MCP工具"),
        uiText("tab.settings.memory", "长效记忆"),
        uiText("tab.settings.presets", "Agent 预设"),
        uiText("tab.settings.data", "数据管理")
    )
    val settingsViewModel: SettingsViewModel = viewModel()

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
            divider = {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    text = {
                        val uiSettings = LocalUISettings.current
                        Text(
                            text = title,
                            fontSize = (14 * uiSettings.fontSizeScale).sp,
                            fontWeight = if (selectedSubTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }
        
        Box(modifier = Modifier.weight(1f)) {
            when (selectedSubTab) {
                0 -> ModelsConfigView(viewModel)
                1 -> McpConfigScreen(mcpViewModel = mcpViewModel)
                2 -> MemoryAndPromptView(viewModel)
                3 -> AgentPresetConfigScreen(workspaceViewModel = workspaceViewModel)
                4 -> ExportImportView(settingsViewModel = settingsViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    currentTab: String,
    viewModel: ChatViewModel,
    workspaceViewModel: WorkspaceViewModel,
    onOpenDrawer: () -> Unit
) {
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val isSyncing = viewModel.isMemorySyncing
    
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSession = sessions.find { it.id == activeSessionId }

    val activeWsSession by workspaceViewModel.selectedWorkspaceSession.collectAsStateWithLifecycle()

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }

    val titleText = when (currentTab) {
        "chat" -> activeSession?.title ?: uiText("topbar.title.chat", "会话")
        "workspace" -> activeWsSession?.title ?: uiText("topbar.title.workspace", "工作区")
        "settings" -> uiText("topbar.title.settings", "设置")
        else -> "AI"
    }

    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    Column {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = titleText,
                        fontSize = (17 * fs).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = resolvedFontFamily,
                        letterSpacing = (-0.4).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    if (currentTab == "chat" && defaultProvider != null) {
                        Text(
                            text = "${uiText("topbar.provider.prefix", "提供商: ")}${defaultProvider.name}",
                            fontSize = (11 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 2
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = uiText("topbar.menu.open", "打开菜单"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            actions = {
                if (isSyncing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "blink")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "blink_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(com.example.ui.theme.LocalCustomColors.current.success.copy(alpha = alpha)) // Apple green
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = uiText("topbar.memory.syncing", "记忆同步中"),
                            fontSize = (11 * fs).sp,
                            color = com.example.ui.theme.LocalCustomColors.current.success
                        )
                    }
                } else if (currentTab == "chat" && defaultProvider?.memoryModelId?.isNotBlank() == true) {
                    IconButton(onClick = { viewModel.triggerMemorySync(force = true) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = uiText("topbar.memory.sync", "同步记忆"),
                            tint = MaterialTheme.colorScheme.primary // Material style
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 0.5.dp
        )
    }
}
