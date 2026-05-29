# OmniChat 鈥?AI Agent Instructions

An Android AI chat app with embedded MCP runtime support, long-term memory, multi-provider model configuration, AI-adjustable UI theming, and a multi-agent workspace system.

## Quick Reference

| Item | Value |
|------|-------|
| Package | `com.example` (applicationId: `com.aistudio.aichatmemory.qwzkvp`) |
| Min SDK | 26 路 Target SDK | 36 |
| Language | Kotlin 2.2.10 + C++17 (JNI bridges) |
| KSP | 2.2.10-2.0.2 |
| UI | Jetpack Compose (Material 3) |
| Build | AGP 9.1.1, KSP, CMake 3.22.1 |
| Architecture | MVVM + Repository (no DI framework) |

## Build & Run

```bash
./gradlew assembleDebug      # Debug build
./gradlew testDebugUnitTest    # Unit tests
./gradlew testDebugUnitTest --tests "com.example.YourTestClass" # Single test class
./gradlew connectedDebugAndroidTest # Instrumented tests (needs device/emulator)
./gradlew verifyRoborazziDebug  # Screenshot tests (Roborazzi)
./gradlew generateUiTextKeys   # Regenerate ui_text_keys.json (runs automatically before asset merge)
```

**Windows:** Use `gradlew.bat` (same flags). Shell note: PowerShell uses `;` not `&&` for chaining.

**Before first run:** No special configuration needed. API keys are configured per-provider in the app UI (妯″瀷閰嶇疆 tab). The signing config in `app/build.gradle.kts` has hardcoded release keystore passwords 鈥?do not commit real secrets.

**Environment note:** `gradle.properties` does NOT hardcode JDK path (set locally or via CI). Override `org.gradle.java.home` if needed. Kotlin compiler runs `in-process` to avoid daemon connection errors.

**CI:** `.github/workflows/release.yml` builds release APKs on tag push (`Release-V*.*`). No CI for PRs or unit tests.

**Build quirks:** Configuration cache and parallel builds enabled. Core library desugaring is on (`desugarJdkLibs`). `useLegacyPackaging = false` for jniLibs (mmap for faster .so loading). No minification (`isMinifyEnabled = false`).

## Architecture

```
Compose UI (Screens) 鈫?ViewModels 鈫?AppRepository 鈫?Room Database (16 entities, v30)
                  鈫?ApiClient (OkHttp + SSE, vision support)
                  鈫?McpRuntimeManager (Node.js / Python / remote_http via JNI)
```

- **Single Activity** (`MainActivity`) with three top-level views: `"chat"`, `"workspace"`, and `"settings"` (toggled via `mutableStateOf`)
- The `"settings"` view contains a **TabRow with 5 sub-tabs**: 妯″瀷閰嶇疆, MCP宸ュ叿, 闀挎晥璁板繂, Agent 棰勮, 鏁版嵁绠＄悊
- Session sidebar is a **ModalNavigationDrawer** (hamburger icon in top bar)
- **No DI framework** 鈥?ViewModels directly instantiate `AppDatabase` / `AppRepository`
- **`AndroidViewModel`** used instead of `ViewModel` to access `Application` context
- **Dual state management**: `mutableStateOf` for UI state, `StateFlow` for DB-driven reactive data
- **DB-driven theming**: `SettingsViewModel` synchronously pre-loads `UISettings` on startup to feed `MyApplicationTheme`, preventing theme flash

### Key Packages

