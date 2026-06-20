# Interactive Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Interactive Pipeline mode with IDLE/REVISION states, inter-agent messaging, timeout handling, and workflow templates.

**Architecture:** Extend existing WorkflowEngine with a new `executeInteractivePipeline()` method. SubAgent gains suspend/resume capabilities via `IdleAgentHandle`. MessageBus routes messages between agents. UI displays IDLE/REVISION states with timeout warnings.

**Tech Stack:** Kotlin, Coroutines (suspendCoroutine), Compose UI, SharedFlow events

---

## File Structure

| File | Purpose |
|------|---------|
| `agent/WorkflowEngine.kt` | Add IDLE/REVISION/PENDING_REVIEW status, InteractivePipelineState, executeInteractivePipeline() |
| `agent/SubAgent.kt` | Add IdleAgentHandle, createIdle(), wakeUp(), recall() methods |
| `agent/WorkflowEventBus.kt` | Add new event types: StepWokeUp, StepRecalled, IdleTimeoutWarning, etc. |
| `agent/WorkflowTemplates.kt` | New file: template definitions and instantiation logic |
| `agent/AgentPrompts.kt` | Add workflow mode guidance to base prompt |
| `tool/builtin/SubAgentTools.kt` | Extend run_workflow with template and interactive_pipeline mode |
| `ui/screens/WorkflowProgressCard.kt` | Add IDLE/REVISION/PENDING_REVIEW state display, timeout warnings |
| `ui/viewmodel/ChatViewModel.kt` | Handle new WorkflowEvent types for UI state updates |

---

## Phase 1: Data Structures & Events

### Task 1: Extend WorkflowStepStatus Enum

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt` (lines 26-28)

- [ ] **Step 1: Add new status values to WorkflowStepStatus enum**

```kotlin
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
```

- [ ] **Step 2: Extend WorkflowStep data class**

```kotlin
data class WorkflowStep(
    val id: String,
    val agentType: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    val resultVariable: String? = null,
    val timeoutMs: Long? = null,         // RUNNING 超时（默认 10 分钟）
    val maxIdleMs: Long? = null,         // IDLE 超时（默认 30 分钟）
    val maxRetries: Int = 0,
    val wakeUpOnMessage: Boolean = true  // 是否监听消息并自动唤醒
)
```

- [ ] **Step 3: Extend StepResult data class**

```kotlin
data class StepResult(
    val stepId: String,
    val status: WorkflowStepStatus,
    val result: String? = null,
    val error: String? = null,
    val retries: Int = 0,
    val revisionCount: Int = 0,          // 被召回修改次数
    val idleTimeMs: Long = 0,            // IDLE 状态累计时长
    val lastMessageFrom: String? = null  // 最后收到的消息来源
)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add IDLE, REVISION, PENDING_REVIEW statuses and extend data classes"
```

---

### Task 2: Add New WorkflowEvent Types

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEventBus.kt`

- [ ] **Step 1: Add new event types to WorkflowEvent sealed class**

Add after `WorkflowFailed`:

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEventBus.kt
git commit -m "feat(workflow): add new WorkflowEvent types for interactive pipeline"
```

---

### Task 3: Update WorkflowUiState for UI

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEventBus.kt`

- [ ] **Step 1: Extend WorkflowStepUiState**

```kotlin
data class WorkflowStepUiState(
    val stepId: String,
    val agentType: String,
    val task: String,
    val status: WorkflowStepStatus,
    val result: String? = null,
    val error: String? = null,
    val dependsOn: List<String> = emptyList(),
    // New fields
    val idleSince: Long? = null,
    val runningSince: Long? = null,
    val revisionCount: Int = 0,
    val lastMessageFrom: String? = null,
    val lastMessagePreview: String? = null
)
```

- [ ] **Step 2: Add IdleWarningInfo and MessageErrorInfo data classes**

```kotlin
data class IdleWarningInfo(
    val stepId: String,
    val idleDurationMs: Long,
    val message: String,
    val timestamp: Long
)

data class MessageErrorInfo(
    val from: String,
    val to: String,
    val error: String,
    val availableTargets: List<String>,
    val timestamp: Long
)
```

- [ ] **Step 3: Extend WorkflowUiState**

