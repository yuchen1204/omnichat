package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = AppRepository(database)

    // Active session selection state
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    // Sessions flow
    val sessions: StateFlow<List<Session>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active chat messages flow
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

    // Real-time operations UI state
    var isStreaming by mutableStateOf(false)
        private set

    var currentStreamingText by mutableStateOf("")
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

    // 当前会话临时覆盖的 Provider+Model（不持久化，切换会话后重置）
    // Pair<ModelConfig, modelId>
    var activeOverrideConfig by mutableStateOf<Pair<ModelConfig, String>?>(null)
        private set

    /** 临时切换当前会话使用的 Provider 和模型，不影响默认 Provider 设置 */
    fun setSessionOverrideModel(provider: ModelConfig, modelId: String) {
        activeOverrideConfig = Pair(provider, modelId)
    }

    /** 清除临时覆盖，恢复使用默认 Provider */
    fun clearSessionOverride() {
        activeOverrideConfig = null
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
        _selectedSessionId.value = sessionId
        activeOverrideConfig = null  // 切换会话时重置临时模型覆盖
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
        val sessionId = _selectedSessionId.value ?: return
        if (text.isBlank() || isStreaming) return

        viewModelScope.launch {
            // 1. Insert User Message
            val userMsg = Message(sessionId = sessionId, role = "user", content = text)
            repository.insertMessage(userMsg)

            // 2. Fetch configurations
            val providerConfig = run {
                val override = activeOverrideConfig
                if (override != null) {
                    // 使用临时覆盖的 Provider，并替换 selectedModelId
                    override.first.copy(selectedModelId = override.second)
                } else {
                    repository.getDefaultProvider()
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

            // 3. Fetch physical long-term memories list
            val localMemories = repository.getAllMemories()
            val memoriesText = if (localMemories.isEmpty()) {
                "无 (None recorded)"
            } else {
                localMemories.joinToString("\n") { "- ${it.content}" }
            }

            // 4. Inject memories into prompt template
            val finalSystemPrompt = if (customSystemPrompt.contains("[CROSS_SESSION_MEMORY]")) {
                customSystemPrompt.replace("[CROSS_SESSION_MEMORY]", memoriesText)
            } else {
                customSystemPrompt + "\n\n[User's Cross-Session History & Preferences]:\n" + memoriesText
            }

            // 5. Gather existing message history
            val messageHistory = repository.getMessagesBySession(sessionId)

            // 6. Start Streaming Response
            isStreaming = true
            currentStreamingText = ""

            ApiClient.executeStreamingChat(providerConfig, finalSystemPrompt, messageHistory)
                .collect { chunk ->
                    if (chunk.startsWith("ERROR:")) {
                        currentStreamingText += "\n$chunk"
                        isStreaming = false
                    } else {
                        currentStreamingText += chunk
                    }
                }

            // Save streamed result to local database
            if (currentStreamingText.isNotEmpty()) {
                repository.insertMessage(
                    Message(sessionId = sessionId, role = "assistant", content = currentStreamingText)
                )
            }
            isStreaming = false
            currentStreamingText = ""

            // 7. Auto-trigger Memory Syncer after each assistant message round to analyze dialogue dynamically
            triggerMemorySync()
        }
    }

    /**
     * 15 分钟增量记忆算法：
     *
     * 触发条件（满足任一即运行）：
     *   - 距上次总结超过 MEMORY_INTERVAL_MS（15 分钟）
     *   - 自上次总结后新增消息数 >= NEW_MESSAGES_THRESHOLD（10 条）
     *
     * 每次运行流程：
     *   Step 1  取最近 100 条消息 + 上次会话摘要 → 生成新的会话滚动摘要
     *   Step 2  用新摘要 + 现有全局偏好事实 → 提炼/更新跨会话 MemoryItem 列表
     */
    fun triggerMemorySync() {
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

                val shouldRun = timeSinceLast >= MEMORY_INTERVAL_MS || newMsgCount >= NEW_MESSAGES_THRESHOLD
                if (!shouldRun) {
                    isMemorySyncing = false
                    return@launch
                }

                // ── Step 1：生成本会话的新滚动摘要 ──────────────────────
                val recentMessages = allMessages.takeLast(MEMORY_WINDOW_SIZE)
                val dialogueFormatted = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }
                val prevSummaryText = prevSummary?.summaryText?.takeIf { it.isNotBlank() }

                val summarySystemPrompt =
                    "You are a concise conversation summarizer. " +
                    "Produce a compact, factual summary of the conversation. " +
                    "Focus on: topics discussed, decisions made, user's stated preferences or problems, key conclusions. " +
                    "Aim for 3-8 sentences. No filler or meta-commentary."

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

                // ── Step 2：用新摘要提炼/更新全局跨会话偏好事实 ─────────
                val currentMemories = repository.getAllMemories()
                val memoriesFormatted = if (currentMemories.isEmpty()) {
                    "No existing facts recorded."
                } else {
                    currentMemories.mapIndexed { idx, item -> "${idx + 1}. ${item.content}" }.joinToString("\n")
                }

                val factsSystemPrompt =
                    "You are a User Preference & Persona Synthesizer. " +
                    "Extract durable, cross-session personal facts from conversation summaries. " +
                    "Focus only on stable, reusable facts: preferences, skills, habits, goals, dislikes, setup details. " +
                    "Ignore transient topics (e.g., a one-off question)."

                val factsUserQuery = buildString {
                    append("Existing facts:\n###\n$memoriesFormatted\n###\n\n")
                    append("New conversation summary:\n###\n$newSummaryText\n###\n\n")
                    append("Task: 1. Extract NEW durable facts from the summary. ")
                    append("2. Update/remove facts that are contradicted or outdated. ")
                    append("3. Keep valid existing facts. ")
                    append("Output a complete revised numbered list. Each item = one short sentence. ")
                    append("Return ONLY the numbered list, no intro or commentary.")
                }

                val revisedFactsText = ApiClient.executeCompletion(memoryConfig, factsSystemPrompt, factsUserQuery)

                if (!revisedFactsText.isNullOrBlank()) {
                    val parsedFacts = mutableListOf<String>()
                    revisedFactsText.lines().forEach { line ->
                        val clean = line.trim()
                        when {
                            clean.matches(Regex("^\\d+[.)]\\s*.+")) -> {
                                val content = clean.replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim()
                                if (content.isNotBlank()) parsedFacts.add(content)
                            }
                            clean.startsWith("-") || clean.startsWith("*") -> {
                                val content = clean.removePrefix("-").removePrefix("*").trim()
                                if (content.isNotBlank()) parsedFacts.add(content)
                            }
                        }
                    }
                    if (parsedFacts.isNotEmpty()) {
                        repository.deleteAllMemories()
                        parsedFacts.forEach { repository.insertMemory(MemoryItem(content = it)) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isMemorySyncing = false
            }
        }
    }

    companion object {
        private const val MEMORY_INTERVAL_MS = 15 * 60 * 1000L  // 15 分钟
        private const val NEW_MESSAGES_THRESHOLD = 10            // 新增消息达到此数也触发
        private const val MEMORY_WINDOW_SIZE = 100               // 每次取最近 N 条消息
    }

    /**
     * 解析模型能力。优先级：JSON 元数据 > 模型 ID 规则推断。
     *
     * 支持的 JSON 字段（各兼容服务均有不同程度的覆盖）：
     *
     * ── 上下文 / 输出 Token ──────────────────────────────────────────────
     *  context_length          OpenRouter 标准字段
     *  context_window          OpenAI 官方 /v1/models 扩展字段
     *  max_context_length      部分中转服务（OneAPI / NewAPI）
     *  top_provider.context_length          OpenRouter 嵌套字段（更精确）
     *  top_provider.max_completion_tokens   OpenRouter 最大输出 token
     *
     * ── 视觉能力 ─────────────────────────────────────────────────────────
     *  architecture.input_modalities[]      OpenRouter：数组含 "image" 则有视觉
     *  architecture.modality                OpenRouter：字符串如 "text+image->text"
     *
     * ── 思考能力 ─────────────────────────────────────────────────────────
     *  supported_parameters[]               OpenRouter：含 "reasoning" 或 "include_reasoning"
     *
     * ── 工具调用 ─────────────────────────────────────────────────────────
     *  supported_parameters[]               OpenRouter：含 "tools" 或 "tool_choice"
     */
    fun parseModelCapabilities(modelId: String, providerId: Long = 0, json: org.json.JSONObject? = null): FetchedModel {
        val lower = modelId.lowercase()

        // ── 1. 上下文窗口 & 最大输出 Token ──────────────────────────────
        // 优先读 JSON，多个字段按精确度降序尝试
        val rawContext: Int = run {
            if (json == null) return@run 0
            // OpenRouter: top_provider.context_length 最精确（实际可用值）
            json.optJSONObject("top_provider")?.optInt("context_length", 0)?.takeIf { it > 0 }
                // OpenRouter / 通用: 顶层 context_length
                ?: json.optInt("context_length", 0).takeIf { it > 0 }
                // OpenAI 官方扩展字段
                ?: json.optInt("context_window", 0).takeIf { it > 0 }
                // OneAPI / NewAPI 等中转服务
                ?: json.optInt("max_context_length", 0).takeIf { it > 0 }
                ?: 0
        }

        val rawMaxOutput: Int = run {
            if (json == null) return@run 0
            // OpenRouter: top_provider.max_completion_tokens
            json.optJSONObject("top_provider")?.optInt("max_completion_tokens", 0)?.takeIf { it > 0 }
                ?: json.optInt("max_completion_tokens", 0).takeIf { it > 0 }
                ?: json.optInt("max_output_tokens", 0).takeIf { it > 0 }
                ?: 0
        }

        // 格式化为人类可读字符串，如 "128k"、"1M"
        fun formatTokenCount(n: Int): String = when {
            n <= 0      -> ""
            n >= 1_000_000 -> "${n / 1_000_000}M"
            n >= 1_000     -> "${n / 1_000}k"
            else           -> "$n"
        }

        val contextStr: String = if (rawContext > 0) {
            formatTokenCount(rawContext)
        } else {
            // JSON 无数据，按模型 ID 规则兜底（仅覆盖已知官方模型）
            when {
                lower.contains("gemini-2.5") || lower.contains("gemini-2.0-pro") -> "1M"
                lower.contains("gemini-1.5-pro") -> "2M"
                lower.contains("gemini-1.5") || lower.contains("gemini-2.0") -> "1M"
                lower.contains("gemini") -> "1M"
                lower.contains("claude-3-5") || lower.contains("claude-3.5") -> "200k"
                lower.contains("claude-3") || lower.contains("claude-4") -> "200k"
                lower.contains("gpt-4o") || lower.contains("gpt-4.1") -> "128k"
                lower.contains("o1") || lower.contains("o3") || lower.contains("o4") -> "200k"
                lower.contains("deepseek-r1") -> "128k"
                lower.contains("deepseek-v3") || lower.contains("deepseek-chat") -> "64k"
                lower.contains("llama-3") -> "128k"
                lower.contains("qwen2.5") || lower.contains("qwq") -> "128k"
                lower.contains("gpt-4") -> "128k"
                lower.contains("gpt-3.5") -> "16k"
                else -> "128k"
            }
        }

        val maxOutputStr: String = formatTokenCount(rawMaxOutput) // 空字符串表示未知

        // ── 2. 视觉能力 ──────────────────────────────────────────────────
        // 第一优先级：OpenRouter architecture.input_modalities 数组（最可靠）
        var vision = false
        if (json != null) {
            val arch = json.optJSONObject("architecture")
            if (arch != null) {
                // input_modalities: ["text", "image"] — OpenRouter 标准
                val inputModalities = arch.optJSONArray("input_modalities")
                if (inputModalities != null) {
                    for (i in 0 until inputModalities.length()) {
                        if (inputModalities.optString(i).equals("image", ignoreCase = true)) {
                            vision = true
                            break
                        }
                    }
                }
                // modality 字符串兜底：如 "text+image->text"
                if (!vision && arch.optString("modality").contains("image", ignoreCase = true)) {
                    vision = true
                }
            }
        }
        // 第二优先级：模型 ID 规则推断（仅在 JSON 无 architecture 时生效）
        if (!vision) {
            vision = lower.contains("vision") ||
                     lower.contains("-vl") || lower.contains(":vl") ||
                     lower.contains("multimodal") ||
                     lower.contains("pixtral") ||
                     lower.contains("llava") ||
                     lower.contains("bakllava") ||
                     lower.contains("gpt-4o") ||
                     lower.contains("gpt-4.1") ||
                     lower.contains("gpt-4-turbo") ||
                     lower.contains("claude-3") || lower.contains("claude-4") ||
                     lower.contains("gemini") ||
                     lower.contains("qwen-vl") || lower.contains("qvq")
        }

        // ── 3. 思考 / 推理能力 ───────────────────────────────────────────
        // 第一优先级：OpenRouter supported_parameters 数组
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
        // 第二优先级：模型 ID 规则推断
        if (!thinking) {
            // 使用分隔符边界匹配，避免 llama-3.1 / mistral-v0.1 等误判
            val segments = lower.split("-", "/", ":", ".")
            thinking = segments.any { it == "r1" || it == "o1" || it == "o3" || it == "o4" } ||
                       lower.contains("reasoner") ||
                       lower.contains("thinking") ||
                       lower.contains("qwq") ||
                       lower.contains("deepseek-r1") ||
                       // o1/o3/o4 系列（含 mini、pro 等后缀）
                       Regex("\\bo[134]-").containsMatchIn(lower) ||
                       lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")
        }

        // ── 4. 工具调用能力 ──────────────────────────────────────────────
        // 第一优先级：OpenRouter supported_parameters 数组（最权威）
        var toolUse: Boolean? = null  // null = 未从 JSON 确定
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
                // 只有当 supported_parameters 非空时才信任其结果
                if (checkedParams) toolUse = hasTools
            }
        }
        // 第二优先级：模型 ID 规则推断（JSON 未给出明确答案时）
        if (toolUse == null) {
            val segments = lower.split("-", "/", ":", ".")
            // DeepSeek R1 官方版（非 distill）不支持工具调用
            val isR1Official = (segments.any { it == "r1" } || lower.contains("deepseek-r1")) &&
                               !lower.contains("distill")
            // OpenAI o1-preview 不支持工具调用（o1-mini / o1 正式版支持）
            val isO1Preview = lower.contains("o1-preview")
            toolUse = !(isR1Official || isO1Preview)
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

    /**
     * Authenticates and dynamically fetches standard compatibility models for the user
     */
    fun fetchModelsAndSave(endpoint: String, apiKey: String, providerId: Long) {
        viewModelScope.launch {
            isFetchingModels = true
            modelFetchError = null
            fetchedModels = emptyList()
            try {
                val list = ApiClient.fetchOpenAIModels(endpoint, apiKey)
                if (list.isEmpty()) {
                    modelFetchError = "未获取到模型列表。请核对Endpoint与API key是否支持/models查询。"
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
                e.printStackTrace()
                modelFetchError = e.localizedMessage ?: "连接错误：请核对Endpoint格式与网络访问权限。"
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

    // Model Config Management DB wrappers
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

    /**
     * 更新默认 Provider 的副模型 ID 和所属 Provider（用于记忆优化和会话标题生成）
     * @param modelId 副模型 ID
     * @param providerId 副模型所属的 Provider ID，0 表示与主 Provider 相同
     */
    fun updateMemoryModelId(modelId: String, providerId: Long = 0L) {
        viewModelScope.launch {
            val provider = repository.getDefaultProvider() ?: return@launch
            repository.updateConfig(provider.copy(memoryModelId = modelId, memoryProviderId = providerId))
        }
    }

    // Memories management wrappers
    fun deleteMemoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteMemoryById(id)
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
            repository.deleteAllMemories()
        }
    }

    // Prompt Template DB wrappers
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
        // Seed prompt templates if empty
        val templates = repository.getAllTemplates()
        if (templates.isEmpty()) {
            repository.insertTemplate(
                PromptTemplate(
                    name = "智能助手 (Default Assistant)",
                    templateText = "You are a friendly, highly intelligent assistant. Adopt a constructive tone and tailor responses precisely to the user's context.\n\n" +
                            "Use the historical facts & preferences below from previous conversations (Cross-Session Memory) to personalize your replies seamlessly without explicitly stating 'As per your memory':\n" +
                            "[CROSS_SESSION_MEMORY]",
                    isActive = true
                )
            )
            repository.insertTemplate(
                PromptTemplate(
                    name = "精炼极简 (Concise Responder)",
                    templateText = "You are an expert coder and technician. Answer with strict precision, minimal explanations, clean syntax, and direct answers.\n\n" +
                            "Incorporate context from user's memory when applicable:\n" +
                            "[CROSS_SESSION_MEMORY]",
                    isActive = false
                )
            )
        }

        // Seed default provider if empty
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
}
