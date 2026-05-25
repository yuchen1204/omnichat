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
    private val onError: (message: String) -> Unit,
) {
    companion object {
        private const val TAG = "AgentLifecycle"
    }

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

    /** 跨会话记忆文本 */
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
        existingNames: Set<String>,
        parentScope: CoroutineScope,
        createRunner: (AgentContext, Boolean) -> AgentRunner,
        executeLoop: suspend (AgentRunner, TeammateIdentity) -> Unit,
    ): TeammateIdentity {
        // 检查 Sub-Agent 数量上限
        val subAgentCount = runners.count { it.key != ORCHESTRATOR_NAME }
        if (subAgentCount >= config.maxSubAgents) {
            val errorMessage = "已达到子 Agent 上限（${config.maxSubAgents}个），无法继续创建"
            Log.w(TAG, errorMessage)
            onError(errorMessage)
            error(errorMessage)
        }

        // 生成唯一名称
        val uniqueName = generateUniqueName(name, existingNames)
            .take(config.maxAgentNameLength)

        // 优先匹配 AgentPreset
        val presetMatch = repository.getAllAgentPresets().find { it.name == uniqueName }
        val finalSystemPrompt = (
            presetMatch?.systemPrompt?.takeIf { it.isNotEmpty() } ?: systemPrompt
        ).take(config.maxSystemPromptLength)

        // 确定模型配置
        val finalModelConfigId = presetMatch?.modelConfigId ?: modelConfigId
        val modelConfig = if (finalModelConfigId != null) {
            repository.getConfigById(finalModelConfigId)
        } else null
        val actualModelConfig = modelConfig ?: runners[ORCHESTRATOR_NAME]?.getModelConfig()
            ?: error("无法确定模型配置")

        // 创建身份（对标 Claude Code 的 formatAgentId: "name@team"）
        val teamName = runners[ORCHESTRATOR_NAME]?.getTeamName()?.takeIf { it.isNotEmpty() } ?: "default"
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
            teamName = teamName,
        )

        // 创建 AgentRunner（子 Agent 禁用编排工具）
        val runner = createRunner(context, true)
        runners[uniqueName] = runner

        // 创建独立 CoroutineScope（对标 Claude Code 的 SupervisorJob + TeammateContext）
        val teammateScope = CoroutineScope(
            parentScope.coroutineContext + SupervisorJob() + TeammateContext(identity)
        )
        teammateScopes[uniqueName] = teammateScope

        // 注册完成 Deferred
        agentCompletionDeferreds[uniqueName] = CompletableDeferred()

        Log.d(TAG, "Spawned teammate '$uniqueName' with model ${actualModelConfig.name}")

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
        teammateScopes[agentName]?.cancel()

        // 等待 Job 完成，确保 finally 块执行完毕
        try {
            teammateJobs[agentName]?.join()
        } catch (_: Exception) { }

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
     */
    internal suspend fun cleanupAll() {
        // WHY: 先取消所有 scope，再 join 所有 job，确保协程正常退出（Fix #4）
        teammateScopes.values.forEach { it.cancel() }
        teammateJobs.forEach { (_, job) ->
            try { job.join() } catch (_: Exception) {}
        }
        teammateScopes.clear()
        teammateJobs.clear()
        runners.values.forEach { it.dispose() }
        runners.clear()
        messageBus.clear()
        agentCompletionDeferreds.values.forEach { it.complete(Unit) }
        agentCompletionDeferreds.clear()
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
    // 默认 Teammate 系统提示
    // ═══════════════════════════════════════════════════════════════════════════════

    internal val DEFAULT_TEAMMATE_PROMPT = """
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

**⚠️ 完成前必须自查**：在报告"任务完成"之前，你必须逐项检查任务描述中的每一个要求是否都已满足。
- 如果任务要求创建 3 个文件，你必须确认 3 个文件都已创建
- 如果任务要求修改多个位置，你必须确认所有位置都已修改
- 不要只做了任务的一部分就报告完成

**🚫 只做自己的任务**：
- 你只负责执行分配给你的任务，不要执行其他 Agent 的任务
- 如果任务描述中提到了其他 Agent 的名字或职责（如 "Agent B 负责..."），那是其他 Agent 的工作，不是你的
- 不要因为看到任务背景中提到了多个子任务就试图完成所有子任务
- 只做「你的任务」部分明确要求的内容

**不要越权**：严格按照任务描述执行，不要做任务之外的事。

**结果要自包含**：汇报结果时，把关键数据直接写在回复里，不要只说"已生成报告文件，请查看"。

## Sub-Agent 间协作（peer_message 工具）

你可以使用 peer_message 工具与其他 Sub-Agent 直接通信：
- 点对点：peer_message(to: "researcher", message: "请把分析结果发给我")
- 广播：peer_message(to: "*", message: "我已完成数据库迁移，请更新相关代码")

## 文件系统环境
本设备为 Android 系统。文件系统工具的根目录为 "/"，对应设备存储根路径。
- 下载目录: /Download
- 文档目录: /Documents
- 图片目录: /Pictures

可用工具：
[MCP_TOOLS]
"""
}
