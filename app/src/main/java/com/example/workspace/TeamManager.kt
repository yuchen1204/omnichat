package com.example.workspace

import android.util.Log
import com.example.data.AgentPreset
import com.example.data.AppRepository
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ═══════════════════════════════════════════════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 工作区可配置参数。
 *
 * 将原本硬编码的常量提取为可配置的数据类，允许通过 UI 或配置文件调整。
 *
 * @property maxSubAgents Sub-Agent 数量上限（默认 10）
 * @property maxAgentNameLength Agent 名称最大长度（默认 50）
 * @property maxSystemPromptLength 系统提示最大长度（默认 2000）
 * @property maxParseRetries Orchestrator 输出解析失败时的最大重试次数（默认 3）
 * @property memoryInjectLimit 跨会话记忆注入条数上限（默认 20）
 * @property enableCrossSessionMemory 是否启用跨会话记忆注入（默认 true）
 */
data class WorkspaceConfig(
    val maxSubAgents: Int = 10,
    val maxAgentNameLength: Int = 50,
    val maxSystemPromptLength: Int = 2000,
    val maxParseRetries: Int = 3,
    val memoryInjectLimit: Int = 20,
    val enableCrossSessionMemory: Boolean = true,
)

/**
 * 团队完成时的全量消息快照。
 *
 * 包含 Orchestrator 和所有 Sub-Agent 的消息，用于持久化。
 *
 * @property orchestratorMessages Orchestrator 的消息列表
 * @property subAgentMessages Sub-Agent 消息映射（agentName -> messages）
 * @property subAgentIdentities Sub-Agent 身份信息映射（agentName -> identity）
 */
data class TeamCompletionSnapshot(
    val orchestratorMessages: List<AgentMessage>,
    val subAgentMessages: Map<String, List<AgentMessage>>,
    val subAgentIdentities: Map<String, TeammateIdentity>,
)

/**
 * 团队状态。
 *
 * 对标蓝图中的 TeamState，通过 StateFlow 驱动 UI 更新。
 *
 * @property teamName 团队名称
 * @property orchestratorConfig Orchestrator 的模型配置
 * @property agentPresets Agent 预设列表
 * @property teammates 团队成员映射（Key: agentName）
 * @property isCompleted 工作区是否已完成
 */
data class TeamState(
    val teamName: String,
    val orchestratorConfig: ModelConfig,
    val agentPresets: List<AgentPreset>,
    val teammates: Map<String, TeammateState> = emptyMap(),
    val isCompleted: Boolean = false,
)

/**
 * Teammate 状态。
 *
 * 对标蓝图中的 TeammateState，表示单个 Agent 的运行时状态。
 *
 * @property identity Teammate 身份信息
 * @property status 当前 Agent 状态
 * @property isOrchestrator 是否为 Orchestrator
 * @property lastActivity 最近一次状态变更时间戳
 */
data class TeammateState(
    val identity: TeammateIdentity,
    val status: AgentStatus = AgentStatus.IDLE,
    val isOrchestrator: Boolean = false,
    val lastActivity: Long = System.currentTimeMillis(),
)

/**
 * Sub-Agent 等待结果。
 *
 * Teammate 执行循环中等待下一条消息时的返回值。
 */
sealed class WaitResult {
    /** 收到关闭请求 */
    data class ShutdownRequest(val request: TeamMessage.ShutdownRequest) : WaitResult()
    /** 收到新消息 */
    data class NewMessage(val message: String, val from: String) : WaitResult()
    /** 认领了新任务 */
    data class TaskClaimed(val taskDescription: String) : WaitResult()
    /** 已中止 */
    data object Aborted : WaitResult()
}

/**
 * Agent 完成信号。
 *
 * 用于 [spawnWithDependencies] 中替代直接读取 Orchestrator 收件箱，
 * 避免多个协程竞争同一个 Channel。
 *
 * @property agentName 完成的 Agent 名称
 * @property success 是否成功完成
 */
private data class TeammateCompletion(
    val agentName: String,
    val success: Boolean,
)

// ═══════════════════════════════════════════════════════════════════════════════
// TeamManager
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 团队管理器。
 *
 * 对标蓝图中的 TeamManager，替代现有的 [WorkspaceOrchestrator]。
 * 负责团队的创建、Agent 的调度、消息路由和生命周期管理。
 *
 * 核心设计：
 * - 通过 [MessageBus] 实现 Agent 间通信（替代文件邮箱）
 * - 通过 [TeammateContext] 实现协程上下文隔离（替代 AsyncLocalStorage）
 * - 通过 [TaskManager] 实现任务自动认领
 * - 通过 [StateFlow] 驱动 UI 更新
 *
 * @property repository 数据仓库，用于持久化和模型配置查询
 * @property mcpRuntimeManager MCP 运行时管理器，用于工具调用
 * @property messageBus 消息总线，用于 Agent 间通信
 * @property taskManager 任务管理器，用于任务认领
 * @property parentScope 父协程作用域，Teammate 的 CoroutineScope 会继承其上下文
 * @property onAgentCreated Agent 创建回调
 * @property onStreamChunk 流式 chunk 回调
 * @property onAgentStatusChanged Agent 状态变更回调
 * @property onWorkspaceComplete 工作区完成回调
 * @property onError 错误回调
 */
