package com.omnichat.cloud

import android.content.Context
import com.omnichat.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudBackupManager(private val context: Context) {

    private val repository = CloudBackupRepository(context)

    val isBound: Boolean get() = repository.isBound
    val userId: String? get() = repository.userId

    // --- Binding ---

    suspend fun bindTotp(): Result<BindTotpResponse> {
        return repository.bindTotp()
    }

    suspend fun verifyAndBind(totpSecret: String, totpCode: String): Result<VerifyResponse> {
        return repository.verifyAndBind(totpSecret, totpCode)
    }

    suspend fun verifyForRecovery(totpSecret: String, totpCode: String): Result<VerifyResponse> {
        return repository.verifyForRecovery(totpSecret, totpCode)
    }

    fun unbind() {
        repository.unbind()
    }

    // --- Backup ---

    suspend fun uploadConfigBackup(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Generate config export (same logic as SettingsViewModel.exportToUri)
            val configJson = generateConfigExport()
            val data = configJson.toByteArray(Charsets.UTF_8)
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val filename = "omnichat_config_$timestamp.omniconfig"

            val result = repository.uploadBackup("omniconfig", data, filename)
            result.map { it.backupId }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadDatabaseBackup(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")

            val dbPath = context.getDatabasePath("ai_chat_memory_db")
            val data = dbPath.readBytes()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val filename = "omnichat_db_$timestamp.omnidb"

            val result = repository.uploadBackup("omnidb", data, filename)
            result.map { it.backupId }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadAllBackups(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val configResult = uploadConfigBackup()
        val dbResult = uploadDatabaseBackup()

        if (configResult.isSuccess && dbResult.isSuccess) {
            Result.success(configResult.getOrThrow() to dbResult.getOrThrow())
        } else {
            val error = configResult.exceptionOrNull() ?: dbResult.exceptionOrNull()
            Result.failure(error ?: Exception("Upload failed"))
        }
    }

    // --- Restore ---

    suspend fun listBackups(): Result<List<BackupMeta>> {
        return repository.listBackups()
    }

    suspend fun restoreConfigBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = repository.downloadBackup(backupId).getOrThrow()
            // Save to temp file and return path for SettingsViewModel to import
            val tempFile = File(context.cacheDir, "restore_config.omniconfig")
            tempFile.writeBytes(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreDatabaseBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = repository.downloadBackup(backupId).getOrThrow()

            // Validate header
            if (!String(data, 0, minOf(10, data.size)).startsWith("OMNIDB_V1")) {
                return@withContext Result.failure(Exception("Invalid backup file"))
            }

            // Close database, replace file, restart app
            val db = AppDatabase.getDatabase(context)
            db.close()

            val dbPath = context.getDatabasePath("ai_chat_memory_db")
            dbPath.writeBytes(data)

            // Delete WAL/SHM
            File(dbPath.path + "-wal").delete()
            File(dbPath.path + "-shm").delete()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Settings ---

    fun setWorkersUrl(url: String) {
        repository.setWorkersUrl(url)
    }

    fun getWorkersUrl(): String {
        return repository.getWorkersUrl()
    }

    private fun generateConfigExport(): String {
        // TODO: Implement config export (reuse logic from SettingsViewModel)
        // This should generate the same JSON format as the .omniconfig export
        return "{}"
    }
}
