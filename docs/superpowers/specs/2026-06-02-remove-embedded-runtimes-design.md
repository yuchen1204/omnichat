# Remove Embedded Node.js and Python Runtimes

## Overview

Remove all embedded Node.js and Python runtime support from OmniChat. After this change, MCP servers will only support remote HTTP connections. This eliminates ~65MB+ of native .so files, all JNI/C++ code, and the embedded runtime orchestration layer.

## Motivation

- The embedded runtimes add significant APK size (~65MB for libnode.so + Python libs across ABIs)
- Node.js can only start once per process (nodejs-mobile limitation)
- Remote HTTP MCP servers are more flexible, require no native code, and work on all architectures
- The bridge source files are already deleted from the working directory — this completes the cleanup

## Scope

### In Scope
- Delete all native .so files, C++ bridge code, Kotlin bridge classes, bundled scripts
- Remove build configuration for CMake, NDK, abiFilters
- Remove node/python code paths from McpRuntimeManager
- Remove node/python UI elements (runtime selector, status bar, badges)
- Remove McpScriptManager
- DB migration v36 to remove runtime/isNodeEnabled/isPythonEnabled columns
- Update CLAUDE.md prerequisites and documentation

### Out of Scope
- Refactoring McpRuntimeManager beyond removing dead code
- Changing the remote HTTP MCP server implementation
- Modifying built-in tools (BuiltinToolHandler)

## Design

### 1. Files to Delete

Source files (already deleted from disk, need `git rm`):
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/node_bridge.cpp`
- `app/src/main/cpp/python_bridge.cpp`
- `app/src/main/java/com/omnichat/mcp/NodeJsBridge.kt`
- `app/src/main/java/com/omnichat/mcp/PythonBridge.kt`
- `app/src/main/java/com/omnichat/mcp/PythonRuntime.kt`
- `app/src/main/java/com/omnichat/mcp/McpScriptManager.kt`
- `app/src/main/assets/node/` (all files: mcp_multi_bridge.js, mcp_socket_bridge.js, mcp_fetch.js, mcp_pkg_manager.js, PKG_MANAGER_README.md, README.md, example-server/)
- `app/src/main/assets/python/stdlib.zip`

Native .so files (delete from disk):
- `app/src/main/jniLibs/arm64-v8a/libnode.so`
- `app/src/main/jniLibs/arm64-v8a/libpython3.14.so`
- `app/src/main/jniLibs/arm64-v8a/libpython3.so`
- `app/src/main/jniLibs/arm64-v8a/libssl_python.so`
- `app/src/main/jniLibs/arm64-v8a/libcrypto_python.so`
- `app/src/main/jniLibs/arm64-v8a/libsqlite3_python.so`
- `app/src/main/jniLibs/x86_64/libnode.so`
- Any Python .so files under `x86_64/`

If the `jniLibs/` directory is empty after deletion, remove it entirely.

### 2. Build Configuration (`app/build.gradle.kts`)

Remove:
- `externalNativeBuild.cmake` block (path, cppFlags, STL arguments)
- `ndk.abiFilters` (no native code to filter by ABI)
- `sourceSets.main.jniLibs.srcDirs` (no .so files to bundle)
- `packaging.jniLibs.useLegacyPackaging`

### 3. Data Model + DB Migration (v35 to v36)

**McpServer entity** (`Entities.kt`):
- Remove `runtime: String` field

**UISettings entity** (`Entities.kt`):
- Remove `isNodeEnabled: Boolean` field
- Remove `isPythonEnabled: Boolean` field

**Migration v36** (`AppDatabase.kt`):
- Rebuild `mcp_servers` table without `runtime` column (SQLite on API 26 doesn't support DROP COLUMN; use table rebuild pattern)
- Delete any `mcp_servers` rows where `runtime` was 'node' or 'python' (they can't connect anyway)
- Rebuild `ui_settings` table without `isNodeEnabled` and `isPythonEnabled` columns
- Bump database version to 36

### 4. McpRuntimeManager.kt

Remove methods:
- `startNodeServer()` and all node-specific helpers
- `startNodeMultiBridge()`, `connectToNodeServer()`, `addServerToRunningBridge()`
- `startPythonServer()`

Remove classes:
- `McpChannel.SocketChannel` (only used by embedded runtimes)
- Unwrap `McpChannel` sealed class — `HttpChannel` becomes the direct channel type used throughout

Simplify:
- `startServer()`: Remove runtime dispatching, always call remote HTTP logic inline
- `startServers()`: Remove node/python batch separation optimization
- `triggerAutoStart()`: Remove runtime toggle filtering
- Remove all imports for `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`

### 5. UI Cleanup

**McpConfigScreen.kt**:
- Remove `RuntimeStatusBar` composable (node/python readiness indicators)
- Remove `RuntimeBadge` node/python variants
- Remove `RuntimeInfoDialog`

**McpDialogs.kt**:
- Remove `RuntimeSelector` (no runtime choice needed)
- Remove `RuntimeHint` for node/python
- Simplify `McpServerEditDialog`: remove runtime selection field

**McpViewModel.kt**:
- Remove `isNodeRuntimeAvailable` property
- Remove `isPythonRuntimeReady` property
- Remove `PythonRuntime` import

### 6. Documentation (CLAUDE.md)

Remove from prerequisites:
- CMake 3.22.1
- NDK 27.0.12077973

Update:
- "Native Code (MCP Runtime)" section: remove Node.js and Python subsections, keep Remote HTTP
- Package structure table: remove `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`
- "Node.js can start only once per process" note
- "Native runtimes are optional; app degrades gracefully" note
- Common modification tasks: remove "Add MCP server support" patterns for node/python

## Verification

1. `./gradlew assembleDebug` compiles successfully
2. `./gradlew testDebugUnitTest` passes
3. MCP config screen shows only remote HTTP option
4. No references to NodeJsBridge, PythonBridge, PythonRuntime remain in source
5. No .so files remain in jniLibs/
6. APK size reduction of ~65MB+
