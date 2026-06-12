package com.omnichat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ═══════════════════════════════════════════════════════════════════════════════
// 原有实体
// ═══════════════════════════════════════════════════════════════════════════════

@Entity(tableName = "model_configs")
data class ModelConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val endpoint: String,
    val apiKey: String,
    val selectedModelId: String,
    val memoryModelId: String,
    val memoryProviderId: Long = 0L, // 0 = 与主 Provider 相同
    val isDefaultProvider: Boolean = false,
    val enableThinking: Boolean = true,
    val thinkingEffort: String = "medium",
    /** 自定义 HTTP 请求头，JSON 对象字符串，例如 '{"X-Custom-Header": "value"}' */
    val customHeaders: String = "{}",
    /** 嵌入模型 ID，用于记忆语义搜索（如 "text-embedding-3-small"） */
    val embeddingModelId: String = ""
)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["sessionId"])]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 图片路径的 JSON 数组，支持多图。
     * 格式: ["/storage/emulated/0/Pictures/photo.jpg", "data:image/jpeg;base64,..."]
     * 为 null 时表示纯文本消息。
     */
    val imagePaths: String? = null
)

@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["confidence"]),
        Index(value = ["updatedAt"])
    ]
)
data class MemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 置信度：每次被 LLM 确认/强化时 +1，初始为 1。越高越稳定。 */
    val confidence: Int = 1,
    /** 最近一次被更新（新增或强化）的时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),
    /** 用户手动锁定：pinned=true 时 LLM 不可删除或覆盖此条目 */
    val pinned: Boolean = false,
    /** 最近一次被强化的时间戳，用于置信度衰减计算 */
    val lastReinforcedAt: Long = System.currentTimeMillis(),
    /** LLM 生成的语义标签，逗号分隔，如 "preference,fact" */
    val tags: String = "",
    /** 嵌入向量的 JSON 序列化，如 "[0.1,0.2,...]"，用于语义搜索 */
    val embedding: String = "",
    /** 截止日期（ISO 格式 "YYYY-MM-DD"），null 表示非时间记忆 */
    val dueDate: String? = null,
    /** 是否已提醒用户，防止重复提醒 */
    val reminded: Boolean = false
)

@Entity(
    tableName = "memory_associations",
    indices = [
        Index(value = ["fromMemoryId"]),
        Index(value = ["toMemoryId"])
    ]
)
data class MemoryAssociation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromMemoryId: Long,
    val toMemoryId: Long,
    /** LLM-generated relation label: related, causes, part_of, contrasts, belongs_to, implies */
    val relationLabel: String,
    /** "bidirectional" (default) or "directed" */
    val direction: String = "bidirectional",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memory_audit_log",
    indices = [
        Index(value = ["memoryId"]),
        Index(value = ["timestamp"])
    ]
)
data class MemoryAuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryId: Long,           // -1 表示批量操作
    val opType: String,           // ADD, UPDATE, REINFORCE, DELETE, DECAY, MANUAL
    val contentSnapshot: String,  // 操作时的记忆内容快照
    val triggerReason: String,    // "sync", "manual", "dedup_merge", "backfill_tags"
    val confidenceBefore: Int?,
    val confidenceAfter: Int?,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "prompt_templates")
data class PromptTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val templateText: String,
    val isActive: Boolean = false
)

/**
 * 每个会话的滚动摘要，用于 15 分钟增量记忆算法。
 * 每次总结时：取最近 100 条消息 + 上次摘要 → 生成新摘要，再与全局 MemoryItem 合并提炼偏好。
 */
@Entity(tableName = "session_summaries")
data class SessionSummary(
    @PrimaryKey val sessionId: Long,
    val summaryText: String,                  // 本会话的滚动摘要（每次总结后更新）
    val lastSummarizedAt: Long = 0L,          // 上次总结的时间戳（毫秒）
    val messageCountAtLastSummary: Int = 0    // 上次总结时的消息总数（用于判断是否有新内容）
)

@Entity(tableName = "fetched_models")
data class FetchedModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val contextSize: String,
    val hasThinking: Boolean,
    val hasVision: Boolean,
    val hasToolUse: Boolean
)

