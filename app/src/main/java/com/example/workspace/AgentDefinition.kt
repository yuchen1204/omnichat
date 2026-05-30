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

    // === New fields for Claude Code alignment ===

    /** Memory configuration for this agent (e.g., enabled/disabled, custom instructions) */
    val memory: AgentMemoryConfig? = null,
    /** MCP servers to attach to this agent */
    val mcpServers: List<AgentMcpServerSpec>? = null,
    /** Lifecycle hooks for this agent */
    val hooks: AgentHooks? = null,
    /** Permission mode: "auto", "plan", "ask", or "review" */
    val permissionMode: String? = null,
    /** Initial prompt to inject when agent starts */
    val initialPrompt: String? = null,
    /** Reasoning effort level: "low", "medium", "high", "xhigh" */
    val effort: String? = null,
    /** Whether to omit CLAUDE.md from agent context */
    val omitClaudeMd: Boolean = false,
    /** MCP servers that must be available for this agent to function */
    val requiredMcpServers: List<String>? = null,
    /** Source filename if loaded from a .claude/agents/ file */
    val filename: String? = null,
    /** Base directory for resolving relative paths */
    val baseDir: String? = null,
    /** Critical system reminder to inject after system prompt */
    val criticalSystemReminder: String? = null,
    /** Pending snapshot update to apply */
    val pendingSnapshotUpdate: PendingSnapshotUpdate? = null,
)

/**
 * Memory configuration for an agent.
 */
data class AgentMemoryConfig(
    /** Whether memory is enabled for this agent */
    val enabled: Boolean = true,
    /** Custom memory instructions */
    val instructions: String? = null,
)

/**
 * MCP server specification for an agent.
 * Can be either a reference to an existing server or an inline configuration.
 */
sealed class AgentMcpServerSpec {
    /** Reference to an MCP server by name */
    data class Reference(val name: String) : AgentMcpServerSpec()

    /** Inline MCP server configuration */
    data class Inline(val config: McpServerConfig) : AgentMcpServerSpec()
}

/**
 * MCP server configuration for inline server definitions.
 */
data class McpServerConfig(
    /** Server name/identifier */
    val name: String,
    /** Transport type: "stdio", "sse", "http" */
    val transport: String,
    /** Command to execute (for stdio transport) */
    val command: String? = null,
    /** Arguments for the command */
    val args: List<String>? = null,
    /** Environment variables */
    val env: Map<String, String>? = null,
    /** URL for SSE/HTTP transport */
    val url: String? = null,
    /** Request timeout in milliseconds */
    val timeout: Long? = null,
    /** Whether to trust all certificates (for development) */
    val trustAllCertificates: Boolean = false,
)

/**
 * Lifecycle hooks for an agent.
 */
data class AgentHooks(
    /** Hook to run before agent starts */
    val preStart: List<AgentHook>? = null,
    /** Hook to run after agent completes */
    val postEnd: List<AgentHook>? = null,
    /** Hook to run before each tool call */
    val preToolCall: List<AgentHook>? = null,
    /** Hook to run after each tool call */
    val postToolCall: List<AgentHook>? = null,
    /** Hook to run before each message */
    val preMessage: List<AgentHook>? = null,
    /** Hook to run after each message */
    val postMessage: List<AgentHook>? = null,
)

/**
 * A single hook definition.
 */
data class AgentHook(
    /** Hook type/identifier */
    val type: String,
    /** Shell command to execute (if applicable) */
    val command: String? = null,
    /** Script to execute (if applicable) */
    val script: String? = null,
    /** Whether to stop agent on hook failure */
    val stopOnFailure: Boolean = false,
    /** Timeout for hook execution in milliseconds */
    val timeout: Long? = null,
)

/**
 * Pending snapshot update to apply to the agent.
 */
data class PendingSnapshotUpdate(
    /** Operation type: "add", "update", "delete" */
    val operation: String,
    /** Target path for the operation */
    val path: String,
    /** Content for add/update operations */
    val content: String? = null,
    /** Whether this is a critical update */
    val critical: Boolean = false,
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

suspend fun loadAgentDefinitions(
    repository: com.example.data.AppRepository,
): List<AgentDefinition> {
    val presets = repository.getAllAgentPresets()
    val customAgents = presets.map { preset ->
        AgentDefinition(
            agentType = "custom:${preset.name}",
            displayName = preset.name,
            systemPrompt = preset.systemPrompt,
            modelConfigId = preset.modelConfigId,
            isBuiltIn = false,
            source = "preset",
        )
    }
    return BuiltInAgents.ALL + customAgents
}
