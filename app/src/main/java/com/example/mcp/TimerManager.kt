package com.example.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 AI 创建的一次性定时任务。
 *
 * 双轨机制：
 * - Handler.postDelayed：进程存活时精确触发（精度：秒级）
 * - 触发时：向对应 session 插入一条消息 + 发送系统通知
 *
 * 所有公开方法均线程安全。
 */
object TimerManager {

    private const val TAG = "TimerManager"
    const val NOTIFICATION_CHANNEL_ID = "ai_timer_channel"
    private const val NOTIFICATION_CHANNEL_NAME = "AI 定时提醒"

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** timerId -> 待执行的 Runnable（用于取消） */
    private val pendingRunnables = ConcurrentHashMap<String, Runnable>()

    /** timerId -> 定时器元数据（用于 list_timers 查询） */
    private val timerMeta = ConcurrentHashMap<String, TimerMeta>()

    data class TimerMeta(
        val timerId: String,
        val sessionId: Long,
        val message: String,
        val label: String,
        val fireAtMs: Long,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    /**
     * 创建一次性定时任务。
     *
     * @param context       Application context
     * @param sessionId     触发时要插入消息的 session ID
     * @param delaySeconds  延迟秒数（1 ~ 86400）
     * @param message       提醒内容，触发时作为消息插入聊天
     * @param label         简短标签，用于通知标题（可选）
     * @return 新建的 timerId（UUID）
     */
    fun createTimer(
        context: Context,
        sessionId: Long,
        delaySeconds: Long,
        message: String,
        label: String = "AI 定时提醒"
    ): String {
        val timerId = UUID.randomUUID().toString().take(8)
        val fireAtMs = System.currentTimeMillis() + delaySeconds * 1000L

        val meta = TimerMeta(
            timerId = timerId,
            sessionId = sessionId,
            message = message,
            label = label,
            fireAtMs = fireAtMs
        )
        timerMeta[timerId] = meta

        val runnable = Runnable {
            timerMeta.remove(timerId)
            pendingRunnables.remove(timerId)
            onTimerFired(context, meta)
        }
        pendingRunnables[timerId] = runnable
        handler.postDelayed(runnable, delaySeconds * 1000L)

        Log.i(TAG, "[createTimer] id=$timerId, delay=${delaySeconds}s, session=$sessionId")
        return timerId
    }

    /**
     * 取消一个尚未触发的定时任务。
     *
     * @return true 表示成功取消，false 表示 timerId 不存在或已触发
     */
    fun cancelTimer(timerId: String): Boolean {
        val runnable = pendingRunnables.remove(timerId) ?: return false
        handler.removeCallbacks(runnable)
        timerMeta.remove(timerId)
        Log.i(TAG, "[cancelTimer] id=$timerId 已取消")
        return true
    }

    /**
     * 返回当前所有待触发的定时任务列表（快照）。
     */
    fun listTimers(): List<TimerMeta> {
        return timerMeta.values.sortedBy { it.fireAtMs }
    }

    /**
     * 定时器触发时的处理逻辑：插入聊天消息 + 发送通知。
     */
    private fun onTimerFired(context: Context, meta: TimerMeta) {
        Log.i(TAG, "[onTimerFired] id=${meta.timerId}, session=${meta.sessionId}")

        // 1. 向 session 插入一条 assistant 消息
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val content = "⏰ **定时提醒**\n\n${meta.message}"
                repository.insertMessage(
                    Message(
                        sessionId = meta.sessionId,
                        role = "assistant",
                        content = content
                    )
                )
                Log.i(TAG, "[onTimerFired] 消息已插入 session=${meta.sessionId}")
            } catch (e: Exception) {
                Log.e(TAG, "[onTimerFired] 插入消息失败", e)
            }
        }

        // 2. 发送系统通知
        sendNotification(context, meta)
    }

    /**
     * 发送系统通知。
     */
    private fun sendNotification(context: Context, meta: TimerMeta) {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Android 8+ 需要 NotificationChannel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "AI 助手创建的定时提醒"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 点击通知时打开 app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                meta.timerId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(meta.label)
                .setContentText(meta.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(meta.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            // 用 timerId hashCode 作为通知 ID，避免覆盖其他通知
            notificationManager.notify(meta.timerId.hashCode(), notification)
            Log.i(TAG, "[sendNotification] 通知已发送 id=${meta.timerId}")
        } catch (e: Exception) {
            Log.e(TAG, "[sendNotification] 发送通知失败", e)
        }
    }

    /**
     * 初始化通知 Channel（在 Application/Activity onCreate 时调用一次即可）。
     */
    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI 助手创建的定时提醒"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
