# run_workflow MCP 工具实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 WorkflowEngine 通过统一的 `run_workflow` MCP 工具暴露给 MainAgent，支持 pipeline/dag/conversational 三种执行模式。

**Architecture:** 在 McpRuntimeManager 中新增工具定义，在 BuiltinToolHandler 中添加 handler 方法，根据 mode 参数分发到 WorkflowEngine 的对应执行方法，结果格式化为摘要输出。

**Tech Stack:** Kotlin, MCP 工具系统, WorkflowEngine, SubAgent

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `mcp/McpRuntimeManager.kt` | 修改 | 添加 `run_workflow` 工具定义到 `builtinTools` 列表；添加分组映射到 `toolGroupMap` |
| `mcp/BuiltinToolHandler.kt` | 修改 | 添加 `handleRunWorkflow` 入口方法；添加三个子方法处理各模式；添加辅助方法解析和格式化结果；添加路由分支 |

---

## Task 1: 添加 run_workflow 工具定义

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt:974-978` (在 `read_agent_inbox` 工具后添加新工具)

- [ ] **Step 1: 在 builtinTools 列表末尾添加 run_workflow 工具定义**

在 `read_agent_inbox` 工具定义的闭合括号 `)` 后、`builtinTools` 列表的闭合括号 `)` 前，添加新工具：

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "run_workflow",
            description = """Execute a multi-agent workflow with coordinated execution.

MODES:
- pipeline: Sequential execution. Each step receives prior results as context.
- dag: Dependency-based execution. Independent steps run in parallel.
- conversational: Two agents exchange messages until convergence.

WHEN TO USE EACH MODE:
- pipeline: Steps have strict order dependency (A must finish before B starts).
  Example: "Research → Code → Review" → pipeline
- dag: Steps have partial dependencies, some can run in parallel.
  Example: "Analyze modules A, B, C independently, then integrate" → dag
- conversational: Need multi-perspective discussion or debate between two agents.
  Example: "Design debate between coder and reviewer" → conversational

AGENT TYPES AVAILABLE:
general, researcher, coder, reviewer, tester, planner, orchestrator

FAILURE HANDLING:
- pipeline: Stops immediately on first failure.
- dag: Failed step blocks its dependents; independent steps continue.
- conversational: Stops immediately on any agent failure.

CONVERGENCE (conversational mode):
Agents must output [CONVERGED] marker when discussion is complete.
If no marker, runs until maxRounds (default: 5).

OUTPUT FORMAT:
Returns step summary + final result. Failed steps show error message.
""",
            inputSchema = schema {
                prop("mode", "string", "Execution mode") {
                    enum("pipeline", "dag", "conversational")
                }
                prop("steps", "array", "Workflow steps (required for pipeline/dag mode)") {
                    items {
                        prop("id", "string", "Step identifier (e.g. 'step1', 'research')")
                        prop("agentType", "string", "Agent type for this step")
                        prop("task", "string", "Task description for this agent")
                        prop("dependsOn", "array", "Step IDs this depends on (dag mode only)") {
                            items { type("string") }
                        }
                        prop("resultVariable", "string", "Optional: name to reference this result in downstream steps")
                    }
                }
                prop("agentA", "string", "First agent type (conversational mode)")
                prop("agentB", "string", "Second agent type (conversational mode)")
                prop("topic", "string", "Discussion topic (conversational mode)")
                prop("maxRounds", "integer", "Max conversation rounds (default: 5, conversational mode)")
                required("mode")
            }
        ),
```

- [ ] **Step 2: 在 toolGroupMap 中添加分组映射**

在 `toolGroupMap` 的 `"read_agent_inbox" to "subagent"` 行后添加：

```kotlin
            "run_workflow" to "subagent",
```

- [ ] **Step 3: 提交工具定义**

```bash
git add app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt
git commit -m "feat(mcp): add run_workflow tool definition

Add unified workflow orchestration tool with pipeline/dag/conversational modes."
```

---

## Task 2: 添加 Handler 入口方法和路由

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt:68-72` (路由分发)

- [ ] **Step 1: 添加必要的 import 语句**

在文件顶部的 import 区域（约第 13 行 `import com.omnichat.agent.SubAgent` 后）添加：

```kotlin
import com.omnichat.agent.WorkflowEngine
import com.omnichat.agent.WorkflowStep
import com.omnichat.agent.StepResult
import com.omnichat.agent.WorkflowStepStatus
```

- [ ] **Step 2: 在 handleBuiltinTool 路由中添加分支**

在 `"read_agent_inbox" -> handleReadAgentInbox(context)` 行后、`else ->` 行前添加：

```kotlin
            "run_workflow" -> handleRunWorkflow(context, arguments, sessionId)
