# Interactive Pipeline 设计文档

## 概述

Interactive Pipeline 是一种增强的 Workflow 执行模式，支持：
- 步骤间双向通信（通过 MessageBus）
- 步骤可以进入 IDLE 状态等待唤醒
- 已完成的步骤可以被召回进入 REVISION 状态
- 超时机制保护（IDLE 30分钟提醒，RUNNING 10分钟超时）
- Workflow 模板系统（预定义 + 自定义）

## 用户场景示例

用户请求："帮我整理 SpaceX 从初创公司到 IPO 的历程，做成一个 HTML 页面展示出来"

执行流程：
1. MainAgent 规划步骤：Explorer → Coder → Reviewer
2. WorkflowEngine 创建三个 SubAgent（均进入 IDLE）
3. 唤醒 Explorer（IDLE → RUNNING），检索 SpaceX 信息
4. Explorer 完成，发送消息给 Coder，自己进入 IDLE
5. Coder 被唤醒（IDLE → RUNNING），创建 HTML
6. Coder 完成，发送消息给 Reviewer，自己进入 IDLE
7. Reviewer 被唤醒（IDLE → RUNNING），审查代码
8. Reviewer 发现问题，发送消息给 Coder
9. Coder 被召回（COMPLETED → REVISION），修改代码
10. Coder 修改完成，发送消息给 Reviewer
11. Reviewer 确认通过，所有步骤标记 COMPLETED

---

## Section 1: 数据结构

### WorkflowStep 扩展

```kotlin
data class WorkflowStep(
    val id: String,
    val agentType: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    val resultVariable: String? = null,
    val timeoutMs: Long? = null,         // RUNNING 超时（默认 10 分钟）
    val maxIdleMs: Long? = null,         // IDLE 超时（默认 30 分钟）
    val maxRetries: Int = 0,
    val wakeUpOnMessage: Boolean = true  // 是否监听消息并自动唤醒
)
```

### WorkflowStepStatus 扩展

```kotlin
enum class WorkflowStepStatus {
    PENDING,           // 等待开始
    IDLE,              // 等待唤醒（已创建 SubAgent，挂起中）
    RUNNING,           // 正在执行
    PENDING_REVIEW,    // 完成等待确认
    REVISION,          // 被召回修改中
    COMPLETED,         // 最终完成
    FAILED,            // 失败
    SKIPPED            // 跳过
}
```

### StepResult 扩展

```kotlin
data class StepResult(
    val stepId: String,
    val status: WorkflowStepStatus,
    val result: String? = null,
    val error: String? = null,
    val retries: Int = 0,
    val revisionCount: Int = 0,          // 被召回修改次数
    val idleTimeMs: Long = 0,            // IDLE 状态累计时长
    val lastMessageFrom: String? = null  // 最后收到的消息来源
)
```

---

## Section 2: WorkflowEngine 核心方法

### executeInteractivePipeline

```kotlin
suspend fun executeInteractivePipeline(
    context: Context,
    sessionId: Long,
    steps: List<WorkflowStep>,
    callerContext: AgentCallerContext? = null,
    workflowId: String? = null
): List<StepResult>
```

### 执行流程

1. **初始化阶段**
   - 创建所有 SubAgent（调用 `SubAgent.createIdle()`）
   - 所有步骤进入 IDLE 状态
   - 发射 WorkflowStarted 事件

2. **启动第一个步骤**
   - 唤醒 step 0 的 SubAgent
   - 状态: IDLE → RUNNING

3. **事件循环**
   - 监听 MessageBus 消息
   - 根据消息目标唤醒对应 SubAgent
   - 监听超时事件
   - 处理步骤完成/失败

4. **消息路由**
   - SubAgent 完成后发送消息: `send_agent_message(to="step:coder", content="...")`
   - WorkflowEngine 解析目标，唤醒对应步骤
   - 如果目标步骤已 COMPLETED，进入 REVISION 状态

5. **完成判定**
   - 所有步骤进入 COMPLETED 或 FAILED
   - 发射 WorkflowCompleted/WorkflowFailed 事件

### 内部状态结构

