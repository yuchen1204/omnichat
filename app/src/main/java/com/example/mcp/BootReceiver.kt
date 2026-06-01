package com.example.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备重启后恢复所有待触发的定时器。
 * 通过 BOOT_COMPLETED 广播触发。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "[onReceive] 设备启动完成，恢复定时器...")
        TimerManager.restoreFromDisk(context)
    }
}
