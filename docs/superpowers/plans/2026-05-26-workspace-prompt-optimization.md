# Workspace Prompt & Runtime Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix Sub-Agent "fake completion" and Orchestrator infinite polling by optimizing prompts, adding TaskList progress tracking, and implementing runtime verification.

**Architecture:** Four-layer defense — prompt prevention (Sub-Agent + Orchestrator), runtime auto-verification, and UI progress display. Changes touch 7 files across workspace core and UI layers.

**Tech Stack:** Kotlin, Android Compose, Coroutines, StateFlow

**Design Spec:** `docs/superpowers/specs/2026-05-26-workspace-prompt-optimization-design.md`

---

## File Map

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/example/workspace/AgentLifecycle.kt` | Sub-Agent system prompt (`DEFAULT_TEAMMATE_PROMPT`) |
| `app/src/main/java/com/example/workspace/AgentContext.kt` | Orchestrator system prompt (`ORCHESTRATOR_SYSTEM_PROMPT`) |
| `app/src/main/java/com/example/workspace/OrchestratorTools.kt` | peer_message error handling (verify existing behavior) |
| `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt` | Auto-verification after task completion |
| `app/src/main/java/com/example/workspace/WorkspaceModels.kt` | `AgentTaskStep`, `AgentTaskProgress` data classes |
| `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt` | TaskList parsing, `_agentTaskProgress` StateFlow |
| `app/src/main/java/com/example/ui/screens/AgentTabBar.kt` | Progress display in tab bar |

---

### Task 1: Sub-Agent Prompt Optimization

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentLifecycle.kt:21-76`

- [ ] **Step 1: Rewrite `DEFAULT_TEAMMATE_PROMPT`**

Replace the entire `DEFAULT_TEAMMATE_PROMPT` constant with the following. The key additions are: tool usage iron rule (1.1), TaskList planning (1.2), multi-file workflow (1.3), enhanced self-check (1.4), and peer_message name guidance (1.5).

