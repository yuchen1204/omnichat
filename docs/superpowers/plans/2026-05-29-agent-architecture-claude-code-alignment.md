# Agent Architecture — Claude Code Alignment Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [`) syntax for tracking.

**Goal:** Refactor OmniChat's workspace/agent architecture to fully match Claude Code's multi-agent patterns — async execution, agent definitions, inter-agent messaging, progress tracking, and task management.

**Architecture:** Replace the current synchronous-only sub-agent model with Claude Code's dual sync/async execution model. Introduce agent definitions (built-in + custom), centralized tool filtering, `<task-notification>`-style result delivery, `SendMessage`-style inter-agent communication, and proper agent lifecycle management with transcript persistence and resumption.

**Tech Stack:** Kotlin coroutines (structured concurrency), Room DB (transcript persistence), StateFlow (reactive UI), JSON (agent definitions, notifications)

---

## Phase Overview

This plan is broken into 7 phases. Each phase produces working, testable software independently.

| Phase | Description | Dependencies |
|-------|-------------|--------------|
| 1 | Dead Code Cleanup & Agent ID Fix | None |
| 2 | Agent Definition System | Phase 1 |
| 3 | Centralized Tool Filtering | Phase 2 |
| 4 | Async Agent Execution | Phase 2 |
| 5 | Inter-Agent Communication | Phase 4 |
| 6 | Progress Tracking & Lifecycle | Phase 4 |
| 7 | Task Management Integration | Phase 5 |

---

## Phase 1: Dead Code Cleanup & Agent ID Fix

### Task 1.1: Fix Sub-Agent Naming Collision

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentTool.kt:95`

- [ ] **Step 1: Replace timestamp-based naming with UUID**

```kotlin
// AgentTool.kt:95 — replace:
val subAgentName = "SubAgent-${System.currentTimeMillis() % 10000}"
// with:
val subAgentName = "SubAgent-${java.util.UUID.randomUUID().toString().take(8)}"
```

- [ ] **Step 2: Verify no other files reference the old naming pattern**

Run: `grep -rn "System.currentTimeMillis() % 10000" app/src/main/java/`
Expected: No matches after the change.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTool.kt
git commit -m "fix: replace timestamp-based sub-agent naming with UUID to prevent collisions"
```

### Task 1.2: Remove Legacy Orchestration Tool UI

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/OrchestrationToolCallCard.kt`
- Modify: `app/src/main/java/com/example/ui/screens/AgentBubbleMessage.kt`

- [ ] **Step 1: Simplify OrchestrationToolCallCard to only handle "agent" tool**

Remove the legacy `create_agents`, `assign_task`, `continue_conversation`, `peer_message` rendering branches from `OrchestrationToolCallCard.kt`. Keep only the `agent` tool card rendering.

- [ ] **Step 2: Update ORCHESTRATION_TOOLS set**

```kotlin
// OrchestrationToolCallCard.kt:43 — replace:
private val ORCHESTRATION_TOOLS = setOf(
    "create_agents", "assign_task", "continue_conversation", "peer_message", "agent"
)
// with:
private val ORCHESTRATION_TOOLS = setOf("agent")
```

- [ ] **Step 3: Remove dead rendering branches**

Remove the `renderCreateAgentsCard`, `renderAssignTaskCard`, `renderContinueConversationCard`, `renderPeerMessageCard` functions and their call sites. Keep `renderAgentCard` (for the `agent` tool).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/OrchestrationToolCallCard.kt
git commit -m "chore: remove legacy orchestration tool UI (create_agents, assign_task, etc.)"
```

### Task 1.3: Wire TeamTask System into Execution (or Remove)

**Decision:** The `TeamTask` entity and DAO exist but are unused. Claude Code has a full task management system (`TaskCreate`, `TaskGet`, `TaskList`, `TaskUpdate` tools). We will wire `TeamTask` into the execution flow in Phase 7. For now, keep the entity but add a TODO comment.

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt` (add comment near TeamTask)

- [ ] **Step 1: Add tracking comment to TeamTask entity**

```kotlin
// Near TeamTask entity definition, add:
// TODO: Phase 7 will wire TeamTask into agent execution via TaskCreate/TaskUpdate tools
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/data/Entities.kt
git commit -m "docs: mark TeamTask for Phase 7 integration"
```

---

## Phase 2: Agent Definition System

### Task 2.1: Create AgentDefinition Data Model

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentDefinition.kt`

- [ ] **Step 1: Create AgentDefinition data class**

