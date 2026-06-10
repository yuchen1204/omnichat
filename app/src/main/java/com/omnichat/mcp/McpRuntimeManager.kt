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
import com.omnichat.R
import com.omnichat.mcp.ToolSchemaDsl.schema
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

    private val requestIdCounter = AtomicLong(1)

    // ── 内置工具服务器 ────────────────────────────────────────────────────
    // 使用负数 ID 避免与用户创建的 MCP server ID 冲突

    private val builtinTools = listOf(
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_current_time",
            description = "Get the current real date and time (including timezone). Call this tool whenever you need to know today's date, the current time, the day of the week, or perform any reasoning that depends on the current time.",
            // 使用 ToolSchemaDsl 替代手写 JSONObject
            inputSchema = schema {
                prop("timezone", "string", "Optional. IANA timezone name, e.g. Asia/Shanghai or America/New_York. Leave empty to use the device's local timezone.")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_ui_capabilities",
            description = "Query the capability manifest and current values of the app's UI theme configuration. **Call this tool before calling adjust_ui** to learn all adjustable fields, their semantics, constraints, and current effective values. The response includes: color field list (primary palette / status colors / extended colors), layout parameters (corner radius / spacing), valid value constraints (HEX range), and recommended color combination suggestions.",
            inputSchema = schema {}
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_ui",
            description = "Adjust the app's complete visual theme in a single call — color scheme, layout, and font settings.\n\n**Covers:** Material 3 color palette (primary / secondary / tertiary + their container and on-colors), surface and outline colors, error / success / warning / info / accent colors, corner radius, spacing multiplier, global font size scale, chat font size scale, and font family.\n\n**Important:** Call get_ui_capabilities first to see current values and constraints. All colors must be #RRGGBB or #RRGGBBAA. Fields not provided retain their current values (incremental update). Pass resetToDefault=true to restore everything to defaults. Changes take effect immediately without restart.",
            inputSchema = schema {
                for (f in UiFieldRegistry.colorFields) {
                    put(f.key, colorProp(f.desc))
                }
                prop("cornerRadiusDp", "integer", "Global corner radius in dp, range 0–32. Affects cards, buttons, and other rounded elements.")
                prop("spacingMultiplier", "number", "Global spacing multiplier, range 0.5–2.0. 1.0 is the default; >1 is more spacious, <1 is more compact.")
                for (f in UiFieldRegistry.fontFields) {
                    put(f.key, f.toSchemaProp())
                }
                prop("resetToDefault", "boolean", "Pass true to immediately reset ALL UI settings (colors, layout, font) to defaults. Other fields are ignored.")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "color_scheme",
            description = "Manage saved color scheme presets: save the current theme, list presets, apply a preset, or delete one.\n\n" +
                "• **save** — Save current UI settings as a named preset (name + description required). Up to ${com.omnichat.data.ColorSchemePreset.MAX_PRESETS} presets allowed.\n" +
                "• **list** — List all saved presets with their schemeId, name, description, and preview colors.\n" +
                "• **apply** — Apply a preset by schemeId; takes effect immediately.\n" +
                "• **delete** — Delete a preset by schemeId to free up a slot.",
            inputSchema = schema {
                prop("action", "string", "Operation to perform.") {
                    enum("save", "list", "apply", "delete")
                }
                prop("name", "string", "Preset name (max 30 chars). Required for 'save'.")
                prop("description", "string", "Preset description (max 100 chars). Required for 'save'.")
                prop("schemeId", "string", "Preset ID (from list). Required for 'apply' and 'delete'.")
                required("action")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "search_memory",
            description = "Search the long-term memory store for entries related to a keyword. Call this tool when you need to recall a specific user preference, habit, or historical detail that is not present in the current context. The system automatically injects the top 30 highest-confidence memories; all other memories must be retrieved proactively via this tool. Results include automatic traversal of the memory association network (controlled by the 'depth' parameter).",
            inputSchema = schema {
                prop("query", "string", "Search keywords; multiple words are supported (space-separated), e.g. \"programming language Kotlin\" or \"dietary preference\". The search performs fuzzy matching against memory content.")
                prop("tag", "string", "Optional tag filter. Valid values: preference, fact, instruction, habit, context. When provided, only memories with this tag are searched.")
                prop("limit", "integer", "Maximum number of results to return. Default 10, max 50.")
                prop("depth", "integer", "Association traversal depth (1-5). Default 3. When set, the tool traverses the memory association network to find related memories beyond direct keyword matches.")
                required("query")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "mark_reminded",
            description = "Mark a time reminder as reminded to prevent repeat reminders. Call this after you have naturally mentioned a pending reminder to the user in your response.",
            inputSchema = schema {
                prop("memory_id", "integer", "The ID of the memory reminder to mark as reminded")
                required("memory_id")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "list_ui_texts",
            description = "View all adjustable UI text strings in the app along with their default values (English primary, Chinese secondary) and current AI override values. An optional `query` parameter (e.g. \"mcp\" or \"session\") can be provided to fuzzy-filter results by key or default value.\n\n## Line break tip\n\nYou can use `\\n` in `set_ui_texts` values to insert line breaks. For longer translated strings (e.g. French, German), insert `\\n` at semantic break points to enable automatic wrapping and prevent text from being clipped.",
            inputSchema = schema {
                prop("query", "string", "Optional. Fuzzy-filter by key name or default value. If not provided, all UI text entries are listed.")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "set_ui_texts",
            description = "Override any UI text labels (buttons, headings, hints, placeholders, etc.). Changes take effect globally and immediately without a restart.\n\n## How it works\n\nEvery UI string in the app is registered via a `uiText(key, resId)` call. Each key maps to a localized string resource (English by default, Chinese on zh-CN devices). When the AI writes a new value for a key using this tool, every location that references that key immediately displays the new text. Keys without an override automatically fall back to the localized default.\n\n## Usage\n\n• `updates`: A key→value dictionary of strings to set or update. E.g. `{\"topbar.title.chat\": \"Chat\", \"action.confirm\": \"OK\"}`.\n• `delete`: A list of keys whose overrides should be removed (reverting to the localized default). E.g. `[\"action.confirm\"]`.\n• `resetAll`: Pass true to remove all overrides at once and restore all default localized strings.\n\n## Key naming conventions (not enforced)\n\ntopbar.* / sidebar.* / nav.* / tab.* / chat.* / models.* / memory.* / mcp.* / dialog.* / action.* / status.* / hint.* / icon.*\n\n## Line break support\n\nUse `\\n` in values to insert line breaks. For languages where translations are significantly longer (e.g. French, German), insert `\\n` at appropriate semantic break points to enable automatic wrapping and prevent text from being clipped. Example: `\"tab.settings.memory\": \"Mémoire\\nlongue\"`. The app handles multi-line text display automatically.\n\n## Important\n\nThe key must exactly match the key used in the `uiText()` call in the code for the override to take effect. Call `list_ui_texts` first to see existing overrides. If the user wants to change a string but no existing key is found, ask which area of the UI it appears in (top bar / sidebar / chat / settings / dialog, etc.) and derive the key from the naming conventions above. Common example keys: `topbar.title.chat`, `topbar.title.settings`, `topbar.menu.open`, `topbar.memory.syncing`, `topbar.provider.prefix`, `sidebar.title`, `sidebar.menu.newSession`, `chat.input.placeholder`, `chat.empty.title`, `action.confirm`, `action.cancel`, `dialog.delete.title`, `status.loading`, `hint.swipeDelete`.",
            inputSchema = schema {
                prop("updates", "object", "A key→value dictionary of UI text strings to set or update. The key is the name used in the uiText() call in the code; the value is the new text to display.") {
                    additionalProperties { /* type string is default */ }
                }
                prop("delete", "array", "A list of keys whose overrides should be removed (reverting to the localized default).") {
                    items { /* type string is default */ }
                }
                prop("resetAll", "boolean", "Pass true to remove all overrides at once and restore all default localized strings (other fields are ignored).")
            }
        ),
        // ── 文件系统工具 ──────────────────────────────────────────────────
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_write",
            description = "Write content to a file on device storage. Creates the file (and any missing parent directories) if it does not exist, or overwrites it if it does. Use this to save notes, generated code, configuration snippets, or any text data the user wants to persist.\n\n**Path rules**: Relative paths (e.g. `notes/todo.txt`) resolve under /sdcard. Absolute paths (e.g. `/sdcard/Documents/file.txt`) are also accepted. A permission popup may appear for paths outside the app sandbox.",
            inputSchema = schema {
                prop("path", "string", "File path. Relative paths resolve under /sdcard. Absolute paths accepted. Parent directories are created automatically.")
                prop("content", "string", "Text content to write. The file is saved as UTF-8.")
                prop("encoding", "string", "Content encoding. \"utf8\" (default) writes the string as-is; \"base64\" decodes the string first (useful for binary files).") {
                    enum("utf8", "base64")
                }
                required("path", "content")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_read",
            description = "Read the content of a file from device storage. Returns the file content as a UTF-8 string (or Base64 if encoding is \"base64\"). Supports byte-based truncation and line-range reading.\n\n**Path rules**: Relative paths resolve under /sdcard. Absolute paths accepted. A permission popup may appear for paths outside the app sandbox.",
            inputSchema = schema {
                prop("path", "string", "File path. Relative paths resolve under /sdcard.")
                prop("encoding", "string", "\"utf8\" (default) returns the content as a plain string; \"base64\" returns Base64-encoded bytes (useful for binary files).") {
                    enum("utf8", "base64")
                }
                prop("maxBytes", "integer", "Optional. Maximum number of bytes to read from the start of the file. Useful for previewing large files. Default: read the entire file (up to 1 MB).")
                prop("startLine", "integer", "Optional. Start line number (1-based, inclusive). When provided with or without endLine, reads by line range instead of bytes.")
                prop("endLine", "integer", "Optional. End line number (1-based, inclusive). If omitted with startLine, reads to end of file.")
                required("path")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_append",
            description = "Append text to the end of an existing file on device storage. If the file does not exist it is created. A newline is automatically inserted before the appended content when the file already has content and does not end with a newline.",
            inputSchema = schema {
                prop("path", "string", "File path. Relative paths resolve under /sdcard.")
                prop("content", "string", "Text to append. Saved as UTF-8.")
                required("path", "content")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_delete",
            description = "Delete a file or an empty directory from device storage. To delete a directory and all its contents recursively, set recursive to true.\n\n**Safety**: Deletion is permanent.",
            inputSchema = schema {
                prop("path", "string", "Path of the file or directory to delete.")
                prop("recursive", "boolean", "If true, delete the directory and all its contents recursively. Default false (only deletes empty directories or files).")
                required("path")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_list",
            description = "List the contents of a directory on device storage. Returns file names, types, sizes, and last-modified timestamps. Supports recursive listing with configurable depth. Pass an empty string or \".\" to list /sdcard root.",
            inputSchema = schema {
                prop("path", "string", "Directory path. Use \"\" or \".\" for /sdcard root. Relative paths resolve under /sdcard.")
                prop("showHidden", "boolean", "Include entries whose names start with a dot. Default false.")
                prop("recursive", "boolean", "List subdirectories recursively. Default false.")
                prop("maxDepth", "integer", "Maximum recursion depth (1-10). Default 3. Only effective when recursive=true.")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_search",
            description = "Search for files by name pattern or by text/regex content within device storage. Supports glob-style name matching and full-text or regex content search with context lines.",
            inputSchema = schema {
                prop("namePattern", "string", "Optional. Glob-style filename pattern, e.g. \"*.txt\", \"report_*\", \"*.json\". Matches against the file name only.")
                prop("contentQuery", "string", "Optional. Search string or regex to look for inside file contents. Case-insensitive by default.")
                prop("directory", "string", "Optional. Directory to restrict the search to. Defaults to /sdcard root (recursive).")
                prop("maxResults", "integer", "Maximum number of results to return. Default 20, max 100.")
                prop("isRegex", "boolean", "Treat contentQuery as a regular expression. Default false.")
                prop("contextLines", "integer", "Number of lines to show before and after each match. Default 0, max 10.")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_info",
            description = "Get metadata for a file or directory on device storage: absolute path, size in bytes, last-modified timestamp, MIME type guess, whether it is readable/writable, and (for directories) the number of direct children.",
            inputSchema = schema {
                prop("path", "string", "File or directory path.")
                required("path")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_move",
            description = "Move or rename a file or directory on device storage. The destination parent directory is created automatically if it does not exist.",
            inputSchema = schema {
                prop("sourcePath", "string", "Source path.")
                prop("destinationPath", "string", "Destination path.")
                prop("overwrite", "boolean", "If true, overwrite the destination if it already exists. Default false (returns an error if destination exists).")
                required("sourcePath", "destinationPath")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_copy",
            description = "Copy a file or directory on device storage. Directories are copied recursively. The destination parent directory is created automatically if it does not exist.",
            inputSchema = schema {
                prop("sourcePath", "string", "Source path.")
                prop("destinationPath", "string", "Destination path.")
                prop("overwrite", "boolean", "If true, overwrite the destination if it already exists. Default false.")
                required("sourcePath", "destinationPath")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "file_mkdir",
            description = "Create a directory (and any missing parent directories) on device storage. Returns an error if the path exists and is not a directory.",
            inputSchema = schema {
                prop("path", "string", "Directory path to create.")
                required("path")
            }
        ),
        // ── 文档创建工具 ──────────────────────────────────────────────────
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "create_document",
            description = "Create an exquisite, formatted document (PDF, Excel, Word, or PowerPoint) with rich sections and styling. Use this for reports, presentations, or data analysis.\n\n**Sections support:**\n- `heading`: Title/Header text with hierarchy (1-3).\n- `text`: Paragraph text, supports **bold**, *italic*, and lists if `markdown` is true.\n- `table`: Data grid with headers and rows.\n- `image`: Insert image from local path (OmniChat/files/path.jpg).\n- `page_break`: Force a new page or slide.\n\n**Style options:**\n- `themeColor`: Hex color code (e.g., \"#1A73E8\").\n- `preset`: \"business\", \"modern\", or \"classic\".",
            inputSchema = schema {
                prop("path", "string", "Relative file path inside OmniChat/files/, e.g. \"reports/analysis.pdf\".")
                prop("format", "string", "Document format.") {
                    enum("pdf", "xlsx", "docx", "pptx")
                }
                prop("title", "string", "Main document title.")
                prop("style", "object", "Document style options.") {
                    properties {
                        prop("themeColor", "string", "Hex color code.")
                        prop("preset", "string", "Style preset.") {
                            enum("business", "modern", "classic")
                        }
                    }
                }
                prop("sections", "array", "List of document sections in order.") {
                    items {
                        properties {
                            prop("type", "string", "Section type.") {
                                enum("heading", "text", "table", "image", "page_break")
                            }
                            prop("content", "string", "Text content for heading/text, or image path.")
                            prop("level", "integer", "For heading: 1 (main), 2 (sub), 3 (minor).")
                            prop("markdown", "boolean", "Apply markdown formatting to text.")
                            prop("table", "object", "Table data for table sections.") {
                                properties {
                                    prop("headers", "array", "Column headers.") { items { } }
                                    prop("rows", "array", "Table rows.") { items { } }
                                }
                            }
                        }
                    }
                }
                prop("paragraphs", "array", "Legacy: use sections instead.") { items { } }
                prop("table", "object", "Legacy: use sections instead.")
                prop("slides", "array", "Legacy: use sections instead.") { items { } }
                required("path", "format")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "ask_user",
            description = "Ask the user a clarifying question when their request is ambiguous or underspecified, or to confirm a decision. You can provide 1 to 5 options for them to choose from, or they can input their custom answer. Supports single-select (default) and multi-select modes. The function will block and wait for user response.",
            inputSchema = schema {
                prop("question", "string", "The clarifying question or prompt to display to the user.")
                prop("options", "array", "Optional list of 1 to 5 predefined options that the user can choose from.") {
                    items { }
                }
                prop("multi_select", "boolean", "If true, the user can select multiple options (checkboxes). If false or omitted, the user can only select one option (buttons). When multi_select is true, the response is a JSON array of selected options.")
                required("question")
            }
        ),
        // ── 定时器工具 ────────────────────────────────────────────────────
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "create_timer",
            description = "Create a timer that fires after a delay (supports one-shot and repeating). When the timer fires, it inserts a reminder message into the current chat session AND sends a system notification. Timers survive app restarts and device reboots.\n\nPREREQUISITE: You MUST call get_current_time first to confirm the current time before creating any timer. This ensures the delay is calculated correctly relative to the actual time.\n\nUse this when the user asks to be reminded about something (e.g. \"remind me in 30 minutes\", \"set a timer for 1 hour\", \"remind me every 2 hours to drink water\").\n\nSpecify delay using hours, minutes, and/or seconds — at least one must be > 0. For example: hours=1, minutes=30 means 1 hour 30 minutes. Do NOT do math yourself — just pass the human-readable time components directly.\n\nReturns a `timerId` that can be used with `cancel_timer`.",
            inputSchema = schema {
                prop("hours", "integer", "Hours component of the delay (default 0). E.g. for \"2 hours 30 minutes\", set hours=2.")
                prop("minutes", "integer", "Minutes component of the delay (default 0). E.g. for \"45 minutes\", set minutes=45.")
                prop("seconds", "integer", "Seconds component of the delay (default 0). E.g. for \"90 seconds\", set seconds=90.")
                prop("delay_seconds", "integer", "Legacy parameter. Prefer using hours/minutes/seconds instead. Total delay in seconds. If hours/minutes/seconds are also provided, they take precedence.")
                prop("message", "string", "The reminder message to display when the timer fires. This text will appear in the chat and in the system notification. Be specific and actionable.")
                prop("label", "string", "Optional short label for the notification title (max 30 characters). Defaults to \"AI 定时提醒\" if not provided.")
                prop("repeat_hours", "integer", "Repeat interval: hours component (default 0). E.g. for \"every 2 hours\", set repeat_hours=2.")
                prop("repeat_minutes", "integer", "Repeat interval: minutes component (default 0). E.g. for \"every 30 minutes\", set repeat_minutes=30.")
                prop("repeat_seconds", "integer", "Repeat interval: seconds component (default 0). E.g. for \"every 90 seconds\", set repeat_seconds=90.")
                prop("repeat_interval_seconds", "integer", "Legacy parameter. Prefer repeat_hours/repeat_minutes/repeat_seconds. Repeat interval in seconds. The new params take precedence if provided.")
                prop("task_id", "string", "Optional. Associate this timer with a subAgent task (from delegate_task). When the task completes, this timer is auto-cancelled. Include the taskId in the message so you know what to check when the timer fires.")
                required("message")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "cancel_timer",
            description = "Cancel a pending timer before it fires. Use the `timerId` returned by `create_timer`. Returns an error if the timer does not exist or has already fired.",
            inputSchema = schema {
                prop("timer_id", "string", "The timer ID returned by create_timer.")
                required("timer_id")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "list_timers",
            description = "List all currently pending (not yet fired) timers created in this session. Returns each timer's ID, label, message, remaining seconds, and scheduled fire time.",
            inputSchema = schema {}
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "set_tool_display_mode",
            description = "Control how tool call results are displayed in the chat. When silent=true, tool calls are completely hidden from the UI — no indicators, no cards, nothing. The tools still execute normally; only the display is suppressed. Use this when performing multiple sequential tool calls to avoid flooding the screen. Call set_tool_display_mode(silent=false) to restore the normal detailed display.",
            inputSchema = schema {
                prop("silent", "boolean", "true = completely hide tool calls from UI, false = show full tool call cards (default).")
                required("silent")
            }
        ),
        // ── 运行时工具组管理 ──────────────────────────────────────────────
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "list_mcp_tool_groups",
            description = "List all available built-in MCP tool groups and their current enabled/disabled status. Use this tool to discover what capabilities are currently available to you or can be activated. Groups: core (essential), ui_appearance (theming/colors), ui_text (i18n), files (storage), documents (office), efficiency (timers), memory (long-term facts).",
            inputSchema = schema {}
        ),
        // ── subAgent 任务委托工具 ──────────────────────────────────────────
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "delegate_task",
            description = """将任务委托给专门的子代理异步执行。

可用代理类型：
- general: 通用任务，适合不确定分类的工作
- researcher: 信息搜索、资料整理、网络检索
- coder: 代码编写、文件创建/修改
- reviewer: 代码审查、质量检查、问题发现
- tester: 测试用例编写、验证逻辑

任务将在后台执行，完成后结果会插入当前会话。返回一个 taskId 用于追踪。

IMPORTANT: After delegating, do NOT immediately call check_task_status — the task runs asynchronously and won't be done yet. Use create_timer(minutes=1, task_id="<taskId>") to set a reminder, then continue with other work. When the timer fires, check status. The result will also appear automatically when complete.""",
            inputSchema = schema {
                prop("agent_type", "string", "代理类型") {
                    enum("general", "researcher", "coder", "reviewer", "tester")
                }
                prop("task", "string", "任务描述。清晰说明目标、约束、期望输出格式。")
                prop("context", "string", "可选。附加上下文：相关文件路径、代码片段、背景信息。")
                prop("files", "array", "可选。需要操作的文件路径列表。") {
                    items { }
                }
                required("agent_type", "task")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "check_task_status",
            description = "查询委托任务的执行状态和结果。",
            inputSchema = schema {
                prop("task_id", "string", "delegate_task 返回的任务 ID")
                required("task_id")
            }
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "list_agent_tasks",
            description = "列出当前会话中所有 subAgent 任务及其状态。",
            inputSchema = schema {}
        ),
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "configure_mcp_tool_groups",
            description = "Enable or disable specific built-in MCP tool groups. Use this when you need a tool that is currently disabled, or when you want to simplify your toolset. Note: 'core' group cannot be disabled. Changes persist across sessions.",
            // BUG-010: 使用 enum 约束有效组名，避免无效值写入数据库
            inputSchema = schema {
                prop("enable", "array", "List of group names to enable. Valid: ui_text, ui_appearance, files, documents, efficiency, memory. Note: 'core' cannot be disabled.") {
                    items {
                        enum("ui_text", "ui_appearance", "files", "documents", "efficiency", "memory")
                    }
                }
                prop("disable", "array", "List of group names to disable. Note: 'core' cannot be disabled.") {
                    items {
                        enum("ui_text", "ui_appearance", "files", "documents", "efficiency", "memory")
                    }
                }
            }
        ),
    )

    /**
     * 将内置工具映射到分类组
     */
    private val builtinToolGroups = mapOf(
        "get_current_time" to "core",
        "search_memory" to "memory",
        "mark_reminded" to "memory",
        "get_ui_capabilities" to "ui_appearance",
        "adjust_ui" to "ui_appearance",
        "color_scheme" to "ui_appearance",
        "list_ui_texts" to "ui_text",
        "set_ui_texts" to "ui_text",
        "file_write" to "files",
        "file_read" to "files",
        "file_append" to "files",
        "file_delete" to "files",
        "file_list" to "files",
        "file_search" to "files",
        "file_info" to "files",
        "file_move" to "files",
        "file_copy" to "files",
        "file_mkdir" to "files",
        "create_document" to "documents",
        "ask_user" to "core",
        "create_timer" to "efficiency",
        "cancel_timer" to "efficiency",
        "list_timers" to "efficiency",
        "list_mcp_tool_groups" to "core",
        "configure_mcp_tool_groups" to "core",
        "delegate_task" to "core",
        "check_task_status" to "core",
        "list_agent_tasks" to "core",
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
                    command = ""
                ),
                status = McpServerStatus.RUNNING,
                tools = builtinTools
            )
        )
    )
    val serverStates: StateFlow<Map<Long, McpServerState>> = _serverStates.asStateFlow()

    private val enabledBuiltinTools: StateFlow<List<McpTool>> = AppDatabase.getDatabase(context).uiSettingsDao().getSettingsFlow()
        .map { settings ->
            val enabledGroups = settings?.enabledMcpGroups?.split(",")?.toSet() ?: setOf("core", "ui_appearance", "efficiency", "memory")
            builtinTools.filter { tool ->
                val group = builtinToolGroups[tool.name] ?: "core"
                group == "core" || group in enabledGroups
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, builtinTools.filter { builtinToolGroups[it.name] == "core" || builtinToolGroups[it.name] in listOf("ui_appearance", "efficiency", "memory") })

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
        return allTools.value.find { it.name == toolName }?.serverId
    }

    suspend fun callTool(serverId: Long, toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject? {
        Log.d(TAG, "[callTool] serverId=$serverId, tool=$toolName")

        // 1. Dispatch Before Execute Hook
        val processedArgs = com.omnichat.hooks.HookManager.dispatchBeforeToolExecute(toolName, arguments)
        if (processedArgs == null) {
            Log.w(TAG, "[callTool] Hook blocked execution of tool $toolName")
            return JSONObject().apply {
                put("content", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Error: Execution blocked by Hook")
                    })
                })
                put("isError", true)
            }
        }

        val rawResult = if (serverId == BUILTIN_SERVER_ID) {
            try {
                handleBuiltinTool(toolName, processedArgs, sessionId)
            } catch (t: Throwable) {
                Log.e(TAG, "内置工具 $toolName 执行发生严重错误", t)
                JSONObject().apply {
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Error running builtin tool $toolName: ${t.localizedMessage}")
                        })
                    })
                    put("isError", true)
                }
            }
        } else {
            try {
                val response = sendRequest(
                    serverId = serverId,
                    method = "tools/call",
                    params = JSONObject().apply {
                        put("name", toolName)
                        put("arguments", processedArgs)
                    }
                )
                response.optJSONObject("result")
            } catch (e: Exception) {
                Log.e(TAG, "调用工具 $toolName 失败", e)
                null
            }
        }

        // 2. Dispatch After Execute Hook
        if (rawResult != null) {
            val resultStr = rawResult.toString()
            val processedResultStr = com.omnichat.hooks.HookManager.dispatchAfterToolExecute(toolName, resultStr)
            return try {
                JSONObject(processedResultStr)
            } catch (e: Exception) {
                Log.w(TAG, "Hook returned invalid JSON for tool $toolName, falling back to wrapping as text: $processedResultStr", e)
                JSONObject().apply {
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", processedResultStr)
                        })
                    })
                }
            }
        }

        return null
    }

    /**
     * 处理内置工具调用，直接在 JVM 层执行，无需外部进程。
     */
    private suspend fun handleBuiltinTool(toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject {
        return BuiltinToolHandler.handleBuiltinTool(context, toolName, arguments, sessionId)
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
