package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.service.WorkspaceForegroundService
import com.example.workspace.*
import com.example.workspace.TeamCompletionSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.mcp.AskUserManager

/**
 * 多 Agent 工作区 ViewModel。
 *
 * 管理工作区会话列表、Agent Tab 状态、流式输出缓冲和 Agent 预设。
 * 继承 AndroidViewModel 以访问 Application context，直接实例化 AppDatabase / AppRepository。
 *
 * 需求：2.1、2.2、7.1
 */
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private var teamManager: TeamManager? = null

    // WHY: 每次 submitTask/loadSessionHistory 都会启动新的 collect 协程。
    // 旧协程未取消会导致多个收集器同时更新 _teamState，造成状态污染。
    private var teamStateCollectorJob: Job? = null

    // Protects team lifecycle: createTeam, deleteTeam, submitTask, selectWorkspaceSession
    private val teamLifecycleMutex = Mutex()

    // BUG-10：恢复模式标志，防止 onAgentCreated 回调覆盖已从 DB 恢复的 _agentTabs
    @Volatile
    private var isRestoringSession = false

    // ── StateFlow 声明 ─────────────────────────────────────────────────────

    /**
     * 工作区会话列表（用于侧边栏展示）。
     *
     * 需求 2.1：侧边栏在普通会话列表之外新增独立的"工作区"分类区域。
     */
    val workspaceSessions: StateFlow<List<WorkspaceSession>> = repository.allWorkspaceSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 当前选中的工作区会话 ID。
     *
     * 需求 2.2：用户点击工作区条目时导航至该工作区界面。
     */
    private val _selectedWorkspaceId = MutableStateFlow<Long?>(null)
    val selectedWorkspaceId: StateFlow<Long?> = _selectedWorkspaceId.asStateFlow()

    /**
     * 当前工作区内的 Agent Tab 列表。
     *
     * 需求 7.1：Tab 栏展示所有 Agent，Orchestrator 固定在最左侧。
     */
    private val _agentTabs = MutableStateFlow<List<AgentTabState>>(emptyList())
    val agentTabs: StateFlow<List<AgentTabState>> = _agentTabs.asStateFlow()

    /**
     * 每个 Agent 的流式输出缓冲（agentName -> 累积文本）。
     *
     * 需求 7.2：流式输出以不超过 50ms 的刷新间隔实时追加渲染。
     */
    private val _agentStreamBuffers = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentStreamBuffers: StateFlow<Map<String, String>> = _agentStreamBuffers.asStateFlow()

    /**
     * 每个 Agent 的状态（agentName -> AgentStatus）。
     *
     * 需求 7.4、7.5：根据状态显示加载指示器或完成图标。
     */
    private val _agentStatuses = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())
    val agentStatuses: StateFlow<Map<String, AgentStatus>> = _agentStatuses.asStateFlow()

    /**
     * 已完成会话的 Orchestrator 历史消息（从 DB 加载）。
     *
     * 需求 9.4：重新打开已完成的工作区会话时，仅展示 Orchestrator 的历史对话记录。
     */
    private val _completedOrchestratorMessages = MutableStateFlow<List<WorkspaceMessage>>(emptyList())
    val completedOrchestratorMessages: StateFlow<List<WorkspaceMessage>> = _completedOrchestratorMessages.asStateFlow()

    /**
     * Agent 预设列表。
     *
     * 需求 1.3：Agent 预设持久化存储。
     */
    val agentPresets: StateFlow<List<AgentPreset>> = repository.allAgentPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 模型配置列表（用于选择器）。
     *
     * 需求 3.1：主控 Agent 模型选择器列出所有 ModelConfig 条目。
     */
    val modelConfigs: StateFlow<List<ModelConfig>> = repository.allConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 所有抓取的具体模型列表。
     */
    val fetchedModels: StateFlow<List<FetchedModel>> = repository.allFetchedModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 辅助属性 ───────────────────────────────────────────────────────────

    /**
     * 当前选中的工作区会话。
     */
    private val _selectedWorkspaceSession = MutableStateFlow<WorkspaceSession?>(null)
    val selectedWorkspaceSession: StateFlow<WorkspaceSession?> = _selectedWorkspaceSession.asStateFlow()

    /**
     * 错误消息（用于 UI 展示）。
     */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 工作区文件操作沙盒路径。
     *
     * 用户在提交任务前指定，所有 Agent 的文件操作将被限制在此目录内。
     */
    private val _sandboxPath = MutableStateFlow<String?>(null)
    val sandboxPath: StateFlow<String?> = _sandboxPath.asStateFlow()

    /**
     * 团队状态（来自 TeamManager 的 StateFlow）。
     *
     * 包含所有 Teammate 的运行时状态，用于 UI 展示 Agent 颜色等。
     */
    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    // 团队任务列表，供 TeamTaskPanel 展示
    private val _teamTasks = MutableStateFlow<List<com.example.data.TeamTask>>(emptyList())
    val teamTasks: StateFlow<List<com.example.data.TeamTask>> = _teamTasks.asStateFlow()

    // ── 公共方法 ───────────────────────────────────────────────────────────

    /**
     * 清除错误消息。
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * 加载指定团队的任务列表。
     *
     * 在工作区会话被选中或创建时调用，使 TeamTaskPanel 能展示实时任务数据。
     */
    fun loadTeamTasks(teamName: String) {
        viewModelScope.launch {
            try {
                val tasks = repository.getTeamTasksByTeam(teamName)
                _teamTasks.value = tasks
            } catch (e: Exception) {
                Log.w("WorkspaceViewModel", "Failed to load team tasks", e)
            }
        }
    }

    /**
     * 设置工作区文件操作沙盒路径。
     */
    fun setSandboxPath(path: String?) {
        _sandboxPath.value = path?.trim()?.ifEmpty { null }
    }

    /**
     * 获取可恢复的活跃工作区团队列表。
     */
    suspend fun getResumableTeams(): List<com.example.data.WorkspaceTeam> {
        return repository.getActiveWorkspaceTeams()
    }

    /**
     * 创建新的工作区会话。
     *
     * 写入数据库，失败时通过 [errorMessage] StateFlow 暴露错误消息"创建工作区失败，请重试"，
     * 成功时返回新会话 ID。
     *
     * 需求 2.2、2.6：创建新工作区会话，失败时显示错误提示。
     *
     * @return 新创建的会话 ID，失败时返回 -1
     */
    suspend fun createWorkspaceSession(): Long {
        val session = WorkspaceSession(
            title = "新工作区",
            isActive = true,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis()
        )

        return try {
            val newId = repository.insertWorkspaceSession(session)
            if (newId > 0) {
                _selectedWorkspaceId.value = newId
                _selectedWorkspaceSession.value = session.copy(id = newId)
                // 重置 Agent 状态
                _agentTabs.value = emptyList()
                _agentStreamBuffers.value = emptyMap()
                _agentStatuses.value = emptyMap()
                _completedOrchestratorMessages.value = emptyList()
                _teamState.value = null
                _sandboxPath.value = null
                // 初始化新工作区的团队任务列表（空）
                loadTeamTasks(teamNameForSession(newId))
                newId
            } else {
                _errorMessage.value = "创建工作区失败，请重试"
                -1L
            }
        } catch (e: Exception) {
            _errorMessage.value = "创建工作区失败，请重试"
            -1L
        }
    }

    /**
     * 选择工作区会话。
     *
     * 更新 [selectedWorkspaceId]，若会话已完成（isActive=false）则从数据库加载 Orchestrator 的历史消息。
     *
     * 需求 2.2、9.4：选择工作区会话，已完成会话加载历史记录。
     *
     * @param id 工作区会话 ID
     */
    fun selectWorkspaceSession(id: Long) {
        AskUserManager.clearAll()
        val prevWsId = _selectedWorkspaceId.value
        val prevSession = _selectedWorkspaceSession.value
        val prevManager = teamManager

        val cachedSession = workspaceSessions.value.find { it.id == id }
        if (cachedSession != null) {
            _selectedWorkspaceId.value = id
            _selectedWorkspaceSession.value = cachedSession
        } else {
            _selectedWorkspaceId.value = id
        }

        viewModelScope.launch {
            // 切换前持久化旧会话的消息，避免切回时丢失
            persistCurrentSessionMessages(prevManager, prevWsId, prevSession)

            // 切换到任何会话前必须清理旧活跃会话的 TeamManager 和收集器
            teamStateCollectorJob?.cancel()
            teamLifecycleMutex.withLock {
                val oldManager = teamManager
                teamManager = null
                try {
                    oldManager?.deleteTeam()
                } catch (e: Exception) {
                    Log.w("WorkspaceViewModel", "deleteTeam on switch failed (non-fatal)", e)
                }
            }
            // 停止前台服务
            WorkspaceForegroundService.stop(getApplication())
            _teamState.value = null
            // 切换时清空 agentTabs，避免显示上一个会话的 tabs
            _agentTabs.value = emptyList()
            _agentStreamBuffers.value = emptyMap()
            _agentStatuses.value = emptyMap()
            _completedOrchestratorMessages.value = emptyList()
            _sandboxPath.value = null

            val session = repository.getWorkspaceSessionById(id)
            if (session != null) {
                _selectedWorkspaceId.value = id
                _selectedWorkspaceSession.value = session
                loadSessionHistory(id, session.isActive)
                // 加载该工作区的团队任务，使 TeamTaskPanel 展示实时数据
                loadTeamTasks(teamNameForSession(id))
            }
        }
    }

    /**
     * 删除工作区会话。
     *
     * 级联删除该会话关联的 AgentInstance 和 WorkspaceMessage，再删除 WorkspaceSession。
     * Repository 层已实现级联删除逻辑。
     *
     * 需求 2.4：删除工作区会话时级联删除关联数据。
     *
     * @param id 工作区会话 ID
     */
    fun deleteWorkspaceSession(id: Long) {
        viewModelScope.launch {
            try {
                teamStateCollectorJob?.cancel()

                val oldManager = teamManager
                teamManager = null
                try {
                    oldManager?.deleteTeam()
                } catch (e: Exception) {
                    Log.w("WorkspaceViewModel", "deleteTeam on delete failed (non-fatal)", e)
                }
                WorkspaceForegroundService.stop(getApplication())

                repository.deleteWorkspaceSession(id)

                if (_selectedWorkspaceId.value == id) {
                    _selectedWorkspaceId.value = null
                    _selectedWorkspaceSession.value = null
                    _agentTabs.value = emptyList()
                    _agentStreamBuffers.value = emptyMap()
                    _agentStatuses.value = emptyMap()
                    _completedOrchestratorMessages.value = emptyList()
                    _teamState.value = null
                }
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "deleteWorkspaceSession failed", e)
                _errorMessage.value = "删除工作区失败，请重试"
            }
        }
    }

    /**
     * 提交任务，启动多 Agent 协作工作区。
     *
     * 通过 [TeamManager] 创建团队并启动 Orchestrator 执行循环。
     *
     * 需求 4.1、4.5、5.4、5.5、7.2、7.4、7.5、9.1、9.2、9.5、10.6
     */
    fun submitTask(task: String, orchestratorModelConfigId: Long, orchestratorOverrideModelId: String? = null, imagePath: String? = null, sandboxPath: String? = null) {
        val currentSession = _selectedWorkspaceSession.value ?: return
        val wsId = currentSession.id
        if (!currentSession.isActive) return

        viewModelScope.launch {
            try {
                val allConfigs = repository.getAllConfigs()
                val orchestratorConfig = repository.getConfigById(orchestratorModelConfigId)
                    ?: allConfigs.find { it.isDefaultProvider }
                    ?: allConfigs.firstOrNull()
                    ?: throw IllegalStateException("No model configs available")

                // 重置状态
                _agentStreamBuffers.value = emptyMap()
                _agentStatuses.value = emptyMap()
                _agentTabs.value = emptyList()

                // 清理旧 TeamManager 并创建新的（受 mutex 保护，防止与 selectWorkspaceSession 竞争）
                teamLifecycleMutex.withLock {
                    val oldManager = teamManager
                    teamManager = null
                    oldManager?.deleteTeam()
                    teamManager = createTeamManager(wsId)
                }

                val runtimeManager = com.example.mcp.McpRuntimeManager.getInstance(getApplication())
                runtimeManager.waitForStartingServersToFinish()

                teamStateCollectorJob?.cancel()

                // 收集 teamState 到 VM 层供 UI 使用
                teamStateCollectorJob = viewModelScope.launch {
                    teamManager?.teamState?.collect { state ->
                        _teamState.value = state
                    }
                }

                teamManager?.createTeam(teamNameForSession(wsId), orchestratorConfig = orchestratorConfig, orchestratorOverrideModelId = orchestratorOverrideModelId, sandboxPath = sandboxPath)

                // 团队创建后刷新任务列表，捕获 task_create 工具产生的任务
                loadTeamTasks(teamNameForSession(wsId))

                WorkspaceForegroundService.start(
                    getApplication(),
                    "正在执行多 Agent 协作：${task.take(30)}..."
                )

                teamManager?.startExecution(task, imagePath)

            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Failed to start workspace execution", e)
                teamStateCollectorJob?.cancel()
                teamManager?.deleteTeam()
                teamManager = null
                com.example.mcp.BuiltinToolHandler.teamManager = null
                WorkspaceForegroundService.stop(getApplication())
                _errorMessage.value = "启动工作区执行失败，请重试"
            }
        }
    }

    /**
     * 发送用户干预消息。
     *
     * 需求 8.2-8.5：向指定 Agent 注入干预消息。
     */
    fun sendIntervention(targetAgentName: String, message: String, imagePath: String? = null) {
        viewModelScope.launch {
            try {
                val manager = teamManager
                if (manager == null) {
                    Log.w("WorkspaceViewModel", "Cannot send intervention: no active team")
                    return@launch
                }

                // 注入消息到 AgentRunner 的运行时上下文
                val runner = manager.getRunner(targetAgentName)
                if (runner != null) {
                    runner.injectMessage("user", message, isIntervention = true, imagePath = imagePath)
                } else {
                    Log.w("WorkspaceViewModel", "Cannot send intervention: runner not found for $targetAgentName")
                }

                // 同步更新 UI 状态
                val userMsg = AgentMessage(
                    role = "user",
                    content = message,
                    isIntervention = true,
                    imagePath = imagePath,
                    timestamp = System.currentTimeMillis()
                )
                _agentTabs.update { tabs ->
                    tabs.map { tab ->
                        if (tab.agentName == targetAgentName) {
                            tab.copy(messages = tab.messages + userMsg)
                        } else tab
                    }
                }
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Failed to send intervention", e)
            }
        }
    }

    /**
     * 保存 Agent 预设。
     *
     * 需求 1.4、1.5
     */
    fun saveAgentPreset(preset: AgentPreset) {
        if (preset.name.trim().isEmpty()) {
            return
        }
        viewModelScope.launch {
            try {
                repository.insertAgentPreset(preset)
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Failed to save preset", e)
            }
        }
    }

    /**
     * 删除 Agent 预设。
     *
     * 需求 1.5
     */
    fun deleteAgentPreset(preset: AgentPreset) {
        viewModelScope.launch {
            try {
                repository.deleteAgentPreset(preset)
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Failed to delete preset", e)
            }
        }
    }

    // ── 导出工作日志 ─────────────────────────────────────────────────────

    private val _exportLogStatus = MutableStateFlow<ExportImportStatus>(ExportImportStatus.Idle)
    val exportLogStatus: StateFlow<ExportImportStatus> = _exportLogStatus.asStateFlow()

    fun clearExportLogStatus() {
        _exportLogStatus.value = ExportImportStatus.Idle
    }

    /**
     * 导出当前工作区的完整日志。
     */
    fun exportWorkspaceLogs(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _exportLogStatus.value = ExportImportStatus.Loading

                val session = _selectedWorkspaceSession.value
                val state = _teamState.value
                val wsId = _selectedWorkspaceId.value

                val log = buildWorkspaceLog(session, state, wsId)

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(log)
                    }
                }

                _exportLogStatus.value = ExportImportStatus.Success("日志导出成功")
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Failed to export workspace logs", e)
                _exportLogStatus.value = ExportImportStatus.Error("导出失败: ${e.message}")
            }
        }
    }

    /**
     * 构建工作区日志文本。
     */
    private suspend fun buildWorkspaceLog(
        session: WorkspaceSession?,
        state: TeamState?,
        wsId: Long?
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = dateFormat.format(Date())

        return buildString {
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("  OmniChat 工作区日志")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()

            appendLine("【工作区信息】")
            appendLine("  导出时间: $now")
            if (session != null) {
                appendLine("  会话 ID: ${session.id}")
                appendLine("  标题: ${session.title}")
                appendLine("  状态: ${if (session.isActive) "运行中" else "已完成"}")
                appendLine("  创建时间: ${dateFormat.format(Date(session.createdAt))}")
            }
            appendLine()

            if (state != null) {
                appendLine("【团队状态】")
                appendLine("  团队名: ${state.teamName}")
                appendLine("  Agent 数量: ${1 + state.activeSubAgents.size}")
                appendLine("  - ${state.orchestratorName} (Orchestrator)")
                for (sub in state.activeSubAgents) {
                    appendLine("  - ${sub.name} (Sub-Agent) [${sub.status}] ${sub.description}")
                }
                appendLine()
            }

            if (wsId != null) {
                val agentInstances = try {
                    repository.getAgentInstancesByWorkspaceSession(wsId)
                } catch (e: Exception) {
                    Log.w("WorkspaceViewModel", "Failed to load agent instances", e)
                    emptyList()
                }

                for (instance in agentInstances.sortedByDescending { it.isOrchestrator }) {
                    val messages = try {
                        repository.getWorkspaceMessagesByAgent(instance.id).map { wsMsg ->
                            AgentMessage(
                                role = wsMsg.role,
                                content = wsMsg.content,
                                toolCallId = wsMsg.toolCallId,
                                toolCallsJson = wsMsg.toolCallsJson,
                                isIntervention = wsMsg.isIntervention,
                                timestamp = wsMsg.timestamp
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("WorkspaceViewModel", "Failed to load messages for ${instance.agentName}", e)
                        emptyList()
                    }

                    val liveHistory = teamManager?.getAgentHistory(instance.agentName)
                    val allMessages = if (!liveHistory.isNullOrEmpty()) liveHistory else messages

                    if (allMessages.isEmpty()) continue

                    val roleLabel = if (instance.isOrchestrator) "Orchestrator" else "Sub-Agent"

                    appendLine()
                    appendLine("═══════════════════════════════════════════════════════════════")
                    appendLine("  $roleLabel: ${instance.agentName} (${allMessages.size} 条消息)")
                    appendLine("  创建时间: ${dateFormat.format(Date(instance.createdAt))}")
                    appendLine("═══════════════════════════════════════════════════════════════")
                    appendLine()
                    for ((index, msg) in allMessages.withIndex()) {
                        appendMessage(index, msg, dateFormat)
                    }
                }

                if (agentInstances.isEmpty()) {
                    appendAgentHistoriesFromLive(state, dateFormat)
                }
            } else {
                appendAgentHistoriesFromLive(state, dateFormat)
            }

            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("  日志结束")
            appendLine("═══════════════════════════════════════════════════════════════")
        }
    }

    /**
     * 从 teamManager 活跃实例读取 Agent 历史（fallback）。
     */
    private fun StringBuilder.appendAgentHistoriesFromLive(
        state: TeamState?,
        dateFormat: SimpleDateFormat
    ) {
        val orchestratorHistory = teamManager?.getAgentHistory(ORCHESTRATOR_NAME)
        if (!orchestratorHistory.isNullOrEmpty()) {
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("  Orchestrator 对话历史 (${orchestratorHistory.size} 条消息)")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()
            for ((index, msg) in orchestratorHistory.withIndex()) {
                appendMessage(index, msg, dateFormat)
            }
        }
    }

    /**
     * 格式化单条消息。
     */
    private fun StringBuilder.appendMessage(
        index: Int,
        msg: AgentMessage,
        dateFormat: SimpleDateFormat
    ) {
        val time = dateFormat.format(Date(msg.timestamp))
        val roleLabel = when (msg.role) {
            "user" -> "USER"
            "assistant" -> "ASSISTANT"
            "tool" -> "TOOL"
            "system" -> "SYSTEM"
            else -> msg.role.uppercase()
        }

        val interventionTag = if (msg.isIntervention) " [用户干预]" else ""
        val toolCallTag = if (msg.toolCallId != null) " [tool_call_id=${msg.toolCallId?.take(8)}]" else ""

        appendLine("  [$index] $time $roleLabel$interventionTag$toolCallTag")

        val content = msg.content.trim()
        if (content.isNotEmpty()) {
            val maxLen = 2000
            val truncated = if (content.length > maxLen) {
                content.take(maxLen) + "\n    ... (截断，共 ${content.length} 字符)"
            } else {
                content
            }
            for (line in truncated.lines()) {
                appendLine("    $line")
            }
        }

        if (msg.toolCallsJson != null) {
            appendLine("    [tool_calls]: ${msg.toolCallsJson?.take(500)}")
        }

        appendLine()
    }

    // ── 私有辅助方法 ───────────────────────────────────────────────────────

    /**
     * 加载工作区历史。如果是活跃的，还原 UI 状态并重建 TeamManager 等待新输入；
     * 否则仅加载 Orchestrator 历史。
     */
    private suspend fun loadSessionHistory(workspaceSessionId: Long, isActive: Boolean) {
        if (!isActive) {
            loadCompletedSessionHistory(workspaceSessionId)
            return
        }

        try {
            val agentInstances = repository.getAgentInstancesByWorkspaceSession(workspaceSessionId)
            val orchestratorInstance = agentInstances.find { it.isOrchestrator }

            val messagesByAgentInstanceId = mutableMapOf<Long, List<AgentMessage>>()
            for (instance in agentInstances) {
                messagesByAgentInstanceId[instance.id] = repository.getWorkspaceMessagesByAgent(instance.id).map { wsMsg ->
                    AgentMessage(
                        role = wsMsg.role,
                        content = wsMsg.content,
                        toolCallId = wsMsg.toolCallId,
                        toolCallsJson = wsMsg.toolCallsJson,
                        isIntervention = wsMsg.isIntervention,
                        timestamp = wsMsg.timestamp
                    )
                }
            }

            _agentStreamBuffers.value = emptyMap()
            _agentStatuses.value = emptyMap()
            _completedOrchestratorMessages.value = emptyList()
            _teamState.value = null

            teamStateCollectorJob?.cancel()
            teamLifecycleMutex.withLock {
                val oldManager = teamManager
                teamManager = null
                oldManager?.deleteTeam()
            }

            if (orchestratorInstance != null) {
                val allConfigs = repository.getAllConfigs()
                val orchestratorConfig = repository.getConfigById(orchestratorInstance.modelConfigId)
                    ?: allConfigs.find { it.isDefaultProvider }
                    ?: allConfigs.firstOrNull()

                if (orchestratorConfig != null) {
                    teamLifecycleMutex.withLock {
                        teamManager = createTeamManager(workspaceSessionId)
                    }

                    teamStateCollectorJob = viewModelScope.launch {
                        teamManager?.teamState?.collect { state ->
                            _teamState.value = state
                        }
                    }

                    isRestoringSession = true
                    teamManager?.createTeam(teamNameForSession(workspaceSessionId), orchestratorConfig = orchestratorConfig)

                    _agentTabs.value = agentInstances.map { instance ->
                        AgentTabState(
                            agentName = instance.agentName,
                            isOrchestrator = instance.isOrchestrator,
                            status = if (instance.isOrchestrator) AgentStatus.IDLE else AgentStatus.COMPLETED,
                            messages = messagesByAgentInstanceId[instance.id] ?: emptyList()
                        )
                    }

                    // FIX: Don't auto-start execution with empty task.
                    // Original code called startExecution("") which caused Orchestrator to
                    // output a greeting message and then exit (text-only response with no task).
                    // Now: just create the team and wait for user to submit a real task.
                    isRestoringSession = false
                    // Orchestrator stays in IDLE state, ready to receive user input via submitTask()
                }
            } else {
                Log.d("WorkspaceViewModel", "No AgentInstance in DB for session $workspaceSessionId yet (task just submitted), keeping current agentTabs")
            }
        } catch (e: Exception) {
            Log.e("WorkspaceViewModel", "Error restoring active workspace session history", e)
        }
    }

    /**
     * 持久化当前活跃会话的消息到数据库。
     */
    private suspend fun persistCurrentSessionMessages(
        manager: TeamManager? = teamManager,
        wsId: Long? = _selectedWorkspaceId.value,
        session: WorkspaceSession? = _selectedWorkspaceSession.value
    ) {
        if (wsId == null) return
        if (session == null) return
        if (!session.isActive) return

        val tabs = _agentTabs.value
        if (tabs.isEmpty()) return

        try {
            val instances = repository.getAgentInstancesByWorkspaceSession(wsId)
            val allMessages = mutableListOf<WorkspaceMessage>()

            for (tab in tabs) {
                val tabMessages = tab.messages.ifEmpty {
                    manager?.getAgentHistory(tab.agentName) ?: emptyList()
                }
                if (tabMessages.isEmpty()) continue

                val existingInstance = instances.find { it.agentName == tab.agentName }
                val agentInstance: AgentInstance = if (existingInstance != null) {
                    existingInstance
                } else {
                    val orchestratorConfigId = instances.find { it.isOrchestrator }?.modelConfigId
                        ?: try {
                            repository.getDefaultProvider()?.id
                                ?: repository.getAllConfigs().firstOrNull()?.id
                                ?: 1L
                        } catch (_: Exception) { 1L }
                    val newInstance = AgentInstance(
                        workspaceSessionId = wsId,
                        agentName = tab.agentName,
                        isOrchestrator = tab.isOrchestrator,
                        systemPrompt = if (tab.isOrchestrator) ORCHESTRATOR_SYSTEM_PROMPT else "",
                        modelConfigId = orchestratorConfigId
                    )
                    val id = repository.insertAgentInstance(newInstance)
                    if (id <= 0) {
                        Log.e("WorkspaceViewModel", "Failed to insert AgentInstance for ${tab.agentName}, skipping")
                        continue
                    }
                    newInstance.copy(id = id)
                }

                for (msg in tabMessages) {
                    allMessages.add(WorkspaceMessage(
                        workspaceSessionId = wsId,
                        agentInstanceId = agentInstance.id,
                        role = msg.role,
                        content = msg.content,
                        toolCallId = msg.toolCallId,
                        toolCallsJson = msg.toolCallsJson,
                        isIntervention = msg.isIntervention,
                        timestamp = msg.timestamp
                    ))
                }
            }

            if (allMessages.isNotEmpty()) {
                repository.replaceWorkspaceMessages(wsId, allMessages)
                Log.d("WorkspaceViewModel", "Persisted ${allMessages.size} messages from _agentTabs before session switch")
            }
        } catch (e: Exception) {
            Log.e("WorkspaceViewModel", "Failed to persist messages before session switch", e)
        }
    }

    /**
     * 保存所有 Agent 的历史对话消息至数据库。
     */
    private suspend fun persistAllAgentMessages(workspaceSessionId: Long, snapshot: TeamCompletionSnapshot) {
        try {
            val instances = repository.getAgentInstancesByWorkspaceSession(workspaceSessionId)

            var orchestratorInstance = instances.find { it.isOrchestrator }
            if (orchestratorInstance == null) {
                val allConfigs = repository.getAllConfigs()
                val configId = allConfigs.firstOrNull()?.id ?: 1L
                orchestratorInstance = AgentInstance(
                    workspaceSessionId = workspaceSessionId,
                    agentName = ORCHESTRATOR_NAME,
                    isOrchestrator = true,
                    systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
                    modelConfigId = configId
                )
                val id = repository.insertAgentInstance(orchestratorInstance)
                if (id <= 0) {
                    Log.e("WorkspaceViewModel", "Failed to insert Orchestrator AgentInstance (id=$id), skipping message persistence")
                    return
                }
                orchestratorInstance = orchestratorInstance.copy(id = id)
            }

            val allMessages = mutableListOf<WorkspaceMessage>()

            for (msg in snapshot.orchestratorMessages) {
                allMessages.add(WorkspaceMessage(
                    workspaceSessionId = workspaceSessionId,
                    agentInstanceId = orchestratorInstance.id,
                    role = msg.role,
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    toolCallsJson = msg.toolCallsJson,
                    isIntervention = msg.isIntervention,
                    timestamp = msg.timestamp
                ))
            }

            for ((agentName, messages) in snapshot.subAgentMessages) {
                if (messages.isEmpty()) continue

                val existingInstance = instances.find { it.agentName == agentName }

                val agentInstance = existingInstance ?: run {
                    val newInstance = AgentInstance(
                        workspaceSessionId = workspaceSessionId,
                        agentName = agentName,
                        isOrchestrator = false,
                        systemPrompt = "",
                        modelConfigId = instances.find { i -> i.isOrchestrator }?.modelConfigId ?: 1L
                    )
                    val id = repository.insertAgentInstance(newInstance)
                    if (id <= 0) {
                        Log.e("WorkspaceViewModel", "Failed to insert AgentInstance for $agentName (id=$id), skipping")
                        return@run null
                    }
                    newInstance.copy(id = id)
                } ?: continue

                for (msg in messages) {
                    allMessages.add(WorkspaceMessage(
                        workspaceSessionId = workspaceSessionId,
                        agentInstanceId = agentInstance.id,
                        role = msg.role,
                        content = msg.content,
                        toolCallId = msg.toolCallId,
                        toolCallsJson = msg.toolCallsJson,
                        isIntervention = msg.isIntervention,
                        timestamp = msg.timestamp
                    ))
                }
            }

            repository.replaceWorkspaceMessages(workspaceSessionId, allMessages)

            Log.d("WorkspaceViewModel", "Persisted ${snapshot.orchestratorMessages.size} orchestrator + ${snapshot.subAgentMessages.values.sumOf { it.size }} sub-agent messages")
        } catch (e: Exception) {
            Log.e("WorkspaceViewModel", "Failed to persist agent messages", e)
        }
    }

    /**
     * 加载已完成会话的 Orchestrator 历史消息。
     */
    private suspend fun loadCompletedSessionHistory(workspaceSessionId: Long) {
        val agentInstances = repository.getAgentInstancesByWorkspaceSession(workspaceSessionId)

        if (agentInstances.isEmpty()) {
            _completedOrchestratorMessages.value = emptyList()
            _agentTabs.value = emptyList()
            _agentStreamBuffers.value = emptyMap()
            _agentStatuses.value = emptyMap()
            return
        }

        val tabs = agentInstances.map { instance ->
            val messages = repository.getWorkspaceMessagesByAgent(instance.id).map { wsMsg ->
                AgentMessage(
                    role = wsMsg.role,
                    content = wsMsg.content,
                    toolCallId = wsMsg.toolCallId,
                    toolCallsJson = wsMsg.toolCallsJson,
                    isIntervention = wsMsg.isIntervention,
                    timestamp = wsMsg.timestamp
                )
            }
            AgentTabState(
                agentName = instance.agentName,
                isOrchestrator = instance.isOrchestrator,
                status = AgentStatus.COMPLETED,
                messages = messages
            )
        }

        _agentTabs.value = tabs.sortedByDescending { it.isOrchestrator }

        val orchestratorInstance = agentInstances.find { it.isOrchestrator }
        if (orchestratorInstance != null) {
            _completedOrchestratorMessages.value = repository.getWorkspaceMessagesByAgent(orchestratorInstance.id)
        } else {
            _completedOrchestratorMessages.value = emptyList()
        }

        _agentStreamBuffers.value = emptyMap()
        _agentStatuses.value = agentInstances.associate { it.agentName to AgentStatus.COMPLETED }
    }

    // ── TeamManager 工厂 ────────────────────────────────────────────────────

    /**
     * 创建 TeamManager 实例，绑定统一的回调定义。
     */
    private fun createTeamManager(wsId: Long): TeamManager {
        val runtimeManager = com.example.mcp.McpRuntimeManager.getInstance(getApplication())
        var managerRef: TeamManager? = null
        val teamScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val manager = TeamManager(
            repository = repository,
            mcpRuntimeManager = runtimeManager,
            parentScope = teamScope,
            callbacks = TeamCallbacks(
                onAgentCreated = { agentName, isOrchestrator ->
                    if (!isRestoringSession && isOrchestrator) {
                        val newTab = AgentTabState(
                            agentName = agentName,
                            isOrchestrator = true,
                            status = AgentStatus.IDLE,
                            messages = emptyList()
                        )
                        _agentTabs.value = listOf(newTab)
                    }
                },
                onStreamChunk = { agentName, chunk ->
                    // Only update stream buffer for agents with tabs (Orchestrator).
                    // SubAgent streams are captured by AgentTool and shown inline.
                    if (_agentTabs.value.any { it.agentName == agentName }) {
                        _agentStreamBuffers.update { it.toMutableMap().apply { put(agentName, chunk) } }
                    }
                },
                onMessageAdded = { agentName, message ->
                    val hasTab = _agentTabs.value.any { it.agentName == agentName }
                    if (hasTab) {
                        _agentTabs.update { tabs ->
                            tabs.map { tab ->
                                if (tab.agentName == agentName) {
                                    tab.copy(messages = tab.messages + message)
                                } else tab
                            }
                        }
                    }
                },
                onAgentStatusChanged = { agentName, status ->
                    _agentStatuses.update { it.toMutableMap().apply { put(agentName, status) } }
                    val hasTab = _agentTabs.value.any { it.agentName == agentName }
                    if (hasTab) {
                        if (status == AgentStatus.IDLE || status == AgentStatus.COMPLETED) {
                            val history = managerRef?.getAgentHistory(agentName) ?: emptyList()
                            _agentTabs.update { tabs ->
                                tabs.map { tab ->
                                    if (tab.agentName == agentName) {
                                        val newMessages = history.ifEmpty { tab.messages }
                                        tab.copy(status = status, messages = newMessages)
                                    } else tab
                                }
                            }
                            _agentStreamBuffers.update { it.toMutableMap().apply { remove(agentName) } }

                            if (status == AgentStatus.COMPLETED && history.isNotEmpty()) {
                                viewModelScope.launch {
                                    try {
                                        val currentWsId = _selectedWorkspaceId.value ?: return@launch
                                        val instances = repository.getAgentInstancesByWorkspaceSession(currentWsId)
                                        val instance = instances.find { it.agentName == agentName } ?: return@launch
                                        val messages = history.map { msg ->
                                            WorkspaceMessage(
                                                workspaceSessionId = currentWsId,
                                                agentInstanceId = instance.id,
                                                role = msg.role,
                                                content = msg.content,
                                                toolCallId = msg.toolCallId,
                                                toolCallsJson = msg.toolCallsJson,
                                                isIntervention = msg.isIntervention,
                                                timestamp = msg.timestamp
                                            )
                                        }
                                        repository.replaceAgentMessages(currentWsId, instance.id, messages)
                                        Log.d("WorkspaceViewModel", "Eagerly persisted ${messages.size} messages for $agentName on COMPLETED")
                                    } catch (e: Exception) {
                                        Log.w("WorkspaceViewModel", "Eager persist for $agentName failed (non-fatal)", e)
                                    }
                                }
                            }
                        } else {
                            _agentTabs.update { tabs ->
                                tabs.map { tab ->
                                    if (tab.agentName == agentName) {
                                        tab.copy(status = status)
                                    } else tab
                                }
                            }
                        }
                    }
                    // SubAgents without tabs are tracked via teamState.activeSubAgents (updated by TeamManager)
                },
                onWorkspaceComplete = { snapshot ->
                    viewModelScope.launch {
                        try {
                            _agentTabs.update { tabs ->
                                tabs.map { tab ->
                                    val snapshotMessages = when {
                                        tab.isOrchestrator ->
                                            snapshot.orchestratorMessages
                                        else ->
                                            snapshot.subAgentMessages[tab.agentName]
                                                ?: tab.messages
                                    }
                                    tab.copy(
                                        status = AgentStatus.COMPLETED,
                                        messages = snapshotMessages.ifEmpty { tab.messages }
                                    )
                                }
                            }

                            persistAllAgentMessages(wsId, snapshot)

                            val firstUserMsg = snapshot.orchestratorMessages.find { it.role == "user" }?.content ?: "新工作区"
                            val title = firstUserMsg.trim().replace(Regex("\\s+"), " ").take(20)
                            repository.updateWorkspaceSessionTitle(wsId, title)

                            repository.updateWorkspaceSessionStatus(wsId, isActive = false, lastActiveAt = System.currentTimeMillis())

                            val updatedSession = repository.getWorkspaceSessionById(wsId)
                            if (updatedSession != null) {
                                _selectedWorkspaceSession.value = updatedSession
                            }

                            WorkspaceForegroundService.complete(
                                getApplication(),
                                "任务已完成：$title"
                            )

                            val completedManager = managerRef
                            if (teamManager === completedManager) {
                                teamManager = null
                                com.example.mcp.BuiltinToolHandler.teamManager = null
                            }
                            completedManager?.deleteTeam()
                        } catch (e: Exception) {
                            Log.e("WorkspaceViewModel", "Failed to persist workspace session complete state", e)
                        }
                    }
                },
                onError = { errMsg ->
                    Log.e("WorkspaceViewModel", "Orchestrator error: $errMsg")
                    val errorSystemMsg = AgentMessage(
                        role = "system",
                        content = "⚠️ $errMsg",
                        timestamp = System.currentTimeMillis()
                    )
                    _agentTabs.update { tabs ->
                        tabs.map { tab ->
                            if (tab.isOrchestrator) {
                                tab.copy(messages = tab.messages + errorSystemMsg)
                            } else tab
                        }
                    }
                }
            ),
        )
        managerRef = manager
        com.example.mcp.BuiltinToolHandler.teamManager = manager
        return manager
    }

    /**
     * 生成团队名称，统一 "workspace_$wsId" 模式。
     */
    private fun teamNameForSession(wsId: Long): String = "workspace_$wsId"

    // ── 初始化 ─────────────────────────────────────────────────────────────

    init {
        // 初始化时可以选择自动选中第一个工作区会话
        // 当前实现：不自动选中，等待用户手动选择
    }

    override fun onCleared() {
        super.onCleared()
        AskUserManager.clearAll()
        com.example.mcp.BuiltinToolHandler.teamManager = null
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.SupervisorJob()) {
            try {
                persistCurrentSessionMessages(teamManager)
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Persist failed in onCleared", e)
            }
            try {
                withContext(NonCancellable) {
                    teamManager?.deleteTeam()
                }
            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Cleanup failed in onCleared", e)
            }
        }
    }
}


/**
 * Agent Tab 状态数据类。
 *
 * 需求 7.1、7.6：用于 Tab 栏展示和消息列表展示。
 *
 * @property agentName Agent 名称
 * @property isOrchestrator 是否为主控 Agent
 * @property status 当前状态
 * @property messages 内存中的消息列表（活跃会话）或 DB 加载的消息（已完成会话）
 */
data class AgentTabState(
    val agentName: String,
    val isOrchestrator: Boolean,
    val status: AgentStatus,
    val messages: List<AgentMessage>
)
