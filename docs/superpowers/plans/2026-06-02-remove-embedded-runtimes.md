# Remove Embedded Node.js and Python Runtimes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all embedded Node.js and Python runtime support so only remote HTTP MCP servers remain, eliminating ~65MB+ of native .so files and all JNI/C++ code.

**Architecture:** Targeted removal of dead code paths. The McpRuntimeManager structure is preserved — only node/python-specific methods, classes, and dispatch logic are stripped. Remote HTTP server support is untouched.

**Tech Stack:** Kotlin, Room (SQLite), Compose, Gradle (CMake removal)

---

## File Map

| Action | File | What changes |
|--------|------|-------------|
| Delete | `app/src/main/cpp/CMakeLists.txt` | Entire file (JNI build config) |
| Delete | `app/src/main/cpp/node_bridge.cpp` | Entire file (Node.js JNI bridge) |
| Delete | `app/src/main/cpp/python_bridge.cpp` | Entire file (Python JNI bridge) |
| Delete | `app/src/main/java/com/omnichat/mcp/NodeJsBridge.kt` | Entire file |
| Delete | `app/src/main/java/com/omnichat/mcp/PythonBridge.kt` | Entire file |
| Delete | `app/src/main/java/com/omnichat/mcp/PythonRuntime.kt` | Entire file |
| Delete | `app/src/main/java/com/omnichat/mcp/McpScriptManager.kt` | Entire file |
| Delete | `app/src/main/assets/node/` | All files (JS bridge scripts) |
| Delete | `app/src/main/assets/python/stdlib.zip` | Python stdlib |
| Delete | `app/src/main/jniLibs/` | All .so files, then directory |
| Modify | `app/build.gradle.kts` | Remove CMake, NDK, jniLibs config |
| Modify | `app/src/main/java/com/omnichat/data/Entities.kt` | Remove `runtime` from McpServer, `isNodeEnabled`/`isPythonEnabled` from UISettings |
| Modify | `app/src/main/java/com/omnichat/data/AppDatabase.kt` | Add migration v36, bump version |
| Modify | `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt` | Remove node/python methods, SocketChannel, runtime dispatching |
| Modify | `app/src/main/java/com/omnichat/mcp/McpViewModel.kt` | Remove runtime availability state, simplify init |
| Modify | `app/src/main/java/com/omnichat/ui/screens/McpConfigScreen.kt` | Remove RuntimeStatusBar, RuntimeBadge node/python, RuntimeInfoDialog |
| Modify | `app/src/main/java/com/omnichat/ui/screens/McpDialogs.kt` | Remove RuntimeSelector, RuntimeHint node/python, simplify edit dialog |
| Modify | `CLAUDE.md` | Update prerequisites, architecture docs |

---

### Task 1: Delete Native Files and .so Libraries

**Files:**
- Delete: `app/src/main/cpp/` (entire directory — 3 files)
- Delete: `app/src/main/java/com/omnichat/mcp/NodeJsBridge.kt`
- Delete: `app/src/main/java/com/omnichat/mcp/PythonBridge.kt`
- Delete: `app/src/main/java/com/omnichat/mcp/PythonRuntime.kt`
- Delete: `app/src/main/java/com/omnichat/mcp/McpScriptManager.kt`
- Delete: `app/src/main/assets/node/` (entire directory)
- Delete: `app/src/main/assets/python/stdlib.zip`
- Delete: `app/src/main/jniLibs/` (all .so files, then directory)

- [ ] **Step 1: git rm tracked source files that are already deleted from disk**

```bash
cd E:/omnichat
git rm app/src/main/cpp/CMakeLists.txt \
       app/src/main/cpp/node_bridge.cpp \
       app/src/main/cpp/python_bridge.cpp \
       app/src/main/java/com/omnichat/mcp/NodeJsBridge.kt \
       app/src/main/java/com/omnichat/mcp/PythonBridge.kt \
       app/src/main/java/com/omnichat/mcp/PythonRuntime.kt \
       app/src/main/assets/node/PKG_MANAGER_README.md \
       app/src/main/assets/node/README.md \
       app/src/main/assets/node/example-server/index.js \
       app/src/main/assets/node/example-server/package.json \
       app/src/main/assets/node/mcp_fetch.js \
       app/src/main/assets/node/mcp_multi_bridge.js \
       app/src/main/assets/node/mcp_pkg_manager.js \
       app/src/main/assets/node/mcp_socket_bridge.js \
       app/src/main/assets/python/stdlib.zip
```

