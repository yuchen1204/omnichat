# Agent Team Refactoring Design

## Overview

Full refactoring of the OmniChat `workspace/` package, aligning with ClaudeCode-Main's swarm architecture. Bottom-up phased approach with clean break (no backward compatibility).

**Goals:**
- Decouple agent execution from `TeamManager` via `TeammateExecutor` abstraction
- Room DB-backed mailbox for all inter-agent communication
- Dual AbortController (lifecycle + per-turn) via `AgentLifecycleManager`
- Full team persistence surviving app restarts
- Support both orchestrator-led and peer-to-peer modes

---

## Phase 1: Core Abstractions

### TeammateExecutor Interface

```kotlin
// workspace/executor/TeammateExecutor.kt

interface TeammateExecutor {
    val type: ExecutorType
    suspend fun spawn(config: SpawnConfig): SpawnResult
    suspend fun sendMessage(agentId: String, message: MailboxMessage)
    suspend fun terminate(agentId: String, reason: String? = null): Boolean
    suspend fun kill(agentId: String): Boolean
    suspend fun isActive(agentId: String): Boolean
}

enum class ExecutorType { ORCHESTRATOR, PEER }

data class SpawnConfig(
    val name: String,
    val teamName: String,
    val prompt: String,
    val agentDefinition: AgentDefinition,
    val modelOverride: String? = null,
    val isBackground: Boolean = false,
)

data class SpawnResult(
    val success: Boolean,
    val agentId: String,
    val error: String? = null,
)
```

### AgentLifecycleManager

```kotlin
// workspace/lifecycle/AgentLifecycleManager.kt

class AgentLifecycleManager(
    val identity: TeammateIdentity,
    private val onStateChanged: (AgentStatus) -> Unit,
) {
    private val lifecycleAbort = AtomicBoolean(false)
    private val turnAbort = AtomicBoolean(false)
    @Volatile var status: AgentStatus = AgentStatus.IDLE; private set

    fun abort() { lifecycleAbort.lazySet(true); turnAbort.lazySet(true) }
    fun abortTurn() { turnAbort.lazySet(true) }
    fun resetTurn() { turnAbort.lazySet(false) }
    fun isAborted(): Boolean = lifecycleAbort.get()
    fun isTurnAborted(): Boolean = turnAbort.get()
    fun transitionTo(newStatus: AgentStatus) { status = newStatus; onStateChanged(newStatus) }
}
```

### AgentRegistry

```kotlin
// workspace/AgentRegistry.kt

class AgentRegistry {
    private val agents = ConcurrentHashMap<String, AgentEntry>()

    data class AgentEntry(
        val identity: TeammateIdentity,
        val lifecycle: AgentLifecycleManager,
        val instanceId: Long,
        val runner: AgentRunner?,
    )

    fun register(entry: AgentEntry)
    fun unregister(agentId: String)
    fun get(agentId: String): AgentEntry?
    fun getActiveAgents(): List<AgentEntry>
}
```

### Room Entities (v30 → v31)

```kotlin
@Entity(tableName = "workspace_teams")
data class WorkspaceTeam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamName: String,
    val mode: String,                  // "orchestrator" | "peer"
    val orchestratorModelConfigId: Long,
    val sandboxPath: String? = null,
    val status: String,                // "active" | "paused" | "completed"
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "agent_instances",
    foreignKeys = [ForeignKey(entity = WorkspaceTeam::class,
        parentColumns = ["id"], childColumns = ["teamId"], onDelete = ForeignKey.CASCADE)])
data class AgentInstance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamId: Long,
    val agentName: String,
    val agentType: String,
    val status: String,
    val isOrchestrator: Boolean = false,
    val modelConfigId: Long? = null,
    val overrideModelId: String? = null,
    val systemPrompt: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "mailbox_messages",
    foreignKeys = [ForeignKey(entity = AgentInstance::class,
        parentColumns = ["id"], childColumns = ["recipientAgentId"], onDelete = ForeignKey.CASCADE)])
data class MailboxMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientAgentId: Long,
    val senderAgentName: String,
    val role: String,
    val content: String,
    val source: String,
    val delivered: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "agent_state_snapshots",
    foreignKeys = [ForeignKey(entity = AgentInstance::class,
        parentColumns = ["id"], childColumns = ["agentInstanceId"], onDelete = ForeignKey.CASCADE)])
data class AgentStateSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentInstanceId: Long,
    val messagesJson: String,
    val usageStatsJson: String,
    val snapshotType: String,          // "checkpoint" | "completion"
    val createdAt: Long,
)
```

---

## Phase 2: Execution Layer

### AgentRunner Simplification

Current `AgentRunner` (1034 lines) split:

- **AgentRunner** — LLM streaming loop only (`runTurn`, `buildContextMessages`, `getFilteredTools`, `compactContextIfNeeded`)
- **ToolOrchestrator** — stays as-is
- **AgentLifecycleManager** — replaces `TeammateContext` for abort/status
- **MailboxService** — replaces `pendingMessages` deque and `interventionQueue`
- **AgentMessageStore** — message list + read/write lock (extracted)

New constructor:
```kotlin
class AgentRunner(
    private val context: AgentContext,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val lifecycleManager: AgentLifecycleManager,
    private val mailboxService: MailboxService,
    private val toolOrchestrator: ToolOrchestrator,
    private val crossSessionMemory: String = "",
    private val availableModels: String = "",
    private val sandboxPath: String? = null,
    private val maxToolIterations: Int = MAX_TOOL_CALL_ITERATIONS,
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onMessageAdded: (agentName: String, message: AgentMessage) -> Unit,
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String,
    private val persistMessage: ((AgentMessage) -> Unit)? = null,
)
```

