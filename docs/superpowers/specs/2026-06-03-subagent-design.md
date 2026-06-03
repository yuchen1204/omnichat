# OmniChat subAgent 功能设计

日期：2026-06-03
状态：✅ **已实现** (2026-06-03)

## 概述

为 OmniChat 添加 subAgent 功能，允许 MainAgent 将任务委托给专门的子代理（researcher、coder、reviewer、tester、general）异步执行。采用 MCP 内置工具实现，与现有架构无缝集成。

## 实现状态

| 组件 | 状态 | Commit |
|------|------|--------|
| AgentConfig 实体 | ✅ | d6b13a3 |
| 数据库迁移 v38→v39 | ✅ | 05ce476 |
| Repository 方法 | ✅ | 2fff1ae |
| AgentPrompts 模板 | ✅ | 0cbd2be |
| AgentExecutor 引擎 | ✅ | c68af32 |
| MCP 工具定义 | ✅ | 62afb31 |
| BuiltinToolHandler | ✅ | 783b85d |
| 字符串资源 | ✅ | fb33c30 |
| ChatViewModel 集成 | ✅ | 3203ec5 |
| 代码审查修复 | ✅ | 63bc126 |

## 核心决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 架构模式 | MCP 内置工具 | 复用现有 BuiltinToolHandler + ApiClient，最小改动 |
| Agent 类型 | 5 种预定义 | 覆盖常见场景，避免配置复杂度 |
| 模型配置 | 每类型可单独配置 | 灵活分配快速/强大模型 |
| 工具权限 | 继承主会话工具集 | 简单直接，无需额外配置 |
| 通知机制 | 消息插入 + 主动查询 | 适配 LLM 无回调限制 |

## 新增组件

### 1. 数据实体

**文件**: `app/src/main/java/com/omnichat/data/Entities.kt`

```kotlin
@Entity(tableName = "agent_configs")
data class AgentConfig(
    @PrimaryKey val agentType: String,        // "general", "researcher", "coder", "reviewer", "tester"
    val providerId: Long,                     // 关联 ModelConfig.id
    val modelId: String,                      // 具体模型 ID
    val isEnabled: Boolean = true,
    val maxConcurrency: Int = 1,              // 该类型最大并行数（V1 固定为 1）
    val createdAt: Long = System.currentTimeMillis()
)
```

### 2. 任务状态

**文件**: `app/src/main/java/com/omnichat/agent/AgentExecutor.kt`

```kotlin
enum class AgentTaskStatus {
    PENDING,    // 等待执行
    RUNNING,    // 正在执行
    COMPLETED,  // 已完成
    FAILED,     // 执行失败
    CANCELLED   // 已取消
}

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
```

### 3. AgentExecutor 引擎

**文件**: `app/src/main/java/com/omnichat/agent/AgentExecutor.kt`

职责：
- 管理任务生命周期（创建、执行、取消、查询）
- 调用 LLM API 执行任务
- 将结果插入主会话
- 维护任务状态流

核心方法：
```kotlin
class AgentExecutor(...) {
    val taskStates: StateFlow<Map<String, AgentTaskState>>

    fun execute(sessionId: Long, agentType: String, task: String, context: String?, files: List<String>?): String
    fun cancel(taskId: String)
    fun getStatus(taskId: String): AgentTaskState?
    fun getCompletedTasksForSession(sessionId: Long): List<AgentTaskState>
}
```

### 4. MCP 工具

**文件**: `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt`

新增 3 个内置工具：

#### delegate_task
```kotlin
McpTool(
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
)
```

#### check_task_status
```kotlin
McpTool(
    name = "check_task_status",
    description = "查询委托任务的执行状态和结果。",
    inputSchema = schema {
        prop("task_id", "string", "delegate_task 返回的任务 ID")
        required("task_id")
    }
)
```

#### list_agent_tasks
```kotlin
McpTool(
    name = "list_agent_tasks",
    description = "列出当前会话中所有 subAgent 任务及其状态。",
    inputSchema = schema {}
)
```

### 5. 系统提示模板

**文件**: `app/src/main/java/com/omnichat/agent/AgentPrompts.kt`

