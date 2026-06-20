package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.mcp.TimerManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时器工具。
 */

object CreateTimerTool : BuiltinTool(
    name = "create_timer",
    description = """Create a timer that fires after a delay (supports one-shot and repeating). When the timer fires, it inserts a reminder message into the current chat session AND sends a system notification. Timers survive app restarts and device reboots.

PREREQUISITE: You MUST call get_current_time first to confirm the current time before creating any timer.

Specify delay using hours, minutes, and/or seconds — at least one must be > 0. For example: hours=1, minutes=30 means 1 hour 30 minutes.

Returns a timerId that can be used with cancel_timer.""",
    group = "efficiency",
    isReadOnly = false,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "create a reminder timer"
) {

    override val inputSchema = schema {
        prop("hours", "integer", "Hours component of the delay (default 0).")
        prop("minutes", "integer", "Minutes component of the delay (default 0).")
        prop("seconds", "integer", "Seconds component of the delay (default 0).")
        prop("message", "string", "The reminder message to display when the timer fires.")
        prop("label", "string", "Optional short label for the notification title (max 30 chars).")
        prop("repeat_hours", "integer", "Repeat interval: hours component (default 0).")
        prop("repeat_minutes", "integer", "Repeat interval: minutes component (default 0).")
        prop("repeat_seconds", "integer", "Repeat interval: seconds component (default 0).")
        required("message")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val message = arguments.optString("message").trim()
        if (message.isEmpty()) return "Message is required"

        // 计算总延迟时间
        val hours = arguments.optLong("hours", 0L)
        val minutes = arguments.optLong("minutes", 0L)
        val seconds = arguments.optLong("seconds", 0L)
        val totalDelay = hours * 3600 + minutes * 60 + seconds

        if (totalDelay < 1) return "Total delay must be at least 1 second"

        // 验证重复间隔
        val repeatHours = arguments.optLong("repeat_hours", 0L)
        val repeatMinutes = arguments.optLong("repeat_minutes", 0L)
        val repeatSeconds = arguments.optLong("repeat_seconds", 0L)
        val totalRepeat = repeatHours * 3600 + repeatMinutes * 60 + repeatSeconds

        if (totalRepeat < 0) return "Repeat interval cannot be negative"
        if (totalRepeat in 1 until 60) return "Repeat interval must be at least 1 minute (60 seconds)"

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        if (sessionId == null) {
            return errorResponse("Timer requires a session context")
        }

        val hours = arguments.optLong("hours", 0L)
        val minutes = arguments.optLong("minutes", 0L)
        val seconds = arguments.optLong("seconds", 0L)
        val delaySeconds = hours * 3600 + minutes * 60 + seconds

        val message = arguments.optString("message")
        val label = arguments.optString("label", "AI Timer").take(30)

        val repeatHours = arguments.optLong("repeat_hours", 0L)
        val repeatMinutes = arguments.optLong("repeat_minutes", 0L)
        val repeatSeconds = arguments.optLong("repeat_seconds", 0L)
        val repeatIntervalSec = repeatHours * 3600 + repeatMinutes * 60 + repeatSeconds

        val timerId = TimerManager.createTimer(
            context = context,
            sessionId = sessionId,
            delaySeconds = delaySeconds,
            message = message,
            label = label,
            repeatIntervalSec = repeatIntervalSec
        )

        val humanDelay = formatDuration(context, delaySeconds)
        val repeatInfo = if (repeatIntervalSec > 0) {
            " (repeats every ${formatDuration(context, repeatIntervalSec)})"
        } else ""

        return successResponse("Timer created:\n• ID: $timerId\n• Delay: $humanDelay$repeatInfo\n• Message: $message")
    }

    private fun formatDuration(context: Context, totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            if (seconds > 0 || isEmpty()) append("${seconds}s")
        }.trim()
    }
}

object CancelTimerTool : BuiltinTool(
    name = "cancel_timer",
    description = "Cancel a pending timer before it fires. Use the timerId returned by create_timer. Returns an error if the timer does not exist or has already fired.",
    group = "efficiency",
    isReadOnly = false,
    isConcurrencySafe = true,
    searchHint = "cancel a timer"
) {

    override val inputSchema = schema {
        prop("timer_id", "string", "The timer ID returned by create_timer.")
        required("timer_id")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val timerId = arguments.optString("timer_id").trim()
        if (timerId.isEmpty()) return "timer_id is required"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val timerId = arguments.optString("timer_id")
        val cancelled = TimerManager.cancelTimer(context, timerId)

        return if (cancelled) {
            successResponse("Timer cancelled: $timerId")
        } else {
            errorResponse("Timer not found or already fired: $timerId")
        }
    }
}

object ListTimersTool : BuiltinTool(
    name = "list_timers",
    description = "List all currently pending (not yet fired) timers created in this session. Returns each timer's ID, label, message, remaining seconds, and scheduled fire time.",
    group = "efficiency",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "list all pending timers"
) {

    override val inputSchema = schema {}

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val timers = TimerManager.listTimers(context)

        if (timers.isEmpty()) {
            return successResponse("No pending timers.")
        }

        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        val text = buildString {
            appendLine("Pending Timers: ${timers.size}")
            appendLine()

            timers.forEachIndexed { i, t ->
                val remainingMs = (t.fireAtMs - now).coerceAtLeast(0L)
                val remainingSec = remainingMs / 1000
                val fireTime = sdf.format(Date(t.fireAtMs))
                val type = if (t.repeatIntervalMs > 0) "Repeating (${t.repeatIntervalMs / 1000}s)" else "One-shot"

                appendLine("${i + 1}. ID: `${t.timerId}` [$type]")
                appendLine("   Label:    ${t.label}")
                appendLine("   Message:  ${t.message}")
                appendLine("   Remaining: ${remainingSec}s (fires at $fireTime)")
                appendLine()
            }
        }

        return successResponse(text.trimEnd())
    }
}