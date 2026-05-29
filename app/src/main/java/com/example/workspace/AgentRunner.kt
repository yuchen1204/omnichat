package com.example.workspace

import android.util.Log
import com.example.data.MailboxMessage
import com.example.data.Message
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import com.example.network.ApiClient
import com.example.workspace.lifecycle.AgentLifecycleManager
import com.example.workspace.mailbox.MailboxService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 使用统计。
 *
 * 跟踪单个 Agent 的 token 消耗、工具调用次数和执行时长。
 *
 * 线程安全：所有可变字段通过 [AgentRunner.usageStatsLock] 保护，
 * [AgentRunner.getUsageStats] 在锁内复制返回，确保外部读到一致快照。
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
    private val lifecycleManager: AgentLifecycleManager,
    private val mailboxService: MailboxService,
    private val crossSessionMemory: String = "",
    private val availableModels: String = "",
    private val disallowedTools: Set<String> = emptySet(),
    private val sandboxPath: String? = null,
    // FIX (Bug #8): AgentDefinition.maxToolIterations 之前未生效。
    // 让调用方传入此值，覆盖默认 50；用于 explorer/verifier 等设置 30 的预设。
    private val maxToolIterations: Int = MAX_TOOL_CALL_ITERATIONS,
    // Phase 4: 支持异步/后台 Agent 执行模式
    private val isAsync: Boolean = false,
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onMessageAdded: (agentName: String, message: AgentMessage) -> Unit = { _, _ -> },
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String,
    // 消息持久化回调：每条消息写入内存后同步到 Room DB
    private val persistMessage: ((AgentMessage) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "AgentRunner"

        /** 上下文压缩阈值（字符数），约 25000 token */
        private const val MAX_CONTEXT_CHARS = 100_000

        /** 压缩时保留的最近消息条数 */
        private const val KEEP_RECENT_MESSAGES = 10

        /** Tool Call 循环最大迭代次数默认值，防止无限循环 */
        const val MAX_TOOL_CALL_ITERATIONS = 50

        /**
         * 连续纯文本响应（无 tool call）的最大重试次数。
         *
         * WHY: Sub-Agent 的 LLM 有时会回复"我马上创建文件"之类的文本但不实际调用工具，
         * runTurn 立即 break 导致 Agent 报告"成功"却未执行任何操作。
         * 检测到连续 N 次纯文本响应后，注入系统提示强制 Agent 调用工具或明确拒绝，
         * 超过次数后结束本轮避免死循环。
         */
        private const val MAX_TEXT_ONLY_RETRIES = 3

        /**
         * BUG-022 修复：连续工具调用失败的最大重试次数。
         *
         * 当工具调用持续失败（如网络问题、工具不可用）时，会导致无限循环：
         * 工具调用失败 -> 添加错误消息 -> LLM 再次尝试 -> 再次失败 -> ...
         * 检测到连续 N 次工具调用失败后，强制结束本轮对话。
         */
        private const val MAX_CONSECUTIVE_TOOL_FAILURES = 5

    }

    /** Agent 使用统计 */
    private val usageStats = AgentUsageStats()

    /**
     * 工具列表缓存（Bug #12）。
     *
     * getFilteredTools() 在 runTurn 的 do-while 循环中每次迭代都被调用，但单次
     * runTurn 期间 MCP 工具集不会变化。通过缓存 allTools StateFlow 的 hashCode
     * 来检测工具集是否变更，命中时直接返回缓存，避免重复的格式转换和过滤遍历。
     *
     * 线程安全：仅在 runTurn 协程内读写，无需额外同步。
     */
    private var cachedTools: JSONArray? = null
    private var cachedToolsVersion: Long = -1

    /**
     * Usage stats 写入锁。
     *
     * WHY: usageStats 在 runTurn 协程中被多次写入（toolUseCount++、durationMs、totalTokens、startTimeMs），
     * 同时被外部读者（TeamManager.triggerWorkspaceComplete）通过
     * [getUsageStats] 读取。原实现没有同步，存在数据竞争（撕裂读、可见性问题）。
     */
    private val usageStatsLock = Any()

    /**
     * 消息列表读写锁（BUG-5/6）。
     *
     * 替代 CopyOnWriteArrayList，统一保护所有对 context.messages 的读写操作。
     * - 读锁：getHistory()、buildContextMessages()
     * - 写锁：injectMessage()、compactContext()、runTurn() 中的 add()、dispose()
     */
    private val messagesLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    // 接入 ToolOrchestrator：只读工具并行、写入工具串行
    private val toolOrchestrator = ToolOrchestrator(onToolCall)

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
    suspend fun runTurn(userMessage: String? = null, source: String = "", imagePath: String? = null) {
        // Dispatch Agent Turn Start Hook（传入简洁的 agentType 而非完整系统提示）
        com.example.hooks.HookManager.dispatchAgentTurnStart(
            agentId = context.agentName,
            agentType = if (context.isOrchestrator) "orchestrator" else "teammate",
            teamName = context.teamName,
            task = userMessage?.take(200) ?: ""
        )

        // 如果提供了用户消息，先追加到上下文
        if (userMessage != null) {
            injectMessage("user", userMessage, isIntervention = false, source = source, imagePath = imagePath)
        }

        // 上下文压缩检查
        compactContextIfNeeded()

        // WHY: isStreaming 必须在 try 块内设置，确保任何异常（如 compactContext 抛出）
        // 都能通过 finally 将其重置为 false，防止 Agent 永久卡在"流式输出"状态（Bug #23）。
        var lastAssistantResponse = ""
        try {
            isStreaming = true
            synchronized(usageStatsLock) {
                usageStats.startTimeMs = System.currentTimeMillis()
            }
            var iterationCount = 0
            var consecutiveTextOnlyCount = 0
            // BUG-022 修复：跟踪连续工具调用失败次数，防止无限循环
            var consecutiveToolFailureCount = 0
            do {
                if (lifecycleManager.isTurnAborted()) {
                    break
                }
                
                iterationCount++
                if (iterationCount > maxToolIterations) {
                    Log.w(TAG, "Agent '${context.agentName}' reached max tool call iterations ($maxToolIterations)")
                    messagesLock.writeLock().lock()
                    try {
                        context.messages.add(
                            AgentMessage(
                                role = "system",
                                content = "已达到最大工具调用次数限制 ($maxToolIterations)，强制结束本轮对话。"
                            )
                        )
                        onMessageAdded(context.agentName, context.messages.last())
                    } finally {
                        messagesLock.writeLock().unlock()
                    }
                    break
                }
                
                // 构建当前上下文消息列表
                val history = buildContextMessages()

                // 获取过滤后的工具列表
                val tools = getFilteredTools()

                // 构建系统提示（替换 [CROSS_SESSION_MEMORY]、[MCP_TOOLS]、[AVAILABLE_MODELS] 和 [SANDBOX_PATH]）
                val systemPrompt = context.buildSystemPrompt(tools.toString(), crossSessionMemory, availableModels, sandboxPath)

                // 累积的文本响应
                var accumulatedText = ""
                var lastCallbackTime = 0L
                val accumulatedToolCalls = mutableMapOf<Int, JSONObject>()

                // Abort 检查在 do-while 循环顶部通过 lifecycleManager.isTurnAborted() 完成，
                // 不再需要在 Flow 内部拦截（TeammateContext 已删除）。
                ApiClient.executeStreamingChat(
                    config = context.modelConfig.let {
                        if (context.overrideModelId != null) it.copy(selectedModelId = context.overrideModelId)
                        else it
                    },
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
                                    if (id.isNotEmpty() && id != "null") existing.put("id", id)

                                    val function = item.optJSONObject("function")
                                    if (function != null) {
                                        val existingFunc = existing.optJSONObject("function")
                                            ?: JSONObject().also { existing.put("function", it) }
                                        val name = function.optString("name")
                                        if (name.isNotEmpty() && name != "null") existingFunc.put("name", name)
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
                            // WHY: 同时过滤空字符串和字符串字面量 "null"。
                            // 部分 SSE 实现在流结束时会发送字符串 "null" 作为终止标记，
                            // 不应将其追加到累积文本中（Bug #21）。
                            if (chunk.isNotEmpty() && chunk != "null") {
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
                if (finalContent.isNotEmpty()) {
                    lastAssistantResponse = finalContent
                }
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
                        onMessageAdded(context.agentName, context.messages.last())
                        // 持久化 assistant 消息到 Room DB（写锁内，保证顺序一致）
                        persistMessage?.invoke(context.messages.last())
                    } finally {
                        messagesLock.writeLock().unlock()
                    }
                }

                // 通过 ToolOrchestrator 处理工具调用（只读工具并行、写入工具串行）
                if (accumulatedToolCalls.isNotEmpty()) {
                    val toolCalls = accumulatedToolCalls.values.mapNotNull { toolCall ->
                        val function = toolCall.optJSONObject("function") ?: return@mapNotNull null
                        val toolName = function.optString("name")
                        if (toolName.isEmpty()) {
                            Log.w(TAG, "Skipping tool call with empty name: $toolCall")
                            return@mapNotNull null
                        }
                        val argsStr = function.optString("arguments")
                        val callId = toolCall.optString("id")

                        val argsJson = try {
                            JSONObject(argsStr)
                        } catch (e: org.json.JSONException) {
                            Log.e(TAG, "Invalid JSON arguments for tool '$toolName' (callId=$callId): $argsStr", e)
                            messagesLock.writeLock().lock()
                            try {
                                context.messages.add(
                                    AgentMessage(
                                        role = "tool",
                                        content = "Error: invalid arguments JSON for '$toolName': ${e.message}",
                                        toolCallId = callId
                                    )
                                )
                                onMessageAdded(context.agentName, context.messages.last())
                            } finally {
                                messagesLock.writeLock().unlock()
                            }
                            return@mapNotNull null
                        }

                        ToolCall(name = toolName, args = argsJson, callId = callId)
                    }

                    if (toolCalls.isNotEmpty()) {
                        // 通过 ToolOrchestrator 批量执行，自动分区只读/写入工具
                        val results = toolOrchestrator.executeToolCalls(toolCalls, context.agentName)

                        var hasToolError = false
                        for (result in results) {
                            synchronized(usageStatsLock) {
                                usageStats.toolUseCount++
                            }

                            messagesLock.writeLock().lock()
                            try {
                                context.messages.add(
                                    AgentMessage(
                                        role = "tool",
                                        content = result.content,
                                        toolCallId = result.callId
                                    )
                                )
                                onMessageAdded(context.agentName, context.messages.last())
                                // 持久化 tool 结果消息到 Room DB（写锁内，保证顺序一致）
                                persistMessage?.invoke(context.messages.last())
                            } finally {
                                messagesLock.writeLock().unlock()
                            }

                            if (result.isError) {
                                hasToolError = true
                                consecutiveToolFailureCount++
                            } else {
                                consecutiveToolFailureCount = 0
                            }
                        }

                        // BUG-022 修复：跟踪连续工具调用失败，防止无限循环
                        if (consecutiveToolFailureCount >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                            Log.w(TAG, "Agent '${context.agentName}' reached max consecutive tool failures ($MAX_CONSECUTIVE_TOOL_FAILURES), stopping")
                            messagesLock.writeLock().lock()
                            try {
                                context.messages.add(
                                    AgentMessage(
                                        role = "system",
                                        content = "连续工具调用失败 $MAX_CONSECUTIVE_TOOL_FAILURES 次，强制结束本轮对话。请检查工具是否可用或参数是否正确。"
                                    )
                                )
                                onMessageAdded(context.agentName, context.messages.last())
                            } finally {
                                messagesLock.writeLock().unlock()
                            }
                            break
                        }

                        // 在工具轮次边界从 MailboxService 取出待处理消息
                        // 必须在 continue 之前，确保下一轮 API 调用前消息已注入上下文
                        val instanceId = context.agentInstanceId ?: 0L
                        if (instanceId > 0) {
                            val mailboxMsgs = mailboxService.drain(instanceId)
                            if (mailboxMsgs.isNotEmpty()) {
                                messagesLock.writeLock().lock()
                                try {
                                    for (msg in mailboxMsgs) {
                                        val agentMsg = AgentMessage(
                                            role = msg.role,
                                            content = msg.content,
                                            source = msg.source,
                                        )
                                        context.messages.add(agentMsg)
                                        onMessageAdded(context.agentName, agentMsg)
                                        persistMessage?.invoke(agentMsg)
                                    }
                                } finally {
                                    messagesLock.writeLock().unlock()
                                }
                            }
                        }

                        continue
                    }
                }

                // 没有工具调用 — 检测是否为"假完成"（LLM 回复文本但不执行工具）
                // 跳过明确表示"等待中"的 Agent：其任务就是等待，不是假完成
                val isWaitingResponse = lastAssistantResponse.let { text ->
                    text.contains("等待") || text.contains("等待中") ||
                    text.contains("空闲") || text.contains("waiting") ||
                    text.contains("idle") || text.contains("⏳")
                }
                if (isWaitingResponse) {
                    Log.d(TAG, "Agent '${context.agentName}' is in waiting state, skipping nudge")
                    break
                }
                // 跳过明确表示任务完成的 Agent：文本报告是合理的结束方式
                // 只允许 Orchestrator 通过文本声明完成（它负责协调，不执行具体文件操作）。
                // 子 Agent 不能自己宣布完成——它们必须跑完迭代或重试耗尽，
                // 由 Orchestrator 判断整体任务是否完成。否则子 Agent 可能只做了 1/3
                // 的工作（如只创建了 index.html）就报告"任务完成"，导致 css/js 缺失。
                if (context.isOrchestrator) {
                    if (lastAssistantResponse.contains(COMPLETION_MARKER)) {
                        Log.d(TAG, "Orchestrator '${context.agentName}' output completion marker, accepting")
                        break
                    }
                }
                consecutiveTextOnlyCount++
                if (consecutiveTextOnlyCount <= MAX_TEXT_ONLY_RETRIES && !context.isOrchestrator) {
                    Log.w(TAG, "Agent '${context.agentName}' produced text-only response " +
                        "($consecutiveTextOnlyCount/$MAX_TEXT_ONLY_RETRIES), injecting retry nudge")
                    messagesLock.writeLock().lock()
                    try {
                        context.messages.add(
                            AgentMessage(
                                role = "system",
                                content = "你的上一条回复没有调用任何工具。如果你需要执行操作（如创建文件、读取文件等），请立即调用相应的工具完成任务。" +
                                    "如果确实无法继续，请明确说明原因。不要回复'我马上执行'之类的文本而不实际调用工具。"
                            )
                        )
                        onMessageAdded(context.agentName, context.messages.last())
                    } finally {
                        messagesLock.writeLock().unlock()
                    }
                    continue
                }
                break
            } while (true)

        } finally {
            isStreaming = false

            // 从 MailboxService 取出并注入剩余的未投递消息（干预、通知等）
            val drainInstanceId = context.agentInstanceId ?: 0L
            if (drainInstanceId > 0) {
                val remaining = mailboxService.drain(drainInstanceId)
                if (remaining.isNotEmpty()) {
                    messagesLock.writeLock().lock()
                    try {
                        for (msg in remaining) {
                            context.messages.add(AgentMessage(role = msg.role, content = msg.content, source = msg.source))
                        }
                    } finally {
                        messagesLock.writeLock().unlock()
                    }
                }
            }

            // BUG-7：在锁内读取 messages 计算 token；之前的实现绕过 messagesLock，
            // 与并发的 injectMessage / compactContext 存在数据竞争（潜在 ConcurrentModificationException）。
            val totalChars: Int
            messagesLock.readLock().lock()
            try {
                totalChars = context.messages.sumOf {
                    it.content.length + (it.toolCallsJson?.length ?: 0)
                }
            } finally {
                messagesLock.readLock().unlock()
            }

            val finalDuration: Long
            val finalToolUseCount: Int
            synchronized(usageStatsLock) {
                usageStats.durationMs = System.currentTimeMillis() - usageStats.startTimeMs
                // 估算 token 消耗（混合中英文内容约 2.5 字符/token）
                usageStats.totalTokens = (totalChars / 2.5).toInt()
                finalDuration = usageStats.durationMs
                finalToolUseCount = usageStats.toolUseCount
            }

            // Dispatch Agent Turn End Hook
            com.example.hooks.HookManager.dispatchAgentTurnEnd(
                agentId = context.agentName,
                agentType = if (context.isOrchestrator) "orchestrator" else "teammate",
                teamName = context.teamName,
                result = lastAssistantResponse,
                toolUseCount = finalToolUseCount,
                durationMs = finalDuration,
            )
        }
    }

    /**
     * 获取 Agent 使用统计。
     *
     * @return 使用统计副本（在锁内拷贝，保证一致快照）
     */
    fun getUsageStats(): AgentUsageStats = synchronized(usageStatsLock) { usageStats.copy() }

    /**
     * 向该 Agent 注入一条消息。
     *
     * 用于 Orchestrator 下发任务、用户干预等场景。
     * 如果当前正在流式输出且为干预消息，通过 MailboxService 投递，
     * runTurn 结束时统一 drain 注入上下文。
     *
     * @param role 消息角色："user" | "assistant" | "tool" | "system"
     * @param content 消息内容
     * @param isIntervention 是否为用户干预消息
     * @param source 消息来源标识："" = 用户真实输入，"orchestrator" = 主控注入，"subagent" = 子Agent上报
     */
    fun injectMessage(role: String, content: String, isIntervention: Boolean = false, source: String = "", imagePath: String? = null) {
        val message = AgentMessage(
            role = role,
            content = content,
            isIntervention = isIntervention,
            source = source,
            imagePath = imagePath
        )

        // 干预消息在流式输出期间通过 MailboxService 投递，runTurn 结束时统一 drain。
        if (isStreaming && isIntervention) {
            val instanceId = context.agentInstanceId ?: 0L
            if (instanceId > 0) {
                WorkspaceScopes.auxiliary.launch {
                    mailboxService.send(instanceId, MailboxMessage(
                        recipientAgentId = instanceId,
                        senderAgentName = "user",
                        role = role,
                        content = content,
                        source = "intervention",
                    ))
                }
                Log.d(TAG, "Queued intervention via mailbox for ${context.agentName}")
                return
            }
        }

        messagesLock.writeLock().lock()
        try {
            context.messages.add(message)
            onMessageAdded(context.agentName, context.messages.last())
        } finally {
            messagesLock.writeLock().unlock()
        }
    }

    /**
     * 注入系统消息（非干预，直接写入消息列表）。
     *
     * 用于在新任务开始前插入任务分界标记等系统级提示，
     * 不经过干预队列，立即生效。
     */
    fun injectSystemMessage(content: String) {
        messagesLock.writeLock().lock()
        try {
            context.messages.add(AgentMessage(role = "system", content = content))
            onMessageAdded(context.agentName, context.messages.last())
        } finally {
            messagesLock.writeLock().unlock()
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
        Log.d(TAG, "Disposed AgentRunner for ${context.agentName}")
    }

    /**
     * 如果上下文过长，则进行压缩。
     *
     * 保留最近 [KEEP_RECENT_MESSAGES] 条消息，将更早的消息压缩为摘要。
     * 确保 tool/tool_call 消息的配对关系不被破坏。
     *
     * 使用写锁保护整个压缩过程，避免 TOCTOU 竞态：
     * - 读锁阶段：检查条件并复制消息
     * - 写锁阶段：验证消息未被修改后执行替换
     *
     * BUG-021 修复：原实现在读锁和写锁之间存在时间窗口，
     * 其他协程可能在此期间修改消息，导致修改被丢失。
     * 现在在写锁内验证消息未变化后再执行替换。
     */
    private fun compactContextIfNeeded() {
        // 1. 读锁内检查条件并复制消息
        val oldMessages: List<AgentMessage>
        val recentMessages: List<AgentMessage>
        val snapshotSize: Int
        val snapshotTotalChars: Int

        messagesLock.readLock().lock()
        try {
            snapshotTotalChars = context.messages.sumOf { it.content.length + (it.toolCallsJson?.length ?: 0) }
            if (snapshotTotalChars <= MAX_CONTEXT_CHARS || context.messages.size <= KEEP_RECENT_MESSAGES) return

            snapshotSize = context.messages.size
            val oldSize = snapshotSize - KEEP_RECENT_MESSAGES

            val oldList = ArrayList<AgentMessage>(oldSize)
            val recentList = ArrayList<AgentMessage>(KEEP_RECENT_MESSAGES)
            for (i in 0 until oldSize) {
                oldList.add(context.messages[i])
            }
            for (i in oldSize until snapshotSize) {
                recentList.add(context.messages[i])
            }
            oldMessages = oldList
            recentMessages = recentList
        } finally {
            messagesLock.readLock().unlock()
        }

        // 2. 锁外计算摘要（耗时的 O(n) 操作，只读取复制的数据）
        val oldCount = oldMessages.size
        val safeDropIndex = findSafeCutIndex(oldMessages, recentMessages)

        val toCompress = oldMessages.take(safeDropIndex)
        val kept = oldMessages.drop(safeDropIndex) + recentMessages

        val summary = buildString {
            appendLine("（以上对话已压缩，共 $oldCount 条消息。以下是早期关键信息摘要）")
            var userMessageCount = 0
            var assistantMessageCount = 0
            val maxUserMessages = 2
            val maxAssistantMessages = 2
            var maxToolResults = 3

            for (msg in toCompress) {
                when (msg.role) {
                    "system" -> {
                        appendLine("[系统] ${msg.content.take(200)}")
                    }
                    "user" -> {
                        if (userMessageCount < maxUserMessages) {
                            appendLine("[用户] ${msg.content.take(300)}")
                            userMessageCount++
                        }
                    }
                    "assistant" -> {
                        if (msg.content.isNotBlank() && assistantMessageCount < maxAssistantMessages) {
                            appendLine("[助手] ${msg.content.take(300)}")
                            assistantMessageCount++
                        }
                    }
                    "tool" -> {
                        if (maxToolResults > 0) {
                            val contentPreview = if (msg.content.length > 100) msg.content.take(100) + "..." else msg.content
                            appendLine("[工具结果] $contentPreview")
                            maxToolResults--
                        }
                    }
                }
            }
        }

        val newMessages = mutableListOf(AgentMessage(role = "system", content = summary))
        newMessages.addAll(kept)

        var currentChars = newMessages.sumOf { it.content.length + (it.toolCallsJson?.length ?: 0) }
        if (currentChars > MAX_CONTEXT_CHARS) {
            var truncated = false
            for (i in newMessages.indices.reversed()) {
                val msg = newMessages[i]
                if (msg.role == "tool" && msg.content.length > 2000) {
                    val oldLen = msg.content.length
                    val truncatedContent = msg.content.take(1000) + "\n\n...[输出过长已由系统截断]...\n\n" + msg.content.takeLast(500)
                    newMessages[i] = msg.copy(content = truncatedContent)
                    truncated = true
                    currentChars -= (oldLen - truncatedContent.length)
                    if (currentChars <= MAX_CONTEXT_CHARS) break
                }
            }
            if (!truncated) {
                Log.w(TAG, "Cannot compact further for ${context.agentName}: all tool messages already under 2000 chars but total still exceeds ${MAX_CONTEXT_CHARS}")
            }
        }

        // 3. 写锁内验证消息未变化后执行替换
        messagesLock.writeLock().lock()
        try {
            // BUG-021 修复：验证消息列表在我们计算摘要期间未被修改
            // 如果大小或内容变化，说明有新消息被添加，放弃本次压缩
            // 下次 runTurn 循环时会重新检查
            if (context.messages.size != snapshotSize) {
                Log.d(TAG, "Aborting compaction for ${context.agentName}: messages changed during summary computation " +
                    "(was $snapshotSize, now ${context.messages.size})")
                return
            }
            
            // 验证总字符数（快速检查，避免逐条比较）
            val currentTotalChars = context.messages.sumOf { it.content.length + (it.toolCallsJson?.length ?: 0) }
            if (currentTotalChars != snapshotTotalChars) {
                Log.d(TAG, "Aborting compaction for ${context.agentName}: message content changed during summary computation")
                return
            }
            
            context.messages.clear()
            context.messages.addAll(newMessages)
        } finally {
            messagesLock.writeLock().unlock()
        }

        Log.d(TAG, "Compacted context for ${context.agentName}: $oldCount -> ${newMessages.size} messages")
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
        var safeIndex = oldMessages.size
        var previousSafeIndex: Int
        do {
            previousSafeIndex = safeIndex
            // 将 safeIndex 之后的旧消息和所有最近消息合并，作为一个完整的“保留列表”检查
            val kept = oldMessages.subList(safeIndex, oldMessages.size) + recentMessages
            val orphanToolIndex = findOrphanToolMessage(kept)
            
            if (orphanToolIndex != -1) {
                val orphanTool = kept[orphanToolIndex]
                if (orphanTool.toolCallId != null) {
                    val assistantIndex = oldMessages.subList(0, safeIndex).indexOfLast { msg ->
                        msg.role == "assistant" && msg.toolCallsJson?.contains(orphanTool.toolCallId!!) == true
                    }
                    if (assistantIndex >= 0) {
                        safeIndex = assistantIndex
                    }
                }
            }
        } while (safeIndex != previousSafeIndex && safeIndex > 0)
        
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
     * 入队一条待处理消息，供异步 Agent 在下一轮工具轮次边界处理。
     *
     * 通过 MailboxService 投递，替代原来的 ConcurrentLinkedDeque。
     * 用于 SendMessage 工具和任务通知投递场景。
     */
    fun queuePendingMessage(message: AgentMessage) {
        val instanceId = context.agentInstanceId ?: 0L
        if (instanceId <= 0) {
            Log.w(TAG, "Cannot queue message for ${context.agentName}: no agentInstanceId")
            return
        }
        WorkspaceScopes.auxiliary.launch {
            mailboxService.send(instanceId, MailboxMessage(
                recipientAgentId = instanceId,
                senderAgentName = message.source.ifEmpty { "system" },
                role = message.role,
                content = message.content,
                source = message.source,
            ))
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
                    imagePath = agentMsg.imagePath,
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
     * 通过 [AgentToolFilter.filterTools] 集中过滤，基于 AgentDefinition 的
     * tools/disallowedTools 和 Orchestrator/Async 角色过滤工具。
     *
     * 结果在单次 runTurn 内缓存：工具集的 hashCode 未变化时直接返回缓存，
     * 避免 do-while 循环中重复的格式转换和过滤遍历（Bug #12）。
     *
     * @return 过滤后的 OpenAI 格式工具数组
     */
    fun getFilteredTools(): JSONArray {
        val allTools = mcpRuntimeManager.getAllToolsAsOpenAiFormat()
        val currentHashCode = allTools.toString().hashCode().toLong()
        cachedTools?.let { if (cachedToolsVersion == currentHashCode) return it }

        val allToolNames = mutableSetOf<String>()
        for (i in 0 until allTools.length()) {
            allTools.getJSONObject(i).optJSONObject("function")?.optString("name")?.let {
                allToolNames.add(it)
            }
        }

        val allowedNames = AgentToolFilter.filterTools(
            allToolNames = allToolNames,
            agentDef = context.agentDefinition,
            isOrchestrator = context.isOrchestrator,
            isAsync = isAsync,
        )

        val filtered = JSONArray()
        for (i in 0 until allTools.length()) {
            val tool = allTools.getJSONObject(i)
            val name = tool.optJSONObject("function")?.optString("name") ?: continue
            if (name in allowedNames) {
                filtered.put(tool)
            }
        }

        cachedTools = filtered
        cachedToolsVersion = currentHashCode
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

    // 暴露系统提示，供外部（如 WorkspaceViewModel 保存快照）使用
    fun getSystemPrompt(): String = context.systemPrompt

    /**
     * 检查是否为 Orchestrator。
     *
     * @return true 如果是 Orchestrator，false 否则
     */
    fun isOrchestrator(): Boolean = context.isOrchestrator

    /**
     * 获取 Agent 的模型配置。
     *
     * @return ModelConfig 实例
     */
    fun getModelConfig(): ModelConfig = context.modelConfig

    /**
     * 获取 Agent 的团队名称。
     *
     * @return 团队名称
     */
    fun getTeamName(): String = context.teamName
}
