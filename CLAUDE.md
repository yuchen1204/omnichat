# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OmniChat is an Android AI chat app with embedded MCP runtime support, long-term memory, multi-provider model configuration, and AI-adjustable UI theming.

## Prerequisites & Requirements

- **Android Studio**: Hedgehog or later
- **JDK**: 17+
- **Android SDK**: 36 (compileSdk: 36, targetSdk: 36, minSdk: 26)
- **CMake**: 3.22.1
- **NDK**: 27.0.12077973 (for JNI native bridges)
- **Kotlin**: 2.2.10

**Environment note:** `gradle.properties` does NOT hardcode JDK path. Set locally or via CI. Override `org.gradle.java.home` if needed. Kotlin compiler runs `in-process` to avoid daemon connection errors.

## Build & Test Commands

```bash
# Debug build
./gradlew assembleDebug

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.omnichat.YourTestClass"

# Android instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Screenshot tests (Roborazzi)
./gradlew verifyRoborazziDebug

# Generate UI text keys (runs automatically before asset merge)
./gradlew generateUiTextKeys
```

**Before first run:** No special configuration needed. API keys are configured per-provider in the app UI (模型配置 tab).

**CI:** `.github/workflows/release.yml` builds release APKs on tag push (`Release-V*.*`). No CI for PRs or unit tests.

**Windows:** Use `gradlew.bat` (same flags). PowerShell uses `;` not `&&` for chaining.

## Build Quirks & Configuration

- Configuration cache and parallel builds enabled
- Core library desugaring is on (`desugarJdkLibs`)
- `useLegacyPackaging = false` for jniLibs (mmap for faster .so loading)
- No minification (`isMinifyEnabled = false`)
- Signing config in `app/build.gradle.kts` uses env vars (`STORE_PASSWORD` / `KEY_PASSWORD` in CI, fallbacks locally)

## Architecture

MVVM + Repository pattern, no DI framework. Single Activity (`MainActivity`) with three top-level views: `"chat"`, `"workspace"`, and `"settings"` (toggled via `mutableStateOf`). The `"settings"` view contains a **TabRow with 5 sub-tabs**: 模型配置, MCP工具, 长效记忆, Agent 预设, 数据管理.

```
Compose UI (Screens) → ViewModels → AppRepository → Room Database (20 tables, v35)
                                    ↘ ApiClient (OkHttp + SSE, vision support)
                                    ↘ McpRuntimeManager (Node.js / Python / remote_http via JNI)
```

### Key Architectural Decisions

- **No DI framework** — ViewModels directly instantiate `AppDatabase` / `AppRepository` using `AndroidViewModel` for Application context
- **Dual state management**: `mutableStateOf` for UI state, `StateFlow` for DB-driven reactive data
- **DB-driven theming**: `SettingsViewModel` synchronously pre-loads `UISettings` on startup to feed `MyApplicationTheme`, preventing theme flash
- **UI strings** use Android `strings.xml` for i18n (English default, Chinese in `values-zh-rCN`). AI-adjustable decorative strings use the `uiText("namespace.key", "English default")` pattern with auto-generated `ui_text_keys.json`
- **Room database** version 35 with 30+ sequential migrations (v4→v35). Versions 1–3 use `fallbackToDestructiveMigrationFrom` for legacy installs only. **Rule: only add columns/tables, never delete data. Never use `fallbackToDestructiveMigration`**
- **Node.js can start only once per process** (nodejs-mobile limitation) — merge multiple servers into one entry script
- **Native runtimes are optional**; app degrades gracefully without them

## Package Structure

