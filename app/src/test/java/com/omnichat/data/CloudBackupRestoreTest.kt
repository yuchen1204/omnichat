package com.omnichat.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.omnichat.cloud.BindTotpResponse
import com.omnichat.cloud.CloudBackupApi
import com.omnichat.cloud.CloudBackupManager
import com.omnichat.cloud.ListResponse
import com.omnichat.cloud.RecoverResponse
import com.omnichat.cloud.RecoverRequest
import com.omnichat.cloud.UploadRequest
import com.omnichat.cloud.UploadResponse
import com.omnichat.cloud.VerifyRequest
import com.omnichat.cloud.VerifyResponse
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CloudBackupRestoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        AppDatabase.clearInstance()
        context.deleteDatabase(DATABASE_NAME)
        File(context.getDatabasePath(DATABASE_NAME).path + "-wal").delete()
        File(context.getDatabasePath(DATABASE_NAME).path + "-shm").delete()
        context.getSharedPreferences(CLOUD_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun validOmnifileRestoresSessionThroughCloudManager() = runBlocking {
        val sessionId = 739L
        val sessionTitle = "restored from omnifile"
        val backupBytes = createCurrentDatabaseBackup(sessionId, sessionTitle)

        val result = restore(omnifileWith(backupBytes))

        assertTrue(result.isSuccess)
        val restoredDatabase = AppDatabase.getDatabase(context)
        val restored = restoredDatabase.sessionDao().getSessionById(sessionId)
        restoredDatabase.close()
        AppDatabase.clearInstance()
        assertNotNull(restored)
        assertTrue(restored?.title == sessionTitle)
    }

    @Test
    fun legacyOmnifile1RestoresSessionThroughCloudManager() = runBlocking {
        val sessionId = 1L
        val sessionTitle = "restored from legacy omnifile"
        val databaseBytes = createCurrentDatabaseBackup(sessionId, sessionTitle)

        val result = restore(legacyOmnifile1With(databaseBytes))

        assertTrue(result.isSuccess)
        val restoredDatabase = AppDatabase.getDatabase(context)
        val restored = restoredDatabase.sessionDao().getSessionById(sessionId)
        restoredDatabase.close()
        AppDatabase.clearInstance()
        assertNotNull(restored)
        assertTrue(restored?.title == sessionTitle)
    }

    @Test
    fun version59OmnifileRestoresSessionAndMigratesToCurrentVersion() = runBlocking {
        val sessionId = 590L
        val sessionTitle = "restored from v59 omnifile"
        val backupBytes = createVersion59DatabaseBackup(sessionId, sessionTitle)

        val result = restore(omnifileWith(backupBytes))

        assertTrue(result.isSuccess)
        val restoredDatabase = AppDatabase.getDatabase(context)
        val restored = restoredDatabase.sessionDao().getSessionById(sessionId)
        val userVersion = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            restoredDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA user_version")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
        restoredDatabase.close()
        AppDatabase.clearInstance()
        assertNotNull(restored)
        assertTrue(restored?.title == sessionTitle)
        assertEquals(CURRENT_DATABASE_VERSION, userVersion)
    }

    @Test
    fun corruptDatabasePayloadNeverReportsRestoreSuccess() = runBlocking {
        val result = restore(omnifileWith(byteArrayOf(0x01, 0x02, 0x03, 0x04)))

        assertFalse("Corrupt database payload must fail restore", result.isSuccess)
        assertFalse(context.getDatabasePath(DATABASE_NAME).exists())
    }

    @Test
    fun incompatibleDatabaseSchemaNeverReportsRestoreSuccess() = runBlocking {
        val incompatibleDatabase = File(context.cacheDir, "incompatible_${System.nanoTime()}.db")
        try {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(incompatibleDatabase, null)
            try {
                db.execSQL("CREATE TABLE incompatible_data (id INTEGER PRIMARY KEY NOT NULL)")
                db.version = 60
            } finally {
                db.close()
            }

            val result = restore(omnifileWith(incompatibleDatabase.readBytes()))

            assertFalse("Room schema validation failure must fail restore", result.isSuccess)
            assertFalse(context.getDatabasePath(DATABASE_NAME).exists())
        } finally {
            incompatibleDatabase.delete()
        }
    }

    private suspend fun createCurrentDatabaseBackup(sessionId: Long, title: String): ByteArray {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppDatabase.clearInstance()
            context.deleteDatabase(DATABASE_NAME)
            val database = AppDatabase.getDatabase(context)
            database.sessionDao().insertSession(
                Session(id = sessionId, title = title, createdAt = 1_700_000_000_000L)
            )
            database.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).close()
            database.close()
            AppDatabase.clearInstance()
            context.getDatabasePath(DATABASE_NAME).readBytes()
        }
    }

    private suspend fun createVersion59DatabaseBackup(sessionId: Long, title: String): ByteArray {
        createCurrentDatabaseBackup(sessionId, title)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val database = android.database.sqlite.SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                database.beginTransaction()
                database.execSQL("DROP INDEX IF EXISTS index_messages_sessionId")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_sessionId_timestamp ON messages(sessionId, timestamp)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_projectId ON sessions(projectId)")

                database.execSQL(
                    "CREATE TABLE projects_v59 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                database.execSQL(
                    "INSERT INTO projects_v59 (id, name, description, createdAt, updatedAt) SELECT id, name, description, createdAt, updatedAt FROM projects"
                )
                database.execSQL("DROP TABLE projects")
                database.execSQL("ALTER TABLE projects_v59 RENAME TO projects")

                database.execSQL(
                    "CREATE TABLE project_knowledge_v59 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, projectId INTEGER NOT NULL, fileName TEXT NOT NULL, fileType TEXT NOT NULL, fileSize INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )
                database.execSQL(
                    "INSERT INTO project_knowledge_v59 (id, projectId, fileName, fileType, fileSize, createdAt) SELECT id, projectId, fileName, fileType, fileSize, createdAt FROM project_knowledge"
                )
                database.execSQL("DROP TABLE project_knowledge")
                database.execSQL("ALTER TABLE project_knowledge_v59 RENAME TO project_knowledge")
                database.execSQL(
                    "CREATE INDEX index_project_knowledge_projectId ON project_knowledge(projectId)"
                )
                database.execSQL(
                    "UPDATE room_master_table SET identity_hash = '$VERSION_59_IDENTITY_HASH' WHERE id = 42"
                )
                database.version = LEGACY_DATABASE_VERSION
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
                database.close()
            }
            databaseFile.readBytes()
        }
    }

    private suspend fun restore(backup: ByteArray): Result<Unit> {
        AppDatabase.clearInstance()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(CLOUD_BACKUP_PREFS, Context.MODE_PRIVATE).edit()
            .putString("user_id", "test-user")
            .putString("session_token", "test-token")
            .commit()

        return CloudBackupManager(context).also { manager ->
            val repositoryField = manager.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            val repository = repositoryField.get(manager)
            repository.javaClass.getDeclaredField("api").apply {
                isAccessible = true
                set(repository, DownloadingApi(backup))
            }
        }.restoreOmnifileBackup("backup-id")
    }

    private fun omnifileWith(databaseBytes: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { output ->
            OmnifileFormat.writeOmnifile(
                output,
                OmnifileFormat.OmnifileMetadata(
                    exportType = OmnifileFormat.ExportType.FULL,
                    includedSections = OmnifileFormat.CATEGORY_CHAT_HISTORY,
                    databaseLength = databaseBytes.size.toLong(),
                    projectsZipLength = 0
                ),
                databaseBytes,
                projectsZip = null
            )
            output.toByteArray()
        }
    }

    private fun legacyOmnifile1With(databaseBytes: ByteArray): ByteArray {
        val metadataJson = JSONObject().apply {
            put("version", 1)
            put("exportedAt", 1_700_000_000_000L)
            put("exportType", "full")
            put("includedSections", JSONArray(OmnifileFormat.CATEGORY_CHAT_HISTORY))
            put("deviceInfo", "legacy test device")
            put("appVersion", "1.0.0")
        }.toString().toByteArray(Charsets.UTF_8)

        return ByteArrayOutputStream().use { output ->
            output.write("OMNIFILE1\n".toByteArray(Charsets.US_ASCII))
            output.write(
                java.nio.ByteBuffer.allocate(Int.SIZE_BYTES)
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                    .putInt(metadataJson.size)
                    .array()
            )
            output.write(metadataJson)
            output.write(databaseBytes)
            output.toByteArray()
        }
    }

    private class DownloadingApi(private val backup: ByteArray) : CloudBackupApi {
        override suspend fun bindTotp(): Response<BindTotpResponse> = unsupported()
        override suspend fun verify(request: VerifyRequest): Response<VerifyResponse> = unsupported()
        override suspend fun recover(request: RecoverRequest): Response<RecoverResponse> = unsupported()
        override suspend fun upload(token: String, request: UploadRequest): Response<UploadResponse> = unsupported()
        override suspend fun list(token: String): Response<ListResponse> = unsupported()
        override suspend fun delete(token: String, backupId: String): Response<Unit> = unsupported()

        override suspend fun download(token: String, backupId: String): Response<ResponseBody> {
            return Response.success(backup.toResponseBody())
        }

        private fun <T> unsupported(): T = throw UnsupportedOperationException("Not used by restore")
    }

    private companion object {
        const val DATABASE_NAME = "ai_chat_memory_db"
        const val CLOUD_BACKUP_PREFS = "cloud_backup"
        const val LEGACY_DATABASE_VERSION = 59
        const val CURRENT_DATABASE_VERSION = 61
        const val VERSION_59_IDENTITY_HASH = "3df3ec1dfbef399df0fa3dde47debbe7"
    }
}