```kotlin
data class InteractivePipelineState(
    val workflowId: String,
    val steps: List<WorkflowStep>,
    val agentStates: Map<String, AgentState>,  // stepId -> AgentState
    val contextVariables: MutableMap<String, String>,
    val messageQueue: MutableList<PendingMessage>
)

data class AgentState(
    val stepId: String,
    val agentId: String,           // SubAgent 的 taskId
    val status: WorkflowStepStatus,
    val coroutineHandle: Job?,      // 协程句柄，用于挂起/恢复
    val idleSince: Long?,           // 进入 IDLE 的时间戳
    val runningSince: Long?,        // 进入 RUNNING 的时间戳
    val conversationHistory: List<JSONObject>,  // LLM 对话历史
    val idleTimeoutWarningSent: Boolean = false  // 是否已发送超时提醒
)

data class PendingMessage(
    val from: String,
    val to: String,                 // 目标 stepId
    val content: String,
    val timestamp: Long
)
```

---

## Section 3: SubAgent 挂起/唤醒机制

### 新增方法

```kotlin
object SubAgent {
    // 创建 IDLE 状态的 SubAgent（挂起等待唤醒）
    fun createIdle(
        context: Context,
        agentType: String,
        sessionId: Long,
        stepId: String
    ): IdleAgentHandle
    
    // 唤醒 IDLE 的 SubAgent，传入任务和上下文
    suspend fun wakeUp(
        handle: IdleAgentHandle,
        task: String,
        contextVariables: Map<String, String>,
        conversationHistory: List<JSONObject>? = null
    ): String
    
    // 召回已完成的 SubAgent 进入 REVISION
    suspend fun recall(
        handle: IdleAgentHandle,
        revisionPrompt: String,
        fromAgent: String
    ): String
}

data class IdleAgentHandle(
    val agentId: String,
    val agentType: String,
    val stepId: String,
    val sessionId: Long,
    val suspendPoint: CancellableContinuation<String>  // 协程挂起点
)
```

### 挂起/唤醒流程

**createIdle():**
1. 创建 SubAgent 任务记录
2. 初始化 LLM 会话（构建 system prompt）
3. 获取 globalSemaphore 许可
4. 挂起协程（suspendCoroutine），等待唤醒信号
5. 返回 IdleAgentHandle（包含挂起点引用）

**wakeUp():**
1. 通过 IdleAgentHandle 找到挂起点
2. 将任务和上下文注入协程
3. 恢复协程执行
4. SubAgent 开始 LLM + 工具循环
5. 完成后返回结果，再次挂起或结束

**recall():**
1. 类似 wakeUp，但标记为 REVISION 模式
2. 在任务前添加 `[REVISION] 之前的结果需要修改...`
3. 传入 revisionPrompt 作为修改指引

---

## Section 4: 消息路由与唤醒逻辑

### 消息处理器

```kotlin
private suspend fun startMessageListener(
    state: InteractivePipelineState,
    context: Context
) {
    while (state.status != WorkflowStatus.COMPLETED && state.status != WorkflowStatus.FAILED) {
        // 检查每个 IDLE 状态的 Agent 是否收到消息
        state.agentStates.forEach { (stepId, agentState) ->
            if (agentState.status == WorkflowStepStatus.IDLE) {
                val messages = MessageBus.readInbox(agentState.agentId)
                messages.forEach { msg ->
                    handleIncomingMessage(state, context, stepId, msg)
                }
            }
        }
        
        checkTimeouts(state)
        delay(500)  // 每 500ms 检查一次
    }
}
```

### 消息路由（含错误处理）

