package com.omnichat.mcp

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 管理定时器触发后的自动检查事件。
 *
 * 当 linkedTaskId 的定时器触发时，AlarmReceiver 发射事件，
 * ChatViewModel 监听并触发 LLM 调用 check_task_status。
 */
object TimerAutoCheckManager {

    private const val TAG = "TimerAutoCheckManager"

    data class AutoCheckEvent(
        val sessionId: Long,
        val taskId: String,
        val timerMessage: String
    )

    private val _events = MutableSharedFlow<AutoCheckEvent>(
        extraBufferCapacity = 16
    )
    val events: SharedFlow<AutoCheckEvent> = _events.asSharedFlow()

    /**
     * 由 AlarmReceiver 调用：当 linkedTaskId 的定时器触发时发射事件。
     */
    suspend fun emitAutoCheck(sessionId: Long, taskId: String, timerMessage: String) {
        Log.i(TAG, "[emitAutoCheck] session=$sessionId, task=$taskId")
        _events.emit(AutoCheckEvent(sessionId, taskId, timerMessage))
    }
}
