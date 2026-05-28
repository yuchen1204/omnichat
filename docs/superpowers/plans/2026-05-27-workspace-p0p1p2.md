# Workspace P0/P1/P2 改进 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Fork vs Spawn, real-time summaries, Coordinator mode, Agent type system, and lightweight transcript recovery for the workspace module.

**Architecture:** Extend existing types (AgentDefinition, AgentRunner, AgentLifecycle, AgentRegistry, OrchestratorTools) with new enum fields and optional callbacks. All changes are backward-compatible with default values.

**Tech Stack:** Kotlin 2.2.10, Room 2.7.0, coroutines, ConcurrentHashMap

---

## File Structure

| 操作 | 文件路径 | 改动 |
|------|---------|------|
| 修改 | `app/src/main/java/com/example/workspace/AgentDefinition.kt` | 新增 `AgentMode` 枚举 + `mode` 字段 |
| 修改 | `app/src/main/java/com/example/workspace/AgentRegistry.kt` | 新增 `registerBuiltinTypes()` |
| 修改 | `app/src/main/java/com/example/workspace/AgentRunner.kt` | 新增 `getSystemPrompt()`、`onProgressSummary`、`persistMessage` |
| 修改 | `app/src/main/java/com/example/workspace/AgentLifecycle.kt` | 新增 `SpawnMode`、`restoreAgentContext` |
| 修改 | `app/src/main/java/com/example/workspace/OrchestratorTools.kt` | `create_agents` 支持 `type` + `spawnMode` |
| 修改 | `app/src/main/java/com/example/workspace/TeamManager.kt` | 新增 `resumeAgent()` |
| 修改 | `app/src/main/java/com/example/data/Daos.kt` | 新增 `getMessagesForAgent` 查询 |
| 修改 | `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt` | 集成 `persistMessage` |

---

## Task 1: AgentDefinition 新增 AgentMode

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentDefinition.kt`

- [ ] **Step 1: 添加 AgentMode 枚举和 mode 字段**

在 `AgentDefinition.kt` 顶部（`data class` 之前）添加枚举：

```kotlin
/**
 * Agent execution mode.
 *
 * Controls tool filtering strategy for this agent.
 */
enum class AgentMode {
    /** Normal mode: filter by allowedTools/disallowedTools */
    NORMAL,
    /** Coordinator mode: only orchestration tools available */
    COORDINATOR
}
```

在 `AgentDefinition` data class 中添加字段（在 `description` 之后）：

```kotlin
val mode: AgentMode = AgentMode.NORMAL,
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentDefinition.kt
git commit -m "feat(workspace): add AgentMode enum to AgentDefinition"
```

---

## Task 2: AgentRegistry 内置类型注册

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRegistry.kt`

- [ ] **Step 1: 在 loadAll() 末尾调用 registerBuiltinTypes()**

在 `AgentRegistry.kt` 的 `loadAll()` 方法末尾添加：

```kotlin
fun loadAll() {
    loadFromAssets()
    loadFromExternalStorage()
    registerBuiltinTypes()  // NEW
}
```

在类末尾（`loadFromExternalStorage` 方法之后）添加：

```kotlin
/**
 * Register built-in agent types.
 *
 * These are the lowest priority definitions — file-based presets and DB presets
 * override them via putIfAbsent.
 */
private fun registerBuiltinTypes() {
    val builtinTypes = listOf(
        AgentDefinition(
            name = "general",
            displayName = "通用 Agent",
            systemPrompt = "",
            mode = AgentMode.NORMAL,
            description = "Multi-step research and implementation, all tools available"
        ),
        AgentDefinition(
            name = "explorer",
            displayName = "探索者",
            systemPrompt = "",
            modelHint = ModelHint.FAST,
            allowedTools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
            mode = AgentMode.NORMAL,
            maxToolIterations = 30,
            description = "Fast read-only search and analysis"
        ),
        AgentDefinition(
            name = "verifier",
            displayName = "验证者",
            systemPrompt = "",
            modelHint = ModelHint.REASONING,
            allowedTools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time", "execute_command"),
            mode = AgentMode.NORMAL,
            maxToolIterations = 30,
            description = "Implementation verification, read-only + bash"
        ),
        AgentDefinition(
            name = "coordinator",
            displayName = "协调者",
            systemPrompt = "",
            mode = AgentMode.COORDINATOR,
            description = "Pure orchestration mode, only dispatch tools"
        )
    )
    for (def in builtinTypes) {
        definitions.putIfAbsent(def.name, def)
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRegistry.kt
git commit -m "feat(workspace): register built-in agent types in AgentRegistry"
```

