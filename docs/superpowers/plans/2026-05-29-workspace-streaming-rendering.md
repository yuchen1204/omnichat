# Workspace Streaming Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the workspace UI render messages in real-time during agent execution — tool calls, tool results, and LLM text should appear as they happen, not after all work completes.

**Architecture:** Add an `onMessageAdded` callback to `AgentRunner` that fires whenever a message is added to an agent's context. `TeamManager` passes this through to `WorkspaceViewModel`, which incrementally updates the active tab's message list. The `StreamingBubble` continues to show live text during LLM streaming, and is replaced by the final assistant message when it arrives via `onMessageAdded`. SubAgent tool calls also stream their results inline via a new `onSubAgentToolResult` callback.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Coroutines

---

## Files to Modify

| File | Change |
|------|--------|
| `app/src/main/java/com/example/workspace/AgentRunner.kt` | Add `onMessageAdded` parameter, fire it after every `context.messages.add()` |
| `app/src/main/java/com/example/workspace/TeamManager.kt` | Accept and forward `onMessageAdded` callback; add `onSubAgentToolResult` for inline tool display |
| `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt` | Handle `onMessageAdded` to update tab messages incrementally; manage streaming bubble lifecycle |
| `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt` | Hide streaming bubble when final assistant message arrives; handle incremental message list |

---

### Task 1: Add `onMessageAdded` callback to AgentRunner

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`

- [ ] **Step 1: Add the `onMessageAdded` parameter to AgentRunner**

In `AgentRunner.kt`, add the new callback parameter to the class constructor (around line 50, alongside `onStreamChunk`):

```kotlin
// Find the existing onStreamChunk parameter and add onMessageAdded after it
private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
private val onMessageAdded: (agentName: String, message: AgentMessage) -> Unit = { _, _ -> },
```

- [ ] **Step 2: Fire `onMessageAdded` after every `context.messages.add()` call**

There are multiple places where messages are added to `context.messages`. Each one needs an `onMessageAdded` call right after. The locations are:

1. **User message injection** (~line 205, after `injectMessage("user", ...)`): `injectMessage` already adds to `context.messages`. Add after it:
```kotlin
if (userMessage != null) {
    injectMessage("user", userMessage, isIntervention = false, source = source, imagePath = imagePath)
    onMessageAdded(context.agentName, context.messages.last())
}
```

2. **Assistant message** (~line 367, after `context.messages.add(AgentMessage(role="assistant", ...))`): Add inside the `messagesLock.writeLock` block, right after the `context.messages.add(...)` call and before `persistMessage`:
```kotlin
context.messages.add(
    AgentMessage(
        role = "assistant",
        content = finalContent,
        toolCallsJson = toolCallsJson
    )
)
onMessageAdded(context.agentName, context.messages.last())
persistMessage?.invoke(context.messages.last())
```

3. **Tool result message** (~line 427, after `context.messages.add(AgentMessage(role="tool", ...))`): Same pattern:
```kotlin
context.messages.add(
    AgentMessage(
        role = "tool",
        content = result.content,
        toolCallId = result.callId
    )
)
onMessageAdded(context.agentName, context.messages.last())
persistMessage?.invoke(context.messages.last())
```

4. **Invalid JSON arguments error** (~line 399, after `context.messages.add(AgentMessage(role="tool", content="Error:..."))`): Same pattern.

5. **Any other `context.messages.add()` calls** — search the file for all occurrences and add the callback.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt
git commit -m "feat(workspace): add onMessageAdded callback to AgentRunner"
```

---

### Task 2: Forward `onMessageAdded` through TeamManager

**Files:**
- Modify: `app/src/main/java/com/example/workspace/TeamManager.kt`

- [ ] **Step 1: Add `onMessageAdded` parameter to TeamManager constructor**

Find the constructor (around line 48) and add the new parameter:

```kotlin
private val onStreamChunk: (agentName: String, chunk: String) -> Unit,
private val onMessageAdded: (agentName: String, message: AgentMessage) -> Unit = { _, _ -> },
private val onAgentStatusChanged: (agentName: String, status: AgentStatus) -> Unit,
```

- [ ] **Step 2: Pass `onMessageAdded` to AgentRunner creation**

Find where AgentRunner is instantiated (search for `AgentRunner(` in TeamManager). Add the parameter:

```kotlin
onMessageAdded = onMessageAdded,
```

This should be added alongside the existing `onStreamChunk = onStreamChunk` parameter.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/workspace/TeamManager.kt
git commit -m "feat(workspace): forward onMessageAdded through TeamManager"
```

---

### Task 3: Handle incremental messages in WorkspaceViewModel

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt`

- [ ] **Step 1: Wire up `onMessageAdded` in the TeamManager construction**

Find where TeamManager is constructed (search for `TeamManager(` in WorkspaceViewModel, around line 930). Add the callback:

```kotlin
onMessageAdded = { agentName, message ->
    val hasTab = _agentTabs.value.any { it.agentName == agentName }
    if (hasTab) {
        _agentTabs.update { tabs ->
            tabs.map { tab ->
                if (tab.agentName == agentName) {
                    tab.copy(messages = tab.messages + message)
                } else tab
            }
        }
    }
},
```

- [ ] **Step 2: Update the `onAgentStatusChanged` handler to not overwrite incremental messages**

