# OmniChat subAgent 功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 OmniChat 添加 subAgent 功能，允许 MainAgent 通过 MCP 工具委托任务给专门的子代理异步执行。

**Architecture:** 采用 MCP 内置工具实现，新增 AgentExecutor 引擎管理任务生命周期，通过消息插入机制通知结果。新增 AgentConfig 实体存储每种代理类型的模型配置。

**Tech Stack:** Kotlin, Room Database, OkHttp, Coroutines, StateFlow

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/omnichat/data/Entities.kt` | 修改 | 新增 AgentConfig 实体 |
| `app/src/main/java/com/omnichat/data/Daos.kt` | 修改 | 新增 AgentConfigDao |
| `app/src/main/java/com/omnichat/data/AppDatabase.kt` | 修改 | 新增表 + 迁移 v38→v39 |
| `app/src/main/java/com/omnichat/data/Repository.kt` | 修改 | 新增 AgentConfig 访问方法 |
| `app/src/main/java/com/omnichat/agent/AgentPrompts.kt` | 新增 | 系统提示模板 |
| `app/src/main/java/com/omnichat/agent/AgentExecutor.kt` | 新增 | subAgent 执行引擎 |
| `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt` | 修改 | 新增 3 个工具定义 |
| `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt` | 修改 | 新增工具处理逻辑 |
| `app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt` | 修改 | 注入已完成任务摘要 |

---

## Task 1: 数据层 - AgentConfig 实体与 DAO

**Files:**
- Modify: `app/src/main/java/com/omnichat/data/Entities.kt`
- Modify: `app/src/main/java/com/omnichat/data/Daos.kt`

- [ ] **Step 1: 在 Entities.kt 末尾添加 AgentConfig 实体**

在 `McpFilePermission` 实体之后添加：

```kotlin
/**
 * subAgent 配置。存储每种代理类型的模型设置。
 *
 * agentType: "general", "researcher", "coder", "reviewer", "tester"
 * providerId: 关联的 ModelConfig.id
 * modelId: 具体模型 ID
 */
