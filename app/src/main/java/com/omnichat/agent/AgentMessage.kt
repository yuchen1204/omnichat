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

    private val inboxes = ConcurrentHashMap<String, MutableList<AgentMessage>>()

    /**
     * Send a message from one agent to another.
     */
    fun send(from: String, to: String, content: String) {
        val inbox = inboxes.getOrPut(to) { mutableListOf() }
        synchronized(inbox) {
            inbox.add(AgentMessage(from, to, content))
        }
    }

    /**
     * Read all messages in an agent's inbox without clearing.
     */
    fun readInbox(agentId: String): List<AgentMessage> {
        val inbox = inboxes[agentId] ?: return emptyList()
        synchronized(inbox) {
            return inbox.toList()
        }
    }

    /**
     * Read and atomically clear an agent's inbox.
     */
    fun readAndClearInbox(agentId: String): List<AgentMessage> {
        val inbox = inboxes[agentId] ?: return emptyList()
        synchronized(inbox) {
            val messages = inbox.toList()
            inbox.clear()
            return messages
        }
    }

    /**
     * Clear an agent's inbox.
     */
    fun clearInbox(agentId: String) {
        inboxes[agentId]?.let { synchronized(it) { it.clear() } }
    }

    /**
     * Clear all inboxes (used when workflow completes).
     */
    fun clearAll() {
        inboxes.clear()
    }
}
