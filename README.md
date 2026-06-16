[中文版](README_zh.md)

# OmniChat

> Android AI Assistant with MCP Runtime -- Let AI truly control your device

<div align="center">

![Min SDK](https://img.shields.io/badge/Min%20SDK-26-green?logo=android)
![Target SDK](https://img.shields.io/badge/Target%20SDK-36-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

## Core Features

- **MCP Runtime** -- Connect to remote MCP servers via HTTP/HTTPS, supporting both SSE (2024-11-05) and Streamable HTTP (2025-03-26) protocols; 34 built-in tools covering file ops, UI theming, memory, documents, timers, and more
- **Multi-Agent Workspace** -- Orchestrator pattern with `TeamManager`, `AgentRunner`, `AgentTool` for spawning isolated SubAgents; supports inter-agent messaging, shared task board, agent presets, and per-agent model override
- **Cross-Session Memory System** -- Dual-layer: 15-minute rolling session summaries + long-term memory items with confidence scoring, embedding-based semantic search, FTS full-text search, memory association graph (BFS traversal), tag system, and time-based reminders
- **AI-Adjustable UI** -- Full Material 3 palette (30 color fields), layout parameters (corner radius, spacing), font settings (scale, family), color scheme presets (up to 5), and ~130 AI-editable UI text labels
- **Cloud Backup** -- Cloudflare Worker backend with TOTP authentication, R2 storage; supports `.omniconfig`, `.omnidb`, `.omnifile` formats with section-selective backup and periodic scheduling
- **Multimedia Capabilities** -- Multi-image vision support (camera capture + gallery picker), document generation (PDF/Excel/Word/PowerPoint), AlarmManager timers with repeating tasks
- **Multi-Model Support** -- OpenAI-compatible API; supports Gemini, OpenAI, DeepSeek, local models; per-provider custom HTTP headers, embedding model config, thinking/reasoning mode with budget_tokens
- **SSE Streaming** -- Real-time streaming with typewriter effect, chunked rendering, foreground service to prevent process death, special chunk prefixes for tool calls and retries
- **Hook System** -- Extensible hooks for message interception, tool execution control, file permission management, and SubAgent approval
- **Version Check** -- GitHub tag-based update checking with semantic version comparison

## App Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                       Compose UI Layer                           │
│  ┌──────────┐  ┌───────────┐  ┌────────────┐  ┌──────────────┐ │
│  │ChatScreen│  │ Workspace │  │  Settings  │  │    Sidebar   │ │
│  │          │  │  Screen   │  │  (5 tabs)  │  │    Drawer    │ │
│  └────┬─────┘  └─────┬─────┘  └─────┬──────┘  └──────┬───────┘ │
├───────┴───────────────┴──────────────┴────────────────┴─────────┤
│                       ViewModel Layer                            │
│  ┌──────────────┐  ┌─────────────────┐  ┌─────────────────────┐ │
│  │ ChatViewModel│  │SettingsViewModel│  │WorkspaceViewModel   │ │
│  └──────┬───────┘  └───────┬─────────┘  └──────────┬──────────┘ │
├─────────┴──────────────────┴───────────────────────┴────────────┤
│                   Data / Repository Layer                        │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         AppRepository (Room DB v47, 16 entities)           │ │
│  └────────────────────────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────────────┤
│                    MCP Runtime Layer                             │
│  ┌───────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  Remote HTTP  │  │  SubAgent    │  │  Cloud Backup        │ │
│  │  (SSE+Stream) │  │  Executor    │  │  (CF Worker + R2)    │ │
│  └───────────────┘  └──────────────┘  └──────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

Single Activity architecture (`MainActivity`), three top-level views: Chat, Workspace, Settings (with 5 sub-tabs: 模型配置, MCP工具, 长效记忆, Agent 预置, 数据管理).

## Quick Start

### Requirements

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 36

### Install

```bash
# 1. Clone the repository
git clone https://github.com/yuchen1204/omnichat.git
cd omnichat

# 2. Build and install the Debug build
./gradlew assembleDebug
./gradlew installDebug
```

### Running Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Single test class
./gradlew testDebugUnitTest --tests "com.omnichat.YourTestClass"

# Android instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Screenshot tests (Roborazzi)
./gradlew verifyRoborazziDebug

# Regenerate UI text keys (usually runs automatically)
./gradlew generateUiTextKeys
```

## Project Structure

```
omnichat/
├── app/src/main/java/com/omnichat/
│   ├── MainActivity.kt              # Entry Activity
│   ├── MyApplication.kt             # Application class (backup scheduling)
│   ├── StreamingForegroundService.kt # Foreground service during LLM streaming
│   ├── data/                        # Data layer
│   │   ├── Entities.kt              # 16 Room entity definitions
│   │   ├── Daos.kt                  # DAO interfaces
│   │   ├── AppDatabase.kt           # Database config (v47, 43 migrations)
│   │   ├── Repository.kt            # Repository (AppRepository)
│   │   └── OmnifileFormat.kt        # Binary export format (.omnifile)
│   ├── network/
│   │   └── ApiClient.kt            # OpenAI-compatible API client (SSE, vision, embedding)
│   ├── mcp/                         # MCP runtime
│   │   ├── McpRuntimeManager.kt     # Runtime manager (34 built-in tools)
│   │   ├── BuiltinToolHandler.kt    # Built-in tool handler
│   │   ├── McpPermissionManager.kt  # MCP file permission manager
│   │   ├── AskUserManager.kt        # ask_user tool suspend/resume
│   │   ├── TimerManager.kt          # Dual-track timer (AlarmManager + Handler)
│   │   ├── TimerStorage.kt          # Timer disk persistence
│   │   ├── ToolSchemaDsl.kt         # JSON Schema DSL for tool definitions
│   │   ├── UiFieldRegistry.kt       # AI-adjustable UI field metadata
│   │   └── McpViewModel.kt          # MCP config ViewModel
│   ├── hooks/                       # Hook system
│   │   ├── HookManager.kt           # Hook manager
│   │   ├── HookInterfaces.kt        # Hook interface definitions
│   │   ├── LoggingHooks.kt          # Logging hooks
│   │   ├── McpFilePermissionHook.kt # File permission hook
│   │   └── AgentApprovalHook.kt     # SubAgent approval hook
│   ├── memory/                      # Memory engine
│   │   ├── MemoryEngine.kt          # Cross-session memory (associations, embedding, FTS)
│   │   └── MemoryTokenizer.kt       # CJK bigram + English tokenizer
│   ├── workspace/                   # Multi-agent workspace
│   │   ├── TeamManager.kt           # Team facade
│   │   ├── AgentRunner.kt           # Per-agent LLM loop
│   │   ├── AgentTool.kt             # SubAgent spawning
│   │   ├── AgentDefinition.kt       # Agent type registry
│   │   ├── ToolOrchestrator.kt      # Tool routing
│   │   ├── SendMessageTool.kt       # Inter-agent messaging
│   │   ├── TaskTools.kt             # Task CRUD
│   │   └── WorkspaceModels.kt       # Workspace data models
│   ├── agent/                       # Legacy subAgent system
│   │   ├── AgentExecutor.kt         # Task execution engine
│   │   ├── AgentPrompts.kt          # System prompt templates
│   │   └── AgentTeamManager.kt      # Inter-agent messaging
│   ├── cloud/                       # Cloud backup
│   │   ├── CloudBackupApi.kt        # Retrofit API interface
│   │   ├── CloudBackupRepository.kt # Auth + API client management
│   │   ├── CloudBackupManager.kt    # Backup/restore operations
│   │   └── CloudBackupViewModel.kt  # Cloud backup ViewModel
│   ├── update/
│   │   └── UpdateChecker.kt         # GitHub tag-based version check
│   ├── worker/
│   │   └── CloudBackupWorker.kt     # WorkManager periodic backup
│   └── ui/
│       ├── screens/                 # Compose screens
│       ├── viewmodel/               # ViewModels
│       ├── components/              # Reusable components
│       ├── theme/                   # Material 3 theme system
│       └── performance/             # Refresh rate & animation optimization
├── app/src/main/assets/
│   └── node/                        # Node.js MCP scripts
├── cloudflare-worker/               # Cloud backup backend (CF Workers + R2 + KV)
└── scripts/                         # Utility scripts
```

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3) |
| Database | Room v2.7.0 (v47, 16 entities) |
| Networking | OkHttp + SSE + Retrofit 2.12.0 |
| Serialization | Moshi 1.15.2 |
| Firebase | Firebase BOM 34.12.0 |
| Build | AGP 9.1.1, KSP 2.2.10-2.0.2 |
| Document Generation | Apache POI 5.5.1 |
| Permissions | Accompanist Permissions 0.37.3 |
| Image Loading | Coil 2.7.0 |
| Camera | CameraX 1.5.0 |
| Markdown | compose-markdown 0.7.2 |
| Background Work | WorkManager |
| QR Code | ZXing |
| Testing | JUnit, Robolectric, Roborazzi 1.59.0 |
| CI/CD | GitHub Actions (automated release builds) |

## MCP Tool Extension

### Built-in Tools (34 total)

| Group | Tool | Description |
|-------|------|-------------|
| core | `get_current_time` | Get current date/time with timezone |
| core | `ask_user` | Ask clarifying question with single/multi-select options |
| core | `list_mcp_tool_groups` | List available tool groups and status |
| core | `configure_mcp_tool_groups` | Enable/disable tool groups |
| core | `delegate_task` | Delegate task to SubAgent |
| core | `check_task_status` | Query SubAgent task status |
| core | `list_agent_tasks` | List all SubAgent tasks in session |
| core | `send_message` | Inter-agent messaging |
| core | `read_inbox` | Read agent inbox |
| core | `manage_task_board` | Shared task board (create/claim/complete/list) |
| core | `approve_agent_request` | Approve/reject SubAgent file operations |
| memory | `search_memory` | Search long-term memory with BFS association traversal |
| memory | `mark_reminded` | Mark time reminder as delivered |
| ui_appearance | `get_ui_capabilities` | Query UI theme manifest and current values |
| ui_appearance | `adjust_ui` | Adjust full Material 3 theme (30 colors + layout + fonts) |
| ui_appearance | `color_scheme` | Save/list/apply/delete color scheme presets |
| ui_text | `list_ui_texts` | List all adjustable UI text strings |
| ui_text | `set_ui_texts` | Override UI text labels |
| files | `file_write` | Write file (UTF-8 or base64) |
| files | `file_read` | Read file (byte/line range support) |
| files | `file_append` | Append to file |
| files | `file_delete` | Delete file/directory (recursive option) |
| files | `file_list` | List directory (recursive, depth control) |
| files | `file_search` | Search by name pattern or content (regex) |
| files | `file_info` | Get file metadata |
| files | `file_move` | Move/rename file |
| files | `file_copy` | Copy file/directory |
| files | `file_mkdir` | Create directory |
| documents | `create_document` | Generate PDF/Excel/Word/PowerPoint |
| efficiency | `create_timer` | Create one-shot or repeating timer with notifications |
| efficiency | `cancel_timer` | Cancel pending timer |
| efficiency | `list_timers` | List all pending timers |
| efficiency | `set_tool_display_mode` | Control tool display visibility in chat UI |

### Multi-Agent Workspace

OmniChat supports an orchestrator-based multi-agent workspace:

- **TeamManager** -- Facade managing teammate lifecycle via `TeammateContext` coroutine elements
- **AgentRunner** -- Per-agent LLM loop with tool filtering via `AgentToolFilter`
- **AgentTool** -- Spawns isolated SubAgents with configurable `AgentDefinition`
- **Inter-Agent Communication** -- `SendMessageTool` for direct agent-to-agent messaging, inbox system
- **Shared Task Board** -- `TaskTools` for task CRUD with auto-claim and blocking
- **Agent Presets** -- Saved agent configurations stored in `agent_presets` DB table
- **Per-Agent Model Override** -- Each agent instance can use a different model

**Built-in Agent Types:** general-purpose, explore, plan, verification; custom types from saved presets.

### Adding Custom MCP Servers

1. Go to **Settings -> MCP Tools** tab and tap **Add**
2. Configure the server:
   - **Remote HTTP**: Enter the server URL (supports SSE 2024-11-05 and Streamable HTTP 2025-03-26)
3. Supports standard `mcpServers` JSON format import

## Cloud Backup

OmniChat supports cloud backup via a Cloudflare Worker backend:

- **Authentication**: TOTP-based (no password required), QR code binding, account recovery
- **Storage**: Cloudflare R2 object storage with KV metadata
- **Backup Formats**:
  - `.omniconfig` -- JSON config (providers, MCP servers, memories, templates, UI settings, presets)
  - `.omnidb` -- Full SQLite database
  - `.omnifile` -- Binary format with selectable sections
- **Periodic Backup**: WorkManager with configurable frequency (3h/6h/12h/24h/manual)
- **Backend Source**: `cloudflare-worker/` directory

## Release Build

Pushing a `Release-V*.*` tag automatically triggers a GitHub Actions release APK build:

```bash
git tag Release-V0.5
git push origin main --tags
```

Artifacts are published to [GitHub Releases](https://github.com/yuchen1204/omnichat/releases).

## Contribution Guide

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Conventions

- Room migration rule: **only add columns / tables, never delete data**
- Chinese UI strings are hardcoded in Compose (not `strings.xml`). Keep user-facing text in Chinese.
- AI-adjustable decorative strings use `uiText("namespace.key", "默认中文")` pattern
- Use `CompositionLocal` for theme and config: `LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`

## License

This project is licensed under the MIT License -- see [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with love and Kotlin**

[Report Issues](https://github.com/yuchen1204/omnichat/issues) · [Request Features](https://github.com/yuchen1204/omnichat/issues/new)

</div>