@Entity(tableName = "agent_configs")
data class AgentConfig(
    @PrimaryKey val agentType: String,
    val providerId: Long,
    val modelId: String,
    val isEnabled: Boolean = true,
    val maxConcurrency: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: 在 Daos.kt 末尾添加 AgentConfigDao**

在 `MemoryAuditDao` 之后添加：

```kotlin
@Dao
interface AgentConfigDao {
    @Query("SELECT * FROM agent_configs")
    suspend fun getAllConfigs(): List<AgentConfig>

    @Query("SELECT * FROM agent_configs WHERE agentType = :agentType LIMIT 1")
    suspend fun getConfigByType(agentType: String): AgentConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: AgentConfig)

    @Query("DELETE FROM agent_configs WHERE agentType = :agentType")
    suspend fun deleteConfigByType(agentType: String)
}
```

- [ ] **Step 3: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/data/Entities.kt app/src/main/java/com/omnichat/data/Daos.kt
git commit -m "feat(agent): add AgentConfig entity and DAO"
```

---

## Task 2: 数据库迁移 v38→v39

**Files:**
- Modify: `app/src/main/java/com/omnichat/data/AppDatabase.kt`

- [ ] **Step 1: 在 @Database entities 列表中添加 AgentConfig::class**

将 `AgentConfig::class,` 添加到 entities 数组中（在 `MemoryAuditEntry::class` 之后）：

```kotlin
@Database(
    entities = [
        // ... 现有实体 ...
        MemoryAuditEntry::class,
        AgentConfig::class,
    ],
    version = 39,
    exportSchema = false,
)
```

- [ ] **Step 2: 更新 version 为 39**

将 `version = 38` 改为 `version = 39`

- [ ] **Step 3: 添加 abstract fun agentConfigDao()**

在 DAO 声明区域添加：

```kotlin
abstract fun agentConfigDao(): AgentConfigDao
```

- [ ] **Step 4: 添加迁移脚本 MIGRATION_38_39**

在 `MIGRATION_37_38` 之后添加：

```kotlin
/** v38→v39：新增 agent_configs 表（subAgent 模型配置） */
private val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_configs (
                agentType TEXT PRIMARY KEY NOT NULL,
                providerId INTEGER NOT NULL,
                modelId TEXT NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                maxConcurrency INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 5: 在 getDatabase() 的 addMigrations 中注册 MIGRATION_38_39**

在迁移列表末尾添加 `MIGRATION_38_39`：

```kotlin
.addMigrations(
    // ... 现有迁移 ...
    MIGRATION_37_38,
    MIGRATION_38_39
)
```

- [ ] **Step 6: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/omnichat/data/AppDatabase.kt
git commit -m "feat(agent): add database migration v38→v39 for agent_configs"
```

---

## Task 3: Repository 层扩展

**Files:**
- Modify: `app/src/main/java/com/omnichat/data/Repository.kt`

- [ ] **Step 1: 在 AppRepository 类中添加 agentConfigDao**

在 DAO 初始化区域添加：

```kotlin
private val agentConfigDao = db.agentConfigDao()
```

- [ ] **Step 2: 添加 AgentConfig 访问方法**

在类的末尾添加：

```kotlin
// Agent Configs
suspend fun getAllAgentConfigs(): List<AgentConfig> = agentConfigDao.getAllConfigs()
suspend fun getAgentConfigByType(agentType: String): AgentConfig? = agentConfigDao.getConfigByType(agentType)
suspend fun upsertAgentConfig(config: AgentConfig) = agentConfigDao.upsertConfig(config)
suspend fun deleteAgentConfig(agentType: String) = agentConfigDao.deleteConfigByType(agentType)
```

- [ ] **Step 3: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/data/Repository.kt
git commit -m "feat(agent): add AgentConfig methods to Repository"
```

---

## Task 4: AgentPrompts 系统提示模板

**Files:**
- Create: `app/src/main/java/com/omnichat/agent/AgentPrompts.kt`

- [ ] **Step 1: 创建 AgentPrompts.kt 文件**

```kotlin
package com.omnichat.agent

/**
 * subAgent 系统提示模板。
 * 每种代理类型有专属的系统提示，定义其行为模式和工具使用策略。
 */
object AgentPrompts {
    val PROMPTS = mapOf(
        "general" to """You are a helpful assistant. Complete the assigned task accurately and thoroughly.

Guidelines:
- Follow the task description precisely
- Use available tools when needed
- Provide clear, structured output
- Report progress and any issues encountered""",

        "researcher" to """You are a research assistant. Your job is to gather, analyze, and synthesize information.

When researching:
- Use search_memory to find relevant historical context
- Use file_read to examine existing documents
- Organize findings clearly with headers and bullet points
- Cite sources when available
- Summarize key points and highlight important findings
- Note any gaps or uncertainties in the information""",

        "coder" to """You are a coding assistant. Your job is to write, modify, or analyze code.

When coding:
- Use file_read to understand existing code structure
- Use file_write/file_append to create or modify files
- Follow existing code style and conventions
- Add clear comments for complex logic
- Consider edge cases and error handling
- Test your changes mentally before submitting
- Report what files were modified and why""",

        "reviewer" to """You are a code reviewer. Your job is to review code and identify issues.

When reviewing:
- Check for bugs, security issues, performance problems
- Suggest improvements for readability and maintainability
- Be specific: cite file paths, line numbers, code snippets
- Prioritize findings by severity (Critical > High > Medium > Low)
- Provide actionable recommendations
- Acknowledge good patterns when you see them""",

        "tester" to """You are a test engineer. Your job is to write test cases.

When testing:
- Cover edge cases and error scenarios
- Use descriptive test names that explain the scenario
- Follow existing test patterns in the project
- Include both positive and negative tests
- Consider boundary conditions
- Mock external dependencies appropriately
- Ensure tests are deterministic and repeatable"""
    )

    /**
     * 获取指定代理类型的系统提示。
     * 如果类型不存在，返回 general 的提示。
     */
    fun getPrompt(agentType: String): String {
        return PROMPTS[agentType] ?: PROMPTS["general"]!!
    }

    /**
     * 所有支持的代理类型。
     */
    val ALL_TYPES = listOf("general", "researcher", "coder", "reviewer", "tester")
}
```

- [ ] **Step 2: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/AgentPrompts.kt
git commit -m "feat(agent): add AgentPrompts system prompt templates"
```

---

## Task 5: AgentExecutor 执行引擎

**Files:**
- Create: `app/src/main/java/com/omnichat/agent/AgentExecutor.kt`

- [ ] **Step 1: 创建 AgentExecutor.kt 文件**

```kotlin
package com.omnichat.agent

import android.content.Context
import android.util.Log
import com.omnichat.data.*
import com.omnichat.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private const val TAG = "AgentExecutor"

/** subAgent 任务状态 */
enum class AgentTaskStatus {
    PENDING,    // 等待执行
    RUNNING,    // 正在执行
    COMPLETED,  // 已完成
    FAILED,     // 执行失败
    CANCELLED   // 已取消
}

/** subAgent 任务状态快照 */
data class AgentTaskState(
    val taskId: String,
    val sessionId: Long,
    val agentType: String,
    val status: AgentTaskStatus,
    val taskDescription: String,
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null
)

/**
 * subAgent 执行引擎。
 *
 * 职责：
 * - 管理任务生命周期（创建、执行、取消、查询）
 * - 调用 LLM API 执行任务
 * - 将结果插入主会话
 * - 维护任务状态流
 *
 * 并发控制：
 * - 每种 agent 类型默认最大并行数 = 1
 * - 全局最大并行数 = 3
 */
class AgentExecutor(
    private val context: Context,
    private val repository: AppRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 任务状态管理
    private val _taskStates = MutableStateFlow<Map<String, AgentTaskState>>(emptyMap())
    val taskStates: StateFlow<Map<String, AgentTaskState>> = _taskStates.asStateFlow()

    // 运行中的任务 Job，用于取消
    private val runningJobs = ConcurrentHashMap<String, Job>()

    // 并发控制：全局最多 3 个并行任务
    private val globalSemaphore = Semaphore(3)

    // 每种 agent 类型的信号量（各自最多 1 个并行）
    private val typeSemaphores = AgentPrompts.ALL_TYPES.associateWith { Semaphore(1) }

    companion object {
        /** 任务超时时间（毫秒） */
        private const val TASK_TIMEOUT_MS = 5 * 60 * 1000L

        /** 单例 */
        @Volatile
        private var INSTANCE: AgentExecutor? = null

        fun getInstance(context: Context, repository: AppRepository): AgentExecutor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentExecutor(context.applicationContext, repository).also { INSTANCE = it }
            }
        }
    }

    /**
     * 启动 subAgent 任务。
     *
     * @param sessionId 主会话 ID，结果将插入此会话
     * @param agentType 代理类型
     * @param task 任务描述
     * @param contextStr 附加上下文
     * @param files 相关文件路径
     * @return taskId 用于追踪
     */
    fun execute(
        sessionId: Long,
        agentType: String,
        task: String,
        contextStr: String?,
        files: List<String>?
    ): String {
        val taskId = UUID.randomUUID().toString()

        // 创建初始状态
        val initialState = AgentTaskState(
            taskId = taskId,
            sessionId = sessionId,
            agentType = agentType,
            status = AgentTaskStatus.PENDING,
            taskDescription = task
        )
        updateState(taskId, initialState)

        // 启动执行协程
        val job = scope.launch {
            try {
                // 获取并发许可
                val typeSemaphore = typeSemaphores[agentType] ?: typeSemaphores["general"]!!
                if (!globalSemaphore.tryAcquire()) {
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.FAILED,
                        error = "全局并发数已达上限（3），请等待其他任务完成"
                    ))
                    return@launch
                }
                if (!typeSemaphore.tryAcquire()) {
                    globalSemaphore.release()
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.FAILED,
                        error = "该代理类型（$agentType）已有任务在执行，请等待完成"
                    ))
                    return@launch
                }

                try {
                    // 更新状态为 RUNNING
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.RUNNING,
                        startedAt = System.currentTimeMillis()
                    ))

                    // 执行任务
                    val result = executeTask(sessionId, agentType, task, contextStr, files)

                    // 更新状态为 COMPLETED
                    updateState(taskId, initialState.copy(
                        status = AgentTaskStatus.COMPLETED,
                        result = result,
                        completedAt = System.currentTimeMillis()
                    ))

                    // 插入结果消息到主会话
                    insertResultMessage(sessionId, taskId, agentType, result)

                } finally {
                    // 释放许可
                    typeSemaphore.release()
                    globalSemaphore.release()
                }
            } catch (e: CancellationException) {
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.CANCELLED,
                    error = "任务被取消"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "任务执行失败: $taskId", e)
                updateState(taskId, initialState.copy(
                    status = AgentTaskStatus.FAILED,
                    error = e.localizedMessage ?: "执行失败"
                ))
            } finally {
                runningJobs.remove(taskId)
            }
        }

        runningJobs[taskId] = job
        return taskId
    }

    /**
     * 执行单个任务的核心逻辑。
     */
    private suspend fun executeTask(
        sessionId: Long,
        agentType: String,
        task: String,
        contextStr: String?,
        files: List<String>?
    ): String = withTimeout(TASK_TIMEOUT_MS) {
        // 1. 获取模型配置
        val config = getAgentModelConfig(agentType)
            ?: throw IllegalStateException("代理类型 $agentType 未配置模型，请在设置中配置")

        // 2. 构建系统提示
        val systemPrompt = AgentPrompts.getPrompt(agentType)

        // 3. 构建用户消息
        val userMessage = buildUserMessage(task, contextStr, files)

        // 4. 调用 LLM API（非流式，等待完整结果）
        val messages = listOf(
            Message(sessionId = sessionId, role = "system", content = systemPrompt),
            Message(sessionId = sessionId, role = "user", content = userMessage)
        )

        val result = ApiClient.executeCompletion(config, systemPrompt, userMessage)
            ?: throw IllegalStateException("LLM 调用返回空结果")

        result
    }

    /**
     * 获取指定代理类型的模型配置。
     * 如果未配置，回退到主 provider。
     */
    private suspend fun getAgentModelConfig(agentType: String): ModelConfig? {
        // 尝试获取专用配置
        val agentConfig = repository.getAgentConfigByType(agentType)
        if (agentConfig != null && agentConfig.isEnabled) {
            val provider = repository.getConfigById(agentConfig.providerId)
            if (provider != null) {
                return provider.copy(selectedModelId = agentConfig.modelId)
            }
        }

        // 回退到主 provider
        val defaultProvider = repository.getDefaultProvider() ?: return null
        return defaultProvider
    }

    /**
     * 构建发送给 subAgent 的用户消息。
     */
    private fun buildUserMessage(task: String, contextStr: String?, files: List<String>?): String {
        return buildString {
            appendLine("## 任务")
            appendLine(task)
            appendLine()

            if (!contextStr.isNullOrBlank()) {
                appendLine("## 上下文")
                appendLine(contextStr)
                appendLine()
            }

            if (!files.isNullOrEmpty()) {
                appendLine("## 相关文件")
                files.forEach { appendLine("- $it") }
                appendLine()
            }
        }
    }

    /**
     * 将结果插入主会话。
     */
    private suspend fun insertResultMessage(
        sessionId: Long,
        taskId: String,
        agentType: String,
        result: String
    ) {
        val msg = Message(
            sessionId = sessionId,
            role = "agent_result",
            content = result,
            toolCallId = taskId,
            toolCallsJson = JSONObject().apply {
                put("agentType", agentType)
                put("status", "completed")
                put("completedAt", System.currentTimeMillis())
            }.toString()
        )
        repository.insertMessage(msg)
        Log.i(TAG, "任务结果已插入会话: sessionId=$sessionId, taskId=$taskId")
    }

    /**
     * 取消正在执行的任务。
     */
    fun cancel(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
    }

    /**
     * 获取任务状态。
     */
    fun getStatus(taskId: String): AgentTaskState? {
        return _taskStates.value[taskId]
    }

    /**
     * 获取指定会话的所有任务。
     */
    fun getTasksForSession(sessionId: Long): List<AgentTaskState> {
        return _taskStates.value.values.filter { it.sessionId == sessionId }
    }

    /**
     * 获取指定会话已完成的任务（用于注入系统提示）。
     */
    fun getCompletedTasksForSession(sessionId: Long): List<AgentTaskState> {
        return _taskStates.value.values.filter {
            it.sessionId == sessionId && it.status == AgentTaskStatus.COMPLETED
        }
    }

    /**
     * 更新任务状态。
     */
    private fun updateState(taskId: String, state: AgentTaskState) {
        _taskStates.value = _taskStates.value + (taskId to state)
    }

    /**
     * 清理已完成的任务状态（可选，用于内存管理）。
     */
    fun clearCompletedTasks() {
        _taskStates.value = _taskStates.value.filterValues {
            it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.FAILED && it.status != AgentTaskStatus.CANCELLED
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnichat/agent/AgentExecutor.kt
git commit -m "feat(agent): add AgentExecutor for subAgent task execution"
```

---

## Task 6: MCP 工具定义

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt`

- [ ] **Step 1: 在 builtinTools 列表中添加 3 个新工具**

在 `list_mcp_tool_groups` 工具之后，`set_tool_display_mode` 工具之前添加：

```kotlin
// ── subAgent 任务委托工具 ──────────────────────────────────────────
McpTool(
    serverId = BUILTIN_SERVER_ID,
    serverName = BUILTIN_SERVER_NAME,
    name = "delegate_task",
    description = """将任务委托给专门的子代理异步执行。

可用代理类型：
- general: 通用任务，适合不确定分类的工作
- researcher: 信息搜索、资料整理、网络检索
- coder: 代码编写、文件创建/修改
- reviewer: 代码审查、质量检查、问题发现
- tester: 测试用例编写、验证逻辑

任务将在后台执行，完成后结果会插入当前会话。返回一个 taskId 用于追踪。""",
    inputSchema = schema {
        prop("agent_type", "string", "代理类型") {
            enum("general", "researcher", "coder", "reviewer", "tester")
        }
        prop("task", "string", "任务描述。清晰说明目标、约束、期望输出格式。")
        prop("context", "string", "可选。附加上下文：相关文件路径、代码片段、背景信息。")
        prop("files", "array", "可选。需要操作的文件路径列表。") {
            items { }
        }
        required("agent_type", "task")
    }
),
McpTool(
    serverId = BUILTIN_SERVER_ID,
    serverName = BUILTIN_SERVER_NAME,
    name = "check_task_status",
    description = "查询委托任务的执行状态和结果。",
    inputSchema = schema {
        prop("task_id", "string", "delegate_task 返回的任务 ID")
        required("task_id")
    }
),
McpTool(
    serverId = BUILTIN_SERVER_ID,
    serverName = BUILTIN_SERVER_NAME,
    name = "list_agent_tasks",
    description = "列出当前会话中所有 subAgent 任务及其状态。",
    inputSchema = schema {}
),
```

- [ ] **Step 2: 更新 builtinToolGroups 映射**

在 `builtinToolGroups` map 中添加：

```kotlin
"delegate_task" to "core",
"check_task_status" to "core",
"list_agent_tasks" to "core",
```

- [ ] **Step 3: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt
git commit -m "feat(agent): add MCP tool definitions for subAgent"
```

---

## Task 7: BuiltinToolHandler 工具处理逻辑

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: 在 handleBuiltinTool 的 when 分支中添加新工具处理**

在 `"set_tool_display_mode"` 分支之后添加：

```kotlin
"delegate_task" -> handleDelegateTask(context, arguments, sessionId)
"check_task_status" -> handleCheckTaskStatus(context, arguments, sessionId)
"list_agent_tasks" -> handleListAgentTasks(context, arguments, sessionId)
```

- [ ] **Step 2: 在文件末尾（errorResponse 函数之前）添加处理函数**

```kotlin
// ── subAgent 任务委托工具 ──────────────────────────────────────────────

private suspend fun handleDelegateTask(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
    if (sessionId == null) {
        return errorResponse(str(context, R.string.tool_agent_no_session))
    }

    val agentType = arguments.optString("agent_type").trim()
    val task = arguments.optString("task").trim()
    val contextStr = arguments.optString("context").takeIf { it.isNotBlank() }
    val filesArray = arguments.optJSONArray("files")
    val files = if (filesArray != null) {
        (0 until filesArray.length()).map { filesArray.optString(it) }.filter { it.isNotBlank() }
    } else null

    if (agentType.isEmpty()) {
        return errorResponse(str(context, R.string.tool_agent_type_empty))
    }
    if (agentType !in AgentPrompts.ALL_TYPES) {
        return errorResponse(str(context, R.string.tool_agent_type_invalid, agentType, AgentPrompts.ALL_TYPES.joinToString(", ")))
    }
    if (task.isEmpty()) {
        return errorResponse(str(context, R.string.tool_agent_task_empty))
    }

    val repository = getRepository(context)
    val executor = com.omnichat.agent.AgentExecutor.getInstance(context, repository)

    val taskId = executor.execute(sessionId, agentType, task, contextStr, files)

    return successResponse(str(context, R.string.tool_agent_delegated, agentType, taskId, taskId))
}

private fun handleCheckTaskStatus(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
    val taskId = arguments.optString("task_id").trim()
    if (taskId.isEmpty()) {
        return errorResponse(str(context, R.string.tool_agent_task_id_empty))
    }

    val repository = getRepository(context)
    val executor = com.omnichat.agent.AgentExecutor.getInstance(context, repository)
    val state = executor.getStatus(taskId)

    if (state == null) {
        return errorResponse(str(context, R.string.tool_agent_task_not_found, taskId))
    }

    val statusText = when (state.status) {
        com.omnichat.agent.AgentTaskStatus.PENDING -> str(context, R.string.tool_agent_status_pending)
        com.omnichat.agent.AgentTaskStatus.RUNNING -> str(context, R.string.tool_agent_status_running)
        com.omnichat.agent.AgentTaskStatus.COMPLETED -> str(context, R.string.tool_agent_status_completed)
        com.omnichat.agent.AgentTaskStatus.FAILED -> str(context, R.string.tool_agent_status_failed)
        com.omnichat.agent.AgentTaskStatus.CANCELLED -> str(context, R.string.tool_agent_status_cancelled)
    }

    val text = buildString {
        appendLine(str(context, R.string.tool_agent_status_header, taskId))
        appendLine(str(context, R.string.tool_agent_status_type, state.agentType))
        appendLine(str(context, R.string.tool_agent_status_status, statusText))
        appendLine(str(context, R.string.tool_agent_status_task, state.taskDescription.take(100)))
        if (state.startedAt != null) {
            appendLine(str(context, R.string.tool_agent_status_started, java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.startedAt))))
        }
        if (state.completedAt != null) {
            appendLine(str(context, R.string.tool_agent_status_completed_at, java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.completedAt))))
        }
        if (state.error != null) {
            appendLine(str(context, R.string.tool_agent_status_error, state.error))
        }
        if (state.result != null) {
            appendLine()
            appendLine(str(context, R.string.tool_agent_status_result))
            appendLine(state.result)
        }
    }

    return successResponse(text.trimEnd())
}

