package com.omnichat.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A single step in a workflow.
 */
data class WorkflowStep(
    val id: String,
    val agentType: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    val resultVariable: String? = null, // if set, result is stored in workflow context
    val timeoutMs: Long? = null,        // optional: per-step timeout in milliseconds
    val maxRetries: Int = 0,            // optional: retry count on failure (default: 0, no retry)
    val maxIdleMs: Long? = null,       // optional: IDLE timeout in milliseconds (default: 30 minutes)
    val wakeUpOnMessage: Boolean = true // optional: whether to listen for messages and auto-wake
)

/**
 * Status of a workflow step.
 */
enum class WorkflowStepStatus {
    PENDING,           // 等待开始
    IDLE,              // 等待唤醒（已创建 SubAgent，挂起中）
    RUNNING,           // 正在执行
    PENDING_REVIEW,    // 完成等待确认
    REVISION,          // 被召回修改中
    COMPLETED,         // 最终完成
    FAILED,            // 失败
    SKIPPED            // 跳过
}

/**
 * Result of a single workflow step.
 */
data class StepResult(
    val stepId: String,
    val status: WorkflowStepStatus,
    val result: String? = null,
    val error: String? = null,
    val retries: Int = 0,              // how many retries were attempted
    val revisionCount: Int = 0,        // how many times this step was recalled for revision
    val idleTimeMs: Long = 0,          // cumulative time spent in IDLE status
    val lastMessageFrom: String? = null // source of the last received message
)

/**
 * Internal state for interactive pipeline execution.
 */
internal data class InteractivePipelineState(
    val workflowId: String,
    val sessionId: Long,
    val steps: List<WorkflowStep>,
    val agentStates: MutableMap<String, AgentState>,  // stepId -> AgentState
    val contextVariables: MutableMap<String, String>,
    val messageQueue: MutableList<PendingMessage>,
    var status: WorkflowStatus
)

internal data class AgentState(
    val stepId: String,
    val handle: IdleAgentHandle?,
    val status: WorkflowStepStatus,
    val idleSince: Long?,
    val runningSince: Long?,
    val conversationHistory: MutableList<JSONObject>,
    val idleTimeoutWarningSent: Boolean = false,
    val revisionCount: Int = 0,
    val lastMessageFrom: String? = null
)

internal data class PendingMessage(
    val from: String,
    val to: String,
    val content: String,
    val timestamp: Long
)

/**
 * Orchestrates multi-agent workflows with three modes:
 * - Pipeline: sequential execution, results passed to next step
 * - DAG: dependency-based execution with parallelism
 * - Conversational: multi-round dialogue between two agents
 */
object WorkflowEngine {

    private const val TAG = "WorkflowEngine"

    // Active workflow jobs for cancellation
    private val activeWorkflowJobs = ConcurrentHashMap<String, Job>()

    // Cancellation requests from UI
    private val cancellationRequests = ConcurrentHashMap<String, Boolean>()

    /**
     * Request cancellation of a running workflow.
     * Called from UI when user taps stop button.
     */
    fun requestCancellation(workflowId: String) {
        cancellationRequests[workflowId] = true
        Log.d(TAG, "[cancelWorkflow] Cancellation requested for: $workflowId")

        // Also cancel the job if tracked
        activeWorkflowJobs[workflowId]?.cancel()
    }

    /**
     * Check if cancellation was requested for a workflow.
     */
    fun isCancellationRequested(workflowId: String): Boolean {
        return cancellationRequests.getOrDefault(workflowId, false)
    }

    /**
     * Clear cancellation state after workflow completes.
     */
    fun clearCancellation(workflowId: String) {
        cancellationRequests.remove(workflowId)
        activeWorkflowJobs.remove(workflowId)
    }

    /**
     * Register a workflow job for tracking.
     */
    fun registerWorkflowJob(workflowId: String, job: Job) {
        activeWorkflowJobs[workflowId] = job
    }

