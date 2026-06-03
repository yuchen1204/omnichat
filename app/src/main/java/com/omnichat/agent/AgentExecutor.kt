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

    // 并发控制：全局最多 3 个并行任务
    private val globalSemaphore = Semaphore(3)

    // 每种 agent 类型的信号量（各自最多 1 个并行）
    private val typeSemaphores = AgentPrompts.ALL_TYPES.associateWith { Semaphore(1) }

    companion object {
        /** 任务超时时间（毫秒） */
        private const val TASK_TIMEOUT_MS = 5 * 60 * 1000L

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
                val typeSemaphore = typeSemaphores[agentType] ?: typeSemaphores["general"]!!
                if (!globalSemaphore.tryAcquire()) {
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.FAILED,
                        error = "全局并发数已达上限（3），请等待其他任务完成"
                    ))
                    return@launch
                }
                if (!typeSemaphore.tryAcquire()) {
                    globalSemaphore.release()
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.FAILED,
                        error = "该代理类型（$agentType）已有任务在执行，请等待完成"
                    ))
                    return@launch
                }

                try {
                    // 更新状态为 RUNNING
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.RUNNING,
                        startedAt = System.currentTimeMillis()
                    ))

                    // 执行任务
                    val result = executeTask(sessionId, agentType, task, contextStr, files)

                    // 更新状态为 COMPLETED
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.COMPLETED,
                        result = result,
                        completedAt = System.currentTimeMillis()
                    ))

                    // 插入结果消息到主会话
                    insertResultMessage(sessionId, taskId, agentType, result)

                } finally {
                    // 释放许可
                    typeSemaphore.release()
                    globalSemaphore.release()
                }
            } catch (e: CancellationException) {
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.CANCELLED,
                    error = "任务被取消"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "任务执行失败: $taskId", e)
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.FAILED,
                    error = e.localizedMessage ?: "执行失败"
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
     */
    private suspend fun executeTask(
        sessionId: Long,
        agentType: String,
        task: String,
        contextStr: String?,
        files: List<String>?
    ): String = withTimeout(TASK_TIMEOUT_MS) {
        // 1. 获取模型配置
        val config = getAgentModelConfig(agentType)
            ?: throw IllegalStateException("代理类型 $agentType 未配置模型，请在设置中配置")

        // 2. 构建系统提示
        val systemPrompt = AgentPrompts.getPrompt(agentType)

        // 3. 构建用户消息
        val userMessage = buildUserMessage(task, contextStr, files)

        // 4. 调用 LLM API（非流式，等待完整结果）
        val messages = listOf(
            Message(sessionId = sessionId, role = "system", content = systemPrompt),
            Message(sessionId = sessionId, role = "user", content = userMessage)
        )

        val result = ApiClient.executeCompletion(config, systemPrompt, userMessage)
            ?: throw IllegalStateException("LLM 调用返回空结果")

        result
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
            role = "agent_result",
            content = result,
            toolCallId = taskId,
            toolCallsJson = JSONObject().apply {
                put("agentType", agentType)
                put("status", "completed")
                put("completedAt", System.currentTimeMillis())
            }.toString()
        )
        repository.insertMessage(msg)
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
     * 更新任务状态。
     */
    private fun updateState(taskId: String, state: AgentTaskState) {
        _taskStates.value = _taskStates.value + (taskId to state)
    }

    /**
     * 清理已完成的任务状态（可选，用于内存管理）。
     */
    fun clearCompletedTasks() {
        _taskStates.value = _taskStates.value.filterValues {
            it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.FAILED && it.status != AgentTaskStatus.CANCELLED
        }
    }
}
