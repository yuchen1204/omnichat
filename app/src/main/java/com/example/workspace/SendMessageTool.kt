package com.example.workspace

import android.util.Log
import com.example.data.MailboxMessage
import com.example.mcp.ToolSchemaDsl.schema
import com.example.workspace.mailbox.MailboxService
import org.json.JSONObject

/**
 * SendMessage tool -- enables inter-agent communication via MailboxService.
 *
 * Mirrors Claude Code's SendMessageTool:
 * - Send to named agent (running or completed)
 * - Send to orchestrator from sub-agent
 * - Broadcast to all agents
 *
 * Messages are persisted to Room DB via MailboxService.
 */
class SendMessageTool(
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val senderAgentName: String = "orchestrator",
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

    private suspend fun sendToAgent(agentName: String, message: String): JSONObject {
        val entry = agentRegistry.getActiveAgents().find {
            it.identity.agentName == agentName
        } ?: return errorResult("Agent '$agentName' not found")

        mailboxService.send(entry.instanceId, MailboxMessage(
            recipientAgentId = entry.instanceId,
            senderAgentName = senderAgentName,
            role = "user",
            content = message,
            source = "send_message",
        ))

        return JSONObject().apply {
            put("content", "Message sent to $agentName")
        }
    }

    private suspend fun broadcastMessage(message: String): JSONObject {
        val agents = agentRegistry.getActiveAgents()
        for (entry in agents) {
            mailboxService.send(entry.instanceId, MailboxMessage(
                recipientAgentId = entry.instanceId,
                senderAgentName = senderAgentName,
                role = "user",
                content = message,
                source = "broadcast",
            ))
        }
        return JSONObject().apply {
            put("content", "Message broadcast to ${agents.size} agents")
        }
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
