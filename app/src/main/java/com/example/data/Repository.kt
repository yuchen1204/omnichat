package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val db: AppDatabase) {
    private val modelConfigDao = db.modelConfigDao()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val memoryItemDao = db.memoryItemDao()
    private val promptTemplateDao = db.promptTemplateDao()
    private val fetchedModelDao = db.fetchedModelDao()
    private val sessionSummaryDao = db.sessionSummaryDao()
    private val mcpServerDao = db.mcpServerDao()
    private val uiSettingsDao = db.uiSettingsDao()

    // Model Configs
    val allConfigs: Flow<List<ModelConfig>> = modelConfigDao.getAllConfigsFlow()
    suspend fun getAllConfigs(): List<ModelConfig> = modelConfigDao.getAllConfigs()
    suspend fun getConfigById(id: Long): ModelConfig? = modelConfigDao.getConfigById(id)
    suspend fun getDefaultProvider(): ModelConfig? = modelConfigDao.getDefaultProvider()
    suspend fun insertConfig(config: ModelConfig): Long = modelConfigDao.insertConfig(config)
    suspend fun updateConfig(config: ModelConfig) = modelConfigDao.updateConfig(config)
    suspend fun deleteConfig(config: ModelConfig) = modelConfigDao.deleteConfig(config)
    suspend fun setDefaultProvider(id: Long) = modelConfigDao.setDefaultProvider(id)

    // Sessions
    val allSessions: Flow<List<Session>> = sessionDao.getAllSessionsFlow()
    suspend fun insertSession(session: Session): Long = sessionDao.insertSession(session)
    suspend fun updateSessionTitle(id: Long, title: String) = sessionDao.updateSessionTitle(id, title)
    suspend fun deleteSession(id: Long) {
        messageDao.deleteMessagesBySession(id)
        sessionSummaryDao.deleteSummaryBySession(id)
        sessionDao.deleteSessionById(id)
    }

    // Messages
    fun getMessagesBySessionFlow(sessionId: Long): Flow<List<Message>> = messageDao.getMessagesBySessionFlow(sessionId)
    suspend fun getMessagesBySession(sessionId: Long): List<Message> = messageDao.getMessagesBySession(sessionId)
    suspend fun insertMessage(message: Message): Long = messageDao.insertMessage(message)
    suspend fun deleteMessagesFrom(sessionId: Long, timestamp: Long) = messageDao.deleteMessagesFrom(sessionId, timestamp)
    suspend fun deleteMessagesBySession(sessionId: Long) = messageDao.deleteMessagesBySession(sessionId)

    // Memories
    val allMemories: Flow<List<MemoryItem>> = memoryItemDao.getAllMemoriesFlow()
    suspend fun getAllMemories(): List<MemoryItem> = memoryItemDao.getAllMemories()
    suspend fun getMemoryById(id: Long): MemoryItem? = memoryItemDao.getMemoryById(id)
    suspend fun insertMemory(memory: MemoryItem): Long = memoryItemDao.insertMemory(memory)
    suspend fun updateMemory(memory: MemoryItem) = memoryItemDao.updateMemory(memory)
    suspend fun reinforceMemory(id: Long, content: String, now: Long) = memoryItemDao.reinforceMemory(id, content, now)
    suspend fun setPinned(id: Long, pinned: Boolean) = memoryItemDao.setPinned(id, pinned)
    suspend fun deleteMemoryById(id: Long) = memoryItemDao.deleteMemoryById(id)
    suspend fun deleteAllUnpinnedMemories() = memoryItemDao.deleteAllUnpinnedMemories()
    suspend fun deleteAllMemories() = memoryItemDao.deleteAllMemories()

    // Prompt Templates
    val allTemplates: Flow<List<PromptTemplate>> = promptTemplateDao.getAllTemplatesFlow()
    suspend fun getAllTemplates(): List<PromptTemplate> = promptTemplateDao.getAllTemplates()
    suspend fun getActiveTemplate(): PromptTemplate? = promptTemplateDao.getActiveTemplate()
    suspend fun insertTemplate(template: PromptTemplate): Long = promptTemplateDao.insertTemplate(template)
    suspend fun setActiveTemplate(id: Long) = promptTemplateDao.setActiveTemplate(id)
    suspend fun deleteTemplate(template: PromptTemplate) = promptTemplateDao.deleteTemplate(template)

    // Fetched Models Helper methods
    fun getModelsByProviderFlow(providerId: Long): Flow<List<FetchedModel>> = fetchedModelDao.getModelsByProviderFlow(providerId)
    suspend fun getModelsByProvider(providerId: Long): List<FetchedModel> = fetchedModelDao.getModelsByProvider(providerId)
    suspend fun insertFetchedModel(model: FetchedModel): Long = fetchedModelDao.insertFetchedModel(model)
    suspend fun deleteModelsByProvider(providerId: Long) = fetchedModelDao.deleteModelsByProvider(providerId)

    // Session Summaries
    suspend fun getSessionSummary(sessionId: Long): SessionSummary? = sessionSummaryDao.getSummaryBySession(sessionId)
    suspend fun upsertSessionSummary(summary: SessionSummary) = sessionSummaryDao.upsertSummary(summary)

    // MCP Servers
    val allMcpServers: Flow<List<McpServer>> = mcpServerDao.getAllServersFlow()
    suspend fun getAllMcpServers(): List<McpServer> = mcpServerDao.getAllServers()
    suspend fun getEnabledMcpServers(): List<McpServer> = mcpServerDao.getEnabledServers()
    suspend fun insertMcpServer(server: McpServer): Long = mcpServerDao.insertServer(server)
    suspend fun updateMcpServer(server: McpServer) = mcpServerDao.updateServer(server)
    suspend fun deleteMcpServer(server: McpServer) = mcpServerDao.deleteServer(server)

    // UI Settings
    val uiSettings: Flow<UISettings?> = uiSettingsDao.getSettingsFlow()
    suspend fun getUISettings(): UISettings? = uiSettingsDao.getSettings()
    suspend fun upsertUISettings(settings: UISettings) = uiSettingsDao.upsertSettings(settings)
}
