# Agent Team Refactor: Port Claude Code's Agent Architecture

**Date:** 2026-05-28
**Status:** Approved
**Goal:** Replace the current complex multi-agent system with Claude Code's minimal Agent tool pattern

## Problem Statement

The current OmniChat Agent Team system has several issues:

1. **SubAgents lack context isolation** - they can't distinguish their own working directory from the Orchestrator's, leading to path confusion
2. **Unclear execution order** - SubAgents don't know who starts first
3. **Over-engineered complexity** - MessageBus, TaskManager, Claim mode, dependsOn, peer_message add ~5500 lines of code for capabilities that are rarely needed
4. **Fragile parsing** - `create_agents` tool has 3-layer JSON fallback parsing that breaks easily

The root cause: the Orchestrator pattern gives SubAgents too much autonomy (idle→execute→idle lifecycle, peer communication, task claiming) when what's actually needed is simple task delegation with result collection.

## Design Decision

Port Claude Code's Agent tool architecture: Lead Agent calls `Agent` tool → SubAgent executes independently with isolated context → returns result → Lead Agent continues.

Reference implementation: `E:/omnichat/ClaudeCode-Main/ClaudeCode-main/src/tools/AgentTool/`

## Architecture

### Core Flow

```
User task
  ↓
Orchestrator (MainAgent)
  ↓ calls Agent tool
AgentTool.call(description, prompt, model?)
  ↓
1. Create isolated AgentContext (fresh message history + system prompt)
2. Create SubAgentRunner (tool set + API streaming)
3. Synchronously execute runAgent() — coroutine suspends until completion
4. Collect last assistant message as result
5. Clean up resources
  ↓
Return tool result to Orchestrator
  ↓
Orchestrator continues reasoning or outputs completion marker
```

### New Files (~330 lines)

| File | Est. Lines | Responsibility |
|------|-----------|----------------|
| `AgentTool.kt` | ~80 | Tool schema definition + `call()` entry point |
| `AgentRunner.kt` | ~200 | SubAgent isolated execution loop |
| `AgentContext.kt` | ~50 | Slim context (from current ~200 lines) |

### Files to Delete (~4400 lines)

| File | Lines | Reason |
|------|-------|--------|
| `MessageBus.kt` | ~180 | No inter-agent messaging |
| `TaskManager.kt` | ~171 | No Claim mode task queue |
| `Scratchpad.kt` | ~100 | No cross-agent shared state |
| `AgentExecutionLoops.kt` | ~1245 | Replaced by AgentRunner.runAgent() |
| `OrchestratorTools.kt` | ~602 | Replaced by single AgentTool |
| `AgentLifecycle.kt` | ~575 | SubAgent lifecycle is inline in AgentTool |
| `AgentRegistry.kt` | ~200 | No preset system (for now) |
| `AgentDefinition.kt` | ~80 | No preset definitions |
| `AgentTask.kt` | ~263 | No TaskRegistry |
| `ModelSelector.kt` | ~152 | Simple model inheritance |
| `AgentProgressSummarizer.kt` | ~100 | No progress summaries for SubAgents |

### Files to Modify

| File | Change |
|------|--------|
| `TeamManager.kt` | 1201 → ~150 lines. Remove spawn/peer/task methods |
| `TeammateContext.kt` | Keep as-is for identity propagation |
| `WorkspaceModels.kt` | Keep TeamState/UI types, remove TaskMode/SpawnMode/etc |
| `McpRuntimeManager.kt` | Register `agent` in builtinTools |
| `BuiltinToolHandler.kt` | Add `agent` tool handler |
| `AgentTabBar.kt` | Simplify to show Orchestrator + active SubAgents only |
| `ToolOrchestrator.kt` | Keep (read-parallel/write-serial is useful) |

### Files to Delete (Tests)

| File | Reason |
|------|--------|
| `AgentRegistryTest.kt` | No AgentRegistry |
| `MessageBusDeadAgentTest.kt` | No MessageBus |
| `DeleteTeamSelfJoinTest.kt` | No complex lifecycle |
| `ScratchpadTest.kt` | No Scratchpad |
| `SpawnWithDependenciesDeadlineTest.kt` | No dependsOn |

### Files to Keep (Tests)

| File | Action |
|------|--------|
| `WorkspaceCoreTest.kt` | Rewrite: keep completion marker tests |
| `ToolOrchestratorTest.kt` | Keep as-is |

## Detailed Design

### 1. AgentTool Schema

