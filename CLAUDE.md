# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OmniChat is an Android AI chat app with embedded MCP runtime support, long-term memory, multi-provider model configuration, and AI-adjustable UI theming. Key differentiators: 34 built-in MCP tools, orchestrator-based multi-agent workspace, cross-session associative memory, and cloud backup via Cloudflare Workers.

## Prerequisites & Requirements

- **Android Studio**: Hedgehog or later
- **JDK**: 17+
- **Android SDK**: 36 (compileSdk: 36, targetSdk: 36, minSdk: 26)
- **Kotlin**: 2.2.10

**Environment note:** `gradle.properties` does NOT hardcode JDK path. Set locally or via CI. Override `org.gradle.java.home` if needed. Kotlin compiler runs `in-process` to avoid daemon connection errors.

## Build & Test Commands

```bash
# Debug build
./gradlew assembleDebug

# Install debug APK
./gradlew installDebug

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

**CI:** `.github/workflows/release.yml` builds release APKs on tag push (`Release-V*.*`). `.github/workflows/post-merge-build.yml` runs on merge to main. No CI for PRs or unit tests.

**Windows:** Use `gradlew.bat` (same flags). PowerShell uses `;` not `&&` for chaining.

## Build Quirks & Configuration

- Configuration cache and parallel builds enabled (set in `gradle.properties`)
- Core library desugaring is on (`desugarJdkLibs`)
- `useLegacyPackaging = false` for jniLibs (mmap for faster .so loading)
- No minification (`isMinifyEnabled = false`)
- Signing config in `app/build.gradle.kts` uses env vars (`STORE_PASSWORD` / `KEY_PASSWORD` in CI, fallbacks locally)
- `generateUiTextKeys` is a custom Gradle task that scans Kotlin sources for `uiText()` calls and auto-generates `assets/ui_text_keys.json`. It runs as a dependency of asset-merge and lint tasks.
- Version name from CI env var `VERSION_NAME` (e.g. "1.2.3" → versionCode 10203); local builds default to 1.0/1

## Architecture

MVVM + Repository pattern, **no DI framework**. Single Activity (`MainActivity`) with three top-level views toggled via `mutableStateOf`:
- `"chat"` — ChatScreen (main chat interface)
- `"workspace"` — workspace screen (multi-agent, defined in `MainScreen.kt`)
- `"settings"` — SettingsView with **5 sub-tabs**: 模型配置, MCP工具, 长效记忆, Agent 预置, 数据管理

```
Compose UI (Screens) → ViewModels → AppRepository → Room Database (v55, 16 entities)
                                    ↘ ApiClient (OkHttp + SSE, vision support)
                                    ↘ ToolExecutor → ToolRegistry (interface-based tool system)
                                         ├ BuiltinTool (file ops, memory, UI, timers, etc.)
                                         └ McpRemoteTool (remote HTTP MCP servers)
