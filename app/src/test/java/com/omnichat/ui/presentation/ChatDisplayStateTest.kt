package com.omnichat.ui.presentation

import com.omnichat.data.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDisplayStateTest {

    @Test
    fun `groups contiguous tool messages and reverses display order`() {
        val state = buildChatDisplayState(
            messages = listOf(
                message(id = 1, role = "user", content = "question"),
                message(
                    id = 2,
                    role = "assistant",
                    content = "",
                    toolCallsJson = toolCallsJson("call_read" to "file_read")
                ),
                message(id = 3, role = "tool", content = "file contents", toolCallId = "call_read"),
                message(id = 4, role = "tool", content = "clock", toolCallId = "call_clock"),
                message(id = 5, role = "assistant", content = "answer")
            ),
            silentToolGroups = "",
            builtinToolGroups = mapOf("file_read" to "files")
        )

        assertEquals(5, state.visibleMessageCount)
        assertEquals("file_read", state.toolCallLookup.getValue("call_read").name)
        assertEquals("/workspace/readme.md", state.toolCallLookup.getValue("call_read").arguments.optString("path"))
        assertEquals(
            listOf("message:5", "tools:3,4", "message:2", "message:1"),
            state.items.map(::itemLabel)
        )
    }

    @Test
    fun `filters configured builtin tool groups without hiding unknown tools`() {
        val state = buildChatDisplayState(
            messages = listOf(
                message(
                    id = 1,
                    role = "assistant",
                    content = "",
                    toolCallsJson = toolCallsJson("call_read" to "file_read")
                ),
                message(id = 2, role = "tool", content = "hidden", toolCallId = "call_read"),
                message(id = 3, role = "tool", content = "visible", toolCallId = "external_call"),
                message(id = 4, role = "assistant", content = "answer")
            ),
            silentToolGroups = "files",
            builtinToolGroups = mapOf("file_read" to "files")
        )

        assertEquals(3, state.visibleMessageCount)
        assertEquals(
            listOf("message:4", "tools:3", "message:1"),
            state.items.map(::itemLabel)
        )
    }

    @Test
    fun `wildcard filters all tool output`() {
        val state = buildChatDisplayState(
            messages = listOf(
                message(id = 1, role = "user", content = "question"),
                message(id = 2, role = "tool", content = "external output", toolCallId = "external_call"),
                message(id = 3, role = "assistant", content = "answer")
            ),
            silentToolGroups = "*",
            builtinToolGroups = emptyMap()
        )

        assertEquals(2, state.visibleMessageCount)
        assertEquals(listOf("message:3", "message:1"), state.items.map(::itemLabel))
        assertTrue(state.toolCallLookup.isEmpty())
    }

    private fun message(
        id: Long,
        role: String,
        content: String,
        toolCallId: String? = null,
        toolCallsJson: String? = null
    ) = Message(
        id = id,
        sessionId = 1,
        role = role,
        content = content,
        toolCallId = toolCallId,
        toolCallsJson = toolCallsJson,
        timestamp = id
    )

    private fun toolCallsJson(vararg calls: Pair<String, String>): String = calls.joinToString(
        prefix = "[",
        postfix = "]"
    ) { (id, name) ->
        """{"id":"$id","function":{"name":"$name","arguments":"{\"path\":\"/workspace/readme.md\"}"}}"""
    }

    private fun itemLabel(item: ChatDisplayItem): String = when (item) {
        is ChatDisplayItem.MessageItem -> "message:${item.message.id}"
        is ChatDisplayItem.ToolGroupItem -> "tools:${item.messages.joinToString(",") { it.id.toString() }}"
    }
}
