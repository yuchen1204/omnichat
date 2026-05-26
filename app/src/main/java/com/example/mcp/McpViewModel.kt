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

    init {
        // McpRuntimeManager 单例在创建时已自动启动所有已启用的 server（见 McpRuntimeManager.init）。
        // 这里根据全局运行时开关更新状态，并按需检测 Python 运行时。
        viewModelScope.launch {
            // 监听全局运行时开关变化
            repository.uiSettings.collect { settings ->
                if (settings == null) return@collect
                
                // 1. 更新各 server 运行状态
                val servers = repository.getAllMcpServers()
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
                        runtimeManager.startServer(server)
                    }
                }

                // 2. 更新 Node.js 可用性状态 (仅在启用时加载 native 库)
                if (settings.isNodeEnabled) {
                    isNodeRuntimeAvailable = NodeJsBridge.ensureLoaded()
                } else {
                    // 如果未启用，则不触发加载，仅报告当前内存中的加载状态
                    isNodeRuntimeAvailable = NodeJsBridge.isLoaded
                }

                // 3. 更新 Python 可用性状态 (仅在启用时初始化解释器)
                if (settings.isPythonEnabled) {
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
                } else {
                    isPythonRuntimeReady = false
                    pythonRuntimeStatus = "运行时已禁用"
                }
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
        // DO NOT call runtimeManager.stopAll() here! McpRuntimeManager is a singleton 
        // shared across the application. If its scope is cancelled, MCP tools will permanently 
        // break until app restart.
    }
}
