package com.omnichat.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Approval request from a SubAgent to MainAgent for a file tool operation.
 */
data class AgentApprovalRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val args: JSONObject,
    val taskContext: String,
    val agentType: String,
    val sessionId: Long
)

/**
 * MainAgent's decision on an approval request.
 */
data class AgentApprovalDecision(
    val decision: String,        // "approve" or "reject"
    val reason: String,
    val alternative: String? = null
)

/**
 * Channel for SubAgent -> MainAgent approval communication.
 * SubAgent suspends on requestApproval(), MainAgent responds via respond().
 */
object AgentApprovalChannel {
    private const val APPROVAL_TIMEOUT_MS = 30_000L

    private val _pendingRequests = MutableStateFlow<List<AgentApprovalRequest>>(emptyList())
    val pendingRequests: StateFlow<List<AgentApprovalRequest>> = _pendingRequests.asStateFlow()

    private val deferreds = ConcurrentHashMap<String, CompletableDeferred<AgentApprovalDecision>>()

    /**
     * Called by McpRuntimeManager.callTool() for SubAgents in AUTO mode.
     * Suspends until MainAgent responds or timeout.
     */
    suspend fun requestApproval(request: AgentApprovalRequest): AgentApprovalDecision {
        val deferred = CompletableDeferred<AgentApprovalDecision>()
        deferreds[request.requestId] = deferred
        _pendingRequests.update { it + request }

        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            AgentApprovalDecision("reject", "Approval timed out")
        } finally {
            deferreds.remove(request.requestId)
            _pendingRequests.update { list -> list.filter { it.requestId != request.requestId } }
        }
    }

    /**
     * Called by BuiltinToolHandler.handleApproveAgentRequest().
     */
    fun respond(requestId: String, decision: AgentApprovalDecision) {
        deferreds[requestId]?.complete(decision)
    }

    /**
     * Resolve all pending as approved (e.g., when mode switches to GENERAL mid-task).
     */
    fun approveAllPending() {
        deferreds.forEach { (id, deferred) ->
            deferred.complete(AgentApprovalDecision("approve", "Mode switched to General"))
        }
    }
}
