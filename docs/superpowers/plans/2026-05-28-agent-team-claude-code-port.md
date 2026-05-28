# Agent Team Claude Code Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current complex multi-agent system (MessageBus, TaskManager, Claim mode, dependsOn, peer_message) with Claude Code's minimal Agent tool pattern — Lead Agent calls `agent` tool → SubAgent executes independently → returns result.

**Architecture:** Single `agent` built-in tool registered in McpRuntimeManager. Orchestrator calls it like any other tool. SubAgent gets isolated AgentContext (fresh messages, English system prompt, no `agent` tool access). Synchronous blocking — coroutine suspends until SubAgent completes. Reuses existing AgentRunner for the SubAgent LLM loop.

**Tech Stack:** Kotlin, Android coroutines, existing ApiClient (SSE streaming), existing McpRuntimeManager, existing ToolOrchestrator.

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/example/workspace/AgentTool.kt` | Tool schema + `call()` entry point + SubAgent orchestration |
| `app/src/test/java/com/example/workspace/AgentToolTest.kt` | Unit tests for AgentTool |

### Files to Rewrite

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/example/workspace/AgentContext.kt` | Slim context + new system prompts (Chinese orchestrator, English SubAgent) |

### Files to Modify

| File | Change |
|------|--------|
| `app/src/main/java/com/example/workspace/TeamManager.kt` | Wire AgentTool into onToolCall, remove old orchestrator tool routing |
| `app/src/main/java/com/example/workspace/WorkspaceModels.kt` | Remove TaskMode, SpawnMode, WaitResult, OrchestratorDirective, AgentSpec; simplify TeamState |
| `app/src/main/java/com/example/mcp/McpRuntimeManager.kt` | Register `agent` in builtinTools list + builtinToolGroups map |
| `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` | Add `agent` dispatch case |

### Files to Delete

| File | Reason |
|------|--------|
| `MessageBus.kt` | No inter-agent messaging |
| `TaskManager.kt` | No Claim mode task queue |
| `Scratchpad.kt` | No cross-agent shared state |
| `AgentExecutionLoops.kt` | Replaced by AgentTool + existing AgentRunner |
| `OrchestratorTools.kt` | Replaced by AgentTool intercept in onToolCall |
| `AgentLifecycle.kt` | SubAgent lifecycle is inline in AgentTool |
| `AgentRegistry.kt` | No preset system |
| `AgentDefinition.kt` | No preset definitions |
| `AgentTask.kt` | No TaskRegistry |
| `ModelSelector.kt` | Simple model config lookup in AgentTool |
| `AgentProgressSummarizer.kt` | No progress summaries for SubAgents |

### Test Files to Delete

| File | Reason |
|------|--------|
| `AgentRegistryTest.kt` | No AgentRegistry |
| `MessageBusDeadAgentTest.kt` | No MessageBus |
| `DeleteTeamSelfJoinTest.kt` | No complex lifecycle |
| `ScratchpadTest.kt` | No Scratchpad |
| `SpawnWithDependenciesDeadlineTest.kt` | No dependsOn |

### Test Files to Keep/Modify

| File | Action |
|------|--------|
| `WorkspaceCoreTest.kt` | Keep completion marker tests, remove directive parsing tests |
| `ToolOrchestratorTest.kt` | Keep as-is |

---

### Task 1: Create AgentTool.kt

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentTool.kt`

- [ ] **Step 1: Create AgentTool with schema and call() method**

```kotlin
package com.example.workspace

import android.util.Log
import com.example.data.ModelConfig
import com.example.mcp.McpRuntimeManager
import com.example.network.ApiClient
import org.json.JSONObject

/**
 * Claude Code-style agent tool. Spawns a SubAgent with isolated context,
 * executes synchronously, returns the last assistant message as result.
 */
