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

- **MCP Runtime** -- Connect to remote MCP servers via HTTP/HTTPS, enabling AI to invoke external tools
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
|  +----------+  +------------+  +---------+                   |
|  |ChatScreen|  |  Settings  |  | Sidebar |                   |
|  |          |  |   (4 tabs) |  |  Drawer |                   |
|  +----+-----+  +-----+------+  +----+----+                   |
+-------+--------------+--------------+-------------------------+
|                     ViewModel Layer                          |
|  +--------------+  +---------------+                         |
|  | ChatViewModel|  |SettingsVM     |                         |
|  +------+-------+  +-------+-------+                         |
+---------+-------------------+--------------------------------+
|                 Data / Repository Layer                      |
|  +---------------------------------------------------------+ |
|  |           AppRepository (Room DB v37)                    | |
|  +---------------------------------------------------------+ |
+---------------------------------------------------------------+
|                   MCP Runtime Layer                          |
|  +---------------+                                           |
|  |  Remote HTTP  |                                           |
|  |  (SSE+Stream) |                                           |
|  +---------------+                                           |
+---------------------------------------------------------------+
```

Single Activity architecture (`MainActivity`), two top-level views: Chat, Settings (with 4 sub-tabs).

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
+-- app/src/main/java/com/omnichat/
|   +-- MainActivity.kt              # Entry Activity
|   +-- data/                        # Data layer
|   |   +-- Entities.kt              # Room entity definitions
|   |   +-- Daos.kt                  # DAO interfaces
|   |   +-- AppDatabase.kt           # Database config (v37)
|   |   +-- Repository.kt            # Repository (AppRepository)
|   +-- mcp/                         # MCP runtime
|   |   +-- McpRuntimeManager.kt     # Runtime manager
|   |   +-- McpPermissionManager.kt  # MCP permission manager
|   |   +-- BuiltinToolHandler.kt    # Built-in tool handler
|   +-- hooks/                       # Hook system
|   |   +-- HookManager.kt           # Hook manager
|   |   +-- HookInterfaces.kt        # Hook interface definitions
|   |   +-- LoggingHooks.kt          # Logging hooks
|   |   +-- McpFilePermissionHook.kt # File permission hook
|   +-- network/
|   |   +-- ApiClient.kt            # OpenAI-compatible API client (SSE)
|   +-- memory/                      # Memory engine
|   |   +-- MemoryEngine.kt          # Cross-session memory engine
|   |   +-- MemoryTokenizer.kt       # Tokenizer for memory search
|   +-- ui/
|   |   +-- screens/                 # Compose screens
|   |   +-- viewmodel/               # ViewModel layer
|   |   +-- components/              # Reusable components
|   |   +-- theme/                   # Material 3 theme system
|   +-- TimerManager.kt             # Timer manager (AlarmManager)
+-- app/src/main/assets/
|   +-- node/                        # Node.js MCP scripts
+-- scripts/                         # Utility scripts
```

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3) |
| Database | Room v2.7.0 (v37) |
| Networking | OkHttp + SSE + Retrofit 2.12.0 |
| Serialization | Moshi 1.15.2 |
| Firebase | Firebase BOM 34.12.0 |
| Build | AGP 9.1.1, KSP 2.2.10-2.0.2 |
| Document Generation | Apache POI 5.5.1 |
| Permissions | Accompanist Permissions 0.37.3 |
| Image Loading | Coil 2.7.0 |
| Camera | CameraX 1.5.0 |
| Markdown | compose-markdown 0.7.2 |
| Testing | JUnit, Robolectric, Roborazzi 1.59.0 |
| CI/CD | GitHub Actions (automated release builds) |

## MCP Tool Extension

### Built-in Tools

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
| Memory Search | Search cross-session memories |

### Adding Custom MCP Servers

1. Go to **Settings -> MCP Tools** tab and tap **Add**
2. Configure the server:
   - **Remote HTTP**: Enter the server URL (supports SSE 2024-11-05 and Streamable HTTP 2025-03-26)
3. Supports standard `mcpServers` JSON format import

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
