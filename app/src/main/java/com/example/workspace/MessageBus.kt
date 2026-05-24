package com.example.workspace

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

// ═══════════════════════════════════════════════════════════════════════════════
// 结构化消息类型（对标 teammateMailbox.ts）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Agent 间通信的结构化消息密封类。
 *
 * 每条消息携带 [from]（发送方名称）和 [timestamp]（发送时间戳）。
 * 对标 Claude Code 的 teammateMailbox.ts 中定义的消息类型。
 */
sealed class TeamMessage {
    abstract val from: String
    abstract val timestamp: Long

    /**
     * 纯文本消息。
     *
     * Agent 间的自由文本通信，可附带摘要和 UI 标识色。
     *
     * @property from 发送方 Agent 名称
     * @property content 消息正文
     * @property summary 可选摘要，用于 UI 快速预览
     * @property color 发送方的 UI 标识色
     * @property timestamp 发送时间戳
     */
    data class Text(
        override val from: String,
        val content: String,
        val summary: String = "",
        val color: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 空闲通知。
     *
     * Teammate 完成当前工作后向 Leader 发送此通知，表明可以接受新任务。
     * 对标 inProcessRunner.ts 中的 idle_notification。
     *
     * @property from 发送方 Agent 名称
     * @property idleReason 空闲原因
     * @property summary 可选的工作摘要
     * @property timestamp 发送时间戳
     */
    data class IdleNotification(
        override val from: String,
        val idleReason: IdleReason = IdleReason.AVAILABLE,
        val summary: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 关闭请求。
     *
     * Leader 向 Teammate 发送的关闭请求。Teammate 收到后由 LLM 决定是否批准。
     * 对标 SendMessageTool.ts 中的 shutdown_request。
     *
     * @property from 发送方名称（通常为 Leader）
     * @property requestId 请求唯一标识
     * @property reason 关闭原因
     * @property timestamp 发送时间戳
     */
    data class ShutdownRequest(
        override val from: String,
        val requestId: String,
        val reason: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 关闭响应。
     *
     * Teammate 对关闭请求的响应，由 LLM 决定批准或拒绝。
     *
     * @property from 发送方 Agent 名称
     * @property requestId 对应的请求 ID
     * @property approved 是否批准关闭
     * @property reason 拒绝原因（仅当 approved=false 时有值）
     * @property timestamp 发送时间戳
     */
    data class ShutdownResponse(
        override val from: String,
        val requestId: String,
        val approved: Boolean,
        val reason: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 任务分配。
     *
     * Leader 向 Teammate 分配的具体任务。包含任务主题和详细描述。
     * 对标 inProcessRunner.ts 中的 task_assignment。
     *
     * @property from 发送方名称（通常为 Leader）
     * @property taskId 任务 ID（对应 TeamTask.id）
     * @property subject 任务主题
     * @property description 任务详细描述
     * @property timestamp 发送时间戳
     */
    data class TaskAssignment(
        override val from: String,
        val taskId: String,
        val subject: String,
        val description: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 结果上报。
     *
     * Teammate 完成任务后向 Leader 上报执行结果。
     * 对标旧版 AgentMessageBus 中的 result report 格式。
     *
     * @property from 发送方 Agent 名称
     * @property taskId 对应的任务 ID
     * @property result 执行结果描述
     * @property success 任务是否成功完成
     * @property timestamp 发送时间戳
     */
    /**
     * 结果上报。
     *
     * Teammate 完成任务后向 Leader 上报执行结果。
     *
     * @property from 发送方 Agent 名称
     * @property taskId 对应的任务 ID
     * @property result 执行结果描述
     * @property success 任务是否成功完成
     * @property timestamp 发送时间戳
     */
    data class ResultReport(
        override val from: String,
        val taskId: String,
        val result: String,
        val success: Boolean = true,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 权限请求。
     *
     * Teammate 需要使用敏感工具时向 Leader 发送权限请求。
     * 对标 Claude Code 的 permission_request 消息类型。
     *
     * @property from 发送方 Agent 名称
     * @property requestId 请求唯一标识
     * @property toolName 请求使用的工具名称
     * @property description 工具用途描述
     * @property timestamp 发送时间戳
     */
    data class PermissionRequest(
        override val from: String,
        val requestId: String,
        val toolName: String,
        val description: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    /**
     * 权限响应。
     *
     * Leader 对权限请求的审批结果。
     * 对标 Claude Code 的 permission_response 消息类型。
     *
     * @property from 发送方名称（通常为 Leader）
     * @property requestId 对应的请求 ID
     * @property approved 是否批准
     * @property timestamp 发送时间戳
     */
    data class PermissionResponse(
        override val from: String,
        val requestId: String,
        val approved: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()
}

/**
 * Teammate 空闲原因枚举。
 *
 * 对标 Claude Code 的 idle_reason，用于区分 Teammate 进入空闲状态的不同场景。
 */
enum class IdleReason {
    /** 正常完成工作，可接受新任务 */
    AVAILABLE,
    /** 被用户中断（如 Escape 键） */
    INTERRUPTED,
    /** 执行过程中发生错误 */
    FAILED
}

// ═══════════════════════════════════════════════════════════════════════════════
// 消息总线（替代文件邮箱）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Channel-based 消息总线。
 *
 * 替代 Claude Code 的文件邮箱系统（~/.claude/teams/{team}/inboxes/{agent}.json）。
 * 每个 Agent 拥有一个独立的 [Channel] 作为收件箱，所有消息同时通过 [globalEvents]
 * 广播给 Leader 监听。
 *
 * 核心设计：
 * - [getOrCreateInbox]：按需创建 Agent 收件箱
 * - [send]：点对点发送，同时广播到全局事件流
 * - [broadcast]：向除发送方外的所有团队成员发送消息
 * - [receive]：挂起式接收（对标 readMailbox 的阻塞读取）
 * - [tryReceive]：非阻塞检查（对标 readMailbox 的轮询逻辑）
 *
 * 线程安全：[inboxes] 使用 [ConcurrentHashMap]，[Channel] 本身是线程安全的。
 */
class MessageBus {
    /** 每个 Agent 一个 Channel 收件箱（对标 inboxes/{agent}.json） */
    private val inboxes = ConcurrentHashMap<String, Channel<TeamMessage>>()

    /** 全局事件流，Leader 通过此流监听所有消息 */
    private val _globalEvents = MutableSharedFlow<TeamMessage>(extraBufferCapacity = 64)
    val globalEvents: SharedFlow<TeamMessage> = _globalEvents

    /**
     * 获取或创建指定 Agent 的收件箱 Channel。
     *
     * 如果该 Agent 尚无收件箱，会自动创建一个无界 Channel。
     * 无界 Channel 确保发送方不会被阻塞（与文件邮箱的异步写入语义一致）。
     *
     * @param agentName Agent 名称
     * @return 该 Agent 的收件箱 Channel
     */
    fun getOrCreateInbox(agentName: String): Channel<TeamMessage> {
        return inboxes.getOrPut(agentName) {
            Channel(Channel.UNLIMITED)
        }
    }

    /**
     * 向指定 Agent 发送消息。
     *
     * 消息会被投递到目标 Agent 的收件箱，同时广播到全局事件流。
     * 对标 writeToMailbox()。
     *
     * @param recipientName 接收方 Agent 名称
     * @param message 要发送的消息
     */
    suspend fun send(recipientName: String, message: TeamMessage) {
        getOrCreateInbox(recipientName).send(message)
        _globalEvents.emit(message)
    }

    /**
     * 向团队所有成员广播消息（排除发送方自己）。
     *
     * 对标 Claude Code 中 Leader 向所有 Teammate 广播权限更新等场景。
     *
     * @param senderName 发送方名称
     * @param message 要广播的消息
     * @param teamMembers 团队成员名称列表
     */
    suspend fun broadcast(
        senderName: String,
        message: TeamMessage,
        teamMembers: List<String>
    ) {
        for (member in teamMembers) {
            if (member != senderName) {
                send(member, message)
            }
        }
    }

    /**
     * 挂起式接收消息。
     *
     * 从指定 Agent 的收件箱中接收下一条消息，如果没有消息则挂起等待。
     * 对标 readMailbox 的阻塞读取模式。
     *
     * @param agentName 接收方 Agent 名称
     * @return 接收到的消息
     */
    suspend fun receive(agentName: String): TeamMessage {
        return getOrCreateInbox(agentName).receive()
    }

    /**
     * 非阻塞检查是否有待接收的消息。
     *
     * 对标 readMailbox 的轮询逻辑，用于 Agent 执行循环中的消息优先级检查。
     *
     * @param agentName Agent 名称
     * @return 如果有消息则返回消息，否则返回 null
     */
    fun tryReceive(agentName: String): TeamMessage? {
        return getOrCreateInbox(agentName).tryReceive().getOrNull()
    }

    /**
     * 移除指定 Agent 的收件箱。
     *
     * Teammate 退出时调用，释放 Channel 资源。
     *
     * @param agentName Agent 名称
     */
    fun removeInbox(agentName: String) {
        inboxes.remove(agentName)?.close()
    }

    /**
     * 清空所有收件箱。
     *
     * 团队解散时调用，关闭所有 Channel 并清空映射。
     */
    fun clear() {
        inboxes.values.forEach { it.close() }
        inboxes.clear()
    }
}