| Package | Purpose |
|---------|---------|
| `com.example` | Entry point (`MainActivity.kt` 鈥?note lowercase 'm') |
| `com.example.data` | Room entities, DAOs, database (`AppDatabase.kt`), repository (`Repository.kt` contains class `AppRepository`) |
| `com.example.network` | OpenAI-compatible API client with SSE streaming (`ApiClient.kt`) |
| `com.example.mcp` | MCP runtime: `McpRuntimeManager`, `BuiltinToolHandler`, `McpPermissionManager`, `AskUserManager`, `TimerManager`, `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`, `McpViewModel` |
| `com.example.hooks` | Hook system: `HookManager`, `MessageHook`, `McpHook`, `AgentHook`, `McpFilePermissionHook`, `WorkspaceSandboxHook`, `LoggingHooks` |
| `com.example.workspace` | Multi-agent system: `TeamManager`, `AgentRunner`, `AgentContext`, `TeammateContext`, `AgentTool`, `AgentDefinition`, `AgentToolFilter`, `SendMessageTool`, `TaskTools`, `ToolOrchestrator`, `ProgressTracker`, `WorkspaceModels` |
| `com.example.ui.screens` | Compose screens: `MainScreen`, `ChatScreen`, `SessionSidebarPanel`, `WorkspaceScreen`, `WorkspaceToolbar`, `WorkspaceReadyView`, `AgentTabBar`, `AgentMessageArea`, `AgentBubbleMessage`, `OrchestrationToolCallCard`, `TeamTaskPanel`, `InterventionInput`, `AgentPresetConfigScreen`, `ExportImportScreen`, `ModelsConfigScreen`, `MemoryAndPromptScreen`, `McpConfigScreen`, `McpDialogs`, `AskUserDialog` |
| `com.example.ui.viewmodel` | `ChatViewModel`, `SettingsViewModel`, `WorkspaceViewModel` |
| `com.example.ui.components` | Reusable Compose components (`ChunkedStreamingText`, `MarkdownChunkParser`) |
| `com.example.ui.theme` | Material 3 theming with DB-driven dynamic color, `UiStrings` |

## Data Layer

Room database `ai_chat_memory_db` (version 30) with 16 entities. See `app/src/main/java/com/example/data/Entities.kt`.

| Entity | Table | Purpose |
|--------|-------|---------|
| `ModelConfig` | `model_configs` | Provider/model config, API key, custom headers |
| `Session` | `sessions` | Chat sessions |
| `Message` | `messages` | Chat messages (user/assistant/tool roles); `imagePath` for vision attachments |
| `MemoryItem` | `memory_items` | Cross-session memory facts with confidence scoring, tags, and reinforcement tracking |
| `PromptTemplate` | `prompt_templates` | System prompt templates |
| `FetchedModel` | `fetched_models` | Cached model list per provider |
| `SessionSummary` | `session_summaries` | Rolling 15-min session summaries |
| `McpServer` | `mcp_servers` | MCP server configs (node/python/remote_http) |
| `UISettings` | `ui_settings` | Single-row AI-adjustable color/font/layout settings; includes `isNodeEnabled`, `isPythonEnabled`, `enabledMcpGroups` |
| `ColorSchemePreset` | `color_scheme_presets` | Up to 5 named color scheme snapshots (UUID-keyed) |
| `AgentPreset` | `agent_presets` | Saved agent configurations for workspace |
| `WorkspaceSession` | `workspace_sessions` | Multi-agent workspace sessions |
| `AgentInstance` | `agent_instances` | Running agent instances within a workspace; `overrideModelId` for per-agent model override |
| `WorkspaceMessage` | `workspace_messages` | Messages in workspace agent conversations; `imagePath` for vision attachments |
| `TeamTask` | `team_tasks` | Agent team task management with status/blocking/`intendedAgent` |
| `McpFilePermission` | `mcp_file_permissions` | User's allow/deny choices for MCP file access outside sandbox |

26 sequential migrations exist (v4鈫抳30). Versions 1鈥? are handled by `fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)` 鈥?a safety net for very old installs only. Versions 4+ always have proper incremental migrations. **Rule: 鍙姞鍒?鍔犺〃锛岀粷涓嶅垹鏁版嵁** (only add columns/tables, never delete data).

**KSP codegen:** Room compiler + Moshi Kotlin codegen both run via KSP. Generated sources appear in `build/generated/ksp/`.

## Native Code (MCP Runtime)

- **Node.js**: `libnode.so` (nodejs-mobile) 鈫?`NodeJsBridge` (JNI) 鈫?TCP socket bridge 鈫?MCP JSON-RPC
- **Python**: `libpython3.14.so` (dlopen via `PythonRuntime`) 鈫?`ProcessBuilder` 鈫?stdin/stdout MCP JSON-RPC
- **Remote HTTP**: Direct HTTP/HTTPS connection to remote MCP servers (no native runtime needed)
- Node.js scripts go in `app/src/main/assets/node/`; `McpScriptManager` deploys built-in scripts (`mcp_filesystem.js`, `mcp_fetch.js`, `mcp_socket_bridge.js`, `mcp_multi_bridge.js`, `mcp_pkg_manager.js`) to `OmniChat/mcp/` on external storage
- Python stdlib in `assets/python/stdlib.zip`; `PythonRuntime` handles extraction, dlopen, and `Py_Initialize` lifecycle
- **Constraint**: Node.js can start only once per process (nodejs-mobile limitation) 鈥?merge multiple servers into one entry script
- Native runtimes are optional; app degrades gracefully without them
- CMake config: `app/src/main/cpp/CMakeLists.txt` with `c++_shared` STL