```

- [ ] **Step 3: 提交路由修改**

```bash
git add app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt
git commit -m "feat(mcp): add run_workflow route in BuiltinToolHandler"
```

---

## Task 3: 实现 handleRunWorkflow 入口方法

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt` (在文件末尾、闭合大括号前添加)

- [ ] **Step 1: 添加 handleRunWorkflow 入口方法**

在 `handleReadAgentInbox` 方法后添加：

```kotlin
    // ── Workflow 工具 ────────────────────────────────────────────────────────

    private suspend fun handleRunWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long?
    ): JSONObject {
        val mode = arguments.optString("mode", "")
        if (mode.isBlank()) {
            return errorResponse("mode is required")
        }
        if (sessionId == null) {
            return errorResponse("sessionId is required for run_workflow")
        }

        return when (mode) {
            "pipeline" -> handlePipelineWorkflow(context, arguments, sessionId)
            "dag" -> handleDagWorkflow(context, arguments, sessionId)
            "conversational" -> handleConversationalWorkflow(context, arguments, sessionId)
            else -> errorResponse("Invalid mode: $mode. Valid modes: pipeline, dag, conversational")
        }
    }
```

- [ ] **Step 2: 提交入口方法**

```bash
git add app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt
git commit -m "feat(mcp): add handleRunWorkflow entry method"
```

---

## Task 4: 实现 Pipeline 和 DAG 模式处理方法

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: 添加 handlePipelineWorkflow 方法**

在 `handleRunWorkflow` 方法后添加：

```kotlin
    private suspend fun handlePipelineWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long
    ): JSONObject {
        val stepsArray = arguments.optJSONArray("steps")
        if (stepsArray == null || stepsArray.length() == 0) {
            return errorResponse("steps array is required for pipeline mode")
        }

        val steps = try {
            parseWorkflowSteps(stepsArray)
        } catch (e: IllegalArgumentException) {
            return errorResponse(e.message ?: "Invalid steps format")
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = WorkflowEngine.executePipeline(
                context = context,
                sessionId = sessionId,
                steps = steps
            )
            val duration = (System.currentTimeMillis() - startTime) / 1000
            successResponse(formatWorkflowResult(results, duration))
        } catch (e: Exception) {
            errorResponse("Pipeline workflow failed: ${e.message}")
        }
    }
```

- [ ] **Step 2: 添加 handleDagWorkflow 方法**

在 `handlePipelineWorkflow` 方法后添加：

```kotlin
    private suspend fun handleDagWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long
    ): JSONObject {
        val stepsArray = arguments.optJSONArray("steps")
        if (stepsArray == null || stepsArray.length() == 0) {
            return errorResponse("steps array is required for dag mode")
        }

        val steps = try {
            parseWorkflowSteps(stepsArray)
        } catch (e: IllegalArgumentException) {
            return errorResponse(e.message ?: "Invalid steps format")
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = WorkflowEngine.executeDAG(
                context = context,
                sessionId = sessionId,
                steps = steps
            )
            val duration = (System.currentTimeMillis() - startTime) / 1000
            successResponse(formatWorkflowResult(results, duration))
        } catch (e: Exception) {
            errorResponse("DAG workflow failed: ${e.message}")
        }
    }
```

- [ ] **Step 3: 提交 Pipeline 和 DAG 处理方法**

```bash
git add app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt
git commit -m "feat(mcp): add handlePipelineWorkflow and handleDagWorkflow methods"
```

---

## Task 5: 实现 Conversational 模式处理方法

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: 添加 handleConversationalWorkflow 方法**

在 `handleDagWorkflow` 方法后添加：

```kotlin
    private suspend fun handleConversationalWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long
    ): JSONObject {
        val agentA = arguments.optString("agentA", "")
        val agentB = arguments.optString("agentB", "")
        val topic = arguments.optString("topic", "")
        val maxRounds = arguments.optInt("maxRounds", 5)

        if (agentA.isBlank() || agentB.isBlank()) {
            return errorResponse("agentA and agentB are required for conversational mode")
        }
        if (topic.isBlank()) {
            return errorResponse("topic is required for conversational mode")
        }
        if (maxRounds <= 0) {
            return errorResponse("maxRounds must be > 0")
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = WorkflowEngine.executeConversational(
                context = context,
                sessionId = sessionId,
                agentA = agentA,
                agentB = agentB,
                topic = topic,
                maxRounds = maxRounds
            )
            val duration = (System.currentTimeMillis() - startTime) / 1000
            successResponse(formatWorkflowResult(results, duration))
        } catch (e: Exception) {
            errorResponse("Conversational workflow failed: ${e.message}")
        }
    }
```

- [ ] **Step 2: 提交 Conversational 处理方法**

```bash
git add app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt
git commit -m "feat(mcp): add handleConversationalWorkflow method"
```

---

## Task 6: 实现辅助方法

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: 添加 parseWorkflowSteps 方法**

在 `handleConversationalWorkflow` 方法后添加：

