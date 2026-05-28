package com.example.workspace

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val mcpRuntimeManager: com.example.mcp.McpRuntimeManager,
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
            // FIX (Bug #14): 总是 drain 收件箱里所有待处理的 ResultReport，避免
            // 多个 Sub-Agent 同时完成时其它 ResultReport 被卡在收件箱里直到
            // 下一次"subagent"来源的 wake-up 才被注入。
            //
            // 原实现仅当 currentInput.second == "subagent" 时才调用，理由是避免
            // 在 assign_task 返回后立即注入旧结果触发重复调度。但 assign_task
            // 刚返回时 Sub-Agent 还在执行，pendingResults 必然为空，所以即使每轮
            // 都 collect 也不会引入旧的"假结果"。这样能保证用户消息或系统提醒到达
            // 后，Orchestrator 也能立刻看到所有已完成的子任务结果。
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
                Log.d(TAG, "Orchestrator output completion marker, awaiting user feedback")
                // 完成后先等待用户反馈，再决定是否真正结束
                // 如果用户发现问题，可以继续调度 Sub-Agent 修改
                onAgentStatusChanged(ORCHESTRATOR_NAME, AgentStatus.IDLE)
                val feedbackInput = waitForUserFeedback(isCompleted)

                if (feedbackInput.isBlank()) {
                    // 用户没有反馈（超时或确认完成），真正结束
                    // FIX (workflow): triggerWorkspaceComplete 必须放在用户反馈窗口之后。
                    // 原实现先 trigger（cancel 所有 sub-agent + isCompleted=true）再等反馈，
                    // 用户即使提供反馈也无法继续——sub-agent 已被销毁、isCompleted=true 让
                    // while 循环立即退出。现在反过来：先等反馈，没反馈才 trigger 销毁。
                    Log.d(TAG, "No feedback received, workspace truly completed")
                    completionTriggered = true
                    onWorkspaceComplete(runner)
                    break
                }

                // 用户提供了反馈，继续调度（sub-agent 可能仍存活，可继续工作）
                Log.d(TAG, "Received user feedback after completion, continuing...")
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
                        // FIX (Bug #2 + 工作流): isFreshTask=false 表示 continue_conversation
                        // 或用户干预，executeTask 不会注入"新任务开始"标记，避免丢失上下文。
                        executeTask(runner, identity, waitResult.message, isFreshTask = waitResult.isFreshTask)
                    }
                    is WaitResult.TaskClaimed -> {
                        // claim 模式下认领的任务：始终是新任务
                        executeTask(runner, identity, waitResult.taskDescription, isFreshTask = true)
                    }
                    is WaitResult.PeerMessage -> {
                        // WHY: Peer 消息不走 executeTask，避免被当作任务执行。
                        // 直接注入上下文并运行一轮对话，不包任务标记、不发 ResultReport。
                        handlePeerMessage(runner, identity, waitResult.message, waitResult.from)
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
            // WHY: 不在此处发送 ResultReport —— executeTask 的 finally 块已经保证发送。
            // 如果在此重复发送，Orchestrator 会收到两条 ResultReport（一条来自
            // executeTask.finally，一条来自这里），导致重复注入上下文。
            Log.e(TAG, "Teammate '${identity.agentName}' error: ${e.message}", e)
            onError("Agent '${identity.agentName}' 出错: ${e.message}")
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
     * 从任务描述中提取文件路径并验证是否存在。
     *
     * 扫描任务文本中的文件路径模式（如 /path/to/file.ext），
     * 调用 list_directory 检查父目录中是否包含目标文件。
     *
     * @return 验证结果摘要，如果任务不涉及文件操作则返回 null
     */
    private suspend fun verifyTaskFiles(taskPrompt: String): String? {
        // 匹配文件路径模式：/开头的路径中包含扩展名的部分
        val filePathPattern = Regex("""(/[a-zA-Z0-9_/]+\.[a-zA-Z]{1,10})""")
        val filePaths = filePathPattern.findAll(taskPrompt)
            .map { it.groupValues[1] }
            .distinct()
            .filter { path ->
                // 过滤掉明显的非文件路径（如 /v1/chat/completions）
                val segments = path.trim('/').split('/')
                segments.size >= 2 && !path.startsWith("/v1/") && !path.startsWith("/api/")
            }
            .toList()

        if (filePaths.isEmpty()) return null

        // 查找 list_directory 工具的 serverId
        val listDirServerId = mcpRuntimeManager.findServerIdForTool("list_directory")
        if (listDirServerId == null) {
            Log.w(TAG, "list_directory tool not available, skipping file verification")
            return null
        }

        val results = mutableListOf<String>()
        for (filePath in filePaths) {
            val parentDir = filePath.substringBeforeLast('/')
            val fileName = filePath.substringAfterLast('/')
            if (parentDir.isEmpty() || fileName.isEmpty()) continue

            try {
                val listResult = mcpRuntimeManager.callTool(listDirServerId, "list_directory", org.json.JSONObject().apply {
                    put("path", parentDir)
                })
                val content = listResult?.optString("content", "") ?: ""
                val exists = content.contains(fileName)
                results.add("$fileName ${if (exists) "✅" else "❌ 不存在"}")
            } catch (e: Exception) {
                results.add("$fileName ⚠️ 验证失败: ${e.message}")
            }
        }

        return if (results.isEmpty()) null else results.joinToString(", ")
    }

    /**
     * 执行任务并上报结果。
     *
     * 支持 per-turn abort：如果当前轮次被中止，teammate 回到 idle 而非退出。
     *
     * ## 报告保障机制（Silent Death Fix）
     *
     * 核心不变量：**无论任务如何结束（成功、失败、取消、异常），finally 块保证
     * Orchestrator 一定收到 ResultReport 和 IdleNotification。**
     *
     * 原实现的报告发送分散在三个代码路径（成功路径的 withContext、catch(CancellationException)、finally），
     * 每条路径的 send 都可能失败，且没有保底机制。如果 withContext(NonCancellable)
     * 内的 send 自身抛异常（Channel 关闭、内存不足等），Orchestrator 将永远收不到通知。
     *
     * 修复方案：所有报告发送统一收拢到 finally 块，try 块只负责计算结果。
     */
    private suspend fun executeTask(
        runner: AgentRunner,
        identity: TeammateIdentity,
        taskPrompt: String,
        // FIX (Bug #2): 区分"分配新任务"与"延续对话"两种场景。
        // - true（assign_task / claim 模式认领）→ 注入"新任务开始"标记，
        //   要求 LLM 忽略旧任务的待办事项，专注新任务。
        // - false（continue_conversation / 用户干预）→ 不注入标记，让 LLM
        //   利用已有上下文继续工作。
        isFreshTask: Boolean = true,
    ) {
        if (taskPrompt.isBlank()) {
            Log.w(TAG, "Teammate '${identity.agentName}' received empty task prompt, skipping execution")
            sendReportSafely(identity, "收到空任务，跳过执行", success = false)
            onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)
            sendIdleSafely(identity, IdleReason.AVAILABLE)
            return
        }

        // 任务执行结果，由 try 块计算，finally 块负责上报。
        var resultSummary = "Agent 意外停止或被系统中断，任务未完成。"
        var hasError = true
        var taskCompleted = false
        var idleReason = IdleReason.AVAILABLE

        try {
            val teammateCtx = coroutineContext[TeammateContext]
            teammateCtx?.resetTurnAbort()

            val messageOffset = runner.getHistory().size
            onAgentStatusChanged(identity.agentName, AgentStatus.STREAMING)
            // FIX (Bug #2): 仅在全新任务（assign_task / 自动认领）时注入"新任务开始"标记。
            // continue_conversation 和用户干预属于上下文延续，注入此标记会让 LLM
            // 把之前的待办事项一并丢弃，破坏延续语义。
            if (isFreshTask) {
                runner.injectSystemMessage("═══ 新任务开始 ═══\n以下是全新的任务指令。请忽略之前所有任务计划和待办事项，专注于执行以下新任务。")
            }
            runner.runTurn(taskPrompt, source = "orchestrator")

            // runTurn 正常返回即视为任务执行完毕
            taskCompleted = true

            // 任务完成后生成进度摘要，注入 Orchestrator 上下文以便追踪
            try {
                val progressSummary = AgentProgressSummarizer(identity.agentName) { runner.getHistory() }
                    .forceSummarize()
                if (progressSummary.isNotBlank()) {
                    messageBus.send(
                        ORCHESTRATOR_NAME,
                        TeamMessage.Text(from = "system", content = progressSummary)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Progress summary injection failed (non-fatal): ${e.message}")
            }

            // 自动验证：检查任务中提到的文件是否真的存在
            val fileVerification = verifyTaskFiles(taskPrompt)

            if (teammateCtx?.isCurrentTurnAborted == true) {
                Log.d(TAG, "Teammate '${identity.agentName}' turn aborted, returning to idle")
                idleReason = IdleReason.INTERRUPTED
                resultSummary = "任务被中断"
                // FIX (Bug #7): 中断的任务不应上报为成功。
                // 标记为失败让 Orchestrator 收到 status=failed 的 task-notification，
                // 避免它误以为任务已完成而进入下一阶段。
                hasError = true
                taskCompleted = false
                // DB 也需同步置为 FAILED，否则数据库行残留 IN_PROGRESS 状态
                try {
                    taskManager.failTask(identity.teamName, identity.agentName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to mark interrupted task failed for ${identity.agentName}", e)
                }
                // 不 return，让 finally 统一发送报告和 IdleNotification
            } else {
                // 收集结果（锁外操作，可能抛异常）
                try {
                    resultSummary = collectAgentResult(runner, fromIndex = messageOffset)
                    if (fileVerification != null) {
                        resultSummary += "\n\n【自动验证】$fileVerification"
                    }

                    val history = runner.getHistory()
                    val safeOffset = if (messageOffset >= history.size) {
                        val lastTaskStart = history.indexOfLast { it.role == "user" && it.source == "orchestrator" }
                        if (lastTaskStart >= 0) lastTaskStart else (history.size - 10).coerceAtLeast(0)
                    } else {
                        messageOffset.coerceIn(0, history.size)
                    }
                    val taskMessages = history.subList(safeOffset, history.size)
                    // BUG-024 修复：使用更灵活的错误检测，支持多种错误格式
                    // 原实现只检查特定前缀（如 "ERROR:"），可能遗漏其他格式的错误
                    hasError = taskMessages.any { msg ->
                        val content = msg.content
                        when (msg.role) {
                            "assistant" -> {
                                content.startsWith("ERROR:", ignoreCase = true) ||
                                    content.startsWith("error:", ignoreCase = true) ||
                                    content.contains("执行出错") ||
                                    content.contains("执行失败") ||
                                    content.contains("任务失败") ||
                                    content.contains("操作失败")
                            }
                            "tool" -> {
                                content.startsWith("Error:", ignoreCase = true) ||
                                    content.startsWith("error:", ignoreCase = true) ||
                                    content.startsWith("FAILED:", ignoreCase = true) ||
                                    content.contains("失败") ||
                                    content.contains("failed")
                            }
                            "system" -> {
                                content.contains("执行出错") ||
                                    content.contains("执行失败") ||
                                    content.contains("任务失败") ||
                                    content.contains("操作失败") ||
                                    content.contains("出错") ||
                                    content.contains("错误")
                            }
                            else -> false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to collect result for ${identity.agentName}", e)
                    resultSummary = "任务执行完成但结果收集失败: ${e.message}"
                    hasError = true
                }

                // 更新任务状态（尽力而为，失败不影响报告发送）
                try {
                    taskManager.completeTask(identity.teamName, identity.agentName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to mark task complete for ${identity.agentName}", e)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 外部取消（killTeammate 调用 scope.cancel()）
            // FIX (workflow): suspend 调用在 CancelledScope 上会立刻抛 CE，
            // 所以 failTask / sendReport 等清理动作必须包在 NonCancellable 内执行。
            if (!taskCompleted) {
                resultSummary = "Agent 被系统中止，任务未完成。"
                hasError = true
                idleReason = IdleReason.FAILED
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        taskManager.failTask(identity.teamName, identity.agentName)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to mark task failed for ${identity.agentName}", e2)
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            // 非取消异常（API 错误等）
            resultSummary = "Agent 执行出错: ${e.message}"
            hasError = true
            taskCompleted = false
            idleReason = IdleReason.FAILED
            try {
                taskManager.failTask(identity.teamName, identity.agentName)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to mark task failed for ${identity.agentName}", e2)
            }
            throw e
        } finally {
            // ═══ 保底上报：无论走哪条路径，这里一定执行 ═══
            // 1. 发送 ResultReport（Orchestrator 收到后才知道任务结束）
            sendReportSafely(identity, resultSummary, success = taskCompleted && !hasError)
            // 2. 更新 UI 状态
            onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)
            // 3. 发送 IdleNotification（Orchestrator 知道 Agent 可接收新任务）
            sendIdleSafely(identity, idleReason)
        }
    }

    /**
     * 处理来自其他 Sub-Agent 的协作消息（peer_message）。
     *
     * 与 [executeTask] 的关键区别：
     * - 不包 "═══ 新任务开始 ═══" 标记（不是任务，是协作消息）
     * - 不发 ResultReport 给 Orchestrator（避免混淆 Orchestrator 的任务跟踪）
     * - 不操作 TaskManager（不标记 complete/fail）
     * - Agent 回复通过 peer_message 直接返回给发送方，而非经 Orchestrator 中转
     */
    private suspend fun handlePeerMessage(
        runner: AgentRunner,
        identity: TeammateIdentity,
        message: String,
        from: String,
    ) {
        try {
            onAgentStatusChanged(identity.agentName, AgentStatus.STREAMING)
            // WHY: 注入来源标记，让 Agent 知道这是来自其他 Agent 的协作消息
            val taggedMessage = "[来自 $from 的协作消息]\n$message"
            runner.runTurn(taggedMessage, source = "peer")
        } catch (e: Exception) {
            Log.e(TAG, "Peer message handling failed for ${identity.agentName}: ${e.message}", e)
        } finally {
            onAgentStatusChanged(identity.agentName, AgentStatus.IDLE)
            // 只发 IdleNotification（通知 Orchestrator Agent 空闲），不发 ResultReport
            sendIdleSafely(identity, IdleReason.AVAILABLE)
        }
    }

    /**
     * 安全发送 ResultReport，捕获所有异常。
     *
     * 无论 Channel 状态如何，尽力发送，失败只记录日志。
     *
     * FIX (Bug #10): 加 5 秒超时。原实现使用 suspend send，若 Orchestrator
     * 收件箱被填满（容量 256），Sub-Agent 的 finally 块会无限挂起，
     * 协程无法正常终止，killTeammate 的 join 也只能强制取消。
     *
     * FIX (workflow): finally 块在 CancellationException 路径下运行时，
     * 父协程已处于 cancelled 状态，普通的 suspend 调用会立即抛 CE。
     * 必须在 NonCancellable 上下文中执行，确保关键的清理上报能完成。
     */
    private suspend fun sendReportSafely(identity: TeammateIdentity, result: String, success: Boolean) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val sent = withTimeoutOrNull(5_000L) {
                    messageBus.send(
                        ORCHESTRATOR_NAME,
                        TeamMessage.ResultReport(
                            from = identity.agentName,
                            taskId = "",
                            result = result,
                            success = success,
                        )
                    )
                    true
                }
                if (sent == null) {
                    Log.e(TAG, "CRITICAL: ResultReport for ${identity.agentName} timed out — Orchestrator inbox blocked")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to send ResultReport for ${identity.agentName} — Orchestrator may hang", e)
        }
    }

    /**
     * 安全发送 IdleNotification，捕获所有异常。
     *
     * FIX (Bug #10 + workflow): 同 sendReportSafely，使用 NonCancellable 包裹超时 send。
     */
    private suspend fun sendIdleSafely(identity: TeammateIdentity, reason: IdleReason) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val sent = withTimeoutOrNull(5_000L) {
                    messageBus.send(
                        ORCHESTRATOR_NAME,
                        TeamMessage.IdleNotification(
                            from = identity.agentName,
                            idleReason = reason,
                        )
                    )
                    true
                }
                if (sent == null) {
                    Log.e(TAG, "CRITICAL: IdleNotification for ${identity.agentName} timed out — Orchestrator inbox blocked")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to send IdleNotification for ${identity.agentName} — Orchestrator may hang", e)
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
                if (msg is TeamMessage.Wakeup) {
                    // 仅为唤醒信号，丢弃并继续检查任务
                } else {
                    return convertToWaitResult(msg)
                }
            }

            // 检查任务列表
            val task = taskManager.tryClaimNextTask(identity.teamName, identity.agentName)
            if (task != null) {
                // 认领成功后再检查一次收件箱（竞态窗口保护）
                val pendingMsg = messageBus.tryReceive(identity.agentName)
                if (pendingMsg != null) {
                    if (pendingMsg is TeamMessage.Wakeup) {
                        // 忽略唤醒信号
                    } else if (pendingMsg is TeamMessage.ShutdownRequest) {
                        // ShutdownRequest 优先级最高：放弃已认领的任务，处理关闭请求
                        taskManager.abandonTask(identity.teamName, identity.agentName)
                        return WaitResult.ShutdownRequest(pendingMsg)
                    } else {
                        // 其他消息 requeue 回去，优先处理已认领的任务
                        messageBus.requeue(identity.agentName, pendingMsg)
                    }
                }
                Log.d(TAG, "Teammate '${identity.agentName}' claimed task #${task.id}")
                return WaitResult.TaskClaimed(
                    "任务 #${task.id}: ${task.subject}\n${task.description}"
                )
            }

            // 邮箱空且无可认领任务，挂起等待
            val received = try {
                coroutineScope {
                    val job = launch {
                        taskManager.observeTasks(identity.teamName).collect { tasks ->
                            val completedIds = tasks.filter { it.status == "COMPLETED" }.map { it.id }.toSet()
                            val hasClaimableTask = tasks.any { task ->
                                task.status == "PENDING" && task.owner == null &&
                                        (task.intendedAgent == null || task.intendedAgent == identity.agentName) &&
                                        (task.blockedBy.isEmpty() || task.blockedBy.all { depIdStr ->
                                            depIdStr.toLongOrNull() in completedIds
                                        })
                            }
                            if (hasClaimableTask) {
                                messageBus.send(identity.agentName, TeamMessage.Wakeup)
                            }
                        }
                    }
                    try {
                        messageBus.receive(identity.agentName)
                    } finally {
                        job.cancel()
                    }
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                Log.d(TAG, "Channel closed for ${identity.agentName}")
                return WaitResult.Aborted
            } catch (e: kotlinx.coroutines.CancellationException) {
                // WHY: CancellationException 必须重新抛出，否则协程取消信号被吞掉，
                // Agent 陷入无限轮询（while 循环不退出，receive 不断超时返回 null）。
                throw e
            } catch (e: Exception) {
                // WHY: 添加日志，避免意外异常被静默吞掉后难以调试
                Log.w(TAG, "Unexpected error waiting for message for ${identity.agentName}: ${e.message}", e)
                null
            }

            if (received != null) {
                if (received is TeamMessage.Wakeup) {
                    // 仅为唤醒信号，循环重新开始，会去检查认领任务
                } else {
                    return convertToWaitResult(received)
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
                } else if (!isCompleted.get()) {
                    Log.w(TAG, "Orchestrator input timeout, injecting system prompt to wake it up")
                    return Triple("系统提示：当前等待时间过长且无活动消息。如果整体任务已完成，请在回复中输出【任务完成】；如果未完成，请继续分析进度并分配任务。", "system", null)
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
            // FIX (workflow): 外部强制设置 isCompleted（例如用户在 UI 点击"停止"）时，
            // 应当立即退出而不是继续等待 5 分钟超时。
            if (isCompleted.get()) {
                Log.d(TAG, "isCompleted set externally, exiting feedback wait")
                return ""
            }
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
            // 回退策略：找到最近的一个 orchestrator 下发的 user 消息作为任务起点
            val lastTaskStart = history.indexOfLast { it.role == "user" && it.source == "orchestrator" }
            if (lastTaskStart >= 0) lastTaskStart else (history.size - 10).coerceAtLeast(0)
        } else {
            fromIndex.coerceIn(0, history.size)
        }
        val taskMessages = history.subList(safeFromIndex, history.size)
        val sb = StringBuilder()

        val toolResults = taskMessages.filter { it.role == "tool" && it.content.isNotBlank() }
        val assistantMessages = taskMessages.filter { it.role == "assistant" && it.content.isNotBlank() }

        if (forDependency) {
            if (assistantMessages.isNotEmpty()) {
                sb.appendLine(assistantMessages.last().content)
            } else if (toolResults.isNotEmpty()) {
                sb.appendLine("【工具执行结果摘要】")
                for (toolMsg in toolResults) {
                    val content = toolMsg.content
                    if (content.length > 500) {
                        sb.appendLine(content.take(500) + "...")
                    } else {
                        sb.appendLine(content)
                    }
                }
            }
        } else {
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

            val lastAssistant = assistantMessages.lastOrNull()
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

            // 附加当前可用 Agent 列表，提示 Orchestrator 复用已有 Agent
            val availableAgents = lifecycle.runners.keys.filter { it != ORCHESTRATOR_NAME }
            if (availableAgents.isNotEmpty()) {
                appendLine()
                appendLine("当前团队中可用的 Agent（可直接用 assign_task 分配新任务，无需重新创建）：")
                for (agentName in availableAgents) {
                    appendLine("- $agentName")
                }
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
     *
     * FIX (Bug #2): 来自 Orchestrator 的 Text 必须映射为 NewMessage 而非 PeerMessage。
     * Orchestrator 通过 [TeamManager.continueAgent] / [TeamManager.sendMessage] 发送
     * Text 消息（如 continue_conversation 工具或用户干预），Sub-Agent 必须把它当作
     * 新任务执行（走 executeTask 路径），从而向 Orchestrator 回送 ResultReport。
     * 原实现把所有 Text 都当作 peer 协作消息，导致：
     *   - Orchestrator 的 continue_conversation 不会收到 <task-notification>
     *   - 用户对 Sub-Agent 的干预不会回流给 Orchestrator
     *   - Orchestrator 永久挂起在 waitForOrchestratorInput 直到 30 分钟超时
     */
    private fun convertToWaitResult(msg: TeamMessage): WaitResult {
        return when (msg) {
            is TeamMessage.ShutdownRequest -> WaitResult.ShutdownRequest(msg)
            is TeamMessage.Text -> {
                // Orchestrator/user/system 来源 → 当作消息（需回送 ResultReport），
                // 但不算"新任务"——continue_conversation 和用户干预要保留已有上下文，
                // 不能注入"新任务开始"标记。
                // 其它 Sub-Agent 来源 → 协作消息（peer），不打扰 Orchestrator。
                val isFromOrchestratorOrUser =
                    msg.from == ORCHESTRATOR_NAME ||
                    msg.from == "user" ||
                    msg.from == "system"
                if (isFromOrchestratorOrUser) {
                    WaitResult.NewMessage(msg.content, msg.from, isFreshTask = false)
                } else {
                    WaitResult.PeerMessage(msg.content, msg.from)
                }
            }
            is TeamMessage.TaskAssignment -> WaitResult.NewMessage(
                "任务: ${msg.subject}\n${msg.description}",
                msg.from,
                isFreshTask = true,
            )
            is TeamMessage.Wakeup -> {
                // FIX (Bug #13): Wakeup 不应被解释为 Aborted。
                Log.w(TAG, "Wakeup reached convertToWaitResult unexpectedly; treating as no-op")
                WaitResult.NewMessage("[wakeup]", "system", isFreshTask = false)
            }
            else -> WaitResult.NewMessage(
                "[${msg::class.simpleName}] from ${msg.from}",
                msg.from,
                isFreshTask = false,
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

            // 附加当前可用 Agent 列表，提示 Orchestrator 复用已有 Agent
            val availableAgents = lifecycle.runners.keys.filter { it != ORCHESTRATOR_NAME }
            if (availableAgents.isNotEmpty()) {
                appendLine()
                appendLine("当前团队中可用的 Agent（可直接用 assign_task 分配新任务，无需重新创建）：")
                for (agentName in availableAgents) {
                    appendLine("- $agentName")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// AgentTask 实现
//
// 将 runOrchestratorLoop / runTeammateLoop 封装为 AgentTask，供 TaskRegistry 统一管理。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Orchestrator task implementation.
 *
 * Wraps runOrchestratorLoop as an AgentTask.
 */
class OrchestratorTask(
    override val taskName: String,
    private val runner: AgentRunner,
    private val executionLoops: AgentExecutionLoops,
    private val initialTask: String,
    private val imagePath: String? = null,
    private val isCompleted: java.util.concurrent.atomic.AtomicBoolean,
    private val onWorkspaceComplete: suspend (AgentRunner) -> Unit,
) : AgentTask {
    override val taskId = ORCHESTRATOR_NAME
    override val taskType = TaskType.ORCHESTRATOR

    @Volatile
    override var status: TaskStatus = TaskStatus.CREATED
        private set

    private val statusListeners = mutableListOf<(TaskStatus) -> Unit>()

    override suspend fun execute() {
        updateStatus(TaskStatus.RUNNING)
        try {
            executionLoops.runOrchestratorLoop(runner, initialTask, imagePath, isCompleted, onWorkspaceComplete)
            updateStatus(TaskStatus.COMPLETED)
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateStatus(TaskStatus.CANCELLED)
            throw e
        } catch (e: Exception) {
            updateStatus(TaskStatus.FAILED)
            throw e
        }
    }

    override fun kill() {
        updateStatus(TaskStatus.CANCELLED)
    }

    override fun onStatusChange(listener: (TaskStatus) -> Unit) {
        statusListeners.add(listener)
    }

    private fun updateStatus(newStatus: TaskStatus) {
        val oldStatus = status
        status = newStatus
        if (oldStatus != newStatus) {
            statusListeners.forEach { it(newStatus) }
        }
    }
}

/**
 * Teammate task implementation.
 *
 * Wraps runTeammateLoop as an AgentTask.
 */
class TeammateTask(
    override val taskName: String,
    private val runner: AgentRunner,
    private val identity: TeammateIdentity,
    private val executionLoops: AgentExecutionLoops,
) : AgentTask {
    override val taskId = identity.agentName
    override val taskType = TaskType.TEAMMATE

    @Volatile
    override var status: TaskStatus = TaskStatus.CREATED
        private set

    private val statusListeners = mutableListOf<(TaskStatus) -> Unit>()

    override suspend fun execute() {
        updateStatus(TaskStatus.RUNNING)
        try {
            executionLoops.runTeammateLoop(runner, identity)
            updateStatus(TaskStatus.COMPLETED)
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateStatus(TaskStatus.CANCELLED)
            throw e
        } catch (e: Exception) {
            updateStatus(TaskStatus.FAILED)
            throw e
        }
    }

    override fun kill() {
        updateStatus(TaskStatus.CANCELLED)
    }

    override fun onStatusChange(listener: (TaskStatus) -> Unit) {
        statusListeners.add(listener)
    }

    private fun updateStatus(newStatus: TaskStatus) {
        val oldStatus = status
        status = newStatus
        if (oldStatus != newStatus) {
            statusListeners.forEach { it(newStatus) }
        }
    }
}
