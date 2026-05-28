package com.example.workspace

import android.util.Log
import com.example.mcp.ToolSchemaDsl.schema
import org.json.JSONObject

/**
 * SendMessage tool — enables inter-agent communication.
 *
 * Mirrors Claude Code's SendMessageTool:
 * - Send to named agent (running or completed)
 * - Send to orchestrator from sub-agent
 * - Broadcast to all agents
 *
 * Messages are queued in the target agent's pendingMessages deque.
 */
class SendMessageTool(
    private val teamManager: TeamManager,
) {
    companion object {
        private const val TAG = "SendMessageTool"
        const val TOOL_NAME = "send_message"

        val TOOL_SCHEMA = schema {
            prop("to", "string", "Target agent name or '*' for broadcast.")
            prop("message", "string", "Message content to send.")
            required("to", "message")
        }
    }

    suspend fun call(args: JSONObject): JSONObject {
        val to = args.optString("to", "")
        val message = args.optString("message", "")

        if (to.isEmpty()) return errorResult("Missing 'to' parameter")
        if (message.isEmpty()) return errorResult("Missing 'message' parameter")

        return try {
            if (to == "*") {
                broadcastMessage(message)
            } else {
                sendToAgent(to, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SendMessage failed", e)
            errorResult("SendMessage failed: ${e.message}")
        }
    }

    // 路由消息到指定 Agent 的 pending 队列
    private fun sendToAgent(agentName: String, message: String): JSONObject {
        val runner = teamManager.getRunner(agentName)
            ?: return errorResult("Agent '$agentName' not found")

        runner.queuePendingMessage(AgentMessage(
            role = "user",
            content = message,
            source = "send_message",
        ))

        return JSONObject().apply {
            put("content", "Message sent to $agentName")
        }
    }

    // 广播消息到所有活跃 Agent 的 pending 队列
    private fun broadcastMessage(message: String): JSONObject {
        val runners = teamManager.getAllRunners()
        for ((name, runner) in runners) {
            runner.queuePendingMessage(AgentMessage(
                role = "user",
                content = message,
                source = "broadcast",
            ))
        }
        return JSONObject().apply {
            put("content", "Message broadcast to ${runners.size} agents")
        }
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
