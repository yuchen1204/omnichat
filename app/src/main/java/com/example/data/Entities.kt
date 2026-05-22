package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val customHeaders: String = "{}"
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
    val timestamp: Long = System.currentTimeMillis()
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
    val pinned: Boolean = false
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
 * runtime 字段：
 *   "node"   — 通过内嵌 Node.js 运行 JS/TS MCP server
 *   "python" — 通过内嵌 Python 运行 Python MCP server
 *   "remote_http" — 通过 HTTP/HTTPS 连接远程 MCP server
 *
 * command  — 入口脚本路径、包名 或 远程 URL
 * args     — JSON 数组字符串，例如 '["--root", "/sdcard"]'
 * env      — JSON 对象字符串，例如 '{"API_KEY": "xxx"}'
 */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val runtime: String = "node",   // "node" | "python" | "remote_http"
    val command: String,            // 入口脚本路径 或 npm 包名 或 URL
    val args: String = "[]",        // JSON 数组字符串
    val env: String = "{}",         // JSON 对象字符串
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 配色方案预设快照。
 *
 * 每次 AI 调用 save_color_scheme 时，将当前 [UISettings] 的全部颜色 + 布局参数
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
    val primaryColor: String = "#6750A4",
    val onPrimaryColor: String = "#FFFFFF",
    val primaryContainerColor: String = "#EADDFF",
    val onPrimaryContainerColor: String = "#21005D",

    val secondaryColor: String = "#625B71",
    val onSecondaryColor: String = "#FFFFFF",
    val secondaryContainerColor: String = "#E8DEF8",
    val onSecondaryContainerColor: String = "#1D192B",

    val tertiaryColor: String = "#7D5260",
    val onTertiaryColor: String = "#FFFFFF",

    // ── 表面与文字 ─────────────────────────────────────────────
    val backgroundColor: String = "#FFFBFE",
    val onBackgroundColor: String = "#1C1B1F",
    val surfaceColor: String = "#FFFBFE",
    val onSurfaceColor: String = "#1C1B1F",
    val surfaceVariantColor: String = "#E7E0EC",
    val onSurfaceVariantColor: String = "#49454F",
    val outlineColor: String = "#79747E",
    val outlineVariantColor: String = "#CAC4D0",

    // ── 状态色（错误 / 成功 / 警告 / 信息 / 强调） ──────────────
    val errorColor: String = "#B00020",
    val onErrorColor: String = "#FFFFFF",
    val errorContainerColor: String = "#F9DEDC",
    val onErrorContainerColor: String = "#410E0B",
    val successColor: String = "#34C759",
    val warningColor: String = "#FF9800",
    val infoColor: String = "#007AFF",
    val accentColor: String = "#FF9500",

    // ── 布局约束 ──────────────────────────────────────────────
    val cornerRadiusDp: Int = 12,        // 圆角大小 (0-32)
    val spacingMultiplier: Float = 1.0f, // 间距倍数 (0.5-2.0)
    val updatedAt: Long = System.currentTimeMillis()
)