---

## Task 3: OrchestratorTools 支持 type + spawnMode

**Files:**
- Modify: `app/src/main/java/com/example/workspace/OrchestratorTools.kt`

- [ ] **Step 1: 在 AgentSpec 中新增 spawnMode 字段**

在 `WorkspaceModels.kt` 中的 `AgentSpec` data class 添加字段：

```kotlin
data class AgentSpec(
    val name: String,
    val role: String = "",
    val systemPrompt: String = "",
    val modelConfigId: Long? = null,
    val modelId: String? = null,
    val modelHint: ModelHint? = null,
    val dependsOn: List<String> = emptyList(),
    val spawnMode: SpawnMode = SpawnMode.SPAWN,  // NEW
)
```

在 `WorkspaceModels.kt` 中添加 `SpawnMode` 枚举（在 `AgentSpec` 之前）：

```kotlin
/**
 * Agent spawn mode.
 *
 * SPAWN: fresh context (default)
 * FORK: inherit parent's conversation history + system prompt
 */
enum class SpawnMode {
    SPAWN,
    FORK
}
```

- [ ] **Step 2: 修改 parseAgentSpecs 解析 type 和 spawnMode**

在 `OrchestratorTools.kt` 的 `parseAgentSpecs` 方法中，在构建 `AgentSpec` 时添加：

```kotlin
// After modelHint parsing, before AgentSpec construction:
val spawnModeStr = agentObj.optString("spawnMode", "spawn")
val spawnMode = runCatching { SpawnMode.valueOf(spawnModeStr.uppercase()) }.getOrNull() ?: SpawnMode.SPAWN

val type = agentObj.optString("type", "")
val agentDef = if (type.isNotEmpty()) agentRegistry?.get(type) else null

val spec = AgentSpec(
    name = name,
    role = agentObj.optString("role", ""),
    systemPrompt = agentDef?.systemPrompt?.takeIf { it.isNotEmpty() }
        ?: agentObj.optString("systemPrompt", ""),
    modelConfigId = agentObj.optLong("modelConfigId").takeIf { it > 0 },
    modelId = agentObj.optString("modelId").takeIf { it.isNotEmpty() },
    modelHint = agentDef?.modelHint
        ?: agentObj.optString("modelHint", "").takeIf { it.isNotEmpty() }
            ?.let { runCatching { ModelHint.valueOf(it.uppercase()) }.getOrNull() },
    dependsOn = deps,
    spawnMode = spawnMode,
)
```

需要在 `OrchestratorTools` 构造函数中添加 `agentRegistry` 参数：

```kotlin
class OrchestratorTools(
    private val teamManager: TeamManager,
    private val repository: AppRepository,
    private val messageBus: MessageBus,
    private val mcpRuntimeManager: com.example.mcp.McpRuntimeManager,
    private val agentRegistry: AgentRegistry,  // NEW
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/WorkspaceModels.kt
git add app/src/main/java/com/example/workspace/OrchestratorTools.kt
git commit -m "feat(workspace): add type + spawnMode support to create_agents tool"
```

---

## Task 4: AgentRunner 新增 getSystemPrompt + onProgressSummary + persistMessage

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`

- [ ] **Step 1: 在构造函数中添加新参数**

在 `AgentRunner` 构造函数中添加（在 `onToolCall` 之后）：

```kotlin
class AgentRunner(
    private val context: AgentContext,
    private val mcpRuntimeManager: McpRuntimeManager,
    private val crossSessionMemory: String = "",
    private val availableModels: String = "",
    private val disallowedTools: Set<String> = emptySet(),
    private val sandboxPath: String? = null,
    private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String,
    private val onProgressSummary: ((agentName: String, summary: String) -> Unit)? = null,  // NEW
    private val persistMessage: ((AgentMessage) -> Unit)? = null,  // NEW
)
```

- [ ] **Step 2: 添加 getSystemPrompt() 方法**

在 `AgentRunner` 类中（在 `getAgentName()` 方法附近）添加：

```kotlin
/**
 * Get the system prompt for this agent.
 *
 * Used by Fork mode to inherit parent's system prompt.
 */