class TeamManager(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val messageBus: MessageBus,
    private val taskManager: TaskManager,
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

        /** Orchestrator 固定名称 */
        const val ORCHESTRATOR_NAME = "主控 Agent"

        /** 任务完成标记 */
        const val COMPLETION_MARKER = "【任务完成】"

        /** 消息轮询间隔（毫秒） */
        private const val POLL_INTERVAL_MS = 500L

        /** Orchestrator 输出解析失败时的最大重试次数（已废弃，改为 tool call） */
        // private const val MAX_PARSE_RETRIES = 3

        /** Agent UI 标识色列表 */
        private val AGENT_COLORS = listOf(
            "#4285F4", "#EA4335", "#34A853", "#FBBC05",
            "#FF6D00", "#AA00FF", "#00BFA5", "#D50000",
            "#6200EA", "#0091EA"
        )
    }

    // ─── 团队状态（StateFlow 驱动 UI）───

    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    // ─── Teammate 协程作用域和任务 ───

    private val teammateJobs = ConcurrentHashMap<String, Job>()
    private val teammateScopes = ConcurrentHashMap<String, CoroutineScope>()

    // ─── Agent Runner 映射 ───

    private val runners = ConcurrentHashMap<String, AgentRunner>()

    // ─── 工作区完成标记 ──-

    @Volatile
    private var isCompleted = false

    // ─── Agent 完成信号（用于依赖流程） ───

    /** Agent 完成信号，replay=1 确保不会丢失 */
    private val agentCompletionFlow = MutableSharedFlow<TeammateCompletion>(replay = 1)

    /** 用于等待特定 Agent 完成的 Deferred 映射 */
    private val agentCompletionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // ─── 颜色分配索引 ───

    private var colorIndex = 0

    // ─── 跨会话记忆文本 ───

    private var crossSessionMemoryText: String = ""

    // ═══════════════════════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 创建团队。
     *
     * 初始化团队状态和 Orchestrator 的 AgentRunner，使用 [TeammateContext] 隔离。
     * 对标蓝图中的 TeamCreateTool.call()。
     *
     * @param teamName 团队名称
     * @param orchestratorConfig Orchestrator 的模型配置
     * @param agentPresets Agent 预设列表，用于匹配 Sub-Agent 角色
     * @return 创建的团队状态
     * @throws IllegalStateException 如果已有活跃团队
     */
    suspend fun createTeam(
        teamName: String,
        orchestratorConfig: ModelConfig,
        agentPresets: List<AgentPreset>,
    ): TeamState {
        require(_teamState.value == null) { "已有活跃团队，请先删除当前团队" }

        Log.d(TAG, "Creating team: $teamName")

        // 加载跨会话记忆
        crossSessionMemoryText = loadCrossSessionMemory()

        // 创建 Orchestrator 身份
        val orchestratorIdentity = TeammateIdentity(
            agentId = "${ORCHESTRATOR_NAME}@${teamName}",
            agentName = ORCHESTRATOR_NAME,
            teamName = teamName,
            color = assignColor(),
            parentSessionId = "leader",
        )

        // 创建 Orchestrator 的 AgentContext
        val orchestratorContext = AgentContext(
            agentName = ORCHESTRATOR_NAME,
            isOrchestrator = true,
            systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
            modelConfig = orchestratorConfig,
        )

        // 创建 AgentRunner
        val orchestratorRunner = createAgentRunner(orchestratorContext)
        runners[ORCHESTRATOR_NAME] = orchestratorRunner

        // 初始化团队状态
        val state = TeamState(
            teamName = teamName,
            orchestratorConfig = orchestratorConfig,
            agentPresets = agentPresets,
            teammates = mapOf(
                ORCHESTRATOR_NAME to TeammateState(
                    identity = orchestratorIdentity,
                    status = AgentStatus.IDLE,
                    isOrchestrator = true,
                )
            ),
        )
        _teamState.value = state

        onAgentCreated(ORCHESTRATOR_NAME, true)
        Log.d(TAG, "Team '$teamName' created with Orchestrator")

        return state
    }

    /**
     * 启动 Orchestrator 执行循环。
     *
     * 在独立协程中运行 Orchestrator 的调度循环，处理用户任务、
     * 解析 Sub-Agent 创建指令、等待 Sub-Agent 结果并汇总。
     * 调用此方法会阻塞当前协程直到工作区完成或被取消。
     *
     * 如果 [userTask] 为空（会话恢复场景），Orchestrator 直接进入等待状态，
     * 不向 LLM 发送空消息，避免无意义的 API 调用。
     *
     * @param userTask 用户提交的任务描述，空字符串表示恢复已有会话等待新输入
     */
    suspend fun startExecution(userTask: String) {
        val state = _teamState.value ?: error("无活跃团队，请先调用 createTeam")
        val runner = runners[ORCHESTRATOR_NAME] ?: error("Orchestrator 未初始化")
        val identity = state.teammates[ORCHESTRATOR_NAME]?.identity
            ?: error("Orchestrator 身份信息缺失")

        Log.d(TAG, "Starting orchestrator execution with task: ${userTask.take(80)}...")

        // 创建 Orchestrator 的独立 scope（与 spawnTeammate 一致的隔离模式）
        val orchestratorScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        teammateScopes[ORCHESTRATOR_NAME] = orchestratorScope

        // 存储 Job 引用，用于 killTeammate / deleteTeam
        val job = orchestratorScope.launch {
            try {
                runOrchestratorLoop(runner, userTask)
            } finally {
                teammateJobs.remove(ORCHESTRATOR_NAME)
                teammateScopes.remove(ORCHESTRATOR_NAME)
            }
        }
        teammateJobs[ORCHESTRATOR_NAME] = job

        // 等待完成（保持 suspend 函数语义）
        job.join()
    }

    /**
     * 创建 Teammate（Sub-Agent）。
     *
     * 创建独立 [CoroutineScope]（[SupervisorJob] + [TeammateContext]），
     * 启动 AgentRunner 执行循环。对标的蓝图中的 spawnTeammate。
     *
     * @param name Sub-Agent 名称
     * @param prompt 初始提示
     * @param systemPrompt 系统提示（可选）
     * @param modelConfigId 模型配置 ID（可选，null 表示使用 Orchestrator 默认配置）
     * @return 创建的 Teammate 身份信息
     * @throws IllegalStateException 如果无活跃团队或已达上限
     */
    suspend fun spawnTeammate(
        name: String,
        prompt: String,
        systemPrompt: String = "",
        modelConfigId: Long? = null,
    ): TeammateIdentity {
        val state = _teamState.value ?: error("无活跃团队")
        val teamName = state.teamName

        // 检查 Sub-Agent 数量上限
        val subAgentCount = state.teammates.count { !it.value.isOrchestrator }
        if (subAgentCount >= config.maxSubAgents) {
            val errorMessage = "已达到子 Agent 上限（${config.maxSubAgents}个），无法继续创建"
            Log.w(TAG, errorMessage)
            onError(errorMessage)
            error(errorMessage)
        }

        // 生成唯一名称，截断至最大长度
        val uniqueName = generateUniqueName(name, state.teammates.keys)
            .take(config.maxAgentNameLength)

        // 优先匹配 AgentPreset
        val preset = state.agentPresets.find { it.name == uniqueName }
        val finalSystemPrompt = (
            preset?.systemPrompt?.takeIf { it.isNotEmpty() } ?: systemPrompt
        ).take(config.maxSystemPromptLength)

        // 确定模型配置
        val finalModelConfigId = preset?.modelConfigId ?: modelConfigId
        val modelConfig = if (finalModelConfigId != null) {
            repository.getConfigById(finalModelConfigId)
        } else null
        val actualModelConfig = modelConfig ?: state.orchestratorConfig

        // 创建身份
        val identity = TeammateIdentity(
            agentId = "${uniqueName}@${teamName}",
            agentName = uniqueName,
            teamName = teamName,
            color = assignColor(),
            parentSessionId = "leader",
        )

        // 创建 AgentContext
        val context = AgentContext(
            agentName = uniqueName,
            isOrchestrator = false,
            systemPrompt = finalSystemPrompt.ifEmpty { DEFAULT_TEAMMATE_PROMPT },
            modelConfig = actualModelConfig,
        )

        // 创建 AgentRunner（子 Agent 禁用编排工具）
        val runner = createAgentRunner(context, isSubAgent = true)
        runners[uniqueName] = runner

        // 创建独立 CoroutineScope（对标蓝图的 parentScope.coroutineContext + SupervisorJob() + TeammateContext）
        val teammateScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        teammateScopes[uniqueName] = teammateScope

        // 更新团队状态
        _teamState.update { currentState ->
            currentState?.copy(
                teammates = currentState.teammates + (uniqueName to TeammateState(
                    identity = identity,
                    status = AgentStatus.IDLE,
                    isOrchestrator = false,
                ))
            )
        }

        onAgentCreated(uniqueName, false)
        Log.d(TAG, "Spawned teammate '$uniqueName' with model ${actualModelConfig.name}")

        // 启动执行循环，传入占位 prompt，实际任务由 TaskAssignment 消息触发
        val job = teammateScope.launch {
            runTeammateLoop(runner, identity, "等待主控分配任务...")
        }
        teammateJobs[uniqueName] = job

        return identity
    }

    /**
     * 向指定 Agent 发送消息。
     *
     * 通过 [MessageBus] 投递文本消息。对标的蓝图中的 SendMessageTool。
     *
     * @param to 接收方 Agent 名称
     * @param message 消息内容
     */
    suspend fun sendMessage(to: String, message: String) {
        _teamState.value ?: error("无活跃团队")
        Log.d(TAG, "Sending message to '$to': ${message.take(50)}...")
        messageBus.send(
            to,
            TeamMessage.Text(from = ORCHESTRATOR_NAME, content = message)
        )
    }

    /**
     * 向所有 Sub-Agent 广播消息。
     *
     * @param message 广播内容
     */
    suspend fun broadcast(message: String) {
        val state = _teamState.value ?: error("无活跃团队")
        val subAgentNames = state.teammates.values
            .filter { !it.isOrchestrator }
            .map { it.identity.agentName }

        Log.d(TAG, "Broadcasting to ${subAgentNames.size} sub-agents")
        messageBus.broadcast(
            senderName = ORCHESTRATOR_NAME,
            message = TeamMessage.Text(from = ORCHESTRATOR_NAME, content = message),
            teamMembers = subAgentNames,
        )
    }

    /**
     * 请求关闭指定 Teammate。
     *
     * 通过 [MessageBus] 发送 shutdown_request。Teammate 的执行循环收到后退出。
     * 对标蓝图中的 shutdown_request 流程。
     *
     * @param agentName 目标 Agent 名称
     * @param reason 关闭原因
     * @return 请求 ID
     */
    suspend fun requestShutdown(agentName: String, reason: String = ""): String {
        val requestId = java.util.UUID.randomUUID().toString()
        Log.d(TAG, "Requesting shutdown of '$agentName' (reason: $reason)")
        messageBus.send(
            agentName,
            TeamMessage.ShutdownRequest(
                from = ORCHESTRATOR_NAME,
                requestId = requestId,
                reason = reason,
            )
        )
        return requestId
    }

    /**
     * 强制终止指定 Teammate。
     *
     * 取消其协程作用域，立即停止执行。不经过 shutdown_request 流程。
     *
     * @param agentName 目标 Agent 名称
     */
    fun killTeammate(agentName: String) {
        Log.d(TAG, "Killing teammate '$agentName'")
        teammateScopes[agentName]?.cancel()
        teammateScopes.remove(agentName)
        teammateJobs.remove(agentName)
        runners[agentName]?.dispose()
        runners.remove(agentName)
        messageBus.removeInbox(agentName)

        _teamState.update { state ->
            state?.copy(teammates = state.teammates - agentName)
        }
    }

    /**
     * 删除团队。
     *
     * 取消所有 Teammate 协程，清理消息总线和运行时资源。
     * 使用 [NonCancellable] 确保即使当前协程被取消也能完成清理。
     * 对标蓝图中的 TeamDeleteTool。
     */
    suspend fun deleteTeam() = withContext(NonCancellable) {
        val state = _teamState.value ?: return@withContext

        Log.d(TAG, "Deleting team '${state.teamName}'")

        // 取消所有 Teammate 协程
        teammateScopes.values.forEach { it.cancel() }
        // 等待所有任务完成
        teammateJobs.values.forEach {
            try {
                it.join()
            } catch (_: Exception) { }
        }

        // 清理运行时
        teammateScopes.clear()
        teammateJobs.clear()
        runners.values.forEach { it.dispose() }
        runners.clear()
        messageBus.clear()

        // 清理持久化数据
        repository.deleteAllTeamTasks(state.teamName)

        isCompleted = false
        colorIndex = 0
        crossSessionMemoryText = ""
        _teamState.value = null

        Log.d(TAG, "Team '${state.teamName}' deleted")
    }

    /**
     * 发送干预消息。
     *
     * 向目标 Agent 注入用户干预消息。如果目标不是 Orchestrator，
     * 同时向 Orchestrator 注入系统通知。
     * 对标 WorkspaceOrchestrator.sendIntervention。
     *
     * @param targetAgentName 目标 Agent 名称
     * @param message 干预消息内容
     */
    suspend fun sendIntervention(targetAgentName: String, message: String) {
        Log.d(TAG, "Sending intervention to '$targetAgentName'")

        val state = _teamState.value ?: run {
            onError("无活跃团队")
            return
        }

        if (targetAgentName !in state.teammates) {
            onError("目标 Agent 不存在：$targetAgentName")
            return
        }

        // 向目标 Agent 发送干预消息
        messageBus.send(
            targetAgentName,
            TeamMessage.Text(from = "user", content = message)
        )

        // 如果目标不是 Orchestrator，向 Orchestrator 注入系统通知
        if (targetAgentName != ORCHESTRATOR_NAME) {
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.Text(
                    from = "system",
                    content = "[用户干预] 用户向 $targetAgentName 发送了消息：$message",
                )
            )
        }
    }

    /**
     * 获取指定 Agent 的对话历史。
     *
     * @param agentName Agent 名称
     * @return 对话历史列表，如果 Agent 不存在则返回空列表
     */
    fun getAgentHistory(agentName: String): List<AgentMessage> {
        return runners[agentName]?.getHistory() ?: emptyList()
    }

    /**
     * 检测完成标记。
     *
     * 需求 5.5：最终响应以"【任务完成】"开头。
     *
     * @param text 输出文本
     * @return true 如果包含完成标记
     */
    fun isCompletionMarker(text: String): Boolean {
        return text.trim().startsWith(COMPLETION_MARKER)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Orchestrator 执行循环
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Orchestrator 调度循环。
     *
     * 核心改动：Agent 创建和任务分配通过 tool call 处理（handleCreateAgentsTool / handleAssignTaskTool），
     * 不再依赖解析 LLM 的文本输出。循环逻辑大幅简化：
     *
     * 1. 检查是否有 Sub-Agent 结果待汇总 → 注入上下文
     * 2. 调用 runTurn（LLM 可能调用 create_agents / assign_task 工具）
     * 3. 检测完成标记 → 结束
     * 4. 无结果且无工具调用 → 等待用户干预或 Agent 消息
     *
     * @param runner Orchestrator 的 AgentRunner
     * @param initialTask 用户初始任务，空字符串表示恢复等待
     */
    private suspend fun runOrchestratorLoop(runner: AgentRunner, initialTask: String) {
        // 会话恢复场景：跳过首轮 LLM 调用，直接等待用户输入
        if (initialTask.isBlank()) {
            updateTeammateStatus(ORCHESTRATOR_NAME, AgentStatus.IDLE)
            val firstInput = waitForOrchestratorInput()
            runOrchestratorLoop(runner, firstInput)
            return
        }

        var currentInput = initialTask

        while (!isCompleted && coroutineContext.isActive) {
            // 先检查是否有 Sub-Agent 结果待汇总（在 runTurn 之前注入）
            val pendingResults = collectPendingResults()
            if (pendingResults.isNotEmpty()) {
                Log.d(TAG, "Collected ${pendingResults.size} agent result(s), injecting into context")
                summarizeAndInject(runner, pendingResults)
            }

            updateTeammateStatus(ORCHESTRATOR_NAME, AgentStatus.STREAMING)

            // 执行一轮对话（内部可能触发 create_agents / assign_task 工具调用）
            runner.runTurn(currentInput)

            // 获取最后一条 assistant 消息
            val history = runner.getHistory()
            val lastAssistant = history.lastOrNull { it.role == "assistant" }

            // 检测完成标记
            if (lastAssistant != null && isCompletionMarker(lastAssistant.content)) {
                Log.d(TAG, "Orchestrator output completion marker")
                triggerWorkspaceComplete(runner)
                break
            }

            // 无结果待处理：等待用户干预或 Sub-Agent 消息
            updateTeammateStatus(ORCHESTRATOR_NAME, AgentStatus.IDLE)
            currentInput = waitForOrchestratorInput()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Teammate 执行循环
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Teammate（Sub-Agent）执行循环。
     *
     * 核心流程：
     * 1. 创建后进入 IDLE 等待状态
     * 2. 发送就绪通知（IdleNotification）
     * 3. 等待 TaskAssignment 消息
     * 4. 执行任务
     * 5. 发送 ResultReport（包含执行结果）+ IdleNotification（表示完成）
     * 6. 回到 3 等待下一个任务
     *
     * @param runner Teammate 的 AgentRunner
     * @param identity Teammate 身份信息
     * @param initialPrompt 初始提示
     */
    private suspend fun runTeammateLoop(
        runner: AgentRunner,
        identity: TeammateIdentity,
        initialPrompt: String,
    ) {
        // 注册完成 Deferred，供 spawnWithDependencies 等待
        val completionDeferred = CompletableDeferred<Unit>()
        agentCompletionDeferreds[identity.agentName] = completionDeferred

        try {
            // 创建后立即进入 IDLE 等待状态
            updateTeammateStatus(identity.agentName, AgentStatus.IDLE)

            // 发送就绪通知，告知主控已准备好接收任务
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.IdleNotification(
                    from = identity.agentName,
                    idleReason = IdleReason.AVAILABLE,
                )
            )

            var currentPrompt = initialPrompt

            while (coroutineContext.isActive) {
                // 等待收到 TaskAssignment 或 Text 消息后才执行
                val waitResult = waitForNextMessage(identity)

                when (waitResult) {
                    is WaitResult.ShutdownRequest -> {
                        Log.d(TAG, "Teammate '${identity.agentName}' received shutdown request")
                        break
                    }
                    is WaitResult.NewMessage -> {
                        currentPrompt = waitResult.message
                        updateTeammateStatus(identity.agentName, AgentStatus.STREAMING)
                        runner.runTurn(currentPrompt)

                        // 发送 ResultReport（包含执行结果和工具调用输出）
                        val resultSummary = collectAgentResult(runner)
                        messageBus.send(
                            ORCHESTRATOR_NAME,
                            TeamMessage.ResultReport(
                                from = identity.agentName,
                                taskId = "",
                                result = resultSummary,
                                success = true,
                            )
                        )

                        updateTeammateStatus(identity.agentName, AgentStatus.IDLE)
                        messageBus.send(
                            ORCHESTRATOR_NAME,
                            TeamMessage.IdleNotification(
                                from = identity.agentName,
                                idleReason = IdleReason.AVAILABLE,
                            )
                        )
                    }
                    is WaitResult.TaskClaimed -> {
                        currentPrompt = waitResult.taskDescription
                        updateTeammateStatus(identity.agentName, AgentStatus.STREAMING)
                        runner.runTurn(currentPrompt)

                        // 发送 ResultReport（包含执行结果和工具调用输出）
                        val resultSummary = collectAgentResult(runner)
                        messageBus.send(
                            ORCHESTRATOR_NAME,
                            TeamMessage.ResultReport(
                                from = identity.agentName,
                                taskId = "",
                                result = resultSummary,
                                success = true,
                            )
                        )

                        updateTeammateStatus(identity.agentName, AgentStatus.IDLE)
                        messageBus.send(
                            ORCHESTRATOR_NAME,
                            TeamMessage.IdleNotification(from = identity.agentName)
                        )
                    }
                    is WaitResult.Aborted -> {
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Teammate '${identity.agentName}' error: ${e.message}", e)
            onError("Agent '${identity.agentName}' 出错: ${e.message}")
            // 发送失败报告
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.ResultReport(
                    from = identity.agentName,
                    taskId = "",
                    result = "执行出错: ${e.message}",
                    success = false,
                )
            )
        } finally {
            updateTeammateStatus(identity.agentName, AgentStatus.COMPLETED)
            messageBus.removeInbox(identity.agentName)
            agentCompletionDeferreds.remove(identity.agentName)

            // 发射完成信号，供依赖流程等待
            val success = true // 如果走到这里说明没有未捕获异常
            agentCompletionFlow.emit(TeammateCompletion(identity.agentName, success))
            completionDeferred.complete(Unit)

            Log.d(TAG, "Teammate '${identity.agentName}' loop exited")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 消息等待
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 等待下一条消息（Sub-Agent 视角）。
     *
     * 对标蓝图中 waitForNextPromptOrShutdown 的优先级逻辑：
     * 1. shutdown_request — 最高优先级
     * 2. leader 消息 — 次优先
     * 3. 其他未读消息 — FIFO
     * 4. 任务列表 — 自动认领未分配任务
     *
     * @param identity Teammate 身份信息
     * @return 等待结果
     */
    private suspend fun waitForNextMessage(identity: TeammateIdentity): WaitResult {
        while (coroutineContext.isActive) {
            // 先排空邮箱中已有的消息（优先级处理）
            val msg = messageBus.tryReceive(identity.agentName)
            if (msg != null) {
                return when (msg) {
                    is TeamMessage.ShutdownRequest -> WaitResult.ShutdownRequest(msg)
                    is TeamMessage.Text -> WaitResult.NewMessage(msg.content, msg.from)
                    is TeamMessage.TaskAssignment -> WaitResult.NewMessage(
                        "任务: ${msg.subject}\n${msg.description}", msg.from
                    )
                    // 其他消息类型（IdleNotification, PermissionResponse 等）转为文本注入
                    else -> WaitResult.NewMessage(
                        "[${msg::class.simpleName}] from ${msg.from}", msg.from
                    )
                }
            }

            // 检查任务列表（对标 tryClaimNextTask）
            val task = taskManager.tryClaimNextTask(identity.teamName, identity.agentName)
            if (task != null) {
                Log.d(TAG, "Teammate '${identity.agentName}' claimed task #${task.id}")
                return WaitResult.TaskClaimed(
                    "任务 #${task.id}: ${task.subject}\n${task.description}"
                )
            }

            // 邮箱空且无可认领任务，挂起等待新消息到达
            // 使用 withTimeoutOrNull 挂起等待消息，超时后重新检查任务列表
            val received = try {
                withTimeoutOrNull(POLL_INTERVAL_MS) {
                    messageBus.receive(identity.agentName)
                }
            } catch (_: Exception) {
                null
            }

            if (received != null) {
                return when (received) {
                    is TeamMessage.ShutdownRequest -> WaitResult.ShutdownRequest(received)
                    is TeamMessage.Text -> WaitResult.NewMessage(received.content, received.from)
                    is TeamMessage.TaskAssignment -> WaitResult.NewMessage(
                        "任务: ${received.subject}\n${received.description}", received.from
                    )
                    else -> WaitResult.NewMessage(
                        "[${received::class.simpleName}] from ${received.from}", received.from
                    )
                }
            }
            // 超时后重新循环，先 tryReceive 再查任务列表
        }
        return WaitResult.Aborted
    }

    /**
     * 等待 Orchestrator 的下一条输入。
     *
     * Orchestrator 空闲时阻塞在 [MessageBus.receive] 上，等待用户干预或 Sub-Agent 消息。
     * 收到 ResultReport 时立即返回汇总结果，避免死锁。
     * 添加 10 分钟超时，防止所有 Sub-Agent 崩溃时 Orchestrator 永久挂起。
     *
     * @return 输入内容
     */
    private suspend fun waitForOrchestratorInput(): String {
        val timeoutMs = 10 * 60 * 1000L // 10 分钟
        while (coroutineContext.isActive) {
            val msg = withTimeoutOrNull(timeoutMs) {
                messageBus.receive(ORCHESTRATOR_NAME)
            }
            if (msg == null) {
                Log.w(TAG, "Orchestrator input timeout (${timeoutMs / 1000}s), checking state")
                if (isCompleted) break
                continue
            }
            when (msg) {
                is TeamMessage.Text -> {
                    return msg.content
                }
                is TeamMessage.ResultReport -> {
                    // 收到子 Agent 结果报告，立即返回汇总
                    Log.d(TAG, "Received result from '${msg.from}' while orchestrator waiting")
                    return buildString {
                        appendLine("<task-notification>")
                        appendLine("  <agent name=\"${msg.from}\">")
                        appendLine("    <status>${if (msg.success) "completed" else "failed"}</status>")
                        appendLine("    <result>${msg.result}</result>")
                        appendLine("  </agent>")
                        appendLine("</task-notification>")
                    }
                }
                is TeamMessage.IdleNotification -> {
                    Log.d(TAG, "Sub-agent '${msg.from}' is idle (orchestrator waiting for input)")
                }
                else -> {
                    Log.d(TAG, "Orchestrator received: ${msg::class.simpleName}")
                }
            }
        }
        error("Orchestrator coroutine cancelled or timed out")
    }

    /**
     * 等待所有 Sub-Agent 完成初始任务。
     *
     * 阻塞直到所有指定 Sub-Agent 发送 [TeamMessage.IdleNotification]。
     * 期间收到的非空闲消息会被缓冲并在等待结束后重新注入。
     * 添加超时机制，避免无限等待。
     *
     * @param subAgentNames 需要等待的 Sub-Agent 名称列表
     * @param timeoutMs 超时时间（毫秒），默认 5 分钟
     */
    private suspend fun waitForSubAgents(subAgentNames: List<String>, timeoutMs: Long = 300_000L) {
        val pending = subAgentNames.toMutableSet()
        val bufferedMessages = mutableListOf<TeamMessage>()
        val deadline = System.currentTimeMillis() + timeoutMs

        Log.d(TAG, "Waiting for ${pending.size} sub-agent(s): $pending")

        while (pending.isNotEmpty() && coroutineContext.isActive) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                Log.w(TAG, "Timeout waiting for sub-agents: $pending")
                break
            }

            val msg = try {
                withTimeoutOrNull(remaining) {
                    messageBus.receive(ORCHESTRATOR_NAME)
                }
            } catch (_: Exception) { null }

            if (msg == null) continue // 超时后重试检查 pending

            if (msg is TeamMessage.IdleNotification && msg.from in pending) {
                pending.remove(msg.from)
                Log.d(TAG, "Sub-agent '${msg.from}' completed, waiting for ${pending.size} more")
            } else {
                // 缓冲非空闲消息，等待结束后重新注入
                bufferedMessages.add(msg)
            }
        }

        // 重新注入缓冲的消息
        for (msg in bufferedMessages) {
            messageBus.send(ORCHESTRATOR_NAME, msg)
        }
    }

    /**
     * 等待指定 Agent 完成（通过 CompletableDeferred）。
     *
     * 替代 [waitForSubAgents] 在依赖流程中的使用，
     * 避免从 Orchestrator 收件箱直接读取消息导致的竞争条件。
     *
     * @param agentName 需要等待的 Agent 名称
     * @param timeoutMs 超时时间（毫秒），默认 5 分钟
     * @return true 如果 Agent 正常完成，false 如果超时
     */
    private suspend fun waitForAgentCompletion(agentName: String, timeoutMs: Long = 300_000L): Boolean {
        val deferred = agentCompletionDeferreds.getOrPut(agentName) { CompletableDeferred() }
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 等待所有 Sub-Agent 就绪（发送初始 IdleNotification）。
     *
     * 与 waitForSubAgents 的区别：
     * - waitForSubAgents 等待"任务执行完成"
     * - waitForAgentsReady 只等待"初始就绪"（Agent 创建后的第一条 IdleNotification）
     *
     * @param subAgentNames 需要等待的 Sub-Agent 名称列表
     * @param timeoutMs 超时时间（毫秒），默认 30 秒
     */
    private suspend fun waitForAgentsReady(subAgentNames: List<String>, timeoutMs: Long = 30_000L) {
        val pending = subAgentNames.toMutableSet()
        val deadline = System.currentTimeMillis() + timeoutMs

        Log.d(TAG, "Waiting for ${pending.size} agent(s) to be ready: $pending")

        while (pending.isNotEmpty() && coroutineContext.isActive) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                Log.w(TAG, "Timeout waiting for agents to be ready: $pending")
                break
            }

            val msg = try {
                withTimeoutOrNull(remaining) {
                    messageBus.receive(ORCHESTRATOR_NAME)
                }
            } catch (_: Exception) { null }

            if (msg is TeamMessage.IdleNotification && msg.from in pending) {
                pending.remove(msg.from)
                Log.d(TAG, "Agent '${msg.from}' is ready, waiting for ${pending.size} more")
            }
            // 忽略其他消息（它们会在后续循环中被处理）
        }
    }

    /**
     * 收集 Orchestrator 收件箱中待处理的 ResultReport 消息。
     *
     * 非阻塞地排空收件箱中的 ResultReport，返回结果列表。
     * 其他类型的消息会被重新投递。
     *
     * @return 待处理的结果列表（agentName -> result）
     */
    private suspend fun collectPendingResults(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val requeue = mutableListOf<TeamMessage>()

        // 非阻塞排空收件箱
        while (true) {
            val msg = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            when (msg) {
                is TeamMessage.ResultReport -> {
                    results.add(msg.from to msg.result)
                }
                else -> requeue.add(msg)
            }
        }

        // 重新投递非 ResultReport 消息
        for (msg in requeue) {
            messageBus.send(ORCHESTRATOR_NAME, msg)
        }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 双模式调度（已迁移至 tool call 处理，保留供依赖流程使用）
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 汇总 Sub-Agent 执行结果并注入 Orchestrator 上下文。
     *
     * 使用 system 角色 + task-notification XML 格式注入，避免污染 user 对话。
     * 对标 Claude Code 的 task-notification 机制。
     *
     * @param runner Orchestrator 的 AgentRunner
     * @param results Sub-Agent 执行结果列表（agentName -> result）
     */
    private suspend fun summarizeAndInject(runner: AgentRunner, results: List<Pair<String, String>>) {
        if (results.isEmpty()) return

        val summaryInput = buildString {
            appendLine("<task-notification>")
            for ((name, result) in results) {
                appendLine("  <agent name=\"$name\">")
                appendLine("    <status>completed</status>")
                appendLine("    <result>$result</result>")
                appendLine("  </agent>")
            }
            appendLine("</task-notification>")
        }

        // 用 system 角色注入，不污染 user 对话
        runner.injectMessage("system", summaryInput, isIntervention = false)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Sub-Agent 创建与结果收集
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 从 Orchestrator 指令批量创建 Sub-Agent。
     *
     * @param directive Orchestrator 解析出的创建指令
     * @param originalTask 原始用户任务（作为上下文注入 Sub-Agent）
     * @return 创建的 Sub-Agent 名称列表
     */
    private suspend fun spawnSubAgentsFromDirective(
        directive: OrchestratorDirective,
        originalTask: String,
    ): List<String> {
        val subAgentNames = mutableListOf<String>()

        for (spec in directive.agents) {
            // 不再在创建时发送任务，等 Agent 就绪后再分配
            try {
                val identity = spawnTeammate(
                    name = spec.name,
                    prompt = "等待主控分配任务...",
                    systemPrompt = spec.systemPrompt,
                    modelConfigId = spec.modelConfigId,
                )
                subAgentNames.add(identity.agentName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to spawn sub-agent '${spec.name}'", e)
            }
        }

        return subAgentNames
    }

    /**
     * 收集单个 Agent 的执行结果。
     *
     * 综合收集工具调用输出和最后的 assistant 消息，生成有意义的结果摘要。
     * 解决"无文本输出"问题：当 Agent 通过工具调用（如 write_file）完成工作时，
     * 工具调用的输出也应包含在结果中。
     *
     * @param runner Agent 的 AgentRunner
     * @return 结果摘要文本
     */
    private fun collectAgentResult(runner: AgentRunner): String {
        val history = runner.getHistory()
        val sb = StringBuilder()

        // 收集工具调用结果（write_file、create_directory 等）
        val toolResults = history.filter { it.role == "tool" && it.content.isNotBlank() }
        if (toolResults.isNotEmpty()) {
            sb.appendLine("【工具执行结果】")
            for (toolMsg in toolResults) {
                // 截取工具结果的关键信息，避免过长
                val content = toolMsg.content
                if (content.length > 500) {
                    sb.appendLine(content.take(500) + "...")
                } else {
                    sb.appendLine(content)
                }
            }
        }

        // 收集最后的 assistant 消息（Agent 的总结或说明）
        val lastAssistant = history.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
        if (lastAssistant != null) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("【Agent 输出】")
            sb.appendLine(lastAssistant.content)
        }

        return sb.toString().takeIf { it.isNotBlank() } ?: "子任务已完成，无文本输出"
    }

    /**
     * 收集所有 Sub-Agent 的执行结果。
     *
     * 优先从 Runner 历史中读取最后一条 assistant 消息作为结果。
     * 用于依赖流程中的结果收集。
     *
     * @param subAgentNames Sub-Agent 名称列表
     * @return 结果列表（Pair: agentName -> 最后一条 assistant 消息内容）
     */
    private fun collectResults(subAgentNames: List<String>): List<Pair<String, String>> {
        return subAgentNames.map { name ->
            val runner = runners[name]
            val result = if (runner != null) {
                collectAgentResult(runner)
            } else {
                "子任务已完成，无文本输出"
            }
            Pair(name, result)
        }
    }

    /**
     * 按依赖顺序创建并启动 Sub-Agent。
     *
     * 核心流程：
     * 1. 找出所有依赖已满足的 Agent（dependsOn 为空，或依赖的 Agent 都已完成）
     * 2. 创建这些 Agent（进入 IDLE 等待）
     * 3. 分配任务并等待执行完成
     * 4. 重复直到所有 Agent 完成
     *
     * @param directive Orchestrator 指令
     * @param originalTask 原始用户任务
     * @return 汇总结果文本，用于注入 Orchestrator 上下文
     */
    private suspend fun spawnWithDependencies(
        directive: OrchestratorDirective,
        originalTask: String,
    ): String {
        val allAgents = directive.agents.toMutableList()
        val completed = mutableSetOf<String>()
        val failed = mutableSetOf<String>()

        while (allAgents.isNotEmpty()) {
            // 找出当前可以创建的 Agent（依赖已满足，排除已失败的依赖）
            val ready = allAgents.filter { spec ->
                spec.dependsOn.all { dep -> dep in completed || dep in failed }
            }

            if (ready.isEmpty()) {
                Log.w(TAG, "Circular dependency or unresolvable deps. Remaining: ${allAgents.map { it.name }}")
                break
            }

            val spawnedNames = mutableListOf<String>()

            // 创建这些 Agent
            for (spec in ready) {
                try {
                    spawnTeammate(
                        name = spec.name,
                        prompt = "等待主控分配任务...",
                        systemPrompt = spec.systemPrompt,
                        modelConfigId = spec.modelConfigId,
                    )
                    spawnedNames.add(spec.name)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to spawn '${spec.name}'", e)
                    failed.add(spec.name)
                }
            }

            if (spawnedNames.isNotEmpty()) {
                // 为本批次创建 per-spawn 完成追踪
                val spawnCompletionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
                for (name in spawnedNames) {
                    spawnCompletionDeferreds[name] = CompletableDeferred()
                }

                // 注册一次性监听器：当 Agent 发送 IdleNotification 时完成对应 Deferred
                // 使用 take(1) 确保只处理每批次的第一个 IdleNotification
                parentScope.launch {
                    agentCompletionFlow
                        .filter { it.agentName in spawnedNames }
                        .take(spawnedNames.size)
                        .collect { completion ->
                            spawnCompletionDeferreds[completion.agentName]?.complete(Unit)
                        }
                }

                // 分配任务（根据 taskMode）
                when (directive.taskMode) {
                    TaskMode.DIRECT -> {
                        for (spec in ready) {
                            if (spec.name !in spawnedNames) continue
                            messageBus.send(
                                spec.name,
                                TeamMessage.TaskAssignment(
                                    from = ORCHESTRATOR_NAME,
                                    taskId = "",
                                    subject = spec.role.ifEmpty { "执行 ${spec.name} 的任务" },
                                    description = originalTask,
                                )
                            )
                            Log.d(TAG, "Direct mode: Assigned task to '${spec.name}'")
                        }
                    }
                    TaskMode.CLAIM -> {
                        val teamName = _teamState.value?.teamName ?: continue
                        for (spec in ready) {
                            if (spec.name !in spawnedNames) continue
                            taskManager.createTask(
                                teamName = teamName,
                                subject = spec.role.ifEmpty { spec.name },
                                description = originalTask,
                            )
                            Log.d(TAG, "Claim mode: Created task for '${spec.name}'")
                        }
                    }
                }

                // 等待本批次所有 Agent 完成（通过 CompletableDeferred，不读取 Orchestrator 收件箱）
                for (name in spawnedNames) {
                    val ok = withTimeoutOrNull(300_000L) {
                        spawnCompletionDeferreds[name]?.await()
                    }
                    if (ok == null) {
                        Log.w(TAG, "Timeout waiting for agent '$name' to complete")
                        failed.add(name)
                    }
                }

                // 收集结果并注入给依赖方
                val results = collectResults(spawnedNames)
                for ((name, result) in results) {
                    if (result.isNotBlank()) {
                        val dependents = allAgents.filter { spec -> name in spec.dependsOn }
                        for (dep in dependents) {
                            messageBus.send(
                                dep.name,
                                TeamMessage.Text(
                                    from = name,
                                    content = "【${name} 的产出】\n$result",
                                    summary = "${name} 完成",
                                )
                            )
                        }
                    }
                    completed.add(name)
                }
            }

            // 从待创建列表中移除已处理的（包括失败的）
            allAgents.removeAll(ready.toSet())
        }

        // 汇总最终结果（不注入消息历史，由调用方通过 MessageBus 正常处理）
        val allResults = collectResults(completed.toList())
        val summaryInput = allResults.joinToString("\n\n") { (name, result) ->
            "【${name}的结果】\n$result"
        }

        // 排空 Orchestrator 收件箱中已有的 ResultReport / IdleNotification 残余消息
        // 避免依赖链完成后这些消息被再次格式化为 <task-notification> 重复注入
        var drained = 0
        while (true) {
            val residual = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            drained++
            Log.d(TAG, "Drained residual message from orchestrator inbox: ${residual::class.simpleName} from ${residual.from}")
        }
        if (drained > 0) {
            Log.d(TAG, "Drained $drained residual message(s) from orchestrator inbox after dependency chain")
        }

        Log.d(TAG, "spawnWithDependencies completed. Agents: ${completed.size}, Failed: ${failed.size}")
        return summaryInput
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 工作区完成
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 触发工作区完成。
     *
     * 更新团队状态，释放 Sub-Agent 资源，通过回调通知 ViewModel 层。
     * 仅持久化 Orchestrator 的消息。
     *
     * @param runner Orchestrator 的 AgentRunner
     */
    private suspend fun triggerWorkspaceComplete(runner: AgentRunner) {
        if (isCompleted) return
        isCompleted = true

        Log.d(TAG, "Triggering workspace complete")

        // 获取 Orchestrator 的消息列表
        val orchestratorMessages = runner.getHistory()

        // 收集 Sub-Agent 消息和身份信息（在释放前）
        val subAgentMessages = mutableMapOf<String, List<AgentMessage>>()
        val subAgentIdentities = mutableMapOf<String, TeammateIdentity>()
        val state = _teamState.value
        if (state != null) {
            for ((name, teammateState) in state.teammates) {
                if (teammateState.isOrchestrator) continue
                subAgentMessages[name] = runners[name]?.getHistory() ?: emptyList()
                subAgentIdentities[name] = teammateState.identity
            }
        }

        // 更新团队状态
        _teamState.update { s ->
            s?.copy(isCompleted = true)
        }

        // 释放所有 Sub-Agent（不包括 Orchestrator）
        val subAgentNames = state?.teammates?.values
            ?.filter { !it.isOrchestrator }
            ?.map { it.identity.agentName }
            ?: emptyList()

        for (name in subAgentNames) {
            teammateScopes[name]?.cancel()
            teammateScopes.remove(name)
            teammateJobs.remove(name)
            runners[name]?.dispose()
            runners.remove(name)
            messageBus.removeInbox(name)
        }

        // 构建全量快照并通知 ViewModel 层
        val snapshot = TeamCompletionSnapshot(
            orchestratorMessages = orchestratorMessages,
            subAgentMessages = subAgentMessages,
            subAgentIdentities = subAgentIdentities,
        )
        onWorkspaceComplete(snapshot)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 内部工具方法
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 解析任务分配 JSON。
     *
     * 支持两种格式：
     * 1. 显式格式：`{"action": "assign_task", "to": "AgentName", "task": "...", "context": "..."}`
     * 2. 自然语言：从 "向AgentName分配..." 后面的 JSON 中提取
     *
     * @param content Orchestrator 输出文本
     * @param validAgents 有效的 Agent 名称列表
     * @return 任务分配映射表（targetAgent -> 任务信息）
     */
    private fun parseTaskAssignments(
        content: String,
        validAgents: List<String>,
    ): Map<String, TaskInfo> {
        val result = mutableMapOf<String, TaskInfo>()

        // 格式 1：action: "assign_task"
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

        // 格式 2：自然语言 "向{AgentName}分配...{JSON}"
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

    private data class TaskInfo(val task: String, val context: String)

    /**
     * 创建 AgentRunner，绑定统一的工具调用和流式回调。
     *
     * @param context Agent 运行时上下文
     * @param isSubAgent 是否为子 Agent（子 Agent 禁用编排工具）
     * @return AgentRunner 实例
     */
    private fun createAgentRunner(context: AgentContext, isSubAgent: Boolean = false): AgentRunner {
        return AgentRunner(
            context = context,
            mcpRuntimeManager = mcpRuntimeManager,
            crossSessionMemory = crossSessionMemoryText,
            disallowedTools = if (isSubAgent) ORCHESTRATOR_ONLY_TOOLS else emptySet(),
            onStreamChunk = onStreamChunk,
            onToolCall = { agentName, toolName, args, callId ->
                handleToolCall(agentName, toolName, args, callId)
            },
        )
    }

    /**
     * 处理工具调用。
     *
     * 通过 [McpRuntimeManager] 执行工具调用，返回结果。
     * 对于 Orchestrator 专属工具（create_agents、assign_task），直接在本地处理。
     *
     * @param agentName Agent 名称
     * @param toolName 工具名称
     * @param args 工具参数
     * @param callId 工具调用 ID
     * @return 工具执行结果
     */
    private suspend fun handleToolCall(
        agentName: String,
        toolName: String,
        args: JSONObject,
        callId: String,
    ): String {
        // ── 拦截 Orchestrator 专属编排工具 ──
        when (toolName) {
            "create_agents" -> return handleCreateAgentsTool(args)
            "assign_task" -> return handleAssignTaskTool(args)
        }

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
        } catch (e: Exception) {
            Log.e(TAG, "Tool call failed: $toolName", e)
            "Error: ${e.message}"
        } finally {
            onAgentStatusChanged(agentName, AgentStatus.STREAMING)
        }
    }

    /**
     * 处理 create_agents 工具调用。
     *
     * 从结构化参数中解析 Agent 规格，创建团队成员并等待就绪。
     * 返回给 LLM 的是确认消息，LLM 据此决定后续调度。
     *
     * @param args create_agents 工具的参数
     * @return 执行结果文本
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
                    modelConfigId = null,
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

        if (agentSpecs.isEmpty()) {
            return "Error: 未能解析出有效的 Agent 定义"
        }

        Log.d(TAG, "create_agents tool: ${agentSpecs.size} agent(s), mode=$taskMode")

        val directive = OrchestratorDirective(agentSpecs, taskMode)
        val state = _teamState.value
        val originalTask = runners[ORCHESTRATOR_NAME]?.getHistory()
            ?.lastOrNull { it.role == "user" }?.content ?: ""

        // 检查是否有依赖关系
        val hasDependencies = agentSpecs.any { it.dependsOn.isNotEmpty() }

        if (hasDependencies) {
            // 有依赖：同步执行，等待所有依赖链完成后再返回
            val summary = try {
                spawnWithDependencies(directive, originalTask)
            } catch (e: Exception) {
                Log.e(TAG, "spawnWithDependencies failed", e)
                return "Error: 依赖流程执行失败: ${e.message}"
            }
            // 返回汇总结果，明确告知 LLM 所有 Agent 已自动完成，不需要再调用 assign_task
            return buildString {
                appendLine("已按依赖顺序创建并执行 ${agentSpecs.size} 个 Agent，全部完成。")
                appendLine("所有 Agent 已自动执行完毕并返回结果，无需再调用 assign_task。")
                appendLine("请直接根据以下结果汇总回复用户：")
                appendLine()
                appendLine(summary)
            }
        }

        // 无依赖：同步创建并等待就绪
        val subAgentNames = spawnSubAgentsFromDirective(directive, originalTask)

        if (subAgentNames.isEmpty()) {
            return "Error: 所有 Agent 创建失败"
        }

        // 如果是 claim 模式，创建任务到 TaskManager
        if (taskMode == TaskMode.CLAIM) {
            val teamName = state?.teamName ?: ""
            for (spec in agentSpecs) {
                taskManager.createTask(
                    teamName = teamName,
                    subject = spec.role.ifEmpty { spec.name },
                    description = originalTask,
                )
            }
        }

        // 等待所有 Agent 就绪
        waitForAgentsReady(subAgentNames)

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
     *
     * 通过 MessageBus 向目标 Agent 发送 TaskAssignment。
     *
     * @param args assign_task 工具的参数
     * @return 执行结果文本
     */
    private suspend fun handleAssignTaskTool(args: JSONObject): String {
        val to = args.optString("to")
        val task = args.optString("task")
        val context = args.optString("context", "")

        if (to.isEmpty() || task.isEmpty()) {
            return "Error: 'to' 和 'task' 参数必填"
        }

        val state = _teamState.value
        if (state == null || to !in state.teammates) {
            return "Error: Agent '$to' 不存在"
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
     * 更新指定 Teammate 的状态。
     *
     * 同时更新 [_teamState] 和触发 [onAgentStatusChanged] 回调。
     *
     * @param agentName Agent 名称
     * @param status 新状态
     */
    private fun updateTeammateStatus(agentName: String, status: AgentStatus) {
        _teamState.update { state ->
            state?.copy(
                teammates = state.teammates.toMutableMap().apply {
                    val existing = this[agentName] ?: return@update state
                    this[agentName] = existing.copy(status = status)
                }
            )
        }
        onAgentStatusChanged(agentName, status)
    }

    /**
     * 生成唯一名称。
     *
     * 如果 desiredName 已存在，追加数字后缀（如 "researcher2"）。
     *
     * @param desiredName 期望名称
     * @param existingNames 已存在的名称集合
     * @return 唯一名称
     */
    private fun generateUniqueName(desiredName: String, existingNames: Set<String>): String {
        if (desiredName !in existingNames) return desiredName
        var counter = 2
        while ("${desiredName}${counter}" in existingNames) {
            counter++
        }
        return "${desiredName}${counter}"
    }

    /**
     * 分配 Agent UI 标识色。
     *
     * 从预定义颜色列表中循环分配。
     *
     * @return 颜色值（#RRGGBB 格式）
     */
    private fun assignColor(): String {
        val color = AGENT_COLORS[colorIndex % AGENT_COLORS.size]
        colorIndex++
        return color
    }

    /**
     * 构建任务分配消息。
     *
     * 格式：`{"task": "任务描述", "context": "上下文信息"}`
     * 对标 AgentMessageBus.buildTaskAssignment。
     *
     * @param task 任务描述
     * @param context 上下文信息
     * @return JSON 格式的任务分配消息
     */
    private fun buildTaskAssignment(task: String, context: String): String {
        return JSONObject().apply {
            put("task", task)
            put("context", context)
        }.toString()
    }

    /**
     * Orchestrator 输出解析失败时的重试提示（已废弃，改为 tool call）。
     * 保留供 parseOrchestratorOutputRobust 使用。
     */
    private val PARSE_RETRY_PROMPT = """
你的输出中没有包含有效的创建 Sub-Agent 指令。请使用 create_agents 工具创建团队。

如果当前任务不需要创建 Sub-Agent，请直接输出【任务完成】。
""".trimIndent()

    // ═══════════════════════════════════════════════════════════════════════════════
    // Orchestrator 输出解析
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 增强版 Orchestrator 输出解析（多策略 fallback）。
     *
     * 按优先级尝试以下策略：
     * 1. 标准 `create_agents` JSON 解析
     * 2. Markdown 代码块中的 JSON 提取
     * 3. 自然语言中 Agent 规格提取
     *
     * @param output Orchestrator 输出文本
     * @return 解析后的 [OrchestratorDirective]，所有策略失败则返回 null
     */
    fun parseOrchestratorOutputRobust(output: String): OrchestratorDirective? {
        // 策略 1：标准 JSON 解析
        parseOrchestratorOutput(output)?.let { return it }

        // 策略 2：从 markdown 代码块中提取 JSON
        extractJsonFromCodeBlock(output)?.let { jsonStr ->
            parseJsonString(jsonStr)?.let { return it }
        }

        // 策略 3：自然语言提取 Agent 规格
        extractAgentsFromNaturalLanguage(output)?.let { return it }

        return null
    }

    /**
     * 从 markdown 代码块中提取 JSON 字符串。
     *
     * 处理 LLM 输出 ```json ... ``` 或 ``` ... ``` 包裹的 JSON。
     *
     * @param text 包含代码块的文本
     * @return 提取的 JSON 字符串，无代码块则返回 null
     */
    private fun extractJsonFromCodeBlock(text: String): String? {
        val codeBlockPattern = """```(?:json)?\s*\n?(.*?)\n?\s*```""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(text) ?: return null
        val blockContent = match.groupValues[1].trim()
        // 只返回包含 create_agents 的代码块
        return if (blockContent.contains("create_agents")) blockContent else null
    }

    /**
     * 从自然语言中提取 Agent 规格。
     *
     * 处理 LLM 输出类似 "我将创建以下 Agent：\n- Agent1: 执行代码编写\n- Agent2: 执行代码审查"
     * 的自然语言格式，尝试构建 OrchestratorDirective。
     *
     * @param text Orchestrator 输出文本
     * @return 解析后的 [OrchestratorDirective]，无法提取则返回 null
     */
    private fun extractAgentsFromNaturalLanguage(text: String): OrchestratorDirective? {
        // 匹配 "创建以下Agent" / "create the following agents" 等意图
        val intentPattern = """(?:创建|创建以下|spawn|create)\s*(?:以下)?\s*(?:Agent|agent|子Agent|Sub-Agent)""".toRegex(RegexOption.IGNORE_CASE)
        if (!intentPattern.containsMatchIn(text)) return null

        // 匹配列表项格式："- AgentName: role" 或 "* AgentName — role" 或 "1. AgentName (role)"
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
     *
     * @param jsonStr JSON 字符串
     * @return 解析后的 [OrchestratorDirective]，解析失败返回 null
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
     *
     * 需求 4.1：解析 `{"action": "create_agents", "agents": [...]}` JSON 指令。
     * 从 WorkspaceOrchestrator 迁移，逻辑保持一致。
     *
     * @param output Orchestrator 的输出文本
     * @return 解析后的 [OrchestratorDirective]，如果未找到有效指令则返回 null
     */
    fun parseOrchestratorOutput(output: String): OrchestratorDirective? {
        val actionPattern = """['"]?action['"]?\s*:\s*['"]create_agents['"]""".toRegex()
        val matchResult = actionPattern.find(output) ?: return null

        val startIndex = output.lastIndexOf("{", matchResult.range.first)
        if (startIndex == -1) return null

        val jsonStr = extractJsonObject(output, startIndex) ?: return null

        return try {
            val json = JSONObject(jsonStr)
            if (json.optString("action") != "create_agents") return null

            // 解析 taskMode
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
            Log.e(TAG, "Failed to parse Orchestrator output", e)
            null
        }
    }

    /**
     * 从字符串中提取完整的 JSON 对象。
     *
     * 从指定起始位置开始，找到匹配的闭合大括号。
     * 从 WorkspaceOrchestrator 迁移，逻辑保持一致。
     *
     * @param text 输入文本
     * @param startIndex JSON 起始位置
     * @return 提取的 JSON 字符串，如果提取失败则返回 null
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

    // ═══════════════════════════════════════════════════════════════════════════════
    // 内部数据类
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 任务分配模式枚举。
     */
    enum class TaskMode {
        DIRECT,
        CLAIM
    }

    /**
     * Orchestrator 指令。
     *
     * @property agents 要创建的 Agent 规格列表
     * @property taskMode 任务分配模式，默认 CLAIM
     */
    data class OrchestratorDirective(
        val agents: List<AgentSpec>,
        val taskMode: TaskMode = TaskMode.CLAIM
    )

    /**
     * Agent 规格。
     *
     * @property name Agent 名称
     * @property role 角色描述
     * @property systemPrompt 系统提示
     * @property modelConfigId 模型配置 ID（可选）
     * @property dependsOn 依赖的 Agent 名称列表
     */
    data class AgentSpec(
        val name: String,
        val role: String,
        val systemPrompt: String,
        val modelConfigId: Long?,
        val dependsOn: List<String> = emptyList(),
    )

    // ═══════════════════════════════════════════════════════════════════════════════
    // 默认 Teammate 系统提示
    // ═══════════════════════════════════════════════════════════════════════════════

    private val DEFAULT_TEAMMATE_PROMPT = """
你是一个多 Agent 工作区中的子 Agent。你的职责是：
1. 执行分配给你的具体子任务。
2. 使用可用的工具完成工作。
3. 完成后清晰地汇报执行结果。
4. 如果遇到无法解决的问题，明确说明原因。

## 文件系统环境
本设备为 Android 系统。文件系统工具（list_directory、search_files 等）的根目录为 "/"，对应设备存储根路径。
- 下载目录: /Download（注意：不是 ~/Downloads，也不是 /home/xxx/Downloads，也不是 /sdcard/Download）
- 文档目录: /Documents
- 图片目录: /Pictures
- 使用路径时请直接用 "/" 开头的绝对路径，或 "." 表示根目录。不要在路径前面加 /sdcard/ 前缀。

可用工具：
[MCP_TOOLS]
"""

    /**
     * 加载跨会话记忆并构建注入文本。
     *
     * 从数据库读取所有 MemoryItem，按置信度排序，取前 [MEMORY_INJECT_LIMIT] 条，
     * 格式化为系统提示可注入的文本。
     *
     * @return 格式化的记忆文本，无记忆时返回空字符串
     */
    private suspend fun loadCrossSessionMemory(): String {
        if (!config.enableCrossSessionMemory) return ""
        return try {
            val memories = repository.getAllMemories().take(config.memoryInjectLimit)
            if (memories.isEmpty()) return ""

            val sb = StringBuilder("以下是关于用户的历史偏好和记忆：\n")
            for ((index, item) in memories.withIndex()) {
                val pinMark = if (item.pinned) " [已锁定]" else ""
                sb.appendLine("${index + 1}. ${item.content}$pinMark")
            }
            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cross-session memory", e)
            ""
        }
    }
}
