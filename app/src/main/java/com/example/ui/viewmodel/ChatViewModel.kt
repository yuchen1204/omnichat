package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.ApiClient
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.mcp.AskUserManager

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)
    private val runtimeManager = com.example.mcp.McpRuntimeManager.getInstance(application)

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
            // 先把所有 provider 的 isDefaultProvider 清掉
            val allConfigs = repository.getAllConfigs()
            allConfigs.forEach { config ->
                if (config.isDefaultProvider) {
                    repository.updateConfig(config.copy(isDefaultProvider = false))
                }
            }
            // 将选中的 provider 设为默认，并更新 selectedModelId
            repository.updateConfig(provider.copy(isDefaultProvider = true, selectedModelId = modelId))
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
                createNewSession("探讨与交谈 (Aesthetic Conversation)")
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
                    createNewSession("探讨与交谈")
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
            val processedText = com.example.hooks.HookManager.dispatchBeforeSendMessage(text)
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
                        content = "错误：未设置默认提供商。请在“模型设置”菜单中添加 Provider 并将其设为默认。"
                    )
                )
                return@launch
            }

            // Generate title using memory model of default provider
            val currentSession = sessions.value.find { it.id == sessionId }
            if (currentSession != null && (currentSession.title.startsWith("探讨与交谈") || currentSession.title.startsWith("新会话"))) {
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

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt)

            startAssistantResponse(sessionId, providerConfig, finalSystemPrompt)
        }
    }

    private suspend fun generateSystemPrompt(customSystemPrompt: String): String {
        // 3. Fetch physical long-term memories list (single DB call, reuse for both injection and count)
        val allMemories = repository.getAllMemories()
        val localMemories = allMemories.take(MEMORY_INJECT_LIMIT)
        val memoriesText = if (localMemories.isEmpty()) {
            "无 (None recorded)"
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
        val dateTimeStr = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm (EEEE, z)", Locale.CHINESE))
        finalSystemPrompt += "\n\n<!-- SYSTEM TIME: 当前真实时间为 $dateTimeStr。请以此为准回答所有涉及日期、时间、今天、现在、最近等时间相关的问题，不要凭训练数据猜测当前时间。 -->"

        // Hidden formatting instruction: always respond using Markdown
        finalSystemPrompt += "\n\n<!-- FORMATTING RULE: You MUST always format your responses using Markdown. Use headers, bold, italic, code blocks, lists, tables, and other Markdown elements as appropriate to make your response clear and well-structured. Never reply with plain unformatted text. -->"

        // Hidden memory search instruction
        val totalMemoryCount = allMemories.size
        if (totalMemoryCount > MEMORY_INJECT_LIMIT) {
            finalSystemPrompt += "\n\n<!-- MEMORY SEARCH HINT: The cross-session memory above only shows the top $MEMORY_INJECT_LIMIT entries (by confidence) out of $totalMemoryCount total stored memories. If the user asks about something not covered by the injected memories, proactively call the [search_memory] tool with relevant keywords to retrieve additional matching memories before answering. -->"
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

            val finalSystemPrompt = generateSystemPrompt(customSystemPrompt)

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
                    isStreaming = false
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
                            if (id.isNotEmpty()) existing.put("id", id)
                            
                            val function = item.optJSONObject("function")
                            if (function != null) {
                                val existingFunc = existing.optJSONObject("function") ?: org.json.JSONObject().also { existing.put("function", it) }
                                val name = function.optString("name")
                                if (name.isNotEmpty()) existingFunc.put("name", name)
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
            com.example.hooks.HookManager.dispatchAfterReceiveResponse(finalContent)
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
                        Message(sessionId = sessionId, role = "assistant", content = "⚠️ 工具调用深度超过限制（${MAX_TOOL_CALL_DEPTH}），已自动停止以防止无限循环。")
                    )
                }
            }
        }

        // 所有处理完毕后才关闭 streaming 状态
        isStreaming = false

        if (!wasOnlyToolCalls && finalContent.isNotEmpty()) {
            triggerMemorySync()
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
     *     {"op": "ADD",    "content": "..."},
     *     {"op": "UPDATE", "id": 7, "content": "..."},
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
            if (isMemorySyncing) return@launch
            isMemorySyncing = true
            try {
                val defaultProvider = repository.getDefaultProvider()
                if (defaultProvider == null) {
                    isMemorySyncing = false
                    return@launch
                }

                // 构建副模型配置
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

                val allMessages = repository.getMessagesBySession(sessionId)
                if (allMessages.size < 2) {
                    isMemorySyncing = false
                    return@launch
                }

                // 读取上次摘要记录，判断是否需要运行
                val prevSummary = repository.getSessionSummary(sessionId)
                val now = System.currentTimeMillis()
                val lastSummarizedAt = prevSummary?.lastSummarizedAt ?: 0L
                val msgCountAtLast = prevSummary?.messageCountAtLastSummary ?: 0
                val newMsgCount = allMessages.size - msgCountAtLast
                val timeSinceLast = now - lastSummarizedAt

                // force=true（手动触发）时跳过节流检查
                val shouldRun = force || timeSinceLast >= MEMORY_INTERVAL_MS || newMsgCount >= NEW_MESSAGES_THRESHOLD
                if (!shouldRun) {
                    isMemorySyncing = false
                    return@launch
                }

                // 预检：新消息内容太少（如全是"好的/嗯/谢谢"）则跳过，避免无效 API 调用
                if (!force) {
                    val newMessages = allMessages.drop(msgCountAtLast)
                    val newCharsTotal = newMessages.sumOf { it.content.length }
                    if (newCharsTotal < MIN_NEW_CHARS_THRESHOLD) {
                        isMemorySyncing = false
                        return@launch
                    }
                }

                // ── Step 1：生成本会话的新滚动摘要 ──────────────────────
                // 按字符数截断而非固定条数，避免长消息撑爆小模型上下文
                val recentMessages = run {
                    var charCount = 0
                    allMessages.asReversed().takeWhile { msg ->
                        charCount += msg.content.length
                        charCount <= MEMORY_WINDOW_CHARS
                    }.reversed()
                }
                val dialogueFormatted = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }
                val prevSummaryText = prevSummary?.summaryText?.takeIf { it.isNotBlank() }

                val summarySystemPrompt =
                    "You are a conversation analyst. Produce a compact summary of the conversation. " +
                    "Cover two aspects:\n" +
                    "1. Topics & conclusions: what was discussed, decisions made, problems solved.\n" +
                    "2. User signals: any preferences, habits, skills, tools, dislikes, or personal context " +
                    "the user revealed — even implicitly (e.g. choice of language, frustration with a tool, " +
                    "repeated patterns). Be specific, not generic.\n" +
                    "Aim for 4-10 sentences total. No filler or meta-commentary."

                val summaryUserQuery = buildString {
                    if (prevSummaryText != null) {
                        append("Previous summary (earlier in this session):\n###\n$prevSummaryText\n###\n\n")
                    }
                    append("Recent messages (last ${recentMessages.size}):\n###\n$dialogueFormatted\n###\n\n")
                    append("Produce an updated summary incorporating both. Return ONLY the summary text.")
                }

                val newSummaryText = ApiClient.executeCompletion(memoryConfig, summarySystemPrompt, summaryUserQuery)
                    ?.trim() ?: run {
                    isMemorySyncing = false
                    return@launch
                }

                // 持久化新摘要
                repository.upsertSessionSummary(
                    SessionSummary(
                        sessionId = sessionId,
                        summaryText = newSummaryText,
                        lastSummarizedAt = now,
                        messageCountAtLastSummary = allMessages.size
                    )
                )

                // ── Step 2：增量 CRUD — LLM 返回操作列表，客户端事务性 apply ──
                val currentMemories = repository.getAllMemories()
                val memoriesFormatted = if (currentMemories.isEmpty()) {
                    "No existing facts recorded."
                } else {
                    currentMemories.joinToString("\n") { item ->
                        val pinnedTag = if (item.pinned) " [PINNED]" else ""
                        "${item.id}. (confidence=${item.confidence}${pinnedTag}) ${item.content}"
                    }
                }

                // 最近原始消息片段（最多 MEMORY_RECENT_RAW_COUNT 条），作为摘要的补充
                // 让 LLM 能看到未经压缩的原始信号，提升偏好提炼准确性
                val recentRawSnippet = allMessages.takeLast(MEMORY_RECENT_RAW_COUNT)
                    .joinToString("\n") { "${it.role}: ${it.content}" }

                val factsSystemPrompt = """
You are a User Preference & Persona Synthesizer.
Your job: maintain a list of durable, cross-session personal facts about the user.
Focus ONLY on stable, reusable facts: preferences, skills, habits, goals, dislikes, setup/environment details.
Ignore transient topics (e.g., a one-off question with no lasting relevance).

You will receive:
- Existing facts (each with an id and confidence score; [PINNED] items must NOT be deleted or updated)
- A conversation summary (may compress details)
- Recent raw messages (ground truth — use these to catch signals the summary may have missed)

Output a JSON object with an "ops" array. Each op must be one of:
  {"op": "ADD",       "content": "<one short sentence>"}
  {"op": "UPDATE",    "id": <existing_id>, "content": "<revised sentence>"}
  {"op": "REINFORCE", "id": <existing_id>}
  {"op": "DELETE",    "id": <existing_id>}

Rules:
- ADD new facts not yet captured. IMPORTANT: before adding, check if an existing fact already covers the same information — if so, use REINFORCE or UPDATE instead of ADD. Avoid semantic duplicates.
- UPDATE facts that need revision (do NOT update [PINNED] items).
- REINFORCE facts confirmed again without change (boosts confidence).
- DELETE facts that are clearly contradicted or permanently irrelevant (do NOT delete [PINNED] items).
- If nothing changed, return {"ops": []}.
- Return ONLY the raw JSON object, no markdown fences, no commentary.
""".trimIndent()

                val factsUserQuery = buildString {
                    append("Existing facts:\n###\n$memoriesFormatted\n###\n\n")
                    append("Conversation summary:\n###\n$newSummaryText\n###\n\n")
                    append("Recent raw messages (last ${recentRawSnippet.lines().size} lines):\n###\n$recentRawSnippet\n###\n\n")
                    append("Output the ops JSON now.")
                }

                val crudJson = ApiClient.executeCompletion(memoryConfig, factsSystemPrompt, factsUserQuery)
                    ?.trim() ?: return@launch  // API 失败 → 保留旧记忆，直接退出

                // 解析并 apply CRUD ops（解析失败则整体放弃，旧记忆不受影响）
                applyMemoryCrudOps(crudJson, currentMemories, now)

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isMemorySyncing = false
            }
        }
    }

    /**
     * 解析 LLM 返回的 CRUD JSON 并事务性地 apply 到数据库。
     * 任何解析异常都会被捕获并静默忽略，确保旧记忆不被破坏。
     */
    private suspend fun applyMemoryCrudOps(
        json: String,
        existingMemories: List<MemoryItem>,
        now: Long
    ) {
        try {
            // 清理 LLM 可能输出的 markdown 代码块包裹
            val cleaned = json
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val root = org.json.JSONObject(cleaned)
            val ops = root.optJSONArray("ops") ?: return  // ops 缺失 → 放弃

            val existingById = existingMemories.associateBy { it.id }

            for (i in 0 until ops.length()) {
                val op = ops.optJSONObject(i) ?: continue
                when (op.optString("op").uppercase()) {
                    "ADD" -> {
                        val content = op.optString("content").trim()
                        if (content.isNotBlank()) {
                            // 本地去重：若现有记忆中已有语义相近的条目（词级 Jaccard ≥ 0.55），
                            // 则改为 REINFORCE 而非重复插入
                            val duplicate = existingMemories.firstOrNull { existing ->
                                jaccardSimilarity(content, existing.content) >= DEDUP_SIMILARITY_THRESHOLD
                            }
                            if (duplicate != null) {
                                repository.reinforceMemory(duplicate.id, duplicate.content, now)
                            } else {
                                repository.insertMemory(
                                    MemoryItem(content = content, createdAt = now, updatedAt = now, confidence = 1)
                                )
                            }
                        }
                    }
                    "UPDATE" -> {
                        val id = op.optLong("id", -1L)
                        val content = op.optString("content").trim()
                        val existing = existingById[id]
                        // 拒绝更新 pinned 条目
                        if (existing != null && !existing.pinned && content.isNotBlank()) {
                            repository.updateMemory(
                                existing.copy(content = content, updatedAt = now, confidence = existing.confidence + 1)
                            )
                        }
                    }
                    "REINFORCE" -> {
                        val id = op.optLong("id", -1L)
                        val existing = existingById[id]
                        if (existing != null) {
                            repository.reinforceMemory(id, existing.content, now)
                        }
                    }
                    "DELETE" -> {
                        val id = op.optLong("id", -1L)
                        val existing = existingById[id]
                        // 拒绝删除 pinned 条目
                        if (existing != null && !existing.pinned) {
                            repository.deleteMemoryById(id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // JSON 解析失败或任何异常 → 静默忽略，旧记忆完整保留
            e.printStackTrace()
        }
    }

    /**
     * 词级 Jaccard 相似度：将两个字符串分词后计算交集/并集比例。
     * 用于 ADD 去重，避免语义相近的记忆条目重复插入。
     */
    private fun jaccardSimilarity(a: String, b: String): Double {
        val tokensA = a.lowercase().split(Regex("\\s+|[,，。.!！?？;；]+")).filter { it.isNotBlank() }.toSet()
        val tokensB = b.lowercase().split(Regex("\\s+|[,，。.!！?？;；]+")).filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return intersection.toDouble() / union.toDouble()
    }

    companion object {
        private const val MEMORY_INTERVAL_MS = 15 * 60 * 1000L  // 15 分钟
        private const val NEW_MESSAGES_THRESHOLD = 10            // 新增消息达到此数也触发
        private const val MEMORY_WINDOW_CHARS = 12_000           // 摘要窗口最大字符数（替代固定条数）
        private const val MEMORY_RECENT_RAW_COUNT = 20           // Step 2 额外传入的原始消息条数
        private const val MIN_NEW_CHARS_THRESHOLD = 200          // 新消息总字符数低于此值时跳过同步
        private const val DEDUP_SIMILARITY_THRESHOLD = 0.55      // ADD 去重的 Jaccard 相似度阈值
        private const val MEMORY_INJECT_LIMIT = 30               // 注入 system prompt 的最大记忆条数
        private const val MAX_TOOL_CALL_DEPTH = 10               // 工具调用最大递归深度，防止无限循环
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
                    modelFetchError = "未获取到模型列表。"
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
                    name = "智能助手 (Default Assistant)",
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
