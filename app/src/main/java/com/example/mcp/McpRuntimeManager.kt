package com.example.mcp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.McpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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

    /** Socket 通道（Node.js JNI 模式，通过本地 TCP socket 通信） */
    class SocketChannel(private val socket: Socket) : McpChannel() {
        override val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        override val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        override fun close() {
            writer.close()
            socket.close()
        }
    }

    /**
     * HTTP 通道（远程 MCP server）。
     *
     * 支持两种协议：
     *
     * 1. 旧版 HTTP/SSE（MCP 规范 2024-11-05）
     *    - GET <sseUrl> 建立 SSE 长连接，服务器推送 `event: endpoint` 告知 POST URL
     *    - POST <postUrl> 发送 JSON-RPC 消息，响应通过 SSE 流返回
     *    - 典型端点：/sse + /message
     *
     * 2. 新版 Streamable HTTP（MCP 规范 2025-03-26）
     *    - 单一端点，所有消息通过 POST 发送
     *    - 响应可以是普通 JSON（单条消息）或 SSE 流（多条消息）
     *    - 支持 Mcp-Session-Id 头进行会话管理
     *    - 典型端点：/mcp
     *
     * [isStreamableHttp] 为 true 时使用新版协议，false 时使用旧版协议。
     */
    class HttpChannel(
        val sseUrl: String,
        private val okHttpClient: OkHttpClient,
        private val scope: CoroutineScope,
        private val onResponse: (JSONObject) -> Unit,
        val isStreamableHttp: Boolean = false,
        private val customHeaders: Map<String, String> = emptyMap()
    ) : McpChannel() {
        private val pipedInputStream = PipedInputStream()
        private val pipedOutputStream = PipedOutputStream(pipedInputStream)
        override val reader = BufferedReader(InputStreamReader(pipedInputStream))
        override val writer = PrintWriter(BufferedWriter(OutputStreamWriter(pipedOutputStream)), true)

        // 旧版 SSE 协议：服务器通过 endpoint 事件告知 POST URL
        @Volatile
        var postUrl: String? = null

        // 新版 Streamable HTTP 协议：会话 ID（服务器可选返回）
        @Volatile
        var sessionId: String? = null

        private var sseCall: okhttp3.Call? = null

        // ── 旧版 HTTP/SSE 协议 ────────────────────────────────────────────

        fun startSse() {
            val requestBuilder = okhttp3.Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
            customHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
            val request = requestBuilder.build()

            sseCall = okHttpClient.newCall(request)
            sseCall?.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e(TAG, "SSE 连接失败: $sseUrl", e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "SSE 响应错误: ${response.code}")
                        return
                    }
                    parseSseStream(response)
                }
            })
        }

        // ── 新版 Streamable HTTP 协议 ─────────────────────────────────────

        /**
         * 发送 JSON-RPC 消息（Streamable HTTP 协议）。
         * 响应可能是：
         * - `application/json`：单条 JSON-RPC 响应，直接解析
         * - `text/event-stream`：SSE 流，包含一条或多条 `data:` 事件
         * - 202 Accepted：无响应体，结果将通过后续 GET SSE 流推送
         */
        suspend fun sendStreamablePost(jsonRpc: String) {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBuilder = okhttp3.Request.Builder()
                .url(sseUrl)
                .header("Accept", "application/json, text/event-stream")
                .post(jsonRpc.toRequestBody(mediaType))

            sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
            customHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

            val request = requestBuilder.build()

            withContext(Dispatchers.IO) {
                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        // 保存服务器返回的会话 ID
                        response.header("Mcp-Session-Id")?.let { id ->
                            if (sessionId == null) {
                                sessionId = id
                                Log.d(TAG, "Streamable HTTP 会话 ID: $id")
                            }
                        }

                        if (!response.isSuccessful) {
                            Log.e(TAG, "Streamable HTTP POST 失败: ${response.code} ${response.message}")
                            return@use
                        }

                        val contentType = response.header("Content-Type") ?: ""
                        when {
                            contentType.contains("text/event-stream") -> {
                                // 响应是 SSE 流，解析其中的 data 事件
                                parseSseStream(response)
                            }
                            contentType.contains("application/json") -> {
                                // 响应是单条 JSON
                                val body = response.body?.string()
                                if (!body.isNullOrBlank()) {
                                    try {
                                        onResponse(JSONObject(body))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "解析 Streamable HTTP JSON 响应失败", e)
                                    }
                                }
                            }
                            response.code == 202 -> {
                                // 服务器接受请求，结果将异步推送，无需处理响应体
                                Log.d(TAG, "Streamable HTTP 202 Accepted")
                            }
                            else -> {
                                // 尝试作为 JSON 解析
                                val body = response.body?.string()
                                if (!body.isNullOrBlank()) {
                                    try {
                                        onResponse(JSONObject(body))
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streamable HTTP POST 请求失败", e)
                }
            }
        }

        // ── 旧版 SSE 协议发送 ─────────────────────────────────────────────

        suspend fun sendPost(jsonRpc: String) {
            val url = postUrl ?: throw IOException("尚未收到 POST 端点地址")
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBuilder = okhttp3.Request.Builder()
                .url(url)
                .post(jsonRpc.toRequestBody(mediaType))
            customHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
            val request = requestBuilder.build()

            withContext(Dispatchers.IO) {
                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.e(TAG, "POST 请求失败: ${response.code}")
                        }
                        // 某些实现可能在 POST 响应中直接返回结果，某些通过 SSE 返回
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            try {
                                onResponse(JSONObject(body))
                            } catch (e: Exception) {
                                // 可能不是 JSON，忽略
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "发送 MCP POST 请求失败", e)
                }
            }
        }

        // ── 公共 SSE 流解析 ───────────────────────────────────────────────

        /**
         * 解析 SSE 响应流。
         * 处理 `event: endpoint`（旧版协议）和 `event: message` / 无名事件（两种协议均有）。
         */
        private fun parseSseStream(response: okhttp3.Response) {
            val body = response.body?.source() ?: return
            try {
                var eventType = "message"
                val dataLines = mutableListOf<String>()

                while (!body.exhausted()) {
                    val line = body.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> {
                            eventType = line.substringAfter("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            dataLines.add(line.substringAfter("data:").trim())
                        }
                        line.isEmpty() -> {
                            // 空行表示一个事件结束，分发事件
                            if (dataLines.isNotEmpty()) {
                                val data = dataLines.joinToString("\n")
                                dataLines.clear()
                                dispatchSseEvent(eventType, data)
                                eventType = "message" // 重置为默认事件类型
                            }
                        }
                        line.startsWith(":") -> {
                            // SSE 注释/心跳，忽略
                        }
                    }
                }
                // 处理流末尾未以空行结束的事件
                if (dataLines.isNotEmpty()) {
                    dispatchSseEvent(eventType, dataLines.joinToString("\n"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取 SSE 流失败", e)
            }
        }

        private fun dispatchSseEvent(event: String, data: String) {
            when (event) {
                "endpoint" -> {
                    // 旧版协议：服务器告知 POST 端点
                    postUrl = if (data.startsWith("/")) {
                        val uri = java.net.URI(sseUrl)
                        "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}$data"
                    } else data
                    Log.d(TAG, "收到 MCP HTTP POST 端点: $postUrl")
                }
                "message", "" -> {
                    // JSON-RPC 消息
                    if (data.isNotBlank()) {
                        try {
                            onResponse(JSONObject(data))
                        } catch (e: Exception) {
                            Log.e(TAG, "解析 SSE message 失败: $data", e)
                        }
                    }
                }
                else -> {
                    // 其他事件类型，尝试作为 JSON-RPC 消息处理
                    if (data.isNotBlank()) {
                        try {
                            onResponse(JSONObject(data))
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        override fun close() {
            sseCall?.cancel()
            writer.close()
            pipedInputStream.close()
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
class McpRuntimeManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: McpRuntimeManager? = null

        fun getInstance(context: Context): McpRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: McpRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 需要长连接
        .build()

    // serverId -> 通信通道
    private val channels = ConcurrentHashMap<Long, McpChannel>()

    // serverId -> 待响应请求 (requestId -> Deferred)
    private val pendingRequests = ConcurrentHashMap<Long, ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>>()

    private val requestIdCounter = AtomicLong(1)

    // ── 内置工具服务器 ────────────────────────────────────────────────────
    // 使用负数 ID 避免与用户创建的 MCP server ID 冲突
    private val BUILTIN_SERVER_ID = -1L
    private val BUILTIN_SERVER_NAME = "内置工具"

    private val builtinTools = listOf(
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_current_time",
            description = "获取当前的真实日期和时间（含时区）。当需要知道今天是几号、现在几点、当前星期几，或进行任何与当前时间相关的推理时，请调用此工具。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("timezone", JSONObject().apply {
                        put("type", "string")
                        put("description", "可选。IANA 时区名称，例如 Asia/Shanghai、America/New_York。留空则使用设备本地时区。")
                    })
                })
                put("required", JSONArray())
            }
        )
    )

    private val _serverStates = MutableStateFlow<Map<Long, McpServerState>>(
        mapOf(
            BUILTIN_SERVER_ID to McpServerState(
                server = McpServer(
                    id = BUILTIN_SERVER_ID,
                    name = BUILTIN_SERVER_NAME,
                    runtime = "builtin",
                    command = ""
                ),
                status = McpServerStatus.RUNNING,
                tools = builtinTools
            )
        )
    )
    val serverStates: StateFlow<Map<Long, McpServerState>> = _serverStates.asStateFlow()

    val allTools: StateFlow<List<McpTool>> = serverStates
        .map { states -> states.values.flatMap { it.tools } }
        .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * 将所有可用工具转换为文本描述，用于注入 System Prompt
     */
    fun getAllToolsAsTextDescription(): String {
        val tools = allTools.value
        if (tools.isEmpty()) return "无可用 MCP 工具 (No MCP tools available)"
        
        return tools.joinToString("\n\n") { tool ->
            "工具名: ${tool.name}\n" +
            "来自服务器: ${tool.serverName}\n" +
            "描述: ${tool.description}\n" +
            "参数架构: ${tool.inputSchema.toString(2)}"
        }
    }

    /**
     * 将所有可用工具转换为 OpenAI 兼容的 tools JSON 数组
     */
    fun getAllToolsAsOpenAiFormat(): org.json.JSONArray {
        val array = org.json.JSONArray()
        allTools.value.forEach { tool ->
            val toolObj = org.json.JSONObject()
            toolObj.put("type", "function")
            
            val functionObj = org.json.JSONObject()
            functionObj.put("name", tool.name)
            functionObj.put("description", tool.description)
            functionObj.put("parameters", tool.inputSchema)
            
            toolObj.put("function", functionObj)
            array.put(toolObj)
        }
        return array
    }

    // ── 公开 API ──────────────────────────────────────────────────────────

    fun startServer(server: McpServer) {
        scope.launch {
            updateState(server.id) { McpServerState(server, McpServerStatus.STARTING) }
            try {
                when (server.runtime) {
                    "node" -> startNodeServer(server)
                    "python" -> startPythonServer(server)
                    "remote_http" -> startRemoteHttpServer(server)
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

    /**
     * 批量启动多个 server，对 Node.js 类型的 server 进行合并优化：
     * 先收集所有 Node.js server，再一次性通过多路复用 bridge 启动，
     * 避免第二个 Node.js server 因 hasStarted=true 而失败。
     *
     * 非 Node.js 类型的 server 仍然逐个独立启动。
     */
    fun startServers(servers: List<McpServer>) {
        val nodeServers = servers.filter { it.runtime == "node" }
        val otherServers = servers.filter { it.runtime != "node" }

        // 非 Node.js server 正常逐个启动
        otherServers.forEach { startServer(it) }

        if (nodeServers.isEmpty()) return

        // Node.js server 批量启动
        scope.launch {
            nodeServers.forEach { server ->
                updateState(server.id) { McpServerState(server, McpServerStatus.STARTING) }
            }

            if (!NodeJsBridge.isLoaded) {
                nodeServers.forEach { server ->
                    updateState(server.id) {
                        McpServerState(
                            server, McpServerStatus.ERROR,
                            "Node.js 运行时不可用（libnode.so 未加载）。"
                        )
                    }
                }
                return@launch
            }

            if (NodeJsBridge.hasStarted) {
                // 如果 Node.js 已经启动，说明多路复用 bridge 已经在运行
                // 此时应该逐个调用 startNodeServer，它会通过控制通道热添加这些 server
                nodeServers.forEach { startNodeServer(it) }
                return@launch
            }

            val mcpDir = McpScriptManager.ensureScriptsDeployed(context)

            // 为每个 server 分配端口并验证脚本存在
            val validServers = mutableListOf<Pair<McpServer, Int>>()  // server -> port
            nodeServers.forEach { server ->
                val scriptFile = resolveUserScript(server.command, mcpDir)
                if (scriptFile == null || !scriptFile.exists()) {
                    updateState(server.id) {
                        McpServerState(
                            server, McpServerStatus.ERROR,
                            "找不到脚本文件: ${server.command}\n请将 .js 脚本放入 MCP 工作目录：${mcpDir.absolutePath}"
                        )
                    }
                } else {
                    val port = findFreePort()
                    val extraArgs = parseJsonArray(server.args)
                    pendingNodeServers[server.id] = Triple(port, scriptFile, extraArgs)
                    validServers.add(server to port)
                    Log.d(TAG, "Node.js server [${server.name}] 分配端口: $port")
                }
            }

            if (validServers.isEmpty()) return@launch

            // 启动多路复用 bridge
            startNodeMultiBridge(mcpDir)

            // 并发连接所有 server
            validServers.map { (server, port) ->
                async { connectToNodeServer(server, port) }
            }.awaitAll()
        }
    }

    fun isCommandAvailable(command: String): Boolean {
        // 1. Check system PATH
        val path = System.getenv("PATH") ?: ""
        for (p in path.split(":")) {
            val file = File(p, command)
            if (file.exists() && file.canExecute()) return true
        }
        // 2. Check common Termux path
        val termuxPath = "/data/data/com.termux/files/usr/bin/$command"
        return File(termuxPath).exists()
    }

    fun stopServer(serverId: Long) {
        scope.launch {
            val channel = channels[serverId]
            if (channel != null) {
                // 发送退出指令给 Node.js bridge 或 Python 脚本
                try {
                    val exitMsg = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("method", "exit")
                    }.toString()
                    channel.writer.println(exitMsg)
                    delay(200) // 给一点时间处理
                } catch (e: Exception) {
                    Log.w(TAG, "发送退出指令失败: ${e.message}")
                }
                channel.close()
            }
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

    /**
     * 根据工具名查找对应的服务器 ID。
     * 假设工具名在所有 server 中是唯一的。
     */
    fun findServerIdForTool(toolName: String): Long? {
        return allTools.value.find { it.name == toolName }?.serverId
    }

    suspend fun callTool(serverId: Long, toolName: String, arguments: JSONObject): JSONObject? {
        // 内置工具直接在本地处理，不走任何网络/进程通道
        if (serverId == BUILTIN_SERVER_ID) {
            return handleBuiltinTool(toolName, arguments)
        }
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

    /**
     * 处理内置工具调用，直接在 JVM 层执行，无需外部进程。
     */
    private fun handleBuiltinTool(toolName: String, arguments: JSONObject): JSONObject {
        return when (toolName) {
            "get_current_time" -> {
                val tzId = arguments.optString("timezone").takeIf { it.isNotBlank() }
                val zone = try {
                    if (tzId != null) java.time.ZoneId.of(tzId) else java.time.ZoneId.systemDefault()
                } catch (e: Exception) {
                    java.time.ZoneId.systemDefault()
                }
                val now = ZonedDateTime.now(zone)
                val fullFmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)", Locale.CHINESE)
                val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                val result = buildString {
                    appendLine("当前时间信息：")
                    appendLine("• 本地时间：${now.format(fullFmt)}")
                    appendLine("• 时区：${zone.id} (UTC${now.format(DateTimeFormatter.ofPattern("xxx"))})")
                    appendLine("• ISO 8601：${now.format(isoFmt)}")
                    appendLine("• Unix 时间戳：${now.toEpochSecond()}")
                }
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", result.trim())
                        })
                    })
                }
            }
            else -> JSONObject().apply {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "未知的内置工具: $toolName")
                    })
                })
                put("isError", true)
            }
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

    private suspend fun startRemoteHttpServer(server: McpServer) {
        val url = server.command.trim()
        if (!url.startsWith("http")) {
            updateState(server.id) {
                McpServerState(server, McpServerStatus.ERROR, "无效的 URL: $url")
            }
            return
        }

        updateState(server.id) { McpServerState(server, McpServerStatus.STARTING) }

        // 先尝试 Streamable HTTP（2025-03-26 规范）：直接 POST 发送 initialize
        // 如果服务器返回 405，则回退到旧版 HTTP/SSE（2024-11-05 规范）
        val isStreamable = probeStreamableHttp(url, customHeaders = parseJsonObject(server.env))
        Log.d(TAG, "MCP 服务器 [${server.name}] 协议: ${if (isStreamable) "Streamable HTTP (2025-03-26)" else "HTTP/SSE (2024-11-05)"}")

        val channel = McpChannel.HttpChannel(
            sseUrl = url,
            okHttpClient = okHttpClient,
            scope = scope,
            onResponse = { json -> dispatchResponse(server.id, json) },
            isStreamableHttp = isStreamable,
            customHeaders = parseJsonObject(server.env)
        )

        channels[server.id] = channel
        pendingRequests[server.id] = ConcurrentHashMap()

        if (isStreamable) {
            // Streamable HTTP：无需预先建立 SSE 连接，直接握手
            performHandshake(server)
        } else {
            // 旧版 HTTP/SSE：先建立 SSE 长连接，等待 endpoint 事件
            channel.startSse()

            withTimeoutOrNull(10_000L) {
                while (channel.postUrl == null) {
                    delay(500)
                }
            }

            if (channel.postUrl == null) {
                updateState(server.id) {
                    McpServerState(server, McpServerStatus.ERROR, "连接超时：未能在 10 秒内收到 SSE endpoint 事件")
                }
                return
            }

            performHandshake(server)
        }
    }

    /**
     * 探测服务器是否支持 Streamable HTTP 协议（2025-03-26 规范）。
     *
     * 发送一个最小的 initialize POST 请求：
     * - 如果服务器返回 2xx 或 4xx（非 405）→ 支持 Streamable HTTP
     * - 如果服务器返回 405 Method Not Allowed → 不支持，使用旧版 HTTP/SSE
     * - 如果请求失败（网络错误等）→ 默认使用旧版协议
     */
    private suspend fun probeStreamableHttp(url: String, customHeaders: Map<String, String> = emptyMap()): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val probe = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 0)
                    put("method", "initialize")
                    put("params", JSONObject().apply {
                        put("protocolVersion", "2025-03-26")
                        put("capabilities", JSONObject())
                        put("clientInfo", JSONObject().apply {
                            put("name", "OmniChat")
                            put("version", "1.0.0")
                        })
                    })
                }
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, text/event-stream")
                    .post(probe.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                customHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
                val request = requestBuilder.build()

                // 使用短超时探测，避免阻塞太久
                val probeClient = okHttpClient.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()

                probeClient.newCall(request).execute().use { response ->
                    val isStreamable = response.code != 405
                    Log.d(TAG, "Streamable HTTP 探测 $url → HTTP ${response.code}, isStreamable=$isStreamable")
                    isStreamable
                }
            } catch (e: Exception) {
                Log.w(TAG, "Streamable HTTP 探测失败，回退到旧版 SSE 协议: ${e.message}")
                false
            }
        }
    }

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

    /**
     * 多路复用 Node.js 实例的状态。
     * 由于 nodejs-mobile 只能启动一次，所有 Node.js MCP server 共享同一个实例，
     * 通过 mcp_multi_bridge.js 在不同端口上各自运行。
     */
    private val nodeMultiplexLock = Any()

    /**
     * 待启动的 Node.js server 队列。
     * 当 Node.js 尚未启动时，新的 server 请求会加入此队列，
     * 等待 Node.js 实例启动后统一通过多路复用 bridge 启动。
     *
     * 格式：serverId -> (port, scriptFile, extraArgs)
     */
    private val pendingNodeServers = ConcurrentHashMap<Long, Triple<Int, File, List<String>>>()

    /**
     * 标记多路复用 bridge 是否已经启动（Node.js 实例已运行）。
     * 一旦为 true，新的 Node.js server 需要通过热注册机制加入。
     */
    @Volatile
    private var nodeMultiBridgeStarted = false

    /**
     * Node.js bridge 的控制端口，用于运行时动态添加新的 server。
     */
    @Volatile
    private var nodeControlPort: Int = 0

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

        // 确保内置脚本已部署到 MCP 工作目录
        val mcpDir = McpScriptManager.ensureScriptsDeployed(context)

        // 解析用户脚本路径
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
                    "  • mcp_fetch.js      — HTTP 请求\n" +
                    "  • mcp_pkg_manager.js — 包管理器"
                )
            }
            return
        }

        // 检查并安装依赖
        if (!checkAndInstallDependencies(userScriptFile)) {
            updateState(server.id) {
                McpServerState(
                    server, McpServerStatus.ERROR,
                    "安装依赖失败。请检查 package.json 和网络连接。"
                )
            }
            return
        }

        val port = findFreePort()
        val extraArgs = parseJsonArray(server.args)
        Log.d(TAG, "Node.js MCP server [${server.name}] 分配端口: $port，脚本: ${userScriptFile.absolutePath}")

        if (!NodeJsBridge.hasStarted) {
            // Node.js 尚未启动：将此 server 加入队列，然后启动多路复用 bridge
            pendingNodeServers[server.id] = Triple(port, userScriptFile, extraArgs)
            startNodeMultiBridge(mcpDir)
        } else {
            // Node.js 已在运行：通过控制通道动态添加
            val success = addServerToRunningBridge(port, userScriptFile, extraArgs)
            if (!success) {
                updateState(server.id) {
                    McpServerState(
                        server, McpServerStatus.ERROR,
                        "无法连接到运行中的 Node.js Bridge 控制通道。"
                    )
                }
                return
            }
        }

        // 等待多路复用 bridge 启动并连接
        connectToNodeServer(server, port)
    }

    /**
     * 启动多路复用 Node.js bridge（mcp_multi_bridge.js）。
     * 将所有待启动的 Node.js server 打包成配置，一次性传给 bridge。
     *
     * 此方法是幂等的：如果 bridge 已经启动，不会重复启动。
     */
    private fun startNodeMultiBridge(mcpDir: File) {
        synchronized(nodeMultiplexLock) {
            if (nodeMultiBridgeStarted) return  // 已经启动（或正在启动），无需重复

            nodeControlPort = findFreePort()
            val multiBridgeScript = File(mcpDir, "mcp_multi_bridge.js")
            if (!multiBridgeScript.exists()) {
                Log.e(TAG, "mcp_multi_bridge.js 不存在，请重启应用重新部署")
                return
            }

            // 收集所有待启动的 server 配置
            val serversArray = JSONArray()
            pendingNodeServers.forEach { (_, triple) ->
                val (port, scriptFile, args) = triple
                serversArray.put(JSONObject().apply {
                    put("port", port)
                    put("script", scriptFile.absolutePath)
                    put("args", JSONArray(args))
                })
            }

            val config = JSONObject().apply {
                put("servers", serversArray)
                put("controlPort", nodeControlPort)
            }
            val configBase64 = Base64.encodeToString(
                config.toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            Log.d(TAG, "启动 Node.js 多路复用 bridge，共 ${pendingNodeServers.size} 个 server")
            Log.d(TAG, "配置: $config")

            val nodeThread = Thread({
                val result = NodeJsBridge.startScript(
                    multiBridgeScript.absolutePath,
                    "--config=$configBase64"
                )
                Log.i(TAG, "Node.js 多路复用 bridge 退出，返回码: $result")
                nodeMultiBridgeStarted = false

                // 所有 Node.js server 标记为已停止
                scope.launch {
                    pendingNodeServers.keys.forEach { serverId ->
                        val current = _serverStates.value[serverId]
                        if (current?.status == McpServerStatus.RUNNING ||
                            current?.status == McpServerStatus.STARTING) {
                            updateState(serverId) {
                                it.copy(status = McpServerStatus.STOPPED, tools = emptyList())
                            }
                        }
                    }
                }
            }, "nodejs-multi-bridge")
            nodeThread.isDaemon = true
            nodeThread.start()
            nodeMultiBridgeStarted = true
        }
    }

    /**
     * 通过控制通道向正在运行的 Node.js Bridge 发送指令，动态添加一个新的 MCP server。
     */
    private suspend fun addServerToRunningBridge(port: Int, script: File, args: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (nodeControlPort == 0) return@withContext false
        
        // 由于 Node.js 实例刚启动时，控制通道可能还没来得及 listen，这里增加重试机制
        var lastException: Exception? = null
        for (attempt in 1..5) {
            try {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", nodeControlPort), 2000)
                    socket.soTimeout = 5000
                    
                    val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    val command = JSONObject().apply {
                        put("command", "add_server")
                        put("serverConfig", JSONObject().apply {
                            put("port", port)
                            put("script", script.absolutePath)
                            put("args", JSONArray(args))
                        })
                    }

                    writer.println(command.toString())
                    val response = reader.readLine() ?: throw IOException("控制通道未响应")
                    val json = JSONObject(response)
                    return@withContext json.optString("status") == "ok"
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "第 $attempt 次尝试连接控制通道失败: ${e.message}")
                delay(500)
            }
        }
        
        Log.e(TAG, "所有尝试连接控制通道均失败", lastException)
        false
    }

    /**
     * 等待 Node.js bridge 在指定端口就绪，然后建立 socket 连接并完成 MCP 握手。
     */
    private suspend fun connectToNodeServer(server: McpServer, port: Int) {
        // 等待 bridge 启动（最多 15 秒）
        delay(1500)

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
                s ?: throw IOException("无法连接到 Node.js MCP server (port=$port)")
            }

            val channel = McpChannel.SocketChannel(socket)
            channels[server.id] = channel
            pendingRequests[server.id] = ConcurrentHashMap()

            scope.launch { readChannelOutput(server.id, channel) }
            performHandshake(server)

        } catch (e: Exception) {
            Log.e(TAG, "连接 Node.js MCP server [${server.name}] 失败", e)
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

    /**
     * 检查并安装脚本依赖
     * 如果脚本目录下有 package.json 且 node_modules 不存在，则自动安装依赖
     */
    private suspend fun checkAndInstallDependencies(scriptFile: File): Boolean {
        val scriptDir = scriptFile.parentFile ?: return true
        val packageJson = File(scriptDir, "package.json")
        val nodeModules = File(scriptDir, "node_modules")

        // 如果没有 package.json 或 node_modules 已存在，跳过
        if (!packageJson.exists() || nodeModules.exists()) {
            return true
        }

        Log.i(TAG, "检测到 package.json，正在安装依赖: ${scriptDir.absolutePath}")
        updateState(0) { // 使用临时 serverId
            McpServerState(
                McpServer(id = 0, name = "包管理器", command = ""),
                McpServerStatus.STARTING
            )
        }

        try {
            // 确保包管理器脚本已部署
            val mcpDir = McpScriptManager.getMcpDir(context)
            val pkgManagerScript = File(mcpDir, "mcp_pkg_manager.js")

            if (!pkgManagerScript.exists()) {
                Log.e(TAG, "包管理器脚本不存在")
                return false
            }

            // 启动包管理器进程
            val port = findFreePort()
            val process = ProcessBuilder(
                "node",
                pkgManagerScript.absolutePath,
                "--port=$port"
            )
                .directory(scriptDir)
                .redirectErrorStream(true)
                .start()

            // 等待进程启动
            delay(1000)

            // 连接到包管理器
            val socket = withTimeout(10000L) {
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
                s ?: throw IOException("无法连接到包管理器")
            }

            val channel = McpChannel.SocketChannel(socket)

            // 发送安装请求
            val installRequest = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "npm_install_all")
                    put("arguments", JSONObject().apply {
                        put("directory", scriptDir.absolutePath)
                    })
                })
            }

            channel.writer.println(installRequest.toString())

            // 读取响应
            val responseLine = withTimeout(120000L) { // 2分钟超时
                channel.reader.readLine()
            }

            channel.close()
            process.destroy()

            if (responseLine != null) {
                val response = JSONObject(responseLine)
                val result = response.optJSONObject("result")
                val content = result?.optJSONArray("content")
                val text = content?.optJSONObject(0)?.optString("text")

                if (text != null) {
                    Log.i(TAG, "依赖安装完成: $text")
                    return true
                }
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "安装依赖失败", e)
            return false
        }
    }

    // ── MCP 握手 ──────────────────────────────────────────────────────────

    private suspend fun performHandshake(server: McpServer) {
        try {
            // Streamable HTTP 服务器使用新版协议版本号
            val channel = channels[server.id]
            val protocolVersion = if (channel is McpChannel.HttpChannel && channel.isStreamableHttp) {
                "2025-03-26"
            } else {
                "2024-11-05"
            }

            val initResponse = sendRequest(
                serverId = server.id,
                method = "initialize",
                params = JSONObject().apply {
                    put("protocolVersion", protocolVersion)
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
    ): JSONObject = withContext(Dispatchers.IO) {
        withTimeout(30_000L) {
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

            if (channel is McpChannel.HttpChannel) {
                if (channel.isStreamableHttp) {
                    channel.sendStreamablePost(request.toString())
                } else {
                    channel.sendPost(request.toString())
                }
            } else {
                channel.writer.println(request.toString())
            }

            try {
                deferred.await()
            } finally {
                pendingRequests[serverId]?.remove(id)
            }
        }
    }

    private fun sendNotification(serverId: Long, method: String, params: JSONObject) {
        scope.launch {
            val notification = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
            val channel = channels[serverId]
            if (channel is McpChannel.HttpChannel) {
                if (channel.isStreamableHttp) {
                    channel.sendStreamablePost(notification.toString())
                } else {
                    channel.sendPost(notification.toString())
                }
            } else {
                channel?.writer?.println(notification.toString())
            }
        }
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