```kotlin
private fun handleIncomingMessage(
    state: InteractivePipelineState,
    context: Context,
    targetStepId: String,
    message: AgentMessage
) {
    val agentState = state.agentStates[targetStepId]
    
    if (agentState == null) {
        // 目标不存在，返回错误消息给发送方
        val availableTargets = state.agentStates.keys.joinToString(", ")
        val errorMsg = "目标步骤 '$targetStepId' 不存在。可用目标: $availableTargets"
        MessageBus.send(from = "workflow", to = message.from, content = errorMsg)
        
        WorkflowEventBus.emit(WorkflowEvent.MessageRoutingError(
            workflowId = state.workflowId,
            from = message.from,
            to = targetStepId,
            error = errorMsg,
            availableTargets = state.agentStates.keys.toList()
        ))
        return
    }
    
    when (agentState.status) {
        IDLE -> wakeUpAgent(state, targetStepId, message.content, message.from)
        COMPLETED -> recallAgent(state, targetStepId, message.content, message.from)
        RUNNING, REVISION -> {
            // 正在执行，消息入队等待
            state.messageQueue.add(PendingMessage(message.from, targetStepId, message.content, System.currentTimeMillis()))
        }
        else -> {
            val statusMsg = "步骤 '$targetStepId' 当前状态为 ${agentState.status}，无法接收消息"
            MessageBus.send(from = "workflow", to = message.from, content = statusMsg)
        }
    }
}
```

### 唤醒时显示消息来源

```kotlin
private fun wakeUpAgent(
    state: InteractivePipelineState,
    stepId: String,
    content: String,
    from: String
) {
    val agentState = state.agentStates[stepId]!!
    val step = state.steps.find { it.id == stepId }!!
    
    val taskWithSource = buildString {
        appendLine("收到来自 [$from] 的消息：")
        appendLine(content)
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("你的任务：${step.task}")
    }
    
    SubAgent.wakeUp(agentState.handle, taskWithSource, state.contextVariables, agentState.conversationHistory)
    
    state.agentStates[stepId] = agentState.copy(
        status = WorkflowStepStatus.RUNNING,
        runningSince = System.currentTimeMillis(),
        lastMessageFrom = from
    )
    
    WorkflowEventBus.emit(WorkflowEvent.StepWokeUp(
        workflowId = state.workflowId,
        stepId = stepId,
        fromAgent = from,
        messagePreview = content.take(100)
    ))
}
```

### 消息地址约定

```
发送格式：send_agent_message(to="step:coder", content="请修改...")

目标地址解析：
- "step:xxx" → 发送给 Workflow 中 stepId 为 xxx 的 Agent
- "workflow" → 发送给 WorkflowEngine
- "main" → 发送给 MainAgent
```

---

## Section 5: 超时处理

### IDLE 超时（30分钟）

- 发送提醒给 MainAgent
- MainAgent 决定继续等待或终止
- 回复格式：`continue:stepId` 或 `terminate:stepId`

### RUNNING 超时（10分钟）

- 自动标记步骤为 FAILED
- 通知下游依赖步骤
- 发射 StepTimeout 事件

### 超时检查逻辑

```kotlin
private fun checkTimeouts(state: InteractivePipelineState) {
    val now = System.currentTimeMillis()
    
    state.agentStates.forEach { (stepId, agentState) ->
        when (agentState.status) {
            IDLE -> {
                val idleDuration = now - (agentState.idleSince ?: now)
                val maxIdleMs = state.steps.find { it.id == stepId }?.maxIdleMs ?: 30 * 60 * 1000L
                
                if (idleDuration >= maxIdleMs && !agentState.idleTimeoutWarningSent) {
                    WorkflowEventBus.emit(WorkflowEvent.IdleTimeoutWarning(
                        workflowId = state.workflowId,
                        stepId = stepId,
                        idleDurationMs = idleDuration,
                        message = "步骤 '$stepId' 已等待 ${idleDuration / 60000} 分钟"
                    ))
                    
                    MessageBus.send(from = "workflow", to = "main",
                        content = "[IDLE超时] 步骤 '$stepId' 已等待 ${idleDuration / 60000} 分钟。\n" +
                                  "回复 'continue:$stepId' 继续等待，或 'terminate:$stepId' 终止。"
                    )
                    
                    state.agentStates[stepId] = agentState.copy(idleTimeoutWarningSent = true)
                }
            }
            
            RUNNING, REVISION -> {
                val runningDuration = now - (agentState.runningSince ?: now)
                val timeoutMs = state.steps.find { it.id == stepId }?.timeoutMs ?: 10 * 60 * 1000L
                
                if (runningDuration >= timeoutMs) {
                    handleRunningTimeout(state, stepId, runningDuration)
                }
            }
            
            else -> {}
        }
    }
}
```

