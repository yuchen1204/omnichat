# OmniChat — AI Agent Instructions

An Android AI chat app with embedded MCP runtime support, long-term memory, multi-provider model configuration, and AI-adjustable UI theming.

## Quick Reference

| Item | Value |
|------|-------|
| Package | `com.example` (applicationId: `com.aistudio.aichatmemory.qwzkvp`) |
| Min SDK | 24 · Target SDK | 36 |
| Language | Kotlin 2.2.10 + C++17 (JNI bridges) |
| KSP | 2.2.10-2.0.2 |
| UI | Jetpack Compose (Material 3) |
| Build | AGP 9.1.1, KSP, CMake 3.22.1 |
| Architecture | MVVM + Repository (no DI framework) |

## Build & Run

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run Android instrumented tests
./gradlew connectedDebugAndroidTest

# Screenshot tests (Roborazzi)
./gradlew verifyRoborazziDebug
```

**Before first run:** Create `.env` in project root with `GEMINI_API_KEY=your_key` (see `.env.example`). For debug builds, remove `signingConfig = signingConfigs.getByName("debugConfig")` from `app/build.gradle.kts` if present.

## Architecture

```
Compose UI (Screens) → ViewModels → AppRepository → Room Database (10 entities)
                                    ↘ ApiClient (OkHttp + SSE)
                                    ↘ McpRuntimeManager (Node.js / Python / remote_http via JNI)