```kotlin
val AGENT_PROMPTS = mapOf(
    "general" to "You are a helpful assistant. Complete the assigned task accurately.",

    "researcher" to """You are a research assistant. Your job is to gather, analyze, and synthesize information.
When researching:
- Use search_memory to find relevant historical context
- Use file_read to examine existing documents
- Organize findings clearly with headers and bullet points
- Cite sources when available""",

    "coder" to """You are a coding assistant. Your job is to write, modify, or analyze code.
When coding:
- Use file_read to understand existing code structure
- Use file_write/file_append to create or modify files
- Follow existing code style and conventions
- Add clear comments for complex logic""",

    "reviewer" to """You are a code reviewer. Your job is to review code and identify issues.
When reviewing:
- Check for bugs, security issues, performance problems
- Suggest improvements for readability and maintainability
- Be specific: cite file paths, line numbers, code snippets
- Prioritize findings by severity""",

    "tester" to """You are a test engineer. Your job is to write test cases.
When testing:
- Cover edge cases and error scenarios
- Use descriptive test names
- Follow existing test patterns in the project
- Include both positive and negative tests"""
)
```

## 数据库变更

### 新增表：agent_configs

```sql
CREATE TABLE agent_configs (
    agentType TEXT PRIMARY KEY NOT NULL,
    providerId INTEGER NOT NULL,
    modelId TEXT NOT NULL,
    isEnabled INTEGER NOT NULL DEFAULT 1,
    maxConcurrency INTEGER NOT NULL DEFAULT 1,
    createdAt INTEGER NOT NULL
);
```

### 迁移计划

- 版本 38 → 39：新增 `agent_configs` 表
- Seed 默认配置：5 种 agent 类型均继承主 provider

## 执行流程

```
MainAgent 调用 delegate_task
    ↓
BuiltinToolHandler.handleDelegateTask()
    ↓
AgentExecutor.execute() 启动协程
    ↓
┌─────────────────────────────────────┐
│  1. 生成 taskId (UUID)              │
│  2. 创建 AgentTaskState (PENDING)   │
│  3. 获取该 agentType 的模型配置      │
│  4. 构建系统提示 (AGENT_PROMPTS)    │
│  5. 构建用户消息 (task + context)   │
│  6. 更新状态为 RUNNING              │
│  7. 调用 ApiClient.executeStreamingChat() │
│  8. 流式收集响应                     │
│  9. 更新状态为 COMPLETED            │
│  10. 插入结果消息到主会话            │
└─────────────────────────────────────┘
    ↓
返回 taskId 给 MainAgent
```

## 通知机制

### 问题
LLM 工具调用是同步阻塞的，subAgent 异步执行完成后无法主动通知 MainAgent。

### 解决方案

1. **结果直接插入主会话**
   - subAgent 完成后，结果作为 `Message(role="agent_result")` 插入数据库
   - 用户可在聊天记录中看到 subAgent 输出

2. **主动查询工具**
   - `check_task_status(taskId)` — 查询单个任务
   - `list_agent_tasks()` — 列出所有任务

3. **会话恢复时注入**
   - 用户发送新消息时，系统提示中注入已完成的任务摘要
   - MainAgent 在下一轮对话中可感知结果

### 结果消息格式

```kotlin
Message(
    sessionId = sessionId,
    role = "agent_result",
    content = result,
    toolCallId = taskId,
    toolCallsJson = """{
        "agentType": "coder",
        "status": "completed",
        "duration_ms": 12345
    }"""
)
```

## 并发控制

- 每种 agent 类型默认最大并行数 = 1
- 全局最大并行数 = 3
- 超出限制时返回错误，提示 MainAgent 等待

## 错误处理

| 场景 | 处理 |
|------|------|
| 模型配置缺失 | 返回错误，提示用户配置该 agent 类型 |
| LLM 调用失败 | 标记 FAILED，记录错误信息 |
| 超时（默认 5 分钟） | 标记 FAILED，取消协程 |
| 用户取消 | 标记 CANCELLED，清理资源 |

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `Entities.kt` | 修改 | 新增 AgentConfig 实体 |
| `Daos.kt` | 修改 | 新增 AgentConfigDao |
| `AppDatabase.kt` | 修改 | 新增表 + 迁移 |
| `Repository.kt` | 修改 | 新增 AgentConfig 访问方法 |
| `AgentExecutor.kt` | 新增 | subAgent 执行引擎 |
| `AgentPrompts.kt` | 新增 | 系统提示模板 |
| `McpRuntimeManager.kt` | 修改 | 新增 3 个工具定义 |
| `BuiltinToolHandler.kt` | 修改 | 新增工具处理逻辑 |
| `ChatViewModel.kt` | 修改 | 注入已完成任务摘要 |

## UI 影响

V1 不新增 UI 组件。后续可扩展：
- 设置页面增加 Agent 模型配置入口
- 聊天界面特殊渲染 `agent_result` 消息
- 状态栏显示后台任务数量

## 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| 并发资源耗尽 | 全局并行数限制 + 队列机制 |
| 任务丢失 | 状态持久化到内存流，支持恢复 |
| 模型配置错误 | 启动时校验，缺失时回退主模型 |
