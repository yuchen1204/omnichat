package com.omnichat.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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
     */
    suspend fun executeDAG(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        callerContext: AgentCallerContext? = null
    ): List<StepResult> = coroutineScope {
        val results = mutableMapOf<String, StepResult>()
        val completedSteps = mutableSetOf<String>()

        // Keep processing until all steps are done
        val remaining = steps.toMutableList()

        while (remaining.isNotEmpty()) {
            // Find steps whose dependencies are all met
            val ready = remaining.filter { step ->
                step.dependsOn.all { it in completedSteps }
            }

            if (ready.isEmpty()) {
                // Deadlock — remaining steps have unresolvable dependencies
                remaining.forEach { step ->
                    results[step.id] = StepResult(step.id, WorkflowStepStatus.SKIPPED,
                        error = "Dependency not met")
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
                        if (depStep?.resultVariable != null && depResult != null) {
                            contextVariables[depStep.resultVariable] = depResult.result ?: ""
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
                remaining.removeAll { it.id == result.stepId }
            }
        }

        steps.map { results[it.id] ?: StepResult(it.id, WorkflowStepStatus.SKIPPED) }
    }

    /**
     * Execute a conversational workflow — two agents exchange messages until convergence.
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
        var currentMessage = topic

        for (round in 0 until maxRounds) {
            // Agent A responds
            val responseA = try {
                val output = SubAgent.executeSync(
                    context = context,
                    agentType = agentA,
                    taskDescription = "Regarding: $topic\n\nPrevious message:\n$currentMessage\n\nRespond with your analysis or output. If you believe the discussion is complete and a final answer has been reached, start your response with [CONVERGED].",
                    sessionId = sessionId,
                    callerContext = callerContext
                )
                StepResult("round-${round}-$agentA", WorkflowStepStatus.COMPLETED, result = output)
            } catch (e: Exception) {
                StepResult("round-${round}-$agentA", WorkflowStepStatus.FAILED, error = e.message)
            }
            results.add(responseA)

            if (responseA.status == WorkflowStepStatus.FAILED) break

            val contentA = responseA.result ?: ""
            if (contentA.startsWith("[CONVERGED]")) {
                results.add(StepResult("convergence", WorkflowStepStatus.COMPLETED,
                    result = contentA.removePrefix("[CONVERGED]").trim()))
                break
            }

            // Agent B responds to Agent A
            currentMessage = contentA
            val responseB = try {
                val output = SubAgent.executeSync(
                    context = context,
                    agentType = agentB,
                    taskDescription = "Regarding: $topic\n\nPrevious message from $agentA:\n$currentMessage\n\nRespond with your analysis or output. If you believe the discussion is complete and a final answer has been reached, start your response with [CONVERGED].",
                    sessionId = sessionId,
                    callerContext = callerContext
                )
                StepResult("round-${round}-$agentB", WorkflowStepStatus.COMPLETED, result = output)
            } catch (e: Exception) {
                StepResult("round-${round}-$agentB", WorkflowStepStatus.FAILED, error = e.message)
            }
            results.add(responseB)

            if (responseB.status == WorkflowStepStatus.FAILED) break

            val contentB = responseB.result ?: ""
            if (contentB.startsWith("[CONVERGED]")) {
                results.add(StepResult("convergence", WorkflowStepStatus.COMPLETED,
                    result = contentB.removePrefix("[CONVERGED]").trim()))
                break
            }

            currentMessage = contentB
        }

        results
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
