package com.example.workspace

import com.example.data.AgentPreset
import com.example.data.ModelConfig

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
    /** 收到新消息 */
    data class NewMessage(val message: String, val from: String) : WaitResult()
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