class AgentTool(
    private val mcpRuntimeManager: McpRuntimeManager,
    private val apiClient: ApiClient,
) {
    companion object {
        const val TOOL_NAME = "agent"

        val TOOL_SCHEMA = mapOf(
            "name" to TOOL_NAME,
            "description" to "Launch a sub-agent to perform a task independently. " +
                "The agent runs in isolation with its own context and tools. " +
                "Use for: parallel work, isolated exploration, delegation of sub-tasks.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "description" to mapOf(
                        "type" to "string",
                        "description" to "A short (3-5 word) description of the task"
                    ),
                    "prompt" to mapOf(
                        "type" to "string",
                        "description" to "The complete task for the agent to perform, including all context and requirements"
                    ),
                    "model" to mapOf(
                        "type" to "string",
                        "description" to "Model override (optional). If omitted, uses the orchestrator's model"
                    )
                ),
                "required" to listOf("description", "prompt")
            )
        )

        private const val TAG = "AgentTool"
        private const val MAX_TOOL_ITERATIONS = 30
    }

    /**
     * Execute the agent tool. Creates an isolated SubAgent, runs it synchronously,
     * and returns the last assistant message as the tool result.
     *
     * @param args JSON arguments with "description", "prompt", and optional "model"
     * @param parentContext The orchestrator's AgentContext (for model config inheritance)
     * @param sandboxPath The working directory for the SubAgent
     * @return Tool result JSON with the SubAgent's final output
     */
    suspend fun call(
        args: JSONObject,
        parentContext: AgentContext,
        sandboxPath: String,
    ): JSONObject {
        val description = args.optString("description", "unnamed task")
        val prompt = args.optString("prompt", "")
        val modelOverride = args.optString("model", "").ifEmpty { null }

        if (prompt.isBlank()) {
            return errorResult("prompt is required and cannot be empty")
        }

        Log.i(TAG, "Spawning SubAgent: $description")

        return try {
            val result = runSubAgent(description, prompt, modelOverride, parentContext, sandboxPath)
            successResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "SubAgent failed: $description", e)
            errorResult("Agent execution failed: ${e.message}")
        }
    }

    private suspend fun runSubAgent(
        description: String,
        prompt: String,
        modelOverride: String?,
        parentContext: AgentContext,
        sandboxPath: String,
    ): SubAgentResult {
        // 1. Resolve model config
        val modelConfig = modelOverride?.let { resolveModel(it, parentContext.modelConfig) }
            ?: parentContext.modelConfig

        // 2. Create isolated SubAgent context
        val subContext = AgentContext(
            agentName = "sub-${description.hashCode().toUInt().toString(16)}",
            isOrchestrator = false,
            systemPrompt = buildSubAgentPrompt(description, prompt, sandboxPath),
            modelConfig = modelConfig,
            teamName = parentContext.teamName,
            messages = ArrayList(),
        )

        // 3. Build tool set (exclude 'agent' to prevent recursion)
        val toolSet = buildSubAgentToolSet()

        // 4. Create AgentRunner for SubAgent
        val runner = AgentRunner(
            context = subContext,
            mcpRuntimeManager = mcpRuntimeManager,
            crossSessionMemory = "",
            availableModels = "",
            disallowedTools = setOf(TOOL_NAME),
            sandboxPath = sandboxPath,
            maxToolIterations = MAX_TOOL_ITERATIONS,
            onStreamChunk = { _, _ -> }, // SubAgent streams are not shown to user
            onToolCall = { _, toolName, toolArgs, _ ->
                // SubAgent tool calls go directly to McpRuntimeManager
                mcpRuntimeManager.callTool(toolName, toolArgs).toString()
            },
        )

        // 5. Run synchronously
        runner.runTurn(userMessage = prompt)

        // 6. Extract last assistant message
        val lastAssistant = subContext.messages.lastOrNull { it.role == "assistant" }
        val content = lastAssistant?.content ?: "(no response)"

        return SubAgentResult(
            content = content,
            totalTokens = 0, // TODO: track if needed
            totalToolCalls = subContext.messages.count { it.role == "tool" }
        )
    }

    private fun resolveModel(modelName: String, fallback: ModelConfig): ModelConfig {
        // Simple lookup: try to match by model name in the repository
        // For now, just use the fallback. Override can be added later.
        return fallback
    }

    private fun buildSubAgentToolSet(): Map<String, Any> {
        // The actual tool set is built by AgentRunner.getFilteredTools()
        // We just need to ensure 'agent' is in disallowedTools
        return emptyMap() // AgentRunner handles tool filtering internally
    }

    private fun buildSubAgentPrompt(description: String, prompt: String, sandboxPath: String): String {
        return """You are a sub-agent executing a specific task.

## Task
$description

## Instructions
$prompt

## Working Directory
$sandboxPath

## Rules
- Use available tools to complete the work
- Do NOT call the agent tool
- Output your final result when done
- If you encounter an unsolvable problem, explain why and stop"""
    }

    private fun successResult(result: SubAgentResult): JSONObject {
        return JSONObject().apply {
            put("content", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", result.content)
                })
            })
            put("isError", false)
        }
    }

    private fun errorResult(message: String): JSONObject {
        return JSONObject().apply {
            put("content", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", message)
                })
            })
            put("isError", true)
        }
    }

    data class SubAgentResult(
        val content: String,
        val totalTokens: Int,
        val totalToolCalls: Int,
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (AgentTool has no external dependencies beyond existing code)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTool.kt
git commit -m "feat(workspace): add AgentTool for Claude Code-style sub-agent delegation"
```

---

### Task 2: Rewrite AgentContext.kt with new system prompts

**Files:**
- Rewrite: `app/src/main/java/com/example/workspace/AgentContext.kt`

- [ ] **Step 1: Read the current AgentContext.kt to understand the full structure**

Read `app/src/main/java/com/example/workspace/AgentContext.kt` and note:
- The `AgentContext` data class fields
- The `AgentMessage` data class (if it exists here or elsewhere)
- The `buildSystemPrompt()` extension function
- The `ORCHESTRATOR_SYSTEM_PROMPT` constant
- Any other constants or classes in this file

- [ ] **Step 2: Rewrite AgentContext.kt**

Keep the `AgentContext` data class and `buildSystemPrompt()` function. Replace `ORCHESTRATOR_SYSTEM_PROMPT` with the new Chinese prompt that explains the `agent` tool. Remove any references to `create_agents`, `assign_task`, `continue_conversation`, `peer_message`.

New `ORCHESTRATOR_SYSTEM_PROMPT`:

```kotlin
const val ORCHESTRATOR_SYSTEM_PROMPT = """你是一个任务编排助手。你的职责是理解用户需求，将复杂任务拆分为子任务，并使用 agent 工具委派给独立的子 Agent 执行。

## 可用工具
你可以直接使用所有可用的工具（文件操作、MCP 工具等），也可以使用 agent 工具委派子任务。

## agent 工具
调用方式：
- description: 3-5 个词的简短任务描述（英文）
- prompt: 完整的任务描述，包含所有必要的上下文和要求（英文）
- model: 可选，指定子 Agent 使用的模型

## 何时委派
- 任务可以拆分为独立的子任务
- 子任务之间没有强依赖
- 需要并行处理以提高效率

## 何时自己做
- 任务简单直接
- 需要与用户交互确认
- 子任务之间有强依赖，必须顺序执行

## 子 Agent 特性
- 子 Agent 有独立的消息历史，无法访问你的对话
- 子 Agent 有自己的工具集（文件操作、MCP 工具等）
- 子 Agent 不能调用 agent 工具（防止递归）
- 子 Agent 完成后返回结果文本

## 收到子 Agent 结果后
- 分析子 Agent 的输出
- 如果结果不完整或有误，可以重新委派或自己补充
- 汇总所有结果后输出【任务完成】标记

## 工作目录
[SANDBOX_PATH]

## 可用模型
[AVAILABLE_MODELS]

## 可用工具
[MCP_TOOLS]"""
```

- [ ] **Step 3: Verify the buildSystemPrompt() function still works**

Ensure the `[SANDBOX_PATH]`, `[MCP_TOOLS]`, `[AVAILABLE_MODELS]` placeholders are still replaced correctly. The `[CROSS_SESSION_MEMORY]` placeholder should also be kept for future use.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentContext.kt
git commit -m "refactor(workspace): rewrite ORCHESTRATOR_SYSTEM_PROMPT for agent tool"
```

---

### Task 3: Register agent tool in McpRuntimeManager

**Files:**
- Modify: `app/src/main/java/com/example/mcp/McpRuntimeManager.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Add agent tool to builtinTools list in McpRuntimeManager.kt**

In the `builtinTools` list (around line 497), add a new entry:

```kotlin
McpTool(
    serverId = BUILTIN_SERVER_ID,
    serverName = BUILTIN_SERVER_NAME,
    name = "agent",
    description = "Launch a sub-agent to perform a task independently",
    inputSchema = AgentTool.TOOL_SCHEMA["parameters"] as Map<String, Any?>
),
```

- [ ] **Step 2: Add agent to builtinToolGroups map**

In the `builtinToolGroups` map (around line 876), add:

```kotlin
"agent" to "core",
```

- [ ] **Step 3: Add agent case to BuiltinToolHandler.kt**

In `handleBuiltinTool()` (line 35), add a new case in the `when` block:

```kotlin
"agent" -> handleAgentTool(arguments)
```

Add the handler method:

```kotlin
private suspend fun handleAgentTool(arguments: JSONObject): JSONObject {
    val agentTool = teamManager?.getAgentTool()
        ?: return errorResponse("AgentTool not available: no active workspace")
    val parentContext = teamManager?.getOrchestratorContext()
        ?: return errorResponse("AgentTool not available: no orchestrator context")
    val sandboxPath = teamManager?.getSandboxPath()
        ?: return errorResponse("AgentTool not available: no sandbox path")
    return agentTool.call(arguments, parentContext, sandboxPath)
}
```

- [ ] **Step 4: Add accessor methods to TeamManager**

In TeamManager.kt, add:

```kotlin
fun getAgentTool(): AgentTool? = agentTool
fun getOrchestratorContext(): AgentContext? = orchestratorContext
fun getSandboxPath(): String? = sandboxPath
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/mcp/McpRuntimeManager.kt
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git add app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat(workspace): register agent tool in McpRuntimeManager"
```

---

### Task 4: Refactor TeamManager — wire AgentTool, remove old routing

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Read current TeamManager.kt completely**

Read the full file. Identify:
- Constructor parameters (lines 56-71)
- `createTeam()` method (lines 115-214)
- `startExecution()` method (lines 219-268)
- `createAgentRunner()` method (lines 974-1039)
- `deleteTeam()` method (lines 433-497)
- All methods to remove: `spawnTeammate()`, `spawnSubAgentsFromDirective()`, `spawnWithDependencies()`, `sendPeerMessage()`, `continueAgent()`, `createTaskForAgent()`, etc.

- [ ] **Step 2: Simplify TeamManager constructor**

Remove parameters that no longer exist:
- `messageBus: MessageBus` — delete
- `taskManager: TaskManager` — delete
- `agentRegistry: AgentRegistry` — delete
- `taskRegistry: TaskRegistry` — delete

Add new field:
```kotlin
private var agentTool: AgentTool? = null
private var orchestratorContext: AgentContext? = null
private var sandboxPath: String? = null
```

- [ ] **Step 3: Simplify createTeam()**

In `createTeam()`, after creating the orchestrator's AgentContext and AgentRunner:
1. Store `orchestratorContext` reference
2. Store `sandboxPath` reference
3. Create `AgentTool` instance
4. Store `agentTool` reference

- [ ] **Step 4: Update createAgentRunner() to route agent tool**

In the `onToolCall` callback, add intercept for `agent` tool:

```kotlin
onToolCall = { agentName, toolName, args, callId ->
    if (toolName == AgentTool.TOOL_NAME) {
        // Route to AgentTool directly
        agentTool?.call(args, orchestratorContext!!, sandboxPath!!)
            ?.toString()
            ?: JSONObject().apply {
                put("content", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("type", "text")
                        put("text", "AgentTool not available")
                    })
                })
                put("isError", true)
            }.toString()
    } else {
        // Route to McpRuntimeManager
        mcpRuntimeManager.callTool(toolName, args).toString()
    }
},
```

- [ ] **Step 5: Remove old methods**

Delete these methods entirely:
- `spawnTeammate()`
- `spawnSubAgentsFromDirective()`
- `spawnWithDependencies()`
- `sendPeerMessage()`
- `continueAgent()`
- `broadcast()`
- `requestShutdown()`
- `killTeammate()`
- `sendIntervention()`
- `resumeAgent()`
- `createTaskForAgent()`

Remove the `OrchestratorTools` field and its construction.

- [ ] **Step 6: Simplify deleteTeam()**

Keep only: cancel orchestrator scope/job, set state to completed. Remove MessageBus/TaskManager cleanup.

- [ ] **Step 7: Verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: Build may fail due to references from other files (WorkspaceViewModel, UI screens). Fix compilation errors in subsequent tasks.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "refactor(workspace): simplify TeamManager, wire AgentTool"
```

