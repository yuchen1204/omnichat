package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ═══════════════════════════════════════════════════════════════════════════════
// 默认 Teammate 系统提示（顶层常量）
//
// WHY: 原本作为 AgentLifecycle 的实例字段存在，每个实例都会在堆上重复持有同一份字符串。
// 提取为顶层 const 后所有实例共享同一引用，且避免在反射/拷贝场景下被意外修改。
// ═══════════════════════════════════════════════════════════════════════════════

internal const val DEFAULT_TEAMMATE_PROMPT = """
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

## 必须使用工具执行操作

当任务要求你创建文件、读取文件、执行命令等操作时，
你必须立即调用相应的工具完成。不要回复"我马上创建"、"接下来我会..."之类的文本
而不实际调用工具。每次回复中如果涉及操作，必须包含至少一个 tool_call。
如果你说"我要做 X"，那 X 必须在同一个回复中通过工具调用完成。

## 任务分解与进度跟踪

收到任务后，你必须先制定执行计划，再开始执行：

1. **规划阶段**：分析任务要求，将任务拆解为具体步骤，以结构化格式输出：
   任务计划：
   - [ ] 步骤 1：创建 index.html（主页面结构）
   - [ ] 步骤 2：创建 style.css（样式文件）
   - [ ] 步骤 3：创建 script.js（游戏逻辑）
   - [ ] 步骤 4：验证所有文件已创建且引用正确

2. **执行阶段**：每完成一步，用工具调用标记完成：
   ✅ 步骤 1 完成：index.html 已创建（4236 字节）

3. **完成标准**：所有步骤标记为 ✅ 后，才能报告任务完成。
   如果某个步骤失败，说明失败原因，不要跳过。

⚠️ 不要跳过规划阶段：即使任务看起来简单，也必须先列出步骤再执行。
这能帮助你不会遗漏任何要求。

## 多文件创建流程

如果任务要求创建多个文件（如 HTML + CSS + JS），
你必须在一个 turn 内依次创建所有文件。不要只创建第一个文件就停止。
工作流程：
1. 创建第一个文件（调用 write_file）
2. 工具返回成功后，立即创建第二个文件（再次调用 write_file）
3. 重复直到所有文件创建完成
4. 最后用 list_directory 确认所有文件已存在

## 关键行为准则

⚠️ 完成前必须逐项验证：在报告"任务完成"之前，你必须：
1. 逐项检查任务描述中的每一个要求是否都已满足
2. 如果任务要求创建文件，用 read_file 或 list_directory 确认文件确实存在且内容正确
3. 如果任务要求修改多个位置，确认所有位置都已修改
4. 不要只做了任务的一部分就报告完成
5. 不要仅凭自己的记忆判断完成状态，必须通过工具调用验证

🚫 只做自己的任务：
- 你只负责执行分配给你的任务，不要执行其他 Agent 的任务
- 如果任务描述中提到了其他 Agent 的名字或职责（如 "Agent B 负责..."），那是其他 Agent 的工作，不是你的
- 不要因为看到任务背景中提到了多个子任务就试图完成所有子任务
- 只做「你的任务」部分明确要求的内容

不要越权：严格按照任务描述执行，不要做任务之外的事。

结果要自包含：汇报结果时，把关键数据直接写在回复里，不要只说"已生成报告文件，请查看"。

## Sub-Agent 间协作（peer_message 工具）

你可以使用 peer_message 工具与其他 Sub-Agent 直接通信：
- 点对点：peer_message(to: "researcher", message: "请把分析结果发给我")
- 广播：peer_message(to: "*", message: "我已完成数据库迁移，请更新相关代码")

⚠️ 使用正确的 Agent 名称：发送 peer_message 时，to 参数必须使用
任务上下文中明确给出的 Agent 名称（如 "CodeWriter"），不要自行猜测或使用
简称（如 "coder"）。如果名称不存在，工具会返回错误，错误信息中会列出
当前可用的 Agent 名称，请使用正确的名称重试。

⚠️ peer_message 失败时的处理：如果 peer_message 返回 Error（如目标 Agent 不存在），
不要卡在那里反复重试。正确做法是：
1. 在你的任务结果中直接描述你发现的问题或需要传递的信息
2. 完成你的任务并正常汇报结果
3. Orchestrator 会根据你的结果决定后续协调工作

任务完成后必须停止：完成任务并汇报结果后，立即停止，不要继续生成额外内容，
不要等待其他 Agent 的响应，不要尝试做超出任务范围的事情。

## 文件系统环境
本设备为 Android 系统。文件系统工具的根目录为 "/"，对应设备存储根路径。
[SANDBOX_PATH]

可用工具：
[MCP_TOOLS]
"""