```kotlin
data class WorkflowUiState(
    val workflowId: String,
    val sessionId: Long,
    val mode: WorkflowMode,
    val status: WorkflowStatus,
    val steps: List<WorkflowStepUiState>,
    val currentStepIndex: Int = 0,
    val topic: String? = null,
    val agentA: String? = null,
    val agentB: String? = null,
    val currentRound: Int = 0,
    val maxRounds: Int = 5,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null,
    // New fields
    val idleWarnings: List<IdleWarningInfo> = emptyList(),
    val messageErrors: List<MessageErrorInfo> = emptyList()
)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEventBus.kt
git commit -m "feat(workflow): extend WorkflowUiState with idle warnings and message errors"
```

---

## Phase 2: SubAgent Suspend/Resume

### Task 4: Add IdleAgentHandle and ExecutionContext

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/SubAgent.kt`

- [ ] **Step 1: Add IdleAgentHandle data class and AgentStateInfo**

Add after `SubAgentTask` data class:

```kotlin
/**
 * Handle for a suspended SubAgent that can be woken up.
 */
data class IdleAgentHandle(
    val agentId: String,
    val agentType: String,
    val stepId: String,
    val sessionId: Long,
    val context: Context,
    internal val resumeChannel: kotlinx.coroutines.channels.Channel<WakeUpSignal>
)

/**
 * Signal to wake up a suspended SubAgent.
 */
internal data class WakeUpSignal(
    val task: String,
    val contextVariables: Map<String, String>,
    val conversationHistory: List<org.json.JSONObject>?,
    val isRevision: Boolean,
    val revisionPrompt: String?
)

/**
 * Internal state for tracking suspended agents.
 */
internal data class AgentStateInfo(
    val handle: IdleAgentHandle,
    val status: WorkflowStepStatus,
    val idleSince: Long?,
    val runningSince: Long?,
    val conversationHistory: MutableList<org.json.JSONObject>,
    val idleTimeoutWarningSent: Boolean = false
)
```

- [ ] **Step 2: Add suspendedAgents map to SubAgent object**

Add after `activeJobs`:

```kotlin
// Map of agentId -> AgentStateInfo for suspended agents
private val suspendedAgents = ConcurrentHashMap<String, AgentStateInfo>()
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/SubAgent.kt
git commit -m "feat(subagent): add IdleAgentHandle and suspend/resume data structures"
```

---

### Task 5: Implement createIdle() Method

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/SubAgent.kt`

- [ ] **Step 1: Add createIdle() method**

```kotlin
/**
 * Create a SubAgent in IDLE state, suspended and waiting for wake-up.
 * Used by Interactive Pipeline to pre-create agents.
 */
suspend fun createIdle(
    context: Context,
    agentType: String,
    sessionId: Long,
    stepId: String
): IdleAgentHandle = withContext(Dispatchers.Default) {
    val agentId = "idle-${stepId}-${UUID.randomUUID().toString().take(8)}"
    val resumeChannel = kotlinx.coroutines.channels.Channel<WakeUpSignal>(capacity = 1)
    
    val handle = IdleAgentHandle(
        agentId = agentId,
        agentType = agentType,
        stepId = stepId,
        sessionId = sessionId,
        context = context,
        resumeChannel = resumeChannel
    )
    
    // Initialize state
    suspendedAgents[agentId] = AgentStateInfo(
        handle = handle,
        status = WorkflowStepStatus.IDLE,
        idleSince = System.currentTimeMillis(),
        runningSince = null,
        conversationHistory = mutableListOf()
    )
    
    // Emit event
    SubAgentEventBus.emit(SubAgentEvent.TaskStarted(
        taskId = agentId,
        sessionId = sessionId,
        taskType = agentType,
        description = "[IDLE] Waiting for wake-up signal"
    ))
    
    Log.d("SubAgent", "[createIdle] Created idle agent: $agentId for step: $stepId")
    
    handle
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/SubAgent.kt
git commit -m "feat(subagent): implement createIdle() for suspended agent creation"
```

---

### Task 6: Implement wakeUp() Method

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/SubAgent.kt`

- [ ] **Step 1: Add wakeUp() method**

```kotlin
/**
 * Wake up a suspended SubAgent and execute a task.
 */
