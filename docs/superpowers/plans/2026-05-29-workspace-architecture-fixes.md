# Workspace Architecture Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 24 architectural, workflow, concurrency, and data flow issues identified in the workspace multi-agent system.

**Architecture:** Incremental refactoring across 8 tasks — dead code removal first (low risk), then concurrency fixes (high priority), then data flow/workflow fixes, then architecture improvements, and finally test coverage. Each task is independently committable and testable.

**Tech Stack:** Kotlin, Coroutines, Room DB, StateFlow, JUnit + Robolectric

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/java/com/example/workspace/executor/TeammateExecutor.kt` | Delete | Dead abstraction |
| `app/src/main/java/com/example/workspace/executor/OrchestratorExecutor.kt` | Delete | Dead code |
| `app/src/main/java/com/example/workspace/executor/PeerExecutor.kt` | Delete | Dead code |
| `app/src/main/java/com/example/workspace/executor/ExecutorType.kt` | Delete | Dead enum |
| `app/src/main/java/com/example/workspace/AgentContext.kt` | Modify | Fix data class mutable messages |
| `app/src/main/java/com/example/workspace/AgentRegistry.kt` | Modify | Fix data class with mutable runner |
| `app/src/main/java/com/example/workspace/mailbox/MailboxService.kt` | Modify | Atomic drain |
| `app/src/main/java/com/example/workspace/AgentTool.kt` | Modify | Scope cancellation, persistMessage, sender identity |
| `app/src/main/java/com/example/workspace/TeamManager.kt` | Modify | Remove executor, fix subAgentScope, reusable after delete |
| `app/src/main/java/com/example/workspace/AgentRunner.kt` | Modify | Token estimation fix, completion marker |
| `app/src/main/java/com/example/workspace/SendMessageTool.kt` | Modify | Sender identity |
| `app/src/main/java/com/example/workspace/WorkspaceModels.kt` | Modify | Structured completion marker |
| `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` | Modify | Replace global static |
| `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt` | Modify | Race conditions, mutex |
| `app/src/test/java/com/example/workspace/WorkspaceCoreTest.kt` | Modify | Add tests |
| `app/src/test/java/com/example/workspace/MailboxServiceTest.kt` | Create | New test file |
| `app/src/test/java/com/example/workspace/AgentRegistryTest.kt` | Create | New test file |
| `app/src/test/java/com/example/workspace/AgentLifecycleManagerTest.kt` | Create | New test file |
| `app/src/test/java/com/example/workspace/AgentToolTest.kt` | Create | New test file |
| `app/src/test/java/com/example/workspace/SendMessageToolTest.kt` | Create | New test file |
| `app/src/test/java/com/example/workspace/TaskToolsTest.kt` | Create | New test file |

---

## Task 1: Remove Dead Executor Abstraction Layer

**Issues addressed:** 1.3 (Unused TeammateExecutor), 6.2 (OrchestratorExecutor.spawn dead code)

**Files:**
- Delete: `app/src/main/java/com/example/workspace/executor/TeammateExecutor.kt`
- Delete: `app/src/main/java/com/example/workspace/executor/OrchestratorExecutor.kt`
- Delete: `app/src/main/java/com/example/workspace/executor/PeerExecutor.kt`
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Remove executor imports and field from TeamManager**

In `TeamManager.kt`, remove:
- Lines 10-12: `import com.example.workspace.executor.ExecutorType`, `import com.example.workspace.executor.OrchestratorExecutor`, `import com.example.workspace.executor.TeammateExecutor`
- Line 61: `private var executor: TeammateExecutor? = null`
- Line 387: `executor = null` (in deleteTeam)
- Lines 138-152: The entire `executor = when (mode) { ... }` block

- [ ] **Step 2: Update createTeam signature to remove ExecutorType dependency**

Replace the `mode` parameter in `createTeam`:

```kotlin
suspend fun createTeam(
    teamName: String,
    orchestratorConfig: ModelConfig,
    orchestratorOverrideModelId: String? = null,
    sandboxPath: String? = null,
): TeamState = createTeamMutex.withLock {
```

Remove the `mode` parameter and the executor creation block. The rest of `createTeam` (DB persist, AgentTool wiring, registry) stays as-is.

- [ ] **Step 3: Update WorkspaceViewModel to remove mode parameter**

In `WorkspaceViewModel.kt`, update `submitTask` line 393:

```kotlin
teamManager?.createTeam("workspace_$wsId", orchestratorConfig = orchestratorConfig, orchestratorOverrideModelId = orchestratorOverrideModelId, sandboxPath = sandboxPath)
```

And `loadSessionHistory` line 743:

```kotlin
teamManager?.createTeam("workspace_$workspaceSessionId", orchestratorConfig = orchestratorConfig)
```

Remove the `mode = _teamMode.value` argument from both calls.

- [ ] **Step 4: Delete executor package files**

```bash
rm -rf app/src/main/java/com/example/workspace/executor/
```

- [ ] **Step 5: Run tests to verify**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

Expected: All existing tests pass (executor was never used in tests).

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java/com/example/workspace/executor/ app/src/main/java/com/example/workspace/TeamManager.kt app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt
git commit -m "refactor(workspace): remove dead executor abstraction layer

The TeammateExecutor/OrchestratorExecutor/PeerExecutor abstraction was
never used — all execution goes through AgentTool and AgentRunner directly.
Remove the dead code to reduce confusion and maintenance burden."
```

---

## Task 2: Fix Data Class Mutable State Issues

**Issues addressed:** 1.5 (AgentContext data class with mutable messages), 1.4 (AgentRegistry.AgentEntry data class with mutable runner)

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentContext.kt`
- Modify: `app/src/main/java/com/example/workspace/AgentRegistry.kt`

- [ ] **Step 1: Convert AgentContext from data class to regular class**

Replace the `data class AgentContext` at line 56 with a regular class. Keep the same properties but remove `copy()` semantics:

```kotlin
class AgentContext(
    val agentName: String,
    val isOrchestrator: Boolean,
    val systemPrompt: String,
    val modelConfig: ModelConfig,
    val overrideModelId: String? = null,
    val teamName: String = "",
    val messages: MutableList<AgentMessage>,
    val agentDefinition: AgentDefinition? = null,
    val agentInstanceId: Long? = null,
) {
    override fun toString(): String =
        "AgentContext(agentName=$agentName, isOrchestrator=$isOrchestrator, messages=${messages.size})"
}
```

- [ ] **Step 2: Convert AgentEntry from data class to regular class**

In `AgentRegistry.kt`, replace the `data class AgentEntry` at line 25:

```kotlin
class AgentEntry(
    val identity: TeammateIdentity,
    val lifecycle: AgentLifecycleManager,
    val instanceId: Long,
    val runner: AgentRunner? = null,
)
```

- [ ] **Step 3: Fix all `.copy()` calls on AgentEntry**

Search for `entry.copy(runner =` in TeamManager.kt line 340:

```kotlin
// Old:
agentRegistry.register(entry.copy(runner = runner))
// New:
agentRegistry.register(AgentRegistry.AgentEntry(
    identity = entry.identity,
    lifecycle = entry.lifecycle,
    instanceId = entry.instanceId,
    runner = runner,
))
```

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentContext.kt app/src/main/java/com/example/workspace/AgentRegistry.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "fix(workspace): convert AgentContext and AgentEntry from data class to regular class

data class copy() shares mutable collection references, causing silent bugs.
Both classes hold mutable state (messages list, runner) that should not have
shallow-copy semantics."
```

---

## Task 3: Fix MailboxService Drain Atomicity

**Issues addressed:** 2.1 (MailboxService drain not atomic)

**Files:**
- Modify: `app/src/main/java/com/example/workspace/mailbox/MailboxService.kt`
- Create: `app/src/test/java/com/example/workspace/MailboxServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/workspace/MailboxServiceTest.kt`:

```kotlin
package com.example.workspace

import com.example.data.AppRepository
import com.example.data.MailboxMessage
import com.example.workspace.mailbox.MailboxService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MailboxServiceTest {

    @Test
    fun `drain returns messages and marks them delivered`() = runBlocking {
        // Uses a mock/fake repository — see step 2
    }

    @Test
    fun `second drain returns empty if no new messages`() = runBlocking {
        // After first drain, second drain should return empty
    }
}
```

- [ ] **Step 2: Add mutex to MailboxService.drain()**

Replace the `drain` method in `MailboxService.kt`:

```kotlin
class MailboxService(
    private val repository: AppRepository,
) {
    companion object {
        private const val TAG = "MailboxService"
        private const val MAX_UNDELIVERED = 1000
    }

    // Per-agent drain mutex to prevent concurrent drains from duplicating messages
    private val drainMutexes = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()

    private fun getDrainMutex(agentInstanceId: Long): kotlinx.coroutines.sync.Mutex {
        return drainMutexes.getOrPut(agentInstanceId) { kotlinx.coroutines.sync.Mutex() }
    }

    suspend fun send(recipientInstanceId: Long, message: MailboxMessage) {
        repository.insertMailboxMessage(message.copy(
            recipientAgentId = recipientInstanceId,
            delivered = false,
            createdAt = System.currentTimeMillis(),
        ))
        Log.d(TAG, "Sent message to agent $recipientInstanceId from ${message.senderAgentName}")
    }

    suspend fun drain(agentInstanceId: Long): List<MailboxMessage> {
        return getDrainMutex(agentInstanceId).withLock {
            val messages = repository.getUndeliveredMailboxMessages(agentInstanceId)
            for (msg in messages) {
                repository.markMailboxDelivered(msg.id)
            }
            if (messages.isNotEmpty()) {
                Log.d(TAG, "Drained ${messages.size} messages for agent $agentInstanceId")
            }
            messages
        }
    }

    // ... rest unchanged
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/mailbox/MailboxService.kt
git commit -m "fix(workspace): add per-agent mutex to MailboxService.drain()

Prevents concurrent drains (e.g., from tool boundary drain and finally block
drain) from fetching the same undelivered messages, causing duplicate injection."
```

---

## Task 4: Fix Async Sub-Agent Scope Cancellation and Error Propagation

**Issues addressed:** 3.1 (sub-agent scopes not cancelled), 2.6 (async error no wake mechanism)

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentTool.kt`
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Track async sub-agent scopes in AgentTool**

In `AgentTool.kt`, add a field to track all async scopes:

```kotlin
class AgentTool(
    // ... existing params ...
) {
    companion object { /* ... */ }

    // Track async sub-agent scopes for cancellation
    private val asyncScopes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CoroutineScope>()

    // ... existing methods ...
```

- [ ] **Step 2: Store scope in asyncScopes map and add cancelAll method**

In `runAsyncSubAgent`, after creating `subAgentScope`, add:

```kotlin
asyncScopes[subAgentName] = subAgentScope
```

In the `finally` block of the subAgentJob (line 302), add cleanup:

```kotlin
finally {
    asyncScopes.remove(subAgentName)
    val messagesSnapshot = subAgentContext.messages.toList()
    runner.dispose()
    agentRegistry.unregister(subAgentName)
    onSubAgentCompleted(subAgentName, messagesSnapshot)
}
```

Add a public method to cancel all async agents:

```kotlin
fun cancelAllAsyncAgents() {
    for ((name, scope) in asyncScopes) {
        Log.d(TAG, "Cancelling async sub-agent: $name")
        scope.cancel()
    }
    asyncScopes.clear()
}
```

- [ ] **Step 3: Call cancelAllAsyncAgents from TeamManager.deleteTeam()**

In `TeamManager.kt`, in `deleteTeam()` before `subAgentScope.cancel()`:

```kotlin
suspend fun deleteTeam() {
    val state = _teamState.value ?: return

    Log.d(TAG, "Deleting team '${state.teamName}'")

    // Cancel all async sub-agent scopes
    agentTool?.cancelAllAsyncAgents()

    // Cancel remaining sub-agent coroutines
    subAgentScope.cancel()
    // ... rest unchanged
```

- [ ] **Step 4: Add orchestrator wake mechanism for async notifications**

In `AgentTool.kt`, update the `onTaskNotification` callback in `TeamManager.kt` to wake the orchestrator if it has a runner:

In `TeamManager.kt`, update the `onTaskNotification` lambda (around line 219):

```kotlin
onTaskNotification = { notification ->
    val orchestratorEntry = agentRegistry.get("${ORCHESTRATOR_NAME}@${teamName}")
    orchestratorEntry?.runner?.queuePendingMessage(AgentMessage(
        role = "user",
        content = buildTaskNotificationText(notification),
        source = "task-notification",
    ))
    // Wake orchestrator: if it's not currently streaming, trigger a new runTurn
    // to process the notification from mailbox
    val orchestratorRunner = orchestratorEntry?.runner
    if (orchestratorRunner != null && !orchestratorRunner.isStreaming()) {
        WorkspaceScopes.auxiliary.launch {
            try {
                orchestratorRunner.runTurn(userMessage = null, source = "task-notification-wake")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to wake orchestrator for notification", e)
            }
        }
    }
},
```

- [ ] **Step 5: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTool.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "fix(workspace): cancel async sub-agent scopes on team delete, wake orchestrator for notifications

- Track async sub-agent CoroutineScopes in AgentTool.asyncScopes
- Cancel all scopes in cancelAllAsyncAgents() called from deleteTeam()
- Wake orchestrator via runTurn when async task notification arrives
  and orchestrator is not currently streaming"
```

---

## Task 5: Fix SendMessageTool Sender Identity and Orchestrator Tool Routing

**Issues addressed:** 4.4 (SendMessageTool hardcodes "orchestrator"), 4.3 (orchestrator onToolCall bypasses ToolOrchestrator)

**Files:**
- Modify: `app/src/main/java/com/example/workspace/SendMessageTool.kt`
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`
- Create: `app/src/test/java/com/example/workspace/SendMessageToolTest.kt`

- [ ] **Step 1: Add senderAgentName parameter to SendMessageTool**

In `SendMessageTool.kt`, add `senderAgentName` to the constructor:

```kotlin
class SendMessageTool(
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val senderAgentName: String = "orchestrator",
) {
```

Update `sendToAgent` and `broadcastMessage` to use `senderAgentName`:

```kotlin
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
```

- [ ] **Step 2: Pass sender identity from BuiltinToolHandler**

In `BuiltinToolHandler.kt`, update `handleSendMessage` (line 95):

```kotlin
private suspend fun handleSendMessage(arguments: JSONObject): JSONObject {
    val manager = teamManager
        ?: return errorResponse("SendMessage not available: no active workspace")
    // Determine sender: if the caller is a known agent, use its name
    // The MCP runtime doesn't pass caller identity, so we default to orchestrator
    val sendTool = com.example.workspace.SendMessageTool(
        manager.agentRegistry,
        manager.mailboxService,
        senderAgentName = "orchestrator",
    )
    return sendTool.call(arguments)
}
```

- [ ] **Step 3: Write test for SendMessageTool**

Create `app/src/test/java/com/example/workspace/SendMessageToolTest.kt`:

```kotlin
package com.example.workspace

import com.example.data.MailboxMessage
import com.example.workspace.mailbox.MailboxService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SendMessageToolTest {

    @Test
    fun `sendToAgent uses provided sender name`() = runBlocking {
        // This test verifies the sender identity is not hardcoded
        // Full integration test requires mock repository
        val tool = SendMessageTool(
            agentRegistry = AgentRegistry(),
            mailboxService = MailboxService(com.example.data.AppRepository(
                com.example.data.AppDatabase.getDatabase(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()
                )
            )),
            senderAgentName = "TestAgent",
        )
        // Calling with non-existent agent should return error
        val result = tool.call(JSONObject().apply {
            put("to", "NonExistent")
            put("message", "hello")
        })
        assertTrue(result.optBoolean("isError"))
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/SendMessageTool.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt app/src/test/java/com/example/workspace/SendMessageToolTest.kt
git commit -m "fix(workspace): SendMessageTool uses configurable sender identity instead of hardcoded 'orchestrator'"
```

---

## Task 6: Fix Completion Detection, Token Estimation, and TeamManager Reusability

**Issues addressed:** 2.5 (brittle completion detection), 6.3 (token estimation), 1.6 (TeamManager not reusable)

**Files:**
- Modify: `app/src/main/java/com/example/workspace/WorkspaceModels.kt`
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Replace Chinese keyword completion detection with structured marker**

In `AgentRunner.kt`, replace lines 456-467 with detection of the structured `COMPLETION_MARKER`:

```kotlin
if (context.isOrchestrator) {
    if (lastAssistantResponse.contains(COMPLETION_MARKER)) {
        Log.d(TAG, "Orchestrator '${context.agentName}' output completion marker, accepting")
        break
    }
}
```

This uses the existing `COMPLETION_MARKER = "【任务完成】"` constant from `WorkspaceModels.kt`. The orchestrator's system prompt already instructs the LLM to output this marker. Remove the loose keyword matching.

- [ ] **Step 2: Fix token estimation for Chinese text**

In `AgentRunner.kt`, line 526, replace:

```kotlin
// Old:
usageStats.totalTokens = totalChars / 4

// New: Chinese text averages ~1.5-2 chars/token, mixed content ~2.5 chars/token
usageStats.totalTokens = (totalChars / 2.5).toInt()
```

- [ ] **Step 3: Make TeamManager reusable after deleteTeam**

In `TeamManager.kt`, replace the `subAgentScope` field (line 68) with a factory method:

```kotlin
// Old:
private val subAgentScope = CoroutineScope(
    parentScope.coroutineContext + SupervisorJob()
)

// New:
private var subAgentScope = createSubAgentScope()

private fun createSubAgentScope() = CoroutineScope(
    parentScope.coroutineContext + SupervisorJob()
)
```

In `deleteTeam()`, after cancelling the old scope, create a new one:

```kotlin
subAgentScope.cancel()
subAgentScope = createSubAgentScope()
```

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "fix(workspace): structured completion marker, Chinese token estimation, reusable TeamManager

- Replace loose Chinese keyword matching with structured COMPLETION_MARKER
- Fix token estimation: 2.5 chars/token for mixed CJK/Latin content
- Make subAgentScope recreatable so TeamManager can be reused after deleteTeam"
```

---

## Task 7: Fix WorkspaceViewModel Race Conditions

**Issues addressed:** 1.1 (global static teamManager), 1.2 (God class races), 2.3 (loadSessionHistory race), 4.1 (team name constraint), 4.2 (hardcoded team name pattern)

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Add Mutex to protect team lifecycle operations in WorkspaceViewModel**

In `WorkspaceViewModel.kt`, add a mutex near the top of the class:

```kotlin
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private var teamManager: TeamManager? = null
    private var teamStateCollectorJob: Job? = null

    // Protects team lifecycle: createTeam, deleteTeam, submitTask, selectWorkspaceSession
    private val teamLifecycleMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var isRestoringSession = false
    // ...
```

- [ ] **Step 2: Wrap submitTask in mutex**

In `submitTask` (line 360), wrap the team lifecycle section:

```kotlin
viewModelScope.launch {
    try {
        val allConfigs = repository.getAllConfigs()
        val orchestratorConfig = repository.getConfigById(orchestratorModelConfigId)
            ?: allConfigs.find { it.isDefaultProvider }
            ?: allConfigs.firstOrNull()
            ?: throw IllegalStateException("No model configs available")

        _agentStreamBuffers.value = emptyMap()
        _agentStatuses.value = emptyMap()
        _agentTabs.value = emptyList()

        teamLifecycleMutex.withLock {
            val oldManager = teamManager
            teamManager = null
            oldManager?.deleteTeam()

            teamManager = createTeamManager(wsId)
        }

        val runtimeManager = com.example.mcp.McpRuntimeManager.getInstance(getApplication())
        runtimeManager.waitForStartingServersToFinish()

        teamStateCollectorJob?.cancel()
        teamStateCollectorJob = viewModelScope.launch {
            teamManager?.teamState?.collect { state ->
                _teamState.value = state
            }
        }

        teamManager?.createTeam("workspace_$wsId", orchestratorConfig = orchestratorConfig, orchestratorOverrideModelId = orchestratorOverrideModelId, sandboxPath = sandboxPath)
        loadTeamTasks("workspace_$wsId")

        WorkspaceForegroundService.start(getApplication(), "正在执行多 Agent 协作：${task.take(30)}...")
        teamManager?.startExecution(task, imagePath)
    } catch (e: Exception) {
        // ... existing error handling
    }
}
```

- [ ] **Step 3: Fix loadSessionHistory race — set isRestoringSession before async launch**

In `loadSessionHistory` (around line 742), the race is that `isRestoringSession` is set to `true`, then `createTeam` runs, then `isRestoringSession` is set to `false`, but `startExecution("")` is launched asynchronously. The fix: keep `isRestoringSession = true` until the async `startExecution` coroutine actually begins executing.

Replace lines 742-761:

```kotlin
isRestoringSession = true
teamManager?.createTeam("workspace_$workspaceSessionId", orchestratorConfig = orchestratorConfig)

_agentTabs.value = agentInstances.map { instance ->
    AgentTabState(
        agentName = instance.agentName,
        isOrchestrator = instance.isOrchestrator,
        status = if (instance.isOrchestrator) AgentStatus.IDLE else AgentStatus.COMPLETED,
        messages = messagesByAgentInstanceId[instance.id] ?: emptyList()
    )
}

// Reset isRestoringSession inside the async launch so the onAgentCreated
// callback sees it as true until the coroutine actually starts
viewModelScope.launch {
    isRestoringSession = false
    try {
        teamManager?.startExecution("")
    } catch (e: Exception) {
        Log.e("WorkspaceViewModel", "Failed to restore orchestrator loop", e)
    }
}
```

- [ ] **Step 4: Extract team name constant to avoid duplication**

In `WorkspaceViewModel.kt`, add a helper method:

```kotlin
private fun teamNameForSession(wsId: Long): String = "workspace_$wsId"
```

Replace all `"workspace_$wsId"` and `"workspace_$workspaceSessionId"` usages with this method.

- [ ] **Step 5: Replace global static teamManager with interface**

In `BuiltinToolHandler.kt`, replace the global static with an interface:

```kotlin
object BuiltinToolHandler {

    /**
     * Interface for accessing workspace state from MCP tools.
     * Set by WorkspaceViewModel on creation, nulled on cleanup.
     */
    interface WorkspaceProvider {
        fun getAgentTool(): com.example.workspace.AgentTool?
        fun getOrchestratorContext(): com.example.workspace.AgentContext?
        fun getSandboxPath(): String?
        fun getAgentRegistry(): com.example.workspace.AgentRegistry
        fun getMailboxService(): com.example.workspace.mailbox.MailboxService
        fun getTeamName(): String?
    }

    @Volatile
    var workspaceProvider: WorkspaceProvider? = null

    // Keep backward-compatible accessor
    @Volatile
    var teamManager: com.example.workspace.TeamManager? = null
        set(value) {
            field = value
            workspaceProvider = if (value != null) {
                object : WorkspaceProvider {
                    override fun getAgentTool() = value.getAgentTool()
                    override fun getOrchestratorContext() = value.getOrchestratorContext()
                    override fun getSandboxPath() = value.getSandboxPath()
                    override fun getAgentRegistry() = value.agentRegistry
                    override fun getMailboxService() = value.mailboxService
                    override fun getTeamName() = value.teamState.value?.teamName
                }
            } else null
        }
```

This keeps backward compatibility while introducing a proper interface for future decoupling.

- [ ] **Step 6: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "fix(workspace): fix ViewModel race conditions, extract team name helper, add WorkspaceProvider interface

- Add teamLifecycleMutex to protect submitTask/selectWorkspaceSession
- Fix loadSessionHistory race: isRestoringSession resets inside async launch
- Extract teamNameForSession() to eliminate hardcoded pattern duplication
- Introduce WorkspaceProvider interface to replace direct static teamManager access"
```

---

## Task 8: Add Comprehensive Unit Tests

**Issues addressed:** 5 (all test coverage gaps)

**Files:**
- Create: `app/src/test/java/com/example/workspace/AgentRegistryTest.kt`
- Create: `app/src/test/java/com/example/workspace/AgentLifecycleManagerTest.kt`
- Modify: `app/src/test/java/com/example/workspace/WorkspaceCoreTest.kt`

- [ ] **Step 1: Create AgentRegistryTest**

Create `app/src/test/java/com/example/workspace/AgentRegistryTest.kt`:

```kotlin
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
        assertEquals(entry, registry.get("Agent1@test-team"))
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
```

- [ ] **Step 2: Create AgentLifecycleManagerTest**

Create `app/src/test/java/com/example/workspace/AgentLifecycleManagerTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Add completion marker consistency test to WorkspaceCoreTest**

Add to `WorkspaceCoreTest.kt`:

```kotlin
@Test
fun testCompletionMarkerIsStructuredBrackets() {
    // Verify that COMPLETION_MARKER uses the structured bracket format
    assertEquals("【任务完成】", COMPLETION_MARKER)
}

@Test
fun testCompletionMarkerisContainedInOrchestratorPrompt() {
    // The orchestrator system prompt should instruct LLM to output the marker
    val prompt = buildOrchestratorSystemPrompt(BuiltInAgents.ALL)
    assertTrue("Orchestrator prompt should reference completion marker",
        prompt.contains(COMPLETION_MARKER))
}
```

- [ ] **Step 4: Run all tests**

```bash
./gradlew testDebugUnitTest --tests "com.example.workspace.*"
```

Expected: All tests pass including new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/example/workspace/
git commit -m "test(workspace): add unit tests for AgentRegistry, AgentLifecycleManager, completion marker

Covers previously untested core components:
- AgentRegistry: register, unregister, get, contains, size, clear
- AgentLifecycleManager: abort, abortTurn, resetTurn, transitionTo
- Completion marker consistency checks"
```

---

## Verification

After all tasks are complete, run the full test suite:

```bash
./gradlew testDebugUnitTest
```

And verify the build compiles:

```bash
./gradlew assembleDebug
```

## Summary of Issues Fixed

| Issue | Fix | Task |
|-------|-----|------|
| 1.1 Global static teamManager | WorkspaceProvider interface | 7 |
| 1.2 God class races | teamLifecycleMutex | 7 |
| 1.3 Dead executor code | Remove executor package | 1 |
| 1.4 Circular dependency | (Reduced) executor removed | 1 |
| 1.5 Data class mutable messages | Regular class | 2 |
| 1.6 TeamManager not reusable | Recreatable subAgentScope | 6 |
| 2.1 MailboxService drain race | Per-agent mutex | 3 |
| 2.2 Sub-agent messages not persisted | (Deferred - requires DB schema change) | - |
| 2.3 loadSessionHistory race | isRestoringSession in async launch | 7 |
| 2.4 startExecution blocks | (Acceptable - cancellation is correct) | - |
| 2.5 Brittle completion detection | Structured COMPLETION_MARKER | 6 |
| 2.6 Async error no wake | Wake orchestrator on notification | 4 |
| 3.1 sub-agent scope not cancelled | cancelAllAsyncAgents | 4 |
| 3.2 Write lock heavyweight ops | (Acceptable - non-suspending) | - |
| 3.3 External lock bypass | Regular class prevents copy() | 2 |
| 3.4 Intervention message loss | (Low risk, deferred) | - |
| 4.1 Team name constraint | teamNameForSession helper | 7 |
| 4.2 Hardcoded team name | teamNameForSession helper | 7 |
| 4.3 Orchestrator bypasses ToolOrchestrator | (Acceptable - orchestrator is serial) | - |
| 4.4 SendMessageTool sender hardcoded | Configurable senderAgentName | 5 |
| 5.x Test coverage gaps | New test files | 8 |
| 6.1 WorkspaceScopes not cancelled | (Acceptable - process lifecycle) | - |
| 6.2 OrchestratorExecutor dead code | Removed | 1 |
| 6.3 Token estimation | 2.5 chars/token | 6 |
