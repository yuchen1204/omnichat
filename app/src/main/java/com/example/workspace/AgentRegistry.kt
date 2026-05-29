package com.example.workspace

import com.example.workspace.lifecycle.AgentLifecycleManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 注册表 — 运行时 Agent 实例索引。
 *
 * 所有活跃 Agent 在此注册，支持按名称查找、状态查询和遍历。
 * 用于 Agent 间通信和 TeamManager 的生命周期管理。
 *
 * 线程安全：使用 ConcurrentHashMap。
 */
class AgentRegistry {
    private val agents = ConcurrentHashMap<String, AgentEntry>()

    /**
     * 注册表条目。
     *
     * @property identity Teammate 身份
     * @property lifecycle 生命周期管理器
     * @property instanceId Room DB 中的 AgentInstance ID
     * @property runner AgentRunner 实例（可为 null，如 Agent 已完成但条目保留）
     */
    data class AgentEntry(
        val identity: TeammateIdentity,
        val lifecycle: AgentLifecycleManager,
        val instanceId: Long,
        val runner: AgentRunner? = null,
    )

    /** 注册一个 Agent。如果同名 Agent 已存在，返回 false。 */
    fun register(entry: AgentEntry): Boolean {
        return agents.putIfAbsent(entry.identity.agentId, entry) == null
    }

    /** 注销一个 Agent。 */
    fun unregister(agentId: String) {
        agents.remove(agentId)
    }

    /** 按 agentId 查找。 */
    fun get(agentId: String): AgentEntry? = agents[agentId]

    /** 获取所有活跃 Agent。 */
    fun getActiveAgents(): List<AgentEntry> = agents.values.toList()

    /** 按 Agent 类型查找。 */
    fun getByType(type: String): List<AgentEntry> =
        agents.values.filter { it.identity.agentType == type }

    /** 检查 Agent 是否已注册。 */
    fun contains(agentId: String): Boolean = agents.containsKey(agentId)

    /** 注册表大小。 */
    fun size(): Int = agents.size

    /** 清空注册表。 */
    fun clear() = agents.clear()
}
