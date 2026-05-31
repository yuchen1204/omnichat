package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ═══════════════════════════════════════════════════════════════════════════════
// 多 Agent 工作区相关实体
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Agent 预设模板。
 *
 * 用户在设置页预先定义的可复用 Agent 模板，包含名称、描述、系统提示和指定模型。
 * 在工作区执行时，Orchestrator 可根据角色名称精确匹配预设来初始化 Sub-Agent。
 */
@Entity(tableName = "agent_presets")
data class AgentPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                          // 必填，不超过 100 字符
    val description: String = "",              // 选填，摘要展示截取前 50 字符
    val systemPrompt: String = "",             // 选填，Agent 系统提示
    val modelConfigId: Long? = null,           // null = 使用工作区默认模型
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 工作区会话。
 *
 * 包含主控 Agent（Orchestrator）和若干子 Agent 的独立会话单元，
 * 与普通聊天会话分类隔离。isActive=true 表示执行中，false 表示已完成。
 */
@Entity(tableName = "workspace_sessions")
data class WorkspaceSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新工作区",
    val isActive: Boolean = true,              // true = 执行中；false = 已完成
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
)

/**
 * Agent Team 实体。
 *
 * 代表一个可复用的 Agent 团队配置，包含模式（编排/对等）、
 * 编排器模型、沙盒路径等元数据。
 */
@Entity(
    tableName = "workspace_teams",
    indices = [Index(value = ["teamName"], unique = true)]
)
data class WorkspaceTeam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamName: String,
    val mode: String,                  // "orchestrator" | "peer"
    val orchestratorModelConfigId: Long,
    val sandboxPath: String? = null,
    val status: String = "active",     // "active" | "paused" | "completed"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Agent 实例元数据。
 *
 * 记录工作区内每个 Agent 的元数据。仅 Orchestrator 的记录在完成后保留，
 * Sub-Agent 的记录在工作区完成后被删除。
 */
@Entity(
    tableName = "agent_instances",
    indices = [
        Index(value = ["workspaceSessionId"]),
        Index(value = ["teamId"]),
    ]
)
data class AgentInstance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceSessionId: Long,
    val agentName: String,                     // Orchestrator 固定为 "主控 Agent"
    val isOrchestrator: Boolean = false,
    val systemPrompt: String = "",
    val modelConfigId: Long,
    val overrideModelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // ── v31 新增列（Agent Team 重构） ──────────────────────────
    val teamId: Long? = null,                  // 关联 WorkspaceTeam.id
    val agentType: String = "sub",             // "orchestrator" | "sub" | "standalone"
    val status: String = "idle",               // "idle" | "busy" | "paused" | "completed"
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Agent 邮箱消息。
 *
 * 用于跨 Agent 异步通信。发送方将消息写入收件人的邮箱，
 * 收件人在下一次 LLM 循环时拉取未投递的消息。
 */
@Entity(
    tableName = "mailbox_messages",
    indices = [
        Index(value = ["recipientAgentId"]),
        Index(value = ["delivered"]),
    ]
)
data class MailboxMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientAgentId: Long,
    val senderAgentName: String,
    val role: String,                  // "user" | "system"
    val content: String,
    val source: String,                // "orchestrator" | "send_message" | "broadcast" | "task-notification"
    val delivered: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Agent 状态快照。
 *
 * 用于持久化 Agent 的对话历史和使用统计，
 * 支持 checkpoint（定期保存）和 completion（任务完成时保存）两种类型。
 */
@Entity(
    tableName = "agent_state_snapshots",
    indices = [Index(value = ["agentInstanceId"])]
)
data class AgentStateSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentInstanceId: Long,
    val messagesJson: String,
    val usageStatsJson: String,
    val snapshotType: String,          // "checkpoint" | "completion"
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 工作区消息记录。
 *
 * 仅持久化 Orchestrator 的消息；Sub-Agent 消息仅存在内存中，工作区完成后不保留。
 * isIntervention=true 标记用户干预消息。
 */
@Entity(
    tableName = "workspace_messages",
    indices = [
        Index(value = ["workspaceSessionId"]),
        Index(value = ["agentInstanceId"])
    ]
)
data class WorkspaceMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceSessionId: Long,
    val agentInstanceId: Long,                 // 关联 AgentInstance
    val role: String,                          // "user" | "assistant" | "tool" | "system"
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val isIntervention: Boolean = false,       // 用户干预消息标记
    val imagePath: String? = null,             // 图片路径
    val timestamp: Long = System.currentTimeMillis()
)

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
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 图片的本地路径或 Base64 数据 URL。
     * - 本地路径: /storage/emulated/0/Pictures/photo.jpg
     * - Base64 Data URL: data:image/jpeg;base64,/9j/4AAQ...
     * 为 null 时表示纯文本消息。
     */
    val imagePath: String? = null
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
    val tags: String = ""
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

    /** 是否启用 Node.js 运行时 */
    val isNodeEnabled: Boolean = true,
    /** 是否启用 Python 运行时 */
    val isPythonEnabled: Boolean = true,

    /**
     * 已启用的内置 MCP 工具组，逗号分隔。
     * 默认启用: core,ui_appearance,efficiency,memory
     * 可选禁用: files,documents,ui_text
     */
    val enabledMcpGroups: String = "core,ui_appearance,efficiency,memory",

    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * AI 可调整的 UI 文字标签，JSON 对象字符串。
     * 空字符串或 "{}" 表示使用默认中文标签。
     * 格式示例：`{"topbar_title_chat":"Chat","nav_settings":"Settings"}`
     * 完整字段列表见 [com.example.ui.theme.UiStrings]。
     */
    val uiStrings: String = "{}"
)

