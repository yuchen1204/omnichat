# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OmniChat is an Android AI chat app with embedded MCP runtime support, long-term memory, multi-provider model configuration, and AI-adjustable UI theming.

## Build & Test Commands

```bash
# Debug build
./gradlew assembleDebug

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.example.YourTestClass"

# Android instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Screenshot tests (Roborazzi)
./gradlew verifyRoborazziDebug

# Generate UI text keys (runs automatically before asset merge)
./gradlew generateUiTextKeys
```

**Before first run:** Create `.env` in project root with `GEMINI_API_KEY=your_key` (see `.env.example`).

## Architecture

MVVM + Repository pattern, no DI framework. Single Activity (`MainActivity`) with two top-level views: `"chat"` and `"settings"` (toggled via `mutableStateOf`).

```
Compose UI (Screens) → ViewModels → AppRepository → Room Database
                                    ↘ ApiClient (OkHttp + SSE)
                                    ↘ McpRuntimeManager (Node.js / Python / remote_http via JNI)
```

### Key Architectural Decisions

- **No DI framework** — ViewModels directly instantiate `AppDatabase` / `AppRepository` using `AndroidViewModel` for Application context
- **Dual state management**: `mutableStateOf` for UI state, `StateFlow` for DB-driven reactive data
- **DB-driven theming**: `SettingsViewModel` synchronously pre-loads `UISettings` on startup to feed `MyApplicationTheme`, preventing theme flash
- **Chinese UI strings** are hardcoded in Compose (not `strings.xml`). AI-adjustable strings use the `uiText("namespace.key", "默认中文")` pattern with auto-generated `ui_text_keys.json`
- **Room database** version 19 with 18 sequential migrations. Rule: only add columns/tables, never delete data. Never use `fallbackToDestructiveMigration`
- **Node.js can start only once per process** (nodejs-mobile limitation) — merge multiple servers into one entry script
- **Native runtimes are optional**; app degrades gracefully without them

## Package Structure

| Package | Purpose |
|---------|---------|
| `com.example` | Entry point (`MainActivity.kt` — note lowercase 'm') |
| `com.example.data` | Room entities, DAOs, database (`AppDatabase.kt`), repository (`Repository.kt` contains class `AppRepository`) |
| `com.example.network` | OpenAI-compatible API client with SSE streaming (`ApiClient.kt`) |
| `com.example.mcp` | MCP runtime: `McpRuntimeManager`, `BuiltinToolHandler`, `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`, `McpViewModel` |
| `com.example.ui.screens` | Compose screens: `MainScreen`, `ChatScreen`, `SessionSidebarPanel`, `ModelsConfigScreen`, `MemoryAndPromptScreen`, `McpConfigScreen`, `McpDialogs` |
| `com.example.ui.viewmodel` | `ChatViewModel`, `SettingsViewModel` |
| `com.example.ui.components` | Reusable Compose components (`ChunkedStreamingText`, `MarkdownChunkParser`) |
| `com.example.ui.theme` | Material 3 theming with DB-driven dynamic color |
| `com.example.workspace` | Workspace feature (new) |

## Native Code (MCP Runtime)

- **Node.js**: `libnode.so` (nodejs-mobile) → `NodeJsBridge` (JNI) → TCP socket bridge → MCP JSON-RPC
- **Python**: `libpython3.14.so` (dlopen via `PythonRuntime`) → `ProcessBuilder` → stdin/stdout MCP JSON-RPC
- **Remote HTTP**: Direct HTTP/HTTPS connection to remote MCP servers (no native runtime needed)
- Node.js scripts in `app/src/main/assets/node/`; `McpScriptManager` deploys built-in scripts to `OmniChat/mcp/` on external storage
- CMake config: `app/src/main/cpp/CMakeLists.txt` with `c++_shared` STL
- ABI filters: only `arm64-v8a` and `x86_64`

## Common Modification Tasks

- **Add Room entity**: Define in `Entities.kt`, add DAO in `Daos.kt`, update `AppDatabase` with new version + migration, expose in `AppRepository`
- **Add screen/tab**: Add composable in `ui/screens/`, wire into `MainScreen.kt` — either as top-level view or sub-tab inside `SettingsView`
- **Add MCP server support**: Add runtime config in `McpRuntimeManager`, bridge class following `NodeJsBridge`/`PythonBridge` patterns
- **Add/modify built-in MCP tools**: Add tool schema in `McpRuntimeManager.kt` (`builtinTools`), implement logic in `BuiltinToolHandler.kt` (`handleBuiltinTool`)
- **Add/modify AI-adjustable UI strings**: Add fields to `UiStrings` in `ui/theme/UiStrings.kt`, update `fromJson`/`toJson`, add tool parameter in `McpRuntimeManager.kt` (`adjust_ui_strings` schema), implement in `BuiltinToolHandler.kt`, use `LocalUiStrings.current` in Compose screens
- **Modify MCP config UI**: `McpConfigScreen.kt` for main list, `McpDialogs.kt` for dialogs/overlays
- **Modify theming**: `UISettings` entity drives theme; `SettingsViewModel` loads it; `MyApplicationTheme` applies it; MCP tools in `BuiltinToolHandler` update it

## Conventions

- **CompositionLocals**: `MyApplicationTheme` provides `LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`
- **OpenAI-compatible API**: endpoint auto-correction strips `/chat/completions`, adds `/v1` for OpenAI
- **Memory system**: Dual-layer — session summaries (15-min rolling) + cross-session memory facts injected via `[CROSS_SESSION_MEMORY]` placeholder. LLM outputs structured JSON `{"ops": [...]}` with ADD/UPDATE/REINFORCE/DELETE; pinned memories protected client-side
- **API keys**: Managed via Secrets Gradle Plugin from `.env` file, never hardcoded
- **Custom HTTP headers**: `ModelConfig.customHeaders` is a JSON object string sent with every API request
- **Streaming internals**: SSE chunk prefixes `ERROR:`, `INFO:`, `TOOL_CALL_DELTA:`, `RETRY_RESET:` have special handling in `ChatViewModel`. UI updates throttled to 50ms intervals
- **No minification** (`isMinifyEnabled = false`)
- **MCP protocol versions**: Remote HTTP supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26)