- [ ] **Step 2: Delete McpScriptManager.kt (still on disk)**

```bash
git rm app/src/main/java/com/omnichat/mcp/McpScriptManager.kt
```

- [ ] **Step 3: Delete all .so files from jniLibs/**

```bash
rm -rf app/src/main/jniLibs/arm64-v8a/
rm -rf app/src/main/jniLibs/x86_64/
rm -rf app/src/main/jniLibs/
```

- [ ] **Step 4: Verify jniLibs directory is gone**

```bash
ls app/src/main/jniLibs/ 2>/dev/null || echo "OK: jniLibs removed"
```
Expected: `OK: jniLibs removed`

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/cpp/ app/src/main/jniLibs/ \
          app/src/main/java/com/omnichat/mcp/NodeJsBridge.kt \
          app/src/main/java/com/omnichat/mcp/PythonBridge.kt \
          app/src/main/java/com/omnichat/mcp/PythonRuntime.kt \
          app/src/main/java/com/omnichat/mcp/McpScriptManager.kt \
          app/src/main/assets/node/ \
          app/src/main/assets/python/
git commit -m "chore: delete embedded Node.js/Python native code, .so files, and scripts"
```

---

### Task 2: Remove Build Configuration for Native Code

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Remove NDK abiFilters block (lines 24-27)**

In `app/build.gradle.kts`, remove:
```kotlin
    // 只打包主流 ABI，减小 APK 体积（libnode.so 和 python 二进制都按 ABI 分包）
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
```

- [ ] **Step 2: Remove defaultConfig-level CMake config (lines 29-35)**

Remove:
```kotlin
    externalNativeBuild {
      cmake {
        cppFlags("-std=c++17")
        // 使用 c++_shared STL，与 libnode.so 的构建方式一致
        arguments("-DANDROID_STL=c++_shared")
      }
    }
```

- [ ] **Step 3: Remove top-level CMake externalNativeBuild (lines 70-76)**

Remove:
```kotlin
  // CMake 构建配置（用于 JNI 桥接 libnode.so）
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
```

- [ ] **Step 4: Remove sourceSets jniLibs config (lines 78-83)**

Remove:
```kotlin
  // 将 libnode/bin/ 目录下的预编译 .so 文件打包进 APK
  sourceSets {
    getByName("main") {
      jniLibs.srcDirs("src/main/jniLibs")
    }
  }
```

- [ ] **Step 5: Remove packaging jniLibs config (lines 85-90)**

Remove:
```kotlin
  // 不压缩 .so 文件，让系统可以直接 mmap（加快加载速度）
  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
  }
```

- [ ] **Step 6: Verify build.gradle.kts is syntactically correct**

Read the file and confirm the `android {}` block closes properly with no dangling commas or missing braces.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: remove CMake, NDK, and jniLibs build configuration"
```

---

### Task 3: Update Data Model and Add DB Migration v36

**Files:**
- Modify: `app/src/main/java/com/omnichat/data/Entities.kt`
- Modify: `app/src/main/java/com/omnichat/data/AppDatabase.kt`

- [ ] **Step 1: Remove `runtime` field from McpServer entity**

In `Entities.kt`, change the `McpServer` data class (line 294):

**Before:**
```kotlin
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val runtime: String = "node",   // "node" | "python" | "remote_http"
    val command: String,            // 入口脚本路径 或 npm 包名 或 URL
    val args: String = "[]",        // JSON 数组字符串
    val env: String = "{}",         // JSON 对象字符串
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
```

**After:**
```kotlin
/**
 * MCP (Model Context Protocol) 服务配置。
 *
 * 通过 HTTP/HTTPS 连接远程 MCP server。
 *
 * command  — 远程 URL
 * args     — JSON 数组字符串，例如 '["--root", "/sdcard"]'
 * env      — JSON 对象字符串（自定义 HTTP 请求头），例如 '{"Authorization": "Bearer token"}'
 */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,            // 远程 URL
    val args: String = "[]",        // JSON 数组字符串
    val env: String = "{}",         // JSON 对象字符串
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: Remove `isNodeEnabled` and `isPythonEnabled` from UISettings entity**

