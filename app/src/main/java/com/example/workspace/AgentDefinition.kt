package com.example.workspace

/**
 * Agent definition.
 *
 * Describes the static configuration of an Agent, including name, system prompt, tool permissions, etc.
 * Can be loaded from JSON files or Room database presets.
 *
 * @property name Unique identifier
 * @property displayName UI display name
 * @property systemPrompt System prompt
 * @property modelHint Model selection hint (for automatic model matching)
 * @property allowedTools Tool whitelist (null = all allowed)
 * @property disallowedTools Tool blacklist (null = no restrictions)
 * @property isOrchestrator Whether this is an Orchestrator
 * @property maxToolIterations Maximum tool call iterations
 * @property description Agent description
 */
data class AgentDefinition(
    val name: String,
    val displayName: String,
    val systemPrompt: String,
    val modelHint: ModelHint? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    val isOrchestrator: Boolean = false,
    val maxToolIterations: Int = 50,
    val description: String = ""
) {
    /**
     * Filter tool list.
     *
     * First applies whitelist (allowedTools), then applies blacklist (disallowedTools).
     * When whitelist is null, all tools are retained; when blacklist is null, no restrictions apply.
     *
     * @param allToolNames Set of all available tool names
     * @return Filtered set of tool names
     */
    fun filterTools(allToolNames: Set<String>): Set<String> {
        // Whitelist filter: null retains all
        val afterWhitelist = if (allowedTools != null) {
            allToolNames.intersect(allowedTools.toSet())
        } else {
            allToolNames
        }
        // Blacklist filter: null means no restrictions
        return if (disallowedTools != null) {
            afterWhitelist.subtract(disallowedTools.toSet())
        } else {
            afterWhitelist
        }
    }

    /**
     * Check if a single tool is allowed.
     *
     * @param toolName Tool name
     * @return true if allowed
     */
    fun isToolAllowed(toolName: String): Boolean {
        // Whitelist check: reject if not in whitelist
        if (allowedTools != null && toolName !in allowedTools) return false
        // Blacklist check: reject if in blacklist
        if (disallowedTools != null && toolName in disallowedTools) return false
        return true
    }
}
