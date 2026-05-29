package com.example.workspace

import com.example.workspace.lifecycle.AgentLifecycleManager
import org.junit.Assert.*
import org.junit.Test

class AgentRegistryTest {

    private fun makeEntry(name: String, teamName: String = "test-team"): AgentRegistry.AgentEntry {
        val identity = TeammateIdentity(agentId = "$name@$teamName", agentName = name, teamName = teamName)
        return AgentRegistry.AgentEntry(
            identity = identity,
            lifecycle = AgentLifecycleManager(identity),
            instanceId = 0L,
        )
    }

    @Test
    fun `register and get agent`() {
        val registry = AgentRegistry()
        val entry = makeEntry("Agent1")
        assertTrue(registry.register(entry))
        assertSame(entry, registry.get("Agent1@test-team"))
    }

    @Test
    fun `register duplicate returns false`() {
        val registry = AgentRegistry()
        val entry = makeEntry("Agent1")
        assertTrue(registry.register(entry))
        assertFalse(registry.register(entry))
    }

    @Test
    fun `unregister removes agent`() {
        val registry = AgentRegistry()
        val entry = makeEntry("Agent1")
        registry.register(entry)
        registry.unregister("Agent1@test-team")
        assertNull(registry.get("Agent1@test-team"))
    }

    @Test
    fun `getActiveAgents returns all registered`() {
        val registry = AgentRegistry()
        registry.register(makeEntry("A1"))
        registry.register(makeEntry("A2"))
        assertEquals(2, registry.getActiveAgents().size)
    }

    @Test
    fun `contains checks registration`() {
        val registry = AgentRegistry()
        assertFalse(registry.contains("Agent1@test-team"))
        registry.register(makeEntry("Agent1"))
        assertTrue(registry.contains("Agent1@test-team"))
    }

    @Test
    fun `size returns count`() {
        val registry = AgentRegistry()
        assertEquals(0, registry.size())
        registry.register(makeEntry("A1"))
        assertEquals(1, registry.size())
        registry.register(makeEntry("A2"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `clear removes all agents`() {
        val registry = AgentRegistry()
        registry.register(makeEntry("A1"))
        registry.register(makeEntry("A2"))
        registry.clear()
        assertEquals(0, registry.size())
    }
}
