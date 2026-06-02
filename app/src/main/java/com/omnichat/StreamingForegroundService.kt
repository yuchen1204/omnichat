package com.omnichat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：在 LLM 流式回复期间保持通知，防止用户切换应用后进程被系统回收。
 *
 * 生命周期：
 *   1. ChatViewModel 开始流式回复 → startForeground()
 *   2. 流式回复完成 → 更新通知为"已完成"
 *   3. 通知展示 5 秒后自动 stopSelf()
 */
class StreamingForegroundService : Service() {

    companion object {
        private const val TAG = "StreamingFgService"
        const val NOTIFICATION_CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "AI 回复通知"
        const val NOTIFICATION_ID = 2001

        private const val ACTION_START = "com.omnichat.ACTION_STREAMING_START"
        private const val ACTION_COMPLETE = "com.omnichat.ACTION_STREAMING_COMPLETE"
        private const val ACTION_STOP = "com.omnichat.ACTION_STREAMING_STOP"

        /** 倒计时自动停止的延迟（毫秒） */
        private const val AUTO_STOP_DELAY_MS = 5000L

        /**
         * 初始化通知 Channel（Application/Activity onCreate 时调用）。
         */
        fun initChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW   // 无声音/振动，静默展示
                ).apply {
                    description = "LLM 流式回复期间的前台通知"
                }
                nm.createNotificationChannel(channel)
            }
        }

        /**
         * 启动前台服务，显示"正在回复"通知。
         */
        fun start(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 通知回复已完成，5 秒后自动停止服务。
         */
        fun complete(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_COMPLETE
            }
            context.startService(intent)
        }

        /**
         * 立即停止服务并移除通知。
         */
        fun stop(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "[onStartCommand] ACTION_START — 发起前台通知")
                startForeground(NOTIFICATION_ID, buildStreamingNotification())
            }

            ACTION_COMPLETE -> {
                Log.i(TAG, "[onStartCommand] ACTION_COMPLETE — 更新通知为已完成")
                updateNotification(buildCompleteNotification())
                // 5 秒后自动停止服务
                handler.postDelayed({
                    Log.i(TAG, "[AUTO_STOP] 自动停止前台服务")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }, AUTO_STOP_DELAY_MS)
            }

            ACTION_STOP -> {
                Log.i(TAG, "[onStartCommand] ACTION_STOP — 立即停止服务")
                handler.removeCallbacksAndMessages(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "[onDestroy] 服务已销毁")
    }

    // ── 通知构建 ───────────────────────────────────────────────────────

    private fun buildStreamingNotification(): android.app.Notification {
        val pendingIntent = buildMainActivityPendingIntent()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text))
            .setOngoing(true)                               // 不可滑动清除
            .setOnlyAlertOnce(true)                         // 不重复振动/响铃
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildCompleteNotification(): android.app.Notification {
        val pendingIntent = buildMainActivityPendingIntent()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.streaming_notification_complete_title))
            .setContentText(getString(R.string.streaming_notification_complete_text))
            .setAutoCancel(true)                            // 点击后自动消失
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // 已完成时提升优先级
            .build()
    }

    private fun buildMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 权限未授予时安全降级
            Log.w(TAG, "[updateNotification] 无通知权限，跳过更新", e)
        }
    }
}
