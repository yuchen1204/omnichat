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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.omnichat.R
import com.omnichat.mcp.AskUserManager
import com.omnichat.StreamingForegroundService

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private val runtimeManager = com.omnichat.mcp.McpRuntimeManager.getInstance(application)
    private val memoryEngine = com.omnichat.memory.MemoryEngine(repository, ApiClient)
    private val agentExecutor = com.omnichat.agent.AgentExecutor.getInstance(application, repository)

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

    // Memory items flow
    val memories: StateFlow<List<MemoryItem>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    var isMemorySyncing by mutableStateOf(false)
        private set

    var isBackfillingTags by mutableStateOf(false)
        private set

    // BUG-016: 使用 Mutex 替代非原子的 boolean 检查，防止并发 triggerMemorySync
    private val memorySyncMutex = kotlinx.coroutines.sync.Mutex()

    // Temporary list of models fetched from endpoints
    var fetchedModels by mutableStateOf<List<FetchedModel>>(emptyList())
        private set
    var modelFetchError by mutableStateOf<String?>(null)
        private set
    var isFetchingModels by mutableStateOf(false)
        private set

    /** 切换当前使用的 Provider 和模型，持久化到数据库，重启后生效 */
    fun setSessionOverrideModel(provider: ModelConfig, modelId: String) {
        viewModelScope.launch {
            // BUG-017: 使用 DAO 的 @Transaction 方法保证原子性，避免手动迭代的竞态窗口
            // 先更新 selectedModelId，再切换默认 provider
            repository.updateConfig(provider.copy(selectedModelId = modelId))
            repository.setDefaultProvider(provider.id)
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
        }
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

    /**
     * User actions: sends a message and starts streaming response using Primary Chat Model
     */
    fun sendMessage(text: String) {
        sendMessageWithImage(text, null)
    }

    /**
     * 发送带有图片的消息。
     *
     * @param text 文本内容
     * @param imagePath 图片本地路径（可选）
     */
    fun sendMessageWithImage(text: String, imagePath: String?) {
        val sessionId = _selectedSessionId.value ?: return
        if ((text.isBlank() && imagePath.isNullOrBlank()) || isStreaming) return

        viewModelScope.launch {
            // Apply hook to user message
            val processedText = com.omnichat.hooks.HookManager.dispatchBeforeSendMessage(text)
            if (processedText == null) {
                // Hook cancelled the message sending
                return@launch
            }

            // 1. Insert User Message (with image if provided)
            val userMsg = Message(
                sessionId = sessionId,
                role = "user",
                content = processedText,
                imagePath = imagePath
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

            // Generate title using memory model of default provider
            val currentSession = sessions.value.find { it.id == sessionId }
            val zhDefault = getApplication<Application>().getString(R.string.default_session_title)
            val newSession = getApplication<Application>().getString(R.string.new_session)
            if (currentSession != null && (currentSession.title.startsWith(zhDefault) || currentSession.title.startsWith(newSession)
                    || currentSession.title.startsWith("Aesthetic Conversation") || currentSession.title.startsWith("New Session"))) {
                try {
                    val defaultForTitle = repository.getDefaultProvider()
                    val titleConfig = if (defaultForTitle != null) {
                        val memoryProviderId = defaultForTitle.memoryProviderId
                        val memoryProvider = if (memoryProviderId > 0L) {
                            repository.getConfigById(memoryProviderId) ?: defaultForTitle
                        } else {
                            defaultForTitle
                        }
                        memoryProvider.copy(
                            selectedModelId = defaultForTitle.memoryModelId.takeIf { it.isNotBlank() }
                                ?: defaultForTitle.selectedModelId
                        )
                    } else {
                        providerConfig
                    }
                    val prompt = "Generate a very short (max 10 words) descriptive title for a conversation that starts with the attached user message. Return ONLY the title without quotes, markdown headers, or other text."
                    val generatedTitle = ApiClient.executeCompletion(titleConfig, prompt, text)
                    val finalTitle = generatedTitle?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
                        ?: (if (text.length > 15) text.take(15) + "..." else text)
                    repository.updateSessionTitle(sessionId, finalTitle.replace("\n", ""))
                } catch(e: Exception) {
                    val shortenedText = if (text.length > 15) text.take(15) + "..." else text
                    repository.updateSessionTitle(sessionId, shortenedText.replace("\n", ""))
                }
            }

            val activeTemplate = repository.getActiveTemplate()
            val customSystemPrompt = activeTemplate?.templateText ?: "You are a helpful assistant."

            // 等待正在启动的 MCP 服务就绪，确保获取到正确的工具列表
            runtimeManager.waitForStartingServersToFinish()

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt, processedText)

            startAssistantResponse(sessionId, providerConfig, finalSystemPrompt)
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

        // 注入已完成的 subAgent 任务摘要
        val currentSessionId = _selectedSessionId.value
        if (currentSessionId != null) {
            val completedTasks = agentExecutor.getCompletedTasksForSession(currentSessionId)
            if (completedTasks.isNotEmpty()) {
                finalSystemPrompt += "\n\n<!-- COMPLETED SUBAGENT TASKS -->"
                finalSystemPrompt += "\nThe following subAgent tasks have completed. Their results are available for reference:\n"
                completedTasks.forEach { task ->
                    val resultPreview = task.result?.take(200) ?: "(no result)"
                    finalSystemPrompt += "- [${task.agentType}] ${task.taskDescription.take(50)}... (taskId: ${task.taskId})\n"
                    finalSystemPrompt += "  Result summary: $resultPreview\n"
                }
            }
        }

        return finalSystemPrompt
    }

    fun retryMessage(message: Message) {
        if (isStreaming) return
        viewModelScope.launch {
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
    }

    private suspend fun startAssistantResponse(sessionId: Long, config: ModelConfig, systemPrompt: String, toolCallDepth: Int = 0) {
        val messageHistory = repository.getMessagesBySession(sessionId)
        val openAiTools = runtimeManager.getAllToolsAsOpenAiFormat()

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

        ApiClient.executeStreamingChat(config, systemPrompt, messageHistory, openAiTools, getApplication())
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

        // 最后一次同步更新
        updateStreamingStates(accumulatedText)

        val finalContent = if (accumulatedText.trim() == "null") "" else accumulatedText

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
        
        val wasOnlyToolCalls = processedContent.isEmpty() && accumulatedToolCalls.isNotEmpty()
        // 清理流式状态，但保持 isStreaming=true 直到工具调用处理完毕
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
     * 解析模型能力。优先级：JSON 元数据 > 模型 ID 规则推断。
     */
    fun parseModelCapabilities(modelId: String, providerId: Long = 0, json: org.json.JSONObject? = null): FetchedModel {
        val lower = modelId.lowercase()

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

        fun formatTokenCount(n: Int): String = when {
            n <= 0      -> ""
            n >= 1_000_000 -> "${n / 1_000_000}M"
            n >= 1_000     -> "${n / 1_000}k"
            else           -> n.toString()
        }

        val contextStr: String = if (rawContext > 0) {
            formatTokenCount(rawContext)
        } else {
            when {
                lower.contains("gemini") -> "1M"
                lower.contains("claude-3") -> "200k"
                lower.contains("gpt-4o") -> "128k"
                lower.contains("deepseek") -> "64k"
                else -> "128k"
            }
        }

        val maxOutputStr: String = formatTokenCount(rawMaxOutput)

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
        if (!vision) {
            vision = lower.contains("vision") || lower.contains("gpt-4o") || lower.contains("claude-3") || lower.contains("gemini")
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
        if (!thinking) {
            thinking = lower.contains("r1") || lower.contains("o1") || lower.contains("reasoner")
        }

        var toolUse: Boolean? = null
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
                if (checkedParams) toolUse = hasTools
            }
        }
        if (toolUse == null) {
            toolUse = !lower.contains("o1-preview")
        }

        return FetchedModel(
            providerId = providerId,
            modelId = modelId,
            contextSize = if (maxOutputStr.isNotEmpty()) "$contextStr / $maxOutputStr out" else contextStr,
            hasThinking = thinking,
            hasVision = vision,
            hasToolUse = toolUse!!
        )
    }

    fun fetchModelsAndSave(endpoint: String, apiKey: String, providerId: Long, customHeaders: String = "{}") {
        viewModelScope.launch {
            isFetchingModels = true
            modelFetchError = null
            fetchedModels = emptyList()
            try {
                val list = ApiClient.fetchOpenAIModels(endpoint, apiKey, customHeaders)
                if (list.isEmpty()) {
                    modelFetchError = getApplication<Application>().getString(R.string.error_model_fetch_failed)
                } else {
                    val parsedList = list.map { json -> 
                        val id = json.optString("id")
                        parseModelCapabilities(id, providerId, json) 
                    }
                    fetchedModels = parsedList
                    
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