private fun handleListAgentTasks(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
    if (sessionId == null) {
        return errorResponse(str(context, R.string.tool_agent_no_session))
    }

    val repository = getRepository(context)
    val executor = com.omnichat.agent.AgentExecutor.getInstance(context, repository)
    val tasks = executor.getTasksForSession(sessionId)

    if (tasks.isEmpty()) {
        return successResponse(str(context, R.string.tool_agent_list_empty))
    }

    val text = buildString {
        appendLine(str(context, R.string.tool_agent_list_header, tasks.size))
        appendLine()
        tasks.forEachIndexed { i, state ->
            val statusIcon = when (state.status) {
                com.omnichat.agent.AgentTaskStatus.PENDING -> "⏳"
                com.omnichat.agent.AgentTaskStatus.RUNNING -> "🔄"
                com.omnichat.agent.AgentTaskStatus.COMPLETED -> "✅"
                com.omnichat.agent.AgentTaskStatus.FAILED -> "❌"
                com.omnichat.agent.AgentTaskStatus.CANCELLED -> "🚫"
            }
            appendLine("$statusIcon ${i + 1}. [${state.agentType}] ${state.taskDescription.take(50)}...")
            appendLine("   taskId: ${state.taskId}")
            appendLine("   status: ${state.status}")
            if (state.error != null) {
                appendLine("   error: ${state.error}")
            }
        }
    }

    return successResponse(text.trimEnd())
}
```

- [ ] **Step 3: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（会有字符串资源缺失警告，下一步添加）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/mcp/BuiltinToolHandler.kt
git commit -m "feat(agent): add tool handlers for delegate_task, check_task_status, list_agent_tasks"
```

