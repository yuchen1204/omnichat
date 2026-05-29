package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room TypeConverter，将 List<String> 与 JSON 字符串互转。
 *
 * 用于 TeamTask.blockedBy 字段的持久化。
 */
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        if (value.isEmpty()) return ""
        return org.json.JSONArray(value).toString()
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        // 兼容旧格式：如果非 JSON 数组格式（不含 '['），按逗号分隔解析
        if (!value.startsWith("[")) return value.split(",")
        return try {
            val arr = org.json.JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            // JSON 解析失败，回退到逗号分隔
            value.split(",")
        }
    }
}

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
        // 多 Agent 工作区相关实体
        AgentPreset::class,
        WorkspaceSession::class,
        WorkspaceTeam::class,
        AgentInstance::class,
        WorkspaceMessage::class,
        MailboxMessage::class,
        AgentStateSnapshot::class,
        // Agent Team 任务系统
        TeamTask::class,
        McpFilePermission::class,
    ],
    version = 31,
    exportSchema = false,
)
@TypeConverters(Converters::class)
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
    // 多 Agent 工作区相关 DAO
    abstract fun agentPresetDao(): AgentPresetDao
    abstract fun workspaceSessionDao(): WorkspaceSessionDao
    abstract fun workspaceTeamDao(): WorkspaceTeamDao
    abstract fun agentInstanceDao(): AgentInstanceDao
    abstract fun workspaceMessageDao(): WorkspaceMessageDao
    abstract fun mailboxMessageDao(): MailboxMessageDao
    abstract fun agentStateSnapshotDao(): AgentStateSnapshotDao
    // Agent Team 任务系统 DAO
    abstract fun teamTaskDao(): TeamTaskDao
    // MCP 文件权限 DAO
    abstract fun mcpFilePermissionDao(): McpFilePermissionDao

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

        /** v19→v20：新增多 Agent 工作区相关表（agent_presets、workspace_sessions、agent_instances、workspace_messages） */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // agent_presets 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        modelConfigId INTEGER,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // workspace_sessions 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspace_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL DEFAULT '新工作区',
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        lastActiveAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // agent_instances 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_instances (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workspaceSessionId INTEGER NOT NULL,
                        agentName TEXT NOT NULL,
                        isOrchestrator INTEGER NOT NULL DEFAULT 0,
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        modelConfigId INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_instances_workspaceSessionId ON agent_instances(workspaceSessionId)")

                // workspace_messages 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspace_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workspaceSessionId INTEGER NOT NULL,
                        agentInstanceId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        toolCallId TEXT,
                        toolCallsJson TEXT,
                        isIntervention INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_messages_workspaceSessionId ON workspace_messages(workspaceSessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_messages_agentInstanceId ON workspace_messages(agentInstanceId)")
            }
        }

        /** v20→v21：新增 team_tasks 表（Agent Team 任务系统） */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS team_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        teamName TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        owner TEXT,
                        blockedBy TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_team_tasks_teamName ON team_tasks(teamName)")
            }
        }

        /** v21→v22：team_tasks 表新增 intendedAgent 字段（任务认领匹配） */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE team_tasks ADD COLUMN intendedAgent TEXT")
            }
        }

        /** v22→v23：messages 表新增 imagePath 字段（图片消息支持） */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
            }
        }

        /** v23→v24：agent_instances 表新增 overrideModelId 字段 */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_instances ADD COLUMN overrideModelId TEXT")
            }
        }

        /** v24→v25：workspace_messages 表新增 imagePath 字段（工作区图片消息支持） */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workspace_messages ADD COLUMN imagePath TEXT")
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

        /** v30→v31：新增 workspace_teams、mailbox_messages、agent_state_snapshots 表；
         *  agent_instances 增加 teamId、agentType、status、updatedAt 字段 */
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // workspace_teams 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspace_teams (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        teamName TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        orchestratorModelConfigId INTEGER NOT NULL,
                        sandboxPath TEXT,
                        status TEXT NOT NULL DEFAULT 'active',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workspace_teams_teamName ON workspace_teams(teamName)")

                // agent_instances 新增列
                db.execSQL("ALTER TABLE agent_instances ADD COLUMN teamId INTEGER")
                db.execSQL("ALTER TABLE agent_instances ADD COLUMN agentType TEXT NOT NULL DEFAULT 'sub'")
                db.execSQL("ALTER TABLE agent_instances ADD COLUMN status TEXT NOT NULL DEFAULT 'idle'")
                db.execSQL("ALTER TABLE agent_instances ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_instances_teamId ON agent_instances(teamId)")

                // mailbox_messages 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mailbox_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recipientAgentId INTEGER NOT NULL,
                        senderAgentName TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        source TEXT NOT NULL,
                        delivered INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mailbox_messages_recipientAgentId ON mailbox_messages(recipientAgentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mailbox_messages_delivered ON mailbox_messages(delivered)")

                // agent_state_snapshots 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_state_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        agentInstanceId INTEGER NOT NULL,
                        messagesJson TEXT NOT NULL,
                        usageStatsJson TEXT NOT NULL,
                        snapshotType TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_state_snapshots_agentInstanceId ON agent_state_snapshots(agentInstanceId)")
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
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31
                    )
                    // 兜底：只对 v1、v2、v3 这些极旧版本触发破坏性迁移（BUG-13）。
                    // v4 及以上版本有完整的迁移脚本，不应触发破坏性迁移，避免清空用户数据。
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
