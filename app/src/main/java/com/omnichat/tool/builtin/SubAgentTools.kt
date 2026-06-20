package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.agent.AgentMessage
import com.omnichat.agent.MessageBus
import com.omnichat.agent.SubAgent
import com.omnichat.agent.AgentCallerContext
import com.omnichat.agent.WorkflowEngine
import com.omnichat.agent.WorkflowStep
import com.omnichat.agent.StepResult
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/**
 * SubAgent 相关工具。
 */

object DelegateTaskTool : BuiltinTool(
    name = "delegate_task",
    description = """Delegate a task to a sub-agent for independent execution. The sub-agent runs with its own LLM context and tool access. Returns a taskId.

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

        try {
            val taskId = SubAgent.execute(
                context = context,
                agentType = agentType,
                taskDescription = task,
                sessionId = sessionId
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

MODES:
- pipeline: Sequential execution. Each step receives prior results as context.
- dag: Dependency-based execution. Independent steps run in parallel.
- conversational: Two agents exchange messages until convergence.

WHEN TO USE EACH MODE:
- pipeline: Steps have strict order dependency (A must finish before B starts).
- dag: Steps have partial dependencies, some can run in parallel.
- conversational: Need multi-perspective discussion or debate between two agents.""",
    group = "subagent",
    isReadOnly = false,
    isConcurrencySafe = false,
    requiresSession = true,
    searchHint = "run multi-agent workflow"
) {

    override val inputSchema = schema {
        prop("mode", "string", "Execution mode.") {
            enum("pipeline", "dag", "conversational")
        }
        prop("steps", "array", "Workflow steps (for pipeline/dag modes).") {
            items {
                properties {
                    prop("id", "string", "Step identifier.")
                    prop("agentType", "string", "Agent type for this step.")
                    prop("task", "string", "Task description.")
                    prop("dependsOn", "array", "IDs of steps this depends on (dag only).") { items { } }
                }
            }
        }
        prop("agentA", "string", "First agent type (conversational only).")
        prop("agentB", "string", "Second agent type (conversational only).")
        prop("topic", "string", "Discussion topic (conversational only).")
        prop("maxRounds", "integer", "Maximum rounds (conversational only, default 5).")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val mode = arguments.optString("mode").trim()

        if (mode.isEmpty()) return "mode is required"

        when (mode) {
            "pipeline", "dag" -> {
                val steps = arguments.optJSONArray("steps")
                if (steps == null || steps.length() == 0) return "steps is required for pipeline/dag modes"
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

        return when (mode) {
            "pipeline" -> executePipeline(context, arguments, sessionId)
            "dag" -> executeDag(context, arguments, sessionId)
            "conversational" -> executeConversational(context, arguments, sessionId)
            else -> errorResponse("Invalid mode: $mode")
        }
    }

    private suspend fun executePipeline(context: Context, arguments: JSONObject, sessionId: Long): JSONObject {
        val stepsArray = arguments.optJSONArray("steps") ?: return errorResponse("No steps provided")

        val steps = (0 until stepsArray.length()).map { i ->
            val stepObj = stepsArray.optJSONObject(i) ?: return errorResponse("Invalid step at index $i")
            WorkflowStep(
                id = stepObj.optString("id"),
                agentType = stepObj.optString("agentType"),
                task = stepObj.optString("task"),
                dependsOn = emptyList()
            )
        }

        val results = WorkflowEngine.executePipeline(
            context = context,
            sessionId = sessionId,
            steps = steps
        )

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

    private suspend fun executeDag(context: Context, arguments: JSONObject, sessionId: Long): JSONObject {
        val stepsArray = arguments.optJSONArray("steps") ?: return errorResponse("No steps provided")

        val steps = (0 until stepsArray.length()).map { i ->
            val stepObj = stepsArray.optJSONObject(i) ?: return errorResponse("Invalid step at index $i")
            val dependsOnArray = stepObj.optJSONArray("dependsOn")
            val dependsOn = if (dependsOnArray != null) {
                (0 until dependsOnArray.length()).map { dependsOnArray.optString(it) }
            } else emptyList()

            WorkflowStep(
                id = stepObj.optString("id"),
                agentType = stepObj.optString("agentType"),
                task = stepObj.optString("task"),
                dependsOn = dependsOn
            )
        }

        val results = WorkflowEngine.executeDAG(
            context = context,
            sessionId = sessionId,
            steps = steps
        )

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

    private suspend fun executeConversational(context: Context, arguments: JSONObject, sessionId: Long): JSONObject {
        val agentA = arguments.optString("agentA")
        val agentB = arguments.optString("agentB")
        val topic = arguments.optString("topic")
        val maxRounds = arguments.optInt("maxRounds", 5)

        val results = WorkflowEngine.executeConversational(
            context = context,
            sessionId = sessionId,
            agentA = agentA,
            agentB = agentB,
            topic = topic,
            maxRounds = maxRounds
        )

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