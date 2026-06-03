package com.omnichat.mcp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化定时器元数据到 SharedPreferences。
 * 确保定时器在进程死亡、系统重启后仍可恢复。
 */
object TimerStorage {

    private const val TAG = "TimerStorage"
    private const val PREFS_NAME = "timer_meta"
    private const val KEY_TIMERS = "timers"

    /**
     * 保存全部定时器元数据（覆盖写入）。
     */
    fun saveAll(context: Context, timers: Collection<TimerManager.TimerMeta>) {
        val arr = JSONArray()
        timers.forEach { m ->
            arr.put(JSONObject().apply {
                put("timerId", m.timerId)
                put("sessionId", m.sessionId)
                put("message", m.message)
                put("label", m.label)
                put("fireAtMs", m.fireAtMs)
                put("createdAtMs", m.createdAtMs)
                put("repeatIntervalMs", m.repeatIntervalMs)
                m.linkedTaskId?.let { put("linkedTaskId", it) }
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TIMERS, arr.toString()).apply()
        Log.d(TAG, "[saveAll] 保存 ${timers.size} 个定时器")
    }

    /**
     * 从磁盘加载所有定时器元数据。
     */
    fun loadAll(context: Context): List<TimerManager.TimerMeta> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TIMERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                try {
                    TimerManager.TimerMeta(
                        timerId = o.getString("timerId"),
                        sessionId = o.getLong("sessionId"),
                        message = o.getString("message"),
                        label = o.optString("label", "AI 定时提醒"),
                        fireAtMs = o.getLong("fireAtMs"),
                        createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                        repeatIntervalMs = o.optLong("repeatIntervalMs", 0L),
                        linkedTaskId = o.optString("linkedTaskId", "").takeIf { it.isNotBlank() }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[loadAll] 跳过损坏条目: $o", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[loadAll] JSON 解析失败", e)
            emptyList()
        }
    }

    /**
     * 删除磁盘上的全部定时器数据。
     */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_TIMERS).apply()
    }
}
