package com.omnichat.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Workflow 执行模式。
 */
enum class WorkflowMode {
    PIPELINE,
    DAG,
    CONVERSATIONAL
}

/**
 * 单个步骤的 UI 状态。
 */
data class WorkflowStepUiState(
    val stepId: String,
    val agentType: String,
    val task: String,
    val status: WorkflowStepStatus,
    val result: String? = null,
    val error: String? = null,
    val dependsOn: List<String> = emptyList()
)

/**
 * 整体 Workflow 的 UI 状态。
 */
data class WorkflowUiState(
    val workflowId: String,
    val sessionId: Long,
    val mode: WorkflowMode,
    val status: WorkflowStatus,
    val steps: List<WorkflowStepUiState>,
    val currentStepIndex: Int = 0,
    val topic: String? = null,        // Conversational 模式的主题
    val agentA: String? = null,      // Conversational 模式的 Agent A
    val agentB: String? = null,      // Conversational 模式的 Agent B
    val currentRound: Int = 0,       // Conversational 模式的当前轮次
    val maxRounds: Int = 5,          // Conversational 模式的最大轮次
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null
)

/**
 * Workflow 整体状态。
 */
enum class WorkflowStatus {
    RUNNING, COMPLETED, FAILED, CANCELLED
}

/**
 * Workflow 生命周期事件。
 */
sealed class WorkflowEvent {
    data class WorkflowStarted(
        val workflowId: String,
        val sessionId: Long,
        val mode: WorkflowMode,
        val totalSteps: Int,
        val topic: String? = null,
        val agentA: String? = null,
        val agentB: String? = null,
        val maxRounds: Int = 5
    ) : WorkflowEvent()

    data class StepStarted(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val stepIndex: Int,
        val agentType: String,
        val task: String
    ) : WorkflowEvent()

    data class StepCompleted(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val stepIndex: Int,
        val result: String?,
        val status: WorkflowStepStatus
    ) : WorkflowEvent()

    data class WorkflowProgress(
        val workflowId: String,
        val sessionId: Long,
        val completedSteps: Int,
        val totalSteps: Int,
        val currentStepId: String?,
        val currentStepIndex: Int
    ) : WorkflowEvent()

    data class WorkflowCompleted(
        val workflowId: String,
        val sessionId: Long,
        val results: List<StepResult>
    ) : WorkflowEvent()

    data class WorkflowFailed(
        val workflowId: String,
        val sessionId: Long,
        val error: String
    ) : WorkflowEvent()

    data class StepWokeUp(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val fromAgent: String,
        val messagePreview: String
    ) : WorkflowEvent()

    data class StepRecalled(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val fromAgent: String,
        val revisionPrompt: String
    ) : WorkflowEvent()

    data class StepEnteredIdle(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val agentType: String,
        val reason: String
    ) : WorkflowEvent()

    data class IdleTimeoutWarning(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val idleDurationMs: Long,
        val message: String
    ) : WorkflowEvent()

    data class StepTimeout(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val durationMs: Long,
        val error: String
    ) : WorkflowEvent()

    data class MessageRoutingError(
        val workflowId: String,
        val sessionId: Long,
        val from: String,
        val to: String,
        val error: String,
        val availableTargets: List<String>
    ) : WorkflowEvent()

    data class StepRevisionCompleted(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val revisionCount: Int,
        val result: String
    ) : WorkflowEvent()
}

/**
 * Workflow 事件总线。
 *
 * 由 RunWorkflowTool 发射事件，ChatViewModel 收集并管理 UI 状态。
 */
object WorkflowEventBus {

    private const val TAG = "WorkflowEventBus"

    private val _events = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    /**
     * 发射 Workflow 事件。
     */
    suspend fun emit(event: WorkflowEvent) {
        Log.d(TAG, "[emit] ${event::class.simpleName}: workflowId=${when (event) {
            is WorkflowEvent.WorkflowStarted -> event.workflowId
            is WorkflowEvent.StepStarted -> event.workflowId
            is WorkflowEvent.StepCompleted -> event.workflowId
            is WorkflowEvent.WorkflowProgress -> event.workflowId
            is WorkflowEvent.WorkflowCompleted -> event.workflowId
            is WorkflowEvent.WorkflowFailed -> event.workflowId
            is WorkflowEvent.StepWokeUp -> event.workflowId
            is WorkflowEvent.StepRecalled -> event.workflowId
            is WorkflowEvent.StepEnteredIdle -> event.workflowId
            is WorkflowEvent.IdleTimeoutWarning -> event.workflowId
            is WorkflowEvent.StepTimeout -> event.workflowId
            is WorkflowEvent.MessageRoutingError -> event.workflowId
            is WorkflowEvent.StepRevisionCompleted -> event.workflowId
        }}")
        _events.emit(event)
    }
}
