# Internationalization (i18n) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add English-primary + Chinese-secondary i18n using Android strings.xml, migrating 308 uiText() calls and ~151 non-UI Chinese strings.

**Architecture:** Android `res/values/strings.xml` (English default) + `res/values-zh-rCN/strings.xml` (Chinese). Standard `stringResource()` for all UI strings. Existing `uiText()` system retained only for ~30-50 AI-overridable decorative strings. Non-UI code uses `Resources.getString()` via AndroidViewModel or injected Context.

**Tech Stack:** Android strings.xml, Jetpack Compose `stringResource()`, Android `Resources`

**Spec:** `docs/superpowers/specs/2026-06-01-i18n-design.md`

---

## File Structure

### New files
- `app/src/main/res/values/strings.xml` — English strings (primary)
- `app/src/main/res/values-zh-rCN/strings.xml` — Chinese strings (secondary)
- `README.md` — English version (replaces current Chinese README)
- `README_zh.md` — Chinese version (renamed from current README)

### Modified files (Compose — uiText → stringResource)
- `app/src/main/java/com/example/ui/screens/MainScreen.kt` (17 uiText calls)
- `app/src/main/java/com/example/ui/screens/ChatScreen.kt` (27 uiText calls)
- `app/src/main/java/com/example/ui/screens/SessionSidebarPanel.kt` (20 uiText + 7 hardcoded)
- `app/src/main/java/com/example/ui/screens/ModelsConfigScreen.kt` (42 uiText calls)
- `app/src/main/java/com/example/ui/screens/McpConfigScreen.kt` (63 uiText calls)
- `app/src/main/java/com/example/ui/screens/McpDialogs.kt` (35 uiText calls)
- `app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt` (28 uiText calls)
- `app/src/main/java/com/example/ui/screens/ExportImportScreen.kt` (27 uiText + 4 hardcoded)
- `app/src/main/java/com/example/ui/screens/InterventionInput.kt` (13 uiText calls)
- `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt` (5 uiText calls)
- `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt` (1 uiText call)
- `app/src/main/java/com/example/ui/screens/WorkspaceToolbar.kt` (7 uiText calls)
- `app/src/main/java/com/example/ui/screens/AgentTabBar.kt` (1 uiText call)
- `app/src/main/java/com/example/ui/screens/TeamTaskPanel.kt` (9 uiText + 2 hardcoded)
- `app/src/main/java/com/example/ui/screens/AskUserDialog.kt` (6 uiText calls)
- `app/src/main/java/com/example/ui/screens/AgentPresetConfigScreen.kt` (17 uiText + 1 hardcoded)
- `app/src/main/java/com/example/ui/screens/OrchestrationToolCallCard.kt` (8 uiText calls)
- `app/src/main/java/com/example/ui/screens/WorkspaceReadyView.kt` (17 uiText + 1 hardcoded)
- `app/src/main/java/com/example/ui/screens/AgentBubbleMessage.kt` (3 uiText + 1 hardcoded)

### Modified files (non-UI)
- `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt` (9 Chinese strings)
- `app/src/main/java/com/example/ui/viewmodel/SettingsViewModel.kt` (6 Chinese strings)
- `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` (110+ Chinese strings)
- `app/src/main/java/com/example/workspace/AgentRunner.kt` (13 Chinese strings)
- `app/src/main/java/com/example/workspace/AgentDefinition.kt` (12 entries + ~500 lines of system prompts)
- `app/src/main/java/com/example/data/Entities.kt` (1 Chinese default)

### Modified files (build/tooling)
- `app/build.gradle.kts` (update generateUiTextKeys task)
- `app/src/main/assets/ui_text_keys.json` (shrink to AI-override keys only)
- `app/src/main/java/com/example/ui/theme/UiStrings.kt` (add __res: prefix helper)

---

## Task 1: Create English strings.xml from ui_text_keys.json

**Files:**
- Create: `app/src/main/res/values/strings.xml`
- Read: `app/src/main/assets/ui_text_keys.json`

The existing `ui_text_keys.json` has 344 key-value pairs (Chinese → Chinese). We need to create the English `strings.xml` by translating each value and converting dot-notation keys to underscore format.

- [ ] **Step 1: Read the current ui_text_keys.json**

Read `app/src/main/assets/ui_text_keys.json` to get all 344 key-value pairs.

- [ ] **Step 2: Create English strings.xml**

