package com.omnichat.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnichat.mcp.McpViewModel
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.LocalWindowSizeClass
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.toComposeColor
import androidx.compose.ui.res.stringResource
import com.omnichat.R
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.viewmodel.ChatViewModel
import com.omnichat.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import com.omnichat.mcp.AskUserManager
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.PermissionResult
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow

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
                    .widthIn(max = 560.dp)
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
                                text = uiText("dialog.permission.title", R.string.dialog_permission_title),
                                fontSize = (16 * fsPerm).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = resolvedFontFamilyPerm,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (permissionRequest?.accessType == com.omnichat.data.FileAccessType.WRITE)
                                    uiText("dialog.permission.subtitle.write", R.string.dialog_permission_subtitle_write)
                                else
                                    uiText("dialog.permission.subtitle.read", R.string.dialog_permission_subtitle_read),
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
                        text = uiText("dialog.permission.desc", R.string.dialog_permission_desc),
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
                            Text(uiText("dialog.permission.allow.always", R.string.dialog_permission_allow_always), fontFamily = resolvedFontFamilyPerm, fontSize = (13 * fsPerm).sp)
                        }
                        OutlinedButton(
                            onClick = { permissionRequest?.onResult?.invoke(PermissionResult.ALLOW_ONCE) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(uiText("dialog.permission.allow.once", R.string.dialog_permission_allow_once), fontFamily = resolvedFontFamilyPerm, fontSize = (13 * fsPerm).sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = { permissionRequest?.onResult?.invoke(PermissionResult.DONT_ASK_AGAIN) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp)
                            ) {
                                Text(uiText("dialog.permission.dont.ask", R.string.dialog_permission_dont_ask), fontFamily = resolvedFontFamilyPerm, fontSize = (12 * fsPerm).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = { permissionRequest?.onResult?.invoke(PermissionResult.DENY) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape((cornerPerm.value - 2).coerceAtLeast(0f).dp)
                            ) {
                                Text(uiText("dialog.permission.deny", R.string.dialog_permission_deny), fontFamily = resolvedFontFamilyPerm, fontSize = (12 * fsPerm).sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier

    val sidebarColors = com.omnichat.ui.theme.LocalSidebarColors.current
    val windowSizeClass = LocalWindowSizeClass.current
    val isExpandedScreen = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    // 内容区域的 Scaffold，共享给两种布局模式
    @Composable
    fun ContentScaffold(onOpenDrawer: () -> Unit) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                MainTopAppBar(
                    currentTab = currentTab,
                    viewModel = viewModel,
                    onOpenDrawer = onOpenDrawer,
                    isExpandedScreen = isExpandedScreen
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp * (spacingMultiplier - 1f))
                    .consumeWindowInsets(paddingValues),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(
                            if (currentTab == "chat") Modifier.widthIn(max = 720.dp)
                            else Modifier.fillMaxWidth()
                        )
                ) {
                    when (currentTab) {
                        "chat" -> ChatView(viewModel)
                        "settings" -> SettingsView(viewModel, mcpViewModel)
                    }
                }
            }
        }
    }

    // 侧边栏内容，共享给两种布局模式
    @Composable
    fun SidebarContent(onSessionSelected: () -> Unit, onSettingsClick: () -> Unit) {
        SessionSidebarPanel(
            viewModel = viewModel,
            onSessionSelected = onSessionSelected,
            onSettingsClick = onSettingsClick
        )
    }

    if (isExpandedScreen) {
        // 平板模式：Row 布局，侧边栏可收起
        val uiSettingsForSidebar = LocalUISettings.current
        var sidebarExpanded by remember { mutableStateOf(uiSettingsForSidebar.sidebarExpanded) }
        val settingsViewModel: SettingsViewModel = viewModel()

        Row(modifier = modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = sidebarExpanded,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    color = sidebarColors.background
                ) {
                    SidebarContent(
                        onSessionSelected = { currentTab = "chat" },
                        onSettingsClick = { currentTab = "settings" }
                    )
                }
            }
            ContentScaffold(onOpenDrawer = {
                sidebarExpanded = !sidebarExpanded
                settingsViewModel.setSidebarExpanded(sidebarExpanded)
            })
        }
    } else {
        // 窄屏：ModalNavigationDrawer，抽屉滑出
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = sidebarColors.background,
                    drawerShape = RoundedCornerShape(topEnd = uiSettings.cornerRadiusDp.dp, bottomEnd = uiSettings.cornerRadiusDp.dp)
                ) {
                    SidebarContent(
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
            ContentScaffold(onOpenDrawer = { scope.launch { drawerState.open() } })
        }
    }
}

// SettingsView is now defined in SettingsScreen.kt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    currentTab: String,
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    isExpandedScreen: Boolean = false
) {
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSession = sessions.find { it.id == activeSessionId }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }

    val titleText = when (currentTab) {
        "chat" -> activeSession?.title ?: uiText("topbar.title.chat", R.string.topbar_title_chat)
        "settings" -> uiText("topbar.title.settings", R.string.topbar_title_settings)
        else -> "AI"
    }

    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    // 模型选择器弹窗状态
    var showModelPicker by remember { mutableStateOf(false) }

    Column {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (currentTab == "chat" && defaultProvider != null) {
                        val subtitleColor = uiSettings.topbarSubtitleColor.toComposeColor()
                            .let { if (it != androidx.compose.ui.graphics.Color.Unspecified) it else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) }
                        val modelDisplay = defaultProvider.selectedModelId.ifEmpty { "—" }
                        val annotated = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append("Provider: ") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(defaultProvider.name) }
                            append("  ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append("Model: ") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(modelDisplay) }
                        }
                        Surface(
                            onClick = { showModelPicker = true },
                            shape = RoundedCornerShape(20.dp),
                            color = subtitleColor.copy(alpha = 0.1f),
                            modifier = Modifier.offset(y = (-2).dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = annotated,
                                    fontSize = (11 * fs).sp,
                                    fontFamily = resolvedFontFamily,
                                    color = subtitleColor,
                                    maxLines = 2,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = titleText,
                            fontSize = (17 * fs).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = resolvedFontFamily,
                            letterSpacing = (-0.4).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = uiText("topbar.menu.open", R.string.topbar_menu_open),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            actions = {
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

    // 模型选择器弹窗
    if (showModelPicker && currentTab == "chat") {
        ProviderModelPicker(
            allConfigs = modelConfigs,
            allModelsFlow = { viewModel.getModelsByProviderFlow(it) },
            currentProviderId = defaultProvider?.id ?: 0L,
            currentModelId = defaultProvider?.selectedModelId ?: "",
            onConfirm = { provider, modelId ->
                viewModel.setSessionOverrideModel(provider, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}