---

## Section 6: WorkflowEvent 新增事件

```kotlin
sealed class WorkflowEvent {
    // 现有事件保持不变
    
    // 新增事件
    data class StepWokeUp(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val fromAgent: String,
        val messagePreview: String
    ) : WorkflowEvent()
    
    data class StepRecalled(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val fromAgent: String,
        val revisionPrompt: String
    ) : WorkflowEvent()
    
    data class StepEnteredIdle(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val agentType: String,
        val reason: String
    ) : WorkflowEvent()
    
    data class IdleTimeoutWarning(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val idleDurationMs: Long,
        val message: String
    ) : WorkflowEvent()
    
    data class StepTimeout(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val durationMs: Long,
        val error: String
    ) : WorkflowEvent()
    
    data class MessageRoutingError(
        val workflowId: String,
        val sessionId: Long,
        val from: String,
        val to: String,
        val error: String,
        val availableTargets: List<String>
    ) : WorkflowEvent()
    
    data class StepRevisionCompleted(
        val workflowId: String,
        val sessionId: Long,
        val stepId: String,
        val revisionCount: Int,
        val result: String
    ) : WorkflowEvent()
}
```

### WorkflowUiState 更新

```kotlin
data class WorkflowUiState(
    // 现有字段保持不变
    val status: WorkflowStatus,
    val steps: List<WorkflowStepUiState>,
    // 新增
    val idleWarnings: List<IdleWarningInfo> = emptyList(),
    val messageErrors: List<MessageErrorInfo> = emptyList()
)

data class WorkflowStepUiState(
    // 现有字段保持不变
    val status: WorkflowStepStatus,
    // 新增
    val idleSince: Long? = null,
    val runningSince: Long? = null,
    val revisionCount: Int = 0,
    val lastMessageFrom: String? = null,
    val lastMessagePreview: String? = null
)

data class IdleWarningInfo(
    val stepId: String,
    val idleDurationMs: Long,
    val message: String,
    val timestamp: Long
)

data class MessageErrorInfo(
    val from: String,
    val to: String,
    val error: String,
    val availableTargets: List<String>,
    val timestamp: Long
)
```

---

## Section 7: WorkflowProgressCard UI

### 状态显示对照表

| 状态 | 图标 | 颜色 | 显示文本 |
|------|------|------|---------|
| PENDING | Schedule | 灰色半透明 | "等待开始" |
| IDLE | PauseCircle | 灰色 | "IDLE (等待 X分钟)" |
| RUNNING | Sync (旋转) | 主色 | "运行中" |
| PENDING_REVIEW | HourglassTop | 橙色 | "等待确认" |
| REVISION | Edit | 橙色 | "修改中 (第N次)" |
| COMPLETED | CheckCircle | 成功色 | "完成" |
| FAILED | Cancel | 错误色 | "失败: 原因" |
| SKIPPED | SkipNext | 灰色半透明 | "已跳过" |

### IDLE 等待时长显示

```kotlin
if (step.status == WorkflowStepStatus.IDLE && step.idleSince != null) {
    val idleMinutes = (System.currentTimeMillis() - step.idleSince) / 60000
    Text(
        text = "等待 ${idleMinutes}分钟",
        fontSize = (9 * chatFs * fs).sp,
        color = contentColor.copy(alpha = 0.5f)
    )
}
```

### 超时警告显示

```kotlin
if (workflow.idleWarnings.isNotEmpty()) {
    workflow.idleWarnings.forEach { warning ->
        Surface(color = MaterialTheme.colorScheme.warningContainer) {
            Row(modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Default.Warning, modifier = Modifier.size(16.dp))
                Text(warning.message)
            }
        }
    }
}
```

### 消息路由错误显示

```kotlin
if (workflow.messageErrors.isNotEmpty()) {
    workflow.messageErrors.forEach { error ->
        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("消息发送失败: ${error.from} → ${error.to}")
                Text(error.error)
                Text("可用目标: ${error.availableTargets.joinToString(", ")}")
            }
        }
    }
}
```

---

## Section 8: Workflow 模板系统

### 模板定义