Create `app/src/main/res/values/strings.xml` with all 344 keys translated to English. Key naming: replace dots with underscores (e.g., `topbar.title.chat` → `topbar_title_chat`). Also add the 11 hardcoded Chinese strings from Compose screens that aren't yet in ui_text_keys.json. Also add strings needed for non-UI code (ViewModels, BuiltinToolHandler, AgentRunner, AgentDefinition).

The file must include:
1. All 344 existing uiText keys (translated to English)
2. ~11 hardcoded Compose strings (new keys)
3. ~130 non-UI strings (BuiltinToolHandler tool responses, AgentRunner system messages, ChatViewModel/SettingsViewModel errors)
4. ~50 AgentDefinition agent names, descriptions, and system prompt strings

Key naming conventions:
- Existing uiText keys: `topbar.title.chat` → `topbar_title_chat`
- Compose hardcoded: `sidebar.just_now`, `sidebar.minutes_ago`, `sidebar.hours_ago`, `export.import.providers`, `export.import.mcp`, `export.import.memory`, `export.import.colors`, `workspace.just_now`, `workspace.seconds_ago`, `workspace.minutes_ago`, `workspace.hours_ago`, `workspace.days_ago`, `preset.name_empty_error`, `workspace.default_suffix`, `workspace.tool_calls_count`
- Non-UI: `error_no_default_provider`, `error_tool_depth_exceeded`, `error_model_fetch_failed`, `export_success`, `export_failed`, `import_file_unreadable`, `import_success`, `import_failed`, `default_workspace_title`, `default_session_title`, `default_assistant_name`

- [ ] **Step 3: Create Chinese strings.xml**

Create `app/src/main/res/values-zh-rCN/strings.xml` with the same keys but Chinese values (matching the current defaults from ui_text_keys.json and the hardcoded Chinese strings).

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (string resources are valid XML, all referenced keys exist in both files)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(i18n): add English and Chinese string resource files

Create Android standard strings.xml for both locales:
- values/strings.xml (English, default)
- values-zh-rCN/strings.xml (Chinese Simplified)

Includes all 344 existing uiText keys, hardcoded Compose strings,
non-UI strings (ViewModels, BuiltinToolHandler, AgentRunner, AgentDefinition)."
```

---

## Task 2: Migrate Compose screens — Group A (MainScreen, ChatScreen, AgentMessageArea, WorkspaceScreen, WorkspaceToolbar, AgentTabBar, AskUserDialog)

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/MainScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/ChatScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt`
- Modify: `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/WorkspaceToolbar.kt`
- Modify: `app/src/main/java/com/example/ui/screens/AgentTabBar.kt`
- Modify: `app/src/main/java/com/example/ui/screens/AskUserDialog.kt`

For each file, replace every `uiText("key", "default")` call with `stringResource(R.string.key_name)` where `key_name` is the key with dots replaced by underscores.

**Transformation pattern:**
```kotlin
// Before
import com.example.ui.theme.uiText
Text(uiText("topbar.title.chat", "会话"))

// After
import androidx.compose.ui.res.stringResource
Text(stringResource(R.string.topbar_title_chat))
```

For keys with format arguments (e.g., `uiText("chat.current.model", "当前模型: %s  ·  %s")`):
```kotlin
// Before
uiText("chat.current.model", "当前模型: %s  ·  %s").format(modelName, providerName)

// After
stringResource(R.string.chat_current_model, modelName, providerName)
```

- [ ] **Step 1: Migrate MainScreen.kt** (17 uiText calls)

Read `MainScreen.kt`, replace all 17 `uiText()` calls with `stringResource()`. Add `import androidx.compose.ui.res.stringResource`. Remove `import com.example.ui.theme.uiText` if no longer used.

- [ ] **Step 2: Migrate ChatScreen.kt** (27 uiText calls)

Read `ChatScreen.kt`, replace all 27 `uiText()` calls with `stringResource()`. Handle format strings properly (e.g., `chat.current.model` takes 2 `%s` args, `chat.tools_used_count` takes `%d`).

- [ ] **Step 3: Migrate AgentMessageArea.kt** (5 uiText calls)

- [ ] **Step 4: Migrate WorkspaceScreen.kt** (1 uiText call)

- [ ] **Step 5: Migrate WorkspaceToolbar.kt** (7 uiText calls)

- [ ] **Step 6: Migrate AgentTabBar.kt** (1 uiText call)

- [ ] **Step 7: Migrate AskUserDialog.kt** (6 uiText calls)