```kotlin
package com.example.workspace

import com.example.data.ModelConfig

/**
 * Agent definition — describes a type of agent that can be spawned.
 *
 * Mirrors Claude Code's AgentDefinition (loaded from built-in registry,
 * plugin frontmatter, or custom .claude/agents/ markdown files).
 *
 * Built-in agents: generalPurpose, explore, plan, verification
 * Custom agents: loaded from agent_presets DB table
 */
data class AgentDefinition(
    /** Unique agent type identifier (e.g., "general-purpose", "explore", "custom:my-agent") */
    val agentType: String,
    /** Human-readable display name */
    val displayName: String,
    /** System prompt template (may contain [CROSS_SESSION_MEMORY], [MCP_TOOLS], etc.) */
    val systemPrompt: String,
    /** Model alias hint: "default", "fast", "reasoning", "vision" — resolved at spawn time */
    val modelHint: String? = null,
    /** Specific model config ID override — takes precedence over modelHint */
    val modelConfigId: Long? = null,
    /** Specific model ID override within the config */
    val overrideModelId: String? = null,
    /** Tool names this agent is allowed to use. null or ["*"] = all tools */
    val tools: List<String>? = null,
    /** Tool names this agent is NOT allowed to use */
    val disallowedTools: List<String>? = null,
    /** Whether this agent should run in the background (async) */
    val background: Boolean = false,
    /** Maximum tool call iterations for this agent */
    val maxTurns: Int = AgentRunner.MAX_TOOL_CALL_ITERATIONS,
    /** UI color for this agent type */
    val color: String? = null,
    /** Whether this is a built-in agent (vs user-defined) */
    val isBuiltIn: Boolean = true,
    /** Source: "built-in", "preset", "custom" */
    val source: String = "built-in",
)

/**
 * Built-in agent definitions registry.
 *
 * Mirrors Claude Code's builtInAgents.ts — provides the default agent types
 * available in every workspace session.
 */
object BuiltInAgents {
    val GENERAL_PURPOSE = AgentDefinition(
        agentType = "general-purpose",
        displayName = "通用 Agent",
        systemPrompt = "", // Uses orchestrator's system prompt or default
        tools = listOf("*"), // All tools
        isBuiltIn = true,
    )

    val EXPLORE = AgentDefinition(
        agentType = "explore",
        displayName = "探索 Agent",
        systemPrompt = """You are a read-only exploration agent. Your job is to search, read, and analyze files to answer questions about the codebase.

Rules:
- You may ONLY use read-only tools: read_file, list_directory, search_files, get_file_info, search_memory, get_current_time
- Do NOT modify any files
- Report your findings clearly and concisely""",
        tools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
        background = true,
        color = "#4285F4",
        isBuiltIn = true,
    )

    val PLAN = AgentDefinition(
        agentType = "plan",
        displayName = "规划 Agent",
        systemPrompt = """You are a planning agent. Your job is to analyze tasks and create detailed implementation plans.

Rules:
- You may ONLY use read-only tools: read_file, list_directory, search_files, get_file_info, search_memory, get_current_time
- Do NOT modify any files
- Output a structured plan with clear steps""",
        tools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
        background = true,
        color = "#34A853",
        isBuiltIn = true,
    )

    val VERIFICATION = AgentDefinition(
        agentType = "verification",
        displayName = "验证 Agent",
        systemPrompt = """You are a verification agent. Your job is to review and verify work done by other agents.

Rules:
- Check for correctness, completeness, and potential issues
- Report any problems found with specific details
- Be adversarial — try to find flaws""",
        background = true,
        color = "#EA4335",
        isBuiltIn = true,
    )

    /** All built-in agents */
    val ALL = listOf(GENERAL_PURPOSE, EXPLORE, PLAN, VERIFICATION)

    /** Lookup by agentType */
    fun findByType(agentType: String): AgentDefinition? =
        ALL.find { it.agentType == agentType }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentDefinition.kt
git commit -m "feat: add AgentDefinition data model and built-in agent registry"
```

### Task 2.2: Load Agent Definitions from AgentPreset DB

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentDefinition.kt` (add loading function)
- Modify: `app/src/main/java/com/example/data/Repository.kt` (add preset query)

- [ ] **Step 1: Add AgentPreset → AgentDefinition conversion**

Add to `AgentDefinition.kt`:

```kotlin
/**
 * Loads custom agent definitions from the AgentPreset DB table.
 * Merges with built-in definitions to produce the full agent list.
 */
suspend fun loadAgentDefinitions(
    repository: com.example.data.AppRepository,
): List<AgentDefinition> {
    val presets = repository.getAllAgentPresets()
    val customAgents = presets.map { preset ->
        AgentDefinition(
            agentType = "custom:${preset.name}",
            displayName = preset.name,
            systemPrompt = preset.systemPrompt,
            modelConfigId = preset.modelConfigId?.toLongOrNull(),
            isBuiltIn = false,
            source = "preset",
        )
    }
    return BuiltInAgents.ALL + customAgents
}
```

- [ ] **Step 2: Ensure `getAllAgentPresets()` exists in Repository**

Check if `AppRepository` already has this method. If not, add it (it should exist based on the AgentPreset entity).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentDefinition.kt
git commit -m "feat: load custom agent definitions from AgentPreset DB"
```