In `Entities.kt`, in the `UISettings` data class (around line 514-517), remove:
```kotlin
    /** 是否启用 Node.js 运行时 */
    val isNodeEnabled: Boolean = true,
    /** 是否启用 Python 运行时 */
    val isPythonEnabled: Boolean = true,
```

- [ ] **Step 3: Add migration MIGRATION_35_36 in AppDatabase.kt**

Add after `MIGRATION_34_35` (around line 635):

```kotlin
        /** v35→v36：移除 mcp_servers.runtime 列和 ui_settings.isNodeEnabled/isPythonEnabled 列 */
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建 mcp_servers 表（移除 runtime 列，删除 node/python 类型的 server）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mcp_servers_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        command TEXT NOT NULL,
                        args TEXT NOT NULL DEFAULT '[]',
                        env TEXT NOT NULL DEFAULT '{}',
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO mcp_servers_new (id, name, command, args, env, isEnabled, createdAt) SELECT id, name, command, args, env, isEnabled, createdAt FROM mcp_servers WHERE runtime = 'remote_http'")
                db.execSQL("DROP TABLE mcp_servers")
                db.execSQL("ALTER TABLE mcp_servers_new RENAME TO mcp_servers")

                // 重建 ui_settings 表（移除 isNodeEnabled 和 isPythonEnabled 列）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ui_settings_new (
                        id INTEGER PRIMARY KEY NOT NULL,
                        primaryColor TEXT NOT NULL,
                        onPrimaryColor TEXT NOT NULL,
                        primaryContainerColor TEXT NOT NULL,
                        onPrimaryContainerColor TEXT NOT NULL,
                        secondaryColor TEXT NOT NULL,
                        onSecondaryColor TEXT NOT NULL,
                        secondaryContainerColor TEXT NOT NULL,
                        onSecondaryContainerColor TEXT NOT NULL,
                        tertiaryColor TEXT NOT NULL,
                        onTertiaryColor TEXT NOT NULL,
                        backgroundColor TEXT NOT NULL,
                        onBackgroundColor TEXT NOT NULL,
                        surfaceColor TEXT NOT NULL,
                        onSurfaceColor TEXT NOT NULL,
                        surfaceVariantColor TEXT NOT NULL,
                        onSurfaceVariantColor TEXT NOT NULL,
                        outlineColor TEXT NOT NULL,
                        outlineVariantColor TEXT NOT NULL,
                        errorColor TEXT NOT NULL,
                        onErrorColor TEXT NOT NULL,
                        errorContainerColor TEXT NOT NULL,
                        onErrorContainerColor TEXT NOT NULL,
                        successColor TEXT NOT NULL,
                        warningColor TEXT NOT NULL,
                        infoColor TEXT NOT NULL,
                        accentColor TEXT NOT NULL,
                        sidebarBackgroundColor TEXT NOT NULL,
                        sidebarOnBackgroundColor TEXT NOT NULL,
                        sidebarActiveColor TEXT NOT NULL,
                        sidebarOnActiveColor TEXT NOT NULL,
                        cornerRadiusDp INTEGER NOT NULL,
                        spacingMultiplier REAL NOT NULL,
                        fontSizeScale REAL NOT NULL,
                        chatFontSizeScale REAL NOT NULL,
                        fontFamily TEXT NOT NULL,
                        enabledMcpGroups TEXT NOT NULL,
                        silentToolCalls INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        uiStrings TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO ui_settings_new SELECT
                        id, primaryColor, onPrimaryColor, primaryContainerColor, onPrimaryContainerColor,
                        secondaryColor, onSecondaryColor, secondaryContainerColor, onSecondaryContainerColor,
                        tertiaryColor, onTertiaryColor,
                        backgroundColor, onBackgroundColor, surfaceColor, onSurfaceColor,
                        surfaceVariantColor, onSurfaceVariantColor, outlineColor, outlineVariantColor,
                        errorColor, onErrorColor, errorContainerColor, onErrorContainerColor,
                        successColor, warningColor, infoColor, accentColor,
                        sidebarBackgroundColor, sidebarOnBackgroundColor, sidebarActiveColor, sidebarOnActiveColor,
                        cornerRadiusDp, spacingMultiplier, fontSizeScale, chatFontSizeScale, fontFamily,
                        enabledMcpGroups, silentToolCalls, updatedAt, uiStrings
                    FROM ui_settings
                """.trimIndent())
                db.execSQL("DROP TABLE ui_settings")
                db.execSQL("ALTER TABLE ui_settings_new RENAME TO ui_settings")
            }
        }
```