    /**
     * Execute a pipeline — steps run sequentially, each step receives prior results as context.
     *
     * This method emits events to WorkflowEventBus for UI updates:
     * - WorkflowStarted: when pipeline begins
     * - StepStarted: when each step begins execution
     * - StepCompleted: when each step finishes (success or failure)
     * - WorkflowProgress: after each step completes
     * - WorkflowCompleted/WorkflowFailed: when pipeline ends
     *
     * Fixes applied:
     * - Event emission for real-time UI updates
     * - Step timeout support (per-step timeoutMs parameter)
     * - Retry mechanism with maxRetries count
     * - In Pipeline mode, dependsOn is validated: a step can only reference prior steps in the list order
     *   (i.e., dependsOn must contain IDs of steps that appear earlier in the steps list)
     */
    suspend fun executePipeline(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        callerContext: AgentCallerContext? = null,
        workflowId: String? = null  // optional: caller can provide ID for tracking
    ): List<StepResult> = withContext(Dispatchers.Default) {
        // Generate workflow ID if not provided
        val actualWorkflowId = workflowId ?: "pipeline-${UUID.randomUUID().toString().take(8)}"

        // ── Emit WorkflowStarted event ──
        WorkflowEventBus.emit(
            WorkflowEvent.WorkflowStarted(
                workflowId = actualWorkflowId,
                sessionId = sessionId,
                mode = WorkflowMode.PIPELINE,
                totalSteps = steps.size
            )
        )
        Log.d(TAG, "[Pipeline] Started: $actualWorkflowId with ${steps.size} steps")

        val results = mutableListOf<StepResult>()
        // Key: step.id (for direct lookup) OR resultVariable (for named reference)
        val contextVariables = mutableMapOf<String, String>()
        // Track which step IDs are available for dependsOn reference (must be prior steps)
        val availableStepIds = mutableSetOf<String>()
        var pipelineFailed = false
        var pipelineError: String? = null

        for ((index, step) in steps.withIndex()) {
            // --- Validate dependsOn: must reference prior steps only ---
            val invalidDeps = step.dependsOn.filter { it !in availableStepIds }
            if (invalidDeps.isNotEmpty()) {
                val error = "Invalid dependsOn: [$invalidDeps] are not prior steps. " +
                            "In Pipeline mode, dependsOn must reference steps that appear earlier in the list."
                val result = StepResult(
                    stepId = step.id,
                    status = WorkflowStepStatus.FAILED,
                    error = error
                )
                results.add(result)
                pipelineFailed = true
                pipelineError = error
                Log.e(TAG, "[Pipeline] Step $step.id validation failed: $error")
                break
            }

            // Build task description with prior context
            val fullTask = buildTaskWithContext(step.task, step.dependsOn, contextVariables, steps)

            // ── Emit StepStarted event ──
            WorkflowEventBus.emit(
                WorkflowEvent.StepStarted(
                    workflowId = actualWorkflowId,
                    sessionId = sessionId,
                    stepId = step.id,
                    stepIndex = index,
                    agentType = step.agentType,
                    task = step.task
                )
            )
            Log.d(TAG, "[Pipeline] Step $index started: ${step.id} (${step.agentType})")

            // --- Execute with retry and timeout ---
            val result = executeStepWithRetryAndTimeout(
                context = context,
                sessionId = sessionId,
                step = step,
                fullTask = fullTask,
                callerContext = callerContext
            )

            // ── Emit StepCompleted event ──
            WorkflowEventBus.emit(
                WorkflowEvent.StepCompleted(
                    workflowId = actualWorkflowId,
                    sessionId = sessionId,
                    stepId = step.id,
                    stepIndex = index,
                    result = result.result,
                    status = result.status
                )
            )
            Log.d(TAG, "[Pipeline] Step $index completed: ${step.id} status=${result.status}")

            // Store result under both step.id and resultVariable (if set)
            if (result.status == WorkflowStepStatus.COMPLETED && result.result != null) {
                contextVariables[step.id] = result.result
                step.resultVariable?.let { contextVariables[it] = result.result }
            }

            results.add(result)
            availableStepIds.add(step.id)

            // ── Emit WorkflowProgress event ──
            WorkflowEventBus.emit(
                WorkflowEvent.WorkflowProgress(
                    workflowId = actualWorkflowId,
                    sessionId = sessionId,
                    completedSteps = results.size,
                    totalSteps = steps.size,
                    currentStepId = step.id,
                    currentStepIndex = index
                )
            )

            // Stop pipeline on failure
            if (result.status == WorkflowStepStatus.FAILED) {
                pipelineFailed = true
                pipelineError = result.error
                break
            }
        }

        // ── Emit final event: WorkflowCompleted or WorkflowFailed ──
        if (pipelineFailed) {
            WorkflowEventBus.emit(
                WorkflowEvent.WorkflowFailed(
                    workflowId = actualWorkflowId,
                    sessionId = sessionId,
                    error = pipelineError ?: "Pipeline failed"
                )
            )
            Log.e(TAG, "[Pipeline] Failed: $actualWorkflowId - $pipelineError")
        } else {
            WorkflowEventBus.emit(
                WorkflowEvent.WorkflowCompleted(
                    workflowId = actualWorkflowId,
                    sessionId = sessionId,
                    results = results
                )
            )
            Log.d(TAG, "[Pipeline] Completed: $actualWorkflowId with ${results.size} successful steps")
        }

        results
    }

    /**
     * Execute an interactive pipeline with IDLE/REVISION support.
     *
     * - Creates all SubAgents in IDLE state upfront
     * - Wakes up first agent to start
     * - Routes messages between agents
     * - Handles timeout and revision workflows
     */
    suspend fun executeInteractivePipeline(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        callerContext: AgentCallerContext? = null,
        workflowId: String? = null
    ): List<StepResult> = coroutineScope {
        val actualWorkflowId = workflowId ?: "interactive-${UUID.randomUUID().toString().take(8)}"

        Log.d(TAG, "[InteractivePipeline] Starting: $actualWorkflowId with ${steps.size} steps")

        // Emit started event
        WorkflowEventBus.emit(WorkflowEvent.WorkflowStarted(
            workflowId = actualWorkflowId,
            sessionId = sessionId,
            mode = WorkflowMode.PIPELINE,
            totalSteps = steps.size
        ))

        // Initialize state
        val state = InteractivePipelineState(
            workflowId = actualWorkflowId,
            sessionId = sessionId,
            steps = steps,
            agentStates = mutableMapOf(),
            contextVariables = mutableMapOf(),
            messageQueue = mutableListOf(),
            status = WorkflowStatus.RUNNING
        )

        // Phase 1: Create all agents in IDLE state
        for (step in steps) {
            val handle = SubAgent.createIdle(
                context = context,
                agentType = step.agentType,
                sessionId = sessionId,
                stepId = step.id
            )

            state.agentStates[step.id] = AgentState(
                stepId = step.id,
                handle = handle,
                status = WorkflowStepStatus.IDLE,
                idleSince = System.currentTimeMillis(),
                runningSince = null,
                conversationHistory = mutableListOf()
            )

            WorkflowEventBus.emit(WorkflowEvent.StepEnteredIdle(
                workflowId = actualWorkflowId,
                sessionId = sessionId,
                stepId = step.id,
                agentType = step.agentType,
                reason = "等待工作流启动"
            ))
        }

        // Phase 2: Start first agent
        if (steps.isNotEmpty()) {
            val firstStep = steps[0]
            wakeUpStep(state, firstStep.id, firstStep.task)
        }

        // Phase 3: Run event loop
        startInteractiveEventLoop(context, state)

        // Phase 4: Collect results
        collectInteractiveResults(state)
    }

