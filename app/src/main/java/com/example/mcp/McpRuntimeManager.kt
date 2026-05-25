package com.example.mcp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.AppDatabase
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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 需要长连接
        .build()

    init {
        Log.i(TAG, "McpRuntimeManager 单例创建")
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
                Log.i(TAG, "[autoStart] 开始部署 MCP 脚本")
                McpScriptManager.ensureScriptsDeployed(context)
                val db = AppDatabase.getDatabase(context)
                val enabled = db.mcpServerDao().getEnabledServers()
                Log.i(TAG, "[autoStart] 数据库中已启用的 MCP server 数量: ${enabled.size}")
                if (enabled.isNotEmpty()) {
                    Log.i(TAG, "[autoStart] 即将启动: ${enabled.joinToString { "${it.name}(${it.runtime})" }}")
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
                McpScriptManager.ensureScriptsDeployed(context)
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
                        Log.i(TAG, "[ensureAutoStarted] 启动未运行的 server: ${notRunning.joinToString { "${it.name}(${it.runtime})" }}")
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
            description = "Get the current real date and time (including timezone). Call this tool whenever you need to know today's date, the current time, the day of the week, or perform any reasoning that depends on the current time.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("timezone", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. IANA timezone name, e.g. Asia/Shanghai or America/New_York. Leave empty to use the device's local timezone.")
                    })
                })
                put("required", JSONArray())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_ui_capabilities",
            description = "Query the capability manifest and current values of the app's UI theme configuration. **Call this tool before calling adjust_ui** to learn all adjustable fields, their semantics, constraints, and current effective values. The response includes: color field list (primary palette / status colors / extended colors), layout parameters (corner radius / spacing), valid value constraints (HEX range), and recommended color combination suggestions.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_ui",
            description = "Adjust the app's complete color scheme and layout. Covers the full Material 3 color palette (primary / secondary / tertiary + their container and on-colors), surface and outline colors, error / success / warning / info / accent colors, as well as corner radius and spacing multiplier.\n\n**Important**: Call get_ui_capabilities first to see the current values and constraints for all adjustable fields. All colors must be in #RRGGBB or #RRGGBBAA format. Fields not provided will retain their current values (incremental update). Changes take effect immediately across the entire app without a restart.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    // —— Primary palette ——
                    put("primaryColor", colorProp("Primary color (buttons / selected / brand color), e.g. #6750A4"))
                    put("onPrimaryColor", colorProp("Text and icon color on primary (high contrast against primaryColor), e.g. #FFFFFF"))
                    put("primaryContainerColor", colorProp("Primary container (e.g. default provider badge background), a lighter variant of the primary color"))
                    put("onPrimaryContainerColor", colorProp("Text color on primary container, high contrast against primaryContainerColor"))
                    put("secondaryColor", colorProp("Secondary color"))
                    put("onSecondaryColor", colorProp("Text color on secondary"))
                    put("secondaryContainerColor", colorProp("Secondary container background"))
                    put("onSecondaryContainerColor", colorProp("Text color on secondary container"))
                    put("tertiaryColor", colorProp("Tertiary color (accent highlight), often used for special badges"))
                    put("onTertiaryColor", colorProp("Text color on tertiary"))
                    // —— Surface & text ——
                    put("backgroundColor", colorProp("Full-page background color"))
                    put("onBackgroundColor", colorProp("Body text color on background"))
                    put("surfaceColor", colorProp("Surface color for cards / dialogs / input fields"))
                    put("onSurfaceColor", colorProp("Primary text color on surface (e.g. headings)"))
                    put("surfaceVariantColor", colorProp("Secondary surface (aggregated tool messages, thinking panel background)"))
                    put("onSurfaceVariantColor", colorProp("Secondary text color on surface variant"))
                    put("outlineColor", colorProp("Primary divider / border color"))
                    put("outlineVariantColor", colorProp("Lighter divider / border color"))
                    // —— Status colors ——
                    put("errorColor", colorProp("Error state color (delete buttons, error messages)"))
                    put("onErrorColor", colorProp("Text color on error"))
                    put("errorContainerColor", colorProp("Error container background (error message bubbles)"))
                    put("onErrorContainerColor", colorProp("Text color inside error container"))
                    put("successColor", colorProp("Success color (running status, green badges); iOS-style #34C759 is the default"))
                    put("warningColor", colorProp("Warning color (starting up, orange hints); #FF9800 is the default"))
                    put("infoColor", colorProp("Info color (visual / blue badges); #007AFF is the default"))
                    put("accentColor", colorProp("Accent color (thinking-process star, orange highlight); #FF9500 is the default"))
                    // —— Sidebar-specific colors ——
                    put("sidebarBackgroundColor", colorProp("Sidebar background color, e.g. #FFFBFE"))
                    put("sidebarOnBackgroundColor", colorProp("Sidebar text and secondary icon color, e.g. #1C1B1F"))
                    put("sidebarActiveColor", colorProp("Sidebar active item background color, e.g. #EADDFF"))
                    put("sidebarOnActiveColor", colorProp("Sidebar active item text and icon color, e.g. #21005D"))
                    // —— Layout ——
                    put("cornerRadiusDp", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Global corner radius in dp, range 0–32. Affects cards, buttons, and other rounded elements.")
                    })
                    put("spacingMultiplier", JSONObject().apply {
                        put("type", "number")
                        put("description", "Global spacing multiplier, range 0.5–2.0. 1.0 is the default; >1 is more spacious, <1 is more compact.")
                    })
                    put("resetToDefault", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Pass true to immediately reset all UI to defaults (other fields are ignored).")
                    })
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "reset_ui_to_default",
            description = "Reset all UI settings — colors, layout, corner radius, etc. — to their system defaults. Call this tool when the user asks to reset the interface, restore the original look, or when you have made a mess of the color scheme.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "save_color_scheme",
            description = "Save the current app color scheme as a named preset for easy restoration later. Up to ${com.example.data.ColorSchemePreset.MAX_PRESETS} presets can be saved; if the limit is reached an error is returned — call delete_color_scheme to free up a slot first. Returns the unique schemeId of the newly saved preset.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "Preset name — short and memorable, e.g. \"Deep Ocean Blue\" or \"Minimal White\". Max 30 characters.")
                    })
                    put("description", JSONObject().apply {
                        put("type", "string")
                        put("description", "Preset summary describing the color style or use case, e.g. \"An immersive dark night theme with deep blue as the primary color\". Max 100 characters.")
                    })
                })
                put("required", JSONArray().apply {
                    put("name")
                    put("description")
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "list_color_schemes",
            description = "List all saved color scheme presets, returning each preset's schemeId, name, description, save time, and a preview of the primary and background colors. Call this tool before applying or deleting a preset to obtain the schemeId.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "apply_color_scheme",
            description = "Apply a saved color scheme preset by its schemeId as the current theme, taking effect immediately. Call list_color_schemes first to obtain the available schemeIds.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("schemeId", JSONObject().apply {
                        put("type", "string")
                        put("description", "The ID of the preset to apply (returned by save_color_scheme or listed by list_color_schemes).")
                    })
                })
                put("required", JSONArray().apply { put("schemeId") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "search_memory",
            description = "Search the long-term memory store for entries related to a keyword. Call this tool when you need to recall a specific user preference, habit, or historical detail that is not present in the current context. The system automatically injects the top 30 highest-confidence memories; all other memories must be retrieved proactively via this tool.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Search keywords; multiple words are supported (space-separated), e.g. \"programming language Kotlin\" or \"dietary preference\". The search performs fuzzy matching against memory content.")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of results to return. Default 10, max 50.")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "delete_color_scheme",
            description = "Delete a saved color scheme preset by its schemeId. Use this when ${com.example.data.ColorSchemePreset.MAX_PRESETS} presets are already saved and you need to free up a slot.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("schemeId", JSONObject().apply {
                        put("type", "string")
                        put("description", "The ID of the preset to delete (listed by list_color_schemes).")
                    })
                })
                put("required", JSONArray().apply { put("schemeId") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_font",
            description = "Adjust the app's font settings, including global font size scale, chat bubble font size scale, and font family. Changes take effect globally and immediately without a restart.\n\n**Adjustable fields:**\n• fontSizeScale — Global UI font size scale (0.75–1.5, default 1.0)\n• chatFontSizeScale — Chat bubble body font size scale (0.75–1.5, default 1.0)\n• fontFamily — Font family (\"default\" / \"serif\" / \"monospace\" / \"cursive\")\n\nFields not provided retain their current values (incremental update).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("fontSizeScale", JSONObject().apply {
                        put("type", "number")
                        put("description", "Global UI font size scale, range 0.75–1.5. 1.0 is the default (100%); 1.2 enlarges by 20%, 0.9 reduces by 10%. Affects all UI text including headings, buttons, and labels.")
                    })
                    put("chatFontSizeScale", JSONObject().apply {
                        put("type", "number")
                        put("description", "Chat bubble body font size scale, range 0.75–1.5. Independent of the global scale — you can increase chat content font size without affecting other UI elements.")
                    })
                    put("fontFamily", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("default")
                            put("serif")
                            put("monospace")
                            put("cursive")
                        })
                        put("description", "Font family. \"default\" = system default (Roboto), \"serif\" = serif font (Noto Serif), \"monospace\" = monospace font (Noto Sans Mono), \"cursive\" = handwriting-style font.")
                    })
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "reset_font_to_default",
            description = "Reset all font settings (font size scale, chat font scale, and font family) to their default values. Call this tool when the user asks to restore the default font or when you have made a mess of the font configuration.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "list_ui_texts",
            description = "View all adjustable UI text strings in the app along with their default Chinese values and current override values. An optional `query` parameter (e.g. \"mcp\" or \"session\") can be provided to fuzzy-filter results by key or default value.\n\n## Line break tip\n\nYou can use `\\n` in `set_ui_texts` values to insert line breaks. For longer translated strings (e.g. French, German), insert `\\n` at semantic break points to enable automatic wrapping and prevent text from being clipped.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Fuzzy-filter by key name or default Chinese text. If not provided, all UI text entries are listed.")
                    })
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "set_ui_texts",
            description = "Override any UI text labels (buttons, headings, hints, placeholders, etc.). Changes take effect globally and immediately without a restart.\n\n## How it works\n\nEvery UI string in the app is registered via a `uiText(key, default)` call. Each key maps to a default Chinese string in the code. When the AI writes a new value for a key using this tool, every location that references that key immediately displays the new text. Keys without an override automatically fall back to their default.\n\n## Usage\n\n• `updates`: A key→value dictionary of strings to set or update. E.g. `{\"topbar.title.chat\": \"Chat\", \"action.confirm\": \"OK\"}`.\n• `delete`: A list of keys whose overrides should be removed (reverting to the default Chinese). E.g. `[\"action.confirm\"]`.\n• `resetAll`: Pass true to remove all overrides at once and restore all default Chinese strings.\n\n## Key naming conventions (not enforced)\n\ntopbar.* / sidebar.* / nav.* / tab.* / chat.* / models.* / memory.* / mcp.* / dialog.* / action.* / status.* / hint.* / icon.*\n\n## Line break support\n\nUse `\\n` in values to insert line breaks. For languages where translations are significantly longer (e.g. French, German), insert `\\n` at appropriate semantic break points to enable automatic wrapping and prevent text from being clipped. Example: `\"tab.settings.memory\": \"Mémoire\\nlongue\"`. The app handles multi-line text display automatically.\n\n## Important\n\nThe key must exactly match the key used in the `uiText()` call in the code for the override to take effect. Call `list_ui_texts` first to see existing overrides. If the user wants to change a string but no existing key is found, ask which area of the UI it appears in (top bar / sidebar / chat / settings / dialog, etc.) and derive the key from the naming conventions above. Common example keys: `topbar.title.chat`, `topbar.title.settings`, `topbar.menu.open`, `topbar.memory.syncing`, `topbar.provider.prefix`, `sidebar.title`, `sidebar.settings`, `sidebar.session.add`, `tab.settings.models` / `tab.settings.mcp` / `tab.settings.memory`, `chat.input.hint`, `chat.send.contentDescription`, `chat.no_provider.warning`, `chat.memory.injected` (contains %d), `chat.current.model` (contains two %s), `models.empty.hint`, `models.default.badge`, `models.add.provider`, `models.set.default.title` / `models.set.default.desc`, `memory.manual.input.title`, `memory.empty.hint`, `mcp.empty.title` / `mcp.empty.desc`, `mcp.examples.title`, `mcp.builtin.title` / `mcp.builtin.status` / `mcp.view.tools`, `dialog.delete.session.title` / `dialog.delete.session.body` (contains %s), `action.confirm` / `action.cancel` / `action.delete` / `action.save` / `action.edit` / `action.add` / `action.close` / `action.reset`.\n\n## Placeholders\n\nSome strings contain format placeholders: `%s` for strings, `%d` for numbers. When rewriting these strings you **must preserve the same number and order of placeholders**, otherwise a runtime crash will occur. The comments returned by `list_ui_texts` indicate which placeholders are present.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("updates", JSONObject().apply {
                        put("type", "object")
                        put("description", "A key→value dictionary of UI text strings to set or update. The key is the name used in the uiText() call in the code; the value is the new text to display.")
                        put("additionalProperties", JSONObject().apply {
                            put("type", "string")
                        })
                    })
                    put("delete", JSONObject().apply {
                        put("type", "array")
                        put("description", "A list of keys whose overrides should be removed (reverting to the default Chinese).")
                        put("items", JSONObject().apply {
                            put("type", "string")
                        })
                    })
                    put("resetAll", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Pass true to remove all overrides at once and restore all default Chinese strings (other fields are ignored).")
                    })
                })
            }
        ),
        // ── 文件系统工具 ──────────────────────────────────────────────────
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_write",
            description = "Write content to a file in the OmniChat/files/ directory on external storage. Creates the file (and any missing parent directories) if it does not exist, or overwrites it if it does. Use this to save notes, generated code, configuration snippets, or any text data the user wants to persist.\n\n**Path rules**: Provide a relative path such as `notes/todo.txt` or `output.json`. Absolute paths and `..` traversal are rejected for safety. The resolved absolute path is returned on success.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative file path inside OmniChat/files/, e.g. \"notes/todo.txt\" or \"data/result.json\". Parent directories are created automatically.")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text content to write. The file is saved as UTF-8.")
                    })
                    put("encoding", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply { put("utf8"); put("base64") })
                        put("description", "Content encoding. \"utf8\" (default) writes the string as-is; \"base64\" decodes the string first (useful for binary files).")
                    })
                })
                put("required", JSONArray().apply { put("path"); put("content") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_read",
            description = "Read the content of a file from the OmniChat/files/ directory. Returns the file content as a UTF-8 string (or Base64 if `encoding` is set to \"base64\"). Optionally limit the output to a specific byte range for large files.\n\n**Path rules**: Relative paths only; `..` traversal is rejected.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative file path inside OmniChat/files/, e.g. \"notes/todo.txt\".")
                    })
                    put("encoding", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply { put("utf8"); put("base64") })
                        put("description", "\"utf8\" (default) returns the content as a plain string; \"base64\" returns Base64-encoded bytes (useful for binary files).")
                    })
                    put("maxBytes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional. Maximum number of bytes to read from the start of the file. Useful for previewing large files. Default: read the entire file (up to 1 MB).")
                    })
                })
                put("required", JSONArray().apply { put("path") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_append",
            description = "Append text to the end of an existing file in OmniChat/files/. If the file does not exist it is created. A newline is automatically inserted before the appended content when the file already has content and does not end with a newline.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative file path inside OmniChat/files/.")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text to append. Saved as UTF-8.")
                    })
                })
                put("required", JSONArray().apply { put("path"); put("content") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_delete",
            description = "Delete a file or an empty directory from OmniChat/files/. To delete a directory and all its contents recursively, set `recursive` to true.\n\n**Safety**: `..` traversal is rejected. Deletion is permanent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative path of the file or directory to delete inside OmniChat/files/.")
                    })
                    put("recursive", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, delete the directory and all its contents recursively. Default false (only deletes empty directories or files).")
                    })
                })
                put("required", JSONArray().apply { put("path") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_list",
            description = "List the contents of a directory inside OmniChat/files/. Returns file names, types (file/directory), sizes, and last-modified timestamps. Pass an empty string or \".\" to list the root OmniChat/files/ directory.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative directory path inside OmniChat/files/. Use \"\" or \".\" for the root. E.g. \"notes\" or \"data/exports\".")
                    })
                    put("showHidden", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Include entries whose names start with a dot. Default false.")
                    })
                })
                put("required", JSONArray())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_search",
            description = "Search for files by name pattern or by text content within OmniChat/files/. Supports glob-style name matching (e.g. `*.txt`, `report_*`) and optional full-text content search. Returns matching file paths with optional context lines around content matches.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("namePattern", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Glob-style filename pattern, e.g. \"*.txt\", \"report_*\", \"*.json\". Matches against the file name only (not the full path). If omitted, all files are candidates.")
                    })
                    put("contentQuery", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Search string to look for inside file contents. Case-insensitive plain-text match. Only text files are searched.")
                    })
                    put("directory", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Relative directory to restrict the search to, e.g. \"notes\". Defaults to the root OmniChat/files/ directory (recursive).")
                    })
                    put("maxResults", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of results to return. Default 20, max 100.")
                    })
                })
                put("required", JSONArray())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_info",
            description = "Get metadata for a file or directory inside OmniChat/files/: absolute path, size in bytes, last-modified timestamp, MIME type guess, whether it is readable/writable, and (for directories) the number of direct children.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative path inside OmniChat/files/, e.g. \"notes/todo.txt\".")
                    })
                })
                put("required", JSONArray().apply { put("path") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_move",
            description = "Move or rename a file or directory inside OmniChat/files/. The destination parent directory is created automatically if it does not exist. Both source and destination must be within OmniChat/files/.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sourcePath", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative source path inside OmniChat/files/, e.g. \"drafts/note.txt\".")
                    })
                    put("destinationPath", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative destination path inside OmniChat/files/, e.g. \"archive/note_2024.txt\".")
                    })
                    put("overwrite", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, overwrite the destination if it already exists. Default false (returns an error if destination exists).")
                    })
                })
                put("required", JSONArray().apply { put("sourcePath"); put("destinationPath") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "ask_user",
            description = "Ask the user a clarifying question when their request is ambiguous or underspecified, or to confirm a decision. You can provide 1 to 5 options for them to choose from, or they can input their custom answer. The function will block and wait for user response.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "The clarifying question or prompt to display to the user.")
                    })
                    put("options", JSONObject().apply {
                        put("type", "array")
                        put("description", "Optional list of 1 to 5 predefined options that the user can choose from.")
                        put("items", JSONObject().apply {
                            put("type", "string")
                        })
                    })
                })
                put("required", JSONArray().apply { put("question") })
            }
        )
    )

    /** Internal helper: build a HEX color schema node */
    private fun colorProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("pattern", "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")
        put("description", "$desc. Format: #RRGGBB or #RRGGBBAA")
    }

    /** Internal helper: build a string schema node */
    private fun strProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", desc)
    }

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
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

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
        Log.i(TAG, "[startServer] name=${server.name}, id=${server.id}, runtime=${server.runtime}, command=${server.command}")
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

        Log.i(TAG, "[startServers] 总计 ${servers.size} 个 server: node=${nodeServers.size}, other=${otherServers.size}")

        // 非 Node.js server 正常逐个启动
        otherServers.forEach { startServer(it) }

        if (nodeServers.isEmpty()) return

        // Node.js server 批量启动
        scope.launch {
            nodeServers.forEach { server ->
                updateState(server.id) { McpServerState(server, McpServerStatus.STARTING) }
            }

            if (!NodeJsBridge.isLoaded) {
                Log.w(TAG, "[startServers] Node.js 运行时不可用 (isLoaded=false)")
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
                Log.i(TAG, "[startServers] Node.js 已启动 (hasStarted=true), 逐个热添加")
                nodeServers.forEach { startNodeServer(it) }
                return@launch
            }

            val mcpDir = McpScriptManager.ensureScriptsDeployed(context)

            // 为每个 server 分配端口并验证脚本存在
            val validServers = mutableListOf<Pair<McpServer, Int>>()  // server -> port
            nodeServers.forEach { server ->
                val scriptFile = resolveUserScript(server.command, mcpDir)
                if (scriptFile == null || !scriptFile.exists()) {
                    Log.w(TAG, "[startServers] Node.js server [${server.name}] 脚本不存在: command=${server.command}, mcpDir=${mcpDir.absolutePath}, resolved=${scriptFile?.absolutePath}")
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

            if (validServers.isEmpty()) {
                Log.w(TAG, "[startServers] 没有有效的 Node.js server 可启动")
                return@launch
            }

            Log.i(TAG, "[startServers] 启动 Node.js 多路复用 bridge, 共 ${validServers.size} 个 server")
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
        Log.i(TAG, "[stopServer] serverId=$serverId")
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
        Log.d(TAG, "[callTool] serverId=$serverId, tool=$toolName")
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
    private suspend fun handleBuiltinTool(toolName: String, arguments: JSONObject): JSONObject {
        return BuiltinToolHandler.handleBuiltinTool(context, toolName, arguments)
    }

    suspend fun refreshTools(serverId: Long) {
        try {
            Log.i(TAG, "[refreshTools] serverId=$serverId")
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
            Log.i(TAG, "[refreshTools] serverId=$serverId, 发现 ${tools.size} 个工具: ${tools.map { it.name }}")
        } catch (e: Exception) {
            Log.e(TAG, "刷新工具列表失败 serverId=$serverId", e)
        }
    }

    // ── Python 启动 ───────────────────────────────────────────────────────

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
        Log.i(TAG, "[startPythonServer] name=${server.name}, command=${server.command}")
        // 确保 Python 运行时已就绪
        val ready = PythonRuntime.ensureReady(context)
        if (!ready) {
            Log.w(TAG, "[startPythonServer] Python 运行时不可用")
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
        Log.i(TAG, "[startNodeServer] name=${server.name}, command=${server.command}, hasStarted=${NodeJsBridge.hasStarted}")
        if (!NodeJsBridge.isLoaded) {
            Log.w(TAG, "[startNodeServer] Node.js 运行时不可用 (isLoaded=false)")
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
            Log.w(TAG, "[startNodeServer] 脚本不存在: command=${server.command}, mcpDir=${mcpDir.absolutePath}, resolved=${userScriptFile?.absolutePath}")
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
            Log.i(TAG, "[startNodeServer] Node.js 未启动, 加入队列并启动多路复用 bridge, port=$port")
            pendingNodeServers[server.id] = Triple(port, userScriptFile, extraArgs)
            startNodeMultiBridge(mcpDir)
        } else {
            // Node.js 已在运行：通过控制通道动态添加
            Log.i(TAG, "[startNodeServer] Node.js 已运行, 通过控制通道热添加, port=$port, controlPort=$nodeControlPort")
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

            Log.i(TAG, "[startNodeMultiBridge] 启动 Node.js 多路复用 bridge, 共 ${pendingNodeServers.size} 个 server, controlPort=$nodeControlPort")
            Log.d(TAG, "[startNodeMultiBridge] 配置: $config")

            val nodeThread = Thread({
                val result = NodeJsBridge.startScript(
                    multiBridgeScript.absolutePath,
                    "--config=$configBase64"
                )
                Log.i(TAG, "[startNodeMultiBridge] Node.js 多路复用 bridge 退出, 返回码: $result")
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
        Log.i(TAG, "[connectToNodeServer] name=${server.name}, port=$port")
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
            Log.i(TAG, "[connectToNodeServer] name=${server.name} 已连接 port=$port, 开始 MCP 握手")
            performHandshake(server)

        } catch (e: Exception) {
            Log.e(TAG, "[connectToNodeServer] name=${server.name} 连接失败 (port=$port)", e)
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
        Log.i(TAG, "[performHandshake] name=${server.name}, id=${server.id}")
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
        Log.d(TAG, "[readChannelOutput] 开始监听 serverId=$serverId")
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
            Log.d(TAG, "[readChannelOutput] serverId=$serverId 输出流关闭: ${e.message}")
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
