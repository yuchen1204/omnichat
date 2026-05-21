package com.example.mcp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(database)

    val runtimeManager = McpRuntimeManager(application)

    // 所有已配置的 MCP server（来自数据库）
    val mcpServers: StateFlow<List<McpServer>> = repository.allMcpServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 各 server 的运行状态
    val serverStates: StateFlow<Map<Long, McpServerState>> = runtimeManager.serverStates

    // 所有已发现的工具
    val allTools: StateFlow<List<McpTool>> = runtimeManager.allTools

    // ── 运行时可用性状态 ──────────────────────────────────────────────────

    /** Node.js 运行时（libnode.so）是否已加载 */
    val isNodeRuntimeAvailable: Boolean get() = NodeJsBridge.isLoaded

    /** Python 运行时是否已就绪 */
    var isPythonRuntimeReady by mutableStateOf(false)
        private set

    /** Python 运行时初始化状态消息 */
    var pythonRuntimeStatus by mutableStateOf("检测中...")
        private set

    /** MCP 工作目录路径（用于 UI 展示） */
    val mcpWorkDir: String get() = McpScriptManager.getMcpDir(getApplication()).absolutePath

    init {
        // 部署内置 MCP 脚本到工作目录
        viewModelScope.launch(Dispatchers.IO) {
            McpScriptManager.ensureScriptsDeployed(application)
        }

        // 检测 Python 运行时
        viewModelScope.launch {
            val ready = PythonRuntime.ensureReady(application)
            if (ready) {
                isPythonRuntimeReady = true
                pythonRuntimeStatus = "就绪 (Python 3.14, PYTHONHOME=${PythonRuntime.getPythonHome(application)})"
            } else {
                isPythonRuntimeReady = false
                val abi = PythonRuntime.getSupportedAbi()
                val abiName = if (abi == "arm64-v8a") "aarch64" else abi
                pythonRuntimeStatus = "未就绪 — 请下载 python-3.14.5-$abiName-linux-android.tar.gz\n" +
                    "并将 .so 放入 jniLibs/$abi/，stdlib.zip 放入 assets/python/"
            }
        }

        // 应用启动时自动启动所有已启用的 server
        viewModelScope.launch {
            val enabled = repository.getEnabledMcpServers()
            enabled.forEach { server ->
                runtimeManager.startServer(server)
            }
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    fun addServer(server: McpServer) {
        viewModelScope.launch {
            val id = repository.insertMcpServer(server)
            if (server.isEnabled) {
                runtimeManager.startServer(server.copy(id = id))
            }
        }
    }

    fun updateServer(server: McpServer) {
        viewModelScope.launch {
            repository.updateMcpServer(server)
            runtimeManager.stopServer(server.id)
            if (server.isEnabled) {
                runtimeManager.startServer(server)
            }
        }
    }

    fun deleteServer(server: McpServer) {
        viewModelScope.launch {
            runtimeManager.stopServer(server.id)
            repository.deleteMcpServer(server)
        }
    }

    fun toggleServer(server: McpServer) {
        val updated = server.copy(isEnabled = !server.isEnabled)
        viewModelScope.launch {
            repository.updateMcpServer(updated)
            if (updated.isEnabled) {
                runtimeManager.startServer(updated)
            } else {
                runtimeManager.stopServer(updated.id)
            }
        }
    }

    fun restartServer(server: McpServer) {
        viewModelScope.launch {
            runtimeManager.stopServer(server.id)
            if (server.isEnabled) {
                runtimeManager.startServer(server)
            }
        }
    }

    fun refreshTools(serverId: Long) {
        viewModelScope.launch {
            runtimeManager.refreshTools(serverId)
        }
    }

    // ── JSON 配置导入 ─────────────────────────────────────────────────────

    /**
     * 从标准 MCP JSON 配置字符串批量导入服务器。
     * 支持格式：
     * {
     *   "mcpServers": {
     *     "serverName": {
     *       "command": "npx",
     *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"],
     *       "env": { "KEY": "value" }
     *     }
     *   }
     * }
     *
     * 运行时自动推断规则：
     *   command == "npx" 或 args 中含 "npx"  → runtime = "npx"，command = 第一个非 flag 的包名
     *   command == "uvx"                      → runtime = "uvx"，command = args[0]
     *   command == "node"                     → runtime = "node"，command = args[0]
     *   command == "python" / "python3"       → runtime = "python"，command = args[0]
     *   其他                                  → runtime = "npx"，command = command（原样保留）
     *
     * @return 成功导入的数量，或 -1 表示 JSON 解析失败
     */
    fun importFromJson(jsonString: String): Int {
        return try {
            val root = org.json.JSONObject(jsonString.trim())
            val servers = root.optJSONObject("mcpServers") ?: return -1

            var count = 0
            servers.keys().forEach { serverName ->
                val cfg = servers.optJSONObject(serverName) ?: return@forEach
                val rawCommand = cfg.optString("command", "").trim()
                val argsArray = cfg.optJSONArray("args") ?: org.json.JSONArray()
                val envObj = cfg.optJSONObject("env") ?: org.json.JSONObject()

                val argsList = (0 until argsArray.length()).map { argsArray.optString(it) }

                // 推断运行时和实际命令
                val (runtime, command, remainingArgs) = inferRuntime(rawCommand, argsList)

                val server = McpServer(
                    name = serverName,
                    runtime = runtime,
                    command = command,
                    args = org.json.JSONArray(remainingArgs).toString(),
                    env = envObj.toString(),
                    isEnabled = true
                )
                addServer(server)
                count++
            }
            count
        } catch (e: Exception) {
            android.util.Log.e("McpViewModel", "导入 JSON 配置失败", e)
            -1
        }
    }

    /**
     * 根据 command + args 推断运行时类型，返回 Triple(runtime, command, remainingArgs)。
     *
     * 处理以下常见模式：
     *   "npx" ["-y"/"--yes", "@scope/pkg", ...args]  → npx, @scope/pkg, [...args]
     *   "cmd" ["/c", "npx", "-y", "@scope/pkg", ...] → npx, @scope/pkg, [...args]
     *   "uvx" ["pkg", ...args]                        → uvx, pkg, [...args]
     *   "node" ["script.js", ...args]                 → node, script.js, [...args]
     *   "python"/"python3" ["script.py", ...args]     → python, script.py, [...args]
     *   其他                                           → npx, command, args（原样）
     */
    private fun inferRuntime(rawCommand: String, args: List<String>): Triple<String, String, List<String>> {
        val cmd = rawCommand.lowercase()

        // cmd /c npx ... 或 cmd.exe /c npx ...
        if (cmd == "cmd" || cmd == "cmd.exe") {
            val rest = args.dropWhile { it.startsWith("/") || it.startsWith("-") }
            if (rest.isNotEmpty()) {
                val innerCmd = rest[0].lowercase()
                val innerArgs = rest.drop(1)
                if (innerCmd == "npx") {
                    return extractNpxPackage(innerArgs)
                }
                if (innerCmd == "uvx") {
                    val pkg = innerArgs.firstOrNull() ?: ""
                    return Triple("uvx", pkg, innerArgs.drop(1))
                }
            }
            // 无法识别，原样保留（用 npx 运行时）
            return Triple("npx", rawCommand, args)
        }

        return when (cmd) {
            "npx" -> extractNpxPackage(args)
            "uvx" -> {
                val pkg = args.firstOrNull() ?: ""
                Triple("uvx", pkg, args.drop(1))
            }
            "node", "node.exe" -> {
                val script = args.firstOrNull() ?: ""
                Triple("node", script, args.drop(1))
            }
            "python", "python3", "python.exe", "python3.exe" -> {
                val script = args.firstOrNull() ?: ""
                Triple("python", script, args.drop(1))
            }
            else -> Triple("npx", rawCommand, args)
        }
    }

    /** 从 npx 参数列表中提取包名和剩余参数，跳过 -y/--yes 等 flag */
    private fun extractNpxPackage(args: List<String>): Triple<String, String, List<String>> {
        val npxFlags = setOf("-y", "--yes", "--no", "-p", "--package", "--prefer-offline",
                             "--prefer-online", "--ignore-existing", "-q", "--quiet")
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a in npxFlags) { i++; continue }
            if (a.startsWith("--") && !a.startsWith("@")) { i++; continue }
            // 找到包名
            val pkg = a
            val remaining = args.drop(i + 1)
            return Triple("npx", pkg, remaining)
        }
        return Triple("npx", args.firstOrNull() ?: "", emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        runtimeManager.stopAll()
    }
}