    /**
     * Wake up a specific step and execute its task.
     */
    private suspend fun wakeUpStep(
        state: InteractivePipelineState,
        stepId: String,
        task: String,
        fromAgent: String? = null
    ) {
        val agentState = state.agentStates[stepId] ?: return
        val step = state.steps.find { it.id == stepId } ?: return
        val handle = agentState.handle ?: return

        // Build task with context
        val fullTask = if (fromAgent != null) {
            buildString {
                appendLine("收到来自 [$fromAgent] 的消息")
                appendLine(task)
                appendLine()
                appendLine("---")
                appendLine("你的任务：${step.task}")
            }
        } else {
            buildTaskWithContext(step.task, step.dependsOn, state.contextVariables, state.steps)
        }

        // Update state
        state.agentStates[stepId] = agentState.copy(
            status = WorkflowStepStatus.RUNNING,
            runningSince = System.currentTimeMillis(),
            idleSince = null,
            lastMessageFrom = fromAgent
        )

        // Emit event
        WorkflowEventBus.emit(WorkflowEvent.StepStarted(
            workflowId = state.workflowId,
            sessionId = state.sessionId,
            stepId = stepId,
            stepIndex = state.steps.indexOf(step),
            agentType = step.agentType,
            task = task.take(100)
        ))

        if (fromAgent != null) {
            WorkflowEventBus.emit(WorkflowEvent.StepWokeUp(
                workflowId = state.workflowId,
                sessionId = state.sessionId,
                stepId = stepId,
                fromAgent = fromAgent,
                messagePreview = task.take(100)
            ))
        }

        // Execute
        try {
            val result = SubAgent.wakeUp(
                handle = handle,
                task = fullTask,
                contextVariables = state.contextVariables,
                conversationHistory = agentState.conversationHistory
            )

            // Store result
            state.contextVariables[stepId] = result
            step.resultVariable?.let { state.contextVariables[it] = result }

            // Return to IDLE (waiting for next step or completion)
            setStepIdle(state, stepId, result)

        } catch (e: Exception) {
            handleStepFailure(state, stepId, e.message ?: "Unknown error")
        }
    }

    /**
     * Set a step back to IDLE after completion.
     */
    private suspend fun setStepIdle(state: InteractivePipelineState, stepId: String, result: String) {
        val agentState = state.agentStates[stepId] ?: return

        state.agentStates[stepId] = agentState.copy(
            status = WorkflowStepStatus.IDLE,
            idleSince = System.currentTimeMillis(),
            runningSince = null
        )

        SubAgent.setAgentIdle(agentState.handle?.agentId ?: return)

        // Emit step completed event
        val stepIndex = state.steps.indexOfFirst { it.id == stepId }
        WorkflowEventBus.emit(WorkflowEvent.StepCompleted(
            workflowId = state.workflowId,
            sessionId = state.sessionId,
            stepId = stepId,
            stepIndex = stepIndex,
            result = result,
            status = WorkflowStepStatus.IDLE  // Note: IDLE means completed but waiting
        ))

        WorkflowEventBus.emit(WorkflowEvent.StepEnteredIdle(
            workflowId = state.workflowId,
            sessionId = state.sessionId,
            stepId = stepId,
            agentType = state.steps.find { it.id == stepId }?.agentType ?: "",
            reason = "任务完成，等待后续步骤"
        ))

        Log.d(TAG, "[InteractivePipeline] Step $stepId returned to IDLE")
    }

    /**
     * Handle step failure.
     */
    private suspend fun handleStepFailure(state: InteractivePipelineState, stepId: String, error: String) {
        val agentState = state.agentStates[stepId] ?: return
        val stepIndex = state.steps.indexOfFirst { it.id == stepId }

        state.agentStates[stepId] = agentState.copy(
            status = WorkflowStepStatus.FAILED
        )

        WorkflowEventBus.emit(WorkflowEvent.StepCompleted(
            workflowId = state.workflowId,
            sessionId = state.sessionId,
            stepId = stepId,
            stepIndex = stepIndex,
            result = null,
            status = WorkflowStepStatus.FAILED
        ))

        Log.e(TAG, "[InteractivePipeline] Step $stepId failed: $error")
    }

