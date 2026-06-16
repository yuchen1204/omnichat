package com.omnichat.agent

import android.content.Context
import android.util.Log
import com.omnichat.data.*
import com.omnichat.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private const val TAG = "AgentExecutor"

/** Result of task execution with the config used (avoids duplicate lookup). */
private data class TaskExecutionResult(
    val result: String,
    val config: ModelConfig
)

/**
 * Coroutine context element that identifies the current SubAgent caller.
 * Used by McpRuntimeManager.callTool() to determine if a tool call needs approval.
 */
data class AgentCallerContext(
    val agentType: String,
    val agentName: String,
    val taskContext: String,
    val agentMode: String,
    val sessionId: Long,
    val depth: Int = 0
) : kotlin.coroutines.AbstractCoroutineContextElement(AgentCallerContext) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AgentCallerContext>
}

/** subAgent 任务状态 */
enum class AgentTaskStatus {
    PENDING,    // 等待执行
    RUNNING,    // 正在执行
    COMPLETED,  // 已完成
    FAILED,     // 执行失败
    CANCELLED   // 已取消
}

/** subAgent 任务状态快照 */
data class AgentTaskState(
    val taskId: String,
    val sessionId: Long,
    val agentType: String,
    val status: AgentTaskStatus,
    val taskDescription: String,
    val result: String? = null,
    val summary: String? = null,  // structured summary of what was done
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null
)

/**
 * subAgent 执行引擎。
 *
 * 职责：
 * - 管理任务生命周期（创建、执行、取消、查询）
 * - 调用 LLM API 执行任务
 * - 将结果插入主会话
 * - 维护任务状态流（内存缓存 + Room 持久化）
 *
 * 并发控制：
 * - 全局最大并行数 = [MAX_GLOBAL_PARALLELISM]
 * - 每种 agent 类型最大并行数从 [AgentConfig.maxConcurrency] 读取，默认 1
 */
