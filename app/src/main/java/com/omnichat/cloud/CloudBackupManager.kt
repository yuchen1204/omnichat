package com.omnichat.cloud

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.OmnifileFormat
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

    suspend fun recover(totpCode: String): Result<RecoverResponse> {
        return repository.recover(totpCode)
    }

    fun unbind() {
        repository.unbind()
    }

    // --- Backup ---

    /**
     * Upload a single omnifile backup containing selected sections.
     */
    suspend fun uploadOmnifileBackup(
        sections: List<String> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            database.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).close()

            val dbFile = context.getDatabasePath("ai_chat_memory_db")
            val databaseBytes = dbFile.readBytes()

            val exportType = if (sections.isEmpty()) {
                OmnifileFormat.ExportType.FULL
            } else {
                OmnifileFormat.ExportType.SELECTIVE
            }

            val metadata = OmnifileFormat.OmnifileMetadata(
                exportType = exportType,
                includedSections = if (sections.isEmpty()) {
                    OmnifileFormat.CATEGORY_PROVIDER_MCP +
                    OmnifileFormat.CATEGORY_MEMORY_PROMPTS +
                    OmnifileFormat.CATEGORY_THEME_UI +
                    OmnifileFormat.CATEGORY_CHAT_HISTORY
                } else {
                    sections
                }
            )

            val baos = java.io.ByteArrayOutputStream()
            OmnifileFormat.writeOmnifile(baos, metadata, databaseBytes)
            val data = baos.toByteArray()

            val timestamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.getDefault()
            ).format(java.util.Date())
            val filename = "omnichat_backup_$timestamp.omnifile"

            val result = repository.uploadBackup(data, filename)
            result.map { it.backupId }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Restore ---

    suspend fun listBackups(): Result<List<BackupMeta>> {
        return repository.listBackups()
    }

    suspend fun deleteBackup(backupId: String): Result<Unit> {
        return repository.deleteBackup(backupId)
    }

    suspend fun restoreOmnifileBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = repository.downloadBackup(backupId).getOrThrow()

            // Validate header
            val headerStr = String(data, 0, minOf(10, data.size), Charsets.UTF_8)
            if (!headerStr.startsWith("OMNIFILE1")) {
                return@withContext Result.failure(Exception("Invalid omnifile header"))
            }

            // Parse omnifile: skip 10-byte header, read metadata length, metadata, then database payload
            val headerSize = "OMNIFILE1\n".toByteArray().size
            val metadataLength = java.nio.ByteBuffer.wrap(data, headerSize, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).int
            val metadataStart = headerSize + 4
            val databaseStart = metadataStart + metadataLength

            if (databaseStart >= data.size) {
                return@withContext Result.failure(Exception("Omnifile contains no database payload"))
            }

            val dbData = data.copyOfRange(databaseStart, data.size)

            // Close database, replace file, restart app
            val db = AppDatabase.getDatabase(context)
            db.close()
            AppDatabase.clearInstance()

            val dbPath = context.getDatabasePath("ai_chat_memory_db")
            dbPath.writeBytes(dbData)

            // Delete WAL/SHM
            File(dbPath.path + "-wal").delete()
            File(dbPath.path + "-shm").delete()

            // Verify the restored database can be opened; if not, delete and let Room recreate
            try {
                val testDb = AppDatabase.getDatabase(context)
                testDb.openHelper.readableDatabase
                testDb.close()
                AppDatabase.clearInstance()
            } catch (e: Exception) {
                // Restored DB schema is incompatible — delete and recreate fresh
                AppDatabase.clearInstance()
                dbPath.delete()
                File(dbPath.path + "-wal").delete()
                File(dbPath.path + "-shm").delete()
            }

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
}
