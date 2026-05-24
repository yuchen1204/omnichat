package com.example.workspace

import android.util.Log
import com.example.data.Message
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import com.example.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Agent 使用统计。
 *
 * 跟踪单个 Agent 的 token 消耗、工具调用次数和执行时长。
 */
data class AgentUsageStats(
    var totalTokens: Int = 0,
    var toolUseCount: Int = 0,
    var durationMs: Long = 0,
) {
    /** 开始时间戳，用于计算 duration */
    internal var startTimeMs: Long = 0
}

/**
 * Agent 运行器。
 *
 * 负责单个 Agent 的 API 调用和流式输出管理。
 * 处理 tool call 循环，管理干预消息队列。
 *
 * @property context Agent 运行时上下文
 * @property mcpRuntimeManager MCP 运行时管理器，用于获取工具列表和执行工具调用
 * @property crossSessionMemory 跨会话记忆文本
 * @property disallowedTools 不可用的工具名称集合（用于工具隔离）
 * @property onStreamChunk 流式 chunk 回调
 * @property onToolCall 工具调用回调
 */
class AgentRunner(
    private val context: AgentContext,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val crossSessionMemory: String = "",
    private val disallowedTools: Set<String> = emptySet(),
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String,
) {
    companion object {
        private const val TAG = "AgentRunner"

        /** 上下文压缩阈值（字符数），约 25000 token */
        private const val MAX_CONTEXT_CHARS = 100_000

        /** 压缩时保留的最近消息条数 */
        private const val KEEP_RECENT_MESSAGES = 10

        /** Tool Call 循环最大迭代次数，防止无限循环 */
        private const val MAX_TOOL_CALL_ITERATIONS = 50

        /** Orchestrator 可用的只读工具白名单 */
        private val ORCHESTRATOR_READ_ONLY_TOOLS = setOf(
            "list_directory",
            "read_file",
            "search_files",
            "get_file_info",
            "read_multiple_files",
            "list_directory_with_sizes",
            "directory_tree",
            "search_code",
            "get_file_contents",
        )
    }

    /** Agent 使用统计 */
    private val usageStats = AgentUsageStats()

    /** 干预队列锁，保护 isStreaming 的 check-then-act 操作 */
    private val interventionLock = Any()

    /**
     * 消息列表读写锁（BUG-5/6）。
     *
     * 替代 CopyOnWriteArrayList，统一保护所有对 context.messages 的读写操作。
     * - 读锁：getHistory()、buildContextMessages()
     * - 写锁：injectMessage()、compactContext()、processInterventionQueue()、runTurn() 中的 add()、dispose()
     */
    private val messagesLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /**
     * 干预消息队列。
     *
     * 使用 ConcurrentLinkedDeque 保证线程安全。
     * 需求 8.6：用户发送的干预消息在当前流式输出完成后立即被处理。
     * 流式输出结束后依次处理队列中的干预消息。
     */
    private val interventionQueue = ConcurrentLinkedDeque<AgentMessage>()

    /**
     * 标记当前是否正在流式输出。
     */
    @Volatile
    private var isStreaming = false

    /**
     * 执行一轮对话。
     *
     * 调用 ApiClient.executeStreamingChat，处理 tool call 循环，通过回调推送流式 chunk。
     * 如果提供了 userMessage，先将其追加到上下文中。
     *
     * 需求 3.4、3.5：上下文构建时不调用任何记忆相关方法。
     *
     * @param userMessage 用户消息，可选。如果提供，将追加到对话历史后再调用 API
     */
    suspend fun runTurn(userMessage: String? = null) {
        // 如果提供了用户消息，先追加到上下文
        if (userMessage != null) {
            injectMessage("user", userMessage, isIntervention = false)
        }

        // 上下文压缩检查
        if (shouldCompact()) {
            compactContext()
        }

        isStreaming = true
        usageStats.startTimeMs = System.currentTimeMillis()

        try {
            var iterationCount = 0
            do {
                iterationCount++
                if (iterationCount > MAX_TOOL_CALL_ITERATIONS) {
                    Log.w(TAG, "Agent '${context.agentName}' reached max tool call iterations ($MAX_TOOL_CALL_ITERATIONS)")
                    messagesLock.writeLock().lock()
                    try {
                        context.messages.add(
                            AgentMessage(
                                role = "system",
                                content = "已达到最大工具调用次数限制 ($MAX_TOOL_CALL_ITERATIONS)，强制结束本轮对话。"
                            )
                        )
                    } finally {
                        messagesLock.writeLock().unlock()
                    }
                    break
                }
                
                // 构建当前上下文消息列表
                val history = buildContextMessages()

                // 获取过滤后的工具列表
                val tools = getFilteredTools()

                // 构建系统提示（替换 [CROSS_SESSION_MEMORY] 和 [MCP_TOOLS]）
                val systemPrompt = context.buildSystemPrompt(tools.toString(), crossSessionMemory)

                // 累积的文本响应
                var accumulatedText = ""
                var lastCallbackTime = 0L
                val accumulatedToolCalls = mutableMapOf<Int, JSONObject>()

                // 执行流式 API 调用
                ApiClient.executeStreamingChat(
                    config = context.modelConfig,
                    systemPrompt = systemPrompt,
                    history = history,
                    tools = tools
                ).collect { chunk ->
                    when {
                        chunk.startsWith("ERROR:") -> {
                            // 错误消息，直接推送
                            onStreamChunk(context.agentName, chunk)
                            accumulatedText += "\n$chunk"
                        }
                        chunk.startsWith("INFO:") -> {
                            // 信息消息，直接推送
                            onStreamChunk(context.agentName, chunk)
                            accumulatedText += "\n$chunk"
                        }
                        chunk == "RETRY_RESET:" -> {
                            // 重试重置，清空累积状态
                            accumulatedText = ""
                            accumulatedToolCalls.clear()
                        }
                        chunk.startsWith("TOOL_CALL_DELTA:") -> {
                            // 工具调用增量，累积到 map 中
                            val deltaJson = chunk.substringAfter("TOOL_CALL_DELTA:")
                            try {
                                val toolCallsArr = JSONArray(deltaJson)
                                for (i in 0 until toolCallsArr.length()) {
                                    val item = toolCallsArr.getJSONObject(i)
                                    val index = item.optInt("index", 0)
                                    val existing = accumulatedToolCalls.getOrPut(index) { JSONObject() }

                                    val id = item.optString("id")
                                    if (id.isNotEmpty()) existing.put("id", id)

                                    val function = item.optJSONObject("function")
                                    if (function != null) {
                                        val existingFunc = existing.optJSONObject("function")
                                            ?: JSONObject().also { existing.put("function", it) }
                                        val name = function.optString("name")
                                        if (name.isNotEmpty()) existingFunc.put("name", name)
                                        val args = function.optString("arguments")
                                        if (args.isNotEmpty()) {
                                            val currentArgs = existingFunc.optString("arguments", "")
                                            existingFunc.put("arguments", currentArgs + args)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse tool call delta", e)
                            }
                        }
                        else -> {
                            // 普通内容，累积并节流推送
                            if (chunk != "null") {
                                accumulatedText += chunk
                                val now = System.currentTimeMillis()
                                // 节流回调，每 50ms 更新一次（需求 7.2）
                                if (now - lastCallbackTime > 50) {
                                    onStreamChunk(context.agentName, accumulatedText)
                                    lastCallbackTime = now
                                }
                            }
                        }
                    }
                }

                // 最终推送累积的文本
                if (accumulatedText.isNotEmpty()) {
                    onStreamChunk(context.agentName, accumulatedText)
                }

                // 保存 assistant 消息到上下文
                val finalContent = if (accumulatedText.trim() == "null") "" else accumulatedText
                val toolCallsJson = if (accumulatedToolCalls.isNotEmpty()) {
                    val arr = JSONArray()
                    accumulatedToolCalls.values.forEach { arr.put(it) }
                    arr.toString()
                } else null

                if (finalContent.isNotEmpty() || toolCallsJson != null) {
                    messagesLock.writeLock().lock()
                    try {
                        context.messages.add(
                            AgentMessage(
                                role = "assistant",
                                content = finalContent,
                                toolCallsJson = toolCallsJson
                            )
                        )
                    } finally {
                        messagesLock.writeLock().unlock()
                    }
                }

                // 处理工具调用
                if (accumulatedToolCalls.isNotEmpty()) {
                    var hasToolResults = false

                    for (toolCall in accumulatedToolCalls.values) {
                        val function = toolCall.optJSONObject("function") ?: continue
                        val toolName = function.optString("name")
                        val argsStr = function.optString("arguments")
                        val callId = toolCall.optString("id")

                        try {
                            val argsJson = JSONObject(argsStr)

                            // 通过回调执行工具调用
                            val toolResult = onToolCall(
                                context.agentName,
                                toolName,
                                argsJson,
                                callId
                            )

                            // 更新工具调用计数
                            usageStats.toolUseCount++

                            // 保存工具结果到上下文
                            messagesLock.writeLock().lock()
                            try {
                                context.messages.add(
                                    AgentMessage(
                                        role = "tool",
                                        content = toolResult,
                                        toolCallId = callId
                                    )
                                )
                            } finally {
                                messagesLock.writeLock().unlock()
                            }

                            hasToolResults = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Tool call failed: $toolName", e)
                            messagesLock.writeLock().lock()
                            try {
                                context.messages.add(
                                    AgentMessage(
                                        role = "tool",
                                        content = "Error: ${e.message}",
                                        toolCallId = callId
                                    )
                                )
                            } finally {
                                messagesLock.writeLock().unlock()
                            }
                            hasToolResults = true
                        }
                    }

                    // 如果有工具调用结果，继续下一轮对话（tool call 循环）
                    if (hasToolResults) {
                        continue
                    }
                }

                // 没有工具调用，结束循环
                break
            } while (true)

        } finally {
            isStreaming = false
            usageStats.durationMs = System.currentTimeMillis() - usageStats.startTimeMs
            
            // 估算 token 消耗（粗略估算：1 token ≈ 4 字符）
            val totalChars = context.messages.sumOf { 
                it.content.length + (it.toolCallsJson?.length ?: 0) 
            }
            usageStats.totalTokens = totalChars / 4

            // 处理干预消息队列（需求 8.6）
            processInterventionQueue()
        }
    }

    /**
     * 获取 Agent 使用统计。
     *
     * @return 使用统计副本
     */
    fun getUsageStats(): AgentUsageStats = usageStats.copy()

    /**
     * 向该 Agent 注入一条消息。
     *
     * 用于 Orchestrator 下发任务、用户干预等场景。
     * 如果当前正在流式输出，将消息加入干预队列，等待流式输出结束后处理。
     * 使用 synchronized 保护 isStreaming 的 check-then-act 操作，避免 TOCTOU 竞态。
     *
     * @param role 消息角色："user" | "assistant" | "tool" | "system"
     * @param content 消息内容
     * @param isIntervention 是否为用户干预消息
     */
    fun injectMessage(role: String, content: String, isIntervention: Boolean = false) {
        val message = AgentMessage(
            role = role,
            content = content,
            isIntervention = isIntervention
        )

        synchronized(interventionLock) {
            if (isStreaming && isIntervention) {
                // 正在流式输出时，干预消息加入队列
                interventionQueue.addLast(message)
                Log.d(TAG, "Queued intervention message for ${context.agentName}")
            } else {
                // BUG-6：使用写锁保护直接写入，消除 TOCTOU 竞态
                messagesLock.writeLock().lock()
                try {
                    context.messages.add(message)
                } finally {
                    messagesLock.writeLock().unlock()
                }
            }
        }
    }

    /**
     * 获取当前对话历史。
     *
     * 返回当前 Agent 的内存中对话历史列表。
     *
     * @return 对话历史列表
     */
    fun getHistory(): List<AgentMessage> {
        messagesLock.readLock().lock()
        return try {
            context.messages.toList()
        } finally {
            messagesLock.readLock().unlock()
        }
    }

    /**
     * 释放内存上下文。
     *
     * 清空 AgentContext.messages，释放内存。
     * 工作区完成后，Sub-Agent 的上下文会被释放。
     */
    fun dispose() {
        messagesLock.writeLock().lock()
        try {
            context.messages.clear()
        } finally {
            messagesLock.writeLock().unlock()
        }
        interventionQueue.clear()
        Log.d(TAG, "Disposed AgentRunner for ${context.agentName}")
    }

    /**
     * 判断是否需要上下文压缩。
     */
    private fun shouldCompact(): Boolean {
        val totalChars = context.messages.sumOf { it.content.length + (it.toolCallsJson?.length ?: 0) }
        return totalChars > MAX_CONTEXT_CHARS
    }

    /**
     * 压缩上下文。
     *
     * 保留最近 [KEEP_RECENT_MESSAGES] 条消息，将更早的消息压缩为摘要。
     * 确保 tool/tool_call 消息的配对关系不被破坏。
     */
    private fun compactContext() {
        if (context.messages.size <= KEEP_RECENT_MESSAGES) return

        val recentMessages = context.messages.takeLast(KEEP_RECENT_MESSAGES)
        val oldMessages = context.messages.dropLast(KEEP_RECENT_MESSAGES)
        val oldCount = oldMessages.size

        // 找到安全的切割点：确保不切断 tool call 配对
        // 策略：从 oldMessages 末尾往前找，找到最后一个完整的 tool call 块的结束位置
        val safeDropIndex = findSafeCutIndex(oldMessages, recentMessages)

        val toCompress = oldMessages.take(safeDropIndex)
        val kept = oldMessages.drop(safeDropIndex) + recentMessages

        val summary = buildString {
            appendLine("（以上对话已压缩，共 $oldCount 条消息。以下是早期关键信息摘要）")
            // 提取 system 和第一条 user 消息作为上下文
            for (msg in toCompress) {
                when (msg.role) {
                    "system" -> appendLine("[系统] ${msg.content.take(200)}")
                    "user" -> {
                        appendLine("[用户] ${msg.content.take(200)}")
                        break // 只保留第一条用户消息
                    }
                }
            }
        }

        // BUG-5：使用写锁原子替换消息列表，保证 clear() 和 addAll() 的原子性
        val newMessages = mutableListOf(AgentMessage(role = "system", content = summary))
        newMessages.addAll(kept)
        messagesLock.writeLock().lock()
        try {
            context.messages.clear()
            context.messages.addAll(newMessages)
        } finally {
            messagesLock.writeLock().unlock()
        }

        Log.d(TAG, "Compacted context for ${context.agentName}: $oldCount -> ${context.messages.size} messages")
    }

    /**
     * 找到安全的切割索引。
     *
     * 从 oldMessages 末尾往前扫描，确保切割点不会破坏 tool call 配对关系。
     * 
     * 规则：
     * 1. 如果 recentMessages 的第一条是 tool 消息，必须保留其对应的 assistant 消息
     * 2. 如果 oldMessages 末尾有未配对的 tool 消息，必须往前找到对应的 assistant 消息
     * 3. 切割点必须在完整的 tool call 块边界上
     *
     * @param oldMessages 待压缩的旧消息列表
     * @param recentMessages 保留的最近消息列表
     * @return 安全的切割索引
     */
    private fun findSafeCutIndex(
        oldMessages: List<AgentMessage>,
        recentMessages: List<AgentMessage>
    ): Int {
        // 默认切割点：保留所有 oldMessages
        var safeIndex = oldMessages.size
        
        // 情况 1：recentMessages 的第一条是 tool 消息
        // 需要确保其对应的 assistant 消息在 kept 中
        val recentFirst = recentMessages.firstOrNull()
        if (recentFirst?.role == "tool" && recentFirst.toolCallId != null) {
            val toolCallId = recentFirst.toolCallId
            // 在 oldMessages 中找到包含此 toolCallId 的 assistant 消息
            val assistantIndex = oldMessages.indexOfLast { msg ->
                msg.role == "assistant" && msg.toolCallsJson?.contains(toolCallId) == true
            }
            if (assistantIndex >= 0) {
                // 切割点应该在 assistant 消息之前
                safeIndex = assistantIndex
            }
        }
        
        // 情况 2：从 safeIndex 往后扫描，确保没有孤立的 tool 消息
        // 如果有 tool 消息但没有对应的 assistant 消息，需要继续往前调整
        // WHY: 添加 previousSafeIndex 守卫，防止 safeIndex 不变时死循环。
        // 当 orphanTool 的 assistant 恰好在 safeIndex 位置时，safeIndex 不变导致无限循环。
        var previousSafeIndex: Int
        do {
            previousSafeIndex = safeIndex
            val sliceToCheck = oldMessages.subList(safeIndex, oldMessages.size)
            val orphanToolIndex = findOrphanToolMessage(sliceToCheck)
            if (orphanToolIndex == -1) break
            
            // 找到孤立的 tool 消息，需要往前找到对应的 assistant
            val orphanTool = sliceToCheck[orphanToolIndex]
            if (orphanTool.toolCallId != null) {
                val assistantIndex = oldMessages.subList(0, safeIndex + orphanToolIndex)
                    .indexOfLast { msg ->
                        msg.role == "assistant" && msg.toolCallsJson?.contains(orphanTool.toolCallId!!) == true
                    }
                if (assistantIndex >= 0) {
                    safeIndex = assistantIndex
                } else {
                    // 找不到对应的 assistant，跳过这个 tool 消息
                    safeIndex = safeIndex + orphanToolIndex
                    break
                }
            } else {
                break
            }
        } while (safeIndex != previousSafeIndex && safeIndex > 0)
        
        // 确保 safeIndex 不为负数
        return maxOf(0, safeIndex)
    }

    /**
     * 查找消息列表中第一个孤立的 tool 消息的索引。
     *
     * 孤立的 tool 消息是指：在它之前没有对应的 assistant 消息（包含相同的 toolCallId）。
     *
     * @param messages 消息列表
     * @return 第一个孤立 tool 消息的索引，如果没有则返回 -1
     */
    private fun findOrphanToolMessage(messages: List<AgentMessage>): Int {
        val seenToolCallIds = mutableSetOf<String>()
        
        for ((index, msg) in messages.withIndex()) {
            when {
                msg.role == "assistant" && msg.toolCallsJson != null -> {
                    // 收集这个 assistant 消息中的所有 toolCallId
                    try {
                        val toolCalls = org.json.JSONArray(msg.toolCallsJson)
                        for (i in 0 until toolCalls.length()) {
                            val id = toolCalls.getJSONObject(i).optString("id")
                            if (id.isNotEmpty()) seenToolCallIds.add(id)
                        }
                    } catch (_: Exception) { }
                }
                msg.role == "tool" && msg.toolCallId != null -> {
                    // 检查这个 tool 消息是否有对应的 assistant
                    if (msg.toolCallId !in seenToolCallIds) {
                        return index // 找到孤立的 tool 消息
                    }
                }
            }
        }
        
        return -1 // 没有孤立的 tool 消息
    }

    /**
     * 处理干预消息队列。
     *
     * 需求 8.6：流式输出结束后依次处理队列中的干预消息。
     */
    private fun processInterventionQueue() {
        while (interventionQueue.isNotEmpty()) {
            val message = interventionQueue.removeFirst()
            messagesLock.writeLock().lock()
            try {
                context.messages.add(message)
            } finally {
                messagesLock.writeLock().unlock()
            }
            Log.d(TAG, "Processed queued intervention message for ${context.agentName}")
        }
    }

    /**
     * 构建上下文消息列表。
     *
     * 将 AgentContext.messages 转换为 ApiClient 所需的 Message 列表格式。
     *
     * 需求 3.5、10.1、10.2、10.3：
     * - 不调用 repository.getAllMemories()
     * - 不调用 triggerMemorySync()
     * - 不调用 upsertSessionSummary()
     * - 不注入任何跨会话记忆内容
     *
     * @return Message 列表，用于 API 请求
     */
    private fun buildContextMessages(): List<Message> {
        // 直接从内存中的消息列表构建，不涉及任何记忆相关操作
        messagesLock.readLock().lock()
        return try {
            context.messages.map { agentMsg ->
                Message(
                    id = 0,
                    sessionId = 0,
                    role = agentMsg.role,
                    content = agentMsg.content,
                    toolCallId = agentMsg.toolCallId,
                    toolCallsJson = agentMsg.toolCallsJson,
                    timestamp = agentMsg.timestamp
                )
            }
        } finally {
            messagesLock.readLock().unlock()
        }
    }

    /**
     * 获取过滤后的工具列表。
     *
     * 从 MCP 工具列表中移除 [disallowedTools] 中的工具，
     * 并为 Orchestrator 追加专属编排工具。
     *
     * Orchestrator 工具隔离：只保留只读工具 + 编排工具，禁止写操作。
     *
     * @return 过滤后的 OpenAI 格式工具数组
     */
    fun getFilteredTools(): JSONArray {
        val allTools = mcpRuntimeManager.getAllToolsAsOpenAiFormat()
        val filtered = JSONArray()

        for (i in 0 until allTools.length()) {
            val tool = allTools.getJSONObject(i)
            val function = tool.optJSONObject("function") ?: continue
            val name = function.optString("name")
            
            // 基础过滤：移除 disallowedTools 中的工具
            if (name in disallowedTools) continue
            
            // Orchestrator 工具隔离：只保留只读工具
            if (context.isOrchestrator && name !in ORCHESTRATOR_READ_ONLY_TOOLS) {
                continue
            }
            
            filtered.put(tool)
        }

        // Orchestrator 追加专属编排工具
        if (context.isOrchestrator) {
            filtered.put(CREATE_AGENTS_TOOL)
            filtered.put(ASSIGN_TASK_TOOL)
            filtered.put(CONTINUE_CONVERSATION_TOOL)
        }
        
        // WHY: Orchestrator 有专属编排工具，不应使用 peer_message 绕过编排流程。
        // peer_message 仅用于 Sub-Agent 间协作。
        if (!context.isOrchestrator) {
            filtered.put(PEER_MESSAGE_TOOL)
        }

        return filtered
    }

    /**
     * 检查是否正在流式输出。
     *
     * @return true 如果正在流式输出，false 否则
     */
    fun isStreaming(): Boolean = isStreaming

    /**
     * 获取 Agent 名称。
     *
     * @return Agent 名称
     */
    fun getAgentName(): String = context.agentName

    /**
     * 检查是否为 Orchestrator。
     *
     * @return true 如果是 Orchestrator，false 否则
     */
    fun isOrchestrator(): Boolean = context.isOrchestrator
}
