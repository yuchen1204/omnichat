package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import com.example.data.ModelConfig
import com.example.data.WorkspaceMessage
import com.example.data.WorkspaceTeam
import com.example.data.AgentInstance
import com.example.mcp.McpRuntimeManager
import com.example.workspace.lifecycle.AgentLifecycleManager
import com.example.workspace.mailbox.MailboxService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.hooks.HookManager
import com.example.hooks.WorkspaceSandboxHook
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

// ═══════════════════════════════════════════════════════════════════════════════
// TeamManager — 团队管理器（薄门面）
//
// 职责：管理团队生命周期和持久化，委托给 AgentTool（Agent 调度）、
// AgentRegistry（Agent 追踪）和 MailboxService（Agent 间通信）。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 团队管理器。
 *
 * 负责团队的创建、Agent 的调度和生命周期管理。
 * 通过 [AgentTool] 实现 Orchestrator 的子 Agent 委派。
 *
 * @property repository 数据仓库
 * @property mcpRuntimeManager MCP 运行时管理器
 * @property parentScope 父协程作用域
 * @property config 工作区配置
 * @property callbacks 团队事件回调
 */
class TeamManager(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val parentScope: CoroutineScope,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val callbacks: TeamCallbacks,
) {
    companion object {
        private const val TAG = "TeamManager"
    }

    // ─── 新抽象 ───

    val agentRegistry = AgentRegistry()
    val mailboxService = MailboxService(repository)
    private var teamDbId: Long = 0L

    // ─── Sub-Agent 管理（通过 onSubAgentLaunched 追踪）───

    private var subAgentScope = createSubAgentScope()

    private fun createSubAgentScope() = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob()
    )
    private val subAgentJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // ─── AgentTool（Orchestrator 用于委派子 Agent）───

    private var agentTool: AgentTool? = null
    private var orchestratorContext: AgentContext? = null
    private var sandboxPath: String? = null

    // ─── 团队状态（StateFlow 驱动 UI）───

    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    // ─── 工作区完成标记 ──

    private val isCompleted = AtomicBoolean(false)

    // ─── 工作区开始时间（用于计算总耗时）───
    private var workspaceStartTimeMs: Long = 0L

    // ─── 沙盒 Hook 引用（用于注销）───
    private var sandboxHook: WorkspaceSandboxHook? = null

    @Volatile private var cachedAvailableModelsStr: String = ""

    private val createTeamMutex = kotlinx.coroutines.sync.Mutex()

    // ─── 跨会话记忆 ──
    private var crossSessionMemoryText: String = ""

    // ─── AgentTool 访问器 ──

    fun getAgentTool(): AgentTool? = agentTool
    fun getOrchestratorContext(): AgentContext? = orchestratorContext
    fun getSandboxPath(): String? = sandboxPath

    // ═══════════════════════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 创建团队。
     *
     * 初始化团队状态，创建 AgentTool，并注册 Orchestrator 到 [AgentRegistry]。
     */
    suspend fun createTeam(
        teamName: String,
        orchestratorConfig: ModelConfig,
        orchestratorOverrideModelId: String? = null,
        sandboxPath: String? = null,
    ): TeamState = createTeamMutex.withLock {
        require(_teamState.value == null) { "已有活跃团队，请先删除当前团队" }
        require(teamName.isNotBlank()) { "团队名称不能为空" }

        Log.d(TAG, "Creating team: $teamName")

        crossSessionMemoryText = loadCrossSessionMemory()

        // Persist team to DB
        teamDbId = repository.insertWorkspaceTeam(WorkspaceTeam(
            teamName = teamName,
            mode = "orchestrator",
            orchestratorModelConfigId = orchestratorConfig.id,
            sandboxPath = sandboxPath,
        ))

        // Create orchestrator agent instance in DB
        val orchestratorInstanceId = repository.insertAgentInstance(
            AgentInstance(
                workspaceSessionId = teamDbId,
                agentName = ORCHESTRATOR_NAME,
                agentType = "orchestrator",
                isOrchestrator = true,
                status = "idle",
                modelConfigId = orchestratorConfig.id,
                overrideModelId = orchestratorOverrideModelId,
                teamId = teamDbId,
            )
        )

        val identity = TeammateIdentity(
            agentId = "${ORCHESTRATOR_NAME}@${teamName}",
            agentName = ORCHESTRATOR_NAME,
            teamName = teamName,
        )

        val lifecycle = AgentLifecycleManager(identity) { status ->
            callbacks.onAgentStatusChanged(ORCHESTRATOR_NAME, status)
        }

        agentRegistry.register(AgentRegistry.AgentEntry(
            identity = identity,
            lifecycle = lifecycle,
            instanceId = orchestratorInstanceId,
        ))

        // Wire AgentTool for orchestrator's sub-agent spawning
        this.sandboxPath = sandboxPath
        val agentDefinitions = loadAgentDefinitions(repository)
        this.agentTool = AgentTool(
            mcpRuntimeManager = mcpRuntimeManager,
            agentRegistry = agentRegistry,
            mailboxService = mailboxService,
            agentDefinitions = agentDefinitions,
            onSubAgentCreated = { name, description ->
                // Add SubAgent to active list
                _teamState.update { state ->
                    state?.copy(
                        activeSubAgents = state.activeSubAgents + SubAgentInfo(
                            name = name,
                            description = description,
                            status = AgentStatus.IDLE,
                        )
                    )
                }
                callbacks.onAgentCreated(name, false)
                Log.d(TAG, "SubAgent created: $name ($description)")
            },
            onSubAgentStreamChunk = { name, chunk ->
                // Forward to stream chunk callback for live display
                callbacks.onStreamChunk(name, chunk)
            },
            onSubAgentCompleted = { name, messages ->
                // Remove SubAgent from active list
                _teamState.update { state ->
                    state?.copy(
                        activeSubAgents = state.activeSubAgents.filter { it.name != name }
                    )
                }
                Log.d(TAG, "SubAgent completed: $name")
            },
            onTaskNotification = { notification ->
                // Inject notification as user message into orchestrator's pending queue
                val orchestratorEntry = agentRegistry.get("${ORCHESTRATOR_NAME}@${teamName}")
                orchestratorEntry?.runner?.queuePendingMessage(AgentMessage(
                    role = "user",
                    content = buildTaskNotificationText(notification),
                    source = "task-notification",
                ))
                // Wake orchestrator: if it's not currently streaming, trigger a new runTurn
                // to process the notification from mailbox
                val orchestratorRunner = orchestratorEntry?.runner
                if (orchestratorRunner != null && !orchestratorRunner.isStreaming()) {
                    WorkspaceScopes.auxiliary.launch {
                        try {
                            orchestratorRunner.runTurn(userMessage = null, source = "task-notification-wake")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to wake orchestrator for notification", e)
                        }
                    }
                }
            },
            onSubAgentLaunched = { name, scope, job ->
                subAgentJobs[name] = job
                Log.d(TAG, "SubAgent scope registered: $name")
            },
        )

        val state = TeamState(
            teamName = teamName,
            orchestratorConfig = orchestratorConfig,
            sandboxPath = sandboxPath,
        )
        if (!_teamState.compareAndSet(null, state)) {
            agentRegistry.clear()
            error("并发创建团队冲突，请重试")
        }

        // 注册沙盒 Hook（限制文件操作范围）
        if (!sandboxPath.isNullOrBlank()) {
            val hook = WorkspaceSandboxHook(sandboxPath)
            HookManager.registerMcpHook(hook)
            sandboxHook = hook
            Log.d(TAG, "Registered workspace sandbox hook for: $sandboxPath")
        }

        callbacks.onAgentCreated(ORCHESTRATOR_NAME, true)
        Log.d(TAG, "Team '$teamName' created with Orchestrator")

        state
    }

    /**
     * 启动 Orchestrator 执行循环。
     */
    suspend fun startExecution(userTask: String, imagePath: String? = null) {
        val state = _teamState.value ?: error("无活跃团队，请先调用 createTeam")
        val orchestratorId = "${ORCHESTRATOR_NAME}@${state.teamName}"
        val entry = agentRegistry.get(orchestratorId) ?: error("Orchestrator 未注册")

        Log.d(TAG, "Starting orchestrator execution with task: ${userTask.take(80)}...")

        workspaceStartTimeMs = System.currentTimeMillis()
        _teamState.update { it?.copy(isRunning = true) }

        // Load agent definitions and build system prompt
        val agentDefinitions = loadAgentDefinitions(repository)
        val cachedModels = run {
            if (cachedAvailableModelsStr.isEmpty()) {
                val allConfigs = repository.getAllConfigs()
                val allFetchedModels = repository.getAllFetchedModels()
                cachedAvailableModelsStr = buildAvailableModelsString(allConfigs, allFetchedModels)
            }
            cachedAvailableModelsStr
        }

        // Build orchestrator context with agentInstanceId for MailboxService routing
        val ctx = AgentContext(
            agentName = ORCHESTRATOR_NAME,
            isOrchestrator = true,
            systemPrompt = buildOrchestratorSystemPrompt(agentDefinitions, state.sandboxPath),
            modelConfig = state.orchestratorConfig,
            teamName = state.teamName,
            messages = ArrayList(),
            agentInstanceId = entry.instanceId,
        )

        // Store orchestrator context for AgentTool access
        this.orchestratorContext = ctx

        val runner = AgentRunner(
            context = ctx,
            mcpRuntimeManager = mcpRuntimeManager,
            lifecycleManager = entry.lifecycle,
            mailboxService = mailboxService,
            crossSessionMemory = crossSessionMemoryText,
            availableModels = cachedModels,
            sandboxPath = state.sandboxPath,
            onStreamChunk = callbacks.onStreamChunk,
            onMessageAdded = callbacks.onMessageAdded,
            onToolCall = { agentName, toolName, args, callId ->
                // Route tool calls: agent tool -> AgentTool, others -> MCP
                if (toolName == AgentTool.TOOL_NAME && agentTool != null) {
                    agentTool!!.call(args, orchestratorContext!!, sandboxPath ?: "").toString()
                } else {
                    val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
                    if (serverId == null) "Error: Tool '$toolName' not found"
                    else mcpRuntimeManager.callTool(serverId, toolName, args)?.toString() ?: "No result"
                }
            },
            persistMessage = { message ->
                WorkspaceScopes.auxiliary.launch {
                    try {
                        repository.insertWorkspaceMessage(
                            WorkspaceMessage(
                                workspaceSessionId = teamDbId,
                                agentInstanceId = entry.instanceId,
                                role = message.role,
                                content = message.content,
                                toolCallId = message.toolCallId,
                                toolCallsJson = message.toolCallsJson,
                                isIntervention = message.isIntervention,
                                imagePath = message.imagePath,
                                timestamp = message.timestamp,
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist message", e)
                    }
                }
            },
        )

        // Update registry with runner
        agentRegistry.register(AgentRegistry.AgentEntry(
            identity = entry.identity,
            lifecycle = entry.lifecycle,
            instanceId = entry.instanceId,
            runner = runner,
        ))

        entry.lifecycle.transitionTo(AgentStatus.STREAMING)
        try {
            runner.runTurn(userMessage = userTask, imagePath = imagePath)
            entry.lifecycle.transitionTo(AgentStatus.COMPLETED)
            triggerWorkspaceComplete(runner)
        } catch (e: Exception) {
            Log.e(TAG, "Orchestrator execution failed", e)
            callbacks.onError(e.message ?: "Unknown error")
            entry.lifecycle.transitionTo(AgentStatus.ERROR)
        }
    }

    /**
     * 删除团队。
     */
    suspend fun deleteTeam() {
        val state = _teamState.value ?: return

        Log.d(TAG, "Deleting team '${state.teamName}'")

        // Cancel all async sub-agent scopes (prevents leaked coroutines after team deletion)
        agentTool?.cancelAllAsyncAgents()

        // 取消所有 Sub-Agent 协程
        subAgentScope.cancel()
        subAgentScope = createSubAgentScope()

        // 注销沙盒 Hook
        sandboxHook?.let {
            HookManager.unregisterMcpHook(it)
            Log.d(TAG, "Unregistered workspace sandbox hook")
        }
        sandboxHook = null

        // 从数据库删除团队（级联清理 AgentInstance、MailboxMessage、AgentStateSnapshot）
        if (teamDbId > 0) {
            try {
                repository.deleteWorkspaceTeam(teamDbId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete workspace team from DB", e)
            }
        }

        isCompleted.set(false)
        cachedAvailableModelsStr = ""
        agentTool = null
        orchestratorContext = null
        sandboxPath = null
        teamDbId = 0L
        agentRegistry.clear()
        subAgentJobs.clear()
        _teamState.value = null

        Log.d(TAG, "Team '${state.teamName}' deleted")
    }

    /**
     * 获取指定 Agent 的对话历史。
     */
    fun getAgentHistory(agentName: String): List<AgentMessage> {
        val state = _teamState.value ?: return emptyList()
        val agentId = "${agentName}@${state.teamName}"
        return agentRegistry.get(agentId)?.runner?.getHistory() ?: emptyList()
    }

    // ─── Runner 访问器（供 SendMessageTool 等跨组件调用）───

    /** Get a specific agent's runner by name */
    fun getRunner(agentName: String): AgentRunner? {
        val state = _teamState.value ?: return null
        val agentId = "${agentName}@${state.teamName}"
        return agentRegistry.get(agentId)?.runner
    }

    /** Get all active runners */
    fun getAllRunners(): Map<String, AgentRunner> {
        return agentRegistry.getActiveAgents()
            .filter { it.runner != null }
            .associate { it.identity.agentName to it.runner!! }
    }

    /**
     * 检测完成标记。
     */
    fun isCompletionMarker(text: String): Boolean = text.contains(COMPLETION_MARKER)

    // ═══════════════════════════════════════════════════════════════════════════════
    // 工作区完成
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 触发工作区完成。
     */
    private suspend fun triggerWorkspaceComplete(runner: AgentRunner) {
        if (!isCompleted.compareAndSet(false, true)) return

        Log.d(TAG, "Triggering workspace complete")
        callbacks.onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.COMPLETED)

        val orchestratorMessages = runner.getHistory()
        val subAgentMessages = mutableMapOf<String, List<AgentMessage>>()
        val subAgentIdentities = mutableMapOf<String, TeammateIdentity>()

        _teamState.update { s -> s?.copy(isCompleted = true, isRunning = false) }

        // 先捕获 sub-agent 消息历史，再清理
        val nonOrchestratorEntries = agentRegistry.getActiveAgents()
            .filter { it.identity.agentName != ORCHESTRATOR_NAME }

        for (entry in nonOrchestratorEntries) {
            subAgentMessages[entry.identity.agentName] = entry.runner?.getHistory() ?: emptyList()
            subAgentIdentities[entry.identity.agentName] = entry.identity
        }

        val agentCountBeforeCleanup = agentRegistry.size()

        // 清理 sub-agent
        for (entry in nonOrchestratorEntries) {
            entry.runner?.dispose()
            agentRegistry.unregister(entry.identity.agentId)
        }

        val snapshot = TeamCompletionSnapshot(
            orchestratorMessages = orchestratorMessages,
            subAgentMessages = subAgentMessages,
            subAgentIdentities = subAgentIdentities,
        )
        callbacks.onWorkspaceComplete(snapshot)

        // 触发工作区完成 Hook
        val teamName = _teamState.value?.teamName ?: ""
        val totalDurationMs = if (workspaceStartTimeMs > 0) System.currentTimeMillis() - workspaceStartTimeMs else 0L
        val orchestratorSummary = orchestratorMessages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content?.take(500) ?: ""
        WorkspaceScopes.auxiliary.launch {
            try {
                com.example.hooks.HookManager.dispatchWorkspaceComplete(
                    teamName = teamName,
                    agentCount = agentCountBeforeCleanup,
                    totalDurationMs = totalDurationMs,
                    orchestratorSummary = orchestratorSummary,
                )
            } catch (e: Exception) {
                Log.w(TAG, "dispatchWorkspaceComplete failed (non-fatal)", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 内部工具方法
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 加载跨会话记忆。
     */
    private suspend fun loadCrossSessionMemory(): String {
        if (!config.enableCrossSessionMemory) return ""
        return try {
            val memories = repository.getAllMemories()
                .sortedByDescending { it.confidence }
                .take(config.memoryInjectLimit)
            if (memories.isEmpty()) ""
            else memories.joinToString("\n") { it.content }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cross-session memory", e)
            ""
        }
    }

    /**
     * Build the notification text for a completed background task.
     * Mirrors Claude Code's <task-notification> XML format.
     */
    private fun buildTaskNotificationText(notification: TaskNotification): String {
        return buildString {
            appendLine("<task-notification>")
            appendLine("<task-id>${notification.taskId}</task-id>")
            appendLine("<status>${notification.status.name.lowercase()}</status>")
            if (notification.result != null) {
                appendLine("<result>${notification.result}</result>")
            }
            if (notification.error != null) {
                appendLine("<error>${notification.error}</error>")
            }
            appendLine("<usage>")
            appendLine("  <total_tokens>${notification.totalTokens}</total_tokens>")
            appendLine("  <tool_uses>${notification.toolUseCount}</tool_uses>")
            appendLine("  <duration_ms>${notification.durationMs}</duration_ms>")
            appendLine("</usage>")
            appendLine("</task-notification>")
        }
    }

    /**
     * 构建包含模型能力标注的可用模型列表字符串。
     */
    private fun buildAvailableModelsString(
        allConfigs: List<com.example.data.ModelConfig>,
        allFetchedModels: List<com.example.data.FetchedModel>
    ): String {
        val modelsByProvider = allFetchedModels.groupBy { it.providerId }
        return buildString {
            for (config in allConfigs) {
                appendLine("- Provider: ${config.name} (modelConfigId: ${config.id})")
                val models = modelsByProvider[config.id] ?: emptyList()
                if (models.isEmpty()) {
                    appendLine("  - ${config.selectedModelId} (默认)")
                } else {
                    for (m in models) {
                        val tags = mutableListOf<String>()
                        if (m.hasThinking) tags.add("reasoning")
                        if (m.hasVision) tags.add("vision")
                        if (m.hasToolUse) tags.add("tools")
                        if (m.contextSize.isNotBlank()) tags.add("context:${m.contextSize}")
                        val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
                        appendLine("  - ${m.modelId}$tagStr")
                    }
                }
            }
            appendLine()
            appendLine("快捷模型选择：在 agent 工具中使用 model 字段指定子 Agent 模型：")
            appendLine("- modelHint=\"reasoning\" -> 推理/编程任务，选择支持 thinking 的模型")
            appendLine("- modelHint=\"vision\" -> 图像理解任务，选择支持视觉的模型")
            appendLine("- modelHint=\"fast\" -> 快速响应，选择轻量模型")
            appendLine("- modelHint=\"large-context\" -> 长文档处理，选择最大上下文窗口")
            appendLine("- modelHint=\"tools\" -> 需要工具调用，选择支持 tool use 的模型")
        }
    }
}
