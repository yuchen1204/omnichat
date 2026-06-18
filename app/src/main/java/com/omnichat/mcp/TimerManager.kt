package com.omnichat.mcp

import android.app.AlarmManager
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
import com.omnichat.MainActivity
import com.omnichat.R
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 AI 创建的定时任务（单次 + 重复）。
 *
 * 双轨机制：
 * - AlarmManager：主要调度器，进程死亡后仍可被系统唤起，支持重复定时器
 * - Handler.postDelayed：进程存活时的精确触发辅助（精度：秒级）
 *
 * 所有公开方法均线程安全。
 */
object TimerManager {

    private const val TAG = "TimerManager"
    const val NOTIFICATION_CHANNEL_ID = "ai_timer_channel"
    const val NOTIFICATION_CHANNEL_NAME = "AI 定时提醒"

    private val handler = Handler(Looper.getMainLooper())

    /** timerId -> 定时器元数据（进程内存缓存） */
    private val timerMeta = ConcurrentHashMap<String, TimerMeta>()

    data class TimerMeta(
        val timerId: String,
        val sessionId: Long,
        val message: String,
        val label: String,
        val fireAtMs: Long,
        val createdAtMs: Long = System.currentTimeMillis(),
        val repeatIntervalMs: Long = 0L,  // 0 = 单次，>0 = 重复间隔（毫秒）
        val linkedTaskId: String? = null
    ) {
        val isRepeating: Boolean get() = repeatIntervalMs > 0
    }

    /**
     * 创建定时任务（支持单次和重复）。
     *
     * @param context           Application context
     * @param sessionId         触发时要插入消息的 session ID
     * @param delaySeconds      首次触发延迟秒数（1 ~ 604800，即 7 天）
     * @param message           提醒内容
     * @param label             通知标题（可选）
     * @param repeatIntervalSec 重复间隔秒数（0 = 单次，>0 = 重复）
     * @param linkedTaskId      关联的 subAgent taskId（可选，用于任务完成时自动取消）
     * @return 新建的 timerId
     */
    fun createTimer(
        context: Context,
        sessionId: Long,
        delaySeconds: Long,
        message: String,
        label: String = "AI 定时提醒",
        repeatIntervalSec: Long = 0L,
        linkedTaskId: String? = null
    ): String {
        val timerId = UUID.randomUUID().toString().take(8)
        val fireAtMs = System.currentTimeMillis() + delaySeconds * 1000L

        val meta = TimerMeta(
            timerId = timerId,
            sessionId = sessionId,
            message = message,
            label = label,
            fireAtMs = fireAtMs,
            repeatIntervalMs = repeatIntervalSec * 1000L,
            linkedTaskId = linkedTaskId
        )

        // 写入内存缓存
        timerMeta[timerId] = meta

        // 持久化到磁盘
        persistTimers(context)

        // 注册 AlarmManager（主要机制，存活进程死亡）
        scheduleAlarm(context, meta)

        // 注册 Handler（辅助机制，进程存活时精确触发）
        scheduleHandler(context, timerId, delaySeconds)

        val type = if (meta.isRepeating) "重复(每${repeatIntervalSec}s)" else "单次"
        Log.i(TAG, "[createTimer] id=$timerId, type=$type, delay=${delaySeconds}s, session=$sessionId")
        return timerId
    }

    /**
     * 取消一个尚未触发的定时任务。
     */
    fun cancelTimer(context: Context, timerId: String): Boolean {
        val meta = timerMeta.remove(timerId) ?: return false

        // 取消 AlarmManager
        cancelAlarm(context, meta)

        // 持久化
        persistTimers(context)

        Log.i(TAG, "[cancelTimer] id=$timerId 已取消")
        return true
    }

    /**
     * 根据关联的 subAgent taskId 取消定时器。
     * 用于 subAgent 任务完成时自动清理等待 timer。
     */
    fun cancelByTaskId(context: Context, taskId: String): Boolean {
        val meta = timerMeta.values.find { it.linkedTaskId == taskId }
            ?: return false
        return cancelTimer(context, meta.timerId)
    }

    /**
     * 取消关联了指定 SubAgent 任务的定时器。
     *
     * SubAgent 任务完成时调用：直接取消轮询定时器。
     */
    fun fireFinalCheckAndCancel(context: Context, taskId: String) {
        val meta = timerMeta.values.find { it.linkedTaskId == taskId } ?: return
        // 取消定时器
        cancelTimer(context, meta.timerId)
    }

    /**
     * 返回当前所有待触发的定时任务列表。
     * 同时包含内存和磁盘中的数据。
     */
    fun listTimers(context: Context): List<TimerMeta> {
        // 合并磁盘数据（进程重启后内存可能为空）
        if (timerMeta.isEmpty()) {
            val diskTimers = TimerStorage.loadAll(context)
            diskTimers.forEach { timerMeta[it.timerId] = it }
        }
        return timerMeta.values.sortedBy { it.fireAtMs }
    }

    /**
     * 获取指定定时器的元数据（供 AlarmReceiver 使用）。
     */
    fun getTimerMeta(timerId: String): TimerMeta? = timerMeta[timerId]