### Task 2.3: Integrate AgentDefinition into AgentTool

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentTool.kt`

- [ ] **Step 1: Add subagent_type parameter to tool schema**

```kotlin
// AgentTool.kt — update TOOL_SCHEMA:
val TOOL_SCHEMA = schema {
    prop("description", "string", "Short description of what the SubAgent will do.")
    prop("prompt", "string", "Full task prompt for the SubAgent.")
    prop("subagent_type", "string", "Optional agent type (e.g., 'explore', 'plan', 'verification', 'custom:my-agent'). Defaults to general-purpose.")
    prop("model", "string", "Optional model ID to override the SubAgent's model.")
    required("description", "prompt")
}
```

- [ ] **Step 2: Add agent definition resolution to AgentTool**

```kotlin
class AgentTool(
    private val mcpRuntimeManager: McpRuntimeManager,
    private val agentDefinitions: List<AgentDefinition> = BuiltInAgents.ALL,
    private val onSubAgentCreated: (name: String, description: String) -> Unit = { _, _ -> },
    private val onSubAgentStreamChunk: (name: String, chunk: String) -> Unit = { _, _ -> },
    private val onSubAgentCompleted: (name: String, messages: List<AgentMessage>) -> Unit = { _, _ -> },
) {
    // ... existing code ...

    private fun resolveAgentDefinition(subagentType: String?): AgentDefinition {
        if (subagentType == null) return BuiltInAgents.GENERAL_PURPOSE
        return agentDefinitions.find { it.agentType == subagentType }
            ?: throw IllegalArgumentException("Agent type '$subagentType' not found. Available: ${agentDefinitions.map { it.agentType }}")
    }
}
```

- [ ] **Step 3: Update runSubAgent to use AgentDefinition**

```kotlin
private suspend fun runSubAgent(
    description: String,
    prompt: String,
    modelOverride: String?,
    parentContext: AgentContext,
    sandboxPath: String,
    subagentType: String? = null,
): JSONObject {
    val agentDef = resolveAgentDefinition(subagentType)
    val subAgentName = "SubAgent-${java.util.UUID.randomUUID().toString().take(8)}"

    // Resolve effective model: explicit override > definition override > parent's model
    val effectiveModelId = modelOverride ?: agentDef.overrideModelId

    val subAgentContext = AgentContext(
        agentName = subAgentName,
        isOrchestrator = false,
        systemPrompt = agentDef.systemPrompt.ifEmpty { buildSubAgentPrompt(description, sandboxPath) },
        modelConfig = parentContext.modelConfig,
        overrideModelId = effectiveModelId,
        teamName = parentContext.teamName,
        messages = ArrayList(),
    )

    // Use agent definition's tool filtering
    val disallowedTools = buildDisallowedTools(agentDef)

    val runner = AgentRunner(
        context = subAgentContext,
        mcpRuntimeManager = mcpRuntimeManager,
        disallowedTools = disallowedTools,
        sandboxPath = sandboxPath,
        maxToolIterations = agentDef.maxTurns,
        // ... rest of runner setup
    )
    // ... rest of execution
}