---

### Task 5: Clean up WorkspaceModels.kt

**Files:**
- Modify: `app/src/main/java/com/example/workspace/WorkspaceModels.kt`

- [ ] **Step 1: Read current WorkspaceModels.kt**

Read the full file. Identify what to keep and what to remove.

- [ ] **Step 2: Remove deleted types**

Remove these types entirely:
- `sealed class WaitResult` and all subclasses
- `enum class TaskMode`
- `enum class SpawnMode`
- `enum class ModelHint`
- `data class OrchestratorDirective`
- `data class AgentSpec`
- `data class AgentTaskStep`
- `data class AgentTaskProgress`
- `ORCHESTRATOR_ONLY_TOOLS` constant
- `COLLABORATION_TOOLS` constant

- [ ] **Step 3: Simplify TeamState**

Replace current TeamState with:

```kotlin
data class TeamState(
    val teamName: String,
    val orchestratorConfig: ModelConfig,
    val orchestratorName: String = ORCHESTRATOR_NAME,
    val activeSubAgents: List<SubAgentInfo> = emptyList(),
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val sandboxPath: String? = null,
)

data class SubAgentInfo(
    val name: String,
    val description: String,
    val status: AgentStatus,
)
```

- [ ] **Step 4: Keep these types**

- `WorkspaceScopes` object
- `WorkspaceConfig`
- `TeamCompletionSnapshot`
- `ORCHESTRATOR_NAME` constant
- `COMPLETION_MARKER` constant
- `POLL_INTERVAL_MS` constant
- `AGENT_COLORS`

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: May have compilation errors from files referencing removed types. Fix in next tasks.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/workspace/WorkspaceModels.kt
git commit -m "refactor(workspace): clean up WorkspaceModels, remove TaskMode/SpawnMode/etc"
```

---

### Task 6: Delete obsolete files

**Files:**
- Delete: `app/src/main/java/com/example/workspace/MessageBus.kt`
- Delete: `app/src/main/java/com/example/workspace/TaskManager.kt`
- Delete: `app/src/main/java/com/example/workspace/Scratchpad.kt`
- Delete: `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt`
- Delete: `app/src/main/java/com/example/workspace/OrchestratorTools.kt`
- Delete: `app/src/main/java/com/example/workspace/AgentLifecycle.kt`
- Delete: `app/src/main/java/com/example/workspace/AgentRegistry.kt`
- Delete: `app/src/main/java/com/example/workspace/AgentDefinition.kt`
- Delete: `app/src/main/java/com/example/workspace/AgentTask.kt`
- Delete: `app/src/main/java/com/example/workspace/ModelSelector.kt`
- Delete: `app/src/main/java/com/example/workspace/AgentProgressSummarizer.kt`

- [ ] **Step 1: Delete all obsolete source files**

```bash
rm app/src/main/java/com/example/workspace/MessageBus.kt
rm app/src/main/java/com/example/workspace/TaskManager.kt
rm app/src/main/java/com/example/workspace/Scratchpad.kt
rm app/src/main/java/com/example/workspace/AgentExecutionLoops.kt
rm app/src/main/java/com/example/workspace/OrchestratorTools.kt
rm app/src/main/java/com/example/workspace/AgentLifecycle.kt
rm app/src/main/java/com/example/workspace/AgentRegistry.kt
rm app/src/main/java/com/example/workspace/AgentDefinition.kt
rm app/src/main/java/com/example/workspace/AgentTask.kt
rm app/src/main/java/com/example/workspace/ModelSelector.kt
rm app/src/main/java/com/example/workspace/AgentProgressSummarizer.kt
```

- [ ] **Step 2: Delete obsolete test files**

```bash
rm app/src/test/java/com/example/workspace/AgentRegistryTest.kt
rm app/src/test/java/com/example/workspace/MessageBusDeadAgentTest.kt
rm app/src/test/java/com/example/workspace/DeleteTeamSelfJoinTest.kt
rm app/src/test/java/com/example/workspace/ScratchpadTest.kt
rm app/src/test/java/com/example/workspace/SpawnWithDependenciesDeadlineTest.kt
```

- [ ] **Step 3: Fix all compilation errors**

Run: `./gradlew compileDebugKotlin`
Fix any remaining references to deleted types/classes. This will likely involve:
- WorkspaceViewModel: remove MessageBus, TaskManager, AgentRegistry, TaskRegistry creation
- UI screens: remove references to deleted types
- BuiltinToolHandler: remove scratchpad access via TeamManager

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(workspace): delete obsolete files (MessageBus, TaskManager, etc)"
```

