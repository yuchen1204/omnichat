package com.omnichat.mcp

import android.content.Context
import android.util.Log
import com.omnichat.data.AppDatabase
import com.omnichat.data.McpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.util.UUID
import com.omnichat.R
import com.omnichat.tool.McpRemoteTool
import com.omnichat.tool.ToolExecutor
import com.omnichat.tool.ToolInitializer
import com.omnichat.tool.ToolRegistry
private const val TAG = "McpRuntimeManager"

// ── 公开数据类 ────────────────────────────────────────────────────────────

data class McpTool(
    val serverId: Long,
    val serverName: String,
    /** Globally unique name exposed to the model and used in tool calls. */
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    /** Original name expected by the remote MCP server. Built-ins use [name]. */
    val remoteName: String = name
)

enum class McpServerStatus { STOPPED, STARTING, RUNNING, ERROR }

data class McpServerState(
    val server: McpServer,
    val status: McpServerStatus = McpServerStatus.STOPPED,
    val errorMessage: String? = null,
    val tools: List<McpTool> = emptyList()
)

// ── 内部通信通道 ──────────────────────────────────────────────────────────

/**
 * 代表一个与远程 MCP server 的 HTTP 通信通道。
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
private class HttpChannel(
    val sseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val onResponse: (JSONObject) -> Unit,
    val isStreamableHttp: Boolean = false,
    private val customHeaders: Map<String, String> = emptyMap()
) {
    private val pipedInputStream = PipedInputStream()
    private val pipedOutputStream = PipedOutputStream(pipedInputStream)
    val reader = BufferedReader(InputStreamReader(pipedInputStream))
    val writer = PrintWriter(BufferedWriter(OutputStreamWriter(pipedOutputStream)), true)

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

    fun close() {
        sseCall?.cancel()
        writer.close()
        pipedInputStream.close()
    }
}

// ── McpRuntimeManager ─────────────────────────────────────────────────────

/**
 * MCP 运行时管理器。
 *
 * 通过 HTTP/HTTPS 连接远程 MCP server，支持两种协议：
 *
 * 1. 旧版 HTTP/SSE（MCP 规范 2024-11-05）
 * 2. 新版 Streamable HTTP（MCP 规范 2025-03-26）
 */
class McpRuntimeManager private constructor(private val context: Context) {

    /** Expose application context for string resource access */
    fun getContext(): Context = context

