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
    }

    /**
     * 干预消息队列。
     *
     * 需求 8.6：用户发送的干预消息在当前流式输出完成后立即被处理。
     * 流式输出结束后依次处理队列中的干预消息。
     */
    private val interventionQueue = ArrayDeque<AgentMessage>()

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

        try {
            do {
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
                    context.messages.add(
                        AgentMessage(
                            role = "assistant",
                            content = finalContent,
                            toolCallsJson = toolCallsJson
                        )
                    )
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

                            // 保存工具结果到上下文
                            context.messages.add(
                                AgentMessage(
                                    role = "tool",
                                    content = toolResult,
                                    toolCallId = callId
                                )
                            )

                            hasToolResults = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Tool call failed: $toolName", e)
                            context.messages.add(
                                AgentMessage(
                                    role = "tool",
                                    content = "Error: ${e.message}",
                                    toolCallId = callId
                                )
                            )
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

            // 处理干预消息队列（需求 8.6）
            processInterventionQueue()
        }
    }

    /**
     * 向该 Agent 注入一条消息。
     *
     * 用于 Orchestrator 下发任务、用户干预等场景。
     * 如果当前正在流式输出，将消息加入干预队列，等待流式输出结束后处理。
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

        if (isStreaming && isIntervention) {
            // 正在流式输出时，干预消息加入队列
            interventionQueue.addLast(message)
            Log.d(TAG, "Queued intervention message for ${context.agentName}")
        } else {
            // 直接追加到上下文
            context.messages.add(message)
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
        return context.messages.toList()
    }

    /**
     * 释放内存上下文。
     *
     * 清空 AgentContext.messages，释放内存。
     * 工作区完成后，Sub-Agent 的上下文会被释放。
     */
    fun dispose() {
        context.messages.clear()
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
        // 如果 recentMessages 的第一条是 tool 角色，需要往前找到对应的 assistant
        val recentFirst = recentMessages.firstOrNull()
        val safeDropIndex = if (recentFirst?.role == "tool") {
            // 往前找到包含对应 tool_call_id 的 assistant 消息
            val toolCallId = recentFirst.toolCallId
            val assistantIndex = oldMessages.indexOfLast { 
                it.toolCallsJson?.contains(toolCallId ?: "") == true 
            }
            if (assistantIndex >= 0) assistantIndex + 1 else oldMessages.size - KEEP_RECENT_MESSAGES
        } else {
            oldMessages.size
        }

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

        context.messages.clear()
        context.messages.add(AgentMessage(role = "system", content = summary))
        context.messages.addAll(kept)

        Log.d(TAG, "Compacted context for ${context.agentName}: $oldCount -> ${context.messages.size} messages")
    }

    /**
     * 处理干预消息队列。
     *
     * 需求 8.6：流式输出结束后依次处理队列中的干预消息。
     */
    private fun processInterventionQueue() {
        while (interventionQueue.isNotEmpty()) {
            val message = interventionQueue.removeFirst()
            context.messages.add(message)
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
        return context.messages.map { agentMsg ->
            Message(
                id = 0, // 临时消息不需要 ID
                sessionId = 0, // 不关联具体 session
                role = agentMsg.role,
                content = agentMsg.content,
                toolCallId = agentMsg.toolCallId,
                toolCallsJson = agentMsg.toolCallsJson,
                timestamp = agentMsg.timestamp
            )
        }
    }

    /**
     * 获取过滤后的工具列表。
     *
     * 从 MCP 工具列表中移除 [disallowedTools] 中的工具，
     * 并为 Orchestrator 追加专属编排工具。
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
            if (name !in disallowedTools) {
                filtered.put(tool)
            }
        }

        // Orchestrator 追加专属工具
        if (context.isOrchestrator) {
            filtered.put(CREATE_AGENTS_TOOL)
            filtered.put(ASSIGN_TASK_TOOL)
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