- [ ] **Step 8: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/MainScreen.kt app/src/main/java/com/example/ui/screens/ChatScreen.kt app/src/main/java/com/example/ui/screens/AgentMessageArea.kt app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt app/src/main/java/com/example/ui/screens/WorkspaceToolbar.kt app/src/main/java/com/example/ui/screens/AgentTabBar.kt app/src/main/java/com/example/ui/screens/AskUserDialog.kt
git commit -m "refactor(i18n): migrate Group A Compose screens to stringResource

Replace uiText() with stringResource() in 7 files (64 call sites):
MainScreen, ChatScreen, AgentMessageArea, WorkspaceScreen,
WorkspaceToolbar, AgentTabBar, AskUserDialog."
```

---

## Task 3: Migrate Compose screens — Group B (ModelsConfigScreen, MemoryAndPromptScreen, InterventionInput)

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/ModelsConfigScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/InterventionInput.kt`

- [ ] **Step 1: Migrate ModelsConfigScreen.kt** (42 uiText calls)

This is one of the largest files. Replace all 42 `uiText()` calls. Handle format strings: `models.context_size_format` takes `%s`, `models.headers_count` takes `%d`, `models.fetch_error` takes `%s`.

- [ ] **Step 2: Migrate MemoryAndPromptScreen.kt** (28 uiText calls)

- [ ] **Step 3: Migrate InterventionInput.kt** (13 uiText calls)

Handle format strings: `workspace.intervention.hint.sub` takes `%s`.

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/ModelsConfigScreen.kt app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt app/src/main/java/com/example/ui/screens/InterventionInput.kt
git commit -m "refactor(i18n): migrate Group B Compose screens to stringResource

Replace uiText() with stringResource() in 3 files (83 call sites):
ModelsConfigScreen, MemoryAndPromptScreen, InterventionInput."
```

---

## Task 4: Migrate Compose screens — Group C (McpConfigScreen, McpDialogs)

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/McpConfigScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/McpDialogs.kt`

- [ ] **Step 1: Migrate McpConfigScreen.kt** (63 uiText calls)

This is the largest single file. Replace all 63 `uiText()` calls. Note: some keys have `\n` newline escapes in defaults — these become `\\n` in string resources.

- [ ] **Step 2: Migrate McpDialogs.kt** (35 uiText calls)

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/McpConfigScreen.kt app/src/main/java/com/example/ui/screens/McpDialogs.kt
git commit -m "refactor(i18n): migrate Group C Compose screens to stringResource

Replace uiText() with stringResource() in 2 files (98 call sites):
McpConfigScreen, McpDialogs."
```

---

## Task 5: Migrate Compose screens — Group D (SessionSidebarPanel, ExportImportScreen, TeamTaskPanel, remaining workspace files)

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/SessionSidebarPanel.kt`
- Modify: `app/src/main/java/com/example/ui/screens/ExportImportScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/TeamTaskPanel.kt`
- Modify: `app/src/main/java/com/example/ui/screens/AgentPresetConfigScreen.kt`
- Modify: `app/src/main/java/com/example/ui/screens/OrchestrationToolCallCard.kt`
- Modify: `app/src/main/java/com/example/ui/screens/WorkspaceReadyView.kt`
- Modify: `app/src/main/java/com/example/ui/screens/AgentBubbleMessage.kt`

These files have BOTH uiText() calls AND hardcoded Chinese strings to wrap.

- [ ] **Step 1: Migrate SessionSidebarPanel.kt** (20 uiText + 7 hardcoded)

Replace 20 `uiText()` calls. Also wrap these 7 hardcoded strings:

```kotlin
// Line 296: Toast message
// Before: Toast.makeText(context, "已解锁多智能体工作区", Toast.LENGTH_SHORT).show()
// After: Toast.makeText(context, context.getString(R.string.sidebar_workspace_unlocked), Toast.LENGTH_SHORT).show()

// Line 707: Fallback label
// Before: Text("未选择模型")
// After: Text(stringResource(R.string.sidebar_no_model_selected))

// Lines 797-803: formatRelativeTime function
// Before: return "刚刚"
// After: return context.getString(R.string.time_just_now)
// Before: return "${diffMinutes}分钟前"
// After: return context.getString(R.string.time_minutes_ago, diffMinutes)
// Before: return "${diffHours}小时前"
// After: return context.getString(R.string.time_hours_ago, diffHours)
```

Note: `formatRelativeTime` is not a `@Composable` function, so it needs `Context` to access resources. The function likely already receives a context parameter or can get one from the calling composable. If not, add a `context: Context` parameter.

- [ ] **Step 2: Migrate ExportImportScreen.kt** (27 uiText + 4 hardcoded)

Replace 27 `uiText()` calls. Also wrap the 4 hardcoded import category labels:

```kotlin
// Lines 601-604: buildList in ImportConfirmDialog
// Before: add("供应商配置")
// After: add(context.getString(R.string.export_option_providers))
// Before: add("MCP 配置")
// After: add(context.getString(R.string.export_option_mcp))
// Before: add("长效记忆 & Prompt 模板")
// After: add(context.getString(R.string.export_option_memory))
// Before: add("配色方案")
// After: add(context.getString(R.string.export_option_colors))
```

- [ ] **Step 3: Migrate TeamTaskPanel.kt** (9 uiText + 2 hardcoded)

Replace 9 `uiText()` calls. Wrap the `formatTaskAge` function strings:

```kotlin
// Line 442: formatTaskAge function
// Before: return "刚刚"
// After: return context.getString(R.string.time_just_now)
// Lines 444-448:
// Before: "${seconds}s 前" / "${minutes}m 前" / "${hours}h 前" / "${days}d 前"
// After: context.getString(R.string.time_seconds_ago_short, seconds) / etc.
```

- [ ] **Step 4: Migrate AgentPresetConfigScreen.kt** (17 uiText + 1 hardcoded)

```kotlin
// Line 332: validation error
// Before: nameError = "名称不能为空"
// After: nameError = context.getString(R.string.preset_name_empty_error)
```

- [ ] **Step 5: Migrate OrchestrationToolCallCard.kt** (8 uiText calls)

- [ ] **Step 6: Migrate WorkspaceReadyView.kt** (17 uiText + 1 hardcoded)

```kotlin
// Line 343: dropdown suffix
// Before: "${config.selectedModelId} (默认)"
// After: stringResource(R.string.workspace_model_default_format, config.selectedModelId)
```

- [ ] **Step 7: Migrate AgentBubbleMessage.kt** (3 uiText + 1 hardcoded)

```kotlin
// Line 389: tool call count
// Before: "${parsed.toolUses} 次工具调用"
// After: stringResource(R.string.workspace_tool_calls_count, parsed.toolUses)
```

- [ ] **Step 8: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/
git commit -m "refactor(i18n): migrate Group D Compose screens to stringResource

Replace uiText() and wrap hardcoded Chinese in 7 files (63 uiText + 16 hardcoded):
SessionSidebarPanel, ExportImportScreen, TeamTaskPanel,
AgentPresetConfigScreen, OrchestrationToolCallCard,
WorkspaceReadyView, AgentBubbleMessage."
```

---

## Task 6: Shrink ui_text_keys.json and update Gradle task

**Files:**
- Modify: `app/src/main/assets/ui_text_keys.json`
- Modify: `app/build.gradle.kts` (generateUiTextKeys task)
- Modify: `app/src/main/java/com/example/ui/theme/UiStrings.kt`

- [ ] **Step 1: Curate AI-override keys**

Identify ~30-50 keys that should remain overridable by AI (decorative/personality strings). These stay in `uiText()` and remain in `ui_text_keys.json`. All other keys have been migrated to `stringResource()` and should be removed.

Recommended AI-override keys (subset — final list depends on which strings are kept as `uiText()` after migration):
- `sidebar.title` ("OmniChat" — app personality)
- `sidebar.subtitle` ("多模型 · 长效记忆 · 多智能体")
- `chat.17bbe99c` → rename to `chat.ready_status` ("AI 准备就绪")
- `chat.1fda5871` → rename to `chat.welcome_title` ("欢迎使用长效记忆 AI 助手")
- `chat.aa2781f8` → rename to `chat.welcome_desc` (dual-model explanation)
- `topbar.title.*` (3 titles)
- `workspace.ready.hint`
- Other personality/descriptive text

For keys kept as `uiText()`, update their defaults to English since English is now primary:
```kotlin
// Before
uiText("sidebar.title", "OmniChat")
// After — same call, but default is now English (OmniChat is already English)
uiText("topbar.title.chat", "Chat")  // was "会话"
```

- [ ] **Step 2: Rewrite ui_text_keys.json with only AI-override keys**

Overwrite `app/src/main/assets/ui_text_keys.json` with only the curated ~30-50 AI-override keys, using English defaults.

- [ ] **Step 3: Update UiStrings.kt — add resolveUiString helper**

Add a helper function to `UiStrings.kt` for resolving `__res:` prefixed strings from DB entities:

```kotlin
/**
 * Resolve a display string that may be a literal or a __res: prefixed resource key.
 * Used for DB entity defaults that need i18n.
 */