| Package | Purpose |
|---------|---------|
| `com.omnichat` | Entry point (`MainActivity.kt` — note lowercase 'm') |
| `com.omnichat.data` | Room entities, DAOs, database (`AppDatabase.kt`), repository (`Repository.kt` contains class `AppRepository`) |
| `com.omnichat.network` | OpenAI-compatible API client with SSE streaming (`ApiClient.kt`) |
| `com.omnichat.mcp` | MCP runtime: `McpRuntimeManager`, `BuiltinToolHandler`, `McpPermissionManager`, `AskUserManager`, `TimerManager`, `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`, `McpViewModel` |
| `com.omnichat.hooks` | Hook system: `HookManager`, `MessageHook`, `McpHook`, `AgentHook`, `McpFilePermissionHook`, `WorkspaceSandboxHook`, `LoggingHooks` |
| `com.omnichat.ui.screens` | Compose screens: `MainScreen`, `ChatScreen`, `SessionSidebarPanel`, `WorkspaceScreen`, `WorkspaceToolbar`, `WorkspaceReadyView`, `AgentTabBar`, `AgentMessageArea`, `AgentBubbleMessage`, `OrchestrationToolCallCard`, `TeamTaskPanel`, `InterventionInput`, `AgentPresetConfigScreen`, `ExportImportScreen`, `ModelsConfigScreen`, `MemoryAndPromptScreen`, `McpConfigScreen`, `McpDialogs`, `AskUserDialog` |
| `com.omnichat.ui.viewmodel` | `ChatViewModel`, `SettingsViewModel`, `WorkspaceViewModel` |
| `com.omnichat.ui.components` | Reusable Compose components (`ChunkedStreamingText`, `MarkdownChunkParser`) |
| `com.omnichat.ui.theme` | Material 3 theming with DB-driven dynamic color, `UiStrings` |
| `com.omnichat.workspace` | Multi-agent system: `TeamManager`, `AgentRunner`, `AgentContext`, `TeammateContext`, `AgentTool`, `AgentDefinition`, `AgentToolFilter`, `SendMessageTool`, `TaskTools`, `ToolOrchestrator`, `ProgressTracker`, `WorkspaceModels` |

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
- **Add/modify built-in MCP tools**: Add tool schema in `McpRuntimeManager.kt` (`builtinTools`), implement logic in `BuiltinToolHandler.kt` (`handleBuiltinTool`). Tools are grouped (core, memory, ui_appearance, ui_text, files, documents, efficiency); `UISettings.enabledMcpGroups` controls active groups
- **Add/modify AI-adjustable UI strings**: Add fields to `UiStrings` in `ui/theme/UiStrings.kt`, update `fromJson`/`toJson`, add tool parameter in `McpRuntimeManager.kt` (`adjust_ui_strings` schema), implement in `BuiltinToolHandler.kt`, use `LocalUiStrings.current` in Compose screens
- **Add hooks**: Implement `MessageHook`, `McpHook`, or `AgentHook` interface in `com.omnichat.hooks` package, register with `HookManager` (object with register/unregister methods)
- **Modify MCP config UI**: `McpConfigScreen.kt` for main list, `McpDialogs.kt` for dialogs/overlays
- **Modify theming**: `UISettings` entity drives theme; `SettingsViewModel` loads it; `MyApplicationTheme` applies it; MCP tools in `BuiltinToolHandler` update it
- **Modify workspace multi-agent logic**: Edit `TeamManager.kt` (facade), `AgentRunner.kt` (per-agent LLM loop), `AgentTool.kt` (SubAgent spawning), `ToolOrchestrator.kt` (tool routing), `TaskTools.kt` (task CRUD), `SendMessageTool.kt` (inter-agent messaging), `AgentDefinition.kt` (agent type registry)
- **Modify workspace UI**: Edit `WorkspaceScreen.kt` for main layout, `WorkspaceToolbar.kt` for top bar, `AgentTabBar.kt` for agent switching, `AgentMessageArea.kt` for message display, `TeamTaskPanel.kt` for task status, `InterventionInput.kt` for user input to agents

## Conventions

- **CompositionLocals**: `MyApplicationTheme` provides `LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`
- **OpenAI-compatible API**: endpoint auto-correction strips `/chat/completions`, adds `/v1` for OpenAI
- **Memory system**: Dual-layer — session summaries (15-min rolling) + cross-session memory facts injected via `[CROSS_SESSION_MEMORY]` placeholder. LLM outputs structured JSON `{"ops": [...]}` with ADD/UPDATE/REINFORCE/DELETE; pinned memories protected client-side
- **API keys**: Configured per-provider in the app UI (ModelConfig entity), never hardcoded
- **Custom HTTP headers**: `ModelConfig.customHeaders` is a JSON object string sent with every API request
- **Streaming internals**: SSE chunk prefixes `ERROR:`, `INFO:`, `TOOL_CALL_DELTA:`, `RETRY_RESET:` have special handling in `ChatViewModel`. UI updates throttled to 50ms intervals
- **Vision support**: `Message.imagePath` and `WorkspaceMessage.imagePath` store local image paths. `ApiClient.imageToBase64DataUrl()` auto-compresses and converts to base64
- **Storage permissions**: Android 11+ needs `MANAGE_EXTERNAL_STORAGE` (settings page); required for MCP script deployment
- **Thinking/reasoning support**: `reasoning_effort` (low/medium/high/xhigh) with `budget_tokens`
- **MCP protocol versions**: Remote HTTP supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26)
- **Workspace (multi-agent)**: Orchestrator pattern — `TeamManager` manages teammates via `TeammateContext` coroutine elements, `AgentTool` spawns isolated SubAgents with configurable `AgentDefinition` (built-in: general-purpose, explore, plan, verification; custom: from `agent_presets` DB), `AgentRunner` implements the core LLM loop per agent with tool filtering via `AgentToolFilter`, `SendMessageTool` enables inter-agent communication, `TaskTools` handles task CRUD with auto-claim and blocking, `ToolOrchestrator` routes tool calls. See `workspace/` package and tests in `app/src/test/java/com/example/workspace/`