```

### Major Refactor (current): Tool Interface System

The built-in tools are being migrated from a monolithic `McpRuntimeManager`/`BuiltinToolHandler` architecture to a clean `Tool` interface-based system:

| Component | Location | Purpose |
|-----------|----------|---------|
| `Tool` interface | `tool/Tool.kt` | Unified interface: `name`, `description`, `inputSchema`, `call()`, `validateInput()`, `checkPermissions()`, metadata flags |
| `ToolRegistry` | `tool/ToolRegistry.kt` | Singleton registry with name/alias lookup, group filtering, deny rules, OpenAI-format export |
| `ToolExecutor` | `tool/ToolExecutor.kt` | Routes calls, handles concurrency control (global semaphore + serial locks), permission checks, error handling |
| `BuiltinTool` | `tool/builtin/*.kt` | One file per tool (e.g. `FileReadTool.kt`, `AdjustUiTool.kt`) — 20+ individual tool implementations |
| `McpRemoteTool` | `tool/McpRemoteTool.kt` | Wraps remote MCP server tools in the Tool interface |
| `ToolInitializer` | `tool/ToolInitializer.kt` | Registers all tools on startup |

The old `McpRuntimeManager`/`BuiltinToolHandler` code still exists and may have overlapping functionality — when adding or modifying tools, **prefer the new `tool/` package** over the `mcp/` package.

### Key Architectural Decisions

- **No DI framework** — ViewModels directly instantiate `AppDatabase` / `AppRepository` using `AndroidViewModel` for Application context
- **Dual state management**: `mutableStateOf` for UI state, `StateFlow` for DB-driven reactive data
- **DB-driven theming**: `SettingsViewModel` synchronously pre-loads `UISettings` on startup to feed `MyApplicationTheme`, preventing theme flash
- **UI strings** use Android `strings.xml` for i18n (English default, Chinese in `values-zh-rCN`). AI-adjustable decorative strings use the `uiText("namespace.key", "默认中文")` pattern with auto-generated `ui_text_keys.json`
- **Room database** version 55 with sequential migrations (v4→v55). Versions 1–3 use `fallbackToDestructiveMigrationFrom` for legacy installs only. **Rule: only add columns/tables, never delete data. Never use `fallbackToDestructiveMigration`**
- **Foreground service** (`StreamingForegroundService`) keeps the process alive during LLM streaming

## Package Structure

| Package | Purpose |
|---------|---------|
| `com.omnichat` | Entry point (`MainActivity.kt` — note lowercase 'm'), `MyApplication` (backup scheduling), `StreamingForegroundService` |
| `com.omnichat.data` | Room entities (16 types in `Entities.kt`), DAOs (`Daos.kt`), database (`AppDatabase.kt` v55), repository (`Repository.kt`), `OmnifileFormat.kt` for binary export |
| `com.omnichat.network` | OpenAI-compatible API client with SSE streaming (`ApiClient.kt`), `ModelsDevCache.kt` |
| `com.omnichat.tool` | **New tool system**: `Tool` interface, `ToolRegistry`, `ToolExecutor`, `ToolInitializer`, `BuiltinTool` (one file per tool in `builtin/`), `McpRemoteTool` |
| `com.omnichat.mcp` | Legacy MCP runtime: `McpRuntimeManager`, `BuiltinToolHandler`, `McpPermissionManager`, `PermissionReviewManager`, `AskUserManager`, `TimerManager`/`TimerStorage`, `AlarmReceiver`/`BootReceiver`, `ToolSchemaDsl`, `ToolHandler`, `ToolUtils`, `UiFieldRegistry`, `McpViewModel` |
| `com.omnichat.memory` | Memory engine: `MemoryEngine.kt` (associations, embedding, FTS, BFS traversal), `MemoryTokenizer.kt` (CJK bigram + English) |
| `com.omnichat.workspace` | Multi-agent workspace: `TeamManager`, `AgentRunner`, `AgentTool`, `AgentDefinition`, `ToolOrchestrator`, `SendMessageTool`, `TaskTools`, `WorkspaceModels` |
| `com.omnichat.agent` | Legacy subAgent system: `SubAgent`, `SubAgentApproval`, `SubAgentApprovalManager`, `SubAgentEventBus`, `WorkflowEngine`, `WorkflowEventBus`, `WorkflowTemplates`, `AgentMessage` |
| `com.omnichat.cloud` | Cloud backup: `CloudBackupApi` (Retrofit), `CloudBackupRepository`, `CloudBackupManager`, `CloudBackupViewModel`, `CloudBackupDiagnosticViewModel`, `SslTestUtil` |
| `com.omnichat.ui.screens` | Compose screens: `MainScreen`, `ChatScreen`, `SessionSidebarPanel`, `ModelsConfigScreen`, `McpConfigScreen`, `McpDialogs`, `MemoryAndPromptScreen`, `ExportImportScreen`, `CloudBackupCard`, `AskUserDialog`, `PermissionManagerScreen`, `SubAgentTaskCard`, `WorkflowProgressCard` |
| `com.omnichat.ui.viewmodel` | `ChatViewModel`, `SettingsViewModel` |
| `com.omnichat.ui.components` | Reusable components: `ChunkedStreamingText`, `MarkdownChunkParser`, `ToolCallComponents` |
| `com.omnichat.ui.theme` | Material 3 theming with DB-driven dynamic color: `Color.kt`, `Theme.kt`, `Type.kt`, `UiStrings.kt`, `WindowSizeLocal.kt` |
| `com.omnichat.ui.performance` | Refresh rate & animation optimization: `RefreshRateManager`, `AnimationOptimizer`, `FrameRateMonitor`, etc. |
| `com.omnichat.update` | `UpdateChecker.kt` — GitHub tag-based version check |
| `com.omnichat.worker` | `CloudBackupWorker.kt` — WorkManager periodic backup |
| `com.omnichat.util` | `SessionLogExporter.kt` — export chat logs |
| `cloudflare-worker/` | Cloud backup backend (CF Workers + R2 + KV), separate from the Android app |

## MCP Runtime

- **Remote HTTP**: Direct HTTP/HTTPS connection to remote MCP servers (no native runtime needed)
- Supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26) protocols
- 34 built-in tools across 7 groups: core, memory, ui_appearance, ui_text, files, documents, efficiency
- MCP remote tools are wrapped as `McpRemoteTool` instances in the new `tool/` system
- Tool groups are controlled by `UISettings.enabledMcpGroups`; `core` group is always enabled

## Common Modification Tasks

- **Add Room entity**: Define in `Entities.kt`, add DAO in `Daos.kt`, update `AppDatabase` with new version + migration, expose in `AppRepository`
- **Add screen/tab**: Add composable in `ui/screens/`, wire into `MainScreen.kt` — either as top-level view or sub-tab inside `SettingsView`
- **Add/modify built-in tool (new system)**: Create a class implementing `Tool` in `tool/builtin/`, register it in `ToolInitializer`. No changes needed to `McpRuntimeManager`/`BuiltinToolHandler` unless the old system also needs to know about it.
- **Add/modify built-in tool (legacy system)**: Add tool schema in `McpRuntimeManager.kt` (`builtinTools`), implement logic in `BuiltinToolHandler.kt` (`handleBuiltinTool`)
- **Add/modify AI-adjustable UI strings**: Add fields to `UiStrings` in `ui/theme/UiStrings.kt`, update `fromJson`/`toJson`, add tool parameter in `tool/builtin/UiTextTools.kt`, implement in the tool, use `LocalUiStrings.current` in Compose screens
- **Modify MCP config UI**: `McpConfigScreen.kt` for main list, `McpDialogs.kt` for dialogs/overlays
- **Modify theming**: `UISettings` entity drives theme; `SettingsViewModel` loads it; `MyApplicationTheme` applies it; `tool/builtin/AdjustUiTool.kt` and `ColorSchemeTool.kt` handle AI adjustments
- **Modify workspace (multi-agent)**: Edit files in `workspace/` package — `TeamManager.kt` for lifecycle, `AgentRunner.kt` for LLM loop, `AgentTool.kt` for spawning, `TaskTools.kt` for task board

## Conventions

- **CompositionLocals**: `MyApplicationTheme` provides `LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`
- **OpenAI-compatible API**: endpoint auto-correction strips `/chat/completions`, adds `/v1` for OpenAI
- **Memory system**: Dual-layer — session summaries (15-min rolling) + cross-session memory facts injected via `[CROSS_SESSION_MEMORY]` placeholder. LLM outputs structured JSON `{"ops": [...]}` with ADD/UPDATE/REINFORCE/DELETE; pinned memories protected client-side. Supports embedding-based semantic search, FTS full-text search, and BFS association graph traversal.
- **API keys**: Configured per-provider in the app UI (ModelConfig entity), never hardcoded
- **Custom HTTP headers**: `ModelConfig.customHeaders` is a JSON object string sent with every API request
- **Streaming internals**: SSE chunk prefixes `ERROR:`, `INFO:`, `TOOL_CALL_DELTA:`, `RETRY_RESET:` have special handling in `ChatViewModel`. UI updates throttled to 50ms intervals
- **Vision support**: `Message.imagePath` stores local image paths. `ApiClient.imageToBase64DataUrl()` auto-compresses and converts to base64
- **Storage permissions**: Android 11+ needs `MANAGE_EXTERNAL_STORAGE` (settings page); required for MCP script deployment
- **Thinking/reasoning support**: `reasoning_effort` (low/medium/high/xhigh) with `budget_tokens`; `Session.thinkingEffort` stores per-session setting
- **MCP protocol versions**: Remote HTTP supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26)
- **Chinese UI**: User-facing text in Compose is hardcoded in Chinese (not `strings.xml`). Only `strings.xml` has English for i18n fallback. AI-adjustable strings use `uiText("key", "默认中文")`.
- **`@OptIn` annotations are used**: `ExperimentalMaterial3Api`, `ExperimentalFoundationApi`, `ExperimentalLayoutApi`, `ExperimentalCoroutinesApi` are all in use across the codebase

## Room Database Entities (16 total)

| Entity | Table | Notes |
|--------|-------|-------|
| `ModelConfig` | `model_configs` | Provider config (endpoint, API key, model ID, thinking, custom headers, embedding model) |
| `Session` | `sessions` | Chat session (title, thinkingEffort) |
| `Message` | `messages` | Chat messages (role, content, image paths, tool calls) |
| `MemoryItem` | `memory_items` | Long-term memory (content, tags, confidence, embedding, pinned, reminder) |
| `MemoryAssociation` | `memory_associations` | Memory graph edges (source → target with relation type) |
| `MemoryAuditEntry` | `memory_audit` | Audit log for memory operations |
| `PromptTemplate` | `prompt_templates` | Reusable system prompt templates |
| `SessionSummary` | `session_summaries` | 15-min rolling summaries |
| `FetchedModel` | `fetched_models` | Cached model list from provider APIs |
| `McpServer` | `mcp_servers` | Remote MCP server configs |
| `UISettings` | `ui_settings` | Theme (30 colors, layout, fonts, tool groups, color scheme presets) |
| `ColorSchemePreset` | `color_scheme_presets` | Saved color scheme presets (up to 5) |
| `McpFilePermission` | `mcp_file_permissions` | Granular file access permissions |
| `CloudBackupRecord` | `cloud_backup_records` | Cloud backup metadata |
| (`AgentPreset`) | `agent_presets` | Saved agent configurations (defined in `Daos.kt`) |
