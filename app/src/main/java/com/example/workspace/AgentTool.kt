package com.example.workspace

import android.util.Log
import com.example.mcp.McpRuntimeManager
import com.example.mcp.ToolSchemaDsl.schema
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Claude Code-style agent tool.
 *
 * Spawns a [SubAgent] with isolated context, executes it synchronously,
 * and returns the last assistant message as the tool result.
 *
 * Key design:
 * - SubAgent gets a fresh `messages = ArrayList()` (not shared with parent)
 * - SubAgent gets `disallowedTools = setOf("agent")` to prevent recursion
 * - SubAgent's `onToolCall` routes directly to [McpRuntimeManager.callTool]
 * - SubAgent's `onStreamChunk` is a no-op (streams not shown to user)
 * - Errors are caught and returned as result text (not thrown)
 */
class AgentTool(
    private val mcpRuntimeManager: McpRuntimeManager,
    // 支持 AgentDefinition 注入，使 subagent_type 参数可解析到对应的 Agent 定义
    private val agentDefinitions: List<AgentDefinition> = BuiltInAgents.ALL,
    private val onSubAgentCreated: (name: String, description: String) -> Unit = { _, _ -> },
    private val onSubAgentStreamChunk: (name: String, chunk: String) -> Unit = { _, _ -> },
    private val onSubAgentCompleted: (name: String, messages: List<AgentMessage>) -> Unit = { _, _ -> },
    /** Callback to inject a notification message into the orchestrator's queue */
    private val onTaskNotification: (notification: TaskNotification) -> Unit = { _ -> },
) {
    companion object {
        private const val TAG = "AgentTool"
        const val TOOL_NAME = "agent"

        /**
         * Tool schema in McpTool inputSchema format.
         *
         * Parameters:
         * - description (required): short description of what the SubAgent will do
         * - prompt (required): full task prompt for the SubAgent
         * - subagent_type (optional): agent type key, resolved via AgentDefinition registry
         * - model (optional): override model ID for the SubAgent
         */
        val TOOL_SCHEMA = schema {
            prop("description", "string", "Short description of what the SubAgent will do.")
            prop("prompt", "string", "Full task prompt for the SubAgent.")
            prop("subagent_type", "string", "Optional agent type (e.g., 'explore', 'plan', 'verification', 'custom:my-agent'). Defaults to general-purpose.")
            prop("model", "string", "Optional model ID to override the SubAgent's model.")
            prop("run_in_background", "boolean", "Set to true to run this agent in the background. You will be notified when it completes.")
            required("description", "prompt")
        }
    }

    /**
     * Execute the agent tool.
     *
     * Creates an isolated SubAgent context, runs it with the given prompt,
     * and returns the last assistant message as the result.
     *
     * @param args JSON arguments containing `description`, `prompt`, and optionally `model`
     * @param parentContext the calling agent's context (used to inherit model config)
     * @param sandboxPath optional sandbox path for file operations
     * @return a JSON object with `content` (string) and optionally `isError` (boolean)
     */
    suspend fun call(
        args: JSONObject,
        parentContext: AgentContext,
        sandboxPath: String,
    ): JSONObject {
        val description = args.optString("description", "")
        val prompt = args.optString("prompt", "")
        val modelOverride = args.optString("model", "").ifEmpty { null }
        // 提取 subagent_type，用于查找对应的 AgentDefinition
        val subagentType = args.optString("subagent_type", "").ifEmpty { null }
        val runInBackground = args.optBoolean("run_in_background", false)

        if (prompt.isEmpty()) {
            return errorResult("Missing required parameter: prompt")
        }

        return try {
            val agentDef = resolveAgentDefinition(subagentType)
            if (runInBackground || agentDef.background) {
                runAsyncSubAgent(description, prompt, modelOverride, parentContext, sandboxPath, agentDef)
            } else {
                runSubAgent(description, prompt, modelOverride, parentContext, sandboxPath, subagentType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SubAgent execution failed", e)
            errorResult("SubAgent execution failed: ${e.message}")
        }
    }

    /**
     * Resolve the AgentDefinition for the given subagent_type string.
     * Falls back to GENERAL_PURPOSE when type is blank; throws if type is unknown.
     */
    private fun resolveAgentDefinition(subagentType: String?): AgentDefinition {
        if (subagentType.isNullOrBlank()) return BuiltInAgents.GENERAL_PURPOSE
        return agentDefinitions.find { it.agentType == subagentType }
            ?: throw IllegalArgumentException(
                "Agent type '$subagentType' not found. Available: ${agentDefinitions.map { it.agentType }}"
            )
    }

    /**
     * Build the set of disallowed tools: always includes "agent" (prevent recursion)
     * plus any tools listed in the AgentDefinition.
     */
    private fun buildDisallowedTools(agentDef: AgentDefinition): Set<String> {
        val base = mutableSetOf(TOOL_NAME) // Always prevent recursion
        agentDef.disallowedTools?.forEach { base.add(it) }
        return base
    }

    /**
     * Create an isolated SubAgent context and run it.
     *
     * Uses AgentDefinition to configure system prompt, disallowed tools,
     * and max tool iterations. Explicit model override > definition override > parent's model.
     */
    private suspend fun runSubAgent(
        description: String,
        prompt: String,
        modelOverride: String?,
        parentContext: AgentContext,
        sandboxPath: String,
        subagentType: String? = null,
    ): JSONObject {
        // 根据 subagent_type 解析 AgentDefinition，获取系统提示词、工具限制等配置
        val agentDef = resolveAgentDefinition(subagentType)
        val subAgentName = "SubAgent-${java.util.UUID.randomUUID().toString().take(8)}"

        // 优先级：显式 model 参数 > AgentDefinition 的 overrideModelId > 父级 model
        val effectiveModelId = modelOverride ?: agentDef.overrideModelId

        val subAgentContext = AgentContext(
            agentName = subAgentName,
            isOrchestrator = false,
            systemPrompt = agentDef.systemPrompt.ifEmpty { buildSubAgentPrompt(description, sandboxPath) },
            modelConfig = parentContext.modelConfig,
            overrideModelId = effectiveModelId,
            teamName = parentContext.teamName,
            messages = ArrayList(),
            agentDefinition = agentDef,
        )

        // 合并禁止工具列表：递归防护 + AgentDefinition 配置
        val disallowedTools = buildDisallowedTools(agentDef)

        onSubAgentCreated(subAgentName, description)

        val runner = AgentRunner(
            context = subAgentContext,
            mcpRuntimeManager = mcpRuntimeManager,
            disallowedTools = disallowedTools,
            sandboxPath = sandboxPath,
            // 使用 AgentDefinition 中的 maxTurns 限制工具调用次数
            maxToolIterations = agentDef.maxTurns,
            onStreamChunk = { _, chunk ->
                onSubAgentStreamChunk(subAgentName, chunk)
            },
            onToolCall = { _, toolName, toolArgs, _ ->
                val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
                if (serverId == null) {
                    Log.e(TAG, "Tool '$toolName' not found in any MCP server")
                    "Error: Tool '$toolName' not found"
                } else {
                    val result = mcpRuntimeManager.callTool(serverId, toolName, toolArgs)
                    result?.toString() ?: "No result"
                }
            },
        )

        try {
            runner.runTurn(userMessage = prompt, source = "agent-tool")
        } finally {
            runner.dispose()
        }

        onSubAgentCompleted(subAgentName, subAgentContext.messages.toList())

        val lastAssistantMsg = subAgentContext.messages
            .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }

        val resultText = lastAssistantMsg?.content
            ?: "(SubAgent completed with no output)"

        return successResult(resultText)
    }

    /**
     * Run a sub-agent asynchronously in the background.
     * Returns immediately with a task ID; result delivered via notification.
     */
    private suspend fun runAsyncSubAgent(
        description: String,
        prompt: String,
        modelOverride: String?,
        parentContext: AgentContext,
        sandboxPath: String,
        agentDef: AgentDefinition,
    ): JSONObject {
        val subAgentName = "SubAgent-${java.util.UUID.randomUUID().toString().take(8)}"
        val effectiveModelId = modelOverride ?: agentDef.overrideModelId

        val subAgentContext = AgentContext(
            agentName = subAgentName,
            isOrchestrator = false,
            systemPrompt = agentDef.systemPrompt.ifEmpty { buildSubAgentPrompt(description, sandboxPath) },
            modelConfig = parentContext.modelConfig,
            overrideModelId = effectiveModelId,
            teamName = parentContext.teamName,
            messages = ArrayList(),
            agentDefinition = agentDef,
        )

        val disallowedTools = buildDisallowedTools(agentDef)

        onSubAgentCreated(subAgentName, description)

        // Launch in background scope — NOT blocking the orchestrator
        WorkspaceScopes.auxiliary.launch {
            val runner = AgentRunner(
                context = subAgentContext,
                mcpRuntimeManager = mcpRuntimeManager,
                disallowedTools = disallowedTools,
                sandboxPath = sandboxPath,
                maxToolIterations = agentDef.maxTurns,
                isAsync = true,
                onStreamChunk = { _, chunk -> onSubAgentStreamChunk(subAgentName, chunk) },
                onToolCall = { _, toolName, toolArgs, _ ->
                    val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
                    if (serverId == null) "Error: Tool '$toolName' not found"
                    else mcpRuntimeManager.callTool(serverId, toolName, toolArgs)?.toString() ?: "No result"
                },
            )

            try {
                runner.runTurn(userMessage = prompt, source = "agent-tool")
                val lastMsg = subAgentContext.messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                onTaskNotification(TaskNotification(
                    taskId = subAgentName,
                    agentName = subAgentName,
                    status = TaskNotificationStatus.COMPLETED,
                    result = lastMsg?.content,
                    totalTokens = runner.getUsageStats().totalTokens,
                    toolUseCount = runner.getUsageStats().toolUseCount,
                    durationMs = runner.getUsageStats().durationMs,
                ))
            } catch (e: Exception) {
                onTaskNotification(TaskNotification(
                    taskId = subAgentName,
                    agentName = subAgentName,
                    status = TaskNotificationStatus.FAILED,
                    error = e.message,
                ))
            } finally {
                runner.dispose()
                onSubAgentCompleted(subAgentName, subAgentContext.messages.toList())
            }
        }

        return JSONObject().apply {
            put("status", "async_launched")
            put("agentId", subAgentName)
            put("description", description)
        }
    }

    /**
     * Generate an English system prompt for the SubAgent.
     */
    private fun buildSubAgentPrompt(description: String, sandboxPath: String): String {
        return buildString {
            appendLine("You are a focused sub-agent executing a specific task.")
            appendLine("Task: $description")
            appendLine()
            appendLine("Instructions:")
            appendLine("- Execute the task as described in the user prompt.")
            appendLine("- Use available tools to complete the work.")
            appendLine("- Report your findings clearly and concisely.")
            if (sandboxPath.isNotBlank()) {
                appendLine("- File operations are restricted to: $sandboxPath")
            }
        }
    }

    /**
     * Build a success result JSON object.
     */
    private fun successResult(content: String): JSONObject = JSONObject().apply {
        put("content", content)
    }

    /**
     * Build an error result JSON object.
     */
    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
