# Silent Tool Display Mode — Design Spec

**Date:** 2026-06-01
**Status:** Approved

## Problem

When the AI calls multiple tools during a conversation, each tool call renders as a full `ToolGroupCard` in the chat UI. This causes "screen flooding" — the user sees large expandable cards for every tool invocation, making the conversation hard to follow. The user wants the AI to be able to suppress this noise on demand.

## Solution

Add a new builtin MCP tool `set_tool_display_mode` that lets the AI toggle a "silent mode" for tool call display. When enabled, tool groups render as a compact single-line indicator instead of the full card.

## Tool Definition

**Name:** `set_tool_display_mode`
**Group:** `core` (always enabled)
**Parameters:**
- `silent` (boolean, required) — `true` to enable silent display, `false` to restore normal display

**Description (shown to AI):**
> Control how tool call results are displayed in the chat. When silent=true, tool calls show a compact "Working..." indicator instead of detailed cards. The user can still tap to expand. Use this when performing multiple sequential tool calls to avoid flooding the screen. Call set_tool_display_mode(silent=false) to restore the normal detailed display.

## Behavior

### Silent mode ON
- Tool groups render as a compact `SilentToolIndicator`:
  ```
  [icon] 工作中... (已调用 N 个工具)  [▼]
  ```
- Tapping the indicator expands to show the full `ToolGroupCard` details (same as normal mode)
- During streaming, when tool calls are being accumulated, the streaming bubble can show a brief "工作中..." state

### Silent mode OFF (default)
- Normal `ToolGroupCard` rendering (existing behavior, unchanged)

### Storage
- Add `silentToolCalls: Boolean = false` to `UISettings` entity
- DB migration v32 → v33: `ALTER TABLE ui_settings ADD COLUMN silentToolCalls INTEGER NOT NULL DEFAULT 0`
- Setting is global (persists across sessions). Once the AI enables it, it stays on until disabled.

## Files to Modify

### 1. `app/src/main/java/com/example/data/Entities.kt`
Add field to `UISettings`:
```kotlin
val silentToolCalls: Boolean = false,
```

### 2. `app/src/main/java/com/example/data/AppDatabase.kt`
- Bump version to 33
- Add `MIGRATION_32_33`:
  ```sql
  ALTER TABLE ui_settings ADD COLUMN silentToolCalls INTEGER NOT NULL DEFAULT 0
  ```
- Register migration in the builder

### 3. `app/src/main/java/com/example/mcp/McpRuntimeManager.kt`
Add `set_tool_display_mode` to `builtinTools` list:
```kotlin
McpTool(
    serverId = BUILTIN_SERVER_ID,
    serverName = BUILTIN_SERVER_NAME,
    name = "set_tool_display_mode",
    description = "Control how tool call results are displayed...",
    inputSchema = schema {
        prop("silent", "boolean", "true = compact indicator, false = full card") { }
        required("silent")
    }
)
```

### 4. `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`
- Add `"set_tool_display_mode"` case in `handleBuiltinTool` when-block
- Implement `handleSetToolDisplayMode(context, arguments)`:
  - Read current `UISettings` from DB
  - Update `silentToolCalls` field
  - Upsert back to DB
  - Return success response

### 5. `app/src/main/java/com/example/ui/components/ToolCallComponents.kt`
Add new composable `SilentToolIndicator`:
- Compact single-line card with tool icon + "工作中... (已调用 N 个工具)"
- Clickable to expand — when expanded, shows the full `ToolGroupCard` content
- Reuses existing `ToolGroupCard` internals for expanded state

### 6. `app/src/main/java/com/example/ui/screens/ChatScreen.kt`
In the `processedMessages` rendering:
- Read `LocalUISettings.current.silentToolCalls`
- When rendering a `List<*>` (tool group):
  - If `silentToolCalls == true` → render `SilentToolIndicator`
  - Else → render `ToolGroupCard` (existing behavior)

### 7. `app/src/main/java/com/example/ui/screens/AgentMessageArea.kt`
Same conditional rendering for workspace agent messages.

## Design Decisions

1. **Global vs per-session:** Global via `UISettings`. Simpler implementation, consistent with existing settings pattern. The AI can toggle it off when done.
2. **Expandable:** Silent mode doesn't permanently hide details — user can always tap to expand. This preserves access to tool call debugging info.
3. **Core group:** The tool belongs to the `core` group (always enabled), since it's a meta-tool about UI behavior, not a domain tool.
