package com.omnichat

import android.app.Application
import com.omnichat.worker.CloudBackupWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        applicationScope.launch {
            CloudBackupWorker.reconcilePeriodicWork(this@MyApplication)
        }
    }
}