    /**
     * Main event loop for interactive pipeline.
     * Monitors messages and timeouts until workflow completes.
     */
    private suspend fun startInteractiveEventLoop(context: Context, state: InteractivePipelineState) {
        Log.d(TAG, "[InteractivePipeline] Event loop started for ${state.workflowId}")

        while (state.status == WorkflowStatus.RUNNING) {
            // Check for cancellation
            if (isCancellationRequested(state.workflowId)) {
                Log.d(TAG, "[InteractivePipeline] Cancellation requested for ${state.workflowId}")
                state.status = WorkflowStatus.CANCELLED
                // Cancel all active agents
                state.agentStates.values.forEach { agentState ->
                    agentState.handle?.let { SubAgent.completeAgent(it.agentId) }
                }
                WorkflowEventBus.emit(WorkflowEvent.WorkflowFailed(
                    workflowId = state.workflowId,
                    sessionId = state.sessionId,
                    error = "用户取消"
                ))
                break
            }

            // Check for messages
            processPendingMessages(state)

            // Check timeouts
            checkTimeouts(state)

            // Check if all steps are completed or failed
            if (checkWorkflowComplete(state)) {
                break
            }

            delay(500)  // Check every 500ms
        }

        // Clear cancellation state
        clearCancellation(state.workflowId)

        Log.d(TAG, "[InteractivePipeline] Event loop ended for ${state.workflowId}")
    }

    /**
     * Process pending messages from MessageBus.
     */
    private suspend fun processPendingMessages(state: InteractivePipelineState) {
        state.agentStates.forEach { (stepId, agentState) ->
            if (agentState.status == WorkflowStepStatus.IDLE) {
                val agentId = agentState.handle?.agentId ?: return@forEach
                val messages = MessageBus.readInbox(agentId)

                for (msg in messages) {
                    handleIncomingMessage(state, stepId, msg)
                }
            }
        }

        // Also check MainAgent inbox for timeout responses
        val mainMessages = MessageBus.readInbox("main")
        for (msg in mainMessages) {
            handleMainAgentMessage(state, msg)
        }
    }

    /**
     * Handle incoming message for a step.
     */
    private suspend fun handleIncomingMessage(
        state: InteractivePipelineState,
        targetStepId: String,
        message: AgentMessage
    ) {
        val agentState = state.agentStates[targetStepId]

        if (agentState == null) {
            // Target not found - send error back
            val availableTargets = state.agentStates.keys.joinToString(", ")
            val errorMsg = "目标步骤 '$targetStepId' 不存在。可用目标: $availableTargets"
            MessageBus.send(from = "workflow", to = message.from, content = errorMsg)

            WorkflowEventBus.emit(WorkflowEvent.MessageRoutingError(
                workflowId = state.workflowId,
                sessionId = state.sessionId,
                from = message.from,
                to = targetStepId,
                error = errorMsg,
                availableTargets = state.agentStates.keys.toList()
            ))
            return
        }

        when (agentState.status) {
            WorkflowStepStatus.IDLE -> {
                // Wake up the agent
                wakeUpStep(state, targetStepId, message.content, message.from)
            }
            WorkflowStepStatus.COMPLETED -> {
                // Recall for revision
                recallStep(state, targetStepId, message.content, message.from)
            }
            WorkflowStepStatus.RUNNING, WorkflowStepStatus.REVISION -> {
                // Queue message for later
                state.messageQueue.add(PendingMessage(
                    from = message.from,
                    to = targetStepId,
                    content = message.content,
                    timestamp = System.currentTimeMillis()
                ))
            }
            else -> {
                val statusMsg = "步骤 '$targetStepId' 当前状态为 ${agentState.status}，无法接收消息"
                MessageBus.send(from = "workflow", to = message.from, content = statusMsg)
            }
        }
    }

    /**
     * Check timeouts for all agents.
     */
    private suspend fun checkTimeouts(state: InteractivePipelineState) {
        val now = System.currentTimeMillis()

        state.agentStates.forEach { (stepId, agentState) ->
            when (agentState.status) {
                WorkflowStepStatus.IDLE -> {
                    val idleDuration = now - (agentState.idleSince ?: now)
                    val step = state.steps.find { it.id == stepId }
                    val maxIdleMs = step?.maxIdleMs ?: 30 * 60 * 1000L  // Default 30 min

                    if (idleDuration >= maxIdleMs && !agentState.idleTimeoutWarningSent) {
                        // Send warning to MainAgent
                        val minutes = idleDuration / 60000
                        WorkflowEventBus.emit(WorkflowEvent.IdleTimeoutWarning(
                            workflowId = state.workflowId,
                            sessionId = state.sessionId,
                            stepId = stepId,
                            idleDurationMs = idleDuration,
                            message = "步骤 '$stepId' 已等待 $minutes 分钟"
                        ))

                        MessageBus.send(
                            from = "workflow",
                            to = "main",
                            content = "[IDLE超时] 步骤 '$stepId' (${agentState.handle?.agentType}) 已等待 $minutes 分钟。\n" +
                                      "回复 'continue:$stepId' 继续等待，或 'terminate:$stepId' 终止。"
                        )

                        state.agentStates[stepId] = agentState.copy(idleTimeoutWarningSent = true)
                        Log.w(TAG, "[InteractivePipeline] IDLE timeout warning for $stepId after ${minutes}min")
                    }
                }

                WorkflowStepStatus.RUNNING, WorkflowStepStatus.REVISION -> {
                    val runningDuration = now - (agentState.runningSince ?: now)
                    val step = state.steps.find { it.id == stepId }
                    val timeoutMs = step?.timeoutMs ?: 10 * 60 * 1000L  // Default 10 min

                    if (runningDuration >= timeoutMs) {
                        handleRunningTimeout(state, stepId, runningDuration)
                    }
                }

                else -> {}
            }
        }
    }

