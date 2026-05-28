package com.example.workspace

/**
 * Agent definition — describes a type of agent that can be spawned.
 *
 * Mirrors Claude Code's AgentDefinition (loaded from built-in registry,
 * plugin frontmatter, or custom .claude/agents/ markdown files).
 *
 * Built-in agents: generalPurpose, explore, plan, verification
 * Custom agents: loaded from agent_presets DB table
 */
data class AgentDefinition(
    /** Unique agent type identifier (e.g., "general-purpose", "explore", "custom:my-agent") */
    val agentType: String,
    /** Human-readable display name */
    val displayName: String,
    /** System prompt template (may contain [CROSS_SESSION_MEMORY], [MCP_TOOLS], etc.) */
    val systemPrompt: String,
    /** Model alias hint: "default", "fast", "reasoning", "vision" — resolved at spawn time */
    val modelHint: String? = null,
    /** Specific model config ID override — takes precedence over modelHint */
    val modelConfigId: Long? = null,
    /** Specific model ID override within the config */
    val overrideModelId: String? = null,
    /** Tool names this agent is allowed to use. null or ["*"] = all tools */
    val tools: List<String>? = null,
    /** Tool names this agent is NOT allowed to use */
    val disallowedTools: List<String>? = null,
    /** Whether this agent should run in the background (async) */
    val background: Boolean = false,
    /** Maximum tool call iterations for this agent */
    val maxTurns: Int = AgentRunner.MAX_TOOL_CALL_ITERATIONS,
    /** UI color for this agent type */
    val color: String? = null,
    /** Whether this is a built-in agent (vs user-defined) */
    val isBuiltIn: Boolean = true,
    /** Source: "built-in", "preset", "custom" */
    val source: String = "built-in",
)

/**
 * Built-in agent definitions registry.
 *
 * Mirrors Claude Code's builtInAgents.ts — provides the default agent types
 * available in every workspace session.
 */
object BuiltInAgents {
    val GENERAL_PURPOSE = AgentDefinition(
        agentType = "general-purpose",
        displayName = "通用 Agent",
        systemPrompt = "", // Uses orchestrator's system prompt or default
        tools = listOf("*"), // All tools
        isBuiltIn = true,
    )

    val EXPLORE = AgentDefinition(
        agentType = "explore",
        displayName = "探索 Agent",
        systemPrompt = """You are a read-only exploration agent. Your job is to search, read, and analyze files to answer questions about the codebase.

Rules:
- You may ONLY use read-only tools: read_file, list_directory, search_files, get_file_info, search_memory, get_current_time
- Do NOT modify any files
- Report your findings clearly and concisely""",
        tools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
        background = true,
        color = "#4285F4",
        isBuiltIn = true,
    )

    val PLAN = AgentDefinition(
        agentType = "plan",
        displayName = "规划 Agent",
        systemPrompt = """You are a planning agent. Your job is to analyze tasks and create detailed implementation plans.

Rules:
- You may ONLY use read-only tools: read_file, list_directory, search_files, get_file_info, search_memory, get_current_time
- Do NOT modify any files
- Output a structured plan with clear steps""",
        tools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
        background = true,
        color = "#34A853",
        isBuiltIn = true,
    )

    val VERIFICATION = AgentDefinition(
        agentType = "verification",
        displayName = "验证 Agent",
        systemPrompt = """You are a verification agent. Your job is to review and verify work done by other agents.

Rules:
- Check for correctness, completeness, and potential issues
- Report any problems found with specific details
- Be adversarial — try to find flaws""",
        background = true,
        color = "#EA4335",
        isBuiltIn = true,
    )

    /** All built-in agents */
    val ALL = listOf(GENERAL_PURPOSE, EXPLORE, PLAN, VERIFICATION)

    /** Lookup by agentType */
    fun findByType(agentType: String): AgentDefinition? =
        ALL.find { it.agentType == agentType }
}
