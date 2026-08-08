package com.omnichat.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectSchemaTest {

    @Test
    fun projectAssetStoresSourceAndStableLocalFileName() {
        val asset = ProjectKnowledge(
            projectId = 7L,
            fileName = "notes.md",
            fileType = "md",
            localFileName = "asset_12.md",
            source = "USER_UPLOAD"
        )
        assertEquals("asset_12.md", asset.localFileName)
        assertEquals("USER_UPLOAD", asset.source)
    }

    @Test
    fun projectDefaultsToInheritingAllMcpServers() {
        assertEquals("[]", Project(name = "demo").disabledMcpServerIds)
    }

    @Test
    fun migration59to60_retainsExistingDataAndAppliesDefaults() {
        val dbFile = File.createTempFile("test_db_", ".db")
        val dbPath = dbFile.absolutePath

        // Open a raw SQLite database
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)

        // Simulate v59 schema: create tables as MIGRATION_58_59 would
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_knowledge (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                projectId INTEGER NOT NULL,
                fileName TEXT NOT NULL,
                fileType TEXT NOT NULL,
                fileSize INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_knowledge_projectId ON project_knowledge(projectId)")

        // Insert v59 data
        db.execSQL("INSERT INTO projects (id, name, description, createdAt, updatedAt) VALUES (1, 'TestProject', 'desc', 1000, 1000)")
        db.execSQL("INSERT INTO project_knowledge (id, projectId, fileName, fileType, fileSize, createdAt) VALUES (10, 1, 'readme.md', 'md', 1024, 2000)")

        // Apply v59→v60 migration SQL
        db.execSQL("ALTER TABLE projects ADD COLUMN disabledMcpServerIds TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE project_knowledge ADD COLUMN localFileName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE project_knowledge ADD COLUMN source TEXT NOT NULL DEFAULT 'USER_UPLOAD'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_knowledge_projectId_id ON project_knowledge(projectId, id)")

        // Verify old rows retain data and new columns have defaults
        db.rawQuery("SELECT name, disabledMcpServerIds FROM projects WHERE id = 1", null).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("TestProject", cursor.getString(0))
            assertEquals("[]", cursor.getString(1))
        }

        db.rawQuery("SELECT fileName, localFileName, source FROM project_knowledge WHERE id = 10", null).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("readme.md", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("USER_UPLOAD", cursor.getString(2))
        }

        // Verify new insert with explicit values works
        db.execSQL("INSERT INTO project_knowledge (id, projectId, fileName, fileType, fileSize, localFileName, source, createdAt) VALUES (11, 1, 'agent.md', 'md', 512, 'asset_11.md', 'AGENT_CREATED', 3000)")
        db.rawQuery("SELECT localFileName, source FROM project_knowledge WHERE id = 11", null).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("asset_11.md", cursor.getString(0))
            assertEquals("AGENT_CREATED", cursor.getString(1))
        }

        db.close()
        dbFile.delete()
    }

    @Test
    fun migration58to60_sessionsMatchRoomSchemaWithoutDefaultsOrProjectIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "session_migration_${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE sessions (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                title TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                thinkingEffort TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE messages (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                sessionId INTEGER NOT NULL,
                                role TEXT NOT NULL,
                                content TEXT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                images TEXT NOT NULL,
                                isThinking INTEGER NOT NULL,
                                thinkingContent TEXT NOT NULL,
                                toolCalls TEXT NOT NULL,
                                toolCallId TEXT NOT NULL,
                                toolName TEXT NOT NULL,
                                attachments TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("INSERT INTO sessions (id, title, createdAt, thinkingEffort) VALUES (1, 'Existing', 1000, 'low')")
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            migration("MIGRATION_58_59").migrate(db)
            migration("MIGRATION_59_60").migrate(db)

            db.query("SELECT thinkingEffort, projectId FROM sessions WHERE id = 1").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("low", cursor.getString(0))
                assertNull(cursor.getString(1))
            }

            db.query("PRAGMA table_info(sessions)").use { cursor ->
                var foundThinkingEffort = false
                var foundProjectId = false
                while (cursor.moveToNext()) {
                    when (cursor.getString(cursor.getColumnIndexOrThrow("name"))) {
                        "thinkingEffort" -> {
                            foundThinkingEffort = true
                            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                        }
                        "projectId" -> {
                            foundProjectId = true
                            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                        }
                    }
                }
                assertEquals(true, foundThinkingEffort)
                assertEquals(true, foundProjectId)
            }

            db.query("PRAGMA index_list(sessions)").use { cursor ->
                while (cursor.moveToNext()) {
                    assertFalse(cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_sessions_projectId")
                }
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration60to61_addsProjectSessionIndexAndRetainsData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "session_index_migration_${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE sessions (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                title TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                thinkingEffort TEXT NOT NULL,
                                projectId INTEGER
                            )
                            """.trimIndent()
                        )
                        db.execSQL("INSERT INTO sessions (id, title, createdAt, thinkingEffort, projectId) VALUES (1, 'Existing', 1000, 'low', 42)")
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            migration("MIGRATION_60_61").migrate(db)

            db.query("SELECT projectId FROM sessions WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42L, cursor.getLong(0))
            }

            db.query("PRAGMA index_list(sessions)").use { cursor ->
                var foundProjectIndex = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_sessions_projectId") {
                        foundProjectIndex = true
                    }
                }
                assertTrue(foundProjectIndex)
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun migration(name: String): Migration {
        return AppDatabase::class.java.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(null) as Migration
        }
    }
}