// ═══════════════════════════════════════════════════════════════════════════════
// Agent 生命周期管理
//
// 对标 Claude Code 的 spawnInProcess.ts + teamHelpers.ts。
// 负责团队创建、Agent 生成/终止、颜色分配等生命周期操作。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Agent 生命周期管理器。
 *
 * 从 TeamManager 中提取的 Agent 创建和生命周期管理逻辑。
 * 对标 Claude Code 的 spawnInProcess.ts。
 *
 * @property repository 数据仓库
 * @property messageBus 消息总线
 * @property taskManager 任务管理器
 * @property config 工作区配置
 * @property onError 错误回调
 */
class AgentLifecycle(
    private val repository: AppRepository,
    private val messageBus: MessageBus,
    private val taskManager: TaskManager,
    private val config: WorkspaceConfig,
    // WHY: AgentRegistry 提供 AgentDefinition 查找，替代 spawnTeammate 中的 preset 直接查询
    private val agentRegistry: AgentRegistry,
    // WHY: TaskRegistry 管理 AgentTask 生命周期，cleanupAll 时统一终止和清理
    private val taskRegistry: TaskRegistry,
    private val onError: (message: String) -> Unit,
) {
    companion object {
        private const val TAG = "AgentLifecycle"
    }

    private val modelSelector = ModelSelector(repository)

    // ─── 共享状态（由 TeamManager 持有，通过 init 注入）───

    /** Agent Runner 映射 */
    internal val runners = ConcurrentHashMap<String, AgentRunner>()

    /** Teammate 协程 Job */
    internal val teammateJobs = ConcurrentHashMap<String, Job>()

    /** Teammate 协程作用域 */
    internal val teammateScopes = ConcurrentHashMap<String, CoroutineScope>()

    /** Agent 完成信号（用于依赖流程） */
    internal val agentCompletionDeferreds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** 颜色分配索引 */
    private val colorIndex = AtomicInteger(0)

    /**
     * 跨会话记忆文本。
     *
     * @Volatile 确保多线程可见性：[loadCrossSessionMemory] 在 createTeam 时由
     * Orchestrator 协程写入，[spawnTeammate] 在另一线程读取传给 AgentRunner。
     * 没有 @Volatile 时存在数据竞争（写后被读到旧值）。
     */
    @Volatile
    internal var crossSessionMemoryText: String = ""

    // ═══════════════════════════════════════════════════════════════════════════════
    // Agent 创建
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 创建 Teammate（Sub-Agent）。
     *
     * 创建独立 [CoroutineScope]（[SupervisorJob] + [TeammateContext]），
     * 启动 AgentRunner 执行循环。对标 Claude Code 的 spawnInProcessTeammate。
     *
     * @param name Sub-Agent 名称
     * @param prompt 初始提示
     * @param systemPrompt 系统提示（可选）
     * @param modelConfigId 模型配置 ID（可选）
     * @param existingNames 已存在的名称集合（用于去重）
     * @param parentScope 父协程作用域
     * @param createRunner AgentRunner 创建工厂
     * @param executeLoop 执行循环入口
     * @return 创建的 Teammate 身份信息
     */
    suspend fun spawnTeammate(
        name: String,
        prompt: String,
        systemPrompt: String = "",
        modelConfigId: Long? = null,
        overrideModelId: String? = null,
        modelHint: ModelHint? = null,
        // WHY: 支持 FORK 模式，允许新 Agent 继承父级的对话历史和系统提示，减少重复提示词传递
        spawnMode: SpawnMode = SpawnMode.SPAWN,
        // WHY: FORK 模式下必须提供父级 AgentRunner，用于获取历史和系统提示
        parentRunner: AgentRunner? = null,
        existingNames: Set<String>,
        parentScope: CoroutineScope,
        createRunner: suspend (AgentContext, Boolean, Int) -> AgentRunner,
        executeLoop: suspend (AgentRunner, TeammateIdentity) -> Unit,
    ): TeammateIdentity {
        // WHY: 使用 size - containsKey 替代 count { predicate }（Bug #27）。
        // ConcurrentHashMap.count 需要遍历整个 map，在遍历期间其他线程可能修改 map，
        // 导致计数略微不准确。size 和 containsKey 都是 O(1) 且对 ConcurrentHashMap
        // 是原子操作，结果更可靠，性能也更好。
        val subAgentCount = runners.size - if (runners.containsKey(ORCHESTRATOR_NAME)) 1 else 0
        if (subAgentCount >= config.maxSubAgents) {
            val errorMessage = "已达到子 Agent 上限（${config.maxSubAgents}个），无法继续创建"
            Log.w(TAG, errorMessage)
            onError(errorMessage)
            error(errorMessage)
        }

        // 生成唯一名称
        val uniqueName = generateUniqueName(name, existingNames)
            .take(config.maxAgentNameLength)

        // FIX (Bug #6): 注册中心和预设查找应使用调用方传入的原始 name 而非生成的 uniqueName。
        // generateUniqueName 在重名时会追加数字后缀（CodeWriter → CodeWriter2），
        // 但同名 Agent 应共享相同的 SystemPrompt/modelHint/preset，否则后建的 Agent 会
        // 退化为默认配置（无法找到 AgentDefinition / AgentPreset）。
        val agentDef = agentRegistry.get(name)
        val finalSystemPrompt = (
            agentDef?.systemPrompt?.takeIf { it.isNotEmpty() }
                ?: systemPrompt.takeIf { it.isNotEmpty() }
                ?: ""
        ).take(config.maxSystemPromptLength)

        // 确定模型配置
        // WHY: AgentDefinition 不含 modelConfigId，仍需从 Room preset 查找模型配置 ID
        val presetModelConfigId = repository.getAllAgentPresets()
            .find { it.name == name }?.modelConfigId
        val orchestratorRunner = runners[ORCHESTRATOR_NAME]
            ?: error("无法生成 Sub-Agent '$uniqueName'：Orchestrator runner 不存在（团队可能已被销毁）")
        val orchestratorConfig = orchestratorRunner.getModelConfig()
        val selection = modelSelector.resolve(
            presetModelConfigId = presetModelConfigId,
            explicitConfigId = modelConfigId,
            explicitModelId = overrideModelId,
            modelHint = modelHint,
            orchestratorConfig = orchestratorConfig
        )
        val actualModelConfig = selection.modelConfigId?.let { repository.getConfigById(it) }
            ?: orchestratorConfig
        val actualOverrideModelId = selection.modelId
        val teamName = orchestratorRunner.getTeamName().ifEmpty { "default" }
        val identity = TeammateIdentity(
            agentId = "${uniqueName}@${teamName}",
            agentName = uniqueName,
            teamName = teamName,
            color = assignColor(),
            parentSessionId = "leader",
        )

        // 创建 AgentContext（根据 spawnMode 决定是否继承父级上下文）
        // WHY: FORK 模式复用父级对话历史和系统提示，SPAWN 模式使用全新上下文
        val context = when (spawnMode) {
            SpawnMode.FORK -> {
                requireNotNull(parentRunner) { "Fork mode requires parentRunner" }
                AgentContext(
                    agentName = uniqueName,
                    isOrchestrator = false,
                    systemPrompt = parentRunner.getSystemPrompt(),
                    modelConfig = actualModelConfig,
                    overrideModelId = actualOverrideModelId,
                    teamName = teamName,
                    messages = ArrayList(parentRunner.getHistory()),
                )
            }
            SpawnMode.SPAWN -> {
                AgentContext(
                    agentName = uniqueName,
                    isOrchestrator = false,
                    systemPrompt = finalSystemPrompt.ifEmpty { DEFAULT_TEAMMATE_PROMPT },
                    modelConfig = actualModelConfig,
                    overrideModelId = actualOverrideModelId,
                    teamName = teamName,
                    messages = ArrayList(),
                )
            }
        }

        // 创建 AgentRunner（子 Agent 禁用编排工具）
        // FIX (Bug #8): 传递 AgentDefinition 中的 maxToolIterations，否则 explorer/verifier
        // 等预设设置的较小循环上限被忽略，所有 sub-agent 均使用默认 50 次。
        val maxIter = agentDef?.maxToolIterations ?: AgentRunner.MAX_TOOL_CALL_ITERATIONS
        val runner = createRunner(context, true, maxIter)
        runners[uniqueName] = runner

        // 创建独立 CoroutineScope（对标 Claude Code 的 SupervisorJob + TeammateContext）
        val teammateScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        teammateScopes[uniqueName] = teammateScope
        
        // 显式创建收件箱并清除“已移除”标记，确保同名 Agent 被重新创建时 Channel 正常
        messageBus.explicitCreateInbox(uniqueName)

        // 注册完成 Deferred
        agentCompletionDeferreds[uniqueName] = CompletableDeferred()

        Log.d(TAG, "Spawned teammate '$uniqueName' with model ${actualModelConfig.name}${if (actualOverrideModelId != null) "/$actualOverrideModelId" else ""} (${selection.reason})")

        // 触发 Teammate 创建 Hook
        WorkspaceScopes.auxiliary.launch {
            try {
                com.example.hooks.HookManager.dispatchTeammateSpawned(
                    agentName = uniqueName,
                    role = finalSystemPrompt.take(200),
                    teamName = identity.teamName,
                    color = identity.color,
                )
            } catch (e: Exception) {
                Log.w(TAG, "dispatchTeammateSpawned failed (non-fatal)", e)
            }
        }

        // 启动执行循环
        val job = teammateScope.launch {
            executeLoop(runner, identity)
        }
        teammateJobs[uniqueName] = job

        return identity
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Agent 终止
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 强制终止指定 Teammate。
     *
     * 取消其协程作用域，等待 Job 完成后再清理资源。
     * 对标 Claude Code 的 killInProcessTeammate。
     *
     * @param agentName 目标 Agent 名称
     */
    suspend fun killTeammate(agentName: String) {
        Log.d(TAG, "Killing teammate '$agentName'")

        // 取消协程作用域
        teammateScopes[agentName]?.let {
            it.coroutineContext[TeammateContext]?.abort()
            it.cancel()
        }

        // WHY: 添加 5 秒超时，防止 job.join() 永久阻塞。
        // 场景：Agent 的 runTurn 卡在 OkHttp 阻塞调用时，scope.cancel() 无法中断
        // 底层网络 I/O，导致 join() 永远等不到协程结束。超时后强制清理资源。
        try {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                teammateJobs[agentName]?.join()
            } ?: run {
                Log.w(TAG, "Timeout waiting for teammate '$agentName' job to complete, forcing cleanup")
            }
        } catch (_: Exception) { }

        // 触发 Teammate 销毁 Hook
        val teamName = runners[agentName]?.getTeamName() ?: ""
        WorkspaceScopes.auxiliary.launch {
            try {
                com.example.hooks.HookManager.dispatchTeammateKilled(
                    agentName = agentName,
                    teamName = teamName,
                    reason = "killed",
                )
            } catch (e: Exception) {
                Log.w(TAG, "dispatchTeammateKilled failed (non-fatal)", e)
            }
        }

        // 清理资源
        cleanupAgent(agentName)
    }

    /**
     * 清理单个 Agent 的资源。
     */
    internal fun cleanupAgent(agentName: String) {
        teammateScopes.remove(agentName)
        teammateJobs.remove(agentName)
        runners[agentName]?.dispose()
        runners.remove(agentName)
        messageBus.removeInbox(agentName)
        agentCompletionDeferreds.remove(agentName)
    }

    /**
     * 清理所有资源。
     *
     * 先取消所有协程作用域，再清理映射和 runner。
     *
     * BUG-025 修复：ConcurrentHashMap 的 forEach 是弱一致性的，
     * 在遍历过程中如果其他线程修改了 map，可能不会反映在遍历中。
     * 修复：先复制 keys/values 到独立列表，再遍历复制的列表。
     *
     * FIX (workflow): job.join() 在被取消的 OkHttp 阻塞调用上仍可能等到天荒地老。
     * 与 [killTeammate] 同样套一层 5 秒超时，超时后直接进入资源清理路径，
     * 避免整个 workspace 卸载流程被某个卡住的 Agent 挡住。
     */
    internal suspend fun cleanupAll() {
        // WHY: 先通过 TaskRegistry 终止所有注册的 AgentTask，确保任务级资源被释放
        taskRegistry.killAll()

        // WHY: 先复制 keys 到独立列表，再遍历取消。
        // ConcurrentHashMap.forEach 是弱一致性的，遍历期间其他线程的修改可能不会被看到。
        val scopeKeys = teammateScopes.keys.toList()
        for (key in scopeKeys) {
            teammateScopes[key]?.coroutineContext?.get(TeammateContext)?.abort()
            teammateScopes[key]?.cancel()
        }

        // 复制 jobs 到独立列表再 join，套 5 秒整体超时防卡死
        val jobEntries = teammateJobs.entries.toList()
        kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            for ((_, job) in jobEntries) {
                try { job.join() } catch (_: Exception) {}
            }
        } ?: run {
            Log.w(TAG, "cleanupAll: timeout waiting for ${jobEntries.size} teammate job(s), forcing cleanup")
        }

        teammateScopes.clear()
        teammateJobs.clear()

        // 复制 runners 到独立列表再 dispose
        val runnerEntries = runners.entries.toList()
        for ((_, runner) in runnerEntries) {
            try { runner.dispose() } catch (e: Exception) {
                Log.w(TAG, "Runner dispose failed (non-fatal)", e)
            }
        }
        runners.clear()

        messageBus.clear()

        // 复制 deferreds 到独立列表再 complete
        val deferredEntries = agentCompletionDeferreds.entries.toList()
        for ((_, deferred) in deferredEntries) {
            deferred.complete(Unit)
        }
        agentCompletionDeferreds.clear()

        // WHY: 清理 TaskRegistry 注册信息，在所有协程取消和 runner dispose 之后
        taskRegistry.clear()
        colorIndex.set(0)
        crossSessionMemoryText = ""
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 分配 Agent UI 标识色。
     */
    fun assignColor(): String {
        val idx = colorIndex.getAndIncrement()
        return AGENT_COLORS[idx % AGENT_COLORS.size]
    }

    /**
     * 生成唯一名称。
     */
    fun generateUniqueName(desiredName: String, existingNames: Set<String>): String {
        if (desiredName !in existingNames) return desiredName
        var counter = 2
        while ("${desiredName}${counter}" in existingNames) {
            counter++
        }
        return "${desiredName}${counter}"
    }

    /**
     * 等待指定 Agent 完成。
     */
    suspend fun waitForAgentCompletion(agentName: String, timeoutMs: Long = 300_000L): Boolean {
        val deferred = agentCompletionDeferreds.getOrPut(agentName) { CompletableDeferred() }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 加载跨会话记忆。
     */
    suspend fun loadCrossSessionMemory(): String {
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

    // ═══════════════════════════════════════════════════════════════════════════════
    // 默认 Teammate 系统提示已迁移到顶层 const DEFAULT_TEAMMATE_PROMPT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 从 Room DB 恢复 Agent 上下文。
     *
     * 用于工作区恢复场景：从持久化的 WorkspaceMessage 中重建 Agent 的对话历史。
     *
     * @param agentName Agent 名称
     * @param sessionId 工作区会话 ID
     * @param systemPrompt 系统提示（可选，为空时使用默认提示）
     * @return 恢复的 AgentContext，若无消息则返回 null
     */
    suspend fun restoreAgentContext(
        agentName: String,
        sessionId: Long,
        systemPrompt: String = "",
    ): AgentContext? {
        // WHY: 通过 DAO 查询该 Agent 在指定会话中的所有消息，按时间戳升序排列
        val messages = repository.getWorkspaceMessagesForAgent(sessionId, agentName)
        if (messages.isEmpty()) return null

        val orchestratorRunner = runners[ORCHESTRATOR_NAME]
        val teamName = orchestratorRunner?.getTeamName() ?: "default"

        return AgentContext(
            agentName = agentName,
            isOrchestrator = false,
            systemPrompt = systemPrompt.ifEmpty { DEFAULT_TEAMMATE_PROMPT },
            modelConfig = orchestratorRunner?.getModelConfig()
                ?: error("No orchestrator config available"),
            teamName = teamName,
            // WHY: 将持久化的 WorkspaceMessage 转换为内存中的 AgentMessage 列表
            // WorkspaceMessage 无 source 字段，默认为空字符串
            messages = ArrayList(messages.map { msg ->
                AgentMessage(
                    role = msg.role,
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    toolCallsJson = msg.toolCallsJson,
                    isIntervention = msg.isIntervention,
                    imagePath = msg.imagePath,
                    timestamp = msg.timestamp,
                )
            }),
        )
    }
}
