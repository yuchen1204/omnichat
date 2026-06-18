package com.omnichat.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnichat.data.*
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
import com.omnichat.ui.screens.SubAgentTaskUiState
import com.omnichat.ui.screens.TaskStatus
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.delay

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private val runtimeManager = com.omnichat.mcp.McpRuntimeManager.getInstance(application)
    private val memoryEngine = com.omnichat.memory.MemoryEngine(repository, ApiClient)

    // Active session selection state
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    // Sessions flow
    val sessions: StateFlow<List<Session>> = repository.allSessions
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

    // Streaming job reference — used to cancel streaming when user taps stop
    private var streamingJob: Job? = null

    // Edit message state — holds the message ID being edited (null = not editing)
    var editingMessageId by mutableStateOf<Long?>(null)
        private set

    var isMemorySyncing by mutableStateOf(false)
        private set

    var isBackfillingTags by mutableStateOf(false)
        private set

    /** Active SubAgent tasks for current session — drives in-chat status cards */
    val activeTasks = mutableStateMapOf<String, SubAgentTaskUiState>()

    // BUG-016: 使用 Mutex 替代非原子的 boolean 检查，防止并发 triggerMemorySync
    private val memorySyncMutex = kotlinx.coroutines.sync.Mutex()

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

            // Automatically select the first session if available
            repository.allSessions.firstOrNull()?.firstOrNull()?.let { firstSession ->
                _selectedSessionId.value = firstSession.id
            } ?: run {
                // Pre-create an initial default session
                createNewSession(getApplication<Application>().getString(R.string.default_session_title_display))
            }

            // 加载已有模型数据并刷新视觉能力状态
            fetchedModels = repository.getAllFetchedModels()
            refreshCurrentModelVision()


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
        if ((text.isBlank() && imagePaths.isEmpty()) || isStreaming) return

        viewModelScope.launch {
            // Apply hook to user message
            val processedText = com.omnichat.hooks.HookManager.dispatchBeforeSendMessage(text)
            if (processedText == null) {
                // Hook cancelled the message sending
                return@launch
            }

            // 1. Insert User Message (with images if provided)
            val pathsJson = if (imagePaths.isNotEmpty()) {
                org.json.JSONArray(imagePaths).toString()
            } else null

            val userMsg = Message(
                sessionId = sessionId,
                role = "user",
                content = processedText,
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

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, processedText)

            // Launch streaming in a separate coroutine so we can cancel it via stopStreaming()
            streamingJob = viewModelScope.launch(Dispatchers.Default) {
                startAssistantResponse(sessionId, providerConfig, finalSystemPrompt)
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
        currentStreamingThinking = ""
        currentStreamingBody = ""
        isThinkingFinished = true

        // 停止前台服务
        StreamingForegroundService.complete(getApplication())
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
        startAssistantResponse(sessionId, providerConfig, finalSystemPrompt)

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
                // Auto-remove card after 5 seconds
                viewModelScope.launch {
                    delay(5000)
                    activeTasks.remove(event.taskId)
                }
            }
        }
    }

    private suspend fun generateSystemPrompt(customSystemPrompt: String, userMessage: String = ""): String {
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
        if (isStreaming) return
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
        if (isStreaming) return

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

            streamingJob = viewModelScope.launch(Dispatchers.Default) {
                startAssistantResponse(sessionId, providerConfig, finalSystemPrompt)
            }
        }
    }

    fun retryMessage(message: Message) {
        if (isStreaming) return
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

            // 3. Re-trigger assistant response
            startAssistantResponse(message.sessionId, providerConfig, finalSystemPrompt)
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

        // 构造标题生成的用户内容：用户消息 + AI 回复摘要
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

        val titleContent = buildString {
            if (userText.isNotBlank()) append("User: $userText")
            if (assistantText.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("Assistant: $assistantText")
            }
        }
        android.util.Log.d("TitleGen", "titleContent=$titleContent")
        if (titleContent.isBlank()) return

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
            android.util.Log.d("TitleGen", "Calling executeCompletion with model=${titleConfig.selectedModelId}")
            val prompt = "Generate a very short (max 10 words) descriptive title for the following conversation. Return ONLY the title without quotes, markdown headers, or other text."
            val generatedTitle = ApiClient.executeCompletion(titleConfig, prompt, titleContent)
            android.util.Log.d("TitleGen", "generatedTitle=$generatedTitle")
            val finalTitle = generatedTitle?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
                ?: if (titleContent.length > 15) titleContent.take(15) + "..." else titleContent
            android.util.Log.d("TitleGen", "finalTitle=$finalTitle")
            repository.updateSessionTitle(sessionId, finalTitle.replace("\n", ""))
        } catch (e: Exception) {
            android.util.Log.e("TitleGen", "Error generating title", e)
        }
    }

    private suspend fun startAssistantResponse(sessionId: Long, config: ModelConfig, systemPrompt: String, toolCallDepth: Int = 0) {
        val messageHistory = repository.getMessagesBySession(sessionId)
        val openAiTools = runtimeManager.getAllToolsAsOpenAiFormat()
        val sessionThinkingEffort = repository.getSessionById(sessionId)?.thinkingEffort

        isStreaming = true
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
        var accumulatedText = ""
        var accumulatedReasoningContent = ""
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

        ApiClient.executeStreamingChat(config, systemPrompt, messageHistory, openAiTools, getApplication(), thinkingEffortOverride = sessionThinkingEffort)
            .collect { chunk ->
                if (errorReceived) return@collect
                if (chunk.startsWith("ERROR:")) {
                    accumulatedText += "\n$chunk"
                    updateStreamingStates(accumulatedText)
                    errorReceived = true
                } else if (chunk.startsWith("INFO:")) {
                    accumulatedText += "\n$chunk"
                    updateStreamingStates(accumulatedText)
                } else if (chunk == "RETRY_RESET:") {
                    accumulatedText = ""
                    accumulatedReasoningContent = ""
                    accumulatedToolCalls.clear()
                    updateStreamingStates("")
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
                            
                            val function = item.optJSONObject("function")
                            if (function != null) {
                                val existingFunc = existing.optJSONObject("function") ?: org.json.JSONObject().also { existing.put("function", it) }
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
                } else if (chunk.startsWith("REASONING:")) {
                    accumulatedReasoningContent += chunk.substringAfter("REASONING:")
                    currentStreamingThinking = accumulatedReasoningContent
                    isThinkingFinished = false
                } else {
                    if (chunk != "null") {
                        accumulatedText += chunk
                        val now = System.currentTimeMillis()
                        // 节流更新 UI，每 50ms 更新一次
                        if (now - lastUiUpdateTime > 50) {
                            updateStreamingStates(accumulatedText)
                            lastUiUpdateTime = now
                        }
                    }
                }
            }

        // 最后一次同步更新（将 reasoning_content 合并为 <think> 标签，确保持久化后 parseMessageContent 可正确解析）
        val finalAccumulatedText = if (accumulatedReasoningContent.isNotEmpty()) {
            "<think>${accumulatedReasoningContent}</think>$accumulatedText"
        } else {
            accumulatedText
        }
        updateStreamingStates(finalAccumulatedText)

        val finalContent = if (finalAccumulatedText.trim() == "null") "" else finalAccumulatedText

        // Apply hook to assistant response
        val processedContent = if (finalContent.isNotEmpty()) {
            com.omnichat.hooks.HookManager.dispatchAfterReceiveResponse(finalContent)
        } else {
            finalContent
        }

        // 1. Save assistant text response AND tool calls
        if (processedContent.isNotEmpty() || accumulatedToolCalls.isNotEmpty()) {
            val toolCallsJson = if (accumulatedToolCalls.isNotEmpty()) {
                val arr = org.json.JSONArray()
                accumulatedToolCalls.values.forEach { arr.put(it) }
                arr.toString()
            } else null
            
            repository.insertMessage(
                Message(
                    sessionId = sessionId,
                    role = "assistant",
                    content = processedContent,
                    toolCallsJson = toolCallsJson
                )
            )
        }

        // 消息已入库，立即停止流式气泡渲染，防止与 BubbleMessage 同时显示导致重复
        isStreaming = false

        // 首次回复后生成会话标题（结合用户第一条消息和 AI 第一条回复）
        android.util.Log.d("TitleGen", "After assistant response: toolCallDepth=$toolCallDepth, sessionId=$sessionId")
        if (toolCallDepth == 0) {
            generateSessionTitle(sessionId, processedContent)
        }
        
        val wasOnlyToolCalls = processedContent.isEmpty() && accumulatedToolCalls.isNotEmpty()
        // 清理流式状态
        currentStreamingThinking = ""
        currentStreamingBody = ""
        isThinkingFinished = true

        // 2. Process Tool Calls if any
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
                    repository.insertMessage(Message(sessionId = sessionId, role = "tool", content = "Tool not found", toolCallId = callId))
                    hasNewResults = true
                }
            }

            if (hasNewResults) {
                // Trigger the follow-up turn with depth limit to prevent infinite loops
                if (toolCallDepth < MAX_TOOL_CALL_DEPTH) {
                    startAssistantResponse(sessionId, config, systemPrompt, toolCallDepth + 1)
                } else {
                    repository.insertMessage(
                        Message(sessionId = sessionId, role = "assistant", content = "⚠️ " + getApplication<Application>().getString(R.string.error_tool_depth_exceeded, MAX_TOOL_CALL_DEPTH))
                    )
                }
            }
        }

        if (!wasOnlyToolCalls && finalContent.isNotEmpty()) {
            triggerMemorySync()
        }
        } catch (e: CancellationException) {
            // 用户通过 stopStreaming() 主动终止 — 部分回复已在 stopStreaming() 中保存，
            // 此处不再重复保存，仅重新抛出以保持协程取消语义
            throw e
        } finally {
            // BUG-015: 确保 isStreaming 在所有路径上都被重置，防止 UI 永久卡在加载状态
            isStreaming = false
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
        val sessionId = _selectedSessionId.value ?: return
        viewModelScope.launch {
            // BUG-016: 使用 Mutex 保证原子性，避免并发调用同时通过 guard 检查
            if (!memorySyncMutex.tryLock()) return@launch
            isMemorySyncing = true
            try {
                val memoryConfig = memoryEngine.getMemoryModelConfig() ?: return@launch

                val allMessages = repository.getMessagesBySession(sessionId)
                if (allMessages.size < 2) return@launch

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
                    if (!memoryEngine.shouldRunSync(force, timeSinceLast, newMsgCount, newCharsTotal)) return@launch
                }

                // 衰减非 pinned 记忆的置信度
                memoryEngine.applyConfidenceDecay(now)

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
                ) ?: return@launch

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
                ) ?: return@launch

                memoryEngine.applyMemoryCrudOps(crudJson, currentMemories, now)

                // ── Step 2.5：冷启动补关联（阈值提高到 5，因为即时关联已覆盖新记忆）──
                try {
                    val unassociated = repository.getUnassociatedMemories(COLD_START_ASSOC_LIMIT)
                    if (unassociated.size >= 5) {
                        val backfillJson = memoryEngine.generateAssociationsForUnassociated(unassociated, memoryConfig)
                        if (backfillJson != null) {
                            memoryEngine.applyAssociationsFromJson(backfillJson, unassociated.map { it.id }.toSet())
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("ChatViewModel", "Association backfill failed: ${e.message}", e)
                }

                // 裁剪旧审计日志（30 天前）
                memoryEngine.pruneOldAuditLogs()

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("ChatViewModel", "Memory sync failed: ${e.message}", e)
            } finally {
                isMemorySyncing = false
                memorySyncMutex.unlock()
            }
        }
    }

    // ── 记忆辅助方法已迁移到 com.omnichat.memory.MemoryEngine ─────────

    companion object {
        private const val MEMORY_WINDOW_CHARS = 12_000           // 摘要窗口最大字符数
        private const val MEMORY_RECENT_RAW_COUNT = 20           // Step 2 额外传入的原始消息条数
        private const val MAX_TOOL_CALL_DEPTH = 10               // 工具调用最大递归深度，防止无限循环
        private const val COLD_START_ASSOC_LIMIT = 20
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
