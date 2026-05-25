package com.example.workspace

import android.util.Log
import com.example.data.AgentPreset
import com.example.data.AppRepository
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ═══════════════════════════════════════════════════════════════════════════════
// TeamManager — 团队管理器（薄门面）
//
// 对标 Claude Code 的 TeamCreateTool / TeamDeleteTool。
// 职责：协调 AgentLifecycle、AgentExecutionLoops、OrchestratorTools 三个模块。
// 具体实现分别在各自的文件中。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 团队管理器。
 *
 * 负责团队的创建、Agent 的调度、消息路由和生命周期管理。
 * 通过 [AgentLifecycle]、[AgentExecutionLoops]、[OrchestratorTools] 三个子模块
 * 实现具体逻辑，自身仅负责状态持有和模块协调。
 *
 * @property repository 数据仓库
 * @property mcpRuntimeManager MCP 运行时管理器
 * @property messageBus 消息总线
 * @property taskManager 任务管理器
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
    }

    // ─── 子模块 ───

    private val lifecycle = AgentLifecycle(repository, messageBus, taskManager, config, onError)
    private val executionLoops = AgentExecutionLoops(messageBus, taskManager, lifecycle, onAgentStatusChanged, onError)
    private val orchestratorTools = OrchestratorTools(this, repository, messageBus, mcpRuntimeManager, onAgentStatusChanged)

    // ─── 团队状态（StateFlow 驱动 UI）───

    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    // ─── 工作区完成标记 ───

    private val isCompleted = AtomicBoolean(false)

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
        agentPresets: List<AgentPreset>,
    ): TeamState {
        require(_teamState.value == null) { "已有活跃团队，请先删除当前团队" }

        Log.d(TAG, "Creating team: $teamName")

        lifecycle.crossSessionMemoryText = lifecycle.loadCrossSessionMemory()

        val orchestratorIdentity = TeammateIdentity(
            agentId = "${ORCHESTRATOR_NAME}@${teamName}",
            agentName = ORCHESTRATOR_NAME,
            teamName = teamName,
            color = lifecycle.assignColor(),
            parentSessionId = "leader",
        )

        val orchestratorContext = AgentContext(
            agentName = ORCHESTRATOR_NAME,
            isOrchestrator = true,
            systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
            modelConfig = orchestratorConfig,
            teamName = teamName,
        )

        val orchestratorRunner = createAgentRunner(orchestratorContext)
        lifecycle.runners[ORCHESTRATOR_NAME] = orchestratorRunner

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
     */
    suspend fun startExecution(userTask: String) {
        val state = _teamState.value ?: error("无活跃团队，请先调用 createTeam")
        val runner = lifecycle.runners[ORCHESTRATOR_NAME] ?: error("Orchestrator 未初始化")
        val identity = state.teammates[ORCHESTRATOR_NAME]?.identity
            ?: error("Orchestrator 身份信息缺失")

        Log.d(TAG, "Starting orchestrator execution with task: ${userTask.take(80)}...")

        val orchestratorScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        lifecycle.teammateScopes[ORCHESTRATOR_NAME] = orchestratorScope

        val job = orchestratorScope.launch {
            try {
                executionLoops.runOrchestratorLoop(runner, userTask, isCompleted) { completedRunner ->
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
     * 创建 Teammate（Sub-Agent）。
     */
    suspend fun spawnTeammate(
        name: String,
        prompt: String,
        systemPrompt: String = "",
        modelConfigId: Long? = null,
    ): TeammateIdentity {
        val state = _teamState.value ?: error("无活跃团队")

        val identity = lifecycle.spawnTeammate(
            name = name,
            prompt = prompt,
            systemPrompt = systemPrompt,
            modelConfigId = modelConfigId,
            existingNames = state.teammates.keys,
            parentScope = parentScope,
            createRunner = { ctx, isSub -> createAgentRunner(ctx, isSub) },
            executeLoop = { runner, id -> executionLoops.runTeammateLoop(runner, id) },
        )

        _teamState.update { currentState ->
            currentState?.copy(
                teammates = currentState.teammates + (identity.agentName to TeammateState(
                    identity = identity,
                    status = AgentStatus.IDLE,
                    isOrchestrator = false,
                ))
            )
        }

        onAgentCreated(identity.agentName, false)
        return identity
    }

    /**
     * 向指定 Agent 发送消息。
     */
    suspend fun sendMessage(to: String, message: String) {
        _teamState.value ?: error("无活跃团队")
        messageBus.send(to, TeamMessage.Text(from = ORCHESTRATOR_NAME, content = message))
    }

    /**
     * 继续与已有 Agent 对话。
     */
    suspend fun continueAgent(agentName: String, message: String): String {
        val state = _teamState.value ?: return "Error: 无活跃团队"

        if (agentName !in state.teammates) return "Error: Agent '$agentName' 不存在"
        if (agentName == ORCHESTRATOR_NAME) return "Error: 不能向 Orchestrator 发送 continue 消息"

        // 检查 Agent 是否正在忙碌（执行任务中）
        val teammateState = state.teammates[agentName]
        if (teammateState != null && teammateState.status == AgentStatus.STREAMING) {
            return "Warning: Agent '$agentName' 正在执行任务中，消息已加入队列。请等待其完成后（收到 <task-notification>）再继续操作。消息已发送，但可能不会立即被处理。"
        }

        if (lifecycle.teammateJobs[agentName]?.isActive != true) {
            return "Error: Agent '$agentName' 已结束，无法继续对话"
        }

        messageBus.send(agentName, TeamMessage.Text(from = ORCHESTRATOR_NAME, content = message))
        return "已向 $agentName 发送继续消息，等待其响应..."
    }

    /**
     * 向所有 Sub-Agent 广播消息。
     */
    suspend fun broadcast(message: String) {
        val state = _teamState.value ?: error("无活跃团队")
        val subAgentNames = state.teammates.values
            .filter { !it.isOrchestrator }
            .map { it.identity.agentName }

        messageBus.broadcast(
            senderName = ORCHESTRATOR_NAME,
            message = TeamMessage.Text(from = ORCHESTRATOR_NAME, content = message),
            teamMembers = subAgentNames,
        )
    }

    /**
     * Sub-Agent 间直接通信。
     */
    suspend fun sendPeerMessage(from: String, to: String, message: String, summary: String = ""): String {
        val state = _teamState.value ?: return "Error: 无活跃团队"

        if (to == "*") {
            val subAgentNames = state.teammates.values
                .filter { !it.isOrchestrator && it.identity.agentName != from }
                .map { it.identity.agentName }

            if (subAgentNames.isEmpty()) return "Error: 没有其他 Sub-Agent 可以接收消息"

            messageBus.broadcast(
                senderName = from,
                message = TeamMessage.Text(from = from, content = message, summary = summary),
                teamMembers = subAgentNames,
            )
            return "已广播给 ${subAgentNames.size} 个 Agent: ${subAgentNames.joinToString(", ")}"
        }

        if (to !in state.teammates) return "Error: 目标 Agent '$to' 不存在"
        if (to == from) return "Error: 不能向自己发送消息"

        messageBus.send(to, TeamMessage.Text(from = from, content = message, summary = summary))
        return "已向 $to 发送消息"
    }

    /**
     * 请求关闭指定 Teammate。
     */
    suspend fun requestShutdown(agentName: String, reason: String = ""): String {
        val requestId = java.util.UUID.randomUUID().toString()
        messageBus.send(
            agentName,
            TeamMessage.ShutdownRequest(from = ORCHESTRATOR_NAME, requestId = requestId, reason = reason)
        )
        return requestId
    }

    /**
     * 强制终止指定 Teammate。
     */
    suspend fun killTeammate(agentName: String) {
        lifecycle.killTeammate(agentName)
        _teamState.update { state ->
            state?.copy(teammates = state.teammates - agentName)
        }
    }

    /**
     * 删除团队。
     */
    suspend fun deleteTeam() = withContext(NonCancellable) {
        val state = _teamState.value ?: return@withContext

        Log.d(TAG, "Deleting team '${state.teamName}'")

        // 先取消所有协程作用域，触发正在执行的 runTurn 等操作退出
        lifecycle.teammateScopes.values.forEach { it.cancel() }

        // 等待所有 Job 完成（排除当前 Job 以避免自 join 死锁）
        // 如果 deleteTeam 从外部调用（如 UI 线程），当前 Job 不是 teammate 的 Job，
        // 所有 teammate Job 都会被 join；如果是从 Orchestrator 自身调用，
        // 跳过自身 Job 是正确的，因为 cancel 已触发其退出。
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        lifecycle.teammateJobs.forEach { (name, job) ->
            if (job !== currentJob) {
                try { job.join() } catch (_: Exception) { }
            }
        }

        // 清理所有资源（包括 runners、messageBus、completionDeferreds 等）
        lifecycle.cleanupAll()
        repository.deleteAllTeamTasks(state.teamName)

        isCompleted.set(false)
        _teamState.value = null

        Log.d(TAG, "Team '${state.teamName}' deleted")
    }

    /**
     * 发送干预消息。
     */
    suspend fun sendIntervention(targetAgentName: String, message: String) {
        val state = _teamState.value ?: run {
            onError("无活跃团队")
            return
        }

        if (targetAgentName !in state.teammates) {
            onError("目标 Agent 不存在：$targetAgentName")
            return
        }

        messageBus.send(targetAgentName, TeamMessage.Text(from = "user", content = message))

        if (targetAgentName != ORCHESTRATOR_NAME) {
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.Text(from = "system", content = "[用户干预] 用户向 $targetAgentName 发送了消息：$message")
            )
        }
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

    // ═══════════════════════════════════════════════════════════════════════════════
    // 内部方法（供子模块调用）
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 获取 Orchestrator 的对话历史（供 OrchestratorTools 使用）。
     */
    internal fun getOrchestratorHistory(): List<AgentMessage> {
        return lifecycle.runners[ORCHESTRATOR_NAME]?.getHistory() ?: emptyList()
    }

    /**
     * 获取当前团队名称。
     */
    internal fun getTeamName(): String? = _teamState.value?.teamName

    /**
     * 检查指定 Teammate 是否存活。
     */
    internal fun isTeammateAlive(agentName: String): Boolean {
        val state = _teamState.value ?: return false
        return agentName in state.teammates
    }

    /**
     * 为 Agent 创建任务（claim 模式）。
     *
     * 任务描述必须包含完整的内容，确保 Sub-Agent 认领后知道具体要做什么。
     * intendedAgent 字段确保只有指定名称的 Agent 才能认领此任务。
     */
    internal suspend fun createTaskForAgent(teamName: String, spec: AgentSpec, originalTask: String) {
        // 构建完整的任务描述，确保 Sub-Agent 认领后有明确的工作内容
        val fullTaskDescription = buildString {
            // 核心任务描述（必须有内容）
            if (spec.role.isNotBlank()) {
                appendLine("## 你的任务")
                appendLine(spec.role)
            } else {
                appendLine("## 你的任务")
                appendLine("执行分配给你的工作，完成后汇报结果。")
            }

            // 背景信息
            if (originalTask.isNotBlank()) {
                appendLine()
                appendLine("## 背景（仅供参考）")
                appendLine(originalTask)
            }

            // 角色说明
            if (spec.systemPrompt.isNotBlank()) {
                appendLine()
                appendLine("## 角色说明")
                appendLine(spec.systemPrompt)
            }

            // 完成标准
            appendLine()
            appendLine("## 完成标准")
            appendLine("- 只做上述「你的任务」中描述的事，不要扩展范围")
            appendLine("- 完成后把关键结果直接写在回复里")
            appendLine("- 结果写完后立即停止，不要继续生成额外内容")
        }

        taskManager.createTask(
            teamName = teamName,
            subject = spec.role.ifEmpty { spec.name },
            description = fullTaskDescription,
            intendedAgent = spec.name,  // 标记预期执行的 Agent
        )
    }

    /**
     * 从 Orchestrator 指令批量创建 Sub-Agent。
     */
    internal suspend fun spawnSubAgentsFromDirective(
        directive: OrchestratorDirective,
        originalTask: String,
    ): List<String> {
        val state = _teamState.value ?: return emptyList()
        val subAgentNames = mutableListOf<String>()

        for (spec in directive.agents) {
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
     * 等待所有 Sub-Agent 就绪。
     */
    internal suspend fun waitForAgentsReady(subAgentNames: List<String>, timeoutMs: Long = 30_000L) {
        executionLoops.waitForAgentsReady(subAgentNames, timeoutMs)
    }

    /**
     * 按依赖顺序创建并启动 Sub-Agent。
     *
     * 对标 Claude Code 的 dependsOn 模式：上游完成后结果自动注入下游 Agent 上下文。
     */
    internal suspend fun spawnWithDependencies(
        directive: OrchestratorDirective,
        originalTask: String,
    ): String {
        val allAgents = directive.agents.toMutableList()
        val completed = mutableSetOf<String>()   // spec names
        val failed = mutableSetOf<String>()       // spec names
        val skipped = mutableSetOf<String>()      // spec names
        val globalSpecToActual = mutableMapOf<String, String>()
        // WHY: 累积每批次的结果，避免最终汇总时 runner 已被 dispose（Fix #2）
        val accumulatedResults = mutableMapOf<String, String>()  // actualName -> result

        while (allAgents.isNotEmpty()) {
            val ready = allAgents.filter { spec ->
                spec.dependsOn.all { it in completed }
            }

            val toSkip = allAgents.filter { spec ->
                spec.dependsOn.any { it in failed } &&
                    spec.dependsOn.all { it in completed || it in failed }
            }

            for (spec in toSkip) {
                Log.w(TAG, "Skipping '${spec.name}' because dependency failed")
                skipped.add(spec.name)
                failed.add(spec.name)
            }
            allAgents.removeAll(toSkip.toSet())

            if (ready.isEmpty()) {
                if (allAgents.isNotEmpty()) {
                    Log.w(TAG, "Circular dependency or unresolvable deps. Remaining: ${allAgents.map { it.name }}")
                }
                break
            }

            // WHY: 立即移除 ready agents，避免下一轮迭代重复处理（Fix #3）
            allAgents.removeAll(ready.toSet())

            val spawnedNames = mutableListOf<String>()
            val specToActualName = mutableMapOf<String, String>()

            for (spec in ready) {
                try {
                    val identity = spawnTeammate(
                        name = spec.name,
                        prompt = "等待主控分配任务...",
                        systemPrompt = spec.systemPrompt,
                        modelConfigId = spec.modelConfigId,
                    )
                    spawnedNames.add(identity.agentName)
                    specToActualName[spec.name] = identity.agentName
                    globalSpecToActual[spec.name] = identity.agentName
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to spawn '${spec.name}'", e)
                    failed.add(spec.name)
                }
            }

            if (spawnedNames.isNotEmpty()) {
                // 等待所有 Agent 就绪（收到 IdleNotification）
                // 这确保 Agent 已启动并进入等待状态，然后再创建任务
                executionLoops.waitForAgentsReady(spawnedNames, timeoutMs = 30_000L)

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
                                    description = buildDependencyTaskDescription(spec, originalTask),
                                )
                            )
                        }
                    }
                    TaskMode.CLAIM -> {
                        val teamName = _teamState.value?.teamName ?: continue
                        for (spec in ready) {
                            createTaskForAgent(teamName, spec, originalTask)
                        }
                    }
                }

                val actualToSpecName = specToActualName.entries.associate { (k, v) -> v to k }
                val batchFailedActual = mutableSetOf<String>()
                val pendingAgents = spawnedNames.toMutableSet()
                val deadline = System.currentTimeMillis() + 300_000L

                while (pendingAgents.isNotEmpty() && coroutineContext.isActive) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        for (actualName in pendingAgents) {
                            val specName = actualToSpecName[actualName] ?: actualName
                            failed.add(specName)
                            batchFailedActual.add(actualName)
                            try { killTeammate(actualName) } catch (e: Exception) {
                                Log.w(TAG, "Failed to kill timed-out agent '$actualName': ${e.message}")
                                // kill 失败时强制清理资源，防止孤儿协程
                                try { lifecycle.cleanupAgent(actualName) } catch (_: Exception) {}
                            }
                        }
                        break
                    }
                    val msg = try {
                        withTimeoutOrNull(remaining) {
                            messageBus.receive(ORCHESTRATOR_NAME)
                        }
                    } catch (_: Exception) { null }

                    if (msg != null) {
                        when {
                            // ResultReport: 正常完成
                            msg is TeamMessage.ResultReport && msg.from in pendingAgents -> {
                                pendingAgents.remove(msg.from)
                                if (!msg.success) {
                                    val specName = actualToSpecName[msg.from] ?: msg.from
                                    failed.add(specName)
                                    batchFailedActual.add(msg.from)
                                }
                            }
                            // Text 消息来自 pending agent: 可能是 peer_message 汇报或出错信息
                            msg is TeamMessage.Text && msg.from in pendingAgents -> {
                                Log.d(TAG, "Received Text from pending agent '${msg.from}': ${msg.content.take(100)}")
                                // requeue 等待 ResultReport，但如果 agent 后续没发 ResultReport，
                                // 最终会超时处理
                                messageBus.requeue(ORCHESTRATOR_NAME, msg)
                            }
                            // IdleNotification 来自 pending agent: 这是"就绪"信号，不是"完成"信号
                            // 在依赖流程中，agent 会在启动时发送 IdleNotification，
                            // 然后等待任务，执行任务后发送 ResultReport
                            // 所以收到 IdleNotification 不应该标记为完成
                            msg is TeamMessage.IdleNotification && msg.from in pendingAgents -> {
                                Log.d(TAG, "Agent '${msg.from}' is idle (ready), waiting for ResultReport")
                                // 不做任何处理，继续等待 ResultReport
                            }
                            else -> {
                                messageBus.requeue(ORCHESTRATOR_NAME, msg)
                            }
                        }
                    }
                }

                // WHY: 先收集结果再 kill，否则 runners 被 dispose 后结果丢失（Fix #2）
                val successActualNames = spawnedNames.filter { it !in batchFailedActual }
                val results = executionLoops.collectResults(successActualNames, forDependency = true)
                for ((actualName, result) in results) {
                    // 累积结果到 map
                    accumulatedResults[actualName] = result
                    val specName = actualToSpecName[actualName] ?: actualName
                    if (result.isNotBlank()) {
                        val dependents = allAgents.filter { spec -> specName in spec.dependsOn }
                        for (dep in dependents) {
                            val depActualName = specToActualName[dep.name]
                            if (depActualName == null) continue
                            messageBus.send(
                                depActualName,
                                TeamMessage.Text(
                                    from = actualName,
                                    content = buildUpstreamResultMessage(actualName, result, dep),
                                    summary = "${actualName} 完成，传递产出",
                                )
                            )
                        }
                    }
                    completed.add(specName)
                }

                // 结果已收集，现在可以安全 kill
                for (actualName in successActualNames) {
                    try {
                        killTeammate(actualName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to kill agent '$actualName': ${e.message}")
                        try { lifecycle.cleanupAgent(actualName) } catch (_: Exception) {}
                    }
                }
            }
        }

        // WHY: 使用累积的结果而非再次 collectResults（runner 可能已 dispose）（Fix #2）
        val summaryInput = buildString {
            for ((actualName, result) in accumulatedResults) {
                val displayName = globalSpecToActual.entries.find { it.value == actualName }?.key ?: actualName
                appendLine("【${displayName}的结果】\n$result")
            }
            for (name in failed) {
                if (name !in completed) appendLine("【${name}】执行失败或超时")
            }
            for (name in skipped) {
                appendLine("【${name}】因依赖失败而跳过")
            }
        }

        drainDependencyChainMessages(completed, failed, skipped, globalSpecToActual)

        return summaryInput
    }

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
            try { lifecycle.teammateJobs[name]?.join() } catch (_: Exception) {}
            lifecycle.cleanupAgent(name)
        }

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
     * 创建 AgentRunner。
     */
    private fun createAgentRunner(context: AgentContext, isSubAgent: Boolean = false): AgentRunner {
        return AgentRunner(
            context = context,
            mcpRuntimeManager = mcpRuntimeManager,
            crossSessionMemory = lifecycle.crossSessionMemoryText,
            disallowedTools = if (isSubAgent) ORCHESTRATOR_ONLY_TOOLS else emptySet(),
            onStreamChunk = onStreamChunk,
            onToolCall = { agentName, toolName, args, callId ->
                orchestratorTools.handleToolCall(agentName, toolName, args, callId)
            },
        )
    }

    /**
     * 构建依赖任务描述。
     */
    private fun buildDependencyTaskDescription(spec: AgentSpec, originalTask: String): String {
        return buildString {
            appendLine("## 你的任务")
            appendLine(spec.role)
            if (spec.systemPrompt.isNotBlank()) {
                appendLine()
                appendLine("## 角色说明")
                appendLine(spec.systemPrompt)
            }
            appendLine()
            appendLine("## 背景（仅供参考）")
            appendLine("用户原始需求：$originalTask")
            appendLine()
            appendLine("## 完成标准")
            appendLine("- 只做「你的任务」中描述的事，不要扩展范围")
            appendLine("- 完成后把关键结果直接写在回复里")
            appendLine("- 结果写完后立即停止，不要继续生成额外内容")
        }
    }

    /**
     * 构建上游结果传递消息。
     */
    private fun buildUpstreamResultMessage(actualName: String, result: String, dep: AgentSpec): String {
        return buildString {
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
        }
    }

    /**
     * 排空依赖链 agent 的残余消息。
     */
    private suspend fun drainDependencyChainMessages(
        completed: Set<String>,
        failed: Set<String>,
        skipped: Set<String>,
        globalSpecToActual: Map<String, String>,
    ) {
        val chainActualNames = (completed + failed + skipped).mapNotNull { globalSpecToActual[it] }.toSet()
        val chainAgents = completed + failed + skipped + chainActualNames
        val requeueList = mutableListOf<TeamMessage>()

        while (true) {
            val residual = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            if (residual.from in chainAgents) {
                Log.d(TAG, "Drained residual message from '${residual.from}'")
            } else {
                requeueList.add(residual)
            }
        }
        for (msg in requeueList) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 解析方法（保留兼容，委托给 OrchestratorTools）
    // ═══════════════════════════════════════════════════════════════════════════════

    fun parseOrchestratorOutputRobust(output: String): OrchestratorDirective? =
        orchestratorTools.parseOrchestratorOutputRobust(output)

    fun parseOrchestratorOutput(output: String): OrchestratorDirective? =
        orchestratorTools.parseOrchestratorOutput(output)

    fun parseTaskAssignments(content: String, validAgents: List<String>): Map<String, OrchestratorTools.TaskInfo> =
        orchestratorTools.parseTaskAssignments(content, validAgents)
}