```

- **Single Activity** (`MainActivity`) with two top-level views: `"chat"` and `"settings"` (toggled via `mutableStateOf`)
- The `"settings"` view contains a **TabRow with 3 sub-tabs**: 模型配置, MCP工具, 长效记忆
- Session sidebar is a **ModalNavigationDrawer** (hamburger icon in top bar)
- **No DI framework** — ViewModels directly instantiate `AppDatabase` / `AppRepository`
- **`AndroidViewModel`** used instead of `ViewModel` to access `Application` context
- **Dual state management**: `mutableStateOf` for UI state, `StateFlow` for DB-driven reactive data
- **DB-driven theming**: `SettingsViewModel` synchronously pre-loads `UISettings` on startup to feed `MyApplicationTheme`, preventing theme flash

### Key Packages

| Package | Purpose |
|---------|---------|
| `com.example` | Entry point (`MainActivity.kt`) |
| `com.example.data` | Room entities, DAOs, database, repository |
| `com.example.network` | OpenAI-compatible API client with SSE streaming |
| `com.example.mcp` | MCP runtime: `McpRuntimeManager`, `BuiltinToolHandler`, `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`, `McpViewModel` |
| `com.example.ui.screens` | Compose screens and dialogs (`MainScreen`, `ChatScreen`, `SessionSidebarPanel`, `ModelsConfigScreen`, `MemoryAndPromptScreen`, `McpConfigScreen`, `McpDialogs`) |
| `com.example.ui.viewmodel` | `ChatViewModel`, `SettingsViewModel` |
| `com.example.ui.components` | Reusable Compose components (`ChunkedStreamingText`, `MarkdownChunkParser`) |
| `com.example.ui.theme` | Material 3 theming with DB-driven dynamic color |

## App Structure

- **会话中心 (Chat)** — Multi-session chat with SSE streaming; session list in a ModalNavigationDrawer
- **设置 (Settings)** — Three sub-tabs:
  1. **模型配置** — Provider/model configuration, endpoint + API key management, custom HTTP headers
  2. **MCP工具** — MCP server CRUD, runtime status, tool discovery
  3. **长效记忆** — Cross-session memory items, prompt templates

## Data Layer

Room database `ai_chat_memory_db` (version 19) with 10 entities. See `app/src/main/java/com/example/data/Entities.kt`.

| Entity | Table | Purpose |
|--------|-------|---------|
| `ModelConfig` | `model_configs` | Provider/model config, API key, custom headers |
| `Session` | `sessions` | Chat sessions |
| `Message` | `messages` | Chat messages (user/assistant/tool roles) |
| `MemoryItem` | `memory_items` | Cross-session memory facts with confidence scoring |
| `PromptTemplate` | `prompt_templates` | System prompt templates |
| `FetchedModel` | `fetched_models` | Cached model list per provider |
| `SessionSummary` | `session_summaries` | Rolling 15-min session summaries |
| `McpServer` | `mcp_servers` | MCP server configs (node/python/remote_http) |
| `UISettings` | `ui_settings` | Single-row AI-adjustable color/font/layout settings |
| `ColorSchemePreset` | `color_scheme_presets` | Up to 5 named color scheme snapshots (UUID-keyed) |

18 sequential migrations exist (v1→v19). Always add new migrations when changing schemas — do not rely on `fallbackToDestructiveMigration`. **Rule: 只加列/加表，绝不删数据** (only add columns/tables, never delete data).

## Native Code (MCP Runtime)

- **Node.js**: `libnode.so` (nodejs-mobile) → `NodeJsBridge` (JNI) → TCP socket bridge → MCP JSON-RPC
- **Python**: `libpython3.14.so` (dlopen via `PythonRuntime`) → `ProcessBuilder` → stdin/stdout MCP JSON-RPC
- **Remote HTTP**: Direct HTTP/HTTPS connection to remote MCP servers (no native runtime needed)
- Node.js scripts go in `app/src/main/assets/node/`; `McpScriptManager` deploys built-in scripts (`mcp_filesystem.js`, `mcp_fetch.js`, `mcp_socket_bridge.js`, `mcp_multi_bridge.js`, `mcp_pkg_manager.js`) to `OmniChat/mcp/` on external storage
- Python stdlib in `assets/python/stdlib.zip`; `PythonRuntime` handles extraction, dlopen, and `Py_Initialize` lifecycle
- **Constraint**: Node.js can start only once per process (nodejs-mobile limitation) — merge multiple servers into one entry script
- Native runtimes are optional; app degrades gracefully without them
- CMake config: `app/src/main/cpp/CMakeLists.txt` with `c++_shared` STL

## Conventions

- **File naming quirks**: `MainActivity.kt` uses lowercase 'm' (non-standard); `Repository.kt` file contains class `AppRepository` (file name ≠ class name)
- **Chinese UI strings** are hardcoded in Compose (not `strings.xml`). Keep user-facing text in Chinese.
- **AI-adjustable UI strings** use the `uiText("namespace.key", "默认中文")` pattern. Keys are auto-collected by the `generateUiTextKeys` Gradle task into `assets/ui_text_keys.json`. Namespaces: `topbar.*`, `sidebar.*`, `chat.*`, `models.*`, `memory.*`, `mcp.*`, `dialog.*`, `action.*`
- **CompositionLocals**: `MyApplicationTheme` provides `LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`
- **OpenAI-compatible API**: endpoint auto-correction strips `/chat/completions`, adds `/v1` for OpenAI
- **Thinking/reasoning support**: `reasoning_effort` (low/medium/high/xhigh) with `budget_tokens`
- **Memory system**: Dual-layer — session summaries (15-min rolling) + cross-session memory facts injected via `[CROSS_SESSION_MEMORY]` placeholder in prompts. LLM outputs structured JSON `{"ops": [...]}` with ADD/UPDATE/REINFORCE/DELETE; **pinned** memories are protected client-side
- **API keys**: Managed via Secrets Gradle Plugin from `.env` file, never hardcoded
- **ABI filters**: Only `arm64-v8a` and `x86_64` are packaged
- **Custom HTTP headers**: `ModelConfig.customHeaders` is a JSON object string (e.g. `'{"X-Custom-Header": "value"}'`) sent with every API request for that provider
- **AI-adjustable theming**: Full Material 3 palette + font + layout stored in `UISettings` (id=1, single row). AI calls MCP tools to update colors, corner radius, spacing multiplier, and font settings at runtime. Color scheme snapshots saved as `ColorSchemePreset` (max 5)
- **MCP protocol versions**: Remote HTTP supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26)
- **Storage permissions**: Android 11+ needs `MANAGE_EXTERNAL_STORAGE` (settings page); required for MCP script deployment
- **Streaming internals**: SSE chunk prefixes `ERROR:`, `INFO:`, `TOOL_CALL_DELTA:`, `RETRY_RESET:` have special handling in `ChatViewModel`. UI updates throttled to 50ms intervals
- **No minification** (`isMinifyEnabled = false`); `useLegacyPackaging = false` for jniLibs (allows mmap for faster .so loading)

## Common Tasks

- **Add a new Room entity**: Define in `Entities.kt`, add DAO in `Daos.kt`, update `AppDatabase` with new version + migration, expose in `AppRepository`
- **Add a new screen/tab**: Add composable in `ui/screens/`, wire into `MainScreen.kt` — either as a top-level view or a sub-tab inside `SettingsView`
- **Add MCP server support**: Add runtime config in `McpRuntimeManager`, bridge class following `NodeJsBridge`/`PythonBridge` patterns
- **Add or modify built-in MCP tools (e.g. UI customization, color scheme presets, time tools)**: Add the tool schema in `McpRuntimeManager.kt` (`builtinTools`), and implement the tool logic in `BuiltinToolHandler.kt` (`handleBuiltinTool`)
- **Add or modify AI-adjustable UI strings**: Add fields to `UiStrings` in `ui/theme/UiStrings.kt`, update `fromJson`/`toJson` methods, add the tool parameter in `McpRuntimeManager.kt` (`adjust_ui_strings` schema), implement in `BuiltinToolHandler.kt`, then use `LocalUiStrings.current` in Compose screens
- **Modify MCP config/management UI**: Edit `McpConfigScreen.kt` for the main list, and `McpDialogs.kt` for dialogs and overlays (edit/import servers, tool lists, runtime info)
- **Modify Main screens**: Edit `MainScreen.kt` for main Scaffold/topbar/drawer. Edit `ChatScreen.kt`, `SessionSidebarPanel.kt`, `ModelsConfigScreen.kt`, or `MemoryAndPromptScreen.kt` for respective views/sidebars
- **Modify API client**: Edit `ApiClient.kt` — it handles SSE streaming, model discovery, thinking config, and custom headers
- **Modify theming**: `UISettings` entity drives the theme; `SettingsViewModel` loads it; `MyApplicationTheme` applies it. MCP tools in `BuiltinToolHandler` update it
- **Add reusable UI components**: Add to `com.example.ui.components` package
