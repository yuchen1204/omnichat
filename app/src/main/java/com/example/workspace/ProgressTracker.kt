package com.example.workspace

/**
 * Tracks agent progress metrics.
 * Mirrors Claude Code's ProgressTracker in LocalAgentTask.
 */
data class ProgressTracker(
    var toolUseCount: Int = 0,
    var totalTokens: Int = 0,
    val recentActivities: MutableList<ActivityEntry> = mutableListOf(),
) {
    data class ActivityEntry(
        val toolName: String,
        val description: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun recordToolUse(toolName: String, description: String = toolName) {
        toolUseCount++
        recentActivities.add(ActivityEntry(toolName, description))
        while (recentActivities.size > 5) {
            recentActivities.removeFirst()
        }
    }

    fun recordTokens(tokens: Int) {
        totalTokens += tokens
    }
}