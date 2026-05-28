# Workspace 模块架构重构设计

## 概述

基于 Claude Code 源码分析，对 OmniChat 的 workspace 多 Agent 模块进行架构重构。目标是引入统一的 Task 抽象、工具并发执行、Agent 定义文件化、进度摘要和共享暂存区，提升代码质量和系统性能。

## 背景

当前 workspace 模块存在以下架构问题：

1. **生命周期管理分散**：Agent 的创建/销毁/中止分散在 `AgentLifecycle`、`AgentExecutionLoops`、`TeamManager` 中，通过 `CoroutineScope` + `Job` + `AtomicBoolean` 组合管理，缺乏统一抽象。
2. **工具执行效率低**：`AgentRunner.runTurn()` 中工具调用完全串行执行，多个只读工具（如同时读取多个文件）无法并行。
3. **Agent 配置硬编码**：系统提示词、工具过滤规则硬编码在 `AgentContext.kt` 和 `AgentRunner.kt` 中，修改需要改代码。
4. **进度可见性差**：Orchestrator 无法实时了解子 Agent 的执行进展，只能等待 `ResultReport`。
5. **跨 Agent 数据传递低效**：子 Agent 之间传递大块数据只能通过消息，容易丢失上下文。

## 设计目标

- 统一 Task 生命周期管理，所有后台工作通过 `AgentTask` 接口控制
- 工具调用按读/写分类，并行执行只读工具提升吞吐
- Agent 配置外部化，支持从 JSON 文件加载
- 子 Agent 定期生成进度摘要，Orchestrator 可查询
- 提供共享 Scratchpad 目录，支持跨 Agent 文件共享

## 模块设计

### 1. AgentTask — 统一 Task 抽象

**文件**: `workspace/AgentTask.kt`

```kotlin
interface AgentTask {
    val taskId: String
    val taskName: String
    val taskType: TaskType
    val status: TaskStatus

    suspend fun execute()
    fun kill()
    fun onStatusChange(listener: (TaskStatus) -> Unit)
}

enum class TaskType { ORCHESTRATOR, TEAMMATE, COORDINATOR }
enum class TaskStatus { CREATED, RUNNING, COMPLETED, FAILED, CANCELLED }
```

**TaskRegistry**:

```kotlin
class TaskRegistry {
    private val tasks = ConcurrentHashMap<String, AgentTask>()

    fun register(task: AgentTask)
    fun get(taskId: String): AgentTask?
    fun getByType(type: TaskType): List<AgentTask>
    fun killAll()
    fun observeStatus(): Flow<TaskStatusEvent>
}

data class TaskStatusEvent(
    val taskId: String,
    val taskName: String,
    val oldStatus: TaskStatus,
    val newStatus: TaskStatus,
    val timestamp: Long
)
```

**实现类**:

- `OrchestratorTask : AgentTask` — 封装 `runOrchestratorLoop()`
- `TeammateTask : AgentTask` — 封装 `runTeammateLoop()`

每个 Task 内部持有自己的 `CoroutineScope`、`TeammateContext`、`AgentRunner`。`TeammateContext` 的中止控制委托给 Task 的 `kill()` 方法。

**与现有代码的关系**:

- `AgentLifecycle.runners` 改为引用 `TaskRegistry`
- `AgentLifecycle.teammateJobs/teammateScopes` 被 `AgentTask` 内部管理
- `WorkspaceViewModel` 通过 `TaskRegistry.observeStatus()` 获取任务状态
- `TeamManager.deleteTeam()` 调用 `TaskRegistry.killAll()`

### 2. ToolOrchestrator — 工具并发执行

**文件**: `workspace/ToolOrchestrator.kt`

```kotlin
class ToolOrchestrator(
    private val mcpRuntimeManager: McpRuntimeManager,
    private val builtinToolHandler: BuiltinToolHandler
) {
    private val readOnlyTools = setOf(
        "read_file", "list_directory", "search_files",
        "get_file_info", "search_memory", "get_current_time"
    )

    suspend fun executeToolCalls(
        calls: List<ToolCall>,
        agentName: String
    ): List<ToolResult>
}
```

**执行策略**:

```
输入: [read_file A, read_file B, write_file C, read_file D, edit_file E]
  ↓
分区:
  Batch 1 (读): [read_file A, read_file B] → 并行 (coroutineScope + async)
  Batch 2 (写): [write_file C] → 串行
  Batch 3 (读): [read_file D] → 并行
  Batch 4 (写): [edit_file E] → 串行
  ↓
结果按原序返回
```

**分区算法** (`partitionToolCalls()`):

```kotlin
fun partitionToolCalls(calls: List<ToolCall>): List<ToolBatch> {
    val batches = mutableListOf<ToolBatch>()
    var currentReadBatch = mutableListOf<ToolCall>()

    for (call in calls) {
        if (call.name in readOnlyTools) {
            currentReadBatch.add(call)
        } else {
            if (currentReadBatch.isNotEmpty()) {
                batches.add(ToolBatch(currentReadBatch.toList(), isParallel = true))
                currentReadBatch.clear()
            }
            batches.add(ToolBatch(listOf(call), isParallel = false))
        }
    }
    if (currentReadBatch.isNotEmpty()) {
        batches.add(ToolBatch(currentReadBatch.toList(), isParallel = true))
    }
    return batches
}
```

**错误处理**: 单个工具失败不影响同批次其他工具，失败结果照常返回 `ToolResult(isError = true)`。

**MCP 外部工具分类**: 默认归类为写工具。如果 MCP 工具 schema 中包含 `"readOnly": true` 标记，则归类为读工具。

**对 `AgentRunner.runTurn()` 的改动**:

```kotlin
// 现在: 顺序执行
for (toolCall in toolCalls) {
    val result = onToolCall(agentName, toolCall.name, toolCall.args, toolCall.callId)
}

// 改为: 通过 ToolOrchestrator
val results = toolOrchestrator.executeToolCalls(toolCalls, agentName)
```

**编排工具不受影响**: `OrchestratorTools` 中的编排工具 (`create_agents`, `assign_task` 等) 仍由 `OrchestratorTools.handleToolCall()` 拦截处理，不进入 `ToolOrchestrator`。

### 3. AgentDefinition + AgentRegistry — Agent 定义文件化

**文件**: `workspace/AgentDefinition.kt`

```kotlin
data class AgentDefinition(
    val name: String,
    val displayName: String,
    val systemPrompt: String,
    val modelHint: ModelHint? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    val isOrchestrator: Boolean = false,
    val maxToolIterations: Int = 50,
    val description: String = ""
)
```

**JSON 文件格式**:

```json
{
    "name": "code_reviewer",
    "displayName": "代码审查员",
    "systemPrompt": "你是一个专业的代码审查员...",
    "modelHint": "REASONING",
    "allowedTools": ["read_file", "list_directory", "search_files", "get_file_info"],
    "disallowedTools": [],
    "isOrchestrator": false,
    "maxToolIterations": 30,
    "description": "负责代码审查和质量分析"
}
```

**加载来源（优先级从高到低）**:

1. `assets/workspace/agents/*.json` — 内置预设
2. 外部存储 `OmniChat/workspace/agents/*.json` — 用户自定义
3. Room DB `AgentPreset` — UI 中保存的配置（向后兼容）

**AgentRegistry**:

```kotlin
class AgentRegistry(private val context: Context) {
    private val definitions = ConcurrentHashMap<String, AgentDefinition>()

    fun loadAll()  // 扫描 assets + 外存 + DB，合并去重
    fun get(name: String): AgentDefinition?
    fun getAll(): List<AgentDefinition>
    fun getByHint(hint: ModelHint): List<AgentDefinition>
}
```

**声明式工具过滤**:

```kotlin
fun getFilteredTools(def: AgentDefinition, allTools: List<Tool>): List<Tool> {
    var tools = allTools
    def.allowedTools?.let { allowed ->
        tools = tools.filter { it.name in allowed }
    }
    def.disallowedTools?.let { blocked ->
        tools = tools.filter { it.name !in blocked }
    }
    return tools
}
```

**内置预设定义**:

| 名称 | 类型 | 工具策略 | 用途 |
|------|------|---------|------|
| `orchestrator` | Orchestrator | 黑名单: 写工具/UI工具 | 主控 Agent |
| `teammate_default` | Teammate | 全部工具 | 通用子 Agent |
| `explorer` | Teammate | 白名单: 只读工具 | 快速搜索分析 |
| `verifier` | Teammate | 白名单: 只读+bash | 验证实现 |