fun getSystemPrompt(): String = context.systemPrompt
```

- [ ] **Step 3: 添加 progressSummarizer 属性**

在 `AgentRunner` 类中（在 `interventionQueue` 之后）添加：

```kotlin
private val progressSummarizer = AgentProgressSummarizer(context.agentName) { getHistory() }
```

- [ ] **Step 4: 在 runTurn 中集成摘要回调**

在 `runTurn()` 方法中，工具执行后（`toolOrchestrator.executeToolCalls` 返回后，`consecutiveToolFailureCount` 检查之前）添加：

```kotlin
// Progress summary check
val progressSummary = progressSummarizer.onToolCallCompleted()
if (progressSummary != null) {
    onProgressSummary?.invoke(context.agentName, progressSummary)
}
```

- [ ] **Step 5: 在消息写入后调用 persistMessage**

在 `runTurn()` 中，所有 `context.messages.add(...)` 调用之后添加 `persistMessage?.invoke(message)`。具体位置：

1. Assistant 消息保存后（约 line 365）：`persistMessage?.invoke(context.messages.last())`
2. Tool 结果保存后（在 `messagesLock.writeLock()` 块内）：`persistMessage?.invoke(message)`

- [ ] **Step 6: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt
git commit -m "feat(workspace): add getSystemPrompt, onProgressSummary, persistMessage to AgentRunner"
```

---

