package com.omnichat.data

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
        McpFilePermission::class,
        // 记忆关联网络
        MemoryAssociation::class,
        // 记忆审计日志
        MemoryAuditEntry::class,
        // 云端备份记录
        CloudBackupRecord::class,
    ],
    version = 56,
    exportSchema = true,
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
    // MCP 文件权限 DAO
    abstract fun mcpFilePermissionDao(): McpFilePermissionDao
    // 记忆关联网络 DAO
    abstract fun memoryAssociationDao(): MemoryAssociationDao
    // 记忆审计日志 DAO
    abstract fun memoryAuditDao(): MemoryAuditDao
    // 云端备份记录 DAO
    abstract fun cloudBackupDao(): CloudBackupDao

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
                    VALUES (1, '#007AFF', '#FFFFFF', '#5856D6', '#F2F2F7', '#FFFFFF', 12, 1.0, 0)
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

        /** v19→v22：中间版本的工作区迁移已被移除，破坏性迁移：删除所有表，由 Room 重建 */
        private val MIGRATION_19_22 = object : Migration(19, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS memory_audit_log")
                db.execSQL("DROP TABLE IF EXISTS memory_associations")
                db.execSQL("DROP TABLE IF EXISTS mcp_file_permissions")
                db.execSQL("DROP TABLE IF EXISTS color_scheme_presets")
                db.execSQL("DROP TABLE IF EXISTS ui_settings")
                db.execSQL("DROP TABLE IF EXISTS mcp_servers")
                db.execSQL("DROP TABLE IF EXISTS session_summaries")
                db.execSQL("DROP TABLE IF EXISTS fetched_models")
                db.execSQL("DROP TABLE IF EXISTS prompt_templates")
                db.execSQL("DROP TABLE IF EXISTS memory_items")
                db.execSQL("DROP TABLE IF EXISTS messages")
                db.execSQL("DROP TABLE IF EXISTS sessions")
                db.execSQL("DROP TABLE IF EXISTS model_configs")
                db.execSQL("DROP TABLE IF EXISTS agent_presets")
                db.execSQL("DROP TABLE IF EXISTS workspace_sessions")
            }
        }

        /** v22→v23：messages 表新增 imagePath 字段（图片消息支持） */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
            }
        }

        /** v23→v25：中间版本的工作区迁移已被移除，破坏性迁移：删除所有表，由 Room 重建 */
        private val MIGRATION_23_25 = object : Migration(23, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS memory_audit_log")
                db.execSQL("DROP TABLE IF EXISTS memory_associations")
                db.execSQL("DROP TABLE IF EXISTS mcp_file_permissions")
                db.execSQL("DROP TABLE IF EXISTS color_scheme_presets")
                db.execSQL("DROP TABLE IF EXISTS ui_settings")
                db.execSQL("DROP TABLE IF EXISTS mcp_servers")
                db.execSQL("DROP TABLE IF EXISTS session_summaries")
                db.execSQL("DROP TABLE IF EXISTS fetched_models")
                db.execSQL("DROP TABLE IF EXISTS prompt_templates")
                db.execSQL("DROP TABLE IF EXISTS memory_items")
                db.execSQL("DROP TABLE IF EXISTS messages")
                db.execSQL("DROP TABLE IF EXISTS sessions")
                db.execSQL("DROP TABLE IF EXISTS model_configs")
                db.execSQL("DROP TABLE IF EXISTS agent_presets")
                db.execSQL("DROP TABLE IF EXISTS workspace_sessions")
            }
        }

        /** v25→v26：ui_settings 增加 isNodeEnabled 和 isPythonEnabled 字段 */
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN isNodeEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN isPythonEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v26→v27：ui_settings 增加 enabledMcpGroups 字段 */
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN enabledMcpGroups TEXT NOT NULL DEFAULT 'core,ui_appearance,efficiency,memory'")
            }
        }

        /** v27→v28：新增 mcp_file_permissions 表，用于记录 MCP 工具访问沙盒外文件的用户授权 */
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_file_permissions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        path TEXT NOT NULL,
                        isAllowed INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** v28→v29：memory_items 增加 lastReinforcedAt 字段，用于置信度衰减计算 */
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN lastReinforcedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE memory_items SET lastReinforcedAt = updatedAt WHERE lastReinforcedAt = 0")
            }
        }

        /** v29→v30：memory_items 增加 tags 字段，用于 LLM 生成的语义标签 */
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v30→v32：中间版本的工作区迁移已被移除，破坏性迁移：删除所有表，由 Room 重建 */
        private val MIGRATION_30_32 = object : Migration(30, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS memory_audit_log")
                db.execSQL("DROP TABLE IF EXISTS memory_associations")
                db.execSQL("DROP TABLE IF EXISTS mcp_file_permissions")
                db.execSQL("DROP TABLE IF EXISTS color_scheme_presets")
                db.execSQL("DROP TABLE IF EXISTS ui_settings")
                db.execSQL("DROP TABLE IF EXISTS mcp_servers")
                db.execSQL("DROP TABLE IF EXISTS session_summaries")
                db.execSQL("DROP TABLE IF EXISTS fetched_models")
                db.execSQL("DROP TABLE IF EXISTS prompt_templates")
                db.execSQL("DROP TABLE IF EXISTS memory_items")
                db.execSQL("DROP TABLE IF EXISTS messages")
                db.execSQL("DROP TABLE IF EXISTS sessions")
                db.execSQL("DROP TABLE IF EXISTS model_configs")
                db.execSQL("DROP TABLE IF EXISTS agent_presets")
                db.execSQL("DROP TABLE IF EXISTS workspace_sessions")
            }
        }

        /** v32→v33：ui_settings 增加 silentToolCalls 字段 */
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN silentToolCalls INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v33→v34：mcp_file_permissions 增加 permissionType 字段，区分 read/write 权限 */
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mcp_file_permissions ADD COLUMN permissionType TEXT NOT NULL DEFAULT 'read'")
            }
        }

        /** v34→v35：新增 memory_associations 表（记忆关联网络） */
        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_associations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromMemoryId INTEGER NOT NULL,
                        toMemoryId INTEGER NOT NULL,
                        relationLabel TEXT NOT NULL,
                        direction TEXT NOT NULL DEFAULT 'bidirectional',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (fromMemoryId) REFERENCES memory_items(id) ON DELETE CASCADE,
                        FOREIGN KEY (toMemoryId) REFERENCES memory_items(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_associations_fromMemoryId ON memory_associations(fromMemoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_associations_toMemoryId ON memory_associations(toMemoryId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_associations_from_to ON memory_associations(fromMemoryId, toMemoryId)")
            }
        }

        /** v35→v36：移除 mcp_servers.runtime 列和 ui_settings.isNodeEnabled/isPythonEnabled 列 */
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建 mcp_servers 表（移除 runtime 列，删除 node/python 类型的 server）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mcp_servers_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        command TEXT NOT NULL,
                        args TEXT NOT NULL DEFAULT '[]',
                        env TEXT NOT NULL DEFAULT '{}',
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO mcp_servers_new (id, name, command, args, env, isEnabled, createdAt) SELECT id, name, command, args, env, isEnabled, createdAt FROM mcp_servers WHERE runtime = 'remote_http'")
                db.execSQL("DROP TABLE mcp_servers")
                db.execSQL("ALTER TABLE mcp_servers_new RENAME TO mcp_servers")

                // 重建 ui_settings 表（移除 isNodeEnabled 和 isPythonEnabled 列）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ui_settings_new (
                        id INTEGER PRIMARY KEY NOT NULL,
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
                        sidebarBackgroundColor TEXT NOT NULL,
                        sidebarOnBackgroundColor TEXT NOT NULL,
                        sidebarActiveColor TEXT NOT NULL,
                        sidebarOnActiveColor TEXT NOT NULL,
                        cornerRadiusDp INTEGER NOT NULL,
                        spacingMultiplier REAL NOT NULL,
                        fontSizeScale REAL NOT NULL,
                        chatFontSizeScale REAL NOT NULL,
                        fontFamily TEXT NOT NULL,
                        enabledMcpGroups TEXT NOT NULL,
                        silentToolCalls INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        uiStrings TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO ui_settings_new SELECT
                        id, primaryColor, onPrimaryColor, primaryContainerColor, onPrimaryContainerColor,
                        secondaryColor, onSecondaryColor, secondaryContainerColor, onSecondaryContainerColor,
                        tertiaryColor, onTertiaryColor,
                        backgroundColor, onBackgroundColor, surfaceColor, onSurfaceColor,
                        surfaceVariantColor, onSurfaceVariantColor, outlineColor, outlineVariantColor,
                        errorColor, onErrorColor, errorContainerColor, onErrorContainerColor,
                        successColor, warningColor, infoColor, accentColor,
                        sidebarBackgroundColor, sidebarOnBackgroundColor, sidebarActiveColor, sidebarOnActiveColor,
                        cornerRadiusDp, spacingMultiplier, fontSizeScale, chatFontSizeScale, fontFamily,
                        enabledMcpGroups, silentToolCalls, updatedAt, uiStrings
                    FROM ui_settings
                """.trimIndent())
                db.execSQL("DROP TABLE ui_settings")
                db.execSQL("ALTER TABLE ui_settings_new RENAME TO ui_settings")
            }
        }

        /** v36→v37：删除所有工作区相关表 */
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS agent_presets")
                db.execSQL("DROP TABLE IF EXISTS workspace_sessions")
                db.execSQL("DROP TABLE IF EXISTS workspace_teams")
                db.execSQL("DROP TABLE IF EXISTS agent_instances")
                db.execSQL("DROP TABLE IF EXISTS mailbox_messages")
                db.execSQL("DROP TABLE IF EXISTS agent_state_snapshots")
                db.execSQL("DROP TABLE IF EXISTS workspace_messages")
                db.execSQL("DROP TABLE IF EXISTS team_tasks")
                db.execSQL("DROP TABLE IF EXISTS agent_definitions")
            }
        }

        /** v37→v38：记忆系统增强 — 添加嵌入向量存储、嵌入模型配置、FTS 全文索引、审计日志 */
        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. memory_items 加 embedding 列（JSON 序列化的 float 数组）
                db.execSQL("ALTER TABLE memory_items ADD COLUMN embedding TEXT NOT NULL DEFAULT ''")

                // 2. model_configs 加 embeddingModelId 列
                db.execSQL("ALTER TABLE model_configs ADD COLUMN embeddingModelId TEXT NOT NULL DEFAULT ''")

                // 3. FTS 全文索引（FTS3，Android 内置 SQLite 不支持 FTS5；不用 content= 语法，通过 triggers 手动同步）
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_items_fts USING fts3(content, tags)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS memory_items_ai AFTER INSERT ON memory_items BEGIN INSERT INTO memory_items_fts(rowid, content, tags) VALUES (new.id, new.content, new.tags); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS memory_items_ad AFTER DELETE ON memory_items BEGIN INSERT INTO memory_items_fts(memory_items_fts, rowid, content, tags) VALUES ('delete', old.id, old.content, old.tags); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS memory_items_au AFTER UPDATE ON memory_items BEGIN INSERT INTO memory_items_fts(memory_items_fts, rowid, content, tags) VALUES ('delete', old.id, old.content, old.tags); INSERT INTO memory_items_fts(rowid, content, tags) VALUES (new.id, new.content, new.tags); END")
                // 回填已有数据到 FTS 索引
                db.execSQL("INSERT INTO memory_items_fts(rowid, content, tags) SELECT id, content, tags FROM memory_items")

                // 4. 审计日志表
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, memoryId INTEGER NOT NULL, opType TEXT NOT NULL, contentSnapshot TEXT NOT NULL, triggerReason TEXT NOT NULL, confidenceBefore INTEGER, confidenceAfter INTEGER, timestamp INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_audit_log_memoryId ON memory_audit_log(memoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_audit_log_timestamp ON memory_audit_log(timestamp)")
            }
        }

        /** v38→v39：新增 agent_configs 表（subAgent 模型配置） */
        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_configs (
                        agentType TEXT PRIMARY KEY NOT NULL,
                        providerId INTEGER NOT NULL,
                        modelId TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        maxConcurrency INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v39→v40：时间提醒记忆 — memory_items 加 dueDate / reminded 列 */
        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN dueDate TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN reminded INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v40→v41：新增 cloud_backups 表（云端备份记录） */
        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cloud_backups (
                        backupId TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        userId TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建 messages 表：重命名 imagePath → imagePaths（兼容 SQLite < 3.25）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS messages_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        toolCallId TEXT,
                        toolCallsJson TEXT,
                        timestamp INTEGER NOT NULL,
                        imagePaths TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)")
                db.execSQL("""
                    INSERT INTO messages_new (id, sessionId, role, content, toolCallId, toolCallsJson, timestamp, imagePaths)
                    SELECT id, sessionId, role, content, toolCallId, toolCallsJson, timestamp, imagePath FROM messages
                """.trimIndent())
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
            }
        }

        /** v42→v43：silentToolCalls(Boolean) → silentToolGroups(String)（兼容 SQLite < 3.35） */
        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建 ui_settings 表：移除 silentToolCalls，添加 silentToolGroups
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ui_settings_new (
                        id INTEGER PRIMARY KEY NOT NULL,
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
                        sidebarBackgroundColor TEXT NOT NULL,
                        sidebarOnBackgroundColor TEXT NOT NULL,
                        sidebarActiveColor TEXT NOT NULL,
                        sidebarOnActiveColor TEXT NOT NULL,
                        cornerRadiusDp INTEGER NOT NULL,
                        spacingMultiplier REAL NOT NULL,
                        fontSizeScale REAL NOT NULL,
                        chatFontSizeScale REAL NOT NULL,
                        fontFamily TEXT NOT NULL,
                        enabledMcpGroups TEXT NOT NULL,
                        silentToolGroups TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL,
                        uiStrings TEXT NOT NULL
                    )
                """.trimIndent())
                // 旧 silentToolCalls=true → 新 silentToolGroups="*"（静默所有）
                db.execSQL("""
                    INSERT INTO ui_settings_new (
                        id, primaryColor, onPrimaryColor, primaryContainerColor, onPrimaryContainerColor,
                        secondaryColor, onSecondaryColor, secondaryContainerColor, onSecondaryContainerColor,
                        tertiaryColor, onTertiaryColor,
                        backgroundColor, onBackgroundColor, surfaceColor, onSurfaceColor,
                        surfaceVariantColor, onSurfaceVariantColor, outlineColor, outlineVariantColor,
                        errorColor, onErrorColor, errorContainerColor, onErrorContainerColor,
                        successColor, warningColor, infoColor, accentColor,
                        sidebarBackgroundColor, sidebarOnBackgroundColor, sidebarActiveColor, sidebarOnActiveColor,
                        cornerRadiusDp, spacingMultiplier, fontSizeScale, chatFontSizeScale, fontFamily,
                        enabledMcpGroups, silentToolGroups, updatedAt, uiStrings
                    )
                    SELECT
                        id, primaryColor, onPrimaryColor, primaryContainerColor, onPrimaryContainerColor,
                        secondaryColor, onSecondaryColor, secondaryContainerColor, onSecondaryContainerColor,
                        tertiaryColor, onTertiaryColor,
                        backgroundColor, onBackgroundColor, surfaceColor, onSurfaceColor,
                        surfaceVariantColor, onSurfaceVariantColor, outlineColor, outlineVariantColor,
                        errorColor, onErrorColor, errorContainerColor, onErrorContainerColor,
                        successColor, warningColor, infoColor, accentColor,
                        sidebarBackgroundColor, sidebarOnBackgroundColor, sidebarActiveColor, sidebarOnActiveColor,
                        cornerRadiusDp, spacingMultiplier, fontSizeScale, chatFontSizeScale, fontFamily,
                        enabledMcpGroups,
                        CASE WHEN silentToolCalls = 1 THEN '*' ELSE '' END AS silentToolGroups,
                        updatedAt, uiStrings
                    FROM ui_settings
                """.trimIndent())
                db.execSQL("DROP TABLE ui_settings")
                db.execSQL("ALTER TABLE ui_settings_new RENAME TO ui_settings")
            }
        }

        /** v43→v44：新增 agent_tasks 表（subAgent 任务状态持久化） */
        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_tasks (
                        taskId TEXT NOT NULL PRIMARY KEY,
                        sessionId INTEGER NOT NULL,
                        agentType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        taskDescription TEXT NOT NULL,
                        result TEXT,
                        summary TEXT,
                        error TEXT,
                        startedAt INTEGER,
                        completedAt INTEGER,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_sessionId ON agent_tasks(sessionId)")
            }
        }

        /** v44→v45：add agentMode column to sessions */
        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN agentMode TEXT NOT NULL DEFAULT 'GENERAL'")
            }
        }

        /** v45->v46: add hideYoloWarning column to ui_settings */
        private val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN hideYoloWarning INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v46→v47: add cloudBackupFrequency and cloudBackupSections columns to ui_settings */
        private val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN cloudBackupFrequency TEXT NOT NULL DEFAULT 'H6'")
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN cloudBackupSections TEXT NOT NULL DEFAULT '[\"providers\",\"mcpServers\",\"mcpFilePermissions\",\"memories\",\"promptTemplates\",\"uiSettings\",\"colorSchemePresets\"]'")
            }
        }

        /** v48→v49: rename hideYoloWarning → hideAgentModeWarning in ui_settings */
        private val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings RENAME COLUMN hideYoloWarning TO hideAgentModeWarning")
            }
        }

        /** v49→v50: add maxToolCalls column to ui_settings */
        private val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN maxToolCalls INTEGER NOT NULL DEFAULT 10")
            }
        }

        /** v50→v51: 移除 SubAgent 相关 — 删除 agent_configs/agent_tasks 表，移除 agentMode/hideAgentModeWarning/maxToolCalls 列 */
        private val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 删除 agent 相关表
                db.execSQL("DROP TABLE IF EXISTS agent_configs")
                db.execSQL("DROP TABLE IF EXISTS agent_tasks")

                // 重建 sessions 表：移除 agentMode 列
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sessions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        thinkingEffort TEXT NOT NULL DEFAULT 'low'
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO sessions_new (id, title, createdAt, thinkingEffort) SELECT id, title, createdAt, thinkingEffort FROM sessions")
                db.execSQL("DROP TABLE sessions")
                db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")

                // 重建 ui_settings 表：移除 hideAgentModeWarning 和 maxToolCalls 列
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ui_settings_new (
                        id INTEGER PRIMARY KEY NOT NULL,
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
                        sidebarBackgroundColor TEXT NOT NULL,
                        sidebarOnBackgroundColor TEXT NOT NULL,
                        sidebarActiveColor TEXT NOT NULL,
                        sidebarOnActiveColor TEXT NOT NULL,
                        cornerRadiusDp INTEGER NOT NULL,
                        spacingMultiplier REAL NOT NULL,
                        fontSizeScale REAL NOT NULL,
                        chatFontSizeScale REAL NOT NULL,
                        fontFamily TEXT NOT NULL,
                        enabledMcpGroups TEXT NOT NULL,
                        silentToolGroups TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL,
                        uiStrings TEXT NOT NULL,
                        cloudBackupFrequency TEXT NOT NULL DEFAULT 'H6',
                        cloudBackupSections TEXT NOT NULL DEFAULT '[]'
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO ui_settings_new (
                        id, primaryColor, onPrimaryColor, primaryContainerColor, onPrimaryContainerColor,
                        secondaryColor, onSecondaryColor, secondaryContainerColor, onSecondaryContainerColor,
                        tertiaryColor, onTertiaryColor,
                        backgroundColor, onBackgroundColor, surfaceColor, onSurfaceColor,
                        surfaceVariantColor, onSurfaceVariantColor, outlineColor, outlineVariantColor,
                        errorColor, onErrorColor, errorContainerColor, onErrorContainerColor,
                        successColor, warningColor, infoColor, accentColor,
                        sidebarBackgroundColor, sidebarOnBackgroundColor, sidebarActiveColor, sidebarOnActiveColor,
                        cornerRadiusDp, spacingMultiplier, fontSizeScale, chatFontSizeScale, fontFamily,
                        enabledMcpGroups, silentToolGroups, updatedAt, uiStrings,
                        cloudBackupFrequency, cloudBackupSections
                    )
                    SELECT
                        id, primaryColor, onPrimaryColor, primaryContainerColor, onPrimaryContainerColor,
                        secondaryColor, onSecondaryColor, secondaryContainerColor, onSecondaryContainerColor,
                        tertiaryColor, onTertiaryColor,
                        backgroundColor, onBackgroundColor, surfaceColor, onSurfaceColor,
                        surfaceVariantColor, onSurfaceVariantColor, outlineColor, outlineVariantColor,
                        errorColor, onErrorColor, errorContainerColor, onErrorContainerColor,
                        successColor, warningColor, infoColor, accentColor,
                        sidebarBackgroundColor, sidebarOnBackgroundColor, sidebarActiveColor, sidebarOnActiveColor,
                        cornerRadiusDp, spacingMultiplier, fontSizeScale, chatFontSizeScale, fontFamily,
                        enabledMcpGroups, silentToolGroups, updatedAt, uiStrings,
                        cloudBackupFrequency, cloudBackupSections
                    FROM ui_settings
                """.trimIndent())
                db.execSQL("DROP TABLE ui_settings")
                db.execSQL("ALTER TABLE ui_settings_new RENAME TO ui_settings")
            }
        }

        /** v47→v48: add thinkingEffort column to sessions */
        private val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinkingEffort TEXT NOT NULL DEFAULT 'low'")
            }
        }

        /** v51→v52：ui_settings 增加 agentMode 字段（智能体模式开关） */
        private val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN agentMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v52→v53: no-op，避免设备已有 v53 时降级触发 destructive migration */
        private val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // no-op: version bump only
            }
        }

        /** v53→v54: 为 ui_settings 添加 topbarSubtitleColor 字段 */
        private val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN topbarSubtitleColor TEXT NOT NULL DEFAULT '#636366'")
            }
        }

        /** v54→v55: 为 ui_settings 添加 sidebarExpanded 字段 */
        private val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN sidebarExpanded INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v55→v56: 记忆系统增强 — embeddingModelId、lastDecayedAt、关联唯一索引 */
        private val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. memory_items 添加 embeddingModelId 列
                db.execSQL("ALTER TABLE memory_items ADD COLUMN embeddingModelId TEXT NOT NULL DEFAULT ''")
                // 2. memory_items 添加 lastDecayedAt 列（与 lastReinforcedAt 语义分离）
                db.execSQL("ALTER TABLE memory_items ADD COLUMN lastDecayedAt INTEGER NOT NULL DEFAULT 0")
                // 回填 lastDecayedAt = lastReinforcedAt（兼容旧数据）
                db.execSQL("UPDATE memory_items SET lastDecayedAt = lastReinforcedAt WHERE lastDecayedAt = 0")
                // 3. 为 embeddingModelId 创建索引
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_embeddingModelId ON memory_items(embeddingModelId)")
                // 4. memory_associations 添加唯一索引（防止重复关联边）
                // 需先删除可能的重复记录，再创建唯一索引
                db.execSQL("""
                    DELETE FROM memory_associations WHERE id NOT IN (
                        SELECT MIN(id) FROM memory_associations
                        GROUP BY fromMemoryId, toMemoryId, relationLabel
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_associations_unique ON memory_associations(fromMemoryId, toMemoryId, relationLabel)")
            }
        }

        /**
         * 清除单例实例（用于数据库恢复后重新初始化）。
         */
        fun clearInstance() {
            INSTANCE = null
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_chat_memory_db"
                )
                    .addMigrations(
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
                        MIGRATION_18_19,
                        MIGRATION_19_22,
                        MIGRATION_22_23,
                        MIGRATION_23_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37,
                        MIGRATION_37_38,
                        MIGRATION_38_39,
                        MIGRATION_39_40,
                        MIGRATION_40_41,
                        MIGRATION_41_42,
                        MIGRATION_42_43,
                        MIGRATION_43_44,
                        MIGRATION_44_45,
                        MIGRATION_45_46,
                        MIGRATION_46_47,
                        MIGRATION_47_48,
                        MIGRATION_48_49,
                        MIGRATION_49_50,
                        MIGRATION_50_51,
                        MIGRATION_51_52,
                        MIGRATION_52_53,
                        MIGRATION_53_54,
                        MIGRATION_54_55,
                        MIGRATION_55_56
                    )
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    // 兜底：v1-v3 使用破坏性迁移（非常旧的安装版本）。
                    // v4+ 均有显式迁移脚本（含中间版本的破坏性迁移 MIGRATION_19_22/23_25/30_32）。
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, *(1..3).toList().toIntArray())
                    // 全局兜底：恢复备份等场景下数据库版本可能不匹配，允许 destructive migration
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