fun resolveUiString(value: String, getString: (Int) -> String): String {
    if (value.startsWith("__res:")) {
        val resName = value.removePrefix("__res:")
        val resId = android.content.res.Resources.getSystem().getIdentifier(
            resName, "string", "com.example"
        )
        return if (resId != 0) getString(resId) else resName
    }
    return value
}
```

Note: The actual implementation should use the app's package context, not `Resources.getSystem()`. The caller passes a `getString` lambda that has the correct context. Example usage in Compose:

```kotlin
resolveUiString(session.title) { id -> stringResource(id) }
```

In non-composable code (ViewModel):
```kotlin
resolveUiString(session.title) { id -> getApplication<Application>().getString(id) }
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. The `generateUiTextKeys` task should now produce a much smaller JSON file (but since we manually wrote it, the task will regenerate it on next build — which is fine, it will match).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/ui_text_keys.json app/src/main/java/com/example/ui/theme/UiStrings.kt
git commit -m "refactor(i18n): shrink ui_text_keys.json to AI-override only

Keep ~30-50 decorative/personality keys in uiText() system.
Add resolveUiString() helper for __res: prefixed DB defaults.
Update uiText() defaults to English."
```

---

## Task 7: Migrate ViewModel non-UI strings

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/viewmodel/SettingsViewModel.kt`

ViewModels extend `AndroidViewModel` so they can access `getApplication<Application>().getString(R.string.xxx)`.

- [ ] **Step 1: Migrate ChatViewModel.kt** (9 Chinese strings)

Replace each Chinese string with a resource lookup:

```kotlin
// Line 112: session title default
// Before: "探讨与交谈 (Aesthetic Conversation)"
// After: getApplication<Application>().getString(R.string.default_session_title_display)

// Line 138: fallback session title
// Before: "探讨与交谈"
// After: getApplication<Application>().getString(R.string.default_session_title)

// Line 203: error message
// Before: "错误：未设置默认提供商。请在\"模型设置\"菜单中添加 Provider 并将其设为默认。"
// After: getApplication<Application>().getString(R.string.error_no_default_provider)

// Line 256: no memories placeholder
// Before: "无 (None recorded)"
// After: getApplication<Application>().getString(R.string.no_memories_recorded)

// Line 279: time instruction (AI system prompt injection — keep in both languages)
// This is an AI instruction, not user-visible. Use string resource so it matches user's locale.
// Before: "当前真实时间为 $dateTimeStr。请以此为准..."
// After: getApplication<Application>().getString(R.string.ai_time_instruction, dateTimeStr)

// Line 496: tool depth exceeded
// Before: "工具调用深度超过限制..."
// After: getApplication<Application>().getString(R.string.error_tool_depth_exceeded, MAX_TOOL_CALL_DEPTH)

// Line 957: model fetch error
// Before: "未获取到模型列表。"
// After: getApplication<Application>().getString(R.string.error_model_fetch_failed)

// Line 1143: default assistant name
// Before: "智能助手 (Default Assistant)"
// After: getApplication<Application>().getString(R.string.default_assistant_name)
```

Note: Lines 211 use `"探讨与交谈"` and `"新会话"` in `.startsWith()` comparisons for detecting auto-generated session titles. These must be updated to compare against BOTH the English AND Chinese resource values, or use a more robust detection method (e.g., a boolean flag on the session entity). The simplest fix:

```kotlin
// Before
val isAutoTitle = session.title.startsWith("探讨与交谈") || session.title.startsWith("新会话")

// After — compare against both locale versions
val zhDefault = getApplication<Application>().getString(R.string.default_session_title)
val enDefault = // English value hardcoded as fallback for cross-locale detection
val isAutoTitle = session.title.startsWith(zhDefault) || session.title.startsWith(enDefault)
    || session.title.startsWith("新会话") || session.title.startsWith("New Session")
```

- [ ] **Step 2: Migrate SettingsViewModel.kt** (6 Chinese strings)

```kotlin
// Line 173: export success
// Before: "导出成功"
// After: getApplication<Application>().getString(R.string.export_success)

// Line 175: export failed
// Before: "导出失败: ${e.message}"
// After: getApplication<Application>().getString(R.string.export_failed, e.message ?: "")

// Line 197: file unreadable
// Before: "无法读取文件"
// After: getApplication<Application>().getString(R.string.import_file_unreadable)

// Line 315: import success
// Before: "导入成功，共处理 $importedCount 条记录"
// After: getApplication<Application>().getString(R.string.import_success, importedCount)

// Line 317: import failed
// Before: "导入失败: ${e.message}"
// After: getApplication<Application>().getString(R.string.import_failed, e.message ?: "")

// Line 456: default scheme name
// Before: "导入方案"
// After: getApplication<Application>().getString(R.string.imported_scheme_name)
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt app/src/main/java/com/example/ui/viewmodel/SettingsViewModel.kt
git commit -m "refactor(i18n): migrate ViewModel strings to Resources.getString()

Replace 15 hardcoded Chinese strings in ChatViewModel and
SettingsViewModel with localized string resource lookups."
```

