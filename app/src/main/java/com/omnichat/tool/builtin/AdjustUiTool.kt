package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.UISettings
import com.omnichat.mcp.UiFieldRegistry
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/**
 * UI 调整工具。
 *
 * 功能：
 * - 调整颜色、布局、字体
 * - 支持重置为默认值
 */
object AdjustUiTool : BuiltinTool(
    name = "adjust_ui",
    description = """Adjust the app's complete visual theme in a single call — color scheme, layout, and font settings.

**Covers:** Material 3 color palette (primary / secondary / tertiary + their container and on-colors), surface and outline colors, error / success / warning / info / accent colors, corner radius, spacing multiplier, global font size scale, chat font size scale, and font family.

**Important:** Call get_ui_capabilities first to see current values and constraints. All colors must be #RRGGBB or #RRGGBBAA. Fields not provided retain their current values (incremental update). Pass resetToDefault=true to restore everything to defaults. Changes take effect immediately without restart.""",
    group = "ui_appearance",
    isReadOnly = false,
    isConcurrencySafe = false,
    searchHint = "adjust UI theme and colors"
) {

    override val inputSchema = schema {
        // 颜色字段
        for (f in UiFieldRegistry.colorFields) {
            put(f.key, buildColorProp(f.purpose))
        }

        // 布局字段
        prop("cornerRadiusDp", "integer", "Global corner radius in dp, range 0–32. Affects cards, buttons, and other rounded elements.")
        prop("spacingMultiplier", "number", "Global spacing multiplier, range 0.5–2.0. 1.0 is the default; >1 is more spacious, <1 is more compact.")

        // 字体字段
        prop("fontSizeScale", "number", "Global font size scale, range 0.75–1.5.")
        prop("chatFontSizeScale", "number", "Chat-specific font size scale, range 0.75–1.5.")
        prop("fontFamily", "string", "Font family: default, serif, monospace, cursive.")

        prop("resetToDefault", "boolean", "Pass true to immediately reset ALL UI settings to defaults. Other fields are ignored.")
    }

    private fun buildColorProp(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
        put("pattern", "^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        var current = repository.getUISettings() ?: UISettings()

        // 重置为默认值
        if (arguments.optBoolean("resetToDefault", false)) {
            repository.upsertUISettings(UISettings())
            return successResponse("UI settings reset to defaults.")
        }

        val changed = mutableListOf<String>()

        // 处理颜色字段
        for (f in UiFieldRegistry.colorFields) {
            val v = arguments.optString(f.key).takeIf { UiFieldRegistry.isValidHex(it) }
            if (v != null) {
                current = f.setter(current, v)
                changed += f.key
            }
        }

        // 处理布局字段
        if (arguments.has("cornerRadiusDp")) {
            val cr = arguments.optInt("cornerRadiusDp", current.cornerRadiusDp).coerceIn(0, 32)
            if (cr != current.cornerRadiusDp) {
                current = current.copy(cornerRadiusDp = cr)
                changed += "cornerRadiusDp"
            }
        }

        if (arguments.has("spacingMultiplier")) {
            val sp = arguments.optDouble("spacingMultiplier", current.spacingMultiplier.toDouble())
                .toFloat().coerceIn(0.5f, 2.0f)
            if (sp != current.spacingMultiplier) {
                current = current.copy(spacingMultiplier = sp)
                changed += "spacingMultiplier"
            }
        }

        // 处理字体字段
        val validFontFamilies = setOf("default", "serif", "monospace", "cursive")

        if (arguments.has("fontSizeScale")) {
            val fs = arguments.optDouble("fontSizeScale", current.fontSizeScale.toDouble())
                .toFloat().coerceIn(0.75f, 1.5f)
            if (fs != current.fontSizeScale) {
                current = current.copy(fontSizeScale = fs)
                changed += "fontSizeScale"
            }
        }

        if (arguments.has("chatFontSizeScale")) {
            val cfs = arguments.optDouble("chatFontSizeScale", current.chatFontSizeScale.toDouble())
                .toFloat().coerceIn(0.75f, 1.5f)
            if (cfs != current.chatFontSizeScale) {
                current = current.copy(chatFontSizeScale = cfs)
                changed += "chatFontSizeScale"
            }
        }

        if (arguments.has("fontFamily")) {
            val ff = arguments.optString("fontFamily", "").trim().lowercase()
            if (ff.isNotEmpty() && ff in validFontFamilies && ff != current.fontFamily) {
                current = current.copy(fontFamily = ff)
                changed += "fontFamily"
            }
        }

        repository.upsertUISettings(current.copy(updatedAt = System.currentTimeMillis()))

        return if (changed.isEmpty()) {
            successResponse("No changes made. All values already match the requested settings.")
        } else {
            successResponse("UI updated: ${changed.size} fields changed (${changed.joinToString(", ")})")
        }
    }
}