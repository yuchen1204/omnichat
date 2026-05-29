package com.example.workspace

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
 * @property orchestratorName Orchestrator 名称
 * @property activeSubAgents 当前活跃的 Sub-Agent 列表
 * @property isRunning 团队是否正在运行
 * @property isCompleted 工作区是否已完成
 * @property sandboxPath 工作区文件操作沙盒路径。非空时，所有 Agent 的文件工具调用仅允许在此目录内操作。
 */
data class TeamState(
    val teamName: String,
    val orchestratorConfig: ModelConfig,
    val orchestratorName: String = ORCHESTRATOR_NAME,
    val activeSubAgents: List<SubAgentInfo> = emptyList(),
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val sandboxPath: String? = null,
)

/**
 * Sub-Agent 简要信息，用于 UI 展示。
 *
 * @property name Agent 名称
 * @property description Agent 角色描述
 * @property status 当前 Agent 状态
 */
data class SubAgentInfo(
    val name: String,
    val description: String,
    val status: AgentStatus,
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
// 常量
// ═══════════════════════════════════════════════════════════════════════════════

/** Orchestrator 固定名称 */
const val ORCHESTRATOR_NAME = "主控 Agent"

/** 任务完成标记（装饰性，由 LLM 输出，无程序化检测） */
const val COMPLETION_MARKER = "【任务完成】"

/** Agent UI 标识色列表 */
val AGENT_COLORS = listOf(
    "#4285F4", "#EA4335", "#34A853", "#FBBC05",
    "#FF6D00", "#AA00FF", "#00BFA5", "#D50000",
    "#6200EA", "#0091EA"
)

// ═══════════════════════════════════════════════════════════════════════════════
// 后台任务通知
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Task notification — delivered to parent when an async agent completes.
 * Mirrors Claude Code's <task-notification> XML.
 */
data class TaskNotification(
    val taskId: String,
    val agentName: String,
    val status: TaskNotificationStatus,
    val result: String? = null,
    val error: String? = null,
    val totalTokens: Int = 0,
    val toolUseCount: Int = 0,
    val durationMs: Long = 0,
)

enum class TaskNotificationStatus {
    COMPLETED, FAILED, KILLED
}

// ═══════════════════════════════════════════════════════════════════════════════
// Teammate 身份与权限
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Teammate 身份信息。
 *
 * 标识一个 Agent 实例的完整身份，用于消息路由、日志和 UI 展示。
 */
data class TeammateIdentity(
    val agentId: String,
    val agentName: String,
    val teamName: String,
    val color: String = "",
    val agentType: String = "",
    val parentSessionId: String = "",
)

/**
 * 权限模式枚举。
 */
enum class PermissionMode {
    DEFAULT,
    AUTO,
    PLAN
}