### 4. AgentProgressSummarizer — 进度摘要

**文件**: `workspace/AgentProgressSummarizer.kt`

```kotlin
class AgentProgressSummarizer(
    private val agentName: String,
    private val messageHistory: () -> List<AgentMessage>
) {
    private var lastSummaryIndex = 0

    suspend fun maybeSummarize(toolCallCount: Int): String? {
        if (toolCallCount - lastSummaryIndex < SUMMARY_INTERVAL) return null
        lastSummaryIndex = toolCallCount

        val recentMessages = messageHistory().drop(lastSummaryIndex)
        if (recentMessages.isEmpty()) return null

        return buildString {
            appendLine("[$agentName 进度摘要]")
            extractKeyActions(recentMessages).forEach { appendLine("- $it") }
        }
    }

    companion object {
        const val SUMMARY_INTERVAL = 5
    }
}
```

**触发条件**:

1. 每 5 次工具调用自动触发
2. 子 Agent 执行超过 2 分钟时触发一次
3. Orchestrator 主动查询时触发

**注入方式**: 摘要作为系统消息注入到 Orchestrator 上下文，格式 `<agent-progress agent="xxx">...</agent-progress>`，不占用子 Agent 上下文。

### 5. Scratchpad — 共享暂存区

**文件**: `workspace/Scratchpad.kt`

```kotlin
class Scratchpad(private val basePath: File) {

    init { basePath.mkdirs() }

    fun write(agentName: String, key: String, content: String) {
        basePath.resolve("${agentName}_${key}.txt").writeText(content)
    }

    fun read(agentName: String, key: String): String? {
        val file = basePath.resolve("${agentName}_${key}.txt")
        return if (file.exists()) file.readText() else null
    }

    fun list(): List<ScratchpadEntry>
    fun cleanup(agentName: String)
    fun clearAll()
}
```

**存储位置**: `OmniChat/workspace/{sessionId}/scratchpad/`

**MCP 工具暴露**:

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `scratchpad_write` | `key`, `content` | 写入当前 Agent 的共享数据 |
| `scratchpad_read` | `agentName`, `key` | 读取任意 Agent 的共享数据 |
| `scratchpad_list` | 无 | 列出所有共享数据 |

**生命周期**: 随 `TeamManager.deleteTeam()` 时调用 `Scratchpad.clearAll()` 清理。

