package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.workspace.*
import com.example.workspace.TeamCompletionSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 多 Agent 工作区 ViewModel。
 *
 * 管理工作区会话列表、Agent Tab 状态、流式输出缓冲和 Agent 预设。
 * 继承 AndroidViewModel 以访问 Application context，直接实例化 AppDatabase / AppRepository。
 *
 * 使用 [TeamManager] 替代 [WorkspaceOrchestrator]，通过回调定义一次消除重复代码。
 *
 * 需求：2.1、2.2、7.1
 */
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private val messageBus = MessageBus()
    private val taskManager = TaskManager(repository.teamTaskDao)
    private var teamManager: TeamManager? = null

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
     * 每个 Agent 的流式输出缓冲（agentName → 累积文本）。
     *
     * 需求 7.2：流式输出以不超过 50ms 的刷新间隔实时追加渲染。
     */
    private val _agentStreamBuffers = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentStreamBuffers: StateFlow<Map<String, String>> = _agentStreamBuffers.asStateFlow()

    /**
     * 每个 Agent 的状态（agentName → AgentStatus）。
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
     * 团队状态（来自 TeamManager 的 StateFlow）。
     *
     * 包含所有 Teammate 的运行时状态，用于 UI 展示 Agent 颜色、任务面板等。
     */
    private val _teamState = MutableStateFlow<TeamState?>(null)
    val teamState: StateFlow<TeamState?> = _teamState.asStateFlow()

    /**
     * 当前工作区的任务列表。
     *
     * 通过 TaskManager 观察数据库中的 team_tasks 表，
     * 展示 PENDING / IN_PROGRESS / COMPLETED / FAILED 状态。
     */
    private val _teamTasks = MutableStateFlow<List<TeamTask>>(emptyList())
    val teamTasks: StateFlow<List<TeamTask>> = _teamTasks.asStateFlow()

    // ── 公共方法 ───────────────────────────────────────────────────────────

    /**
     * 清除错误消息。
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
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
                _teamTasks.value = emptyList()
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
        viewModelScope.launch {
            // 切换前持久化当前活跃会话的消息，避免切回时丢失
            persistCurrentSessionMessages()

            val session = repository.getWorkspaceSessionById(id)
            if (session != null) {
                _selectedWorkspaceId.value = id
                _selectedWorkspaceSession.value = session
                loadSessionHistory(id, session.isActive)
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
                // 清理 TeamManager 资源（先捕获引用，避免竞争）
                val oldManager = teamManager
                teamManager = null
                oldManager?.deleteTeam()

                repository.deleteWorkspaceSession(id)

                // 如果删除的是当前选中的会话，清除选中状态
                if (_selectedWorkspaceId.value == id) {
                    _selectedWorkspaceId.value = null
                    _selectedWorkspaceSession.value = null
                    _agentTabs.value = emptyList()
                    _agentStreamBuffers.value = emptyMap()
                    _agentStatuses.value = emptyMap()
                    _completedOrchestratorMessages.value = emptyList()
                    _teamState.value = null
                    _teamTasks.value = emptyList()
                }
            } catch (e: Exception) {
                // 删除失败时可以选择显示错误消息
                // 当前实现：静默失败，日志记录
                // _errorMessage.value = "删除工作区失败，请重试"
            }
        }
    }

    /**
     * 提交任务，启动多 Agent 协作工作区。
     *
     * 通过 [TeamManager] 创建团队并启动 Orchestrator 执行循环。
     * 回调定义集中在此处，不再重复。
     *
     * 需求 4.1、4.5、5.4、5.5、7.2、7.4、7.5、9.1、9.2、9.5、10.6
     */
    fun submitTask(task: String, orchestratorModelConfigId: Long) {
        val wsId = selectedWorkspaceId.value ?: return
        val currentSession = selectedWorkspaceSession.value ?: return
        if (!currentSession.isActive) return

        viewModelScope.launch {
            try {
                // 1. 获取 Orchestrator 的 modelConfig
                val allConfigs = repository.getAllConfigs()
                val orchestratorConfig = repository.getConfigById(orchestratorModelConfigId)
                    ?: allConfigs.find { it.isDefaultProvider } // fallback to default
                    ?: allConfigs.firstOrNull()
                    ?: throw IllegalStateException("No model configs available")

                // 2. 获取 presets
                val presets = repository.getAllAgentPresets()

                // 3. 重置状态
                _agentStreamBuffers.value = emptyMap()
                _agentStatuses.value = emptyMap()
                _agentTabs.value = emptyList()

                // 4. 清理旧 TeamManager（先捕获引用，避免 deleteTeam 完成前被置 null 的竞争）
                val oldManager = teamManager
                teamManager = null
                oldManager?.deleteTeam()

                // 4.5 清理 MessageBus 残留消息，避免旧 ResultReport 干扰新会话
                messageBus.clear()

                // 5. 创建 TeamManager 并启动执行
                teamManager = createTeamManager(wsId)

                // 收集 teamState 到 VM 层供 UI 使用
                viewModelScope.launch {
                    teamManager?.teamState?.collect { state ->
                        _teamState.value = state
                    }
                }

                // 观察当前工作区的任务列表
                viewModelScope.launch {
                    taskManager.observeTasks("workspace_$wsId").collect { tasks ->
                        _teamTasks.value = tasks
                    }
                }

                teamManager?.createTeam("workspace_$wsId", orchestratorConfig, presets)
                teamManager?.startExecution(task)

            } catch (e: Exception) {
                Log.e("WorkspaceViewModel", "Failed to start workspace execution", e)
                _errorMessage.value = "启动工作区执行失败，请重试"
            }
        }
    }

    /**
     * 发送用户干预消息。
     *
     * 需求 8.2–8.5：
     * - 委托给 TeamManager 发送干预消息
     * - 立即更新 VM 的消息列表，以便 UI 能立即显示干预内容
     */
    fun sendIntervention(targetAgentName: String, message: String) {
        viewModelScope.launch {
            try {
                // 1. 委托给 TeamManager
                teamManager?.sendIntervention(targetAgentName, message)

                // 2. 立即向 UI 注入该消息，以快速响应用户
                val userMsg = AgentMessage(
                    role = "user",
                    content = message,
                    isIntervention = true,
                    timestamp = System.currentTimeMillis()
                )
                _agentTabs.value = _agentTabs.value.map { tab ->
                    if (tab.agentName == targetAgentName) {
                        tab.copy(messages = tab.messages + userMsg)
                    } else {
                        tab
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
     *
     * 包含 Orchestrator 和所有 Sub-Agent 的对话历史、工具调用、状态变更。
     * 兼容活跃会话（从 teamManager 读取）和已完成会话（从数据库读取）。
     *
     * @param context Android Context
     * @param uri SAF 文件选择器返回的 URI
     */
    fun exportWorkspaceLogs(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _exportLogStatus.value = ExportImportStatus.Loading

                val session = _selectedWorkspaceSession.value
                val state = _teamState.value
                val tasks = _teamTasks.value
                val wsId = _selectedWorkspaceId.value

                val log = buildWorkspaceLog(session, state, tasks, wsId)

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
     *
     * 优先从 teamManager 读取（活跃会话），fallback 到数据库（已完成会话）。
     */
    private suspend fun buildWorkspaceLog(
        session: WorkspaceSession?,
        state: TeamState?,
        tasks: List<TeamTask>,
        wsId: Long?
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = dateFormat.format(Date())

        return buildString {
            // ── 头部 ──
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("  OmniChat 工作区日志")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()

            // ── 工作区信息 ──
            appendLine("【工作区信息】")
            appendLine("  导出时间: $now")
            if (session != null) {
                appendLine("  会话 ID: ${session.id}")
                appendLine("  标题: ${session.title}")
                appendLine("  状态: ${if (session.isActive) "运行中" else "已完成"}")
                appendLine("  创建时间: ${dateFormat.format(Date(session.createdAt))}")
            }
            appendLine()

            // ── 团队状态 ──
            if (state != null) {
                appendLine("【团队状态】")
                appendLine("  团队名: ${state.teamName}")
                appendLine("  Agent 数量: ${state.teammates.size}")
                for ((name, teammate) in state.teammates) {
                    val role = if (teammate.isOrchestrator) "Orchestrator" else "Sub-Agent"
                    appendLine("  - $name ($role) [${teammate.status}] color=${teammate.identity.color}")
                }
                appendLine()
            }

            // ── 任务列表 ──
            if (tasks.isNotEmpty()) {
                appendLine("【任务列表】")
                for (task in tasks.sortedBy { it.id }) {
                    val owner = task.owner ?: "未认领"
                    appendLine("  #${task.id} [${task.status}] ${task.subject}")
                    appendLine("    认领者: $owner")
                    if (task.description.isNotBlank()) {
                        appendLine("    描述: ${task.description.take(200)}")
                    }
                }
                appendLine()
            }

            // ── 从数据库加载 Agent 实例和消息 ──
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

                    // 同时尝试从 teamManager 读取（活跃会话可能有更多最新消息）
                    val liveHistory = teamManager?.getAgentHistory(instance.agentName)
                    val allMessages = if (!liveHistory.isNullOrEmpty()) liveHistory else messages

                    if (allMessages.isEmpty()) continue

                    val roleLabel = if (instance.isOrchestrator) "Orchestrator" else "Sub-Agent"
                    val status = state?.teammates?.get(instance.agentName)?.status
                    val statusTag = if (status != null) "  状态: $status" else ""

                    appendLine()
                    appendLine("═══════════════════════════════════════════════════════════════")
                    appendLine("  $roleLabel: ${instance.agentName} (${allMessages.size} 条消息)")
                    if (statusTag.isNotEmpty()) appendLine(statusTag)
                    appendLine("═══════════════════════════════════════════════════════════════")
                    appendLine()
                    for ((index, msg) in allMessages.withIndex()) {
                        appendMessage(index, msg, dateFormat)
                    }
                }

                // 如果数据库没有 Agent 实例（可能是旧数据），fallback 到 teamManager
                if (agentInstances.isEmpty()) {
                    appendAgentHistoriesFromLive(state, dateFormat)
                }
            } else {
                // 没有 wsId，只能从 teamManager 读取
                appendAgentHistoriesFromLive(state, dateFormat)
            }

            // ── 尾部 ──
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
        // Orchestrator
        val orchestratorHistory = teamManager?.getAgentHistory(TeamManager.ORCHESTRATOR_NAME)
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

        // Sub-Agents
        val subAgents = state?.teammates?.values?.filter { !it.isOrchestrator } ?: emptyList()
        for (teammate in subAgents) {
            val name = teammate.identity.agentName
            val history = teamManager?.getAgentHistory(name)
            if (history.isNullOrEmpty()) continue

            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("  Sub-Agent: $name (${history.size} 条消息)")
            appendLine("  状态: ${teammate.status}")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()
            for ((index, msg) in history.withIndex()) {
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

        // 消息内容（缩进处理）
        val content = msg.content.trim()
        if (content.isNotEmpty()) {
            val maxLen = 2000 // 截断过长内容
            val truncated = if (content.length > maxLen) {
                content.take(maxLen) + "\n    ... (截断，共 ${content.length} 字符)"
            } else {
                content
            }
            for (line in truncated.lines()) {
                appendLine("    $line")
            }
        }

        // 工具调用信息
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
            // 活跃会话状态还原
            val agentInstances = repository.getAgentInstancesByWorkspaceSession(workspaceSessionId)
            val orchestratorInstance = agentInstances.find { it.isOrchestrator }

            val orchestratorHistory = if (orchestratorInstance != null) {
                repository.getWorkspaceMessagesByAgent(orchestratorInstance.id).map { wsMsg ->
                    AgentMessage(
                        role = wsMsg.role,
                        content = wsMsg.content,
                        toolCallId = wsMsg.toolCallId,
                        toolCallsJson = wsMsg.toolCallsJson,
                        isIntervention = wsMsg.isIntervention,
                        timestamp = wsMsg.timestamp
                    )
                }
            } else {
                emptyList()
            }

            // 根据保存的 Agent 实例构建 Tab，Sub-Agent 历史在内存中是空的，只有 Orchestrator 有历史
            _agentTabs.value = agentInstances.map { instance ->
                AgentTabState(
                    agentName = instance.agentName,
                    isOrchestrator = instance.isOrchestrator,
                    status = if (instance.isOrchestrator) AgentStatus.IDLE else AgentStatus.COMPLETED,
                    messages = if (instance.isOrchestrator) orchestratorHistory else emptyList()
                )
            }

            // 重置流式缓冲与状态
            _agentStreamBuffers.value = emptyMap()
            _agentStatuses.value = agentInstances.associate { it.agentName to (if (it.isOrchestrator) AgentStatus.IDLE else AgentStatus.COMPLETED) }

            // 清理旧 TeamManager 并重建（先捕获引用，避免竞争）
            val oldManager = teamManager
            teamManager = null
            oldManager?.deleteTeam()

            // 清理 MessageBus 残留消息
            messageBus.clear()

            if (orchestratorInstance != null) {
                val allConfigs = repository.getAllConfigs()
                val orchestratorConfig = repository.getConfigById(orchestratorInstance.modelConfigId)
                    ?: allConfigs.find { it.isDefaultProvider }
                    ?: allConfigs.firstOrNull()

                if (orchestratorConfig != null) {
                    val presets = repository.getAllAgentPresets()

                    teamManager = createTeamManager(workspaceSessionId)

                    // 收集 teamState 到 VM 层供 UI 使用
                    viewModelScope.launch {
                        teamManager?.teamState?.collect { state ->
                            _teamState.value = state
                        }
                    }

                    // 观察当前工作区的任务列表
                    viewModelScope.launch {
                        taskManager.observeTasks("workspace_$workspaceSessionId").collect { tasks ->
                            _teamTasks.value = tasks
                        }
                    }

                    // 创建团队并启动 Orchestrator 等待新用户输入
                    teamManager?.createTeam("workspace_$workspaceSessionId", orchestratorConfig, presets)
                    // 在后台启动 Orchestrator 循环，等待用户干预或新输入
                    viewModelScope.launch {
                        try {
                            teamManager?.startExecution("")
                        } catch (e: Exception) {
                            Log.e("WorkspaceViewModel", "Failed to restore orchestrator loop", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WorkspaceViewModel", "Error restoring active workspace session history", e)
        }
    }

    /**
     * 持久化当前活跃会话的消息到数据库。
     *
     * 在切换会话前调用，确保活跃会话的 Orchestrator 和 Sub-Agent 消息不会因 teamManager 销毁而丢失。
     * 如果当前无活跃会话或 teamManager 为空，跳过。
     */
    private suspend fun persistCurrentSessionMessages() {
        val wsId = _selectedWorkspaceId.value ?: return
        val session = _selectedWorkspaceSession.value ?: return
        if (!session.isActive) return

        val manager = teamManager ?: return

        try {
            // 获取或创建 Orchestrator 的 AgentInstance
            val instances = repository.getAgentInstancesByWorkspaceSession(wsId)
            var orchestratorInstance = instances.find { it.isOrchestrator }

            if (orchestratorInstance == null) {
                val allConfigs = repository.getAllConfigs()
                val configId = allConfigs.firstOrNull()?.id ?: 1L
                orchestratorInstance = AgentInstance(
                    workspaceSessionId = wsId,
                    agentName = TeamManager.ORCHESTRATOR_NAME,
                    isOrchestrator = true,
                    systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
                    modelConfigId = configId
                )
                val id = repository.insertAgentInstance(orchestratorInstance)
                orchestratorInstance = orchestratorInstance.copy(id = id)
            }

            // 持久化 Orchestrator 消息（先清除旧的，再插入全量）
            repository.deleteWorkspaceMessagesBySession(wsId)
            val orchestratorHistory = manager.getAgentHistory(TeamManager.ORCHESTRATOR_NAME)
            for (msg in orchestratorHistory) {
                val wsMsg = WorkspaceMessage(
                    workspaceSessionId = wsId,
                    agentInstanceId = orchestratorInstance.id,
                    role = msg.role,
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    toolCallsJson = msg.toolCallsJson,
                    isIntervention = msg.isIntervention,
                    timestamp = msg.timestamp
                )
                repository.insertWorkspaceMessage(wsMsg)
            }

            // 持久化 Sub-Agent 消息
            val state = _teamState.value
            val subAgents = state?.teammates?.values?.filter { !it.isOrchestrator } ?: emptyList()
            for (teammate in subAgents) {
                val name = teammate.identity.agentName
                val history = manager.getAgentHistory(name)
                if (history.isEmpty()) continue

                val existingInstance = instances.find { it.agentName == name }
                val agentInstance = existingInstance ?: run {
                    val newInstance = AgentInstance(
                        workspaceSessionId = wsId,
                        agentName = name,
                        isOrchestrator = false,
                        systemPrompt = "",
                        modelConfigId = orchestratorInstance.modelConfigId
                    )
                    val id = repository.insertAgentInstance(newInstance)
                    newInstance.copy(id = id)
                }

                for (msg in history) {
                    val wsMsg = WorkspaceMessage(
                        workspaceSessionId = wsId,
                        agentInstanceId = agentInstance.id,
                        role = msg.role,
                        content = msg.content,
                        toolCallId = msg.toolCallId,
                        toolCallsJson = msg.toolCallsJson,
                        isIntervention = msg.isIntervention,
                        timestamp = msg.timestamp
                    )
                    repository.insertWorkspaceMessage(wsMsg)
                }
            }

            Log.d("WorkspaceViewModel", "Persisted messages before session switch: ${orchestratorHistory.size} orchestrator + ${subAgents.sumOf { manager.getAgentHistory(it.identity.agentName).size }} sub-agent")
        } catch (e: Exception) {
            Log.e("WorkspaceViewModel", "Failed to persist messages before session switch", e)
        }
    }

    /**
     * 保存所有 Agent 的历史对话消息至数据库（包括 Sub-Agent）。
     *
     * Orchestrator 消息持久化到 workspace_messages 表；
     * Sub-Agent 消息也持久化，以便后续查看工作区执行详情。
     *
     * @param workspaceSessionId 工作区会话 ID
     * @param snapshot 工作区完成时的全量消息快照
     */
    private suspend fun persistAllAgentMessages(workspaceSessionId: Long, snapshot: TeamCompletionSnapshot) {
        try {
            val instances = repository.getAgentInstancesByWorkspaceSession(workspaceSessionId)

            // 1. 持久化 Orchestrator 消息
            var orchestratorInstance = instances.find { it.isOrchestrator }
            if (orchestratorInstance == null) {
                val allConfigs = repository.getAllConfigs()
                val configId = allConfigs.firstOrNull()?.id ?: 1L
                orchestratorInstance = AgentInstance(
                    workspaceSessionId = workspaceSessionId,
                    agentName = TeamManager.ORCHESTRATOR_NAME,
                    isOrchestrator = true,
                    systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
                    modelConfigId = configId
                )
                val id = repository.insertAgentInstance(orchestratorInstance)
                orchestratorInstance = orchestratorInstance.copy(id = id)
            }

            // 清除旧消息后重新插入
            repository.deleteWorkspaceMessagesBySession(workspaceSessionId)

            // 插入 Orchestrator 消息
            for (msg in snapshot.orchestratorMessages) {
                val wsMsg = WorkspaceMessage(
                    workspaceSessionId = workspaceSessionId,
                    agentInstanceId = orchestratorInstance.id,
                    role = msg.role,
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    toolCallsJson = msg.toolCallsJson,
                    isIntervention = msg.isIntervention,
                    timestamp = msg.timestamp
                )
                repository.insertWorkspaceMessage(wsMsg)
            }

            // 2. 持久化 Sub-Agent 消息
            for ((agentName, messages) in snapshot.subAgentMessages) {
                if (messages.isEmpty()) continue

                val identity = snapshot.subAgentIdentities[agentName]
                val existingInstance = instances.find { it.agentName == agentName }

                // 创建或复用 AgentInstance 记录
                val agentInstance = existingInstance ?: run {
                    val newInstance = AgentInstance(
                        workspaceSessionId = workspaceSessionId,
                        agentName = agentName,
                        isOrchestrator = false,
                        systemPrompt = "",
                        modelConfigId = snapshot.orchestratorMessages.let {
                            // 使用 Orchestrator 的 modelConfigId 作为 fallback
                            instances.find { i -> i.isOrchestrator }?.modelConfigId ?: 1L
                        }
                    )
                    val id = repository.insertAgentInstance(newInstance)
                    newInstance.copy(id = id)
                }

                // 插入 Sub-Agent 消息
                for (msg in messages) {
                    val wsMsg = WorkspaceMessage(
                        workspaceSessionId = workspaceSessionId,
                        agentInstanceId = agentInstance.id,
                        role = msg.role,
                        content = msg.content,
                        toolCallId = msg.toolCallId,
                        toolCallsJson = msg.toolCallsJson,
                        isIntervention = msg.isIntervention,
                        timestamp = msg.timestamp
                    )
                    repository.insertWorkspaceMessage(wsMsg)
                }
            }

            Log.d("WorkspaceViewModel", "Persisted ${snapshot.orchestratorMessages.size} orchestrator + ${snapshot.subAgentMessages.values.sumOf { it.size }} sub-agent messages")
        } catch (e: Exception) {
            Log.e("WorkspaceViewModel", "Failed to persist agent messages", e)
        }
    }

    /**
     * 加载已完成会话的 Orchestrator 历史消息。
     *
     * 从数据库加载该会话中 Orchestrator 的所有消息，用于重新打开已完成的工作区会话时展示。
     * 同时设置 Agent Tab 状态，仅显示 Orchestrator Tab。
     *
     * 需求 9.4：重新打开已完成的工作区会话时，仅展示 Orchestrator 的历史对话记录。
     *
     * @param workspaceSessionId 工作区会话 ID
     */
    private suspend fun loadCompletedSessionHistory(workspaceSessionId: Long) {
        // 获取该会话的所有 Agent 实例
        val agentInstances = repository.getAgentInstancesByWorkspaceSession(workspaceSessionId)

        if (agentInstances.isEmpty()) {
            _completedOrchestratorMessages.value = emptyList()
            _agentTabs.value = emptyList()
            _agentStreamBuffers.value = emptyMap()
            _agentStatuses.value = emptyMap()
            return
        }

        // 构建所有 Agent 的 Tab（包括 Sub-Agent）
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

        // Orchestrator Tab 排在最前面
        _agentTabs.value = tabs.sortedByDescending { it.isOrchestrator }

        // 设置 Orchestrator 历史消息（向后兼容）
        val orchestratorInstance = agentInstances.find { it.isOrchestrator }
        if (orchestratorInstance != null) {
            _completedOrchestratorMessages.value = repository.getWorkspaceMessagesByAgent(orchestratorInstance.id)
        } else {
            _completedOrchestratorMessages.value = emptyList()
        }

        // 清空流式缓冲，所有 Agent 状态为 COMPLETED
        _agentStreamBuffers.value = emptyMap()
        _agentStatuses.value = agentInstances.associate { it.agentName to AgentStatus.COMPLETED }
    }

    // ── TeamManager 工厂 ────────────────────────────────────────────────────

    /**
     * 创建 TeamManager 实例，绑定统一的回调定义。
     *
     * 消除 submitTask() 和 loadSessionHistory() 中 ~90 行重复的回调代码。
     *
     * @param wsId 工作区会话 ID，用于 onWorkspaceComplete 中持久化
     */
    private fun createTeamManager(wsId: Long): TeamManager {
        val runtimeManager = com.example.mcp.McpRuntimeManager.getInstance(getApplication())
        return TeamManager(
            repository = repository,
            mcpRuntimeManager = runtimeManager,
            messageBus = messageBus,
            taskManager = taskManager,
            parentScope = viewModelScope,
            onAgentCreated = { agentName, isOrchestrator ->
                val newTab = AgentTabState(
                    agentName = agentName,
                    isOrchestrator = isOrchestrator,
                    status = AgentStatus.IDLE,
                    messages = emptyList()
                )
                _agentTabs.value = if (isOrchestrator) {
                    listOf(newTab)
                } else {
                    _agentTabs.value.filter { it.agentName != agentName } + newTab
                }
            },
            onStreamChunk = { agentName, chunk ->
                _agentStreamBuffers.value = _agentStreamBuffers.value.toMutableMap().apply {
                    put(agentName, chunk)
                }
            },
            onAgentStatusChanged = { agentName, status ->
                _agentStatuses.value = _agentStatuses.value.toMutableMap().apply {
                    put(agentName, status)
                }
                if (status == AgentStatus.COMPLETED) {
                    val history = teamManager?.getAgentHistory(agentName) ?: emptyList()
                    _agentTabs.value = _agentTabs.value.map { tab ->
                        if (tab.agentName == agentName) {
                            tab.copy(status = status, messages = history)
                        } else {
                            tab
                        }
                    }
                } else {
                    _agentTabs.value = _agentTabs.value.map { tab ->
                        if (tab.agentName == agentName) {
                            tab.copy(status = status)
                        } else {
                            tab
                        }
                    }
                }
            },
            onWorkspaceComplete = { snapshot ->
                viewModelScope.launch {
                    try {
                        persistAllAgentMessages(wsId, snapshot)

                        val firstUserMsg = snapshot.orchestratorMessages.find { it.role == "user" }?.content ?: "新工作区"
                        val title = firstUserMsg.trim().replace(Regex("\\s+"), " ").take(20)
                        repository.updateWorkspaceSessionTitle(wsId, title)

                        repository.updateWorkspaceSessionStatus(wsId, isActive = false, lastActiveAt = System.currentTimeMillis())

                        val updatedSession = repository.getWorkspaceSessionById(wsId)
                        if (updatedSession != null) {
                            _selectedWorkspaceSession.value = updatedSession
                        }

                        // 先捕获引用再置 null，避免与 submitTask 的竞争
                        val completedManager = teamManager
                        teamManager = null
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
                _agentTabs.value = _agentTabs.value.map { tab ->
                    if (tab.isOrchestrator) {
                        tab.copy(messages = tab.messages + errorSystemMsg)
                    } else tab
                }
            }
        )
    }

    // ── 初始化 ─────────────────────────────────────────────────────────────

    init {
        // 初始化时可以选择自动选中第一个工作区会话
        // 当前实现：不自动选中，等待用户手动选择
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
