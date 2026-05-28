package com.example.workspace

import com.example.data.AgentPreset
import com.example.data.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// ═══════════════════════════════════════════════════════════════════════════════
// 工作区共享协程作用域
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 工作区辅助任务共享作用域。
 *
 * WHY: 原代码大量使用 [kotlinx.coroutines.GlobalScope] 触发 Hook、记录日志等
 * 副作用任务，存在以下问题：
 * 1. 无法集中取消（应用退出时孤儿协程仍在运行）
 * 2. 无法挂载结构化的异常处理器
 * 3. Lint 警告且不被 Kotlin 团队推荐
 *
 * 该作用域使用 [SupervisorJob] 隔离子协程异常，统一在 [Dispatchers.Default] 上运行，
 * 仅用于 fire-and-forget 的辅助任务（如 Hook 分发），主业务循环仍由 TeamManager 自带的
 * `parentScope` 驱动。
 */
internal object WorkspaceScopes {
    /** 用于 Hook 分发等 fire-and-forget 任务，生命周期与进程一致 */
    val auxiliary: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 取消所有辅助协程。
     *
     * WHY: 原 auxiliary scope 永远不取消，进程退出时孤儿协程（Hook 分发等）仍在运行，
     * 可能导致延迟崩溃或资源泄漏。提供显式 cancel 供 Application.onTerminate 或
     * 进程级清理调用。日常使用中不调用（生命周期与进程一致），仅作为安全出口。
     */
    fun cancelAll() {
        auxiliary.cancel()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工作区配置
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

// ═══════════════════════════════════════════════════════════════════════════════
// 团队状态
// ═══════════════════════════════════════════════════════════════════════════════

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
 * 通过 StateFlow 驱动 UI 更新。
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
    /** 工作区文件操作沙盒路径。非空时，所有 Agent 的文件工具调用仅允许在此目录内操作。 */
    val sandboxPath: String? = null,
)

/**
 * Teammate 状态。
 *
 * 表示单个 Agent 的运行时状态。
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

// ═══════════════════════════════════════════════════════════════════════════════
// Sub-Agent 等待结果
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Sub-Agent 等待结果。
 *
 * Teammate 执行循环中等待下一条消息时的返回值。
 */
sealed class WaitResult {
    /** 收到关闭请求 */
    data class ShutdownRequest(val request: TeamMessage.ShutdownRequest) : WaitResult()
    /**
     * 收到新消息（来自 Orchestrator 的任务分配 / 用户干预 / continue_conversation）。
     *
     * @param isFreshTask 是否为全新任务。true 表示需要注入"新任务开始"标记
     *                    （执行 assign_task 或自动认领时为 true）；false 表示
     *                    在已有上下文中继续（continue_conversation / 用户干预）。
     */
    data class NewMessage(val message: String, val from: String, val isFreshTask: Boolean = true) : WaitResult()
    /** 收到其他 Sub-Agent 的协作消息 */
    data class PeerMessage(val message: String, val from: String) : WaitResult()
    /** 认领了新任务 */
    data class TaskClaimed(val taskDescription: String) : WaitResult()
    /** 已中止 */
    data object Aborted : WaitResult()
}

// ═══════════════════════════════════════════════════════════════════════════════
// Orchestrator 指令与 Agent 规格
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 任务分配模式枚举。
 */
enum class TaskMode {
    DIRECT,
    CLAIM
}

/**
 * Agent 生成模式。
 * SPAWN: 全新上下文（默认）
 * FORK: 继承父级的对话历史和系统提示
 */
enum class SpawnMode {
    SPAWN,
    FORK
}

/**
 * 模型能力提示，用于自动模型选择。
 *
 * 在 create_agents 中通过 modelHint 字段指定，系统根据提示
 * 自动选择最匹配的模型（基于 FetchedModel 的能力标记）。
 * 优先级低于显式指定的 modelConfigId/modelId。
 */
enum class ModelHint {
    /** 推理/编程任务，优先选择支持 thinking 的模型 */
    REASONING,
    /** 图像理解任务，需要支持视觉输入 */
    VISION,
    /** 快速响应，选择轻量模型 */
    FAST,
    /** 大上下文窗口需求 */
    LARGE_CONTEXT,
    /** 需要工具调用能力 */
    TOOLS
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
    val modelId: String? = null,
    val modelHint: ModelHint? = null,
    val dependsOn: List<String> = emptyList(),
    // WHY: 支持 FORK 模式，允许新 Agent 继承父级上下文，减少重复提示词传递
    val spawnMode: SpawnMode = SpawnMode.SPAWN,
)

// ═══════════════════════════════════════════════════════════════════════════════
// 常量
// ═══════════════════════════════════════════════════════════════════════════════

/** Orchestrator 固定名称 */
const val ORCHESTRATOR_NAME = "主控 Agent"

/** 任务完成标记 */
const val COMPLETION_MARKER = "【任务完成】"

/** 消息轮询间隔（毫秒） */
const val POLL_INTERVAL_MS = 500L

/** Agent UI 标识色列表 */
val AGENT_COLORS = listOf(
    "#4285F4", "#EA4335", "#34A853", "#FBBC05",
    "#FF6D00", "#AA00FF", "#00BFA5", "#D50000",
    "#6200EA", "#0091EA"
)

/** Orchestrator 专属工具名称集合，子 Agent 不可调用 */
val ORCHESTRATOR_ONLY_TOOLS = setOf("create_agents", "assign_task", "continue_conversation")

/** 所有 Agent 共享的协作工具 */
val COLLABORATION_TOOLS = setOf("peer_message")

// ═══════════════════════════════════════════════════════════════════════════════
// Sub-Agent TaskList 进度跟踪
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Sub-Agent 任务计划中的单个步骤。
 *
 * 由 Sub-Agent 在规划阶段自动生成，格式为 `- [ ] 描述` 或 `- [x] 描述`。
 *
 * @property description 步骤描述（如 "创建 index.html"）
 * @property isCompleted 是否已完成
 */
data class AgentTaskStep(
    val description: String,
    val isCompleted: Boolean,
)

/**
 * Sub-Agent 的任务进度。
 *
 * 从 Sub-Agent 的流式输出中解析 TaskList 格式，实时更新进度。
 *
 * @property agentName Agent 名称
 * @property steps 任务步骤列表
 */
data class AgentTaskProgress(
    val agentName: String,
    val steps: List<AgentTaskStep>,
) {
    val completedCount: Int get() = steps.count { it.isCompleted }
    val totalCount: Int get() = steps.size
    val isAllCompleted: Boolean get() = steps.isNotEmpty() && steps.all { it.isCompleted }
    val progressText: String get() = "$completedCount/$totalCount"
}
