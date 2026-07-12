package com.omnichat

import android.app.Application
import com.omnichat.worker.CloudBackupWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        CloudBackupWorker.enqueuePeriodicWork(this)
    }
}
