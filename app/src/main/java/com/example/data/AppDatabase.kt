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
        UISettings::class,
        ColorSchemePreset::class,
    ],
    version = 19,
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
    abstract fun uiSettingsDao(): UISettingsDao
    abstract fun colorSchemePresetDao(): ColorSchemePresetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ── 迁移脚本 ────────────────────────────────────────────────────
        // 规则：只加列/加表，绝不删数据。
        // 如果用户从非常旧的版本升级，Room 会按顺序依次执行所有中间迁移。

        /** v11→v12：增加索引 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_confidence ON memory_items(confidence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_updatedAt ON memory_items(updatedAt)")
            }
        }

        /** v9→v10：messages 加 toolCallsJson 列 */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallsJson TEXT")
            }
        }

        /** v10→v11：model_configs 加 customHeaders 列 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_configs ADD COLUMN customHeaders TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /** v8→v9：messages 加 toolCallId 列 */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT")
            }
        }

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

        /** v7→v8：memory_items 加 confidence / updatedAt / pinned 列 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN confidence INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                // 把现有条目的 updatedAt 初始化为 createdAt
                db.execSQL("UPDATE memory_items SET updatedAt = createdAt")
            }
        }

        /** v12→v13：新增 ui_settings 表 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ui_settings (
                        id INTEGER PRIMARY KEY NOT NULL,
                        primaryColor TEXT NOT NULL,
                        onPrimaryColor TEXT NOT NULL,
                        secondaryColor TEXT NOT NULL,
                        backgroundColor TEXT NOT NULL,
                        surfaceColor TEXT NOT NULL,
                        cornerRadiusDp INTEGER NOT NULL,
                        spacingMultiplier REAL NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 插入默认行
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO ui_settings 
                    (id, primaryColor, onPrimaryColor, secondaryColor, backgroundColor, surfaceColor, cornerRadiusDp, spacingMultiplier, updatedAt)
                    VALUES (1, '#6750A4', '#FFFFFF', '#625B71', '#FFFBFE', '#FFFBFE', 12, 1.0, 0)
                    """.trimIndent()
                )
            }
        }

        /** v13→v14：ui_settings 增加 error/success/warning 颜色 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN errorColor TEXT NOT NULL DEFAULT '#B00020'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN successColor TEXT NOT NULL DEFAULT '#4CAF50'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN warningColor TEXT NOT NULL DEFAULT '#FF9800'")
            }
        }

        /** v14→v15：扩展 ui_settings，覆盖完整 Material 3 调色板 + 状态色，让 AI 可以调整全部配色 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 主色容器
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN primaryContainerColor TEXT NOT NULL DEFAULT '#EADDFF'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onPrimaryContainerColor TEXT NOT NULL DEFAULT '#21005D'")
                // 次色 + 容器
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onSecondaryColor TEXT NOT NULL DEFAULT '#FFFFFF'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN secondaryContainerColor TEXT NOT NULL DEFAULT '#E8DEF8'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onSecondaryContainerColor TEXT NOT NULL DEFAULT '#1D192B'")
                // 第三色
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN tertiaryColor TEXT NOT NULL DEFAULT '#7D5260'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onTertiaryColor TEXT NOT NULL DEFAULT '#FFFFFF'")
                // 表面 / 文字
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onBackgroundColor TEXT NOT NULL DEFAULT '#1C1B1F'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onSurfaceColor TEXT NOT NULL DEFAULT '#1C1B1F'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN surfaceVariantColor TEXT NOT NULL DEFAULT '#E7E0EC'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onSurfaceVariantColor TEXT NOT NULL DEFAULT '#49454F'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN outlineColor TEXT NOT NULL DEFAULT '#79747E'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN outlineVariantColor TEXT NOT NULL DEFAULT '#CAC4D0'")
                // 错误容器
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onErrorColor TEXT NOT NULL DEFAULT '#FFFFFF'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN errorContainerColor TEXT NOT NULL DEFAULT '#F9DEDC'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN onErrorContainerColor TEXT NOT NULL DEFAULT '#410E0B'")
                // 信息 / 强调
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN infoColor TEXT NOT NULL DEFAULT '#007AFF'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN accentColor TEXT NOT NULL DEFAULT '#FF9500'")
                // success 默认值更新为 iOS 风格的绿色（从 #4CAF50 -> #34C759 的迁移仅更新默认值，不强制覆盖已有数据）
            }
        }

        /** v15→v16：新增 color_scheme_presets 表（配色方案预设，最多 5 条） */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS color_scheme_presets (
                        schemeId TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        primaryColor TEXT NOT NULL,
                        onPrimaryColor TEXT NOT NULL,
                        primaryContainerColor TEXT NOT NULL,
                        onPrimaryContainerColor TEXT NOT NULL,
                        secondaryColor TEXT NOT NULL,
                        onSecondaryColor TEXT NOT NULL,
                        secondaryContainerColor TEXT NOT NULL,
                        onSecondaryContainerColor TEXT NOT NULL,
                        tertiaryColor TEXT NOT NULL,
                        onTertiaryColor TEXT NOT NULL,
                        backgroundColor TEXT NOT NULL,
                        onBackgroundColor TEXT NOT NULL,
                        surfaceColor TEXT NOT NULL,
                        onSurfaceColor TEXT NOT NULL,
                        surfaceVariantColor TEXT NOT NULL,
                        onSurfaceVariantColor TEXT NOT NULL,
                        outlineColor TEXT NOT NULL,
                        outlineVariantColor TEXT NOT NULL,
                        errorColor TEXT NOT NULL,
                        onErrorColor TEXT NOT NULL,
                        errorContainerColor TEXT NOT NULL,
                        onErrorContainerColor TEXT NOT NULL,
                        successColor TEXT NOT NULL,
                        warningColor TEXT NOT NULL,
                        infoColor TEXT NOT NULL,
                        accentColor TEXT NOT NULL,
                        cornerRadiusDp INTEGER NOT NULL,
                        spacingMultiplier REAL NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v16→v17：ui_settings 和 color_scheme_presets 增加 sidebarBackgroundColor / sidebarOnBackgroundColor / sidebarActiveColor / sidebarOnActiveColor */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN sidebarBackgroundColor TEXT NOT NULL DEFAULT '#FFFBFE'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN sidebarOnBackgroundColor TEXT NOT NULL DEFAULT '#1C1B1F'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN sidebarActiveColor TEXT NOT NULL DEFAULT '#EADDFF'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN sidebarOnActiveColor TEXT NOT NULL DEFAULT '#21005D'")
                
                db.execSQL("ALTER TABLE color_scheme_presets ADD COLUMN sidebarBackgroundColor TEXT NOT NULL DEFAULT '#FFFBFE'")
                db.execSQL("ALTER TABLE color_scheme_presets ADD COLUMN sidebarOnBackgroundColor TEXT NOT NULL DEFAULT '#1C1B1F'")
                db.execSQL("ALTER TABLE color_scheme_presets ADD COLUMN sidebarActiveColor TEXT NOT NULL DEFAULT '#EADDFF'")
                db.execSQL("ALTER TABLE color_scheme_presets ADD COLUMN sidebarOnActiveColor TEXT NOT NULL DEFAULT '#21005D'")
            }
        }

        /** v17→v18：ui_settings 增加字体设置字段 */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN fontSizeScale REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN chatFontSizeScale REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN fontFamily TEXT NOT NULL DEFAULT 'default'")
            }
        }

        /** v18→v19：ui_settings 增加 uiStrings 字段（AI 可调整的 UI 文字标签，JSON 字符串） */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN uiStrings TEXT NOT NULL DEFAULT '{}'")
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
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19
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
