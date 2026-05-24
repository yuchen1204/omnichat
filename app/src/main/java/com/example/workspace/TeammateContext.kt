package com.example.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// ═══════════════════════════════════════════════════════════════════════════════
// Teammate 身份与上下文隔离
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 权限模式枚举。
 *
 * 对标 Claude Code 的 PermissionMode，控制 Teammate 执行工具时的权限策略。
 */
enum class PermissionMode {
    /** 默认模式，敏感工具需要用户确认 */
    DEFAULT,
    /** 自动模式，部分工具自动放行 */
    AUTO,
    /** 计划模式，需要 Leader 审批执行计划 */
    PLAN
}

/**
 * Teammate 身份信息。
 *
 * 标识一个 Agent 实例的完整身份，用于消息路由、日志和 UI 展示。
 * 对标 Claude Code 的 teammateIdentity（通过 CLI 参数或 AsyncLocalStorage 传递）。
 *
 * @property agentId 全局唯一标识，格式 "agentName@teamName"（如 "researcher@my-team"）
 * @property agentName Agent 短名称（如 "researcher"）
 * @property teamName 所属团队名称
 * @property color UI 标识色，用于区分不同 Agent 的消息气泡
 * @property agentType 角色类型（如 "researcher"、"coder"、"reviewer"）
 * @property parentSessionId 父会话 ID，用于权限桥接（Leader 的 ToolUseConfirm 对话框）
 */
data class TeammateIdentity(
    val agentId: String,
    val agentName: String,
    val teamName: String,
    val color: String = "",
    val agentType: String = "",
    val parentSessionId: String = "",
)

/**
 * Teammate 协程上下文元素。
 *
 * 对标 Claude Code 的 AsyncLocalStorage 上下文隔离机制。
 * 通过 Kotlin 的 [CoroutineContext.Element] 实现：每个 Teammate 运行在独立的
 * [CoroutineScope] 中，通过 [TeammateContext] 携带身份信息和中止控制。
 *
 * 使用方式：
 * ```kotlin
 * val identity = TeammateIdentity(...)
 * val context = TeammateContext(identity)
 * launch(context) {
 *     // 此协程内可通过 coroutineContext[TeammateContext] 获取身份
 *     val name = coroutineContext[TeammateContext]?.identity?.agentName
 * }
 * ```
 *
 * @property identity Teammate 身份信息
 */
class TeammateContext(
    val identity: TeammateIdentity,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TeammateContext>

    /**
     * 中止标志（对标 AbortController）。
     *
     * 当 Leader 需要终止 Teammate 时，调用 [abort] 将此标志设为 true。
     * Teammate 的执行循环应定期检查 [isAborted] 以响应中止请求。
     */
    private val _isAborted = MutableStateFlow(false)

    /** 当前是否已被中止 */
    val isAborted: Boolean get() = _isAborted.value

    /** 中止此 Teammate 的执行 */
    fun abort() {
        _isAborted.value = true
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 便捷扩展函数（对标 teammate.ts 的 getAgentName() 等）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 从 [CoroutineScope] 中获取 [TeammateContext]。
 *
 * 如果当前协程未携带 TeammateContext（例如在 Leader 的作用域中），返回 null。
 */
val CoroutineScope.teammateContext: TeammateContext?
    get() = coroutineContext[TeammateContext]

/**
 * 获取当前 Teammate 的 Agent 名称。
 *
 * 便捷属性，等价于 `teammateContext?.identity?.agentName`。
 */
val CoroutineScope.agentName: String?
    get() = teammateContext?.identity?.agentName

/**
 * 获取当前 Teammate 的 Agent ID。
 *
 * 便捷属性，等价于 `teammateContext?.identity?.agentId`。
 */
val CoroutineScope.agentId: String?
    get() = teammateContext?.identity?.agentId

/**
 * 获取当前 Teammate 所属的团队名称。
 *
 * 便捷属性，等价于 `teammateContext?.identity?.teamName`。
 */
val CoroutineScope.teamName: String?
    get() = teammateContext?.identity?.teamName

/**
 * 判断当前协程是否运行在 Teammate 上下文中。
 */
val CoroutineScope.isTeammate: Boolean
    get() = teammateContext != null

/**
 * 获取当前 Teammate 上下文，如果不在 Teammate 上下文中则抛出异常。
 *
 * 对标蓝图中的 requireTeammateContext() 扩展函数。
 */
fun CoroutineScope.requireTeammateContext(): TeammateContext =
    teammateContext ?: error("Not running in a teammate context")
