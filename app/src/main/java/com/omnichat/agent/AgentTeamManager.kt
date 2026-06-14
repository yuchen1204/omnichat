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

object AgentTeamManager {
    val inboxes = ConcurrentHashMap<String, MutableList<AgentMessage>>()
    val taskBoard = ConcurrentHashMap<String, TeamTask>()

    fun sendMessage(from: String, to: String, content: String) {
        val messages = inboxes.getOrPut(to) { mutableListOf() }
        synchronized(messages) {
            messages.add(AgentMessage(from, to, content))
        }
    }

    fun readInbox(agent: String): List<AgentMessage> {
        return inboxes[agent]?.toList() ?: emptyList()
    }

    fun clearInbox(agent: String) {
        inboxes[agent]?.clear()
    }

    fun createTask(id: String, description: String) {
        taskBoard[id] = TeamTask(id, description, null, "pending")
    }

    fun claimTask(id: String, assignee: String): Boolean {
        val task = taskBoard[id] ?: return false
        if (task.assignee != null) return false
        taskBoard[id] = task.copy(assignee = assignee, status = "in_progress")
        return true
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
