package com.example.workspace.mailbox

import android.util.Log
import com.example.data.AppRepository
import com.example.data.MailboxMessage

/**
 * 邮箱服务 — Agent 间通信的 Room DB 后端。
 *
 * 替代 AgentRunner 中的 pendingMessages deque 和 interventionQueue。
 * 所有 Agent 间通信通过 MailboxService 进行，支持：
 * - 持久化消息队列（应用崩溃后可恢复）
 * - 投递确认（delivered 标志）
 * - 消息历史查询
 * - 广播（遍历 AgentRegistry 发送）
 *
 * @property repository 数据仓库
 */
class MailboxService(
    private val repository: AppRepository,
) {
    companion object {
        private const val TAG = "MailboxService"
        /** 每个 Agent 的未投递消息上限，超过后删除最旧的已投递消息 */
        private const val MAX_UNDELIVERED = 1000
    }

    /**
     * 向指定 Agent 发送消息。
     *
     * @param recipientInstanceId 目标 Agent 的 AgentInstance DB ID
     * @param message 要发送的消息（id 和 delivered 字段会被覆盖）
     */
    suspend fun send(recipientInstanceId: Long, message: MailboxMessage) {
        repository.insertMailboxMessage(message.copy(
            recipientAgentId = recipientInstanceId,
            delivered = false,
            createdAt = System.currentTimeMillis(),
        ))
        Log.d(TAG, "Sent message to agent $recipientInstanceId from ${message.senderAgentName}")
    }

    /**
     * 取出并标记所有未投递消息（原子操作）。
     *
     * 调用后这些消息的 delivered 标志被设为 true，
     * 后续调用不会重复返回。
     *
     * @param agentInstanceId Agent 的 AgentInstance DB ID
     * @return 未投递的消息列表，按时间顺序
     */
    suspend fun drain(agentInstanceId: Long): List<MailboxMessage> {
        val messages = repository.getUndeliveredMailboxMessages(agentInstanceId)
        for (msg in messages) {
            repository.markMailboxDelivered(msg.id)
        }
        if (messages.isNotEmpty()) {
            Log.d(TAG, "Drained ${messages.size} messages for agent $agentInstanceId")
        }
        return messages
    }

    /**
     * 检查是否有未投递消息（非阻塞）。
     */
    suspend fun hasUndelivered(agentInstanceId: Long): Boolean {
        return repository.countUndeliveredMailboxMessages(agentInstanceId) > 0
    }

    /**
     * 获取消息历史。
     */
    suspend fun getHistory(agentInstanceId: Long): List<MailboxMessage> {
        return repository.getMailboxHistory(agentInstanceId)
    }
}