## Task 5: AgentLifecycle 支持 Fork + Transcript 恢复

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentLifecycle.kt`

- [ ] **Step 1: 在 spawnTeammate 中添加 spawnMode 和 parentRunner 参数**

```kotlin
suspend fun spawnTeammate(
    name: String,
    prompt: String,
    systemPrompt: String = "",
    modelConfigId: Long? = null,
    overrideModelId: String? = null,
    modelHint: ModelHint? = null,
    spawnMode: SpawnMode = SpawnMode.SPAWN,  // NEW
    parentRunner: AgentRunner? = null,        // NEW: required for FORK mode
    existingNames: Set<String>,
    parentScope: CoroutineScope,
    createRunner: suspend (AgentContext, Boolean) -> AgentRunner,
    executeLoop: suspend (AgentRunner, TeammateIdentity) -> Unit,
): TeammateIdentity {
```

- [ ] **Step 2: 修改 AgentContext 创建逻辑**

在 `spawnTeammate` 中，替换现有的 `AgentContext` 创建逻辑（约 line 258-266）：

```kotlin
val context = when (spawnMode) {
    SpawnMode.FORK -> {
        requireNotNull(parentRunner) { "Fork mode requires parentRunner" }
        AgentContext(
            agentName = uniqueName,
            isOrchestrator = false,
            systemPrompt = parentRunner.getSystemPrompt(),
            modelConfig = actualModelConfig,
            overrideModelId = actualOverrideModelId,
            teamName = teamName,
            messages = ArrayList(parentRunner.getHistory()),
        )
    }
    SpawnMode.SPAWN -> {
        AgentContext(
            agentName = uniqueName,
            isOrchestrator = false,
            systemPrompt = finalSystemPrompt.ifEmpty { DEFAULT_TEAMMATE_PROMPT },
            modelConfig = actualModelConfig,
            overrideModelId = actualOverrideModelId,
            teamName = teamName,
            messages = ArrayList(),
        )
    }
}
```

- [ ] **Step 3: 添加 restoreAgentContext 方法**

在 `AgentLifecycle` 类末尾添加：

```kotlin
/**
 * Restore agent context from Room DB.
 *
 * Used for transcript recovery — rebuilds an agent's conversation history
 * from persisted WorkspaceMessage records.
 */
suspend fun restoreAgentContext(
    agentName: String,
    sessionId: Long,
    systemPrompt: String = "",
): AgentContext? {
    val messages = repository.getWorkspaceMessagesForAgent(sessionId, agentName)
    if (messages.isEmpty()) return null

    val orchestratorRunner = runners[ORCHESTRATOR_NAME]
    val teamName = orchestratorRunner?.getTeamName() ?: "default"

    return AgentContext(
        agentName = agentName,
        isOrchestrator = false,
        systemPrompt = systemPrompt.ifEmpty { DEFAULT_TEAMMATE_PROMPT },
        modelConfig = orchestratorRunner?.getModelConfig()
            ?: error("No orchestrator config available"),
        teamName = teamName,
        messages = ArrayList(messages.map { msg ->
            AgentMessage(
                role = msg.role,
                content = msg.content,
                toolCallId = msg.toolCallId,
                toolCallsJson = msg.toolCallsJson,
                isIntervention = msg.isIntervention,
                source = msg.source,
                imagePath = msg.imagePath,
                timestamp = msg.timestamp,
            )
        }),
    )
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentLifecycle.kt
git commit -m "feat(workspace): add SpawnMode fork and restoreAgentContext to AgentLifecycle"
```

---

## Task 6: TeamManager 支持 resumeAgent + Fork 传递

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: 在 spawnTeammate 中传递 spawnMode 和 parentRunner**

修改 `TeamManager.spawnTeammate()` 方法，添加参数：

```kotlin
suspend fun spawnTeammate(
    name: String,
    prompt: String,
    systemPrompt: String = "",
    modelConfigId: Long? = null,
    overrideModelId: String? = null,
    modelHint: ModelHint? = null,
    spawnMode: SpawnMode = SpawnMode.SPAWN,  // NEW
    parentRunner: AgentRunner? = null,        // NEW
): TeammateIdentity {
    // ... existing code ...
    val identity = lifecycle.spawnTeammate(
        // ... existing params ...
        spawnMode = spawnMode,     // NEW
        parentRunner = parentRunner, // NEW
        // ... rest of params ...
    )
    // ... existing code ...
}
```

- [ ] **Step 2: 在 spawnSubAgentsFromDirective 中传递 spawnMode**

修改 `spawnSubAgentsFromDirective()` 中的 `spawnTeammate` 调用：

```kotlin
val identity = spawnTeammate(
    name = spec.name,
    prompt = "等待主控分配任务...",
    systemPrompt = spec.systemPrompt,
    modelConfigId = spec.modelConfigId,
    overrideModelId = spec.modelId,
    modelHint = spec.modelHint,
    spawnMode = spec.spawnMode,  // NEW
    parentRunner = if (spec.spawnMode == SpawnMode.FORK) lifecycle.runners[ORCHESTRATOR_NAME] else null,  // NEW
)
```

- [ ] **Step 3: 添加 resumeAgent 方法**

在 `TeamManager` 类末尾添加：

```kotlin
/**
 * Resume an agent from persisted transcript.
 *
 * Restores the agent's conversation history from Room DB and
 * restarts its execution loop.
 */
suspend fun resumeAgent(agentName: String, sessionId: Long): Boolean {
    val state = _teamState.value ?: return false
    if (agentName !in state.teammates) return false

    val def = agentRegistry.get(agentName)
    val context = lifecycle.restoreAgentContext(
        agentName = agentName,
        sessionId = sessionId,
        systemPrompt = def?.systemPrompt ?: "",
    ) ?: return false

    val runner = createAgentRunner(context, isSubAgent = true)
    lifecycle.runners[agentName] = runner

    val identity = state.teammates[agentName]?.identity ?: return false
    val scope = lifecycle.teammateScopes[agentName] ?: return false

    val job = scope.launch {
        executionLoops.runTeammateLoop(runner, identity)
    }
    lifecycle.teammateJobs[agentName] = job
    return true
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat(workspace): add fork mode and resumeAgent to TeamManager"
```

---

## Task 7: WorkspaceMessageDao 新增查询 + Repository 暴露

**Files:**
- Modify: `app/src/main/java/com/example/data/Daos.kt`
- Modify: `app/src/main/java/com/example/data/Repository.kt`

- [ ] **Step 1: 在 WorkspaceMessageDao 中添加查询**

在 `Daos.kt` 的 `WorkspaceMessageDao` 中添加：

```kotlin
@Query("SELECT * FROM workspace_messages WHERE workspaceSessionId = :sessionId AND agent_name = :agentName ORDER BY timestamp ASC")
suspend fun getMessagesForAgent(sessionId: Long, agentName: String): List<WorkspaceMessage>
```

注意：需要检查 `workspace_messages` 表的实际列名。如果 `agent_name` 列实际叫 `agentInstanceId`，则使用：

```kotlin
@Query("SELECT * FROM workspace_messages WHERE workspaceSessionId = :sessionId AND agentInstanceId = :agentName ORDER BY timestamp ASC")
suspend fun getMessagesForAgent(sessionId: Long, agentName: String): List<WorkspaceMessage>
```

- [ ] **Step 2: 在 Repository 中暴露**

在 `AppRepository` 中添加：

```kotlin
suspend fun getWorkspaceMessagesForAgent(sessionId: Long, agentName: String): List<WorkspaceMessage> =
    workspaceMessageDao.getMessagesForAgent(sessionId, agentName)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/data/Daos.kt
git add app/src/main/java/com/example/data/Repository.kt
git commit -m "feat(workspace): add getMessagesForAgent query for transcript recovery"
```

---

## Task 8: WorkspaceViewModel 集成 persistMessage

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt`

- [ ] **Step 1: 在 createAgentRunner 中传入 persistMessage 回调**

找到 `createAgentRunner` 方法（或 TeamManager 中创建 runner 的位置），添加 `persistMessage` 参数：

```kotlin
// In TeamManager.createAgentRunner():
return AgentRunner(
    // ... existing params ...
    onProgressSummary = { agentName, summary ->
        // Send progress summary to orchestrator via messageBus
        WorkspaceScopes.auxiliary.launch {
            try {
                messageBus.send(ORCHESTRATOR_NAME, TeamMessage.Text(from = "system", content = summary))
            } catch (_: Exception) {}
        }
    },
    persistMessage = { message ->
        // Persist to Room DB asynchronously
        WorkspaceScopes.auxiliary.launch {
            try {
                val sessionId = _teamState.value?.teamName?.hashCode()?.toLong() ?: 0L
                repository.insertWorkspaceMessage(
                    WorkspaceMessage(
                        workspaceSessionId = sessionId,
                        agentInstanceId = context.agentName,
                        role = message.role,
                        content = message.content,
                        toolCallId = message.toolCallId,
                        toolCallsJson = message.toolCallsJson,
                        isIntervention = message.isIntervention,
                        source = message.source,
                        imagePath = message.imagePath,
                        timestamp = message.timestamp,
                    )
                )
            } catch (e: Exception) {
                Log.w("TeamManager", "Failed to persist message", e)
            }
        }
    },
)
```

注意：`persistMessage` 的实现需要 `currentSessionId`。实际实现时需要从 `WorkspaceViewModel` 传入当前 session ID。

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 运行全部测试**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.*"`
Expected: ALL TESTS PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat(workspace): integrate persistMessage and onProgressSummary in TeamManager"
```

---

## 最终验证

- [ ] **全量编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **全量单元测试**

Run: `./gradlew testDebugUnitTest`
Expected: ALL TESTS PASS

- [ ] **功能验证**

在设备上运行 app，测试：
1. Fork 模式：Orchestrator 调用 `create_agents` 时指定 `"spawnMode": "fork"`，子 Agent 应继承父上下文
2. 实时摘要：子 Agent 执行过程中，Orchestrator 应收到进度摘要消息
3. Coordinator 模式：JSON 预设中设置 `"mode": "COORDINATOR"`，Orchestrator 应只有编排工具
4. 类型系统：`create_agents` 中指定 `"type": "explorer"`，应自动匹配只读工具策略
5. Transcript 恢复：子 Agent 执行后消息应持久化到 Room DB
