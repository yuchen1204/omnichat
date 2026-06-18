package com.omnichat.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lifecycle events emitted by SubAgent tasks.
 * Used by ChatViewModel to render in-chat status cards.
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
 * Event bus for SubAgent lifecycle events.
 * Replaces TimerAutoCheckManager — push-based instead of poll-based.
 */
object SubAgentEventBus {
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    suspend fun emit(event: SubAgentEvent) {
        _events.emit(event)
    }
}
