# Workspace Prompt & Runtime Optimization Design

**Date:** 2026-05-26
**Status:** Approved
**Scope:** Sub-Agent prompt, Orchestrator prompt, runtime verification, UI progress display

---

## Problem Statement

From test log `workspace_log_create-sicbo.txt`, three critical issues were identified:

1. **Sub-Agent "fake completion"**: CodeWriter created `index.html` but stopped — repeatedly said "我现在立即创建这两个文件" without calling any tools. `runTurn` broke on text-only response, agent reported "success" with no work done.
2. **Orchestrator infinite polling**: Orchestrator called `list_directory` ~90 times waiting for files that never appeared, burning its 50-tool-call budget twice.
3. **Name collision blocks legitimate tasks**: `assign_task` rejected task descriptions mentioning other agent names (e.g., "等待 CodeWriter 完成"), forcing the Orchestrator to rephrase 3 times.

Root causes:
- Prompt lacks explicit "must use tool calls for actions" rule
- No TaskList/progress tracking mechanism for sub-agents
- No runtime verification after sub-agent reports completion
- Orchestrator has no guidance on when to intervene vs. wait

---

## Solution Overview

Four-layer defense:

| Layer | Mechanism | Prevents |
|-------|-----------|----------|
| Prompt (Sub-Agent) | Tool usage iron rule + TaskList planning + self-check | "Said but didn't do", "Did but not all" |
| Prompt (Orchestrator) | Verification guidance + intervention timing | Infinite polling, blind trust |
| Runtime | Auto-verify file existence after task completion | Residual failures |
| UI | TaskList progress display in AgentTabBar | User can't see sub-agent progress |

---

## 1. Sub-Agent Prompt Optimization

**File:** `AgentLifecycle.kt` — `DEFAULT_TEAMMATE_PROMPT`

### 1.1 Tool Usage Iron Rule (NEW)

Insert before "关键行为准则" section:

```
**必须使用工具执行操作**：当任务要求你创建文件、读取文件、执行命令等操作时，
你必须立即调用相应的工具完成。不要回复"我马上创建"、"接下来我会..."之类的文本
而不实际调用工具。每次回复中如果涉及操作，必须包含至少一个 tool_call。
如果你说"我要做 X"，那 X 必须在同一个回复中通过工具调用完成。
```

### 1.2 TaskList Planning & Progress Tracking (NEW)

Insert after "任务结构说明" section:

```
**任务分解与进度跟踪**：

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

**⚠️ 不要跳过规划阶段**：即使任务看起来简单，也必须先列出步骤再执行。
这能帮助你不会遗漏任何要求。
```

### 1.3 Multi-File Creation Workflow (NEW)

Insert after TaskList section:

```
**多文件创建流程**：如果任务要求创建多个文件（如 HTML + CSS + JS），
你必须在一个 turn 内依次创建所有文件。不要只创建第一个文件就停止。
工作流程：
1. 创建第一个文件（调用 write_file）
2. 工具返回成功后，立即创建第二个文件（再次调用 write_file）
3. 重复直到所有文件创建完成
4. 最后用 list_directory 确认所有文件已存在
```

### 1.4 Enhanced Self-Check (MODIFY existing)

Replace existing "完成前必须自查" with:

```
**⚠️ 完成前必须逐项验证**：在报告"任务完成"之前，你必须：
1. 逐项检查任务描述中的每一个要求是否都已满足
2. 如果任务要求创建文件，用 read_file 或 list_directory 确认文件确实存在且内容正确
3. 如果任务要求修改多个位置，确认所有位置都已修改
4. 不要只做了任务的一部分就报告完成
5. **不要仅凭自己的记忆判断完成状态，必须通过工具调用验证**
```

### 1.5 peer_message Guidance (MODIFY existing)

Add to peer_message section:

```
**⚠️ 使用正确的 Agent 名称**：发送 peer_message 时，`to` 参数必须使用
任务上下文中明确给出的 Agent 名称（如 "CodeWriter"），不要自行猜测或使用
简称（如 "coder"）。如果名称不存在，工具会返回错误，错误信息中会列出
当前可用的 Agent 名称，请使用正确的名称重试。
```

---

## 2. Orchestrator Prompt Optimization

**File:** `AgentContext.kt` — `ORCHESTRATOR_SYSTEM_PROMPT`

### 2.1 Enhanced Result Verification (MODIFY existing)

Replace existing "结果完整性验证" section:

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

### 2.2 Intervention Timing Guidance (NEW)

Insert before "重要规则" section:

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

### 2.3 Task Description Example (MODIFY existing)

Add to assign_task section:

```
✅ 正确格式（包含完成标准的多文件任务）：
在 /Download/sicbo-web/ 目录下创建一个骰宝网页游戏，包含以下文件：
1. index.html - 主页面，包含骰子展示区、下注区、历史记录
2. style.css - 深色主题样式，骰子动画
3. script.js - 游戏逻辑（掷骰、大小判定、余额管理）
完成标准：1) 三个文件都已创建 2) HTML 引用了 CSS 和 JS 3) 游戏可正常运行
注意：请依次创建所有文件，不要只创建第一个就停止。
```

---

## 3. Runtime: peer_message Error Enhancement

**File:** `OrchestratorTools.kt` — `handlePeerMessageTool()`

When `peer_message` target agent is not found, the error message should include the list of currently available sub-agents:

```
// Current:
"Error: 目标 Agent '$to' 不存在"

// New:
"Error: 目标 Agent '$to' 不存在。当前可用的 Sub-Agent: ${availableAgents.joinToString(", ")}"
```

Implementation: In `handlePeerMessageTool()`, when `sendPeerMessage` returns an error indicating agent not found, query `teamState.teammates.keys` to get available agent names and append them to the error message.

---

## 4. Runtime: Auto-Verification After Task Completion

**File:** `AgentExecutionLoops.kt` — `executeTask()`

After `runner.runTurn()` returns, before reporting completion:

1. Scan the task prompt for file path patterns (e.g., `/path/to/file.ext` or directory paths)
2. If file paths are found, call `McpRuntimeManager.callTool("list_directory", ...)` or `callTool("read_file", ...)` to verify files exist
3. Append verification results to the `resultSummary` sent in `ResultReport`

```kotlin
// Pseudocode in executeTask(), after runTurn returns:
val fileVerification = verifyTaskFiles(runner, taskPrompt)
if (fileVerification != null) {
    resultSummary += "\n\n【自动验证】$fileVerification"
}
```

The `verifyTaskFiles()` function:
- Extracts file paths from task prompt using regex (paths starting with `/` or relative paths like `sicbo-web/`)
- For each path, calls `list_directory` on the parent directory
- Checks if the expected file name appears in the result
- Returns a summary: "index.html ✅, style.css ❌ 不存在, script.js ❌ 不存在"

**Scope limitation:** Only verifies file creation tasks. Does not verify logic correctness, styling, or other non-file outputs.

---

## 5. UI: TaskList Progress Display

### 5.1 Data Model

**File:** `WorkspaceModels.kt`

```kotlin
data class AgentTaskStep(
    val description: String,   // "创建 index.html（主页面结构）"
    val isCompleted: Boolean,  // true = ✅
)

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

### 5.2 Parsing Logic

**File:** `WorkspaceViewModel.kt`

When receiving stream chunks from a Sub-Agent, parse for TaskList patterns:

- `- [ ] 描述` → uncompleted step
- `- [x] 描述` → completed step
- `✅ 步骤 N 完成：描述` → mark step N as completed (match by step number or description substring)

Update `_agentTaskProgress: MutableStateFlow<Map<String, AgentTaskProgress>>` on each parse.

### 5.3 UI Display

**File:** `WorkspaceScreen.kt` — AgentTabBar

Display progress indicator on each Sub-Agent tab:

```
[CodeWriter 2/4]  [Reviewer 等待中]
```

- Show `completedCount/totalCount` when TaskList exists
- Show agent status (IDLE/STREAMING) when no TaskList yet
- Completed state: all steps ✅, tab shows checkmark or green indicator

### 5.4 Callback Chain

```
AgentRunner.onStreamChunk (existing callback)
  → WorkspaceViewModel detects TaskList pattern in stream buffer
  → Updates _agentTaskProgress StateFlow
  → WorkspaceScreen collects and renders in AgentTabBar
```

---

## 6. Files to Modify

| File | Changes |
|------|---------|
| `AgentLifecycle.kt` | Rewrite `DEFAULT_TEAMMATE_PROMPT` (sections 1.1–1.5) |
| `AgentContext.kt` | Update `ORCHESTRATOR_SYSTEM_PROMPT` (sections 2.1–2.3) |
| `OrchestratorTools.kt` | Enhance peer_message error with available agents (section 3) |
| `AgentExecutionLoops.kt` | Add auto-verification after task completion (section 4) |
| `WorkspaceModels.kt` | Add `AgentTaskStep`, `AgentTaskProgress` data classes (section 5.1) |
| `WorkspaceViewModel.kt` | Add TaskList parsing and `_agentTaskProgress` StateFlow (section 5.2) |
| `WorkspaceScreen.kt` | Display progress in AgentTabBar (section 5.3) |

---

## 7. Verification Plan

1. Run the same "create sicbo-web" task that triggered the original bug
2. Verify Sub-Agent generates TaskList before executing
3. Verify Sub-Agent creates all 3 files (not just 1)
4. Verify Orchestrator receives complete results
5. Verify UI shows task progress in AgentTabBar
6. Verify peer_message error includes available agent names
7. Run existing workspace unit tests to ensure no regressions