---

### Task 7: Fix WorkspaceViewModel and UI compilation

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt` (if needed)
- Modify: `app/src/main/java/com/example/ui/screens/AgentTabBar.kt` (if needed)
- Modify: `app/src/main/java/com/example/ui/screens/TeamTaskPanel.kt` (if needed)

- [ ] **Step 1: Read WorkspaceViewModel.kt to understand TeamManager creation**

Read `createTeamManager()` method (around lines 1198-1369). Remove:
- `messageBus` creation
- `taskManager` creation
- `agentRegistry` creation
- `taskRegistry` creation
- These parameters from the TeamManager constructor call

- [ ] **Step 2: Simplify WorkspaceViewModel state management**

Remove any state that tracks:
- MessageBus messages
- TaskManager tasks
- Agent registry
- Task claiming

Keep: orchestrator state, SubAgent tracking, completion state.

- [ ] **Step 3: Fix UI screen compilation**

Check each UI file for references to deleted types and fix:
- `WorkspaceScreen.kt` — remove references to TaskMode, SpawnMode, etc.
- `AgentTabBar.kt` — simplify to show Orchestrator + active SubAgents
- `TeamTaskPanel.kt` — remove or simplify (no Claim mode tasks)

- [ ] **Step 4: Fix BuiltinToolHandler scratchpad access**

The scratchpad tools (`scratchpad_write`, `scratchpad_read`, `scratchpad_list`) currently access `teamManager`. Since Scratchpad.kt is deleted, either:
- Remove scratchpad tools entirely, OR
- Implement simple file-based scratchpad inline

Remove the `@Volatile var teamManager` field from BuiltinToolHandler if no longer needed.

- [ ] **Step 5: Verify full compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(workspace): fix WorkspaceViewModel and UI for simplified architecture"
```

