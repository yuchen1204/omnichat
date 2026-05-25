package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.MainActivity
import com.example.R

// ═══════════════════════════════════════════════════════════════════════════════
// 工作区前台服务
//
// 在活跃工作区会话期间保持一个常驻通知，防止应用被系统杀死。
// 任务完成时更新通知提醒用户。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 工作区前台服务。
 *
 * 当有活跃的工作区任务时启动，显示常驻通知防止应用被后台杀死。
 * 任务完成时更新通知内容提醒用户。
 *
 * 使用方式：
 * - 任务开始时：`WorkspaceForegroundService.start(context, "正在执行多 Agent 协作...")`
 * - 任务完成时：`WorkspaceForegroundService.complete(context, "任务已完成")`
 * - 手动停止：`WorkspaceForegroundService.stop(context)`
 */
class WorkspaceForegroundService : Service() {

    companion object {
        private const val TAG = "WorkspaceFgService"
        private const val CHANNEL_ID = "workspace_running"
        private const val CHANNEL_ID_COMPLETE = "workspace_complete"
        private const val NOTIFICATION_ID = 2001

        private const val ACTION_START = "com.example.service.WORKSPACE_START"
        private const val ACTION_COMPLETE = "com.example.service.WORKSPACE_COMPLETE"
        private const val ACTION_STOP = "com.example.service.WORKSPACE_STOP"
        private const val EXTRA_MESSAGE = "message"

        /**
         * 启动前台服务，显示"正在工作中"通知。
         *
         * @param context 上下文
         * @param message 通知副标题（如任务描述）
         */
        fun start(context: Context, message: String = "多 Agent 协作进行中...") {
            val intent = Intent(context, WorkspaceForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 更新通知为"任务已完成"状态，然后延迟停止服务。
         *
         * @param context 上下文
         * @param message 完成消息
         */
        fun complete(context: Context, message: String = "任务已完成") {
            val intent = Intent(context, WorkspaceForegroundService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }

        /**
         * 停止前台服务。
         *
         * @param context 上下文
         */
        fun stop(context: Context) {
            val intent = Intent(context, WorkspaceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "多 Agent 协作进行中..."
                Log.d(TAG, "Starting foreground service: $message")
                createNotificationChannels()
                val notification = buildRunningNotification(message)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_COMPLETE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "任务已完成"
                Log.d(TAG, "Task completed: $message")
                createNotificationChannels()
                val notification = buildCompleteNotification(message)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, notification)
                // 延迟 5 秒后停止服务，给用户时间看到完成通知
                android.os.Handler(mainLooper).postDelayed({
                    stopSelf()
                }, 5000)
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping foreground service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 创建通知渠道。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 运行中渠道
            val runningChannel = NotificationChannel(
                CHANNEL_ID,
                "工作区任务运行中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "多 Agent 工作区任务正在执行时显示"
                setShowBadge(false)
            }
            nm.createNotificationChannel(runningChannel)

            // 完成渠道（高优先级提醒）
            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "工作区任务完成",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "多 Agent 工作区任务完成时提醒"
                enableVibration(true)
            }
            nm.createNotificationChannel(completeChannel)
        }
    }

    /**
     * 构建"运行中"通知。
     */
    private fun buildRunningNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle("OmniChat")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle("OmniChat")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
        }
    }

    /**
     * 构建"完成"通知。
     */
    private fun buildCompleteNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_COMPLETE)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle("OmniChat")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle("OmniChat")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Foreground service destroyed")
    }
}