- [ ] **Step 4: Bump database version to 36**

Change `version = 35` to `version = 36` in the `@Database` annotation (line 67).

- [ ] **Step 5: Register the migration**

Add `MIGRATION_35_36` to the `.addMigrations(...)` call in `getDatabase()` (around line 676).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/omnichat/data/Entities.kt \
       app/src/main/java/com/omnichat/data/AppDatabase.kt
git commit -m "feat: db migration v36 — remove runtime/isNodeEnabled/isPythonEnabled columns"
```

---

### Task 4: Clean Up McpRuntimeManager

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt`

This is the largest change. The file is 2129 lines. The goal is to remove all node/python-specific code while preserving the remote HTTP path.

- [ ] **Step 1: Remove `McpChannel.SocketChannel` class (lines 59-65)**

Remove the entire `SocketChannel` class:
```kotlin
    /** Socket 通道（Node.js JNI 模式，通过本地 TCP socket 通信） */
    class SocketChannel(private val socket: Socket) : McpChannel() {
        override val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        override val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        override fun close() {
            writer.close()
            socket.close()
        }
    }
```

- [ ] **Step 2: Unwrap McpChannel sealed class — make HttpChannel the direct type**

Replace the `sealed class McpChannel` wrapper. Change:
```kotlin
private sealed class McpChannel {
    abstract val reader: BufferedReader
    abstract val writer: PrintWriter
    abstract fun close()

    class HttpChannel(...) : McpChannel() {
```

To a standalone `private class HttpChannel(...)` (no sealed class, no abstract members — keep `reader`, `writer`, `close()` as regular members).

Update the `channels` map type (line 494) from `ConcurrentHashMap<Long, McpChannel>` to `ConcurrentHashMap<Long, HttpChannel>`.

Update any references to `McpChannel.HttpChannel` to just `HttpChannel`.

- [ ] **Step 3: Remove runtime toggle filtering from `triggerAutoStart()` (lines 396-434)**

Remove the `McpScriptManager.ensureScriptsDeployed(context)` call (line 406).

Remove the runtime filter block (lines 412-419):
```kotlin
                // 根据全局运行时开关过滤
                enabled = enabled.filter { server ->
                    when (server.runtime) {
                        "node" -> settings.isNodeEnabled
                        "python" -> settings.isPythonEnabled
                        else -> true
                    }
                }
```

And simplify the log messages that reference `isNodeEnabled`/`isPythonEnabled`.

- [ ] **Step 4: Remove runtime toggle filtering from `ensureAutoStarted()` (lines 442-491)**

Same pattern — remove `McpScriptManager.ensureScriptsDeployed(context)` call (line 455) and the runtime filter block (lines 462-468).

- [ ] **Step 5: Simplify `startServer()` (lines 1083-1122)**

Remove runtime toggle check (lines 1086-1101). Remove the `when (server.runtime)` dispatch (lines 1105-1114). Always call `startRemoteHttpServer(server)`.

**After:**
```kotlin
    fun startServer(server: McpServer) {
        Log.i(TAG, "[startServer] name=${server.name}, id=${server.id}, command=${server.command}")
        scope.launch {
            updateState(server.id) { McpServerState(server, McpServerStatus.STARTING) }
            try {
                startRemoteHttpServer(server)
            } catch (e: Exception) {
                Log.e(TAG, "启动 MCP server [${server.name}] 失败", e)
                updateState(server.id) {
                    McpServerState(server, McpServerStatus.ERROR, e.localizedMessage ?: "启动失败")
                }
            }
        }
    }
```

- [ ] **Step 6: Simplify `startServers()` (lines 1131-1370+)**

The entire node-python batch optimization is dead code. Replace the whole method with a simple loop:

**After:**
```kotlin
    fun startServers(servers: List<McpServer>) {
        servers.forEach { startServer(it) }
    }
```

- [ ] **Step 7: Delete `startNodeServer()` method (lines 1620-1700)**

Remove the entire method.

- [ ] **Step 8: Delete `startNodeMultiBridge()` method (lines 1702-1765)**

