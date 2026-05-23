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

    private fun triggerAutoStart() {
        if (autoStartTriggered) return
        autoStartTriggered = true
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "[autoStart] 自动启动 MCP server 失败", e)
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
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_ui_capabilities",
            description = "查询当前应用 UI 主题配置的能力清单与当前值。**在调用 adjust_ui 之前请先调用此工具**，了解所有可调整的字段、各字段的语义、约束和当前生效值。返回内容包含：颜色字段列表（主调色板/状态色/扩展色）、布局参数（圆角/间距）、有效值约束（HEX 范围）、以及推荐的色彩组合建议。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_ui",
            description = "调整应用的整套配色和布局。覆盖完整的 Material 3 调色板（主色/次色/第三色 + 各自的容器与文字色）、表面与轮廓、错误/成功/警告/信息/强调色，以及圆角和间距倍数。\n\n**重要**：调用前请先用 get_ui_capabilities 查看所有可调整字段的当前值与约束。所有颜色必须是 #RRGGBB 或 #RRGGBBAA 格式。未提供的字段会保持当前值不变（增量更新）。设置后整个 App 立即生效，无需重启。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    // —— 主调色板 ——
                    put("primaryColor", colorProp("主色调（按钮/选中/品牌色），例如 #6750A4"))
                    put("onPrimaryColor", colorProp("主色上的文字与图标颜色（与 primaryColor 形成高对比度），例如 #FFFFFF"))
                    put("primaryContainerColor", colorProp("主色容器（如默认提供商徽章背景），主色的浅色变体"))
                    put("onPrimaryContainerColor", colorProp("主色容器上的文字颜色，与 primaryContainerColor 高对比"))
                    put("secondaryColor", colorProp("次要色调"))
                    put("onSecondaryColor", colorProp("次色上的文字颜色"))
                    put("secondaryContainerColor", colorProp("次色容器背景"))
                    put("onSecondaryContainerColor", colorProp("次色容器上的文字颜色"))
                    put("tertiaryColor", colorProp("第三色（强调点缀），常用于特殊徽章"))
                    put("onTertiaryColor", colorProp("第三色上的文字颜色"))
                    // —— 表面与文字 ——
                    put("backgroundColor", colorProp("整页背景色"))
                    put("onBackgroundColor", colorProp("背景上正文文字色"))
                    put("surfaceColor", colorProp("卡片/对话框/输入框等表面颜色"))
                    put("onSurfaceColor", colorProp("表面上的主要文字颜色（如标题）"))
                    put("surfaceVariantColor", colorProp("次级表面（聚合工具消息、思考面板背景）"))
                    put("onSurfaceVariantColor", colorProp("次级表面上的辅助文字色"))
                    put("outlineColor", colorProp("分隔线 / 边框主色"))
                    put("outlineVariantColor", colorProp("更浅的分隔线 / 边框色"))
                    // —— 状态色 ——
                    put("errorColor", colorProp("错误状态色（删除按钮、错误提示）"))
                    put("onErrorColor", colorProp("错误色上的文字色"))
                    put("errorContainerColor", colorProp("错误容器背景（错误提示气泡）"))
                    put("onErrorContainerColor", colorProp("错误容器内的文字色"))
                    put("successColor", colorProp("成功色（运行中状态、绿色徽章），iOS 风格 #34C759 是默认"))
                    put("warningColor", colorProp("警告色（启动中、橙色提示），#FF9800 是默认"))
                    put("infoColor", colorProp("信息色（视觉/蓝色徽章），#007AFF 是默认"))
                    put("accentColor", colorProp("强调色（思考过程的星标、橙色点缀），#FF9500 是默认"))
                    // —— 侧边栏专属颜色 ——
                    put("sidebarBackgroundColor", colorProp("侧边栏背景色，例如 #FFFBFE"))
                    put("sidebarOnBackgroundColor", colorProp("侧边栏文字与辅助图标颜色，例如 #1C1B1F"))
                    put("sidebarActiveColor", colorProp("侧边栏激活项背景色，例如 #EADDFF"))
                    put("sidebarOnActiveColor", colorProp("侧边栏激活项文字与图标颜色，例如 #21005D"))
                    // —— 布局 ──
                    put("cornerRadiusDp", JSONObject().apply {
                        put("type", "integer")
                        put("description", "全局圆角大小（dp），范围 0-32。影响卡片、按钮等的圆角")
                    })
                    put("spacingMultiplier", JSONObject().apply {
                        put("type", "number")
                        put("description", "全局间距倍数，范围 0.5-2.0。1.0 为默认，>1 更宽松，<1 更紧凑")
                    })
                    put("resetToDefault", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "传 true 立刻重置全部 UI 为默认（其他字段被忽略）")
                    })
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "reset_ui_to_default",
            description = "将应用的配色、布局、圆角等所有 UI 设置恢复为系统默认状态。当用户要求重置界面、恢复原样或你弄乱了配色时，请调用此工具。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "save_color_scheme",
            description = "将当前应用配色方案保存为一个命名预设，方便日后一键恢复。最多可保存 ${com.example.data.ColorSchemePreset.MAX_PRESETS} 个方案；超出时会返回错误，需先调用 delete_color_scheme 删除旧方案。保存成功后返回新方案的唯一 schemeId。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "方案名称，简短易记，例如「深海蓝」「极简白」。不超过 30 个字符。")
                    })
                    put("description", JSONObject().apply {
                        put("type", "string")
                        put("description", "方案概述，描述配色风格或适用场景，例如「以深蓝为主色的沉浸式夜间主题」。不超过 100 个字符。")
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
            description = "列出所有已保存的配色方案预设，返回每个方案的 schemeId、名称、概述、保存时间，以及主色/背景色预览。在应用或删除方案前请先调用此工具获取 schemeId。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "apply_color_scheme",
            description = "将指定 schemeId 的已保存配色方案应用为当前主题，立即生效。调用前请先用 list_color_schemes 获取可用的 schemeId。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("schemeId", JSONObject().apply {
                        put("type", "string")
                        put("description", "要应用的方案 ID（由 save_color_scheme 返回或 list_color_schemes 列出）")
                    })
                })
                put("required", JSONArray().apply { put("schemeId") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "search_memory",
            description = "在长效记忆库中搜索与关键词相关的记忆条目。当你需要回忆用户的某个具体偏好、习惯或历史信息，但当前上下文中没有相关内容时，请调用此工具。系统会自动注入置信度最高的前 30 条记忆，其余记忆需通过此工具主动检索。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索关键词，支持多个词（空格分隔），例如「编程语言 Kotlin」或「饮食偏好」。搜索会对记忆内容做模糊匹配。")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "最多返回的结果数量，默认 10，最大 50。")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "delete_color_scheme",
            description = "删除指定 schemeId 的已保存配色方案预设。当已保存 ${com.example.data.ColorSchemePreset.MAX_PRESETS} 个方案需要腾出空间时使用。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("schemeId", JSONObject().apply {
                        put("type", "string")
                        put("description", "要删除的方案 ID（由 list_color_schemes 列出）")
                    })
                })
                put("required", JSONArray().apply { put("schemeId") })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_font",
            description = "调整应用的字体设置，包括全局字体大小缩放、聊天气泡字体大小缩放和字体族。设置后立即全局生效，无需重启。\n\n**可调字段：**\n• fontSizeScale — 全局 UI 字体大小缩放（0.75–1.5，默认 1.0）\n• chatFontSizeScale — 聊天气泡正文字体大小缩放（0.75–1.5，默认 1.0）\n• fontFamily — 字体族（\"default\" / \"serif\" / \"monospace\" / \"cursive\"）\n\n未提供的字段保持当前值不变（增量更新）。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("fontSizeScale", JSONObject().apply {
                        put("type", "number")
                        put("description", "全局 UI 字体大小缩放比例，范围 0.75–1.5。1.0 为默认（100%），1.2 放大 20%，0.9 缩小 10%。影响标题、按钮、标签等所有 UI 文字。")
                    })
                    put("chatFontSizeScale", JSONObject().apply {
                        put("type", "number")
                        put("description", "聊天气泡正文字体大小缩放比例，范围 0.75–1.5。独立于全局缩放，可单独调大聊天内容字号而不影响其他 UI。")
                    })
                    put("fontFamily", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("default")
                            put("serif")
                            put("monospace")
                            put("cursive")
                        })
                        put("description", "字体族。\"default\"=系统默认（Roboto），\"serif\"=衬线字体（Noto Serif），\"monospace\"=等宽字体（Noto Sans Mono），\"cursive\"=手写风格字体。")
                    })
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "reset_font_to_default",
            description = "将应用的所有字体设置（字体大小缩放、聊天字体缩放、字体族）恢复为默认值。当用户要求恢复默认字体或你调乱了字体时，请调用此工具。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_ui_strings",
            description = "查询当前应用所有 UI 文字标签的当前值与默认值。**在调用 adjust_ui_strings 之前请先调用此工具**，了解所有可修改的字段名、当前生效值和默认中文值。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_ui_strings",
            description = "修改应用的 UI 文字标签（按钮文字、标题、提示语等）。调用后立即全局生效，无需重启。\n\n**重要**：调用前请先用 get_ui_strings 查看所有可修改字段的当前值。只需传入想修改的字段，未传字段保持当前值不变（增量更新）。\n\n含 %s / %d 的字段为格式化字符串，请保留占位符。\n\n传 resetToDefault=true 可一键恢复全部默认中文。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("resetToDefault", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "传 true 立刻重置全部文字标签为默认中文（其他字段被忽略）")
                    })
                    // 顶部导航栏
                    put("topbar_title_chat", strProp("聊天页顶部标题，默认「会话」"))
                    put("topbar_title_settings", strProp("设置页顶部标题，默认「设置」"))
                    put("topbar_provider_prefix", strProp("顶部栏提供商名称前缀，默认「提供商: 」"))
                    put("topbar_memory_syncing", strProp("记忆同步中状态文字，默认「记忆同步中」"))
                    // 导航
                    put("nav_chat", strProp("底部/侧边导航聊天项文字，默认「会话」"))
                    put("nav_settings", strProp("底部/侧边导航设置项文字，默认「设置」"))
                    // 设置子 Tab
                    put("settings_tab_models", strProp("设置页第一个子 Tab 标签，默认「模型配置」"))
                    put("settings_tab_mcp", strProp("设置页第二个子 Tab 标签，默认「MCP工具」"))
                    put("settings_tab_memory", strProp("设置页第三个子 Tab 标签，默认「长效记忆」"))
                    // 侧边栏
                    put("sidebar_title", strProp("侧边栏顶部标题，默认「对话列表」"))
                    put("sidebar_settings", strProp("侧边栏底部设置按钮文字，默认「设置」"))
                    put("sidebar_delete_confirm", strProp("删除会话确认对话框文字，含 %s 占位符（会话标题），默认「确定要删除「%s」吗？...」"))
                    // 聊天界面
                    put("chat_no_provider_warning", strProp("未配置提供商时的警告文字"))
                    put("chat_memory_injected", strProp("记忆注入提示，含 %d 占位符（记忆条数），默认「已动态融合共 %d 条长效学习偏好记忆」"))
                    put("chat_current_model", strProp("当前模型信息，含两个 %s 占位符（模型ID、提供商名），默认「当前模型: %s  ·  %s」"))
                    put("chat_input_hint", strProp("聊天输入框占位提示，默认「输入消息…」"))
                    put("chat_send", strProp("发送按钮文字，默认「发送」"))
                    put("chat_stop", strProp("停止生成按钮文字，默认「停止」"))
                    put("chat_new_session", strProp("新建会话按钮文字，默认「新建会话」"))
                    put("chat_thinking", strProp("AI 思考中状态文字，默认「思考中」"))
                    put("chat_tool_calling", strProp("调用工具状态文字，默认「调用工具」"))
                    // 模型配置页
                    put("models_empty_hint", strProp("无提供商时的提示文字"))
                    put("models_default_badge", strProp("默认提供商徽章文字，默认「默认提供商」"))
                    put("models_set_default", strProp("设为默认配置菜单项，默认「设为默认配置」"))
                    put("models_set_default_desc", strProp("设为默认配置描述，默认「将此 API 提供商作为全局使用」"))
                    put("models_custom_headers", strProp("自定义请求头标签，默认「自定义请求头: 」"))
                    put("models_no_headers", strProp("无自定义请求头提示，默认「暂无自定义请求头」"))
                    put("models_add_provider", strProp("新增提供商按钮文字，默认「新增提供商」"))
                    put("models_fetch_models", strProp("拉取模型区域标题，默认「自动拉取并解析可用模型:」"))
                    put("models_fetch_error_prefix", strProp("拉取错误前缀，默认「拉取错误: 」"))
                    put("models_no_saved_models", strProp("无已保存模型提示，默认「该 Provider 暂无已保存的模型列表」"))
                    put("models_fetch_first", strProp("引导先拉取模型的提示，默认「请先在「模型配置」中拉取模型」"))
                    // MCP 配置页
                    put("mcp_empty_hint", strProp("无 MCP 服务时的标题，默认「暂无 MCP 服务」"))
                    put("mcp_empty_desc", strProp("无 MCP 服务时的描述"))
                    put("mcp_examples_title", strProp("常用示例区域标题，默认「常用示例」"))
                    put("mcp_builtin_title", strProp("内置工具卡片标题，默认「内置工具」"))
                    put("mcp_builtin_status", strProp("内置工具运行状态文字，默认「运行中」"))
                    put("mcp_view_tools", strProp("查看工具按钮文字，默认「查看工具」"))
                    put("mcp_remote_http_support", strProp("远程 HTTP 支持标签，默认「支持远程 HTTP MCP」"))
                    put("mcp_import_title", strProp("导入配置对话框标题，默认「导入 MCP 配置 (JSON)」"))
                    put("mcp_import_desc", strProp("导入配置对话框描述"))
                    put("mcp_runtime_label", strProp("运行时选择标签，默认「运行时」"))
                    put("mcp_auto_start", strProp("自动启动开关文字，默认「启动时自动运行」"))
                    // 长效记忆页
                    put("memory_manual_input", strProp("手动录入记忆区域标题"))
                    put("memory_empty_hint", strProp("无记忆时的提示文字"))
                    // 通用操作
                    put("action_confirm", strProp("确认按钮，默认「确定」"))
                    put("action_cancel", strProp("取消按钮，默认「取消」"))
                    put("action_delete", strProp("删除按钮，默认「删除」"))
                    put("action_edit", strProp("编辑按钮，默认「编辑」"))
                    put("action_save", strProp("保存按钮，默认「保存」"))
                    put("action_add", strProp("添加按钮，默认「添加」"))
                    put("action_close", strProp("关闭按钮，默认「关闭」"))
                    put("action_reset", strProp("重置按钮，默认「重置」"))
                })
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "reset_ui_strings",
            description = "将应用所有 UI 文字标签恢复为默认中文。当用户要求恢复默认文字或你改乱了标签时，请调用此工具。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    /** 内部小工具：构造一个 HEX 颜色 schema 节点 */
    private fun colorProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("pattern", "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")
        put("description", "$desc。格式 #RRGGBB 或 #RRGGBBAA")
    }

    /** 内部小工具：构造一个字符串 schema 节点 */
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
    private suspend fun handleBuiltinTool(toolName: String, arguments: JSONObject): JSONObject {
        return BuiltinToolHandler.handleBuiltinTool(context, toolName, arguments)
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
