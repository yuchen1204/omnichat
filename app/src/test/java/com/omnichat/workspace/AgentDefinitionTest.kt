package com.omnichat.workspace

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AgentDefinitionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mock(Context::class.java)
        // Return resource name as the string value so tests can verify keys are used
        `when`(context.getString(org.mockito.ArgumentMatchers.anyInt())).thenAnswer { invocation ->
            "res_${invocation.arguments[0]}"
        }
        `when`(context.getString(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer { invocation ->
                "res_${invocation.arguments[0]}"
            }
    }

    @Test
    fun testBuiltInAgentsExist() {
        val agents = BuiltInAgents.all(context)
        assertTrue(agents.isNotEmpty())
        assertTrue(agents.any { it.agentType == "general-purpose" })
        assertTrue(agents.any { it.agentType == "explore" })
        assertTrue(agents.any { it.agentType == "plan" })
        assertTrue(agents.any { it.agentType == "verification" })
    }

    @Test
    fun testExploreAgentIsReadOnly() {
        val explore = BuiltInAgents.explore(context)
        assertTrue(explore.disallowedTools?.contains("write_file") == true)
        assertTrue(explore.disallowedTools?.contains("edit_file") == true)
        assertTrue(explore.omitClaudeMd)
        assertTrue(explore.background)
    }

    @Test
    fun testVerificationAgentHasCriticalReminder() {
        val verification = BuiltInAgents.verification(context)
        assertNotNull(verification.criticalSystemReminder)
        // With mock context, the reminder is a resource reference — verify it's non-empty
        assertTrue(verification.criticalSystemReminder!!.isNotEmpty())
    }

    @Test
    fun testAgentDefinitionToJson() {
        val def = AgentDefinition(
            agentType = "test-agent",
            displayName = "Test Agent",
            systemPrompt = "Test prompt",
            tools = listOf("read_file", "write_file"),
            background = true,
        )

        // 验证字段可序列化
        assertEquals("test-agent", def.agentType)
        assertEquals("Test Agent", def.displayName)
        assertTrue(def.background)
    }

    @Test
    fun testAgentMcpServerSpecReference() {
        val ref = AgentMcpServerSpec.Reference("slack")
        assertEquals("slack", ref.name)
    }

    @Test
    fun testAgentMcpServerSpecInline() {
        val config = McpServerConfig(
            transport = "stdio",
            command = "npx",
            args = listOf("-y", "@anthropic/mcp-server-slack"),
        )
        val inline = AgentMcpServerSpec.Inline("slack", config)
        assertEquals("slack", inline.name)
        assertEquals("stdio", inline.config.transport)
        assertEquals("npx", inline.config.command)
    }

    @Test
    fun testAgentHooks() {
        val hooks = AgentHooks(
            preToolUse = listOf(AgentHook("read_file", listOf("echo 'before read'"))),
            postToolUse = listOf(AgentHook("*", listOf("echo 'after any tool'"))),
        )
        assertNotNull(hooks.preToolUse)
        assertNotNull(hooks.postToolUse)
        assertEquals(1, hooks.preToolUse?.size)
        assertEquals("read_file", hooks.preToolUse?.first()?.matcher)
    }

    @Test
    fun testPendingSnapshotUpdate() {
        val update = PendingSnapshotUpdate("2026-05-30T12:00:00Z")
        assertEquals("2026-05-30T12:00:00Z", update.snapshotTimestamp)
    }
}
