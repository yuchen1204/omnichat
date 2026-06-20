package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.UISettings
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/**
 * MCP 工具组管理工具。
 */

object ListMcpToolGroupsTool : BuiltinTool(
    name = "list_mcp_tool_groups",
    description = "List all available built-in MCP tool groups and their current enabled/disabled status. Use this tool to discover what capabilities are currently available to you or can be activated. Groups: core (essential), ui_appearance (theming/colors), ui_text (i18n), files (storage), documents (office), efficiency (timers), memory (long-term facts).",
    group = "core",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "list MCP tool groups"
) {

    override val inputSchema = schema {}

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val settings = repository.getUISettings() ?: UISettings()
        val enabledGroups = settings.enabledMcpGroups.split(",").toSet()

        val allGroups = listOf(
            "core" to "Essential tools: time, user interaction, configuration",
            "memory" to "Long-term memory storage and retrieval",
            "ui_appearance" to "Theme colors, layout, and visual customization",
            "ui_text" to "UI text string overrides and i18n",
            "files" to "File system operations (read, write, search)",
            "documents" to "Office document creation (PDF, Word, Excel, PPT)",
            "efficiency" to "Timers and productivity tools",
            "subagent" to "Sub-agent delegation and workflows"
        )

        val text = buildString {
            appendLine("MCP Tool Groups")
            appendLine()

            allGroups.forEach { (id, desc) ->
                val status = if (id == "core" || id in enabledGroups) "✓ ENABLED" else "✗ DISABLED"
                appendLine("$status  [$id]")
                appendLine("   $desc")
                appendLine()
            }

            appendLine("Use configure_mcp_tool_groups to enable or disable groups.")
        }

        return successResponse(text.trimEnd())
    }
}

object ConfigureMcpToolGroupsTool : BuiltinTool(
    name = "configure_mcp_tool_groups",
    description = "Enable or disable specific built-in MCP tool groups. Use this when you need a tool that is currently disabled, or when you want to simplify your toolset. Note: 'core' group cannot be disabled. Changes persist across sessions.",
    group = "core",
    isReadOnly = false,
    isConcurrencySafe = false,
    searchHint = "enable or disable tool groups"
) {

    override val inputSchema = schema {
        prop("enable", "array", "List of group names to enable. Valid: ui_text, ui_appearance, files, documents, efficiency, memory, subagent.") {
            items {
                enum("ui_text", "ui_appearance", "files", "documents", "efficiency", "memory", "subagent")
            }
        }
        prop("disable", "array", "List of group names to disable. Note: 'core' cannot be disabled.") {
            items {
                enum("ui_text", "ui_appearance", "files", "documents", "efficiency", "memory", "subagent")
            }
        }
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val current = repository.getUISettings() ?: UISettings()
        val currentGroups = current.enabledMcpGroups.split(",").toMutableSet()

        val toEnable = arguments.optJSONArray("enable")
        val toDisable = arguments.optJSONArray("disable")

        val enabled = mutableListOf<String>()
        val disabled = mutableListOf<String>()

        if (toEnable != null) {
            for (i in 0 until toEnable.length()) {
                val g = toEnable.optString(i)
                if (g.isNotEmpty() && g != "core" && currentGroups.add(g)) {
                    enabled += g
                }
            }
        }

        if (toDisable != null) {
            for (i in 0 until toDisable.length()) {
                val g = toDisable.optString(i)
                if (g.isNotEmpty() && g != "core" && currentGroups.remove(g)) {
                    disabled += g
                }
            }
        }

        if (enabled.isEmpty() && disabled.isEmpty()) {
            return successResponse("No changes made. Current groups: ${currentGroups.sorted().joinToString(", ")}")
        }

        val nextGroups = currentGroups.sorted().joinToString(",")
        repository.upsertUISettings(current.copy(enabledMcpGroups = nextGroups, updatedAt = System.currentTimeMillis()))

        val text = buildString {
            appendLine("Tool groups updated.")
            if (enabled.isNotEmpty()) appendLine("Enabled: ${enabled.joinToString(", ")}")
            if (disabled.isNotEmpty()) appendLine("Disabled: ${disabled.joinToString(", ")}")
            appendLine()
            appendLine("Current active groups: $nextGroups")
        }

        return successResponse(text.trimEnd())
    }
}

object SetToolDisplayModeTool : BuiltinTool(
    name = "set_tool_display_mode",
    description = "Control which tool groups are hidden in the chat UI. Tools still execute normally; only the display is suppressed. Use this when performing multiple sequential tool calls to avoid flooding the screen. Call with groups=\"\" to restore normal display for all tools.",
    group = "efficiency",
    isReadOnly = false,
    isConcurrencySafe = true,
    searchHint = "control tool display in UI"
) {

    override val inputSchema = schema {
        prop("groups", "string", "Comma-separated group names to silence. Empty string = show all (default). '*' = silence all built-in tools. Available groups: core, memory, ui_appearance, ui_text, files, documents, efficiency. External MCP tools are never affected.")
        required("groups")
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val current = repository.getUISettings() ?: UISettings()
        val groups = arguments.optString("groups", "")

        repository.upsertUISettings(current.copy(silentToolGroups = groups, updatedAt = System.currentTimeMillis()))

        return if (groups.isNotEmpty()) {
            successResponse("Tool display silenced for groups: $groups\nTools will still execute, but their output won't appear in chat.")
        } else {
            successResponse("Tool display restored. All tool outputs will appear in chat.")
        }
    }
}