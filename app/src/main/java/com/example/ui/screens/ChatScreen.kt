package com.example.ui.screens

import android.Manifest
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.MemoryItem
import com.example.data.ModelConfig
import com.example.ui.components.ChunkedStreamingText
import com.example.ui.components.ToolGroupCard
import com.example.ui.components.toUIModel
import com.example.ui.theme.LocalChatFontScale
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ChatViewModel
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

    // 图片选择相关状态
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImagePath by remember { mutableStateOf<String?>(null) }

    // 图片选择器 (Photo Picker - Android 13+ 推荐)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            // content:// URI 无法被 File() 直接读取，需要通过 ContentResolver 复制到 cache 目录
            try {
                val tempFile = java.io.File(
                    context.cacheDir,
                    "picked_${System.currentTimeMillis()}.jpg"
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                selectedImagePath = tempFile.absolutePath
            } catch (e: Exception) {
                // 回退：直接用 URI 字符串（仅用于预览，API 发送会失败）
                selectedImagePath = uri.toString()
            }
        }
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

    // 相机启动器
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            // 将拍摄的图片保存到临时文件
            val tempFile = java.io.File(
                context.cacheDir,
                "camera_${System.currentTimeMillis()}.jpg"
            )
            java.io.FileOutputStream(tempFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            selectedImagePath = tempFile.absolutePath
            selectedImageUri = Uri.fromFile(tempFile)
        }
    }

    // 自动滚动到底部的控制逻辑
    // 核心原则：只在用户已经在底部时跟随滚动，用户主动上翻时不干扰
    var autoScrollEnabled by remember { mutableStateOf(true) }
    
    // 检测用户手动滚动：如果用户正在拖动，且不在底部，说明用户主动上翻，暂停自动滚动
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState.canScrollBackward, isDragged) {
        if (!listState.canScrollBackward) {
            // 已经在底部了，恢复自动滚动
            autoScrollEnabled = true
        } else if (isDragged) {
            // 不在底部且用户正在手动拖动，说明用户主动上翻，暂停自动滚动
            autoScrollEnabled = false
        }
    }
    
    // 新消息到来时的自动滚动
    LaunchedEffect(messages.size) {
        if (autoScrollEnabled && messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }
    
    // 流式输出时的平滑跟随滚动（使用 snapshotFlow 避免 LaunchedEffect 频繁取消重启导致抖动）
    LaunchedEffect(Unit) {
        snapshotFlow { streamingBody?.length to streamingThinking?.length }
            .collect {
                if (autoScrollEnabled && isStreaming) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }
    // 当前实际使用的 Provider 和模型
    val activeProviderName = defaultProvider?.name ?: uiText("chat.not_set", "未设置")
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
                        contentDescription = uiText("chat.4c423b81", "提醒"),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiText("chat.no_provider.warning", "需设置全局自动提供商才能正常会话！"),
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = uiText("chat.b489ee1d", "记忆"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiText("chat.memory.injected", "已动态融合共 %d 条长效学习偏好记忆").format(memories.size),
                        fontSize = (11 * fs).sp,
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
                    text = uiText("chat.mcp_loading", "MCP 工具加载中：%s").format(
                        startingServers.joinToString(uiText("chat.separator", "、")) { it.server.name }
                    ),
                    fontSize = (11 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val uiModelMessages = remember(messages) {
            messages.map { it.toUIModel() }
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
                            val toolMsgs = (item as List<com.example.data.Message>).map { it.toUIModel() }
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
                            contentDescription = uiText("chat.scroll_to_bottom", "回到最新"),
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
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
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
                                text = uiText("chat.current.model", "当前模型: %s  ·  %s").format(activeModelId, activeProviderName),
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                                maxLines = 2
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
                            val toolBtnShape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(6, 16).dp)
                            val toolBtnBorder = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            val toolBtnColors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f))

                            // 切换模型按钮
                            OutlinedCard(
                                modifier = Modifier
                                    .clickable { showModelPicker = true },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = toolBtnColors
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
                                        text = uiText("chat.57841df8", "切换模型"),
                                        fontSize = (12 * fs).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // 图片选择按钮
                            OutlinedCard(
                                modifier = Modifier
                                    .clickable {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = toolBtnColors
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiText("chat.select_image", "选择图片"),
                                        fontSize = (12 * fs).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // 拍照按钮
                            OutlinedCard(
                                modifier = Modifier
                                    .clickable {
                                        if (cameraPermissionState.value) {
                                            cameraLauncher.launch(null)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = toolBtnColors
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiText("chat.take_photo", "拍照"),
                                        fontSize = (12 * fs).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 已选图片预览 ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = selectedImageUri != null || selectedImagePath != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 图片预览
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
                            if (selectedImagePath != null) {
                                AsyncImage(
                                    model = selectedImageUri ?: selectedImagePath,
                                    contentDescription = uiText("chat.selected_image", "已选图片"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = uiText("chat.image_attached", "已添加图片"),
                            fontSize = (12 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // 清除图片按钮
                        IconButton(
                            onClick = {
                                selectedImageUri = null
                                selectedImagePath = null
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = uiText("chat.remove_image", "移除图片"),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
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
                            contentDescription = if (showToolbar) uiText("chat.toolbar.collapse", "收起") else uiText("chat.toolbar.expand", "展开工具"),
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
                            val hint = if (selectedImagePath != null) {
                                uiText("chat.input.hint_with_image", "添加描述（可选）...")
                            } else {
                                uiText("chat.input.hint", "输入消息...")
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
                            val hasImage = selectedImagePath != null
                            if ((toSend.isNotBlank() || hasImage) && !isStreaming) {
                                viewModel.sendMessageWithImage(toSend, selectedImagePath)
                                textInput = ""
                                selectedImageUri = null
                                selectedImagePath = null
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

                    // Material filled icon button
                    val canSend = (textInput.isNotBlank() || selectedImagePath != null) && !isStreaming
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
                                val hasImage = selectedImagePath != null
                                if ((toSend.isNotBlank() || hasImage) && !isStreaming) {
                                    viewModel.sendMessageWithImage(toSend, selectedImagePath)
                                    textInput = ""
                                    selectedImageUri = null
                                    selectedImagePath = null
                                    showToolbar = false
                                }
                            }
                            .testTag("chat_send_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = uiText("chat.send.contentDescription", "发送"),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
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
            contentDescription = uiText("chat.17bbe99c", "AI 准备就绪"),
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
            text = uiText("chat.1fda5871", "欢迎使用长效记忆 AI 助手"),
            fontSize = (18 * fs).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = resolvedFontFamily,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiText("chat.aa2781f8", "本应用特别支持双模型架构！一个主模型专门负责对话聊天，而一个副模型专门在每次回答后在后台分析提炼对话事实信息，跨越不同对话会话、重启不丢失！"),
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
                Text(uiText("chat.4d810ed0", "状态一览:"), fontWeight = FontWeight.Bold, fontSize = (13 * fs).sp, fontFamily = resolvedFontFamily)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (config != null) uiText("chat.status.provider_ok", "✓ 当前提供商: %s").format(config.name) else uiText("chat.status.provider_empty", "✗ 未设置 Provider"),
                    fontSize = (12 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = if (config != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiText("chat.status.memories_count", "✓ 当前存储的长效记忆: %d 条").format(memories.size),
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
    val accentColor = com.example.ui.theme.LocalCustomColors.current.accent
    val successColor = com.example.ui.theme.LocalCustomColors.current.success

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
                    text = if (!isThinkingFinished) uiText("chat.thinking.in_progress", "AI 正在深度思考中（实时吐流）...") else uiText("chat.thinking.folded", "深度思考过程 (已折叠，点击展开)"),
                    fontSize = (12 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) uiText("chat.action.fold", "折叠") else uiText("chat.action.unfold", "展开"),
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
    message: com.example.data.Message,
    onRetry: (com.example.data.Message) -> Unit = {}
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
                            // 显示图片（如果有）
                            if (!message.imagePath.isNullOrBlank()) {
                                val imagePath = message.imagePath
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f))
                                ) {
                                    AsyncImage(
                                        model = imagePath,
                                        contentDescription = uiText("chat.image", "图片"),
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.Fit
                                    )
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
                            text = { Text(uiText("chat.403a6bf8", "复制内容")) },
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
                            thinkingText = parsed.thinking.ifEmpty { uiText("chat.thinking.default", "正在深度思考...") },
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
                                    text = { Text(uiText("chat.7a875b8c", "重试")) },
                                    onClick = {
                                        showMenu = false
                                        onRetry(message)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(uiText("chat.403a6bf8", "复制内容")) },
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
                    thinkingText = uiText("chat.thinking.start", "正在唤醒深度推理模型并深度构思中....."),
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
                            if (!isThinkingFinished) "" else uiText("chat.thinking.reply_plan", "正在构思答复内容...") 
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
                text = uiText("chat.32423845", "助手正在输入..."),
                fontSize = (10 * fs).sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
