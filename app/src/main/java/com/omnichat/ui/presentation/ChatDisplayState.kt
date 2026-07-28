package com.omnichat.ui.presentation

import com.omnichat.data.Message
import com.omnichat.mcp.McpRuntimeManager
import org.json.JSONArray
import org.json.JSONObject

sealed interface ChatDisplayItem {
    data class MessageItem(val message: Message) : ChatDisplayItem
    data class ToolGroupItem(val messages: List<Message>) : ChatDisplayItem
}

data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

data class ChatDisplayState(
    val items: List<ChatDisplayItem> = emptyList(),
    val visibleMessageCount: Int = 0,
    val toolCallLookup: Map<String, ToolCallInfo> = emptyMap()
)

/**
 * Prepares the persisted message list for the reverse-layout chat UI.
 *
 * This is intentionally outside Compose so database emissions do not make the
 * composition phase repeatedly parse tool-call JSON or re-group the full session.
 */
fun buildChatDisplayState(
    messages: List<Message>,
    silentToolGroups: String,
    builtinToolGroups: Map<String, String> = McpRuntimeManager.builtinToolGroups
): ChatDisplayState {
    val toolCallLookup = buildToolCallLookup(messages)
    val silentGroups = silentToolGroups
        .split(",")
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    val silenceAllTools = "*" in silentGroups
    val itemsInChronologicalOrder = mutableListOf<ChatDisplayItem>()
    val currentToolGroup = mutableListOf<Message>()
    var visibleMessageCount = 0

    fun flushToolGroup() {
        if (currentToolGroup.isNotEmpty()) {
            itemsInChronologicalOrder += ChatDisplayItem.ToolGroupItem(currentToolGroup.toList())
            currentToolGroup.clear()
        }
    }

    messages.forEach { message ->
        if (message.role != "tool") {
            flushToolGroup()
            itemsInChronologicalOrder += ChatDisplayItem.MessageItem(message)
            visibleMessageCount++
            return@forEach
        }

        val toolName = message.toolCallId?.let(toolCallLookup::get)?.name
        val group = toolName?.let(builtinToolGroups::get)
        val shouldSilence = silenceAllTools || (group != null && group in silentGroups)
        if (shouldSilence) {
            // A hidden tool breaks the contiguous visible group, matching the
            // previous presentation behavior.
            flushToolGroup()
        } else {
            currentToolGroup += message
            visibleMessageCount++
        }
    }
    flushToolGroup()

    return ChatDisplayState(
        items = itemsInChronologicalOrder.asReversed(),
        visibleMessageCount = visibleMessageCount,
        toolCallLookup = toolCallLookup
    )
}

fun buildToolCallLookup(messages: Iterable<Message>): Map<String, ToolCallInfo> {
    val lookup = mutableMapOf<String, ToolCallInfo>()
    messages.forEach { message ->
        if (message.role != "assistant" || message.toolCallsJson.isNullOrBlank()) {
            return@forEach
        }

        try {
            val calls = JSONArray(message.toolCallsJson)
            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                val id = call.optString("id")
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (id.isEmpty() || name.isEmpty()) continue

                val arguments = try {
                    JSONObject(function.optString("arguments", "{}"))
                } catch (_: Exception) {
                    JSONObject()
                }
                lookup[id] = ToolCallInfo(id, name, arguments)
            }
        } catch (_: Exception) {
            // Invalid historical tool payloads should not prevent rendering.
        }
    }
    return lookup
}
