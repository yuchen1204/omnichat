package com.example.workspace.executor

import com.example.workspace.AgentDefinition
import com.example.workspace.AgentStatus
import com.example.workspace.TeammateIdentity

/**
 * 执行器类型 — 决定 Agent 间的协作模式。
 */
enum class ExecutorType {
    /** 编排器模式：Leader 派发任务给 Worker，Worker 不能互相通信 */
    ORCHESTRATOR,
    /** 对等模式：任何 Agent 可以创建和通信任何其他 Agent */
    PEER,
}

/**
 * Agent 启动配置。
 */
data class SpawnConfig(
    val name: String,
    val teamName: String,
    val prompt: String,
    val agentDefinition: AgentDefinition,
    val modelOverride: String? = null,
    val isBackground: Boolean = false,
)

/**
 * Agent 启动结果。
 */
data class SpawnResult(
    val success: Boolean,
    val agentId: String,
    val error: String? = null,
)

/**
 * Teammate 执行器接口 — 抽象 Agent 的完整生命周期管理。
 *
 * 两种实现：
 * - OrchestratorExecutor：编排器模式，仅 Leader 可以 spawn
 * - PeerExecutor：对等模式，任何 Agent 可以 spawn/message 任何其他 Agent
 */
interface TeammateExecutor {
    val type: ExecutorType

    /** 创建并启动一个新 Agent。 */
    suspend fun spawn(config: SpawnConfig): SpawnResult

    /** 向指定 Agent 发送消息（通过 MailboxService）。 */
    suspend fun sendMessage(agentId: String, message: com.example.data.MailboxMessage)

    /** 优雅终止 — Agent 完成当前工具调用后退出。 */
    suspend fun terminate(agentId: String, reason: String? = null): Boolean

    /** 强制终止 — 立即停止。 */
    suspend fun kill(agentId: String): Boolean

    /** 检查 Agent 是否仍在运行。 */
    suspend fun isActive(agentId: String): Boolean
}
