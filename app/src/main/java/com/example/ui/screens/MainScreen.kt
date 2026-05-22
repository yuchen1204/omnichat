package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.MemoryItem
import com.example.data.ModelConfig
import com.example.ui.components.ChunkedStreamingText
import com.example.data.PromptTemplate
import com.example.data.Session
import com.example.data.FetchedModel
import com.example.mcp.McpViewModel
import com.example.ui.theme.LocalUISettings
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val tabs = listOf("模型配置", "MCP工具", "长效记忆")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
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
                        Text(
                            text = title,
                            fontSize = 14.sp,
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
            }
        }
    }
}

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
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    // 删除确认对话框
    var deleteTargetSession by remember { mutableStateOf<Session?>(null) }
    // 重命名对话框
    var renameTargetSession by remember { mutableStateOf<Session?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.surface)
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
                text = "对话列表",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor
            )
            IconButton(
                onClick = {
                    viewModel.createNewSession("新会话")
                    onSessionSelected()
                },
                modifier = Modifier
                    .size(36.dp)
                    .background(primaryContainer, RoundedCornerShape(8.dp * uiSettings.spacingMultiplier))
                    .testTag("add_session_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建会话",
                    tint = onPrimaryContainer,
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
                                if (isActive) MaterialTheme.colorScheme.primaryContainer
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
                            contentDescription = "对话",
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = session.title,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        // 更多操作按钮（始终显示）
                        IconButton(
                            onClick = { showItemMenu = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多操作",
                                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 长按 / 点击 ⋮ 弹出的操作菜单
                    DropdownMenu(
                        expanded = showItemMenu,
                        onDismissRequest = { showItemMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Edit, contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("重命名", fontSize = 14.sp)
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
                                    Text("删除", fontSize = 14.sp,
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

        // ── 设置按钮与状态 ────────────────────────────────────────────
        val defaultProvider = modelConfigs.find { it.isDefaultProvider }
        val activeProviderName = defaultProvider?.name ?: "未设置"
        val activeModelId = defaultProvider?.selectedModelId ?: "未设置"

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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
                    fontSize = 11.sp,
                    color = onSurfaceColor.copy(alpha = 0.6f),
                    maxLines = 1
                )
                Text(
                    text = activeModelId,
                    fontSize = 10.sp,
                    color = primaryColor,
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
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "设置",
                        fontSize = 14.sp,
                        color = onSurfaceColor
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
                    contentDescription = "恢复默认配色",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
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
            title = { Text("删除会话") },
            text = {
                Text(
                    text = "确定要删除「${session.title}」吗？\n该会话的所有消息记录将被永久清除，无法恢复。",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
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
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetSession = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 重命名对话框 ──────────────────────────────────────────────────
    renameTargetSession?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTargetSession = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("会话名称") },
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
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetSession = null }) {
                    Text("取消")
                }
            }
        )
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
        "chat" -> activeSession?.title ?: "会话"
        "settings" -> "设置"
        else -> "AI"
    }

    Column {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = titleText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (currentTab == "chat" && defaultProvider != null) {
                        Text(
                            text = "提供商: ${defaultProvider.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "打开菜单",
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
                            text = "记忆同步中",
                            fontSize = 11.sp,
                            color = com.example.ui.theme.LocalCustomColors.current.success
                        )
                    }
                } else if (currentTab == "chat" && defaultProvider?.memoryModelId?.isNotBlank() == true) {
                    IconButton(onClick = { viewModel.triggerMemorySync(force = true) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "同步记忆",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatView(viewModel: ChatViewModel) {
    val messages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val isStreaming = viewModel.isStreaming
    val streamingThinking = viewModel.currentStreamingThinking
    val streamingBody = viewModel.currentStreamingBody
    val isThinkingFinished = viewModel.isThinkingFinished
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val mcpServerStates by viewModel.mcpServerStates.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 工具栏展开状态
    var showToolbar by remember { mutableStateOf(false) }
    // 模型选择器弹窗
    var showModelPicker by remember { mutableStateOf(false) }

    // Keep scrolled to bottom whenever message sizes change or streaming occurs
    LaunchedEffect(messages.size, streamingBody, streamingThinking) {
        // 在 reverseLayout 模式下，索引 0 就是底部。
        // 为了优化体验：只有当用户已经在底部（firstVisibleItemIndex == 0）时，才在流式输出期间跟随滚动。
        // 如果用户正在向上翻阅历史消息，不应强行拉回底部。
        val isAtBottom = listState.firstVisibleItemIndex == 0
        
        if (isStreaming) {
            if (isAtBottom) {
                listState.scrollToItem(0)
            }
        } else if (messages.isNotEmpty()) {
            // 非流式状态下（如刚发送或刚切回会话），通常期望看到最新消息
            listState.animateScrollToItem(0)
        }
    }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }
    // 当前实际使用的 Provider 和模型
    val activeProviderName = defaultProvider?.name ?: "未设置"
    val activeModelId = defaultProvider?.selectedModelId ?: ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Memories alert indicator chip row
        if (memories.isNotEmpty() || defaultProvider == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (defaultProvider == null) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "提醒",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "需设置全局自动提供商才能正常会话！",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "记忆",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已动态融合共 ${memories.size} 条长效学习偏好记忆",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- MCP 启动状态提示条 ---
        val startingServers = mcpServerStates.values.filter {
            it.status == com.example.mcp.McpServerStatus.STARTING
        }
        if (startingServers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "mcp_blink")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "mcp_alpha"
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(com.example.ui.theme.LocalCustomColors.current.warning.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MCP 工具加载中：${startingServers.joinToString("、") { it.server.name }}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- 聚合 Tool 消息展示逻辑并反转以适应 reverseLayout ---
        val processedMessages = remember(messages) {
            val list = mutableListOf<Any>()
            var currentToolGroup = mutableListOf<com.example.data.Message>()
            
            messages.forEach { msg ->
                if (msg.role == "tool") {
                    currentToolGroup.add(msg)
                } else {
                    if (currentToolGroup.isNotEmpty()) {
                        list.add(currentToolGroup.toList())
                        currentToolGroup.clear()
                    }
                    list.add(msg)
                }
            }
            if (currentToolGroup.isNotEmpty()) {
                list.add(currentToolGroup.toList())
            }
            list.reversed()
        }

        // Messages Box
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Streaming assistant response (在 reverseLayout 中 index 0 位于最底部)
            if (isStreaming) {
                item(key = "streaming_bubble") {
                    StreamingBubble(
                        thinkingText = streamingThinking,
                        bodyText = streamingBody,
                        isThinkingFinished = isThinkingFinished
                    )
                }
            }

            items(processedMessages, key = { 
                when(it) {
                    is com.example.data.Message -> it.id
                    is List<*> -> "group_${(it.firstOrNull() as? com.example.data.Message)?.id}"
                    else -> it.hashCode()
                }
            }) { item ->
                when (item) {
                    is com.example.data.Message -> BubbleMessage(
                        message = item,
                        onRetry = { viewModel.retryMessage(it) }
                    )
                    is List<*> -> {
                        // 渲染工具调用聚合条
                        @Suppress("UNCHECKED_CAST")
                        ToolGroupIndicator(messages = item as List<com.example.data.Message>)
                    }
                }
            }

            if (messages.isEmpty() && !isStreaming) {
                item {
                    EmptyChatGreeting(defaultProvider, memories)
                }
            }
        }

        // Send Area (Material design)
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f),
                    thickness = 0.5.dp
                )

                // ── 工具栏（展开时显示）──────────────────────────────
                AnimatedVisibility(
                    visible = showToolbar,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // 当前模型状态行
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(com.example.ui.theme.LocalCustomColors.current.success)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "当前模型: $activeModelId  ·  ${activeProviderName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // 工具按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 切换模型按钮
                            OutlinedCard(
                                modifier = Modifier
                                    .clickable { showModelPicker = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Build,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "切换模型",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 输入行 ────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // + 按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (showToolbar) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { showToolbar = !showToolbar },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showToolbar) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (showToolbar) "收起" else "展开工具",
                            tint = if (showToolbar) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Material styled text input block
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("输入消息...", fontSize = 15.sp) },
                        maxLines = 4,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field")
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Material filled icon button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (textInput.isNotBlank() && !isStreaming) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(enabled = textInput.isNotBlank() && !isStreaming) {
                                val toSend = textInput.trim()
                                if (toSend.isNotBlank() && !isStreaming) {
                                    viewModel.sendMessage(toSend)
                                    textInput = ""
                                    showToolbar = false
                                }
                            }
                            .testTag("chat_send_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            tint = if (textInput.isNotBlank() && !isStreaming) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // 模型选择器弹窗
    if (showModelPicker) {
        ProviderModelPicker(
            allConfigs = modelConfigs,
            allModelsFlow = { viewModel.getModelsByProviderFlow(it) },
            currentProviderId = defaultProvider?.id ?: 0L,
            currentModelId = activeModelId,
            onConfirm = { provider, modelId ->
                viewModel.setSessionOverrideModel(provider, modelId)
                showModelPicker = false
                showToolbar = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

@Composable
fun EmptyChatGreeting(config: ModelConfig?, memories: List<MemoryItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "AI 准备就绪",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(64.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    RoundedCornerShape(32.dp)
                )
                .padding(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "欢迎使用长效记忆 AI 助手",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "本应用特别支持双模型架构！一个主模型专门负责对话聊天，而一个副模型专门在每次回答后在后台分析提炼对话事实信息，跨越不同对话会话、重启不丢失！",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("状态一览:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (config != null) "✓ 当前提供商: ${config.name}" else "✗ 未设置 Provider",
                    fontSize = 12.sp,
                    color = if (config != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✓ 当前存储的长效记忆: ${memories.size} 条",
                    fontSize = 12.sp
                )
            }
        }
    }
}

data class ParsedMessageContent(
    val thinking: String?, // Null if no thinking tag
    val mainBody: String,
    val isThinkingFinished: Boolean
)

fun parseMessageContent(content: String): ParsedMessageContent {
    val thinkStartTag = "<think>"
    val thinkEndTag = "</think>"
    
    val startIndex = content.indexOf(thinkStartTag, ignoreCase = true)
    if (startIndex == -1) {
        return ParsedMessageContent(thinking = null, mainBody = content, isThinkingFinished = true)
    }
    
    val contentAfterStart = content.substring(startIndex + thinkStartTag.length)
    val endIndex = contentAfterStart.indexOf(thinkEndTag, ignoreCase = true)
    
    return if (endIndex != -1) {
        val thinkingText = contentAfterStart.substring(0, endIndex).trim()
        val remainingText = contentAfterStart.substring(endIndex + thinkEndTag.length).trim()
        ParsedMessageContent(
            thinking = thinkingText.ifEmpty { null },
            mainBody = remainingText,
            isThinkingFinished = true
        )
    } else {
        ParsedMessageContent(
            thinking = contentAfterStart.trim().ifEmpty { "正在深度思考..." },
            mainBody = "",
            isThinkingFinished = false
        )
    }
}

@Composable
fun ThinkingProcessPanel(
    thinkingText: String,
    isThinkingFinished: Boolean
) {
    var isExpanded by remember { mutableStateOf(!isThinkingFinished) }

    val containerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val textBg = MaterialTheme.colorScheme.surface
    val borderCol = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val accentColor = com.example.ui.theme.LocalCustomColors.current.accent
    val successColor = com.example.ui.theme.LocalCustomColors.current.success

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .border(0.5.dp, borderCol, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isThinkingFinished) {
                    val infiniteTransition = rememberInfiniteTransition(label = "think_pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.8f,
                        targetValue = 1.2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseScale"
                    )
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Thinking",
                        tint = accentColor,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = successColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (!isThinkingFinished) "AI 正在深度思考中（实时吐流）..." else "深度思考过程 (已折叠，点击展开)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                HorizontalDivider(color = borderCol, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(textBg)
                        .padding(10.dp)
                ) {
                    if (!isThinkingFinished) {
                        ChunkedStreamingText(
                            text = thinkingText,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                    } else {
                        dev.jeziellago.compose.markdowntext.MarkdownText(
                            markdown = thinkingText,
                            style = androidx.compose.ui.text.TextStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            ),
                            syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                            syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolGroupIndicator(messages: List<com.example.data.Message>) {
    // 聚合逻辑：显示 "Tool use [name] x [count]"
    val totalCount = messages.size
    val label = if (totalCount > 1) "Tools used x$totalCount" else "Tool used"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        var showDetails by remember { mutableStateOf(false) }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = { showDetails = !showDetails },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            AnimatedVisibility(visible = showDetails) {
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .widthIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    messages.forEach { msg ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Result: ${msg.content.take(100)}${if(msg.content.length > 100) "..." else ""}",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BubbleMessage(
    message: com.example.data.Message,
    onRetry: (com.example.data.Message) -> Unit = {}
) {
    val isUser = message.role == "user"
    var showMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current

    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (isUser) {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 4.dp
                    ),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 290.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    pressOffset = DpOffset(offset.x.toDp(), offset.y.toDp())
                                    showMenu = true
                                }
                            )
                        }
                ) {
                    Text(
                        text = message.content,
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(14.dp, 10.dp)
                    )
                }
            } else {
                val parsed = parseMessageContent(message.content)
                Column(
                    modifier = Modifier.widthIn(max = 290.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (parsed.thinking != null) {
                        ThinkingProcessPanel(
                            thinkingText = parsed.thinking,
                            isThinkingFinished = parsed.isThinkingFinished
                        )
                    }
                    if (parsed.mainBody.isNotEmpty()) {
                        Surface(
                            color = bubbleColor,
                            shape = RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 20.dp
                            ),
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = { offset ->
                                            pressOffset = DpOffset(offset.x.toDp(), offset.y.toDp())
                                            showMenu = true
                                        }
                                    )
                                }
                        ) {
                            dev.jeziellago.compose.markdowntext.MarkdownText(
                                markdown = parsed.mainBody,
                                style = androidx.compose.ui.text.TextStyle(
                                    color = textColor,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                ),
                                syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                                syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp, 10.dp)
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = pressOffset,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            if (!isUser) {
                DropdownMenuItem(
                    text = { Text("重试") },
                    onClick = {
                        showMenu = false
                        onRetry(message)
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                )
            }
            DropdownMenuItem(
                text = { Text("复制内容") },
                onClick = {
                    showMenu = false
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("OmniChat", message.content)
                    clipboard.setPrimaryClip(clip)
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
            )
        }
    }
}

@Composable
fun StreamingBubble(
    thinkingText: String,
    bodyText: String,
    isThinkingFinished: Boolean
) {
    val bubbleColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val isThinkingFallback = thinkingText.isEmpty() && bodyText.isEmpty() && !isThinkingFinished

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = Modifier.widthIn(max = 290.dp)) {
            if (isThinkingFallback) {
                ThinkingProcessPanel(
                    thinkingText = "正在唤醒深度推理模型并深度构思中.....",
                    isThinkingFinished = false
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (thinkingText.isNotEmpty()) {
                        ThinkingProcessPanel(
                            thinkingText = thinkingText,
                            isThinkingFinished = isThinkingFinished
                        )
                    }
                    if (bodyText.isNotEmpty() || !isThinkingFinished) {
                        val displayText = bodyText.ifEmpty { 
                            if (!isThinkingFinished) "" else "正在构思答复内容..." 
                        }
                        if (displayText.isNotEmpty()) {
                            Surface(
                                color = bubbleColor,
                                shape = RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomStart = 4.dp,
                                    bottomEnd = 20.dp
                                ),
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp, 10.dp)) {
                                    ChunkedStreamingText(
                                        text = displayText,
                                        textColor = textColor,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                    val pulseAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0.2f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(600, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "cursor_alpha"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(width = 4.dp, height = 18.dp)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                text = "助手正在输入...",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun ModelsConfigView(viewModel: ChatViewModel) {
    val configs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    var isAddingNew by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<ModelConfig?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "API 提供商管理库",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Button(
                    onClick = { isAddingNew = true },
                    modifier = Modifier.testTag("add_config_btn")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "新增提供商")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新增提供商", fontSize = 13.sp)
                }
            }

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前未配置任何 API 提供商。\n点击右上角“新增提供商”开始添加！",
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(configs) { config ->
                        ModelConfigCard(
                            config = config,
                            onEdit = { configToEdit = config },
                            onDelete = { viewModel.deleteConfig(config) },
                            onCheckPrimary = { viewModel.setDefaultProvider(config.id) },
                            onCheckMemory = {}
                        )
                    }
                }
            }
        }

        if (isAddingNew) {
            ModelConfigDialog(
                viewModel = viewModel,
                config = null,
                onDismiss = {
                    isAddingNew = false
                    viewModel.clearFetchedModels()
                },
                onSave = { updated, models ->
                    viewModel.createOrUpdateConfig(updated, models)
                    isAddingNew = false
                    viewModel.clearFetchedModels()
                }
            )
        }

        if (configToEdit != null) {
            ModelConfigDialog(
                viewModel = viewModel,
                config = configToEdit,
                onDismiss = {
                    configToEdit = null
                    viewModel.clearFetchedModels()
                },
                onSave = { updated, models ->
                    viewModel.createOrUpdateConfig(updated, models)
                    configToEdit = null
                    viewModel.clearFetchedModels()
                }
            )
        }
    }
}

@Composable
fun ModelConfigCard(
    config: ModelConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCheckPrimary: () -> Unit,
    onCheckMemory: () -> Unit
) {
    val cardBackground = MaterialTheme.colorScheme.surface
    val borderStrokeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${config.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.3).sp
                    )
                }

                // Default status indicator
                if (config.isDefaultProvider) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "默认提供商",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Endpoint display
            Row {
                Text(
                    text = "Endpoint: ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = config.endpoint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Masked API Key display
            Row {
                Text(
                    text = "API Key: ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val maskedKey = if (config.apiKey.length > 8) {
                    config.apiKey.take(4) + "••••••••" + config.apiKey.takeLast(4)
                } else {
                    "••••••••"
                }
                Text(
                    text = maskedKey,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Custom headers count display
            val headerCount = try {
                val obj = org.json.JSONObject(config.customHeaders)
                obj.length()
            } catch (e: Exception) { 0 }
            if (headerCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = "自定义请求头: ",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$headerCount 个",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // iOS-Style Toggles (Grouped Settings Rows)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCheckPrimary() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp))
                                .padding(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                Text(
                    text = "设为默认配置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "将此 API 提供商作为全局使用",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        Switch(
            checked = config.isDefaultProvider,
            onCheckedChange = { onCheckPrimary() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = com.example.ui.theme.LocalCustomColors.current.success,
                uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = Color.Transparent,
                checkedBorderColor = Color.Transparent
            ),
            modifier = Modifier.scale(0.82f)
        )
    }
}

Spacer(modifier = Modifier.height(10.dp))
HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
Spacer(modifier = Modifier.height(8.dp))

// Edit / Delete Actions row
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically
) {
    TextButton(
        onClick = onEdit,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    ) {
        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("编辑", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }

    Spacer(modifier = Modifier.width(16.dp))

    TextButton(
        onClick = onDelete,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
        }
    }
}

@Composable
fun ModelConfigDialog(
    viewModel: ChatViewModel,
    config: ModelConfig?,
    onDismiss: () -> Unit,
    onSave: (ModelConfig, List<FetchedModel>) -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "") }
    var endpoint by remember { mutableStateOf(config?.endpoint ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var selectedModelId by remember { mutableStateOf(config?.selectedModelId?.takeIf { it.isNotBlank() } ?: "gpt-4o") }

    var isApiKeyVisible by remember { mutableStateOf(false) }

    // Custom headers: list of (key, value) pairs for editing
    var headerPairs by remember {
        mutableStateOf<List<Pair<String, String>>>(
            try {
                val json = org.json.JSONObject(config?.customHeaders ?: "{}")
                json.keys().asSequence().map { k: String -> k to json.optString(k) }.toList()
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    val fetchedModels = viewModel.fetchedModels
    val isFetching = viewModel.isFetchingModels
    val fetchError = viewModel.modelFetchError

    // Query previously saved models from DB
    val savedModelsState = remember(config?.id) {
        if (config != null && config.id > 0) {
            viewModel.getModelsByProviderFlow(config.id)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    val storedModels = savedModelsState.value
    var dialogModels by remember { mutableStateOf<List<FetchedModel>>(emptyList()) }

    // Sync dialogModels with fetchedModels or storedModels
    LaunchedEffect(fetchedModels, storedModels) {
        dialogModels = if (fetchedModels.isNotEmpty()) {
            fetchedModels
        } else {
            storedModels
        }
    }

    // Clear fetched list upon entering dialog just to start fresh
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearFetchedModels()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (config == null) "新增提供商配置" else "编辑提供商配置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Provider ID Input (Name field)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("提供商 ID (Provider ID)") },
                    placeholder = { Text("如: openai, deepseek, silicon-flow") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                // Base Endpoint URL Input
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("OpenAI 兼容 Endpoint") },
                    placeholder = { Text("如: https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                // API Key Input
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.Info else Icons.Default.Lock,
                                contentDescription = if (isApiKeyVisible) "隐藏密钥" else "显示密钥"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Custom Headers Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义请求头 (Custom Headers):",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = { headerPairs = headerPairs + ("" to "") },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("添加", fontSize = 12.sp)
                    }
                }

                if (headerPairs.isEmpty()) {
                    Text(
                        text = "暂无自定义请求头",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        headerPairs.forEachIndexed { index, pair ->
                            val hKey = pair.first
                            val hVal = pair.second
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = hKey,
                                    onValueChange = { newKey ->
                                        val updated = headerPairs.toMutableList()
                                        updated[index] = newKey to hVal
                                        headerPairs = updated
                                    },
                                    label = { Text("Header 名", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                                OutlinedTextField(
                                    value = hVal,
                                    onValueChange = { newVal ->
                                        val updated = headerPairs.toMutableList()
                                        updated[index] = hKey to newVal
                                        headerPairs = updated
                                    },
                                    label = { Text("值", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                                IconButton(
                                    onClick = {
                                        val updated = headerPairs.toMutableList()
                                        updated.removeAt(index)
                                        headerPairs = updated
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Auto-fetching section
                Text(
                    text = "自动拉取并解析可用模型:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Button(
                    onClick = {
                        val headersJson = org.json.JSONObject().apply {
                            headerPairs.filter { it.first.isNotBlank() }.forEach { put(it.first, it.second) }
                        }.toString()
                        viewModel.fetchModelsAndSave(endpoint, apiKey, config?.id ?: 0L, headersJson)
                    },
                    enabled = !isFetching && endpoint.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("正在拉取中...", fontSize = 12.sp)
                    } else {
                        Text("一键获取当前 Endpoint 的可用模型", fontSize = 12.sp)
                    }
                }

                if (fetchError != null) {
                    Text(
                        text = "拉取错误: $fetchError",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // If fetched models exist, list them as clickable cards
                if (dialogModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "可用模型列表 (点击模型名选择；点击下方标签可**手动修正** 思考/视觉/工具 调用能力):",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.example.ui.theme.LocalCustomColors.current.success,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dialogModels.take(60).forEach { m ->
                            val isSelected = selectedModelId == m.modelId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedModelId = m.modelId },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                ),
                                border = if (isSelected) BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary
                                ) else null
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = m.modelId,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = if (isSelected) 
                                                MaterialTheme.colorScheme.onPrimaryContainer 
                                            else 
                                                MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        
                                        // Context Badge
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (isSelected) 
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                                                    else 
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "Context: ${m.contextSize}",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) 
                                                    MaterialTheme.colorScheme.onPrimaryContainer 
                                                else 
                                                    MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    // Capabilities indicators (Interactive checkboxes/badges style)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        InteractiveCapabilityBadge(
                                            text = "💭 思考/推理",
                                            enabled = m.hasThinking,
                                            color = com.example.ui.theme.LocalCustomColors.current.success,
                                            onClick = {
                                                val index = dialogModels.indexOf(m)
                                                if (index != -1) {
                                                    val updatedList = dialogModels.toMutableList()
                                                    updatedList[index] = m.copy(hasThinking = !m.hasThinking)
                                                    dialogModels = updatedList
                                                }
                                            }
                                        )
                                        InteractiveCapabilityBadge(
                                            text = "👁️ 视觉",
                                            enabled = m.hasVision,
                                            color = MaterialTheme.colorScheme.primary,
                                            onClick = {
                                                val index = dialogModels.indexOf(m)
                                                if (index != -1) {
                                                    val updatedList = dialogModels.toMutableList()
                                                    updatedList[index] = m.copy(hasVision = !m.hasVision)
                                                    dialogModels = updatedList
                                                }
                                            }
                                        )
                                        InteractiveCapabilityBadge(
                                            text = "🛠️ 工具调用",
                                            enabled = m.hasToolUse,
                                            color = com.example.ui.theme.LocalCustomColors.current.warning,
                                            onClick = {
                                                val index = dialogModels.indexOf(m)
                                                if (index != -1) {
                                                    val updatedList = dialogModels.toMutableList()
                                                    updatedList[index] = m.copy(hasToolUse = !m.hasToolUse)
                                                    dialogModels = updatedList
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank()) name = "custom-provider"
                            if (selectedModelId.isBlank()) selectedModelId = "gpt-4o"
                            val headersJson = org.json.JSONObject().apply {
                                headerPairs.filter { it.first.isNotBlank() }.forEach { put(it.first, it.second) }
                            }.toString()
                            onSave(
                                ModelConfig(
                                    id = config?.id ?: 0,
                                    name = name,
                                    endpoint = endpoint,
                                    apiKey = apiKey,
                                    selectedModelId = selectedModelId,
                                    memoryModelId = selectedModelId,
                                    isDefaultProvider = config?.isDefaultProvider ?: false,
                                    enableThinking = false,
                                    thinkingEffort = "medium",
                                    customHeaders = headersJson
                                ),
                                dialogModels
                            )
                        }
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveCapabilityBadge(text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (enabled) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                RoundedCornerShape(4.dp)
            )
            .border(
                width = 0.5.dp,
                color = if (enabled) color.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun CapabilityBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/**
 * Layout helper flow row for chips wrapping
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    itemSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
    ) {
        content()
    }
}

/**
 * 通用的两步模型选择器：先选 Provider，再选模型。
 * 用于副模型配置和聊天临时切换。
 */
@Composable
fun ProviderModelPicker(
    allConfigs: List<ModelConfig>,
    allModelsFlow: (Long) -> kotlinx.coroutines.flow.Flow<List<FetchedModel>>,
    currentProviderId: Long,   // 0 = 未选 / 与主相同
    currentModelId: String,
    onConfirm: (provider: ModelConfig, modelId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val successColor = com.example.ui.theme.LocalCustomColors.current.success
    val infoColor = com.example.ui.theme.LocalCustomColors.current.info
    val accentColor = com.example.ui.theme.LocalCustomColors.current.accent

    // 步骤：0 = 选 Provider，1 = 选模型
    var step by remember { mutableStateOf(0) }
    var pickedProvider by remember {
        mutableStateOf(allConfigs.find { it.id == currentProviderId } ?: allConfigs.firstOrNull())
    }

    val modelsState = remember(pickedProvider?.id) {
        val id = pickedProvider?.id ?: 0L
        if (id > 0L) allModelsFlow(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val models = modelsState.value

    var pickedModelId by remember { mutableStateOf(currentModelId) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step == 1) {
                        IconButton(
                            onClick = { step = 0 },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (step == 0) "选择 Provider" else "选择模型",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (step == 1 && pickedProvider != null) {
                            Text(
                                text = pickedProvider!!.name,
                                fontSize = 11.sp,
                                color = mutedTextColor
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = mutedTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = dividerColor,
                    thickness = 0.5.dp
                )

                if (step == 0) {
                    // ── 步骤 0：Provider 列表 ──────────────────────────
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allConfigs) { config ->
                            val isSelected = config.id == pickedProvider?.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pickedProvider = config
                                        pickedModelId = if (config.id == currentProviderId) currentModelId else ""
                                        step = 1
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else cardBg
                                ),
                                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = config.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (config.isDefaultProvider) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "默认",
                                                    fontSize = 9.sp,
                                                    color = successColor,
                                                    modifier = Modifier
                                                        .background(successColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = config.endpoint,
                                            fontSize = 10.sp,
                                            color = mutedTextColor,
                                            maxLines = 1
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = mutedTextColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── 步骤 1：模型列表 ──────────────────────────────
                    if (models.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = mutedTextColor,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "该 Provider 暂无已保存的模型列表",
                                    fontSize = 13.sp,
                                    color = mutedTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "请先在「模型配置」中拉取模型",
                                    fontSize = 11.sp,
                                    color = mutedTextColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(models) { model ->
                                val isSelected = model.modelId == pickedModelId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pickedModelId = model.modelId },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else cardBg
                                    ),
                                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.modelId,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                // Context badge
                                                Text(
                                                    text = model.contextSize,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (model.hasThinking) Text(
                                                    "💭 思考", fontSize = 9.sp, color = successColor,
                                                    modifier = Modifier.background(successColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (model.hasVision) Text(
                                                    "👁️ 视觉", fontSize = 9.sp, color = infoColor,
                                                    modifier = Modifier.background(infoColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (model.hasToolUse) Text(
                                                    "🛠️ 工具", fontSize = 9.sp, color = accentColor,
                                                    modifier = Modifier.background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "已选",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 确认按钮
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val p = pickedProvider ?: return@Button
                                val m = pickedModelId.takeIf { it.isNotBlank() } ?: return@Button
                                onConfirm(p, m)
                            },
                            enabled = pickedProvider != null && pickedModelId.isNotBlank()
                        ) {
                            Text("确认选择")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryModelSelectorCard(
    defaultProvider: ModelConfig?,
    allConfigs: List<ModelConfig>,
    allModelsFlow: (Long) -> kotlinx.coroutines.flow.Flow<List<FetchedModel>>,
    onModelSelected: (provider: ModelConfig, modelId: String) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val borderCol = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    
    val currentMemoryModelId = defaultProvider?.memoryModelId ?: ""
    val currentMemoryProviderId = defaultProvider?.memoryProviderId ?: 0L

    var showPicker by remember { mutableStateOf(false) }

    // 找到副模型所属的 Provider 名称
    val memoryProviderName = if (currentMemoryProviderId > 0L) {
        allConfigs.find { it.id == currentMemoryProviderId }?.name ?: "未知 Provider"
    } else {
        defaultProvider?.name ?: ""
    }

    // 找到副模型的能力信息（从对应 Provider 的模型列表里查）
    val memoryProviderModels = remember(currentMemoryProviderId, defaultProvider?.id) {
        val id = if (currentMemoryProviderId > 0L) currentMemoryProviderId else (defaultProvider?.id ?: 0L)
        if (id > 0L) allModelsFlow(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val currentModelInfo = memoryProviderModels.value.find { it.modelId == currentMemoryModelId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = surface
        ),
        border = BorderStroke(0.5.dp, borderCol)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(primaryColor, RoundedCornerShape(7.dp))
                        .padding(5.dp)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("副模型配置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Text("用于记忆优化分析 & 会话标题生成", fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f))
                }
            }

            HorizontalDivider(color = borderCol, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 10.dp))

            if (defaultProvider == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(10.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("请先在「模型配置」页设置默认提供商", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("当前副模型", fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 4.dp))

                // 当前选择展示 + 点击打开选择器
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { showPicker = true },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (currentMemoryModelId.isBlank()) {
                                Text("未选择（将使用主模型）", fontSize = 13.sp, color = onSurface.copy(alpha = 0.5f))
                            } else {
                                Text(
                                    text = currentMemoryModelId,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = onSurface,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    // Provider 来源标签
                                    Text(
                                        text = memoryProviderName,
                                        fontSize = 9.sp,
                                        color = primaryColor,
                                        modifier = Modifier.background(primaryColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                    if (currentModelInfo != null) {
                                        Text(
                                            text = currentModelInfo.contextSize,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                        if (currentModelInfo.hasThinking) Text("💭", fontSize = 9.sp)
                                        if (currentModelInfo.hasVision) Text("👁️", fontSize = 9.sp)
                                        if (currentModelInfo.hasToolUse) Text("🛠️", fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                        Icon(Icons.Default.Edit, contentDescription = "选择", tint = primaryColor, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "副模型在每次对话后台运行，负责提炼记忆条目并生成会话标题。可选择任意 Provider 的模型，建议用速度快、成本低的小模型。",
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.6f),
                    lineHeight = 15.sp
                )
            }
        }
    }

    if (showPicker) {
        ProviderModelPicker(
            allConfigs = allConfigs,
            allModelsFlow = allModelsFlow,
            currentProviderId = currentMemoryProviderId.takeIf { it > 0L } ?: (defaultProvider?.id ?: 0L),
            currentModelId = currentMemoryModelId,
            onConfirm = { provider, modelId ->
                onModelSelected(provider, modelId)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
fun MemoryAndPromptView(viewModel: ChatViewModel) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val templates by viewModel.promptTemplates.collectAsStateWithLifecycle()
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()

    var manualMemoryText by remember { mutableStateOf("") }
    var activeSubTab by remember { mutableStateOf("memory") }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Tab selectors
        TabRow(
            selectedTabIndex = if (activeSubTab == "memory") 0 else 1,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Tab(selected = activeSubTab == "memory", onClick = { activeSubTab = "memory" }) {
                Text("长效对话记忆库 (${memories.size})", modifier = Modifier.padding(12.dp), fontSize = 14.sp)
            }
            Tab(selected = activeSubTab == "prompts", onClick = { activeSubTab = "prompts" }) {
                Text("系统Prompt模板", modifier = Modifier.padding(12.dp), fontSize = 14.sp)
            }
        }

        if (activeSubTab == "memory") {
            // ── 副模型配置卡片 ──────────────────────────────────────────
            MemoryModelSelectorCard(
                defaultProvider = defaultProvider,
                allConfigs = modelConfigs,
                allModelsFlow = { viewModel.getModelsByProviderFlow(it) },
                onModelSelected = { provider, modelId ->
                    viewModel.updateMemoryModelId(modelId, provider.id)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Memories section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualMemoryText,
                    onValueChange = { manualMemoryText = it },
                    placeholder = { Text("手动给AI灌输永久首选项偏好...", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (manualMemoryText.isNotBlank()) {
                            viewModel.insertManualMemory(manualMemoryText.trim())
                            manualMemoryText = ""
                        }
                    },
                    modifier = Modifier.height(52.dp)
                ) {
                    Text("新增")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI在后台持续自我反省得出的记忆条目:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (memories.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearAllMemories() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("清空全部记忆", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂长效记忆库空。你可以通过在对话中提到 preference，或者使用副角色模型在每次聊天完后台自动反省保存！",
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memories) { memory ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("memory_item_${memory.id}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = memory.content,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = { viewModel.deleteMemoryItem(memory.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Prompt custom templates section
            var newTemplateName by remember { mutableStateOf("") }
            var newTemplateText by remember { mutableStateOf("") }
            var isCreatingTemp by remember { mutableStateOf(false) }

            if (isCreatingTemp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("创建自定义 System 模板", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newTemplateName,
                            onValueChange = { newTemplateName = it },
                            label = { Text("模版说明/标题 (如: Kotlin 写法大师)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newTemplateText,
                            onValueChange = { newTemplateText = it },
                            label = { Text("System Prompt 文字") },
                            placeholder = { Text("必须包含占位符 [CROSS_SESSION_MEMORY] 自动编排记忆数据") },
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { isCreatingTemp = false }) {
                                Text("取消")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newTemplateName.isNotBlank() && newTemplateText.isNotBlank()) {
                                        viewModel.insertTemplate(
                                            PromptTemplate(
                                                name = newTemplateName.trim(),
                                                templateText = newTemplateText.trim()
                                            )
                                        )
                                        newTemplateName = ""
                                        newTemplateText = ""
                                        isCreatingTemp = false
                                    }
                                }
                            ) {
                                Text("保存模板")
                            }
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        isCreatingTemp = true
                        newTemplateText = "You are an AI Coding master.\n\nHere are historical preferences about the user:\n[CROSS_SESSION_MEMORY]"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "新增")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新增 System 模版", fontSize = 13.sp)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(templates) { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("prompt_template_${template.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = template.isActive,
                                        onClick = { viewModel.selectTemplate(template.id) },
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = template.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (!template.isActive && templates.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.deleteTemplate(template) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = template.templateText,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 5
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
