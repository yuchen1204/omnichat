# Workspace 模块 P0/P1/P2 改进设计

## 概述

基于 Claude Code 源码对比分析，对 OmniChat workspace 模块进行 5 项改进：Fork vs Spawn、实时周期性摘要、Coordinator 模式、Agent 类型系统、Transcript 轻量版。

## 背景

上一轮重构完成了 Task 抽象、工具并发、Agent 定义文件化、Scratchpad、进度摘要。对比 Claude Code 仍有差距，本次改进填补 P0/P1/P2 级别的能力缺口。

## 改进项

### 1. Fork vs Spawn (P0)

**问题**：子 Agent 从零开始，无法复用父 Agent 的上下文，浪费 token。

**方案**：在 `AgentLifecycle.spawnTeammate()` 中新增 `SpawnMode` 参数。

```kotlin
enum class SpawnMode {
    SPAWN,  // 全新上下文（默认，向后兼容）
    FORK    // 继承父上下文 + 系统提示
}
```

**Fork 流程**：
1. 从 `parentRunner.getHistory()` 复制对话历史
2. 从 `parentRunner.getSystemPrompt()` 复制系统提示
3. 创建新的 `AgentContext`，`messages = ArrayList(parentHistory)`
4. 后续创建 Runner、启动循环（与 Spawn 相同）

**AgentRunner 新增方法**：
```kotlin
fun getSystemPrompt(): String = context.systemPrompt
```

**OrchestratorTools.create_agents 新增 `spawnMode` 字段**：
```json
{"name": "explorer", "role": "搜索", "spawnMode": "fork"}
```

**效果**：Fork 子 Agent 的 API 请求前缀与父相同，命中 prompt cache，节省大量 token。

### 2. 实时周期性摘要 (P1)

**问题**：Orchestrator 无法在子 Agent 执行过程中感知进度。

**方案**：在 `AgentRunner` 中集成 `onProgressSummary` 回调。

```kotlin
// AgentRunner 构造函数新增
class AgentRunner(
    ...
    private val onProgressSummary: ((agentName: String, summary: String) -> Unit)? = null,
)
```

**触发位置**：`runTurn()` 中工具执行后，检查 `progressSummarizer.onToolCallCompleted()`，非 null 时调用回调。

**回调实现**（在 AgentExecutionLoops 中）：
```kotlin
{ agentName, summary ->
    messageBus.send(ORCHESTRATOR_NAME, TeamMessage.Text(from = "system", content = summary))
}
```

**触发条件**：每 5 次工具调用自动触发，不阻塞主执行流程。

### 3. Coordinator 模式 (P1)

**问题**：Orchestrator 工具过滤不够灵活，无法切换到纯调度模式。

**方案**：在 `AgentDefinition` 中新增 `mode` 字段。

```kotlin
enum class AgentMode {
    NORMAL,      // 正常模式（默认）
    COORDINATOR  // 协调者模式：只保留编排工具
}
```

**工具过滤逻辑**：
- `NORMAL` 模式：按 `allowedTools`/`disallowedTools` 过滤（现有逻辑）
- `COORDINATOR` 模式：只返回 `create_agents`、`assign_task`、`continue_conversation`、`peer_message`

**使用方式**：在 JSON 预设中设置 `"mode": "COORDINATOR"`。

### 4. Agent 类型系统 (P2)

**问题**：Orchestrator 无法根据任务类型自动选择最佳 Agent 配置。

**方案**：在 `AgentRegistry` 中注册内置类型，`create_agents` 工具支持 `type` 字段。

**内置类型**：

| type | 模型 | 工具策略 | 用途 |
|------|------|---------|------|
| `general` | default | 全部工具 | 多步研究和实现 |
| `explorer` | FAST hint | 只读工具 | 快速搜索分析 |
| `verifier` | REASONING hint | 只读 + bash | 验证实现 |
| `coordinator` | default | 仅编排工具 | 纯调度模式 |

**加载优先级**：文件预设 > 内置类型（`putIfAbsent`）

**OrchestratorTools.create_agents 新增 `type` 字段**：
```json
{"name": "CodeExplorer", "type": "explorer", "role": "搜索相关文件"}
```

**解析逻辑**：指定 `type` 时从 `AgentRegistry` 查找定义，自动填充 `systemPrompt`、`modelHint`、`allowedTools` 等。

### 5. Transcript 轻量版 (P2)

**问题**：Agent 崩溃后无法从断点恢复。