// ═══════════════════════════════════════════════════════════════════════════════
// Agent Team 任务系统实体
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 团队任务。
 *
 * 对标 Claude Code 的任务系统（~/.claude/tasks/{teamName}/）。
 * 空闲 Agent 通过 [TeamTaskDao.claimTask] 自动认领 PENDING 状态且无 owner 的任务。
 *
 * 状态流转：PENDING -> IN_PROGRESS -> COMPLETED / FAILED
 *
 * @property id 自增主键
 * @property teamName 所属团队名称
 * @property subject 任务主题（简短描述）
 * @property description 任务详细描述
 * @property status 任务状态：PENDING / IN_PROGRESS / COMPLETED / FAILED
 * @property owner 认领者 Agent 名称，null 表示未被认领
 * @property blockedBy 被阻塞的任务 ID 列表，所有依赖任务完成后才能认领
 * @property createdAt 创建时间戳
 * @property updatedAt 最近更新时间戳
 */
// TODO (Phase 7): Wire TeamTask into agent execution via TaskCreate/TaskUpdate tools.
// Currently unused — will be integrated with AgentToolFilter and SendMessage tools.
@Entity(
    tableName = "team_tasks",
    indices = [Index(value = ["teamName"])]
)
data class TeamTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamName: String,
    val subject: String,
    val description: String = "",
    val status: String = "PENDING",
    val owner: String? = null,
    /** 预期执行的 Agent 名称。为 null 时表示任意 Agent 可认领 */
    val intendedAgent: String? = null,
    val blockedBy: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 任务状态枚举。
 *
 * 对标蓝图的 TaskStatus，用于类型安全地表示任务状态。
 * Room 存储为 String，通过 TypeConverter 自动转换。
 */
enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * MCP File Access Permission
 * Records user's choice for accessing a file outside the sandbox.
 * 
 * isAllowed:
 * - true: "Allow always"
 * - false: "Don't ask again" (Deny always)
 */
@Entity(tableName = "mcp_file_permissions")
data class McpFilePermission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val isAllowed: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Agent 定义实体 — 存储用户自定义 Agent 定义。
 * 对齐 Claude Code 的 AgentDefinition 结构。
 */
@Entity(
    tableName = "agent_definitions",
    indices = [Index(value = ["agentType"], unique = true)]
)
data class AgentDefinitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Agent type identifier (e.g., "custom:my-agent") */
    val agentType: String,

    /** Display name */
    val displayName: String,

    /** When to use description */
    val whenToUse: String = "",

    /** System prompt */
    val systemPrompt: String,

    /** Model hint: "default", "fast", "reasoning", "vision", "inherit" */
    val modelHint: String? = null,

    /** Model config ID override */
    val modelConfigId: Long? = null,

    /** Model ID override */
    val overrideModelId: String? = null,

    /** Allowed tools (JSON array string, null or ["*"] = all) */
    val toolsJson: String? = null,

    /** Disallowed tools (JSON array string) */
    val disallowedToolsJson: String? = null,

    /** Background execution */
    val background: Boolean = false,

    /** Max turns */
    val maxTurns: Int = 50,

    /** UI color */
    val color: String? = null,

    /** Memory scope: "user", "project", "local" */
    val memory: String? = null,

    /** MCP servers (JSON array string) */
    val mcpServersJson: String? = null,

    /** Hooks (JSON string) */
    val hooksJson: String? = null,

    /** Permission mode */
    val permissionMode: String? = null,

    /** Initial prompt */
    val initialPrompt: String? = null,

    /** Effort level */
    val effort: String? = null,

    /** Omit CLAUDE.md */
    val omitClaudeMd: Boolean = false,

    /** Required MCP servers (JSON array string) */
    val requiredMcpServersJson: String? = null,

    /** Source file path (for markdown agents) */
    val filePath: String? = null,

    /** Base directory */
    val baseDir: String? = null,

    /** Critical system reminder */
    val criticalSystemReminder: String? = null,

    /** Created timestamp */
    val createdAt: Long = System.currentTimeMillis(),

    /** Updated timestamp */
    val updatedAt: Long = System.currentTimeMillis(),
)
