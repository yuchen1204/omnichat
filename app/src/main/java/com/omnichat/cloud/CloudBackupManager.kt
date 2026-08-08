package com.omnichat.cloud

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.OmnifileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class CloudBackupManager(private val context: Context) {

    private val repository = CloudBackupRepository(context)

    val isBound: Boolean get() = repository.isBound
    val userId: String? get() = repository.userId

    suspend fun bindTotp(): Result<BindTotpResponse> = repository.bindTotp()
    suspend fun verifyAndBind(totpSecret: String, totpCode: String): Result<VerifyResponse> = repository.verifyAndBind(totpSecret, totpCode)
    suspend fun verifyForRecovery(totpSecret: String, totpCode: String): Result<VerifyResponse> = repository.verifyForRecovery(totpSecret, totpCode)
    suspend fun recover(totpCode: String): Result<RecoverResponse> = repository.recover(totpCode)
    fun unbind() = repository.unbind()

    /** Uploads the database and, for a full backup, the private project file tree. */
    suspend fun uploadOmnifileBackup(sections: List<String> = emptyList()): Result<String> = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            database.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).close()
            val databaseBytes = context.getDatabasePath("ai_chat_memory_db").readBytes()
            val exportType = if (sections.isEmpty()) OmnifileFormat.ExportType.FULL else OmnifileFormat.ExportType.SELECTIVE
            val includedSections = if (sections.isEmpty()) {
                OmnifileFormat.CATEGORY_PROVIDER_MCP + OmnifileFormat.CATEGORY_MEMORY_PROMPTS +
                    OmnifileFormat.CATEGORY_THEME_UI + OmnifileFormat.CATEGORY_CHAT_HISTORY
            } else sections
            val projectsZip = if (sections.isEmpty()) {
                OmnifileFormat.zipProjectsDirectory(File(context.filesDir, "projects"))
            } else null
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                OmnifileFormat.writeOmnifile(
                    output,
                    OmnifileFormat.OmnifileMetadata(exportType = exportType, includedSections = includedSections),
                    databaseBytes,
                    projectsZip
                )
                output.toByteArray()
            }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            repository.uploadBackup(bytes, "omnichat_backup_$timestamp.omnifile").map { it.backupId }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listBackups(): Result<List<BackupMeta>> = repository.listBackups()
    suspend fun deleteBackup(backupId: String): Result<Unit> = repository.deleteBackup(backupId)

    suspend fun restoreOmnifileBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dbPath = context.getDatabasePath("ai_chat_memory_db")
        val projectsPath = File(context.filesDir, "projects")
        val hadOriginalDb = dbPath.exists()
        var stagedDb: File? = null
        var originalDb: File? = null
        var stagedProjects: File? = null
        var originalProjects: File? = null
        var restoredDb: AppDatabase? = null

        fun deleteDatabaseFiles(file: File) {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
        fun move(source: File, destination: File) {
            if (!source.renameTo(destination)) throw IOException("Unable to move ${source.name}")
        }

        try {
            val payload = ByteArrayInputStream(repository.downloadBackup(backupId).getOrThrow()).use(OmnifileFormat::readOmnifile)
            val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)
            if (payload.databaseBytes.size < 100 || !payload.databaseBytes.copyOfRange(0, sqliteHeader.size).contentEquals(sqliteHeader)) {
                throw IOException("Omnifile payload is not a SQLite database")
            }
            stagedDb = File.createTempFile("${dbPath.name}.restore-", ".tmp", dbPath.parentFile)
            FileOutputStream(stagedDb).use { output -> output.write(payload.databaseBytes); output.fd.sync() }
            AppDatabase.createDatabase(context, stagedDb.name).apply {
                try {
                    openHelper.writableDatabase
                } finally {
                    close()
                }
            }
            File(stagedDb.path + "-wal").delete()
            File(stagedDb.path + "-shm").delete()
            payload.projectsZip?.let { stagedProjects = OmnifileFormat.extractProjectsZip(it, context.filesDir) }

            if (hadOriginalDb) {
                AppDatabase.getDatabase(context).apply {
                    query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).close()
                    close()
                }
                AppDatabase.clearInstance()
                File(dbPath.path + "-wal").delete()
                File(dbPath.path + "-shm").delete()
                originalDb = File.createTempFile("${dbPath.name}.restore-backup-", ".bak", dbPath.parentFile).also {
                    if (!it.delete()) throw IOException("Unable to prepare database backup")
                    move(dbPath, it)
                }
            }
            move(stagedDb, dbPath)
            stagedDb = null

            if (stagedProjects != null) {
                if (projectsPath.exists()) {
                    originalProjects = File(context.filesDir, "projects.restore-backup-${System.nanoTime()}")
                    move(projectsPath, originalProjects!!)
                }
                move(stagedProjects!!, projectsPath)
                stagedProjects = null
            }

            restoredDb = AppDatabase.getDatabase(context)
            restoredDb!!.openHelper.writableDatabase
            restoredDb!!.close()
            restoredDb = null
            AppDatabase.clearInstance()
            originalDb?.let(::deleteDatabaseFiles)
            originalDb = null
            originalProjects?.deleteRecursively()
            originalProjects = null
            Result.success(Unit)
        } catch (e: Exception) {
            val rollbackError = runCatching {
                restoredDb?.close()
                AppDatabase.clearInstance()
                if (originalDb != null) {
                    deleteDatabaseFiles(dbPath)
                    move(originalDb!!, dbPath)
                    originalDb = null
                } else if (!hadOriginalDb) deleteDatabaseFiles(dbPath)
                if (originalProjects != null) {
                    projectsPath.deleteRecursively()
                    move(originalProjects!!, projectsPath)
                    originalProjects = null
                }
            }.exceptionOrNull()
            stagedDb?.let(::deleteDatabaseFiles)
            stagedProjects?.deleteRecursively()
            rollbackError?.let(e::addSuppressed)
            Result.failure(e)
        }
    }

    fun setWorkersUrl(url: String) = repository.setWorkersUrl(url)
    fun getWorkersUrl(): String = repository.getWorkersUrl()
}