```kotlin
object AgentTool {
    val schema = ToolSchema(
        name = "agent",
        description = "Launch a sub-agent to perform a task independently. " +
            "Use for: parallel work, isolated exploration, delegation of sub-tasks.",
        parameters = mapOf(
            "description" to mapOf(
                "type" to "string",
                "description" to "A short (3-5 word) description of the task"
            ),
            "prompt" to mapOf(
                "type" to "string",
                "description" to "The task for the agent to perform"
            ),
            "model" to mapOf(
                "type" to "string",
                "description" to "Model override (optional). If omitted, uses orchestrator's model"
            )
        ),
        required = listOf("description", "prompt")
    )
}
```

Differences from Claude Code:
- `model`: free string (not enum) because OmniChat supports multiple providers
- No `subagent_type` (no preset system)
- No `run_in_background` (sync only, async later)
- No `name`/`team_name` (no SendMessage routing)
- No `isolation` (no worktree on Android)

### 2. SubAgent Isolation

```kotlin
suspend fun runAgent(
    description: String,
    prompt: String,
    model: String?,
    parentContext: AgentContext,
    mcpRuntimeManager: McpRuntimeManager,
    apiClient: ApiClient,
    sandboxPath: String
): AgentToolResult {
    val modelConfig = model?.let { resolveModel(it, parentContext.modelConfig) }
        ?: parentContext.modelConfig

    val subContext = AgentContext(
        name = "sub-${description.hashCode().toUInt().toString(16)}",
        systemPrompt = buildSubAgentSystemPrompt(sandboxPath),
        modelConfig = modelConfig,
        teamName = parentContext.teamName,
        messages = ArrayList()  // Fresh, not shared
    )

    val tools = buildToolSet(mcpRuntimeManager, excludeAgentTool = true)
    subContext.messages.add(Message(role = "user", content = prompt))

    val result = executeAgentLoop(subContext, tools, apiClient)

    return AgentToolResult(
        content = result.lastAssistantMessage?.content ?: "",
        totalTokens = result.totalTokens,
        totalToolCalls = result.totalToolCalls
    )
}
```

### 3. SubAgent Tool Set

Available: all built-in tools (read_file, write_file, create_directory, etc.) + all MCP tools
Excluded: `agent` (prevent recursion), `adjust_ui_*` (no UI changes), `manage_memory` (no memory ops)

### 4. System Prompts

**Orchestrator (Chinese):**
- Explains the `agent` tool for task delegation
- When to delegate vs do it yourself
- Completion marker: `【任务完成】`
- Injects `[SANDBOX_PATH]`, `[CROSS_SESSION_MEMORY]`, `[MCP_TOOLS]`, `[AVAILABLE_MODELS]`

**SubAgent (English):**
```
You are a sub-agent executing a specific task.

## Task
${description}

## Instructions
${prompt}

## Working Directory
${sandboxPath}

## Rules
- Use available tools to complete the work
- Do NOT call the agent tool
- Output your final result when done
- If you encounter an unsolvable problem, explain why and stop
```

### 5. Result Return

```kotlin
data class AgentToolResult(
    val content: String,
    val totalTokens: Int,
    val totalToolCalls: Int
)

fun AgentToolResult.toToolResult(): ToolResult {
    return ToolResult(
        content = listOf(mapOf("type" to "text", "text" to content))
    )
}
```

Errors are caught and returned as result text (not thrown), letting the Orchestrator decide whether to retry.

### 6. TeamManager Simplification

Remove methods:
- `spawnTeammate()` (~100 lines)
- `spawnSubAgentsFromDirective()` (~80 lines)
- `spawnWithDependencies()` (~120 lines)
- `sendPeerMessage()` (~60 lines)
- `continueAgent()` (~40 lines)
- `createTaskForAgent()` (~30 lines)
- All MessageBus/TaskManager integration (~200+ lines)

Keep:
- `createTeam()` — creates Orchestrator
- `startExecution()` — launches Orchestrator coroutine
- `deleteTeam()` — cleanup
- `_teamState` — UI state

### 7. TeamState Simplification

```kotlin
data class TeamState(
    val teamName: String,
    val orchestratorName: String,
    val status: AgentStatus,
    val activeSubAgents: List<SubAgentInfo>,
    val isRunning: Boolean
)

data class SubAgentInfo(
    val name: String,
    val description: String,
    val status: AgentStatus
)
```

## Net Impact

```
Deleted: ~4400 lines (source) + ~800 lines (tests)
Added:   ~330 lines (source) + ~100 lines (tests)
Net:     -4770 lines (~73% reduction)
```

## Future Extensions

- **Async execution** (`run_in_background`): Agent #1 and #2 can run in parallel
- **Agent presets** (`subagent_type`): Pre-defined agent types like "explorer", "coder"
- **Worktree isolation**: If Android ever supports git worktrees
