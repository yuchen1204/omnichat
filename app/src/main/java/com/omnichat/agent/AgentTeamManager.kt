package com.omnichat.agent

import java.util.concurrent.ConcurrentHashMap

data class AgentMessage(
    val from: String,
    val to: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TeamTask(
    val id: String,
    val description: String,
    val assignee: String?,
    val status: String // pending, in_progress, completed
)

/**
 * In-memory agent team manager for inter-agent messaging and task board.
 *
 * **Limitation**: All data is stored in memory and lost on process death.
 * This is acceptable for short-lived multi-agent coordination tasks.
 * Long-running workflows should use persistent storage (Room) for critical state.
 */
object AgentTeamManager {
    val inboxes = ConcurrentHashMap<String, MutableList<AgentMessage>>()
    val taskBoard = ConcurrentHashMap<String, TeamTask>()

    /** Clear all in-memory state (e.g., on app restart). */
    fun clearAll() {
        inboxes.clear()
        taskBoard.clear()
    }

    fun sendMessage(from: String, to: String, content: String) {
        val messages = inboxes.getOrPut(to) { mutableListOf() }
        synchronized(messages) {
            messages.add(AgentMessage(from, to, content))
        }
    }

    fun readInbox(agent: String): List<AgentMessage> {
        val messages = inboxes[agent] ?: return emptyList()
        synchronized(messages) { return messages.toList() }
    }

    /**
     * 原子地读取并清空收件箱，避免 read-then-clear 竞态导致消息丢失。
     */
    fun readAndClearInbox(agent: String): List<AgentMessage> {
        val messages = inboxes[agent] ?: return emptyList()
        synchronized(messages) {
            val snapshot = messages.toList()
            messages.clear()
            return snapshot
        }
    }

    fun clearInbox(agent: String) {
        val messages = inboxes[agent] ?: return
        synchronized(messages) { messages.clear() }
    }

    fun createTask(id: String, description: String) {
        taskBoard[id] = TeamTask(id, description, null, "pending")
    }

    fun claimTask(id: String, assignee: String): Boolean {
        var claimed = false
        taskBoard.compute(id) { _, existing ->
            if (existing != null && existing.assignee == null) {
                claimed = true
                existing.copy(assignee = assignee, status = "in_progress")
            } else {
                existing
            }
        }
        return claimed
    }

    fun completeTask(id: String): Boolean {
        val task = taskBoard[id] ?: return false
        taskBoard[id] = task.copy(status = "completed")
        return true
    }
    
    fun listTasks(): List<TeamTask> {
        return taskBoard.values.toList()
    }
}
