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
    // WHY: 支持 create_agents 中 type 字段，通过注册中心查找预定义 Agent 的 systemPrompt 和 modelHint
    private val agentRegistry: AgentRegistry,
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
) {
    companion object {
        private const val TAG = "OrchestratorTools"

        private val CODE_BLOCK_PATTERN = """```(?:json)?\s*\n?(.*?)\n?\s*```""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val INTENT_PATTERN = """(?:创建|创建以下|spawn|create)\s*(?:以下)?\s*(?:Agent|agent|子Agent|Sub-Agent)""".toRegex(RegexOption.IGNORE_CASE)
        private val AGENT_PATTERN = """[-*\d.]\s*['"]?([\p{L}_][\p{L}\p{N}_]{0,30})['"]?\s*[:—\-]\s*(.+?)$""".toRegex(RegexOption.MULTILINE)
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

        // 检查是否已有同名 Agent 存在，防止重复创建
        val existingTeamState = teamManager.teamState.value
        if (existingTeamState != null) {
            val existingNames = existingTeamState.teammates.keys
            val duplicates = agentSpecs.map { it.name }.filter { it in existingNames }
            if (duplicates.isNotEmpty()) {
                return buildString {
                    appendLine("Error: 以下 Agent 已存在，不要重复创建：${duplicates.joinToString(", ")}")
                    appendLine()
                    appendLine("当前已有的 Agent：${existingNames.joinToString(", ")}")
                    appendLine()
                    appendLine("请使用 assign_task 为已有 Agent 分配新任务，")
                    appendLine("或使用 continue_conversation 继续与已有 Agent 对话。")
                    appendLine("只有在需要全新角色时才调用 create_agents。")
                }
            }
        }

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
        // WHY: 中文名无 word boundary（\b 对 CJK 无效），contains() 对短中文名
        // 产生大量假阳性（如 Agent 名"数"匹配"分析数据"）。改用引号/括号包围匹配：
        // 中文名只有在被引号（'"「」）包围时才算引用该 Agent，避免中文子串误伤。
        val teamState = teamManager.teamState.value
        if (teamState != null) {
            val otherAgentNames = teamState.teammates.keys
                .filter { it != to && it != ORCHESTRATOR_NAME && it.length >= 3 }
            for (otherName in otherAgentNames) {
                val isEnglishName = otherName.all { it.isLetterOrDigit() && it.code < 128 }
                val hasMatch = if (isEnglishName) {
                    // 英文名：使用 \b 整词匹配
                    val pattern = "\\b${java.util.regex.Pattern.quote(otherName)}\\b".toRegex(RegexOption.IGNORE_CASE)
                    pattern.containsMatchIn(task)
                } else {
                    // 中文名：要求被引号/括号包围才算引用该 Agent，
                    // 避免中文子串误伤（如"数"匹配"分析数据"）
                    val quotedPattern = """['"「『(【]${java.util.regex.Pattern.quote(otherName)}['"」』)】]""".toRegex()
                    quotedPattern.containsMatchIn(task)
                }
                
                if (hasMatch) {
                    // WHY: 降级为警告而非阻断。Orchestrator 经常需要在任务描述中引用其他 Agent
                    // 的名字来描述协作流程（如"等 CodeWriter 完成后开始审查"），阻断会导致
                    // Orchestrator 反复改写任务描述浪费工具调用。改为警告，让任务通过但提示注意。
                    Log.w(TAG, "Task for '$to' mentions other agent '$otherName', allowing anyway")
                    // 不再 return Error，允许任务继续分配
                }
            }
        }

        // BUG-026 修复：移除状态检查，直接发送任务。
        // 原实现检查 Agent 是否正在忙碌（STREAMING 状态），但状态检查和消息发送之间
        // 存在时间窗口，Agent 可能在检查后立即变为空闲。
        // 修复：直接发送任务，让 Agent 自己处理并发。MessageBus 的队列机制会确保
        // 任务被正确投递，Agent 的执行循环会按顺序处理收到的任务。
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

        val result = teamManager.sendPeerMessage(
            from = senderName,
            to = to,
            message = message,
            summary = summary,
        )

        // WHY: 当 peer_message 失败时（目标 Agent 不存在、名称错误等），给出明确的
        // 后续行动指引，避免 Agent 收到错误后不知所措而卡住（Bug #Reviewer卡住）。
        // 常见原因：generateUniqueName 给 Agent 加了数字后缀（如 CodeWriter2），
        // 但发送方 LLM 不知道实际名称变了。指引 Agent 把问题上报给 Orchestrator。
        if (result.startsWith("Error:")) {
            return "$result\n\n提示：请使用上方列出的正确 Agent 名称重试。如果不需要传递信息给其他 Agent，请在你的任务结果中直接描述发现的问题，Orchestrator 会负责协调后续工作。"
        }

        return result
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

            // WHY: 解析 type 字段，通过 AgentRegistry 查找预定义 Agent 的 systemPrompt 和 modelHint，
            // 实现"类型化 Agent"——Orchestrator 只需指定 type 即可复用注册中心的完整配置
            val type = agentObj.optString("type", "")
            val agentDef = if (type.isNotEmpty()) agentRegistry.get(type) else null

            // WHY: 解析 spawnMode 字段，支持 SPAWN（全新上下文）和 FORK（继承父级历史）两种模式
            val spawnModeStr = agentObj.optString("spawnMode", "spawn")
            val spawnMode = runCatching { SpawnMode.valueOf(spawnModeStr.uppercase()) }.getOrNull() ?: SpawnMode.SPAWN

            val deps = agentObj.optJSONArray("dependsOn")
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull {
                        arr.optString(it).takeIf { s -> s.isNotEmpty() }
                    }
                }
                ?: emptyList()

            agentSpecs.add(
                AgentSpec(
                    name = name,
                    role = agentObj.optString("role", ""),
                    // type 存在时优先使用注册中心的 systemPrompt，否则用 JSON 中的值
                    systemPrompt = agentDef?.systemPrompt?.takeIf { it.isNotEmpty() }
                        ?: agentObj.optString("systemPrompt", ""),
                    modelConfigId = agentObj.optLong("modelConfigId", -1)
                        .let { if (it == -1L) null else it },
                    modelId = agentObj.optString("modelId").takeIf { it.isNotEmpty() },
                    // type 存在时优先使用注册中心的 modelHint，否则解析 JSON 中的值
                    modelHint = agentDef?.modelHint
                        ?: parseModelHint(agentObj.optString("modelHint")),
                    dependsOn = deps,
                    spawnMode = spawnMode,
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
        val match = CODE_BLOCK_PATTERN.find(text) ?: return null
        val blockContent = match.groupValues[1].trim()
        return if (blockContent.contains("create_agents")) blockContent else null
    }

    /**
     * 从自然语言中提取 Agent 规格。
     */
    private fun extractAgentsFromNaturalLanguage(text: String): OrchestratorDirective? {
        if (!INTENT_PATTERN.containsMatchIn(text)) return null

        // WHY: 收紧正则，避免误匹配普通列表项。
        // 原正则 (\w[\w\s]{0,48}) 会匹配含空格的名称如 "Agent 1"，
        // 且 [:—\-（(] 分隔符过宽会匹配任何冒号/连字符开头的行。
        // 改为：名称限制为驼峰/下划线格式 ([A-Za-z_]\w{0,30})，
        // 分隔符仅限 : — - 三种，$ 确保行尾匹配。
        val matches = AGENT_PATTERN.findAll(text).toList()

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
                dependsOn = emptyList(),
                spawnMode = SpawnMode.SPAWN,
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

                // WHY: 与 parseAgentSpecs 保持一致，支持 type 和 spawnMode 字段
                val type = agentObj.optString("type", "")
                val agentDef = if (type.isNotEmpty()) agentRegistry.get(type) else null

                val spawnModeStr = agentObj.optString("spawnMode", "spawn")
                val spawnMode = runCatching { SpawnMode.valueOf(spawnModeStr.uppercase()) }.getOrNull() ?: SpawnMode.SPAWN

                agents.add(
                    AgentSpec(
                        name = name,
                        role = agentObj.optString("role"),
                        systemPrompt = agentDef?.systemPrompt?.takeIf { it.isNotEmpty() }
                            ?: agentObj.optString("systemPrompt", ""),
                        modelConfigId = agentObj.optLong("modelConfigId", -1)
                            .let { if (it == -1L) null else it },
                        modelId = agentObj.optString("modelId").takeIf { it.isNotEmpty() },
                        modelHint = agentDef?.modelHint
                            ?: parseModelHint(agentObj.optString("modelHint")),
                        dependsOn = agentObj.optJSONArray("dependsOn")
                            ?.let { arr ->
                                (0 until arr.length()).mapNotNull {
                                    arr.optString(it).takeIf { s -> s.isNotEmpty() }
                                }
                            }
                            ?: emptyList(),
                        spawnMode = spawnMode,
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
     * 解析 modelHint 字符串为枚举值。
     * 支持 kebab-case (large-context) 和 SCREAMING_SNAKE_CASE (LARGE_CONTEXT)。
     */
    private fun parseModelHint(hintStr: String): ModelHint? {
        if (hintStr.isBlank()) return null
        val normalized = hintStr.trim().uppercase().replace("-", "_")
        return try {
            ModelHint.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Unknown modelHint: '$hintStr'")
            null
        }
    }

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
