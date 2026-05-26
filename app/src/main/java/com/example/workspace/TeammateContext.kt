package com.example.workspace

import kotlinx.coroutines.CoroutineScope
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
 * 对标 Claude Code 的 TeammateIdentity（通过 AsyncLocalStorage 传递）。
 *
 * @property agentId 全局唯一标识，格式 "agentName@teamName"（如 "researcher@my-team"）
 * @property agentName Agent 短名称（如 "researcher"）
 * @property teamName 所属团队名称
 * @property color UI 标识色，用于区分不同 Agent 的消息气泡
 * @property agentType 角色类型（如 "researcher"、"coder"、"reviewer"）
 * @property parentSessionId 父会话 ID，用于权限桥接
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
 * ## 双 Abort 控制器（对标 Claude Code 的 dual AbortController）
 *
 * Claude Code 中 in-process teammate 有两个 AbortController：
 * - **lifecycle controller**：杀死整个 teammate（killTeammate 调用）
 * - **per-turn controller**：中断当前工作，teammate 返回 idle 状态（Escape 键）
 *
 * 本类实现了相同的双层中止机制：
 * - [abort] / [isAborted]：生命周期中止，teammate 整个退出
 * - [abortCurrentTurn] / [isCurrentTurnAborted]：当前轮次中止，teammate 回到 idle
 * - [resetTurnAbort]：每轮开始时重置 turn abort 标志
 *
 * @property identity Teammate 身份信息
 * @property permissionMode 权限模式
 */
class TeammateContext(
    val identity: TeammateIdentity,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TeammateContext>

    // ─── 生命周期 Abort（杀死整个 teammate）───

    /**
     * 生命周期中止标志。
     *
     * 当 Leader 需要终止 Teammate 时，调用 [abort] 将此标志设为 true。
     * Teammate 的执行循环应定期检查 [isAborted] 以响应中止请求。
     * 一旦设置为 true，teammate 应立即退出整个执行循环。
     */
    // WHY: 使用 AtomicBoolean 替代 MutableStateFlow 作为 abort 标志。
    // MutableStateFlow.value 的写操作在多线程下不是原子的（先读后写），
    // 而 abort() 同时写 _isAborted 和 _isCurrentTurnAborted，存在撕裂写风险。
    // AtomicBoolean.compareAndSet 保证原子性和可见性。
    private val _isAborted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前是否已被中止（生命周期级别） */
    val isAborted: Boolean get() = _isAborted.get()

    /** 中止此 Teammate 的执行（生命周期级别，teammate 将整个退出） */
    fun abort() {
        // WHY: 使用 lazySet 而非 compareAndSet：abort 语义是"设为 true"，
        // 多次调用是幂等的，不需要 CAS 的原子条件判断，lazySet 性能更好且保证最终可见性。
        _isAborted.lazySet(true)
        _isCurrentTurnAborted.lazySet(true)
    }

    // ─── 当前轮次 Abort（中断当前工作，返回 idle）───

    /**
     * 当前轮次中止标志。
     *
     * 对标 Claude Code 的 per-turn AbortController。
     * 用户按 Escape 或 Leader 中断当前工作时设置。
     * Teammate 完成当前工具调用后回到 idle 状态，而非整个退出。
     *
     * 每轮开始时通过 [resetTurnAbort] 重置。
     */
    // WHY: 与 _isAborted 一致，使用 AtomicBoolean 替代 MutableStateFlow（同上理由）
    private val _isCurrentTurnAborted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前轮次是否已被中止 */
    val isCurrentTurnAborted: Boolean get() = _isCurrentTurnAborted.get()

    /** 中止当前轮次的工作（teammate 将回到 idle 状态，而非退出） */
    fun abortCurrentTurn() {
        _isCurrentTurnAborted.lazySet(true)
    }

    /** 重置当前轮次的中止标志（每轮 runTurn 开始时调用） */
    fun resetTurnAbort() {
        _isCurrentTurnAborted.lazySet(false)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 便捷扩展函数
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
 */
val CoroutineScope.agentName: String?
    get() = teammateContext?.identity?.agentName

/**
 * 获取当前 Teammate 的 Agent ID。
 */
val CoroutineScope.agentId: String?
    get() = teammateContext?.identity?.agentId

/**
 * 获取当前 Teammate 所属的团队名称。
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
 */
fun CoroutineScope.requireTeammateContext(): TeammateContext =
    teammateContext ?: error("Not running in a teammate context")