    companion object {
        @Volatile
        private var INSTANCE: McpRuntimeManager? = null

        /**
         * Built-in tool-to-group mapping for UI display filtering.
         *
         * ToolRegistry owns tool metadata, so derive this map rather than
         * maintaining another manual list that can omit newly registered tools.
         */
        val builtinToolGroups: Map<String, String>
            get() = ToolRegistry.getAll()
                .filterNot { it is McpRemoteTool }
                .associate { tool -> tool.name to tool.group }

        fun getInstance(context: Context): McpRuntimeManager {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: McpRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
            // 在 getInstance 调用后触发自动启动（幂等：autoStartTriggered 保证只执行一次）。
            // 此时单例已完全构造完成，所有 private val 都已初始化，不会出现字段访问顺序问题。
            instance.triggerAutoStart()
            return instance
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val BUILTIN_SERVER_ID = -1L
    private lateinit var BUILTIN_SERVER_NAME: String

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 需要长连接
        .build()

    init {
        BUILTIN_SERVER_NAME = context.getString(R.string.builtin_server_name)
        if (!ToolInitializer.isInitialized()) {
            ToolInitializer.initialize(context)
        }
        Log.i(TAG, "McpRuntimeManager created")
    }

    /**
     * 触发已启用 MCP server 的自动启动。幂等：多次调用只会启动一次。
     * 由 [getInstance] 在单例创建后调用。
     */
    @Volatile
    private var autoStartTriggered = false

    /** autoStart 协程的 Job，供 [waitForAutoStartComplete] 等待 */
    @Volatile
    private var autoStartJob: kotlinx.coroutines.Job? = null

    /** 记录 autoStart 是否成功完成（至少有一个 server 被尝试启动） */
    @Volatile
    private var autoStartCompleted = false

    private fun triggerAutoStart() {
        if (autoStartTriggered) {
            Log.d(TAG, "[autoStart] 已触发过，跳过 (autoStartCompleted=$autoStartCompleted)")
            return
        }
        autoStartTriggered = true
        Log.i(TAG, "[autoStart] 首次触发自动启动")
        autoStartJob = scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val enabled = db.mcpServerDao().getEnabledServers()

                Log.i(TAG, "[autoStart] 数据库中已启用的 MCP server 数量: ${enabled.size}")
                if (enabled.isNotEmpty()) {
                    Log.i(TAG, "[autoStart] 即将启动: ${enabled.joinToString { it.name }}")
                    startServers(enabled)
                    autoStartCompleted = true
                    Log.i(TAG, "[autoStart] startServers 调用完成, autoStartCompleted=true")
                } else {
                    autoStartCompleted = true
                    Log.i(TAG, "[autoStart] 无已启用的 server, autoStartCompleted=true")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[autoStart] 自动启动 MCP server 失败", e)
            }
        }
    }

    /**
     * 确保已启用的 MCP server 已启动。
     * 如果之前的 autoStart 因外部存储权限未就绪等原因失败，此方法会重试。
     * 应在存储权限获得后调用（如 Activity.onResume）。
     */
    fun ensureAutoStarted() {
        Log.d(TAG, "[ensureAutoStarted] 调用: autoStartCompleted=$autoStartCompleted, autoStartJob.isActive=${autoStartJob?.isActive}")
        if (autoStartCompleted) {
            Log.d(TAG, "[ensureAutoStarted] 已成功完成，跳过")
            return
        }
        if (autoStartJob?.isActive == true) {
            Log.d(TAG, "[ensureAutoStarted] autoStart 协程仍在运行，跳过")
            return
        }
        scope.launch {
            try {
                Log.i(TAG, "[ensureAutoStarted] 重试自动启动")
                val db = AppDatabase.getDatabase(context)
                val enabled = db.mcpServerDao().getEnabledServers()

                Log.i(TAG, "[ensureAutoStarted] 数据库中已启用的 MCP server 数量: ${enabled.size}")
                if (enabled.isNotEmpty()) {
                    val notRunning = enabled.filter { server ->
                        val state = _serverStates.value[server.id]
                        val needStart = state == null || state.status == McpServerStatus.STOPPED || state.status == McpServerStatus.ERROR
                        Log.d(TAG, "[ensureAutoStarted] server [${server.name}] id=${server.id} state=${state?.status}, needStart=$needStart")
                        needStart
                    }
                    if (notRunning.isNotEmpty()) {
                        Log.i(TAG, "[ensureAutoStarted] 启动未运行的 server: ${notRunning.joinToString { it.name }}")
                        startServers(notRunning)
                    } else {
                        Log.i(TAG, "[ensureAutoStarted] 所有已启用 server 均在运行中，无需重启")
                    }
                }
                autoStartCompleted = true
                Log.i(TAG, "[ensureAutoStarted] 完成, autoStartCompleted=true")
            } catch (e: Exception) {
                Log.e(TAG, "[ensureAutoStarted] 重试自动启动失败", e)
            }
        }
    }

    // serverId -> 通信通道
    private val channels = ConcurrentHashMap<Long, HttpChannel>()

    // serverId -> 待响应请求 (requestId -> Deferred)
    private val pendingRequests = ConcurrentHashMap<Long, ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>>()

    // serverId -> ToolRegistry names for adapters representing that server
    private val remoteToolNamesByServer = ConcurrentHashMap<Long, Set<String>>()

    private val requestIdCounter = AtomicLong(1)

    // ── 内置工具服务器 ────────────────────────────────────────────────────
    // 使用负数 ID 避免与用户创建的 MCP server ID 冲突

    /**
     * Built-in metadata is derived from ToolRegistry. This intentionally avoids
     * an initialization-time snapshot: registering a new tool updates the model
     * catalog without creating a second schema source of truth.
     */
    private fun currentBuiltinTools(): List<McpTool> = ToolRegistry.getAll()
        .filterNot { it is McpRemoteTool }
        .sortedBy { it.name }
        .map { tool ->
            McpTool(
                serverId = BUILTIN_SERVER_ID,
                serverName = BUILTIN_SERVER_NAME,
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }

    private val _serverStates = MutableStateFlow<Map<Long, McpServerState>>(
        mapOf(
            BUILTIN_SERVER_ID to McpServerState(
                server = McpServer(
                    id = BUILTIN_SERVER_ID,
                    name = BUILTIN_SERVER_NAME,
                    command = ""
                ),
                status = McpServerStatus.RUNNING,
                tools = currentBuiltinTools()
            )
        )
    )
    val serverStates: StateFlow<Map<Long, McpServerState>> = _serverStates.asStateFlow()

    private val enabledBuiltinTools: StateFlow<List<McpTool>> = combine(
        AppDatabase.getDatabase(context).uiSettingsDao().getSettingsFlow(),
        ToolRegistry.changes
    ) { settings, _ ->
        val enabledGroups = settings?.enabledMcpGroups?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: setOf("core", "ui_appearance", "efficiency", "memory", "subagent")
        currentBuiltinTools().filter { tool ->
            val group = builtinToolGroups[tool.name] ?: "core"
            group == "core" || group in enabledGroups
        }
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        currentBuiltinTools().filter {
            builtinToolGroups[it.name] == "core" || builtinToolGroups[it.name] in listOf("ui_appearance", "efficiency", "memory")
        }
    )

    private val _toolsVersion = AtomicLong(0)
    val toolsVersion: Long get() = _toolsVersion.get()

    val allTools: StateFlow<List<McpTool>> = combine(serverStates, enabledBuiltinTools) { states, builtins ->
        val otherTools = states.filter { it.key != BUILTIN_SERVER_ID }.values.flatMap { it.tools }
        _toolsVersion.incrementAndGet()
        builtins + otherTools
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 等待 MCP server 就绪，分两阶段：
     * 1. 等待 autoStart 协程完成（确保 startServers 已被调用，server 状态已变为 STARTING）
     * 2. 等待所有 STARTING 状态的 server 完成启动（变为 RUNNING / ERROR / STOPPED）
     *
     * @param timeoutMillis 总最大等待时间（毫秒），默认 15 秒。
     */
    suspend fun waitForStartingServersToFinish(timeoutMillis: Long = 15_000L) {
        val job = autoStartJob
        val isJobActive = job?.isActive ?: false
        val anyStarting = _serverStates.value.values.any { it.status == McpServerStatus.STARTING }
        if (!isJobActive && !anyStarting) {
            return
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        // 阶段 1：等待 autoStart 协程本身完成（startServers 被调用）
        if (job != null && job.isActive) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                try {
                    withTimeout(remaining) { job.join() }
                } catch (_: Exception) { /* 超时或取消，继续 */ }
            }
        }
        // 短暂让出，让 startServers 内部的 scope.launch 协程有机会运行并将状态改为 STARTING
        if (_serverStates.value.values.any { it.status == McpServerStatus.STARTING }) {
            kotlinx.coroutines.delay(300)
        }
        // 阶段 2：等待所有 STARTING 状态消失
        while (System.currentTimeMillis() < deadline) {
            val stillStarting = _serverStates.value.values.any { it.status == McpServerStatus.STARTING }
            if (!stillStarting) return
            kotlinx.coroutines.delay(200)
        }
        Log.w(TAG, "[waitForReady] 超时，部分 server 可能仍在启动中")
    }

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

    /**
     * 批量启动多个 server，逐个调用 [startServer]。
     */
    fun startServers(servers: List<McpServer>) {
        servers.forEach { startServer(it) }
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
        Log.i(TAG, "[stopServer] serverId=$serverId")
        scope.launch {
            unregisterRemoteTools(serverId)
            val channel = channels[serverId]
            channel?.close()
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
    }

    /**
     * 根据工具名查找对应的服务器 ID。
     * 假设工具名在所有 server 中是唯一的。
     */
    fun findServerIdForTool(toolName: String): Long? {
        return allTools.value.firstOrNull { it.name == toolName }?.serverId
    }

    suspend fun callTool(serverId: Long, toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject? {
        Log.d(TAG, "[callTool] serverId=$serverId, tool=$toolName")

        return if (serverId == BUILTIN_SERVER_ID) {
            try {
                handleBuiltinTool(toolName, arguments, sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Builtin tool $toolName failed unexpectedly", t)
                ToolExecutor.errorResponse("Error running builtin tool $toolName: ${t.localizedMessage}")
            }
        } else {
            val remoteTool = findRemoteTool(serverId, toolName)
                ?: return ToolExecutor.errorResponse("Unknown remote tool: $toolName")
            ToolExecutor.executeTool(context, remoteTool, arguments, sessionId)
        }
    }

    /** Executes a built-in tool through ToolRegistry and ToolExecutor only. */
    private suspend fun handleBuiltinTool(toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject {
        Log.d(TAG, "[handleBuiltinTool] ToolExecutor: $toolName")
        return ToolExecutor.execute(context, toolName, arguments, sessionId)
    }

    /**
     * 调用远程 MCP 工具（供 McpRemoteTool 使用）。
     */
    suspend fun callRemoteTool(serverId: Long, toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject? {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Remote tool $toolName failed", e)
            null
        }
    }

    private fun remoteRegistryName(serverId: Long, remoteName: String): String =
        "mcp_remote:$serverId:$remoteName"

    /**
     * MCP permits multiple servers to expose the same remote tool name. OpenAI-style
     * function calls do not carry a server ID, so expose a stable, valid, unique name.
     */
    private fun remoteModelToolName(serverId: Long, remoteName: String): String {
        val normalized = remoteName.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "tool" }
        val hash = UUID.nameUUIDFromBytes(remoteName.toByteArray(Charsets.UTF_8))
            .toString()
            .substring(0, 12)
        val prefix = "mcp_${serverId}_"
        val maxStemLength = (64 - prefix.length - hash.length - 1).coerceAtLeast(1)
        return "$prefix${normalized.take(maxStemLength)}_$hash"
    }

    private fun unregisterRemoteTools(serverId: Long) {
        val trackedNames = remoteToolNamesByServer.remove(serverId).orEmpty()
        trackedNames.forEach(ToolRegistry::unregister)

        // Also remove adapters left by an interrupted refresh before bookkeeping completed.
        ToolRegistry.getAll()
            .filterIsInstance<McpRemoteTool>()
            .filter { it.serverId == serverId }
            .forEach { ToolRegistry.unregister(it.name) }
    }

    private fun findRemoteTool(serverId: Long, modelToolName: String): McpRemoteTool? {
        val remoteName = _serverStates.value[serverId]
            ?.tools
            ?.firstOrNull { it.name == modelToolName }
            ?.remoteName
            ?: return null
        return ToolRegistry.get(remoteRegistryName(serverId, remoteName)) as? McpRemoteTool
    }

    private fun registerRemoteTools(server: McpServer, tools: List<McpTool>) {
        unregisterRemoteTools(server.id)
        val adapters = tools.map { tool ->
            McpRemoteTool(
                name = remoteRegistryName(server.id, tool.remoteName),
                description = tool.description,
                inputSchema = tool.inputSchema,
                serverId = server.id,
                serverName = server.name,
                remoteName = tool.remoteName,
                runtimeManager = this
            )
        }
        adapters.forEach(ToolRegistry::register)
        remoteToolNamesByServer[server.id] = adapters.mapTo(linkedSetOf()) { it.name }
    }

    suspend fun refreshTools(serverId: Long) {
        try {
            Log.i(TAG, "[refreshTools] serverId=$serverId")
            val response = sendRequest(serverId, "tools/list", JSONObject())
            val toolsArray = response.optJSONObject("result")?.optJSONArray("tools") ?: return
            val server = _serverStates.value[serverId]?.server ?: return
            val tools = (0 until toolsArray.length()).mapNotNull { i ->
                val tool = toolsArray.optJSONObject(i) ?: return@mapNotNull null
                val name = tool.optString("name").trim()
                if (name.isBlank()) return@mapNotNull null
                McpTool(
                    serverId = serverId,
                    serverName = server.name,
                    name = remoteModelToolName(serverId, name),
                    remoteName = name,
                    description = tool.optString("description"),
                    inputSchema = tool.optJSONObject("inputSchema") ?: JSONObject()
                )
            }.distinctBy { it.name }
            registerRemoteTools(server, tools)
            updateState(serverId) { it.copy(tools = tools) }
            Log.i(TAG, "[refreshTools] serverId=$serverId, found ${tools.size} tools: ${tools.map { it.name }}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh tools for serverId=$serverId", e)
        }
    }

    // ── Remote HTTP 启动 ──────────────────────────────────────────────────

    private suspend fun startRemoteHttpServer(server: McpServer) {
        Log.i(TAG, "[startRemoteHttpServer] name=${server.name}, url=${server.command}")
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

        val channel = HttpChannel(
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

    // ── MCP 握手 ──────────────────────────────────────────────────────────

    private suspend fun performHandshake(server: McpServer) {
        Log.i(TAG, "[performHandshake] name=${server.name}, id=${server.id}")
        try {
            // Streamable HTTP 服务器使用新版协议版本号
            val channel = channels[server.id]
            val protocolVersion = if (channel?.isStreamableHttp == true) {
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
                Log.e(TAG, "[performHandshake] name=${server.name} 初始化失败: $errMsg")
                updateState(server.id) { McpServerState(server, McpServerStatus.ERROR, errMsg) }
                return
            }

            sendNotification(server.id, "notifications/initialized", JSONObject())
            updateState(server.id) { McpServerState(server, McpServerStatus.RUNNING) }
            Log.i(TAG, "[performHandshake] name=${server.name} 握手成功, 状态=RUNNING")
            refreshTools(server.id)

        } catch (e: Exception) {
            Log.e(TAG, "[performHandshake] name=${server.name} 握手超时或失败", e)
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

            if (channel.isStreamableHttp) {
                channel.sendStreamablePost(request.toString())
            } else {
                channel.sendPost(request.toString())
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
            if (channel != null) {
                if (channel.isStreamableHttp) {
                    channel.sendStreamablePost(notification.toString())
                } else {
                    channel.sendPost(notification.toString())
                }
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

    private fun parseJsonObject(json: String): Map<String, String> = try {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.optString(it) }
    } catch (e: Exception) { emptyMap() }
}
