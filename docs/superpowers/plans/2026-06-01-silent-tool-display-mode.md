# Silent Tool Display Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a builtin MCP tool `set_tool_display_mode` that lets the AI toggle silent mode for tool call display, replacing full ToolGroupCards with a compact "工作中..." indicator.

**Architecture:** New field `silentToolCalls` on `UISettings` entity (Room DB), new builtin tool in `McpRuntimeManager` + `BuiltinToolHandler`, new `SilentToolIndicator` composable in `ToolCallComponents`, conditional rendering in `ChatScreen` and `AgentMessageArea`.

**Tech Stack:** Kotlin, Jetpack Compose, Room, MCP tool schema DSL

---

### Task 1: Add `silentToolCalls` field to UISettings + DB migration

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt:506-517`
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt:65` (version bump)
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt` (add migration + register)

- [ ] **Step 1: Add field to UISettings entity**

In `app/src/main/java/com/example/data/Entities.kt`, add `silentToolCalls` field before `updatedAt` (line ~508):

```kotlin
    /**
     * 静默工具调用显示模式。
     * true = 工具调用以紧凑的"工作中..."指示器显示，不展开详情卡片。
     * AI 通过 set_tool_display_mode 工具控制。
     */
    val silentToolCalls: Boolean = false,

    val updatedAt: Long = System.currentTimeMillis(),
```

- [ ] **Step 2: Bump DB version and add migration**

In `app/src/main/java/com/example/data/AppDatabase.kt`:

Change version from 32 to 33:
```kotlin
    version = 33,
```

Add migration after `MIGRATION_31_32`:
```kotlin
        /** v32→v33：ui_settings 增加 silentToolCalls 字段 */
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ui_settings ADD COLUMN silentToolCalls INTEGER NOT NULL DEFAULT 0")
            }
        }
```

Register in `addMigrations()`:
```kotlin
                        MIGRATION_31_32,
                        MIGRATION_32_33
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/data/Entities.kt app/src/main/java/com/example/data/AppDatabase.kt
git commit -m "feat: add silentToolCalls field to UISettings with DB migration v32→v33"
```

---

### Task 2: Register `set_tool_display_mode` builtin tool

**Files:**
- Modify: `app/src/main/java/com/example/mcp/McpRuntimeManager.kt:829` (after scratchpad_list, before tool group management)

- [ ] **Step 1: Add tool definition to builtinTools list**

In `app/src/main/java/com/example/mcp/McpRuntimeManager.kt`, insert after the `scratchpad_list` tool (line ~829) and before the `// ── 运行时工具组管理` comment:

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "set_tool_display_mode",
            description = "Control how tool call results are displayed in the chat. When silent=true, tool calls show a compact \"Working...\" indicator instead of detailed cards. The user can still tap to expand for details. Use this when performing multiple sequential tool calls to avoid flooding the screen. Call set_tool_display_mode(silent=false) to restore the normal detailed display.",
            inputSchema = schema {
                prop("silent", "boolean", "true = show compact indicator, false = show full tool call cards (default).")
                required("silent")
            }
        ),
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/mcp/McpRuntimeManager.kt
git commit -m "feat: register set_tool_display_mode builtin tool schema"
```

---

### Task 3: Implement tool handler in BuiltinToolHandler

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt:104` (add case in when-block)
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` (add handler method)

- [ ] **Step 1: Add case to handleBuiltinTool when-block**

In `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`, add before the `else` branch (line ~104):

```kotlin
            "set_tool_display_mode" -> handleSetToolDisplayMode(context, arguments)
