package com.omnichat.workspace

import android.content.Context
import com.omnichat.R

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
    /** Description of when to use this agent (mirrors Claude Code's whenToUse) */
    val whenToUse: String = "",
    /** System prompt template (may contain [CROSS_SESSION_MEMORY], [MCP_TOOLS], etc.) */
    val systemPrompt: String,
    /** Model alias hint: "default", "fast", "reasoning", "vision", "inherit" — resolved at spawn time */
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
    /** Source: "built-in", "preset", "custom", "markdown" */
    val source: String = "built-in",

    // === 新增字段（对齐 Claude Code）===

    /** Memory scope: "user", "project", "local" — enables persistent agent memory */
    val memory: String? = null,

    /** Agent-specific MCP servers (inline definitions or references) */
    val mcpServers: List<AgentMcpServerSpec>? = null,

    /** Agent-level hooks (PreToolUse, PostToolUse, etc.) */
    val hooks: AgentHooks? = null,

    /** Permission mode override: "default", "plan", "acceptEdits", "bypassPermissions" */
    val permissionMode: String? = null,

    /** Initial prompt prepended to first user turn (supports slash commands) */
    val initialPrompt: String? = null,

    /** Reasoning effort level: "low", "medium", "high", "xhigh" */
    val effort: String? = null,

    /** Whether to omit CLAUDE.md hierarchy from agent's context (saves tokens) */
    val omitClaudeMd: Boolean = false,

    /** Required MCP server patterns (agent unavailable if not configured) */
    val requiredMcpServers: List<String>? = null,

    /** Filename for markdown-defined agents (without .md extension) */
    val filename: String? = null,

    /** Base directory for the agent definition source */
    val baseDir: String? = null,

    /** Critical system reminder injected at every turn */
    val criticalSystemReminder: String? = null,

    /** Pending snapshot update info (for memory sync) */
    val pendingSnapshotUpdate: PendingSnapshotUpdate? = null,
)

/**
 * Agent-specific MCP server specification.
 * Can be a reference to an existing server by name, or an inline definition.
 */
sealed class AgentMcpServerSpec {
    /** Reference to existing MCP server by name */
    data class Reference(val name: String) : AgentMcpServerSpec()

    /** Inline MCP server definition with config */
    data class Inline(val name: String, val config: McpServerConfig) : AgentMcpServerSpec()
}

/**
 * MCP server configuration for inline server definitions.
 */
data class McpServerConfig(
    /** Transport type: "stdio", "sse", "http" */
    val transport: String = "stdio",
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
 * Agent-level hooks configuration.
 * Mirrors Claude Code's HooksSettings.
 */
data class AgentHooks(
    val preToolUse: List<AgentHook>? = null,
    val postToolUse: List<AgentHook>? = null,
    val prePrompt: List<AgentHook>? = null,
    val postPrompt: List<AgentHook>? = null,
    val stop: List<AgentHook>? = null,
)

data class AgentHook(
    val matcher: String,  // Tool name pattern or "*"
    val hooks: List<String>,  // Shell commands to run
)

/**
 * Pending memory snapshot update info.
 */
data class PendingSnapshotUpdate(
    val snapshotTimestamp: String,
)

/**
 * Built-in agent definitions registry.
 *
 * Mirrors Claude Code's builtInAgents.ts — provides the default agent types
 * available in every workspace session.
 *
 * Factory functions take Context to access localized string resources.
 */
object BuiltInAgents {
    fun generalPurpose(context: Context) = AgentDefinition(
        agentType = "general-purpose",
        displayName = context.getString(R.string.agent_name_general),
        whenToUse = context.getString(R.string.agent_when_to_use_general),
        systemPrompt = "", // Uses orchestrator's system prompt or default
        tools = listOf("*"), // All tools
        isBuiltIn = true,
        source = "built-in",
    )

    fun explore(context: Context) = AgentDefinition(
        agentType = "explore",
        displayName = context.getString(R.string.agent_name_explore),
        whenToUse = context.getString(R.string.agent_when_to_use_explore),
        systemPrompt = context.getString(R.string.agent_prompt_explore),
        tools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
        disallowedTools = listOf("agent", "exit_plan_mode", "write_file", "edit_file", "create_directory", "move_file", "delete_file"),
        background = true,
        color = "#4285F4",
        isBuiltIn = true,
        source = "built-in",
        omitClaudeMd = true,
    )

    fun plan(context: Context) = AgentDefinition(
        agentType = "plan",
        displayName = context.getString(R.string.agent_name_plan),
        whenToUse = context.getString(R.string.agent_when_to_use_plan),
        systemPrompt = context.getString(R.string.agent_prompt_plan),
        tools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
        disallowedTools = listOf("agent", "exit_plan_mode", "write_file", "edit_file", "create_directory", "move_file", "delete_file"),
        background = true,
        color = "#34A853",
        isBuiltIn = true,
        source = "built-in",
        omitClaudeMd = true,
    )

    fun verification(context: Context) = AgentDefinition(
        agentType = "verification",
        displayName = context.getString(R.string.agent_name_verification),
        whenToUse = context.getString(R.string.agent_when_to_use_verification),
        systemPrompt = context.getString(R.string.agent_prompt_verification),
        disallowedTools = listOf("agent", "exit_plan_mode", "write_file", "edit_file", "create_directory", "move_file", "delete_file"),
        background = true,
        color = "#EA4335",
        isBuiltIn = true,
        source = "built-in",
        criticalSystemReminder = context.getString(R.string.agent_verification_critical),
    )

    /** Create all built-in agents using the provided context */
    fun all(context: Context): List<AgentDefinition> = listOf(
        generalPurpose(context),
        explore(context),
        plan(context),
        verification(context)
    )

    /** Lookup by agentType from a pre-built list */
    fun findByType(agentType: String, agents: List<AgentDefinition>): AgentDefinition? =
        agents.find { it.agentType == agentType }
}

suspend fun loadAgentDefinitions(
    repository: com.omnichat.data.AppRepository,
    context: Context,
): List<AgentDefinition> {
    // Load from DB agent_definitions table
    val dbDefinitions = repository.getAllAgentDefinitions()
    val customFromDb = dbDefinitions.map { entity ->
        AgentDefinition(
            agentType = entity.agentType,
            displayName = entity.displayName,
            whenToUse = entity.whenToUse,
            systemPrompt = entity.systemPrompt,
            modelHint = entity.modelHint,
            modelConfigId = entity.modelConfigId,
            overrideModelId = entity.overrideModelId,
            tools = entity.toolsJson?.let { parseJsonList(it) },
            disallowedTools = entity.disallowedToolsJson?.let { parseJsonList(it) },
            background = entity.background,
            maxTurns = entity.maxTurns,
            color = entity.color,
            memory = entity.memory,
            mcpServers = entity.mcpServersJson?.let { parseMcpServers(it) },
            hooks = entity.hooksJson?.let { parseHooks(it) },
            permissionMode = entity.permissionMode,
            initialPrompt = entity.initialPrompt,
            effort = entity.effort,
            omitClaudeMd = entity.omitClaudeMd,
            requiredMcpServers = entity.requiredMcpServersJson?.let { parseJsonList(it) },
            filename = entity.filePath?.let { java.io.File(it).nameWithoutExtension },
            baseDir = entity.baseDir,
            criticalSystemReminder = entity.criticalSystemReminder,
            isBuiltIn = false,
            source = entity.baseDir ?: "db",
        )
    }

    // Load from legacy agent_presets table (backward compatibility)
    val presets = repository.getAllAgentPresets()
    val legacyPresets = presets.map { preset ->
        AgentDefinition(
            agentType = "custom:${preset.name}",
            displayName = preset.name,
            systemPrompt = preset.systemPrompt,
            modelConfigId = preset.modelConfigId,
            isBuiltIn = false,
            source = "preset",
        )
    }

    return BuiltInAgents.all(context) + customFromDb + legacyPresets
}

private fun parseJsonList(json: String): List<String>? {
    return try {
        val arr = org.json.JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.getString(i))
        }
        list
    } catch (e: Exception) {
        null
    }
}