---

## Task 8: 字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: 在 values/strings.xml 中添加英文字符串**

在文件末尾（`</resources>` 之前）添加：

```xml
<!-- subAgent 工具 -->
<string name="tool_agent_no_session">No active session. Cannot delegate task.</string>
<string name="tool_agent_type_empty">Agent type is required.</string>
<string name="tool_agent_type_invalid">Invalid agent type: %1$s. Valid types: %2$s</string>
<string name="tool_agent_task_empty">Task description is required.</string>
<string name="tool_agent_delegated">Task delegated to %1$s agent. taskId: %2$s\n\nUse check_task_status(\"%3$s\") to check progress, or wait for result to appear in the session.</string>
<string name="tool_agent_task_id_empty">Task ID is required.</string>
<string name="tool_agent_task_not_found">Task not found: %1$s</string>
<string name="tool_agent_status_pending">Pending</string>
<string name="tool_agent_status_running">Running</string>
<string name="tool_agent_status_completed">Completed</string>
<string name="tool_agent_status_failed">Failed</string>
<string name="tool_agent_status_cancelled">Cancelled</string>
<string name="tool_agent_status_header">Task Status: %1$s</string>
<string name="tool_agent_status_type">Agent Type: %1$s</string>
<string name="tool_agent_status_status">Status: %1$s</string>
<string name="tool_agent_status_task">Task: %1$s</string>
<string name="tool_agent_status_started">Started: %1$s</string>
<string name="tool_agent_status_completed_at">Completed: %1$s</string>
<string name="tool_agent_status_error">Error: %1$s</string>
<string name="tool_agent_status_result">Result:</string>
<string name="tool_agent_list_empty">No subAgent tasks in this session.</string>
<string name="tool_agent_list_header">subAgent Tasks (%1$d):</string>
```