```kotlin
    private fun parseWorkflowSteps(stepsArray: JSONArray): List<WorkflowStep> {
        val steps = mutableListOf<WorkflowStep>()
        for (i in 0 until stepsArray.length()) {
            val stepJson = stepsArray.getJSONObject(i)
            val id = stepJson.optString("id", "")
            val agentType = stepJson.optString("agentType", "")
            val task = stepJson.optString("task", "")
            val dependsOn = stepJson.optJSONArray("dependsOn")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val resultVariable = stepJson.optString("resultVariable", null)

            if (id.isBlank() || agentType.isBlank() || task.isBlank()) {
                throw IllegalArgumentException("Each step must have id, agentType, and task")
            }

            steps.add(WorkflowStep(id, agentType, task, dependsOn, resultVariable))
        }
        return steps
    }
```

- [ ] **Step 2: 添加 formatWorkflowResult 方法**

在 `parseWorkflowSteps` 方法后添加：

```kotlin
    private fun formatWorkflowResult(
        results: List<StepResult>,
        durationSeconds: Long
    ): String {
        val successCount = results.count { it.status == WorkflowStepStatus.COMPLETED }
        val failedCount = results.count { it.status == WorkflowStepStatus.FAILED }
        val skippedCount = results.count { it.status == WorkflowStepStatus.SKIPPED }

        val overallStatus = when {
            failedCount > 0 -> "completed with failures"
            skippedCount > 0 -> "completed with skipped steps"
            else -> "completed successfully"
        }

        val sb = StringBuilder()
        sb.appendLine("Workflow $overallStatus ($successCount succeeded, $failedCount failed, $skippedCount skipped in ${durationSeconds}s)")
        sb.appendLine()
        sb.appendLine("Step Summary:")

        for (result in results) {
            val icon = when (result.status) {
                WorkflowStepStatus.COMPLETED -> "✅"
                WorkflowStepStatus.FAILED -> "❌"
                WorkflowStepStatus.SKIPPED -> "⏭️"
                WorkflowStepStatus.RUNNING -> "🔄"
                WorkflowStepStatus.PENDING -> "⏳"
            }
            val summary = when (result.status) {
                WorkflowStepStatus.COMPLETED -> summarizeResult(result.result)
                WorkflowStepStatus.FAILED -> result.error ?: "Unknown error"
                WorkflowStepStatus.SKIPPED -> result.error ?: "Skipped"
                else -> "In progress"
            }
            sb.appendLine("$icon ${result.stepId}: $summary")
        }

        val lastSuccess = results.lastOrNull { it.status == WorkflowStepStatus.COMPLETED }
        if (lastSuccess != null && lastSuccess.result != null) {
            sb.appendLine()
            sb.appendLine("Final Output (${lastSuccess.stepId}):")
            sb.appendLine(lastSuccess.result)
        }

        val failures = results.filter { it.status == WorkflowStepStatus.FAILED }
        if (failures.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Failure Details:")
            for (f in failures) {
                sb.appendLine("- ${f.stepId}: ${f.error}")
            }
        }

        return sb.toString()
    }
```

- [ ] **Step 3: 添加 summarizeResult 方法**

在 `formatWorkflowResult` 方法后添加：

```kotlin
    private fun summarizeResult(result: String?): String {
        if (result.isNullOrBlank()) return "No output"
        val lines = result.lines().filter { it.isNotBlank() }
        val firstLine = lines.firstOrNull()?.take(80) ?: "No output"
        return if (firstLine.length < lines.firstOrNull()?.length ?: 0) "$firstLine..." else firstLine
    }
```

- [ ] **Step 4: 提交辅助方法**

```bash
git add app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt
git commit -m "feat(mcp): add workflow helper methods (parseWorkflowSteps, formatWorkflowResult, summarizeResult)"
```

---

## Task 7: 验证编译

**Files:**
- 无修改

- [ ] **Step 1: 运行编译验证**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 如果编译失败，修复问题**

常见问题：
- import 缺失：确保添加了 WorkflowEngine、WorkflowStep、StepResult、WorkflowStepStatus 的 import
- 类型不匹配：检查 parseWorkflowSteps 返回类型与 WorkflowEngine.executePipeline 参数类型一致

---

## Task 8: 最终提交

- [ ] **Step 1: 确认所有修改已提交**

```bash
git status
```

Expected: nothing to commit, working tree clean

- [ ] **Step 2: 查看提交历史**

```bash
git log --oneline -8
```

Expected: 看到 6 个新提交（工具定义、路由、入口方法、pipeline/dag、conversational、辅助方法）

---

## 自检清单

- [x] Spec coverage: 所有 spec 要求均有对应任务
- [x] Placeholder scan: 无 TBD/TODO
- [x] Type consistency: WorkflowStep、StepResult、WorkflowStepStatus 类型使用一致
