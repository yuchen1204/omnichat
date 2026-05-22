package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.MemoryItem
import com.example.data.ModelConfig
import com.example.ui.components.ChunkedStreamingText
import com.example.ui.viewmodel.ChatViewModel

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
