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
import kotlinx.coroutines.withContext

class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(database)

    val runtimeManager = McpRuntimeManager.getInstance(application)

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
        // McpRuntimeManager 单例在创建时已自动启动所有已启用的 server（见 McpRuntimeManager.init）。
        // 这里只需检测 Python 运行时状态，用于 UI 展示。
        viewModelScope.launch {
            // 监听全局运行时开关变化
            repository.uiSettings.collect { settings ->
                if (settings == null) return@collect
                val servers = repository.getAllMcpServers() // 获取最新列表
                servers.forEach { server ->
                    val isAllowed = when (server.runtime) {
                        "node" -> settings.isNodeEnabled
                        "python" -> settings.isPythonEnabled
                        else -> true
                    }
                    val currentState = serverStates.value[server.id]
                    val isRunning = currentState?.status == McpServerStatus.RUNNING || currentState?.status == McpServerStatus.STARTING
                    
                    if (!isAllowed && isRunning) {
                        runtimeManager.stopServer(server.id)
                    } else if (isAllowed && !isRunning && server.isEnabled) {
                        // 如果运行时被重新启用，且服务本身是启用状态，则尝试启动
                        runtimeManager.startServer(server)
                    }
                }
            }
        }

        viewModelScope.launch {
            // 检测 Python 运行时
            val ready = withContext(Dispatchers.IO) { PythonRuntime.ensureReady(application) }
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

    /**
     * 批量导入 MCP 服务配置（JSON 格式）
     * 支持的格式：{"mcpServers": {"name": {"command": "...", "args": [...], "env": {...}}, ...}}
     */
    fun importConfigJson(jsonContent: String) {
        viewModelScope.launch {
            try {
                val root = org.json.JSONObject(jsonContent)
                val serversObj = root.optJSONObject("mcpServers") ?: return@launch
                
                val keys = serversObj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val config = serversObj.getJSONObject(name)
                    
                    val command = config.getString("command")
                    val argsArr = config.optJSONArray("args")
                    val envObj = config.optJSONObject("env")
                    
                    val argsJson = argsArr?.toString() ?: "[]"
                    val envJson = envObj?.toString() ?: "{}"
                    
                    // 默认使用 node 运行时（如果是路径）或 python
                    val runtime = when {
                        command.startsWith("http") -> "remote_http"
                        command.startsWith("/") || command.endsWith(".js") -> "node"
                        command.endsWith(".py") -> "python"
                        else -> "node"
                    }

                    val server = McpServer(
                        name = name,
                        runtime = runtime,
                        command = command,
                        args = argsJson,
                        env = envJson,
                        isEnabled = true
                    )
                    addServer(server)
                }
            } catch (e: Exception) {
                android.util.Log.e("McpViewModel", "导入 JSON 配置失败", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runtimeManager.stopAll()
    }
}
