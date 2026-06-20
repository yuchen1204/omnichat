package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.UISettings
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/**
 * UI 能力查询工具。
 */
object GetUiCapabilitiesTool : BuiltinTool(
    name = "get_ui_capabilities",
    description = """Query the capability manifest and current values of the app's UI theme configuration. **Call this tool before calling adjust_ui** to learn all adjustable fields, their semantics, constraints, and current effective values. The response includes: color field list (primary palette / status colors / extended colors), layout parameters (corner radius / spacing), valid value constraints (HEX range), and recommended color combination suggestions.""",
    group = "ui_appearance",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "query UI theme capabilities"
) {

    override val inputSchema = schema {}

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val current = repository.getUISettings() ?: UISettings()

        return buildUiCapabilitiesResponse(context, current)
    }

    private fun buildUiCapabilitiesResponse(context: Context, current: UISettings): JSONObject {
        val colorFields = com.omnichat.mcp.UiFieldRegistry.colorFields.map { f ->
            JSONObject().apply {
                put("name", f.key)
                put("currentValue", f.getter(current)?.toString())
                put("purpose", f.purpose)
                put("constraint", "HEX #RRGGBB(AA)")
            }
        }

        val layoutFields = listOf(
            JSONObject().apply {
                put("name", "cornerRadiusDp")
                put("currentValue", current.cornerRadiusDp)
                put("purpose", "Global corner radius in dp")
                put("constraint", "0-32")
            },
            JSONObject().apply {
                put("name", "spacingMultiplier")
                put("currentValue", current.spacingMultiplier)
                put("purpose", "Global spacing multiplier")
                put("constraint", "0.5-2.0")
            }
        )

        val fontFields = listOf(
            JSONObject().apply {
                put("name", "fontSizeScale")
                put("currentValue", current.fontSizeScale)
                put("purpose", "Global font size scale")
                put("constraint", "0.75-1.5")
            },
            JSONObject().apply {
                put("name", "chatFontSizeScale")
                put("currentValue", current.chatFontSizeScale)
                put("purpose", "Chat font size scale")
                put("constraint", "0.75-1.5")
            },
            JSONObject().apply {
                put("name", "fontFamily")
                put("currentValue", current.fontFamily)
                put("purpose", "Font family")
                put("constraint", "default, serif, monospace, cursive")
            }
        )

        val structured = JSONObject().apply {
            put("hasUserOverride", current.updatedAt > 0)
            put("updatedAt", current.updatedAt)
            put("colorFields", org.json.JSONArray(colorFields))
            put("layoutFields", org.json.JSONArray(layoutFields))
            put("fontFields", org.json.JSONArray(fontFields))
        }

        return JSONObject().apply {
            put("content", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "UI Capabilities and Current Values")
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "JSON_DATA: " + structured.toString())
                })
            })
        }
    }
}