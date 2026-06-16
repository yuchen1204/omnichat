package com.omnichat.ui.screens

import android.Manifest
import kotlin.math.roundToInt
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.omnichat.data.MemoryItem
import com.omnichat.data.ModelConfig
import com.omnichat.ui.components.ChunkedStreamingText
import com.omnichat.ui.components.ToolGroupCard

import com.omnichat.ui.components.toUIModel
import com.omnichat.ui.theme.LocalChatFontScale
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import androidx.compose.ui.res.stringResource
import com.omnichat.R
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.viewmodel.ChatViewModel
import com.omnichat.mcp.McpRuntimeManager
import org.json.JSONArray
import kotlinx.coroutines.launch

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
    val context = LocalContext.current

    // Agent mode toggle support
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val currentSession = sessions.find { it.id == activeSessionId }

    // 字体设置
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale  // 全局 UI 字体缩放
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 工具栏展开状态
    var showToolbar by remember { mutableStateOf(false) }
    // 模型选择器弹窗
    var showModelPicker by remember { mutableStateOf(false) }

    // 图片选择相关状态（支持多图）
    var selectedImagePaths by remember { mutableStateOf<List<String>>(emptyList()) }

    // 编辑消息模式
    val editingMessageId = viewModel.editingMessageId
    val isEditing = editingMessageId != null

    // 进入编辑模式时，将消息内容填入输入框
    LaunchedEffect(editingMessageId) {
        if (editingMessageId != null) {
            val msg = messages.find { it.id == editingMessageId }
            if (msg != null) {
                textInput = msg.content
            }
        }
    }

    // 当前模型是否支持视觉
    val currentModelHasVision = viewModel.currentModelHasVision



    // 图片选择器 (Photo Picker - 多选)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris: List<Uri> ->
        val newPaths = uris.mapNotNull { uri ->
            try {
                val tempFile = java.io.File(
                    context.cacheDir,
                    "picked_${System.currentTimeMillis()}_${uri.lastPathSegment?.take(20) ?: "img"}.jpg"
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.absolutePath
            } catch (e: Exception) {
                null
            }
        }
        selectedImagePaths = selectedImagePaths + newPaths
    }

    // 相机权限检查
    val cameraPermissionState = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionState.value = isGranted
    }

    // 相机启动器（全分辨率拍照）
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            selectedImagePaths = selectedImagePaths + tempCameraUri.toString()
        }
    }

    // 自动滚动到底部的控制逻辑
    // 核心原则：只在用户已经在底部时跟随滚动，用户主动上翻时不干扰
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // 检测用户手动滚动：拖动中或惯性滑动中（isScrollInProgress 捕获 fling），
    // 且不在底部 → 暂停自动滚动；回到底部 → 恢复
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState.canScrollBackward, isDragged, listState.isScrollInProgress) {
        if (!listState.canScrollBackward) {
            autoScrollEnabled = true
        } else if (isDragged || listState.isScrollInProgress) {
            autoScrollEnabled = false
        }
    }

    // 新消息到来时的自动滚动（使用 scrollToItem 避免动画与用户手势冲突）
    // 注意：静默模式下 tool 消息不渲染，需要用过滤后的数量，否则隐藏 tool 消息也会触发滚动
    val visibleMessageCount = remember(messages, uiSettings.silentToolGroups) {
        val silentGroups = uiSettings.silentToolGroups
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (silentGroups.isEmpty()) {
            messages.size
        } else {
            val wildcard = silentGroups.contains("*")
            // 构建 toolCallId → group 查找表
            val toolGroupLookup = mutableMapOf<String, String>()
            messages.forEach { msg ->
                if (msg.role == "assistant" && !msg.toolCallsJson.isNullOrBlank()) {
                    try {
                        val arr = JSONArray(msg.toolCallsJson)
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            val function = item.optJSONObject("function") ?: continue
                            val name = function.optString("name")
                            val group = McpRuntimeManager.builtinToolGroups[name]
                            if (id.isNotEmpty() && group != null) {
                                toolGroupLookup[id] = group
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            messages.count { msg ->
                if (msg.role != "tool") true
                else {
                    val group = msg.toolCallId?.let { toolGroupLookup[it] }
                    !wildcard && (group == null || group !in silentGroups)
                }
            }
        }
    }
    LaunchedEffect(visibleMessageCount) {
        if (autoScrollEnabled && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // 流式输出时的跟随滚动（使用 scrollToItem 即时定位，避免 animateScrollToItem 动画抖动）
    LaunchedEffect(Unit) {
        snapshotFlow { streamingBody?.length to streamingThinking?.length }
            .collect {
                if (autoScrollEnabled && isStreaming) {
                    listState.scrollToItem(0)
                }
            }
    }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }
    // 当前实际使用的 Provider 和模型
    val activeProviderName = defaultProvider?.name ?: uiText("chat.not.set", R.string.chat_not_set)
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
                        contentDescription = uiText("chat.4c423b81", R.string.chat_reminder),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiText("chat.no.provider.warning", R.string.chat_no_provider_warning),
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = uiText("chat.b489ee1d", R.string.chat_memory),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiText("chat.memory.injected", R.string.chat_memory_injected).format(memories.size),
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- MCP 启动状态提示条 ---
        val startingServers = mcpServerStates.values.filter {
            it.status == com.omnichat.mcp.McpServerStatus.STARTING
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
                        .background(com.omnichat.ui.theme.LocalCustomColors.current.warning.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = uiText("chat.mcp.loading", R.string.chat_mcp_loading).format(startingServers.joinToString(uiText("chat.separator", R.string.chat_separator)) { it.server.name }),
                    fontSize = (11 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val uiModelMessages = remember(messages) {
            messages.map { it.toUIModel() }
        }

        // --- 聚合 Tool 消息展示逻辑并反转以适应 reverseLayout ---
        val processedMessages = remember(messages, uiSettings.silentToolGroups) {
            val list = mutableListOf<Any>()
            val silentGroups = uiSettings.silentToolGroups
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (silentGroups.isNotEmpty()) {
                // 构建 toolCallId → toolName 查找表
                val toolNameLookup = mutableMapOf<String, String>()
                messages.forEach { msg ->
                    if (msg.role == "assistant" && !msg.toolCallsJson.isNullOrBlank()) {
                        try {
                            val arr = JSONArray(msg.toolCallsJson)
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val id = item.optString("id")
                                val function = item.optJSONObject("function") ?: continue
                                val name = function.optString("name")
                                if (id.isNotEmpty() && name.isNotEmpty()) {
                                    toolNameLookup[id] = name
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                // 按组过滤 tool 消息
                val wildcard = silentGroups.contains("*")
                messages.forEach { msg ->
                    if (msg.role == "tool") {
                        val toolName = msg.toolCallId?.let { toolNameLookup[it] }
                        val group = toolName?.let { McpRuntimeManager.builtinToolGroups[it] }
                        val shouldSilence = wildcard || (group != null && group in silentGroups)
                        if (!shouldSilence) list.add(msg)
                    } else {
                        list.add(msg)
                    }
                }
            } else {
                // 正常模式：聚合连续的 tool 消息为一组
                var currentToolGroup = mutableListOf<com.omnichat.data.Message>()
                fun flushToolGroup() {
                    if (currentToolGroup.isNotEmpty()) {
                        list.add(currentToolGroup.toList())
                        currentToolGroup.clear()
                    }
                }
                messages.forEach { msg ->
                    if (msg.role == "tool") {
                        currentToolGroup.add(msg)
                    } else {
                        flushToolGroup()
                        list.add(msg)
                    }
                }
                flushToolGroup()
            }
            list.reversed()
        }

        // "回到最新"浮动按钮 - 当用户上翻时显示
        val showScrollToBottom by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 || 
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 100)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Messages Box
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
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
                        is com.omnichat.data.Message -> it.id
                        is List<*> -> "group_${(it.firstOrNull() as? com.omnichat.data.Message)?.id}"
                        else -> it.hashCode()
                    }
                }) { item ->
                    when (item) {
                        is com.omnichat.data.Message -> {
                            BubbleMessage(
                                message = item,
                                onRetry = { viewModel.retryMessage(it) },
                                onEdit = { viewModel.editMessage(it) }
                            )
                        }
                        is List<*> -> {
                            // 渲染工具调用聚合条（静默模式下不会出现此分支）
                            @Suppress("UNCHECKED_CAST")
                            val toolMsgs = (item as List<com.omnichat.data.Message>).map { it.toUIModel() }
                            ToolGroupCard(
                                messages = toolMsgs,
                                allMessages = uiModelMessages
                            )
                        }
                    }
                }

                if (messages.isEmpty() && !isStreaming) {
                    item {
                        EmptyChatGreeting(defaultProvider, memories)
                    }
                }
            }
            
            // 浮动按钮，位于右下角
            if (showScrollToBottom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 8.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            autoScrollEnabled = true
                            scope.launch {
                                listState.animateScrollToItem(0)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = uiText("chat.scroll.to.bottom", R.string.chat_scroll_to_bottom),
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                HorizontalDivider(
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
                            .clip(RoundedCornerShape(topStart = uiSettings.cornerRadiusDp.dp, topEnd = uiSettings.cornerRadiusDp.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // 工具按钮行：选择图片、拍照、模式切换 并排
                        val toolBtnShape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(6, 16).dp)
                        val toolBtnBorder = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        val toolBtnColors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 图片选择按钮（仅视觉模型可用）
                            OutlinedCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = currentModelHasVision) {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = if (currentModelHasVision) toolBtnColors
                                    else CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        tint = if (currentModelHasVision) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiText("chat.select.image", R.string.chat_select_image),
                                        fontSize = (12 * fs).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (currentModelHasVision) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            }

                            // 拍照按钮（仅视觉模型可用）
                            OutlinedCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = currentModelHasVision) {
                                        if (cameraPermissionState.value) {
                                            val imagesDir = java.io.File(context.cacheDir, "images")
                                            imagesDir.mkdirs()
                                            val tempFile = java.io.File(
                                                imagesDir,
                                                "camera_${System.currentTimeMillis()}.jpg"
                                            )
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                tempFile
                                            )
                                            tempCameraUri = uri
                                            cameraLauncher.launch(uri)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = if (currentModelHasVision) toolBtnColors
                                    else CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = if (currentModelHasVision) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiText("chat.take.photo", R.string.chat_take_photo),
                                        fontSize = (12 * fs).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (currentModelHasVision) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── Thinking Effort Slider ──
                        if (activeSessionId != null) {
                            val efforts = listOf("low", "medium", "high", "max")
                            val effortLabels = listOf(
                                uiText("thinking_effort_low", R.string.thinking_effort_low),
                                uiText("thinking_effort_medium", R.string.thinking_effort_medium),
                                uiText("thinking_effort_high", R.string.thinking_effort_high),
                                uiText("thinking_effort_max", R.string.thinking_effort_max)
                            )
                            // Use local state to avoid "none" fallback issue
                            val dbEffort = currentSession?.thinkingEffort ?: "none"
                            val initialIndex = efforts.indexOf(dbEffort).coerceAtLeast(0)
                            var sliderIndex by remember(activeSessionId) { mutableIntStateOf(initialIndex) }
                            val currentEffort = efforts[sliderIndex]

                            Column(modifier = Modifier.fillMaxWidth()) {
                                // "Faster" — "Smarter" labels
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = uiText("thinking_effort_faster", R.string.thinking_effort_faster),
                                        fontSize = (10 * fs).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = uiText("thinking_effort_smarter", R.string.thinking_effort_smarter),
                                        fontSize = (10 * fs).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }

                                // Slider
                                Slider(
                                    value = sliderIndex.toFloat(),
                                    onValueChange = { newValue ->
                                        val idx = newValue.roundToInt().coerceIn(0, efforts.lastIndex)
                                        sliderIndex = idx
                                        if (activeSessionId != null) {
                                            viewModel.setThinkingEffort(activeSessionId!!, efforts[idx])
                                        }
                                    },
                                    valueRange = 0f..efforts.lastIndex.toFloat(),
                                    steps = efforts.size - 2,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        activeTickColor = MaterialTheme.colorScheme.primary
                                    )
                                )

                                // Level labels row
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    effortLabels.forEachIndexed { index, label ->
                                        val isActive = efforts[index] == currentEffort
                                        val isMax = efforts[index] == "max"
                                        if (isMax) {
                                            Text(
                                                text = label,
                                                fontSize = (10 * fs).sp,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                                style = TextStyle(
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(
                                                            Color(0xFFFF0000),
                                                            Color(0xFFFF8C00),
                                                            Color(0xFFFFFF00),
                                                            Color(0xFF00CC00),
                                                            Color(0xFF0066FF),
                                                            Color(0xFF9933FF)
                                                        ),
                                                        start = Offset(0f, Float.POSITIVE_INFINITY),
                                                        end = Offset(Float.POSITIVE_INFINITY, 0f)
                                                    )
                                                )
                                            )
                                        } else {
                                            Text(
                                                text = label,
                                                fontSize = (10 * fs).sp,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isActive) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }

                // ── 已选图片预览（支持多图） ─────────────────────────────────
                AnimatedVisibility(
                    visible = selectedImagePaths.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiText("chat.image.attached", R.string.chat_image_attached)
                                    + " (${selectedImagePaths.size})",
                                fontSize = (12 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // 清除所有图片
                            IconButton(
                                onClick = { selectedImagePaths = emptyList() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = uiText("chat.remove.image", R.string.chat_remove_image),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 图片缩略图网格
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectedImagePaths.forEachIndexed { index, path ->
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    AsyncImage(
                                        model = path,
                                        contentDescription = stringResource(R.string.image_n, index + 1),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    // 单张删除按钮
                                    IconButton(
                                        onClick = {
                                            selectedImagePaths = selectedImagePaths.toMutableList().apply {
                                                removeAt(index)
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.remove),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 编辑模式指示器 ──────────────────────────────────────
                if (isEditing) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = uiText("chat.editing_message", R.string.chat_editing_message),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            IconButton(
                                onClick = { viewModel.cancelEdit(); textInput = "" },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = uiText("chat.cancel_edit", R.string.chat_cancel_edit),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
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
                            contentDescription = if (showToolbar) uiText("chat.toolbar.collapse", R.string.chat_toolbar_collapse) else uiText("chat.toolbar.expand", R.string.chat_toolbar_expand),
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
                        placeholder = {
                            val hint = if (selectedImagePaths.isNotEmpty()) {
                                uiText("chat.input.hint.with.image", R.string.chat_input_hint_with_image)
                            } else {
                                uiText("chat.input.hint", R.string.chat_input_hint)
                            }
                            Text(hint, fontSize = (15 * fs).sp)
                        },
                        maxLines = 4,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = (15 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val toSend = textInput.trim()
                            val hasImage = selectedImagePaths.isNotEmpty()
                            if ((toSend.isNotBlank() || hasImage) && !isStreaming) {
                                if (isEditing) {
                                    viewModel.submitEdit(toSend)
                                } else {
                                    viewModel.sendMessageWithImage(toSend, selectedImagePaths)
                                }
                                textInput = ""
                                selectedImagePaths = emptyList()
                                showToolbar = false
                                keyboardController?.hide()
                            }
                        }),
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



                    // Send button / Stop button
                    if (isStreaming) {
                        // Stop button — visible only while streaming
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .clickable { viewModel.stopStreaming() }
                                .testTag("chat_stop_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = uiText("chat.stop.contentDescription", R.string.chat_stop_contentDescription),
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        // Send button
                        val canSend = textInput.isNotBlank() || selectedImagePaths.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = canSend) {
                                    val toSend = textInput.trim()
                                    val hasImage = selectedImagePaths.isNotEmpty()
                                    if (toSend.isNotBlank() || hasImage) {
                                        if (isEditing) {
                                            viewModel.submitEdit(toSend)
                                        } else {
                                            viewModel.sendMessageWithImage(toSend, selectedImagePaths)
                                        }
                                        textInput = ""
                                        selectedImagePaths = emptyList()
                                        showToolbar = false
                                    }
                                }
                                .testTag("chat_send_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = uiText("chat.send.contentDescription", R.string.chat_send_contentDescription),
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = uiText("chat.17bbe99c", R.string.chat_ai_ready),
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
            text = uiText("chat.1fda5871", R.string.chat_welcome_title),
            fontSize = (18 * fs).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = resolvedFontFamily,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiText("chat.aa2781f8", R.string.chat_welcome_desc),
            fontSize = (13 * fs).sp,
            fontFamily = resolvedFontFamily,
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
                Text(uiText("chat.4d810ed0", R.string.chat_status_overview), fontWeight = FontWeight.Bold, fontSize = (13 * fs).sp, fontFamily = resolvedFontFamily)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (config != null) uiText("chat.status.provider.ok", R.string.chat_status_provider_ok).format(config.name) else uiText("chat.status.provider.empty", R.string.chat_status_provider_empty),
                    fontSize = (12 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = if (config != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiText("chat.status.memories.count", R.string.chat_status_memories_count).format(memories.size),
                    fontSize = (12 * fs).sp,
                    fontFamily = resolvedFontFamily
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
            thinking = contentAfterStart.trim(),
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
    val accentColor = com.omnichat.ui.theme.LocalCustomColors.current.accent
    val successColor = com.omnichat.ui.theme.LocalCustomColors.current.success

    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

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
                    text = if (!isThinkingFinished) uiText("chat.thinking.in.progress", R.string.chat_thinking_in_progress) else uiText("chat.thinking.folded", R.string.chat_thinking_folded),
                    fontSize = (12 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) uiText("chat.action.fold", R.string.chat_action_fold) else uiText("chat.action.unfold", R.string.chat_action_unfold),
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
                            fontSize = (12.5f * fs).sp,
                            lineHeight = (18 * fs).sp,
                            fontFamily = resolvedFontFamily
                        )
                    } else {
                        dev.jeziellago.compose.markdowntext.MarkdownText(
                            markdown = thinkingText,
                            style = androidx.compose.ui.text.TextStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontSize = (12.5f * fs).sp,
                                fontFamily = resolvedFontFamily,
                                lineHeight = (18 * fs).sp
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


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BubbleMessage(
    message: com.omnichat.data.Message,
    onRetry: (com.omnichat.data.Message) -> Unit = {},
    onEdit: (com.omnichat.data.Message) -> Unit = {}
) {
    val isUser = message.role == "user"
    var showMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current

    // 聊天气泡字体：使用 chatFontSizeScale + fontFamily
    val chatFs = LocalChatFontScale.current
    val uiSettings = LocalUISettings.current
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

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
            .padding(vertical = 2.dp, horizontal = 12.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (isUser) {
                Box {
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
                        Column(
                            modifier = Modifier.padding(14.dp, 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 显示图片（支持多图）
                            if (!message.imagePaths.isNullOrBlank()) {
                                val paths = try {
                                    val arr = org.json.JSONArray(message.imagePaths)
                                    (0 until arr.length()).map { arr.getString(it) }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                                if (paths.isNotEmpty()) {
                                    val isSingleImage = paths.size == 1
                                    if (isSingleImage) {
                                        // 单图：全宽显示
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 180.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f))
                                        ) {
                                            AsyncImage(
                                                model = paths[0],
                                                contentDescription = uiText("chat.image", R.string.chat_image),
                                                modifier = Modifier.fillMaxWidth(),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    } else {
                                        // 多图：网格布局
                                        val columns = if (paths.size <= 2) paths.size else 2
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(columns),
                                            modifier = Modifier.heightIn(max = 240.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(paths.size) { index ->
                                                val path = paths[index]
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f))
                                                ) {
                                                    AsyncImage(
                                                        model = path,
                                                        contentDescription = uiText("chat.image", R.string.chat_image),
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // 显示文本（如果有）
                            if (message.content.isNotBlank()) {
                                Text(
                                    text = message.content,
                                    color = textColor,
                                    fontSize = (15 * chatFs).sp,
                                    lineHeight = (22 * chatFs).sp,
                                    fontFamily = resolvedFontFamily
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        offset = pressOffset,
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(8, 16).dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text(uiText("chat.edit_message", R.string.chat_edit_message)) },
                            onClick = {
                                showMenu = false
                                onEdit(message)
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(uiText("chat.403a6bf8", R.string.chat_copy_content)) },
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
            } else {
                val parsed = remember(message.content) { parseMessageContent(message.content) }
                Column(
                    modifier = Modifier.widthIn(max = 290.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (parsed.thinking != null) {
                        ThinkingProcessPanel(
                            thinkingText = parsed.thinking.ifEmpty { uiText("chat.thinking.default", R.string.chat_thinking_default) },
                            isThinkingFinished = parsed.isThinkingFinished
                        )
                    }
                    if (parsed.mainBody.isNotEmpty()) {
                        Box {
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
                                        fontSize = (15 * chatFs).sp,
                                        lineHeight = (22 * chatFs).sp,
                                        fontFamily = resolvedFontFamily
                                    ),
                                    syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                                    syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(14.dp, 10.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                offset = pressOffset,
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(8, 16).dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text(uiText("chat.7a875b8c", R.string.chat_retry)) },
                                    onClick = {
                                        showMenu = false
                                        onRetry(message)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(uiText("chat.403a6bf8", R.string.chat_copy_content)) },
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
                }
            }
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

    val chatFs = LocalChatFontScale.current
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = Modifier.widthIn(max = 290.dp)) {
            if (isThinkingFallback) {
                ThinkingProcessPanel(
                    thinkingText = uiText("chat.thinking.start", R.string.chat_thinking_start),
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
                            if (!isThinkingFinished) "" else uiText("chat.thinking.reply.plan", R.string.chat_thinking_reply_plan)
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
                                        fontSize = (15 * chatFs).sp,
                                        lineHeight = (22 * chatFs).sp,
                                        fontFamily = resolvedFontFamily,
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
                text = uiText("chat.32423845", R.string.chat_assistant_typing),
                fontSize = (10 * fs).sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
