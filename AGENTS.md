# OmniChat — AI Agent Instructions

An Android AI chat app with embedded MCP runtime support, long-term memory, and multi-provider model configuration.

## Quick Reference

| Item | Value |
|------|-------|
| Package | `com.example` (applicationId: `com.aistudio.aichatmemory.qwzkvp`) |
| Min SDK | 24 · Target SDK | 36 |
| Language | Kotlin 2.2.10 + C++17 (JNI bridges) |
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
Compose UI (Screens) → ViewModels → AppRepository → Room Database (8 entities)
                                    ↘ ApiClient (OkHttp + SSE)
                                    ↘ McpRuntimeManager (Node.js / Python via JNI)
```

- **Single Activity** (`MainActivity`) with bottom tab navigation (no NavController — uses `mutableStateOf`)
- **No DI framework** — ViewModels directly instantiate `AppDatabase` / `AppRepository`
- **`AndroidViewModel`** used instead of `ViewModel` to access `Application` context
- **Dual state management**: `mutableStateOf` for UI state, `StateFlow` for DB-driven reactive data

### Key Packages

| Package | Purpose |
|---------|---------|
| `com.example` | Entry point (`MainActivity.kt`) |
| `com.example.data` | Room entities, DAOs, database, repository |
| `com.example.network` | OpenAI-compatible API client with SSE streaming |
| `com.example.mcp` | MCP runtime: `McpRuntimeManager`, `NodeJsBridge`, `PythonBridge` |
| `com.example.ui.screens` | Compose screens (chat, models, MCP, memory) |
| `com.example.ui.viewmodel` | `ChatViewModel` |
| `com.example.ui.theme` | Material 3 theming with dynamic color |

## App Structure (4 Tabs)

1. **会话中心 (Chat)** — Multi-session chat with SSE streaming, session sidebar
2. **模型配置 (Models)** — Provider/model configuration, endpoint + API key management
3. **MCP工具 (MCP)** — MCP server CRUD, runtime status, tool discovery
4. **长效记忆 (Memory)** — Cross-session memory items, prompt templates

## Data Layer

Room database `ai_chat_memory_db` (version 7) with 8 entities: `ModelConfig`, `Session`, `Message`, `MemoryItem`, `PromptTemplate`, `FetchedModel`, `SessionSummary`, `McpServer`. See `app/src/main/java/com/example/data/Entities.kt`.

7 sequential migrations exist. Always add new migrations when changing schemas — do not rely on `fallbackToDestructiveMigration`.

## Native Code (MCP Runtime)

- **Node.js**: `libnode.so` (nodejs-mobile) → `NodeJsBridge` (JNI) → TCP socket bridge → MCP JSON-RPC
- **Python**: `libpython3.14.so` (dlopen) → `ProcessBuilder` → stdin/stdout MCP JSON-RPC
- Node.js scripts go in `app/src/main/assets/node/`; Python stdlib in `assets/python/stdlib.zip`
- **Constraint**: Node.js can start only once per process (nodejs-mobile limitation) — merge multiple servers into one entry script
- Native runtimes are optional; app degrades gracefully without them
- CMake config: `app/src/main/cpp/CMakeLists.txt` with `c++_shared` STL

## Conventions

- **Chinese UI strings** are hardcoded in Compose (not in `strings.xml`). Keep user-facing text in Chinese.
- **OpenAI-compatible API**: endpoint auto-correction strips `/chat/completions`, adds `/v1` for OpenAI
- **Thinking/reasoning support**: `reasoning_effort` (low/medium/high/xhigh) with `budget_tokens`
- **Memory system**: Dual-layer — session summaries (15-min rolling) + cross-session memory facts injected via `[CROSS_SESSION_MEMORY]` placeholder in prompts
- **API keys**: Managed via Secrets Gradle Plugin from `.env` file, never hardcoded
- **ABI filters**: Only `arm64-v8a` and `x86_64` are packaged

## Common Tasks

- **Add a new Room entity**: Define in `Entities.kt`, add DAO in `Daos.kt`, update `AppDatabase` with new version + migration, expose in `AppRepository`
- **Add a new screen/tab**: Add composable in `ui/screens/`, wire into `MainScreen.kt` tab state
- **Add MCP server support**: Add runtime config in `McpRuntimeManager`, bridge class following `NodeJsBridge`/`PythonBridge` patterns
- **Modify API client**: Edit `ApiClient.kt` — it handles SSE streaming, model discovery, and thinking config
