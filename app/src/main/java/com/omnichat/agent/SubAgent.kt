package com.omnichat.agent

import android.content.Context
import android.util.Log
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.mcp.McpRuntimeManager
import com.omnichat.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element for tracking SubAgent execution context.
 * This is used by hooks to detect when they are being called from a SubAgent.
 */
data class SubAgentContext(
    val taskDescription: String,
    val agentType: String,
    val taskId: String
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = Key

    companion object Key : CoroutineContext.Key<SubAgentContext>
}

/**
 * Caller context for tracking SubAgent delegation depth.
 */
data class AgentCallerContext(
    val agentId: String,
    val agentType: String,
    val depth: Int = 0,
    val parentAgentId: String? = null
)

/**
 * Task execution status.
 */
enum class SubAgentTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}

/**
 * Execution context for SubAgent - determines event behavior.
 */
enum class SubAgentExecutionContext {
    /** Standalone task - emits full events to SubAgentEventBus, creates UI card */
    STANDALONE,
    /** Part of a workflow - only emits to WorkflowEventBus, no standalone UI card */
    WORKFLOW_STEP
}

/**
 * Represents a sub-agent task and its current state.
 */
data class SubAgentTask(
    val taskId: String = UUID.randomUUID().toString(),
    val agentType: String,
    val taskDescription: String,
    val sessionId: Long,
    val status: SubAgentTaskStatus = SubAgentTaskStatus.PENDING,
    val result: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null
)

/**
 * Handle for a suspended SubAgent that can be woken up.
 */
data class IdleAgentHandle internal constructor(
    val agentId: String,
    val agentType: String,
    val stepId: String,
    val sessionId: Long,
    val context: Context,
    internal val resumeChannel: Channel<WakeUpSignal>
)

/**
 * Signal to wake up a suspended SubAgent.
 */
internal data class WakeUpSignal(
    val task: String,
    val contextVariables: Map<String, String>,
    val conversationHistory: List<JSONObject>?,
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
    val conversationHistory: MutableList<JSONObject>,
    val idleTimeoutWarningSent: Boolean = false
)

/**
 * Minimal SubAgent execution engine.
 *
 * Handles the core LLM + MCP tool loop for sub-agent tasks.
 * All task state is in-memory (lost on process death).
 */
object SubAgent {

    /**
     * Get the current SubAgent context from coroutine context.
     * Used by hooks to detect if they are being called from a SubAgent.
     * Uses coroutine context as primary source, with ThreadLocal as fallback.
     */
    suspend fun getCurrentContext(): SubAgentContext? {
        // Primary: coroutine context (works when context is properly propagated)
        val fromCoroutine = kotlin.coroutines.coroutineContext[SubAgentContext]
        if (fromCoroutine != null) return fromCoroutine
        // Fallback: thread-local (catches cases where coroutine context is lost)
        return threadLocalContext.get()
    }

    /**
     * Non-suspend version for use in non-coroutine contexts.
     */
    fun getCurrentContextBlocking(): SubAgentContext? {
        return threadLocalContext.get()
    }

    private const val MAX_DELEGATION_DEPTH = 3
    private const val MAX_GLOBAL_PARALLELISM = 3
    private const val MAX_ITERATIONS = 50
    private const val DEFAULT_ITERATIONS = 10
    private const val TASK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    private const val CLEANUP_AGE_MS = 30 * 60 * 1000L // 30 minutes

    // Retry configuration for transient network errors
    private const val MAX_API_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 1000L
    private const val MAX_RETRY_DELAY_MS = 10000L

    // Fallback configuration for empty responses
    private const val FALLBACK_RESPONSE_ENABLED = true

    private val globalSemaphore = Semaphore(MAX_GLOBAL_PARALLELISM)
    private val tasks = ConcurrentHashMap<String, SubAgentTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Thread-local fallback for SubAgent context detection
    // Used when coroutine context propagation fails (e.g., dispatcher switches)
    private val threadLocalContext = ThreadLocal<SubAgentContext?>()

    // Map of agentId -> AgentStateInfo for suspended agents
    private val suspendedAgents = ConcurrentHashMap<String, AgentStateInfo>()

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
        val resumeChannel = Channel<WakeUpSignal>(capacity = 1)

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

