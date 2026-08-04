package com.omnichat.tool

/**
 * 项目会话工具作用域。
 *
 * 当 Agent 在项目会话中执行时，其工具调用受此作用域约束：
 * - 只允许使用白名单中的项目工具
 * - 所有项目工具的 [projectId] 由此作用域推导，不接受 caller 提供的参数
 * - MCP 远程工具受 [allowedMcpServerIds] 限制
 */
data class ProjectToolScope(
    val sessionId: Long,
    val projectId: Long,
    val allowedMcpServerIds: Set<Long> = emptySet()
) {
    companion object {
        /**
         * 项目会话中暴露的内置工具白名单。
         * 不在列表中的内置工具在项目会话中被拒绝。
         */
        val ALLOWED_PROJECT_TOOLS: Set<String> = setOf(
            "project_list_knowledge",
            "project_read_knowledge",
            "project_create_knowledge",
            "project_read_memory",
            "project_update_memory"
        )
    }
}