package com.omnichat.mcp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.McpServer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(database)

    val runtimeManager = McpRuntimeManager.getInstance(application)

    // 所有已配置的 MCP server（来自数据库）
    // 使用 Eagerly 策略确保 Flow 始终保持活跃，避免数据更新延迟
    val mcpServers: StateFlow<List<McpServer>> = repository.allMcpServers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 各 server 的运行状态
    val serverStates: StateFlow<Map<Long, McpServerState>> = runtimeManager.serverStates

    // 所有已发现的工具
    val allTools: StateFlow<List<McpTool>> = runtimeManager.allTools

    init {
        // McpRuntimeManager 单例在创建时已自动启动所有已启用的 server
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

                    val server = McpServer(
                        name = name,
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