    /**
     * Wake up a suspended SubAgent and execute a task.
     */
    suspend fun wakeUp(
        handle: IdleAgentHandle,
        task: String,
        contextVariables: Map<String, String> = emptyMap(),
        conversationHistory: List<JSONObject>? = null,
        promptMode: AgentPrompts.PromptMode = AgentPrompts.PromptMode.WORKFLOW
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
                // Add SubAgent context to coroutine context for hooks to detect
                val subAgentCtx = SubAgentContext(task, handle.agentType, handle.agentId)
                val taskResult = withContext(subAgentCtx + threadLocalContext.asContextElement(subAgentCtx)) {
                    executeTask(
                        context = handle.context,
                        agentType = handle.agentType,
                        taskDescription = task,
                        sessionId = handle.sessionId,
                        depth = 1,
                        taskId = handle.agentId,
                        promptMode = promptMode
                    )
                }

                // Update conversation history
                suspendedAgents[handle.agentId]?.let { state ->
                    state.conversationHistory.add(JSONObject().apply {
                        put("role", "user")
                        put("content", task)
                    })
                    state.conversationHistory.add(JSONObject().apply {
                        put("role", "assistant")
                        put("content", taskResult)
                    })
                }

                Log.d("SubAgent", "[wakeUp] Agent ${handle.agentId} completed task")
                taskResult
            } finally {
                globalSemaphore.release()
            }
        } catch (e: Exception) {
            Log.e("SubAgent", "[wakeUp] Agent ${handle.agentId} failed: ${e.message}")
            throw e
        }
    }

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

    /**
     * Execute a sub-agent task asynchronously.
     * Returns the taskId immediately; result is delivered via MessageBus.
     */
    fun execute(
        context: Context,
        agentType: String,
        taskDescription: String,
        sessionId: Long,
        callerContext: AgentCallerContext? = null,
        promptMode: AgentPrompts.PromptMode = AgentPrompts.PromptMode.STANDARD
    ): String {
        val depth = (callerContext?.depth ?: 0) + 1
        if (depth > MAX_DELEGATION_DEPTH) {
            throw IllegalStateException("Max delegation depth ($MAX_DELEGATION_DEPTH) exceeded")
        }

        val taskId = UUID.randomUUID().toString()
        val task = SubAgentTask(
            taskId = taskId,
            agentType = agentType,
            taskDescription = taskDescription,
            sessionId = sessionId
        )
        tasks[taskId] = task

        val job = scope.launch {
            try {
                globalSemaphore.acquire()
                try {
                    // Add SubAgent context to coroutine context for hooks to detect
                    val subAgentContext = SubAgentContext(taskDescription, agentType, taskId)

                    SubAgentEventBus.emit(SubAgentEvent.TaskStarted(
                        taskId = taskId,
                        sessionId = sessionId,
                        taskType = agentType,
                        description = taskDescription
                    ))
                    updateTask(taskId, status = SubAgentTaskStatus.RUNNING, startedAt = System.currentTimeMillis())

                    val result = withContext(subAgentContext + threadLocalContext.asContextElement(subAgentContext)) {
                        executeTask(context, agentType, taskDescription, sessionId, depth, taskId, promptMode)
                    }
                    updateTask(taskId, status = SubAgentTaskStatus.COMPLETED, result = result,
                        completedAt = System.currentTimeMillis())

                    // Emit completion event for UI
                    SubAgentEventBus.emit(SubAgentEvent.TaskCompleted(
                        taskId = taskId,
                        sessionId = sessionId,
                        result = result
                    ))
                } finally {
                    globalSemaphore.release()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Task was cancelled — update status and emit event for UI cleanup
                updateTask(taskId, status = SubAgentTaskStatus.CANCELLED,
                    completedAt = System.currentTimeMillis())
                SubAgentEventBus.emit(SubAgentEvent.TaskFailed(
                    taskId = taskId,
                    sessionId = sessionId,
                    error = "Cancelled"
                ))
                throw e // re-throw to properly cancel the coroutine
            } catch (e: Exception) {
                updateTask(taskId, status = SubAgentTaskStatus.FAILED, error = e.message,
                    completedAt = System.currentTimeMillis())

                SubAgentEventBus.emit(SubAgentEvent.TaskFailed(
                    taskId = taskId,
                    sessionId = sessionId,
                    error = e.message ?: "Unknown error"
                ))
            } finally {
                activeJobs.remove(taskId)
                cleanupOldTasks()
            }
        }
        activeJobs[taskId] = job
        return taskId
    }

    /**
     * Synchronous execution — waits for the task to complete and returns the result.
     * Used by WorkflowEngine for pipeline/DAG execution.
     *
     * @param executionContext If WORKFLOW_STEP, does NOT emit SubAgentEvents (workflow handles UI via WorkflowEventBus).
     *                         If STANDALONE, emits full events for standalone UI card.
     */
    suspend fun executeSync(
        context: Context,
        agentType: String,
        taskDescription: String,
        sessionId: Long,
        callerContext: AgentCallerContext? = null,
        executionContext: SubAgentExecutionContext = SubAgentExecutionContext.WORKFLOW_STEP,
        promptMode: AgentPrompts.PromptMode = AgentPrompts.PromptMode.STANDARD
    ): String = withContext(Dispatchers.Default) {
        val depth = (callerContext?.depth ?: 0) + 1
        if (depth > MAX_DELEGATION_DEPTH) {
            throw IllegalStateException("Max delegation depth ($MAX_DELEGATION_DEPTH) exceeded")
        }

        val taskId = UUID.randomUUID().toString()
        val task = SubAgentTask(
            taskId = taskId,
            agentType = agentType,
            taskDescription = taskDescription,
            sessionId = sessionId
        )
        tasks[taskId] = task

        val emitEvents = executionContext == SubAgentExecutionContext.STANDALONE

        try {
            globalSemaphore.acquire()
            try {
                updateTask(taskId, status = SubAgentTaskStatus.RUNNING, startedAt = System.currentTimeMillis())

                // Only emit SubAgentEvents for STANDALONE context (not for workflow steps)
                if (emitEvents) {
                    SubAgentEventBus.emit(SubAgentEvent.TaskStarted(
                        taskId = taskId,
                        sessionId = sessionId,
                        taskType = agentType,
                        description = taskDescription
                    ))
                }

                // Add SubAgent context to coroutine context for hooks to detect
                val subAgentContext = SubAgentContext(taskDescription, agentType, taskId)
                val result = withContext(subAgentContext + threadLocalContext.asContextElement(subAgentContext)) {
                    executeTask(context, agentType, taskDescription, sessionId, depth, taskId, promptMode)
                }
                updateTask(taskId, status = SubAgentTaskStatus.COMPLETED, result = result,
                    completedAt = System.currentTimeMillis())

                if (emitEvents) {
                    SubAgentEventBus.emit(SubAgentEvent.TaskCompleted(
                        taskId = taskId,
                        sessionId = sessionId,
                        result = result
                    ))
                }

                return@withContext result
            } finally {
                globalSemaphore.release()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateTask(taskId, status = SubAgentTaskStatus.CANCELLED,
                completedAt = System.currentTimeMillis())
            if (emitEvents) {
                SubAgentEventBus.emit(SubAgentEvent.TaskFailed(
                    taskId = taskId,
                    sessionId = sessionId,
                    error = "Cancelled"
                ))
            }
            throw e
        } catch (e: Exception) {
            updateTask(taskId, status = SubAgentTaskStatus.FAILED, error = e.message,
                completedAt = System.currentTimeMillis())
            if (emitEvents) {
                SubAgentEventBus.emit(SubAgentEvent.TaskFailed(
                    taskId = taskId,
                    sessionId = sessionId,
                    error = e.message ?: "Unknown error"
                ))
            }
            throw e
        } finally {
            cleanupOldTasks()
        }
    }

    /**
     * Core LLM + tool execution loop.
     */
    private suspend fun executeTask(
        context: Context,
        agentType: String,
        taskDescription: String,
        sessionId: Long,
        depth: Int,
        taskId: String,
        promptMode: AgentPrompts.PromptMode = AgentPrompts.PromptMode.STANDARD
    ): String {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val runtimeManager = McpRuntimeManager.getInstance(context)

        // Get model config — use default provider
        val config = repository.getDefaultProvider()
            ?: throw IllegalStateException("No default model provider configured")

        // Build system prompt
        val systemPrompt = AgentPrompts.getPrompt(agentType, promptMode)

        // Get available tools
        val toolsArray = runtimeManager.getAllToolsAsOpenAiFormat()

        // Build initial messages
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", taskDescription)
            })
        }

        // LLM + tool call loop
        var finalContent: String? = null
        for (iteration in 0 until MAX_ITERATIONS) {
            // Check task timeout
            val task = tasks[taskId] ?: break
            if (System.currentTimeMillis() - (task.startedAt ?: task.createdAt) > TASK_TIMEOUT_MS) {
                throw RuntimeException("Task timed out after ${TASK_TIMEOUT_MS / 1000}s")
            }

            val response = executeWithRetry(
                config = config,
                systemPrompt = systemPrompt,
                messages = messages,
                toolsArray = toolsArray,
                taskId = taskId
            ) ?: throw RuntimeException("Empty API response after $MAX_API_RETRIES retries")

            // Append assistant response to messages
            messages.put(response)

            // Check for tool calls
            val toolCalls = response.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                // Execute each tool call
                for (i in 0 until toolCalls.length()) {
                    val toolCall = toolCalls.getJSONObject(i)
                    val toolCallId = toolCall.getString("id")
                    val function = toolCall.getJSONObject("function")
                    val toolName = function.getString("name")
                    val argsStr = function.getString("arguments")
                    val argsJson = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }

                    // Execute tool via McpRuntimeManager
                    val serverId = runtimeManager.findServerIdForTool(toolName)
                    if (serverId == null) {
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", toolCallId)
                            put("content", """{"error": "Tool '$toolName' not found"}""")
                        })
                        continue
                    }
                    val result = runtimeManager.callTool(
                        serverId = serverId,
                        toolName = toolName,
                        arguments = argsJson,
                        sessionId = sessionId
                    )

                    val resultText = result?.toString() ?: """{"error": "Tool returned null"}"""

                    // Append tool result to messages
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolCallId)
                        put("content", resultText)
                    })

                    SubAgentEventBus.emit(SubAgentEvent.TaskProgress(
                        taskId = taskId,
                        message = "Called tool: $toolName"
                    ))
                }
            } else {
                // No tool calls — task is complete
                finalContent = response.optString("content", "")
                break
            }
        }

        // Normalize output to JSON format
        val rawOutput = finalContent ?: "Task completed with no output"
        return normalizeOutputToJson(rawOutput)
    }

    /**
     * Ensure the output is valid JSON in our standard format.
     * If the LLM already returned JSON, validate and pass through.
     * Otherwise, wrap in our standard format.
     *
     * Standard format:
     * {
     *   "status": "DONE" | "BLOCKED" | "NEEDS_CONTEXT",
     *   "summary": "<one-line summary>",
     *   "actions": [{ "step": "", "tool": "", "outcome": "" }],
     *   "key_findings": [],
     *   "deliverables": [],
     *   "confidence": "high" | "medium" | "low",
     *   "notes": "",
     *   "next_steps_hint": "",
     *   "full_output": "<complete output content for downstream steps>"
     * }
     */
    private fun normalizeOutputToJson(rawOutput: String): String {
        return try {
            val json = JSONObject(rawOutput)

            // Check if it has the required "status" field
            if (!json.has("status")) {
                // Add missing status
                json.put("status", "DONE")
            }

            // Ensure new structured fields exist (for backwards compatibility)
            if (!json.has("summary")) {
                // If there's a "result" field, use it as summary
                val result = json.optString("result", rawOutput.take(200))
                json.put("summary", result.take(100))
            }

            if (!json.has("actions")) {
                json.put("actions", JSONArray())
            }

            if (!json.has("key_findings")) {
                json.put("key_findings", JSONArray())
            }

            if (!json.has("deliverables")) {
                json.put("deliverables", JSONArray())
            }

            if (!json.has("confidence")) {
                json.put("confidence", "medium")
            }

            if (!json.has("notes")) {
                json.put("notes", "")
            }

            // IMPORTANT: For DAG/summary steps, preserve full output
            // If the LLM didn't provide full_output, extract it from raw response
            if (!json.has("full_output")) {
                // Check if there's substantial content beyond the JSON structure
                // This helps downstream steps access complete information
                val contentField = json.optString("content", "")
                val resultField = json.optString("result", "")

                // Use the most complete field available
                val fullContent = when {
                    contentField.isNotBlank() && contentField.length > 200 -> contentField
                    resultField.isNotBlank() && resultField.length > 200 -> resultField
                    // If JSON has other significant text fields, combine them
                    else -> {
                        val textFields = mutableListOf<String>()
                        json.keys().forEach { key ->
                            val value = json.opt(key)
                            if (value is String && value.length > 100 && key != "summary") {
                                textFields.add("$key: $value")
                            }
                        }
                        if (textFields.isNotEmpty()) {
                            textFields.joinToString("\n\n")
                        } else {
                            ""
                        }
                    }
                }

                if (fullContent.isNotBlank()) {
                    json.put("full_output", fullContent)
                }
            }

            json.toString()

        } catch (_: Exception) {
            // Not valid JSON — wrap it in the new format
            // IMPORTANT: Include the FULL raw output in full_output field
            JSONObject().apply {
                put("status", "DONE")
                put("summary", rawOutput.take(100))
                put("actions", JSONArray().apply {
                    put(JSONObject().apply {
                        put("step", "Task execution")
                        put("tool", null)
                        put("outcome", rawOutput.take(500))
                    })
                })
                put("key_findings", JSONArray())
                put("deliverables", JSONArray())
                put("confidence", "medium")
                put("notes", "Output was not in expected JSON format; wrapped automatically.")
                put("next_steps_hint", "")
                // Preserve full output for downstream consumption
                put("full_output", rawOutput)
            }.toString()
        }
    }

    fun getTask(taskId: String): SubAgentTask? = tasks[taskId]

    /**
     * Get all active tasks for a specific session.
     * Used by export_session_log tool.
     */
    fun getActiveTasksForSession(sessionId: Long): Map<String, com.omnichat.ui.screens.SubAgentTaskUiState> {
        return tasks.entries
            .filter { it.value.sessionId == sessionId }
            .associate { (taskId, task) ->
                taskId to com.omnichat.ui.screens.SubAgentTaskUiState(
                    taskId = taskId,
                    sessionId = task.sessionId,
                    taskType = task.agentType,
                    description = task.taskDescription,
                    status = when (task.status) {
                        SubAgentTaskStatus.PENDING -> com.omnichat.ui.screens.TaskStatus.RUNNING
                        SubAgentTaskStatus.RUNNING -> com.omnichat.ui.screens.TaskStatus.RUNNING
                        SubAgentTaskStatus.COMPLETED -> com.omnichat.ui.screens.TaskStatus.COMPLETED
                        SubAgentTaskStatus.FAILED -> com.omnichat.ui.screens.TaskStatus.FAILED
                        SubAgentTaskStatus.CANCELLED -> com.omnichat.ui.screens.TaskStatus.FAILED
                    },
                    progressMessage = null,
                    result = task.result,
                    startedAtMs = task.startedAt ?: task.createdAt
                )
            }
    }

    fun cancelTask(taskId: String): Boolean {
        val job = activeJobs[taskId] ?: return false
        job.cancel()
        updateTask(taskId, status = SubAgentTaskStatus.CANCELLED, completedAt = System.currentTimeMillis())
        activeJobs.remove(taskId)
        return true
    }

    private fun updateTask(
        taskId: String,
        status: SubAgentTaskStatus? = null,
        result: String? = null,
        error: String? = null,
        startedAt: Long? = null,
        completedAt: Long? = null
    ) {
        tasks.computeIfPresent(taskId) { _, task ->
            task.copy(
                status = status ?: task.status,
                result = result ?: task.result,
                error = error ?: task.error,
                startedAt = startedAt ?: task.startedAt,
                completedAt = completedAt ?: task.completedAt
            )
        }
    }

    private fun cleanupOldTasks() {
        val now = System.currentTimeMillis()
        tasks.entries.removeIf { (_, task) ->
            task.status in setOf(SubAgentTaskStatus.COMPLETED, SubAgentTaskStatus.FAILED, SubAgentTaskStatus.CANCELLED) &&
                (task.completedAt ?: 0L) + CLEANUP_AGE_MS < now
        }
    }

    /**
     * Execute API call with exponential backoff retry for transient errors.
     *
     * Retries on:
     * - SocketTimeoutException (network timeout)
     * - IOException (general network error)
     * - Empty API response (null response)
     *
     * Does NOT retry on:
     * - HTTP 4xx errors (client errors)
     * - Other exceptions
     */
    private suspend fun executeWithRetry(
        config: com.omnichat.data.ModelConfig,
        systemPrompt: String,
        messages: JSONArray,
        toolsArray: JSONArray,
        taskId: String
    ): JSONObject? {
        var delayMs = INITIAL_RETRY_DELAY_MS
        var lastException: Exception? = null
        var emptyResponseCount = 0

        for (attempt in 0 until MAX_API_RETRIES) {
            try {
                val response = ApiClient.executeMessageCompletion(
                    config = config,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = if (toolsArray.length() > 0) toolsArray else null
                )
                if (response != null) {
                    return response
                }
                // null response - count as empty response
                emptyResponseCount++
                lastException = RuntimeException("Empty API response (attempt $emptyResponseCount)")

            } catch (e: Exception) {
                lastException = e
                // Only retry on transient network errors
                if (!isTransientError(e)) {
                    Log.w("SubAgent", "[$taskId] Non-transient error on attempt ${attempt + 1}: ${e.javaClass.simpleName} - ${e.message}")
                    throw e
                }
            }

            // Log retry attempt
            Log.d("SubAgent", "[$taskId] Retry attempt ${attempt + 1}/$MAX_API_RETRIES after ${delayMs}ms (error: ${lastException?.message})")

            // Emit progress event for UI
            SubAgentEventBus.emit(SubAgentEvent.TaskProgress(
                taskId = taskId,
                message = "Network issue, retrying (${attempt + 1}/$MAX_API_RETRIES)..."
            ))

            // Wait before retry
            delay(delayMs)
            delayMs = minOf(delayMs * 2, MAX_RETRY_DELAY_MS) // Exponential backoff, capped at 10s
        }

        Log.e("SubAgent", "[$taskId] All $MAX_API_RETRIES retries exhausted. Last error: ${lastException?.message}")

        // FALLBACK: Return a structured error response instead of null
        // This allows DAG downstream steps to receive partial information
        if (FALLBACK_RESPONSE_ENABLED && emptyResponseCount > 0) {
            Log.w("SubAgent", "[$taskId] Using fallback response due to empty API responses")
            return JSONObject().apply {
                put("role", "assistant")
                put("content", buildFallbackContent(lastException?.message ?: "Empty API response"))
            }
        }

        return null
    }

    /**
     * Build a fallback response content when API fails repeatedly.
     * This ensures downstream DAG steps receive structured information.
     */
    private fun buildFallbackContent(errorMessage: String): String {
        return JSONObject().apply {
            put("status", "BLOCKED")
            put("summary", "API temporarily unavailable - $errorMessage")
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("step", "API call failed after retries")
                    put("tool", null)
                    put("outcome", errorMessage)
                })
            })
            put("key_findings", JSONArray().apply {
                put("API service may be experiencing issues")
                put("Consider retrying or using alternative approach")
            })
            put("deliverables", JSONArray())
            put("confidence", "low")
            put("notes", "This is a fallback response due to API unavailability. Downstream steps should handle this gracefully.")
            put("full_output", """
# API Unavailable

The API service did not respond after $MAX_API_RETRIES attempts.

**Error**: $errorMessage

**Recommendations**:
1. Wait a few minutes and retry
2. Check API service status
3. Consider alternative approaches for this task

**Downstream Steps**: Please note this step was blocked by API issues.
""".trimIndent())
        }.toString()
    }

    /**
     * Check if an exception is a transient network error that should be retried.
     */
    private fun isTransientError(e: Exception): Boolean {
        return e is SocketTimeoutException ||
               e is IOException ||
               (e.cause is SocketTimeoutException) ||
               (e.cause is IOException)
    }
}
