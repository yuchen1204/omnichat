package com.example.hooks

import org.json.JSONObject

/**
 * 消息/会话处理 Hook 接口
 */
interface MessageHook {
    /**
     * 发送用户消息前的 Hook。
     * 可以对文本进行修改（如敏感词过滤、格式化等），如果返回 null，则表示拦截并取消发送。
     */
    suspend fun onBeforeSendMessage(message: String): String?

    /**
     * 接收到大模型响应后的 Hook。
     * 可以对大模型的响应文本进行后处理（如追加免责声明、内容清洗等），如果返回 null，则可丢弃响应或做其他处理（此处返回 String 代表处理后的文本）。
     */
    suspend fun onAfterReceiveResponse(response: String): String
}

/**
 * MCP 工具执行 Hook 接口
 */
interface McpHook {
    /**
     * MCP 工具执行前的 Hook。
     * 可以修改传入的参数 [args]。如果返回 null，则表示拦截并取消该工具的执行。
     */
    suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject?

    /**
     * MCP 工具执行后的 Hook。
     * 可以修改或封装执行结果。
     */
    suspend fun onAfterToolExecute(toolName: String, result: String): String
}

/**
 * Agent 工作流 Hook 接口
 *
 * 覆盖 Agent Team 的完整生命周期：
 * - 单轮对话的开始/结束（[onAgentTurnStart] / [onAgentTurnEnd]）
 * - Sub-Agent 的创建/销毁（[onTeammateSpawned] / [onTeammateKilled]）
 * - 整个工作区的完成（[onWorkspaceComplete]）
 *
 * 所有方法均提供默认空实现，实现类只需覆盖感兴趣的事件。
 */
interface AgentHook {
    /**
     * Agent 开始执行一轮对话时的 Hook。
     *
     * @param agentId   Agent 名称（如 "主控 Agent"、"researcher"）
     * @param agentType "orchestrator" 或 "teammate"
     * @param teamName  所属团队名称（工作区 ID 前缀，如 "workspace_3"）
     * @param task      本轮输入的用户/系统消息（可能为空，表示恢复等待）
     */
    suspend fun onAgentTurnStart(agentId: String, agentType: String, teamName: String, task: String) {}

    /**
     * Agent 完成一轮对话时的 Hook。
     *
     * @param agentId   Agent 名称
     * @param agentType "orchestrator" 或 "teammate"
     * @param teamName  所属团队名称
     * @param result    本轮最后一条 assistant 消息内容（可能为空）
     * @param toolUseCount 本轮工具调用次数
     * @param durationMs   本轮耗时（毫秒）
     */
    suspend fun onAgentTurnEnd(
        agentId: String,
        agentType: String,
        teamName: String,
        result: String,
        toolUseCount: Int,
        durationMs: Long,
    ) {}

    /**
     * Sub-Agent（Teammate）被创建时的 Hook。
     *
     * @param agentName  新创建的 Agent 名称
     * @param role       角色描述（来自 create_agents 的 role 字段）
     * @param teamName   所属团队名称
     * @param color      UI 标识色（十六进制，如 "#4285F4"）
     */
    suspend fun onTeammateSpawned(agentName: String, role: String, teamName: String, color: String) {}

    /**
     * Sub-Agent（Teammate）被销毁时的 Hook。
     *
     * @param agentName Agent 名称
     * @param teamName  所属团队名称
     * @param reason    销毁原因："completed"、"killed"、"error"
     */
    suspend fun onTeammateKilled(agentName: String, teamName: String, reason: String) {}

    /**
     * 整个工作区完成时的 Hook。
     *
     * @param teamName           团队名称
     * @param agentCount         参与的 Agent 总数（含 Orchestrator）
     * @param totalDurationMs    从提交任务到完成的总耗时（毫秒）
     * @param orchestratorSummary Orchestrator 最后一条 assistant 消息（任务完成摘要）
     */
    suspend fun onWorkspaceComplete(
        teamName: String,
        agentCount: Int,
        totalDurationMs: Long,
        orchestratorSummary: String,
    ) {}

    // ── 向后兼容的旧接口（已废弃，保留以免破坏现有实现）──────────────────

    /**
     * @deprecated 请改用 [onAgentTurnStart]。
     * 注意：向后兼容调用时，[role] 参数传入的是 agentType（"orchestrator"/"teammate"），
     * 而非原来的完整系统提示。
     */
    suspend fun onAgentStart(agentId: String, role: String, task: String) {}

    /**
     * @deprecated 请改用 [onAgentTurnEnd]。
     */
    suspend fun onAgentEnd(agentId: String, result: String) {}
}