## Code Search: Always Use Index First

code-index has a symbol index for this project. **Use index tools first; fall back to grep/glob/read only when the index returns nothing.**

| Task | Preferred tool | Fallback |
|------|---------------|----------|
| Search code patterns/keywords | `code-index_search_code_advanced` | `grep` |
| Find files by name/pattern | `code-index_find_files` | `glob` |
| Get file summary before reading | `code-index_get_file_summary` | `read` |
| Find specific function/class source | `code-index_get_symbol_body` | `read` + manual locate |

Index may lag recent edits (6s debounce). Call `code-index_refresh_index` if stale.

## Conventions

- **File naming quirks**: `MainActivity.kt` uses lowercase 'm' (non-standard); `Repository.kt` file contains class `AppRepository` (file name 鈮?class name)
- **Chinese UI strings** are hardcoded in Compose (not `strings.xml`). Keep user-facing text in Chinese.
- **AI-adjustable UI strings** use the `uiText("namespace.key", "榛樿涓枃")` pattern. Keys are auto-collected by the `generateUiTextKeys` Gradle task into `assets/ui_text_keys.json`. `UiStrings` is a dynamic `Map<String, String>` 鈥?any key can be registered. Namespaces: `topbar.*`, `sidebar.*`, `chat.*`, `models.*`, `memory.*`, `mcp.*`, `preset.*`, `export.*`, `import.*`, `tab.settings.*`, `dialog.*`, `action.*`, `workspace.*`, `nav.*`, `tab.*`, `status.*`, `hint.*`, `icon.*` (~130 keys total; some legacy keys use hex IDs like `chat.4c423b81`)
- **CompositionLocals**: `MyApplicationTheme` provides `LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`
- **OpenAI-compatible API**: endpoint auto-correction strips `/chat/completions`, adds `/v1` for OpenAI
- **Thinking/reasoning support**: `reasoning_effort` (low/medium/high/xhigh) with `budget_tokens`
- **Memory system**: Dual-layer 鈥?session summaries (15-min rolling) + cross-session memory facts injected via `[CROSS_SESSION_MEMORY]` placeholder in prompts. LLM outputs structured JSON `{"ops": [...]}` with ADD/UPDATE/REINFORCE/DELETE; **pinned** memories are protected client-side
- **API keys**: Configured per-provider in the app UI (ModelConfig entity), never hardcoded
- **ABI filters**: Only `arm64-v8a` and `x86_64` are packaged
- **Custom HTTP headers**: `ModelConfig.customHeaders` is a JSON object string (e.g. `'{"X-Custom-Header": "value"}'`) sent with every API request for that provider
- **AI-adjustable theming**: Full Material 3 palette + font + layout stored in `UISettings` (id=1, single row). AI calls MCP tools to update colors, corner radius, spacing multiplier, and font settings at runtime. Color scheme snapshots saved as `ColorSchemePreset` (max 5)
- **MCP protocol versions**: Remote HTTP supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26)
- **Workspace (multi-agent)**: Orchestrator pattern 鈥?`TeamManager` manages teammates via `TeammateContext` coroutine elements, `AgentTool` spawns isolated SubAgents with configurable `AgentDefinition` (built-in: general-purpose, explore, plan, verification; custom: from `agent_presets` DB), `AgentRunner` implements the core LLM loop per agent with tool filtering via `AgentToolFilter`, `SendMessageTool` enables inter-agent communication, `TaskTools` handles task CRUD with auto-claim and blocking, `ToolOrchestrator` routes tool calls. `WorkspaceViewModel` manages workspace session lifecycle and agent tab UI. See `workspace/` package.
- **Key dependencies**: Compose BOM 2024.09.00, Room 2.7.0, CameraX 1.5.0, Coil 2.7.0, Firebase BOM 34.12.0 (platform only), Accompanist Permissions 0.37.3, compose-markdown 0.7.2, Roborazzi 1.59.0
- **Storage permissions**: Android 11+ needs `MANAGE_EXTERNAL_STORAGE` (settings page); required for MCP script deployment
- **Streaming internals**: SSE chunk prefixes `ERROR:`, `INFO:`, `TOOL_CALL_DELTA:`, `RETRY_RESET:` have special handling in `ChatViewModel`. UI updates throttled to 50ms intervals
- **TypeConverter**: `Converters` class in `AppDatabase.kt` handles `List<String>` 鈫?comma-separated string for `TeamTask.blockedBy`
- **Vision support**: `Message.imagePath` and `WorkspaceMessage.imagePath` store local image paths. `ApiClient.imageToBase64DataUrl()` auto-compresses and converts to base64. Sent as `image_url` content parts in OpenAI format.
- **MCP tool groups**: Built-in tools are grouped (core, memory, ui_appearance, ui_text, files, documents, efficiency). `UISettings.enabledMcpGroups` controls which groups are active. Users can toggle groups via `list_mcp_tool_groups`/`configure_mcp_tool_groups` tools.
- **Built-in MCP tools (34 total)**: See `McpRuntimeManager.kt` 鈫?`builtinTools` list. Categories: core (time, ask_user, tool groups, agent spawning, inter-agent messaging, task management, scratchpad KV store), memory (search), ui_appearance (9 tools for colors/fonts/layout), ui_text (list/set labels), files (7 CRUD ops + search/info/move), documents (create PDF/Excel/Word/PowerPoint), efficiency (timers)