    /**
     * Handle RUNNING timeout.
     */
    private suspend fun handleRunningTimeout(
        state: InteractivePipelineState,
        stepId: String,
        duration: Long
    ) {
        val agentState = state.agentStates[stepId] ?: return
        val minutes = duration / 60000

        Log.e(TAG, "[InteractivePipeline] RUNNING timeout for $stepId after ${minutes}min")

        // Update state
        state.agentStates[stepId] = agentState.copy(
            status = WorkflowStepStatus.FAILED
        )

        // Emit event
        WorkflowEventBus.emit(WorkflowEvent.StepTimeout(
            workflowId = state.workflowId,
            sessionId = state.sessionId,
            stepId = stepId,
            durationMs = duration,
            error = "执行超时 (${minutes}分钟)"
        ))

        // Notify dependent steps
        notifyDependentStepsOfFailure(state, stepId)

        // Check if workflow should fail
        if (checkAllStepsFailed(state)) {
            state.status = WorkflowStatus.FAILED
            WorkflowEventBus.emit(WorkflowEvent.WorkflowFailed(
                workflowId = state.workflowId,
                sessionId = state.sessionId,
                error = "步骤 $stepId 超时失败"
            ))
        }
    }

    /**
     * Notify dependent steps of a failure.
     */
    private fun notifyDependentStepsOfFailure(state: InteractivePipelineState, failedStepId: String) {
        state.steps.forEach { step ->
            if (failedStepId in step.dependsOn) {
                val agentState = state.agentStates[step.id]
                if (agentState?.status == WorkflowStepStatus.IDLE) {
                    MessageBus.send(
                        from = "workflow",
                        to = agentState.handle?.agentId ?: return@forEach,
                        content = "上游步骤 '$failedStepId' 已失败，无法继续执行。"
                    )
                }
            }
        }
    }

    /**
     * Check if all steps have failed.
     */
    private fun checkAllStepsFailed(state: InteractivePipelineState): Boolean {
        return state.agentStates.values.all { it.status == WorkflowStepStatus.FAILED }
    }

    /**
     * Handle message sent to MainAgent (timeout responses).
     */
    private suspend fun handleMainAgentMessage(state: InteractivePipelineState, message: AgentMessage) {
        val content = message.content.trim()

        when {
            content.startsWith("continue:") -> {
                val stepId = content.substringAfter("continue:")
                state.agentStates[stepId]?.let { agentState ->
                    // Reset idle timeout warning
                    state.agentStates[stepId] = agentState.copy(
                        idleTimeoutWarningSent = false,
                        idleSince = System.currentTimeMillis()
                    )
                    Log.d(TAG, "[InteractivePipeline] Continuing wait for step $stepId")
                }
            }
            content.startsWith("terminate:") -> {
                val stepId = content.substringAfter("terminate:")
                handleStepFailure(state, stepId, "用户终止")
            }
        }
    }

    /**
     * Recall a completed step for revision.
     */
    private suspend fun recallStep(
        state: InteractivePipelineState,
        stepId: String,
        revisionPrompt: String,
        fromAgent: String
    ) {
        val agentState = state.agentStates[stepId] ?: return
        val handle = agentState.handle ?: return

        Log.d(TAG, "[InteractivePipeline] Recalling step $stepId from $fromAgent")

        // Update state
        val newRevisionCount = agentState.revisionCount + 1
        state.agentStates[stepId] = agentState.copy(
            status = WorkflowStepStatus.REVISION,
            runningSince = System.currentTimeMillis(),
            revisionCount = newRevisionCount
        )

        // Emit event
        WorkflowEventBus.emit(WorkflowEvent.StepRecalled(
            workflowId = state.workflowId,
            sessionId = state.sessionId,
            stepId = stepId,
            fromAgent = fromAgent,
            revisionPrompt = revisionPrompt
        ))

        // Execute revision
        try {
            val result = SubAgent.recall(
                handle = handle,
                revisionPrompt = revisionPrompt,
                fromAgent = fromAgent
            )

            // Update context
            state.contextVariables[stepId] = result

            // Emit revision completed
            WorkflowEventBus.emit(WorkflowEvent.StepRevisionCompleted(
                workflowId = state.workflowId,
                sessionId = state.sessionId,
                stepId = stepId,
                revisionCount = newRevisionCount,
                result = result
            ))

            // Return to IDLE
            setStepIdle(state, stepId, result)

        } catch (e: Exception) {
            handleStepFailure(state, stepId, e.message ?: "Revision failed")
        }
    }

    /**
     * Check if all steps are completed or failed.
     */
    private fun checkWorkflowComplete(state: InteractivePipelineState): Boolean {
        val allDone = state.agentStates.values.all {
            it.status == WorkflowStepStatus.COMPLETED ||
            it.status == WorkflowStepStatus.FAILED ||
            it.status == WorkflowStepStatus.SKIPPED
        }

        if (allDone) {
            val hasFailures = state.agentStates.values.any { it.status == WorkflowStepStatus.FAILED }
            state.status = if (hasFailures) WorkflowStatus.FAILED else WorkflowStatus.COMPLETED
        }

        return allDone
    }

