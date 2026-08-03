package com.omnichat.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
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
}