## 架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                      UI Layer (Compose)                          │
│  WorkspaceScreen → AgentTabBar, AgentMessageArea, TeamTaskPanel  │
└──────────────────────────┬───────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────────┐
│                     WorkspaceViewModel                           │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐             │
│  │ TaskRegistry │  │ TeamState    │  │ AgentTabs   │             │
│  │ .observe()   │  │ StateFlow    │  │ StateFlow   │             │
│  └─────────────┘  └──────────────┘  └─────────────┘             │
└──────────────────────────┬───────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────────┐
│                       TeamManager (facade)                       │
│  ┌────────────────┐  ┌─────────────────┐  ┌───────────────────┐ │
│  │  TaskRegistry   │  │ AgentRegistry   │  │   Scratchpad      │ │
│  │  (生命周期管理)  │  │ (Agent 定义)     │  │ (跨Agent共享)     │ │
│  └───────┬────────┘  └────────┬────────┘  └───────────────────┘ │
│          │                    │                                  │
│  ┌───────▼────────────────────▼──────────────────────────────┐  │
│  │                 AgentLifecycle                             │  │
│  │  spawnTeammate(AgentDefinition) → AgentTask               │  │
│  │  killTeammate(taskId)                                     │  │
│  └───────────────────────┬───────────────────────────────────┘  │
│                          │                                      │
│  ┌───────────────────────▼───────────────────────────────────┐  │
│  │              AgentExecutionLoops                           │  │
│  │  OrchestratorTask : AgentTask                             │  │
│  │  TeammateTask : AgentTask                                 │  │
│  │  ┌─────────────────────────────────────────────────────┐  │  │
│  │  │              AgentRunner                             │  │  │
│  │  │  runTurn() → ToolOrchestrator.executeToolCalls()    │  │  │
│  │  │  ┌─────────────────────────────────────────────┐    │  │  │
│  │  │  │         ToolOrchestrator                     │    │  │  │
│  │  │  │  partitionToolCalls() → [读批次并行, 写批次串行] │    │  │  │
│  │  │  └─────────────────────────────────────────────┘    │  │  │
│  │  │  AgentProgressSummarizer                           │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────┐  ┌──────────────────┐  ┌───────────────────┐
│ MessageBus  │  │   TaskManager    │  │  ModelSelector    │
│ (Channel)   │  │   (Room DB)      │  │  (hint→model)     │
└─────────────┘  └──────────────────┘  └───────────────────┘
```

## 文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `workspace/AgentTask.kt` | Task 接口 + TaskRegistry |
| 新增 | `workspace/ToolOrchestrator.kt` | 工具并发执行引擎 |
| 新增 | `workspace/AgentDefinition.kt` | Agent 定义数据类 |
| 新增 | `workspace/AgentRegistry.kt` | Agent 定义加载/查询 |
| 新增 | `workspace/Scratchpad.kt` | 共享暂存区 |
| 新增 | `workspace/AgentProgressSummarizer.kt` | 进度摘要生成 |
| 新增 | `assets/workspace/agents/*.json` | 内置 Agent 预设 |
| 重写 | `workspace/AgentRunner.kt` | 使用 Task 接口 + ToolOrchestrator |
| 重构 | `workspace/AgentExecutionLoops.kt` | 封装为 AgentTask 实现 |
| 重构 | `workspace/AgentLifecycle.kt` | 使用 AgentRegistry + TaskRegistry |
| 重构 | `workspace/OrchestratorTools.kt` | 使用 AgentRegistry 查找定义 |
| 重构 | `workspace/TeamManager.kt` | 使用 TaskRegistry 管理生命周期 |
| 重构 | `mcp/BuiltinToolHandler.kt` | 新增 Scratchpad 工具 |
| 微调 | `viewmodel/WorkspaceViewModel.kt` | 适配 TaskRegistry 状态查询 |

## 实现阶段

| 阶段 | 内容 | 风险 |
|------|------|------|
| Phase 1 | 新增 `AgentTask.kt` + `TaskRegistry` | 低 |
| Phase 2 | 新增 `ToolOrchestrator.kt` | 低 |
| Phase 3 | 新增 `AgentDefinition.kt` + `AgentRegistry.kt` + JSON 预设 | 低 |
| Phase 4 | 新增 `Scratchpad.kt` + MCP 工具 | 低 |
| Phase 5 | 重构 `AgentRunner.kt` — 接入 Task 接口 + ToolOrchestrator | 中 |
| Phase 6 | 重构 `AgentExecutionLoops.kt` — 封装为 AgentTask 实现 | 中 |
| Phase 7 | 重构 `AgentLifecycle.kt` + `OrchestratorTools.kt` — 使用 AgentRegistry | 中 |
| Phase 8 | 重构 `TeamManager.kt` — 使用 TaskRegistry | 中 |
| Phase 9 | 微调 `WorkspaceViewModel.kt` + `BuiltinToolHandler.kt` | 低 |
| Phase 10 | 测试 + 验证 | — |

## 向后兼容

- Room DB `AgentPreset` 保留，`AgentRegistry.loadAll()` 会加载并转换为 `AgentDefinition`
- `TeamManager` 公共 API 签名尽量保持不变，内部实现替换
- 三种任务分配模式（Direct/Claim/DependsOn）保留
- `WorkspaceViewModel` 对外接口不变
- 现有单元测试需要适配新的 TaskRegistry 接口

## 关键设计决策

1. **Task 接口而非抽象类**: 使用接口而非抽象类，因为 `OrchestratorTask` 和 `TeammateTask` 的执行逻辑差异大，接口更灵活。
2. **ToolOrchestrator 独立于 AgentRunner**: 工具编排逻辑从 AgentRunner 中抽离，职责更清晰，也方便独立测试。
3. **AgentDefinition 静态加载**: Agent 定义在 `AgentRegistry.loadAll()` 时一次性加载到内存，运行期间不动态变更（需要重启 workspace 才能生效）。
4. **Scratchpad 文件而非内存**: 使用文件系统而非内存存储，因为跨 Agent 的数据量可能较大，且文件系统更便于调试。
5. **进度摘要不阻塞执行**: `maybeSummarize()` 是非阻塞的，仅在工具调用间隔检查，不影响主执行流程。
