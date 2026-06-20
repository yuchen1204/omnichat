package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale

/**
 * 获取当前时间工具。
 *
 * 功能：
 * - 返回当前日期和时间
 * - 支持指定时区
 * - 返回多种格式（本地、ISO、Unix 时间戳）
 */
object GetCurrentTimeTool : BuiltinTool(
    name = "get_current_time",
    description = "Get the current real date and time (including timezone). Call this tool whenever you need to know today's date, the current time, the day of the week, or perform any reasoning that depends on the current time.",
    group = "core",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "get current date and time"
) {

    override val inputSchema = schema {
        prop("timezone", "string", "Optional. IANA timezone name, e.g. Asia/Shanghai or America/New_York. Leave empty to use the device's local timezone.")
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val tzId = arguments.optString("timezone").takeIf { it.isNotBlank() }

        val zone = try {
            if (tzId != null) ZoneId.of(tzId) else ZoneId.systemDefault()
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }

        val now = ZonedDateTime.now(zone)

        val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm:ss", Locale.getDefault())
        val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        val text = buildString {
            appendLine("Current time information:")
            appendLine()
            appendLine("Local time:    ${now.format(fullFmt)}")
            appendLine("Timezone:      ${zone.id} (${now.format(DateTimeFormatter.ofPattern("xxx"))})")
            appendLine("ISO format:    ${now.format(isoFmt)}")
            appendLine("Unix timestamp: ${now.toEpochSecond()}")
            appendLine("Day of week:   ${now.dayOfWeek.name}")
            appendLine("Date:          ${now.year}-${now.monthValue}-${now.dayOfMonth}")
        }

        return successResponse(text.trim())
    }
}