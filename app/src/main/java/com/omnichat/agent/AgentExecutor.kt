package com.omnichat.agent

import android.content.Context
import android.util.Log
import com.omnichat.data.*
import com.omnichat.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
 * - 维护任务状态流
 *
 * 并发控制：
 * - 每种 agent 类型默认最大并行数 = 1
 * - 全局最大并行数 = 3
 */
class AgentExecutor(
    private val context: Context,
    private val repository: AppRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 任务状态管理
    private val _taskStates = MutableStateFlow<Map<String, AgentTaskState>>(emptyMap())
    val taskStates: StateFlow<Map<String, AgentTaskState>> = _taskStates.asStateFlow()

    // 运行中的任务 Job，用于取消
    private val runningJobs = ConcurrentHashMap<String, Job>()

    // 并发控制：全局最多 MAX_GLOBAL_PARALLELISM 个并行任务
    private val globalSemaphore = Semaphore(MAX_GLOBAL_PARALLELISM)

    // 每种 agent 类型的信号量（各自最多 1 个并行）
    private val typeSemaphores = AgentPrompts.ALL_TYPES.associateWith { Semaphore(1) }

    // 默认信号量，用于未知 agentType 的回退
    private val defaultTypeSemaphore = Semaphore(1)

    companion object {
        /** 任务超时时间（毫秒） */
        private const val TASK_TIMEOUT_MS = 5 * 60 * 1000L

        /** 全局最大并行任务数 */
        private const val MAX_GLOBAL_PARALLELISM = 3

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
        files: List<String>?
    ): String {
        val taskId = UUID.randomUUID().toString()

        // 创建初始状态
        val initialState = AgentTaskState(
            taskId = taskId,
            sessionId = sessionId,
            agentType = agentType,
            status = AgentTaskStatus.PENDING,
            taskDescription = task
        )
        updateState(taskId, initialState)

        // 启动执行协程
        val job = scope.launch {
            try {
                // 获取并发许可
                val typeSemaphore = typeSemaphores[agentType] ?: defaultTypeSemaphore
                if (!globalSemaphore.tryAcquire()) {
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.FAILED,
                        error = "Global concurrency limit reached ($MAX_GLOBAL_PARALLELISM). Please wait for other tasks to complete."
                    ))
                    return@launch
                }
                if (!typeSemaphore.tryAcquire()) {
                    globalSemaphore.release()
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.FAILED,
                        error = "Agent type '$agentType' already has a running task. Please wait for it to complete."
                    ))
                    return@launch
                }

                try {
                    // 更新状态为 RUNNING
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.RUNNING,
                        startedAt = System.currentTimeMillis()
                    ))

                    // 执行任务（also returns the config used, avoiding duplicate lookup）
                    val executionResult = executeTask(sessionId, agentType, task, contextStr, files)

                } finally {
                    // 释放许可
                    typeSemaphore.release()
                    globalSemaphore.release()
                }

                // --- Below this line, semaphores are released ---

                // 生成结构化摘要 (runs without holding concurrency permits)
                val summary = try {
                    generateTaskSummary(agentType, task, executionResult.result, executionResult.config)
                } catch (e: Exception) {
                    Log.w(TAG, "Summary generation failed: ${e.message}")
                    null
                }

                // 更新状态为 COMPLETED
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.COMPLETED,
                    result = executionResult.result,
                    summary = summary,
                    completedAt = System.currentTimeMillis()
                ))

                // 插入结果消息到主会话
                insertResultMessage(sessionId, taskId, agentType, executionResult.result)
            } catch (e: CancellationException) {
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.CANCELLED,
                    error = "Task was cancelled"
                ))
                throw e  // Rethrow to preserve structured concurrency
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed: $taskId", e)
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.FAILED,
                    error = e.localizedMessage ?: "Execution failed"
                ))
            } finally {
                runningJobs.remove(taskId)
            }
        }

        runningJobs[taskId] = job
        return taskId
    }

    /**
     * 执行单个任务的核心逻辑。
     * Returns both the result and the config used, so the caller can reuse the config
     * for summary generation without a duplicate lookup.
     */
    private suspend fun executeTask(
        sessionId: Long,
        agentType: String,
        task: String,
        contextStr: String?,
        files: List<String>?
    ): TaskExecutionResult = withTimeout(TASK_TIMEOUT_MS) {
        // 1. 获取模型配置
        val config = getAgentModelConfig(agentType)
            ?: throw IllegalStateException("Agent type $agentType has no model configured. Please configure in settings.")

        // 2. 构建系统提示
        val systemPrompt = AgentPrompts.getPrompt(agentType)

        // 3. 构建用户消息
        val userMessage = buildUserMessage(task, contextStr, files)

        // 4. 调用 LLM API（非流式，等待完整结果）
        val result = ApiClient.executeCompletion(config, systemPrompt, userMessage)
            ?: throw IllegalStateException("LLM API returned empty result")

        TaskExecutionResult(result = result, config = config)
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
     * 获取任务状态。
     */
    fun getStatus(taskId: String): AgentTaskState? {
        return _taskStates.value[taskId]
    }

    /**
     * 获取指定会话的所有任务。
     */
    fun getTasksForSession(sessionId: Long): List<AgentTaskState> {
        return _taskStates.value.values.filter { it.sessionId == sessionId }
    }

    /**
     * 获取指定会话已完成的任务（用于注入系统提示）。
     */
    fun getCompletedTasksForSession(sessionId: Long): List<AgentTaskState> {
        return _taskStates.value.values.filter {
            it.sessionId == sessionId && it.status == AgentTaskStatus.COMPLETED
        }
    }

    /**
     * 更新任务状态（线程安全）。
     */
    private fun updateState(taskId: String, state: AgentTaskState) {
        _taskStates.update { it + (taskId to state) }
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
