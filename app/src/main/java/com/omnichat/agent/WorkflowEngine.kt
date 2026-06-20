package com.omnichat.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A single step in a workflow.
 */
data class WorkflowStep(
    val id: String,
    val agentType: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    val resultVariable: String? = null // if set, result is stored in workflow context
)

/**
 * Status of a workflow step.
 */
enum class WorkflowStepStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
}

/**
 * Result of a single workflow step.
 */
data class StepResult(
    val stepId: String,
    val status: WorkflowStepStatus,
    val result: String? = null,
    val error: String? = null
)

/**
 * Orchestrates multi-agent workflows with three modes:
 * - Pipeline: sequential execution, results passed to next step
 * - DAG: dependency-based execution with parallelism
 * - Conversational: multi-round dialogue between two agents
 */
object WorkflowEngine {

    /**
     * Execute a pipeline — steps run sequentially, each step receives prior results as context.
     */
    suspend fun executePipeline(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        callerContext: AgentCallerContext? = null
    ): List<StepResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<StepResult>()
        // Key: step.id (for direct lookup) OR resultVariable (for named reference)
        val contextVariables = mutableMapOf<String, String>()

        for (step in steps) {
            // Build task description with prior context
            val fullTask = buildTaskWithContext(step.task, step.dependsOn, contextVariables, steps)

            val result = try {
                val output = SubAgent.executeSync(
                    context = context,
                    agentType = step.agentType,
                    taskDescription = fullTask,
                    sessionId = sessionId,
                    callerContext = callerContext
                )
                // Store result under both step.id and resultVariable (if set)
                // so downstream steps can reference by either name
                contextVariables[step.id] = output
                step.resultVariable?.let { contextVariables[it] = output }
                StepResult(step.id, WorkflowStepStatus.COMPLETED, result = output)
            } catch (e: Exception) {
                StepResult(step.id, WorkflowStepStatus.FAILED, error = e.message)
            }

            results.add(result)

            // Stop pipeline on failure
            if (result.status == WorkflowStepStatus.FAILED) break
        }

        results
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

    /**
     * Check if a response indicates convergence.
     * Uses startsWith for the primary check — the [CONVERGED] marker must be at the
     * very beginning of the response to avoid false positives from quoted text.
     */
    private fun looksConverged(response: String): Boolean {
        return response.trimStart().startsWith("[CONVERGED]")
    }

    /**
     * Extract the actual answer from a converged response, stripping the [CONVERGED] marker.
     */
    private fun extractConvergedAnswer(response: String): String {
        return response.trimStart().removePrefix("[CONVERGED]").trim()
    }

    private fun buildTaskWithContext(
        task: String,
        dependsOn: List<String>,
        contextVariables: Map<String, String>,
        steps: List<WorkflowStep> = emptyList()
    ): String {
        if (dependsOn.isEmpty() || contextVariables.isEmpty()) return task

        val contextBlock = buildString {
            appendLine("Context from previous steps:")
            dependsOn.forEach { depId ->
                // Look up by step.id first; if not found, try resultVariable of the dep step
                val value = contextVariables[depId]
                    ?: steps.find { it.id == depId }?.resultVariable?.let { contextVariables[it] }
                value?.let { result ->
                    appendLine("--- Step $depId result ---")
                    appendLine(result)
                    appendLine("--- End ---")
                }
            }
        }

        return "$contextBlock\n\nTask: $task"
    }
}