---

## Task 8: Migrate BuiltinToolHandler.kt (AI tool response strings)

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

This file has ~110+ Chinese strings that are returned as tool responses to the LLM. The AI reads these to understand operation results. Internationalizing them means the AI gets responses in the user's language.

BuiltinToolHandler does not extend AndroidViewModel. It needs `Context` or `Resources` passed to it. Check the constructor — it likely already receives context or can have it added.

- [ ] **Step 1: Add Context/Resources to BuiltinToolHandler**

If `BuiltinToolHandler` doesn't already have a `Context` reference, add one. Check its constructor in `McpRuntimeManager.kt` where it's instantiated. Add `private val context: Context` parameter if missing.

Once context is available, create a local helper:
```kotlin
private fun str(@StringRes resId: Int): String = context.getString(resId)
private fun str(@StringRes resId: Int, vararg args: Any): String = context.getString(resId, *args)
```

- [ ] **Step 2: Migrate tool response strings by category**

Migrate each handler method. Example patterns:

```kotlin
// handleGetCurrentTime
// Before: "当前时间信息"
// After: str(R.string.tool_time_info)

// handleFileWrite
// Before: "参数 'path' 不能为空"
// After: str(R.string.tool_file_path_empty)
// Before: "文件已写入"
// After: str(R.string.tool_file_written)

// handleSearchMemory
// Before: "搜索关键词"
// After: str(R.string.tool_memory_search_keyword)
// Before: "找到 ${results.size} 条相关记忆"
// After: str(R.string.tool_memory_results_count, results.size)
```

Process all ~110 strings across these handler methods:
- `handleUnknownTool` (1 string)
- `handleAdjustUi`, `handleResetUi` (3 strings)
- `handleGetCurrentTime` (5 strings)
- `handleColorScheme` (~15 strings)
- `handleSearchMemory` (~8 strings)
- `handleListUiTexts` (~12 strings)
- `handleSetUiTexts` (~6 strings)
- `handleListMcpToolGroups` (7 group descriptions)
- `handleConfigureMcpToolGroups`, `handleSetToolDisplayMode` (3 strings)
- File tools: `file_write`, `file_read`, `file_append`, `file_delete`, `file_list`, `file_search`, `file_info`, `file_move`, `file_copy`, `file_mkdir` (~40 strings)
- Document tools: `create_document` (~3 strings)
- Timer tools: `timer_create`, `timer_cancel`, `timer_list` (~12 strings)
- Duration formatting helper (3 strings)
- Capabilities preamble (1 string)

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(i18n): migrate BuiltinToolHandler to localized strings

Replace ~110 hardcoded Chinese AI tool response strings with
localized string resources. AI now receives responses in the
user's device language."
```

---

## Task 9: Migrate AgentRunner.kt and AgentDefinition.kt

**Files:**
- Modify: `app/src/main/java/com/example/workspace/AgentRunner.kt`
- Modify: `app/src/main/java/com/example/workspace/AgentDefinition.kt`

- [ ] **Step 1: Migrate AgentRunner.kt system messages** (category (a) strings)

Replace user-visible system messages injected into agent conversations:

```kotlin
// Line 192: tool iteration limit
// Before: "已达到最大工具调用次数限制 ($maxToolIterations)，强制结束本轮对话。"
// After: context.getString(R.string.agent_tool_iteration_limit, maxToolIterations)

// Line 402: consecutive failures
// Before: "连续工具调用失败 $MAX_CONSECUTIVE_TOOL_FAILURES 次..."
// After: context.getString(R.string.agent_consecutive_failures, MAX_CONSECUTIVE_TOOL_FAILURES)

// Line 724: compaction summary
// Before: "（以上对话已压缩，共 $oldCount 条消息。以下是早期关键信息摘要）"
// After: context.getString(R.string.agent_context_compacted, oldCount)

// Lines 734/738/742/750: role labels
// Before: "[系统]" / "[用户]" / "[助手]" / "[工具结果]"
// After: context.getString(R.string.role_system) / context.getString(R.string.role_user) / etc.