Remove the entire method.

- [ ] **Step 9: Delete `addServerToRunningBridge()` method (lines 1766-1807)**

Remove the entire method.

- [ ] **Step 10: Delete `connectToNodeServer()` method (lines 1808-1870+)**

Remove the entire method.

- [ ] **Step 11: Delete `startPythonServer()` method (lines 1477-1619)**

Remove the entire method.

- [ ] **Step 12: Remove unused imports**

Remove imports for:
- `android.util.Base64`
- `java.net.ServerSocket`
- `java.net.Socket`
- `java.util.Locale`
- Any import referencing `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager`

Keep imports for: `OkHttpClient`, `JSONObject`, `JSONArray`, `BufferedReader`, `PrintWriter`, `PipedInputStream`, `PipedOutputStream`, `ConcurrentHashMap`, etc. (used by HttpChannel).

- [ ] **Step 13: Verify no remaining references to removed types**

```bash
grep -n "NodeJsBridge\|PythonBridge\|PythonRuntime\|McpScriptManager\|SocketChannel\|isNodeEnabled\|isPythonEnabled\|server\.runtime" \
  app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt
```
Expected: No matches.

- [ ] **Step 14: Commit**

```bash
git add app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt
git commit -m "refactor: remove node/python runtime paths from McpRuntimeManager"
```

---

### Task 5: Clean Up McpViewModel

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/McpViewModel.kt`

- [ ] **Step 1: Remove runtime availability state properties (lines 37-49)**

Remove:
```kotlin
    /** Node.js 运行时（libnode.so）是否已加载 */
    var isNodeRuntimeAvailable by mutableStateOf(false)
        private set

    /** Python 运行时是否已就绪 */
    var isPythonRuntimeReady by mutableStateOf(false)
        private set

    /** Python 运行时初始化状态消息 */
    var pythonRuntimeStatus by mutableStateOf("检测中...")
        private set

    /** MCP 工作目录路径（用于 UI 展示） */
    val mcpWorkDir: String get() = McpScriptManager.getMcpDir(getApplication()).absolutePath
```

- [ ] **Step 2: Simplify the `init` block (lines 51-103)**

Replace the entire init block with a simpler version that just observes servers without runtime filtering:

```kotlin
    init {
        // McpRuntimeManager 单例在创建时已自动启动所有已启用的 server
    }
```

The runtime toggle filtering logic in `McpViewModel.init` (lines 56-103) references `server.runtime`, `settings.isNodeEnabled`, `settings.isPythonEnabled`, `NodeJsBridge`, and `PythonRuntime` — all of which are being removed. The `McpRuntimeManager` already handles auto-start.

- [ ] **Step 3: Fix `importConfigJson()` method (lines 165-205)**

Remove the runtime detection logic (lines 183-189):
```kotlin
                    // 默认使用 node 运行时（如果是路径）或 python
                    val runtime = when {
                        command.startsWith("http") -> "remote_http"
                        command.startsWith("/") || command.endsWith(".js") -> "node"
                        command.endsWith(".py") -> "python"
                        else -> "node"
                    }
```

And remove `runtime = runtime` from the `McpServer` constructor call (line 192). Since `McpServer` no longer has a `runtime` field, just construct without it.

- [ ] **Step 4: Remove unused imports**

Remove imports for `NodeJsBridge`, `PythonRuntime`, `McpScriptManager`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnichat/mcp/McpViewModel.kt
git commit -m "refactor: remove runtime availability state from McpViewModel"
```

---

### Task 6: Clean Up McpConfigScreen UI

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/screens/McpConfigScreen.kt`

- [ ] **Step 1: Remove `RuntimeStatusBar` call from `McpConfigScreen` (lines 65-81)**

Remove the entire `RuntimeStatusBar(...)` call block and the `showRuntimeInfo` state variable (line 56).

- [ ] **Step 2: Remove `RuntimeInfoDialog` call (lines 247-256)**

Remove:
```kotlin
    if (showRuntimeInfo) {
        RuntimeInfoDialog(...)
    }
```

- [ ] **Step 3: Remove `RuntimeBadge` call in `McpServerCard` (line 362)**

Replace:
```kotlin
                RuntimeBadge(runtime = server.runtime)
```
With a simple HTTP badge or remove entirely. Since all servers are now remote HTTP, replace with:
```kotlin
                RuntimeBadge()
