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

/**
 * 编排器模式执行器。
 *
 * 仅 Leader（Orchestrator）可以创建 Sub-Agent。
 * Sub-Agent 通过 AgentTool 路由，同步阻塞或异步后台运行。
 */
class OrchestratorExecutor(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val parentScope: CoroutineScope,
    private val callbacks: TeamCallbacks,
    private val orchestratorConfig: ModelConfig,
    private val workspaceSessionId: Long,
    private val sandboxPath: String? = null,
) : TeammateExecutor {

    override val type = ExecutorType.ORCHESTRATOR

    companion object {
        private const val TAG = "OrchestratorExecutor"
    }

    override suspend fun spawn(config: SpawnConfig): SpawnResult {
        Log.d(TAG, "Spawning sub-agent: ${config.name}")

        val identity = TeammateIdentity(
            agentId = "${config.name}@${config.teamName}",
            agentName = config.name,
            teamName = config.teamName,
        )

        val lifecycle = AgentLifecycleManager(identity) { status ->
            callbacks.onAgentStatusChanged(config.name, status)
        }

        // Create AgentInstance in DB
        val instanceId = repository.insertAgentInstance(
            AgentInstance(
                workspaceSessionId = workspaceSessionId,
                agentName = config.name,
                agentType = config.agentDefinition.agentType,
                status = "idle",
                modelConfigId = orchestratorConfig.id,
                overrideModelId = config.modelOverride,
            )
        )

        agentRegistry.register(AgentRegistry.AgentEntry(
            identity = identity,
            lifecycle = lifecycle,
            instanceId = instanceId,
        ))

        callbacks.onAgentCreated(config.name, false)

        return SpawnResult(success = true, agentId = identity.agentId)
    }

    override suspend fun sendMessage(agentId: String, message: MailboxMessage) {
        val entry = agentRegistry.get(agentId)
            ?: return run { Log.w(TAG, "Agent $agentId not found for sendMessage") }
        mailboxService.send(entry.instanceId, message)
    }

    override suspend fun terminate(agentId: String, reason: String?): Boolean {
        val entry = agentRegistry.get(agentId) ?: return false
        entry.lifecycle.abortTurn()
        Log.d(TAG, "Terminated agent $agentId: $reason")
        return true
    }

    override suspend fun kill(agentId: String): Boolean {
        val entry = agentRegistry.get(agentId) ?: return false
        entry.lifecycle.abort()
        Log.d(TAG, "Killed agent $agentId")
        return true
    }

    override suspend fun isActive(agentId: String): Boolean {
        val entry = agentRegistry.get(agentId) ?: return false
        return !entry.lifecycle.isAborted() && entry.lifecycle.status != AgentStatus.COMPLETED
    }
}