// Line 769: truncation marker
// Before: "[输出过长已由系统截断]"
// After: context.getString(R.string.agent_output_truncated)
```

- [ ] **Step 2: Migrate AgentRunner.kt detection strings** (category (d))

Lines 446-473 use Chinese strings as detection patterns (`.contains()` / `.endsWith()`). These detect agent state. Solution: use language-agnostic system markers instead of Chinese text.

```kotlin
// Line 446-473: detection strings
// Before: response.contains("等待用户输入") || response.contains("等待中")
// After: Use English markers that the AI system prompts will produce:
//        response.contains("WAITING_FOR_USER") || response.contains("等待中")

// Best approach: Define constants for detection markers and ensure both
// the system prompts and detection code use the same markers:
const val MARKER_WAITING = "WAITING_FOR_USER_INPUT"
const val MARKER_TASK_COMPLETE = "TASK_COMPLETE"
```

Then update the AgentDefinition system prompts (Step 3) to use these markers. This makes detection language-agnostic.

- [ ] **Step 3: Migrate AgentDefinition.kt** (12 entries + system prompts)

AgentDefinition has 4 built-in agent types (general-purpose, explore, plan, verification) each with Chinese display names, whenToUse descriptions, and massive system prompts.

For display names and descriptions, use string resources:
```kotlin
// Before: displayName = "通用 Agent"
// After: displayName = context.getString(R.string.agent_name_general)

// Before: whenToUse = "通用 Agent 用于研究复杂问题..."
// After: whenToUse = context.getString(R.string.agent_when_to_use_general)
```

For the 3 system prompts (EXPLORE: ~33 lines, PLAN: ~50 lines, VERIFICATION: ~117 lines), these are large multi-line strings. Options:
1. Store as `<string>` resources with `\n` newlines — works but hard to read
2. Store as `<string-array>` with one line per item — cleaner
3. Keep in code but use `if (locale == Chinese) ... else ...` branching

Recommended: Option 1 (string resource with `\n`). The system prompts define agent behavior instructions and should be in the user's language. Store each prompt as a single string resource with embedded `\n` for line breaks.

```kotlin
// Before: EXPLORE_SYSTEM_PROMPT = """你是一个快速探索代码库的 Agent..."""
// After: exploreSystemPrompt = context.getString(R.string.agent_prompt_explore)
```

Also add the detection markers (from Step 2) into the system prompts:
```
...如果等待用户输入，输出 WAITING_FOR_USER_INPUT ...
...任务完成时输出 TASK_COMPLETE ...
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/workspace/AgentRunner.kt app/src/main/java/com/example/workspace/AgentDefinition.kt
git commit -m "refactor(i18n): migrate AgentRunner and AgentDefinition to localized strings

- AgentRunner: 13 system messages + detection markers
- AgentDefinition: 4 agent names/descriptions + 3 system prompts (~500 lines)
- Detection strings replaced with language-agnostic MARKER constants"
```

---

## Task 10: Migrate DB entity defaults

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt`

- [ ] **Step 1: Update WorkspaceSession default title**

```kotlin
// Line 36: Entity default
// Before: @ColumnInfo(defaultValue = "新工作区") val title: String = "新工作区"
// After: @ColumnInfo(defaultValue = "__res:default_workspace_title") val title: String = "__res:default_workspace_title"
```

Note: The `defaultValue` in `@ColumnInfo` is used for Room schema generation. Changing it requires a database migration if the column has existing data. However, since this is a default value used only for NEW rows, existing rows keep their current title. No migration needed — the `defaultValue` annotation affects the SQL CREATE TABLE statement, and Room handles this via schema comparison.

Actually, since Room doesn't support changing column defaults without migration, keep the annotation as-is but change only the Kotlin default:

```kotlin
// Keep @ColumnInfo(defaultValue = "新工作区") for backwards compatibility
// Change only the Kotlin-side default for new instances
@ColumnInfo(defaultValue = "新工作区") val title: String = "__res:default_workspace_title"
```

- [ ] **Step 2: Update any other DB defaults**

Check `AgentInstance.agentName` — the comment says it's fixed to `"主控 Agent"` but this is likely set in code, not as a column default. Update the code that creates AgentInstance to use the string resource.

- [ ] **Step 3: Add string resources for DB defaults**

Ensure `strings.xml` / `strings-zh-rCN.xml` contain:
```xml
<!-- values/strings.xml -->
<string name="default_workspace_title">New Workspace</string>

<!-- values-zh-rCN/strings.xml -->
<string name="default_workspace_title">新工作区</string>
```