Key change: `isStreaming` check in loop uses `lifecycleManager.isTurnAborted()` instead of `coroutineContext[TeammateContext]`.

### OrchestratorExecutor

```kotlin
class OrchestratorExecutor(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val parentScope: CoroutineScope,
    private val callbacks: TeamCallbacks,
) : TeammateExecutor {
    // Only leader can spawn. Sub-agents run via AgentTool.
    // Sync spawn blocks until sub-agent completes.
    // Async spawn returns immediately, result via mailbox notification.
}
```

### PeerExecutor

```kotlin
class PeerExecutor(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val parentScope: CoroutineScope,
    private val callbacks: TeamCallbacks,
) : TeammateExecutor {
    // Any agent can spawn. All communication via mailbox.
    // New tools: spawn_agent, send_message, list_agents, kill_agent.
}
```

---

## Phase 3: Team Management

### TeamManager (Rewritten)

```kotlin
class TeamManager(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val parentScope: CoroutineScope,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val callbacks: TeamCallbacks,
) {
    private var executor: TeammateExecutor? = null
    private val agentRegistry = AgentRegistry()
    private val mailboxService = MailboxService(repository)
    private var teamDbId: Long = 0L

    suspend fun createTeam(teamName: String, mode: ExecutorType,
        orchestratorConfig: ModelConfig, sandboxPath: String? = null): TeamState
    suspend fun resumeTeam(teamId: Long): TeamState
    suspend fun startExecution(userTask: String, imagePath: String? = null)
    suspend fun checkpoint()
    suspend fun deleteTeam()
}
```

**Persistence flow:**
1. `createTeam()` → INSERT `workspace_teams` + `agent_instances` (orchestrator)
2. `startExecution()` → agent runs, periodic `checkpoint()` saves `AgentStateSnapshot`
3. `resumeTeam()` → load from DB, drain mailbox, restore `AgentRunner`
4. `deleteTeam()` → CASCADE deletes all

---

## Phase 4: Communication

### MailboxService

```kotlin
class MailboxService(private val repository: AppRepository) {
    suspend fun send(recipientAgentId: Long, message: MailboxMessage)
    suspend fun drain(agentInstanceId: Long): List<MailboxMessage>
    suspend fun hasUndelivered(agentInstanceId: Long): Boolean
    suspend fun getHistory(agentInstanceId: Long): List<MailboxMessage>
}
```

Replaces:
- `AgentRunner.pendingMessages` deque
- `AgentRunner.interventionQueue`
- `SendMessageTool` direct runner access
- `AgentTool.onTaskNotification` callback

All inter-agent communication flows through `MailboxService`.

---

## Phase 5: UI Integration

### WorkspaceViewModel Changes

- Team mode toggle: `ORCHESTRATOR` / `PEER` at creation
- `resumeTeam(teamId)` — load persisted team
- Active teams list from `workspace_teams` table

### WorkspaceScreen Changes

- Mode selector in team creation dialog
- Resume button on recent teams
- Agent list shows all registered peers (not just orchestrator's sub-agents)
- Agent status from `AgentLifecycleManager`

### AgentTabBar Status Indicators

- `IDLE` = gray
- `STREAMING` = pulsing blue
- `COMPLETED` = green
- `ERROR` = red

---

## Error Handling

- Agent crash → `AgentLifecycleManager` catches, transitions to `ERROR`, notifies team via mailbox
- Mailbox overflow → cap 1000 undelivered per agent, oldest auto-deleted
- Resume with stale agents → `STREAMING` agents reset to `IDLE`
- Concurrent spawn → `ConcurrentHashMap.putIfAbsent`, error if name taken
- Migration v30→v31 → additive only (4 CREATE TABLE), no data loss

---

## Files Modified/Created

### New Files (Phase 1)
- `workspace/executor/TeammateExecutor.kt`
- `workspace/executor/OrchestratorExecutor.kt`
- `workspace/executor/PeerExecutor.kt`
- `workspace/lifecycle/AgentLifecycleManager.kt`
- `workspace/AgentRegistry.kt`
- `workspace/mailbox/MailboxService.kt`
- `workspace/mailbox/MailboxMessage.kt` (data class, reuses Room entity)

### Modified Files
- `workspace/AgentRunner.kt` — simplified, uses new abstractions
- `workspace/TeamManager.kt` — rewritten as facade
- `workspace/AgentTool.kt` — uses `TeammateExecutor.spawn()` instead of direct runner creation
- `workspace/AgentContext.kt` — remove `TeammateContext` coroutine element dependency
- `workspace/WorkspaceModels.kt` — add `TeamCallbacks`, update `TeamState`
- `workspace/SendMessageTool.kt` — uses `MailboxService`
- `workspace/TaskTools.kt` — uses `MailboxService` for notifications
- `data/AppDatabase.kt` — add v31 migration, new entities + DAOs
- `data/Repository.kt` — add mailbox/team/agent DAO methods

### Deleted Files
- `workspace/TeammateContext.kt` — replaced by `AgentLifecycleManager`
- `workspace/ProgressTracker.kt` — merged into `AgentUsageStats`

---

## Phased Implementation Order

1. **Phase 1** — Core abstractions + Room entities + migration. No behavior change yet.
2. **Phase 2** — `AgentRunner` rewrite using new abstractions. Tests pass.
3. **Phase 3** — `TeamManager` rewrite + `OrchestratorExecutor`. Orchestrator mode works.
4. **Phase 4** — `MailboxService` integration. Communication flows through DB.
5. **Phase 5** — `PeerExecutor` + UI updates. Peer mode works.
