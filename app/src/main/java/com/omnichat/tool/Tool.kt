package com.omnichat.tool

import android.content.Context
import org.json.JSONObject

/**
 * 统一工具接口，同时服务于内置工具和 MCP 远程工具。
 *
 * 参考 ClaudeCode 的 Tool.ts 设计，提供：
 * - 自包含：工具定义、Schema、执行逻辑、元信息在同一个对象中
 * - 权限集成：支持 isReadOnly / isDestructive / checkPermissions
 * - 并发安全：支持 isConcurrencySafe 标记
 * - 分组控制：支持按组启用/禁用工具
 */
interface Tool {
    // ══════════════════════════════════════════════════════════════
    // 核心属性
    // ══════════════════════════════════════════════════════════════

    /**
     * 工具唯一标识名，如 "file_read", "delegate_task"。
     * MCP 远程工具名格式为 "mcp__{serverName}__{toolName}"。
     */
    val name: String

    /**
     * 工具描述，用于 LLM 理解工具用途。
     * 应清晰说明：何时使用、参数含义、返回格式。
     */
    val description: String

    /**
     * 输入参数 JSON Schema。
     * 定义工具接受的参数类型、约束、是否必填等。
     */
    val inputSchema: JSONObject

    /**
     * 输出参数 JSON Schema（可选）。
     * 用于描述工具返回值的结构。
     */
    val outputSchema: JSONObject? get() = null

    /**
     * 工具别名列表。
     * 用于工具重命名时保持向后兼容，LLM 使用旧名称仍可调用。
     */
    val aliases: List<String> get() = emptyList()

    // ══════════════════════════════════════════════════════════════
    // 元信息
    // ══════════════════════════════════════════════════════════════

    /**
     * 工具所属分组。
     * 用于 UI 展示和启用/禁用控制。
     *
     * 标准分组：
     * - core: 核心工具，始终启用
     * - memory: 长期记忆操作
     * - ui_appearance: UI 主题配置
     * - ui_text: UI 文字调整
     * - files: 文件系统操作
     * - documents: 文档创建
     * - efficiency: 定时器等效率工具
     * - subagent: 子智能体相关
     * - mcp_remote: MCP 远程工具
     */
    val group: String get() = "core"

    /**
     * 是否只读操作。
     * 只读工具不会修改文件系统、数据库或其他持久化状态。
     * 用于：
     * - 权限 UI 展示（只读工具显示不同图标）
     * - 自动审批策略（只读工具更易自动允许）
     */
    val isReadOnly: Boolean get() = false

    /**
     * 是否破坏性操作。
     * 破坏性操作会删除或永久覆盖数据。
     * 用于：
     * - 权限提示加强
     * - 需要额外确认的操作
     */
    val isDestructive: Boolean get() = false

    /**
     * 是否并发安全。
     * 并发安全的工具可以与其他工具同时执行。
     * 非并发安全的工具需要串行执行（如文件写入）。
     */
    val isConcurrencySafe: Boolean get() = true

    /**
     * 是否需要用户会话上下文。
     * 某些工具（如定时器）需要在特定会话中执行。
     */
    val requiresSession: Boolean get() = false

    /**
     * 用户友好的显示名称。
     * 用于 UI 展示。
     */
    fun userFacingName(): String = name

    /**
     * 简短搜索提示。
     * 用于工具搜索/发现，3-10 个词。
     */
    val searchHint: String? get() = null

    // ══════════════════════════════════════════════════════════════
    // 执行
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行工具。
     *
     * @param context Android Context
     * @param arguments 工具参数（已通过 validateInput 验证）
     * @param sessionId 会话 ID（可选，某些工具需要）
     * @return JSON 格式的执行结果，符合 MCP tools/call 响应格式
     */
    suspend fun call(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject

    // ══════════════════════════════════════════════════════════════
    // 验证与权限
    // ══════════════════════════════════════════════════════════════

    /**
     * 验证输入参数格式。
     * 在 checkPermissions 之前调用。
     *
     * @param arguments 工具参数
     * @return null 表示验证通过，否则返回错误消息
     */
    fun validateInput(arguments: JSONObject): String? = null

    /**
     * 检查是否有权限执行此工具。
     * 在 validateInput 之后、call 之前调用。
     *
     * @param context Android Context
     * @param arguments 工具参数
     * @return null 表示允许执行，否则返回拒绝原因
     */
    suspend fun checkPermissions(context: Context, arguments: JSONObject): String? = null

    // ══════════════════════════════════════════════════════════════
    // 辅助方法（默认实现）
    // ══════════════════════════════════════════════════════════════

    /**
     * 检查工具名称是否匹配（支持别名）。
     */
    fun matchesName(name: String): Boolean {
        return this.name == name || name in aliases
    }
}
