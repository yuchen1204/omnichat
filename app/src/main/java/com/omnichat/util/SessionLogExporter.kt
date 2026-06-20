package com.omnichat.util

import android.content.Context
import android.util.Log
import com.omnichat.agent.WorkflowUiState
import com.omnichat.agent.WorkflowStepStatus
import com.omnichat.agent.WorkflowStatus
import com.omnichat.data.Message
import com.omnichat.data.Session
import com.omnichat.ui.screens.SubAgentTaskUiState
import com.omnichat.ui.screens.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session log exporter for debugging and analysis.
 * Exports messages, SubAgent tasks, and workflow states to JSON format.
 */
object SessionLogExporter {

    private const val TAG = "SessionLogExporter"

    /**
     * Export session data to a JSON file.
     * Returns the file path if successful, null otherwise.
     */
    fun exportSessionLog(
        context: Context,
        session: Session?,
        messages: List<Message>,
        activeTasks: Map<String, SubAgentTaskUiState>,
        activeWorkflows: Map<String, WorkflowUiState>
    ): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "session_log_$timestamp.json"
            val file = File(context.cacheDir, fileName)

            val json = buildSessionJson(session, messages, activeTasks, activeWorkflows)
            file.writeText(json.toString(2))

            Log.d(TAG, "[exportSessionLog] Exported to: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "[exportSessionLog] Failed: ${e.message}")
            null
        }
    }

    private fun buildSessionJson(
        session: Session?,
        messages: List<Message>,
        activeTasks: Map<String, SubAgentTaskUiState>,
        activeWorkflows: Map<String, WorkflowUiState>
    ): JSONObject {
        return JSONObject().apply {
            put("exportTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("appVersion", getAppVersion())

            // Session info
            put("session", JSONObject().apply {
                if (session != null) {
                    put("id", session.id)
                    put("title", session.title)
                    put("createdAt", session.createdAt)
                    put("thinkingEffort", session.thinkingEffort)
                }
            })

            // Messages
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(messageToJson(msg))
                }
            })

            // Active SubAgent tasks
            put("activeTasks", JSONArray().apply {
                activeTasks.forEach { (taskId, task) ->
                    put(taskToJson(taskId, task))
                }
            })

            // Active workflows
            put("activeWorkflows", JSONArray().apply {
                activeWorkflows.forEach { (workflowId, workflow) ->
                    put(workflowToJson(workflowId, workflow))
                }
            })

            // Summary
            put("summary", JSONObject().apply {
                put("totalMessages", messages.size)
                put("userMessages", messages.count { it.role == "user" })
                put("assistantMessages", messages.count { it.role == "assistant" })
                put("toolMessages", messages.count { it.role == "tool" })
                put("activeTasks", activeTasks.size)
                put("activeWorkflows", activeWorkflows.size)
            })
        }
    }

    private fun messageToJson(msg: Message): JSONObject {
        return JSONObject().apply {
            put("id", msg.id)
            put("role", msg.role)
            put("content", msg.content.take(10000)) // Limit content size
            put("timestamp", msg.timestamp)
            put("toolCallId", msg.toolCallId)
            put("toolCallsJson", msg.toolCallsJson?.take(1000))
            put("imagePaths", msg.imagePaths)
        }
    }

    private fun taskToJson(taskId: String, task: SubAgentTaskUiState): JSONObject {
        return JSONObject().apply {
            put("taskId", taskId)
            put("taskType", task.taskType)
            put("description", task.description)
            put("status", task.status.name)
            put("progressMessage", task.progressMessage)
            put("result", task.result?.take(5000))
        }
    }

    private fun workflowToJson(workflowId: String, workflow: WorkflowUiState): JSONObject {
        return JSONObject().apply {
            put("workflowId", workflowId)
            put("mode", workflow.mode.name)
            put("status", workflow.status.name)
            put("startedAt", workflow.startedAt)
            put("completedAt", workflow.completedAt)
            put("error", workflow.error)
            put("topic", workflow.topic)
            put("agentA", workflow.agentA)
            put("agentB", workflow.agentB)
            put("currentRound", workflow.currentRound)
            put("maxRounds", workflow.maxRounds)

            // Steps
            put("steps", JSONArray().apply {
                workflow.steps.forEach { step ->
                    put(JSONObject().apply {
                        put("stepId", step.stepId)
                        put("agentType", step.agentType)
                        put("task", step.task)
                        put("status", step.status.name)
                        put("result", step.result?.take(2000))
                        put("error", step.error)
                        put("revisionCount", step.revisionCount)
                        put("lastMessageFrom", step.lastMessageFrom)
                        put("idleSince", step.idleSince)
                        put("runningSince", step.runningSince)
                    })
                }
            })

            // Idle warnings
            put("idleWarnings", JSONArray().apply {
                workflow.idleWarnings.forEach { warning ->
                    put(JSONObject().apply {
                        put("stepId", warning.stepId)
                        put("message", warning.message)
                        put("idleDurationMs", warning.idleDurationMs)
                    })
                }
            })

            // Message errors
            put("messageErrors", JSONArray().apply {
                workflow.messageErrors.forEach { error ->
                    put(JSONObject().apply {
                        put("from", error.from)
                        put("to", error.to)
                        put("error", error.error)
                        put("availableTargets", JSONArray(error.availableTargets))
                    })
                }
            })
        }
    }

    private fun getAppVersion(): String {
        return try {
            "${com.omnichat.BuildConfig.VERSION_NAME} (${com.omnichat.BuildConfig.VERSION_CODE})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
