package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.agent.AgentMessage
import com.omnichat.agent.AgentPrompts
import com.omnichat.agent.MessageBus
import com.omnichat.agent.SubAgent
import com.omnichat.agent.AgentCallerContext
import com.omnichat.agent.WorkflowEngine
import com.omnichat.agent.WorkflowStep
import com.omnichat.agent.StepResult
import com.omnichat.agent.WorkflowStepStatus
import com.omnichat.agent.WorkflowMode
import com.omnichat.agent.WorkflowStatus
import com.omnichat.agent.WorkflowEvent
import com.omnichat.agent.WorkflowEventBus
import com.omnichat.agent.WorkflowStepUiState
import com.omnichat.agent.WorkflowUiState
import com.omnichat.agent.WorkflowTemplates
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * SubAgent 相关工具。
 */

object DelegateTaskTool : BuiltinTool(
    name = "delegate_task",
    description = """Delegate a task to a sub-agent for independent execution. The sub-agent runs with its own LLM context and tool access. Returns a taskId.

⚠️ BLOCKING CALL: This tool is ASYNC but the result arrives as a chat message. You MUST NOT duplicate the delegated task yourself. DO NOT start the same work in parallel. Wait for the result message to appear before proceeding with related work.

WHEN TO DELEGATE:
- Research tasks (web search, memory lookup, information gathering)
- Multi-step operations that would block the conversation
- Tasks requiring focused execution without conversation context

WHEN NOT TO DELEGATE:
- Simple questions you can answer directly
- Tasks requiring full conversation history context
- Single-step tool calls (just call the tool directly)

AGENT TYPES: general, researcher, coder, reviewer, tester, planner, orchestrator

The sub-agent's result is delivered as a chat message when complete.""",
    group = "subagent",
    isReadOnly = false,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "delegate task to sub-agent"
) {

    override val inputSchema = schema {
        prop("agentType", "string", "The type of sub-agent.") {
            enum("general", "researcher", "coder", "reviewer", "tester", "planner", "orchestrator")
        }
        prop("task", "string", "The task description for the sub-agent to perform.")
        prop("promptMode", "string", "Prompt verbosity: compact (minimal), standard (normal). Default: standard.") {
            enum("compact", "standard")
        }
    }

    override fun validateInput(arguments: JSONObject): String? {
        val agentType = arguments.optString("agentType").trim()
        val task = arguments.optString("task").trim()

        if (agentType.isEmpty()) return "agentType is required"
        if (task.isEmpty()) return "task is required"

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        if (sessionId == null) {
            return errorResponse("delegate_task requires a session context")
        }

        val agentType = arguments.optString("agentType")
        val task = arguments.optString("task")
        val promptModeStr = arguments.optString("promptMode", "standard")
        val promptMode = when (promptModeStr) {
            "compact" -> AgentPrompts.PromptMode.COMPACT
            else -> AgentPrompts.PromptMode.STANDARD
        }

        try {
            val taskId = SubAgent.execute(
                context = context,
                agentType = agentType,
                taskDescription = task,
                sessionId = sessionId,
                promptMode = promptMode
            )

            return successResponse("Task delegated to $agentType agent.\nTask ID: $taskId\n\nThe result will appear in chat when the sub-agent completes.")
        } catch (e: Exception) {
            return errorResponse("Failed to delegate task: ${e.localizedMessage}")
        }
    }
}

