# Workspace 模块架构重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the workspace module to introduce unified Task abstraction, concurrent tool execution, declarative agent definitions, progress summarization, and shared scratchpad — aligning architecture with Claude Code.

**Architecture:** Introduce `AgentTask` interface for lifecycle management, `ToolOrchestrator` for parallel read-only tool execution, `AgentDefinition`/`AgentRegistry` for externalized agent config, `AgentProgressSummarizer` for periodic progress reports, and `Scratchpad` for cross-agent file sharing.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose, Room 2.7.0, coroutines, ConcurrentHashMap

---

## File Structure

| 操作 | 文件路径 | 职责 |
|------|---------|------|
| 新增 | `app/src/main/java/com/example/workspace/AgentTask.kt` | Task 接口 + TaskRegistry + TaskType/TaskStatus |
| 新增 | `app/src/main/java/com/example/workspace/ToolOrchestrator.kt` | 工具并发执行引擎 |
| 新增 | `app/src/main/java/com/example/workspace/AgentDefinition.kt` | Agent 定义数据类 |
| 新增 | `app/src/main/java/com/example/workspace/AgentRegistry.kt` | Agent 定义加载/查询 |
| 新增 | `app/src/main/java/com/example/workspace/Scratchpad.kt` | 共享暂存区 |
| 新增 | `app/src/main/java/com/example/workspace/AgentProgressSummarizer.kt` | 进度摘要生成 |
| 新增 | `app/src/main/assets/workspace/agents/orchestrator.json` | 内置 Orchestrator 预设 |
| 新增 | `app/src/main/assets/workspace/agents/teammate_default.json` | 内置默认子 Agent 预设 |
| 新增 | `app/src/main/assets/workspace/agents/explorer.json` | 内置探索者预设 |
| 新增 | `app/src/main/assets/workspace/agents/verifier.json` | 内置验证者预设 |
| 修改 | `app/src/main/java/com/example/workspace/AgentRunner.kt` | 接入 ToolOrchestrator |
| 修改 | `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt` | 封装为 AgentTask 实现 |
| 修改 | `app/src/main/java/com/example/workspace/AgentLifecycle.kt` | 使用 AgentRegistry + TaskRegistry |
| 修改 | `app/src/main/java/com/example/workspace/OrchestratorTools.kt` | 使用 AgentRegistry |
| 修改 | `app/src/main/java/com/example/workspace/TeamManager.kt` | 使用 TaskRegistry |
| 修改 | `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` | 新增 Scratchpad MCP 工具 |
| 修改 | `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt` | 适配 TaskRegistry |
| 新增 | `app/src/test/java/com/example/workspace/ToolOrchestratorTest.kt` | 工具编排单元测试 |
| 新增 | `app/src/test/java/com/example/workspace/AgentRegistryTest.kt` | Agent 注册表单元测试 |
| 新增 | `app/src/test/java/com/example/workspace/ScratchpadTest.kt` | 暂存区单元测试 |

---

## Task 1: AgentTask 接口 + TaskRegistry

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentTask.kt`

- [ ] **Step 1: 创建 AgentTask.kt**

```kotlin
package com.example.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 任务统一接口。
 *
 * 对标 Claude Code 的 Task 抽象。所有后台工作（Orchestrator、Teammate）
 * 都通过此接口暴露生命周期控制，替代直接操作 CoroutineScope + Job。
 */
interface AgentTask {
    /** 唯一任务 ID */
    val taskId: String

    /** 任务显示名称 */
    val taskName: String

    /** 任务类型 */
    val taskType: TaskType

    /** 当前状态 */
    val status: TaskStatus

    /** 执行任务（suspend，阻塞直到任务完成或取消） */
    suspend fun execute()

    /** 强制终止任务 */
    fun kill()

    /** 注册状态变更监听 */
    fun onStatusChange(listener: (TaskStatus) -> Unit)
}

/** 任务类型 */
enum class TaskType {
    /** 主控 Agent */
    ORCHESTRATOR,
    /** 子 Agent */
    TEAMMATE,
    /** 协调者模式（预留） */
    COORDINATOR
}

/** 任务状态 */
enum class TaskStatus {
    /** 已创建，未开始执行 */
    CREATED,
    /** 正在执行 */
    RUNNING,
    /** 正常完成 */
    COMPLETED,
    /** 执行出错 */
    FAILED,
    /** 被外部取消 */
    CANCELLED
}