class AgentExecutor(
    private val context: Context,
    private val repository: AppRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 任务状态管理（内存缓存，加速读取）
    private val _taskStates = MutableStateFlow<Map<String, AgentTaskState>>(emptyMap())
    val taskStates: StateFlow<Map<String, AgentTaskState>> = _taskStates.asStateFlow()

    // 运行中的任务 Job，用于取消
    private val runningJobs = ConcurrentHashMap<String, Job>()

    // 并发控制：全局最多 MAX_GLOBAL_PARALLELISM 个并行任务
    private val globalSemaphore = Semaphore(MAX_GLOBAL_PARALLELISM)

    // 每种 agent 类型的信号量，按需从 AgentConfig.maxConcurrency 动态创建
    private val typeSemaphores = ConcurrentHashMap<String, Semaphore>()

    // 默认信号量，用于未知 agentType 的回退
    private val defaultTypeSemaphore = Semaphore(1)

    /**
     * 获取指定 agent 类型的信号量，按需从 DB 配置创建。
     * 首次调用时读取 AgentConfig.maxConcurrency 并缓存。
     */
    private suspend fun getOrCreateTypeSemaphore(agentType: String): Semaphore {
        typeSemaphores[agentType]?.let { return it }
        val agentConfig = repository.getAgentConfigByType(agentType)
        val maxConcurrency = agentConfig?.maxConcurrency?.takeIf { it > 0 } ?: 1
        val semaphore = Semaphore(maxConcurrency)
        typeSemaphores.putIfAbsent(agentType, semaphore)?.let { return it }
        return semaphore
    }

    companion object {
        /** 任务超时时间（毫秒） */
        private const val TASK_TIMEOUT_MS = 5 * 60 * 1000L

        /** 全局最大并行任务数 */
        private const val MAX_GLOBAL_PARALLELISM = 3

        /** 最大递归委托深度（防止 subAgent 无限递归调用 delegate_task） */
        const val MAX_DELEGATION_DEPTH = 3

        /** agent_result 消息角色常量 */
        const val ROLE_AGENT_RESULT = "agent_result"

        /** 单例 */
        @Volatile
        private var INSTANCE: AgentExecutor? = null

        fun getInstance(context: Context, repository: AppRepository): AgentExecutor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentExecutor(context.applicationContext, repository).also { INSTANCE = it }
            }
        }
    }

    /**
     * 启动 subAgent 任务。
     *
     * @param sessionId 主会话 ID，结果将插入此会话
     * @param agentType 代理类型
     * @param task 任务描述
     * @param contextStr 附加上下文
     * @param files 相关文件路径
     * @return taskId 用于追踪
     */
    fun execute(
        sessionId: Long,
        agentType: String,
        task: String,
        contextStr: String?,
        files: List<String>?,
        depth: Int = 0
    ): String? {
        val taskId = UUID.randomUUID().toString()

        // 检查递归深度
        if (depth > MAX_DELEGATION_DEPTH) {
            val initialState = AgentTaskState(
                taskId = taskId,
                sessionId = sessionId,
                agentType = agentType,
                status = AgentTaskStatus.FAILED,
                taskDescription = task,
                error = "Delegation depth exceeded (max $MAX_DELEGATION_DEPTH). Tasks cannot delegate to sub-agents beyond this depth."
            )
            updateState(taskId, initialState)
            return taskId
        }

        // 同步获取并发许可，在启动协程前检测并发限制
        val typeSemaphore = try {
            runBlocking { getOrCreateTypeSemaphore(agentType) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get type semaphore for $agentType", e)
            // 回退到默认信号量
            typeSemaphores.getOrPut(agentType) { defaultTypeSemaphore }
        }
        if (!globalSemaphore.tryAcquire()) {
            val failState = AgentTaskState(
                taskId = taskId, sessionId = sessionId, agentType = agentType,
                status = AgentTaskStatus.FAILED, taskDescription = task,
                error = "Global concurrency limit reached ($MAX_GLOBAL_PARALLELISM). Please wait for other tasks to complete.",
                completedAt = System.currentTimeMillis()
            )
            updateState(taskId, failState)
            return null
        }
        if (!typeSemaphore.tryAcquire()) {
            globalSemaphore.release()
            val failState = AgentTaskState(
                taskId = taskId, sessionId = sessionId, agentType = agentType,
                status = AgentTaskStatus.FAILED, taskDescription = task,
                error = "Agent type '$agentType' already has a running task. Please wait for it to complete.",
                completedAt = System.currentTimeMillis()
            )
            updateState(taskId, failState)
            return null
        }

        // 创建初始状态
        val initialState = AgentTaskState(
            taskId = taskId,
            sessionId = sessionId,
            agentType = agentType,
            status = AgentTaskStatus.PENDING,
            taskDescription = task
        )
        updateState(taskId, initialState)

        // 启动执行协程（并发许可已同步获取）
        val job = scope.launch {
            // 追踪 startedAt，确保状态转换不会丢失
            var taskStartedAt: Long? = null
            try {
                // 执行任务（声明在 try 外面，finally 之后仍可访问）
                var executionResult: TaskExecutionResult? = null
                try {
                    // 更新状态为 RUNNING
                    taskStartedAt = System.currentTimeMillis()
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.RUNNING,
                        startedAt = taskStartedAt
                    ))

                    // 执行任务（also returns the config used, avoiding duplicate lookup）
                    executionResult = executeTask(sessionId, agentType, task, contextStr, files, depth)

                } finally {
                    // 释放许可
                    typeSemaphore.release()
                    globalSemaphore.release()
                }

                // --- Below this line, semaphores are released ---

                val result = executionResult?.result
                    ?: throw IllegalStateException("Task execution did not return a result")

                // 立即标记为 COMPLETED（关闭 previous_task_id 竞态窗口）
                // summary 异步生成后更新
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.COMPLETED,
                    result = result,
                    startedAt = taskStartedAt,
                    completedAt = System.currentTimeMillis()
                ))

                // 生成结构化摘要 (runs without holding concurrency permits)
                val summary = try {
                    val config = executionResult!!.config
                    generateTaskSummary(agentType, task, result, config)
                } catch (e: CancellationException) {
                    throw e  // Must rethrow per Kotlin coroutines convention
                } catch (e: Exception) {
                    Log.w(TAG, "Summary generation failed: ${e.message}")
                    null
                }

                // 更新 summary 字段
                if (summary != null) {
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.COMPLETED,
                        result = result,
                        summary = summary,
                        startedAt = taskStartedAt,
                        completedAt = System.currentTimeMillis()
                    ))
                }

                // 自动清理超过 30 分钟的旧任务，防止内存无限增长
                // 插入结果消息到主会话
                // 后置操作失败不应将已完成的任务降级为 FAILED
                try {
                    cleanupOldTasks()
                    insertResultMessage(sessionId, taskId, agentType, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Post-completion cleanup/insert failed for task $taskId: ${e.message}")
                }
            } catch (e: CancellationException) {
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.CANCELLED,
                    error = "Task was cancelled",
                    startedAt = taskStartedAt,
                    completedAt = System.currentTimeMillis()
                ))
                throw e  // Rethrow to preserve structured concurrency
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed: $taskId", e)
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.FAILED,
                    error = e.localizedMessage ?: "Execution failed",
                    startedAt = taskStartedAt,
                    completedAt = System.currentTimeMillis()
                ))
            } finally {
                runningJobs.remove(taskId)
            }
        }

        runningJobs[taskId] = job
        return taskId
    }

    /**
     * 执行单个任务的核心逻辑（升级为 AgentTeam 迭代执行循环）。
     * Returns both the result and the config used, so the caller can reuse the config
     * for summary generation without a duplicate lookup.
     */
    private suspend fun executeTask(
        sessionId: Long,
        agentType: String,
        task: String,
        contextStr: String?,
        files: List<String>?,
        depth: Int = 0
    ): TaskExecutionResult = withTimeout(TASK_TIMEOUT_MS) {
        val session = repository.getSessionById(sessionId)
        val agentMode = session?.agentMode ?: "GENERAL"

        val callerCtx = AgentCallerContext(
            agentType = agentType,
            agentName = agentType,  // 使用 agentType 作为 agent 名称
            taskContext = task,
            agentMode = agentMode,
            sessionId = sessionId,
            depth = depth
        )

        withContext(callerCtx) {
            // 1. 获取模型配置
            val config = getAgentModelConfig(agentType)
                ?: throw IllegalStateException("Agent type $agentType has no model configured. Please configure in settings.")

            // 2. 构建系统提示
            val systemPrompt = AgentPrompts.getPrompt(agentType)

            // 3. 构建用户消息
            val userMessage = buildUserMessage(task, contextStr, files)

            // 4. 获取所有可用 MCP 工具
            val mcpManager = com.omnichat.mcp.McpRuntimeManager.getInstance(context)
            val toolsArray = mcpManager.getAllToolsAsOpenAiFormat()

            // 5. 初始化消息历史
            val messages = org.json.JSONArray()
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })

            var finalResult = ""
            var iteration = 0
            val maxIterations = repository.getUISettings()?.maxToolCalls?.coerceIn(1, 50) ?: 10

            while (iteration < maxIterations) {
                iteration++
                Log.i(TAG, "Agent [$agentType] 迭代执行: 第 $iteration 次调用 API")

                // 调用支持工具的非流式接口
                val responseMsg = ApiClient.executeMessageCompletion(config, systemPrompt, messages, toolsArray)
                    ?: throw IllegalStateException("LLM API returned empty result")

                // 保存助手的原始回复（包含 content 和 tool_calls）
                messages.put(responseMsg)

                val content = responseMsg.optString("content", "")
                val toolCalls = responseMsg.optJSONArray("tool_calls")

                // 如果没有工具调用，则认为任务完成
                if (toolCalls == null || toolCalls.length() == 0) {
                    finalResult = content
                    break
                }

                // 本地执行工具调用
                for (i in 0 until toolCalls.length()) {
                    val toolCall = toolCalls.optJSONObject(i) ?: continue
                    val toolCallId = toolCall.optString("id")
                    val function = toolCall.optJSONObject("function") ?: continue
                    val name = function.optString("name")
                    val argumentsStr = function.optString("arguments", "{}")
                    val arguments = try { JSONObject(argumentsStr) } catch (e: Exception) { JSONObject() }

                    Log.i(TAG, "Agent [$agentType] 调用工具: $name")
                    val toolResultObj = try {
                        val serverId = mcpManager.findServerIdForTool(name)
                        if (serverId != null) {
                            mcpManager.callTool(serverId, name, arguments, sessionId) ?: JSONObject().apply { put("error", "No result returned") }
                        } else {
                            JSONObject().apply { put("error", "Tool not found: $name") }
                        }
                    } catch (e: Exception) {
                        JSONObject().apply { put("error", e.localizedMessage ?: "Unknown error") }
                    }

                    // 添加工具调用结果到消息历史
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolCallId)
                        put("content", toolResultObj.toString())
                    })
                }
            }

            if (iteration >= maxIterations) {
                Log.w(TAG, "Agent [$agentType] 达到了最大迭代次数 ($maxIterations)")
                finalResult += "\n\n(注意: 已达到最大迭代次数，任务可能未完全完成)"
            }

            TaskExecutionResult(result = finalResult, config = config)
        }
    }

    /**
     * 使用 LLM 为已完成的任务生成结构化摘要。
     */
    private suspend fun generateTaskSummary(
        agentType: String,
        task: String,
        result: String,
        config: ModelConfig
    ): String? {
        return try {
            val summaryPrompt = buildString {
                appendLine("你是一个任务总结器。请根据以下信息生成简洁的结构化摘要。")
                appendLine()
                appendLine("## 原始任务")
                appendLine(task)
                appendLine()
                appendLine("## 执行结果")
                appendLine(result.take(3000))
                appendLine()
                appendLine("## 输出格式（严格遵循，不要加任何其他内容）")
                appendLine("- 完成了什么（1-2 句）")
                appendLine("- 做了哪些更改（列出具体文件/操作，如无更改则写\"无文件更改\"）")
                appendLine("- 关键发现（如有）")
                appendLine("- 建议的后续步骤（如有）")
            }

            ApiClient.executeCompletion(
                config,
                "你是任务总结器。输出简洁的结构化摘要，不要加标题或额外说明。",
                summaryPrompt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate task summary: ${e.message}")
            null
        }
    }

    /**
     * 获取指定代理类型的模型配置。
     * 如果未配置，回退到主 provider。
     */
    private suspend fun getAgentModelConfig(agentType: String): ModelConfig? {
        // 尝试获取专用配置
        val agentConfig = repository.getAgentConfigByType(agentType)
        if (agentConfig != null && agentConfig.isEnabled) {
            val provider = repository.getConfigById(agentConfig.providerId)
            if (provider != null) {
                return provider.copy(selectedModelId = agentConfig.modelId)
            }
        }

        // 回退到主 provider
        val defaultProvider = repository.getDefaultProvider() ?: return null
        return defaultProvider
    }

    /**
     * 构建发送给 subAgent 的用户消息。
     */
    private fun buildUserMessage(task: String, contextStr: String?, files: List<String>?): String {
        return buildString {
            appendLine("## 任务")
            appendLine(task)
            appendLine()

            if (!contextStr.isNullOrBlank()) {
                appendLine("## 上下文")
                appendLine(contextStr)
                appendLine()
            }

            if (!files.isNullOrEmpty()) {
                appendLine("## 相关文件")
                files.forEach { appendLine("- $it") }
                appendLine()
            }
        }
    }

    /**
     * 将结果插入主会话。
     */
    private suspend fun insertResultMessage(
        sessionId: Long,
        taskId: String,
        agentType: String,
        result: String
    ) {
        val msg = Message(
            sessionId = sessionId,
            role = ROLE_AGENT_RESULT,
            content = result,
            toolCallId = taskId,
            toolCallsJson = JSONObject().apply {
                put("agentType", agentType)
                put("status", "completed")
                put("completedAt", System.currentTimeMillis())
            }.toString()
        )
        repository.insertMessage(msg)
        // 自动取消关联的等待 timer
        com.omnichat.mcp.TimerManager.cancelByTaskId(context, taskId)
        Log.i(TAG, "任务结果已插入会话: sessionId=$sessionId, taskId=$taskId")
    }

    /**
     * 取消正在执行的任务。
     */
    fun cancel(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
    }

    /**
     * 获取任务状态。优先读内存缓存，回退到 DB。
     */
    suspend fun getStatus(taskId: String): AgentTaskState? {
        // 内存缓存命中
        _taskStates.value[taskId]?.let { return it }
        // 回退到 DB
        return repository.getAgentTaskById(taskId)?.let { it.toTaskState() }
    }

    /**
     * 获取指定会话的所有任务。以 DB 为基准，合并内存缓存中的最新状态。
     */
    suspend fun getTasksForSession(sessionId: Long): List<AgentTaskState> {
        val dbTasks = repository.getAgentTasksBySession(sessionId).map { it.toTaskState() }.associateBy { it.taskId }
        val memoryTasks = _taskStates.value.values.filter { it.sessionId == sessionId }
        // 以 DB 为基准，用内存中的最新状态覆盖（处理尚未持久化的中间状态）
        val merged = dbTasks.toMutableMap()
        memoryTasks.forEach { mem -> merged[mem.taskId] = mem }
        return merged.values.toList()
    }

    /**
     * 获取指定会话已完成的任务（用于注入系统提示）。以 DB 为基准，合并内存缓存。
     */
    suspend fun getCompletedTasksForSession(sessionId: Long): List<AgentTaskState> {
        val dbTasks = repository.getCompletedAgentTasksBySession(sessionId).map { it.toTaskState() }.associateBy { it.taskId }
        val memoryTasks = _taskStates.value.values.filter {
            it.sessionId == sessionId && it.status == AgentTaskStatus.COMPLETED
        }
        val merged = dbTasks.toMutableMap()
        memoryTasks.forEach { mem -> merged[mem.taskId] = mem }
        return merged.values.toList()
    }

    /**
     * 将 DB 实体转换为内存状态对象。
     */
    private fun AgentTaskEntity.toTaskState() = AgentTaskState(
        taskId = taskId,
        sessionId = sessionId,
        agentType = agentType,
        status = try { AgentTaskStatus.valueOf(status) } catch (_: Exception) { AgentTaskStatus.COMPLETED },
        taskDescription = taskDescription,
        result = result,
        summary = summary,
        error = error,
        startedAt = startedAt,
        completedAt = completedAt
    )

    /**
     * 更新任务状态（线程安全），同时异步持久化到 Room。
     * 内存更新是同步的（非 suspend），DB 写入是 fire-and-forget。
     */
    private fun updateState(taskId: String, state: AgentTaskState) {
        _taskStates.update { it + (taskId to state) }
        // 异步持久化到 Room（失败不影响内存状态）
        scope.launch {
            try {
                repository.upsertAgentTask(
                    AgentTaskEntity(
                        taskId = state.taskId,
                        sessionId = state.sessionId,
                        agentType = state.agentType,
                        status = state.status.name,
                        taskDescription = state.taskDescription,
                        result = state.result,
                        summary = state.summary,
                        error = state.error,
                        startedAt = state.startedAt,
                        completedAt = state.completedAt
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist task state to DB: ${e.message}")
            }
        }
    }

    /**
     * 清理已完成的任务状态（可选，用于内存管理）。
     */
    fun clearCompletedTasks() {
        _taskStates.update { map ->
            map.filterValues {
                it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.FAILED && it.status != AgentTaskStatus.CANCELLED
            }
        }
    }

    /**
     * 自动清理超过 30 分钟的已完成任务（防止内存无限增长）。
     * 同时清理内存缓存和 DB 中的终态任务。
     */
    private suspend fun cleanupOldTasks() {
        val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
        _taskStates.update { map ->
            map.filter { (_, state) ->
                // 保留所有非终态任务
                if (state.status != AgentTaskStatus.COMPLETED &&
                    state.status != AgentTaskStatus.FAILED &&
                    state.status != AgentTaskStatus.CANCELLED) {
                    return@filter true
                }
                // 保留最近 30 分钟的终态任务
                val completedAt = state.completedAt ?: return@filter true
                completedAt > cutoff
            }
        }
        // 清理 DB 中的旧终态任务
        try {
            repository.deleteOldTerminatedAgentTasks(cutoff)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old tasks from DB: ${e.message}")
        }
    }

    /**
     * 关闭执行器，释放资源（仅用于测试或应用终止时调用）。
     * 取消所有运行中的任务并清理状态。
     */
    private fun shutdown() {
        // 取消所有运行中的任务
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        // 取消协程作用域
        scope.cancel()
        // 清理状态
        _taskStates.value = emptyMap()
    }
}