```

- [ ] **Step 2: Add handler method**

Add the handler method after the `handleConfigureMcpToolGroups` method (around line ~610). Find a good location near the MCP tool group management section:

```kotlin
    // ── 工具显示模式 ───────────────────────────────────────────────────────

    private suspend fun handleSetToolDisplayMode(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val silent = arguments.optBoolean("silent", false)
        repository.upsertUISettings(current.copy(silentToolCalls = silent, updatedAt = System.currentTimeMillis()))
        return if (silent) {
            successResponse("已开启静默模式。后续工具调用将以紧凑方式显示，不再展开详情卡片。如需恢复，请调用 set_tool_display_mode(silent=false)。")
        } else {
            successResponse("已关闭静默模式。后续工具调用将以正常详情卡片显示。")
        }
    }
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "feat: implement set_tool_display_mode handler"
```

---

### Task 4: Add SilentToolIndicator composable

**Files:**
- Modify: `app/src/main/java/com/example/ui/components/ToolCallComponents.kt` (add new composable after ToolGroupCard)

- [ ] **Step 1: Add SilentToolIndicator composable**

In `app/src/main/java/com/example/ui/components/ToolCallComponents.kt`, add after the `ToolGroupCard` composable (after line ~462):

```kotlin
@Composable
fun SilentToolIndicator(
    messages: List<UIModelToolMessage>,
    allMessages: List<UIModelToolMessage>,
    modifier: Modifier = Modifier
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacingMultiplier = uiSettings.spacingMultiplier

    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Compact indicator
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            shape = RoundedCornerShape(uiSettings.cornerRadiusDp.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp * spacingMultiplier)
                .clickable { isExpanded = !isExpanded }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = uiText("chat.tools_working", "工作中... (已调用 %d 个工具)").format(messages.size),
                    fontSize = (11.5f * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Expanded: show full ToolGroupCard
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ToolGroupCard(
                messages = messages,
                allMessages = allMessages
            )
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/components/ToolCallComponents.kt
git commit -m "feat: add SilentToolIndicator composable"
```

---

### Task 5: Wire silent mode into ChatScreen and AgentMessageArea

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/ChatScreen.kt:349-357`
- Modify: `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt:138-145`

- [ ] **Step 1: Update ChatScreen to use silent indicator**

In `app/src/main/java/com/example/ui/screens/ChatScreen.kt`, add import at top:
```kotlin
import com.omnichat.ui.components.SilentToolIndicator
```

Replace the tool group rendering block (around line 349-357):

```kotlin
                        is List<*> -> {
                            // 渲染工具调用聚合条
                            @Suppress("UNCHECKED_CAST")
                            val toolMsgs = (item as List<com.omnichat.data.Message>).map { it.toUIModel() }
                            if (uiSettings.silentToolCalls) {
                                SilentToolIndicator(
                                    messages = toolMsgs,
                                    allMessages = uiModelMessages
                                )
                            } else {
                                ToolGroupCard(
                                    messages = toolMsgs,
                                    allMessages = uiModelMessages
                                )
                            }
                        }
```

Note: `uiSettings` is already available via `LocalUISettings.current` at the top of the composable. Verify it's in scope — if not, add `val uiSettings = LocalUISettings.current` near the top of the message list composable.

- [ ] **Step 2: Update AgentMessageArea to use silent indicator**

In `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt`, add import:
```kotlin
import com.omnichat.ui.components.SilentToolIndicator
```

Replace the tool group rendering block (around line 138-145):

```kotlin
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val toolMsgs = (item as List<com.omnichat.workspace.AgentMessage>).map { it.toUIModel() }
                    if (uiSettings.silentToolCalls) {
                        SilentToolIndicator(
                            messages = toolMsgs,
                            allMessages = uiModelMessages
                        )
                    } else {
                        ToolGroupCard(
                            messages = toolMsgs,
                            allMessages = uiModelMessages
                        )
                    }
                }
```

Note: `uiSettings` should already be in scope from `LocalUISettings.current` in this composable. Verify before editing.

- [ ] **Step 3: Generate UI text keys**

Run: `./gradlew generateUiTextKeys`
Expected: `ui_text_keys.json` updated with `chat.tools_working` key

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/ChatScreen.kt app/src/main/java/com/example/ui/screens/AgentMessageArea.kt
git commit -m "feat: wire silent tool display mode into ChatScreen and AgentMessageArea"
```

---

### Task 6: Final verification

- [ ] **Step 1: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 3: Commit any fixes if needed**

```bash
git add -A
git commit -m "fix: address test failures from silent tool display mode"
```
