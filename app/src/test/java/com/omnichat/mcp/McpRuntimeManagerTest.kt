package com.omnichat.mcp

import com.omnichat.data.McpServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRuntimeManagerTest {
    private val exaUrl = "https://mcp.exa.ai/mcp"

    @Test
    fun noUserServersDoesNotConflict() {
        assertFalse(McpRuntimeManager.hasExaUrlConflict(emptyList()))
    }

    @Test
    fun sameUrlConflictsRegardlessOfNameOrEnabledState() {
        assertTrue(
            McpRuntimeManager.hasExaUrlConflict(
                listOf(McpServer(name = "anything", command = "  $exaUrl  ", isEnabled = false))
            )
        )
    }

    @Test
    fun differentUrlDoesNotConflict() {
        assertFalse(
            McpRuntimeManager.hasExaUrlConflict(
                listOf(McpServer(name = "Exa", command = "https://example.com/mcp"))
            )
        )
    }

    @Test
    fun sameNameWithDifferentUrlDoesNotConflict() {
        assertFalse(
            McpRuntimeManager.hasExaUrlConflict(
                listOf(McpServer(name = "Exa", command = "https://example.com/other"))
            )
        )
    }
}