**方案**：利用 Room DB `WorkspaceMessage` 增量持久化 + 简单恢复。

#### 5A：增量持久化

```kotlin
// AgentRunner 构造函数新增
class AgentRunner(
    ...
    private val persistMessage: ((AgentMessage) -> Unit)? = null,
)
```

在 `runTurn()` 中，每条消息写入 `context.messages` 后调用 `persistMessage`。

WorkspaceViewModel 实现：
```kotlin
val runner = createRunner(context, isSubAgent) { message ->
    viewModelScope.launch(Dispatchers.IO) {
        repository.insertWorkspaceMessage(WorkspaceMessage(...))
    }
}
```

#### 5B：上下文恢复

```kotlin
// AgentLifecycle 新增
suspend fun restoreAgentContext(agentName: String, sessionId: Long): AgentContext? {
    val messages = repository.getWorkspaceMessages(sessionId, agentName)
    if (messages.isEmpty()) return null
    return AgentContext(
        agentName = agentName,
        systemPrompt = "",
        messages = ArrayList(messages.map { it.toAgentMessage() }),
        ...
    )
}
```

#### 5C：恢复执行

```kotlin
// TeamManager 新增
suspend fun resumeAgent(agentName: String, sessionId: Long): Boolean {
    val context = lifecycle.restoreAgentContext(agentName, sessionId) ?: return false
    val runner = createAgentRunner(context, isSubAgent = true)
    val identity = _teamState.value?.teammates?.get(agentName)?.identity ?: return false
    val task = TeammateTask(agentName, runner, identity, executionLoops)
    taskRegistry.register(task)
    lifecycle.teammateScopes[agentName]?.launch { task.execute() }
    return true
}
```

#### 5D：DAO 查询

```kotlin
@Query("SELECT * FROM workspace_messages WHERE workspace_session_id = :sessionId AND agent_name = :agentName ORDER BY timestamp ASC")
suspend fun getMessagesForAgent(sessionId: Long, agentName: String): List<WorkspaceMessage>
```

## 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    TeamManager (facade)                      │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐ │
│  │ AgentRegistry │  │ TaskRegistry  │  │ Scratchpad       │ │
│  │ + 类型系统    │  │               │  │                  │ │
│  └──────┬───────┘  └───────┬───────┘  └──────────────────┘ │
│         │                  │                                │
│  ┌──────▼──────────────────▼─────────────────────────────┐ │
│  │              AgentLifecycle                            │ │
│  │  spawnTeammate(SpawnMode.SPAWN/FORK)                  │ │
│  │  restoreAgentContext(sessionId)                        │ │
│  └───────────────────────┬───────────────────────────────┘ │
│                          │                                 │
│  ┌───────────────────────▼───────────────────────────────┐ │
│  │              AgentExecutionLoops                       │ │
│  │  OrchestratorTask / TeammateTask                       │ │
│  │  ┌───────────────────────────────────────────────────┐│ │
│  │  │              AgentRunner                           ││ │
│  │  │  runTurn() → ToolOrchestrator                     ││ │
│  │  │  onProgressSummary → MessageBus                   ││ │
│  │  │  persistMessage → Room DB                         ││ │
│  │  └───────────────────────────────────────────────────┘│ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 文件清单

| 操作 | 文件 | 改动 |
|------|------|------|
| 修改 | `AgentLifecycle.kt` | 新增 `SpawnMode`、`restoreAgentContext` |
| 修改 | `AgentRunner.kt` | 新增 `getSystemPrompt()`、`onProgressSummary`、`persistMessage` |
| 修改 | `AgentExecutionLoops.kt` | 集成实时摘要回调 |
| 修改 | `AgentDefinition.kt` | 新增 `mode: AgentMode` |
| 修改 | `AgentRegistry.kt` | 新增 `registerBuiltinTypes()` |
| 修改 | `OrchestratorTools.kt` | `create_agents` 支持 `type` + `spawnMode` |
| 修改 | `TeamManager.kt` | 新增 `resumeAgent()` |
| 修改 | `WorkspaceMessageDao.kt` | 新增 `getMessagesForAgent` |
| 修改 | `WorkspaceViewModel.kt` | 集成 `persistMessage` |

## 向后兼容

- `SpawnMode.SPAWN` 为默认值，现有行为不变
- `AgentMode.NORMAL` 为默认值，现有 Orchestrator 行为不变
- `type` 字段可选，不指定时使用 `name` 查找
- `persistMessage` 回调可选，不传则不持久化
