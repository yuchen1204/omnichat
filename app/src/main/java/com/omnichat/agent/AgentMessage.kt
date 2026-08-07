package com.omnichat.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Inter-agent message for the SubAgent messaging system.
 */
data class AgentMessage(
    val from: String,
    val to: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * In-memory message bus for inter-agent communication.
 * All data is lost on process death — acceptable for sub-agent workflows.
 */
object MessageBus {

    private const val MAX_MESSAGES_PER_INBOX = 200
    private val inboxes = ConcurrentHashMap<String, MutableList<AgentMessage>>()

    /**
     * Send a message from one agent to another.
     *
     * The map lock keeps send/read-and-clear atomic so a message cannot be
     * appended to an inbox just as that inbox is being removed.
     */
    fun send(from: String, to: String, content: String) {
        synchronized(inboxes) {
            val inbox = inboxes.getOrPut(to) { mutableListOf() }
            synchronized(inbox) {
                if (inbox.size >= MAX_MESSAGES_PER_INBOX) {
                    inbox.removeAt(0)
                }
                inbox.add(AgentMessage(from, to, content))
            }
        }
    }

    /**
     * Read all messages in an agent's inbox without clearing.
     */
    fun readInbox(agentId: String): List<AgentMessage> {
        synchronized(inboxes) {
            val inbox = inboxes[agentId] ?: return emptyList()
            synchronized(inbox) {
                return inbox.toList()
            }
        }
    }

    /**
     * Read and atomically clear an agent's inbox.
     */
    fun readAndClearInbox(agentId: String): List<AgentMessage> {
        synchronized(inboxes) {
            val inbox = inboxes.remove(agentId) ?: return emptyList()
            synchronized(inbox) {
                return inbox.toList()
            }
        }
    }

    /**
     * Clear an agent's inbox.
     */
    fun clearInbox(agentId: String) {
        synchronized(inboxes) {
            inboxes.remove(agentId)
        }
    }

    /**
     * Clear all inboxes (used when workflow completes).
     */
    fun clearAll() {
        inboxes.clear()
    }
}
