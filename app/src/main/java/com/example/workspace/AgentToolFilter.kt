package com.example.workspace

/**
 * Centralized tool filtering for agents.
 *
 * Mirrors Claude Code's src/constants/tools.ts:
 * - ALL_AGENT_DISALLOWED_TOOLS: tools blocked for ALL agents
 * - ASYNC_AGENT_ALLOWED_TOOLS: whitelist for async/background agents
 * - ORCHESTRATOR_BLOCKED_TOOLS: tools orchestrator should delegate
 */
object AgentToolFilter {

    /**
     * Tools blocked for ALL agents (sub-agents and orchestrator).
     * These are meta-tools that only the main thread should use.
     */
    val ALL_AGENT_DISALLOWED_TOOLS = setOf(
        "agent", // Prevents recursion — AgentTool handles this separately
    )

    /**
     * Tools allowed for async/background agents.
     * Background agents get a restricted tool set since they can't show UI.
     */
    val ASYNC_AGENT_ALLOWED_TOOLS = setOf(
        "read_file", "list_directory", "search_files", "get_file_info",
        "write_file", "create_directory", "move_file", "delete_file",
        "search_memory", "get_current_time",
        "create_pdf_document", "create_excel_document",
        "create_word_document", "create_powerpoint_document",
    )

    /**
     * Tools blocked for orchestrator (should delegate to sub-agents).
     */
    val ORCHESTRATOR_BLOCKED_TOOLS = setOf(
        // File write operations
        "write_file", "create_directory", "move_file", "delete_file",
        // Document generation
        "create_pdf_document", "create_excel_document",
        "create_word_document", "create_powerpoint_document",
        // UI modifications
        "set_primary_color", "set_secondary_color", "set_background_color",
        "set_surface_color", "set_corner_radius", "set_spacing_multiplier",
        "set_font_family", "set_font_size_scale", "set_font_weight",
        "set_ui_texts", "configure_mcp_tool_groups",
    )

    /**
     * Read-only tools that are safe for parallel execution.
     */
    val READ_ONLY_TOOLS = setOf(
        "read_file", "list_directory", "search_files", "get_file_info",
        "search_memory", "get_current_time",
    )

    /**
     * Filter tools for a specific agent based on its definition and context.
     */
    fun filterTools(
        allToolNames: Set<String>,
        agentDef: AgentDefinition?,
        isOrchestrator: Boolean,
        isAsync: Boolean,
    ): Set<String> {
        var filtered = allToolNames - ALL_AGENT_DISALLOWED_TOOLS

        if (isOrchestrator) {
            filtered = filtered - ORCHESTRATOR_BLOCKED_TOOLS
        }

        if (isAsync) {
            filtered = filtered.intersect(ASYNC_AGENT_ALLOWED_TOOLS)
        }

        // Agent definition's explicit tool list
        agentDef?.tools?.let { allowedTools ->
            if (allowedTools != listOf("*")) {
                filtered = filtered.intersect(allowedTools.toSet())
            }
        }

        // Agent definition's disallowed tools
        agentDef?.disallowedTools?.let { disallowed ->
            filtered = filtered - disallowed.toSet()
        }

        return filtered
    }
}
