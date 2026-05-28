package com.example.workspace

import android.util.Log
import com.example.data.AgentPreset
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
// 职责：协调 AgentLifecycle、AgentExecutionLoops 两个模块，
// 通过 AgentTool 实现 Orchestrator 的子 Agent 委派。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 团队管理器。
 *
 * 负责团队的创建、Agent 的调度和生命周期管理。
 * 通过 [AgentLifecycle]、[AgentExecutionLoops] 两个子模块实现具体逻辑，
 * 通过 [AgentTool] 实现 Orchestrator 的子 Agent 委派。
 *
 * @property repository 数据仓库
 * @property mcpRuntimeManager MCP 运行时管理器
 * @property parentScope 父协程作用域
 * @property config 工作区配置
 * @property agentRegistry Agent 定义注册中心
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
    private val agentRegistry: AgentRegistry,
    private val onAgentCreated: (agentName: String, isOrchestrator: Boolean) -> Unit,
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
    private val onWorkspaceComplete: (snapshot: TeamCompletionSnapshot) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    companion object {
        private const val TAG = "TeamManager"
    }

    // ─── 内部依赖（不再从外部传入）───

    private val messageBus = MessageBus()
    private val taskManager = TaskManager(repository.teamTaskDao)
    private val taskRegistry = TaskRegistry()

    // ─── 子模块 ───

    private val lifecycle = AgentLifecycle(repository, messageBus, taskManager, config, agentRegistry, taskRegistry, onError)
    private val executionLoops = AgentExecutionLoops(messageBus, taskManager, lifecycle, mcpRuntimeManager, onAgentStatusChanged, onError)

    // ─── AgentTool（Orchestrator 用于委派子 Agent）───

    private var agentTool: AgentTool? = null
    private var orchestratorContext: AgentContext? = null
    private var sandboxPath: String? = null

    // ─── 团队状态（StateFlow 驱动 UI）───

    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    // ─── 工作区完成标记 ───

    private val isCompleted = AtomicBoolean(false)

    // ─── 工作区开始时间（用于计算总耗时）───
    private var workspaceStartTimeMs: Long = 0L

    // ─── 沙盒 Hook 引用（用于注销）───
    private var sandboxHook: WorkspaceSandboxHook? = null

    // ─── 共享 Scratchpad（跨 Agent 文件共享）───
    private var scratchpad: Scratchpad? = null

    @Volatile private var cachedAvailableModelsStr: String = ""

    private val createTeamMutex = kotlinx.coroutines.sync.Mutex()

    // ─── AgentTool 访问器 ───

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
     * 在创建前验证 teamName、agentPresets 名称唯一性及 modelConfigId 有效性，
     * 将无效输入提前暴露为明确的 IllegalArgumentException（Bug #13）。
     */
    suspend fun createTeam(
        teamName: String,
        orchestratorConfig: ModelConfig,
        orchestratorOverrideModelId: String? = null,
        agentPresets: List<AgentPreset>,
        sandboxPath: String? = null,
    ): TeamState = createTeamMutex.withLock {
        // WHY: 使用 compareAndSet 替代先检查后赋值，保证原子性（Bug #22）。
        // 结合 Mutex 锁定，确保完全消除并发创建导致的状态不一致（修复 Bug #1）
        require(_teamState.value == null) { "已有活跃团队，请先删除当前团队" }
        require(teamName.isNotBlank()) { "团队名称不能为空" }

        // 检查预设名称：不能为空，且不能重复
        for (preset in agentPresets) {
            require(preset.name.isNotBlank()) { "预设名称不能为空" }
        }
        val duplicateNames = agentPresets.groupBy { it.name }
            .filter { it.value.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) { "预设名称重复: $duplicateNames" }

        // 检查 modelConfigId 是否存在于数据库
        for (preset in agentPresets) {
            val configId = preset.modelConfigId ?: continue
            val config = repository.getConfigById(configId)
            require(config != null) { "预设'${preset.name}'的模型配置不存在: $configId" }
        }

        Log.d(TAG, "Creating team: $teamName")

        // WHY: 确保 AgentRegistry 已加载最新定义，Orchestrator 调用 create_agents 时能查到 Agent 预设
        agentRegistry.loadAll()
        agentRegistry.loadFromPresets(agentPresets)

        lifecycle.crossSessionMemoryText = lifecycle.loadCrossSessionMemory()

        val orchestratorIdentity = TeammateIdentity(
            agentId = "${ORCHESTRATOR_NAME}@${teamName}",
            agentName = ORCHESTRATOR_NAME,
            teamName = teamName,
            color = lifecycle.assignColor(),
            parentSessionId = "leader",
        )

        // WHY: 显式传入 ArrayList()，避免 data class 默认值在 copy() 时共享同一引用
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
        lifecycle.runners[ORCHESTRATOR_NAME] = orchestratorRunner

        // 存储 AgentTool 所需的上下文
        this.orchestratorContext = ctx
        this.sandboxPath = sandboxPath
        this.agentTool = AgentTool(mcpRuntimeManager)

        val state = TeamState(
            teamName = teamName,
            orchestratorConfig = orchestratorConfig,
            agentPresets = agentPresets,
            sandboxPath = sandboxPath,
            teammates = mapOf(
                ORCHESTRATOR_NAME to TeammateState(
                    identity = orchestratorIdentity,
                    status = AgentStatus.IDLE,
                    isOrchestrator = true,
                )
            ),
        )
        // WHY: compareAndSet(null, state) 原子地将 null 替换为 state（Bug #22）。
        // 若并发调用在 createAgentRunner 挂起期间抢先设置了 state，compareAndSet 返回
        // false，当前调用回滚已创建的 runner 并抛出异常，避免两个团队同时存在。
        if (!_teamState.compareAndSet(null, state)) {
            lifecycle.runners.remove(ORCHESTRATOR_NAME)
            error("并发创建团队冲突，请重试")
        }

        // 注册沙盒 Hook（限制文件操作范围）
        if (!sandboxPath.isNullOrBlank()) {
            val hook = WorkspaceSandboxHook(sandboxPath)
            HookManager.registerMcpHook(hook)
            sandboxHook = hook
            Log.d(TAG, "Registered workspace sandbox hook for: $sandboxPath")
        }

        // 创建共享 Scratchpad，供跨 Agent 文件共享
        val scratchpadDir = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "OmniChat/workspace/${teamName}/scratchpad"
        )
        scratchpad = Scratchpad(scratchpadDir)
        Log.d(TAG, "Created scratchpad at: ${scratchpadDir.absolutePath}")

        onAgentCreated(ORCHESTRATOR_NAME, true)
        Log.d(TAG, "Team '$teamName' created with Orchestrator")

        state
    }

    /**
     * 启动 Orchestrator 执行循环。
     */
    suspend fun startExecution(userTask: String, imagePath: String? = null) {
        val state = _teamState.value ?: error("无活跃团队，请先调用 createTeam")
        val runner = lifecycle.runners[ORCHESTRATOR_NAME] ?: error("Orchestrator 未初始化")
        val identity = state.teammates[ORCHESTRATOR_NAME]?.identity
            ?: error("Orchestrator 身份信息缺失")

        // WHY: 防止 startExecution 被重复调用时泄露前一个 orchestratorScope。
        lifecycle.teammateScopes[ORCHESTRATOR_NAME]?.let { oldScope ->
            Log.w(TAG, "Cancelling previous orchestrator scope before restart")
            oldScope.coroutineContext[TeammateContext]?.abort()
            oldScope.cancel()
        }
        try {
            val oldJob = lifecycle.teammateJobs[ORCHESTRATOR_NAME]
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

        val orchestratorScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        lifecycle.teammateScopes[ORCHESTRATOR_NAME] = orchestratorScope

        val job = orchestratorScope.launch {
            try {
                executionLoops.runOrchestratorLoop(runner, userTask, imagePath, isCompleted) { completedRunner ->
                    triggerWorkspaceComplete(completedRunner)
                }
            } finally {
                lifecycle.teammateJobs.remove(ORCHESTRATOR_NAME)
                lifecycle.teammateScopes.remove(ORCHESTRATOR_NAME)
            }
        }
        lifecycle.teammateJobs[ORCHESTRATOR_NAME] = job

        job.join()
    }

    /**
     * 删除团队。
     */
    suspend fun deleteTeam() {
        val state = _teamState.value ?: return

        Log.d(TAG, "Deleting team '${state.teamName}'")

        // 取消所有协程作用域
        val scopeKeys = lifecycle.teammateScopes.keys.toList()
        for (key in scopeKeys) {
            lifecycle.teammateScopes[key]?.let {
                it.coroutineContext[TeammateContext]?.abort()
                it.cancel()
            }
        }

        // 等待所有 Job 完成
        val jobEntries = lifecycle.teammateJobs.entries.toList()
        for ((_, job) in jobEntries) {
            try { job.join() } catch (_: Exception) { }
        }

        // 注销沙盒 Hook
        sandboxHook?.let {
            HookManager.unregisterMcpHook(it)
            Log.d(TAG, "Unregistered workspace sandbox hook")
        }
        sandboxHook = null

        // 清空共享 Scratchpad
        scratchpad?.clearAll()
        scratchpad = null

        isCompleted.set(false)
        cachedAvailableModelsStr = ""
        agentTool = null
        orchestratorContext = null
        sandboxPath = null
        _teamState.value = null

        Log.d(TAG, "Team '${state.teamName}' deleted")
    }

    /**
     * 获取指定 Agent 的对话历史。
     */
    fun getAgentHistory(agentName: String): List<AgentMessage> {
        return lifecycle.runners[agentName]?.getHistory() ?: emptyList()
    }

    /**
     * 检测完成标记。
     */
    fun isCompletionMarker(text: String): Boolean = executionLoops.isCompletionMarker(text)

    /** 获取 Scratchpad 供 MCP 工具访问 */
    fun getScratchpad(): Scratchpad? = scratchpad

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
        val state = _teamState.value

        if (state != null) {
            for ((name, teammateState) in state.teammates) {
                if (teammateState.isOrchestrator) continue
                subAgentMessages[name] = lifecycle.runners[name]?.getHistory() ?: emptyList()
                subAgentIdentities[name] = teammateState.identity
            }
        }

        _teamState.update { s -> s?.copy(isCompleted = true) }

        val subAgentNames = state?.teammates?.values
            ?.filter { !it.isOrchestrator }
            ?.map { it.identity.agentName }
            ?: emptyList()

        for (name in subAgentNames) {
            lifecycle.teammateScopes[name]?.cancel()
            try {
                withTimeoutOrNull(3_000L) {
                    lifecycle.teammateJobs[name]?.join()
                } ?: Log.w(TAG, "Timeout waiting for sub-agent '$name' to stop, forcing cleanup")
            } catch (_: Exception) {}
            lifecycle.cleanupAgent(name)
        }

        val snapshot = TeamCompletionSnapshot(
            orchestratorMessages = orchestratorMessages,
            subAgentMessages = subAgentMessages,
            subAgentIdentities = subAgentIdentities,
        )
        onWorkspaceComplete(snapshot)

        // 触发工作区完成 Hook
        val teamName = state?.teamName ?: ""
        val agentCount = (state?.teammates?.size ?: 0)
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
            crossSessionMemory = lifecycle.crossSessionMemoryText,
            availableModels = cachedAvailableModelsStr,
            disallowedTools = if (isSubAgent) ORCHESTRATOR_ONLY_TOOLS else emptySet(),
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
            // 进度摘要：每 5 次工具调用后通知 Orchestrator（需 launch 因为 messageBus.send 是 suspend）
            onProgressSummary = { agentName, summary ->
                WorkspaceScopes.auxiliary.launch {
                    try {
                        messageBus.send(
                            ORCHESTRATOR_NAME,
                            TeamMessage.Text(from = "system", content = summary)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send progress summary for $agentName", e)
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
            appendLine("快捷模型选择：在 create_agents 中使用 modelHint 字段，系统自动选择最合适的模型：")
            appendLine("- modelHint=\"reasoning\" → 推理/编程任务，选择支持 thinking 的模型")
            appendLine("- modelHint=\"vision\" → 图像理解任务，选择支持视觉的模型")
            appendLine("- modelHint=\"fast\" → 快速响应，选择轻量模型")
            appendLine("- modelHint=\"large-context\" → 长文档处理，选择最大上下文窗口")
            appendLine("- modelHint=\"tools\" → 需要工具调用，选择支持 tool use 的模型")
        }
    }
}