    /**
     * 更新重复定时器的下次触发时间（AlarmReceiver 触发后调用）。
     */
    fun updateRepeatingTimer(context: Context, updated: TimerMeta) {
        timerMeta[updated.timerId] = updated
        persistTimers(context)
        scheduleAlarm(context, updated)
        scheduleHandler(context, updated.timerId, (updated.fireAtMs - System.currentTimeMillis()) / 1000L)
        Log.i(TAG, "[updateRepeatingTimer] id=${updated.timerId}, nextFire=${updated.fireAtMs}")
    }

    /**
     * 移除已触发的单次定时器（AlarmReceiver 调用）。
     */
    fun removeTimer(context: Context, timerId: String) {
        timerMeta.remove(timerId)
        persistTimers(context)
        Log.i(TAG, "[removeTimer] id=$timerId 已清理")
    }

    /**
     * 从磁盘恢复所有定时器并重新注册 AlarmManager。
     * 在设备重启或应用启动时调用。
     */
    fun restoreFromDisk(context: Context) {
        val diskTimers = TimerStorage.loadAll(context)
        val now = System.currentTimeMillis()

        var restored = 0
        var expired = 0

        diskTimers.forEach { meta ->
            if (meta.fireAtMs <= now && meta.repeatIntervalMs == 0L) {
                // 单次定时器已过期，补发通知后清理
                expired++
                fireTimerNow(context, meta)
                removeTimer(context, meta.timerId)
            } else if (meta.fireAtMs <= now && meta.repeatIntervalMs > 0L) {
                // 重复定时器过期了，计算下次触发时间
                val elapsed = now - meta.fireAtMs
                val intervalsMissed = elapsed / meta.repeatIntervalMs + 1
                val nextFire = meta.fireAtMs + intervalsMissed * meta.repeatIntervalMs
                val updated = meta.copy(fireAtMs = nextFire)
                timerMeta[updated.timerId] = updated
                scheduleAlarm(context, updated)
                restored++
            } else {
                // 尚未触发，正常恢复
                timerMeta[meta.timerId] = meta
                scheduleAlarm(context, meta)
                restored++
            }
        }

        persistTimers(context)
        Log.i(TAG, "[restoreFromDisk] 恢复 $restored 个，过期 $expired 个定时器")
    }

    /**
     * 初始化通知 Channel（Application/Activity onCreate 时调用）。
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

    /**
     * 立即触发定时器的通知和消息（供过期恢复时使用）。
     */
    private fun fireTimerNow(context: Context, meta: TimerMeta) {
        Log.i(TAG, "[fireTimerNow] 补发过期定时器 id=${meta.timerId}")

        // 发送通知
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "AI 助手创建的定时提醒"
                    }
                )
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(context, meta.timerId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(meta.label)
                .setContentText(meta.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(meta.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(meta.timerId.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "[fireTimerNow] 通知失败", e)
        }

        // 插入消息
        try {
            val db = AppDatabase.getDatabase(context)
            val repo = AppRepository(db)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    repo.insertMessage(Message(
                        sessionId = meta.sessionId,
                        role = "assistant",
                        content = "⏰ **定时提醒**\n\n${meta.message}"
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "[fireTimerNow] 插入消息失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[fireTimerNow] 消息初始化失败", e)
        }
    }

    // ── 内部方法 ──────────────────────────────────────────────────────────

    /**
     * 注册 AlarmManager 定时任务。
     */
    private fun scheduleAlarm(context: Context, meta: TimerMeta) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildAlarmPendingIntent(context, meta)

        val triggerAtMs = if (meta.fireAtMs > System.currentTimeMillis()) {
            meta.fireAtMs
        } else {
            // 过期的重复定时器，立即触发
            System.currentTimeMillis()
        }

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMs, pendingIntent),
                pendingIntent
            )
            Log.d(TAG, "[scheduleAlarm] id=${meta.timerId}, triggerAt=$triggerAtMs")
        } catch (e: SecurityException) {
            // 极少数设备限制精确闹钟，降级为 inexact
            Log.w(TAG, "[scheduleAlarm] 精确闹钟被拒，降级: ${e.message}")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMs,
                pendingIntent
            )
        }
    }

    /**
     * 取消 AlarmManager 定时任务。
     */
    private fun cancelAlarm(context: Context, meta: TimerMeta) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildAlarmPendingIntent(context, meta))
    }

    /**
     * 构建 AlarmManager PendingIntent。
     */
    private fun buildAlarmPendingIntent(context: Context, meta: TimerMeta): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_TIMER_ID, meta.timerId)
        }
        return PendingIntent.getBroadcast(
            context,
            meta.timerId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 注册 Handler.postDelayed（进程存活时的辅助精确触发）。
     */
    private fun scheduleHandler(context: Context, timerId: String, delaySeconds: Long) {
        if (delaySeconds <= 0) return
        handler.postDelayed({
            // AlarmReceiver 已经处理了通知和消息，这里只做内存清理
            // （如果 AlarmReceiver 先触发，这里不会重复发送）
            Log.d(TAG, "[handler] 辅助触发 timerId=$timerId")
        }, delaySeconds * 1000L)
    }

    /**
     * 持久化当前所有定时器到磁盘。
     */
    private fun persistTimers(context: Context) {
        TimerStorage.saveAll(context, timerMeta.values)
    }
}