```

- [ ] **Step 4: Simplify `RuntimeBadge` composable (lines 621-651)**

Simplify to always show "HTTP":
```kotlin
@Composable
fun RuntimeBadge() {
    val fs = LocalUISettings.current.fontSizeScale
    val color = MaterialTheme.colorScheme.secondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "HTTP",
            fontSize = (10 * fs).sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}
```

- [ ] **Step 5: Remove `RuntimeStatusBar` composable (lines 678-746)**

Delete the entire `RuntimeStatusBar` composable function.

- [ ] **Step 6: Remove `allRuntimesReady` helper function (lines 748-752)**

Delete:
```kotlin
private fun allRuntimesReady(node: Boolean, py: Boolean, nodeEnabled: Boolean, pyEnabled: Boolean): Boolean {
    val nodeOk = !nodeEnabled || node
    val pyOk = !pyEnabled || py
    return nodeOk && pyOk && (nodeEnabled || pyEnabled)
}
```

- [ ] **Step 7: Remove `RuntimeChip` composable (lines 754-784)**

Delete the entire function.

- [ ] **Step 8: Remove unused imports**

Remove any imports only used by the deleted composables (e.g., `collectAsStateWithLifecycle` for `currentUiSettings` if no longer used).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/omnichat/ui/screens/McpConfigScreen.kt
git commit -m "refactor: remove runtime status bar and node/python badges from MCP config screen"
```

---

### Task 7: Clean Up McpDialogs UI

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/screens/McpDialogs.kt`

- [ ] **Step 1: Remove `runtime` state from `McpServerEditDialog` (line 250)**

Remove:
```kotlin
    var runtime by remember { mutableStateOf(server?.runtime ?: "node") }
```

- [ ] **Step 2: Remove `RuntimeSelector` call (lines 299-309)**

Remove the "运行时选择" section:
```kotlin
                // 运行时选择
                Text(
                    text = uiText("mcp.dialog.8436d4b3", R.string.mcp_dialog_runtime),
                    ...
                )
                Spacer(modifier = Modifier.height(6.dp))
                RuntimeSelector(selected = runtime, resolvedFontFamily = resolvedFontFamily, onSelect = { runtime = it })
                Spacer(modifier = Modifier.height(12.dp))
```

- [ ] **Step 3: Remove `RuntimeHint` call (lines 312-317)**

Remove:
```kotlin
                // 运行时说明
                RuntimeHint(
                    runtime = runtime,
                    resolvedFontFamily = resolvedFontFamily,
                    mcpWorkDir = mcpWorkDir
                )
                Spacer(modifier = Modifier.height(12.dp))
