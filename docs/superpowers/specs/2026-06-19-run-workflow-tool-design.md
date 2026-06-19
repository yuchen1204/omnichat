# run_workflow MCP 工具设计

**Date:** 2026-06-19
**Status:** Approved
**Scope:** 将 WorkflowEngine 通过 MCP 工具暴露给 MainAgent

## 问题

WorkflowEngine 提供三种工作流编排模式（Pipeline、DAG、Conversational），但目前未通过 MCP 工具暴露，MainAgent 无法直接使用这些能力。当前只能通过 `delegate_task` 调用单个 SubAgent，无法编排多步骤、多 Agent 协作的复杂工作流。

## 解决方案

新增统一的 `run_workflow` MCP 工具，通过 `mode` 参数让 LLM 选择执行策略：

| 模式 | 执行方式 | 适用场景 |
|------|----------|----------|
| `pipeline` | 顺序执行，前一步结果传递给下一步 | 有严格顺序依赖的任务链 |
| `dag` | 基于依赖执行，无依赖的步骤并行 | 部分任务可并行的复杂依赖图 |
| `conversational` | 两个 Agent 交替对话直到收敛 | 需要多角度讨论/辩论的场景 |

## 架构

```
MainAgent 调用 run_workflow
    ↓
BuiltinToolHandler.handleRunWorkflow()
    ↓ 根据 mode 分发
┌─────────────────────────────────────────┐
│  pipeline → handlePipelineWorkflow()    │
│  dag       → handleDagWorkflow()        │
│  conversational → handleConversationalWorkflow() │
└─────────────────────────────────────────┘
    ↓
WorkflowEngine.executePipeline/DAG/Conversational()
    ↓
SubAgent.executeSync() (每个步骤)
    ↓
格式化结果返回给 MainAgent
```

## 工具定义

### Schema

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

        // pipeline / dag 模式的参数
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

        // conversational 模式的参数
        prop("agentA", "string", "First agent type (conversational mode)")
        prop("agentB", "string", "Second agent type (conversational mode)")
        prop("topic", "string", "Discussion topic (conversational mode)")
        prop("maxRounds", "integer", "Max conversation rounds (default: 5, conversational mode)")

        required("mode")
    }
)
```

## Handler 实现

### 入口方法

```kotlin
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

### Pipeline 模式

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

    val steps = parseWorkflowSteps(stepsArray)
    val startTime = System.currentTimeMillis()

    return try {
        val results = WorkflowEngine.executePipeline(
            context = context,
            sessionId = sessionId,
            steps = steps
        )
        val duration = (System.currentTimeMillis() - startTime) / 1000
        successResponse(formatWorkflowResult(results, duration, "pipeline"))
    } catch (e: Exception) {
        errorResponse("Pipeline workflow failed: ${e.message}")
    }
}
```

### DAG 模式

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

    val steps = parseWorkflowSteps(stepsArray)
    val startTime = System.currentTimeMillis()

    return try {
        val results = WorkflowEngine.executeDAG(
            context = context,
            sessionId = sessionId,
            steps = steps
        )
        val duration = (System.currentTimeMillis() - startTime) / 1000
        successResponse(formatWorkflowResult(results, duration, "dag"))
    } catch (e: Exception) {
        errorResponse("DAG workflow failed: ${e.message}")
    }
}
```

