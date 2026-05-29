package com.example.workspace.lifecycle

import com.example.workspace.AgentStatus
import com.example.workspace.TeammateIdentity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 生命周期管理器 — 双层 Abort 控制。
 *
 * 对标 Claude Code 的 dual AbortController：
 * - lifecycle abort：杀死整个 Agent（terminate/kill 调用）
 * - turn abort：中断当前工作，Agent 返回 idle（用户中断）
 *
 * @property identity Teammate 身份信息
 * @property onStateChanged 状态变更回调
 */
class AgentLifecycleManager(
    val identity: TeammateIdentity,
    private val onStateChanged: (AgentStatus) -> Unit = {},
) {
    // 生命周期 Abort — 一旦设置，Agent 永久退出
    private val lifecycleAbort = AtomicBoolean(false)

    // 轮次 Abort — Agent 完成当前工具调用后回到 idle
    private val turnAbort = AtomicBoolean(false)

    @Volatile
    var status: AgentStatus = AgentStatus.IDLE
        private set

    /** 终止 Agent 生命周期。 */
    fun abort() {
        lifecycleAbort.lazySet(true)
        turnAbort.lazySet(true)
    }

    /** 中断当前轮次（Agent 回到 idle，不退出）。 */
    fun abortTurn() {
        turnAbort.lazySet(true)
    }

    /** 每轮 runTurn 开始时重置 turn abort 标志。 */
    fun resetTurn() {
        turnAbort.lazySet(false)
    }

    /** 是否已被生命周期级中止。 */
    fun isAborted(): Boolean = lifecycleAbort.get()

    /** 是否已被轮次级中止。 */
    fun isTurnAborted(): Boolean = turnAbort.get()

    /** 状态转换。 */
    fun transitionTo(newStatus: AgentStatus) {
        status = newStatus
        onStateChanged(newStatus)
    }
}
