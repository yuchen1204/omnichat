package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.ColorSchemePreset
import com.omnichat.data.ColorSchemePreset.Companion.toUISettings
import com.omnichat.data.UISettings
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 配色方案管理工具。
 */
object ColorSchemeTool : BuiltinTool(
    name = "color_scheme",
    description = """Manage saved color scheme presets: save the current theme, list presets, apply a preset, or delete one.

• **save** — Save current UI settings as a named preset (name + description required). Up to ${ColorSchemePreset.MAX_PRESETS} presets allowed.
• **list** — List all saved presets with their schemeId, name, description, and preview colors.
• **apply** — Apply a preset by schemeId; takes effect immediately.
• **delete** — Delete a preset by schemeId to free up a slot.""",
    group = "ui_appearance",
    isReadOnly = false,
    isConcurrencySafe = false,
    searchHint = "manage color scheme presets"
) {

    override val inputSchema = schema {
        prop("action", "string", "Operation to perform.") {
            enum("save", "list", "apply", "delete")
        }
        prop("name", "string", "Preset name (max 30 chars). Required for 'save'.")
        prop("description", "string", "Preset description (max 100 chars). Required for 'save'.")
        prop("schemeId", "string", "Preset ID (from list). Required for 'apply' and 'delete'.")
        required("action")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val action = arguments.optString("action").trim().lowercase()
        if (action !in listOf("save", "list", "apply", "delete")) {
            return "Invalid action: $action. Must be save, list, apply, or delete."
        }

        when (action) {
            "save" -> {
                val name = arguments.optString("name").trim()
                if (name.isEmpty()) return "Name is required for 'save' action"
            }
            "apply", "delete" -> {
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isEmpty()) return "schemeId is required for '$action' action"
            }
        }

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val action = arguments.optString("action").trim().lowercase()
        val repository = AppRepository(AppDatabase.getDatabase(context))

        return when (action) {
            "save" -> handleSave(context, arguments, repository)
            "list" -> handleList(context, repository)
            "apply" -> handleApply(context, arguments, repository)
            "delete" -> handleDelete(context, arguments, repository)
            else -> errorResponse("Invalid action: $action")
        }
    }

    private suspend fun handleSave(context: Context, arguments: JSONObject, repository: AppRepository): JSONObject {
        val name = arguments.optString("name").trim().take(30)
        val desc = arguments.optString("description").trim().take(100)

        if (name.isEmpty()) return errorResponse("Name is required")

        val count = repository.getColorSchemePresetCount()
        if (count >= ColorSchemePreset.MAX_PRESETS) {
            val existing = repository.getAllColorSchemePresets()
            val list = existing.joinToString("\n") { "• [${it.schemeId}] ${it.name}" }
            return errorResponse("Maximum ${ColorSchemePreset.MAX_PRESETS} presets allowed. Existing presets:\n$list")
        }

        val current = repository.getUISettings() ?: UISettings()
        val schemeId = UUID.randomUUID().toString()
        val preset = ColorSchemePreset.fromUISettings(schemeId, name, desc, current)
        repository.insertColorSchemePreset(preset)

        return successResponse("Color scheme saved: \"${preset.name}\" (ID: $schemeId)\nTotal presets: ${count + 1}/${ColorSchemePreset.MAX_PRESETS}")
    }

    private suspend fun handleList(context: Context, repository: AppRepository): JSONObject {
        val presets = repository.getAllColorSchemePresets()

        if (presets.isEmpty()) {
            return successResponse("No saved color schemes. Use 'save' action to create one.")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val text = buildString {
            appendLine("Saved Color Schemes (${presets.size}/${ColorSchemePreset.MAX_PRESETS})")
            appendLine()

            presets.forEachIndexed { i, p ->
                appendLine("${i + 1}. \"${p.name}\"")
                appendLine("   schemeId:    ${p.schemeId}")
                appendLine("   Description: ${p.description}")
                appendLine("   Saved at:    ${sdf.format(Date(p.createdAt))}")
                appendLine("   Primary:     ${p.primaryColor}, Background: ${p.backgroundColor}")
                appendLine("   Success:     ${p.successColor}, Radius: ${p.cornerRadiusDp}dp, Spacing: ${p.spacingMultiplier}x")
                appendLine()
            }
        }

        return successResponse(text.trimEnd())
    }

    private suspend fun handleApply(context: Context, arguments: JSONObject, repository: AppRepository): JSONObject {
        val schemeId = arguments.optString("schemeId").trim()

        if (schemeId.isEmpty()) return errorResponse("schemeId is required")

        val preset = repository.getColorSchemePresetById(schemeId)
            ?: return errorResponse("Color scheme not found: $schemeId")

        repository.upsertUISettings(preset.toUISettings())

        return successResponse("Applied color scheme: \"${preset.name}\"\n${preset.description}")
    }

    private suspend fun handleDelete(context: Context, arguments: JSONObject, repository: AppRepository): JSONObject {
        val schemeId = arguments.optString("schemeId").trim()

        if (schemeId.isEmpty()) return errorResponse("schemeId is required")

        val preset = repository.getColorSchemePresetById(schemeId)
            ?: return errorResponse("Color scheme not found: $schemeId")

        repository.deleteColorSchemePreset(schemeId)
        val remaining = repository.getColorSchemePresetCount()

        return successResponse("Deleted color scheme: \"${preset.name}\"\nRemaining presets: $remaining/${ColorSchemePreset.MAX_PRESETS}")
    }
}