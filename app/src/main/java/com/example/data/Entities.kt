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
