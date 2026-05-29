package com.example.workspace.executor

import android.util.Log
import com.example.data.AgentInstance
import com.example.data.AppRepository
import com.example.data.MailboxMessage
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import com.example.workspace.*
import com.example.workspace.lifecycle.AgentLifecycleManager
import com.example.workspace.mailbox.MailboxService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 对等模式执行器。
 *
 * 任何 Agent 可以 spawn 和 message 任何其他 Agent。
 * 所有通信通过 MailboxService，无中心化 Leader。
 */
class PeerExecutor(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val parentScope: CoroutineScope,
    private val callbacks: TeamCallbacks,
) : TeammateExecutor {

    override val type = ExecutorType.PEER

    companion object {
        private const val TAG = "PeerExecutor"
    }

    override suspend fun spawn(config: SpawnConfig): SpawnResult {
        val agentId = "${config.name}@${config.teamName}"

        if (agentRegistry.contains(agentId)) {
            return SpawnResult(false, agentId, "Agent '$agentId' already exists")
        }

        val identity = TeammateIdentity(
            agentId = agentId,
            agentName = config.name,
            teamName = config.teamName,
        )

        val lifecycle = AgentLifecycleManager(identity) { status ->
            callbacks.onAgentStatusChanged(config.name, status)
        }

        val instanceId = repository.insertAgentInstance(
            AgentInstance(
                workspaceSessionId = 0,
                agentName = config.name,
                agentType = config.agentDefinition.agentType,
                status = "idle",
                modelConfigId = 0,
                overrideModelId = config.modelOverride,
            )
        )

        agentRegistry.register(AgentRegistry.AgentEntry(
            identity = identity,
            lifecycle = lifecycle,
            instanceId = instanceId,
        ))

        callbacks.onAgentCreated(config.name, false)

        // Launch agent execution in background
        parentScope.launch {
            try {
                lifecycle.transitionTo(AgentStatus.STREAMING)
                // Agent execution would be driven by the caller
                // For now, transition to completed when the coroutine finishes
                lifecycle.transitionTo(AgentStatus.COMPLETED)
            } catch (e: Exception) {
                Log.e(TAG, "Peer agent ${config.name} failed", e)
                lifecycle.transitionTo(AgentStatus.ERROR)
            } finally {
                agentRegistry.unregister(agentId)
            }
        }

        return SpawnResult(true, agentId)
    }

    override suspend fun sendMessage(agentId: String, message: MailboxMessage) {
        val entry = agentRegistry.get(agentId) ?: run {
            Log.w(TAG, "Agent $agentId not found")
            return
        }
        mailboxService.send(entry.instanceId, message)
    }

    override suspend fun terminate(agentId: String, reason: String?): Boolean {
        val entry = agentRegistry.get(agentId) ?: return false
        entry.lifecycle.abortTurn()
        return true
    }

    override suspend fun kill(agentId: String): Boolean {
        val entry = agentRegistry.get(agentId) ?: return false
        entry.lifecycle.abort()
        agentRegistry.unregister(agentId)
        return true
    }

    override suspend fun isActive(agentId: String): Boolean {
        val entry = agentRegistry.get(agentId) ?: return false
        return !entry.lifecycle.isAborted()
    }
}