These should already exist from Task 1.

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/data/Entities.kt
git commit -m "refactor(i18n): use __res: prefix for DB entity defaults

WorkspaceSession.title uses __res:default_workspace_title for
i18n-aware display. Existing rows unaffected."
```

---

## Task 11: Create bilingual README

**Files:**
- Modify: `README.md` (rename current to README_zh.md, create English README)
- Create: `README_zh.md`

- [ ] **Step 1: Rename current README to README_zh.md**

```bash
git mv README.md README_zh.md
```

- [ ] **Step 2: Create English README.md**

Create `README.md` with the same structure as the Chinese version, fully translated. Include cross-link at the top:

```markdown
[中文版](README_zh.md)
```

Translate all sections:
- Title/tagline → "Android AI Assistant with Embedded MCP Agent Runtime — Let AI truly control your device"
- Core Features (9 bullet points)
- App Architecture (keep ASCII diagram, translate labels)
- Quick Start (environment, install, test commands)
- Project Structure (directory tree with translated comments)
- Tech Stack (table — most entries stay English)
- Database Tables (table — translate descriptions)
- MCP Tool Extension (table — translate descriptions)
- Multi-Agent Workspace (component list — translate descriptions)
- Release Build
- Contribution Guide (translate)
- Code conventions (update: "UI strings use Android strings.xml for i18n. AI-overridable strings use `uiText("key", "English default")` pattern.")
- License

- [ ] **Step 3: Update README_zh.md with cross-link**

Add at the top of the Chinese README:
```markdown
[English](README.md)
```

Also update the code conventions section to reflect the new i18n approach:
```markdown
### 代码规范

- Room 迁移规则：**只加列 / 加表，绝不删数据**
- UI 字符串使用 Android `strings.xml` 进行国际化（英文默认，中文 `values-zh-rCN`）
- AI 可调整的装饰性字符串使用 `uiText("key", "English default")` 模式
- 使用 `CompositionLocal` 传递主题和配置：`LocalUISettings`, `LocalCustomColors`, `LocalUiStrings`
```

- [ ] **Step 4: Commit**

```bash
git add README.md README_zh.md
git commit -m "docs(i18n): add English README, rename Chinese to README_zh.md

English README.md is now the primary (GitHub default).
Chinese version preserved as README_zh.md with cross-links.
Updated code conventions to reflect i18n architecture."
```

---

## Task 12: Update CLAUDE.md and AGENTS.md conventions

**Files:**
- Modify: `CLAUDE.md`
- Modify: `AGENTS.md` (if it exists and references UI string conventions)

- [ ] **Step 1: Update CLAUDE.md conventions section**

In `CLAUDE.md`, update the convention about Chinese UI strings:

```markdown
## Conventions

- **Internationalization**: English is the default language. Chinese Simplified is secondary.
  - Standard strings: `res/values/strings.xml` (English) + `res/values-zh-rCN/strings.xml` (Chinese)
  - Use `stringResource(R.string.key)` in Compose for all localized strings
  - AI-overridable decorative strings: `uiText("key", "English default")` via `LocalUiStrings`
  - Non-UI code: `context.getString(R.string.key)` or `getApplication<Application>().getString(R.string.key)`
  - DB entity defaults with `__res:` prefix resolved via `resolveUiString()`
- **Chinese UI strings** are ~~hardcoded in Compose~~ **now in Android strings.xml**. AI-adjustable strings use the `uiText("namespace.key", "English default")` pattern with auto-generated `ui_text_keys.json`
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(i18n): update CLAUDE.md conventions for new i18n architecture"
```

---

## Task 13: Build verification and cleanup

- [ ] **Step 1: Full debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 3: Check for remaining hardcoded Chinese in UI code**

Run a grep for Chinese characters in the `ui/` package (excluding comments and string resource defaults):
```bash
grep -rn '[一-鿿]' app/src/main/java/com/example/ui/ --include="*.kt" | grep -v '// ' | grep -v '/\*' | grep -v 'uiText(' | head -50
```

Any remaining Chinese should be either:
- Inside `uiText()` calls (AI-override defaults — OK)
- In comments (OK)
- In string resource XML files (OK)
- Actual missed hardcoded strings (NOT OK — fix them)

- [ ] **Step 4: Check that ui_text_keys.json is consistent**

Run: `./gradlew generateUiTextKeys`
Verify the regenerated JSON only contains AI-override keys (should be ~30-50 entries).

- [ ] **Step 5: Final commit if any cleanup needed**

```bash
git add -A
git commit -m "chore(i18n): cleanup remaining hardcoded strings and verify build"
```
