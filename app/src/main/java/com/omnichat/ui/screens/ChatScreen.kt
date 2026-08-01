package com.omnichat.ui.screens

import android.Manifest
import kotlin.math.roundToInt
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import com.omnichat.ui.components.LatexMarkdownWebView
import com.omnichat.ui.components.SmartMarkdownText
import com.omnichat.ui.components.ToolGroupCard
import com.omnichat.ui.theme.LocalWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

import com.omnichat.ui.theme.LocalChatFontScale
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import androidx.compose.ui.res.stringResource
import com.omnichat.R
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.viewmodel.ChatViewModel
import com.omnichat.util.DocumentParseErrorCategory
import com.omnichat.util.DocumentParseException
import com.omnichat.util.JsDocumentReader
import com.omnichat.ui.presentation.ChatDisplayItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omnichat.agent.WorkflowUiState

private const val STREAMING_SCROLL_INTERVAL_MS = 100L

private data class StreamingScrollState(
    val bodyLength: Int,
    val thinkingLength: Int,
    val isStreaming: Boolean,
    val isCurrentSession: Boolean,
    val autoScrollEnabled: Boolean
)

data class AttachedFile(
    val name: String,
    val text: String,
    val path: String
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatView(viewModel: ChatViewModel) {
    val messages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val chatDisplayState by viewModel.chatDisplayState.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val isStreaming = viewModel.isStreaming
    val streamingSessionId = viewModel.streamingSessionId
    val subAgentActive = viewModel.subAgentActive
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
    val activeTasks = viewModel.activeTasks  // SnapshotStateMap — already Compose-observable
    val activeWorkflows = viewModel.activeWorkflows  // SnapshotStateMap for Workflow progress

    // 字体设置
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale  // 全局 UI 字体缩放
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val composerFocusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    var composerImeWasVisible by remember { mutableStateOf(false) }
    val composerImeVisible = WindowInsets.ime.getBottom(density) > 0
    val imeAnimationSourceVisible = WindowInsets.imeAnimationSource.getBottom(density) > 0
    val imeAnimationTargetVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
    val imeIsAnimating = imeAnimationSourceVisible != imeAnimationTargetVisible
    // Switch the capsule at the same instant the system IME animation starts, rather
    // than at focus time or after the IME has already completed its movement.
    val composerExpanded = if (imeIsAnimating) imeAnimationTargetVisible else composerImeVisible

    LaunchedEffect(composerImeVisible) {
        if (composerImeVisible) {
            composerImeWasVisible = true
        } else if (composerImeWasVisible) {
            focusManager.clearFocus(force = true)
            composerImeWasVisible = false
        }
    }

    LaunchedEffect(composerExpanded) {
        if (composerExpanded) {
            // AnimatedContent swaps the compact field for the expanded one; restore
            // focus on the replacement while the IME is in its opening transition.
            composerFocusRequester.requestFocus()
        }
    }

    val windowSizeClass = LocalWindowSizeClass.current
    val isExpandedScreen = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    // 当前模型是否支持视觉
    val currentModelHasVision = viewModel.currentModelHasVision

    // 模型选择器弹窗
    var showModelPicker by remember { mutableStateOf(false) }

    // 图片选择相关状态（支持多图）
    var selectedImagePaths by remember { mutableStateOf<List<String>>(emptyList()) }

    // 文档选择相关状态
    var selectedAttachedFiles by remember { mutableStateOf<List<AttachedFile>>(emptyList()) }
    var isParsingFile by remember { mutableStateOf(false) }

    // 文档选择器 launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isParsingFile = true
            scope.launch(Dispatchers.IO) {
                val reader = JsDocumentReader(context)
                val fileName = reader.getFileName(uri)
                try {
                    val result = reader.parse(uri)
                    withContext(Dispatchers.Main) {
                        selectedAttachedFiles = selectedAttachedFiles + AttachedFile(
                            name = fileName,
                            text = result.text,
                            path = uri.toString()
                        )
                    }
                } catch (e: DocumentParseException) {
                    withContext(Dispatchers.Main) {
                        val errorMessage = when (e.category) {
                            DocumentParseErrorCategory.UnsupportedFormat ->
                                context.getString(R.string.document_error_unsupported_format)
                            DocumentParseErrorCategory.FileTooLarge ->
                                context.getString(R.string.document_error_file_too_large)
                            DocumentParseErrorCategory.UnreadableInput ->
                                context.getString(R.string.document_error_unreadable)
                            DocumentParseErrorCategory.RuntimeUnavailable ->
                                context.getString(R.string.document_error_runtime)
                            DocumentParseErrorCategory.PluginLoadFailed ->
                                context.getString(R.string.document_error_plugin_load)
                            DocumentParseErrorCategory.PluginTimeout ->
                                context.getString(R.string.document_error_timeout)
                            DocumentParseErrorCategory.PluginMemoryLimit ->
                                context.getString(R.string.document_error_memory_limit)
                            DocumentParseErrorCategory.MalformedPluginResult ->
                                context.getString(R.string.document_error_parse_failed)
                            DocumentParseErrorCategory.ParseFailed ->
                                context.getString(R.string.document_error_parse_failed)
                            DocumentParseErrorCategory.NoExtractableText ->
                                context.getString(R.string.document_error_no_text)
                        }
                        android.widget.Toast.makeText(
                            context,
                            "$fileName: $errorMessage",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.document_error_parse_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isParsingFile = false
                    }
                }
            }
        }
    }

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


    // 图片选择器 (Photo Picker - 多选)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            // Activity-result callbacks run on the main thread. Copying several
            // camera-size images here would block input and the picker transition.
            scope.launch(Dispatchers.IO) {
                val newPaths = uris.mapIndexedNotNull { index, uri ->
                    try {
                        val tempFile = java.io.File(
                            context.cacheDir,
                            "picked_${SystemClock.elapsedRealtime()}_${index}_${uri.lastPathSegment?.take(20) ?: "img"}.jpg"
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            java.io.FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile.absolutePath
                    } catch (_: Exception) {
                        null
                    }
                }
                if (newPaths.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        selectedImagePaths = selectedImagePaths + newPaths
                    }
                }
            }
        }
    }

    // Activity-result permissions
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
    val visibleMessageCount = chatDisplayState.visibleMessageCount
    LaunchedEffect(visibleMessageCount) {
        if (autoScrollEnabled && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        var lastStreamingScrollAt = 0L
        snapshotFlow {
            StreamingScrollState(
                bodyLength = streamingBody?.length ?: 0,
                thinkingLength = streamingThinking?.length ?: 0,
                isStreaming = isStreaming,
                isCurrentSession = streamingSessionId == activeSessionId,
                autoScrollEnabled = autoScrollEnabled
            )
        }.collect { state ->
            if (!state.isStreaming || !state.isCurrentSession || !state.autoScrollEnabled) {
                return@collect
            }

            // Layout work from scrollToItem is expensive when a provider emits
            // many tiny chunks. Keep the chat pinned without scrolling more than
            // ten times per second.
            val now = SystemClock.elapsedRealtime()
            if (now - lastStreamingScrollAt >= STREAMING_SCROLL_INTERVAL_MS) {
                listState.scrollToItem(0)
                lastStreamingScrollAt = now
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

        val displayItems = chatDisplayState.items

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
                if (isStreaming && streamingSessionId == activeSessionId) {
                    item(key = "streaming_bubble") {
                        StreamingBubble(
                            thinkingText = streamingThinking,
                            bodyText = streamingBody,
                            isThinkingFinished = isThinkingFinished
                        )
                    }
                }

                // Active SubAgent task cards
                activeTasks.values
                    .filter { it.sessionId == activeSessionId }
                    .forEach { task ->
                        item(key = "subagent_${task.taskId}") {
                            SubAgentTaskCard(
                                task = task,
                                onCancelClick = { viewModel.cancelSubAgentTask(task.taskId) }
                            )
                        }
                    }

                // Active Workflow progress cards
                activeWorkflows.values
                    .filter { it.sessionId == activeSessionId }
                    .forEach { workflow ->
                        item(key = "workflow_${workflow.workflowId}") {
                            WorkflowProgressCard(
                                workflow = workflow,
                                onCancelClick = { viewModel.cancelWorkflow(workflow.workflowId) }
                            )
                        }
                    }

                items(displayItems, key = { item ->
                    when (item) {
                        is ChatDisplayItem.MessageItem -> item.message.id
                        is ChatDisplayItem.ToolGroupItem -> "group_${item.messages.firstOrNull()?.id}"
                    }
                }) { item ->
                    when (item) {
                        is ChatDisplayItem.MessageItem -> {
                            BubbleMessage(
                                message = item.message,
                                onRetry = { viewModel.retryMessage(it) },
                                onEdit = { viewModel.editMessage(it) }
                            )
                        }
                        is ChatDisplayItem.ToolGroupItem -> {
                            ToolGroupCard(
                                messages = item.messages,
                                lookup = chatDisplayState.toolCallLookup
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

        // Send area: a floating capsule that expands into a full composer on focus.
        Surface(
            color = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                val canSend = textInput.isNotBlank() ||
                    selectedImagePaths.isNotEmpty() ||
                    selectedAttachedFiles.isNotEmpty()

                fun sendCurrentMessage() {
                    val toSend = textInput.trim()
                    val hasImage = selectedImagePaths.isNotEmpty()
                    val hasDocs = selectedAttachedFiles.isNotEmpty()
                    if ((toSend.isBlank() && !hasImage && !hasDocs) || isStreaming || subAgentActive) return

                    if (isEditing) {
                        viewModel.submitEdit(toSend)
                    } else {
                        val finalPrompt = buildString {
                            selectedAttachedFiles.forEach { file ->
                                append("<document_attachment name=\"${file.name}\">\n")
                                append(file.text)
                                append("\n</document_attachment>\n\n")
                            }
                            append(toSend)
                        }
                        viewModel.sendMessageWithImage(finalPrompt, selectedImagePaths)
                    }
                    textInput = ""
                    selectedImagePaths = emptyList()
                    selectedAttachedFiles = emptyList()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }

                fun openCamera() {
                    if (!currentModelHasVision) return
                    if (cameraPermissionState.value) {
                        val imagesDir = java.io.File(context.cacheDir, "images")
                        imagesDir.mkdirs()
                        val tempFile = java.io.File(imagesDir, "camera_${System.currentTimeMillis()}.jpg")
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", tempFile
                        )
                        tempCameraUri = uri
                        cameraLauncher.launch(uri)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                fun openDocumentPicker() {
                    documentPickerLauncher.launch(
                        arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        )
                    )
                }

                @Composable
                fun ComposerTextField(modifier: Modifier = Modifier) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        enabled = !isStreaming && !subAgentActive,
                        placeholder = {
                            val hint = if (selectedImagePaths.isNotEmpty() || selectedAttachedFiles.isNotEmpty()) {
                                uiText("chat.input.hint.with.image", R.string.chat_input_hint_with_image)
                            } else {
                                uiText("chat.input.hint", R.string.chat_input_hint)
                            }
                            Text(
                                text = hint,
                                fontSize = (15 * fs).sp,
                                fontFamily = resolvedFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
                            )
                        },
                        singleLine = !composerExpanded,
                        maxLines = if (composerExpanded) 4 else 1,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = (15 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCurrentMessage() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent
                        ),
                        modifier = modifier
                            .focusRequester(composerFocusRequester)
                            .onPreviewKeyEvent { event ->
                                val config = context.resources.configuration
                                val hasHardwareKeyboard = config.keyboard !=
                                    android.content.res.Configuration.KEYBOARD_NOKEYS
                                if (hasHardwareKeyboard && event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter && !event.isShiftPressed
                                ) {
                                    sendCurrentMessage()
                                    true
                                } else {
                                    false
                                }
                            }
                            .testTag("chat_input_field")
                    )
                }

                @Composable
                fun AttachmentActions() {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (currentModelHasVision) {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            },
                            enabled = currentModelHasVision,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = uiText("chat.select.image", R.string.chat_select_image),
                                tint = if (currentModelHasVision) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = ::openCamera,
                            enabled = currentModelHasVision,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = uiText("chat.take.photo", R.string.chat_take_photo),
                                tint = if (currentModelHasVision) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = ::openDocumentPicker,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = uiText("chat.upload.file", "上传文件"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    AnimatedContent(
                        targetState = composerExpanded,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(140, delayMillis = 60)) togetherWith
                                fadeOut(animationSpec = tween(90))) using
                                SizeTransform(clip = false) { _, _ ->
                                    tween(durationMillis = 250, easing = LinearEasing)
                                }
                        },
                        label = "composer_layout"
                    ) { expanded ->
                    if (expanded) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ComposerTextField(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 4.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 10.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AttachmentActions()
                                Spacer(modifier = Modifier.weight(1f))
                                if (isStreaming || subAgentActive) {
                                    FilledTonalIconButton(
                                        onClick = { viewModel.stopStreaming() },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("chat_stop_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = uiText(
                                                "chat.stop.contentDescription",
                                                R.string.chat_stop_contentDescription
                                            )
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = ::sendCurrentMessage,
                                        enabled = canSend,
                                        shape = RoundedCornerShape(22.dp),
                                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .height(44.dp)
                                            .testTag("chat_send_button")
                                    ) {
                                        Text(
                                            text = uiText("chat.send.button", "发送"),
                                            fontFamily = resolvedFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 58.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ComposerTextField(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp)
                            )
                            AttachmentActions()
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                    }
                }

                AnimatedVisibility(
                    visible = selectedAttachedFiles.isNotEmpty() || isParsingFile,
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
                                text = uiText("chat.files.attached", "已添加文件") + " (${selectedAttachedFiles.size})",
                                fontSize = (12 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (selectedAttachedFiles.isNotEmpty()) {
                                IconButton(
                                    onClick = { selectedAttachedFiles = emptyList() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = uiText("chat.remove.files", "移除所有文件"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        if (isParsingFile) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // 文件网格/列表
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            selectedAttachedFiles.forEachIndexed { index, file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = file.name,
                                        fontSize = (12 * fs).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedAttachedFiles = selectedAttachedFiles.toMutableList().apply {
                                                removeAt(index)
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.remove),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
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

                // ── 活跃 Skill 指示器 ──────────────────────────────
                val activeSkills = viewModel.skillManager.matchByIntent(textInput)
                if (activeSkills.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        activeSkills.take(2).forEach { skill ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = "🧩 ${skill.name}",
                                    fontSize = (11 * fs).sp,
                                    fontFamily = resolvedFontFamily,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (activeSkills.size > 2) {
                            Text(
                                text = "+${activeSkills.size - 2}",
                                fontSize = (11 * fs).sp,
                                fontFamily = resolvedFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
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
                        SmartMarkdownText(
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


data class ParsedUserMessage(
    val mainText: String,
    val attachedDocuments: List<String>
)

fun parseUserMessageWithDocuments(content: String): ParsedUserMessage {
    val docRegex = "<document_attachment name=\"(.*?)\">.*?</document_attachment>\\n*".toRegex(RegexOption.DOT_MATCHES_ALL)
    val names = mutableListOf<String>()
    val mainText = docRegex.replace(content) { matchResult ->
        names.add(matchResult.groupValues[1])
        ""
    }
    return ParsedUserMessage(mainText = mainText.trim(), attachedDocuments = names)
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

    // 平板模式检测：AI 回复气泡占满宽度
    val windowSizeClass = LocalWindowSizeClass.current
    val isExpandedScreen = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

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
                            // 显示文档附件
                            val parsedUserMsg = remember(message.content) { parseUserMessageWithDocuments(message.content) }
                            if (parsedUserMsg.attachedDocuments.isNotEmpty()) {
                                parsedUserMsg.attachedDocuments.forEach { docName ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AttachFile,
                                                contentDescription = null,
                                                tint = textColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = docName,
                                                color = textColor,
                                                fontSize = (13 * chatFs).sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = resolvedFontFamily
                                            )
                                        }
                                    }
                                }
                            }

                            // 显示文本（如果有）
                            if (parsedUserMsg.mainText.isNotBlank()) {
                                Text(
                                    text = parsedUserMsg.mainText,
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
                    modifier = if (isExpandedScreen) Modifier.fillMaxWidth() else Modifier.widthIn(max = 290.dp),
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
                                SmartMarkdownText(
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

    // 平板模式检测：流式气泡占满宽度
    val windowSizeClass = LocalWindowSizeClass.current
    val isExpandedScreen = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = if (isExpandedScreen) Modifier.fillMaxWidth() else Modifier.widthIn(max = 290.dp)) {
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
