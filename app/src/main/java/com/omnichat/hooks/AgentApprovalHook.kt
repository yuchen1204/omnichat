package com.omnichat.hooks

/**
 * Utility for agent approval logic. Called from McpRuntimeManager.callTool().
 */
object AgentApprovalHook {
    private val FILE_TOOLS = setOf(
        "file_write", "file_delete", "file_move",
        "file_copy", "file_append", "file_mkdir"
    )

    /**
     * Check if toolName is a file tool that may need approval.
     */
    fun isAutoModeFileTool(toolName: String): Boolean = toolName in FILE_TOOLS
}