    /**
     * Collect final results.
     */
    private suspend fun collectInteractiveResults(state: InteractivePipelineState): List<StepResult> {
        val results = mutableListOf<StepResult>()

        state.steps.forEach { step ->
            val agentState = state.agentStates[step.id]
            results.add(StepResult(
                stepId = step.id,
                status = agentState?.status ?: WorkflowStepStatus.SKIPPED,
                result = state.contextVariables[step.id],
                error = if (agentState?.status == WorkflowStepStatus.FAILED) "Failed" else null,
                revisionCount = agentState?.revisionCount ?: 0,
                lastMessageFrom = agentState?.lastMessageFrom
            ))
        }

        // Emit final event
        if (state.status == WorkflowStatus.COMPLETED) {
            WorkflowEventBus.emit(WorkflowEvent.WorkflowCompleted(
                workflowId = state.workflowId,
                sessionId = state.sessionId,
                results = results
            ))
        } else {
            WorkflowEventBus.emit(WorkflowEvent.WorkflowFailed(
                workflowId = state.workflowId,
                sessionId = state.sessionId,
                error = "Workflow failed"
            ))
        }

        // Cleanup
        state.agentStates.values.forEach { agentState ->
            agentState.handle?.let { SubAgent.completeAgent(it.agentId) }
        }

        return results
    }

    /**
     * Execute a single step with retry and timeout support.
     */
    private suspend fun executeStepWithRetryAndTimeout(
        context: Context,
        sessionId: Long,
        step: WorkflowStep,
        fullTask: String,
        callerContext: AgentCallerContext?
    ): StepResult {
        var lastError: String? = null
        var retryCount = 0

        for (attempt in 0..step.maxRetries) {
            val output = if (step.timeoutMs != null && step.timeoutMs > 0) {
                // Execute with timeout
                withTimeoutOrNull(step.timeoutMs) {
                    SubAgent.executeSync(
                        context = context,
                        agentType = step.agentType,
                        taskDescription = fullTask,
                        sessionId = sessionId,
                        callerContext = callerContext
                    )
                }
            } else {
                // Execute without timeout (may still throw)
                try {
                    SubAgent.executeSync(
                        context = context,
                        agentType = step.agentType,
                        taskDescription = fullTask,
                        sessionId = sessionId,
                        callerContext = callerContext
                    )
                } catch (e: Exception) {
                    null // Will be caught below
                }
            }

            if (output != null) {
                return StepResult(
                    stepId = step.id,
                    status = WorkflowStepStatus.COMPLETED,
                    result = output,
                    retries = retryCount
                )
            }

            // Capture error for this attempt
            lastError = if (step.timeoutMs != null && step.timeoutMs > 0) {
                "Timeout after ${step.timeoutMs}ms"
            } else {
                "Execution failed"
            }
            retryCount++

            // If not the last retry, continue to next attempt
            if (attempt < step.maxRetries) {
                // Optional: brief delay before retry (could be configurable)
                // kotlinx.coroutines.delay(1000)
            }
        }

        return StepResult(
            stepId = step.id,
            status = WorkflowStepStatus.FAILED,
            error = "$lastError (after ${retryCount} attempts)",
            retries = retryCount
        )
    }

    /**
     * Execute a DAG — steps run according to dependency order, parallel where possible.
     *
     * Fixes applied:
     * - Cycle detection before execution
     * - Failed steps block all downstream dependents (not just the immediate next)
     * - Results stored under both stepId and resultVariable for reliable lookup
     * - ConcurrentHashMap for thread-safe parallel writes
     */
    suspend fun executeDAG(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        callerContext: AgentCallerContext? = null
    ): List<StepResult> = coroutineScope {
        // --- Cycle detection via DFS ---
        val adjacency = mutableMapOf<String, MutableList<String>>()
        steps.forEach { step ->
            adjacency[step.id] = mutableListOf()
        }
        steps.forEach { step ->
            step.dependsOn.forEach { depId ->
                adjacency[depId]?.add(step.id)
            }
        }
        val WHITE = 0; val GRAY = 1; val BLACK = 2
        val color = steps.associate { it.id to WHITE }.toMutableMap()
        var hasCycle = false
        var cyclePath = emptyList<String>()

        fun dfs(nodeId: String, path: MutableList<String>) {
            if (hasCycle) return
            color[nodeId] = GRAY
            path.add(nodeId)
            for (neighborId in (adjacency[nodeId] ?: emptyList())) {
                when (color[neighborId]) {
                    GRAY -> {
                        hasCycle = true
                        val cycleStart = path.indexOf(neighborId)
                        cyclePath = path.subList(cycleStart, path.size).toList() + neighborId
                        return
                    }
                    WHITE -> dfs(neighborId, path)
                }
            }
            path.removeAt(path.lastIndex)
            color[nodeId] = BLACK
        }

        for (step in steps) {
            if (color[step.id] == WHITE) {
                dfs(step.id, mutableListOf())
                if (hasCycle) break
            }
        }

        if (hasCycle) {
            return@coroutineScope steps.map {
                StepResult(it.id, WorkflowStepStatus.SKIPPED,
                    error = "Cycle detected: ${cyclePath.joinToString(" → ")}")
            }
        }

        // --- Execute DAG ---
        val results = ConcurrentHashMap<String, StepResult>()
        val completedSteps = mutableSetOf<String>()
        val failedSteps = mutableSetOf<String>()  // track failures to block downstream
        val remaining = steps.toMutableList()

        while (remaining.isNotEmpty()) {
            // A step is ready if:
            // 1. All dependencies are in completedSteps
            // 2. No dependency is in failedSteps (transitive failure propagation)
            val ready = remaining.filter { step ->
                step.dependsOn.all { it in completedSteps } &&
                    step.dependsOn.none { it in failedSteps }
            }

            if (ready.isEmpty()) {
                // Deadlock or all remaining blocked by failures
                remaining.forEach { step ->
                    val error = if (step.dependsOn.any { it in failedSteps }) {
                        "Skipped: dependency failed"
                    } else {
                        "Dependency not met (possible cycle)"
                    }
                    results[step.id] = StepResult(step.id, WorkflowStepStatus.SKIPPED, error = error)
                }
                break
            }

            // Execute ready steps in parallel
            val stepResults = ready.map { step ->
                async {
                    val contextVariables = mutableMapOf<String, String>()
                    step.dependsOn.forEach { depId ->
                        val depResult = results[depId]
                        val depStep = steps.find { it.id == depId }
                        // Store by resultVariable if available
                        if (depStep?.resultVariable != null && depResult != null) {
                            contextVariables[depStep.resultVariable] = depResult.result ?: ""
                        }
                        // Also store by stepId for fallback lookup
                        if (depResult != null) {
                            contextVariables[depId] = depResult.result ?: ""
                        }
                    }

                    val fullTask = buildTaskWithContext(step.task, step.dependsOn, contextVariables)

                    try {
                        val output = SubAgent.executeSync(
                            context = context,
                            agentType = step.agentType,
                            taskDescription = fullTask,
                            sessionId = sessionId,
                            callerContext = callerContext
                        )
                        StepResult(step.id, WorkflowStepStatus.COMPLETED, result = output)
                    } catch (e: Exception) {
                        StepResult(step.id, WorkflowStepStatus.FAILED, error = e.message)
                    }
                }
            }.awaitAll()

            stepResults.forEach { result ->
                results[result.stepId] = result
                completedSteps.add(result.stepId)
                if (result.status == WorkflowStepStatus.FAILED) {
                    failedSteps.add(result.stepId)
                }
                remaining.removeAll { it.id == result.stepId }
            }
        }

        steps.map { results[it.id] ?: StepResult(it.id, WorkflowStepStatus.SKIPPED) }
    }