/**
 * MCP (Model Context Protocol) 服务配置。
 *
 * 通过 HTTP/HTTPS 连接远程 MCP server。
 *
 * command  — 远程 URL
 * args     — JSON 数组字符串，例如 '["--root", "/sdcard"]'
 * env      — JSON 对象字符串（自定义 HTTP 请求头），例如 '{"Authorization": "Bearer token"}'
 */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,            // 远程 URL
    val args: String = "[]",        // JSON 数组字符串
    val env: String = "{}",         // JSON 对象字符串
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 配色方案预设快照。
 *
 * 每次 AI 调用 color_scheme(action="save") 时，将当前 [UISettings] 的全部颜色 + 布局参数
 * 序列化为一行记录。最多保存 [MAX_PRESETS] 条，超出时拒绝保存并提示 AI 先删除旧方案。
 *
 * [schemeId] 使用 UUID 字符串，保证跨设备唯一，便于未来同步。
 */
@Entity(tableName = "color_scheme_presets")
data class ColorSchemePreset(
    @PrimaryKey val schemeId: String,          // UUID 字符串
    val name: String,                          // 方案名称，由 AI 提供
    val description: String,                   // 方案概述，由 AI 提供
    val createdAt: Long = System.currentTimeMillis(),

    // ── 颜色快照（与 UISettings 字段一一对应） ──────────────────
    val primaryColor: String,
    val onPrimaryColor: String,
    val primaryContainerColor: String,
    val onPrimaryContainerColor: String,
    val secondaryColor: String,
    val onSecondaryColor: String,
    val secondaryContainerColor: String,
    val onSecondaryContainerColor: String,
    val tertiaryColor: String,
    val onTertiaryColor: String,
    val backgroundColor: String,
    val onBackgroundColor: String,
    val surfaceColor: String,
    val onSurfaceColor: String,
    val surfaceVariantColor: String,
    val onSurfaceVariantColor: String,
    val outlineColor: String,
    val outlineVariantColor: String,
    val errorColor: String,
    val onErrorColor: String,
    val errorContainerColor: String,
    val onErrorContainerColor: String,
    val successColor: String,
    val warningColor: String,
    val infoColor: String,
    val accentColor: String,
    val sidebarBackgroundColor: String,
    val sidebarOnBackgroundColor: String,
    val sidebarActiveColor: String,
    val sidebarOnActiveColor: String,
    val cornerRadiusDp: Int,
    val spacingMultiplier: Float
) {
    companion object {
        const val MAX_PRESETS = 5

        /** 从当前 UISettings 快照出一个 Preset（schemeId/name/description 由调用方填入） */
        fun fromUISettings(
            schemeId: String,
            name: String,
            description: String,
            s: UISettings
        ) = ColorSchemePreset(
            schemeId = schemeId,
            name = name,
            description = description,
            primaryColor = s.primaryColor,
            onPrimaryColor = s.onPrimaryColor,
            primaryContainerColor = s.primaryContainerColor,
            onPrimaryContainerColor = s.onPrimaryContainerColor,
            secondaryColor = s.secondaryColor,
            onSecondaryColor = s.onSecondaryColor,
            secondaryContainerColor = s.secondaryContainerColor,
            onSecondaryContainerColor = s.onSecondaryContainerColor,
            tertiaryColor = s.tertiaryColor,
            onTertiaryColor = s.onTertiaryColor,
            backgroundColor = s.backgroundColor,
            onBackgroundColor = s.onBackgroundColor,
            surfaceColor = s.surfaceColor,
            onSurfaceColor = s.onSurfaceColor,
            surfaceVariantColor = s.surfaceVariantColor,
            onSurfaceVariantColor = s.onSurfaceVariantColor,
            outlineColor = s.outlineColor,
            outlineVariantColor = s.outlineVariantColor,
            errorColor = s.errorColor,
            onErrorColor = s.onErrorColor,
            errorContainerColor = s.errorContainerColor,
            onErrorContainerColor = s.onErrorContainerColor,
            successColor = s.successColor,
            warningColor = s.warningColor,
            infoColor = s.infoColor,
            accentColor = s.accentColor,
            sidebarBackgroundColor = s.sidebarBackgroundColor,
            sidebarOnBackgroundColor = s.sidebarOnBackgroundColor,
            sidebarActiveColor = s.sidebarActiveColor,
            sidebarOnActiveColor = s.sidebarOnActiveColor,
            cornerRadiusDp = s.cornerRadiusDp,
            spacingMultiplier = s.spacingMultiplier
        )

        /** 将 Preset 还原为 UISettings（保留 id=1 和 updatedAt=now） */
        fun ColorSchemePreset.toUISettings() = UISettings(
            primaryColor = primaryColor,
            onPrimaryColor = onPrimaryColor,
            primaryContainerColor = primaryContainerColor,
            onPrimaryContainerColor = onPrimaryContainerColor,
            secondaryColor = secondaryColor,
            onSecondaryColor = onSecondaryColor,
            secondaryContainerColor = secondaryContainerColor,
            onSecondaryContainerColor = onSecondaryContainerColor,
            tertiaryColor = tertiaryColor,
            onTertiaryColor = onTertiaryColor,
            backgroundColor = backgroundColor,
            onBackgroundColor = onBackgroundColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            surfaceVariantColor = surfaceVariantColor,
            onSurfaceVariantColor = onSurfaceVariantColor,
            outlineColor = outlineColor,
            outlineVariantColor = outlineVariantColor,
            errorColor = errorColor,
            onErrorColor = onErrorColor,
            errorContainerColor = errorContainerColor,
            onErrorContainerColor = onErrorContainerColor,
            successColor = successColor,
            warningColor = warningColor,
            infoColor = infoColor,
            accentColor = accentColor,
            sidebarBackgroundColor = sidebarBackgroundColor,
            sidebarOnBackgroundColor = sidebarOnBackgroundColor,
            sidebarActiveColor = sidebarActiveColor,
            sidebarOnActiveColor = sidebarOnActiveColor,
            cornerRadiusDp = cornerRadiusDp,
            spacingMultiplier = spacingMultiplier,
            updatedAt = System.currentTimeMillis()
        )
    }
}