```kotlin
object WorkflowTemplates {
    
    data class Template(
        val id: String,
        val name: String,
        val description: String,
        val steps: List<TemplateStep>,
        val defaultTimeouts: TemplateTimeouts = TemplateTimeouts()
    )
    
    data class TemplateStep(
        val id: String,
        val agentType: String,
        val taskTemplate: String,  // 支持 {{param}} 占位符
        val dependsOn: List<String> = emptyList()
    )
    
    // 内置模板
    val RESEARCH_AND_REPORT = Template(
        id = "research_and_report",
        name = "研究并报告",
        description = "检索信息并生成报告",
        steps = listOf(
            TemplateStep("research", "researcher", "{{task}}"),
            TemplateStep("report", "general", "根据研究结果生成报告")
        )
    )
    
    val CODE_AND_REVIEW = Template(
        id = "code_and_review",
        name = "编码并审查",
        description = "编写代码并进行审查",
        steps = listOf(
            TemplateStep("code", "coder", "{{task}}"),
            TemplateStep("review", "reviewer", "审查代码并提供反馈")
        )
    )
    
    val FULL_DEV_CYCLE = Template(
        id = "full_dev_cycle",
        name = "完整开发周期",
        description = "研究→编码→审查的完整流程",
        steps = listOf(
            TemplateStep("research", "researcher", "研究需求相关信息"),
            TemplateStep("code", "coder", "根据研究结果实现功能"),
            TemplateStep("review", "reviewer", "审查代码质量")
        )
    )
    
    val ALL_TEMPLATES = listOf(RESEARCH_AND_REPORT, CODE_AND_REVIEW, FULL_DEV_CYCLE)
    
    fun instantiateTemplate(templateId: String, params: Map<String, String>): List<WorkflowStep>?
}
```

### run_workflow 参数扩展

```kotlin
// 新增参数
prop("template", "string", "使用预定义模板（与 steps 二选一）") {
    enum("research_and_report", "code_and_review", "full_dev_cycle")
}
prop("task", "string", "任务描述（使用模板时必填）")

// 新增模式
prop("mode", "string", "执行模式") {
    enum("pipeline", "dag", "conversational", "interactive_pipeline")
}
```

---

## Section 9: AgentPrompts 更新

### 新增 Workflow 模式指导

```kotlin
private fun basePrompt(role: String, roleGuidelines: String) = """
    |...
    |
    |## Workflow Modes
    |- You may enter **IDLE** state: wait for messages from other agents before continuing.
    |- You may be **recalled** into **REVISION** state: modify your previous work based on feedback.
    |- Use `send_agent_message(to="step:xxx", content="...")` to communicate with other workflow steps.
    |
    |## Inter-Agent Communication
    |- When complete, send message to next agent: `send_agent_message(to="step:next_id", content="...")`
    |- If you need to recall a previous agent: `send_agent_message(to="step:prev_id", content="Issues: ...")`
    |- Available targets will be listed in your initial context if part of a workflow.
    |
    |...
""".trimMargin()
```

### Reviewer Agent 特殊指导

```kotlin
"reviewer" to basePrompt(
    role = "code review",
    roleGuidelines = """
        |...
        |
        |## Workflow Communication
        |- If issues found: send message to coder step with specific feedback.
        |- If no issues: send message indicating approval.
        |- You will enter IDLE after review. If recalled, re-review the modified code.
        |
        |...
    """.trimMargin()
)
```

---

## 实现优先级

1. **Phase 1 - 数据结构与事件**
   - WorkflowStepStatus 新增状态
   - WorkflowEvent 新增事件
   - WorkflowUiState 更新

2. **Phase 2 - SubAgent 挂起/唤醒**
   - createIdle() / wakeUp() / recall()
   - IdleAgentHandle

3. **Phase 3 - WorkflowEngine**
   - executeInteractivePipeline()
   - 消息路由与唤醒逻辑
   - 超时检查

4. **Phase 4 - UI**
   - WorkflowProgressCard 状态显示
   - IDLE 等待时长
   - 超时警告与错误显示

5. **Phase 5 - 模板与提示**
   - WorkflowTemplates
   - AgentPrompts 更新
   - run_workflow 参数扩展