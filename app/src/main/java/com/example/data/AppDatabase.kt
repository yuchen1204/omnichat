package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ModelConfig::class,
        Session::class,
        Message::class,
        MemoryItem::class,
        PromptTemplate::class,
        FetchedModel::class,
        SessionSummary::class,
        McpServer::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelConfigDao(): ModelConfigDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun promptTemplateDao(): PromptTemplateDao
    abstract fun fetchedModelDao(): FetchedModelDao
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun mcpServerDao(): McpServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ── 迁移脚本 ────────────────────────────────────────────────────
        // 规则：只加列/加表，绝不删数据。
        // 如果用户从非常旧的版本升级，Room 会按顺序依次执行所有中间迁移。

        /** v1→v2：早期版本，补全 model_configs 的基础列（如果不存在则忽略错误） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 早期可能缺少 memoryModelId 列，安全地补上
                runCatching {
                    db.execSQL("ALTER TABLE model_configs ADD COLUMN memoryModelId TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /** v2→v3：补全 enableThinking / thinkingEffort 列 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    db.execSQL("ALTER TABLE model_configs ADD COLUMN enableThinking INTEGER NOT NULL DEFAULT 1")
                }
                runCatching {
                    db.execSQL("ALTER TABLE model_configs ADD COLUMN thinkingEffort TEXT NOT NULL DEFAULT 'medium'")
                }
            }
        }

        /** v3→v4：新增 fetched_models 表 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS fetched_models (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        providerId INTEGER NOT NULL,
                        modelId TEXT NOT NULL,
                        contextSize TEXT NOT NULL,
                        hasThinking INTEGER NOT NULL,
                        hasVision INTEGER NOT NULL,
                        hasToolUse INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4→v5：model_configs 加 memoryProviderId 列 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_configs ADD COLUMN memoryProviderId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5→v6：新增 session_summaries 表 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_summaries (
                        sessionId INTEGER PRIMARY KEY NOT NULL,
                        summaryText TEXT NOT NULL,
                        lastSummarizedAt INTEGER NOT NULL DEFAULT 0,
                        messageCountAtLastSummary INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** v6→v7：新增 mcp_servers 表 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_servers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        runtime TEXT NOT NULL DEFAULT 'node',
                        command TEXT NOT NULL,
                        args TEXT NOT NULL DEFAULT '[]',
                        env TEXT NOT NULL DEFAULT '{}',
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_chat_memory_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    // 兜底：如果遇到无法处理的跨版本跳跃（如从未知旧版本升级），
                    // 才执行破坏性迁移。正常升级路径不会触发这个。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
