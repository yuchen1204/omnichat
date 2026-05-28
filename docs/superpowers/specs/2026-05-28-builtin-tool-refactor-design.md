# BuiltinToolHandler Refactor — Per-Group Handler Decomposition

Date: 2026-05-28

## Problem

`BuiltinToolHandler.kt` is 1942 lines with a single 1096-line `when` block. All 31 tool handlers are inline, making the file hard to navigate, test, and maintain. Response construction is inconsistent (13 tools use verbose JSON, 10 use helpers). `AppRepository` is instantiated 12 times.

## Solution

Split into per-group handler objects behind a common interface. BuiltinToolHandler becomes a thin facade.

## Architecture

```
McpRuntimeManager.handleBuiltinTool()
    ↓
BuiltinToolHandler.handleBuiltinTool()  ← Facade
    ↓ routes by toolName
    ├→ CoreToolHandler
    ├→ MemoryToolHandler
    ├→ UiAppearanceToolHandler
    ├→ UiTextToolHandler
    ├→ FileToolHandler
    ├→ DocumentToolHandler
    └→ EfficiencyToolHandler
```

## Interface

```kotlin
interface ToolHandler {
    suspend fun handle(
        toolName: String,
        arguments: JSONObject,
        context: Context,
        repository: AppRepository
    ): JSONObject
}
```

All 7 handlers implement this interface. `context` and `repository` are passed as parameters — handlers are stateless `object` singletons.

## Facade (BuiltinToolHandler)

```kotlin
object BuiltinToolHandler {
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
        toolName: String,
        arguments: JSONObject,
        context: Context
    ): JSONObject {
        val handler = handlers[toolName]
            ?: return ToolUtils.errorResponse("Unknown tool: $toolName")
        val repository = AppRepository(AppDatabase.getDatabase(context))
        return handler.handle(toolName, arguments, context, repository)
    }
}
```

~30 lines of routing. `AppRepository` created once per call.

## File Structure

```
com/example/mcp/
├── BuiltinToolHandler.kt          ← facade (~30 lines after cleanup)
├── ToolHandler.kt                 ← interface definition
├── ToolUtils.kt                   ← successResponse, errorResponse, isValidHex, etc.
├── handlers/
│   ├── CoreToolHandler.kt
│   ├── MemoryToolHandler.kt
│   ├── UiAppearanceToolHandler.kt
│   ├── UiTextToolHandler.kt
│   ├── FileToolHandler.kt
│   ├── DocumentToolHandler.kt
│   └── EfficiencyToolHandler.kt
```

## Handler Sizes (estimated)

| Handler | Tools | Est. Lines |
|---------|-------|-----------|
| CoreToolHandler | 7 | ~200 |
| MemoryToolHandler | 1 | ~80 |
| UiAppearanceToolHandler | 9 | ~400 |
| UiTextToolHandler | 2 | ~180 |
| FileToolHandler | 8 | ~250 |
| DocumentToolHandler | 1 | ~200 |
| EfficiencyToolHandler | 3 | ~100 |

## ToolUtils.kt

Shared utilities extracted from BuiltinToolHandler:

- `successResponse(text: String): JSONObject`
- `errorResponse(text: String): JSONObject`
- `isValidHex(color: String): Boolean`
- `bigramTokenize(text: String): Set<String>` (used by MemoryToolHandler)

## Migration Strategy

Incremental — one group at a time, each step compiles:

1. Create `ToolHandler` interface + `ToolUtils.kt`
2. Extract `MemoryToolHandler` (1 tool, simplest)
3. Extract `EfficiencyToolHandler` (3 tools)
4. Extract `CoreToolHandler` (7 tools)
5. Extract `UiAppearanceToolHandler` (9 tools, largest)
6. Extract `UiTextToolHandler` (2 tools)
7. Extract `FileToolHandler` (8 tools)
8. Extract `DocumentToolHandler` (1 tool, complex)
9. Slim down `BuiltinToolHandler` to facade-only
10. Unify all response construction to use `successResponse()`/`errorResponse()`

Each step: move code → compile → commit.

## Side Improvements (included in refactor)

- All 13 verbose response constructions replaced with `successResponse()`/`errorResponse()`
- `create_document` catch changed from `Throwable` to `Exception`
- Hex regex deduplicated into `ToolUtils.isValidHex()`

## Out of Scope

- `adjust_ui` field-by-field diff optimization (separate task)
- `search_memory` indexing / performance (separate task)
- `file_append` seek optimization (separate task)
- `list_ui_texts` asset caching (separate task)
- `resolveSafePath` security tightening (separate task)

## Files Changed

| File | Action |
|------|--------|
| `BuiltinToolHandler.kt` | Gut to facade, remove all handler code |
| `ToolHandler.kt` | New — interface |
| `ToolUtils.kt` | New — shared utilities |
| `handlers/CoreToolHandler.kt` | New |
| `handlers/MemoryToolHandler.kt` | New |
| `handlers/UiAppearanceToolHandler.kt` | New |
| `handlers/UiTextToolHandler.kt` | New |
| `handlers/FileToolHandler.kt` | New |
| `handlers/DocumentToolHandler.kt` | New |
| `handlers/EfficiencyToolHandler.kt` | New |

No changes to `McpRuntimeManager.kt` — it still calls `BuiltinToolHandler.handleBuiltinTool()`.
