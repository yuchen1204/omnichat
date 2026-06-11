# Claude Code Agent Team 架构深度分析

> 基于 Claude Code 源码 (`E:\ClaudeCode-main\ClaudeCode-main\src\`) 的完整逆向分析

---

## 目录

1. [架构总览](#1-架构总览)
2. [三种 Agent 架构对比](#2-三种-agent-架构对比)
3. [Agent 定义系统](#3-agent-定义系统)
4. [Coordinator Mode 详解](#4-coordinator-mode-详解)
5. [Agent Teams / Swarms 详解](#5-agent-teams--swarms-详解)
6. [Fork Subagent 详解](#6-fork-subagent-详解)
7. [消息传递系统](#7-消息传递系统)
8. [权限管理机制](#8-权限管理机制)
9. [执行后端抽象](#9-执行后端抽象)
10. [Agent 生命周期管理](#10-agent-生命周期管理)
11. [关键数据结构](#11-关键数据结构)
12. [文件清单与行数参考](#12-文件清单与行数参考)

---

## 1. 架构总览

Claude Code 实现了 **三种相互关联但各自独立** 的多 Agent 系统：

```
┌─────────────────────────────────────────────────────────────────┐
│                        Claude Code 主进程                        │
├──────────────┬──────────────────────┬───────────────────────────┤
│  Coordinator │    Agent Teams       │     Fork Subagent         │
│    Mode      │    (Swarms)          │     (实验性)               │
│              │                      │                           │
│  纯编排器     │  Leader + Teammates  │  父进程 fork Worker       │
│  3个工具      │  文件邮箱通信         │  共享 Prompt Cache        │
│  异步后台     │  tmux/iTerm2/进程内   │  结构化报告               │
└──────────────┴──────────────────────┴───────────────────────────┘
```

### 特性门控

| 架构 | 门控机制 | 外部可用性 |
|------|---------|-----------|
| Coordinator Mode | `feature('COORDINATOR_MODE')` + `CLAUDE_CODE_COORDINATOR_MODE` 环境变量 | 编译时门控 |
| Agent Teams | `isAgentSwarmsEnabled()` — `--agent-teams` 标志 + GrowthBook killswitch | Opt-in |
| Fork Subagent | `feature('FORK_SUBAGENT')` | 编译时门控 |

---

## 2. 三种 Agent 架构对比

### 2.1 Coordinator Mode（协调器模式）

**核心理念**: 主 Claude 变成纯编排器，永远不直接操作代码。

**工具集**: 仅 3 个工具
- `Agent` — 生成 Worker
- `SendMessage` — 给 Worker 发后续指令
- `TaskStop` — 停止 Worker

**工作流**:
```
用户请求 → Coordinator 分析
  → 生成多个 Worker（并行研究）
  → 综合 Worker 结果（Coordinator 自己做）
  → 生成 Worker（实现）
  → 生成 Worker（验证）
  → 汇报用户
```

**Worker 结果格式**: Worker 完成后通过 `<task-notification>` XML 注入为用户消息：

```xml
<task-notification>
  <task-id>agent-a1b</task-id>
  <status>completed|failed|killed</status>
  <summary>人类可读的状态摘要</summary>
  <result>Agent 的最终文本响应</result>
  <usage>
    <total_tokens>N</total_tokens>
    <tool_uses>N</tool_uses>
    <duration_ms>N</duration_ms>
  </usage>
</task-notification>
```

**核心规则**:
- Coordinator 必须**自己综合**研究结果，不能写 "based on your findings"
- Worker 看不到 Coordinator 的对话，每个 prompt 必须自包含
- 只读任务可并行，写入任务需按文件集串行
- 验证意味着**证明代码有效**，而非确认代码存在

### 2.2 Agent Teams / Swarms（团队模式）

**核心理念**: Leader + 多个长期存活的 Teammate，通过文件邮箱通信。

**通信方式**: 基于文件的邮箱系统
```
~/.claude/teams/{team_name}/inboxes/{agent_name}.json
```

**执行后端**: 三种可选
| 后端 | 实现 | 隔离级别 |
|------|------|---------|
| tmux | `TmuxBackend` | 独立 tmux pane 中的进程 |
| iTerm2 | `ITermBackend` | 原生分屏 pane |
| in-process | `InProcessBackend` | AsyncLocalStorage 上下文（同进程） |

**TeamFile 结构** (`~/.claude/teams/{team}/config.json`):
```typescript
type TeamFile = {
  name: string
  description?: string
  createdAt: number
  leadAgentId: string
  leadSessionId?: string
  members: Array<{
    agentId: string           // 格式: agentName@teamName
    name: string
    agentType?: string
    model?: string
    prompt?: string
    color?: string            // UI 颜色标识
    planModeRequired?: boolean
    joinedAt: number
    tmuxPaneId: string
    cwd: string
    worktreePath?: string
    sessionId?: string
    subscriptions: string[]
    backendType?: BackendType
    isActive?: boolean        // 空闲时为 false
    mode?: PermissionMode     // 当前权限模式
  }>
}
```

### 2.3 Fork Subagent（分叉模式）

**核心理念**: 父进程 fork 出 Worker，继承完整对话上下文以共享 Prompt Cache。

**特点**:
- `subagent_type` 可选 — 省略时触发隐式 fork
- 子 Agent 继承父 Agent 的系统提示词（不是 FORK_AGENT 的）
- 所有 fork spawn 强制异步（`forceAsync = true`）
- 使用 `useExactTools: true` 确保工具定义字节一致（prompt cache 命中）
- 防递归 fork：检测 `<fork-boilerplate>` 标签

**Fork Agent 定义**:
```typescript
export const FORK_AGENT = {
  agentType: 'fork',
  tools: ['*'],
  maxTurns: 200,
  model: 'inherit',
  permissionMode: 'bubble',  // 权限冒泡到父进程
  source: 'built-in',
  getSystemPrompt: () => '',  // 不使用 — 父进程的系统提示词通过 override 传入
}
```

---

## 3. Agent 定义系统

### 3.1 定义类型

```typescript
// 基础定义（所有 Agent 共享）
type BaseAgentDefinition = {
  agentType: string              // Agent 类型名
  whenToUse: string              // 何时使用此 Agent 的描述
  tools?: string[]               // 允许的工具列表
  disallowedTools?: string[]     // 禁止的工具列表
  skills?: string[]              // 技能列表
  mcpServers?: AgentMcpServerSpec[]  // MCP 服务器配置
  hooks?: HooksSettings          // Hook 配置
  color?: AgentColorName         // UI 颜色
  model?: string                 // 模型覆盖
  effort?: EffortValue           // 思考努力程度
  permissionMode?: PermissionMode  // 权限模式
  maxTurns?: number              // 最大轮次
  background?: boolean           // 是否始终后台运行
  initialPrompt?: string         // 初始提示
  memory?: AgentMemoryScope      // 内存范围: 'user' | 'project' | 'local'
  isolation?: 'worktree' | 'remote'  // 隔离模式
  requiredMcpServers?: string[]  // 必需的 MCP 服务器
  omitClaudeMd?: boolean         // 是否忽略 CLAUDE.md
}

// 三种具体类型
type BuiltInAgentDefinition = BaseAgentDefinition & {
  source: 'built-in'
  getSystemPrompt: (params: { toolUseContext }) => string
}

type CustomAgentDefinition = BaseAgentDefinition & {
  getSystemPrompt: () => string
  source: SettingSource  // 'userSettings' | 'projectSettings' | 'policySettings' | 'flagSettings'
}

type PluginAgentDefinition = BaseAgentDefinition & {
  getSystemPrompt: () => string
  source: 'plugin'
  plugin: string
}
```

### 3.2 内置 Agent 列表

| Agent | 类型 | 模型 | 特点 |
|-------|------|------|------|
| `generalPurpose` | 通用 | 默认 | 万能 Agent |
| `Explore` | 探索 | haiku (外部) / inherit (内部) | 只读，禁用 Edit/Write/Agent 工具 |
| `Plan` | 规划 | 默认 | 规划专用 |
| `Verification` | 验证 | 默认 | 验证变更 |
| `ClaudeCodeGuide` | 指南 | 默认 | 使用指南 |
| `StatusLineSetup` | 配置 | 默认 | 状态栏配置 |
| `worker` | Worker | 默认 | Coordinator 模式专用 |

### 3.3 自定义 Agent（Markdown 格式）

放置在 `.claude/agents/*.md`，使用 YAML frontmatter：

```yaml
---
name: researcher
description: Research codebase patterns
tools: [Read, Bash, Grep, Glob]
model: sonnet
permissionMode: bubble
maxTurns: 50
background: true
memory: user
isolation: worktree
---

You are a code researcher. Investigate patterns and report findings.
Do not modify any files. Report file paths and line numbers.
```

### 3.4 Agent 加载管线

加载优先级（后者覆盖前者）：

1. **Built-in agents** — 硬编码在 `builtInAgents.ts`
2. **Plugin agents** — 从 MCP 插件目录加载
3. **Custom agents** — 从 `.claude/agents/*.md` 加载

过滤流程：
```
所有 Agent → MCP 需求过滤 → 权限规则过滤 → 可用 Agent 列表
```

---

## 4. Coordinator Mode 详解

### 4.1 系统提示词核心结构

```typescript
// src/coordinator/coordinatorMode.ts:111-369

export function getCoordinatorSystemPrompt(): string {
  return `You are Claude Code, an AI assistant that orchestrates 
  software engineering tasks across multiple workers.

## 1. Your Role
You are a **coordinator**. Your job is to:
- Help the user achieve their goal
- Direct workers to research, implement and verify code changes
- Synthesize results and communicate with the user
- Answer questions directly when possible

## 2. Your Tools
- **Agent** - Spawn a new worker
- **SendMessage** - Continue an existing worker
- **TaskStop** - Stop a running worker

## 4. Task Workflow
| Phase | Who | Purpose |
|-------|-----|---------|
| Research | Workers (parallel) | Investigate codebase |
| Synthesis | **You** (coordinator) | Read findings, craft specs |
| Implementation | Workers | Make targeted changes |
| Verification | Workers | Test changes work |

## 5. Writing Worker Prompts
Workers can't see your conversation. Every prompt must be self-contained.
Never write "based on your findings" — synthesize yourself.`
}
```

### 4.2 Coordinator 的工具过滤

```typescript
// Coordinator 只能使用这 3 个工具
const COORDINATOR_TOOLS = [
  'Agent',        // 生成 Worker
  'SendMessage',  // 给 Worker 发消息
  'TaskStop',     // 停止 Worker
]
```

### 4.3 Worker 工具集

```typescript
// Worker 可以使用标准工具（排除内部工具）
const INTERNAL_WORKER_TOOLS = new Set([
  'TeamCreate', 'TeamDelete', 'SendMessage', 'SyntheticOutput'
])

// Worker 工具 = 所有标准工具 - 内部工具
const workerTools = ASYNC_AGENT_ALLOWED_TOOLS
  .filter(name => !INTERNAL_WORKER_TOOLS.has(name))
```

### 4.4 Scratchpad（跨 Worker 共享目录）

```typescript
// 当 tengu_scratch feature gate 开启时
if (scratchpadDir && isScratchpadGateEnabled()) {
  content += `\nScratchpad directory: ${scratchpadDir}
  Workers can read and write here without permission prompts.
  Use this for durable cross-worker knowledge.`
}
```

---

## 5. Agent Teams / Swarms 详解

### 5.1 团队创建流程

```
1. Leader 调用 TeamCreateTool (operation: 'spawnTeam')
   → 创建 ~/.claude/teams/{team_name}/config.json
   → 注册 leadAgentId

2. Leader 调用 Agent (name: "researcher", team_name: "my-team")
   → spawnTeammate() 被调用
   → 选择执行后端 (tmux/iTerm2/in-process)
   → 创建 pane / 进程
   → 通过邮箱发送初始 prompt
   → 注册到 TeamFile.members
```

### 5.2 Teammate 生成流程（`spawnMultiAgent.ts`）

```typescript
// src/tools/shared/spawnMultiAgent.ts:1040-1078
async function handleSpawn(input, context) {
  // 1. 检查是否启用进程内模式
  if (isInProcessEnabled()) return handleSpawnInProcess(input, context)
  
  // 2. 检测 pane 后端
  try {
    await detectAndGetBackend()
  } catch (error) {
    // 自动回退到进程内模式
    if (getTeammateModeFromSnapshot() === 'auto') {
      markInProcessFallback()
      return handleSpawnInProcess(input, context)
    }
    throw error
  }
  
  // 3. 基于 pane 的生成
  if (useSplitPane) return handleSpawnSplitPane(input, context)
  return handleSpawnSeparateWindow(input, context)
}
```

### 5.3 进程内 Runner 执行循环

这是最复杂的执行路径，核心在 `src/utils/swarm/inProcessRunner.ts`：

```typescript
// 简化后的核心逻辑
export async function runInProcessTeammate(config) {
  // 构建系统提示词
  const teammateSystemPrompt = [
    ...fullSystemPrompt,
    TEAMMATE_SYSTEM_PROMPT_ADDENDUM,  // Teammate 通信说明
    customAgentPrompt,                 // 自定义 Agent 指令（如有）
  ].join('\n')

  // 主循环 — 直到 abort 或 shutdown approved
  while (!abortController.signal.aborted && !shouldExit) {
    // 1. 在 AsyncLocalStorage 上下文中运行 Agent
    await runWithTeammateContext(teammateContext, async () => {
      return runWithAgentContext(agentContext, async () => {
        for await (const message of runAgent({...})) {
          // 追踪进度，更新 AppState
        }
      })
    })
    
    // 2. 标记空闲，发送 idle_notification
    await sendIdleNotification(identity.agentName, ...)
    
    // 3. 等待下一个 prompt 或 shutdown
    const waitResult = await waitForNextPromptOrShutdown(identity, ...)
    
    switch (waitResult.type) {
      case 'shutdown_request':
        // 交给模型决策是否接受关闭
        currentPrompt = formatAsTeammateMessage(...)
        break
      case 'new_message':
        // 新的 prompt（来自 Leader 或其他 Teammate）
        currentPrompt = waitResult.message
        break
      case 'aborted':
        shouldExit = true
        break
    }
  }
}
```

### 5.4 Teammate 系统提示词附加内容

```typescript
// src/utils/swarm/teammatePromptAddendum.ts
export const TEAMMATE_SYSTEM_PROMPT_ADDENDUM = `
# Agent Teammate Communication

IMPORTANT: You are running as an agent in a team. To communicate with anyone on your team:
- Use the SendMessage tool with \`to: "<name>"\` to send messages to specific teammates
- Use the SendMessage tool with \`to: "*"\` sparingly for team-wide broadcasts

Just writing a response in text is not visible to others on your team - 
you MUST use the SendMessage tool.

The user interacts primarily with the team lead. Your work is coordinated 
through the task system and teammate messaging.
`
```

### 5.5 Agent ID 格式

```typescript
// src/utils/agentId.ts
// 格式: agentName@teamName
export function formatAgentId(agentName: string, teamName: string): string {
  return `${agentName}@${teamName}`
}

// Request ID 格式: {requestType}-{timestamp}@{agentId}
export function generateRequestId(requestType: string, agentId: string): string {
  return `${requestType}-${Date.now()}@${agentId}`
}
```

### 5.6 身份解析优先级

```typescript
// src/utils/teammate.ts
// 优先级（从高到低）：
1. AsyncLocalStorage (进程内 Teammate) — via teammateContext.ts
2. dynamicTeamContext (tmux Teammate via CLI args)
3. Environment variables (进程级)
```

---

## 6. Fork Subagent 详解

### 6.1 Feature Gate

```typescript
// src/tools/AgentTool/forkSubagent.ts
export function isForkSubagentEnabled(): boolean {
  if (feature('FORK_SUBAGENT')) {
    if (isCoordinatorMode()) return false      // 与 Coordinator 互斥
    if (getIsNonInteractiveSession()) return false  // 非交互模式不可用
    return true
  }
  return false
}
```

### 6.2 Fork 消息构建

```typescript
// 构建 fork 子 Agent 的对话消息
// 为了让所有 fork 子 Agent 共享 prompt cache，需要字节级一致的 API 请求前缀
export function buildForkedMessages(
  directive: string,
  parentAssistantMessage: AssistantMessage,
): Message[] {
  // 1. 保留父 Agent 的完整 assistant message（所有 tool_use block）
  // 2. 用占位符替换所有 tool_result
  // 3. 添加 fork 指令
  // 4. 结果: 所有 fork 子 Agent 的请求前缀字节一致 → prompt cache 命中
}
```

### 6.3 Worktree 隔离

当 `isolation: 'worktree'` 时：

```typescript
// 创建临时 git worktree
const worktreeInfo = await createAgentWorktree(`agent-${agentId.slice(0, 8)}`)

// Agent 完成后检查是否有变更
const changed = await hasWorktreeChanges(worktreePath, headCommit)
if (!changed) {
  // 无变更 → 清理 worktree
  await removeAgentWorktree(worktreePath, worktreeBranch, gitRoot)
} else {
  // 有变更 → 保留 worktree
}
```

---

## 7. 消息传递系统

### 7.1 文件邮箱系统（`teammateMailbox.ts`）

**存储路径**: `~/.claude/teams/{team_name}/inboxes/{agent_name}.json`

**消息结构**:
```typescript
type TeammateMessage = {
  from: string          // 发送者名称
  text: string          // 消息内容
  timestamp: string     // ISO 时间戳
  read: boolean         // 是否已读
  color?: string        // 发送者颜色
  summary?: string      // 5-10 字预览
}
```

**核心操作**:

| 操作 | 函数 | 说明 |
|------|------|------|
| 写入 | `writeToMailbox(recipient, message, teamName)` | 使用文件锁 |
| 读取全部 | `readMailbox(agentName, teamName)` | 读取所有消息 |
| 读取未读 | `readUnreadMessages(agentName, teamName)` | 过滤未读 |
| 标记已读 | `markMessageAsReadByIndex(agentName, teamName, index)` | 使用文件锁 |
| 清空 | `clearMailbox(agentName, teamName)` | 清空收件箱 |

**文件锁配置**:
```typescript
const LOCK_OPTIONS = {
  retries: {
    retries: 10,
    minTimeout: 5,    // 5ms 最小重试间隔
    maxTimeout: 100,  // 100ms 最大重试间隔
  },
}
```

### 7.2 结构化协议消息类型

邮箱系统承载多种结构化消息：

| 消息类型 | 方向 | 用途 |
|---------|------|------|
| `idle_notification` | Worker → Leader | Worker 空闲通知 |
| `permission_request` | Worker → Leader | 工具权限请求 |
| `permission_response` | Leader → Worker | 权限决策 |
| `sandbox_permission_request` | Worker → Leader | 网络访问请求 |
| `shutdown_request` | Leader → Worker | 优雅关闭请求 |
| `shutdown_approved` | Worker → Leader | 关闭已接受 |
| `shutdown_rejected` | Worker → Leader | 关闭被拒绝 |
| `plan_approval_request` | Worker → Leader | 计划模式审批 |
| `plan_approval_response` | Leader → Worker | 计划决策 |
| `task_assignment` | Leader → Worker | 任务分配 |
| `team_permission_update` | Leader → All | 权限规则变更 |
| `mode_set_request` | Leader → Worker | 权限模式变更 |

### 7.3 SendMessage 路由逻辑

```typescript
// src/tools/SendMessageTool/SendMessageTool.ts:800-874

// 1. 尝试路由到进程内子 Agent
if (typeof input.message === 'string' && input.to !== '*') {
  const registered = appState.agentNameRegistry.get(input.to)
  const agentId = registered ?? toAgentId(input.to)
  if (agentId) {
    const task = appState.tasks[agentId]
    if (isLocalAgentTask(task) && !isMainSessionTask(task)) {
      if (task.status === 'running') {
        // 直接注入消息到 Agent 的 pendingUserMessages
        queuePendingMessage(agentId, input.message, ...)
      } else {
        // 自动恢复已停止的 Agent
        await resumeAgentBackground({ agentId, prompt: input.message, ... })
      }
    }
  }
}

// 2. 广播
if (input.to === '*') return handleBroadcast(...)

// 3. 回退到文件邮箱
return handleMessage(input.to, input.message, ...)
```

### 7.4 进程内消息传递

对于进程内 Teammate（AsyncLocalStorage），消息还通过以下方式传递：

- **`pendingUserMessages`** 数组 — 在 task state 上，用于直接消息注入
- **`agentNameRegistry`** Map — 在 AppState 中，name → agentId 映射
- **`queuePendingMessage()`** — 从 `LocalAgentTask` 用于 SendMessage 路由

---

## 8. 权限管理机制

### 8.1 进程内 Teammate 权限流程

```typescript
// src/utils/swarm/inProcessRunner.ts:128-451

function createInProcessCanUseTool(identity, abortController) {
  return async (tool, input, toolUseContext, ...) => {
    // 1. 检查权限
    const result = await hasPermissionsToUseTool(tool, input, ...)
    
    // 2. allow/deny 直接返回
    if (result.behavior !== 'ask') return result
    
    // 3. Bash 分类器自动审批（如果启用）
    if (feature('BASH_CLASSIFIER') && tool.name === 'Bash' && result.pendingClassifierCheck) {
      const classifierDecision = await awaitClassifierAutoApproval(...)
      if (classifierDecision) return { behavior: 'allow', ... }
    }
    
    // 4. 优先使用 Leader UI Bridge（直接显示 ToolUseConfirm 对话框）
    if (setToolUseConfirmQueue) {
      return new Promise<PermissionDecision>(resolve => {
        setToolUseConfirmQueue(queue => [...queue, {
          tool, input, description,
          workerBadge: { name: identity.agentName, color: identity.color },
          onAllow(updatedInput, permissionUpdates) { resolve({ behavior: 'allow', ... }) },
          onReject(feedback) { resolve({ behavior: 'ask', message: SUBAGENT_REJECT_MESSAGE }) },
        }])
      })
    }
    
    // 5. 回退到邮箱系统
    return new Promise<PermissionDecision>(resolve => {
      const request = createPermissionRequest({...})
      registerPermissionCallback({ requestId: request.id, ... })
      void sendPermissionRequestViaMailbox(request)
      // 轮询邮箱等待响应
      const pollInterval = setInterval(async () => {
        const allMessages = await readMailbox(identity.agentName, identity.teamName)
        for (const msg of allMessages) {
          if (!msg.read && isPermissionResponse(msg.text)) {
            // 处理权限响应
            processMailboxPermissionResponse({...})
            return
          }
        }
      }, 500)
    })
  }
}
```

### 8.2 权限转发流程图

```
Worker 检测到 'ask' 权限
  │
  ├─ Leader UI Bridge 可用?
  │   ├─ 是 → 直接显示 ToolUseConfirm 对话框（带 Worker badge）
  │   │       用户批准/拒绝 → resolve Promise
  │   │
  │   └─ 否 → 发送 permission_request 到 Leader 邮箱
  │           → Leader 轮询邮箱，展示 UI 给用户
  │           → 用户批准/拒绝
  │           → Leader 发送 permission_response 到 Worker 邮箱
  │           → Worker 轮询收到响应，继续执行
```

### 8.3 权限更新同步

```typescript
// 批准时同步权限更新
onAllow(updatedInput, permissionUpdates) {
  persistPermissionUpdates(permissionUpdates)
  if (permissionUpdates.length > 0) {
    const updatedContext = applyPermissionUpdates(
      currentAppState.toolPermissionContext,
      permissionUpdates,
    )
    // 保留 Leader 的模式，防止 Worker 的 'acceptEdits' 泄漏
    setToolPermissionContext(updatedContext, { preserveMode: true })
  }
}
```

---

## 9. 执行后端抽象

### 9.1 PaneBackend 接口

```typescript
// src/utils/swarm/backends/types.ts:39-168

type PaneBackend = {
  readonly type: BackendType  // 'tmux' | 'iterm2' | 'in-process'
  
  isAvailable(): Promise<boolean>
  createTeammatePaneInSwarmView(name: string, color: string): Promise<CreatePaneResult>
  sendCommandToPane(paneId: string, command: string): Promise<void>
  killPane(paneId: string): Promise<boolean>
  hidePane(paneId: string): Promise<boolean>
  showPane(paneId: string, target?: string): Promise<boolean>
  // ... 更多方法
}
```

### 9.2 后端检测与注册

```typescript
// src/utils/swarm/backends/registry.ts

// 检测顺序:
// 1. 用户是否强制指定了后端?
// 2. iTerm2 + it2 CLI 可用?
// 3. tmux 可用?
// 4. 回退到 in-process

export async function detectAndGetBackend(): Promise<DetectionResult> {
  // 按优先级检测
  // 如果没有可用后端且模式为 'auto'，回退到 in-process
}
```

### 9.3 三种后端对比

| 特性 | tmux | iTerm2 | in-process |
|------|------|--------|-----------|
| 进程隔离 | ✅ 独立进程 | ✅ 独立进程 | ❌ 同进程 |
| UI 展示 | tmux pane | 原生分屏 | AppState |
| 通信方式 | 文件邮箱 | 文件邮箱 | ALS + 文件邮箱 |
| 权限提示 | 邮箱轮询 | 邮箱轮询 | UI Bridge |
| 依赖 | tmux | it2 CLI | 无 |
| 性能 | 中 | 中 | 高 |

---

## 10. Agent 生命周期管理

### 10.1 同步 Agent

```
call() 被调用
  → 创建 agentId
  → 注册为 foreground task
  → runAgent() 迭代
  → 可被用户 background（按 Esc 或超时）
  → 完成 → finalizeAgentTool()
  → 清理 worktree（如有）
```

### 10.2 异步 Agent

```
call() 被调用
  → 创建 agentId
  → registerAsyncAgent() — 注册到 AppState.tasks
  → void runAsyncAgentLifecycle() — fire and forget
  → 立即返回 { status: 'async_launched', agentId, outputFile }
  
后台执行:
  → runAgent() 迭代
  → 追踪进度 → updateAsyncAgentProgress()
  → 完成 → completeAsyncAgent()
  → 入队通知 → enqueueAgentNotification()
  → 清理 worktree（如有）
```

### 10.3 Teammate（进程内）

```
startInProcessTeammate(config)
  → runInProcessTeammate(config)  // fire and forget
    → 构建系统提示词
    → while (!aborted && !shouldExit) {
        runAgent() 迭代
        → 标记空闲
        → sendIdleNotification()
        → waitForNextPromptOrShutdown()  // 500ms 轮询
        → 收到新消息 → 继续循环
        → 收到 shutdown_request → 交给模型决策
        → abort → 退出
      }
    → 标记 completed
    → 清理资源
```

### 10.4 优雅关闭协议

```
Leader 发送 shutdown_request 到 Teammate 邮箱
  → Teammate 在 poll loop 中收到
  → 作为用户消息传给模型
  → 模型决定是否接受:
     ├─ 接受 → 发送 shutdown_approved → abort controller → 退出
     └─ 拒绝 → 发送 shutdown_rejected → 继续工作
```

### 10.5 任务列表系统

Team 维护一个共享任务列表，Teammate 可以自动认领任务：

```typescript
// 查找可用任务
function findAvailableTask(tasks: Task[]): Task | undefined {
  return tasks.find(task => {
    if (task.status !== 'pending') return false  // 必须是待处理
    if (task.owner) return false                  // 不能有归属
    return task.blockedBy.every(id => !unresolvedTaskIds.has(id))  // 不能被阻塞
  })
}

// 自动认领
async function tryClaimNextTask(taskListId, agentName) {
  const tasks = await listTasks(taskListId)
  const availableTask = findAvailableTask(tasks)
  if (!availableTask) return undefined
  const result = await claimTask(taskListId, availableTask.id, agentName)
  await updateTask(taskListId, availableTask.id, { status: 'in_progress' })
  return formatTaskAsPrompt(availableTask)
}
```

---

## 11. 关键数据结构

### 11.1 AppState 中的任务状态

```typescript
// 进程内 Teammate 任务状态
type InProcessTeammateTaskState = {
  type: 'in_process_teammate'
  status: 'running' | 'completed' | 'killed' | 'failed'
  identity: TeammateIdentity
  prompt: string
  abortController: AbortController
  currentWorkAbortController?: AbortController  // 当前工作的 abort（Escape 只停当前工作）
  isIdle: boolean
  shutdownRequested: boolean
  permissionMode: PermissionMode
  pendingUserMessages: string[]      // 待处理的用户消息
  messages: Message[]                // 对话消息历史
  progress?: ProgressUpdate          // 进度追踪
  totalPausedMs: number              // 权限等待时间
  inProgressToolUseIDs?: Set<string> // 正在进行的工具调用
  onIdleCallbacks?: Array<() => void>  // 空闲回调
  unregisterCleanup?: () => void     // 清理函数
  // ... 更多字段
}
```

### 11.2 TeammateIdentity

```typescript
type TeammateIdentity = {
  agentId: string        // agentName@teamName
  agentName: string      // 显示名称
  teamName: string       // 团队名称
  color?: string         // UI 颜色
  parentSessionId: string // 父会话 ID
  planModeRequired?: boolean
}
```

### 11.3 AgentContext（分析归因）

```typescript
type AgentContext = {
  agentId: string
  parentSessionId?: string
  agentName?: string
  teamName?: string
  agentColor?: string
  planModeRequired?: boolean
  isTeamLead: boolean
  agentType: 'subagent' | 'teammate'
  subagentName?: string
  isBuiltIn?: boolean
  invokingRequestId?: string
  invocationKind: 'spawn' | 'resume'
  invocationEmitted: boolean
}
```

---

## 12. 文件清单与行数参考

### 核心文件（按重要性排序）

| 优先级 | 文件路径 | 行数 | 职责 |
|--------|---------|------|------|
| 1 | `src/tools/AgentTool/AgentTool.tsx` | ~1200 | 主 Agent 工具：路由、生成、同步/异步执行 |
| 2 | `src/utils/swarm/inProcessRunner.ts` | ~1552 | 进程内 Teammate 执行循环 |
| 3 | `src/tools/shared/spawnMultiAgent.ts` | ~1093 | Teammate 生成编排（跨后端） |
| 4 | `src/utils/teammateMailbox.ts` | ~1183 | 文件邮箱消息系统 |
| 5 | `src/tools/SendMessageTool/SendMessageTool.ts` | ~917 | SendMessage 工具：路由、广播、结构化消息 |
| 6 | `src/coordinator/coordinatorMode.ts` | ~369 | 协调器模式：系统提示词、工具过滤 |
| 7 | `src/tools/AgentTool/runAgent.ts` | ~973 | Agent 执行包装器（query 循环、MCP、hooks） |
| 8 | `src/utils/swarm/teamHelpers.ts` | ~683 | TeamFile CRUD、清理、成员管理 |
| 9 | `src/tools/AgentTool/loadAgentsDir.ts` | ~755 | Agent 定义加载与解析 |
| 10 | `src/utils/swarm/spawnInProcess.ts` | ~328 | 进程内 Teammate 生成与销毁 |
| 11 | `src/utils/teammate.ts` | ~292 | Teammate 身份解析 |
| 12 | `src/utils/teammateContext.ts` | ~96 | AsyncLocalStorage 上下文 |
| 13 | `src/utils/swarm/permissionSync.ts` | ~928 | 跨 Agent 权限转发 |
| 14 | `src/tools/AgentTool/forkSubagent.ts` | ~210 | Fork 实验：cache 共享 Worker |
| 15 | `src/utils/swarm/backends/types.ts` | ~311 | 后端类型定义 |

### 内置 Agent 定义

| 文件 | 行数 | Agent |
|------|------|-------|
| `src/tools/AgentTool/built-in/generalPurposeAgent.ts` | ~50 | 通用 Agent |
| `src/tools/AgentTool/built-in/exploreAgent.ts` | ~83 | 探索 Agent |
| `src/tools/AgentTool/built-in/planAgent.ts` | ~60 | 规划 Agent |
| `src/tools/AgentTool/built-in/verificationAgent.ts` | ~70 | 验证 Agent |
| `src/tools/AgentTool/built-in/claudeCodeGuideAgent.ts` | ~40 | 指南 Agent |

### 辅助模块

| 文件路径 | 职责 |
|---------|------|
| `src/utils/agentId.ts` | Agent ID 格式化 |
| `src/utils/agentSwarmsEnabled.ts` | Agent Teams 特性门控 |
| `src/utils/agentContext.ts` | Agent 分析上下文 |
| `src/utils/teamMemoryOps.ts` | 团队内存操作 |
| `src/utils/teamDiscovery.ts` | 团队发现 |
| `src/utils/swarm/constants.ts` | 常量定义（TEAM_LEAD_NAME 等） |
| `src/utils/swarm/teamHelpers.ts` | TeamFile CRUD |
| `src/utils/swarm/teammateInit.ts` | 停止 hook 注册 |
| `src/utils/swarm/teammatePromptAddendum.ts` | Teammate 系统提示词附加 |
| `src/utils/swarm/teammateModel.ts` | Teammate 模型解析 |
| `src/utils/swarm/teammateLayoutManager.ts` | Pane 布局管理 |
| `src/utils/swarm/spawnInProcess.ts` | 进程内 Teammate 生成 |
| `src/utils/swarm/spawnUtils.ts` | 环境变量传播 |
| `src/utils/swarm/permissionSync.ts` | 权限同步 |
| `src/utils/swarm/leaderPermissionBridge.ts` | Leader UI 权限桥 |
| `src/utils/swarm/reconnection.ts` | Teammate 重连 |
| `src/utils/swarm/backends/registry.ts` | 后端检测与注册 |
| `src/utils/swarm/backends/detection.ts` | tmux/iTerm2 可用性检测 |
| `src/utils/swarm/backends/TmuxBackend.ts` | tmux pane 管理 |
| `src/utils/swarm/backends/ITermBackend.ts` | iTerm2 原生分屏 |
| `src/utils/swarm/backends/InProcessBackend.ts` | 进程内执行后端 |
| `src/utils/swarm/backends/PaneBackendExecutor.ts` | 后端适配器 |
| `src/tools/AgentTool/agentColorManager.ts` | Agent 颜色管理 |
| `src/tools/AgentTool/agentMemory.ts` | Agent 持久化内存 |
| `src/tools/AgentTool/resumeAgent.ts` | 恢复已停止的 Agent |
| `src/tasks/InProcessTeammateTask/` | 进程内 Teammate 任务类型 |
| `src/tasks/LocalAgentTask/` | 本地 Agent 任务管理 |
| `src/tasks/RemoteAgentTask/` | 远程 Agent 任务（仅内部） |
| `src/hooks/toolPermission/handlers/swarmWorkerHandler.ts` | Swarm Worker 权限处理器 |
| `src/memdir/teamMemPrompts.ts` | 团队内存提示词 |
| `src/memdir/teamMemPaths.ts` | 团队内存路径 |
| `src/services/AgentSummary/agentSummary.ts` | Agent 进度摘要 |
| `src/services/teamMemorySync/` | 团队内存同步 |
| `src/commands/agents/agents.tsx` | /agents 命令 UI |

---

## 附录：架构设计亮点

### A. 后端抽象
`PaneBackend` 接口统一了 tmux、iTerm2、in-process 三种执行环境，使得上层逻辑无需关心底层实现。

### B. 优雅关闭
Leader 发送 `shutdown_request` → 模型自主决策是否接受 → 发送 `approved/rejected`。模型有拒绝权，避免被强制中断重要工作。

### C. Prompt Cache 优化
Fork 子 Agent 通过 `buildForkedMessages()` 确保所有子 Agent 的 API 请求前缀字节一致，最大化 prompt cache 命中率。

### D. 权限桥接
进程内 Teammate 通过 `LeaderPermissionBridge` 直接复用 Leader 的 ToolUseConfirm UI，无需走邮箱轮询，提供更流畅的用户体验。

### E. 自动回退
当 tmux/iTerm2 不可用时，自动回退到 in-process 模式，确保功能始终可用。

### F. 任务列表驱动
Team 维护共享任务列表，空闲 Teammate 自动认领待处理任务，实现自动负载均衡。

---

*文档生成时间: 2026-05-24*
*基于 Claude Code 源码版本: E:\ClaudeCode-main*