object CheckTaskStatusTool : BuiltinTool(
    name = "check_task_status",
    description = "Check the status and result of a previously delegated sub-agent task.",
    group = "subagent",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "check sub-agent task status"
) {

    override val inputSchema = schema {
        prop("taskId", "string", "The taskId returned by delegate_task.")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val taskId = arguments.optString("taskId").trim()
        if (taskId.isEmpty()) return "taskId is required"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val taskId = arguments.optString("taskId")

        val task = SubAgent.getTask(taskId)
            ?: return errorResponse("Task not found: $taskId")

        val text = buildString {
            appendLine("Task: ${task.taskDescription}")
            appendLine("Status: ${task.status}")
            appendLine("Agent: ${task.agentType}")

            when (task.status) {
                com.omnichat.agent.SubAgentTaskStatus.PENDING -> {
                    appendLine("The task is waiting to start.")
                }
                com.omnichat.agent.SubAgentTaskStatus.RUNNING -> {
                    appendLine("The task is currently running.")
                }
                com.omnichat.agent.SubAgentTaskStatus.COMPLETED -> {
                    appendLine("Result:")
                    appendLine(task.result ?: "(no result)")
                }
                com.omnichat.agent.SubAgentTaskStatus.FAILED -> {
                    appendLine("Error: ${task.error ?: "Unknown error"}")
                }
                com.omnichat.agent.SubAgentTaskStatus.CANCELLED -> {
                    appendLine("The task was cancelled.")
                }
            }
        }

        return successResponse(text)
    }
}

object SendAgentMessageTool : BuiltinTool(
    name = "send_agent_message",
    description = "Send a message to another agent's inbox. Used for inter-agent communication during collaborative workflows.",
    group = "subagent",
    isReadOnly = false,
    isConcurrencySafe = true,
    searchHint = "send message to another agent"
) {

    override val inputSchema = schema {
        prop("to", "string", "The target agent ID (e.g. 'main', 'subagent:coder:task-id').")
        prop("content", "string", "The message content.")
        required("to", "content")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val to = arguments.optString("to").trim()
        val content = arguments.optString("content").trim()

        if (to.isEmpty()) return "to is required"
        if (content.isEmpty()) return "content is required"

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val to = arguments.optString("to")
        val content = arguments.optString("content")

        // 使用 "main" 作为默认发送者
        MessageBus.send(from = "main", to = to, content = content)

        return successResponse("Message sent to: $to")
    }
}

object ReadAgentInboxTool : BuiltinTool(
    name = "read_agent_inbox",
    description = "Read messages from the current agent's inbox. Returns pending messages from other agents.",
    group = "subagent",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "read messages from agent inbox"
) {

    override val inputSchema = schema {}

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        // 默认读取 "main" 的 inbox
        val messages = MessageBus.readInbox("main")

        if (messages.isEmpty()) {
            return successResponse("No pending messages in inbox.")
        }

        val text = buildString {
            appendLine("Inbox (${messages.size} messages):")
            appendLine()

            messages.forEachIndexed { i, msg ->
                appendLine("${i + 1}. From: ${msg.from}")
                appendLine("   ${msg.content}")
                appendLine()
            }
        }

        return successResponse(text.trimEnd())
    }
}

