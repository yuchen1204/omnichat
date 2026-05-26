package com.example.workspace

import android.util.Log
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

sealed class TeamMessage {
    abstract val from: String
    abstract val timestamp: Long

    data class Text(
        override val from: String,
        val content: String,
        val summary: String = "",
        val color: String = "",
        val imagePath: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class IdleNotification(
        override val from: String,
        val idleReason: IdleReason = IdleReason.AVAILABLE,
        val summary: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class ShutdownRequest(
        override val from: String,
        val requestId: String,
        val reason: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class ShutdownResponse(
        override val from: String,
        val requestId: String,
        val approved: Boolean,
        val reason: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class TaskAssignment(
        override val from: String,
        val taskId: String,
        val subject: String,
        val description: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class ResultReport(
        override val from: String,
        val taskId: String,
        val result: String,
        val success: Boolean = true,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class PermissionRequest(
        override val from: String,
        val requestId: String,
        val toolName: String,
        val description: String = "",
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data class PermissionResponse(
        override val from: String,
        val requestId: String,
        val approved: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : TeamMessage()

    data object Wakeup : TeamMessage() {
        override val from: String = "system"
        override val timestamp: Long = System.currentTimeMillis()
    }
}

enum class IdleReason {
    AVAILABLE,
    INTERRUPTED,
    FAILED
}

class MessageBus {
    private val inboxes = ConcurrentHashMap<String, Channel<TeamMessage>>()

    // WHY: 记录已移除的收件箱，防止死后重建导致内存泄漏或发送方阻塞。
    // 原实现 removeInbox 后 getOrCreateInbox 会为已死亡的 agent 重建 Channel，
    // 但没有消费者读取，导致 send() 挂起（RENDEZVOUS 模式）或内存增长（缓冲模式）。
    private val removedInboxes = ConcurrentHashMap.newKeySet<String>()

    fun getOrCreateInbox(agentName: String): Channel<TeamMessage> {
        // WHY: 已移除的收件箱不再重建，防止死后重建导致内存泄漏
        if (agentName in removedInboxes) {
            return Channel<TeamMessage>(1).also { it.close() } // 返回已关闭的 Channel，send() 立即抛出 ClosedSendChannelException
        }
        return inboxes.computeIfAbsent(agentName) {
            // WHY: 使用 Channel(64) 替代 Channel(1000)。
            // capacity=1000 在高并发场景下（如广播、大量工具调用结果）会累积大量
            // 未消费消息导致 OOM。64 是足够应对突发流量的缓冲区大小，同时限制了
            // 最坏情况下的内存占用。消费端使用 receive() 挂起等待，不会丢失消息。
            Channel(256)
        }
    }

    // WHY: 显式为新启动的 Agent 创建收件箱，允许复用之前死掉的 Agent 名称。
    // 在 spawnTeammate 中调用，清除 removedInboxes 标记后重建 Channel。
    fun explicitCreateInbox(agentName: String) {
        removedInboxes.remove(agentName)
        inboxes.computeIfAbsent(agentName) {
            Channel(256)
        }
    }

    suspend fun send(recipientName: String, message: TeamMessage) {
        try {
            getOrCreateInbox(recipientName).send(message)
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            Log.w("MessageBus", "Channel closed for $recipientName, message dropped")
        }
    }

    suspend fun broadcast(
        senderName: String,
        message: TeamMessage,
        teamMembers: List<String>
    ) {
        for (member in teamMembers) {
            if (member != senderName) {
                val channel = getOrCreateInbox(member)
                val result = channel.trySend(message)
                if (result.isFailure) {
                    // WHY: 区分 Channel 已关闭和 Channel 已满两种失败原因（Bug #28）。
                    // 两者都导致 trySend 返回失败，但原因不同：关闭表示 Agent 已死亡，
                    // 已满表示消费者处理不过来。日志区分有助于快速定位问题。
                    val reason = if (channel.isClosedForSend) "closed" else "full"
                    Log.w("MessageBus", "Channel $reason for $member, message dropped")
                }
            }
        }
    }

    suspend fun receive(agentName: String): TeamMessage {
        return getOrCreateInbox(agentName).receive()
    }

    fun tryReceive(agentName: String): TeamMessage? {
        return getOrCreateInbox(agentName).tryReceive().getOrNull()
    }

    suspend fun requeue(recipientName: String, message: TeamMessage) {
        try {
            getOrCreateInbox(recipientName).send(message)
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            Log.w("MessageBus", "Channel closed for $recipientName, requeue dropped")
        }
    }

    fun removeInbox(agentName: String) {
        removedInboxes.add(agentName)
        inboxes.remove(agentName)?.close()
    }

    fun clear() {
        inboxes.values.forEach { it.close() }
        inboxes.clear()
        removedInboxes.clear()
    }
}