suspend fun wakeUp(
    handle: IdleAgentHandle,
    task: String,
    contextVariables: Map<String, String> = emptyMap(),
    conversationHistory: List<org.json.JSONObject>? = null
): String {
    val stateInfo = suspendedAgents[handle.agentId]
        ?: throw IllegalStateException("Agent ${handle.agentId} not found in suspended state")
    
    if (stateInfo.status != WorkflowStepStatus.IDLE) {
        throw IllegalStateException("Agent ${handle.agentId} is not in IDLE state (current: ${stateInfo.status})")
    }
    
    Log.d("SubAgent", "[wakeUp] Waking up agent: ${handle.agentId}")
    
    // Update state
    suspendedAgents[handle.agentId] = stateInfo.copy(
        status = WorkflowStepStatus.RUNNING,
        runningSince = System.currentTimeMillis()
    )
    
    // Execute the task
    return try {
        globalSemaphore.acquire()
        try {
            currentTaskContext.set(task)
            val result = executeTask(
                context = handle.context,
                agentType = handle.agentType,
                taskDescription = task,
                sessionId = handle.sessionId,
                depth = 1,
                taskId = handle.agentId
            )
            currentTaskContext.remove()
            
            // Update conversation history
            suspendedAgents[handle.agentId]?.let { state ->
                state.conversationHistory.add(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", task)
                })
                state.conversationHistory.add(org.json.JSONObject().apply {
                    put("role", "assistant")
                    put("content", result)
                })
            }
            
            Log.d("SubAgent", "[wakeUp] Agent ${handle.agentId} completed task")
            result
        } finally {
            globalSemaphore.release()
        }
    } catch (e: Exception) {
        Log.e("SubAgent", "[wakeUp] Agent ${handle.agentId} failed: ${e.message}")
        throw e
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/SubAgent.kt
git commit -m "feat(subagent): implement wakeUp() for resuming suspended agents"
```

---

### Task 7: Implement recall() Method

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/SubAgent.kt`

- [ ] **Step 1: Add recall() method**

```kotlin
/**
 * Recall a completed SubAgent into REVISION state.
 * The agent will modify its previous work based on feedback.
 */
suspend fun recall(
    handle: IdleAgentHandle,
    revisionPrompt: String,
    fromAgent: String
): String {
    val stateInfo = suspendedAgents[handle.agentId]
        ?: throw IllegalStateException("Agent ${handle.agentId} not found in suspended state")
    
    Log.d("SubAgent", "[recall] Recalling agent: ${handle.agentId} from: $fromAgent")
    
    // Build revision task with context
    val revisionTask = buildString {
        appendLine("[REVISION REQUEST from $fromAgent]")
        appendLine()
        appendLine(revisionPrompt)
        appendLine()
        appendLine("---")
        appendLine("Please modify your previous work to address the issues above.")
    }
    
    // Update state to REVISION
    suspendedAgents[handle.agentId] = stateInfo.copy(
        status = WorkflowStepStatus.REVISION,
        runningSince = System.currentTimeMillis()
    )
    
    // Execute revision using wakeUp
    return try {
        wakeUp(
            handle = handle,
            task = revisionTask,
            contextVariables = emptyMap(),
            conversationHistory = stateInfo.conversationHistory
        )
    } catch (e: Exception) {
        // Revert to FAILED on error
        suspendedAgents[handle.agentId] = stateInfo.copy(
            status = WorkflowStepStatus.FAILED
        )
        throw e
    }
}
```

- [ ] **Step 2: Add setAgentIdle() method for returning to IDLE**

```kotlin
/**
 * Set an agent back to IDLE state after completion.
 */
fun setAgentIdle(agentId: String) {
    suspendedAgents[agentId]?.let { state ->
        suspendedAgents[agentId] = state.copy(
            status = WorkflowStepStatus.IDLE,
            idleSince = System.currentTimeMillis(),
            runningSince = null,
            idleTimeoutWarningSent = false
        )
        Log.d("SubAgent", "[setAgentIdle] Agent $agentId returned to IDLE")
    }
}

/**
 * Mark an agent as COMPLETED and remove from suspended list.
 */
fun completeAgent(agentId: String) {
    suspendedAgents.remove(agentId)
    Log.d("SubAgent", "[completeAgent] Agent $agentId marked COMPLETED and removed")
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/SubAgent.kt
git commit -m "feat(subagent): implement recall() and setAgentIdle() for revision workflow"
```

---

## Phase 3: WorkflowEngine Interactive Pipeline

### Task 8: Add InteractivePipelineState

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt`

- [ ] **Step 1: Add internal state data classes**

Add after `StepResult`:

```kotlin
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
    val conversationHistory: MutableList<org.json.JSONObject>,
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add InteractivePipelineState and internal data classes"
```

---

### Task 9: Implement executeInteractivePipeline() - Part 1: Initialization

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt`

- [ ] **Step 1: Add executeInteractivePipeline() method skeleton**

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add executeInteractivePipeline() initialization phase"
```

---

### Task 10: Implement wakeUpStep() and setStepIdle()

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt`

- [ ] **Step 1: Add wakeUpStep() helper method**

```kotlin
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
private fun setStepIdle(state: InteractivePipelineState, stepId: String, result: String) {
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add wakeUpStep() and setStepIdle() helper methods"
```

---

### Task 11: Implement Event Loop and Message Routing

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt`

- [ ] **Step 1: Add startInteractiveEventLoop() method**

```kotlin
/**
 * Main event loop for interactive pipeline.
 * Monitors messages and timeouts until workflow completes.
 */
private suspend fun startInteractiveEventLoop(
    context: Context,
    state: InteractivePipelineState
) {
    while (state.status == WorkflowStatus.RUNNING) {
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
}

/**
 * Process pending messages from MessageBus.
 */
private fun processPendingMessages(state: InteractivePipelineState) {
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
private fun handleIncomingMessage(
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add event loop and message routing logic"
```

---

### Task 12: Implement Timeout Handling

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt`

- [ ] **Step 1: Add checkTimeouts() method**

```kotlin
/**
 * Check for IDLE and RUNNING timeouts.
 */
private fun checkTimeouts(state: InteractivePipelineState) {
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
private fun handleRunningTimeout(
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add IDLE and RUNNING timeout handling"
```

---

### Task 13: Implement Revision and Completion Logic

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/WorkflowEngine.kt`

- [ ] **Step 1: Add recallStep() and helper methods**

```kotlin
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
 * Handle step failure.
 */
private fun handleStepFailure(
    state: InteractivePipelineState,
    stepId: String,
    error: String
) {
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
 * Handle MainAgent message (timeout responses).
 */
private fun handleMainAgentMessage(state: InteractivePipelineState, message: AgentMessage) {
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
 * Check if all steps have failed.
 */
private fun checkAllStepsFailed(state: InteractivePipelineState): Boolean {
    return state.agentStates.values.all { it.status == WorkflowStepStatus.FAILED }
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
 * Collect final results.
 */
private fun collectInteractiveResults(state: InteractivePipelineState): List<StepResult> {
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowEngine.kt
git commit -m "feat(workflow): add revision, completion, and cleanup logic"
```

---

## Phase 4: UI Updates

### Task 14: Update WorkflowProgressCard for New States

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/screens/WorkflowProgressCard.kt`

- [ ] **Step 1: Add IDLE and REVISION status display in StepItemWithSummary**

Find the `when (step.status)` block and add cases:

```kotlin
when (step.status) {
    WorkflowStepStatus.RUNNING -> {
        SpinningIndicator(color = statusColor, size = 14.dp)
    }
    WorkflowStepStatus.COMPLETED -> {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Completed",
            tint = statusColor,
            modifier = Modifier.size(14.dp)
        )
    }
    WorkflowStepStatus.IDLE -> {
        Icon(
            imageVector = Icons.Default.PauseCircle,
            contentDescription = "IDLE",
            tint = statusColor,
            modifier = Modifier.size(14.dp)
        )
    }
    WorkflowStepStatus.REVISION -> {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "Revision",
            tint = statusColor,
            modifier = Modifier.size(14.dp)
        )
    }
    WorkflowStepStatus.PENDING_REVIEW -> {
        Icon(
            imageVector = Icons.Default.HourglassTop,
            contentDescription = "Pending Review",
            tint = statusColor,
            modifier = Modifier.size(14.dp)
        )
    }
    else -> {
        val statusIcon: ImageVector = when (step.status) {
            WorkflowStepStatus.PENDING -> Icons.Default.Schedule
            WorkflowStepStatus.FAILED -> Icons.Default.Cancel
            WorkflowStepStatus.SKIPPED -> Icons.Default.SkipNext
            else -> Icons.Default.Circle
        }
        Icon(
            imageVector = statusIcon,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(14.dp)
        )
    }
}
```

- [ ] **Step 2: Add IDLE duration display**

After the status icon, add:

```kotlin
// Show IDLE duration
if (step.status == WorkflowStepStatus.IDLE && step.idleSince != null) {
    val idleMinutes = remember(step.idleSince) {
        (System.currentTimeMillis() - step.idleSince) / 60000
    }
    Text(
        text = "等待 ${idleMinutes}分钟",
        fontSize = (9 * chatFs * fs).sp,
        color = contentColor.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp)
    )
}

// Show revision count
if (step.status == WorkflowStepStatus.REVISION && step.revisionCount > 0) {
    Text(
        text = "(第${step.revisionCount}次修改)",
        fontSize = (9 * chatFs * fs).sp,
        color = contentColor.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp)
    )
}
```

- [ ] **Step 3: Update statusColor calculation**

```kotlin
val statusColor = when (step.status) {
    WorkflowStepStatus.PENDING -> contentColor.copy(alpha = 0.5f)
    WorkflowStepStatus.IDLE -> contentColor.copy(alpha = 0.6f)  // Grey
    WorkflowStepStatus.RUNNING -> MaterialTheme.colorScheme.primary
    WorkflowStepStatus.PENDING_REVIEW -> Color(0xFFFF9800)  // Orange
    WorkflowStepStatus.REVISION -> Color(0xFFFF9800)  // Orange
    WorkflowStepStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
    WorkflowStepStatus.FAILED -> MaterialTheme.colorScheme.error
    WorkflowStepStatus.SKIPPED -> contentColor.copy(alpha = 0.3f)
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/ui/screens/WorkflowProgressCard.kt
git commit -m "feat(ui): add IDLE and REVISION status display in WorkflowProgressCard"
```

---

### Task 15: Add Timeout Warning and Message Error Display

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/screens/WorkflowProgressCard.kt`

- [ ] **Step 1: Add warning container color to theme or use fallback**

At the top of `WorkflowProgressCard`, add:

```kotlin
// Fallback warning color if not in theme
val warningContainerColor = Color(0xFFFFF3E0)
val onWarningContainerColor = Color(0xFFE65100)
```

- [ ] **Step 2: Add idle warnings display after steps**

Find the location after the steps section and add:

```kotlin
// IDLE timeout warnings
if (workflow.idleWarnings.isNotEmpty()) {
    Spacer(modifier = Modifier.height(8.dp))
    workflow.idleWarnings.forEach { warning ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            color = warningContainerColor
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = onWarningContainerColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = warning.message,
                    fontSize = (11 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = onWarningContainerColor
                )
            }
        }
    }
}
```

- [ ] **Step 3: Add message errors display**

```kotlin
// Message routing errors
if (workflow.messageErrors.isNotEmpty()) {
    Spacer(modifier = Modifier.height(8.dp))
    workflow.messageErrors.forEach { error ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "消息发送失败: ${error.from} → ${error.to}",
                    fontSize = (10 * chatFs * fs).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = error.error,
                    fontSize = (9 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.7f)
                )
                Text(
                    text = "可用目标: ${error.availableTargets.joinToString(", ")}",
                    fontSize = (9 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/ui/screens/WorkflowProgressCard.kt
git commit -m "feat(ui): add timeout warning and message error display"
```

---

### Task 16: Update ChatViewModel for New Events

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: Add handlers for new WorkflowEvent types in handleWorkflowEvent()**

Add after the existing `when` branches:

```kotlin
is WorkflowEvent.StepWokeUp -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val updatedSteps = workflow.steps.map { step ->
            if (step.stepId == event.stepId) {
                step.copy(
                    status = WorkflowStepStatus.RUNNING,
                    lastMessageFrom = event.fromAgent,
                    lastMessagePreview = event.messagePreview
                )
            } else step
        }
        activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
    }
}

is WorkflowEvent.StepRecalled -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val updatedSteps = workflow.steps.map { step ->
            if (step.stepId == event.stepId) {
                step.copy(
                    status = WorkflowStepStatus.REVISION,
                    revisionCount = (step.revisionCount ?: 0) + 1,
                    lastMessageFrom = event.fromAgent
                )
            } else step
        }
        activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
    }
}

is WorkflowEvent.StepEnteredIdle -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val updatedSteps = workflow.steps.map { step ->
            if (step.stepId == event.stepId) {
                step.copy(
                    status = WorkflowStepStatus.IDLE,
                    idleSince = System.currentTimeMillis()
                )
            } else step
        }
        activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
    }
}

is WorkflowEvent.IdleTimeoutWarning -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val newWarning = IdleWarningInfo(
            stepId = event.stepId,
            idleDurationMs = event.idleDurationMs,
            message = event.message,
            timestamp = System.currentTimeMillis()
        )
        activeWorkflows[event.workflowId] = workflow.copy(
            idleWarnings = workflow.idleWarnings + newWarning
        )
    }
}

is WorkflowEvent.StepTimeout -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val updatedSteps = workflow.steps.map { step ->
            if (step.stepId == event.stepId) {
                step.copy(
                    status = WorkflowStepStatus.FAILED,
                    error = event.error
                )
            } else step
        }
        activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
    }
}

