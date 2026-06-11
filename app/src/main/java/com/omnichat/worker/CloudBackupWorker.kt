package com.omnichat.worker

import android.content.Context
import androidx.work.*
import com.omnichat.cloud.CloudBackupManager
import java.util.concurrent.TimeUnit

class CloudBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "cloud_backup_periodic"

        fun enqueuePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(
                repeatInterval = 5,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        fun cancelPeriodicWork(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val manager = CloudBackupManager(applicationContext)

        // Skip if not bound
        if (!manager.isBound) {
            return Result.success()
        }

        return try {
            val result = manager.uploadAllBackups()
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
