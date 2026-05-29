# Agent Team Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the workspace/ package to align with ClaudeCode-Main's swarm architecture — TeammateExecutor abstraction, Room DB mailbox, dual AbortController, team persistence, and peer-to-peer mode.

**Architecture:** Bottom-up phased refactoring. New abstractions (TeammateExecutor, AgentLifecycleManager, MailboxService, AgentRegistry) built first, then existing code rewritten to use them. Clean break — no backward compatibility.

**Tech Stack:** Kotlin, Android Room DB (v30→v31), Coroutines, StateFlow, ConcurrentLinkedDeque

---

## Phase 1: Core Abstractions & Room Entities

### Task 1: Add Room Entities and Migration v31

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt`
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/data/Daos.kt`

**Context:** The existing `agent_instances` table (columns: id, workspaceSessionId, agentName, isOrchestrator, systemPrompt, modelConfigId, overrideModelId, createdAt) needs new columns. We also add 3 new tables: `workspace_teams`, `mailbox_messages`, `agent_state_snapshots`.

- [ ] **Step 1: Add WorkspaceTeam entity to Entities.kt**

Add after the `WorkspaceSession` entity (around line 40):

```kotlin
/**
 * 持久化的工作区团队。
 *
 * 替代 WorkspaceSession，支持 orchestrator/peer 两种模式，
 * 团队状态可持久化并在应用重启后恢复。
 */
@Entity(
    tableName = "workspace_teams",
    indices = [Index(value = ["teamName"], unique = true)]
)
data class WorkspaceTeam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamName: String,
    val mode: String,                  // "orchestrator" | "peer"
    val orchestratorModelConfigId: Long,
    val sandboxPath: String? = null,
    val status: String = "active",     // "active" | "paused" | "completed"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 2: Add new columns to AgentInstance entity**

Change the existing `AgentInstance` data class to add `teamId`, `agentType`, `status`, `updatedAt`. The existing `workspaceSessionId` column stays for backward compat with old workspace sessions.

```kotlin
@Entity(
    tableName = "agent_instances",
    indices = [Index(value = ["workspaceSessionId"]), Index(value = ["teamId"])]
)
data class AgentInstance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceSessionId: Long = 0,
    val teamId: Long = 0,              // NEW: FK to workspace_teams
    val agentName: String,
    val agentType: String = "",        // NEW: "general-purpose", "explore", etc.
    val status: String = "idle",       // NEW: "idle" | "streaming" | "completed" | "error"
    val isOrchestrator: Boolean = false,
    val systemPrompt: String = "",
    val modelConfigId: Long,
    val overrideModelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(), // NEW
)
```

- [ ] **Step 3: Add MailboxMessage entity to Entities.kt**

```kotlin
/**
 * 邮箱消息 — Agent 间通信的持久化队列。
 *
 * 所有 Agent 间通信通过 MailboxMessage 进行，存储在 Room DB 中。
 * 支持投递确认（delivered 标志）和消息历史查询。
 */
@Entity(
    tableName = "mailbox_messages",
    indices = [
        Index(value = ["recipientAgentId"]),
        Index(value = ["delivered"]),
    ]
)
data class MailboxMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientAgentId: Long,        // target agent instance ID
    val senderAgentName: String,       // source agent name
    val role: String,                  // "user" | "system"
    val content: String,
    val source: String,                // "orchestrator" | "send_message" | "broadcast" | "task-notification"
    val delivered: Boolean = false,    // consumed by recipient?
    val createdAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 4: Add AgentStateSnapshot entity to Entities.kt**

```kotlin
/**
 * Agent 状态快照 — 用于团队恢复。
 *
 * 定期保存 Agent 的消息历史和使用统计，
 * 应用重启后可通过快照恢复 Agent 执行状态。
 */
@Entity(
    tableName = "agent_state_snapshots",
    indices = [Index(value = ["agentInstanceId"])]
)
data class AgentStateSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentInstanceId: Long,
    val messagesJson: String,          // serialized List<AgentMessage>
    val usageStatsJson: String,        // serialized AgentUsageStats
    val snapshotType: String,          // "checkpoint" | "completion"
    val createdAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 5: Add DAOs for new entities to Daos.kt**

```kotlin
@Dao
interface WorkspaceTeamDao {
    @Query("SELECT * FROM workspace_teams ORDER BY updatedAt DESC")
    suspend fun getAll(): List<WorkspaceTeam>

    @Query("SELECT * FROM workspace_teams WHERE status = 'active' ORDER BY updatedAt DESC")
    suspend fun getActiveTeams(): List<WorkspaceTeam>

    @Query("SELECT * FROM workspace_teams WHERE teamName = :teamName LIMIT 1")
    suspend fun getByTeamName(teamName: String): WorkspaceTeam?

    @Query("SELECT * FROM workspace_teams WHERE id = :id")
    suspend fun getById(id: Long): WorkspaceTeam?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(team: WorkspaceTeam): Long

    @Update
    suspend fun update(team: WorkspaceTeam)

