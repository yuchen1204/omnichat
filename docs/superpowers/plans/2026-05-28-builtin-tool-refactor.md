# BuiltinToolHandler Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the 1942-line BuiltinToolHandler into per-group handler objects behind a common interface.

**Architecture:** BuiltinToolHandler becomes a thin facade with a static `toolName → handler` map. Seven handler objects (one per tool group) implement `ToolHandler` interface. Shared utilities extracted to `ToolUtils.kt`.

**Tech Stack:** Kotlin, Android

---

### Task 1: Create ToolHandler interface and ToolUtils

**Files:**
- Create: `app/src/main/java/com/example/mcp/ToolHandler.kt`
- Create: `app/src/main/java/com/example/mcp/ToolUtils.kt`

- [ ] **Step 1: Create ToolHandler interface**

Create `app/src/main/java/com/example/mcp/ToolHandler.kt`:

```kotlin
package com.example.mcp

import android.content.Context
import com.example.data.AppRepository
import org.json.JSONObject

/**
 * 内置工具处理器接口。每个工具组（core, memory, ui_appearance 等）实现此接口。
 */
interface ToolHandler {
    suspend fun handle(
        toolName: String,
        arguments: JSONObject,
        context: Context,
        repository: AppRepository,
        sessionId: Long?
    ): JSONObject
}
```

- [ ] **Step 2: Create ToolUtils with shared utilities**

Create `app/src/main/java/com/example/mcp/ToolUtils.kt`:

```kotlin
package com.example.mcp

import org.json.JSONArray
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置工具共享的工具函数。
 */
object ToolUtils {

    /** 构造统一的成功响应 */
    fun successResponse(text: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
    }

    /** 构造统一的错误响应 */
    fun errorResponse(message: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", message)
            })
        })
        put("isError", true)
    }

    /** 验证 HEX 颜色格式 #RRGGBB 或 #RRGGBBAA */
    fun isValidHex(hex: String?): Boolean {
        if (hex == null) return false
        return Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$").matches(hex)
    }

    /**
     * 将文本拆分为 token 集合：中文字符 + 中文字符 bigram + 英文/数字整词。
     * 用于 search_memory 的中文友好匹配。
     */
    fun bigramTokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val cjkRange = '一'..'鿿'
        val buffer = StringBuilder()

        for (ch in text) {
            if (ch in cjkRange) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch in "，。！？、；：“”‘’（）【】《》,.!?;:\"'()[]<>") {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString().lowercase())
        }

        // 中文字符 bigram（仅对原文中相邻的 CJK 字符生成）
        var prevCjk: Char? = null
        for (ch in text) {
            if (ch in cjkRange) {
                if (prevCjk != null) {
                    tokens.add("$prevCjk$ch")
                }
                prevCjk = ch
            } else {
                prevCjk = null
            }
        }

        return tokens
    }
}
```

- [ ] **Step 3: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL (new files, nothing references them yet)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/ToolHandler.kt app/src/main/java/com/example/mcp/ToolUtils.kt
git commit -m "refactor(mcp): add ToolHandler interface and ToolUtils shared utilities"
```

---

### Task 2: Extract MemoryToolHandler (1 tool)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/MemoryToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` (replace search_memory block)

- [ ] **Step 1: Create MemoryToolHandler**

Create `app/src/main/java/com/example/mcp/handlers/MemoryToolHandler.kt`:

```kotlin
package com.example.mcp.handlers

import android.content.Context
import com.example.data.AppRepository
import com.example.mcp.ToolHandler
import com.example.mcp.ToolUtils.bigramTokenize
import com.example.mcp.ToolUtils.errorResponse
import com.example.mcp.ToolUtils.successResponse
import org.json.JSONObject

object MemoryToolHandler : ToolHandler {

    override suspend fun handle(
        toolName: String,
        arguments: JSONObject,
        context: Context,
        repository: AppRepository,
        sessionId: Long?
    ): JSONObject = when (toolName) {
        "search_memory" -> handleSearchMemory(arguments, repository)
        else -> errorResponse("Unknown tool: $toolName")
    }

    private suspend fun handleSearchMemory(
        arguments: JSONObject,
        repository: AppRepository
    ): JSONObject {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return errorResponse("搜索失败：query 不能为空。")
        }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
        val allMemories = repository.getAllMemories()

        val queryTokens = bigramTokenize(query)

        data class ScoredMemory(val memory: com.example.data.MemoryItem, val score: Double)

        val scored = allMemories
            .mapNotNull { mem ->
                val memTokens = bigramTokenize(mem.content)
                val intersection = queryTokens.intersect(memTokens).size
                val union = queryTokens.union(memTokens).size
                if (union == 0 || intersection == 0) return@mapNotNull null
                val jaccard = intersection.toDouble() / union.toDouble()
                ScoredMemory(mem, jaccard * mem.confidence)
            }
            .sortedByDescending { it.score }
            .take(limit)

        val totalCount = allMemories.size
        val text = buildString {
            appendLine("记忆库搜索结果（关键词：「$query」，共 $totalCount 条记忆，命中 ${scored.size} 条）：")
            appendLine()
            if (scored.isEmpty()) {
                appendLine("未找到与关键词相关的记忆。")
                appendLine("提示：可以尝试更换关键词，或直接浏览全部记忆（记忆库共 $totalCount 条）。")
            } else {
                scored.forEachIndexed { i, sm ->
                    val pinnedTag = if (sm.memory.pinned) " [已锁定]" else ""
                    appendLine("${i + 1}. [id=${sm.memory.id}, 置信度=${sm.memory.confidence}, 相关度=${String.format("%.2f", sm.score)}$pinnedTag]")
                    appendLine("   ${sm.memory.content}")
                }
            }
        }
        return successResponse(text.trimEnd())
    }
}
```

- [ ] **Step 2: Replace search_memory in BuiltinToolHandler with delegation**

In `BuiltinToolHandler.kt`, find the `"search_memory" ->` block (around lines 473-530) and replace the entire block body with a single delegation call. The block currently starts with `val query = arguments.optString("query").trim()` and ends before the next `when` branch. Replace it with:

```kotlin
"search_memory" -> {
    MemoryToolHandler.handle(toolName, arguments, context, AppRepository(AppDatabase.getDatabase(context)), sessionId)
}
```

Add the import at the top: `import com.example.mcp.handlers.MemoryToolHandler`

- [ ] **Step 3: Remove the now-unused bigramTokenize from BuiltinToolHandler**

The `bigramTokenize` private function (around line 1899-1941) is now in `ToolUtils`. Remove it from `BuiltinToolHandler.kt`.

- [ ] **Step 4: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/MemoryToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract MemoryToolHandler (search_memory)"
```

---

### Task 3: Extract EfficiencyToolHandler (3 tools)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/EfficiencyToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Create EfficiencyToolHandler**

Read the `create_timer`, `cancel_timer`, `list_timers` blocks from `BuiltinToolHandler.kt` (around lines 989-1068). Create `app/src/main/java/com/example/mcp/handlers/EfficiencyToolHandler.kt` with the extracted code, using `successResponse`/`errorResponse` from `ToolUtils`.

The handler structure:

```kotlin
package com.example.mcp.handlers

import android.content.Context
import com.example.data.AppRepository
import com.example.mcp.ToolHandler
import com.example.mcp.ToolUtils.errorResponse
import com.example.mcp.ToolUtils.successResponse
import org.json.JSONObject

object EfficiencyToolHandler : ToolHandler {

    override suspend fun handle(
        toolName: String,
        arguments: JSONObject,
        context: Context,
        repository: AppRepository,
        sessionId: Long?
    ): JSONObject = when (toolName) {
        "create_timer" -> handleCreateTimer(arguments, context)
        "cancel_timer" -> handleCancelTimer(arguments, context)
        "list_timers" -> handleListTimers(context)
        else -> errorResponse("Unknown tool: $toolName")
    }

    // Move the 3 handler functions here from BuiltinToolHandler
    // ...
}
```

- [ ] **Step 2: Replace 3 blocks in BuiltinToolHandler with delegation**

Replace the `create_timer`, `cancel_timer`, `list_timers` blocks with delegation calls to `EfficiencyToolHandler.handle(...)`.

- [ ] **Step 3: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/EfficiencyToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract EfficiencyToolHandler (timers)"
```

---

### Task 4: Extract CoreToolHandler (7 tools)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/CoreToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Create CoreToolHandler**

Extract these 7 tools from `BuiltinToolHandler.kt`:
- `get_current_time` (lines 163-188)
- `ask_user` (lines 963-987)
- `scratchpad_write` (lines 1070-1079)
- `scratchpad_read` (lines 1081-1094)
- `scratchpad_list` (lines 1095-1115)
- `list_mcp_tool_groups` (lines 399-427)
- `configure_mcp_tool_groups` (lines 428-472)

**Important:** The scratchpad tools access `BuiltinToolHandler.teamManager`. CoreToolHandler needs access to this. Pass it as a parameter — add a `teamManager` field to the `handle` call context. The simplest approach: CoreToolHandler accesses `BuiltinToolHandler.teamManager` directly since it's a public `@Volatile` field.