/**
 * 界面显示设置，允许 AI 调整配色和基础布局。
 * id 永远为 1 (单行存储)。
 *
 * 当 [updatedAt] > 0 时表示用户/AI 已自定义，主题层会用这些值构建 lightColorScheme，
 * 完整覆盖整个应用的配色（包含浅色/深色模式）。所有颜色字段均为 #RRGGBB 或 #RRGGBBAA 格式。
 */
@Entity(tableName = "ui_settings")
data class UISettings(
    @PrimaryKey val id: Long = 1L,

    // ── 主调色板（Material 3 主色） ──────────────────────────────
    val primaryColor: String = "#007AFF",
    val onPrimaryColor: String = "#FFFFFF",
    val primaryContainerColor: String = "#E5F2FF",
    val onPrimaryContainerColor: String = "#004080",

    val secondaryColor: String = "#5856D6",
    val onSecondaryColor: String = "#FFFFFF",
    val secondaryContainerColor: String = "#F2F1FA",
    val onSecondaryContainerColor: String = "#2B2A75",

    val tertiaryColor: String = "#FF2D55",
    val onTertiaryColor: String = "#FFFFFF",

    // ── 表面与文字 ─────────────────────────────────────────────
    val backgroundColor: String = "#F2F2F7",
    val onBackgroundColor: String = "#1C1C1E",
    val surfaceColor: String = "#FFFFFF",
    val onSurfaceColor: String = "#1C1C1E",
    val surfaceVariantColor: String = "#F2F2F7",
    val onSurfaceVariantColor: String = "#636366",
    val outlineColor: String = "#C7C7CC",
    val outlineVariantColor: String = "#D1D1D6",

    // ── 状态色（错误 / 成功 / 警告 / 信息 / 强调） ──────────────
    val errorColor: String = "#FF3B30",
    val onErrorColor: String = "#FFFFFF",
    val errorContainerColor: String = "#FFE5E5",
    val onErrorContainerColor: String = "#8B0000",
    val successColor: String = "#34C759",
    val warningColor: String = "#FF9500",
    val infoColor: String = "#007AFF",
    val accentColor: String = "#FF9500",
    val sidebarBackgroundColor: String = "#F5F5F7",
    val sidebarOnBackgroundColor: String = "#1C1C1E",
    val sidebarActiveColor: String = "#E5F2FF",
    val sidebarOnActiveColor: String = "#007AFF",

    // ── 布局约束 ──────────────────────────────────────────────
    val cornerRadiusDp: Int = 12,        // 圆角大小 (0-32)
    val spacingMultiplier: Float = 1.0f, // 间距倍数 (0.5-2.0)

    // ── 字体设置 ──────────────────────────────────────────────
    /**
     * 全局字体大小缩放比例，影响 UI 标签、按钮、标题等非聊天文字。
     * 范围 0.75–1.5，默认 1.0（即 100%）。
     */
    val fontSizeScale: Float = 1.0f,
    /**
     * 聊天气泡内正文字体大小缩放比例，独立于全局缩放，方便单独调大聊天字号。
     * 范围 0.75–1.5，默认 1.0。
     */
    val chatFontSizeScale: Float = 1.0f,
    /**
     * 字体族标识符。支持以下值：
     *   "default"   — 系统默认字体（Roboto / 设备字体）
     *   "serif"     — 衬线字体（Noto Serif）
     *   "monospace" — 等宽字体（Noto Sans Mono）
     *   "cursive"   — 手写风格字体（Dancing Script）
     * 不支持的值会回退到 "default"。
     */
    val fontFamily: String = "default",

    /**
     * 已启用的内置 MCP 工具组，逗号分隔。
     * 默认启用: core,ui_appearance,efficiency,memory
     * 可选禁用: files,documents,ui_text
     */
    val enabledMcpGroups: String = "core,ui_appearance,efficiency,memory",

    /**
     * 静默工具调用：按组隐藏，逗号分隔。
     * 空字符串 = 不静默（显示所有）。
     * "*" = 静默所有内置工具。
     * "files,efficiency" = 静默指定组的工具。
     * 外部 MCP 工具不受影响。
     * AI 通过 set_tool_display_mode 工具控制。
     */
    val silentToolGroups: String = "",

    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * AI 可调整的 UI 文字标签，JSON 对象字符串。
     * 空字符串或 "{}" 表示使用默认中文标签。
     * 格式示例：`{"topbar_title_chat":"Chat","nav_settings":"Settings"}`
     * 完整字段列表见 [com.omnichat.ui.theme.UiStrings]。
     */
    val uiStrings: String = "{}"
)

