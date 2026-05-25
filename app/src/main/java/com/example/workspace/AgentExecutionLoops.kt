package com.example.workspace

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

// ═══════════════════════════════════════════════════════════════════════════════
// Agent 执行循环
//
// 对标 Claude Code 的 inProcessRunner.ts。
// 负责 Orchestrator 和 Teammate 的核心执行循环、消息等待和结果收集。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Agent 执行循环管理器。
 *
 * 从 TeamManager 中提取的执行循环逻辑。
 * 对标 Claude Code 的 inProcessRunner.ts 中的 runInProcessTeammate。
 *
 * @property messageBus 消息总线
 * @property taskManager 任务管理器
 * @property lifecycle Agent 生命周期管理器（共享 runners/teammateJobs 等状态）
 * @property onAgentStatusChanged Agent 状态变更回调
 * @property onError 错误回调
 */
class AgentExecutionLoops(
    private val messageBus: MessageBus,
    private val taskManager: TaskManager,
    private val lifecycle: AgentLifecycle,
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    companion object {
        private const val TAG = "AgentExecLoops"
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Orchestrator 执行循环
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Orchestrator 调度循环。
     *
     * 核心流程（对标 Claude Code 的 coordinator loop）：
     * 1. 检查是否有 Sub-Agent 结果待汇总 → 注入上下文
     * 2. 调用 runTurn（LLM 可能调用 create_agents / assign_task 工具）
     * 3. 检测完成标记 → 结束
     * 4. 无结果且无工具调用 → 等待用户干预或 Agent 消息
     *
     * @param runner Orchestrator 的 AgentRunner
     * @param initialTask 用户初始任务，空字符串表示恢复等待
     * @param isCompleted 工作区完成标记
     * @param onWorkspaceComplete 工作区完成回调
     */
    suspend fun runOrchestratorLoop(
        runner: AgentRunner,
        initialTask: String,
        imagePath: String? = null,
        isCompleted: java.util.concurrent.atomic.AtomicBoolean,
        onWorkspaceComplete: suspend (AgentRunner) -> Unit,
    ) {
        // 会话恢复场景：跳过首轮 LLM 调用，直接等待用户输入
        var currentInput = if (initialTask.isBlank()) {
            onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.IDLE)
            waitForOrchestratorInput(isCompleted)
        } else {
            Triple(initialTask, "", imagePath)
        }

        var completionTriggered = false

        while (!isCompleted.get() && coroutineContext.isActive) {
            // 先检查是否有 Sub-Agent 结果待汇总
            val pendingResults = collectPendingResults()
            if (pendingResults.isNotEmpty()) {
                Log.d(TAG, "Collected ${pendingResults.size} agent result(s), injecting into context")
                summarizeAndInject(runner, pendingResults)
            }

            onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.STREAMING)

            // 执行一轮对话，传入消息来源和图片路径
            runner.runTurn(currentInput.first, source = currentInput.second, imagePath = currentInput.third)

            // 排空残留 IdleNotification
            drainExcessIdleNotifications()

            // 获取最后一条 assistant 消息
            val history = runner.getHistory()
            val lastAssistant = history.lastOrNull { it.role == "assistant" }

            // 检测完成标记
            if (lastAssistant != null && isCompletionMarker(lastAssistant.content)) {
                Log.d(TAG, "Orchestrator output completion marker")
                completionTriggered = true
                onWorkspaceComplete(runner)

                // 完成后等待用户反馈，而不是立即退出
                // 如果用户发现问题，可以继续调度 Sub-Agent 修改
                onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.IDLE)
                val feedbackInput = waitForUserFeedback(isCompleted)

                if (feedbackInput.isBlank()) {
                    // 用户没有反馈（超时或确认完成），退出循环
                    Log.d(TAG, "No feedback received, workspace truly completed")
                    break
                }

                // 用户提供了反馈，继续执行
                Log.d(TAG, "Received user feedback after completion, continuing...")
                completionTriggered = false
                currentInput = Triple(feedbackInput, "", null)
                continue
            }

            // 等待用户干预或 Sub-Agent 消息
            onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.IDLE)
            currentInput = waitForOrchestratorInput(isCompleted)
        }

        // WHY: 超时退出时 isCompleted 被设为 true 但 onWorkspaceComplete 未被调用（Fix #6）。
        // 补充触发完成流程，确保 UI 正确显示工作区已结束。
        if (!completionTriggered && isCompleted.get()) {
            Log.d(TAG, "Orchestrator loop exited (timeout or external completion), triggering workspace complete")
            onWorkspaceComplete(runner)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Teammate 执行循环
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Teammate（Sub-Agent）执行循环。
     *
     * 对标 Claude Code 的 runInProcessTeammate 主循环：
     * 1. 创建后进入 IDLE 等待状态
     * 2. 发送就绪通知
     * 3. 等待消息或任务认领
     * 4. 执行任务（支持 per-turn abort）
     * 5. 发送 ResultReport + IdleNotification
     * 6. 回到 3
     *
     * @param runner Teammate 的 AgentRunner
     * @param identity Teammate 身份信息
     */
    suspend fun runTeammateLoop(
        runner: AgentRunner,
        identity: TeammateIdentity,
    ) {
        val completionDeferred = lifecycle.agentCompletionDeferreds.getOrPut(identity.agentName) {
            kotlinx.coroutines.CompletableDeferred()
        }

        var killedExternally = false
        try {
            // 创建后立即进入 IDLE 等待状态
            onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)

            // 发送就绪通知
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.IdleNotification(
                    from = identity.agentName,
                    idleReason = IdleReason.AVAILABLE,
                )
            )

            while (coroutineContext.isActive) {
                // 检查生命周期 abort
                val teammateCtx = coroutineContext[TeammateContext]
                if (teammateCtx?.isAborted == true) {
                    Log.d(TAG, "Teammate '${identity.agentName}' lifecycle aborted")
                    break
                }

                // 等待收到消息
                val waitResult = waitForNextMessage(identity)

                when (waitResult) {
                    is WaitResult.ShutdownRequest -> {
                        Log.d(TAG, "Teammate '${identity.agentName}' received shutdown request")
                        // 释放可能已认领的任务，避免卡在 IN_PROGRESS
                        taskManager.failTask(identity.teamName, identity.agentName)
                        break
                    }
                    is WaitResult.NewMessage -> {
                        executeTask(runner, identity, waitResult.message)
                    }
                    is WaitResult.TaskClaimed -> {
                        executeTask(runner, identity, waitResult.taskDescription)
                    }
                    is WaitResult.Aborted -> {
                        // 释放可能已认领的任务
                        taskManager.failTask(identity.teamName, identity.agentName)
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            killedExternally = true
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Teammate '${identity.agentName}' error: ${e.message}", e)
            onError("Agent '${identity.agentName}' 出错: ${e.message}")
            taskManager.failTask(identity.teamName, identity.agentName)
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
            onAgentStatusChanged(identity.agentName, AgentStatus.COMPLETED)
            // WHY: 不在这里 removeInbox，由 cleanupAgent 统一负责，避免与 killTeammate 双重 remove（Fix #5）

            completionDeferred.complete(Unit)
            lifecycle.agentCompletionDeferreds.remove(identity.agentName)

            // WHY: 只在非外部 kill 时触发 "completed" hook。
            // 外部 kill（CancellationException）由 AgentLifecycle.killTeammate() 负责触发 "killed" hook，
            // 避免同一个 Agent 触发两次 onTeammateKilled。
            if (!killedExternally) {
                WorkspaceScopes.auxiliary.launch {
                    try {
                        com.example.hooks.HookManager.dispatchTeammateKilled(
                            agentName = identity.agentName,
                            teamName = identity.teamName,
                            reason = "completed",
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "dispatchTeammateKilled(completed) failed (non-fatal)", e)
                    }
                }
            }

            Log.d(TAG, "Teammate '${identity.agentName}' loop exited")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 任务执行
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 执行任务并上报结果。
     *
     * 支持 per-turn abort：如果当前轮次被中止，teammate 回到 idle 而非退出。
     */
    private suspend fun executeTask(
        runner: AgentRunner,
        identity: TeammateIdentity,
        taskPrompt: String,
    ) {
        // 验证任务提示是否有效
        if (taskPrompt.isBlank()) {
            Log.w(TAG, "Teammate '${identity.agentName}' received empty task prompt, skipping execution")
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.ResultReport(
                    from = identity.agentName,
                    taskId = "",
                    result = "收到空任务，跳过执行",
                    success = false,
                )
            )
            onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)
            messageBus.send(
                ORCHESTRATOR_NAME,
                TeamMessage.IdleNotification(
                    from = identity.agentName,
                    idleReason = IdleReason.AVAILABLE,
                )
            )
            return
        }

        var taskCompleted = false
        try {
            // 重置 per-turn abort 标志
            val teammateCtx = coroutineContext[TeammateContext]
            teammateCtx?.resetTurnAbort()

            val messageOffset = runner.getHistory().size
            onAgentStatusChanged(identity.agentName, AgentStatus.STREAMING)
            // 标记来源为 orchestrator，UI 中显示为"来自主控 Agent"而非用户消息
            runner.runTurn(taskPrompt, source = "orchestrator")

            // 检查 per-turn abort
            if (teammateCtx?.isCurrentTurnAborted == true) {
                Log.d(TAG, "Teammate '${identity.agentName}' turn aborted, returning to idle")
                onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)
                messageBus.send(
                    ORCHESTRATOR_NAME,
                    TeamMessage.IdleNotification(
                        from = identity.agentName,
                        idleReason = IdleReason.INTERRUPTED,
                    )
                )
                return
            }

            val resultSummary = collectAgentResult(runner, fromIndex = messageOffset)

            // 检测执行错误：只检查 API 级别错误和工具执行错误，
            // 避免把 Agent 分析的文本内容（如包含"执行失败"字样的日志）误判为任务失败
            val history = runner.getHistory()
            val taskMessages = history.subList(messageOffset.coerceIn(0, history.size), history.size)
            val hasError = taskMessages.any { msg ->
                // API 级别错误（来自 ApiClient 的 ERROR: 前缀）
                (msg.role == "assistant" && msg.content.startsWith("ERROR:")) ||
                // 工具执行错误（来自 MCP 工具的 Error: 前缀）
                (msg.role == "tool" && msg.content.startsWith("Error:")) ||
                // 系统注入的错误标记（来自 AgentRunner 的 system 消息）
                (msg.role == "system" && (msg.content.contains("执行出错") || msg.content.contains("执行失败")))
            }

            // WHY: 在发送 ResultReport 之前先标记 taskCompleted，避免：
            // 1. send 抛出异常时 finally 重复发送一份"意外停止"报告，造成主控收到双重消息
            // 2. completeTask DB 写入失败时 finally 误判为未完成
            // 业务语义上：runTurn 已正常返回即视为任务执行完毕；上报和 DB 写入是 best-effort。
            //
            // WHY: 用 withContext(NonCancellable) 包裹上报 + DB 写入，避免协程取消时
            // 主控收不到 ResultReport 而永久挂起。runTurn 已经返回，"完成"状态确定。
            taskCompleted = true

            withContext(NonCancellable) {
                try {
                    messageBus.send(
                        ORCHESTRATOR_NAME,
                        TeamMessage.ResultReport(
                            from = identity.agentName,
                            taskId = "",
                            result = resultSummary,
                            success = !hasError,
                        )
                    )
                    taskManager.completeTask(identity.teamName, identity.agentName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deliver completion report for ${identity.agentName}", e)
                }
            }
        } finally {
            if (!taskCompleted) {
                // 执行被异常中断（如协程取消、应用退到后台被杀、无响应等）
                // 使用 NonCancellable 确保在取消状态下也能发送消息，避免主控永久等待
                withContext(NonCancellable) {
                    try {
                        messageBus.send(
                            ORCHESTRATOR_NAME,
                            TeamMessage.ResultReport(
                                from = identity.agentName,
                                taskId = "",
                                result = "Agent 意外停止或被系统中断，任务未完成。",
                                success = false,
                            )
                        )
                        taskManager.failTask(identity.teamName, identity.agentName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send interruption report for ${identity.agentName}", e)
                    }
                }
            }
            onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)
            if (taskCompleted) {
                messageBus.send(
                    ORCHESTRATOR_NAME,
                    TeamMessage.IdleNotification(
                        from = identity.agentName,
                        idleReason = IdleReason.AVAILABLE,
                    )
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 消息等待
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 等待下一条消息（Sub-Agent 视角）。
     *
     * 对标 Claude Code 的 waitForNextPromptOrShutdown 优先级逻辑：
     * 1. shutdown_request — 最高优先级
     * 2. leader 消息 — 次优先
     * 3. 其他未读消息 — FIFO
     * 4. 任务列表 — 自动认领未分配任务
     */
    private suspend fun waitForNextMessage(identity: TeammateIdentity): WaitResult {
        while (coroutineContext.isActive) {
            // 检查生命周期 abort
            val teammateCtx = coroutineContext[TeammateContext]
            if (teammateCtx?.isAborted == true) {
                return WaitResult.Aborted
            }

            // 先排空邮箱中已有的消息（优先级处理）
            val msg = messageBus.tryReceive(identity.agentName)
            if (msg != null) {
                return convertToWaitResult(msg)
            }

            // 检查任务列表
            val task = taskManager.tryClaimNextTask(identity.teamName, identity.agentName)
            if (task != null) {
                // 认领成功后再检查一次收件箱（竞态窗口保护）
                val pendingMsg = messageBus.tryReceive(identity.agentName)
                if (pendingMsg != null) {
                    // ShutdownRequest 优先级最高：放弃已认领的任务，处理关闭请求
                    if (pendingMsg is TeamMessage.ShutdownRequest) {
                        taskManager.failTask(identity.teamName, identity.agentName)
                        return WaitResult.ShutdownRequest(pendingMsg)
                    }
                    // 其他消息 requeue 回去，优先处理已认领的任务
                    messageBus.requeue(identity.agentName, pendingMsg)
                }
                Log.d(TAG, "Teammate '${identity.agentName}' claimed task #${task.id}")
                return WaitResult.TaskClaimed(
                    "任务 #${task.id}: ${task.subject}\n${task.description}"
                )
            }

            // 邮箱空且无可认领任务，挂起等待
            val received = try {
                withTimeoutOrNull(POLL_INTERVAL_MS) {
                    messageBus.receive(identity.agentName)
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
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
        }
        return WaitResult.Aborted
    }

    /**
     * 等待 Orchestrator 的下一条输入。
     *
     * Orchestrator 空闲时阻塞在 MessageBus.receive 上，等待用户干预或 Sub-Agent 消息。
     * 添加 10 分钟超时和连续超时计数器，防止所有 Sub-Agent 崩溃时永久挂起。
     *
     * @return Triple(消息内容, 来源标识, 图片路径)。来源："" = 用户输入，"subagent" = 子Agent上报
     */
    internal suspend fun waitForOrchestratorInput(isCompleted: java.util.concurrent.atomic.AtomicBoolean): Triple<String, String, String?> {
        val timeoutMs = 10 * 60 * 1000L
        val maxConsecutiveTimeouts = 3
        var consecutiveTimeouts = 0

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
                    drainExcessIdleNotifications()
                    // 设置 isCompleted=true，runOrchestratorLoop 末尾的补救逻辑会调用 onWorkspaceComplete
                    isCompleted.compareAndSet(false, true)
                    break
                }
                continue
            }
            consecutiveTimeouts = 0
            when (msg) {
                is TeamMessage.Text -> {
                    // 区分用户消息和 Sub-Agent 消息：Sub-Agent 的 from 不是 "user" 且存在于团队中
                    val isFromSubAgent = msg.from != "user" && msg.from != "system"
                        && lifecycle.runners.containsKey(msg.from)
                    val source = if (isFromSubAgent) "subagent" else ""
                    return Triple(msg.content, source, msg.imagePath)
                }
                is TeamMessage.ResultReport -> {
                    Log.d(TAG, "Received result from '${msg.from}' while orchestrator waiting")
                    if (isCompleted.get()) {
                        Log.d(TAG, "Workspace already completed, dropping late ResultReport from '${msg.from}'")
                        continue
                    }
                    val stats = lifecycle.runners[msg.from]?.getUsageStats()
                    return Triple(buildTaskNotification(msg, stats), "subagent", null)
                }
                is TeamMessage.IdleNotification -> {
                    Log.d(TAG, "Sub-agent '${msg.from}' is idle (orchestrator waiting for input)")
                }
                else -> {
                    Log.d(TAG, "Orchestrator received: ${msg::class.simpleName}")
                }
            }
        }
        return Triple("", "", null)
    }

    /**
     * 完成后等待用户反馈。
     *
     * 与 [waitForOrchestratorInput] 类似，但专门用于任务完成后的场景：
     * - 只接受用户 Text 消息作为反馈
     * - 忽略 Sub-Agent 的消息（任务已完成，不应再有新消息）
     * - 超时后返回空字符串，表示用户确认完成
     *
     * @return 用户反馈内容，空字符串表示确认完成或超时
     */
    internal suspend fun waitForUserFeedback(isCompleted: java.util.concurrent.atomic.AtomicBoolean): String {
        val timeoutMs = 5 * 60 * 1000L  // 5 分钟超时

        Log.d(TAG, "Waiting for user feedback after completion...")

        while (coroutineContext.isActive) {
            val msg = withTimeoutOrNull(timeoutMs) {
                messageBus.receive(ORCHESTRATOR_NAME)
            }

            if (msg == null) {
                // 超时，用户没有反馈，视为确认完成
                Log.d(TAG, "User feedback timeout, treating as confirmed completion")
                return ""
            }

            when (msg) {
                is TeamMessage.Text -> {
                    // 区分用户消息和 Sub-Agent 消息
                    val isFromSubAgent = msg.from != "user" && msg.from != "system"
                        && lifecycle.runners.containsKey(msg.from)

                    if (isFromSubAgent) {
                        // Sub-Agent 的消息，忽略（任务已完成）
                        Log.d(TAG, "Ignoring late message from sub-agent '${msg.from}' after completion")
                        continue
                    }

                    // 用户消息，作为反馈返回
                    Log.d(TAG, "Received user feedback after completion: ${msg.content.take(50)}...")
                    return msg.content
                }
                is TeamMessage.ResultReport -> {
                    // Sub-Agent 的延迟报告，忽略
                    Log.d(TAG, "Ignoring late ResultReport from '${msg.from}' after completion")
                    continue
                }
                is TeamMessage.IdleNotification -> {
                    // Sub-Agent 的空闲通知，忽略
                    continue
                }
                else -> {
                    Log.d(TAG, "Ignoring message after completion: ${msg::class.simpleName}")
                }
            }
        }
        return ""
    }

    /**
     * 等待所有 Sub-Agent 就绪。
     */
    internal suspend fun waitForAgentsReady(subAgentNames: List<String>, timeoutMs: Long = 30_000L) {
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
                messageBus.requeue(ORCHESTRATOR_NAME, msg)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 结果收集
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 收集 Orchestrator 收件箱中待处理的 ResultReport 消息。
     */
    internal suspend fun collectPendingResults(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val requeueMessages = mutableListOf<TeamMessage>()

        while (true) {
            val msg = messageBus.tryReceive(ORCHESTRATOR_NAME) ?: break
            when (msg) {
                is TeamMessage.ResultReport -> {
                    results.add(msg.from to msg.result)
                }
                else -> requeueMessages.add(msg)
            }
        }

        for (msg in requeueMessages) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }

        return results
    }

    /**
     * 收集单个 Agent 的执行结果。
     *
     * @param runner Agent 的运行器
     * @param fromIndex 消息起始索引（用于只收集本轮任务的消息）
     * @param forDependency 是否为依赖链结果收集（只取最后一条 assistant 消息）
     */
    internal fun collectAgentResult(runner: AgentRunner, fromIndex: Int = 0, forDependency: Boolean = false): String {
        val history = runner.getHistory()
        // 边界保护：如果 fromIndex 超出历史范围（上下文压缩后），回退到最近的消息
        val safeFromIndex = if (fromIndex >= history.size) {
            // 回退到最后 10 条消息，避免空结果
            (history.size - 10).coerceAtLeast(0)
        } else {
            fromIndex.coerceIn(0, history.size)
        }
        val taskMessages = history.subList(safeFromIndex, history.size)
        val sb = StringBuilder()

        if (forDependency) {
            val assistantMessages = taskMessages.filter { it.role == "assistant" && it.content.isNotBlank() }
            if (assistantMessages.isNotEmpty()) {
                val lastAssistant = assistantMessages.last()
                sb.appendLine(lastAssistant.content)
            }
        } else {
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
     */
    internal fun collectResults(subAgentNames: List<String>, forDependency: Boolean = false): List<Pair<String, String>> {
        return subAgentNames.map { name ->
            val runner = lifecycle.runners[name]
            val result = if (runner != null) {
                collectAgentResult(runner, forDependency = forDependency)
            } else {
                "子任务已完成，无文本输出"
            }
            Pair(name, result)
        }
    }

    /**
     * 汇总 Sub-Agent 执行结果并注入 Orchestrator 上下文。
     */
    internal suspend fun summarizeAndInject(runner: AgentRunner, results: List<Pair<String, String>>) {
        if (results.isEmpty()) return

        val summaryInput = buildString {
            for ((name, result) in results) {
                // 与 executeTask 保持一致的错误检测逻辑：只检查特定角色+前缀
                val hasError = result.startsWith("ERROR:") ||
                    result.startsWith("Error:") ||
                    result.startsWith("执行出错") ||
                    result.startsWith("执行失败")
                val status = if (!hasError) "completed" else "failed"
                val stats = lifecycle.runners[name]?.getUsageStats()
                appendLine("<task-notification>")
                appendLine("  <task-id>$name</task-id>")
                appendLine("  <status>$status</status>")
                appendLine("  <summary>Agent '$name' ${if (!hasError) "completed" else "failed"}</summary>")
                appendLine("  <result>$result</result>")
                appendLine("  <usage>")
                appendLine("    <total_tokens>${stats?.totalTokens ?: 0}</total_tokens>")
                appendLine("    <tool_uses>${stats?.toolUseCount ?: 0}</tool_uses>")
                appendLine("    <duration_ms>${stats?.durationMs ?: 0}</duration_ms>")
                appendLine("  </usage>")
                appendLine("</task-notification>")
            }
        }

        runner.injectMessage("system", summaryInput, isIntervention = false, source = "subagent")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 消息排空
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 排空 Orchestrator 收件箱中多余的 IdleNotification。
     */
    internal suspend fun drainExcessIdleNotifications() {
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
                        kept[msg.from] = msg
                    }
                }
                else -> requeueMessages.add(msg)
            }
        }

        for (msg in requeueMessages) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }
        for (msg in kept.values) {
            messageBus.requeue(ORCHESTRATOR_NAME, msg)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 将 TeamMessage 转换为 WaitResult。
     *
     * 统一处理消息类型的映射逻辑，避免在多处重复 when 表达式。
     */
    private fun convertToWaitResult(msg: TeamMessage): WaitResult {
        return when (msg) {
            is TeamMessage.ShutdownRequest -> WaitResult.ShutdownRequest(msg)
            is TeamMessage.Text -> WaitResult.NewMessage(msg.content, msg.from)
            is TeamMessage.TaskAssignment -> WaitResult.NewMessage(
                "任务: ${msg.subject}\n${msg.description}", msg.from
            )
            else -> WaitResult.NewMessage(
                "[${msg::class.simpleName}] from ${msg.from}", msg.from
            )
        }
    }

    /**
     * 检测完成标记。
     */
    fun isCompletionMarker(text: String): Boolean {
        val trimmed = text.trimEnd()
        return trimmed.endsWith(COMPLETION_MARKER)
            || trimmed.endsWith("【任务完成】。")
            || trimmed.endsWith("【任务完成】！")
            || trimmed.endsWith("【任务完成】!")
    }

    /**
     * 构建 task-notification XML。
     */
    private fun buildTaskNotification(msg: TeamMessage.ResultReport, stats: AgentUsageStats?): String {
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
}
