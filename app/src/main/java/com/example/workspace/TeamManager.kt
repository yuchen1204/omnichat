package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
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
// 职责：管理 AgentRunner 的创建和生命周期，
// 通过 AgentTool 实现 Orchestrator 的子 Agent 委派。
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
 * @property onAgentCreated Agent 创建回调
 * @property onStreamChunk 流式 chunk 回调
 * @property onAgentStatusChanged Agent 状态变更回调
 * @property onWorkspaceComplete 工作区完成回调
 * @property onError 错误回调
 */
class TeamManager(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val parentScope: CoroutineScope,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val onAgentCreated: (agentName: String, isOrchestrator: Boolean) -> Unit,
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
    private val onWorkspaceComplete: (snapshot: TeamCompletionSnapshot) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    companion object {
        private const val TAG = "TeamManager"
    }

    // ─── Runner 管理 ───

    /** Agent 名称 → AgentRunner 映射 */
    private val runners = mutableMapOf<String, AgentRunner>()

    /** Agent 名称 → 协程作用域 */
    private val agentScopes = mutableMapOf<String, CoroutineScope>()

    /** Agent 名称 → Job */
    private val agentJobs = mutableMapOf<String, Job>()

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
     * 初始化团队状态和 Orchestrator 的 AgentRunner。
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

        val ctx = AgentContext(
            agentName = ORCHESTRATOR_NAME,
            isOrchestrator = true,
            systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
            modelConfig = orchestratorConfig,
            overrideModelId = orchestratorOverrideModelId,
            teamName = teamName,
            messages = ArrayList(),
        )

        val orchestratorRunner = createAgentRunner(ctx)
        runners[ORCHESTRATOR_NAME] = orchestratorRunner

        // 存储 AgentTool 所需的上下文
        this.orchestratorContext = ctx
        this.sandboxPath = sandboxPath
        this.agentTool = AgentTool(mcpRuntimeManager)

        val state = TeamState(
            teamName = teamName,
            orchestratorConfig = orchestratorConfig,
            sandboxPath = sandboxPath,
        )
        if (!_teamState.compareAndSet(null, state)) {
            runners.remove(ORCHESTRATOR_NAME)
            error("并发创建团队冲突，请重试")
        }

        // 注册沙盒 Hook（限制文件操作范围）
        if (!sandboxPath.isNullOrBlank()) {
            val hook = WorkspaceSandboxHook(sandboxPath)
            HookManager.registerMcpHook(hook)
            sandboxHook = hook
            Log.d(TAG, "Registered workspace sandbox hook for: $sandboxPath")
        }

        onAgentCreated(ORCHESTRATOR_NAME, true)
        Log.d(TAG, "Team '$teamName' created with Orchestrator")

        state
    }

    /**
     * 启动 Orchestrator 执行循环。
     */
    suspend fun startExecution(userTask: String, imagePath: String? = null) {
        val state = _teamState.value ?: error("无活跃团队，请先调用 createTeam")
        val runner = runners[ORCHESTRATOR_NAME] ?: error("Orchestrator 未初始化")

        // WHY: 防止 startExecution 被重复调用时泄露前一个 scope。
        agentScopes[ORCHESTRATOR_NAME]?.let { oldScope ->
            Log.w(TAG, "Cancelling previous orchestrator scope before restart")
            oldScope.coroutineContext[TeammateContext]?.abort()
            oldScope.cancel()
        }
        try {
            val oldJob = agentJobs[ORCHESTRATOR_NAME]
            if (oldJob != null && !oldJob.isCompleted) {
                withTimeoutOrNull(3000L) {
                    oldJob.join()
                }
                if (!oldJob.isCompleted) {
                    Log.w(TAG, "Cancelling old orchestrator job forcefully")
                    oldJob.cancel()
                }
            }
        } catch (_: Exception) {}

        Log.d(TAG, "Starting orchestrator execution with task: ${userTask.take(80)}...")

        workspaceStartTimeMs = System.currentTimeMillis()
        _teamState.update { it?.copy(isRunning = true) }

        val identity = TeammateIdentity(
            agentId = "${ORCHESTRATOR_NAME}@${state.teamName}",
            agentName = ORCHESTRATOR_NAME,
            teamName = state.teamName,
            parentSessionId = "leader",
        )

        val orchestratorScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        agentScopes[ORCHESTRATOR_NAME] = orchestratorScope

        val job = orchestratorScope.launch {
            try {
                onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.STREAMING)
                runner.runTurn(userTask, imagePath = imagePath)
                onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.COMPLETED)
                triggerWorkspaceComplete(runner)
            } catch (e: Exception) {
                Log.e(TAG, "Orchestrator execution failed", e)
                onError(e.message ?: "Unknown error")
                onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.ERROR)
            } finally {
                agentJobs.remove(ORCHESTRATOR_NAME)
                agentScopes.remove(ORCHESTRATOR_NAME)
            }
        }
        agentJobs[ORCHESTRATOR_NAME] = job

        job.join()
    }

    /**
     * 删除团队。
     */
    suspend fun deleteTeam() {
        val state = _teamState.value ?: return

        Log.d(TAG, "Deleting team '${state.teamName}'")

        // 取消所有协程作用域
        val scopeKeys = agentScopes.keys.toList()
        for (key in scopeKeys) {
            agentScopes[key]?.let {
                it.coroutineContext[TeammateContext]?.abort()
                it.cancel()
            }
        }

        // 等待所有 Job 完成
        val jobEntries = agentJobs.entries.toList()
        for ((_, job) in jobEntries) {
            try { job.join() } catch (_: Exception) { }
        }

        // 注销沙盒 Hook
        sandboxHook?.let {
            HookManager.unregisterMcpHook(it)
            Log.d(TAG, "Unregistered workspace sandbox hook")
        }
        sandboxHook = null

        isCompleted.set(false)
        cachedAvailableModelsStr = ""
        agentTool = null
        orchestratorContext = null
        sandboxPath = null
        runners.clear()
        agentScopes.clear()
        agentJobs.clear()
        _teamState.value = null

        Log.d(TAG, "Team '${state.teamName}' deleted")
    }

    /**
     * 获取指定 Agent 的对话历史。
     */
    fun getAgentHistory(agentName: String): List<AgentMessage> {
        return runners[agentName]?.getHistory() ?: emptyList()
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
        onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.COMPLETED)

        val orchestratorMessages = runner.getHistory()
        val subAgentMessages = mutableMapOf<String, List<AgentMessage>>()
        val subAgentIdentities = mutableMapOf<String, TeammateIdentity>()

        _teamState.update { s -> s?.copy(isCompleted = true, isRunning = false) }

        // 清理 sub-agent scopes
        val state = _teamState.value
        val subAgentNames = runners.keys.filter { it != ORCHESTRATOR_NAME }
        for (name in subAgentNames) {
            agentScopes[name]?.cancel()
            try {
                withTimeoutOrNull(3_000L) {
                    agentJobs[name]?.join()
                } ?: Log.w(TAG, "Timeout waiting for sub-agent '$name' to stop, forcing cleanup")
            } catch (_: Exception) {}
            runners[name]?.dispose()
            runners.remove(name)
        }

        val snapshot = TeamCompletionSnapshot(
            orchestratorMessages = orchestratorMessages,
            subAgentMessages = subAgentMessages,
            subAgentIdentities = subAgentIdentities,
        )
        onWorkspaceComplete(snapshot)

        // 触发工作区完成 Hook
        val teamName = state?.teamName ?: ""
        val agentCount = runners.size
        val totalDurationMs = if (workspaceStartTimeMs > 0) System.currentTimeMillis() - workspaceStartTimeMs else 0L
        val orchestratorSummary = orchestratorMessages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content?.take(500) ?: ""
        WorkspaceScopes.auxiliary.launch {
            try {
                com.example.hooks.HookManager.dispatchWorkspaceComplete(
                    teamName = teamName,
                    agentCount = agentCount,
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
     * 创建 AgentRunner。
     *
     * suspend 函数，避免在 Dispatchers.Default 线程池上使用 runBlocking 阻塞线程。
     */
    private suspend fun createAgentRunner(
        context: AgentContext,
        isSubAgent: Boolean = false,
        maxToolIterations: Int = AgentRunner.MAX_TOOL_CALL_ITERATIONS,
    ): AgentRunner {
        if (cachedAvailableModelsStr.isEmpty()) {
            val allConfigs = repository.getAllConfigs()
            val allFetchedModels = repository.getAllFetchedModels()
            cachedAvailableModelsStr = buildAvailableModelsString(allConfigs, allFetchedModels)
        }

        return AgentRunner(
            context = context,
            mcpRuntimeManager = mcpRuntimeManager,
            crossSessionMemory = crossSessionMemoryText,
            availableModels = cachedAvailableModelsStr,
            disallowedTools = if (isSubAgent) setOf("agent") else emptySet(),
            sandboxPath = _teamState.value?.sandboxPath,
            maxToolIterations = maxToolIterations,
            onStreamChunk = onStreamChunk,
            onToolCall = { agentName, toolName, args, callId ->
                if (toolName == AgentTool.TOOL_NAME && agentTool != null) {
                    // 路由到 AgentTool：创建隔离的 SubAgent 执行
                    agentTool!!.call(args, orchestratorContext!!, sandboxPath ?: "").toString()
                } else {
                    // 路由到 MCP 工具
                    val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
                    if (serverId == null) {
                        Log.e(TAG, "Tool '$toolName' not found in any MCP server")
                        "Error: Tool '$toolName' not found"
                    } else {
                        val result = mcpRuntimeManager.callTool(serverId, toolName, args)
                        result?.toString() ?: "No result"
                    }
                }
            },
            // 消息持久化：每条消息写入内存后同步到 Room DB（fire-and-forget）
            persistMessage = { message ->
                WorkspaceScopes.auxiliary.launch {
                    try {
                        // teamName 格式为 "workspace_$wsId"，提取 sessionId
                        val teamName = _teamState.value?.teamName ?: ""
                        val sessionId = teamName.removePrefix("workspace_").toLongOrNull() ?: 0L
                        // 通过 sessionId + agentName 查找 AgentInstance 的 DB ID
                        val instanceId = repository.getAgentInstancesByWorkspaceSession(sessionId)
                            .firstOrNull { it.agentName == context.agentName }?.id ?: 0L
                        repository.insertWorkspaceMessage(
                            com.example.data.WorkspaceMessage(
                                workspaceSessionId = sessionId,
                                agentInstanceId = instanceId,
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
                        Log.w(TAG, "Failed to persist message for ${context.agentName}", e)
                    }
                }
            },
        )
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
