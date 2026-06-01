[中文版](README_zh.md)

# OmniChat

> Android AI Assistant with Embedded MCP Agent Runtime -- Let AI truly control your device

<div align="center">

![Min SDK](https://img.shields.io/badge/Min%20SDK-26-green?logo=android)
![Target SDK](https://img.shields.io/badge/Target%20SDK-36-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

## Core Features

- **Embedded MCP Runtime** -- Run Node.js / Python MCP servers locally on Android, enabling AI to directly invoke on-device tools
- **Multi-Agent Workspace** -- Orchestrator-pattern multi-agent collaboration with team management, task assignment, and inter-agent communication
- **Cross-Session Memory System** -- 15-minute rolling summaries + long-term memory items (with confidence scoring), so AI truly "remembers" your preferences
- **AI-Adjustable UI** -- Apple-inspired color schemes; AI can modify app themes, colors, fonts, and layouts in real time via MCP tools
- **Multimedia Capabilities** -- Camera capture, image picker, document generation (docx/xlsx), AlarmManager timers (with repeating tasks)
- **Multi-Model Support** -- OpenAI-compatible API; supports Gemini, OpenAI, DeepSeek, local models, and more
- **SSE Streaming Output** -- Real-time streaming responses with typewriter effect; supports Thinking/Reasoning mode
- **Hook System** -- Extensible hook mechanism for logging, file permission control, and more
- **Custom Headers** -- Configurable custom HTTP headers per model provider

## App Architecture

```
+---------------------------------------------------------------+
|                     Compose UI Layer                         |
|  +----------+  +-----------+  +------------+  +---------+   |
|  |ChatScreen|  |Workspace  |  |  Settings  |  | Sidebar |   |
|  |          |  |  Screen   |  |   (5 tabs) |  |  Drawer |   |
|  +----+-----+  +-----+-----+  +-----+------+  +----+----+   |
+-------+--------------+--------------+--------------+---------+
|                     ViewModel Layer                          |
|  +--------------+  +----------------+  +---------------+    |
|  | ChatViewModel|  |WorkspaceVM     |  |SettingsVM     |    |
|  +------+-------+  +-------+--------+  +-------+-------+    |
+---------+-------------------+--------------------+-----------+
|                 Data / Repository Layer                      |
|  +---------------------------------------------------------+ |
|  |              AppRepository (Room DB v32 - 20 tables)     | |
|  +---------------------------------------------------------+ |
+---------------------------------------------------------------+
|                   MCP Runtime Layer                          |
|  +---------------+  +---------------+  +---------------+    |
|  |  NodeJsBridge |  | PythonBridge  |  | Remote HTTP   |    |
|  |   (JNI/TCP)   |  |  (JNI/dlopen) |  |  (SSE+Stream) |    |
|  +---------------+  +---------------+  +---------------+    |
+---------------------------------------------------------------+
|                  Workspace (Multi-Agent)                     |
|  +-----------+  +----------+  +----------+  +-----------+   |
|  |TeamManager|  |TaskTools |  |AgentTool |  |AgentRunner|   |
|  +-----------+  +----------+  +----------+  +-----------+   |
|  +---------------+  +--------------+  +-----------------+   |
|  |ToolOrchestratr|  | AgentRegistry|  |MarkdownAgentLdr |   |
|  +---------------+  +--------------+  +-----------------+   |
+---------------------------------------------------------------+
```

Single Activity architecture (`MainActivity`), three top-level views: Chat, Workspace, Settings (with 5 sub-tabs).

## Quick Start

### Requirements

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 36
- CMake 3.22.1 + NDK 27.0.12077973

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
./gradlew testDebugUnitTest --tests "com.example.YourTestClass"

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
+-- app/src/main/java/com/example/
|   +-- MainActivity.kt              # Entry Activity
|   +-- data/                        # Data layer
|   |   +-- Entities.kt              # Room entity definitions (20 tables)
|   |   +-- Daos.kt                  # DAO interfaces
|   |   +-- AppDatabase.kt           # Database config (v32, 28 migrations)
|   |   +-- Repository.kt            # Repository (AppRepository)
|   +-- mcp/                         # MCP runtime
|   |   +-- McpRuntimeManager.kt     # Runtime manager
|   |   +-- McpScriptManager.kt      # Script deployment manager
|   |   +-- McpPermissionManager.kt  # MCP permission manager
|   |   +-- NodeJsBridge.kt          # Node.js JNI bridge (TCP)
|   |   +-- PythonBridge.kt          # Python bridge (stdin/stdout)
|   |   +-- PythonRuntime.kt         # Python runtime (dlopen)
|   |   +-- BuiltinToolHandler.kt    # Built-in tool handler
|   +-- hooks/                       # Hook system
|   |   +-- HookManager.kt           # Hook manager
|   |   +-- HookInterfaces.kt        # Hook interface definitions
|   |   +-- LoggingHooks.kt          # Logging hooks
|   |   +-- McpFilePermissionHook.kt # File permission hook
|   +-- network/
|   |   +-- ApiClient.kt            # OpenAI-compatible API client (SSE)
|   +-- workspace/                   # Multi-agent workspace
|   |   +-- TeamManager.kt           # Team manager (orchestrator)
|   |   +-- AgentRunner.kt           # Agent executor
|   |   +-- AgentRegistry.kt         # Agent registration and discovery
|   |   +-- AgentDefinition.kt       # Agent type definitions
|   |   +-- AgentContext.kt          # Agent execution context
|   |   +-- AgentTool.kt             # SubAgent creation
|   |   +-- AgentToolFilter.kt       # Tool filtering
|   |   +-- ToolOrchestrator.kt      # Tool routing and orchestration
|   |   +-- TaskTools.kt             # Task management
|   |   +-- SendMessageTool.kt       # Inter-agent communication
|   |   +-- StructuredMessage.kt     # Structured message format
|   |   +-- MemorySnapshot.kt        # Agent memory snapshot
|   |   +-- MarkdownAgentLoader.kt   # Markdown agent definition loader
|   |   +-- WorkspaceModels.kt       # Workspace data models
|   +-- ui/
|   |   +-- screens/                 # Compose screens
|   |   +-- viewmodel/               # ViewModel layer
|   |   +-- components/              # Reusable components
|   |   +-- theme/                   # Material 3 theme system
|   +-- TimerManager.kt             # Timer manager (AlarmManager)
+-- app/src/main/cpp/                # C++ JNI code
+-- app/src/main/assets/
|   +-- node/                        # Node.js MCP scripts
|   +-- python/                      # Python stdlib
+-- scripts/                         # Utility scripts
```

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 2.2.10, C++17 (JNI) |
| UI | Jetpack Compose (Material 3) |
| Database | Room v2.7.0 (v32, 20 entities, 28 migrations) |
| Networking | OkHttp + SSE + Retrofit 2.12.0 |
| Serialization | Moshi 1.15.2 |
| Firebase | Firebase BOM 34.12.0 |
| Build | AGP 9.1.1, KSP 2.2.10-2.0.2, CMake 3.22.1 |
| Native Runtimes | nodejs-mobile (libnode.so), Python 3.14 (dlopen) |
| Document Generation | Apache POI 5.5.1 |
| Permissions | Accompanist Permissions 0.37.3 |
| Image Loading | Coil 2.7.0 |
| Camera | CameraX 1.5.0 |
| Markdown | compose-markdown 0.7.2 |
| Testing | JUnit, Robolectric, Roborazzi 1.59.0 |
| CI/CD | GitHub Actions (automated release builds) |
| ABI | arm64-v8a, x86_64 |

## Database Schema (v32, 20 Entities)

| Entity | Table | Purpose |
|--------|-------|---------|
| `ModelConfig` | `model_configs` | API provider / model configuration |
| `Session` | `sessions` | Chat sessions |
| `Message` | `messages` | Chat messages (user/assistant/tool) |
| `MemoryItem` | `memory_items` | Cross-session memory (with confidence scoring) |
| `PromptTemplate` | `prompt_templates` | System prompt templates |
| `FetchedModel` | `fetched_models` | Model list cache |
| `SessionSummary` | `session_summaries` | 15-minute rolling session summaries |
| `McpServer` | `mcp_servers` | MCP server configuration |
| `UISettings` | `ui_settings` | AI-adjustable global UI settings |
| `ColorSchemePreset` | `color_scheme_presets` | Color scheme snapshots (up to 5) |
| `AgentPreset` | `agent_presets` | Agent preset configurations |
| `WorkspaceSession` | `workspace_sessions` | Workspace sessions |
| `WorkspaceTeam` | `workspace_teams` | Workspace teams |
| `AgentInstance` | `agent_instances` | Running agent instances |
| `AgentDefinitionEntity` | `agent_definitions` | Agent type definitions (with Claude Code-aligned fields) |
| `WorkspaceMessage` | `workspace_messages` | Workspace messages |
| `MailboxMessage` | `mailbox_messages` | Inter-agent mailbox messages |
| `AgentStateSnapshot` | `agent_state_snapshots` | Agent state snapshots |
| `TeamTask` | `team_tasks` | Team tasks (status / blocking management) |
| `McpFilePermission` | `mcp_file_permissions` | MCP file access permissions |

## MCP Tool Extension

### Built-in Tools (35)

| Tool | Description |
|------|-------------|
| File System | Read/write local files and directory management |
| Network Requests | HTTP/HTTPS fetching |
| UI Customization | Dynamically adjust theme colors, corner radius, fonts, spacing |
| Color Schemes | Save / load / switch theme presets |
| UI Text | Adjust interface text content |
| Document Generation | Generate Word (.docx) and Excel (.xlsx) files |
| Camera Capture | Invoke device camera to take and save photos |
| Image Picker | Select images from the gallery |
| Timers | Create and manage countdown / stopwatch timers |
| Agent Management | Create / manage multi-agent workspace |
| Task Management | Task CRUD, status tracking, blocking dependencies |
| Memory Search | Search cross-session memories |

### Adding Custom MCP Servers

1. Go to **Settings -> MCP Tools** tab and tap **Add**
2. Configure the server:
   - **Node.js**: Specify the `.js` file path
   - **Python**: Specify the `.py` file path
   - **Remote HTTP**: Enter the server URL (supports SSE 2024-11-05 and Streamable HTTP 2025-03-26)
3. Supports standard `mcpServers` JSON format import

> Node.js can only start once per process (nodejs-mobile limitation) -- multiple servers are merged into a single entry script.

## Multi-Agent Workspace

Orchestrator-pattern multi-agent collaboration system with Claude Code-style agent definitions:

- **TeamManager** -- Manages teammates and overall workflow
- **AgentRegistry** -- Agent registration and discovery
- **AgentDefinition** -- Agent type definitions (built-in + custom Markdown definitions)
- **MarkdownAgentLoader** -- Loads agent definitions from Markdown files
- **AgentTool** -- Creates isolated SubAgents to execute tasks
- **AgentToolFilter** -- Filters available tools by agent type
- **ToolOrchestrator** -- Tool routing and orchestration
- **TaskTools** -- Task CRUD, status tracking, blocking dependencies
- **AgentRunner** -- Agent execution loop (with MCP tool calls)
- **SendMessageTool** -- Asynchronous inter-agent messaging
- **StructuredMessage** -- Structured message format
- **MemorySnapshot** -- Agent memory snapshots
- **AgentContext** -- Coroutine context isolation

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
- UI strings use Android `strings.xml` for i18n (English default, Chinese in `values-zh-rCN`)
- AI-adjustable decorative strings use `uiText("namespace.key", "English default")` pattern
- Use `CompositionLocal` for theme and config: `LocalUISettings`, `LocalCustomColors`, `LocalUiStrings`

## License

This project is licensed under the MIT License -- see [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with love and Kotlin**

[Report Issues](https://github.com/yuchen1204/omnichat/issues) · [Request Features](https://github.com/yuchen1204/omnichat/issues/new)

</div>
