package com.omnichat.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnichat.data.*
import com.omnichat.ui.presentation.ChatDisplayState
import com.omnichat.ui.presentation.buildChatDisplayState
import com.omnichat.network.ApiClient
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.omnichat.R
import com.omnichat.mcp.AskUserManager
import com.omnichat.StreamingForegroundService
import com.omnichat.BuildConfig
import com.omnichat.update.UpdateChecker
import com.omnichat.agent.SubAgentEvent
import com.omnichat.agent.SubAgentEventBus
import com.omnichat.agent.WorkflowEngine
import com.omnichat.agent.WorkflowEvent
import com.omnichat.agent.WorkflowEventBus
import com.omnichat.agent.WorkflowMode
import com.omnichat.agent.WorkflowStatus
import com.omnichat.agent.WorkflowStepStatus
import com.omnichat.agent.WorkflowStepUiState
import com.omnichat.agent.WorkflowUiState
import com.omnichat.skill.SkillManager
import com.omnichat.tool.ProjectToolScope
import com.omnichat.tool.Tool
import com.omnichat.tool.ToolExecutor
import com.omnichat.tool.ToolRegistry
import com.omnichat.ui.screens.SubAgentTaskUiState
import com.omnichat.ui.screens.TaskStatus
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import java.io.File

private const val STREAMING_UI_UPDATE_INTERVAL_MS = 50L

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private val runtimeManager = com.omnichat.mcp.McpRuntimeManager.getInstance(application)
    private val memoryEngine = com.omnichat.memory.MemoryEngine(repository, ApiClient)
    val skillManager = SkillManager(application)

    // ── 项目系统 ────────────────────────────────────────────────────────
    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProjectId: StateFlow<Long?> = _selectedProjectId.asStateFlow()

    val projects: StateFlow<List<Project>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Project sessions flow
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val projectSessions: StateFlow<List<Session>> = _selectedProjectId
        .flatMapLatest { projectId ->
            if (projectId != null) {
                repository.getSessionsByProjectFlow(projectId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 非项目会话（普通会话）
    val nonProjectSessions: StateFlow<List<Session>> = repository.nonProjectSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active session selection state
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    // Sessions flow
    val sessions: StateFlow<List<Session>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 最近10条历史会话（懒加载，侧边栏初始显示用）
    val recentSessions: StateFlow<List<Session>> = repository.recentSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active chat messages flow
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeMessages: StateFlow<List<Message>> = _selectedSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessagesBySessionFlow(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Preprocesses the entire persisted message snapshot off the composition
    // path: tool-call JSON is parsed once, hidden tools are removed, and
    // contiguous tool outputs are grouped for the reverse-layout list.
    val chatDisplayState: StateFlow<ChatDisplayState> = combine(
        activeMessages,
        repository.uiSettings
    ) { messages, settings ->
        buildChatDisplayState(messages, settings?.silentToolGroups.orEmpty())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatDisplayState())

    // Model configurations flow
    val modelConfigs: StateFlow<List<ModelConfig>> = repository.allConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 版本更新检查 ────────────────────────────────────────────────────
    /** 最新可用版本号（null = 尚未检查或无新版本） */
    var latestVersion by mutableStateOf<String?>(null)
        private set
    /** 是否正在检查更新 */
    var isCheckingUpdate by mutableStateOf(false)
        private set

    // Memory items flow
    val memories: StateFlow<List<MemoryItem>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Memory audit history
    private val _auditHistory = MutableStateFlow<List<MemoryAuditEntry>>(emptyList())
    val auditHistory: StateFlow<List<MemoryAuditEntry>> = _auditHistory.asStateFlow()

    // System prompt templates flow
    val promptTemplates: StateFlow<List<PromptTemplate>> = repository.allTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // MCP server states — 用于 ChatView 显示 MCP 启动状态提示
    val mcpServerStates = runtimeManager.serverStates

    // Real-time operations UI state
    var isStreaming by mutableStateOf(false)
        private set

    var currentStreamingThinking by mutableStateOf("")
        private set

    var currentStreamingBody by mutableStateOf("")
        private set

    var isThinkingFinished by mutableStateOf(true)
        private set

    /** 正在进行流式输出的会话 ID，用于判断是否在当前会话显示 StreamingBubble */
    var streamingSessionId by mutableStateOf<Long?>(null)
        private set

    // Streaming job reference — used to cancel streaming when user taps stop
    private var streamingJob: Job? = null

    // Edit message state — holds the message ID being edited (null = not editing)
    var editingMessageId by mutableStateOf<Long?>(null)
        private set

    var isMemorySyncing by mutableStateOf(false)
        private set

    var isBackfillingTags by mutableStateOf(false)
        private set

    /** 是否正在执行记忆整合优化 */
    var isConsolidating by mutableStateOf(false)
        private set

    /** 上一次整合的结果摘要 */
    var lastConsolidationSummary by mutableStateOf<String?>(null)
        private set

    /** Active SubAgent tasks for current session — drives in-chat status cards */
    val activeTasks = mutableStateMapOf<String, SubAgentTaskUiState>()

    /** Active Workflows for current session — drives in-chat workflow progress cards */
    val activeWorkflows = mutableStateMapOf<String, WorkflowUiState>()

    /** SubAgent 执行期间锁定用户输入，仅允许终止操作 */
    var subAgentActive by mutableStateOf(false)
        private set

    // CONFLATED Channel 替代 Mutex：合并短时间内多次请求，单协程串行处理
    private val memorySyncChannel = Channel<Boolean>(Channel.CONFLATED)

    // Temporary list of models fetched from endpoints
    var fetchedModels by mutableStateOf<List<FetchedModel>>(emptyList())
        private set
    var modelFetchError by mutableStateOf<String?>(null)
        private set
    var isFetchingModels by mutableStateOf(false)
        private set

    /**
     * 当前选中模型是否支持视觉（图片输入）。
     * 优先从 fetchedModels 查找，找不到时默认 true（不阻止用户操作）。
     */
    var currentModelHasVision by mutableStateOf(true)
        private set

    fun refreshCurrentModelVision() {
        viewModelScope.launch {
            val provider = repository.getDefaultProvider() ?: return@launch
            val modelId = provider.selectedModelId.takeIf { it.isNotBlank() }
                ?: repository.getModelsByProvider(provider.id).firstOrNull()?.modelId
                ?: return@launch
            currentModelHasVision = fetchedModels.find { it.modelId == modelId }?.hasVision ?: true
        }
    }

    /** 切换当前使用的 Provider 和模型，持久化到数据库，重启后生效 */
    fun setSessionOverrideModel(provider: ModelConfig, modelId: String) {
        viewModelScope.launch {
            // 原子操作：清除旧默认 → 设置新默认 + 更新 selectedModelId，一步到位
            repository.setDefaultProviderWithModel(provider.id, modelId)
            refreshCurrentModelVision()
        }
    }

    init {
        viewModelScope.launch {
            // Check and Seed Database Safely off the main thread
            seedDatabaseIfNeeded()

            // 默认打开新的会话，不加载历史会话
            createNewSession(getApplication<Application>().getString(R.string.default_session_title_display))

            // 后台懒加载最近10条历史会话（通过 Room Flow 异步加载，侧边栏自动收到数据）
            // 当用户打开侧边栏时，历史会话已就绪，无需重新加载

            // 加载已有模型数据并刷新视觉能力状态
            fetchedModels = repository.getAllFetchedModels()
            refreshCurrentModelVision()

            // 初始化 Skill 系统：安装内置 Skill 并加载到注册表
            skillManager.initialize()


        }

        // 监听 AgentMode 权限审核请求：SubAgent 的破坏性操作由 MainAgent 审核
        viewModelScope.launch {
            com.omnichat.mcp.PermissionReviewManager.pendingReviews.collect { request ->
                handlePermissionReview(request)
            }
        }

        // 监听 SubAgent 生命周期事件：推送式结果交付，替代 timer 轮询
        viewModelScope.launch {
            SubAgentEventBus.events.collect { event ->
                handleSubAgentEvent(event)
            }
        }

        // 监听 Workflow 生命周期事件：管理 in-chat workflow 进度卡片
        viewModelScope.launch {
            WorkflowEventBus.events.collect { event ->
                handleWorkflowEvent(event)
            }
        }

        // 记忆同步后台消费者：CONFLATED Channel 保证串行处理+请求合并
        viewModelScope.launch {
            for (force in memorySyncChannel) {
                isMemorySyncing = true
                try {
                    executeMemorySync(force)
                } finally {
                    isMemorySyncing = false
                }
            }
        }

        // 检查版本更新
        checkForUpdate()
    }

    // ── 版本更新检查 ────────────────────────────────────────────────────

    fun checkForUpdate() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        viewModelScope.launch {
            try {
                val remote = UpdateChecker.fetchLatestVersion() ?: return@launch
                val local = BuildConfig.VERSION_NAME
                val ctx = getApplication<Application>()
                if (UpdateChecker.isNewer(local, remote) && !UpdateChecker.isDismissed(ctx, remote)) {
                    latestVersion = remote
                }
            } catch (_: Exception) {
                // 静默失败，不打扰用户
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    fun dismissUpdate() {
        val version = latestVersion ?: return
        val ctx = getApplication<Application>()
        UpdateChecker.dismiss(ctx, version)
        latestVersion = null
    }

    fun selectSession(sessionId: Long) {
        AskUserManager.clearAll()
        _selectedSessionId.value = sessionId
    }

    fun createNewSession(title: String) {
        viewModelScope.launch {
            val newSessionId = repository.insertSession(Session(title = title))
            _selectedSessionId.value = newSessionId
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_selectedSessionId.value == sessionId) {
                // 删完后自动选第一条，没有则新建一个
                val remaining = repository.allSessions.firstOrNull()?.firstOrNull()
                if (remaining != null) {
                    _selectedSessionId.value = remaining.id
                } else {
                    createNewSession(getApplication<Application>().getString(R.string.default_session_title))
                }
            }
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            repository.updateSessionTitle(sessionId, newTitle.trim())
        }
    }

    // ── 项目系统方法 ─────────────────────────────────────────────────────

    fun selectProject(projectId: Long?) {
        _selectedProjectId.value = projectId
        if (projectId == null) {
            // 切换到普通会话模式，选择第一个非项目会话
            viewModelScope.launch {
                val sessions = repository.nonProjectSessions.first()
                val first = sessions.firstOrNull()
                if (first != null) {
                    _selectedSessionId.value = first.id
                } else {
                    createNewSession(getApplication<Application>().getString(R.string.default_session_title))
                }
            }
        } else {
            // 切换到项目模式，选择项目中的第一个会话（不自动创建）
            viewModelScope.launch {
                val sessions = repository.getSessionsByProjectFlow(projectId).first()
                val first = sessions.firstOrNull()
                if (first != null) {
                    _selectedSessionId.value = first.id
                }
                // 不自动创建会话，由用户在项目详情页手动创建
            }
        }
    }

    fun createProject(name: String, description: String = "") {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val projectId = repository.insertProject(
                Project(name = name, description = description, createdAt = now, updatedAt = now)
            )
            // 创建项目 Memory 文件
            val memoryFile = File(getApplication<Application>().filesDir, "projects/$projectId/project_memory.md")
            memoryFile.parentFile?.mkdirs()
            memoryFile.writeText("""# Project: $name

$description

## Project Memory

This file stores persistent project context, guidelines, and notes.
You can read and modify this file using project_read_memory and project_update_memory tools.
""")
            // 自动切换到项目并创建第一个会话
            selectProject(projectId)
        }
    }

    suspend fun createProjectAndWait(name: String, description: String = ""): Long {
        val now = System.currentTimeMillis()
        val projectId = repository.insertProject(
            Project(name = name, description = description, createdAt = now, updatedAt = now)
        )
        val memoryFile = File(getApplication<Application>().filesDir, "projects/$projectId/project_memory.md")
        memoryFile.parentFile?.mkdirs()
        memoryFile.writeText("""# Project: $name

$description

## Project Memory

This file stores persistent project context, guidelines, and notes.
You can read and modify this file using project_read_memory and project_update_memory tools.
""")
        return projectId
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            val project = repository.getProjectById(projectId) ?: return@launch

            // 删除项目会话
            repository.deleteSessionsByProject(projectId)

            // 删除知识文件记录
            repository.deleteKnowledgeByProject(projectId)

            // 删除项目文件
            val projectDir = File(getApplication<Application>().filesDir, "projects/$projectId")
            if (projectDir.exists()) {
                projectDir.deleteRecursively()
            }

            // 删除项目
            repository.deleteProject(projectId)

            // 如果当前选中了该项目的会话，切回普通模式
            if (_selectedProjectId.value == projectId) {
                selectProject(null)
            }
        }
    }

    suspend fun createProjectSession(projectId: Long, title: String): Long {
        val newSessionId = repository.insertSession(
            Session(title = title, projectId = projectId)
        )
        _selectedSessionId.value = newSessionId
        return newSessionId
    }

    suspend fun createProjectSessionAndWait(projectId: Long, title: String): Long = createProjectSession(projectId, title)

    suspend fun buildPromptForSession(sessionId: Long): String {
        val activeTemplate = repository.getActiveTemplate()
        val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."
        return generateSystemPrompt(customSystemPrompt, "", sessionIdOverride = sessionId)
    }

    /**
     * 为指定会话构建项目工具作用域。
     * 如果会话不是项目会话，返回 null。
     */
    suspend fun projectScopeForSession(sessionId: Long): ProjectToolScope? {
        val session = repository.getSessionById(sessionId) ?: return null
        val projectId = session.projectId ?: return null
        val allowedMcpServerIds = runtimeManager.enabledServerIdsForProject(projectId)
        return ProjectToolScope(
            sessionId = sessionId,
            projectId = projectId,
            allowedMcpServerIds = allowedMcpServerIds
        )
    }

    /**
     * 为当前选中的会话构建项目工具作用域。
     */
    private suspend fun buildProjectToolScope(sessionId: Long): ProjectToolScope? {
        return projectScopeForSession(sessionId)
    }

    fun setThinkingEffort(sessionId: Long, effort: String) {
        viewModelScope.launch {
            repository.updateSessionThinkingEffort(sessionId, effort)
        }
    }

    fun toggleAgentMode() {
        viewModelScope.launch {
            val db = com.omnichat.data.AppDatabase.getDatabase(getApplication())
            val current = db.uiSettingsDao().getSettings() ?: com.omnichat.data.UISettings()
            db.uiSettingsDao().upsertSettings(current.copy(
                agentMode = !current.agentMode,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    /**
     * User actions: sends a message and starts streaming response using Primary Chat Model
     */
    fun sendMessage(text: String) {
        sendMessageWithImage(text)
    }

    /**
     * 发送带有图片的消息。
     *
     * @param text 文本内容
     * @param imagePaths 图片本地路径列表（可选）
     */
    fun sendMessageWithImage(text: String, imagePaths: List<String> = emptyList()) {
        val sessionId = _selectedSessionId.value ?: return
        if ((text.isBlank() && imagePaths.isEmpty()) || isStreaming || subAgentActive) return

        viewModelScope.launch {
            // 1. Insert User Message (with images if provided)
            val pathsJson = if (imagePaths.isNotEmpty()) {
                org.json.JSONArray(imagePaths).toString()
            } else null

            val userMsg = Message(
                sessionId = sessionId,
                role = "user",
                content = text,
                imagePaths = pathsJson
            )
            repository.insertMessage(userMsg)

            // 2. Fetch configurations
            val providerConfig = run {
                val defaultProvider = repository.getDefaultProvider()
                if (defaultProvider != null) {
                    // 若 selectedModelId 为空，则回退到该 Provider 下 fetched_models 的第一个模型 ID
                    val effectiveModelId = defaultProvider.selectedModelId.takeIf { it.isNotBlank() }
                        ?: repository.getModelsByProvider(defaultProvider.id).firstOrNull()?.modelId
                        ?: ""
                    defaultProvider.copy(selectedModelId = effectiveModelId)
                } else {
                    null
                }
            }
            if (providerConfig == null) {
                repository.insertMessage(
                    Message(
                        sessionId = sessionId,
                        role = "assistant",
                        content = getApplication<Application>().getString(R.string.error_no_default_provider)
                    )
                )
                return@launch
            }

            val activeTemplate = repository.getActiveTemplate()
            val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."

            // 等待正在启动的 MCP 服务就绪，确保获取到正确的工具列表
            runtimeManager.waitForStartingServersToFinish()

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, text)

            // 构建项目作用域（如果当前会话是项目会话）
            val projectScope = buildProjectToolScope(sessionId)

            // Launch streaming in a separate coroutine so we can cancel it via stopStreaming()
            streamingJob = viewModelScope.launch(Dispatchers.Default) {
                startAssistantResponse(sessionId, providerConfig, finalSystemPrompt, projectScope = projectScope)
            }
        }
    }

    /**
     * 终止当前流式输出（用户点击终止按钮时调用）。
     * 取消 streamingJob，保存已累积的部分回复到数据库。
     */
    fun stopStreaming() {
        val job = streamingJob ?: return
        streamingJob = null

        // 读取当前已累积的部分回复（在 cancel 生效前读取）
        val partialThinking = currentStreamingThinking
        val partialBody = currentStreamingBody

        job.cancel()

        // 保存部分回复到数据库（如果有内容）
        val sessionId = _selectedSessionId.value
        if (sessionId != null && (partialBody.isNotBlank() || partialThinking.isNotBlank())) {
            val content = if (partialThinking.isNotBlank()) {
                "<think>${partialThinking}</think>$partialBody"
            } else {
                partialBody
            }
            if (content.isNotBlank()) {
                viewModelScope.launch {
                    repository.insertMessage(
                        Message(
                            sessionId = sessionId,
                            role = "assistant",
                            content = content
                        )
                    )
                }
            }
        }

        // 重置流式状态
        isStreaming = false
        streamingSessionId = null
        currentStreamingThinking = ""
        currentStreamingBody = ""
        isThinkingFinished = true

        // 停止前台服务
        StreamingForegroundService.complete(getApplication())
    }

    /**
     * Cancel a running workflow.
     * Called from UI when user taps stop button on WorkflowProgressCard.
     */
    fun cancelWorkflow(workflowId: String) {
        WorkflowEngine.requestCancellation(workflowId)
        activeWorkflows[workflowId]?.let { workflow ->
            activeWorkflows[workflowId] = workflow.copy(
                status = WorkflowStatus.CANCELLED,
                error = "用户取消"
            )
        }
        subAgentActive = false
        Log.d("ChatViewModel", "[cancelWorkflow] Workflow $workflowId cancelled by user")
    }

    /**
     * Cancel a running SubAgent task.
     * Called from UI when user taps stop button on SubAgentTaskCard.
     */
    fun cancelSubAgentTask(taskId: String) {
        com.omnichat.agent.SubAgent.cancelTask(taskId)
        activeTasks.remove(taskId)
        Log.d("ChatViewModel", "[cancelSubAgentTask] Task $taskId cancelled by user")
    }

    /**
     * Export current session log for debugging.
     * Returns the file path if successful, null otherwise.
     */
    fun exportSessionLog(): String? {
        val sessionId = _selectedSessionId.value ?: return null
        val session = sessions.value.find { it.id == sessionId }
        val messages = activeMessages.value

        return com.omnichat.util.SessionLogExporter.exportSessionLog(
            context = getApplication(),
            session = session,
            messages = messages,
            activeTasks = activeTasks.toMap(),
            activeWorkflows = activeWorkflows.toMap()
        )
    }

    /**
     * 处理 AgentMode 权限审核请求：插入提示消息让 LLM 审核 SubAgent 的操作是否合理。
     */
    private suspend fun handlePermissionReview(request: com.omnichat.mcp.PermissionReviewManager.ReviewRequest) {
        val sessionId = _selectedSessionId.value ?: run {
            // 没有选中的 session，无法审核，默认拒绝
            com.omnichat.mcp.PermissionReviewManager.resolveReview(request.requestId, false)
            return
        }

        if (isStreaming) {
            // 正在流式输出，跳过审核，默认拒绝
            com.omnichat.mcp.PermissionReviewManager.resolveReview(request.requestId, false)
            return
        }

        android.util.Log.i("ChatViewModel", "[handlePermissionReview] 审核请求 id=${request.requestId}, tool=${request.toolName}, path=${request.path}")

        // 注册活跃请求供 resolveReview 查找
        com.omnichat.mcp.PermissionReviewManager.registerActiveRequest(request)

        // 构建审核提示
        val accessTypeStr = if (request.accessType == com.omnichat.data.FileAccessType.READ) "读取" else "写入/修改"
        val taskContextInfo = if (!request.taskContext.isNullOrBlank()) {
            "\n\nSubAgent 正在执行的任务：${request.toolName} -> ${request.path}\n任务描述：${request.taskContext}"
        } else {
            "\n\nSubAgent 请求操作：${request.toolName} -> ${request.path}"
        }

        val reviewPrompt = getApplication<Application>().getString(
            R.string.agent_mode_permission_review,
            request.toolName, request.path, accessTypeStr, taskContextInfo
        )

        // 插入审核提示消息
        val promptMessage = Message(
            sessionId = sessionId,
            role = "user",
            content = reviewPrompt
        )
        repository.insertMessage(promptMessage)

        // 获取模型配置
        val providerConfig = repository.getDefaultProvider() ?: run {
            com.omnichat.mcp.PermissionReviewManager.resolveReview(request.requestId, false)
            return
        }

        // 构建系统提示并触发 LLM
        val activeTemplate = repository.getActiveTemplate()
        val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."
        runtimeManager.waitForStartingServersToFinish()
        val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, reviewPrompt)

        // 触发 LLM 回复
        val projectScope = buildProjectToolScope(sessionId)
        startAssistantResponse(sessionId, providerConfig, finalSystemPrompt, projectScope = projectScope)

        // 等待 LLM 回复完成后，解析最后一条 assistant 消息获取决策
        // 通过监听 activeMessages 等待新的 assistant 消息出现
        val decision = waitForLLMDecision(sessionId, request.requestId)
        com.omnichat.mcp.PermissionReviewManager.resolveReview(request.requestId, decision)
    }

    /**
     * 等待 LLM 对权限审核的决策。
     * 监听 session 消息流，等待出现包含 APPROVE 或 DENY 的 assistant 消息。
     */
    private suspend fun waitForLLMDecision(sessionId: Long, requestId: String): Boolean {
        val startTime = System.currentTimeMillis()
        val timeoutMs = 30_000L // 30 秒超时

        // 获取当前消息数量作为基准
        val baselineCount = try {
            repository.getMessagesBySession(sessionId).size
        } catch (_: Exception) { 0 }

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            kotlinx.coroutines.delay(500)

            // 检查最新的 assistant 消息
            try {
                val messages = repository.getMessagesBySession(sessionId)
                // 取最后一条 assistant 消息
                val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                if (lastAssistant != null && lastAssistant.timestamp > startTime) {
                    val content = lastAssistant.content.uppercase()
                    val decision = when {
                        content.contains("APPROVE") || content.contains("批准") || content.contains("允许") -> true
                        content.contains("DENY") || content.contains("拒绝") || content.contains("禁止") -> false
                        else -> null // 没有明确决策，继续等待
                    }
                    if (decision != null) return decision
                }
            } catch (_: Exception) {}
        }

        // 超时默认拒绝
        android.util.Log.w("ChatViewModel", "[waitForLLMDecision] 审核超时, requestId=$requestId")
        return false
    }

    /**
     * 处理 SubAgent 生命周期事件：管理 in-chat 状态卡片。
     */
    private fun handleSubAgentEvent(event: SubAgentEvent) {
        when (event) {
            is SubAgentEvent.TaskStarted -> {
                if (event.sessionId == _selectedSessionId.value) {
                    subAgentActive = true
                    activeTasks[event.taskId] = SubAgentTaskUiState(
                        taskId = event.taskId,
                        sessionId = event.sessionId,
                        taskType = event.taskType,
                        description = event.description,
                        status = TaskStatus.RUNNING,
                        progressMessage = null,
                        result = null,
                        startedAtMs = System.currentTimeMillis()
                    )
                }
            }
            is SubAgentEvent.TaskProgress -> {
                activeTasks[event.taskId]?.let { task ->
                    activeTasks[event.taskId] = task.copy(progressMessage = event.message)
                }
            }
            is SubAgentEvent.TaskCompleted -> {
                activeTasks[event.taskId]?.let { task ->
                    activeTasks[event.taskId] = task.copy(
                        status = TaskStatus.COMPLETED,
                        result = event.result
                    )
                }
                subAgentActive = false

                // Insert tool result message and trigger MainAgent to continue
                val sessionId = event.sessionId
                if (sessionId == _selectedSessionId.value && event.result.isNotBlank()) {
                    viewModelScope.launch {
                        // Insert tool result as a message
                        repository.insertMessage(
                            Message(
                                sessionId = sessionId,
                                role = "tool",
                                content = event.result,
                                toolCallId = event.taskId
                            )
                        )

                        // Trigger MainAgent to process the result
                        val providerConfig = repository.getDefaultProvider()
                        if (providerConfig != null) {
                            val activeTemplate = repository.getActiveTemplate()
                            val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."
                            runtimeManager.waitForStartingServersToFinish()
                            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, "")
                            val projectScope = buildProjectToolScope(sessionId)

                            streamingJob = viewModelScope.launch(Dispatchers.Default) {
                                startAssistantResponse(sessionId, providerConfig, finalSystemPrompt, projectScope = projectScope)
                            }
                        }
                    }
                }

                // Auto-remove card after 3 seconds
                viewModelScope.launch {
                    delay(3000)
                    activeTasks.remove(event.taskId)
                }
            }
            is SubAgentEvent.TaskFailed -> {
                activeTasks[event.taskId]?.let { task ->
                    activeTasks[event.taskId] = task.copy(
                        status = TaskStatus.FAILED,
                        result = event.error
                    )
                }
                subAgentActive = false

                // Insert tool error message and trigger MainAgent to continue
                val sessionId = event.sessionId
                if (sessionId == _selectedSessionId.value) {
                    viewModelScope.launch {
                        // Insert tool error as a message
                        repository.insertMessage(
                            Message(
                                sessionId = sessionId,
                                role = "tool",
                                content = "Error: ${event.error}",
                                toolCallId = event.taskId
                            )
                        )

                        // Trigger MainAgent to process the error
                        val providerConfig = repository.getDefaultProvider()
                        if (providerConfig != null) {
                            val activeTemplate = repository.getActiveTemplate()
                            val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."
                            runtimeManager.waitForStartingServersToFinish()
                            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, "")
                            val projectScope = buildProjectToolScope(sessionId)

                            streamingJob = viewModelScope.launch(Dispatchers.Default) {
                                startAssistantResponse(sessionId, providerConfig, finalSystemPrompt, projectScope = projectScope)
                            }
                        }
                    }
                }

                // Auto-remove card after 5 seconds
                viewModelScope.launch {
                    delay(5000)
                    activeTasks.remove(event.taskId)
                }
            }
        }
    }

    /**
     * 处理 Workflow 生命周期事件：管理 in-chat workflow 进度卡片。
     */
    private fun handleWorkflowEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.WorkflowStarted -> {
                if (event.sessionId == _selectedSessionId.value) {
                    subAgentActive = true
                    val steps = when (event.mode) {
                        WorkflowMode.CONVERSATIONAL -> {
                            // Conversational mode: create placeholder steps for rounds
                            (0 until event.totalSteps).map { i ->
                                val isAgentA = i % 2 == 0
                                WorkflowStepUiState(
                                    stepId = "round-${i / 2}-${if (isAgentA) event.agentA else event.agentB}",
                                    agentType = if (isAgentA) event.agentA ?: "" else event.agentB ?: "",
                                    task = event.topic ?: "",
                                    status = WorkflowStepStatus.PENDING,
                                    dependsOn = emptyList()
                                )
                            }
                        }
                        else -> emptyList()
                    }
                    activeWorkflows[event.workflowId] = WorkflowUiState(
                        workflowId = event.workflowId,
                        sessionId = event.sessionId,
                        mode = event.mode,
                        status = WorkflowStatus.RUNNING,
                        steps = steps,
                        topic = event.topic,
                        agentA = event.agentA,
                        agentB = event.agentB,
                        maxRounds = event.maxRounds
                    )
                }
            }
            is WorkflowEvent.StepStarted -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = if (workflow.mode == WorkflowMode.CONVERSATIONAL) {
                        // For conversational, update the step at the index
                        workflow.steps.mapIndexed { index, step ->
                            if (index == event.stepIndex) {
                                step.copy(
                                    status = WorkflowStepStatus.RUNNING,
                                    agentType = event.agentType,
                                    task = event.task
                                )
                            } else step
                        }
                    } else {
                        // For pipeline/dag, add or update the step
                        val existingIndex = workflow.steps.indexOfFirst { it.stepId == event.stepId }
                        if (existingIndex >= 0) {
                            workflow.steps.mapIndexed { index, step ->
                                if (index == existingIndex) step.copy(
                                    status = WorkflowStepStatus.RUNNING,
                                    task = event.task
                                ) else step
                            }
                        } else {
                            workflow.steps + WorkflowStepUiState(
                                stepId = event.stepId,
                                agentType = event.agentType,
                                task = event.task,
                                status = WorkflowStepStatus.RUNNING
                            )
                        }
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(
                        steps = updatedSteps,
                        currentStepIndex = event.stepIndex
                    )
                }
            }
            is WorkflowEvent.StepCompleted -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = workflow.steps.map { step ->
                        if (step.stepId == event.stepId) {
                            step.copy(
                                status = event.status,
                                result = event.result
                            )
                        } else step
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
                }
            }
            is WorkflowEvent.WorkflowProgress -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    // For conversational, update current round
                    val updatedWorkflow = if (workflow.mode == WorkflowMode.CONVERSATIONAL) {
                        workflow.copy(currentRound = event.completedSteps / 2 + 1)
                    } else {
                        workflow.copy(currentStepIndex = event.currentStepIndex)
                    }
                    activeWorkflows[event.workflowId] = updatedWorkflow
                }
            }
            is WorkflowEvent.WorkflowCompleted -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    activeWorkflows[event.workflowId] = workflow.copy(
                        status = WorkflowStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                }
                subAgentActive = false
                // Auto-remove after delay
                viewModelScope.launch {
                    delay(5000)
                    activeWorkflows.remove(event.workflowId)
                }
            }
            is WorkflowEvent.WorkflowFailed -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    activeWorkflows[event.workflowId] = workflow.copy(
                        status = WorkflowStatus.FAILED,
                        error = event.error,
                        completedAt = System.currentTimeMillis()
                    )
                }
                subAgentActive = false
                // Auto-remove after delay
                viewModelScope.launch {
                    delay(8000)
                    activeWorkflows.remove(event.workflowId)
                }
            }
            // New event types for interactive pipeline - no UI updates needed yet
            is WorkflowEvent.StepWokeUp -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = workflow.steps.map { step ->
                        if (step.stepId == event.stepId) {
                            step.copy(
                                status = WorkflowStepStatus.RUNNING,
                                lastMessageFrom = event.fromAgent,
                                lastMessagePreview = event.messagePreview
                            )
                        } else step
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
                }
            }
            is WorkflowEvent.StepRecalled -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = workflow.steps.map { step ->
                        if (step.stepId == event.stepId) {
                            step.copy(
                                status = WorkflowStepStatus.REVISION,
                                revisionCount = (step.revisionCount) + 1,
                                lastMessageFrom = event.fromAgent
                            )
                        } else step
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
                }
            }
            is WorkflowEvent.StepEnteredIdle -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = workflow.steps.map { step ->
                        if (step.stepId == event.stepId) {
                            step.copy(
                                status = WorkflowStepStatus.IDLE,
                                idleSince = System.currentTimeMillis()
                            )
                        } else step
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
                }
            }
            is WorkflowEvent.IdleTimeoutWarning -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val newWarning = com.omnichat.agent.IdleWarningInfo(
                        stepId = event.stepId,
                        idleDurationMs = event.idleDurationMs,
                        message = event.message,
                        timestamp = System.currentTimeMillis()
                    )
                    activeWorkflows[event.workflowId] = workflow.copy(
                        idleWarnings = workflow.idleWarnings + newWarning
                    )
                }
            }
            is WorkflowEvent.StepTimeout -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = workflow.steps.map { step ->
                        if (step.stepId == event.stepId) {
                            step.copy(
                                status = WorkflowStepStatus.FAILED,
                                error = event.error
                            )
                        } else step
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
                }
            }
            is WorkflowEvent.MessageRoutingError -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val newError = com.omnichat.agent.MessageErrorInfo(
                        from = event.from,
                        to = event.to,
                        error = event.error,
                        availableTargets = event.availableTargets,
                        timestamp = System.currentTimeMillis()
                    )
                    activeWorkflows[event.workflowId] = workflow.copy(
                        messageErrors = workflow.messageErrors + newError
                    )
                }
            }
            is WorkflowEvent.StepRevisionCompleted -> {
                activeWorkflows[event.workflowId]?.let { workflow ->
                    val updatedSteps = workflow.steps.map { step ->
                        if (step.stepId == event.stepId) {
                            step.copy(
                                revisionCount = event.revisionCount,
                                result = event.result
                            )
                        } else step
                    }
                    activeWorkflows[event.workflowId] = workflow.copy(steps = updatedSteps)
                }
            }
        }
    }

    private suspend fun generateSystemPrompt(customSystemPrompt: String, userMessage: String = "", sessionIdOverride: Long? = null): String {
        val currentSessionId = sessionIdOverride ?: _selectedSessionId.value
        val currentProjectId = currentSessionId?.let { repository.getSessionById(it)?.projectId }

        // 如果是项目会话：注入 Project Memory，跳过全局长效记忆
        if (currentProjectId != null) {
            val project = repository.getProjectById(currentProjectId)
            val projectName = project?.name ?: "Project #$currentProjectId"

            // 读取 Project Memory 文件
            val memoryFile = java.io.File(getApplication<Application>().filesDir, "projects/$currentProjectId/project_memory.md")
            val projectMemory = if (memoryFile.exists()) memoryFile.readText() else ""

            // 列出项目知识文件
            val knowledgeFiles = repository.getKnowledgeByProject(currentProjectId)

            // 构建项目上下文
            val projectContext = buildString {
                appendLine("[PROJECT CONTEXT]")
                appendLine("Current Project: $projectName")
                if (project?.description?.isNotBlank() == true) {
                    appendLine("Project Description: ${project.description}")
                }
                appendLine()

                // 注入 Project Memory
                if (projectMemory.isNotBlank()) {
                    appendLine("[Project Memory]:")
                    appendLine(projectMemory)
                    appendLine()
                }

                // 列出知识文件
                if (knowledgeFiles.isNotEmpty()) {
                    appendLine("[Project Knowledge Files]:")
                    knowledgeFiles.forEach { file ->
                        val sizeStr = when {
                            file.fileSize < 1024 -> "${file.fileSize} B"
                            file.fileSize < 1024 * 1024 -> "${file.fileSize / 1024} KB"
                            else -> "${file.fileSize / (1024 * 1024)} MB"
                        }
                        appendLine("- [${file.id}] ${file.fileName} (${fileTypeIcon(file.fileType)}, $sizeStr)")
                    }
                    appendLine()
                }
                appendLine("[/PROJECT CONTEXT]")
                appendLine()
                appendLine("CRITICAL: You are in a PROJECT-ISOLATED session.")
                appendLine("You MUST follow these rules:")
                appendLine("1. When the user asks a question, FIRST use project_read_knowledge to read the relevant knowledge files, then answer based on their content.")
                appendLine("2. You MUST ONLY use project_* tools to access project data (project_read_knowledge, project_list_knowledge, project_create_knowledge, project_read_memory, project_update_memory).")
                appendLine("3. You MUST NOT use document_read, file_read, file_search, file_list, file_info, file_write, file_append, or search_memory — these tools access the device filesystem or global memory, not the project.")
                appendLine("4. To read a PDF or DOCX file in the project, use project_read_knowledge with the file's knowledge_id — it supports PDF and DOCX text extraction.")
                appendLine("5. The project knowledge base is the ONLY source of files. Do not look for files on the device storage.")
                appendLine("6. You may ONLY use the MCP tools explicitly listed below. Any other tool is unavailable in this project session.")
            }

            var finalSystemPrompt = if (customSystemPrompt.contains("[PROJECT_CONTEXT]")) {
                customSystemPrompt.replace("[PROJECT_CONTEXT]", projectContext)
            } else {
                customSystemPrompt + "\n\n$projectContext"
            }

            // Project sessions don't use global cross-session memory
            finalSystemPrompt = finalSystemPrompt.replace("[CROSS_SESSION_MEMORY]",
                "（项目会话不使用全局长效记忆 / Project sessions do not use global cross-session memory）")

            // Inject scoped MCP tools for project session
            val projectScope = buildProjectToolScope(currentSessionId ?: return "")
            val scopedTools = ToolRegistry.toolsForSession(projectScope)
            val mcpToolsText = if (scopedTools.isEmpty()) {
                "无可用 MCP 工具 (No MCP tools available)"
            } else {
                scopedTools.joinToString("\n\n") { tool ->
                    "工具名: ${tool.name}\n分组: ${tool.group}\n描述: ${tool.description}\n参数架构: ${tool.inputSchema.toString(2)}"
                }
            }
            finalSystemPrompt = if (finalSystemPrompt.contains("[MCP_TOOLS]")) {
                finalSystemPrompt.replace("[MCP_TOOLS]", mcpToolsText)
            } else {
                finalSystemPrompt + "\n\n[Available MCP Tools]:\n$mcpToolsText"
            }

            // Inject matched Skill prompts
            val matchedSkills = skillManager.matchByIntent(userMessage)
            if (matchedSkills.isNotEmpty()) {
                val skillsText = matchedSkills.joinToString("\n\n") { skill ->
                    """[Activated Skill: ${skill.name}]
${skill.systemPrompt}"""
                }
                finalSystemPrompt += "\n\n[Activated Skills]:\n$skillsText"
            }

            // Inject current date/time
            val now = ZonedDateTime.now()
            val dateTimeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE, z)", Locale.getDefault()))
            finalSystemPrompt += "\n\n<!-- SYSTEM TIME: " + getApplication<Application>().getString(R.string.ai_time_instruction, dateTimeStr) + " -->"

            // Formatting instruction
            finalSystemPrompt += "\n\n<!-- FORMATTING RULE: You MUST always format your responses using Markdown. Use headers, bold, italic, code blocks, lists, tables, and other Markdown elements as appropriate to make your response clear and well-structured. Never reply with plain unformatted text. -->"

            // SubAgent delegation preference
            finalSystemPrompt += "\n\n<!-- DELEGATION PRIORITY: For complex tasks (research, coding, multi-step operations), prefer delegating to SubAgents via delegate_task or run_workflow tools. SubAgents have focused context and can execute tasks independently. Only handle tasks yourself if: (1) the task is simple and quick, (2) SubAgent failed and you need to recover, or (3) user explicitly wants your direct response. -->"

            return finalSystemPrompt
        }

        // 非项目会话：使用原有逻辑，注入全局长效记忆
        // 3. Fetch relevant memories via MemoryEngine (embedding-based ranking when available)
        val localMemories = memoryEngine.selectRelevantMemories(userMessage)
        val memoriesText = if (localMemories.isEmpty()) {
            getApplication<Application>().getString(R.string.no_memories_recorded)
        } else {
            localMemories.joinToString("\n") { "- ${it.content}" }
        }

        // 4. Inject memories into prompt template
        val mcpToolsText = runtimeManager.getAllToolsAsTextDescription()

        var finalSystemPrompt = if (customSystemPrompt.contains("[CROSS_SESSION_MEMORY]")) {
            customSystemPrompt.replace("[CROSS_SESSION_MEMORY]", memoriesText)
        } else {
            customSystemPrompt + "\n\n[User's Cross-Session History & Preferences]:\n" + memoriesText
        }

        // 4.5 Inject matched Skill prompts
        val matchedSkills = skillManager.matchByIntent(userMessage)
        if (matchedSkills.isNotEmpty()) {
            val skillsText = matchedSkills.joinToString("\n\n") { skill ->
                """[Activated Skill: ${skill.name}]
${skill.systemPrompt}"""
            }
            finalSystemPrompt += "\n\n[Activated Skills]:\n$skillsText"
        }

        finalSystemPrompt = if (finalSystemPrompt.contains("[MCP_TOOLS]")) {
            finalSystemPrompt.replace("[MCP_TOOLS]", mcpToolsText)
        } else {
            finalSystemPrompt + "\n\n[Available MCP Tools]:\n" + mcpToolsText
        }

        // Inject current date/time to prevent AI temporal hallucinations
        val now = ZonedDateTime.now()
        val dateTimeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE, z)", Locale.getDefault()))
        finalSystemPrompt += "\n\n<!-- SYSTEM TIME: " + getApplication<Application>().getString(R.string.ai_time_instruction, dateTimeStr) + " -->"

        // Hidden formatting instruction: always respond using Markdown
        finalSystemPrompt += "\n\n<!-- FORMATTING RULE: You MUST always format your responses using Markdown. Use headers, bold, italic, code blocks, lists, tables, and other Markdown elements as appropriate to make your response clear and well-structured. Never reply with plain unformatted text. -->"

        // SubAgent delegation preference
        finalSystemPrompt += "\n\n<!-- DELEGATION PRIORITY: For complex tasks (research, coding, multi-step operations), prefer delegating to SubAgents via delegate_task or run_workflow tools. SubAgents have focused context and can execute tasks independently. Only handle tasks yourself if: (1) the task is simple and quick, (2) SubAgent failed and you need to recover, or (3) user explicitly wants your direct response. -->"

        // Hidden memory search instruction
        val totalMemoryCount = memoryEngine.getTotalMemoryCount()
        if (totalMemoryCount > com.omnichat.memory.MemoryEngine.MEMORY_INJECT_LIMIT) {
            finalSystemPrompt += "\n\n<!-- MEMORY SEARCH HINT: The cross-session memory above only shows the top ${com.omnichat.memory.MemoryEngine.MEMORY_INJECT_LIMIT} entries (by confidence) out of $totalMemoryCount total stored memories. If the user asks about something not covered by the injected memories, proactively call the [search_memory] tool with relevant keywords to retrieve additional matching memories before answering. -->"
        }

        // 5. Check for pending time reminders
        val pendingReminders = memoryEngine.checkPendingReminders()
        if (pendingReminders.isNotEmpty()) {
            val remindersText = buildString {
                appendLine("[PENDING_REMINDERS]")
                appendLine("以下是你需要主动提醒用户的待办/事件：")
                pendingReminders.forEach { reminder ->
                    val status = getDueDateStatus(reminder.dueDate!!)
                    appendLine("- [$status] ${reminder.content}（原定：${reminder.dueDate}）")
                }
                appendLine("请在回复开头自然地提及这些提醒。提醒后调用 mark_reminded 工具标记，不要重复提醒。")
                appendLine("[/PENDING_REMINDERS]")
            }
            finalSystemPrompt += "\n\n$remindersText"
        }

        return finalSystemPrompt
    }

    private fun fileTypeIcon(type: String): String = when (type) {
        "image" -> "🖼️"
        "pdf" -> "📄"
        "docx" -> "📝"
        "md" -> "📋"
        "txt" -> "📃"
        else -> "📎"
    }

    /**
     * 计算截止日期的显示状态。
     */
    private fun getDueDateStatus(dueDateStr: String): String {
        return try {
            val dueDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dueDateStr)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            )
            if (dueDate == null || today == null) return dueDateStr

            val diffMs = dueDate.time - today.time
            val diffDays = (diffMs / (24 * 60 * 60 * 1000)).toInt()

            when {
                diffDays < 0 -> "已过期${-diffDays}天"
                diffDays == 0 -> "今天"
                diffDays == 1 -> "明天"
                else -> "距今${diffDays}天"
            }
        } catch (e: Exception) {
            dueDateStr
        }
    }

    /**
     * 进入编辑消息模式：记录正在编辑的消息 ID，调用方需将消息内容填入输入框。
     */
    fun editMessage(message: Message) {
        if (isStreaming || subAgentActive) return
        editingMessageId = message.id
    }

    /**
     * 取消编辑消息模式。
     */
    fun cancelEdit() {
        editingMessageId = null
    }

    /**
     * 提交编辑后的消息：更新原消息内容，删除后续消息，重新生成 AI 回复。
     */
    fun submitEdit(newContent: String) {
        val sessionId = _selectedSessionId.value ?: return
        val msgId = editingMessageId ?: return
        if (newContent.isBlank()) return
        if (isStreaming || subAgentActive) return

        editingMessageId = null

        viewModelScope.launch {
            // 1. 更新原消息内容
            repository.updateMessageContent(msgId, newContent)

            // 2. 删除该消息之后的所有消息（基于 ID，避免时间戳比较问题）
            repository.deleteMessagesByIdAfter(sessionId, msgId)

            // 3. 重新生成 AI 回复
            val providerConfig = run {
                val defaultProvider = repository.getDefaultProvider()
                if (defaultProvider != null) {
                    val effectiveModelId = defaultProvider.selectedModelId.takeIf { it.isNotBlank() }
                        ?: repository.getModelsByProvider(defaultProvider.id).firstOrNull()?.modelId
                        ?: ""
                    defaultProvider.copy(selectedModelId = effectiveModelId)
                } else {
                    null
                }
            } ?: return@launch

            val activeTemplate = repository.getActiveTemplate()
            val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."

            runtimeManager.waitForStartingServersToFinish()

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, newContent)
            val projectScope = buildProjectToolScope(sessionId)

            streamingJob = viewModelScope.launch(Dispatchers.Default) {
                startAssistantResponse(sessionId, providerConfig, finalSystemPrompt, projectScope = projectScope)
            }
        }
    }

    fun retryMessage(message: Message) {
        if (isStreaming || subAgentActive) return
        val job = viewModelScope.launch {
            // 1. Delete all messages from this one onwards
            repository.deleteMessagesFrom(message.sessionId, message.timestamp)

            // 2. Prepare configurations (similar to sendMessage)
            val providerConfig = run {
                val defaultProvider = repository.getDefaultProvider()
                if (defaultProvider != null) {
                    val effectiveModelId = defaultProvider.selectedModelId.takeIf { it.isNotBlank() }
                        ?: repository.getModelsByProvider(defaultProvider.id).firstOrNull()?.modelId
                        ?: ""
                    defaultProvider.copy(selectedModelId = effectiveModelId)
                } else {
                    null
                }
            } ?: return@launch

            val activeTemplate = repository.getActiveTemplate()
            val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."

            // 等待正在启动的 MCP 服务就绪，确保获取到正确的工具列表
            runtimeManager.waitForStartingServersToFinish()

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, message.content)
            val projectScope = buildProjectToolScope(message.sessionId)

            // 3. Re-trigger assistant response
            startAssistantResponse(message.sessionId, providerConfig, finalSystemPrompt, projectScope = projectScope)
        }
        streamingJob = job
    }

    /**
     * 根据用户第一条消息和 AI 第一条回复生成会话标题。
     * 仅在会话标题仍为默认值时触发。
     */
    private suspend fun generateSessionTitle(sessionId: Long, assistantContent: String) {
        android.util.Log.d("TitleGen", "generateSessionTitle called, sessionId=$sessionId, assistantContent.length=${assistantContent.length}")
        val currentSession = repository.getSessionById(sessionId) ?: run {
            android.util.Log.d("TitleGen", "currentSession is null, returning")
            return
        }

        // 只有当前两条消息（用户第一条 + AI 第一条）时才生成标题
        val messages = repository.getMessagesBySession(sessionId)
        android.util.Log.d("TitleGen", "messages.size=${messages.size}")
        if (messages.size > 2) {
            android.util.Log.d("TitleGen", "messages.size > 2, already has title, returning")
            return
        }
        val firstUserMsg = messages.firstOrNull { it.role == "user" } ?: run {
            android.util.Log.d("TitleGen", "firstUserMsg is null, returning")
            return
        }

        // 构造 JSON 结构化输入，避免副模型误解为需要回答的问题
        val userText = buildString {
            if (firstUserMsg.content.isNotBlank()) {
                append(firstUserMsg.content.take(200))
            }
            if (!firstUserMsg.imagePaths.isNullOrBlank()) {
                try {
                    val arr = org.json.JSONArray(firstUserMsg.imagePaths)
                    if (arr.length() > 0) {
                        if (isNotEmpty()) append("\n")
                        append("[User attached ${arr.length()} image(s)]")
                    }
                } catch (_: Exception) {}
            }
        }
        val assistantText = assistantContent.take(200)

        val conversationJson = org.json.JSONObject().apply {
            put("user_message", userText)
            put("assistant_response", assistantText)
        }

        android.util.Log.d("TitleGen", "conversationJson=$conversationJson")
        if (conversationJson.toString().isBlank()) return

        try {
            val defaultForTitle = repository.getDefaultProvider() ?: run {
                android.util.Log.d("TitleGen", "defaultForTitle is null, returning")
                return
            }
            val memoryProviderId = defaultForTitle.memoryProviderId
            val memoryProvider = if (memoryProviderId > 0L) {
                repository.getConfigById(memoryProviderId) ?: defaultForTitle
            } else {
                defaultForTitle
            }
            val titleConfig = memoryProvider.copy(
                selectedModelId = defaultForTitle.memoryModelId.takeIf { it.isNotBlank() }
                    ?: defaultForTitle.selectedModelId
            )

            // 强化 system prompt：明确这是一个标题生成任务，模型必须输出标题，不得回答问题
            val titleSystemPrompt = """You are a session title generator. Your ONLY job is to generate a short, descriptive title for the given conversation.

CRITICAL RULES:
1. You MUST output ONLY the title. Do NOT answer, respond, or continue the conversation.
2. The title should be max 10 words, in the same language as the conversation.
3. Do NOT include quotes, markdown headers, prefixes like "Title:", or any other formatting.
4. If the conversation contains a question, the title should DESCRIBE the topic, not ASK the question.

Example inputs and outputs:
Input: {"user_message": "How do I fix a NullPointerException in Kotlin?", "assistant_response": "A NullPointerException occurs when..."}
Output: Kotlin NullPointerException Fix

Input: {"user_message": "帮我写一个 Python 脚本解析 JSON", "assistant_response": "好的，这是一个示例脚本..."}
Output: Python JSON 解析脚本"""

            val titleUserPrompt = """Generate a title for this conversation:

$conversationJson

Output the title now."""

            android.util.Log.d("TitleGen", "Calling executeCompletion with model=${titleConfig.selectedModelId}")
            val generatedTitle = ApiClient.executeCompletion(titleConfig, titleSystemPrompt, titleUserPrompt)
            android.util.Log.d("TitleGen", "generatedTitle=$generatedTitle")
            val finalTitle = generatedTitle?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
                ?: if (userText.length > 15) userText.take(15) + "..." else userText
            android.util.Log.d("TitleGen", "finalTitle=$finalTitle")
            repository.updateSessionTitle(sessionId, finalTitle.replace("\n", ""))
        } catch (e: Exception) {
            android.util.Log.e("TitleGen", "Error generating title", e)
        }
    }

    private suspend fun startAssistantResponse(sessionId: Long, config: ModelConfig, systemPrompt: String, toolCallDepth: Int = 0, projectScope: ProjectToolScope? = null) {
        val messageHistory = repository.getMessagesBySession(sessionId)
        val openAiTools = if (projectScope != null) {
            org.json.JSONArray().apply {
                ToolRegistry.toolsForSession(projectScope).forEach { tool ->
                    put(org.json.JSONObject().apply {
                        put("type", "function")
                        put("function", org.json.JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        })
                    })
                }
            }
        } else {
            runtimeManager.getAllToolsAsOpenAiFormat()
        }
        val sessionThinkingEffort = repository.getSessionById(sessionId)?.thinkingEffort

        isStreaming = true
        streamingSessionId = sessionId
        currentStreamingThinking = ""
        currentStreamingBody = ""
        isThinkingFinished = true

        // 启动前台服务：保持 LLM 连接不被系统回收；仅在顶层调用时启动
        val isTopLevelStreaming = toolCallDepth == 0
        if (isTopLevelStreaming) {
            StreamingForegroundService.start(getApplication())
        }

        // BUG-015: 使用 try/finally 确保 isStreaming 在所有路径（包括异常）上都被重置
        try {
        val accumulatedText = StringBuilder()
        val accumulatedReasoningContent = StringBuilder()
        var lastUiUpdateTime = 0L
        val accumulatedToolCalls = mutableMapOf<Int, org.json.JSONObject>()
        var errorReceived = false

        fun updateStreamingStates(text: String) {
            val thinkStartTag = "<think>"
            val thinkEndTag = "</think>"
            
            val startIndex = text.indexOf(thinkStartTag, ignoreCase = true)
            if (startIndex == -1) {
                currentStreamingThinking = ""
                currentStreamingBody = text
                isThinkingFinished = true
                return
            }
            
            val contentAfterStart = text.substring(startIndex + thinkStartTag.length)
            val endIndex = contentAfterStart.indexOf(thinkEndTag, ignoreCase = true)
            
            if (endIndex != -1) {
                currentStreamingThinking = contentAfterStart.substring(0, endIndex).trim()
                currentStreamingBody = contentAfterStart.substring(endIndex + thinkEndTag.length).trim()
                isThinkingFinished = true
            } else {
                currentStreamingThinking = contentAfterStart.trim()
                currentStreamingBody = ""
                isThinkingFinished = false
            }
        }

        fun publishStreamingStates(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUiUpdateTime < STREAMING_UI_UPDATE_INTERVAL_MS) {
                return
            }

            // Providers that emit dedicated reasoning deltas previously bypassed
            // the text throttle. Apply the same cadence to both output channels.
            if (accumulatedReasoningContent.isNotEmpty()) {
                currentStreamingThinking = accumulatedReasoningContent.toString()
                currentStreamingBody = accumulatedText.toString()
                isThinkingFinished = false
            } else {
                updateStreamingStates(accumulatedText.toString())
            }
            lastUiUpdateTime = now
        }

        ApiClient.executeStreamingChat(config, systemPrompt, messageHistory, openAiTools, getApplication(), thinkingEffortOverride = sessionThinkingEffort)
            .collect { chunk ->
                if (errorReceived) return@collect
                if (chunk.startsWith("ERROR:")) {
                    accumulatedText.append("\n").append(chunk)
                    publishStreamingStates(force = true)
                    errorReceived = true
                } else if (chunk.startsWith("INFO:")) {
                    accumulatedText.append("\n").append(chunk)
                    publishStreamingStates(force = true)
                } else if (chunk == "RETRY_RESET:") {
                    accumulatedText.clear()
                    accumulatedReasoningContent.clear()
                    accumulatedToolCalls.clear()
                    publishStreamingStates(force = true)
                } else if (chunk.startsWith("TOOL_CALL_DELTA:")) {
                    val deltaJson = chunk.substringAfter("TOOL_CALL_DELTA:")
                    try {
                        val toolCallsArr = org.json.JSONArray(deltaJson)
                        for (i in 0 until toolCallsArr.length()) {
                            val item = toolCallsArr.getJSONObject(i)
                            val index = item.optInt("index", 0)
                            val existing = accumulatedToolCalls.getOrPut(index) { org.json.JSONObject() }
                            
                            val id = item.optString("id")
                            if (id.isNotEmpty() && id != "null") existing.put("id", id)

                            // Preserve thought_signature for Gemini thinking models from all potential places.
                            var thoughtSignatureItem = item.optString("thought_signature")
                            if (thoughtSignatureItem.isEmpty() || thoughtSignatureItem == "null") {
                                val ec = item.optJSONObject("extra_content")
                                val g = ec?.optJSONObject("google")
                                val extraSig = g?.optString("thought_signature")
                                if (!extraSig.isNullOrEmpty() && extraSig != "null") {
                                    thoughtSignatureItem = extraSig
                                }
                            }
                            if (thoughtSignatureItem.isNotEmpty() && thoughtSignatureItem != "null") {
                                val currentItemSignature = existing.optString("thought_signature", "")
                                existing.put("thought_signature", currentItemSignature + thoughtSignatureItem)
                            }

                            // Preserve thought for Gemini thinking models.
                            val thoughtItem = item.optString("thought")
                            if (thoughtItem.isNotEmpty() && thoughtItem != "null") {
                                val currentItemThought = existing.optString("thought", "")
                                existing.put("thought", currentItemThought + thoughtItem)
                            }
                            
                            val function = item.optJSONObject("function")
                            if (function != null) {
                                val existingFunc = existing.optJSONObject("function") ?: org.json.JSONObject().also { existing.put("function", it) }
                                
                                // Google's OpenAI compatibility layer might place it inside the function object
                                val thoughtSignatureFunc = function.optString("thought_signature")
                                if (thoughtSignatureFunc.isNotEmpty() && thoughtSignatureFunc != "null") {
                                    val currentFuncSignature = existingFunc.optString("thought_signature", "")
                                    existingFunc.put("thought_signature", currentFuncSignature + thoughtSignatureFunc)
                                }

                                val thoughtFunc = function.optString("thought")
                                if (thoughtFunc.isNotEmpty() && thoughtFunc != "null") {
                                    val currentFuncThought = existingFunc.optString("thought", "")
                                    existingFunc.put("thought", currentFuncThought + thoughtFunc)
                                }

                                val name = function.optString("name")
                                if (name.isNotEmpty() && name != "null") existingFunc.put("name", name)
                                val args = function.optString("arguments")
                                if (args.isNotEmpty()) {
                                    val currentArgs = existingFunc.optString("arguments", "")
                                    existingFunc.put("arguments", currentArgs + args)
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                } else if (chunk.startsWith("THOUGHT_SIG:")) {
                    // Standalone thought_signature chunk from Gemini (arrived without tool_calls).
                    // Inject into all accumulated tool calls that don't have a signature yet.
                    val sig = chunk.substringAfter("THOUGHT_SIG:")
                    for (tc in accumulatedToolCalls.values) {
                        val currentSig = tc.optString("thought_signature", "")
                        tc.put("thought_signature", currentSig + sig)
                    }
                } else if (chunk.startsWith("REASONING:")) {
                    accumulatedReasoningContent.append(chunk.substringAfter("REASONING:"))
                    publishStreamingStates()
                } else {
                    if (chunk != "null") {
                        accumulatedText.append(chunk)
                        publishStreamingStates()
                    }
                }
            }

        // 最后一次同步更新（将 reasoning_content 合并为 <think> 标签，确保持久化后 parseMessageContent 可正确解析）
        val finalAccumulatedText = if (accumulatedReasoningContent.isNotEmpty()) {
            "<think>${accumulatedReasoningContent}</think>${accumulatedText}"
        } else {
            accumulatedText.toString()
        }
        // Always publish the completed value, even if the final delta arrived
        // within the throttle window.
        updateStreamingStates(finalAccumulatedText)

        val finalContent = if (finalAccumulatedText.trim() == "null") "" else finalAccumulatedText

        // 1. Save assistant text response AND tool calls
        if (finalContent.isNotEmpty() || accumulatedToolCalls.isNotEmpty()) {
            val toolCallsJson = if (accumulatedToolCalls.isNotEmpty()) {
                val arr = org.json.JSONArray()
                accumulatedToolCalls.values.forEach { arr.put(it) }
                arr.toString()
            } else null
            
            repository.insertMessage(
                Message(
                    sessionId = sessionId,
                    role = "assistant",
                    content = finalContent,
                    toolCallsJson = toolCallsJson
                )
            )
        }

        // 首次回复后生成会话标题（结合用户第一条消息和 AI 第一条回复）
        android.util.Log.d("TitleGen", "After assistant response: toolCallDepth=$toolCallDepth, sessionId=$sessionId")
        if (toolCallDepth == 0) {
            generateSessionTitle(sessionId, finalContent)
        }

        val wasOnlyToolCalls = finalContent.isEmpty() && accumulatedToolCalls.isNotEmpty()
        // 清理流式状态
        currentStreamingThinking = ""
        currentStreamingBody = ""
        isThinkingFinished = true

        // 2. Process Tool Calls if any
        var followUpTriggered = false
        if (accumulatedToolCalls.isNotEmpty()) {
            var hasNewResults = false
            for (toolCall in accumulatedToolCalls.values) {
                val function = toolCall.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isEmpty()) continue
                val argsStr = function.optString("arguments")
                val callId = toolCall.optString("id")

                val serverId = runtimeManager.findServerIdForTool(name)
                if (serverId != null) {
                    try {
                        val argsJson = org.json.JSONObject(argsStr)
                        val result = runtimeManager.callTool(serverId, name, argsJson, sessionId)

                        repository.insertMessage(
                            Message(
                                sessionId = sessionId,
                                role = "tool",
                                content = result?.toString() ?: "No result",
                                toolCallId = callId
                            )
                        )
                        hasNewResults = true
                    } catch (e: Exception) {
                        repository.insertMessage(Message(sessionId = sessionId, role = "tool", content = "Error: ${e.message}", toolCallId = callId))
                        hasNewResults = true
                    }
                } else {
                    // 非 MCP 工具（内置工具，包括项目工具）：通过 ToolExecutor 执行
                    try {
                        val argsJson = org.json.JSONObject(argsStr)
                        val result = ToolExecutor.execute(
                            getApplication(), name, argsJson, sessionId, projectScope
                        )

                        repository.insertMessage(
                            Message(
                                sessionId = sessionId,
                                role = "tool",
                                content = result?.toString() ?: "No result",
                                toolCallId = callId
                            )
                        )
                        hasNewResults = true
                    } catch (e: Exception) {
                        repository.insertMessage(Message(sessionId = sessionId, role = "tool", content = "Error: ${e.message}", toolCallId = callId))
                        hasNewResults = true
                    }
                }
            }

            if (hasNewResults) {
                // Trigger the follow-up turn with depth limit to prevent infinite loops
                if (toolCallDepth < MAX_TOOL_CALL_DEPTH) {
                    followUpTriggered = true
                    startAssistantResponse(sessionId, config, systemPrompt, toolCallDepth + 1, projectScope = projectScope)
                } else {
                    repository.insertMessage(
                        Message(sessionId = sessionId, role = "assistant", content = "⚠️ " + getApplication<Application>().getString(R.string.error_tool_depth_exceeded, MAX_TOOL_CALL_DEPTH))
                    )
                }
            }
        }

        // 消息已入库且无后续 LLM 轮次时才停止流式状态，避免工具执行期间用户可输入
        if (!followUpTriggered) {
            isStreaming = false
            streamingSessionId = null
        }

        // 项目会话不触发全局记忆同步
        if (projectScope == null && !wasOnlyToolCalls && finalContent.isNotEmpty()) {
            triggerMemorySync()
        }
        } catch (e: CancellationException) {
            // 用户通过 stopStreaming() 主动终止 — 部分回复已在 stopStreaming() 中保存，
            // 此处不再重复保存，仅重新抛出以保持协程取消语义
            throw e
        } finally {
            // BUG-015: 确保 isStreaming 在所有路径上都被重置，防止 UI 永久卡在加载状态
            isStreaming = false
            streamingSessionId = null
            // 顶层调用完成 → 通知前台服务：回复已完成（5 秒后自动停止）
            if (isTopLevelStreaming) {
                StreamingForegroundService.complete(getApplication())
            }
        }
    }

    /**
     * 增量记忆算法（方案 A）：
     *
     * 触发条件（满足任一即运行）：
     *   - 距上次总结超过 MEMORY_INTERVAL_MS（15 分钟）
     *   - 自上次总结后新增消息数 >= NEW_MESSAGES_THRESHOLD（10 条）
     *   - 预检：新消息总字符数 < MIN_NEW_CHARS_THRESHOLD 时跳过（避免无意义触发）
     *
     * 每次运行流程：
     *   Step 1  取最近消息（按字符数截断）+ 上次会话摘要 → 生成新的会话滚动摘要
     *           摘要同时保留偏好信号，供 Step 2 使用
     *   Step 2  用新摘要 + 最近原始消息片段 + 现有全局偏好事实 → LLM 返回结构化 CRUD JSON
     *           → 事务性 apply，解析失败则保留旧记忆（消除单点故障）
     *           → ADD 前做本地相似度去重，避免语义重复条目堆积
     *
     * CRUD JSON 格式（LLM 输出）：
     * {
     *   "ops": [
     *     {"op": "ADD",    "content": "...", "tags": ["preference"]},
     *     {"op": "UPDATE", "id": 7, "content": "...", "tags": ["fact"]},
     *     {"op": "REINFORCE", "id": 3},          // 内容不变，仅 confidence+1
     *     {"op": "DELETE", "id": 12}
     *   ]
     * }
     * - pinned=true 的条目：LLM 可以 REINFORCE，但 DELETE/UPDATE 会被客户端拒绝
     * - 解析失败或 ops 为空 → 放弃本次 Step 2，旧记忆完整保留
     */
    fun triggerMemorySync(force: Boolean = false) {
        // CONFLATED Channel 自动合并并发请求，后台消费者协程串行处理，无需 Mutex
        val sessionId = _selectedSessionId.value ?: return
        // 项目会话跳过全局记忆同步
        viewModelScope.launch {
            val session = repository.getSessionById(sessionId) ?: return@launch
            if (session.projectId != null) return@launch
            memorySyncChannel.trySend(force)
        }
    }

    /**
     * 实际的记忆同步逻辑，在后台消费者协程中串行执行。
     * 从 triggerMemorySync 中提取，由 memorySyncChannel 消费者循环调用。
     */
    private suspend fun executeMemorySync(force: Boolean) {
        val sessionId = _selectedSessionId.value ?: return
        val memoryConfig = memoryEngine.getMemoryModelConfig() ?: return

        val allMessages = repository.getMessagesBySession(sessionId)
        if (allMessages.size < 2) return

        // 读取上次摘要记录，判断是否需要运行
        val prevSummary = repository.getSessionSummary(sessionId)
        val now = System.currentTimeMillis()
        val lastSummarizedAt = prevSummary?.lastSummarizedAt ?: 0L
        val msgCountAtLast = prevSummary?.messageCountAtLastSummary ?: 0
        val newMsgCount = allMessages.size - msgCountAtLast
        val timeSinceLast = now - lastSummarizedAt

        // 节流检查
        if (!force) {
            val newMessages = allMessages.drop(msgCountAtLast)
            val newCharsTotal = newMessages.sumOf { it.content.length }
            if (!memoryEngine.shouldRunSync(force, timeSinceLast, newMsgCount, newCharsTotal)) return
        }

        // 衰减非 pinned 记忆的置信度
        memoryEngine.applyConfidenceDecay(now)

        // 检测并重建 embedding（当模型变更时自动回填）
        memoryEngine.rebuildEmbeddingsIfModelChanged()

        // ── Step 1：生成本会话的新滚动摘要 ──────────────────────
        val recentMessages = run {
            var charCount = 0
            allMessages.asReversed().takeWhile { msg ->
                charCount += msg.content.length
                charCount <= MEMORY_WINDOW_CHARS
            }.reversed()
        }

        val newSummaryText = memoryEngine.generateSessionSummary(
            recentMessages = recentMessages,
            previousSummary = prevSummary?.summaryText?.takeIf { it.isNotBlank() },
            memoryConfig = memoryConfig
        ) ?: return

        // 持久化新摘要
        repository.upsertSessionSummary(
            SessionSummary(
                sessionId = sessionId,
                summaryText = newSummaryText,
                lastSummarizedAt = now,
                messageCountAtLastSummary = allMessages.size
            )
        )

        // ── Step 2：增量 CRUD ────────────────────────────────────
        val currentMemories = repository.getAllMemories()
        val recentRawMessages = allMessages.takeLast(MEMORY_RECENT_RAW_COUNT)

        val crudJson = memoryEngine.generateCrudOps(
            currentMemories = currentMemories,
            summaryText = newSummaryText,
            recentRawMessages = recentRawMessages,
            memoryConfig = memoryConfig
        ) ?: return

        memoryEngine.applyMemoryCrudOps(crudJson, currentMemories, now)

        // ── Step 2.5：冷启动补关联（分批处理）──
        try {
            memoryEngine.batchBackfillAssociations(
                memoryConfig = memoryConfig,
                batchSize = 8,
                maxBatches = 3
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("ChatViewModel", "Association backfill failed: ${e.message}", e)
        }

        // 裁剪旧审计日志（30 天前）
        memoryEngine.pruneOldAuditLogs()
    }

    // ── 记忆辅助方法已迁移到 com.omnichat.memory.MemoryEngine ─────────

    companion object {
        private const val MEMORY_WINDOW_CHARS = 12_000           // 摘要窗口最大字符数
        private const val MEMORY_RECENT_RAW_COUNT = 20           // Step 2 额外传入的原始消息条数
        private const val MAX_TOOL_CALL_DEPTH = 10               // 工具调用最大递归深度，防止无限循环
    }

    /**
     * 解析模型能力。优先级：JSON 元数据 > models.dev 缓存。
     */
    fun parseModelCapabilities(modelId: String, providerId: Long = 0, json: org.json.JSONObject? = null): FetchedModel {
        // --- 1. 尝试从 Provider API JSON 元数据提取 ---
        val rawContext: Int = run {
            if (json == null) return@run 0
            json.optJSONObject("top_provider")?.optInt("context_length", 0)?.takeIf { it > 0 }
                ?: json.optInt("context_length", 0).takeIf { it > 0 }
                ?: json.optInt("context_window", 0).takeIf { it > 0 }
                ?: json.optInt("max_context_length", 0).takeIf { it > 0 }
                ?: 0
        }

        val rawMaxOutput: Int = run {
            if (json == null) return@run 0
            json.optJSONObject("top_provider")?.optInt("max_completion_tokens", 0)?.takeIf { it > 0 }
                ?: json.optInt("max_completion_tokens", 0).takeIf { it > 0 }
                ?: json.optInt("max_output_tokens", 0).takeIf { it > 0 }
                ?: 0
        }

        var vision = false
        if (json != null) {
            val arch = json.optJSONObject("architecture")
            val inputModalities = arch?.optJSONArray("input_modalities")
            if (inputModalities != null) {
                for (i in 0 until inputModalities.length()) {
                    if (inputModalities.optString(i).equals("image", ignoreCase = true)) {
                        vision = true
                        break
                    }
                }
            }
        }

        var thinking = false
        if (json != null) {
            val supportedParams = json.optJSONArray("supported_parameters")
            if (supportedParams != null) {
                for (i in 0 until supportedParams.length()) {
                    val param = supportedParams.optString(i)
                    if (param == "reasoning" || param == "include_reasoning") {
                        thinking = true
                        break
                    }
                }
            }
        }

        var toolUseFromJson: Boolean? = null
        if (json != null) {
            val supportedParams = json.optJSONArray("supported_parameters")
            if (supportedParams != null) {
                var hasTools = false
                var checkedParams = false
                for (i in 0 until supportedParams.length()) {
                    val param = supportedParams.optString(i)
                    checkedParams = true
                    if (param == "tools" || param == "tool_choice") {
                        hasTools = true
                        break
                    }
                }
                if (checkedParams) toolUseFromJson = hasTools
            }
        }

        // --- 2. 如果 JSON 元数据未覆盖，使用 models.dev 缓存补充 ---
        val devInfo = com.omnichat.network.ModelsDevCache.lookup(modelId)

        if (!vision) vision = devInfo?.hasVision == true
        if (!thinking) thinking = devInfo?.reasoning == true
        val toolUse = toolUseFromJson ?: devInfo?.toolCall ?: true

        val contextStr: String = run {
            if (rawContext > 0) {
                formatTokenCount(rawContext)
            } else {
                devInfo?.contextSize?.let { formatTokenCount(it) } ?: "128k"
            }
        }

        val maxOutputStr: String = run {
            if (rawMaxOutput > 0) {
                formatTokenCount(rawMaxOutput)
            } else {
                devInfo?.outputLimit?.let { formatTokenCount(it) } ?: ""
            }
        }

        return FetchedModel(
            providerId = providerId,
            modelId = modelId,
            contextSize = if (maxOutputStr.isNotEmpty()) "$contextStr / $maxOutputStr out" else contextStr,
            hasThinking = thinking,
            hasVision = vision,
            hasToolUse = toolUse
        )
    }

    private fun formatTokenCount(n: Int): String = when {
        n <= 0         -> ""
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1_000     -> "${n / 1_000}k"
        else           -> n.toString()
    }

    fun fetchModelsAndSave(endpoint: String, apiKey: String, providerId: Long, customHeaders: String = "{}") {
        viewModelScope.launch {
            isFetchingModels = true
            modelFetchError = null
            fetchedModels = emptyList()
            try {
                // 预加载 models.dev 缓存（用于能力检测的第二层）
                if (com.omnichat.network.ModelsDevCache.needsRefresh()) {
                    com.omnichat.network.ModelsDevCache.fetchAndCache()
                }

                val list = ApiClient.fetchOpenAIModels(endpoint, apiKey, customHeaders)
                if (list.isEmpty()) {
                    modelFetchError = getApplication<Application>().getString(R.string.error_model_fetch_failed)
                } else {
                    val parsedList = list.map { json -> 
                        val id = json.optString("id")
                        parseModelCapabilities(id, providerId, json) 
                    }
                    fetchedModels = parsedList
                    refreshCurrentModelVision()

                    if (providerId > 0) {
                        repository.deleteModelsByProvider(providerId)
                        parsedList.forEach { model ->
                            repository.insertFetchedModel(model)
                        }
                    }
                }
            } catch (e: Exception) {
                modelFetchError = e.localizedMessage
            } finally {
                isFetchingModels = false
            }
        }
    }

    fun clearFetchedModels() {
        fetchedModels = emptyList()
        modelFetchError = null
    }

    fun getModelsByProviderFlow(providerId: Long): Flow<List<FetchedModel>> {
        return repository.getModelsByProviderFlow(providerId)
    }

    fun createOrUpdateConfig(config: ModelConfig, modelsToSave: List<FetchedModel> = emptyList()) {
        viewModelScope.launch {
            val generatedId = repository.insertConfig(config)
            if (modelsToSave.isNotEmpty()) {
                repository.deleteModelsByProvider(generatedId)
                modelsToSave.forEach { model ->
                    repository.insertFetchedModel(model.copy(providerId = generatedId))
                }
            }
        }
    }

    fun deleteConfig(config: ModelConfig) {
        viewModelScope.launch {
            repository.deleteConfig(config)
        }
    }

    fun setDefaultProvider(id: Long) {
        viewModelScope.launch {
            repository.setDefaultProvider(id)
        }
    }

    fun updateMemoryModelId(modelId: String, providerId: Long = 0L) {
        viewModelScope.launch {
            val provider = repository.getDefaultProvider()
            if (provider != null) {
                repository.updateConfig(provider.copy(memoryModelId = modelId, memoryProviderId = providerId))
            } else {
                val allConfigs = repository.getAllConfigs()
                allConfigs.forEach { config ->
                    repository.updateConfig(config.copy(memoryModelId = modelId, memoryProviderId = providerId))
                }
            }
        }
    }

    fun deleteMemoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteMemoryById(id)
        }
    }

    fun togglePinMemory(item: MemoryItem) {
        viewModelScope.launch {
            repository.setPinned(item.id, !item.pinned)
        }
    }

    fun insertManualMemory(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.insertMemory(MemoryItem(content = text))
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            repository.deleteAllUnpinnedMemories()
        }
    }

    /**
     * 为没有标签的记忆批量补打标签。
     * 调用 memory 模型对 untagged memories 进行分类，然后更新数据库。
     */
    fun manualBackfillTags() {
        if (isBackfillingTags) return
        viewModelScope.launch {
            isBackfillingTags = true
            try {
                val untagged = repository.getAllMemories().filter { it.tags.isBlank() }
                if (untagged.isEmpty()) return@launch

                val defaultProvider = repository.getDefaultProvider() ?: return@launch
                val memoryConfig = run {
                    val memoryProviderId = defaultProvider.memoryProviderId
                    val memoryProvider = if (memoryProviderId > 0L) {
                        repository.getConfigById(memoryProviderId) ?: defaultProvider
                    } else {
                        defaultProvider
                    }
                    memoryProvider.copy(
                        selectedModelId = defaultProvider.memoryModelId.takeIf { it.isNotBlank() }
                            ?: defaultProvider.selectedModelId
                    )
                }

                // Tag rules: free-form, English ≤10 chars, Chinese ≤5 chars
                for (batch in untagged.chunked(20)) {
                    val itemsText = batch.joinToString("\n") { "${it.id}. ${it.content}" }
                    val prompt = """Assign 1-2 short tags to each memory item.

Tag rules:
- English tags: max 10 characters (e.g., "preference", "coding", "workflow")
- Chinese tags: max 5 characters (e.g., "偏好", "技能", "项目")
- Choose descriptive semantic categories

Items:
$itemsText

Output a JSON array where each element is {"id": <number>, "tags": ["tag1", ...]}.
Only output the JSON array, nothing else."""

                    val response = ApiClient.executeCompletion(memoryConfig, "You are a memory tag classifier.", prompt)
                        ?.trim() ?: continue

                    val cleaned = response.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    try {
                        val arr = org.json.JSONArray(cleaned)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val id = obj.optLong("id", 0)
                            if (id <= 0) continue
                            val tags = memoryEngine.parseTagsFromJson(obj.optJSONArray("tags"))
                            if (tags.isNotBlank()) {
                                val existing = batch.find { it.id == id }
                                if (existing != null) {
                                    repository.updateMemory(existing.copy(tags = tags))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // 解析失败跳过此批次
                    }
                }
            } finally {
                isBackfillingTags = false
            }
        }
    }

    /**
     * 执行记忆整合优化：使用副模型对所有记忆进行全量分析、去重、合并、分类、打置信分。
     * 避免记忆条目过多导致 Agent 记忆错乱。
     */
    fun consolidateMemories() {
        if (isConsolidating) return
        viewModelScope.launch {
            isConsolidating = true
            lastConsolidationSummary = null
            try {
                val result = memoryEngine.consolidateMemories(force = false)
                lastConsolidationSummary = result.summary
                Log.i("ChatViewModel", "Memory consolidation: ${result.summary}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("ChatViewModel", "Memory consolidation failed: ${e.message}")
                lastConsolidationSummary = "Error: ${e.message}"
            } finally {
                isConsolidating = false
            }
        }
    }

    fun insertTemplate(template: PromptTemplate) {
        viewModelScope.launch {
            repository.insertTemplate(template)
        }
    }

    fun selectTemplate(id: Long) {
        viewModelScope.launch {
            repository.setActiveTemplate(id)
        }
    }

    fun deleteTemplate(template: PromptTemplate) {
        viewModelScope.launch {
            repository.deleteTemplate(template)
        }
    }

    // ── 审计历史 ────────────────────────────────────────────────────────
    fun loadAuditHistory(limit: Int = 100) {
        viewModelScope.launch {
            try {
                val history = repository.getRecentAuditActivity(limit)
                _auditHistory.value = history
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "加载审计历史失败", e)
            }
        }
    }

    // ── 记忆关联查询 ──────────────────────────────────────────────────
    suspend fun getOutgoingAssociations(memoryId: Long): List<com.omnichat.data.MemoryAssociation> {
        return repository.getOutgoingAssociations(memoryId)
    }

    suspend fun getIncomingAssociations(memoryId: Long): List<com.omnichat.data.MemoryAssociation> {
        return repository.getIncomingAssociations(memoryId)
    }

    suspend fun getAssociationCount(memoryId: Long): Int {
        return repository.getAssociationCount(memoryId)
    }

    // ── 提示词模板查询 ────────────────────────────────────────────────
    suspend fun getTemplateById(id: Long): com.omnichat.data.PromptTemplate? {
        return repository.getTemplateById(id)
    }

    private suspend fun seedDatabaseIfNeeded() = withContext(Dispatchers.IO) {
        val templates = repository.getAllTemplates()
        if (templates.isEmpty()) {
            repository.insertTemplate(
                PromptTemplate(
                    name = getApplication<Application>().getString(R.string.default_assistant_name),
                    templateText = "You are a friendly, highly intelligent assistant. Adopt a constructive tone and tailor responses precisely to the user's context.\n\n" +
                            "Use the historical facts & preferences below (Cross-Session Memory) to personalize your replies:\n" +
                            "[CROSS_SESSION_MEMORY]\n\n" +
                            "You also have access to the following local MCP tools via Model Context Protocol. If you need to use them, please describe what you want to do:\n" +
                            "[MCP_TOOLS]",
                    isActive = true
                )
            )
        }

        val configs = repository.getAllConfigs()
        if (configs.isEmpty()) {
            repository.insertConfig(
                ModelConfig(
                    name = "OpenAI Provider Default",
                    endpoint = "https://api.openai.com/v1",
                    apiKey = "",
                    selectedModelId = "gpt-4o",
                    memoryModelId = "gpt-4o-mini",
                    isDefaultProvider = true
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        AskUserManager.clearAll()
    }
}