is WorkflowEvent.MessageRoutingError -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val newError = MessageErrorInfo(
            from = event.from,
            to = event.to,
            error = event.error,
            availableTargets = event.availableTargets,
            timestamp = System.currentTimeMillis()
        )
        activeWorkflows[event.workflowId] = workflow.copy(
            messageErrors = workflow.messageErrors + newError
        )
    }
}

is WorkflowEvent.StepRevisionCompleted -> {
    activeWorkflows[event.workflowId]?.let { workflow ->
        val updatedSteps = workflow.steps.map { step ->
            if (step.stepId == event.stepId) {
                step.copy(
                    revisionCount = event.revisionCount,
                    result = event.result
                )
            } else step
        }
        activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(vm): add handlers for new WorkflowEvent types"
```

---

## Phase 5: Templates and Tools

### Task 17: Create WorkflowTemplates Object

**Files:**
- Create: `app/src/main/java/com/omnichat/agent/WorkflowTemplates.kt`

- [ ] **Step 1: Create WorkflowTemplates.kt**

```kotlin
package com.omnichat.agent

/**
 * Predefined workflow templates for common use cases.
 */
object WorkflowTemplates {
    
    data class Template(
        val id: String,
        val name: String,
        val description: String,
        val steps: List<TemplateStep>,
        val defaultTimeouts: TemplateTimeouts = TemplateTimeouts()
    )
    
    data class TemplateStep(
        val id: String,
        val agentType: String,
        val taskTemplate: String,  // Supports {{param}} placeholders
        val dependsOn: List<String> = emptyList()
    )
    
    data class TemplateTimeouts(
        val runningTimeoutMs: Long = 10 * 60 * 1000L,  // 10 minutes
        val idleTimeoutMs: Long = 30 * 60 * 1000L      // 30 minutes
    )
    
    // Built-in templates
    val RESEARCH_AND_REPORT = Template(
        id = "research_and_report",
        name = "研究并报告",
        description = "检索信息并生成报告",
        steps = listOf(
            TemplateStep("research", "researcher", "{{task}}"),
            TemplateStep("report", "general", "根据研究结果生成报告")
        )
    )
    
    val CODE_AND_REVIEW = Template(
        id = "code_and_review",
        name = "编码并审查",
        description = "编写代码并进行审查",
        steps = listOf(
            TemplateStep("code", "coder", "{{task}}"),
            TemplateStep("review", "reviewer", "审查代码并提供反馈")
        )
    )
    
    val FULL_DEV_CYCLE = Template(
        id = "full_dev_cycle",
        name = "完整开发周期",
        description = "研究→编码→审查的完整流程",
        steps = listOf(
            TemplateStep("research", "researcher", "研究需求相关信息：{{task}}"),
            TemplateStep("code", "coder", "根据研究结果实现功能"),
            TemplateStep("review", "reviewer", "审查代码质量，如发现问题请发送消息给 code 步骤")
        )
    )
    
    val ALL_TEMPLATES = listOf(RESEARCH_AND_REPORT, CODE_AND_REVIEW, FULL_DEV_CYCLE)
    
    /**
     * Instantiate a template with parameters.
     */
    fun instantiateTemplate(
        templateId: String,
        params: Map<String, String>
    ): List<WorkflowStep>? {
        val template = ALL_TEMPLATES.find { it.id == templateId } ?: return null
        
        return template.steps.map { ts ->
            val task = replaceParams(ts.taskTemplate, params)
            WorkflowStep(
                id = ts.id,
                agentType = ts.agentType,
                task = task,
                dependsOn = ts.dependsOn,
                timeoutMs = template.defaultTimeouts.runningTimeoutMs,
                maxIdleMs = template.defaultTimeouts.idleTimeoutMs
            )
        }
    }
    
    private fun replaceParams(template: String, params: Map<String, String>): String {
        var result = template
        params.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/WorkflowTemplates.kt
git commit -m "feat(workflow): add WorkflowTemplates with built-in templates"
```

---

### Task 18: Update run_workflow Tool

**Files:**
- Modify: `app/src/main/java/com/omnichat/tool/builtin/SubAgentTools.kt`

- [ ] **Step 1: Update RunWorkflowTool inputSchema**

Find `object RunWorkflowTool` and update `inputSchema`:

```kotlin
override val inputSchema = schema {
    prop("mode", "string", "Execution mode.") {
        enum("pipeline", "dag", "conversational", "interactive_pipeline")
    }
    prop("steps", "array", "Workflow steps (for pipeline/dag/interactive_pipeline modes).") {
        items {
            properties {
                prop("id", "string", "Step identifier.")
                prop("agentType", "string", "Agent type for this step.")
                prop("task", "string", "Task description.")
                prop("dependsOn", "array", "IDs of steps this depends on.") { items { } }
                prop("timeoutMs", "integer", "Running timeout in milliseconds (default 600000 = 10min).")
                prop("maxIdleMs", "integer", "IDLE timeout in milliseconds (default 1800000 = 30min).")
            }
        }
    }
    prop("template", "string", "Use predefined template (alternative to steps).") {
        enum("research_and_report", "code_and_review", "full_dev_cycle")
    }
    prop("task", "string", "Task description (required when using template).")
    prop("agentA", "string", "First agent type (conversational only).")
    prop("agentB", "string", "Second agent type (conversational only).")
    prop("topic", "string", "Discussion topic (conversational only).")
    prop("maxRounds", "integer", "Maximum rounds (conversational only, default 5).")
}
```

- [ ] **Step 2: Update validateInput()**

```kotlin
override fun validateInput(arguments: JSONObject): String? {
    val mode = arguments.optString("mode").trim()
    if (mode.isEmpty()) return "mode is required"
    
    when (mode) {
        "pipeline", "dag", "interactive_pipeline" -> {
            val template = arguments.optString("template").trim()
            val steps = arguments.optJSONArray("steps")
            
            if (template.isEmpty() && (steps == null || steps.length() == 0)) {
                return "Either template or steps is required for $mode mode"
            }
            if (template.isNotEmpty() && arguments.optString("task").isEmpty()) {
                return "task is required when using template"
            }
        }
        "conversational" -> {
            if (arguments.optString("agentA").isEmpty()) return "agentA is required for conversational mode"
            if (arguments.optString("agentB").isEmpty()) return "agentB is required for conversational mode"
            if (arguments.optString("topic").isEmpty()) return "topic is required for conversational mode"
        }
        else -> return "Invalid mode: $mode"
    }
    
    return null
}
```

- [ ] **Step 3: Update doExecute() to handle templates and interactive_pipeline**

```kotlin
override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
    if (sessionId == null) {
        return errorResponse("run_workflow requires a session context")
    }
    
    val mode = arguments.optString("mode")
    
    // Parse steps from template or direct input
    val steps: List<WorkflowStep>? = when {
        arguments.optString("template").isNotEmpty() -> {
            val templateId = arguments.optString("template")
            val task = arguments.optString("task")
            WorkflowTemplates.instantiateTemplate(templateId, mapOf("task" to task))
        }
        arguments.optJSONArray("steps") != null -> {
            parseStepsArray(arguments.optJSONArray("steps")!!)
        }
        else -> null
    }
    
    if (steps == null && mode in listOf("pipeline", "dag", "interactive_pipeline")) {
        return errorResponse("Failed to parse steps")
    }
    
    return when (mode) {
        "pipeline" -> executePipeline(context, steps!!, sessionId)
        "dag" -> executeDag(context, steps!!, sessionId)
        "interactive_pipeline" -> executeInteractivePipeline(context, steps!!, sessionId)
        "conversational" -> executeConversational(context, arguments, sessionId)
        else -> errorResponse("Invalid mode: $mode")
    }
}

private fun parseStepsArray(stepsArray: JSONArray): List<WorkflowStep> {
    return (0 until stepsArray.length()).map { i ->
        val stepObj = stepsArray.optJSONObject(i)!!
        val dependsOnArray = stepObj.optJSONArray("dependsOn")
        val dependsOn = if (dependsOnArray != null) {
            (0 until dependsOnArray.length()).map { dependsOnArray.optString(it) }
        } else emptyList()
        
        WorkflowStep(
            id = stepObj.optString("id"),
            agentType = stepObj.optString("agentType"),
            task = stepObj.optString("task"),
            dependsOn = dependsOn,
            timeoutMs = stepObj.optLong("timeoutMs").let { if (it > 0) it else null },
            maxIdleMs = stepObj.optLong("maxIdleMs").let { if (it > 0) it else null }
        )
    }
}
```

- [ ] **Step 4: Add executeInteractivePipeline() method in RunWorkflowTool**

```kotlin
private suspend fun executeInteractivePipeline(
    context: Context,
    steps: List<WorkflowStep>,
    sessionId: Long
): JSONObject {
    val workflowId = UUID.randomUUID().toString()
    
    WorkflowEventBus.emit(WorkflowEvent.WorkflowStarted(
        workflowId = workflowId,
        sessionId = sessionId,
        mode = WorkflowMode.PIPELINE,
        totalSteps = steps.size
    ))
    
    val results = WorkflowEngine.executeInteractivePipeline(
        context = context,
        sessionId = sessionId,
        steps = steps,
        workflowId = workflowId
    )
    
    val text = buildString {
        appendLine("Interactive pipeline execution completed.")
        appendLine()
        
        results.forEachIndexed { i, result ->
            appendLine("${i + 1}. [${result.stepId}] ${result.status}")
            if (result.revisionCount > 0) {
                appendLine("   Revisions: ${result.revisionCount}")
            }
            if (result.result != null) {
                appendLine("   Result: ${result.result.take(200)}${if (result.result.length > 200) "..." else ""}")
            }
            if (result.error != null) {
                appendLine("   Error: ${result.error}")
            }
        }
    }
    
    return successResponse(text)
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnichat/tool/builtin/SubAgentTools.kt
git commit -m "feat(tool): add template and interactive_pipeline support to run_workflow"
```

---

### Task 19: Update AgentPrompts for Workflow Communication

**Files:**
- Modify: `app/src/main/java/com/omnichat/agent/AgentPrompts.kt`

- [ ] **Step 1: Add workflow communication section to basePrompt**

Find the `## Constraints` section and add before it:

```kotlin
|
|## Workflow Communication
|If you are part of a workflow with multiple agents:
|- When complete, send a message to the next agent: `send_agent_message(to="step:next_step_id", content="your message")`
|- If you need to recall a previous agent for revision: `send_agent_message(to="step:prev_step_id", content="Issues found: ...")`
|- Available step IDs will be provided in your task context.
|- After sending a message, you will enter IDLE state waiting for further instructions.
|
```

- [ ] **Step 2: Add reviewer-specific guidance**

Find the `"reviewer"` entry and update its `roleGuidelines`:

```kotlin
"reviewer" to basePrompt(
    role = "code review",
    roleGuidelines = """
        |Perform two-stage review:
        |
        |Stage 1 — Spec Compliance:
        |- Does the implementation match the requirements?
        |- Are all edge cases handled?
        |- Is the API contract respected?
        |
        |Stage 2 — Code Quality:
        |- Bugs and logic errors (Critical)
        |- Security vulnerabilities (Critical)
        |- Performance issues (Important)
        |- Code readability and maintainability (Minor)
        |
        |## Workflow Communication
        |- If issues are found: send message to the coder step with specific feedback for revision.
        |- If no issues: send approval message.
        |- Use: `send_agent_message(to="step:code", content="Issues: ...")` to request revisions.
        |- You will enter IDLE after review. If recalled, re-review the modified code.
        |
        |Provide specific file:line references and concrete suggestions.
        |Categorize issues by actual severity — not everything is Critical.
    """.trimMargin()
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/AgentPrompts.kt
git commit -m "feat(prompts): add workflow communication guidance to agent prompts"
```

---

### Task 20: Build and Test

**Files:**
- None (build verification)

- [ ] **Step 1: Build debug APK**

```bash
./gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL with no compilation errors

- [ ] **Step 2: Install debug APK**

```bash
./gradlew.bat installDebug
```

Expected: APK installed successfully

- [ ] **Step 3: Commit final build**

```bash
git add -A
git commit -m "feat(workflow): complete Interactive Pipeline implementation"
```

---

## Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| 1 | Task 1-3 | Data structures & events |
| 2 | Task 4-7 | SubAgent suspend/resume |
| 3 | Task 8-13 | WorkflowEngine interactive pipeline |
| 4 | Task 14-16 | UI updates |
| 5 | Task 17-20 | Templates, tools, build |

Total: 20 tasks