```kotlin
package com.example.mcp.handlers

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.mcp.BuiltinToolHandler
import com.example.mcp.ToolHandler
import com.example.mcp.ToolUtils.errorResponse
import com.example.mcp.ToolUtils.successResponse
import org.json.JSONObject

object CoreToolHandler : ToolHandler {

    override suspend fun handle(
        toolName: String,
        arguments: JSONObject,
        context: Context,
        repository: AppRepository,
        sessionId: Long?
    ): JSONObject = when (toolName) {
        "get_current_time" -> handleGetCurrentTime(arguments)
        "ask_user" -> handleAskUser(arguments, context, sessionId)
        "scratchpad_write" -> handleScratchpadWrite(arguments)
        "scratchpad_read" -> handleScratchpadRead(arguments)
        "scratchpad_list" -> handleScratchpadList()
        "list_mcp_tool_groups" -> handleListMcpToolGroups(repository)
        "configure_mcp_tool_groups" -> handleConfigureMcpToolGroups(arguments, repository, context)
        else -> errorResponse("Unknown tool: $toolName")
    }

    // Move the 7 handler functions here
    // scratchpad functions access BuiltinToolHandler.teamManager directly
    // ...
}
```

- [ ] **Step 2: Replace 7 blocks in BuiltinToolHandler with delegation**

- [ ] **Step 3: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/CoreToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract CoreToolHandler (time, ask_user, scratchpad, groups)"
```

---

### Task 5: Extract UiAppearanceToolHandler (9 tools)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/UiAppearanceToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Create UiAppearanceToolHandler**

Extract these 9 tools:
- `get_ui_capabilities` (lines 32-37, delegates to `buildUiCapabilitiesResponse` at 1753-1893)
- `adjust_ui` (lines 51-162)
- `reset_ui_to_default` (lines 38-50)
- `save_color_scheme` (lines 189-233)
- `list_color_schemes` (lines 234-267)
- `apply_color_scheme` (lines 268-298)
- `delete_color_scheme` (lines 299-330)
- `adjust_font` (lines 331-378)
- `reset_font_to_default` (lines 379-398)

Also move `buildUiCapabilitiesResponse` (lines 1753-1893) and `isValidHex` (lines 68-71) — though `isValidHex` should use `ToolUtils.isValidHex` instead.

**Important:** The `adjust_ui` handler has inline `isValidHex` and `hex` helper functions. Replace the inline `isValidHex` with `ToolUtils.isValidHex`.

- [ ] **Step 2: Replace 9 blocks in BuiltinToolHandler with delegation**

- [ ] **Step 3: Remove buildUiCapabilitiesResponse from BuiltinToolHandler** (moved to UiAppearanceToolHandler)

- [ ] **Step 4: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/UiAppearanceToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract UiAppearanceToolHandler (theme, colors, fonts)"
```

---

### Task 6: Extract UiTextToolHandler (2 tools)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/UiTextToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Create UiTextToolHandler**

Extract:
- `list_ui_texts` (lines 617-697)
- `set_ui_texts` (lines 531-616)

- [ ] **Step 2: Replace 2 blocks in BuiltinToolHandler with delegation**

- [ ] **Step 3: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/UiTextToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract UiTextToolHandler (UI text overrides)"
```

---

### Task 7: Extract FileToolHandler (8 tools)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/FileToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Create FileToolHandler**

Extract these 8 tools:
- `file_write` (lines 699-718)
- `file_read` (lines 719-745)
- `file_append` (lines 746-760)
- `file_delete` (lines 761-775)
- `file_list` (lines 776-804)
- `file_search` (lines 805-838)
- `file_info` (lines 839-863)
- `file_move` (lines 864-891)

Also move `resolveSafePath` (lines 1645-1668) and `searchFiles` (lines 1679-1714) helper functions.

- [ ] **Step 2: Replace 8 blocks in BuiltinToolHandler with delegation**

- [ ] **Step 3: Remove resolveSafePath and searchFiles from BuiltinToolHandler**

- [ ] **Step 4: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/FileToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract FileToolHandler (file CRUD + search)"
```

---

### Task 8: Extract DocumentToolHandler (1 tool)

**Files:**
- Create: `app/src/main/java/com/example/mcp/handlers/DocumentToolHandler.kt`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Create DocumentToolHandler**

