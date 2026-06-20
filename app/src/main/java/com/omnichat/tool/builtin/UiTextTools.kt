package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.UISettings
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import com.omnichat.ui.theme.UiStrings
import com.omnichat.ui.theme.UiStrings.Companion.fromJson
import com.omnichat.ui.theme.UiStrings.Companion.toJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 文本工具。
 */

object ListUiTextsTool : BuiltinTool(
    name = "list_ui_texts",
    description = """View all adjustable UI text strings in the app along with their default values and current AI override values. An optional query parameter can be provided to fuzzy-filter results by key or default value.

## Line break tip
You can use `\n` in set_ui_texts values to insert line breaks. For longer translated strings, insert `\n` at semantic break points to enable automatic wrapping.""",
    group = "ui_text",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "list adjustable UI text strings"
) {

    override val inputSchema = schema {
        prop("query", "string", "Optional. Fuzzy-filter by key name or default value. If not provided, all UI text entries are listed.")
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val current = repository.getUISettings() ?: UISettings()
        val strings = UiStrings.fromJson(current.uiStrings)

        // 读取所有已注册的 key
        val allKeys = try {
            context.assets.open("ui_text_keys.json").use { input ->
                val jsonStr = input.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key ->
                    map[key] = obj.getString(key)
                }
                map
            }
        } catch (e: Exception) {
            emptyMap<String, String>()
        }

        val query = arguments.optString("query").trim()
        val hasQuery = query.isNotEmpty()

        val text = buildString {
            appendLine("UI Text Strings")
            if (hasQuery) appendLine("Filter: \"$query\"")
            appendLine("Format: key → default [override]")
            appendLine()

            val unionKeys = (allKeys.keys + strings.overrides.keys).sorted()
            var matchCount = 0

            unionKeys.forEach { key ->
                val defaultText = allKeys[key] ?: ""
                val overrideText = strings.overrides[key]

                val matchesQuery = !hasQuery ||
                    key.contains(query, ignoreCase = true) ||
                    defaultText.contains(query, ignoreCase = true) ||
                    (overrideText != null && overrideText.contains(query, ignoreCase = true))

                if (matchesQuery) {
                    matchCount++
                    if (overrideText != null) {
                        appendLine("• $key")
                        appendLine("  Default: $defaultText")
                        appendLine("  Override: $overrideText")
                    } else {
                        appendLine("• $key → $defaultText")
                    }
                }
            }

            appendLine()
            appendLine("Total: ${if (hasQuery) "$matchCount matched of ${unionKeys.size}" else unionKeys.size}")
            appendLine("Overrides: ${strings.overrides.size}")
        }

        return successResponse(text)
    }
}

object SetUiTextsTool : BuiltinTool(
    name = "set_ui_texts",
    description = """Override any UI text labels (buttons, headings, hints, placeholders, etc.). Changes take effect globally and immediately without a restart.

## Usage
• updates: A key→value dictionary of strings to set
• delete: A list of keys whose overrides should be removed
• resetAll: Pass true to remove all overrides at once

## Key naming conventions
topbar.* / sidebar.* / nav.* / tab.* / chat.* / models.* / memory.* / mcp.* / dialog.* / action.* / status.* / hint.*

## Line break support
Use `\n` in values to insert line breaks.""",
    group = "ui_text",
    isReadOnly = false,
    isConcurrencySafe = false,
    searchHint = "override UI text strings"
) {

    override val inputSchema = schema {
        prop("updates", "object", "A key→value dictionary of UI text strings to set or update.") {
            additionalProperties { }
        }
        prop("delete", "array", "A list of keys whose overrides should be removed.") {
            items { }
        }
        prop("resetAll", "boolean", "Pass true to remove all overrides at once.")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val hasUpdates = arguments.has("updates") && arguments.optJSONObject("updates") != null
        val hasDeletes = arguments.has("delete") && arguments.optJSONArray("delete") != null
        val hasResetAll = arguments.optBoolean("resetAll", false)

        if (!hasUpdates && !hasDeletes && !hasResetAll) {
            return "At least one of updates, delete, or resetAll is required"
        }

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val current = repository.getUISettings() ?: UISettings()
        val currentStrings = UiStrings.fromJson(current.uiStrings)

        if (arguments.optBoolean("resetAll", false)) {
            repository.upsertUISettings(current.copy(uiStrings = "{}", updatedAt = System.currentTimeMillis()))
            return successResponse("All UI text overrides have been reset to defaults.")
        }

        val merged = currentStrings.overrides.toMutableMap()
        val applied = mutableListOf<String>()
        val removed = mutableListOf<String>()

        val updates = arguments.optJSONObject("updates")
        if (updates != null) {
            val it = updates.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = updates.optString(k)
                if (v.isNotEmpty()) {
                    merged[k] = v
                    applied += "$k = \"$v\""
                }
            }
        }

        val deletes = arguments.optJSONArray("delete")
        if (deletes != null) {
            for (i in 0 until deletes.length()) {
                val k = deletes.optString(i)
                if (k.isNotEmpty() && merged.remove(k) != null) {
                    removed += k
                }
            }
        }

        val newJson = UiStrings(merged).toJson()
        repository.upsertUISettings(current.copy(uiStrings = newJson, updatedAt = System.currentTimeMillis()))

        val text = buildString {
            appendLine("UI text overrides updated.")
            if (applied.isNotEmpty()) {
                appendLine()
                appendLine("Set (${applied.size}):")
                applied.forEach { appendLine("  • $it") }
            }
            if (removed.isNotEmpty()) {
                appendLine()
                appendLine("Removed: ${removed.joinToString(", ")}")
            }
            appendLine()
            appendLine("Total overrides: ${merged.size}")
        }

        return successResponse(text)
    }
}