```

- [ ] **Step 4: Simplify command label/placeholder (lines 320-328)**

Change:
```kotlin
                    label = { Text(commandLabel(runtime), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(commandPlaceholder(runtime), fontFamily = resolvedFontFamily) },
```
To use remote_http-specific text directly:
```kotlin
                    label = { Text(uiText("mcp.dialog.command.label.remote.http", R.string.mcp_dialog_command_label_remote_http), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(uiText("mcp.dialog.command.placeholder.remote.http", R.string.mcp_dialog_command_placeholder_remote_http), fontFamily = resolvedFontFamily) },
```

- [ ] **Step 5: Simplify env/headers field (lines 351-378)**

Change the `label` and `placeholder` to always use the remote HTTP variant:
```kotlin
                    label = {
                        Text(uiText("mcp.dialog.custom.headers", R.string.mcp_dialog_custom_headers), fontFamily = resolvedFontFamily)
                    },
                    placeholder = {
                        Text("{\"Authorization\": \"Bearer token\", \"X-Api-Key\": \"xxx\"}", fontFamily = resolvedFontFamily)
                    },
```

- [ ] **Step 6: Fix `McpServer` construction in save handler (lines 410-419)**

Remove `runtime = runtime`:
```kotlin
                        val saved = McpServer(
                            id = server?.id ?: 0,
                            name = name.trim(),
                            command = command.trim(),
                            args = args.trim().ifBlank { "[]" },
                            env = env.trim().ifBlank { "{}" },
                            isEnabled = isEnabled,
                            createdAt = server?.createdAt ?: System.currentTimeMillis()
                        )
```

- [ ] **Step 7: Delete `RuntimeSelector` composable (lines 434-460)**

Remove the entire function.

- [ ] **Step 8: Delete `RuntimeHint` composable (lines 462-517)**

Remove the entire function.

- [ ] **Step 9: Delete `commandLabel` function (lines 574-579)**

Remove the entire function.

- [ ] **Step 10: Delete `commandPlaceholder` function (lines 581-587)**

Remove the entire function.

- [ ] **Step 11: Simplify `McpExampleChips` (lines 522-569)**

Remove the node.js example ("mcp_fetch.js"). Keep only the remote_http example. Remove `RuntimeBadge(runtime = example.runtime)` call — use `RuntimeBadge()` (no args).

**After:**
```kotlin
    val examples = listOf(
        McpServer(
            name = uiText("mcp.example.remote", R.string.mcp_example_remote),
            command = "https://mcp-server-example.vercel.app/sse",
            args = "[]",
            env = "{}"
        )
    )
```

- [ ] **Step 12: Remove `mcpWorkDir` parameter from `McpServerEditDialog` signature**

Since `McpScriptManager` is gone, `mcpWorkDir` is no longer available. Remove the parameter from the function signature and from all call sites in `McpConfigScreen.kt`.

- [ ] **Step 13: Remove unused imports**

Remove imports only needed by deleted composables.

- [ ] **Step 14: Commit**

```bash
git add app/src/main/java/com/omnichat/ui/screens/McpDialogs.kt \
       app/src/main/java/com/omnichat/ui/screens/McpConfigScreen.kt
git commit -m "refactor: remove runtime selector and node/python hints from MCP dialogs"
```

---

### Task 8: Update CLAUDE.md Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Remove CMake and NDK from prerequisites**

Remove these lines:
```
- **CMake**: 3.22.1
- **NDK**: 27.0.12077973 (for JNI native bridges)
```

- [ ] **Step 2: Update "Native Code (MCP Runtime)" section**

Replace the entire section with:
```markdown
## MCP Runtime

- **Remote HTTP**: Direct HTTP/HTTPS connection to remote MCP servers (no native runtime needed)
- Supports both old SSE (2024-11-05) and new Streamable HTTP (2025-03-26) protocols
```

- [ ] **Step 3: Remove NodeJsBridge, PythonBridge, PythonRuntime, McpScriptManager from package table**

Remove these rows from the package structure table:
```
| `com.omnichat.mcp` | ... `NodeJsBridge`, `PythonBridge`, `PythonRuntime`, `McpScriptManager` ... |
```

- [ ] **Step 4: Remove embedded runtime notes**

Remove:
- "Node.js can start only once per process (nodejs-mobile limitation) — merge multiple servers into one entry script"
- "Native runtimes are optional; app degrades gracefully without them"

- [ ] **Step 5: Update "Common Modification Tasks" section**

Remove the "Add MCP server support" entry that mentions node/python patterns.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md to reflect removal of embedded runtimes"
```

---

### Task 9: Build Verification

- [ ] **Step 1: Run debug build**

```bash
cd E:/omnichat
./gradlew assembleDebug 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

```bash
./gradlew testDebugUnitTest 2>&1 | tail -20
```
Expected: All tests pass

- [ ] **Step 3: Verify no remaining references to removed types**

```bash
grep -rn "NodeJsBridge\|PythonBridge\|PythonRuntime\|McpScriptManager\|libnode\|libpython\|isNodeEnabled\|isPythonEnabled" \
  app/src/main/java/ app/src/main/cpp/ app/build.gradle.kts CLAUDE.md \
  --include="*.kt" --include="*.kts" --include="*.md" --include="*.xml"
```
Expected: No matches (except possibly in test files — check those separately)

- [ ] **Step 4: Verify no .so files remain**

```bash
find app/src/main/jniLibs/ -name "*.so" 2>/dev/null || echo "OK: no jniLibs directory"
```
Expected: `OK: no jniLibs directory`

- [ ] **Step 5: Check for compilation errors in test sources**

```bash
grep -rn "NodeJsBridge\|PythonBridge\|PythonRuntime\|McpScriptManager\|server\.runtime\|isNodeEnabled\|isPythonEnabled" \
  app/src/test/ --include="*.kt" 2>/dev/null
```
If any matches found, update those test files to remove references to deleted types.
