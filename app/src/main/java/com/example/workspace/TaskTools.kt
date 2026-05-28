package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import com.example.data.TeamTask
import com.example.mcp.ToolSchemaDsl.schema
import org.json.JSONObject

/**
 * Task management tools — enable agents to coordinate via a shared task list.
 * Mirrors Claude Code's TaskCreate/TaskGet/TaskList/TaskUpdate tools.
 */
class TaskTools(private val repository: AppRepository, private val teamName: String) {

    companion object {
        private const val TAG = "TaskTools"
        const val TASK_CREATE = "task_create"
        const val TASK_GET = "task_get"
        const val TASK_LIST = "task_list"
        const val TASK_UPDATE = "task_update"

        val SCHEMA_CREATE = schema {
            prop("subject", "string", "Task subject/title.")
            prop("description", "string", "Detailed task description.")
            prop("intended_agent", "string", "Agent name this task is intended for.")
            required("subject")
        }

        val SCHEMA_GET = schema {
            prop("id", "number", "Task ID to retrieve.")
            required("id")
        }

        val SCHEMA_LIST = schema { }

        val SCHEMA_UPDATE = schema {
            prop("id", "number", "Task ID to update.")
            prop("status", "string", "New status: PENDING, IN_PROGRESS, COMPLETED, FAILED.")
            prop("owner", "string", "Agent name claiming this task.")
            required("id")
        }
    }

    suspend fun callTool(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            TASK_CREATE -> createTask(args)
            TASK_GET -> getTask(args)
            TASK_LIST -> listTasks()
            TASK_UPDATE -> updateTask(args)
            else -> errorResult("Unknown task tool: $toolName")
        }
    }

    private suspend fun createTask(args: JSONObject): JSONObject {
        val subject = args.optString("subject", "")
        val description = args.optString("description", "")
        val intendedAgent = args.optString("intended_agent", "").ifEmpty { null }

        if (subject.isEmpty()) return errorResult("Missing 'subject'")

        val task = TeamTask(
            teamName = teamName,
            subject = subject,
            description = description,
            status = "PENDING",
            intendedAgent = intendedAgent,
        )
        val id = repository.insertTeamTask(task)
        return JSONObject().apply {
            put("content", "Task created: $subject (id=$id)")
        }
    }

    private suspend fun getTask(args: JSONObject): JSONObject {
        val taskId = args.optLong("id", -1)
        if (taskId < 0) return errorResult("Missing 'id'")
        val task = repository.getTeamTaskById(taskId)
            ?: return errorResult("Task $taskId not found")
        return JSONObject().apply {
            put("content", taskToJson(task).toString())
        }
    }

    private suspend fun listTasks(): JSONObject {
        val tasks = repository.getTeamTasksByTeam(teamName)
        val arr = tasks.map { taskToJson(it) }
        return JSONObject().apply {
            put("content", org.json.JSONArray(arr).toString())
        }
    }

    private suspend fun updateTask(args: JSONObject): JSONObject {
        val taskId = args.optLong("id", -1)
        val status = args.optString("status", "")
        val owner = args.optString("owner", "").ifEmpty { null }

        if (taskId < 0) return errorResult("Missing 'id'")
        if (status.isEmpty() && owner == null) return errorResult("Nothing to update")

        val task = repository.getTeamTaskById(taskId)
            ?: return errorResult("Task $taskId not found")

        val updated = task.copy(
            status = status.ifEmpty { task.status },
            owner = owner ?: task.owner,
            updatedAt = System.currentTimeMillis(),
        )
        repository.updateTeamTask(updated)
        return JSONObject().apply {
            put("content", "Task $taskId updated: status=${updated.status}")
        }
    }

    private fun taskToJson(task: TeamTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("subject", task.subject)
        put("description", task.description)
        put("status", task.status)
        put("owner", task.owner ?: JSONObject.NULL)
        put("intendedAgent", task.intendedAgent ?: JSONObject.NULL)
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