Extract `create_document` (lines 892-962). This is the largest single tool handler with PDF/XLSX/DOCX/PPTX generation logic. Also move the helper functions for document creation (lines 1130-1730 approximately — `createPdfDocument`, `createXlsxDocument`, `createDocxDocument`, `createPptxDocument` and their sub-helpers).

**Important fix:** Change `catch (e: Throwable)` to `catch (e: Exception)` at the outer try-catch of create_document.

- [ ] **Step 2: Replace create_document block in BuiltinToolHandler with delegation**

- [ ] **Step 3: Remove all document helper functions from BuiltinToolHandler**

- [ ] **Step 4: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mcp/handlers/DocumentToolHandler.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): extract DocumentToolHandler (PDF/XLSX/DOCX/PPTX)"
```

---

### Task 9: Convert BuiltinToolHandler to facade

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: Rewrite BuiltinToolHandler as facade**

The `when` block should now only contain delegation calls. Replace the entire `handleBuiltinTool` method with the facade pattern. Remove all remaining handler code, private functions, and imports that are no longer needed.

The final `BuiltinToolHandler.kt` should look like:

```kotlin
package com.example.mcp

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.mcp.handlers.CoreToolHandler
import com.example.mcp.handlers.DocumentToolHandler
import com.example.mcp.handlers.EfficiencyToolHandler
import com.example.mcp.handlers.FileToolHandler
import com.example.mcp.handlers.MemoryToolHandler
import com.example.mcp.handlers.UiAppearanceToolHandler
import com.example.mcp.handlers.UiTextToolHandler
import org.json.JSONObject

object BuiltinToolHandler {

    // WHY: 由 WorkspaceViewModel 在创建/清理 TeamManager 时设置，供 scratchpad 工具访问
    @Volatile
    var teamManager: com.example.workspace.TeamManager? = null

    private val handlers = mapOf(
        "get_current_time" to CoreToolHandler,
        "ask_user" to CoreToolHandler,
        "scratchpad_write" to CoreToolHandler,
        "scratchpad_read" to CoreToolHandler,
        "scratchpad_list" to CoreToolHandler,
        "list_mcp_tool_groups" to CoreToolHandler,
        "configure_mcp_tool_groups" to CoreToolHandler,
        "search_memory" to MemoryToolHandler,
        "get_ui_capabilities" to UiAppearanceToolHandler,
        "adjust_ui" to UiAppearanceToolHandler,
        "reset_ui_to_default" to UiAppearanceToolHandler,
        "save_color_scheme" to UiAppearanceToolHandler,
        "list_color_schemes" to UiAppearanceToolHandler,
        "apply_color_scheme" to UiAppearanceToolHandler,
        "delete_color_scheme" to UiAppearanceToolHandler,
        "adjust_font" to UiAppearanceToolHandler,
        "reset_font_to_default" to UiAppearanceToolHandler,
        "list_ui_texts" to UiTextToolHandler,
        "set_ui_texts" to UiTextToolHandler,
        "file_write" to FileToolHandler,
        "file_read" to FileToolHandler,
        "file_append" to FileToolHandler,
        "file_delete" to FileToolHandler,
        "file_list" to FileToolHandler,
        "file_search" to FileToolHandler,
        "file_info" to FileToolHandler,
        "file_move" to FileToolHandler,
        "create_document" to DocumentToolHandler,
        "create_timer" to EfficiencyToolHandler,
        "cancel_timer" to EfficiencyToolHandler,
        "list_timers" to EfficiencyToolHandler,
    )

    suspend fun handleBuiltinTool(
        context: Context,
        toolName: String,
        arguments: JSONObject,
        sessionId: Long? = null
    ): JSONObject {
        val handler = handlers[toolName]
            ?: return ToolUtils.errorResponse("Unknown built-in tool: $toolName")
        val repository = AppRepository(AppDatabase.getDatabase(context))
        return handler.handle(toolName, arguments, context, repository, sessionId)
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "refactor(mcp): slim BuiltinToolHandler to facade-only (~60 lines)"
```

---

### Task 10: Verify and clean up

- [ ] **Step 1: Verify no unused imports remain in BuiltinToolHandler.kt**

Run: `gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL with no warnings about unused imports

- [ ] **Step 2: Verify McpRuntimeManager still works (no changes needed)**

Confirm that `McpRuntimeManager.kt` line 1591 still calls `BuiltinToolHandler.handleBuiltinTool(context, toolName, arguments, sessionId)` — this should not have changed.

- [ ] **Step 3: Final commit if any cleanup needed**

```bash
git add -A
git commit -m "refactor(mcp): cleanup unused imports after handler extraction"
```