private fun buildDisallowedTools(agentDef: AgentDefinition): Set<String> {
    val base = mutableSetOf(AgentTool.TOOL_NAME) // Always prevent recursion
    // If agent has explicit tool list, disallow everything not in it
    if (agentDef.tools != null && agentDef.tools != listOf("*")) {
        // Will be enforced via getFilteredTools() in AgentRunner
    }
    agentDef.disallowedTools?.forEach { base.add(it) }
    return base
}
```

- [ ] **Step 4: Update call() to pass subagent_type**

```kotlin
suspend fun call(args: JSONObject, parentContext: AgentContext, sandboxPath: String): JSONObject {
    val description = args.optString("description", "")
    val prompt = args.optString("prompt", "")
    val modelOverride = args.optString("model", "").ifEmpty { null }
    val subagentType = args.optString("subagent_type", "").ifEmpty { null }

    if (prompt.isEmpty()) return errorResult("Missing required parameter: prompt")

    return try {
        runSubAgent(description, prompt, modelOverride, parentContext, sandboxPath, subagentType)
    } catch (e: Exception) {
        Log.e(TAG, "SubAgent execution failed", e)
        errorResult("SubAgent execution failed: ${e.message}")
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTool.kt
git commit -m "feat: integrate AgentDefinition into AgentTool with subagent_type parameter"
```

### Task 2.4: Update Orchestrator System Prompt with Agent Listings

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentContext.kt`

- [ ] **Step 1: Update ORCHESTRATOR_SYSTEM_PROMPT to include agent listings**

Replace the static `ORCHESTRATOR_SYSTEM_PROMPT` with a function that generates the prompt dynamically based on available agent definitions:

```kotlin
fun buildOrchestratorSystemPrompt(
    agentDefinitions: List<AgentDefinition>,
    sandboxPath: String? = null,
): String {
    val agentList = agentDefinitions.joinToString("\n") { def ->
        "- `${def.agentType}`: ${def.displayName}${if (def.background) " (background)" else ""}"
    }
    return """你是一个任务编排助手。你的职责是理解用户需求，将复杂任务拆分为子任务，并使用 agent 工具委派给独立的子 Agent 执行。

## agent 工具
调用方式：
- description: 3-5 个词的简短任务描述（英文）
- prompt: 完整的任务描述，包含所有必要的上下文和要求（英文）
- subagent_type: 可选，指定 Agent 类型（见下方列表）
- model: 可选，指定子 Agent 使用的模型

## 可用 Agent 类型
$agentList

## 何时委派
- 任务可以拆分为独立的子任务
- 子任务之间没有强依赖
- 需要并行处理以提高效率

## 何时自己做
- 任务简单直接
- 需要与用户交互确认

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
}
```

- [ ] **Step 2: Update TeamManager.createTeam to use new prompt builder**

```kotlin
// In TeamManager.createTeam(), replace:
//   systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
// with:
//   systemPrompt = buildOrchestratorSystemPrompt(agentDefinitions, sandboxPath),
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentContext.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat: generate orchestrator system prompt dynamically with agent listings"
```

---

## Phase 3: Centralized Tool Filtering

### Task 3.1: Create Tool Filter Constants

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentToolFilter.kt`

- [ ] **Step 1: Create centralized tool filter**

```kotlin
package com.example.workspace

/**
 * Centralized tool filtering for agents.
 *
 * Mirrors Claude Code's src/constants/tools.ts:
 * - ALL_AGENT_DISALLOWED_TOOLS: tools blocked for ALL agents
 * - ASYNC_AGENT_ALLOWED_TOOLS: whitelist for async/background agents
 * - COORDINATOR_ALLOWED_TOOLS: minimal set for orchestrator
 */
object AgentToolFilter {

    /**
     * Tools blocked for ALL agents (sub-agents and orchestrator).
     * These are meta-tools that only the main thread should use.
     */
    val ALL_AGENT_DISALLOWED_TOOLS = setOf(
        "agent", // Prevents recursion — AgentTool handles this separately
    )

    /**
     * Tools allowed for async/background agents.
     * Background agents get a restricted tool set since they can't show UI.
     * Mirrors Claude Code's ASYNC_AGENT_ALLOWED_TOOLS.
     */
    val ASYNC_AGENT_ALLOWED_TOOLS = setOf(
        "read_file", "list_directory", "search_files", "get_file_info",
        "write_file", "create_directory", "move_file", "delete_file",
        "search_memory", "get_current_time",
        "create_pdf_document", "create_excel_document",
        "create_word_document", "create_powerpoint_document",
    )

    /**
     * Tools blocked for orchestrator (should delegate to sub-agents).
     * Mirrors Claude Code's coordinator mode restrictions.
     */
    val ORCHESTRATOR_BLOCKED_TOOLS = setOf(
        // File write operations — delegate to sub-agents
        "write_file", "create_directory", "move_file", "delete_file",
        // Document generation
        "create_pdf_document", "create_excel_document",
        "create_word_document", "create_powerpoint_document",
        // UI modifications
        "set_primary_color", "set_secondary_color", "set_background_color",
        "set_surface_color", "set_corner_radius", "set_spacing_multiplier",
        "set_font_family", "set_font_size_scale", "set_font_weight",
        "set_ui_texts", "configure_mcp_tool_groups",
    )

    /**
     * Read-only tools that are safe for parallel execution.
     * Replaces ToolOrchestrator.READ_ONLY_TOOLS.
     */
    val READ_ONLY_TOOLS = setOf(
        "read_file", "list_directory", "search_files", "get_file_info",
        "search_memory", "get_current_time",
    )

    /**
     * Filter tools for a specific agent based on its definition and context.
     *
     * @param allToolNames All available tool names
     * @param agentDef The agent definition (null for orchestrator)
     * @param isOrchestrator Whether this is the orchestrator agent
     * @param isAsync Whether this agent runs in background
     * @return Set of allowed tool names
     */
    fun filterTools(
        allToolNames: Set<String>,
        agentDef: AgentDefinition?,
        isOrchestrator: Boolean,
        isAsync: Boolean,
    ): Set<String> {
        var filtered = allToolNames - ALL_AGENT_DISALLOWED_TOOLS

        if (isOrchestrator) {
            filtered = filtered - ORCHESTRATOR_BLOCKED_TOOLS
        }

        if (isAsync) {
            filtered = filtered.intersect(ASYNC_AGENT_ALLOWED_TOOLS)
        }

        // Agent definition's explicit tool list
        agentDef?.tools?.let { allowedTools ->
            if (allowedTools != listOf("*")) {
                filtered = filtered.intersect(allowedTools.toSet())
            }
        }

        // Agent definition's disallowed tools
        agentDef?.disallowedTools?.let { disallowed ->
            filtered = filtered - disallowed.toSet()
        }

        return filtered
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentToolFilter.kt
git commit -m "feat: add centralized AgentToolFilter mirroring Claude Code's tools.ts"
```

### Task 3.2: Refactor AgentRunner.getFilteredTools() to Use AgentToolFilter

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`

- [ ] **Step 1: Replace getFilteredTools() implementation**

```kotlin
// In AgentRunner, replace the existing getFilteredTools() with:
fun getFilteredTools(): JSONArray {
    val allTools = mcpRuntimeManager.getAllToolsAsOpenAiFormat()
    val currentHashCode = allTools.toString().hashCode().toLong()
    cachedTools?.let { if (cachedToolsVersion == currentHashCode) return it }

    val allToolNames = mutableSetOf<String>()
    for (i in 0 until allTools.length()) {
        allTools.getJSONObject(i).optJSONObject("function")?.optString("name")?.let {
            allToolNames.add(it)
        }
    }

    val allowedNames = AgentToolFilter.filterTools(
        allToolNames = allToolNames,
        agentDef = context.agentDefinition, // New field added in Phase 2
        isOrchestrator = context.isOrchestrator,
        isAsync = false, // Sync for now; async support in Phase 4
    )

    val filtered = JSONArray()
    for (i in 0 until allTools.length()) {
        val tool = allTools.getJSONObject(i)
        val name = tool.optJSONObject("function")?.optString("name") ?: continue
        if (name in allowedNames) {
            filtered.put(tool)
        }
    }

    cachedTools = filtered
    cachedToolsVersion = currentHashCode
    return filtered
}
```

- [ ] **Step 2: Add agentDefinition field to AgentContext**

```kotlin
// In AgentContext.kt, add:
data class AgentContext(
    val agentName: String,
    val isOrchestrator: Boolean,
    val systemPrompt: String,
    val modelConfig: ModelConfig,
    val overrideModelId: String? = null,
    val teamName: String = "",
    val messages: MutableList<AgentMessage>,
    val agentDefinition: AgentDefinition? = null, // NEW
)
```

- [ ] **Step 3: Remove old ORCHESTRATOR_BLOCKED_BUILTIN_TOOLS from AgentRunner**

Delete the `ORCHESTRATOR_BLOCKED_BUILTIN_TOOLS` companion object constant from `AgentRunner.kt` — it's now in `AgentToolFilter`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt app/src/main/java/com/example/workspace/AgentContext.kt
git commit -m "refactor: use centralized AgentToolFilter in AgentRunner.getFilteredTools()"
```

---

## Phase 4: Async Agent Execution

### Task 4.1: Add Async Execution Mode to AgentRunner

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`
- Modify: `app/src/main/java/com/example/workspace/AgentContext.kt`

- [ ] **Step 1: Add async-related fields to AgentRunner**

```kotlin
class AgentRunner(
    // ... existing params ...
    /** Whether this agent runs asynchronously (background) */
    private val isAsync: Boolean = false,
    /** Pending messages from parent/other agents (for async agents) */
    private val pendingMessages: ConcurrentLinkedDeque<AgentMessage> = ConcurrentLinkedDeque(),
) {
    // ... existing code ...
}
```

- [ ] **Step 2: Add drainPendingMessages() method**

```kotlin
/**
 * Drain pending messages queued by parent or other agents.
 * Called at tool-round boundaries in the do-while loop.
 * Mirrors Claude Code's drainPendingMessages() in LocalAgentTask.
 */
private fun drainPendingMessages(): List<AgentMessage> {
    val messages = mutableListOf<AgentMessage>()
    while (pendingMessages.isNotEmpty()) {
        pendingMessages.poll()?.let { messages.add(it) }
    }
    return messages
}
```

- [ ] **Step 3: Integrate pending messages into runTurn loop**

In the `do-while` loop, after tool execution and before the next API call:

```kotlin
// After tool results are saved, before next iteration:
val pendingMsgs = drainPendingMessages()
if (pendingMsgs.isNotEmpty()) {
    messagesLock.writeLock().lock()
    try {
        context.messages.addAll(pendingMsgs)
    } finally {
        messagesLock.writeLock().unlock()
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt
git commit -m "feat: add async execution mode with pending message queue to AgentRunner"
```

### Task 4.2: Add Background Agent Support to AgentTool

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentTool.kt`
- Modify: `app/src/main/java/com/example/workspace/WorkspaceModels.kt`

- [ ] **Step 1: Add task notification data model**

```kotlin
// In WorkspaceModels.kt, add:
/**
 * Task notification — delivered to parent when an async agent completes.
 * Mirrors Claude Code's <task-notification> XML.
 */
data class TaskNotification(
    val taskId: String,
    val agentName: String,
    val status: TaskNotificationStatus, // completed, failed, killed
    val result: String? = null,
    val error: String? = null,
    val totalTokens: Int = 0,
    val toolUseCount: Int = 0,
    val durationMs: Long = 0,
)

enum class TaskNotificationStatus {
    COMPLETED, FAILED, KILLED
}
```

- [ ] **Step 2: Add run_in_background parameter to AgentTool schema**

```kotlin
val TOOL_SCHEMA = schema {
    prop("description", "string", "Short description of what the SubAgent will do.")
    prop("prompt", "string", "Full task prompt for the SubAgent.")
    prop("subagent_type", "string", "Optional agent type.")
    prop("model", "string", "Optional model ID override.")
    prop("run_in_background", "boolean", "Set to true to run this agent in the background.")
    required("description", "prompt")
}
```

- [ ] **Step 3: Add async execution path to AgentTool**

```kotlin
// In AgentTool.call():
val runInBackground = args.optBoolean("run_in_background", false)
    || agentDef.background // Agent definition forces background

if (runInBackground) {
    return runAsyncSubAgent(description, prompt, modelOverride, parentContext, sandboxPath, subagentType, agentDef)
} else {
    return runSubAgent(description, prompt, modelOverride, parentContext, sandboxPath, subagentType)
}
```

- [ ] **Step 4: Implement runAsyncSubAgent**

```kotlin
/**
 * Run a sub-agent asynchronously in the background.
 * Returns immediately with a task ID; result delivered via notification.
 */
private suspend fun runAsyncSubAgent(
    description: String,
    prompt: String,
    modelOverride: String?,
    parentContext: AgentContext,
    sandboxPath: String,
    subagentType: String?,
    agentDef: AgentDefinition,
): JSONObject {
    val subAgentName = "SubAgent-${java.util.UUID.randomUUID().toString().take(8)}"
    val effectiveModelId = modelOverride ?: agentDef.overrideModelId

    val subAgentContext = AgentContext(
        agentName = subAgentName,
        isOrchestrator = false,
        systemPrompt = agentDef.systemPrompt.ifEmpty { buildSubAgentPrompt(description, sandboxPath) },
        modelConfig = parentContext.modelConfig,
        overrideModelId = effectiveModelId,
        teamName = parentContext.teamName,
        messages = ArrayList(),
        agentDefinition = agentDef,
    )

    val disallowedTools = buildDisallowedTools(agentDef)

    // Notify UI: SubAgent created
    onSubAgentCreated(subAgentName, description)

    // Launch in background scope (not blocking the orchestrator)
    WorkspaceScopes.auxiliary.launch {
        val runner = AgentRunner(
            context = subAgentContext,
            mcpRuntimeManager = mcpRuntimeManager,
            disallowedTools = disallowedTools,
            sandboxPath = sandboxPath,
            maxToolIterations = agentDef.maxTurns,
            isAsync = true,
            onStreamChunk = { _, chunk -> onSubAgentStreamChunk(subAgentName, chunk) },
            onToolCall = { _, toolName, toolArgs, _ ->
                val serverId = mcpRuntimeManager.findServerIdForTool(toolName)
                if (serverId == null) "Error: Tool '$toolName' not found"
                else mcpRuntimeManager.callTool(serverId, toolName, toolArgs)?.toString() ?: "No result"
            },
        )

        try {
            runner.runTurn(userMessage = prompt, source = "agent-tool")
            // Deliver notification to orchestrator
            val lastMsg = subAgentContext.messages.lastOrNull { it.role == "assistant" }
            deliverNotification(parentContext, TaskNotification(
                taskId = subAgentName,
                agentName = subAgentName,
                status = TaskNotificationStatus.COMPLETED,
                result = lastMsg?.content,
                totalTokens = runner.getUsageStats().totalTokens,
                toolUseCount = runner.getUsageStats().toolUseCount,
                durationMs = runner.getUsageStats().durationMs,
            ))
        } catch (e: Exception) {
            deliverNotification(parentContext, TaskNotification(
                taskId = subAgentName,
                agentName = subAgentName,
                status = TaskNotificationStatus.FAILED,
                error = e.message,
            ))
        } finally {
            runner.dispose()
            onSubAgentCompleted(subAgentName, subAgentContext.messages.toList())
        }
    }

    // Return immediately with task info
    return JSONObject().apply {
        put("status", "async_launched")
        put("agentId", subAgentName)
        put("description", description)
    }
}

/**
 * Deliver a task notification to the orchestrator's pending message queue.
 * Mirrors Claude Code's enqueueAgentNotification().
 */
private fun deliverNotification(parentContext: AgentContext, notification: TaskNotification) {
    val notificationText = buildString {
        appendLine("<task-notification>")
        appendLine("<task-id>${notification.taskId}</task-id>")
        appendLine("<status>${notification.status.name.lowercase()}</status>")
        if (notification.result != null) {
            appendLine("<result>${notification.result}</result>")
        }
        if (notification.error != null) {
            appendLine("<error>${notification.error}</error>")
        }
        appendLine("<usage>")
        appendLine("  <total_tokens>${notification.totalTokens}</total_tokens>")
        appendLine("  <tool_uses>${notification.toolUseCount}</tool_uses>")
        appendLine("  <duration_ms>${notification.durationMs}</duration_ms>")
        appendLine("</usage>")
        appendLine("</task-notification>")
    }
    // Inject as a user message into the parent's pending queue
    parentContext.messages.add(AgentMessage(
        role = "user",
        content = notificationText,
        source = "task-notification",
    ))
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTool.kt app/src/main/java/com/example/workspace/WorkspaceModels.kt
git commit -m "feat: add async background agent execution with task-notification delivery"
```

---

## Phase 5: Inter-Agent Communication

### Task 5.1: Create SendMessage Tool

**Files:**
- Create: `app/src/main/java/com/example/workspace/SendMessageTool.kt`

- [ ] **Step 1: Implement SendMessage tool**

```kotlin
package com.example.workspace

import android.util.Log
import com.example.mcp.McpRuntimeManager
import com.example.mcp.ToolSchemaDsl.schema
import org.json.JSONObject

/**
 * SendMessage tool — enables inter-agent communication.
 *
 * Mirrors Claude Code's SendMessageTool:
 * - Send to named agent (running or completed)
 * - Send to orchestrator from sub-agent
 * - Broadcast to all agents
 *
 * Messages are queued in the target agent's pendingMessages deque.
 */
class SendMessageTool(
    private val teamManager: TeamManager,
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

    private fun sendToAgent(agentName: String, message: String): JSONObject {
        val runner = teamManager.getRunner(agentName)
            ?: return errorResult("Agent '$agentName' not found")

        runner.injectMessage(
            role = "user",
            content = message,
            source = "send_message",
        )

        return JSONObject().apply {
            put("content", "Message sent to $agentName")
        }
    }

    private fun broadcastMessage(message: String): JSONObject {
        val runners = teamManager.getAllRunners()
        for ((name, runner) in runners) {
            runner.injectMessage(
                role = "user",
                content = message,
                source = "broadcast",
            )
        }
        return JSONObject().apply {
            put("content", "Message broadcast to ${runners.size} agents")
        }
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
```

- [ ] **Step 2: Add getRunner() and getAllRunners() to TeamManager**

```kotlin
// In TeamManager.kt, add:
fun getRunner(agentName: String): AgentRunner? = runners[agentName]
fun getAllRunners(): Map<String, AgentRunner> = runners.toMap()
```

- [ ] **Step 3: Register SendMessage as built-in MCP tool**

In `McpRuntimeManager`, register `SendMessageTool` alongside the existing `agent` tool.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/SendMessageTool.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat: add SendMessage tool for inter-agent communication"
```

---

## Phase 6: Progress Tracking & Lifecycle

### Task 6.1: Create ProgressTracker

**Files:**
- Create: `app/src/main/java/com/example/workspace/ProgressTracker.kt`

- [ ] **Step 1: Implement ProgressTracker**

```kotlin
package com.example.workspace

/**
 * Tracks agent progress metrics.
 * Mirrors Claude Code's ProgressTracker in LocalAgentTask.
 */
data class ProgressTracker(
    var toolUseCount: Int = 0,
    var totalTokens: Int = 0,
    val recentActivities: MutableList<ActivityEntry> = mutableListOf(),
) {
    data class ActivityEntry(
        val toolName: String,
        val description: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun recordToolUse(toolName: String, description: String = toolName) {
        toolUseCount++
        recentActivities.add(ActivityEntry(toolName, description))
        // Keep only last 5 activities
        while (recentActivities.size > 5) {
            recentActivities.removeFirst()
        }
    }

    fun recordTokens(tokens: Int) {
        totalTokens += tokens
    }
}
```

- [ ] **Step 2: Integrate into AgentRunner**

Replace `AgentUsageStats` with `ProgressTracker` in `AgentRunner`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/ProgressTracker.kt
git commit -m "feat: add ProgressTracker for agent progress metrics"
```

### Task 6.2: Improve Completion Detection

**Files:**
- Modify: `app/src/main/java/com/example/workspace/WorkspaceModels.kt`
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Replace COMPLETION_MARKER with natural completion detection**

Claude Code detects completion naturally: when the LLM produces no tool calls, the loop ends. The orchestrator can still output a summary, but the system doesn't require a magic string.

```kotlin
// In WorkspaceModels.kt, keep COMPLETION_MARKER but make it optional:
const val COMPLETION_MARKER = "【任务完成】"  // Optional, not required for completion

// In TeamManager.isCompletionMarker(), make it a hint, not a gate:
fun isCompletionMarker(text: String): Boolean = text.contains(COMPLETION_MARKER)
```

- [ ] **Step 2: Update AgentRunner to support natural completion**

The current code already breaks on no tool calls. The issue is the "fake completion" retry logic that nudges sub-agents. For orchestrator, natural completion should be respected:

```kotlin
// In AgentRunner.runTurn(), after the "no tool calls" check:
// For orchestrator: respect natural completion (no nudge)
if (context.isOrchestrator) {
    break // Orchestrator's text response IS the completion
}
```

- [ ] **Step 3: Update TeamManager.startExecution to detect completion naturally**

```kotlin
// In TeamManager.startExecution(), after runTurn completes:
// Don't require COMPLETION_MARKER — if the orchestrator's turn ended, it's done
triggerWorkspaceComplete(runner)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/WorkspaceModels.kt app/src/main/java/com/example/workspace/AgentRunner.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat: support natural completion detection (no required completion marker)"
```

### Task 6.3: Add Agent Transcript Persistence

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Add transcript recording to AgentRunner**

```kotlin
// In AgentRunner, add a transcriptRecorder callback:
private val transcriptRecorder: ((List<AgentMessage>) -> Unit)? = null,

// In runTurn(), after each message is added:
transcriptRecorder?.invoke(context.messages.toList())
```

- [ ] **Step 2: Wire transcript persistence in TeamManager**

```kotlin
// In TeamManager.createAgentRunner(), add:
transcriptRecorder = { messages ->
    WorkspaceScopes.auxiliary.launch {
        try {
            // Persist to workspace_messages table
            // (existing persistMessage callback handles individual messages)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record transcript for ${context.agentName}", e)
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat: add agent transcript persistence for resumption support"
```

---

## Phase 7: Task Management Integration

### Task 7.1: Create Task CRUD Tools

**Files:**
- Create: `app/src/main/java/com/example/workspace/TaskTools.kt`

- [ ] **Step 1: Implement TaskCreate, TaskUpdate, TaskList, TaskGet tools**

```kotlin
package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import com.example.data.TeamTask
import com.example.mcp.ToolSchemaDsl.schema
import org.json.JSONObject

/**
 * Task management tools — enable agents to coordinate via a shared task list.
 * Mirrors Claude Code's TaskCreate/TaskGet/TaskList/TaskUpdate tools.
 */
class TaskTools(private val repository: AppRepository, private val teamName: String) {

    companion object {
        const val TASK_CREATE = "task_create"
        const val TASK_GET = "task_get"
        const val TASK_LIST = "task_list"
        const val TASK_UPDATE = "task_update"
    }

    suspend fun callTool(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            TASK_CREATE -> createTask(args)
            TASK_GET -> getTask(args)
            TASK_LIST -> listTasks()
            TASK_UPDATE -> updateTask(args)
            else -> errorResult("Unknown task tool: $toolName")
        }
    }

    private suspend fun createTask(args: JSONObject): JSONObject {
        val subject = args.optString("subject", "")
        val description = args.optString("description", "")
        val intendedAgent = args.optString("intended_agent", "").ifEmpty { null }

        if (subject.isEmpty()) return errorResult("Missing 'subject'")

        val task = TeamTask(
            teamName = teamName,
            subject = subject,
            description = description,
            status = "PENDING",
            intendedAgent = intendedAgent,
        )
        val id = repository.insertTeamTask(task)
        return JSONObject().apply {
            put("content", "Task created: $subject (id=$id)")
        }
    }

    private suspend fun getTask(args: JSONObject): JSONObject {
        val taskId = args.optLong("id", -1)
        if (taskId < 0) return errorResult("Missing 'id'")
        val task = repository.getTeamTaskById(taskId)
            ?: return errorResult("Task $taskId not found")
        return JSONObject().apply {
            put("content", taskToJson(task).toString())
        }
    }

    private suspend fun listTasks(): JSONObject {
        val tasks = repository.getTeamTasksByTeam(teamName)
        val arr = tasks.map { taskToJson(it) }
        return JSONObject().apply {
            put("content", org.json.JSONArray(arr).toString())
        }
    }

    private suspend fun updateTask(args: JSONObject): JSONObject {
        val taskId = args.optLong("id", -1)
        val status = args.optString("status", "")
        val owner = args.optString("owner", "").ifEmpty { null }

        if (taskId < 0) return errorResult("Missing 'id'")
        if (status.isEmpty() && owner == null) return errorResult("Nothing to update")

        val task = repository.getTeamTaskById(taskId)
            ?: return errorResult("Task $taskId not found")

        val updated = task.copy(
            status = status.ifEmpty { task.status },
            owner = owner ?: task.owner,
            updatedAt = System.currentTimeMillis(),
        )
        repository.updateTeamTask(updated)
        return JSONObject().apply {
            put("content", "Task $taskId updated: status=${updated.status}")
        }
    }

    private fun taskToJson(task: TeamTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("subject", task.subject)
        put("description", task.description)
        put("status", task.status)
        put("owner", task.owner ?: JSONObject.NULL)
        put("intendedAgent", task.intendedAgent ?: JSONObject.NULL)
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", "Error: $message")
        put("isError", true)
    }
}
```

- [ ] **Step 2: Register task tools in McpRuntimeManager**

Add `TASK_CREATE`, `TASK_GET`, `TASK_LIST`, `TASK_UPDATE` as built-in tools alongside the existing `agent` tool.

- [ ] **Step 3: Update orchestrator system prompt to mention task tools**

```kotlin
// In buildOrchestratorSystemPrompt(), add:
appendLine("## 任务管理工具")
appendLine("- task_create: 创建任务（subject, description, intended_agent）")
appendLine("- task_list: 列出所有任务")
appendLine("- task_get: 获取任务详情（id）")
appendLine("- task_update: 更新任务状态（id, status, owner）")
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/TaskTools.kt
git commit -m "feat: add TaskCreate/TaskGet/TaskList/TaskUpdate tools for agent coordination"
```

### Task 7.2: Wire TeamTask Panel into Workspace UI

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt`

- [ ] **Step 1: Pass actual teamTasks instead of emptyList()**

```kotlin
// In WorkspaceScreen.kt, find:
//   teamTasks = emptyList()
// Replace with:
//   teamTasks = teamTasks  // from WorkspaceViewModel
```

- [ ] **Step 2: Add teamTasks StateFlow to WorkspaceViewModel**

```kotlin
// In WorkspaceViewModel.kt, add:
private val _teamTasks = MutableStateFlow<List<TeamTask>>(emptyList())
val teamTasks: StateFlow<List<TeamTask>> = _teamTasks.asStateFlow()

// Collect from DB when workspace is selected:
fun loadTeamTasks(teamName: String) {
    viewModelScope.launch {
        repository.getTeamTasksByTeamFlow(teamName).collect { tasks ->
            _teamTasks.value = tasks
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt
git commit -m "feat: wire TeamTask panel into workspace UI"
```

---

## Final Verification

- [ ] **Run unit tests:** `./gradlew testDebugUnitTest`
- [ ] **Run lint:** `./gradlew lintDebug`
- [ ] **Build debug APK:** `./gradlew assembleDebug`
- [ ] **Manual test:** Create workspace → submit task → verify orchestrator delegates to sub-agents → verify async agents complete in background → verify task management tools work