```kotlin
internal const val DEFAULT_TEAMMATE_PROMPT = """
你是一个多 Agent 工作区中的子 Agent。你的职责是：
1. 只执行分配给你的具体子任务，不要自行扩展任务范围。
2. 使用可用的工具完成工作。
3. 任务完成后，用简洁的文字汇报执行结果，然后停止。
4. 如果遇到无法解决的问题，明确说明原因和阻塞点。

## 任务结构说明

你收到的任务消息通常包含以下部分：
- **你的任务** / 任务主体：这是你必须完成的核心工作，严格按此执行
- **背景（仅供参考）**：用户原始需求，帮助你理解上下文，但不是你的任务范围
- **角色说明**：你的专业角色定位（如有）
- **完成标准**：判断任务完成的标准

## 必须使用工具执行操作

当任务要求你创建文件、读取文件、执行命令等操作时，
你必须立即调用相应的工具完成。不要回复"我马上创建"、"接下来我会..."之类的文本
而不实际调用工具。每次回复中如果涉及操作，必须包含至少一个 tool_call。
如果你说"我要做 X"，那 X 必须在同一个回复中通过工具调用完成。

## 任务分解与进度跟踪

收到任务后，你必须先制定执行计划，再开始执行：

1. **规划阶段**：分析任务要求，将任务拆解为具体步骤，以结构化格式输出：
   任务计划：
   - [ ] 步骤 1：创建 index.html（主页面结构）
   - [ ] 步骤 2：创建 style.css（样式文件）
   - [ ] 步骤 3：创建 script.js（游戏逻辑）
   - [ ] 步骤 4：验证所有文件已创建且引用正确

2. **执行阶段**：每完成一步，用工具调用标记完成：
   ✅ 步骤 1 完成：index.html 已创建（4236 字节）

3. **完成标准**：所有步骤标记为 ✅ 后，才能报告任务完成。
   如果某个步骤失败，说明失败原因，不要跳过。

⚠️ 不要跳过规划阶段：即使任务看起来简单，也必须先列出步骤再执行。
这能帮助你不会遗漏任何要求。

## 多文件创建流程

如果任务要求创建多个文件（如 HTML + CSS + JS），
你必须在一个 turn 内依次创建所有文件。不要只创建第一个文件就停止。
工作流程：
1. 创建第一个文件（调用 write_file）
2. 工具返回成功后，立即创建第二个文件（再次调用 write_file）
3. 重复直到所有文件创建完成
4. 最后用 list_directory 确认所有文件已存在

## 关键行为准则

⚠️ 完成前必须逐项验证：在报告"任务完成"之前，你必须：
1. 逐项检查任务描述中的每一个要求是否都已满足
2. 如果任务要求创建文件，用 read_file 或 list_directory 确认文件确实存在且内容正确
3. 如果任务要求修改多个位置，确认所有位置都已修改
4. 不要只做了任务的一部分就报告完成
5. 不要仅凭自己的记忆判断完成状态，必须通过工具调用验证

🚫 只做自己的任务：
- 你只负责执行分配给你的任务，不要执行其他 Agent 的任务
- 如果任务描述中提到了其他 Agent 的名字或职责（如 "Agent B 负责..."），那是其他 Agent 的工作，不是你的
- 不要因为看到任务背景中提到了多个子任务就试图完成所有子任务
- 只做「你的任务」部分明确要求的内容

不要越权：严格按照任务描述执行，不要做任务之外的事。

结果要自包含：汇报结果时，把关键数据直接写在回复里，不要只说"已生成报告文件，请查看"。

## Sub-Agent 间协作（peer_message 工具）

你可以使用 peer_message 工具与其他 Sub-Agent 直接通信：
- 点对点：peer_message(to: "researcher", message: "请把分析结果发给我")
- 广播：peer_message(to: "*", message: "我已完成数据库迁移，请更新相关代码")

⚠️ 使用正确的 Agent 名称：发送 peer_message 时，to 参数必须使用
任务上下文中明确给出的 Agent 名称（如 "CodeWriter"），不要自行猜测或使用
简称（如 "coder"）。如果名称不存在，工具会返回错误，错误信息中会列出
当前可用的 Agent 名称，请使用正确的名称重试。

⚠️ peer_message 失败时的处理：如果 peer_message 返回 Error（如目标 Agent 不存在），
不要卡在那里反复重试。正确做法是：
1. 在你的任务结果中直接描述你发现的问题或需要传递的信息
2. 完成你的任务并正常汇报结果
3. Orchestrator 会根据你的结果决定后续协调工作

任务完成后必须停止：完成任务并汇报结果后，立即停止，不要继续生成额外内容，
不要等待其他 Agent 的响应，不要尝试做超出任务范围的事情。

## 文件系统环境
本设备为 Android 系统。文件系统工具的根目录为 "/"，对应设备存储根路径。
- 下载目录: /Download
- 文档目录: /Documents
- 图片目录: /Pictures

可用工具：
[MCP_TOOLS]
"""
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (or only pre-existing JDK warnings)

---

### Task 2: Orchestrator Prompt Optimization

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentContext.kt:112-260`

- [ ] **Step 1: Update `ORCHESTRATOR_SYSTEM_PROMPT`**

Three modifications to the existing prompt:

**1a.** Replace the "结果完整性验证" section (lines 233-239) with:

```
## 结果完整性验证（核心铁律）

收到 Sub-Agent 的 <task-notification> 后，你必须验证结果是否完整：
- 检查 Sub-Agent 的输出是否完成了任务中要求的所有事项
- 如果任务要求创建多个文件/组件，用 list_directory 验证文件是否真的存在
- 如果结果明显不完整（例如要求创建3个文件但只提到了1个），
  使用 continue_conversation 追问或创建新的 Agent 补完
- **不要因为 Agent 报告 "completed" 就盲目相信任务已完成**
- **验证方法**：调用 list_directory 检查目录内容，调用 read_file 检查文件前几行，
  确认输出与预期一致

**常见陷阱**：Sub-Agent 说"我马上创建文件"但实际没有调用工具。
如果你在结果中看到"接下来我会..."、"现在开始创建..."之类的文本
但没有看到文件创建成功的证据，说明任务未完成，必须追问。
```