---

### Task 8: Verify tool routing works end-to-end

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt` (if needed)

- [ ] **Step 1: Trace the full tool call path for 'agent' tool**

Verify the chain:
1. Orchestrator LLM outputs `agent` tool call
2. `AgentRunner.runTurn()` collects it
3. `ToolOrchestrator.executeToolCalls()` dispatches to `onToolCall`
4. `onToolCall` checks `toolName == "agent"` → calls `agentTool.call()`
5. `AgentTool.call()` creates SubAgent, runs it, returns result
6. Result flows back through the chain to the Orchestrator

- [ ] **Step 2: Verify SubAgent tool calls work**

Verify the SubAgent chain:
1. SubAgent LLM outputs a tool call (e.g., `file_read`)
2. SubAgent's `onToolCall` routes to `mcpRuntimeManager.callTool()`
3. Tool executes and returns result
4. SubAgent continues

- [ ] **Step 3: Verify 'agent' tool is excluded for SubAgents**

Verify that SubAgent's `disallowedTools = setOf("agent")` prevents the SubAgent from calling the `agent` tool.

- [ ] **Step 4: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All remaining tests pass

- [ ] **Step 5: Commit if any fixes needed**

```bash
git add -A
git commit -m "fix(workspace): verify and fix agent tool routing"
```

---

### Task 9: Manual testing

- [ ] **Step 1: Build debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Test basic workspace flow**

1. Open the app, navigate to workspace
2. Enter a simple task: "Hello, what tools do you have?"
3. Verify Orchestrator responds
4. Verify completion marker appears

- [ ] **Step 3: Test agent tool delegation**

1. Enter a task that requires delegation: "Read the file src/Main.kt and summarize its contents"
2. Verify Orchestrator calls the `agent` tool
3. Verify SubAgent executes and returns a result
4. Verify Orchestrator processes the result and outputs completion

- [ ] **Step 4: Test SubAgent isolation**

1. Enter a task: "Create two agents: one to list files in src/ and one to count lines in build.gradle"
2. Verify both SubAgents run independently
3. Verify SubAgents cannot call the `agent` tool
4. Verify Orchestrator receives both results

- [ ] **Step 5: Commit final state**

```bash
git add -A
git commit -m "feat(workspace): complete Agent Team refactor to Claude Code pattern"
```

---

### Task 10: Update existing tests

**Files:**
- Modify: `app/src/test/java/com/example/workspace/WorkspaceCoreTest.kt`
- Keep: `app/src/test/java/com/example/workspace/ToolOrchestratorTest.kt`

- [ ] **Step 1: Read WorkspaceCoreTest.kt**

Identify which tests are still relevant:
- Completion marker detection tests — keep
- JSON directive parsing tests — remove (no more directives)
- System prompt compilation tests — update for new prompt
- WorkspaceConfig defaults tests — keep

- [ ] **Step 2: Update tests**

Remove tests that reference deleted types (OrchestratorDirective, AgentSpec, etc.).
Update system prompt tests to verify new prompt content.

- [ ] **Step 3: Add AgentTool unit tests**

Create `app/src/test/java/com/example/workspace/AgentToolTest.kt`:

```kotlin
package com.example.workspace

import org.junit.Assert.*
import org.junit.Test

class AgentToolTest {

    @Test
    fun `tool schema has correct name`() {
        assertEquals("agent", AgentTool.TOOL_NAME)
    }

    @Test
    fun `tool schema has required fields`() {
        val schema = AgentTool.TOOL_SCHEMA
        assertEquals("agent", schema["name"])
        assertNotNull(schema["description"])
        assertNotNull(schema["parameters"])
    }

    @Test
    fun `sub agent prompt includes task description`() {
        val agentTool = AgentTool(
            mcpRuntimeManager = mockMcpRuntimeManager(),
            apiClient = mockApiClient(),
        )
        // Test that buildSubAgentPrompt produces correct output
        // (This would need to make buildSubAgentPrompt internal/public for testing)
    }
}
```

- [ ] **Step 4: Run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/example/workspace/
git commit -m "test(workspace): update tests for simplified agent architecture"
```
