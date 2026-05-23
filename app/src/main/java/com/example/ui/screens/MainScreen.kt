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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChatViewModel,
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
    
    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                SessionSidebarPanel(
                    viewModel = viewModel,
                    onSessionSelected = {
                        currentTab = "chat"
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
                    "settings" -> SettingsView(viewModel, mcpViewModel)
                }
            }
        }
    }
}

@Composable
fun SettingsView(
    viewModel: ChatViewModel,
    mcpViewModel: McpViewModel
) {
    var selectedSubTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        uiText("tab.settings.models", "模型配置"),
        uiText("tab.settings.mcp", "MCP工具"),
        uiText("tab.settings.memory", "长效记忆"),
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
                3 -> ExportImportView(settingsViewModel = settingsViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    currentTab: String,
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit
) {
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val isSyncing = viewModel.isMemorySyncing
    
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSession = sessions.find { it.id == activeSessionId }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }

    val titleText = when (currentTab) {
        "chat" -> activeSession?.title ?: uiText("topbar.title.chat", "会话")
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