- [ ] **Step 2: 在 values-zh-rCN/strings.xml 中添加中文字符串**

在文件末尾（`</resources>` 之前）添加：

```xml
<!-- subAgent 工具 -->
<string name="tool_agent_no_session">没有活跃会话，无法委托任务。</string>
<string name="tool_agent_type_empty">代理类型不能为空。</string>
<string name="tool_agent_type_invalid">无效的代理类型: %1$s。有效类型: %2$s</string>
<string name="tool_agent_task_empty">任务描述不能为空。</string>
<string name="tool_agent_delegated">任务已委托给 %1$s 代理。taskId: %2$s\n\n使用 check_task_status(\"%3$s\") 查询进度，或等待结果自动出现在会话中。</string>
<string name="tool_agent_task_id_empty">任务 ID 不能为空。</string>
<string name="tool_agent_task_not_found">任务不存在: %1$s</string>
<string name="tool_agent_status_pending">等待中</string>
<string name="tool_agent_status_running">执行中</string>
<string name="tool_agent_status_completed">已完成</string>
<string name="tool_agent_status_failed">失败</string>
<string name="tool_agent_status_cancelled">已取消</string>
<string name="tool_agent_status_header">任务状态: %1$s</string>
<string name="tool_agent_status_type">代理类型: %1$s</string>
<string name="tool_agent_status_status">状态: %1$s</string>
<string name="tool_agent_status_task">任务: %1$s</string>
<string name="tool_agent_status_started">开始时间: %1$s</string>
<string name="tool_agent_status_completed_at">完成时间: %1$s</string>
<string name="tool_agent_status_error">错误: %1$s</string>
<string name="tool_agent_status_result">结果:</string>
<string name="tool_agent_list_empty">当前会话没有 subAgent 任务。</string>
<string name="tool_agent_list_header">subAgent 任务 (%1$d):</string>
```