Currently (line 953-961), when status changes to IDLE or COMPLETED, the tab's messages are replaced from history:
```kotlin
val newMessages = history.ifEmpty { tab.messages }
tab.copy(status = status, messages = newMessages)
```

This is fine — `history` from `getAgentHistory()` should contain the same messages that were added incrementally. But we need to make sure the streaming buffer is cleared at the right time. The current code removes the stream buffer at line 963. This should stay the same, but we need to also handle the case where the streaming bubble should disappear when the final assistant message arrives via `onMessageAdded` (before COMPLETED status).

No change needed to the existing `onAgentStatusChanged` logic — it already handles the transition correctly. The key insight is that `onMessageAdded` provides real-time updates DURING execution, and `onAgentStatusChanged` provides the final sync at the END.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/WorkspaceViewModel.kt
git commit -m "feat(workspace): handle incremental message updates in ViewModel"
```

---

### Task 4: Update AgentMessageArea to handle streaming bubble lifecycle

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt`

- [ ] **Step 1: Hide streaming bubble when the final assistant message is in the message list**

The streaming bubble should disappear when the assistant message it represents has been added to `activeTab.messages` via `onMessageAdded`. Currently, the bubble shows when `isStreaming && streamingText.isNotEmpty()`. We need to also check that the last message in the list isn't already an assistant message with the same content.

Update the `showStreamBubble` logic (around line 80):

```kotlin
val isStreaming = agentStatus == AgentStatus.STREAMING || agentStatus == AgentStatus.WAITING_TOOL

// Hide streaming bubble if the last message is an assistant message
// that matches or exceeds the streaming text (meaning onMessageAdded already delivered it)
val lastMessage = activeTab.messages.lastOrNull()
val finalMessageAlreadyPresent = lastMessage?.role == "assistant" &&
    streamingText.isNotEmpty() &&
    lastMessage.content.contains(streamingText.take(50))

val showStreamBubble = isStreaming && streamingText.isNotEmpty() && !finalMessageAlreadyPresent
```

This heuristic checks if the last assistant message contains the beginning of the streaming text, which means the final message has already been added.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/AgentMessageArea.kt
git commit -m "feat(workspace): hide streaming bubble when final message arrives"
```

---

### Task 5: Optimize streaming text rendering performance

**Files:**
- Modify: `app/src/main/java/com/example/ui/components/ChunkedStreamingText.kt`
- Modify: `app/src/main/java/com/example/ui/components/MarkdownChunkParser.kt`

- [ ] **Step 1: Add incremental parsing to MarkdownChunkParser**

Currently `parse()` re-parses the entire text on every update. Add an incremental mode that reuses previously locked chunks.

In `MarkdownChunkParser.kt`, add a new method:

```kotlin
/**
 * Incremental parse: reuse previously locked chunks and only re-parse the tail.
 *
 * @param text Full current text
 * @param previousChunks Chunks from the previous parse call
 * @return Updated chunk list
 */
fun parseIncremental(text: String, previousChunks: List<Chunk>): List<Chunk> {
    if (previousChunks.isEmpty()) return parse(text)

    // Find how much of the text is already locked
    val lockedLength = previousChunks
        .filter { it.isLocked }
        .sumOf { it.text.length }

    if (lockedLength >= text.length) return previousChunks

    // Re-parse only the tail (from the start of the last active chunk)
    val lastActiveStart = previousChunks
        .filter { it.isLocked }
        .sumOf { it.text.length }

    val tail = text.substring(lastActiveStart)
    val tailChunks = parse(tail)

    // Merge: keep all previously locked chunks, replace the active chunk with new tail chunks
    val previouslyLocked = previousChunks.filter { it.isLocked }
    return previouslyLocked + tailChunks
}
```

- [ ] **Step 2: Use incremental parsing in ChunkedStreamingText**

In `ChunkedStreamingText.kt`, change the `remember(text)` call to use incremental parsing:

```kotlin
// Replace:
// val chunks = remember(text) { parser.parse(text) }

// With:
var previousChunks by remember { mutableStateOf<List<MarkdownChunkParser.Chunk>>(emptyList()) }
val chunks = remember(text) {
    val result = parser.parseIncremental(text, previousChunks)
    previousChunks = result
    result
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/components/ChunkedStreamingText.kt app/src/main/java/com/example/ui/components/MarkdownChunkParser.kt
git commit -m "perf(workspace): add incremental markdown chunk parsing for streaming"
```

---

### Task 6: Build and verify

- [ ] **Step 1: Full debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 3: Manual verification checklist**

Deploy to device/emulator and verify:
1. Start a workspace task → orchestrator tab appears immediately
2. During tool calls (create_agents, assign_task) → tool call cards appear in real-time
3. Tool results appear as each tool completes (not batched)
4. LLM text streams via the streaming bubble during each iteration
5. Streaming bubble disappears when the final assistant message is added
6. SubAgent tool calls show results inline
7. Auto-scroll works correctly during incremental updates
8. Scrolling up pauses auto-scroll, scrolling to bottom resumes

- [ ] **Step 4: Final commit (if any fixes needed)**

```bash
git add -A
git commit -m "fix(workspace): address streaming rendering issues from manual testing"
```