### Conversational 模式

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
        successResponse(formatWorkflowResult(results, duration, "conversational"))
    } catch (e: Exception) {
        errorResponse("Conversational workflow failed: ${e.message}")
    }
}
```

## 辅助方法

### 解析步骤数组

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

### 格式化结果

```kotlin
private fun formatWorkflowResult(
    results: List<StepResult>,
    durationSeconds: Long,
    mode: String
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

    // 找到最后一个成功的结果，展示完整内容
    val lastSuccess = results.lastOrNull { it.status == WorkflowStepStatus.COMPLETED }
    if (lastSuccess != null && lastSuccess.result != null) {
        sb.appendLine()
        sb.appendLine("Final Output (${lastSuccess.stepId}):")
        sb.appendLine(lastSuccess.result)
    }

    // 如果有失败，展示失败详情
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

private fun summarizeResult(result: String?): String {
    if (result.isNullOrBlank()) return "No output"
    val lines = result.lines().filter { it.isNotBlank() }
    val firstLine = lines.firstOrNull()?.take(80) ?: "No output"
    return if (firstLine.length < lines.firstOrNull()?.length ?: 0) "$firstLine..." else firstLine
}
```

## 参数验证

| 参数 | 验证规则 | 错误消息 |
|------|----------|----------|
| `mode` | 必填，必须是 `pipeline`/`dag`/`conversational` | "mode is required" 或 "Invalid mode: X" |
| `steps` (pipeline/dag) | 必填，非空数组；每个 step 必有 `id`, `agentType`, `task` | "steps array is required for X mode" 或 "Each step must have id, agentType, and task" |
| `agentA/agentB` (conversational) | 必填，非空 | "agentA and agentB are required for conversational mode" |
| `topic` (conversational) | 必填，非空 | "topic is required for conversational mode" |
| `maxRounds` (conversational) | 必须 > 0 | "maxRounds must be > 0" |
| `sessionId` | 必填（从 ChatViewModel 传入） | "sessionId is required for run_workflow" |

## 输出格式示例

### 成功执行

```
Workflow completed successfully (3 succeeded, 0 failed, 0 skipped in 45s)

Step Summary:
✅ research: Found 3 relevant files about authentication
✅ code: Created UserService.kt with OAuth implementation
✅ review: Approved with 2 minor suggestions for error handling

Final Output (review):
The implementation is solid. Suggestions:
1. Add retry logic for transient network errors
2. Consider caching the auth token to reduce API calls
```

### 部分失败

```
Workflow completed with failures (2 succeeded, 1 failed, 1 skipped in 38s)

Step Summary:
✅ research: Gathered requirements from 3 documents
✅ design: Created architecture diagram
❌ code: Task timed out after 300s
⏭️ review: Skipped: dependency failed

Failure Details:
- code: Task timed out after 300s
```

## 工具注册

### 分组映射

```kotlin
// McpRuntimeManager.kt
private val toolGroupMap = mapOf(
    // ... 现有映射 ...
    "delegate_task" to "subagent",
    "check_task_status" to "subagent",
    "send_agent_message" to "subagent",
    "read_agent_inbox" to "subagent",
    "run_workflow" to "subagent",  // 新增
)
```

### 路由分发

```kotlin
// BuiltinToolHandler.kt
fun handleBuiltinTool(
    context: Context,
    toolName: String,
    arguments: JSONObject,
    sessionId: Long?
): JSONObject {
    return when (toolName) {
        // ... 现有路由 ...
        "run_workflow" -> handleRunWorkflow(context, arguments, sessionId)
        else -> errorResponse(str(context, R.string.tool_unknown_builtin, toolName))
    }
}
```

## 文件修改清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `mcp/McpRuntimeManager.kt` | 修改 | 添加 `run_workflow` 工具定义；添加分组映射 |
| `mcp/BuiltinToolHandler.kt` | 修改 | 添加 `handleRunWorkflow` 及辅助方法；添加路由分支 |

## 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 工具粒度 | 单一工具 + mode 参数 | LLM 学习成本低；参数结构清晰 |
| 决策指引 | 在 description 中嵌入规则 | 提高 LLM 选择准确率 |
| 输出格式 | 摘要 + 最终结果 | 平衡信息完整性和可读性 |
| 错误处理 | 按模式策略（与 WorkflowEngine 一致） | 符合直觉，行为可预测 |
| 收敛检测 | 保持 `[CONVERGED]` 标记 | 简单可靠；maxRounds 作为兜底 |
| 参数验证 | 宽松策略 | 依赖 LLM 正确构造请求 |

## 改动量估算

- 新增代码：约 150 行
- 修改文件：2 个
- 无需修改 WorkflowEngine 或 SubAgent 核心逻辑
