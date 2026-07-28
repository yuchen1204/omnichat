package com.omnichat.worker

import android.content.Context
import androidx.work.*
import com.omnichat.cloud.CloudBackupManager
import com.omnichat.data.AppDatabase
import java.util.concurrent.TimeUnit

class CloudBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "cloud_backup_periodic"

        /**
         * Reconcile the unique periodic work with the persisted setting.
         * This is the startup entry point so process creation cannot overwrite
         * a user-selected cadence with the H6 default.
         */
        suspend fun reconcilePeriodicWork(context: Context) {
            val frequency = AppDatabase.getDatabase(context)
                .uiSettingsDao()
                .getSettings()
                ?.cloudBackupFrequency
                ?: "H6"
            enqueuePeriodicWork(context, frequency)
        }

        fun enqueuePeriodicWork(context: Context, frequency: String = "H6") {
            if (frequency == "MANUAL") {
                cancelPeriodicWork(context)
                return
            }

            val intervalHours = when (frequency) {
                "H3" -> 3L
                "H6" -> 6L
                "H12" -> 12L
                "H24" -> 24L
                else -> 6L
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodicWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val manager = CloudBackupManager(applicationContext)
        if (!manager.isBound) {
            return Result.success()
        }
        return try {
            // Read backup sections from UISettings
            val database = AppDatabase.getDatabase(applicationContext)
            val settings = database.uiSettingsDao().getSettings()
            val sectionsJson = settings?.cloudBackupSections ?: "[]"
            val sections = try {
                org.json.JSONArray(sectionsJson).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } catch (_: Exception) { emptyList() }

            val result = manager.uploadOmnifileBackup(sections = sections)
            if (result.isSuccess) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