/**
 * MCP File Access Permission
 * Records user's choice for accessing a file outside the sandbox.
 * 
 * isAllowed:
 * - true: "Allow always"
 * - false: "Don't ask again" (Deny always)
 */
/**
 * 文件权限访问类型。
 * READ: 查看/读取（file_read, file_list, file_info, file_search 等）
 * WRITE: 修改/删除/创建（file_write, file_delete, file_move, file_copy, file_mkdir 等）
 */
enum class FileAccessType { READ, WRITE }

@Entity(tableName = "mcp_file_permissions")
data class McpFilePermission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val isAllowed: Boolean,
    /** 权限类型：read = 只读访问，write = 读写访问 */
    val permissionType: String = "read",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * subAgent 配置。存储每种代理类型的模型设置。
 *
 * agentType: "general", "researcher", "coder", "reviewer", "tester"
 * providerId: 关联的 ModelConfig.id
 * modelId: 具体模型 ID
 */
@Entity(tableName = "agent_configs")
data class AgentConfig(
    @PrimaryKey val agentType: String,
    val providerId: Long,
    val modelId: String,
    val isEnabled: Boolean = true,
    val maxConcurrency: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cloud_backups")
data class CloudBackupRecord(
    @PrimaryKey val backupId: String,
    val type: String,                   // "omnidb" | "omniconfig"
    val filename: String,
    val createdAt: Long,
    val userId: String
)