- [ ] **Step 3: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(agent): add string resources for subAgent tools"
```

---

## Task 9: ChatViewModel 集成

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: 在 ChatViewModel 中添加 AgentExecutor 引用**

在 `private val memoryEngine` 之后添加：

```kotlin
private val agentExecutor = com.omnichat.agent.AgentExecutor.getInstance(application, repository)
```

- [ ] **Step 2: 在 generateSystemPrompt 函数中注入已完成任务摘要**

在 `generateSystemPrompt` 函数的 `finalSystemPrompt +=` 语句块中，在 `<!-- MEMORY SEARCH HINT: ... -->` 之后添加：

```kotlin
// 注入已完成的 subAgent 任务摘要
val sessionId = _selectedSessionId.value
if (sessionId != null) {
    val completedTasks = agentExecutor.getCompletedTasksForSession(sessionId)
    if (completedTasks.isNotEmpty()) {
        finalSystemPrompt += "\n\n<!-- COMPLETED SUBAGENT TASKS -->"
        finalSystemPrompt += "\n以下子代理任务已完成，结果可供参考：\n"
        completedTasks.forEach { task ->
            val resultPreview = task.result?.take(200) ?: "(无结果)"
            finalSystemPrompt += "- [${task.agentType}] ${task.taskDescription.take(50)}... (taskId: ${task.taskId})\n"
            finalSystemPrompt += "  结果摘要: $resultPreview\n"
        }
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(agent): integrate AgentExecutor into ChatViewModel"
```

---

## Task 10: 最终验证与构建

- [ ] **Step 1: 完整构建验证**

Run: `cd E:/omnichat && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行单元测试**

Run: `cd E:/omnichat && ./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 3: 最终提交**

```bash
git add -A
git commit -m "feat(agent): complete subAgent feature implementation

- Add AgentConfig entity for per-agent model configuration
- Add AgentExecutor for async task execution
- Add 3 MCP tools: delegate_task, check_task_status, list_agent_tasks
- Integrate with ChatViewModel for result injection
- Support 5 agent types: general, researcher, coder, reviewer, tester"
```

---

## Self-Review Checklist

**1. Spec Coverage:**
- [x] AgentConfig 实体 → Task 1
- [x] 数据库迁移 → Task 2
- [x] Repository 方法 → Task 3
- [x] AgentPrompts 模板 → Task 4
- [x] AgentExecutor 引擎 → Task 5
- [x] MCP 工具定义 → Task 6
- [x] 工具处理逻辑 → Task 7
- [x] 字符串资源 → Task 8
- [x] ChatViewModel 集成 → Task 9

**2. Placeholder Scan:**
- [x] 无 TBD/TODO
- [x] 所有代码步骤包含完整代码
- [x] 所有命令包含预期输出

**3. Type Consistency:**
- [x] AgentTaskState 在 AgentExecutor.kt 和 BuiltinToolHandler.kt 中引用一致
- [x] AgentPrompts.ALL_TYPES 与工具定义中的 enum 一致
- [x] 数据库迁移版本号正确（38→39）