object RunWorkflowTool : BuiltinTool(
    name = "run_workflow",
    description = """Execute a multi-agent workflow with coordinated execution.

⚠️ BLOCKING CALL: This tool is SYNCHRONOUS. You MUST wait for it to complete before taking any other action. DO NOT start parallel work while the workflow is running. DO NOT attempt the same task yourself while waiting. You will receive the complete results when the tool returns.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

## 📋 MODES AND REQUIRED PARAMETERS

### 1. pipeline (Sequential Execution)
**When to use**: Steps have strict order dependency (A must finish before B starts).

**Required parameters**:
```json
{
  "mode": "pipeline",
  "steps": [
    {"id": "step1", "agentType": "researcher", "task": "Research X"},
    {"id": "step2", "agentType": "coder", "task": "Implement based on step1"}
  ]
}
```
OR use template:
```json
{
  "mode": "pipeline",
  "template": "research_and_report",
  "task": "Your task description"
}
```

**Optional parameters**:
- `steps[].timeoutMs`: Running timeout (default: 600000 = 10min)

**Execution behavior**:
- Steps run sequentially in order
- Each step receives prior step results as context
- Failure in any step stops the pipeline

---

### 2. dag (Dependency-based Parallel Execution)
**When to use**: Steps have partial dependencies, some can run in parallel.

**Required parameters**:
```json
{
  "mode": "dag",
  "steps": [
    {"id": "A", "agentType": "researcher", "task": "Research module A"},
    {"id": "B", "agentType": "researcher", "task": "Research module B"},
    {"id": "C", "agentType": "researcher", "task": "Research module C"},
    {"id": "summary", "agentType": "orchestrator", "task": "Combine results", "dependsOn": ["A", "B", "C"]}
  ]
}
```

**Key points**:
- `dependsOn`: Array of step IDs this step depends on
- Steps without dependencies run in parallel
- Steps with dependencies wait for all dependencies to complete
- Failed dependency → dependent steps are skipped

**Optional parameters**:
- `steps[].timeoutMs`: Running timeout (default: 600000 = 10min)

**Execution behavior**:
- Independent steps run in parallel
- Dependent steps wait for all dependencies
- Transitive failure propagation (if A fails, steps depending on A are skipped)

---

### 3. conversational (Multi-round Discussion)
**When to use**: Need multi-perspective discussion or debate between two agents.

**Required parameters**:
```json
{
  "mode": "conversational",
  "agentA": "coder",
  "agentB": "reviewer",
  "topic": "How to refactor legacy Python code?"
}
```

**Optional parameters**:
- `maxRounds`: Maximum discussion rounds (default: 5)

**Convergence mechanism**:
- Agent outputs `[CONVERGED]` to indicate discussion is complete
- Stops when either agent outputs `[CONVERGED]` or maxRounds reached
- Full conversation history is accumulated and passed to each agent

**Execution behavior**:
- AgentA speaks first, then AgentB, alternating
- Each agent sees full conversation history
- Converged response is extracted and returned

---

### 4. interactive_pipeline (Revision Support)
**When to use**: Need step-to-step communication with revision support (e.g., code-review cycles).

**Required parameters**:
```json
{
  "mode": "interactive_pipeline",
  "steps": [
    {"id": "code", "agentType": "coder", "task": "Implement feature X"},
    {"id": "review", "agentType": "reviewer", "task": "Review code step"}
  ]
}
```

**Optional parameters**:
- `steps[].timeoutMs`: Running timeout (default: 600000 = 10min)
- `steps[].maxIdleMs`: IDLE timeout (default: 1800000 = 30min)

**Execution behavior**:
- Steps can enter IDLE state after completion
- Steps can be recalled for REVISION if issues found
- Agents can communicate via `send_agent_message(to="step:step_id", content="...")`
- Example: Reviewer sends revision request to coder

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

## 📦 AVAILABLE TEMPLATES

| Template | Steps | Description |
|----------|-------|-------------|
| `research_and_report` | Research → Report | Research a topic and generate report |
| `code_and_review` | Code → Review | Implement and review code |
| `full_dev_cycle` | Research → Code → Review | Full development cycle |

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

## 🤖 AVAILABLE AGENT TYPES

| Type | Best For |
|------|----------|
| `general` | Generic tasks, simple operations |
| `researcher` | Research, verification, information gathering |
| `coder` | Code analysis, generation, refactoring |
| `reviewer` | Code review, quality assessment |
| `tester` | Test design and execution |
| `planner` | Implementation planning |
| `orchestrator` | Multi-step coordination, summarization |

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

## 📤 OUTPUT FORMAT

Each step returns structured JSON:
```json
{
  "status": "DONE" | "BLOCKED" | "NEEDS_CONTEXT",
  "summary": "One-line description",
  "actions": [{"step": "...", "tool": "...", "outcome": "..."}],
  "key_findings": ["..."],
  "deliverables": ["..."],
  "confidence": "high" | "medium" | "low",
  "full_output": "Complete detailed content for downstream steps"
}
```

**For DAG summary steps**: Read `full_output` from upstream steps to access complete content.""",
    group = "subagent",
    isReadOnly = false,
    isConcurrencySafe = false,
    requiresSession = true,
    searchHint = "run multi-agent workflow"
) {

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
                    prop("dependsOn", "array", "IDs of steps this depends on (dag only).") { items { } }
                    prop("timeoutMs", "integer", "Running timeout in milliseconds (default 600000 = 10min).")
                    prop("maxIdleMs", "integer", "IDLE timeout in milliseconds (default 1800000 = 30min, interactive_pipeline only).")
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

    private suspend fun executePipeline(context: Context, steps: List<WorkflowStep>, sessionId: Long): JSONObject {
        // Generate workflow ID and emit start event
        val workflowId = UUID.randomUUID().toString()
        WorkflowEventBus.emit(WorkflowEvent.WorkflowStarted(
            workflowId = workflowId,
            sessionId = sessionId,
            mode = WorkflowMode.PIPELINE,
            totalSteps = steps.size
        ))

        // Execute with progress tracking
        val results = executePipelineWithEvents(context, sessionId, steps, workflowId)

        // Emit completion event
        WorkflowEventBus.emit(WorkflowEvent.WorkflowCompleted(
            workflowId = workflowId,
            sessionId = sessionId,
            results = results
        ))

        val text = buildString {
            appendLine("Pipeline execution completed.")
            appendLine()

            results.forEachIndexed { i, result ->
                appendLine("${i + 1}. [${result.stepId}] ${result.status}")
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

    private suspend fun executePipelineWithEvents(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        workflowId: String
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        val contextVariables = mutableMapOf<String, String>()

        steps.forEachIndexed { index, step ->
            // Emit step started event
            WorkflowEventBus.emit(WorkflowEvent.StepStarted(
                workflowId = workflowId,
                sessionId = sessionId,
                stepId = step.id,
                stepIndex = index,
                agentType = step.agentType,
                task = step.task
            ))

            val fullTask = WorkflowEngine.buildTaskWithContext(step.task, step.dependsOn, contextVariables, steps)

            val result = try {
                val output = SubAgent.executeSync(
                    context = context,
                    agentType = step.agentType,
                    taskDescription = fullTask,
                    sessionId = sessionId
                )
                contextVariables[step.id] = output
                step.resultVariable?.let { contextVariables[it] = output }
                StepResult(step.id, WorkflowStepStatus.COMPLETED, result = output)
            } catch (e: Exception) {
                StepResult(step.id, WorkflowStepStatus.FAILED, error = e.message)
            }

            results.add(result)

            // Emit step completed event
            WorkflowEventBus.emit(WorkflowEvent.StepCompleted(
                workflowId = workflowId,
                sessionId = sessionId,
                stepId = step.id,
                stepIndex = index,
                result = result.result,
                status = result.status
            ))

            // Emit progress event
            WorkflowEventBus.emit(WorkflowEvent.WorkflowProgress(
                workflowId = workflowId,
                sessionId = sessionId,
                completedSteps = index + 1,
                totalSteps = steps.size,
                currentStepId = step.id,
                currentStepIndex = index
            ))

            // Stop pipeline on failure
            if (result.status == WorkflowStepStatus.FAILED) {
                WorkflowEventBus.emit(WorkflowEvent.WorkflowFailed(
                    workflowId = workflowId,
                    sessionId = sessionId,
                    error = "Pipeline failed at step ${step.id}: ${result.error}"
                ))
                return results
            }
        }

        return results
    }

    private suspend fun executeDag(context: Context, steps: List<WorkflowStep>, sessionId: Long): JSONObject {
        // Generate workflow ID and emit start event
        val workflowId = UUID.randomUUID().toString()
        WorkflowEventBus.emit(WorkflowEvent.WorkflowStarted(
            workflowId = workflowId,
            sessionId = sessionId,
            mode = WorkflowMode.DAG,
            totalSteps = steps.size
        ))

        // Execute with progress tracking
        val results = executeDagWithEvents(context, sessionId, steps, workflowId)

        // Emit completion event
        WorkflowEventBus.emit(WorkflowEvent.WorkflowCompleted(
            workflowId = workflowId,
            sessionId = sessionId,
            results = results
        ))

        val text = buildString {
            appendLine("DAG execution completed.")
            appendLine()

            results.forEach { result: StepResult ->
                appendLine("[${result.stepId}] ${result.status}")
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

    private suspend fun executeDagWithEvents(
        context: Context,
        sessionId: Long,
        steps: List<WorkflowStep>,
        workflowId: String
    ): List<StepResult> = coroutineScope {
        // Cycle detection (same as original)
        val adjacency = mutableMapOf<String, MutableList<String>>()
        steps.forEach { step -> adjacency[step.id] = mutableListOf() }
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
            val results = steps.map {
                StepResult(it.id, WorkflowStepStatus.SKIPPED, error = "Cycle detected: ${cyclePath.joinToString(" → ")}")
            }
            WorkflowEventBus.emit(WorkflowEvent.WorkflowFailed(
                workflowId = workflowId,
                sessionId = sessionId,
                error = "Cycle detected: ${cyclePath.joinToString(" → ")}"
            ))
            return@coroutineScope results
        }

        // Execute DAG with events
        val results = java.util.concurrent.ConcurrentHashMap<String, StepResult>()
        val completedSteps = mutableSetOf<String>()
        val failedSteps = mutableSetOf<String>()
        val remaining = steps.toMutableList()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { step ->
                step.dependsOn.all { it in completedSteps } &&
                    step.dependsOn.none { it in failedSteps }
            }

            if (ready.isEmpty()) {
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

            // Emit events for starting parallel steps
            ready.forEach { step ->
                val stepIndex = steps.indexOf(step)
                WorkflowEventBus.emit(WorkflowEvent.StepStarted(
                    workflowId = workflowId,
                    sessionId = sessionId,
                    stepId = step.id,
                    stepIndex = stepIndex,
                    agentType = step.agentType,
                    task = step.task
                ))
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
                        if (depResult != null) {
                            contextVariables[depId] = depResult.result ?: ""
                        }
                    }

                    val fullTask = WorkflowEngine.buildTaskWithContext(
                        task = step.task,
                        dependsOn = step.dependsOn,
                        contextVariables = contextVariables,
                        steps = steps,
                        includeFullOutput = true  // Include full_output for DAG summary steps
                    )

                    try {
                        val output = SubAgent.executeSync(
                            context = context,
                            agentType = step.agentType,
                            taskDescription = fullTask,
                            sessionId = sessionId,
                            promptMode = AgentPrompts.PromptMode.DAG  // DAG mode for parallel execution awareness
                        )
                        StepResult(step.id, WorkflowStepStatus.COMPLETED, result = output)
                    } catch (e: Exception) {
                        StepResult(step.id, WorkflowStepStatus.FAILED, error = e.message)
                    }
                }
            }.awaitAll()

            // Emit completion events and update state
            stepResults.forEach { result ->
                results[result.stepId] = result
                completedSteps.add(result.stepId)
                if (result.status == WorkflowStepStatus.FAILED) {
                    failedSteps.add(result.stepId)
                }
                remaining.removeAll { it.id == result.stepId }

                // Emit step completed event
                val stepIndex = steps.indexOfFirst { it.id == result.stepId }
                WorkflowEventBus.emit(WorkflowEvent.StepCompleted(
                    workflowId = workflowId,
                    sessionId = sessionId,
                    stepId = result.stepId,
                    stepIndex = stepIndex,
                    result = result.result,
                    status = result.status
                ))
            }

            // Emit progress event
            WorkflowEventBus.emit(WorkflowEvent.WorkflowProgress(
                workflowId = workflowId,
                sessionId = sessionId,
                completedSteps = completedSteps.size,
                totalSteps = steps.size,
                currentStepId = null,
                currentStepIndex = completedSteps.size
            ))
        }

        steps.map { results[it.id] ?: StepResult(it.id, WorkflowStepStatus.SKIPPED) }
    }

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

    private suspend fun executeConversational(context: Context, arguments: JSONObject, sessionId: Long): JSONObject {
        val agentA = arguments.optString("agentA")
        val agentB = arguments.optString("agentB")
        val topic = arguments.optString("topic")
        val maxRounds = arguments.optInt("maxRounds", 5)

        // Generate workflow ID and emit start event
        val workflowId = UUID.randomUUID().toString()
        WorkflowEventBus.emit(WorkflowEvent.WorkflowStarted(
            workflowId = workflowId,
            sessionId = sessionId,
            mode = WorkflowMode.CONVERSATIONAL,
            totalSteps = maxRounds * 2,
            topic = topic,
            agentA = agentA,
            agentB = agentB,
            maxRounds = maxRounds
        ))

        val results = WorkflowEngine.executeConversational(
            context = context,
            sessionId = sessionId,
            agentA = agentA,
            agentB = agentB,
            topic = topic,
            maxRounds = maxRounds
        )

        // Emit completion event
        val finalResult = results.lastOrNull()
        if (finalResult?.status == WorkflowStepStatus.COMPLETED) {
            WorkflowEventBus.emit(WorkflowEvent.WorkflowCompleted(
                workflowId = workflowId,
                sessionId = sessionId,
                results = results
            ))
        } else {
            WorkflowEventBus.emit(WorkflowEvent.WorkflowFailed(
                workflowId = workflowId,
                sessionId = sessionId,
                error = finalResult?.error ?: "Conversation failed"
            ))
        }

        val text = buildString {
            appendLine("Conversational workflow completed.")
            appendLine()

            results.forEachIndexed { i, result ->
                appendLine("Round ${i + 1}: [${result.stepId}] ${result.status}")
                if (result.result != null) {
                    appendLine("   ${result.result.take(300)}${if (result.result.length > 300) "..." else ""}")
                }
            }
        }

        return successResponse(text)
    }
}

object ExportSessionLogTool : BuiltinTool(
    name = "export_session_log",
    description = """Export current session log for debugging and analysis. Returns the file path of the exported JSON file.

The exported JSON contains:
- Session metadata (id, title, timestamps)
- All messages (user, assistant, tool)
- Active SubAgent tasks
- Active Workflows with step states

Use this when you need to share the conversation state for debugging or when asked by the user to export logs.""",
    group = "efficiency",
    isReadOnly = true,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "export session log for debugging"
) {

    override val inputSchema = schema {
        // No parameters required - exports current session
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        if (sessionId == null) {
            return errorResponse("export_session_log requires a session context")
        }

        val repository = com.omnichat.data.AppRepository(
            com.omnichat.data.AppDatabase.getDatabase(context)
        )

        // Get session info
        val session = repository.getSessionById(sessionId)

        // Get messages
        val messages = repository.getMessagesBySession(sessionId)

        // Get active tasks and workflows from ViewModel state
        // Note: These are passed via the ChatViewModel context
        val activeTasks = com.omnichat.agent.SubAgent.getActiveTasksForSession(sessionId)
        val activeWorkflows = com.omnichat.agent.WorkflowEngine.getActiveWorkflowsForSession(sessionId)

        // Export
        val filePath = com.omnichat.util.SessionLogExporter.exportSessionLog(
            context = context,
            session = session,
            messages = messages,
            activeTasks = activeTasks,
            activeWorkflows = activeWorkflows
        )

        return if (filePath != null) {
            successResponse(buildString {
                appendLine("Session log exported successfully.")
                appendLine()
                appendLine("File: $filePath")
                appendLine()
                appendLine("Contents:")
                appendLine("- Session: ${session?.title ?: "Unknown"}")
                appendLine("- Messages: ${messages.size}")
                appendLine("- Active tasks: ${activeTasks.size}")
                appendLine("- Active workflows: ${activeWorkflows.size}")
                appendLine()
                appendLine("You can share this file for debugging analysis.")
            })
        } else {
            errorResponse("Failed to export session log")
        }
    }
}