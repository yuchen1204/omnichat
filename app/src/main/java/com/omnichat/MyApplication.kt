package com.omnichat

import android.app.Application
import com.omnichat.worker.CloudBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            CloudBackupWorker.reconcilePeriodicWork(this@MyApplication)
        }
    }
}
