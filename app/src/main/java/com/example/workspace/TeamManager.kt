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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    // WHY: @Volatile 只保证可见性不保证原子性，并发调用 triggerWorkspaceComplete
    // 可能导致 onWorkspaceComplete 触发两次。AtomicBoolean.compareAndSet 保证原子切换。
    private val isCompleted = AtomicBoolean(false)

    // ─── Agent 完成信号（用于依赖流程） ───

    /** 用于等待特定 Agent 完成的 Deferred 映射 */
    private val agentCompletionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // ─── 颜色分配索引 ───

    private val colorIndex = AtomicInteger(0)

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

        // 启动执行循环，实际任务由 TaskAssignment 消息触发
        val job = teammateScope.launch {
            runTeammateLoop(runner, identity)
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
     * 继续与已有 Agent 对话。
     *
     * 对标 Claude Code 的 SendMessage 工具，允许 Orchestrator 向已存在的 Agent
     * 发送后续消息，利用其已加载的上下文继续工作。
     *
     * 如果目标 Agent 处于 IDLE 状态，直接发送消息。
     * 如果目标 Agent 已完成（COMPLETED），会尝试恢复执行。
     *
     * @param agentName 目标 Agent 名称
     * @param message 消息内容
     * @return 操作结果描述
     */
    suspend fun continueAgent(agentName: String, message: String): String {
        val state = _teamState.value ?: return "Error: 无活跃团队"
        
        if (agentName !in state.teammates) {
            return "Error: Agent '$agentName' 不存在"
        }
        
        if (agentName == ORCHESTRATOR_NAME) {
            return "Error: 不能向 Orchestrator 发送 continue 消息，请使用用户干预"
        }

        val teammateState = state.teammates[agentName]!!
        
        // WHY: 目标 agent 协程已退出时，消息发到无人读取的 channel，orchestrator 会永久等待响应
        if (teammateJobs[agentName]?.isActive != true) {
            return "Error: Agent '$agentName' 已结束，无法继续对话"
        }
        
        Log.d(TAG, "Continue agent '$agentName' (status: ${teammateState.status})")
        
        // 通过 MessageBus 发送消息
        // Teammate 的执行循环会从收件箱中读取消息并继续执行
        messageBus.send(
            agentName,
            TeamMessage.Text(from = ORCHESTRATOR_NAME, content = message)
        )
        
        return "已向 $agentName 发送继续消息，等待其响应..."
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
     * Sub-Agent 间直接通信。
     *
     * 对标 Claude Code 的 SendMessage 工具，允许 Sub-Agent 向其他 Sub-Agent 发送消息。
     * 支持点对点发送和广播（to = "*"）。
     *
     * @param from 发送方 Agent 名称
     * @param to 接收方 Agent 名称，"*" 表示广播给所有其他 Sub-Agent
     * @param message 消息内容
     * @param summary 消息摘要（可选）
     * @return 操作结果描述
     */
    suspend fun sendPeerMessage(
        from: String,
        to: String,
        message: String,
        summary: String = "",
    ): String {
        val state = _teamState.value ?: return "Error: 无活跃团队"

        // 广播模式
        if (to == "*") {
            val subAgentNames = state.teammates.values
                .filter { !it.isOrchestrator && it.identity.agentName != from }
                .map { it.identity.agentName }

            if (subAgentNames.isEmpty()) {
                return "Error: 没有其他 Sub-Agent 可以接收消息"
            }

            messageBus.broadcast(
                senderName = from,
                message = TeamMessage.Text(
                    from = from,
                    content = message,
                    summary = summary,
                ),
                teamMembers = subAgentNames,
            )

            Log.d(TAG, "Peer broadcast from '$from' to ${subAgentNames.size} agents")
            return "已广播给 ${subAgentNames.size} 个 Agent: ${subAgentNames.joinToString(", ")}"
        }

        // 点对点模式
        if (to !in state.teammates) {
            return "Error: 目标 Agent '$to' 不存在"
        }

        if (to == from) {
            return "Error: 不能向自己发送消息"
        }

        messageBus.send(
            to,
            TeamMessage.Text(
                from = from,
                content = message,
                summary = summary,
            ),
        )

        Log.d(TAG, "Peer message from '$from' to '$to'")
        return "已向 $to 发送消息"
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
     * 取消其协程作用域，等待 Job 完成后再清理资源。
     * 确保 finally 块中的清理逻辑能够正常执行。
     *
     * @param agentName 目标 Agent 名称
     */
    suspend fun killTeammate(agentName: String) {
        Log.d(TAG, "Killing teammate '$agentName'")
        
        // 取消协程作用域
        teammateScopes[agentName]?.cancel()
        
        // 等待 Job 完成，确保 finally 块执行完毕
        try {
            teammateJobs[agentName]?.join()
        } catch (_: Exception) { }
        
        // Job 完成后再清理资源
        teammateScopes.remove(agentName)
        teammateJobs.remove(agentName)
        runners[agentName]?.dispose()
        runners.remove(agentName)
        messageBus.removeInbox(agentName)
        agentCompletionDeferreds.remove(agentName)

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
        Log.d(TAG, "deleteTeam: Current teammateJobs keys = ${teammateJobs.keys}")

        // 取消所有 Teammate 协程
        teammateScopes.values.forEach { it.cancel() }
        // 等待所有任务完成
        val currentJob = coroutineContext[Job]
        teammateJobs.forEach { (name, job) ->
            if (job !== currentJob) {
                try {
                    Log.d(TAG, "deleteTeam: joining job for '$name'...")
                    job.join()
                    Log.d(TAG, "deleteTeam: joined job for '$name' successfully")
                } catch (e: Exception) { 
                    Log.d(TAG, "deleteTeam: error joining job for '$name': ${e.message}")
                }
            } else {
                Log.d(TAG, "deleteTeam: skipping join for current job '$name'")
            }
        }

        // 清理运行时
        teammateScopes.clear()
        teammateJobs.clear()
        runners.values.forEach { it.dispose() }
        runners.clear()
        messageBus.clear()
        
        // 清理完成信号（先完成所有 Deferred，避免等待的协程永久挂起）
        agentCompletionDeferreds.values.forEach { it.complete(Unit) }
        agentCompletionDeferreds.clear()

        // 清理持久化数据
        repository.deleteAllTeamTasks(state.teamName)

        isCompleted.set(false)
        colorIndex.set(0)
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
     * 要求完成标记出现在文本末尾（允许尾随空白/标点），避免文本中间偶然包含标记时误触发。
     *
     * @param text 输出文本
     * @return true 如果包含完成标记
     */
    fun isCompletionMarker(text: String): Boolean {
        val trimmed = text.trimEnd()
        // 使用 endsWith 而非 contains：Orchestrator 讨论工作流时可能提到"请输出【任务完成】"，
        // contains 会将其误判为完成信号导致工作区提前终止。只在文本末尾匹配可排除此类误触发。
        return trimmed.endsWith(COMPLETION_MARKER)
            || trimmed.endsWith("【任务完成】。")
            || trimmed.endsWith("【任务完成】！")
            || trimmed.endsWith("【任务完成】!")
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
        // 会话恢复场景：跳过首轮 LLM 调用，直接等待用户输入（BUG-1：改为循环，消除尾递归）
        var currentInput = if (initialTask.isBlank()) {
            updateTeammateStatus(ORCHESTRATOR_NAME, AgentStatus.IDLE)
            waitForOrchestratorInput()
        } else {
            initialTask
        }

        while (!isCompleted.get() && coroutineContext.isActive) {
            // 先检查是否有 Sub-Agent 结果待汇总（在 runTurn 之前注入）
            val pendingResults = collectPendingResults()
            if (pendingResults.isNotEmpty()) {
                Log.d(TAG, "Collected ${pendingResults.size} agent result(s), injecting into context")
                summarizeAndInject(runner, pendingResults)
            }

            updateTeammateStatus(ORCHESTRATOR_NAME, AgentStatus.STREAMING)

            // 执行一轮对话（内部可能触发 create_agents / assign_task 工具调用）
            runner.runTurn(currentInput)

            // 排空 spawnWithDependencies 等工具调用产生的残留 IdleNotification，
            // 防止下次 collectPendingResults 将其作为新消息重复处理
            drainExcessIdleNotifications()

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
    ) {
        // 注册完成 Deferred，供 spawnWithDependencies 等待
        // 使用 getOrPut 避免覆盖 spawnWithDependencies 已注册的 Deferred
        val completionDeferred = agentCompletionDeferreds.getOrPut(identity.agentName) {
            CompletableDeferred()
        }

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

            while (coroutineContext.isActive) {
                // 等待收到 TaskAssignment 或 Text 消息后才执行
                val waitResult = waitForNextMessage(identity)

                when (waitResult) {
                    is WaitResult.ShutdownRequest -> {
                        Log.d(TAG, "Teammate '${identity.agentName}' received shutdown request")
                        break
                    }
                    is WaitResult.NewMessage -> {
                        executeTask(runner, identity, waitResult.message)
                    }
                    is WaitResult.TaskClaimed -> {
                        executeTask(runner, identity, waitResult.taskDescription)
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
            // WHY: 执行失败时必须更新任务状态为 FAILED，否则任务永远卡在 IN_PROGRESS
            taskManager.failTask(identity.teamName, identity.agentName)
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

            // WHY: completionDeferred 就是 agentCompletionDeferreds[identity.agentName]
            // 同一对象，只调用一次 complete() 即可；调用两次第二次会静默返回 false 但属于多余操作。
            // 先 complete 再 remove，保证 waitForAgentCompletion 中的 await() 能在 remove 前收到信号。
            completionDeferred.complete(Unit)
            agentCompletionDeferreds.remove(identity.agentName)

            Log.d(TAG, "Teammate '${identity.agentName}' loop exited")
        }
    }

    /**
     * 执行任务并上报结果（NewMessage / TaskClaimed 共用逻辑）。
     *
     * @param runner Teammate 的 AgentRunner
     * @param identity Teammate 身份信息
     * @param taskPrompt 任务描述文本
     */
    private suspend fun executeTask(
        runner: AgentRunner,
        identity: TeammateIdentity,
        taskPrompt: String,
    ) {
        // 记录当前消息偏移量，避免 continueAgent 场景下 collectAgentResult 混入前序任务的工具输出
        val messageOffset = runner.getHistory().size
        updateTeammateStatus(identity.agentName, AgentStatus.STREAMING)
        runner.runTurn(taskPrompt)

        val resultSummary = collectAgentResult(runner, fromIndex = messageOffset)

        // WHY: 检查最后消息是否包含错误，设置准确的 success 状态
        val history = runner.getHistory()
        val lastContent = history.lastOrNull()?.content ?: ""
        val hasError = lastContent.startsWith("ERROR:") || lastContent.contains("执行出错")

        messageBus.send(
            ORCHESTRATOR_NAME,
            TeamMessage.ResultReport(
                from = identity.agentName,
                taskId = "",
                result = resultSummary,
                success = !hasError,
            )
        )

        // WHY: 任务完成后必须更新状态为 COMPLETED，否则任务永远卡在 IN_PROGRESS
        taskManager.completeTask(identity.teamName, identity.agentName)

        updateTeammateStatus(identity.agentName, AgentStatus.IDLE)
        messageBus.send(
            ORCHESTRATOR_NAME,
            TeamMessage.IdleNotification(
                from = identity.agentName,
                idleReason = IdleReason.AVAILABLE,
            )
        )
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
                // 认领成功后再检查一次收件箱。
                // 竞态窗口：tryReceive 返回 null → tryClaimNextTask 期间 → 新消息到达。
                // 若不补查，该消息会留在收件箱中直到下一轮循环，可能丢失 ShutdownRequest 等高优先级消息。
                // ShutdownRequest 和 Text 优先于已认领的任务返回；其他消息类型（IdleNotification 等）可延后处理。
                val pendingMsg = messageBus.tryReceive(identity.agentName)
                if (pendingMsg != null) {
                    when (pendingMsg) {
                        is TeamMessage.ShutdownRequest -> return WaitResult.ShutdownRequest(pendingMsg)
                        is TeamMessage.Text -> return WaitResult.NewMessage(pendingMsg.content, pendingMsg.from)
                        else -> { /* 其他消息类型忽略，优先处理已认领的任务 */ }
                    }
                }
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
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                // Channel 已关闭，Agent 正在退出
                Log.d(TAG, "Channel closed for ${identity.agentName}")
                return WaitResult.Aborted
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
     * 添加 10 分钟超时和连续超时计数器，防止所有 Sub-Agent 崩溃时 Orchestrator 永久挂起。
     *
     * @return 输入内容
     */
    private suspend fun waitForOrchestratorInput(): String {
        val timeoutMs = 10 * 60 * 1000L // 10 分钟
        val maxConsecutiveTimeouts = 3 // 最多连续超时 3 次（30 分钟）
        var consecutiveTimeouts = 0

        // 排空积累的 IdleNotification，避免阻塞在空闲通知上
        drainExcessIdleNotifications()

        while (coroutineContext.isActive) {
            val msg = withTimeoutOrNull(timeoutMs) {
                messageBus.receive(ORCHESTRATOR_NAME)
            }
            if (msg == null) {
                consecutiveTimeouts++
                Log.w(TAG, "Orchestrator input timeout ($consecutiveTimeouts/$maxConsecutiveTimeouts)")
                if (isCompleted.get() || consecutiveTimeouts >= maxConsecutiveTimeouts) {
                    Log.w(TAG, "Orchestrator giving up after $consecutiveTimeouts timeouts")
                    // 超时退出前排空多余的 IdleNotification，防止下次调用时积累
                    drainExcessIdleNotifications()
                    // WHY: 超时退出时必须标记 isCompleted，否则 ViewModel 捕获异常后
                    // 团队状态停留在非完成状态，UI 无法正确显示工作区已结束
                    if (isCompleted.compareAndSet(false, true)) {
                        _teamState.update { s -> s?.copy(isCompleted = true) }
                    }
                    break
                }
                continue
            }
            // 收到消息，重置超时计数器
            consecutiveTimeouts = 0
            when (msg) {
                is TeamMessage.Text -> {
                    return msg.content
                }
                is TeamMessage.ResultReport -> {
                    // 收到子 Agent 结果报告，立即返回汇总
                    Log.d(TAG, "Received result from '${msg.from}' while orchestrator waiting")

                    // WHY: 如果工作区已完成（triggerWorkspaceComplete 已调用），
                    // 这是 spawnWithDependencies 之后 Agent 协程延迟发出的残留 ResultReport，
                    // 直接丢弃，不再注入 Orchestrator 上下文，避免触发多余的 LLM 轮次。
                    if (isCompleted.get()) {
                        Log.d(TAG, "Workspace already completed, dropping late ResultReport from '${msg.from}'")
                        continue
                    }
                    
                    // 从对应的 AgentRunner 获取 usage 数据
                    val stats = runners[msg.from]?.getUsageStats()
                    
                    return buildString {
                        appendLine("<task-notification>")
                        appendLine("  <task-id>${msg.from}</task-id>")
                        appendLine("  <status>${if (msg.success) "completed" else "failed"}</status>")
                        appendLine("  <summary>Agent '${msg.from}' ${if (msg.success) "completed" else "failed"}</summary>")
                        appendLine("  <result>${msg.result}</result>")
                        appendLine("  <usage>")
                        appendLine("    <total_tokens>${stats?.totalTokens ?: 0}</total_tokens>")
                        appendLine("    <tool_uses>${stats?.toolUseCount ?: 0}</tool_uses>")
                        appendLine("    <duration_ms>${stats?.durationMs ?: 0}</duration_ms>")
                        appendLine("  </usage>")
                        appendLine("</task-notification>")
                    }
                }
                is TeamMessage.IdleNotification -> {
                    // 正常的单条通知仍正常处理（排空函数已移除多余的）
                    Log.d(TAG, "Sub-agent '${msg.from}' is idle (orchestrator waiting for input)")
                }
                else -> {
                    Log.d(TAG, "Orchestrator received: ${msg::class.simpleName}")
                }
            }
        }
        // WHY: 到这里有两种情况：
        // 1. 超时正常退出（isCompleted 已设为 true，_teamState 已更新）—— return 空字符串，
        //    Orchestrator 循环将检测到 isCompleted.get() == true 并正常退出。
        // 2. 协程被外部取消（coroutineContext.isActive == false）——
        //    while 循环已因 isActive 判断圆满退出，同样 return 空字符串。
        // 不再使用 error() 招致 ViewModel 层收到非预期的崩溃异常。
        return ""
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
            } else if (msg != null) {
                // WHY: 非 IdleNotification 消息必须 requeue，否则 ResultReport/用户干预会被永久丢弃
                messageBus.requeue(ORCHESTRATOR_NAME, msg)
            }
        }
    }

    /**
     * 收集 Orchestrator 收件箱中待处理的 ResultReport 消息。
     *
     * 非阻塞地排空收件箱中的 ResultReport，返回结果列表。
     * 其他类型的消息会被重新投递（不触发 globalEvents，避免重复广播）。
     *
     * @return 待处理的结果列表（agentName -> result）
     */
    private suspend fun collectPendingResults(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val requeueMessages = mutableListOf<TeamMessage>()

        // 非阻塞排空收件箱
        while (true) {
            val msg = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            when (msg) {
                is TeamMessage.ResultReport -> {
                    results.add(msg.from to msg.result)
                }
                else -> requeueMessages.add(msg)
            }
        }

        // 重新投递非 ResultReport 消息（使用 requeue 避免重复广播）
        for (msg in requeueMessages) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }

        return results
    }

    /**
     * 排空 Orchestrator 收件箱中多余的 IdleNotification。
     *
     * 防止 Sub-Agent 完成任务后发送的 IdleNotification 无限堆积。
     * 每个 Agent 保留一条最新的 IdleNotification（通过 requeue 投递），
     * 其余丢弃。排空后收件箱中只保留非 IdleNotification 消息和每个 Agent 一条空闲通知。
     *
     * 注意：requeue 不触发 globalEvents。当前无外部订阅者依赖 globalEvents，
     * Orchestrator 循环通过直接读取收件箱处理消息，不依赖事件流。
     */
    private suspend fun drainExcessIdleNotifications() {
        val kept = mutableMapOf<String, TeamMessage.IdleNotification>()
        val requeueMessages = mutableListOf<TeamMessage>()

        while (true) {
            val msg = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            when (msg) {
                is TeamMessage.IdleNotification -> {
                    val existing = kept[msg.from]
                    if (existing == null) {
                        kept[msg.from] = msg
                    } else if (msg.timestamp > existing.timestamp) {
                        // 保留最新的，旧的丢弃
                        kept[msg.from] = msg
                    }
                    // else: 旧消息，丢弃
                }
                else -> requeueMessages.add(msg)
            }
        }

        // 重新投递非 IdleNotification 消息和每个 Agent 最新的一条通知
        for (msg in requeueMessages) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }
        for (msg in kept.values) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }
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

        // WHY: 每个 Agent 生成独立的 <task-notification> 块，与 waitForOrchestratorInput
        // 中 ResultReport 的格式保持一致，避免 LLM 解析多个 <task-id>/<status> 时混乱。
        // 同时正确反映 success/failure 状态（而非硬编码 "completed"）。
        val summaryInput = buildString {
            for ((name, result) in results) {
                // 通过检查 result 是否包含错误标记判断成功/失败
                val success = !result.startsWith("ERROR:") && !result.contains("执行出错") && !result.contains("执行失败")
                val status = if (success) "completed" else "failed"
                val stats = runners[name]?.getUsageStats()
                appendLine("<task-notification>")
                appendLine("  <task-id>$name</task-id>")
                appendLine("  <status>$status</status>")
                appendLine("  <summary>Agent '$name' ${if (success) "completed" else "failed"}</summary>")
                appendLine("  <result>$result</result>")
                appendLine("  <usage>")
                appendLine("    <total_tokens>${stats?.totalTokens ?: 0}</total_tokens>")
                appendLine("    <tool_uses>${stats?.toolUseCount ?: 0}</tool_uses>")
                appendLine("    <duration_ms>${stats?.durationMs ?: 0}</duration_ms>")
                appendLine("  </usage>")
                appendLine("</task-notification>")
            }
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
     * @param fromIndex 消息历史起始索引（含），只收集此索引之后的消息，避免跨任务污染
     * @return 结果摘要文本
     */
    /**
     * 收集单个 Agent 的执行结果。
     *
     * @param runner Agent 的 AgentRunner
     * @param fromIndex 消息历史起始索引（含），只收集此索引之后的消息
     * @param forDependency 是否用于依赖传递（true = 只传 assistant 文本，不传原始工具 JSON）
     * @return 结果摘要文本
     */
    private fun collectAgentResult(runner: AgentRunner, fromIndex: Int = 0, forDependency: Boolean = false): String {
        val history = runner.getHistory()
        // WHY: coerceIn 确保 fromIndex 处于 [0, history.size] 内，
        // 边界情况安全：subList(n, n) 返回空列表，而非完整 history。
        val taskMessages = history.subList(fromIndex.coerceIn(0, history.size), history.size)
        val sb = StringBuilder()

        if (forDependency) {
            // 依赖传递场景：只传 assistant 的文本输出，不传原始工具 JSON。
            // WHY: 原始工具 JSON（如 list_directory 返回的几十条 {"content":[...]}）对下游 Agent
            // 没有意义，反而会占满上下文并干扰 LLM 理解。下游 Agent 需要的是上游的分析结论。
            val assistantMessages = taskMessages.filter { it.role == "assistant" && it.content.isNotBlank() }
            if (assistantMessages.isNotEmpty()) {
                // 取最后一条 assistant 消息作为主要结论
                val lastAssistant = assistantMessages.last()
                sb.appendLine(lastAssistant.content)
            }
        } else {
            // 普通结果收集（用于 Orchestrator 汇总）：同时包含工具输出和 assistant 文本
            val toolResults = taskMessages.filter { it.role == "tool" && it.content.isNotBlank() }
            if (toolResults.isNotEmpty()) {
                sb.appendLine("【工具执行结果】")
                for (toolMsg in toolResults) {
                    val content = toolMsg.content
                    if (content.length > 500) {
                        sb.appendLine(content.take(500) + "...")
                    } else {
                        sb.appendLine(content)
                    }
                }
            }

            val lastAssistant = taskMessages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            if (lastAssistant != null) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.appendLine("【Agent 输出】")
                sb.appendLine(lastAssistant.content)
            }
        }

        return sb.toString().takeIf { it.isNotBlank() } ?: "子任务已完成，无文本输出"
    }

    /**
     * 收集所有 Sub-Agent 的执行结果。
     *
     * @param subAgentNames Sub-Agent 名称列表
     * @param forDependency 是否用于依赖传递（只传 assistant 文本）
     * @return 结果列表（Pair: agentName -> 结果文本）
     */
    private fun collectResults(subAgentNames: List<String>, forDependency: Boolean = false): List<Pair<String, String>> {
        return subAgentNames.map { name ->
            val runner = runners[name]
            val result = if (runner != null) {
                collectAgentResult(runner, forDependency = forDependency)
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
        val completed = mutableSetOf<String>()   // spec names (for dependency resolution)
        val failed = mutableSetOf<String>()       // spec names
        val skipped = mutableSetOf<String>()      // spec names
        // Global mapping: spec.name -> actual spawned name (may differ due to generateUniqueName)
        val globalSpecToActual = mutableMapOf<String, String>()

        while (allAgents.isNotEmpty()) {
            // 找出当前可以创建的 Agent（依赖全部成功完成）
            val ready = allAgents.filter { spec ->
                spec.dependsOn.all { it in completed }
            }
            
            // 找出依赖失败的 Agent（至少一个依赖失败，且所有依赖都已结束）
            val toSkip = allAgents.filter { spec ->
                spec.dependsOn.any { it in failed } && 
                spec.dependsOn.all { it in completed || it in failed }
            }
            
            // 跳过依赖失败的 Agent
            for (spec in toSkip) {
                Log.w(TAG, "Skipping '${spec.name}' because dependency failed")
                skipped.add(spec.name)
                failed.add(spec.name) // 标记为失败，供下游依赖判断
            }
            
            // 从待处理列表中移除已跳过的
            allAgents.removeAll(toSkip.toSet())

            if (ready.isEmpty()) {
                if (allAgents.isNotEmpty()) {
                    Log.w(TAG, "Circular dependency or unresolvable deps. Remaining: ${allAgents.map { it.name }}")
                }
                break
            }

            // spec.name -> actual spawned name (may differ due to generateUniqueName deduplication)
            val spawnedNames = mutableListOf<String>()
            val specToActualName = mutableMapOf<String, String>()

            // 创建这些 Agent
            for (spec in ready) {
                try {
                    val identity = spawnTeammate(
                        name = spec.name,
                        prompt = "等待主控分配任务...",
                        systemPrompt = spec.systemPrompt,
                        modelConfigId = spec.modelConfigId,
                    )
                    // WHY: spawnTeammate 内部调用 generateUniqueName，实际名称可能与 spec.name 不同。
                    // 必须使用 identity.agentName 作为消息路由和结果匹配的 key，否则 ResultReport.from
                    // 与 pendingAgents 中的名称不一致，导致 spawnWithDependencies 永久等待。
                    spawnedNames.add(identity.agentName)
                    specToActualName[spec.name] = identity.agentName
                    globalSpecToActual[spec.name] = identity.agentName
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to spawn '${spec.name}'", e)
                    failed.add(spec.name)
                }
            }

            if (spawnedNames.isNotEmpty()) {
                // 分配任务（根据 taskMode）
                when (directive.taskMode) {
                    TaskMode.DIRECT -> {
                        for (spec in ready) {
                            val actualName = specToActualName[spec.name] ?: continue
                            messageBus.send(
                                actualName,
                                TeamMessage.TaskAssignment(
                                    from = ORCHESTRATOR_NAME,
                                    taskId = "",
                                    subject = spec.role.ifEmpty { "执行 ${actualName} 的任务" },
                                    description = buildString {
                                        appendLine("## 你的任务")
                                        appendLine(spec.role)
                                        if (spec.systemPrompt.isNotBlank()) {
                                            appendLine()
                                            appendLine("## 角色说明")
                                            appendLine(spec.systemPrompt)
                                        }
                                        appendLine()
                                        appendLine("## 背景（仅供参考，不是你的任务）")
                                        appendLine("用户原始需求：$originalTask")
                                        appendLine()
                                        appendLine("## 完成标准")
                                        appendLine("- 只做「你的任务」中描述的事，不要扩展范围")
                                        appendLine("- 完成后把关键结果直接写在回复里，不要只说「已生成文件」")
                                        appendLine("- 结果写完后立即停止，不要继续生成额外内容")
                                        appendLine("- 如遇无法解决的问题，明确说明原因和阻塞点")
                                    },
                                )
                            )
                            Log.d(TAG, "Direct mode: Assigned task to '$actualName'")
                        }
                    }
                    TaskMode.CLAIM -> {
                        val teamName = _teamState.value?.teamName ?: continue
                        for (spec in ready) {
                            val actualName = specToActualName[spec.name] ?: continue
                            // claim 模式：role 字段直接作为任务 prompt 发给认领的 Agent。
                            // Orchestrator 系统提示已要求 claim 模式下 role 必须完全自包含，
                            // 此处将 role 作为 subject（主要任务描述），originalTask 作为 description
                            // 补充背景上下文，Agent 认领后收到的是 "任务 #id: {role}\n{originalTask}"。
                            taskManager.createTask(
                                teamName = teamName,
                                subject = spec.role.ifEmpty { actualName },
                                description = buildString {
                                    if (originalTask.isNotBlank()) {
                                        appendLine("## 背景（仅供参考）")
                                        appendLine(originalTask)
                                    }
                                    if (spec.systemPrompt.isNotBlank()) {
                                        appendLine()
                                        appendLine("## 角色说明")
                                        appendLine(spec.systemPrompt)
                                    }
                                    appendLine()
                                    appendLine("## 完成标准")
                                    appendLine("- 只做上述任务中描述的事，不要扩展范围")
                                    appendLine("- 完成后把关键结果直接写在回复里，不要只说「已生成文件」")
                                    appendLine("- 结果写完后立即停止，不要继续生成额外内容")
                                },
                            )
                            Log.d(TAG, "Claim mode: Created task for '$actualName'")
                        }
                    }
                }

                // 等待本批次所有 Agent 完成任务（读取 Orchestrator 收件箱监听 ResultReport）
                // WHY: pendingAgents 使用实际名称（actual names），因为 ResultReport.from 是实际名称。
                // actualToSpecName 用于将实际名称映射回 spec.name，以便更新 completed/failed（依赖解析用 spec.name）。
                val actualToSpecName = specToActualName.entries.associate { (k, v) -> v to k }
                val batchFailedActual = mutableSetOf<String>()
                val pendingAgents = spawnedNames.toMutableSet() // actual names
                val deadline = System.currentTimeMillis() + 300_000L

                while (pendingAgents.isNotEmpty() && coroutineContext.isActive) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        Log.w(TAG, "Timeout waiting for agents: $pendingAgents")
                        for (actualName in pendingAgents) {
                            val specName = actualToSpecName[actualName] ?: actualName
                            failed.add(specName)
                            batchFailedActual.add(actualName)
                            try { killTeammate(actualName) } catch (e: Exception) { Log.e(TAG, "killTeammate failed for $actualName", e) }
                        }
                        break
                    }
                    val msg = try {
                        withTimeoutOrNull(remaining) {
                            messageBus.receive(ORCHESTRATOR_NAME)
                        }
                    } catch (_: Exception) { null }

                    if (msg != null) {
                        if (msg is TeamMessage.ResultReport && msg.from in pendingAgents) {
                            Log.d(TAG, "spawnWithDependencies: agent '${msg.from}' completed normally")
                            pendingAgents.remove(msg.from)
                            if (!msg.success) {
                                val specName = actualToSpecName[msg.from] ?: msg.from
                                failed.add(specName)
                                batchFailedActual.add(msg.from)
                            }
                        } else if (msg is TeamMessage.ResultReport) {
                            // 非本批次的 ResultReport（理论上不该出现），重新投递
                            messageBus.requeue(ORCHESTRATOR_NAME, msg)
                        } else {
                            // 其他消息（Text/IdleNotification等）重新投递给 Orchestrator
                            messageBus.requeue(ORCHESTRATOR_NAME, msg)
                        }
                    }
                }

                // 收集结果并注入给依赖方；只处理成功的 Agent
                // WHY: 失败/超时的 Agent 已在 batchFailedActual 中，不应加入 completed，
                // 否则最终汇总的 if (name !in completed) 判断失效，失败信息不会出现在报告中。
                val successActualNames = spawnedNames.filter { it !in batchFailedActual }
                val results = collectResults(successActualNames, forDependency = true)
                for ((actualName, result) in results) {
                    val specName = actualToSpecName[actualName] ?: actualName
                    if (result.isNotBlank()) {
                        // 依赖方通过 spec.name 声明依赖，所以用 specName 匹配
                        val dependents = allAgents.filter { spec -> specName in spec.dependsOn }
                        for (dep in dependents) {
                            // BUG-3：只向已成功 spawn 的 Agent 发送消息；
                            // 未创建的 Agent（下一批次才会 spawn）等其 spawn 后会通过 TaskAssignment 收到任务
                            val depActualName = specToActualName[dep.name]
                            if (depActualName == null) {
                                Log.d(TAG, "Dep '${dep.name}' not yet spawned, skipping upstream result send")
                                continue
                            }
                            messageBus.send(
                                depActualName,
                                TeamMessage.Text(
                                    from = actualName,
                                    content = buildString {
                                        appendLine("## 上游 Agent「${actualName}」的产出")
                                        appendLine(result)
                                        appendLine()
                                        appendLine("## 你的任务")
                                        appendLine(dep.role)
                                        if (dep.systemPrompt.isNotBlank()) {
                                            appendLine()
                                            appendLine("## 任务说明")
                                            appendLine(dep.systemPrompt)
                                        }
                                        appendLine()
                                        appendLine("## 完成标准")
                                        appendLine("- 基于上游产出完成你的任务，不要重新执行上游已做过的工作")
                                        appendLine("- 完成后把关键结果直接写在回复里")
                                        appendLine("- 结果写完后立即停止，不要继续生成额外内容")
                                    },
                                    summary = "${actualName} 完成，传递产出",
                                )
                            )
                        }
                    }
                    // completed 用 specName，供下一批次的 ready 过滤（spec.dependsOn 用 spec.name）
                    completed.add(specName)
                }

                // WHY: 批次完成后 kill 已完成的 Agent，防止它们继续运行产生多余的 ResultReport/IdleNotification，
                // 这些残留消息会在 waitForOrchestratorInput 中被误当作新任务通知处理。
                for (actualName in successActualNames) {
                    try {
                        killTeammate(actualName)
                        Log.d(TAG, "spawnWithDependencies: killed completed agent '$actualName'")
                    } catch (e: Exception) {
                        Log.w(TAG, "spawnWithDependencies: failed to kill '$actualName': ${e.message}")
                    }
                }
            }

            // 从待创建列表中移除已处理的（包括失败的）
            allAgents.removeAll(ready.toSet())
        }

        // 汇总最终结果（不注入消息历史，由调用方通过 MessageBus 正常处理）
        // WHY: 汇总必须包含失败/跳过的 agent，否则 orchestrator 可能向用户报告所有任务完成
        // completed 存的是 spec names，collectResults 需要 actual names
        val completedActualNames = completed.mapNotNull { specName ->
            globalSpecToActual[specName] ?: specName.takeIf { runners.containsKey(it) }
        }
        val allResults = collectResults(completedActualNames)
        val summaryInput = buildString {
            for ((actualName, result) in allResults) {
                val displayName = globalSpecToActual.entries.find { it.value == actualName }?.key ?: actualName
                appendLine("【${displayName}的结果】\n$result")
            }
            for (name in failed) {
                if (name !in completed) {
                    appendLine("【${name}】执行失败或超时")
                }
            }
            for (name in skipped) {
                appendLine("【${name}】因依赖失败而跳过")
            }
        }

        // 排空 Orchestrator 收件箱中本依赖链 agent 的残余消息
        // WHY: 只排空本批次 agent 的消息，不相关的 ResultReport/用户干预必须 requeue，
        // 否则不属于本依赖链的消息会被误删
        // chainAgents 包含 spec names 和 actual names，确保两种形式的消息都被排空
        val chainActualNames = (completed + failed + skipped).mapNotNull { globalSpecToActual[it] }.toSet()
        val chainAgents = completed + failed + skipped + chainActualNames
        val requeueList = mutableListOf<TeamMessage>()
        var drained = 0
        while (true) {
            val residual = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            if (residual.from in chainAgents) {
                drained++
                Log.d(TAG, "Drained residual message from '${residual.from}': ${residual::class.simpleName}")
            } else {
                requeueList.add(residual)
            }
        }
        for (msg in requeueList) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }
        if (drained > 0) {
            Log.d(TAG, "Drained $drained residual message(s) from dependency chain agents")
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
        // WHY: compareAndSet 保证原子性，防止并发调用导致 onWorkspaceComplete 触发两次
        if (!isCompleted.compareAndSet(false, true)) return

        Log.d(TAG, "Triggering workspace complete")

        // 更新 Orchestrator 状态为 COMPLETED，停止 UI 转圈
        updateTeammateStatus(ORCHESTRATOR_NAME, AgentStatus.COMPLETED)

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

        Log.d(TAG, "triggerWorkspaceComplete: cleaning up sub-agents: $subAgentNames, current jobs: ${teammateJobs.keys}")

        for (name in subAgentNames) {
            teammateScopes[name]?.cancel()
            // 等待 Job 完成，确保 runTeammateLoop 的 finally 块执行完毕
            // 否则 agentCompletionDeferreds 可能未完成，导致等待的协程永久挂起
            try {
                teammateJobs[name]?.join()
            } catch (_: Exception) { }
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
            "continue_conversation" -> return handleContinueConversationTool(args)
        }
        
        // ── 拦截协作工具（所有 Agent 可用）──
        when (toolName) {
            "peer_message" -> return handlePeerMessageTool(agentName, args)
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
                // claim 模式：role 字段直接作为任务 prompt 发给认领的 Agent（subject），
                // 补充背景上下文和完成标准作为 description。
                taskManager.createTask(
                    teamName = teamName,
                    subject = spec.role.ifEmpty { spec.name },
                    description = buildString {
                        if (originalTask.isNotBlank()) {
                            appendLine("## 背景（仅供参考）")
                            appendLine(originalTask)
                        }
                        if (spec.systemPrompt.isNotBlank()) {
                            appendLine()
                            appendLine("## 角色说明")
                            appendLine(spec.systemPrompt)
                        }
                        appendLine()
                        appendLine("## 完成标准")
                        appendLine("- 只做上述任务中描述的事，不要扩展范围")
                        appendLine("- 完成后把关键结果直接写在回复里，不要只说「已生成文件」")
                        appendLine("- 结果写完后立即停止，不要继续生成额外内容")
                    },
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
     * 处理 continue_conversation 工具调用。
     *
     * 向已有 Sub-Agent 发送后续消息，利用其已加载的上下文继续工作。
     * 对标 Claude Code 的 SendMessage 工具。
     *
     * 适用场景：
     * 1. 研究完成后，让同一 Agent 执行实施（高上下文重叠）
     * 2. 修正失败的实现（Agent 有错误上下文）
     * 3. 追加更多任务
     *
     * @param args continue_conversation 工具的参数
     * @return 执行结果文本
     */
    private suspend fun handleContinueConversationTool(args: JSONObject): String {
        val to = args.optString("to")
        val message = args.optString("message")

        if (to.isEmpty() || message.isEmpty()) {
            return "Error: 'to' 和 'message' 参数必填"
        }

        return continueAgent(to, message)
    }

    /**
     * 处理 peer_message 工具调用。
     *
     * Sub-Agent 间直接通信，支持点对点和广播。
     * 对标 Claude Code 的 SendMessage 工具。
     *
     * @param senderName 发送方 Agent 名称
     * @param args peer_message 工具的参数
     * @return 执行结果文本
     */
    private suspend fun handlePeerMessageTool(senderName: String, args: JSONObject): String {
        val to = args.optString("to")
        val message = args.optString("message")
        val summary = args.optString("summary", "")

        if (to.isEmpty() || message.isEmpty()) {
            return "Error: 'to' 和 'message' 参数必填"
        }

        return sendPeerMessage(
            from = senderName,
            to = to,
            message = message,
            summary = summary,
        )
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
     * 从预定义颜色列表中循环分配。使用 AtomicInteger 保证线程安全。
     *
     * @return 颜色值（#RRGGBB 格式）
     */
    private fun assignColor(): String {
        val idx = colorIndex.getAndIncrement()
        return AGENT_COLORS[idx % AGENT_COLORS.size]
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
1. 只执行分配给你的具体子任务，不要自行扩展任务范围。
2. 使用可用的工具完成工作。
3. 任务完成后，用简洁的文字汇报执行结果，然后停止。
4. 如果遇到无法解决的问题，明确说明原因和阻塞点。

## 任务结构说明

你收到的任务消息通常包含以下部分：
- **你的任务** / 任务主体：这是你必须完成的核心工作，严格按此执行
- **背景（仅供参考）**：用户原始需求，帮助你理解上下文，但不是你的任务范围
- **角色说明**：你的专业角色定位（如有）
- **完成标准**：判断任务完成的标准

## 关键行为准则

**完成即停止**：任务完成后立即输出结果并结束，不要继续"完善"、"优化"或生成额外内容。
- ❌ 错误：完成分析后继续写报告、生成图表、创建索引文件
- ✅ 正确：完成分析后输出结论，结束

**不要越权**：严格按照任务描述执行，不要做任务之外的事。
- 如果任务是"分析文件名"，只分析，不写文件到磁盘
- 如果任务是"写一份报告"，只写这一份，不要额外生成多份变体
- 如果任务是"读取数据"，只读取，不要修改任何文件

**结果要自包含**：汇报结果时，把关键数据直接写在回复里，不要只说"已生成报告文件，请查看"。
- ❌ 错误："分析完成，详见 /Download/report.md"
- ✅ 正确："分析完成。共 95 个文件：图片 77 个（81%）、文档 12 个（13%）、其他 6 个（6%）。"

## Sub-Agent 间协作（peer_message 工具）

你可以使用 peer_message 工具与其他 Sub-Agent 直接通信：

### 点对点消息
```
peer_message(to: "researcher", message: "请把 auth 模块的分析结果发给我", summary: "请求分析结果")
```

### 广播消息
```
peer_message(to: "*", message: "我已完成数据库迁移，请各 Agent 更新相关代码", summary: "通知数据库变更")
```

### 典型协作场景
1. **请求帮助**：遇到难题时请求其他 Agent 协助
2. **传递数据**：将你的输出作为另一个 Agent 的输入
3. **协调分工**：协商谁负责哪个部分，避免重复工作
4. **进度同步**：通知其他 Agent 你的进展情况

### 注意事项
- 消息是异步的，目标 Agent 不会在同一轮对话中立即回复
- 如果需要等待对方响应，你需要在后续轮次中继续处理
- 使用 summary 参数可以让 UI 显示消息预览

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
