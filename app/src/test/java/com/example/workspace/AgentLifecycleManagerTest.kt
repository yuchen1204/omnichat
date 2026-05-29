package com.example.workspace

import com.example.workspace.lifecycle.AgentLifecycleManager
import org.junit.Assert.*
import org.junit.Test

class AgentLifecycleManagerTest {

    private fun makeManager(): AgentLifecycleManager {
        val identity = TeammateIdentity(agentId = "test", agentName = "test", teamName = "test-team")
        return AgentLifecycleManager(identity)
    }

    @Test
    fun `initial state is IDLE and not aborted`() {
        val manager = makeManager()
        assertEquals(AgentStatus.IDLE, manager.status)
        assertFalse(manager.isAborted())
        assertFalse(manager.isTurnAborted())
    }

    @Test
    fun `abort sets both lifecycle and turn abort`() {
        val manager = makeManager()
        manager.abort()
        assertTrue(manager.isAborted())
        assertTrue(manager.isTurnAborted())
    }

    @Test
    fun `abortTurn sets only turn abort`() {
        val manager = makeManager()
        manager.abortTurn()
        assertFalse(manager.isAborted())
        assertTrue(manager.isTurnAborted())
    }

    @Test
    fun `resetTurn clears turn abort`() {
        val manager = makeManager()
        manager.abortTurn()
        assertTrue(manager.isTurnAborted())
        manager.resetTurn()
        assertFalse(manager.isTurnAborted())
    }

    @Test
    fun `transitionTo updates status and calls callback`() {
        var callbackStatus: AgentStatus? = null
        val identity = TeammateIdentity(agentId = "test", agentName = "test", teamName = "test-team")
        val manager = AgentLifecycleManager(identity) { callbackStatus = it }

        manager.transitionTo(AgentStatus.STREAMING)
        assertEquals(AgentStatus.STREAMING, manager.status)
        assertEquals(AgentStatus.STREAMING, callbackStatus)
    }
}
