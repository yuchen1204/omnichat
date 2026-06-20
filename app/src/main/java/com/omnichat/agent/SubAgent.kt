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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

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
 * Minimal SubAgent execution engine.
 *
 * Handles the core LLM + MCP tool loop for sub-agent tasks.
 * All task state is in-memory (lost on process death).
 */
object SubAgent {

    /** 当前正在执行的 SubAgent 任务描述，供 Hook 获取上下文做权限审核 */
    val currentTaskContext = ThreadLocal<String>()

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

    private val globalSemaphore = Semaphore(MAX_GLOBAL_PARALLELISM)
    private val tasks = ConcurrentHashMap<String, SubAgentTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Execute a sub-agent task asynchronously.
     * Returns the taskId immediately; result is delivered via MessageBus.
     */
    fun execute(
        context: Context,
        agentType: String,
        taskDescription: String,
        sessionId: Long,
        callerContext: AgentCallerContext? = null
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
                    SubAgentEventBus.emit(SubAgentEvent.TaskStarted(
                        taskId = taskId,
                        sessionId = sessionId,
                        taskType = agentType,
                        description = taskDescription
                    ))
                    updateTask(taskId, status = SubAgentTaskStatus.RUNNING, startedAt = System.currentTimeMillis())
                    currentTaskContext.set(taskDescription)
                    val result = try {
                        executeTask(context, agentType, taskDescription, sessionId, depth, taskId)
                    } finally {
                        currentTaskContext.remove()
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
     */
    suspend fun executeSync(
        context: Context,
        agentType: String,
        taskDescription: String,
        sessionId: Long,
        callerContext: AgentCallerContext? = null
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

        try {
            globalSemaphore.acquire()
            try {
                updateTask(taskId, status = SubAgentTaskStatus.RUNNING, startedAt = System.currentTimeMillis())
                SubAgentEventBus.emit(SubAgentEvent.TaskStarted(
                    taskId = taskId,
                    sessionId = sessionId,
                    taskType = agentType,
                    description = taskDescription
                ))
                currentTaskContext.set(taskDescription)
                val result = try {
                    executeTask(context, agentType, taskDescription, sessionId, depth, taskId)
                } finally {
                    currentTaskContext.remove()
                }
                updateTask(taskId, status = SubAgentTaskStatus.COMPLETED, result = result,
                    completedAt = System.currentTimeMillis())
                SubAgentEventBus.emit(SubAgentEvent.TaskCompleted(
                    taskId = taskId,
                    sessionId = sessionId,
                    result = result
                ))
                return@withContext result
            } finally {
                globalSemaphore.release()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateTask(taskId, status = SubAgentTaskStatus.CANCELLED,
                completedAt = System.currentTimeMillis())
            SubAgentEventBus.emit(SubAgentEvent.TaskFailed(
                taskId = taskId,
                sessionId = sessionId,
                error = "Cancelled"
            ))
            throw e
        } catch (e: Exception) {
            updateTask(taskId, status = SubAgentTaskStatus.FAILED, error = e.message,
                completedAt = System.currentTimeMillis())
            SubAgentEventBus.emit(SubAgentEvent.TaskFailed(
                taskId = taskId,
                sessionId = sessionId,
                error = e.message ?: "Unknown error"
            ))
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
        taskId: String
    ): String {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val runtimeManager = McpRuntimeManager.getInstance(context)

        // Get model config — use default provider
        val config = repository.getDefaultProvider()
            ?: throw IllegalStateException("No default model provider configured")

        // Build system prompt
        val systemPrompt = AgentPrompts.getPrompt(agentType)

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
     * If the LLM already returned JSON, pass through.
     * Otherwise, wrap in our standard format.
     */
    private fun normalizeOutputToJson(rawOutput: String): String {
        return try {
            val json = JSONObject(rawOutput)
            // Verify it has the required "status" field
            if (json.has("status")) {
                rawOutput // already valid format
            } else {
                JSONObject().apply {
                    put("status", "DONE")
                    put("result", rawOutput)
                    put("confidence", "medium")
                    put("notes", "Output missing status field; wrapped automatically.")
                }.toString()
            }
        } catch (_: Exception) {
            // Not valid JSON — wrap it
            JSONObject().apply {
                put("status", "DONE")
                put("result", rawOutput)
                put("confidence", "medium")
                put("notes", "Output was not in expected JSON format; wrapped automatically.")
            }.toString()
        }
    }

    fun getTask(taskId: String): SubAgentTask? = tasks[taskId]

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
                // null response might be transient too
                lastException = RuntimeException("Empty API response")
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
        return null
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
