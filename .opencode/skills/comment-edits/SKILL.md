---
name: comment-edits
description: Enforces that all code edits must include inline comments explaining why the change was made. Apply this rule to every edit tool call.
---

# Comment-Every-Edit Rule

When using the edit tool to modify existing code, **every change must include an inline comment** explaining the reasoning behind the modification.

## Rules

1. **Why, not what**: Comments should explain _why_ the code was changed, not restate _what_ the code does. The code itself is the "what".
2. **Inline placement**: Place the comment directly above or beside the changed lines, not in a separate file.
3. **Concise**: Keep comments to 1-2 lines. Link to issue trackers or design docs if more context is needed.
4. **Bug fixes**: Always note the original symptom or failure mode (e.g. "避免 X 场景下 Y 导致 Z").
5. **Refactors**: Note the motivation (e.g. "提取此方法以支持 Z 场景的复用").
6. **New code**: Comment the purpose and any non-obvious constraints.

## Examples

### Bug fix
```kotlin
// 补查收件箱：tryReceive→tryClaim 窗口期内到达的消息会被遗漏
val pendingMsg = messageBus.tryReceive(identity.agentName)
```

### Refactor
```kotlin
// 只取 fromIndex 之后的消息，防止 continueAgent 复用 Agent 时混入前序任务的工具输出
val taskMessages = history.subList(fromIndex, history.size)
```

### Safety guard
```kotlin
// 必须显式终止超时 Agent 的协程，否则孤儿协程会持续消耗内存
try { killTeammate(name) } catch (_: Exception) { }
```