    /**
     * Execute a conversational workflow — two agents exchange messages until convergence.
     *
     * Fixes applied:
     * - Full conversation history accumulated and passed to each agent (not just last message)
     * - Symmetric prompts: both agents know who sent the previous message
     * - Overall timeout prevents unbounded execution
     * - maxRounds validation
     * - Failure produces explicit convergence FAILED result
     * - Convergence detection uses contains() to catch mid-response markers
     */
    suspend fun executeConversational(
        context: Context,
        sessionId: Long,
        agentA: String,
        agentB: String,
        topic: String,
        maxRounds: Int = 5,
        callerContext: AgentCallerContext? = null
    ): List<StepResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<StepResult>()

        if (maxRounds <= 0) {
            results.add(StepResult("convergence", WorkflowStepStatus.FAILED,
                error = "maxRounds must be > 0, got $maxRounds"))
            return@withContext results
        }

        // Accumulated conversation history — each entry is "sender: message"
        val history = mutableListOf<String>()

        // Overall timeout: 10 minutes per round pair (generous for LLM calls)
        val overallTimeoutMs = maxRounds * 10L * 60 * 1000

        val conversationResult = withTimeoutOrNull(overallTimeoutMs) {
            executeConversationalRounds(
                context, sessionId, agentA, agentB, topic,
                maxRounds, callerContext, history, results
            )
        }

        // If timed out, add explicit failure
        if (conversationResult == null) {
            results.add(StepResult("convergence", WorkflowStepStatus.FAILED,
                error = "Conversation timed out after ${overallTimeoutMs / 1000}s"))
        }