**1b.** Insert before the "重要规则" section (line 220):

```
## 干预时机

当 Sub-Agent 执行时间过长或表现异常时，你应当适时干预：
- **继续等待**：Agent 正在流式输出、工具返回结果正常、只是任务本身耗时较长
- **追问进度**：Agent 超过 1 分钟没有新消息，用 continue_conversation 询问进度
- **接管执行**：Agent 反复说"我马上执行"但不调用工具（超过 2 次），
  或 Agent 报告完成但文件不存在，考虑自己直接执行或创建新 Agent
- **不要无限轮询**：不要反复调用 list_directory 等待文件出现。
  如果 Agent 报告完成但文件不存在，直接用 continue_conversation 追问，
  而不是自己轮询检查
```

**1c.** Add after the existing "✅ 正确格式（多文件创建）" example (line 192):

```
✅ 正确格式（包含完成标准的多文件任务）：
在 /Download/sicbo-web/ 目录下创建一个骰宝网页游戏，包含以下文件：
1. index.html - 主页面，包含骰子展示区、下注区、历史记录
2. style.css - 深色主题样式，骰子动画
3. script.js - 游戏逻辑（掷骰、大小判定、余额管理）
完成标准：1) 三个文件都已创建 2) HTML 引用了 CSS 和 JS 3) 游戏可正常运行
注意：请依次创建所有文件，不要只创建第一个就停止。
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 3: peer_message Error Enhancement

**Files:**
- Modify: `app/src/main/java/com/example/workspace/OrchestratorTools.kt:296-298`

Note: `TeamManager.sendPeerMessage()` at line 373 **already includes** available agent names in the error message. The current code in `OrchestratorTools.kt` passes through this error with an additional hint. Verify this works correctly and enhance the hint text.

- [ ] **Step 1: Update the error hint in `handlePeerMessageTool`**

In `OrchestratorTools.kt`, replace lines 296-298:

```kotlin
// Current:
if (result.startsWith("Error:")) {
    return "$result\n\n提示：如果你需要传递问题或结果给其他 Agent，请在你的回复中直接描述发现的问题，Orchestrator 会负责协调后续工作。你可以直接完成当前任务并在结果中说明需要传递的信息。"
}
```

With:

```kotlin
if (result.startsWith("Error:")) {
    return "$result\n\n提示：请使用上方列出的正确 Agent 名称重试。如果不需要传递信息给其他 Agent，请在你的任务结果中直接描述发现的问题，Orchestrator 会负责协调后续工作。"
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 4: Runtime Auto-Verification

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt:30-36` (constructor)
- Modify: `app/src/main/java/com/example/workspace/AgentExecutionLoops.kt:268-369` (executeTask)
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt` (pass mcpRuntimeManager)

- [ ] **Step 1: Add `mcpRuntimeManager` parameter to `AgentExecutionLoops`**

In `AgentExecutionLoops.kt`, add the parameter to the constructor:

```kotlin
class AgentExecutionLoops(
    private val messageBus: MessageBus,
    private val taskManager: TaskManager,
    private val lifecycle: AgentLifecycle,
    private val mcpRuntimeManager: com.example.mcp.McpRuntimeManager,
    private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
    private val onError: (message: String) -> Unit,
) {
```

- [ ] **Step 2: Add `verifyTaskFiles` helper function**

Add this function inside `AgentExecutionLoops` class, before `executeTask`:

```kotlin
/**
 * 从任务描述中提取文件路径并验证是否存在。
 *
 * 扫描任务文本中的文件路径模式（如 /path/to/file.ext），
 * 调用 list_directory 检查父目录中是否包含目标文件。
 *
 * @return 验证结果摘要，如果任务不涉及文件操作则返回 null
 */
private suspend fun verifyTaskFiles(taskPrompt: String): String? {
    // 匹配文件路径模式：/开头的路径中包含扩展名的部分
    val filePathPattern = Regex("""(/[a-zA-Z0-9_/]+\.[a-zA-Z]{1,10})""")
    val filePaths = filePathPattern.findAll(taskPrompt)
        .map { it.groupValues[1] }
        .distinct()
        .filter { path ->
            // 过滤掉明显的非文件路径（如 /v1/chat/completions）
            val segments = path.trim('/').split('/')
            segments.size >= 2 && !path.startsWith("/v1/") && !path.startsWith("/api/")
        }
        .toList()

    if (filePaths.isEmpty()) return null

    val results = mutableListOf<String>()
    for (filePath in filePaths) {
        val parentDir = filePath.substringBeforeLast('/')
        val fileName = filePath.substringAfterLast('/')
        if (parentDir.isEmpty() || fileName.isEmpty()) continue

        try {
            val listResult = mcpRuntimeManager.callTool("list_directory", org.json.JSONObject().apply {
                put("path", parentDir)
            })
            val content = listResult.optString("content", "")
            val exists = content.contains(fileName)
            results.add("$fileName ${if (exists) "✅" else "❌ 不存在"}")
        } catch (e: Exception) {
            results.add("$fileName ⚠️ 验证失败: ${e.message}")
        }
    }

    return if (results.isEmpty()) null else results.joinToString(", ")
}
```

- [ ] **Step 3: Call verification in `executeTask` after `runTurn` returns**

In `executeTask`, after `taskCompleted = true` (line 296) and before the `if (temateCtx?.isCurrentTurnAborted == true)` check, add:

```kotlin
// runTurn 正常返回即视为任务执行完毕
taskCompleted = true

// 自动验证：检查任务中提到的文件是否真的存在
val fileVerification = verifyTaskFiles(taskPrompt)

if (teammateCtx?.isCurrentTurnAborted == true) {
    // ... existing abort handling ...
} else {
    // ... existing result collection ...
    // After resultSummary is set, append verification:
    if (fileVerification != null) {
        resultSummary += "\n\n【自动验证】$fileVerification"
    }
}
```

The exact insertion point is after line 296 (`taskCompleted = true`) — add the `verifyTaskFiles` call there, then append to `resultSummary` in the else branch after `collectAgentResult`.

- [ ] **Step 4: Pass `mcpRuntimeManager` from `TeamManager`**

In `TeamManager.kt`, find where `AgentExecutionLoops` is instantiated and add the `mcpRuntimeManager` parameter:

```kotlin
private val executionLoops = AgentExecutionLoops(
    messageBus = messageBus,
    taskManager = taskManager,
    lifecycle = lifecycle,
    mcpRuntimeManager = mcpRuntimeManager,  // ADD THIS
    onAgentStatusChanged = onAgentStatusChanged,
    onError = onError,
)
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 5: TaskList Data Model

**Files:**
- Modify: `app/src/main/java/com/example/workspace/WorkspaceModels.kt`

- [ ] **Step 1: Add `AgentTaskStep` and `AgentTaskProgress` data classes**

Add at the end of `WorkspaceModels.kt`, before the closing of the file:

```kotlin
// ═══════════════════════════════════════════════════════════════════════════════
// Sub-Agent TaskList 进度跟踪
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Sub-Agent 任务计划中的单个步骤。
 *
 * 由 Sub-Agent 在规划阶段自动生成，格式为 `- [ ] 描述` 或 `- [x] 描述`。
 *
 * @property description 步骤描述（如 "创建 index.html"）
 * @property isCompleted 是否已完成
 */
data class AgentTaskStep(
    val description: String,
    val isCompleted: Boolean,
)

/**
 * Sub-Agent 的任务进度。
 *
 * 从 Sub-Agent 的流式输出中解析 TaskList 格式，实时更新进度。
 *
 * @property agentName Agent 名称
 * @property steps 任务步骤列表
 */
data class AgentTaskProgress(
    val agentName: String,
    val steps: List<AgentTaskStep>,
) {
    val completedCount: Int get() = steps.count { it.isCompleted }
    val totalCount: Int get() = steps.size
    val isAllCompleted: Boolean get() = steps.isNotEmpty() && steps.all { it.isCompleted }
    val progressText: String get() = "$completedCount/$totalCount"
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 6: TaskList Parsing in ViewModel

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt`

- [ ] **Step 1: Add `_agentTaskProgress` StateFlow**

In the ViewModel class, near the other StateFlow declarations (around line 30-50), add:

```kotlin
/** Sub-Agent 任务进度（从流式输出中解析 TaskList） */
private val _agentTaskProgress = MutableStateFlow<Map<String, AgentTaskProgress>>(emptyMap())
val agentTaskProgress: StateFlow<Map<String, AgentTaskProgress>> = _agentTaskProgress.asStateFlow()
```

- [ ] **Step 2: Add TaskList parsing function**

Add this function in the ViewModel class:

```kotlin
/**
 * 从 Sub-Agent 的流式输出中解析 TaskList 进度。
 *
 * 支持两种格式：
 * - `- [ ] 描述` / `- [x] 描述`（Markdown checkbox）
 * - `✅ 步骤 N 完成：描述`（完成标记）
 *
 * @param agentName Agent 名称
 * @param streamText 当前累积的流式文本
 */
private fun parseAndUpdateTaskProgress(agentName: String, streamText: String) {
    // 解析 Markdown checkbox 格式
    val checkboxPattern = Regex("""- \[([ x])] (.+)""")
    val steps = checkboxPattern.findAll(streamText).map { match ->
        AgentTaskStep(
            description = match.groupValues[2].trim(),
            isCompleted = match.groupValues[1] == "x"
        )
    }.toList()

    if (steps.isEmpty()) return

    // 检查是否有 ✅ 完成标记（补充 checkbox 的 [x]）
    val completedPattern = Regex("""✅\s*步骤\s*(\d+)\s*完成""")
    val completedStepNumbers = completedPattern.findAll(streamText)
        .map { it.groupValues[1].toIntOrNull() }
        .filterNotNull()
        .toSet()

    // 合并：checkbox 标记 + ✅ 标记
    val mergedSteps = steps.mapIndexed { index, step ->
        if (!step.isCompleted && (index + 1) in completedStepNumbers) {
            step.copy(isCompleted = true)
        } else {
            step
        }
    }

    _agentTaskProgress.update { current ->
        current.toMutableMap().apply {
            put(agentName, AgentTaskProgress(agentName = agentName, steps = mergedSteps))
        }
    }
}
```

- [ ] **Step 3: Call parsing from `onStreamChunk` callback**

In `createTeamManager()`, find the `onStreamChunk` callback (around line 1180-1195). After the existing stream buffer update logic, add:

```kotlin
onStreamChunk = { agentName, chunk ->
    // ... existing stream buffer update ...
    _agentStreamBuffers.update { current ->
        current.toMutableMap().apply {
            put(agentName, (current[agentName] ?: "") + chunk)
        }
    }
    // 解析 TaskList 进度
    parseAndUpdateTaskProgress(agentName, _agentStreamBuffers.value[agentName] ?: "")
},
```

- [ ] **Step 4: Reset progress on new task submission**

In `submitTask()` or wherever agent state is reset (look for `_agentTabs.value = emptyList()` or similar), add:

```kotlin
_agentTaskProgress.value = emptyMap()
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 7: UI Progress Display in AgentTabBar

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/AgentTabBar.kt`
- Modify: `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt`

- [ ] **Step 1: Add `agentTaskProgress` parameter to `AgentTabBar`**

Update the `AgentTabBar` composable signature:

```kotlin
@Composable
fun AgentTabBar(
    agentTabs: List<AgentTabState>,
    selectedTabIndex: Int,
    teamState: TeamState?,
    agentStatuses: Map<String, AgentStatus>,
    agentTaskProgress: Map<String, AgentTaskProgress> = emptyMap(),  // ADD THIS
    onTabSelected: (Int) -> Unit,
) {
```

Add import at top of file:
```kotlin
import com.example.workspace.AgentTaskProgress
```

- [ ] **Step 2: Display progress in tab text**

In the `agentTabs.forEachIndexed` block, find the tab text `Row` (around line 73). After the existing agent name text, add progress indicator:

Find the existing code that displays the agent name (look for `Text(tab.agentName, ...)` or similar). After it, add:

```kotlin
// TaskList 进度指示器
val progress = agentTaskProgress[tab.agentName]
if (progress != null && progress.totalCount > 0 && !tab.isOrchestrator) {
    Spacer(modifier = Modifier.width(4.dp))
    if (progress.isAllCompleted) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "任务完成",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
    } else {
        Text(
            text = progress.progressText,
            fontSize = (10 * fs).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}
```

- [ ] **Step 3: Pass `agentTaskProgress` from `WorkspaceScreen`**

In `WorkspaceScreen.kt`, collect the new StateFlow and pass it to `AgentTabBar`:

Add near the other StateFlow collections (around line 40-48):
```kotlin
val agentTaskProgress by workspaceViewModel.agentTaskProgress.collectAsStateWithLifecycle()
```

Pass to `AgentTabBar` call (around line 157):
```kotlin
AgentTabBar(
    agentTabs = agentTabs,
    selectedTabIndex = selectedTabIndex,
    teamState = teamState,
    agentStatuses = agentStatuses,
    agentTaskProgress = agentTaskProgress,  // ADD THIS
    onTabSelected = { selectedTabIndex = it },
)
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 8: Run Tests & Verify

- [ ] **Step 1: Run workspace unit tests**

Run: `./gradlew testDebugUnitTest --tests "com.example.workspace.*" 2>&1 | tail -20`
Expected: All tests pass (or only pre-existing failures unrelated to this change)

- [ ] **Step 2: Manual verification checklist**

Test the same scenario that triggered the original bug:
1. Create a workspace task: "在 /Download/sicbo-web/ 下创建一个骰宝网页游戏，包含 index.html、style.css、script.js"
2. Verify Sub-Agent generates TaskList before executing
3. Verify Sub-Agent creates all 3 files (not just 1)
4. Verify Orchestrator receives complete results
5. Verify UI shows task progress in AgentTabBar
6. Verify peer_message error includes available agent names

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentLifecycle.kt \
       app/src/main/java/com/example/workspace/AgentContext.kt \
       app/src/main/java/com/example/workspace/OrchestratorTools.kt \
       app/src/main/java/com/example/workspace/AgentExecutionLoops.kt \
       app/src/main/java/com/example/workspace/WorkspaceModels.kt \
       app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt \
       app/src/main/java/com/example/ui/screens/AgentTabBar.kt \
       app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt
git commit -m "feat(workspace): optimize prompts, add TaskList progress tracking and auto-verification

- Sub-Agent prompt: add tool usage iron rule, TaskList planning, multi-file workflow
- Orchestrator prompt: add result verification guidance, intervention timing
- Runtime: auto-verify file existence after task completion
- UI: display Sub-Agent task progress in AgentTabBar
- peer_message: enhance error hint with available agent names

Fixes: Sub-Agent 'fake completion', Orchestrator infinite polling"
```