/** 任务状态变更事件 */
data class TaskStatusEvent(
    val taskId: String,
    val taskName: String,
    val oldStatus: TaskStatus,
    val newStatus: TaskStatus,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 任务注册表。
 *
 * 统一管理所有 AgentTask 的生命周期。替代原 AgentLifecycle 中分散的
 * runners / teammateJobs / teammateScopes 管理方式。
 */
class TaskRegistry {
    private val tasks = ConcurrentHashMap<String, AgentTask>()
    private val statusListeners = ConcurrentHashMap<String, MutableList<(TaskStatus) -> Unit>>()
    private val _statusFlow = MutableSharedFlow<TaskStatusEvent>(extraBufferCapacity = 64)

    /** 状态变更事件流，供 UI 层观察 */
    val statusEvents: Flow<TaskStatusEvent> = _statusFlow

    /** 注册任务 */
    fun register(task: AgentTask) {
        tasks[task.taskId] = task
        task.onStatusChange { newStatus ->
            val event = TaskStatusEvent(
                taskId = task.taskId,
                taskName = task.taskName,
                oldStatus = task.status,
                newStatus = newStatus
            )
            _statusFlow.tryEmit(event)
            statusListeners[task.taskId]?.forEach { it(newStatus) }
        }
    }

    /** 获取任务 */
    fun get(taskId: String): AgentTask? = tasks[taskId]

    /** 按类型获取任务 */
    fun getByType(type: TaskType): List<AgentTask> = tasks.values.filter { it.taskType == type }

    /** 获取所有任务 */
    fun getAll(): List<AgentTask> = tasks.values.toList()

    /** 移除任务 */
    fun remove(taskId: String) {
        tasks.remove(taskId)
        statusListeners.remove(taskId)
    }

    /** 终止所有任务 */
    fun killAll() {
        for (task in tasks.values) {
            try { task.kill() } catch (_: Exception) {}
        }
    }

    /** 清空注册表 */
    fun clear() {
        tasks.clear()
        statusListeners.clear()
    }

    /** 任务数量 */
    fun size(): Int = tasks.size

    /** 是否包含指定 ID */
    fun contains(taskId: String): Boolean = tasks.containsKey(taskId)
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL（AgentTask.kt 是纯新增文件，不影响现有代码）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentTask.kt
git commit -m "feat(workspace): add AgentTask interface and TaskRegistry"
```

---

## Task 2: ToolOrchestrator — 工具并发执行

**Files:**
- Create: `app/src/main/java/com/example/workspace/ToolOrchestrator.kt`
- Create: `app/src/test/java/com/example/workspace/ToolOrchestratorTest.kt`

- [ ] **Step 1: 创建 ToolOrchestrator.kt**

```kotlin
package com.example.workspace

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 工具调用。
 *
 * @param name 工具名称
 * @param args 工具参数
 * @param callId 调用 ID（OpenAI 格式）
 */
data class ToolCall(
    val name: String,
    val args: JSONObject,
    val callId: String
)

/**
 * 工具执行结果。
 *
 * @param callId 对应的调用 ID
 * @param content 结果内容
 * @param isError 是否为错误结果
 */
data class ToolResult(
    val callId: String,
    val content: String,
    val isError: Boolean = false
)

/**
 * 工具批次。
 *
 * @param calls 本批次的工具调用列表
 * @param isParallel 是否并行执行
 */
private data class ToolBatch(
    val calls: List<ToolCall>,
    val isParallel: Boolean
)

/**
 * 工具并发执行引擎。
 *
 * 对标 Claude Code 的 StreamingToolExecutor + toolOrchestration.ts。
 * 将工具调用按读/写分类：读工具并行执行，写工具串行执行。
 *
 * 读工具（只读，无副作用）：
 * - read_file, list_directory, search_files, get_file_info
 * - search_memory, get_current_time
 *
 * 写工具（有副作用，必须串行）：
 * - write_file, edit_file, create_directory, move_file, delete_file
 * - 所有 MCP 外部工具（默认视为写工具，除非 schema 标记 readOnly）
 *
 * 编排工具（由 OrchestratorTools 拦截，不进入此引擎）：
 * - create_agents, assign_task, continue_conversation, peer_message
 */
class ToolOrchestrator(
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String
) {
    companion object {
        private const val TAG = "ToolOrchestrator"

        /** 只读工具集合（可并行执行） */
        private val READ_ONLY_TOOLS = setOf(
            "read_file",
            "list_directory",
            "search_files",
            "get_file_info",
            "search_memory",
            "get_current_time",
        )
    }

    /**
     * 批量执行工具调用。
     *
     * 将工具列表按读/写分区，读工具并行执行，写工具串行执行。
     * 结果按原始顺序返回。
     *
     * @param calls 工具调用列表
     * @param agentName 调用方 Agent 名称
     * @return 执行结果列表（与 calls 一一对应）
     */
    suspend fun executeToolCalls(
        calls: List<ToolCall>,
        agentName: String
    ): List<ToolResult> {
        if (calls.isEmpty()) return emptyList()
        if (calls.size == 1) {
            return listOf(executeSingle(calls[0], agentName))
        }

        val batches = partitionToolCalls(calls)
        val allResults = mutableListOf<ToolResult>()

        for (batch in batches) {
            if (batch.isParallel && batch.calls.size > 1) {
                // 读工具并行执行
                val batchResults = coroutineScope {
                    batch.calls.map { call ->
                        async {
                            executeSingle(call, agentName)
                        }
                    }.map { it.await() }
                }
                allResults.addAll(batchResults)
            } else {
                // 写工具串行执行
                for (call in batch.calls) {
                    allResults.add(executeSingle(call, agentName))
                }
            }
        }

        return allResults
    }

    /**
     * 将工具调用列表分区为读批次和写批次。
     *
     * 算法：遍历工具列表，遇到读工具累积到当前读批次，
     * 遇到写工具则关闭当前读批次、开启写批次（单个工具）。
     *
     * 示例：
     * [read A, read B, write C, read D, edit E]
     * → [ReadBatch(A,B), WriteBatch(C), ReadBatch(D), WriteBatch(E)]
     */
    internal fun partitionToolCalls(calls: List<ToolCall>): List<ToolBatch> {
        val batches = mutableListOf<ToolBatch>()
        val currentReadBatch = mutableListOf<ToolCall>()

        for (call in calls) {
            if (call.name in READ_ONLY_TOOLS) {
                currentReadBatch.add(call)
            } else {
                // 遇到写工具，先关闭当前读批次
                if (currentReadBatch.isNotEmpty()) {
                    batches.add(ToolBatch(currentReadBatch.toList(), isParallel = true))
                    currentReadBatch.clear()
                }
                // 写工具单独一个批次
                batches.add(ToolBatch(listOf(call), isParallel = false))
            }
        }
        // 关闭最后的读批次
        if (currentReadBatch.isNotEmpty()) {
            batches.add(ToolBatch(currentReadBatch.toList(), isParallel = true))
        }

        return batches
    }

    /**
     * 执行单个工具调用。
     */
    private suspend fun executeSingle(call: ToolCall, agentName: String): ToolResult {
        return try {
            val result = onToolCall(agentName, call.name, call.args, call.callId)
            ToolResult(callId = call.callId, content = result, isError = false)
        } catch (e: Exception) {
            Log.e(TAG, "Tool call failed: ${call.name}", e)
            ToolResult(callId = call.callId, content = "Error: ${e.message}", isError = true)
        }
    }

    companion object {
        /**
         * 检查工具是否为只读工具。
         */
        fun isReadOnlyTool(toolName: String): Boolean = toolName in READ_ONLY_TOOLS
    }
}
```

- [ ] **Step 2: 创建 ToolOrchestratorTest.kt**

```kotlin
package com.example.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolOrchestratorTest {

    private fun makeOrchestrator() = ToolOrchestrator { _, toolName, _, _ ->
        "result:$toolName"
    }

    @Test
    fun `partitionToolCalls separates read and write tools`() {
        val orchestrator = makeOrchestrator()
        val calls = listOf(
            ToolCall("read_file", JSONObject(), "call_1"),
            ToolCall("list_directory", JSONObject(), "call_2"),
            ToolCall("write_file", JSONObject(), "call_3"),
            ToolCall("read_file", JSONObject(), "call_4"),
            ToolCall("edit_file", JSONObject(), "call_5"),
        )

        val batches = orchestrator.partitionToolCalls(calls)

        assertEquals(4, batches.size)
        // Batch 1: read_file + list_directory (parallel)
        assertTrue(batches[0].isParallel)
        assertEquals(2, batches[0].calls.size)
        assertEquals("call_1", batches[0].calls[0].callId)
        assertEquals("call_2", batches[0].calls[1].callId)
        // Batch 2: write_file (serial)
        assertFalse(batches[1].isParallel)
        assertEquals(1, batches[1].calls.size)
        assertEquals("call_3", batches[1].calls[0].callId)
        // Batch 3: read_file (parallel, but only 1)
        assertTrue(batches[2].isParallel)
        assertEquals(1, batches[2].calls.size)
        assertEquals("call_4", batches[2].calls[0].callId)
        // Batch 4: edit_file (serial)
        assertFalse(batches[3].isParallel)
        assertEquals(1, batches[3].calls.size)
        assertEquals("call_5", batches[3].calls[0].callId)
    }

    @Test
    fun `partitionToolCalls all read tools become single parallel batch`() {
        val orchestrator = makeOrchestrator()
        val calls = listOf(
            ToolCall("read_file", JSONObject(), "c1"),
            ToolCall("search_files", JSONObject(), "c2"),
            ToolCall("get_file_info", JSONObject(), "c3"),
        )

        val batches = orchestrator.partitionToolCalls(calls)

        assertEquals(1, batches.size)
        assertTrue(batches[0].isParallel)
        assertEquals(3, batches[0].calls.size)
    }

    @Test
    fun `partitionToolCalls all write tools become individual serial batches`() {
        val orchestrator = makeOrchestrator()
        val calls = listOf(
            ToolCall("write_file", JSONObject(), "c1"),
            ToolCall("edit_file", JSONObject(), "c2"),
            ToolCall("delete_file", JSONObject(), "c3"),
        )

        val batches = orchestrator.partitionToolCalls(calls)

        assertEquals(3, batches.size)
        batches.forEach { assertFalse(it.isParallel) }
    }

    @Test
    fun `partitionToolCalls empty input returns empty`() {
        val orchestrator = makeOrchestrator()
        val batches = orchestrator.partitionToolCalls(emptyList())
        assertTrue(batches.isEmpty())
    }

    @Test
    fun `isReadOnlyTool returns correct results`() {
        assertTrue(ToolOrchestrator.isReadOnlyTool("read_file"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("list_directory"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("search_files"))
        assertFalse(ToolOrchestrator.isReadOnlyTool("write_file"))
        assertFalse(ToolOrchestrator.isReadOnlyTool("edit_file"))
        assertFalse(ToolOrchestrator.isReadOnlyTool("create_agents"))
    }

    @Test
    fun `executeToolCalls preserves result order`() = kotlinx.coroutines.runBlocking {
        val results = mutableListOf<String>()
        val orchestrator = ToolOrchestrator { _, toolName, _, callId ->
            val result = "result:$toolName:$callId"
            result
        }

        val calls = listOf(
            ToolCall("read_file", JSONObject().put("path", "/a"), "c1"),
            ToolCall("write_file", JSONObject().put("path", "/b"), "c2"),
            ToolCall("read_file", JSONObject().put("path", "/c"), "c3"),
        )

        val results2 = orchestrator.executeToolCalls(calls, "test_agent")

        assertEquals(3, results2.size)
        assertEquals("result:read_file:c1", results2[0].content)
        assertEquals("result:write_file:c2", results2[1].content)
        assertEquals("result:read_file:c3", results2[2].content)
        assertFalse(results2[0].isError)
        assertFalse(results2[1].isError)
        assertFalse(results2[2].isError)
    }

    @Test
    fun `executeToolCalls handles tool failure gracefully`() = kotlinx.coroutines.runBlocking {
        val orchestrator = ToolOrchestrator { _, toolName, _, callId ->
            if (toolName == "write_file") throw RuntimeException("disk full")
            "ok:$callId"
        }

        val calls = listOf(
            ToolCall("read_file", JSONObject(), "c1"),
            ToolCall("write_file", JSONObject(), "c2"),
        )

        val results = orchestrator.executeToolCalls(calls, "test_agent")

        assertEquals(2, results.size)
        assertFalse(results[0].isError)
        assertEquals("ok:c1", results[0].content)
        assertTrue(results[1].isError)
        assertTrue(results[1].content.contains("disk full"))
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.ToolOrchestratorTest"`
Expected: ALL TESTS PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/ToolOrchestrator.kt
git add app/src/test/java/com/example/workspace/ToolOrchestratorTest.kt
git commit -m "feat(workspace): add ToolOrchestrator for concurrent tool execution"
```

---

## Task 3: AgentDefinition + AgentRegistry

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentDefinition.kt`
- Create: `app/src/main/java/com/example/workspace/AgentRegistry.kt`
- Create: `app/src/main/assets/workspace/agents/orchestrator.json`
- Create: `app/src/main/assets/workspace/agents/teammate_default.json`
- Create: `app/src/main/assets/workspace/agents/explorer.json`
- Create: `app/src/main/assets/workspace/agents/verifier.json`
- Create: `app/src/test/java/com/example/workspace/AgentRegistryTest.kt`

- [ ] **Step 1: 创建 AgentDefinition.kt**

```kotlin
package com.example.workspace

/**
 * Agent 定义。
 *
 * 对标 Claude Code 的 AgentDefinition（从 .claude/agents/*.md 加载）。
 * 描述一个 Agent 的角色、能力边界和行为参数。
 *
 * 加载优先级（高→低）：
 * 1. assets/workspace/agents/*.json — 内置预设
 * 2. 外存 OmniChat/workspace/agents/*.json — 用户自定义
 * 3. Room DB AgentPreset — UI 保存的配置（向后兼容）
 *
 * @param name 唯一标识（用于匹配和查找）
 * @param displayName 显示名称（UI 展示用）
 * @param systemPrompt 系统提示词
 * @param modelHint 模型选择提示（null = 使用 Orchestrator 的模型）
 * @param allowedTools 工具白名单（null = 全部允许，非 null = 只允许列表中的工具）
 * @param disallowedTools 工具黑名单（null = 不排除任何工具）
 * @param isOrchestrator 是否为 Orchestrator 角色
 * @param maxToolIterations 最大工具调用迭代次数
 * @param description 用途描述
 */
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
) {
    /**
     * 获取过滤后的工具列表。
     *
     * 根据 allowedTools 和 disallowedTools 过滤可用工具。
     * allowedTools 优先级高于 disallowedTools（白名单模式下黑名单无效）。
     */
    fun filterTools(allToolNames: Set<String>): Set<String> {
        var tools = allToolNames
        allowedTools?.let { allowed ->
            tools = tools.intersect(allowed.toSet())
        }
        disallowedTools?.let { blocked ->
            tools = tools - blocked.toSet()
        }
        return tools
    }

    /**
     * 检查指定工具是否被允许。
     */
    fun isToolAllowed(toolName: String): Boolean {
        if (allowedTools != null && toolName !in allowedTools) return false
        if (disallowedTools != null && toolName in disallowedTools) return false
        return true
    }
}
```

- [ ] **Step 2: 创建 AgentRegistry.kt**

```kotlin
package com.example.workspace

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 注册表。
 *
 * 负责从多个来源加载 AgentDefinition，提供查询接口。
 * 对标 Claude Code 的 loadAgentsDir.ts。
 *
 * 加载顺序：
 * 1. assets/workspace/agents/*.json — 内置预设
 * 2. 外存 OmniChat/workspace/agents/*.json — 用户自定义
 * 3. Room DB AgentPreset — UI 保存的配置
 *
 * 后加载的同名定义覆盖先加载的（用户自定义 > 内置预设）。
 */
class AgentRegistry(private val context: Context) {
    companion object {
        private const val TAG = "AgentRegistry"
        private const val ASSETS_DIR = "workspace/agents"
    }

    private val definitions = ConcurrentHashMap<String, AgentDefinition>()

    /**
     * 加载所有 Agent 定义。
     *
     * 扫描 assets + 外存 + DB，合并去重。同名定义后者覆盖前者。
     */
    fun loadAll() {
        definitions.clear()

        // 1. 加载 assets 内置预设
        loadFromAssets()

        // 2. 加载外存用户自定义
        loadFromExternalStorage()

        Log.d(TAG, "Loaded ${definitions.size} agent definitions: ${definitions.keys}")
    }

    /**
     * 从 Room DB AgentPreset 加载（向后兼容）。
     *
     * 由 TeamManager 在 createTeam 时调用，将 AgentPreset 转换为 AgentDefinition。
     */
    fun loadFromPresets(presets: List<com.example.data.AgentPreset>) {
        for (preset in presets) {
            if (preset.name.isBlank()) continue
            val def = AgentDefinition(
                name = preset.name,
                displayName = preset.name,
                systemPrompt = preset.systemPrompt,
                modelHint = preset.modelHint?.let { runCatching { ModelHint.valueOf(it) }.getOrNull() },
                isOrchestrator = false,
                description = preset.description ?: ""
            )
            // DB 预设不覆盖已有的文件定义
            definitions.putIfAbsent(def.name, def)
        }
    }

    /** 获取指定名称的定义 */
    fun get(name: String): AgentDefinition? = definitions[name]

    /** 获取所有定义 */
    fun getAll(): List<AgentDefinition> = definitions.values.toList()

    /** 按 modelHint 获取匹配的定义 */
    fun getByHint(hint: ModelHint): List<AgentDefinition> {
        return definitions.values.filter { it.modelHint == hint }
    }

    /** 获取 Orchestrator 定义 */
    fun getOrchestrator(): AgentDefinition? {
        return definitions.values.firstOrNull { it.isOrchestrator }
    }

    /** 检查是否存在指定定义 */
    fun contains(name: String): Boolean = definitions.containsKey(name)

    /**
     * 从 assets 加载内置预设。
     */
    private fun loadFromAssets() {
        try {
            val files = context.assets.list(ASSETS_DIR) ?: return
            for (fileName in files) {
                if (!fileName.endsWith(".json")) continue
                try {
                    val json = context.assets.open("$ASSETS_DIR/$fileName")
                        .bufferedReader().use { it.readText() }
                    val def = parseDefinitionJson(json)
                    if (def != null) {
                        definitions[def.name] = def
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load asset agent definition: $fileName", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list asset agent definitions", e)
        }
    }

    /**
     * 从外存加载用户自定义定义。
     */
    private fun loadFromExternalStorage() {
        try {
            val baseDir = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "OmniChat/workspace/agents"
            )
            if (!baseDir.exists() || !baseDir.isDirectory) return

            for (file in baseDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()) {
                try {
                    val json = file.readText()
                    val def = parseDefinitionJson(json)
                    if (def != null) {
                        definitions[def.name] = def
                        Log.d(TAG, "Loaded user agent definition: ${def.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load user agent definition: ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan user agent definitions", e)
        }
    }

    /**
     * 解析 JSON 为 AgentDefinition。
     */
    internal fun parseDefinitionJson(json: String): AgentDefinition? {
        return try {
            val obj = JSONObject(json)
            val name = obj.optString("name")
            if (name.isBlank()) {
                Log.w(TAG, "Agent definition missing 'name' field")
                return null
            }

            AgentDefinition(
                name = name,
                displayName = obj.optString("displayName", name),
                systemPrompt = obj.optString("systemPrompt", ""),
                modelHint = obj.optString("modelHint", "").takeIf { it.isNotBlank() }
                    ?.let { runCatching { ModelHint.valueOf(it) }.getOrNull() },
                allowedTools = obj.optJSONArray("allowedTools")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                disallowedTools = obj.optJSONArray("disallowedTools")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                isOrchestrator = obj.optBoolean("isOrchestrator", false),
                maxToolIterations = obj.optInt("maxToolIterations", 50),
                description = obj.optString("description", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse agent definition JSON", e)
            null
        }
    }
}
```

- [ ] **Step 3: 创建内置预设 JSON 文件**

`app/src/main/assets/workspace/agents/orchestrator.json`:
```json
{
    "name": "orchestrator",
    "displayName": "主控 Agent",
    "systemPrompt": "",
    "isOrchestrator": true,
    "disallowedTools": [
        "write_file", "create_directory", "move_file", "delete_file",
        "create_pdf_document", "create_excel_document", "create_word_document", "create_powerpoint_document",
        "set_primary_color", "set_secondary_color", "set_background_color", "set_surface_color",
        "set_corner_radius", "set_spacing_multiplier", "set_font_family", "set_font_size_scale", "set_font_weight",
        "set_ui_texts", "configure_mcp_tool_groups"
    ],
    "description": "主控 Agent，负责任务分解和调度"
}
```

`app/src/main/assets/workspace/agents/teammate_default.json`:
```json
{
    "name": "teammate_default",
    "displayName": "通用子 Agent",
    "systemPrompt": "",
    "isOrchestrator": false,
    "description": "通用子 Agent，可执行所有工具"
}
```

`app/src/main/assets/workspace/agents/explorer.json`:
```json
{
    "name": "explorer",
    "displayName": "探索者",
    "systemPrompt": "",
    "modelHint": "FAST",
    "allowedTools": ["read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"],
    "isOrchestrator": false,
    "maxToolIterations": 30,
    "description": "快速搜索分析，只读工具"
}
```

`app/src/main/assets/workspace/agents/verifier.json`:
```json
{
    "name": "verifier",
    "displayName": "验证者",
    "systemPrompt": "",
    "modelHint": "REASONING",
    "allowedTools": ["read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time", "execute_command"],
    "isOrchestrator": false,
    "maxToolIterations": 30,
    "description": "验证实现，只读 + bash"
}
```

- [ ] **Step 4: 创建 AgentRegistryTest.kt**

```kotlin
package com.example.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRegistryTest {

    private lateinit var context: Context
    private lateinit var registry: AgentRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = AgentRegistry(context)
    }

    @Test
    fun `parseDefinitionJson parses valid JSON`() {
        val json = """
        {
            "name": "test_agent",
            "displayName": "Test Agent",
            "systemPrompt": "You are a test agent",
            "modelHint": "REASONING",
            "allowedTools": ["read_file", "write_file"],
            "isOrchestrator": false,
            "maxToolIterations": 30,
            "description": "A test agent"
        }
        """.trimIndent()

        val def = registry.parseDefinitionJson(json)

        assertNotNull(def)
        assertEquals("test_agent", def!!.name)
        assertEquals("Test Agent", def.displayName)
        assertEquals("You are a test agent", def.systemPrompt)
        assertEquals(ModelHint.REASONING, def.modelHint)
        assertEquals(listOf("read_file", "write_file"), def.allowedTools)
        assertNull(def.disallowedTools)
        assertFalse(def.isOrchestrator)
        assertEquals(30, def.maxToolIterations)
    }

    @Test
    fun `parseDefinitionJson returns null for missing name`() {
        val json = """{"displayName": "No Name"}"""
        val def = registry.parseDefinitionJson(json)
        assertNull(def)
    }

    @Test
    fun `parseDefinitionJson handles blank name`() {
        val json = """{"name": ""}"""
        val def = registry.parseDefinitionJson(json)
        assertNull(def)
    }

    @Test
    fun `filterTools with allowedTools acts as whitelist`() {
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            allowedTools = listOf("read_file", "list_directory")
        )

        val allTools = setOf("read_file", "list_directory", "write_file", "edit_file")
        val filtered = def.filterTools(allTools)

        assertEquals(setOf("read_file", "list_directory"), filtered)
    }

    @Test
    fun `filterTools with disallowedTools acts as blacklist`() {
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            disallowedTools = listOf("write_file", "delete_file")
        )

        val allTools = setOf("read_file", "write_file", "delete_file", "edit_file")
        val filtered = def.filterTools(allTools)

        assertEquals(setOf("read_file", "edit_file"), filtered)
    }

    @Test
    fun `filterTools with null allows all`() {
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = ""
        )

        val allTools = setOf("read_file", "write_file")
        val filtered = def.filterTools(allTools)

        assertEquals(allTools, filtered)
    }

    @Test
    fun `isToolAllowed respects allowedTools`() {
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            allowedTools = listOf("read_file")
        )

        assertTrue(def.isToolAllowed("read_file"))
        assertFalse(def.isToolAllowed("write_file"))
    }

    @Test
    fun `isToolAllowed respects disallowedTools`() {
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            disallowedTools = listOf("write_file")
        )

        assertTrue(def.isToolAllowed("read_file"))
        assertFalse(def.isToolAllowed("write_file"))
    }

    @Test
    fun `loadFromPresets adds presets without overriding existing`() {
        registry.loadAll() // Load built-in assets first

        val presets = listOf(
            com.example.data.AgentPreset(
                id = 1,
                name = "custom_agent",
                systemPrompt = "Custom prompt",
                modelConfigId = null,
                description = "Custom agent"
            )
        )
        registry.loadFromPresets(presets)

        assertNotNull(registry.get("custom_agent"))
        assertEquals("Custom prompt", registry.get("custom_agent")!!.systemPrompt)
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.AgentRegistryTest"`
Expected: ALL TESTS PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentDefinition.kt
git add app/src/main/java/com/example/workspace/AgentRegistry.kt
git add app/src/main/assets/workspace/agents/
git add app/src/test/java/com/example/workspace/AgentRegistryTest.kt
git commit -m "feat(workspace): add AgentDefinition, AgentRegistry, and built-in agent presets"
```

---

## Task 4: Scratchpad + MCP 工具

**Files:**
- Create: `app/src/main/java/com/example/workspace/Scratchpad.kt`
- Create: `app/src/test/java/com/example/workspace/ScratchpadTest.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: 创建 Scratchpad.kt**

```kotlin
package com.example.workspace

import android.util.Log
import java.io.File

/**
 * 共享暂存区条目。
 */
data class ScratchpadEntry(
    val agentName: String,
    val key: String,
    val content: String,
    val lastModified: Long
)

/**
 * 共享暂存区。
 *
 * 对标 Claude Code 的 scratchpad 目录。提供跨 Agent 的文件共享能力，
 * 避免通过消息传递大块数据。
 *
 * 存储位置：OmniChat/workspace/{sessionId}/scratchpad/
 * 文件命名：{agentName}_{key}.txt
 *
 * 使用场景：
 * - Agent A 分析完代码结构后写入 → Agent B 直接读取
 * - Orchestrator 将任务上下文写入 → 子 Agent 按需读取
 * - 调试时可查看内容了解 Agent 间协作状态
 */
class Scratchpad(private val basePath: File) {

    init {
        basePath.mkdirs()
    }

    /**
     * 写入共享数据。
     *
     * @param agentName 写入方 Agent 名称
     * @param key 数据键名
     * @param content 数据内容
     */
    fun write(agentName: String, key: String, content: String) {
        val sanitizedKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = basePath.resolve("${agentName}_${sanitizedKey}.txt")
        file.writeText(content)
        Log.d(TAG, "Scratchpad write: ${file.name} (${content.length} chars)")
    }

    /**
     * 读取共享数据。
     *
     * @param agentName 数据所属 Agent 名称
     * @param key 数据键名
     * @return 数据内容，不存在则返回 null
     */
    fun read(agentName: String, key: String): String? {
        val sanitizedKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = basePath.resolve("${agentName}_${sanitizedKey}.txt")
        return if (file.exists()) {
            try {
                file.readText()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read scratchpad: ${file.name}", e)
                null
            }
        } else null
    }

    /**
     * 列出所有共享数据。
     */
    fun list(): List<ScratchpadEntry> {
        return basePath.listFiles()?.filter { it.extension == "txt" }?.map { file ->
            val nameParts = file.nameWithoutExtension.split("_", limit = 2)
            ScratchpadEntry(
                agentName = nameParts.getOrElse(0) { "unknown" },
                key = nameParts.getOrElse(1) { file.nameWithoutExtension },
                content = try { file.readText().take(200) } catch (_: Exception) { "" },
                lastModified = file.lastModified()
            )
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /**
     * 清理指定 Agent 的数据。
     */
    fun cleanup(agentName: String) {
        basePath.listFiles()?.filter {
            it.name.startsWith("${agentName}_") && it.extension == "txt"
        }?.forEach { it.delete() }
    }

    /**
     * 清理全部数据。
     */
    fun clearAll() {
        basePath.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Scratchpad cleared")
    }

    companion object {
        private const val TAG = "Scratchpad"
    }
}
```

- [ ] **Step 2: 创建 ScratchpadTest.kt**

```kotlin
package com.example.workspace

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ScratchpadTest {

    private lateinit var tempDir: File
    private lateinit var scratchpad: Scratchpad

    @Before
    fun setUp() {
        tempDir = createTempDir("scratchpad_test")
        scratchpad = Scratchpad(tempDir)
    }

    @Test
    fun `write and read works correctly`() {
        scratchpad.write("agent_a", "analysis", "code structure analysis result")

        val result = scratchpad.read("agent_a", "analysis")
        assertEquals("code structure analysis result", result)
    }

    @Test
    fun `read returns null for non-existent key`() {
        val result = scratchpad.read("agent_a", "nonexistent")
        assertNull(result)
    }

    @Test
    fun `write overwrites existing content`() {
        scratchpad.write("agent_a", "key", "old content")
        scratchpad.write("agent_a", "key", "new content")

        val result = scratchpad.read("agent_a", "key")
        assertEquals("new content", result)
    }

    @Test
    fun `list returns all entries`() {
        scratchpad.write("agent_a", "analysis", "analysis result")
        scratchpad.write("agent_b", "review", "review result")

        val entries = scratchpad.list()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.agentName == "agent_a" && it.key == "analysis" })
        assertTrue(entries.any { it.agentName == "agent_b" && it.key == "review" })
    }

    @Test
    fun `cleanup removes only specified agent data`() {
        scratchpad.write("agent_a", "key1", "data a1")
        scratchpad.write("agent_a", "key2", "data a2")
        scratchpad.write("agent_b", "key1", "data b1")

        scratchpad.cleanup("agent_a")

        assertNull(scratchpad.read("agent_a", "key1"))
        assertNull(scratchpad.read("agent_a", "key2"))
        assertEquals("data b1", scratchpad.read("agent_b", "key1"))
    }

    @Test
    fun `clearAll removes everything`() {
        scratchpad.write("agent_a", "key1", "data")
        scratchpad.write("agent_b", "key2", "data")

        scratchpad.clearAll()

        assertTrue(scratchpad.list().isEmpty())
    }

    @Test
    fun `special characters in key are sanitized`() {
        scratchpad.write("agent", "key/with\\special*chars", "data")

        val result = scratchpad.read("agent", "key/with\\special*chars")
        assertEquals("data", result)
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.ScratchpadTest"`
Expected: ALL TESTS PASS

- [ ] **Step 4: 在 BuiltinToolHandler 中添加 Scratchpad 工具**

在 `BuiltinToolHandler.kt` 的 `builtinTools` 列表中添加三个工具定义，并在 `handleBuiltinTool` 中添加对应的处理分支。

需要添加的工具 schema:
- `scratchpad_write`: 参数 `key` (string, required), `content` (string, required)
- `scratchpad_read`: 参数 `agentName` (string, required), `key` (string, required)
- `scratchpad_list`: 无参数

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/Scratchpad.kt
git add app/src/test/java/com/example/workspace/ScratchpadTest.kt
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "feat(workspace): add Scratchpad for cross-agent file sharing"
```

---

## Task 5: AgentProgressSummarizer

**Files:**
- Create: `app/src/main/java/com/example/workspace/AgentProgressSummarizer.kt`

- [ ] **Step 1: 创建 AgentProgressSummarizer.kt**

```kotlin
package com.example.workspace

import android.util.Log

/**
 * Agent 进度摘要生成器。
 *
 * 对标 Claude Code 的 startAgentSummarization()。
 * 在 Agent 执行过程中定期生成进度摘要，注入到 Orchestrator 上下文，
 * 让主控了解子 Agent 的执行进展。
 *
 * 触发条件：
 * 1. 每 N 次工具调用自动触发
 * 2. Orchestrator 主动查询时触发
 *
 * 摘要格式：
 * ```
 * [AgentName 进度摘要]
 * - 已完成：创建了 index.html 和 style.css
 * - 正在进行：正在编写 script.js
 * - 遇到问题：无
 * ```
 */
class AgentProgressSummarizer(
    private val agentName: String,
    private val messageHistory: () -> List<AgentMessage>
) {
    companion object {
        private const val TAG = "ProgressSummary"

        /** 每 N 次工具调用生成一次摘要 */
        const val SUMMARY_INTERVAL = 5
    }

    /** 上次摘要时的消息索引 */
    private var lastSummaryIndex = 0

    /** 累计工具调用次数 */
    private var toolCallCount = 0

    /**
     * 通知工具调用完成，检查是否需要生成摘要。
     *
     * @return 摘要文本，不需要生成时返回 null
     */
    fun onToolCallCompleted(): String? {
        toolCallCount++
        if (toolCallCount % SUMMARY_INTERVAL != 0) return null

        return generateSummary()
    }

    /**
     * 强制生成摘要（供 Orchestrator 主动查询）。
     *
     * @return 摘要文本
     */
    fun forceSummarize(): String {
        return generateSummary()
    }

    /**
     * 生成进度摘要。
     */
    private fun generateSummary(): String {
        val history = messageHistory()
        if (history.isEmpty()) return "[$agentName 进度摘要] 暂无执行记录"

        val recentMessages = history.drop(lastSummaryIndex)
        if (recentMessages.isEmpty()) return "[$agentName 进度摘要] 无新进展"

        lastSummaryIndex = history.size

        val keyActions = extractKeyActions(recentMessages)

        return buildString {
            appendLine("[$agentName 进度摘要]")
            if (keyActions.isEmpty()) {
                appendLine("- 正在执行中...")
            } else {
                for (action in keyActions) {
                    appendLine("- $action")
                }
            }
        }
    }

    /**
     * 从消息历史中提取关键动作。
     */
    private fun extractKeyActions(messages: List<AgentMessage>): List<String> {
        val actions = mutableListOf<String>()

        for (msg in messages) {
            when (msg.role) {
                "assistant" -> {
                    if (msg.content.isNotBlank()) {
                        // 提取包含工具调用意图的文本
                        val content = msg.content
                        if (content.contains("创建") || content.contains("写入") ||
                            content.contains("读取") || content.contains("搜索") ||
                            content.contains("分析") || content.contains("完成")) {
                            actions.add(content.take(100).replace("\n", " "))
                        }
                    }
                }
                "tool" -> {
                    if (msg.content.isNotBlank() && !msg.content.startsWith("Error")) {
                        // 工具执行成功
                        val preview = msg.content.take(80).replace("\n", " ")
                        actions.add("工具执行: $preview")
                    } else if (msg.content.startsWith("Error")) {
                        actions.add("工具错误: ${msg.content.take(80)}")
                    }
                }
                "system" -> {
                    if (msg.content.contains("新任务开始")) {
                        actions.add("开始新任务")
                    }
                }
            }
        }

        // 最多返回 5 条关键动作
        return actions.takeLast(5)
    }

    /**
     * 重置状态（新任务开始时调用）。
     */
    fun reset() {
        lastSummaryIndex = 0
        toolCallCount = 0
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentProgressSummarizer.kt
git commit -m "feat(workspace): add AgentProgressSummarizer for periodic progress reports"
```

---

## Task 6: 重构 AgentRunner — 接入 ToolOrchestrator

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt:370-460`

这是核心重构，将 `runTurn()` 中的工具执行循环改为使用 `ToolOrchestrator`。

- [ ] **Step 1: 在 AgentRunner 构造函数中添加 ToolOrchestrator**

在 `AgentRunner` 类中添加 `toolOrchestrator` 属性，并在构造时初始化：

```kotlin
// 在 AgentRunner 类中添加
private val toolOrchestrator = ToolOrchestrator(onToolCall)
```

- [ ] **Step 2: 替换 runTurn 中的工具执行循环**

将 `runTurn()` 方法中第 372-484 行的工具执行逻辑（`if (accumulatedToolCalls.isNotEmpty())` 块）替换为使用 `ToolOrchestrator`：

原代码（约 372-484 行）：
```kotlin
// 处理工具调用
if (accumulatedToolCalls.isNotEmpty()) {
    var hasToolResults = false
    var hasToolError = false
    for (toolCall in accumulatedToolCalls.values) {
        // ... 100+ 行顺序执行逻辑
    }
    // ...
}
```

替换为：
```kotlin
// 处理工具调用（通过 ToolOrchestrator 并发执行）
if (accumulatedToolCalls.isNotEmpty()) {
    val toolCalls = accumulatedToolCalls.values.mapNotNull { toolCall ->
        val function = toolCall.optJSONObject("function") ?: return@mapNotNull null
        val toolName = function.optString("name")
        if (toolName.isEmpty()) {
            Log.w(TAG, "Skipping tool call with empty name: $toolCall")
            return@mapNotNull null
        }
        val argsStr = function.optString("arguments")
        val callId = toolCall.optString("id")

        // 解析 JSON 参数
        val argsJson = try {
            JSONObject(argsStr)
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Invalid JSON arguments for tool '$toolName' (callId=$callId): $argsStr", e)
            // 返回错误结果而非跳过
            messagesLock.writeLock().lock()
            try {
                context.messages.add(
                    AgentMessage(
                        role = "tool",
                        content = "Error: invalid arguments JSON for '$toolName': ${e.message}",
                        toolCallId = callId
                    )
                )
            } finally {
                messagesLock.writeLock().unlock()
            }
            return@mapNotNull null
        }

        ToolCall(name = toolName, args = argsJson, callId = callId)
    }

    if (toolCalls.isNotEmpty()) {
        // 通过 ToolOrchestrator 执行（读工具并行，写工具串行）
        val results = toolOrchestrator.executeToolCalls(toolCalls, context.agentName)

        var hasToolError = false
        for (result in results) {
            // 更新工具调用计数
            synchronized(usageStatsLock) {
                usageStats.toolUseCount++
            }

            // 保存工具结果到上下文
            messagesLock.writeLock().lock()
            try {
                context.messages.add(
                    AgentMessage(
                        role = "tool",
                        content = result.content,
                        toolCallId = result.callId
                    )
                )
            } finally {
                messagesLock.writeLock().unlock()
            }

            if (result.isError) {
                hasToolError = true
                consecutiveToolFailureCount++
            } else {
                consecutiveToolFailureCount = 0
            }
        }

        // 连续失败检查
        if (consecutiveToolFailureCount >= MAX_CONSECUTIVE_TOOL_FAILURES) {
            Log.w(TAG, "Agent '${context.agentName}' reached max consecutive tool failures ($MAX_CONSECUTIVE_TOOL_FAILURES), stopping")
            messagesLock.writeLock().lock()
            try {
                context.messages.add(
                    AgentMessage(
                        role = "system",
                        content = "连续工具调用失败 $MAX_CONSECUTIVE_TOOL_FAILURES 次，强制结束本轮对话。请检查工具是否可用或参数是否正确。"
                    )
                )
            } finally {
                messagesLock.writeLock().unlock()
            }
            break
        }

        // 有工具结果，继续下一轮
        continue
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 运行现有测试**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.*"`
Expected: ALL EXISTING TESTS PASS（行为应与重构前一致）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt
git commit -m "refactor(workspace): replace sequential tool execution with ToolOrchestrator"
```

---

## Task 7: 重构 AgentExecutionLoops — 封装为 AgentTask 实现

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt`

- [ ] **Step 1: 创建 OrchestratorTask 和 TeammateTask 类**

在 `AgentExecutionLoops.kt` 末尾添加两个 `AgentTask` 实现类：

```kotlin
/**
 * Orchestrator 任务实现。
 *
 * 封装 runOrchestratorLoop 为 AgentTask 接口。
 */
class OrchestratorTask(
    override val taskName: String,
    private val runner: AgentRunner,
    private val executionLoops: AgentExecutionLoops,
    private val initialTask: String,
    private val imagePath: String? = null,
    private val isCompleted: AtomicBoolean,
    private val onWorkspaceComplete: suspend (AgentRunner) -> Unit,
) : AgentTask {
    override val taskId = ORCHESTRATOR_NAME
    override val taskType = TaskType.ORCHESTRATOR

    @Volatile
    override var status: TaskStatus = TaskStatus.CREATED
        private set

    private val statusListeners = mutableListOf<(TaskStatus) -> Unit>()
    private var job: Job? = null

    override suspend fun execute() {
        updateStatus(TaskStatus.RUNNING)
        try {
            executionLoops.runOrchestratorLoop(runner, initialTask, imagePath, isCompleted, onWorkspaceComplete)
            updateStatus(TaskStatus.COMPLETED)
        } catch (e: CancellationException) {
            updateStatus(TaskStatus.CANCELLED)
            throw e
        } catch (e: Exception) {
            updateStatus(TaskStatus.FAILED)
            throw e
        }
    }

    override fun kill() {
        job?.cancel()
        updateStatus(TaskStatus.CANCELLED)
    }

    override fun onStatusChange(listener: (TaskStatus) -> Unit) {
        statusListeners.add(listener)
    }

    private fun updateStatus(newStatus: TaskStatus) {
        val oldStatus = status
        status = newStatus
        if (oldStatus != newStatus) {
            statusListeners.forEach { it(newStatus) }
        }
    }
}

/**
 * Teammate 任务实现。
 *
 * 封装 runTeammateLoop 为 AgentTask 接口。
 */
class TeammateTask(
    override val taskName: String,
    private val runner: AgentRunner,
    private val identity: TeammateIdentity,
    private val executionLoops: AgentExecutionLoops,
) : AgentTask {
    override val taskId = identity.agentName
    override val taskType = TaskType.TEAMMATE

    @Volatile
    override var status: TaskStatus = TaskStatus.CREATED
        private set

    private val statusListeners = mutableListOf<(TaskStatus) -> Unit>()
    private var job: Job? = null

    override suspend fun execute() {
        updateStatus(TaskStatus.RUNNING)
        try {
            executionLoops.runTeammateLoop(runner, identity)
            updateStatus(TaskStatus.COMPLETED)
        } catch (e: CancellationException) {
            updateStatus(TaskStatus.CANCELLED)
            throw e
        } catch (e: Exception) {
            updateStatus(TaskStatus.FAILED)
            throw e
        }
    }

    override fun kill() {
        job?.cancel()
        updateStatus(TaskStatus.CANCELLED)
    }

    override fun onStatusChange(listener: (TaskStatus) -> Unit) {
        statusListeners.add(listener)
    }

    private fun updateStatus(newStatus: TaskStatus) {
        val oldStatus = status
        status = newStatus
        if (oldStatus != newStatus) {
            statusListeners.forEach { it(newStatus) }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentExecutionLoops.kt
git commit -m "refactor(workspace): add OrchestratorTask and TeammateTask implementations"
```

---

## Task 8: 重构 AgentLifecycle — 使用 AgentRegistry + TaskRegistry

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentLifecycle.kt`

- [ ] **Step 1: 在 AgentLifecycle 中添加 AgentRegistry 和 TaskRegistry**

在构造函数中添加参数：
```kotlin
class AgentLifecycle(
    private val repository: AppRepository,
    private val messageBus: MessageBus,
    private val taskManager: TaskManager,
    private val config: WorkspaceConfig,
    private val agentRegistry: AgentRegistry,  // 新增
    private val taskRegistry: TaskRegistry,    // 新增
    private val onError: (message: String) -> Unit,
)
```

- [ ] **Step 2: 修改 spawnTeammate 使用 AgentDefinition**

在 `spawnTeammate` 方法中，优先从 `agentRegistry` 查找 AgentDefinition：

```kotlin
// 在 spawnTeammate 开头添加
val agentDef = agentRegistry.get(name)
val finalSystemPrompt2 = agentDef?.systemPrompt?.takeIf { it.isNotEmpty() }
    ?: systemPrompt.takeIf { it.isNotEmpty() }
    ?: DEFAULT_TEAMMATE_PROMPT

// 使用 agentDef 的 disallowedTools 替代硬编码
val disallowedTools = agentDef?.let { def ->
    val allBuiltinTools = mcpRuntimeManager.allTools.value
        .filter { it.serverId == -1L }
        .map { it.name }
        .toSet()
    allBuiltinTools.filter { !def.isToolAllowed(it) }.toSet()
} ?: if (isSubAgent) ORCHESTRATOR_ONLY_TOOLS else emptySet()
```

- [ ] **Step 3: 注册 Task 到 TaskRegistry**

在 `spawnTeammate` 中，创建 TeammateTask 并注册：

```kotlin
// 在 launch 之前创建 task
val teammateTask = TeammateTask(
    taskName = uniqueName,
    runner = runner,
    identity = identity,
    executionLoops = executionLoops,  // 需要传入
)
taskRegistry.register(teammateTask)

// 修改 launch 块
val job = teammateScope.launch {
    teammateTask.execute()  // 使用 task 的 execute
}
```

- [ ] **Step 4: 修改 cleanupAll 使用 TaskRegistry**

```kotlin
internal suspend fun cleanupAll() {
    taskRegistry.killAll()
    // ... 保留原有的 cleanup 逻辑
    taskRegistry.clear()
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentLifecycle.kt
git commit -m "refactor(workspace): integrate AgentRegistry and TaskRegistry into AgentLifecycle"
```

---

## Task 9: 重构 TeamManager — 使用 TaskRegistry + AgentRegistry

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: 添加 AgentRegistry 和 TaskRegistry 属性**

```kotlin
class TeamManager(
    // ... 现有参数
) {
    // 新增
    private val agentRegistry = AgentRegistry(context)  // 需要传入 context
    private val taskRegistry = TaskRegistry()

    // 修改 AgentLifecycle 构造
    private val lifecycle = AgentLifecycle(
        repository, messageBus, taskManager, config,
        agentRegistry, taskRegistry,  // 新增
        onError
    )
}
```

- [ ] **Step 2: 在 createTeam 中加载 AgentRegistry**

```kotlin
suspend fun createTeam(...): TeamState = createTeamMutex.withLock {
    // ... 现有验证逻辑

    // 加载 Agent 定义
    agentRegistry.loadAll()
    agentRegistry.loadFromPresets(agentPresets)

    // ... 后续逻辑
}
```

- [ ] **Step 3: 在 deleteTeam 中清理 TaskRegistry**

```kotlin
suspend fun deleteTeam() {
    // ... 现有逻辑
    taskRegistry.clear()
    agentRegistry.let { /* no cleanup needed, GC handles it */ }
}
```

- [ ] **Step 4: 暴露 TaskRegistry 给 WorkspaceViewModel**

```kotlin
/** 获取 TaskRegistry（供 ViewModel 观察任务状态） */
fun getTaskRegistry(): TaskRegistry = taskRegistry
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "refactor(workspace): integrate TaskRegistry and AgentRegistry into TeamManager"
```

---

## Task 10: 集成 Scratchpad 和 ProgressSummarizer

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`
- Modify: `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt`

- [ ] **Step 1: 在 TeamManager 中创建 Scratchpad**

```kotlin
// 在 createTeam 中
val scratchpadDir = java.io.File(
    android.os.Environment.getExternalStorageDirectory(),
    "OmniChat/workspace/${teamName}/scratchpad"
)
val scratchpad = Scratchpad(scratchpadDir)
```

- [ ] **Step 2: 将 Scratchpad 传递给 BuiltinToolHandler**

在 `createAgentRunner` 中，将 Scratchpad 传递给 `orchestratorTools`，最终传到 `BuiltinToolHandler`。

- [ ] **Step 3: 在 AgentExecutionLoops 中集成 ProgressSummarizer**

在 `executeTask` 方法中，创建 `AgentProgressSummarizer` 并在工具调用后检查：

```kotlin
val summarizer = AgentProgressSummarizer(identity.agentName) { runner.getHistory() }

// 在 runner.runTurn 完成后
val summary = summarizer.onToolCallCompleted()
if (summary != null) {
    // 注入到 Orchestrator 上下文
    messageBus.send(ORCHESTRATOR_NAME, TeamMessage.Text(
        from = "system",
        content = summary
    ))
}
```

- [ ] **Step 4: 在 deleteTeam 中清理 Scratchpad**

```kotlin
suspend fun deleteTeam() {
    // ... 现有逻辑
    scratchpad?.clearAll()
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 运行全部测试**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.*"`
Expected: ALL TESTS PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git add app/src/main/java/com/example/workspace/AgentExecutionLoops.kt
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "feat(workspace): integrate Scratchpad and ProgressSummarizer"
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

在设备上运行 app，测试 workspace 功能：
1. 创建团队，提交任务
2. Orchestrator 分配任务给子 Agent
3. 子 Agent 执行任务并汇报结果
4. Scratchpad 工具可正常使用
5. Agent 摘要正常注入
6. 工作区正常完成