private fun parseMcpServers(json: String): List<AgentMcpServerSpec>? {
    return try {
        val arr = org.json.JSONArray(json)
        val list = mutableListOf<AgentMcpServerSpec>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.has("name") && !item.has("config")) {
                // Reference format: {"name": "slack"}
                list.add(AgentMcpServerSpec.Reference(item.getString("name")))
            } else if (item.has("name") && item.has("config")) {
                // Inline format: {"name": "my-server", "config": {...}}
                val configObj = item.getJSONObject("config")
                val config = McpServerConfig(
                    transport = configObj.optString("transport", "stdio"),
                    command = configObj.optString("command").takeIf { it.isNotEmpty() },
                    args = configObj.optJSONArray("args")?.let { arr ->
                        val argsList = mutableListOf<String>()
                        for (j in 0 until arr.length()) {
                            argsList.add(arr.getString(j))
                        }
                        argsList
                    },
                    env = configObj.optJSONObject("env")?.let { obj ->
                        val map = mutableMapOf<String, String>()
                        for (key in obj.keys()) {
                            map[key] = obj.getString(key)
                        }
                        map
                    },
                    url = configObj.optString("url").takeIf { it.isNotEmpty() },
                    timeout = configObj.optLong("timeout").takeIf { it > 0 },
                    trustAllCertificates = configObj.optBoolean("trustAllCertificates", false),
                )
                list.add(AgentMcpServerSpec.Inline(item.getString("name"), config))
            }
        }
        list
    } catch (e: Exception) {
        null
    }
}

private fun parseHooks(json: String): AgentHooks? {
    return try {
        val obj = org.json.JSONObject(json)
        AgentHooks(
            preToolUse = obj.optJSONArray("preToolUse")?.let { parseHookArray(it) },
            postToolUse = obj.optJSONArray("postToolUse")?.let { parseHookArray(it) },
            prePrompt = obj.optJSONArray("prePrompt")?.let { parseHookArray(it) },
            postPrompt = obj.optJSONArray("postPrompt")?.let { parseHookArray(it) },
            stop = obj.optJSONArray("stop")?.let { parseHookArray(it) },
        )
    } catch (e: Exception) {
        null
    }
}

private fun parseHookArray(arr: org.json.JSONArray): List<AgentHook>? {
    val list = mutableListOf<AgentHook>()
    for (i in 0 until arr.length()) {
        val item = arr.getJSONObject(i)
        val matcher = item.optString("matcher", "*")
        val hooksArr = item.optJSONArray("hooks")
        if (hooksArr != null) {
            val hooksList = mutableListOf<String>()
            for (j in 0 until hooksArr.length()) {
                hooksList.add(hooksArr.getString(j))
            }
            list.add(AgentHook(matcher, hooksList))
        }
    }
    return list.takeIf { it.isNotEmpty() }
}
