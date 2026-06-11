package com.omnichat.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnichat.MainActivity
import com.omnichat.R
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 接收 AlarmManager 触发的定时提醒。
 * 进程死亡后仍可被系统唤起执行。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_TIMER_ID = "timer_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getStringExtra(EXTRA_TIMER_ID)
        if (timerId.isNullOrBlank()) {
            Log.w(TAG, "[onReceive] 缺少 timer_id extra")
            return
        }

        val meta = TimerManager.getTimerMeta(timerId)
        if (meta == null) {
            Log.w(TAG, "[onReceive] timerId=$timerId 不在内存中，尝试从磁盘恢复")
            val diskMeta = TimerStorage.loadAll(context).find { it.timerId == timerId }
            if (diskMeta != null) {
                handleFire(context, diskMeta)
            } else {
                Log.w(TAG, "[onReceive] timerId=$timerId 无法找到，忽略")
            }
            return
        }

        handleFire(context, meta)
    }

    private fun handleFire(context: Context, meta: TimerManager.TimerMeta) {
        Log.i(TAG, "[handleFire] id=${meta.timerId}, session=${meta.sessionId}, repeat=${meta.repeatIntervalMs}ms, linkedTask=${meta.linkedTaskId}")

        // 1. 发送系统通知
        sendNotification(context, meta)

        // 2. 关联了 subAgent 任务时，发射自动检查事件唤醒 MainAgent（不插入静态消息，由 LLM 直接响应）
        //    未关联任务时，插入静态提醒消息
        if (!meta.linkedTaskId.isNullOrBlank()) {
            scope.launch {
                try {
                    TimerAutoCheckManager.emitAutoCheck(
                        sessionId = meta.sessionId,
                        taskId = meta.linkedTaskId,
                        timerMessage = meta.message
                    )
                    Log.i(TAG, "[handleFire] 已发射自动检查事件 task=${meta.linkedTaskId}")
                } catch (e: Exception) {
                    Log.e(TAG, "[handleFire] 发射自动检查事件失败", e)
                }
            }
        } else {
            scope.launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val repository = AppRepository(db)
                    val content = context.getString(R.string.alarm_timer_reminder, meta.message)
                    repository.insertMessage(
                        Message(
                            sessionId = meta.sessionId,
                            role = "assistant",
                            content = content
                        )
                    )
                    Log.i(TAG, "[handleFire] 消息已插入 session=${meta.sessionId}")
                } catch (e: Exception) {
                    Log.e(TAG, "[handleFire] 插入消息失败", e)
                }
            }
        }

        // 3. 处理重复/单次
        if (meta.repeatIntervalMs > 0) {
            // 重复定时器：更新下次触发时间，重新调度
            val nextFireAt = System.currentTimeMillis() + meta.repeatIntervalMs
            val updated = meta.copy(fireAtMs = nextFireAt)
            TimerManager.updateRepeatingTimer(context, updated)
        } else {
            // 单次：清理
            TimerManager.removeTimer(context, meta.timerId)
        }
    }

    private fun sendNotification(context: Context, meta: TimerManager.TimerMeta) {
        try {
            // 确保 channel 存在
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    TimerManager.NOTIFICATION_CHANNEL_ID,
                    TimerManager.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.alarm_notification_description)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                meta.timerId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, TimerManager.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(meta.label)
                .setContentText(meta.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(meta.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(meta.timerId.hashCode(), notification)
            Log.i(TAG, "[sendNotification] 通知已发送 id=${meta.timerId}")
        } catch (e: Exception) {
            Log.e(TAG, "[sendNotification] 发送通知失败", e)
        }
    }
}