    @Query("UPDATE workspace_teams SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(team: WorkspaceTeam)

    @Query("DELETE FROM workspace_teams WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MailboxMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MailboxMessage): Long

    @Query("SELECT * FROM mailbox_messages WHERE recipientAgentId = :agentId AND delivered = 0 ORDER BY createdAt ASC")
    suspend fun getUndelivered(agentId: Long): List<MailboxMessage>

    @Query("SELECT COUNT(*) FROM mailbox_messages WHERE recipientAgentId = :agentId AND delivered = 0")
    suspend fun countUndelivered(agentId: Long): Int

    @Query("UPDATE mailbox_messages SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: Long)

    @Query("SELECT * FROM mailbox_messages WHERE recipientAgentId = :agentId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getHistory(agentId: Long, limit: Int = 100): List<MailboxMessage>

    @Query("DELETE FROM mailbox_messages WHERE recipientAgentId = :agentId AND delivered = 1")
    suspend fun deleteDelivered(agentId: Long)

    @Query("DELETE FROM mailbox_messages WHERE recipientAgentId = :agentId")
    suspend fun deleteAllForAgent(agentId: Long)
}

@Dao
interface AgentStateSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: AgentStateSnapshot): Long

    @Query("SELECT * FROM agent_state_snapshots WHERE agentInstanceId = :agentInstanceId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(agentInstanceId: Long): AgentStateSnapshot?

    @Query("DELETE FROM agent_state_snapshots WHERE agentInstanceId = :agentInstanceId")
    suspend fun deleteForAgent(agentInstanceId: Long)
}
```

- [ ] **Step 6: Update AppDatabase for v31**

In `AppDatabase.kt`:
- Add `WorkspaceTeam::class`, `MailboxMessage::class`, `AgentStateSnapshot::class` to `@Database(entities = [...])`
- Change `version = 30` to `version = 31`
- Add abstract DAO accessors:
```kotlin
abstract fun workspaceTeamDao(): WorkspaceTeamDao
abstract fun mailboxMessageDao(): MailboxMessageDao
abstract fun agentStateSnapshotDao(): AgentStateSnapshotDao
```
- Add migration:
```kotlin
private val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New table: workspace_teams
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS workspace_teams (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                teamName TEXT NOT NULL,
                mode TEXT NOT NULL DEFAULT 'orchestrator',
                orchestratorModelConfigId INTEGER NOT NULL,
                sandboxPath TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workspace_teams_teamName ON workspace_teams(teamName)")

        // Extend agent_instances with new columns
        db.execSQL("ALTER TABLE agent_instances ADD COLUMN teamId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_instances ADD COLUMN agentType TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE agent_instances ADD COLUMN status TEXT NOT NULL DEFAULT 'idle'")
        db.execSQL("ALTER TABLE agent_instances ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_instances_teamId ON agent_instances(teamId)")

        // New table: mailbox_messages
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS mailbox_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                recipientAgentId INTEGER NOT NULL,
                senderAgentName TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mailbox_messages_recipientAgentId ON mailbox_messages(recipientAgentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mailbox_messages_delivered ON mailbox_messages(delivered)")

        // New table: agent_state_snapshots
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_state_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                agentInstanceId INTEGER NOT NULL,
                messagesJson TEXT NOT NULL,
                usageStatsJson TEXT NOT NULL,
                snapshotType TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_state_snapshots_agentInstanceId ON agent_state_snapshots(agentInstanceId)")
    }
}
```
- Add `MIGRATION_30_31` to the `addMigrations()` call in `buildDatabase()`.

- [ ] **Step 7: Add Repository methods for new entities**

In `Repository.kt`, add:
```kotlin
// WorkspaceTeam
suspend fun insertWorkspaceTeam(team: WorkspaceTeam): Long = db.workspaceTeamDao().insert(team)
suspend fun getActiveWorkspaceTeams(): List<WorkspaceTeam> = db.workspaceTeamDao().getActiveTeams()
suspend fun getWorkspaceTeamById(id: Long): WorkspaceTeam? = db.workspaceTeamDao().getById(id)
suspend fun getWorkspaceTeamByName(name: String): WorkspaceTeam? = db.workspaceTeamDao().getByTeamName(name)
suspend fun updateWorkspaceTeamStatus(id: Long, status: String) = db.workspaceTeamDao().updateStatus(id, status)
suspend fun deleteWorkspaceTeam(id: Long) = db.workspaceTeamDao().deleteById(id)

// MailboxMessage
suspend fun insertMailboxMessage(msg: MailboxMessage): Long = db.mailboxMessageDao().insert(msg)
suspend fun getUndeliveredMailboxMessages(agentId: Long): List<MailboxMessage> = db.mailboxMessageDao().getUndelivered(agentId)
suspend fun countUndeliveredMailboxMessages(agentId: Long): Int = db.mailboxMessageDao().countUndelivered(agentId)
suspend fun markMailboxDelivered(id: Long) = db.mailboxMessageDao().markDelivered(id)
suspend fun getMailboxHistory(agentId: Long): List<MailboxMessage> = db.mailboxMessageDao().getHistory(agentId)

// AgentStateSnapshot
suspend fun insertAgentStateSnapshot(snapshot: AgentStateSnapshot): Long = db.agentStateSnapshotDao().insert(snapshot)
suspend fun getLatestAgentStateSnapshot(agentInstanceId: Long): AgentStateSnapshot? = db.agentStateSnapshotDao().getLatest(agentInstanceId)
```

- [ ] **Step 8: Run unit tests to verify migration doesn't break existing tests**

Run: `./gradlew testDebugUnitTest`
Expected: All existing tests pass (migration is additive only).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/data/
git commit -m "feat(workspace): add Room entities and v31 migration for team persistence

Add WorkspaceTeam, MailboxMessage, AgentStateSnapshot entities.
Extend AgentInstance with teamId, agentType, status, updatedAt.
Add DAOs and Repository methods. Additive v30→v31 migration."
```

---

### Task 2: Create TeammateExecutor Interface and Supporting Types

**Files:**
- Create: `app/src/main/java/com/example/workspace/executor/TeammateExecutor.kt`

- [ ] **Step 1: Create the executor package and interface**

```kotlin
package com.example.workspace.executor

import com.example.workspace.AgentDefinition
import com.example.workspace.AgentStatus
import com.example.workspace.TeammateIdentity

/**
 * 执行器类型 — 决定 Agent 间的协作模式。
 */
enum class ExecutorType {
    /** 编排器模式：Leader 派发任务给 Worker，Worker 不能互相通信 */
    ORCHESTRATOR,
    /** 对等模式：任何 Agent 可以创建和通信任何其他 Agent */
    PEER,
}

/**
 * Agent 启动配置。
 */
data class SpawnConfig(
    val name: String,
    val teamName: String,
    val prompt: String,
    val agentDefinition: AgentDefinition,
    val modelOverride: String? = null,
    val isBackground: Boolean = false,
)

/**
 * Agent 启动结果。
 */
data class SpawnResult(
    val success: Boolean,
    val agentId: String,
    val error: String? = null,
)

/**
 * Teammate 执行器接口 — 抽象 Agent 的完整生命周期管理。
 *
 * 两种实现：
 * - [OrchestratorExecutor]：编排器模式，仅 Leader 可以 spawn
 * - [PeerExecutor]：对等模式，任何 Agent 可以 spawn/message 任何其他 Agent
 */
interface TeammateExecutor {
    val type: ExecutorType

    /** 创建并启动一个新 Agent。 */
    suspend fun spawn(config: SpawnConfig): SpawnResult

    /** 向指定 Agent 发送消息（通过 MailboxService）。 */
    suspend fun sendMessage(agentId: String, message: com.example.data.MailboxMessage)

    /** 优雅终止 — Agent 完成当前工具调用后退出。 */
    suspend fun terminate(agentId: String, reason: String? = null): Boolean

    /** 强制终止 — 立即停止。 */
    suspend fun kill(agentId: String): Boolean

    /** 检查 Agent 是否仍在运行。 */
    suspend fun isActive(agentId: String): Boolean
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/executor/
git commit -m "feat(workspace): add TeammateExecutor interface and supporting types

Add ExecutorType, SpawnConfig, SpawnResult, and TeammateExecutor
interface. Foundation for OrchestratorExecutor and PeerExecutor."
```

---

### Task 3: Create AgentLifecycleManager

**Files:**
- Create: `app/src/main/java/com/example/workspace/lifecycle/AgentLifecycleManager.kt`

- [ ] **Step 1: Create the lifecycle package and class**

```kotlin
package com.example.workspace.lifecycle

import com.example.workspace.AgentStatus
import com.example.workspace.TeammateIdentity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 生命周期管理器 — 双层 Abort 控制。
 *
 * 对标 Claude Code 的 dual AbortController：
 * - lifecycle abort：杀死整个 Agent（terminate/kill 调用）
 * - turn abort：中断当前工作，Agent 返回 idle（用户中断）
 *
 * @property identity Teammate 身份信息
 * @property onStateChanged 状态变更回调
 */
class AgentLifecycleManager(
    val identity: TeammateIdentity,
    private val onStateChanged: (AgentStatus) -> Unit = {},
) {
    // 生命周期 Abort — 一旦设置，Agent 永久退出
    private val lifecycleAbort = AtomicBoolean(false)

    // 轮次 Abort — Agent 完成当前工具调用后回到 idle
    private val turnAbort = AtomicBoolean(false)

    @Volatile
    var status: AgentStatus = AgentStatus.IDLE
        private set

    /** 终止 Agent 生命周期。 */
    fun abort() {
        lifecycleAbort.lazySet(true)
        turnAbort.lazySet(true)
    }

    /** 中断当前轮次（Agent 回到 idle，不退出）。 */
    fun abortTurn() {
        turnAbort.lazySet(true)
    }

    /** 每轮 runTurn 开始时重置 turn abort 标志。 */
    fun resetTurn() {
        turnAbort.lazySet(false)
    }

    /** 是否已被生命周期级中止。 */
    fun isAborted(): Boolean = lifecycleAbort.get()

    /** 是否已被轮次级中止。 */
    fun isTurnAborted(): Boolean = turnAbort.get()

    /** 状态转换。 */
    fun transitionTo(newStatus: AgentStatus) {
        status = newStatus
        onStateChanged(newStatus)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/lifecycle/
git commit -m "feat(workspace): add AgentLifecycleManager with dual abort control

Replaces TeammateContext coroutine element. Supports lifecycle abort
(terminate/kill) and per-turn abort (user interrupt to idle)."
```

---

### Task 4: Create AgentRegistry

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentRegistry.kt`

- [ ] **Step 1: Create AgentRegistry**

```kotlin
package com.example.workspace

import com.example.workspace.lifecycle.AgentLifecycleManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 注册表 — 运行时 Agent 实例索引。
 *
 * 所有活跃 Agent 在此注册，支持按名称查找、状态查询和遍历。
 * 用于 PeerExecutor 的 agent 间通信和 TeamManager 的生命周期管理。
 *
 * 线程安全：使用 ConcurrentHashMap。
 */
class AgentRegistry {
    private val agents = ConcurrentHashMap<String, AgentEntry>()

    /**
     * 注册表条目。
     *
     * @property identity Teammate 身份
     * @property lifecycle 生命周期管理器
     * @property instanceId Room DB 中的 AgentInstance ID
     * @property runner AgentRunner 实例（可为 null，如 Agent 已完成但条目保留）
     */
    data class AgentEntry(
        val identity: TeammateIdentity,
        val lifecycle: AgentLifecycleManager,
        val instanceId: Long,
        val runner: AgentRunner? = null,
    )

    /** 注册一个 Agent。如果同名 Agent 已存在，返回 false。 */
    fun register(entry: AgentEntry): Boolean {
        return agents.putIfAbsent(entry.identity.agentId, entry) == null
    }

    /** 注销一个 Agent。 */
    fun unregister(agentId: String) {
        agents.remove(agentId)
    }

    /** 按 agentId 查找。 */
    fun get(agentId: String): AgentEntry? = agents[agentId]

    /** 获取所有活跃 Agent。 */
    fun getActiveAgents(): List<AgentEntry> = agents.values.toList()

    /** 按 Agent 类型查找。 */
    fun getByType(type: String): List<AgentEntry> =
        agents.values.filter { it.identity.agentType == type }

    /** 检查 Agent 是否已注册。 */
    fun contains(agentId: String): Boolean = agents.containsKey(agentId)

    /** 注册表大小。 */
    fun size(): Int = agents.size

    /** 清空注册表。 */
    fun clear() = agents.clear()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRegistry.kt
git commit -m "feat(workspace): add AgentRegistry for runtime agent instance tracking

Thread-safe registry for active agents. Supports lookup by ID,
type filtering, and iteration. Foundation for peer-to-peer mode."
```

---

### Task 5: Create MailboxService

**Files:**
- Create: `app/src/main/java/com/example/workspace/mailbox/MailboxService.kt`

- [ ] **Step 1: Create MailboxService**

```kotlin
package com.example.workspace.mailbox

import android.util.Log
import com.example.data.AppRepository
import com.example.data.MailboxMessage

/**
 * 邮箱服务 — Agent 间通信的 Room DB 后端。
 *
 * 替代 AgentRunner 中的 pendingMessages deque 和 interventionQueue。
 * 所有 Agent 间通信通过 MailboxService 进行，支持：
 * - 持久化消息队列（应用崩溃后可恢复）
 * - 投递确认（delivered 标志）
 * - 消息历史查询
 * - 广播（遍历 AgentRegistry 发送）
 *
 * @property repository 数据仓库
 */
class MailboxService(
    private val repository: com.example.data.AppRepository,
) {
    companion object {
        private const val TAG = "MailboxService"
        /** 每个 Agent 的未投递消息上限，超过后删除最旧的已投递消息 */
        private const val MAX_UNDELIVERED = 1000
    }

    /**
     * 向指定 Agent 发送消息。
     *
     * @param recipientInstanceId 目标 Agent 的 AgentInstance DB ID
     * @param message 要发送的消息（id 和 delivered 字段会被覆盖）
     */
    suspend fun send(recipientInstanceId: Long, message: MailboxMessage) {
        repository.insertMailboxMessage(message.copy(
            recipientAgentId = recipientInstanceId,
            delivered = false,
            createdAt = System.currentTimeMillis(),
        ))
        Log.d(TAG, "Sent message to agent $recipientInstanceId from ${message.senderAgentName}")
    }

    /**
     * 取出并标记所有未投递消息（原子操作）。
     *
     * 调用后这些消息的 delivered 标志被设为 true，
     * 后续调用不会重复返回。
     *
     * @param agentInstanceId Agent 的 AgentInstance DB ID
     * @return 未投递的消息列表，按时间顺序
     */
    suspend fun drain(agentInstanceId: Long): List<MailboxMessage> {
        val messages = repository.getUndeliveredMailboxMessages(agentInstanceId)
        for (msg in messages) {
            repository.markMailboxDelivered(msg.id)
        }
        if (messages.isNotEmpty()) {
            Log.d(TAG, "Drained ${messages.size} messages for agent $agentInstanceId")
        }
        return messages
    }

    /**
     * 检查是否有未投递消息（非阻塞）。
     */
    suspend fun hasUndelivered(agentInstanceId: Long): Boolean {
        return repository.countUndeliveredMailboxMessages(agentInstanceId) > 0
    }

    /**
     * 获取消息历史。
     */
    suspend fun getHistory(agentInstanceId: Long): List<MailboxMessage> {
        return repository.getMailboxHistory(agentInstanceId)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/mailbox/
git commit -m "feat(workspace): add MailboxService for Room DB-backed inter-agent communication

Replaces direct queuePendingMessage() calls and interventionQueue.
All inter-agent messages flow through MailboxService."
```

---

## Phase 2: Execution Layer

### Task 6: Rewrite AgentRunner to Use New Abstractions

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`

**Context:** The current AgentRunner (1034 lines) uses `TeammateContext` coroutine element for abort checks and has inline `pendingMessages`/`interventionQueue`. We replace these with `AgentLifecycleManager` and `MailboxService`.

- [ ] **Step 1: Update AgentRunner constructor**

Replace the constructor (lines 46-63) with:

```kotlin
class AgentRunner(
    private val context: AgentContext,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val lifecycleManager: AgentLifecycleManager,
    private val mailboxService: MailboxService,
    private val crossSessionMemory: String = "",
    private val availableModels: String = "",
    private val disallowedTools: Set<String> = emptySet(),
    private val sandboxPath: String? = null,
    private val maxToolIterations: Int = MAX_TOOL_CALL_ITERATIONS,
    private val isAsync: Boolean = false,
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onMessageAdded: (agentName: String, message: AgentMessage) -> Unit = { _, _ -> },
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String,
    private val persistMessage: ((AgentMessage) -> Unit)? = null,
)
```

- [ ] **Step 2: Remove TeammateContext references in runTurn**

In `runTurn()` (line 200), replace:
```kotlin
if (kotlin.coroutines.coroutineContext[TeammateContext]?.isCurrentTurnAborted == true) {
```
with:
```kotlin
if (lifecycleManager.isTurnAborted()) {
```

Remove the `capturedTeammateCtx` variable (line 240) and the `transform` block (lines 255-259). Replace with direct `.collect`:
```kotlin
ApiClient.executeStreamingChat(
    config = context.modelConfig.let {
        if (context.overrideModelId != null) it.copy(selectedModelId = context.overrideModelId)
        else it
    },
    systemPrompt = systemPrompt,
    history = history,
    tools = tools
).collect { chunk ->
    // ... existing chunk handling
}
```

- [ ] **Step 3: Replace pendingMessages with mailbox drain**

Remove the `pendingMessages` deque (line 149) and `drainPendingMessages()` method (lines 902-908). Replace the drain call in the do-while loop (lines 447-460) with:

```kotlin
// Drain mailbox at tool-turn boundary
val instanceId = context.agentInstanceId ?: 0L
if (instanceId > 0) {
    val mailboxMsgs = mailboxService.drain(instanceId)
    if (mailboxMsgs.isNotEmpty()) {
        messagesLock.writeLock().lock()
        try {
            for (msg in mailboxMsgs) {
                val agentMsg = AgentMessage(
                    role = msg.role,
                    content = msg.content,
                    source = msg.source,
                )
                context.messages.add(agentMsg)
                onMessageAdded(context.agentName, agentMsg)
                persistMessage?.invoke(agentMsg)
            }
        } finally {
            messagesLock.writeLock().unlock()
        }
    }
}
```

- [ ] **Step 4: Replace interventionQueue with mailbox**

Remove the `interventionQueue` deque (line 140) and `processInterventionQueue()` method (lines 882-894). In `injectMessage()`, when `isStreaming && isIntervention`, send via mailbox instead of queuing:

```kotlin
if (isStreaming && isIntervention) {
    val instanceId = context.agentInstanceId ?: 0L
    if (instanceId > 0) {
        WorkspaceScopes.auxiliary.launch {
            mailboxService.send(instanceId, MailboxMessage(
                recipientAgentId = instanceId,
                senderAgentName = "user",
                role = role,
                content = content,
                source = "intervention",
            ))
        }
        Log.d(TAG, "Queued intervention via mailbox for ${context.agentName}")
        return
    }
}
```

In the `finally` block of `runTurn()`, replace `processInterventionQueue()` with a mailbox drain call.

- [ ] **Step 5: Replace TeammateContext abort in streaming transform**

The `transform` lambda that checks `capturedTeammateCtx?.isCurrentTurnAborted` should use `lifecycleManager.isTurnAborted()` instead. Remove the `capturedTeammateCtx` variable entirely.

- [ ] **Step 6: Add agentInstanceId to AgentContext**

In `AgentContext.kt`, add `val agentInstanceId: Long? = null` field so AgentRunner can use it for mailbox operations.

- [ ] **Step 7: Run existing tests**

Run: `./gradlew testDebugUnitTest`
Expected: Workspace-related tests pass (some may need updates in later tasks).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt
git add app/src/main/java/com/example/workspace/AgentContext.kt
git commit -m "refactor(workspace): AgentRunner uses AgentLifecycleManager and MailboxService

Replace TeammateContext coroutine element with AgentLifecycleManager.
Replace pendingMessages/interventionQueue with MailboxService.
Remove capturedTeammateCtx, simplify streaming collect."
```

---

### Task 7: Move TeammateIdentity and Remove TeammateContext

**Files:**
- Modify: `app/src/main/java/com/example/workspace/WorkspaceModels.kt`
- Delete: `app/src/main/java/com/example/workspace/TeammateContext.kt`

**Context:** `TeammateIdentity` and `PermissionMode` are defined in `TeammateContext.kt` but used by `AgentLifecycleManager`, `AgentRegistry`, and executors. We must move them to `WorkspaceModels.kt` before deleting `TeammateContext.kt`.

- [ ] **Step 1: Move TeammateIdentity and PermissionMode to WorkspaceModels.kt**

Add to `WorkspaceModels.kt` (after the existing data classes):

```kotlin
/**
 * Teammate 身份信息。
 *
 * 标识一个 Agent 实例的完整身份，用于消息路由、日志和 UI 展示。
 */
data class TeammateIdentity(
    val agentId: String,
    val agentName: String,
    val teamName: String,
    val color: String = "",
    val agentType: String = "",
    val parentSessionId: String = "",
)

/**
 * 权限模式枚举。
 */
enum class PermissionMode {
    DEFAULT,
    AUTO,
    PLAN
}
```

- [ ] **Step 2: Update imports in files that used TeammateContext**

Update imports in `AgentLifecycleManager.kt`, `AgentRegistry.kt`, `OrchestratorExecutor.kt`, `PeerExecutor.kt` to use `com.example.workspace.TeammateIdentity` from `WorkspaceModels.kt` instead of `TeammateContext.kt`.

- [ ] **Step 3: Delete TeammateContext.kt**

Remove the file entirely. The abort-related code is now in `AgentLifecycleManager`. The identity types are now in `WorkspaceModels.kt`.

- [ ] **Step 4: Verify no remaining references**

Run: `grep -r "TeammateContext" app/src/main/java/com/example/`
Fix any remaining imports (the `teammateContext` extension property on CoroutineScope should also be removed).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/
git commit -m "refactor(workspace): remove TeammateContext, replaced by AgentLifecycleManager"
```

---

## Phase 3: Team Management

### Task 8: Create OrchestratorExecutor

**Files:**
- Create: `app/src/main/java/com/example/workspace/executor/OrchestratorExecutor.kt`

**Context:** Implements `TeammateExecutor` for orchestrator mode. Only the leader can spawn sub-agents. Sub-agents run via the existing `AgentTool` pattern but through the new abstraction.

- [ ] **Step 1: Create OrchestratorExecutor**

```kotlin
package com.example.workspace.executor

import android.util.Log
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
        val instanceId = repository.insertWorkspaceAgentInstance(
            com.example.data.AgentInstance(
                teamId = 0, // Will be set by TeamManager
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
```

- [ ] **Step 2: Define TeamCallbacks in WorkspaceModels.kt**

Add to `WorkspaceModels.kt`:

```kotlin
/**
 * 团队回调接口 — 将 TeamManager 的事件统一传递给 UI 层。
 */
data class TeamCallbacks(
    val onAgentCreated: (agentName: String, isOrchestrator: Boolean) -> Unit,
    val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    val onMessageAdded: (agentName: String, message: AgentMessage) -> Unit,
    val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
    val onWorkspaceComplete: (snapshot: TeamCompletionSnapshot) -> Unit,
    val onError: (message: String) -> Unit,
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/executor/OrchestratorExecutor.kt
git add app/src/main/java/com/example/workspace/WorkspaceModels.kt
git commit -m "feat(workspace): add OrchestratorExecutor for orchestrator-mode team management

Only leader can spawn sub-agents. Uses AgentRegistry and MailboxService.
Add TeamCallbacks to unify event handling."
```

---

### Task 9: Rewrite TeamManager as Facade

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

**Context:** Rewrite TeamManager to delegate to `OrchestratorExecutor` (and later `PeerExecutor`), `AgentRegistry`, `MailboxService`. Add team persistence.

- [ ] **Step 1: Rewrite TeamManager class structure**

Replace the entire `TeamManager.kt` with the new facade. Key changes:
- Constructor takes `TeamCallbacks` instead of individual callback lambdas
- `createTeam()` persists to `workspace_teams` table, creates executor
- `startExecution()` uses `AgentRunner` with new constructor (from Task 6)
- `deleteTeam()` cleans up DB records
- New: `resumeTeam()` loads from DB and restores state

The new TeamManager:
```kotlin
class TeamManager(
    private val repository: AppRepository,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val parentScope: CoroutineScope,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val callbacks: TeamCallbacks,
) {
    companion object {
        private const val TAG = "TeamManager"
    }

    private var executor: TeammateExecutor? = null
    private val agentRegistry = AgentRegistry()
    private val mailboxService = MailboxService(repository)
    private var teamDbId: Long = 0L
    private var crossSessionMemoryText: String = ""
    @Volatile private var cachedAvailableModelsStr: String = ""
    private val createTeamMutex = kotlinx.coroutines.sync.Mutex()

    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    suspend fun createTeam(
        teamName: String,
        mode: ExecutorType,
        orchestratorConfig: ModelConfig,
        orchestratorOverrideModelId: String? = null,
        sandboxPath: String? = null,
    ): TeamState = createTeamMutex.withLock {
        require(_teamState.value == null) { "已有活跃团队，请先删除当前团队" }

        crossSessionMemoryText = loadCrossSessionMemory()

        // Persist team to DB
        teamDbId = repository.insertWorkspaceTeam(WorkspaceTeam(
            teamName = teamName,
            mode = mode.name.lowercase(),
            orchestratorModelConfigId = orchestratorConfig.id,
            sandboxPath = sandboxPath,
        ))

        // Create executor based on mode
        executor = when (mode) {
            ExecutorType.ORCHESTRATOR -> OrchestratorExecutor(
                repository = repository,
                mcpRuntimeManager = mcpRuntimeManager,
                agentRegistry = agentRegistry,
                mailboxService = mailboxService,
                parentScope = parentScope,
                callbacks = callbacks,
                orchestratorConfig = orchestratorConfig,
                sandboxPath = sandboxPath,
            )
            ExecutorType.PEER -> throw NotImplementedError("PeerExecutor not yet implemented") // Phase 5
        }

        // Create orchestrator agent instance
        val agentDefinitions = loadAgentDefinitions(repository)
        val orchestratorInstanceId = repository.insertWorkspaceAgentInstance(
            AgentInstance(
                teamId = teamDbId,
                agentName = ORCHESTRATOR_NAME,
                agentType = "orchestrator",
                status = "idle",
                isOrchestrator = true,
                modelConfigId = orchestratorConfig.id,
                overrideModelId = orchestratorOverrideModelId,
            )
        )

        val identity = TeammateIdentity(
            agentId = "${ORCHESTRATOR_NAME}@${teamName}",
            agentName = ORCHESTRATOR_NAME,
            teamName = teamName,
        )

        val lifecycle = AgentLifecycleManager(identity) { status ->
            callbacks.onAgentStatusChanged(ORCHESTRATOR_NAME, status)
        }

        agentRegistry.register(AgentRegistry.AgentEntry(
            identity = identity,
            lifecycle = lifecycle,
            instanceId = orchestratorInstanceId,
        ))

        val state = TeamState(
            teamName = teamName,
            orchestratorConfig = orchestratorConfig,
            sandboxPath = sandboxPath,
        )
        _teamState.compareAndSet(null, state)

        callbacks.onAgentCreated(ORCHESTRATOR_NAME, true)
        state
    }

    suspend fun startExecution(userTask: String, imagePath: String? = null) {
        val state = _teamState.value ?: error("无活跃团队")
        val entry = agentRegistry.get("${ORCHESTRATOR_NAME}@${state.teamName}")
            ?: error("Orchestrator 未注册")

        // Create AgentRunner with new abstractions
        val agentDefinitions = loadAgentDefinitions(repository)
        val runner = AgentRunner(
            context = AgentContext(
                agentName = ORCHESTRATOR_NAME,
                isOrchestrator = true,
                systemPrompt = buildOrchestratorSystemPrompt(agentDefinitions, state.sandboxPath),
                modelConfig = state.orchestratorConfig,
                overrideModelId = null,
                teamName = state.teamName,
                messages = ArrayList(),
                agentInstanceId = entry.instanceId,
            ),
            mcpRuntimeManager = mcpRuntimeManager,
            lifecycleManager = entry.lifecycle,
            mailboxService = mailboxService,
            crossSessionMemory = crossSessionMemoryText,
            availableModels = cachedAvailableModelsStr,
            sandboxPath = state.sandboxPath,
            onStreamChunk = callbacks.onStreamChunk,
            onMessageAdded = callbacks.onMessageAdded,
            onToolCall = { agentName, toolName, args, callId ->
                val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
                if (serverId == null) "Error: Tool '$toolName' not found"
                else mcpRuntimeManager.callTool(serverId, toolName, args)?.toString() ?: "No result"
            },
        )

        // Launch in parent scope
        entry.lifecycle.transitionTo(AgentStatus.STREAMING)
        parentScope.launch {
            try {
                runner.runTurn(userMessage = userTask, imagePath = imagePath)
                entry.lifecycle.transitionTo(AgentStatus.COMPLETED)
            } catch (e: Exception) {
                Log.e(TAG, "Orchestrator execution failed", e)
                callbacks.onError(e.message ?: "Unknown error")
                entry.lifecycle.transitionTo(AgentStatus.ERROR)
            }
        }
    }

    suspend fun deleteTeam() {
        agentRegistry.clear()
        if (teamDbId > 0) {
            repository.deleteWorkspaceTeam(teamDbId)
        }
        _teamState.value = null
        teamDbId = 0L
    }

    // ... helper methods (loadCrossSessionMemory, etc.)
}
```

- [ ] **Step 2: Update Repository to add insertWorkspaceAgentInstance**

Add to `Repository.kt`:
```kotlin
suspend fun insertWorkspaceAgentInstance(instance: AgentInstance): Long =
    db.agentInstanceDao().insert(instance)
```

If `AgentInstanceDao` doesn't have an `insert` method that returns `Long`, add one.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git add app/src/main/java/com/example/data/Repository.kt
git commit -m "refactor(workspace): rewrite TeamManager as facade over executor/registry/mailbox

Delegates to TeammateExecutor, AgentRegistry, MailboxService.
Persists teams to workspace_teams table. Supports team creation
and orchestrator execution via new abstractions."
```

---

## Phase 4: Communication Integration

### Task 10: Update SendMessageTool to Use MailboxService

**Files:**
- Modify: `app/src/main/java/com/example/workspace/SendMessageTool.kt`

- [ ] **Step 1: Rewrite SendMessageTool**

```kotlin
class SendMessageTool(
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
) {
    companion object {
        private const val TAG = "SendMessageTool"
        const val TOOL_NAME = "send_message"

        val TOOL_SCHEMA = schema {
            prop("to", "string", "Target agent name or '*' for broadcast.")
            prop("message", "string", "Message content to send.")
            required("to", "message")
        }
    }

    suspend fun call(args: JSONObject): JSONObject {
        val to = args.optString("to", "")
        val message = args.optString("message", "")

        if (to.isEmpty()) return errorResult("Missing 'to' parameter")
        if (message.isEmpty()) return errorResult("Missing 'message' parameter")

        return try {
            if (to == "*") {
                broadcastMessage(message)
            } else {
                sendToAgent(to, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SendMessage failed", e)
            errorResult("SendMessage failed: ${e.message}")
        }
    }

    private suspend fun sendToAgent(agentName: String, message: String): JSONObject {
        // Find agent by name in registry
        val entry = agentRegistry.getActiveAgents().find {
            it.identity.agentName == agentName
        } ?: return errorResult("Agent '$agentName' not found")

        mailboxService.send(entry.instanceId, MailboxMessage(
            recipientAgentId = entry.instanceId,
            senderAgentName = "orchestrator",
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
                senderAgentName = "orchestrator",
                role = "user",
                content = message,
                source = "broadcast",
            ))
        }
        return JSONObject().apply {
            put("content", "Message broadcast to ${agents.size} agents")
        }
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/SendMessageTool.kt
git commit -m "refactor(workspace): SendMessageTool uses MailboxService instead of direct runner access

All inter-agent communication now flows through Room DB-backed mailbox."
```

---

### Task 11: Update AgentTool to Use New Abstractions

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentTool.kt`

**Context:** AgentTool currently creates AgentRunner directly. It should delegate to `TeammateExecutor.spawn()` or create runners using the new constructor.

- [ ] **Step 1: Update AgentTool to accept new dependencies**

Update constructor to accept `AgentRegistry` and `MailboxService`:
```kotlin
class AgentTool(
    private val mcpRuntimeManager: McpRuntimeManager,
    private val agentRegistry: AgentRegistry,
    private val mailboxService: MailboxService,
    private val agentDefinitions: List<AgentDefinition> = BuiltInAgents.ALL,
    private val onSubAgentCreated: (name: String, description: String) -> Unit = { _, _ -> },
    private val onSubAgentStreamChunk: (name: String, chunk: String) -> Unit = { _, _ -> },
    private val onSubAgentCompleted: (name: String, messages: List<AgentMessage>) -> Unit = { _, _ -> },
    private val onTaskNotification: (notification: TaskNotification) -> Unit = { _ -> },
)
```

- [ ] **Step 2: Update runSubAgent to use new AgentRunner constructor**

In `runSubAgent()`, create `AgentLifecycleManager` and pass it to `AgentRunner`:
```kotlin
val lifecycle = AgentLifecycleManager(subAgentIdentity) { status ->
    // status change callback
}

val runner = AgentRunner(
    context = subAgentContext,
    mcpRuntimeManager = mcpRuntimeManager,
    lifecycleManager = lifecycle,
    mailboxService = mailboxService,
    disallowedTools = disallowedTools,
    sandboxPath = sandboxPath,
    maxToolIterations = agentDef.maxTurns,
    onStreamChunk = { _, chunk -> onSubAgentStreamChunk(subAgentName, chunk) },
    onToolCall = { _, toolName, toolArgs, _ ->
        val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
        if (serverId == null) "Error: Tool '$toolName' not found"
        else mcpRuntimeManager.callTool(serverId, toolName, toolArgs)?.toString() ?: "No result"
    },
)
```

Do the same for `runAsyncSubAgent()`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTool.kt
git commit -m "refactor(workspace): AgentTool uses AgentLifecycleManager and new AgentRunner constructor"
```

---

## Phase 5: Peer Mode & UI

### Task 12: Create PeerExecutor

**Files:**
- Create: `app/src/main/java/com/example/workspace/executor/PeerExecutor.kt`

- [ ] **Step 1: Create PeerExecutor**

```kotlin
package com.example.workspace.executor

import android.util.Log
import com.example.data.AppRepository
import com.example.data.MailboxMessage
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

        val instanceId = repository.insertWorkspaceAgentInstance(
            com.example.data.AgentInstance(
                agentName = config.name,
                agentType = config.agentDefinition.agentType,
                status = "idle",
                modelConfigId = 0, // Will be resolved
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
                // Agent execution loop would go here
                // For now, similar to orchestrator but with spawn_agent tool
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/executor/PeerExecutor.kt
git commit -m "feat(workspace): add PeerExecutor for peer-to-peer agent collaboration

Any agent can spawn/message any other agent. No centralized leader."
```

---

### Task 13: Update WorkspaceViewModel for Team Mode and Resume

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt` (or equivalent)

- [ ] **Step 1: Add team mode state**

```kotlin
private val _teamMode = MutableStateFlow(ExecutorType.ORCHESTRATOR)
val teamMode: StateFlow<ExecutorType> = _teamMode

fun setTeamMode(mode: ExecutorType) {
    _teamMode.value = mode
}
```

- [ ] **Step 2: Add resume functionality**

```kotlin
suspend fun getResumableTeams(): List<WorkspaceTeam> {
    return repository.getActiveWorkspaceTeams()
}

fun resumeTeam(teamId: Long) {
    viewModelScope.launch {
        val team = repository.getWorkspaceTeamById(teamId) ?: return@launch
        // Recreate TeamManager and restore state
        // This will be fleshed out as TeamManager.resumeTeam() is implemented
    }
}
```

- [ ] **Step 3: Update createTeam to pass mode**

```kotlin
fun createTeam(name: String, config: ModelConfig, sandboxPath: String?) {
    teamManager.createTeam(
        teamName = name,
        mode = _teamMode.value,
        orchestratorConfig = config,
        sandboxPath = sandboxPath,
    )
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/
git commit -m "feat(workspace): add team mode toggle and resume support to WorkspaceViewModel"
```

---

### Task 14: Update WorkspaceScreen for Mode Selection

**Files:**
- Modify: relevant workspace UI files

- [ ] **Step 1: Add mode selector to team creation dialog**

Add a `SegmentedButton` or `RadioButton` group:
```kotlin
Row {
    FilterChip(
        selected = teamMode == ExecutorType.ORCHESTRATOR,
        onClick = { setTeamMode(ExecutorType.ORCHESTRATOR) },
        label = { Text("编排器模式") },
    )
    FilterChip(
        selected = teamMode == ExecutorType.PEER,
        onClick = { setTeamMode(ExecutorType.PEER) },
        label = { Text("对等模式") },
    )
}
```

- [ ] **Step 2: Add resume team button**

```kotlin
val resumableTeams = viewModel.getResumableTeams()
if (resumableTeams.isNotEmpty()) {
    // Show list of resumable teams
    LazyColumn {
        items(resumableTeams) { team ->
            ListItem(
                headlineContent = { Text(team.teamName) },
                supportingContent = { Text("模式: ${team.mode}") },
                trailingContent = {
                    Button(onClick = { viewModel.resumeTeam(team.id) }) {
                        Text("恢复")
                    }
                },
            )
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/
git commit -m "feat(workspace): add team mode selector and resume UI to WorkspaceScreen"
```

---

### Task 15: Clean Up — Delete Deprecated Files

**Files:**
- Delete: `app/src/main/java/com/example/workspace/TeammateContext.kt` (if not already done in Task 7)
- Delete: `app/src/main/java/com/example/workspace/ProgressTracker.kt`

- [ ] **Step 1: Delete deprecated files**

Remove `TeammateContext.kt` (replaced by `AgentLifecycleManager`) and `ProgressTracker.kt` (merged into `AgentUsageStats`).

- [ ] **Step 2: Verify no remaining references**

Run: `grep -r "TeammateContext\|ProgressTracker" app/src/main/java/com/example/`
Fix any remaining imports.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(workspace): remove deprecated TeammateContext and ProgressTracker files"
```

---

## Verification

After all tasks are complete:

1. Run `./gradlew testDebugUnitTest` — all tests pass
2. Run `./gradlew assembleDebug` — app builds
3. Manual test: create a team in orchestrator mode, execute a task, verify sub-agents spawn and complete
4. Manual test: create a team in peer mode, verify agents can spawn and message each other
5. Manual test: kill the app mid-execution, restart, verify team can be resumed
