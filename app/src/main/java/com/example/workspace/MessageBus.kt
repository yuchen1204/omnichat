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

    /**
     * FIX (Bug #11): mutate 状态时统一加锁，消除 removeInbox 与 getOrCreateInbox
     * 之间的 check-then-act 竞态。原实现下，T1 进入 getOrCreateInbox 已通过
     * removedInboxes 检查；T2 调用 removeInbox 添加标记并关闭旧 channel；
     * T1 继续 computeIfAbsent，重建一个无人消费的 live channel，
     * 后续 send() 累积消息直到 OOM。
     */
    private val inboxLock = Any()

    fun getOrCreateInbox(agentName: String): Channel<TeamMessage> {
        synchronized(inboxLock) {
            // WHY: 已移除的收件箱不再重建，防止死后重建导致内存泄漏
            if (agentName in removedInboxes) {
                return Channel<TeamMessage>(1).also { it.close() }
            }
            return inboxes.getOrPut(agentName) {
                // WHY: 256 容量足够应对突发流量（广播 + 工具调用结果），同时限制最坏情况内存。
                Channel(256)
            }
        }
    }

    // WHY: 显式为新启动的 Agent 创建收件箱，允许复用之前死掉的 Agent 名称。
    // 在 spawnTeammate 中调用，清除 removedInboxes 标记后重建 Channel。
    fun explicitCreateInbox(agentName: String) {
        synchronized(inboxLock) {
            removedInboxes.remove(agentName)
            inboxes.getOrPut(agentName) { Channel(256) }
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
        synchronized(inboxLock) {
            removedInboxes.add(agentName)
            inboxes.remove(agentName)?.close()
        }
    }

    fun clear() {
        synchronized(inboxLock) {
            inboxes.values.forEach { it.close() }
            inboxes.clear()
            removedInboxes.clear()
        }
    }
}
