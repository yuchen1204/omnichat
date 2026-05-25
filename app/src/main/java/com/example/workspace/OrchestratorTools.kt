package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════════════
// Orchestrator 工具处理
//
// 对标 Claude Code 的 tools/AgentTool/ 和 tools/SendMessageTool/。
// 负责所有编排工具的 schema 定义、拦截处理和 Orchestrator 输出解析。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Orchestrator 工具处理器。
 *
 * 从 TeamManager 中提取的工具调用处理逻辑。
 * 负责拦截 Orchestrator 专属工具（create_agents, assign_task, continue_conversation）
 * 和协作工具（peer_message），其余委托给 McpRuntimeManager。
 *
 * @property teamManager 团队管理器引用，用于访问团队状态和操作
 * @property repository 数据仓库
 * @property messageBus 消息总线
 * @property mcpRuntimeManager MCP 运行时管理器
 * @property onAgentStatusChanged Agent 状态变更回调
 */
class OrchestratorTools(
    private val teamManager: TeamManager,
    private val repository: AppRepository,
    private val messageBus: MessageBus,
    private val mcpRuntimeManager: com.example.mcp.McpRuntimeManager,
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
) {
    companion object {
        private const val TAG = "OrchestratorTools"
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 工具调用入口
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 处理工具调用。
     *
     * 拦截 Orchestrator 专属工具和协作工具，其余委托给 McpRuntimeManager。
     *
     * @param agentName Agent 名称
     * @param toolName 工具名称
     * @param args 工具参数
     * @param callId 工具调用 ID
     * @return 工具执行结果
     */
    suspend fun handleToolCall(
        agentName: String,
        toolName: String,
        args: JSONObject,
        callId: String,
    ): String {
        // ── 拦截 Orchestrator 专属编排工具 ──
        when (toolName) {
            "create_agents" -> return handleCreateAgentsTool(args)
            "assign_task" -> return handleAssignTaskTool(args)
            "continue_conversation" -> return handleContinueConversationTool(args)
        }

        // ── 拦截协作工具（所有 Agent 可用）──
        when (toolName) {
            "peer_message" -> return handlePeerMessageTool(agentName, args)
        }

        // ── 委托给 MCP 运行时 ──
        onAgentStatusChanged(agentName, AgentStatus.WAITING_TOOL)

        return try {
            val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
            if (serverId == null) {
                Log.e(TAG, "Tool '$toolName' not found in any MCP server")
                "Error: Tool '$toolName' not found"
            } else {
                val result = mcpRuntimeManager.callTool(serverId, toolName, args)
                result?.toString() ?: "No result"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // WHY: CancellationException 必须重新抛出，否则协程取消信号会被吞掉（Fix #8）
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool call failed: $toolName", e)
            "Error: ${e.message}"
        } finally {
            onAgentStatusChanged(agentName, AgentStatus.STREAMING)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 编排工具处理
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 处理 create_agents 工具调用。
     *
     * 从结构化参数中解析 Agent 规格，创建团队成员并等待就绪。
     */
    private suspend fun handleCreateAgentsTool(args: JSONObject): String {
        val taskModeStr = args.optString("taskMode", "claim").lowercase()
        val taskMode = when (taskModeStr) {
            "direct" -> TaskMode.DIRECT
            else -> TaskMode.CLAIM
        }

        val agentsArray = args.optJSONArray("agents")
        if (agentsArray == null || agentsArray.length() == 0) {
            return "Error: agents 数组为空，请提供至少一个 Agent 的定义"
        }

        val agentSpecs = parseAgentSpecs(agentsArray)
        if (agentSpecs.isEmpty()) {
            return "Error: 未能解析出有效的 Agent 定义"
        }

        Log.d(TAG, "create_agents tool: ${agentSpecs.size} agent(s), mode=$taskMode")

        val directive = OrchestratorDirective(agentSpecs, taskMode)
        val originalTask = teamManager.getOrchestratorHistory()
            .lastOrNull { it.role == "user" }?.content ?: ""

        // 检查是否有依赖关系
        val hasDependencies = agentSpecs.any { it.dependsOn.isNotEmpty() }

        if (hasDependencies) {
            val summary = try {
                teamManager.spawnWithDependencies(directive, originalTask)
            } catch (e: Exception) {
                Log.e(TAG, "spawnWithDependencies failed", e)
                return "Error: 依赖流程执行失败: ${e.message}"
            }
            return buildString {
                appendLine("已按依赖顺序创建并执行 ${agentSpecs.size} 个 Agent，全部完成。")
                appendLine("所有 Agent 已自动执行完毕并返回结果，无需再调用 assign_task。")
                appendLine("请直接根据以下结果汇总回复用户：")
                appendLine()
                appendLine(summary)
            }
        }

        // 无依赖：同步创建并等待就绪
        val subAgentNames = teamManager.spawnSubAgentsFromDirective(directive, originalTask)

        if (subAgentNames.isEmpty()) {
            return "Error: 所有 Agent 创建失败"
        }

        // 如果是 claim 模式，创建任务到 TaskManager
        if (taskMode == TaskMode.CLAIM) {
            val teamName = teamManager.getTeamName() ?: ""
            for (spec in agentSpecs) {
                teamManager.createTaskForAgent(teamName, spec, originalTask)
            }
        }

        // 等待所有 Agent 就绪
        teamManager.waitForAgentsReady(subAgentNames)

        return buildString {
            appendLine("已创建 ${subAgentNames.size} 个 Agent，全部就绪：")
            for (name in subAgentNames) {
                appendLine("- $name")
            }
            if (taskMode == TaskMode.DIRECT) {
                appendLine("\n请使用 assign_task 工具为每个 Agent 分配具体任务。")
            } else {
                appendLine("\nAgent 将自动从任务队列认领任务。等待 <task-notification> 通知。")
            }
        }
    }

    /**
     * 处理 assign_task 工具调用。
     */
    private suspend fun handleAssignTaskTool(args: JSONObject): String {
        val to = args.optString("to")
        val task = args.optString("task")
        val context = args.optString("context", "")

        if (to.isEmpty() || task.isEmpty()) {
            return "Error: 'to' 和 'task' 参数必填"
        }

        if (!teamManager.isTeammateAlive(to)) {
            return "Error: Agent '$to' 不存在"
        }

        // 检查任务描述是否包含其他 Agent 的名字（可能导致混乱）
        val teamState = teamManager.teamState.value
        if (teamState != null) {
            val otherAgentNames = teamState.teammates.keys.filter { it != to && it != ORCHESTRATOR_NAME }
            for (otherName in otherAgentNames) {
                if (task.contains(otherName, ignoreCase = true)) {
                    return "Error: 任务描述中包含其他 Agent '$otherName' 的名字，这可能导致 '$to' 混淆。请确保任务只描述 '$to' 需要完成的工作，不要包含其他 Agent 的职责。"
                }
            }
        }

        // 检查 Agent 是否正在忙碌（执行任务中）
        val teammateState = teamState?.teammates?.get(to)
        if (teammateState != null && teammateState.status == AgentStatus.STREAMING) {
            Log.w(TAG, "assign_task to busy agent '$to', task will be queued")
            // 仍然发送任务，但返回警告
            messageBus.send(
                to,
                TeamMessage.TaskAssignment(
                    from = ORCHESTRATOR_NAME,
                    taskId = "",
                    subject = task,
                    description = context,
                )
            )
            return "Warning: Agent '$to' 正在执行任务中，新任务已加入队列，但可能不会立即被处理。建议等待其完成（收到 <task-notification>）后再分配新任务。"
        }

        messageBus.send(
            to,
            TeamMessage.TaskAssignment(
                from = ORCHESTRATOR_NAME,
                taskId = "",
                subject = task,
                description = context,
            )
        )

        Log.d(TAG, "assign_task: '$task' -> '$to'")
        return "已向 $to 分配任务: $task"
    }

    /**
     * 处理 continue_conversation 工具调用。
     */
    private suspend fun handleContinueConversationTool(args: JSONObject): String {
        val to = args.optString("to")
        val message = args.optString("message")

        if (to.isEmpty() || message.isEmpty()) {
            return "Error: 'to' 和 'message' 参数必填"
        }

        return teamManager.continueAgent(to, message)
    }

    /**
     * 处理 peer_message 工具调用。
     */
    private suspend fun handlePeerMessageTool(senderName: String, args: JSONObject): String {
        val to = args.optString("to")
        val message = args.optString("message")
        val summary = args.optString("summary", "")

        if (to.isEmpty() || message.isEmpty()) {
            return "Error: 'to' 和 'message' 参数必填"
        }

        return teamManager.sendPeerMessage(
            from = senderName,
            to = to,
            message = message,
            summary = summary,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Agent 规格解析
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 从 JSON 数组解析 Agent 规格列表。
     */
    fun parseAgentSpecs(agentsArray: JSONArray): List<AgentSpec> {
        val agentSpecs = mutableListOf<AgentSpec>()
        for (i in 0 until agentsArray.length()) {
            val agentObj = agentsArray.optJSONObject(i) ?: continue
            val name = agentObj.optString("name")
            if (name.isEmpty()) continue

            agentSpecs.add(
                AgentSpec(
                    name = name,
                    role = agentObj.optString("role", ""),
                    systemPrompt = agentObj.optString("systemPrompt", ""),
                    modelConfigId = agentObj.optLong("modelConfigId", -1)
                        .let { if (it == -1L) null else it },
                    modelId = agentObj.optString("modelId").takeIf { it.isNotEmpty() },
                    dependsOn = agentObj.optJSONArray("dependsOn")
                        ?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                arr.optString(it).takeIf { s -> s.isNotEmpty() }
                            }
                        }
                        ?: emptyList(),
                )
            )
        }
        return agentSpecs
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Orchestrator 输出解析（保留兼容）
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 增强版 Orchestrator 输出解析（多策略 fallback）。
     *
     * 按优先级尝试以下策略：
     * 1. 标准 `create_agents` JSON 解析
     * 2. Markdown 代码块中的 JSON 提取
     * 3. 自然语言中 Agent 规格提取
     */
    fun parseOrchestratorOutputRobust(output: String): OrchestratorDirective? {
        parseOrchestratorOutput(output)?.let { return it }
        extractJsonFromCodeBlock(output)?.let { jsonStr ->
            parseJsonString(jsonStr)?.let { return it }
        }
        extractAgentsFromNaturalLanguage(output)?.let { return it }
        return null
    }

    /**
     * 从 markdown 代码块中提取 JSON 字符串。
     */
    private fun extractJsonFromCodeBlock(text: String): String? {
        val codeBlockPattern = """```(?:json)?\s*\n?(.*?)\n?\s*```""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(text) ?: return null
        val blockContent = match.groupValues[1].trim()
        return if (blockContent.contains("create_agents")) blockContent else null
    }

    /**
     * 从自然语言中提取 Agent 规格。
     */
    private fun extractAgentsFromNaturalLanguage(text: String): OrchestratorDirective? {
        val intentPattern = """(?:创建|创建以下|spawn|create)\s*(?:以下)?\s*(?:Agent|agent|子Agent|Sub-Agent)""".toRegex(RegexOption.IGNORE_CASE)
        if (!intentPattern.containsMatchIn(text)) return null

        val agentPattern = """[-*\d.]\s*['"]?(\w[\w\s]{0,48})['"]?\s*[:—\-（(]\s*(.+?)[）)]?\s*$""".toRegex(RegexOption.MULTILINE)
        val matches = agentPattern.findAll(text).toList()

        if (matches.isEmpty()) return null

        val agents = matches.map { match ->
            val name = match.groupValues[1].trim()
            val role = match.groupValues[2].trim()
            AgentSpec(
                name = name,
                role = role,
                systemPrompt = "执行任务: $role",
                modelConfigId = null,
                modelId = null,
                dependsOn = emptyList()
            )
        }.filter { it.name.isNotEmpty() }

        return if (agents.isNotEmpty()) {
            Log.d(TAG, "Extracted ${agents.size} agent(s) from natural language")
            OrchestratorDirective(agents, TaskMode.CLAIM)
        } else null
    }

    /**
     * 从 JSON 字符串解析 OrchestratorDirective。
     */
    private fun parseJsonString(jsonStr: String): OrchestratorDirective? {
        return try {
            val json = JSONObject(jsonStr)
            if (json.optString("action") != "create_agents") return null

            val taskModeStr = json.optString("taskMode", "claim").lowercase()
            val taskMode = when {
                taskModeStr == "direct" -> TaskMode.DIRECT
                else -> TaskMode.CLAIM
            }

            val agentsArray = json.optJSONArray("agents") ?: return null
            val agents = mutableListOf<AgentSpec>()

            for (i in 0 until agentsArray.length()) {
                val agentObj = agentsArray.optJSONObject(i) ?: continue
                val name = agentObj.optString("name")
                if (name.isEmpty()) continue

                agents.add(
                    AgentSpec(
                        name = name,
                        role = agentObj.optString("role"),
                        systemPrompt = agentObj.optString("systemPrompt", ""),
                        modelConfigId = agentObj.optLong("modelConfigId", -1)
                            .let { if (it == -1L) null else it },
                        modelId = agentObj.optString("modelId").takeIf { it.isNotEmpty() },
                        dependsOn = agentObj.optJSONArray("dependsOn")
                            ?.let { arr ->
                                (0 until arr.length()).mapNotNull {
                                    arr.optString(it).takeIf { s -> s.isNotEmpty() }
                                }
                            }
                            ?: emptyList(),
                    )
                )
            }

            if (agents.isEmpty()) null else OrchestratorDirective(agents, taskMode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON string as directive", e)
            null
        }
    }

    /**
     * 解析 Orchestrator 输出，提取 Sub-Agent 创建指令。
     */
    fun parseOrchestratorOutput(output: String): OrchestratorDirective? {
        val actionPattern = """['"]?action['"]?\s*:\s*['"]create_agents['"]""".toRegex()
        val matchResult = actionPattern.find(output) ?: return null

        val startIndex = output.lastIndexOf("{", matchResult.range.first)
        if (startIndex == -1) return null

        val jsonStr = extractJsonObject(output, startIndex) ?: return null
        return parseJsonString(jsonStr)
    }

    /**
     * 解析任务分配 JSON。
     */
    fun parseTaskAssignments(
        content: String,
        validAgents: List<String>,
    ): Map<String, TaskInfo> {
        val result = mutableMapOf<String, TaskInfo>()

        val assignPattern = """"action"\s*:\s*"assign_task"""".toRegex()
        for (match in assignPattern.findAll(content)) {
            val startIndex = content.lastIndexOf("{", match.range.first)
            if (startIndex == -1) continue
            extractJsonObject(content, startIndex)?.let { jsonStr ->
                try {
                    val json = JSONObject(jsonStr)
                    val to = json.optString("to")
                    if (to.isNotEmpty() && to in validAgents) {
                        result[to] = TaskInfo(
                            task = json.optString("task", "执行任务"),
                            context = json.optString("context", "")
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse assign_task JSON", e)
                }
            }
        }

        val naturalPattern = """向['"\s]*(\w+)['"\s].*?(?:：|:)\s*\{""".toRegex()
        for (match in naturalPattern.findAll(content)) {
            val agentName = match.groupValues[1]
            if (agentName !in validAgents || agentName in result) continue
            val jsonStart = content.indexOf("{", match.range.first)
            if (jsonStart == -1) continue
            extractJsonObject(content, jsonStart)?.let { jsonStr ->
                try {
                    val json = JSONObject(jsonStr)
                    if (json.has("task")) {
                        result[agentName] = TaskInfo(
                            task = json.optString("task", "执行任务"),
                            context = json.optString("context", "")
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse natural task assignment", e)
                }
            }
        }

        return result
    }

    data class TaskInfo(val task: String, val context: String)

    /**
     * 从字符串中提取完整的 JSON 对象。
     */
    private fun extractJsonObject(text: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var escapeNext = false

        for (i in startIndex until text.length) {
            val char = text[i]

            if (escapeNext) {
                escapeNext = false
                continue
            }

            when (char) {
                '\\' -> if (inString) escapeNext = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, i + 1)
                    }
                }
            }
        }

        return null
    }
}
