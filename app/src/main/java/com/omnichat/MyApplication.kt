package com.omnichat

import android.app.Application
import com.omnichat.worker.CloudBackupWorker

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CloudBackupWorker.enqueuePeriodicWork(this)
    }
}
