package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.AgentTabState
import com.example.workspace.AgentStatus
import com.example.ui.components.ToolGroupCard
import com.example.ui.components.toUIModel

// ═══════════════════════════════════════════════════════════════════════════════
// Agent 消息区域
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Agent 消息区域。
 *
 * 行为：
 * - 始终显示完整的历史消息（不再隐藏）
 * - 流式输出时，在末尾追加一个流式气泡，旁边显示空状态提示
 * - 自动滚动到末尾
 *
 * @param activeTab 当前选中的 Agent Tab
 * @param streamingText 当前流式输出文本（空字符串表示无流式）
 * @param agentStatus 当前 Agent 状态
 */
@Composable
fun AgentMessageArea(
    activeTab: AgentTabState,
    streamingText: String,
    agentStatus: AgentStatus,
) {
    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier
    val fs = uiSettings.fontSizeScale
    val fontFamily = resolveFontFamily(uiSettings.fontFamily)

    val processedMessages = remember(activeTab.messages) {
        val list = mutableListOf<Any>()
        var currentToolGroup = mutableListOf<com.example.workspace.AgentMessage>()
        
        activeTab.messages.forEach { msg ->
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
        list
    }

    val uiModelMessages = remember(activeTab.messages) {
        activeTab.messages.map { it.toUIModel() }
    }

    val listState = rememberLazyListState()
    val isStreaming = agentStatus == AgentStatus.STREAMING || agentStatus == AgentStatus.WAITING_TOOL
    val lastMessage = activeTab.messages.lastOrNull()
    val finalMessageAlreadyPresent = lastMessage?.role == "assistant" &&
        streamingText.isNotEmpty() &&
        lastMessage.content.contains(streamingText.take(50))

    val showStreamBubble = isStreaming && streamingText.isNotEmpty() && !finalMessageAlreadyPresent

    val totalCount = processedMessages.size + (if (showStreamBubble) 1 else 0)

    var autoScrollEnabled by remember { mutableStateOf(true) }

    // 检测用户手动滚动：如果用户正在拖动且不在底部，说明用户主动上翻，暂停自动滚动
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState.canScrollForward, isDragged) {
        if (!listState.canScrollForward) {
            // 已经在最底部，恢复自动滚动
            autoScrollEnabled = true
        } else if (isDragged) {
            // 用户正在手动拖动且不在底部，说明用户主动上翻，暂停自动滚动
            autoScrollEnabled = false
        }
    }

    // 新内容到来时滚动到底部
    LaunchedEffect(processedMessages.size, streamingText.length) {
        if (autoScrollEnabled && totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    if (activeTab.messages.isEmpty() && !showStreamBubble) {
        // 完全空状态
        EmptyAgentState(agentName = activeTab.agentName, status = agentStatus, fs = fs)
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp * spacingMultiplier),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(
            items = processedMessages,
            key = { item ->
                when (item) {
                    is com.example.workspace.AgentMessage -> "${item.role}_${item.timestamp}_${item.toolCallId ?: ""}_${item.content.hashCode()}"
                    is List<*> -> "group_${(item.firstOrNull() as? com.example.workspace.AgentMessage)?.timestamp ?: 0L}"
                    else -> item.hashCode().toString()
                }
            }
        ) { item ->
            when (item) {
                is com.example.workspace.AgentMessage -> {
                    AgentBubbleMessage(message = item)
                }
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val toolMsgs = (item as List<com.example.workspace.AgentMessage>).map { it.toUIModel() }
                    ToolGroupCard(
                        messages = toolMsgs,
                        allMessages = uiModelMessages
                    )
                }
            }
        }

        if (showStreamBubble) {
            item(key = "streaming") {
                StreamingBubble(text = streamingText)
            }
        } else if (agentStatus == AgentStatus.WAITING_TOOL) {
            // 等待工具但没有文本输出时，显示一个工具执行中的占位
            item(key = "waiting_tool") {
                WaitingForToolPlaceholder(fs = fs)
            }
        }
    }
}

/**
 * 空状态：当前 Agent 还没有任何消息。
 *
 * 根据 status 显示不同提示：IDLE → 静态欢迎；STREAMING → 提示正在思考。
 */
@Composable
private fun EmptyAgentState(agentName: String, status: AgentStatus, fs: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            when (status) {
                AgentStatus.STREAMING, AgentStatus.WAITING_TOOL -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiText("workspace.agent.thinking", "%s 正在思考…").format(agentName),
                        fontSize = (13 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = uiText("workspace.agent.empty", "%s 等待任务分配").format(agentName),
                        fontSize = (13 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 等待工具结果占位。
 */
@Composable
private fun WaitingForToolPlaceholder(fs: Float) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = uiText("workspace.agent.waiting_tool", "正在执行工具…"),
                fontSize = (12 * fs).sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
