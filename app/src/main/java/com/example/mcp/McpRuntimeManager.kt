package com.example.mcp

import android.content.Context
import android.util.Log
import com.example.data.McpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
private const val TAG = "McpRuntimeManager"

// ── 公开数据类 ────────────────────────────────────────────────────────────

data class McpTool(
    val serverId: Long,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

enum class McpServerStatus { STOPPED, STARTING, RUNNING, ERROR }

data class McpServerState(
    val server: McpServer,
    val status: McpServerStatus = McpServerStatus.STOPPED,
    val errorMessage: String? = null,
    val tools: List<McpTool> = emptyList()
)

// ── 内部通信通道抽象 ──────────────────────────────────────────────────────

/**
 * 代表一个与 MCP server 的双向通信通道。
 * 对于 Python/外部进程：直接包装 Process 的 stdin/stdout。
 * 对于 Node.js（JNI 模式）：通过本地 socket 通信。
 */
private sealed class McpChannel {
    abstract val reader: BufferedReader
    abstract val writer: PrintWriter
    abstract fun close()

    /** 直接 stdio 通道（Python ProcessBuilder） */
    class StdioChannel(private val process: Process) : McpChannel() {
        override val reader = BufferedReader(InputStreamReader(process.inputStream))
        override val writer = PrintWriter(BufferedWriter(OutputStreamWriter(process.outputStream)), true)
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
        val isAlive get() = process.isAlive
        val exitValue get() = if (process.isAlive) null else process.exitValue()
        override fun close() {
            writer.close()
            process.destroyForcibly()
        }
    }

    /** Socket 通道（Node.js JNI 模式，通过本地 TCP socket 通信） */
    class SocketChannel(private val socket: Socket) : McpChannel() {
        override val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        override val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        override fun close() {
            writer.close()
            socket.close()
        }
    }
}

// ── McpRuntimeManager ─────────────────────────────────────────────────────

/**
 * MCP 运行时管理器。
 *
 * 支持两种运行时：
 *
 * 1. Python（runtime = "python"）
 *    通过 ProcessBuilder 启动解压到私有目录的 CPython 二进制，
 *    直接用 stdin/stdout 进行 MCP JSON-RPC 通信。
 *    需要在 assets/python/<ABI>/python.zip 中放置预编译的 CPython。
 *
 * 2. Node.js（runtime = "node"）
 *    通过 JNI 调用 libnode.so（nodejs-mobile 项目）在后台线程运行 JS 脚本。
 *    由于 Node.js 运行在同进程内，通过本地 TCP socket 与主进程通信。
 *    JS 脚本需要监听一个随机端口，并将端口号写入 stdout 的第一行。
 *    需要在 app/src/main/jniLibs/<ABI>/libnode.so 中放置预编译的 libnode.so。
 *
 * 注意：Node.js 运行时（libnode.so）在整个进程生命周期内只能启动一次。
 * 如果需要运行多个 Node.js MCP server，需要在同一个 Node.js 实例中用不同端口启动多个 server。
 */
class McpRuntimeManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // serverId -> 通信通道
    private val channels = ConcurrentHashMap<Long, McpChannel>()

    // serverId -> 待响应请求 (requestId -> Deferred)
    private val pendingRequests = ConcurrentHashMap<Long, ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>>()

    private val requestIdCounter = AtomicLong(1)

    private val _serverStates = MutableStateFlow<Map<Long, McpServerState>>(emptyMap())
    val serverStates: StateFlow<Map<Long, McpServerState>> = _serverStates.asStateFlow()

    val allTools: StateFlow<List<McpTool>> = serverStates
        .map { states -> states.values.flatMap { it.tools } }
        .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    // ── 公开 API ──────────────────────────────────────────────────────────

    fun startServer(server: McpServer) {
        scope.launch {
            updateState(server.id) { McpServerState(server, McpServerStatus.STARTING) }
            try {
                when (server.runtime) {
                    "node" -> startNodeServer(server)
                    "python" -> startPythonServer(server)
                    "npx" -> startProcessServer(server, buildNpxCommand(server))
                    "uvx" -> startProcessServer(server, buildUvxCommand(server))
                    else -> {
                        updateState(server.id) {
                            McpServerState(server, McpServerStatus.ERROR, "不支持的运行时: ${server.runtime}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动 MCP server [${server.name}] 失败", e)
                updateState(server.id) {
                    McpServerState(server, McpServerStatus.ERROR, e.localizedMessage ?: "启动失败")
                }
            }
        }
    }

    private fun buildNpxCommand(server: McpServer): List<String> {
        val args = parseJsonArray(server.args)
        return listOf("npx", "--yes", server.command) + args
    }

    private fun buildUvxCommand(server: McpServer): List<String> {
        val args = parseJsonArray(server.args)
        return listOf("uvx", server.command) + args
    }

    /**
     * 通过 ProcessBuilder 启动外部命令（npx / uvx），使用标准 MCP stdio transport。
     * 直接将进程的 stdin/stdout 作为 MCP JSON-RPC 通道，无需 socket 中转。
     */
    private suspend fun startProcessServer(server: McpServer, command: List<String>) {
        Log.d(TAG, "启动外部进程 MCP server: ${command.joinToString(" ")}")

        val envMap = parseJsonObject(server.env)

        val process = try {
            ProcessBuilder(command).apply {
                redirectErrorStream(false)
                environment().putAll(envMap)
            }.start()
        } catch (e: Exception) {
            val execName = command.firstOrNull() ?: "unknown"
            Log.e(TAG, "无法启动进程: ${command.joinToString(" ")}", e)
            updateState(server.id) {
                McpServerState(
                    server, McpServerStatus.ERROR,
                    "无法启动 $execName 命令：${e.localizedMessage}\n\n" +
                    "请确认设备已安装 $execName 并可通过 PATH 访问。\n" +
                    "Android 设备通常需要 root 或 Termux 环境才能使用系统命令。"
                )
            }
            return
        }

        val channel = McpChannel.StdioChannel(process)
        channels[server.id] = channel
        pendingRequests[server.id] = ConcurrentHashMap()

        // 后台读取 stderr（仅用于日志）
        scope.launch { readStderr(server.id, channel.errorReader) }

        // 后台读取 stdout（MCP JSON-RPC 响应）
        scope.launch {
            readChannelOutput(server.id, channel)
            // 进程退出后更新状态
            val exitCode = channel.exitValue
            Log.i(TAG, "外部进程 MCP server [${server.name}] 退出，返回码: $exitCode")
        }

        // 等待进程就绪（外部进程启动比内嵌运行时快）
        delay(500)

        if (!channel.isAlive) {
            val exitCode = channel.exitValue ?: -1
            updateState(server.id) {
                McpServerState(server, McpServerStatus.ERROR, "进程启动后立即退出（退出码: $exitCode），请检查命令和参数是否正确")
            }
            channels.remove(server.id)
            pendingRequests.remove(server.id)
            return
        }

        performHandshake(server)
    }

    fun stopServer(serverId: Long) {
        scope.launch {
            channels[serverId]?.close()
            channels.remove(serverId)
            pendingRequests[serverId]?.values?.forEach { it.cancel() }
            pendingRequests.remove(serverId)
            val current = _serverStates.value[serverId]
            if (current != null) {
                updateState(serverId) { current.copy(status = McpServerStatus.STOPPED, tools = emptyList()) }
            }
        }
    }

    fun stopAll() {
        channels.keys.toList().forEach { stopServer(it) }
        scope.cancel()
    }

    suspend fun callTool(serverId: Long, toolName: String, arguments: JSONObject): JSONObject? {
        return try {
            val response = sendRequest(
                serverId = serverId,
                method = "tools/call",
                params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            )
            response.optJSONObject("result")
        } catch (e: Exception) {
            Log.e(TAG, "调用工具 $toolName 失败", e)
            null
        }
    }

    suspend fun refreshTools(serverId: Long) {
        try {
            val response = sendRequest(serverId, "tools/list", JSONObject())
            val toolsArray = response.optJSONObject("result")?.optJSONArray("tools") ?: return
            val server = _serverStates.value[serverId]?.server ?: return
            val tools = (0 until toolsArray.length()).mapNotNull { i ->
                val t = toolsArray.optJSONObject(i) ?: return@mapNotNull null
                McpTool(
                    serverId = serverId,
                    serverName = server.name,
                    name = t.optString("name"),
                    description = t.optString("description"),
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
                )
            }
            updateState(serverId) { it.copy(tools = tools) }
        } catch (e: Exception) {
            Log.e(TAG, "刷新工具列表失败 serverId=$serverId", e)
        }
    }

    // ── Python 启动 ───────────────────────────────────────────────────────

    private suspend fun startPythonServer(server: McpServer) {
        // 确保 Python 运行时已就绪
        val ready = PythonRuntime.ensureReady(context)
        if (!ready) {
            updateState(server.id) {
                McpServerState(
                    server, McpServerStatus.ERROR,
                    "Python 运行时不可用。\n\n" +
                    "请按以下步骤准备：\n" +
                    "1. 下载官方 Android 包：\n" +
                    "   https://www.python.org/ftp/python/3.14.5/\n" +
                    "   python-3.14.5-${PythonRuntime.getSupportedAbi().replace("arm64-v8a","aarch64")}-linux-android.tar.gz\n\n" +
                    "2. 解压后将 .so 文件放入：\n" +
                    "   app/src/main/jniLibs/${PythonRuntime.getSupportedAbi()}/\n" +
                    "   (libpython3.14.so, libssl_python.so, libcrypto_python.so)\n\n" +
                    "3. 将标准库打包：\n" +
                    "   cd prefix/lib && zip -r stdlib.zip python3.14/\n" +
                    "   放入 app/src/main/assets/python/stdlib.zip\n\n" +
                    "4. 重新编译 App"
                )
            }
            return
        }

        // 分配随机端口
        val port = findFreePort()
        Log.d(TAG, "Python MCP server 将监听端口: $port")

        // 在后台线程通过 JNI 运行 Python MCP server
        val pythonThread = Thread({
            val args = parseJsonArray(server.args)
            val env = parseJsonObject(server.env)

            // 构建 Python 启动代码：
            // 1. 设置环境变量
            // 2. 将 MCP server 脚本路径加入 sys.path
            // 3. 启动 MCP server，监听指定端口
            val envSetup = env.entries.joinToString("\n") { (k, v) ->
                "import os; os.environ['${k.replace("'", "\\'")}'] = '${v.replace("'", "\\'")}'"
            }
            val scriptPath = server.command
            val argsStr = args.joinToString(", ") { "'${it.replace("'", "\\'")}'" }

            val pythonCode = """
import sys
import os
$envSetup
os.environ['MCP_SOCKET_PORT'] = '$port'
sys.argv = ['$scriptPath'${if (args.isNotEmpty()) ", $argsStr" else ""}]
# 将脚本目录加入 sys.path
import os.path
script_dir = os.path.dirname(os.path.abspath('$scriptPath'))
if script_dir not in sys.path:
    sys.path.insert(0, script_dir)
# 执行 MCP server 脚本
exec(open('$scriptPath').read())
""".trimIndent()

            val result = PythonBridge.runCode(pythonCode)
            Log.i(TAG, "Python MCP server 退出，返回码: $result")

            scope.launch {
                val current = _serverStates.value[server.id]
                if (current?.status == McpServerStatus.RUNNING || current?.status == McpServerStatus.STARTING) {
                    updateState(server.id) {
                        it.copy(status = McpServerStatus.STOPPED, tools = emptyList())
                    }
                }
            }
        }, "python-mcp-${server.id}")
        pythonThread.isDaemon = true
        pythonThread.start()

        // 等待 Python server 启动
        delay(1500)

        // 连接到 Python server 的 socket
        try {
            val socket = withTimeout(15_000L) {
                var s: Socket? = null
                var attempts = 0
                while (s == null && attempts < 30) {
                    try {
                        s = Socket("127.0.0.1", port)
                    } catch (e: Exception) {
                        attempts++
                        delay(500)
                    }
                }
                s ?: throw IOException("无法连接到 Python MCP server (port=$port)")
            }

            val channel = McpChannel.SocketChannel(socket)
            channels[server.id] = channel
            pendingRequests[server.id] = ConcurrentHashMap()

            scope.launch { readChannelOutput(server.id, channel) }
            performHandshake(server)

        } catch (e: Exception) {
            Log.e(TAG, "连接 Python MCP server 失败", e)
            updateState(server.id) {
                McpServerState(server, McpServerStatus.ERROR, "连接失败: ${e.localizedMessage}")
            }
        }
    }

    // ── Node.js 启动 ──────────────────────────────────────────────────────

    private suspend fun startNodeServer(server: McpServer) {
        if (!NodeJsBridge.isLoaded) {
            updateState(server.id) {
                McpServerState(
                    server, McpServerStatus.ERROR,
                    "Node.js 运行时不可用（libnode.so 未加载）。\n" +
                    "请从 https://github.com/nodejs-mobile/nodejs-mobile/releases 下载\n" +
                    "并将 libnode.so 放入 app/src/main/jniLibs/<ABI>/ 目录，\n" +
                    "同时将 include/ 目录复制到 app/src/main/cpp/node_include/"
                )
            }
            return
        }

        if (NodeJsBridge.hasStarted) {
            updateState(server.id) {
                McpServerState(
                    server, McpServerStatus.ERROR,
                    "Node.js 运行时已被占用（每个进程只能启动一次 Node.js）。\n" +
                    "请将多个 Node.js MCP server 合并到同一个入口脚本中。"
                )
            }
            return
        }

        // 确保内置脚本已部署到 MCP 工作目录
        val mcpDir = McpScriptManager.ensureScriptsDeployed(context)

        // 解析用户脚本路径：
        //   - 绝对路径 -> 直接使用
        //   - 仅文件名（如 "mcp_filesystem.js"）-> 在 MCP 工作目录中查找
        val userScriptFile = resolveUserScript(server.command, mcpDir)
        if (userScriptFile == null || !userScriptFile.exists()) {
            val mcpDirPath = mcpDir.absolutePath
            updateState(server.id) {
                McpServerState(
                    server, McpServerStatus.ERROR,
                    "找不到脚本文件: ${server.command}\n\n" +
                    "请将 .js 脚本放入 MCP 工作目录：\n$mcpDirPath\n\n" +
                    "内置脚本（已自动部署）：\n" +
                    "  • mcp_filesystem.js — 文件系统访问\n" +
                    "  • mcp_fetch.js      — HTTP 请求"
                )
            }
            return
        }

        // bridge 脚本负责 socket 通信，用户脚本通过 bridge 的子进程运行
        val bridgeScript = File(mcpDir, "mcp_socket_bridge.js")
        if (!bridgeScript.exists()) {
            updateState(server.id) {
                McpServerState(server, McpServerStatus.ERROR, "bridge 脚本不存在，请重启应用重新部署")
            }
            return
        }

        val port = findFreePort()
        Log.d(TAG, "Node.js MCP server [${server.name}] 将监听端口: $port，脚本: ${userScriptFile.absolutePath}")

        val nodeThread = Thread({
            val extraArgs = parseJsonArray(server.args).toTypedArray()
            // bridge 脚本参数：--mcp-port=<port> <用户脚本路径> [用户参数...]
            val result = NodeJsBridge.startScript(
                bridgeScript.absolutePath,
                "--mcp-port=$port",
                userScriptFile.absolutePath,
                *extraArgs
            )
            Log.i(TAG, "Node.js 进程退出，返回码: $result")
            scope.launch {
                val current = _serverStates.value[server.id]
                if (current?.status == McpServerStatus.RUNNING || current?.status == McpServerStatus.STARTING) {
                    updateState(server.id) { it.copy(status = McpServerStatus.STOPPED, tools = emptyList()) }
                }
            }
        }, "nodejs-mcp-${server.id}")
        nodeThread.isDaemon = true
        nodeThread.start()

        delay(1500)

        try {
            val socket = withTimeout(10_000L) {
                var s: Socket? = null
                var attempts = 0
                while (s == null && attempts < 20) {
                    try {
                        s = Socket("127.0.0.1", port)
                    } catch (e: Exception) {
                        attempts++
                        delay(500)
                    }
                }
                s ?: throw IOException("无法连接到 Node.js MCP server (port=$port)")
            }

            val channel = McpChannel.SocketChannel(socket)
            channels[server.id] = channel
            pendingRequests[server.id] = ConcurrentHashMap()

            scope.launch { readChannelOutput(server.id, channel) }
            performHandshake(server)

        } catch (e: Exception) {
            Log.e(TAG, "连接 Node.js MCP server 失败", e)
            updateState(server.id) {
                McpServerState(server, McpServerStatus.ERROR, "连接失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * 解析用户脚本路径：
     *   - 绝对路径 -> 直接返回对应 File
     *   - 相对路径/文件名 -> 在 mcpDir 中查找
     */
    private fun resolveUserScript(command: String, mcpDir: File): File? {
        if (command.startsWith("/")) return File(command)
        // 去掉可能的目录前缀，只取文件名在 mcpDir 中查找
        val fileName = File(command).name
        return File(mcpDir, fileName)
    }

    // ── MCP 握手 ──────────────────────────────────────────────────────────

    private suspend fun performHandshake(server: McpServer) {
        try {
            val initResponse = sendRequest(
                serverId = server.id,
                method = "initialize",
                params = JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject().apply {
                        put("roots", JSONObject().apply { put("listChanged", true) })
                        put("sampling", JSONObject())
                    })
                    put("clientInfo", JSONObject().apply {
                        put("name", "OmniChat")
                        put("version", "1.0.0")
                    })
                }
            )

            if (initResponse.has("error")) {
                val errMsg = initResponse.optJSONObject("error")?.optString("message") ?: "初始化失败"
                updateState(server.id) { McpServerState(server, McpServerStatus.ERROR, errMsg) }
                return
            }

            sendNotification(server.id, "notifications/initialized", JSONObject())
            updateState(server.id) { McpServerState(server, McpServerStatus.RUNNING) }
            refreshTools(server.id)

        } catch (e: Exception) {
            Log.e(TAG, "MCP 握手失败 [${server.name}]", e)
            updateState(server.id) {
                McpServerState(server, McpServerStatus.ERROR, "握手超时或失败: ${e.localizedMessage}")
            }
        }
    }

    // ── 通信层 ────────────────────────────────────────────────────────────

    private suspend fun sendRequest(
        serverId: Long,
        method: String,
        params: JSONObject
    ): JSONObject = withTimeout(30_000L) {
        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[serverId]?.put(id, deferred)

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val channel = channels[serverId] ?: throw IOException("Server $serverId 未运行")
        channel.writer.println(request.toString())

        try {
            deferred.await()
        } finally {
            pendingRequests[serverId]?.remove(id)
        }
    }

    private fun sendNotification(serverId: Long, method: String, params: JSONObject) {
        val notification = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        channels[serverId]?.writer?.println(notification.toString())
    }

    private suspend fun readChannelOutput(serverId: Long, channel: McpChannel) {
        try {
            var line: String?
            while (channel.reader.readLine().also { line = it } != null) {
                val raw = line?.trim() ?: continue
                if (raw.isEmpty()) continue
                try {
                    val json = JSONObject(raw)
                    dispatchResponse(serverId, json)
                } catch (e: Exception) {
                    Log.w(TAG, "无法解析 MCP 输出: $raw")
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Server $serverId 输出流关闭")
        } finally {
            val current = _serverStates.value[serverId]
            if (current?.status == McpServerStatus.RUNNING || current?.status == McpServerStatus.STARTING) {
                updateState(serverId) { it.copy(status = McpServerStatus.STOPPED, tools = emptyList()) }
            }
        }
    }

    private suspend fun readStderr(serverId: Long, reader: BufferedReader) {
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "[stderr $serverId] $line")
            }
        } catch (e: IOException) { /* 忽略 */ }
    }

    private fun dispatchResponse(serverId: Long, json: JSONObject) {
        if (json.has("id") && !json.isNull("id")) {
            val id = json.optLong("id")
            pendingRequests[serverId]?.get(id)?.complete(json)
        } else {
            Log.d(TAG, "[notification $serverId] ${json.optString("method")}")
        }
    }

    private fun updateState(serverId: Long, transform: (McpServerState) -> McpServerState) {
        val current = _serverStates.value
        val existing = current[serverId]
        if (existing != null) {
            _serverStates.value = current + (serverId to transform(existing))
        } else {
            _serverStates.value = current + (serverId to transform(
                McpServerState(McpServer(id = serverId, name = "", command = ""))
            ))
        }
    }

    // ── 工具函数 ──────────────────────────────────────────────────────────

    private fun parseJsonArray(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.optString(it) }
    } catch (e: Exception) { emptyList() }

    private fun parseJsonObject(json: String): Map<String, String> = try {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.optString(it) }
    } catch (e: Exception) { emptyMap() }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