## Common Tasks

- **Add a new Room entity**: Define in `Entities.kt`, add DAO in `Daos.kt`, update `AppDatabase` with new version + migration, expose in `AppRepository`
- **Add a new screen/tab**: Add composable in `ui/screens/`, wire into `MainScreen.kt` 鈥?either as a top-level view or a sub-tab inside `SettingsView`
- **Add MCP server support**: Add runtime config in `McpRuntimeManager`, bridge class following `NodeJsBridge`/`PythonBridge` patterns
- **Add or modify built-in MCP tools**: Add the tool schema in `McpRuntimeManager.kt` (`builtinTools`), and implement the tool logic in `BuiltinToolHandler.kt` (`handleBuiltinTool`)
- **Add or modify AI-adjustable UI strings**: Use the `uiText("namespace.key", "榛樿涓枃")` pattern in Compose screens. Keys are auto-collected into `assets/ui_text_keys.json`. No schema changes needed 鈥?`UiStrings` is a dynamic `Map<String, String>`. The `list_ui_texts`/`set_ui_texts` MCP tools handle runtime overrides.
- **Modify MCP config/management UI**: Edit `McpConfigScreen.kt` for the main list, and `McpDialogs.kt` for dialogs and overlays (edit/import servers, tool lists, runtime info)
- **Modify Main screens**: Edit `MainScreen.kt` for main Scaffold/topbar/drawer. Edit `ChatScreen.kt`, `SessionSidebarPanel.kt`, `WorkspaceScreen.kt`, `ModelsConfigScreen.kt`, or `MemoryAndPromptScreen.kt` for respective views/sidebars
- **Modify API client**: Edit `ApiClient.kt` 鈥?it handles SSE streaming, model discovery, thinking config, and custom headers
- **Modify theming**: `UISettings` entity drives the theme; `SettingsViewModel` loads it; `MyApplicationTheme` applies it. MCP tools in `BuiltinToolHandler` update it
- **Add reusable UI components**: Add to `com.example.ui.components` package
- **Modify workspace UI**: Edit `WorkspaceScreen.kt` for the main layout, `WorkspaceToolbar.kt` for top bar, `AgentTabBar.kt` for agent switching, `AgentMessageArea.kt` for message display, `TeamTaskPanel.kt` for task status, `InterventionInput.kt` for user input to agents
- **Modify workspace multi-agent logic**: Edit `TeamManager.kt` (facade), `AgentRunner.kt` (per-agent LLM loop), `AgentTool.kt` (SubAgent spawning), `ToolOrchestrator.kt` (tool routing), `TaskTools.kt` (task CRUD), `SendMessageTool.kt` (inter-agent messaging), `AgentDefinition.kt` (agent type registry)