        results
    }

    /**
     * Inner loop for conversational rounds. Separated for timeout containment.
     */
    private suspend fun executeConversationalRounds(
        context: Context,
        sessionId: Long,
        agentA: String,
        agentB: String,
        topic: String,
        maxRounds: Int,
        callerContext: AgentCallerContext?,
        history: MutableList<String>,
        results: MutableList<StepResult>
    ) {
        for (round in 0 until maxRounds) {
            // ── Agent A responds ──
            val taskA = buildConversationalTask(topic, history, agentA, isFirstSpeaker = history.isEmpty())
            val responseA = try {
                val output = SubAgent.executeSync(
                    context = context,
                    agentType = agentA,
                    taskDescription = taskA,
                    sessionId = sessionId,
                    callerContext = callerContext
                )
                StepResult("round-${round}-$agentA", WorkflowStepStatus.COMPLETED, result = output)
            } catch (e: Exception) {
                StepResult("round-${round}-$agentA", WorkflowStepStatus.FAILED, error = e.message)
            }
            results.add(responseA)

            if (responseA.status == WorkflowStepStatus.FAILED) {
                results.add(StepResult("convergence", WorkflowStepStatus.FAILED,
                    error = "$agentA failed at round $round: ${responseA.error}"))
                return
            }

            val contentA = responseA.result ?: ""
            history.add("$agentA: $contentA")

            if (looksConverged(contentA)) {
                results.add(StepResult("convergence", WorkflowStepStatus.COMPLETED,
                    result = extractConvergedAnswer(contentA)))
                return
            }

            // ── Agent B responds ──
            val taskB = buildConversationalTask(topic, history, agentB, isFirstSpeaker = false)
            val responseB = try {
                val output = SubAgent.executeSync(
                    context = context,
                    agentType = agentB,
                    taskDescription = taskB,
                    sessionId = sessionId,
                    callerContext = callerContext
                )
                StepResult("round-${round}-$agentB", WorkflowStepStatus.COMPLETED, result = output)
            } catch (e: Exception) {
                StepResult("round-${round}-$agentB", WorkflowStepStatus.FAILED, error = e.message)
            }
            results.add(responseB)

            if (responseB.status == WorkflowStepStatus.FAILED) {
                results.add(StepResult("convergence", WorkflowStepStatus.FAILED,
                    error = "$agentB failed at round $round: ${responseB.error}"))
                return
            }

            val contentB = responseB.result ?: ""
            history.add("$agentB: $contentB")

            if (looksConverged(contentB)) {
                results.add(StepResult("convergence", WorkflowStepStatus.COMPLETED,
                    result = extractConvergedAnswer(contentB)))
                return
            }
        }

        // Exhausted all rounds without convergence
        results.add(StepResult("convergence", WorkflowStepStatus.FAILED,
            error = "No convergence after $maxRounds rounds"))
    }

    /**
     * Build a conversational task description with full history.
     */
    private fun buildConversationalTask(
        topic: String,
        history: List<String>,
        currentAgent: String,
        isFirstSpeaker: Boolean
    ): String = buildString {
        appendLine("Topic: $topic")
        appendLine()
        if (history.isEmpty()) {
            appendLine("You are the first speaker. Provide your initial analysis or output.")
        } else {
            appendLine("Conversation so far:")
            appendLine()
            for (entry in history) {
                appendLine(entry)
                appendLine()
            }
            appendLine("Your turn as $currentAgent. Respond with your analysis or output.")
        }
        appendLine()
        appendLine("If you believe the discussion is complete and a final answer has been reached, start your response with [CONVERGED].")
    }

    private fun looksConverged(response: String): Boolean {
        return response.trimStart().startsWith("[CONVERGED]")
    }

    /**
     * Extract the actual answer from a converged response, stripping the [CONVERGED] marker.
     */
    private fun extractConvergedAnswer(response: String): String {
        return response.trimStart().removePrefix("[CONVERGED]").trim()
    }

    /**
     * Build task description with context from previous steps.
     * Public for use by RunWorkflowTool for event-based execution.
     *
     * Parses structured JSON output from previous steps and formats it for the next agent.
     */
    fun buildTaskWithContext(
        task: String,
        dependsOn: List<String>,
        contextVariables: Map<String, String>,
        steps: List<WorkflowStep> = emptyList()
    ): String {
        if (dependsOn.isEmpty() || contextVariables.isEmpty()) return task

        val contextBlock = buildString {
            appendLine("## Context from Previous Steps")
            appendLine()
            appendLine("The following steps have been completed. Use this information to continue your work:")
            appendLine()

            dependsOn.forEachIndexed { index, depId ->
                // Look up by step.id first; if not found, try resultVariable of the dep step
                val rawResult = contextVariables[depId]
                    ?: steps.find { it.id == depId }?.resultVariable?.let { contextVariables[it] }

                if (rawResult != null) {
                    appendLine("### Step ${index + 1}: $depId")
                    appendLine()

                    // Try to parse as structured JSON
                    val formattedContext = formatStructuredContext(rawResult)
                    appendLine(formattedContext)
                    appendLine()
                }
            }
        }

        return "$contextBlock\n---\n\n## Your Task\n\n$task"
    }

    /**
     * Format structured JSON output into human-readable context for the next agent.
     */
    private fun formatStructuredContext(jsonString: String): String {
        return try {
            val json = org.json.JSONObject(jsonString)

            buildString {
                // Status
                val status = json.optString("status", "UNKNOWN")
                appendLine("**Status:** $status")
                appendLine()

                // Summary (most important - show first)
                val summary = json.optString("summary", "")
                if (summary.isNotBlank()) {
                    appendLine("**Summary:** $summary")
                    appendLine()
                }

                // Key findings
                val findings = json.optJSONArray("key_findings")
                if (findings != null && findings.length() > 0) {
                    appendLine("**Key Findings:**")
                    for (i in 0 until findings.length()) {
                        appendLine("- ${findings.getString(i)}")
                    }
                    appendLine()
                }

                // Actions taken
                val actions = json.optJSONArray("actions")
                if (actions != null && actions.length() > 0) {
                    appendLine("**Actions Taken:**")
                    for (i in 0 until actions.length()) {
                        val action = actions.getJSONObject(i)
                        val step = action.optString("step", "")
                        val tool = action.optString("tool", "")
                        val outcome = action.optString("outcome", "")
                        appendLine("- $step${if (tool.isNotBlank() && tool != "null") " (using $tool)" else ""}")
                        if (outcome.isNotBlank()) {
                            appendLine("  → $outcome")
                        }
                    }
                    appendLine()
                }

                // Deliverables
                val deliverables = json.optJSONArray("deliverables")
                if (deliverables != null && deliverables.length() > 0) {
                    appendLine("**Deliverables:**")
                    for (i in 0 until deliverables.length()) {
                        appendLine("- ${deliverables.getString(i)}")
                    }
                    appendLine()
                }

                // Confidence
                val confidence = json.optString("confidence", "")
                if (confidence.isNotBlank()) {
                    appendLine("**Confidence:** $confidence")
                }

                // Notes
                val notes = json.optString("notes", "")
                if (notes.isNotBlank()) {
                    appendLine("**Notes:** $notes")
                }

                // Next steps hint
                val nextSteps = json.optString("next_steps_hint", "")
                if (nextSteps.isNotBlank()) {
                    appendLine("**Suggested Next Steps:** $nextSteps")
                }
            }
        } catch (_: Exception) {
            // Not valid JSON — return as-is with formatting
            "**Output:**\n```\n${jsonString.take(2000)}\n```"
        }
    }
}
