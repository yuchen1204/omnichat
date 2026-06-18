package com.omnichat.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SubAgent 任务生命周期事件。
 * 由 ChatViewModel 收集，用于渲染 in-chat 状态卡片。
 */
sealed class SubAgentEvent {
    data class TaskStarted(
        val taskId: String,
        val sessionId: Long,
        val taskType: String,
        val description: String
    ) : SubAgentEvent()

    data class TaskProgress(
        val taskId: String,
        val message: String
    ) : SubAgentEvent()

    data class TaskCompleted(
        val taskId: String,
        val sessionId: Long,
        val result: String
    ) : SubAgentEvent()

    data class TaskFailed(
        val taskId: String,
        val sessionId: Long,
        val error: String
    ) : SubAgentEvent()
}

/**
 * SubAgent 生命周期事件总线。
 *
 * SubAgent 执行任务时发射事件（Started → Progress → Completed/Failed），
 * ChatViewModel 收集事件并管理 in-chat 状态卡片。
 */
object SubAgentEventBus {

    private const val TAG = "SubAgentEventBus"

    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    /**
     * 发射 SubAgent 生命周期事件。
     * 由 [SubAgent.execute] 和 [SubAgent.executeSync] 在任务各阶段调用。
     */
    suspend fun emit(event: SubAgentEvent) {
        Log.d(TAG, "[emit] ${event::class.simpleName}: taskId=${when (event) {
            is SubAgentEvent.TaskStarted -> event.taskId
            is SubAgentEvent.TaskProgress -> event.taskId
            is SubAgentEvent.TaskCompleted -> event.taskId
            is SubAgentEvent.TaskFailed -> event.taskId
        }}")
        _events.emit(event)
    }
}
