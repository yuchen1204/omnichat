package com.example.data

import androidx.room.withTransaction
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
    private val colorSchemePresetDao = db.colorSchemePresetDao()
    private val agentPresetDao = db.agentPresetDao()
    private val workspaceSessionDao = db.workspaceSessionDao()
    private val agentInstanceDao = db.agentInstanceDao()
    private val workspaceMessageDao = db.workspaceMessageDao()
    // Agent Team 任务系统
    val teamTaskDao: TeamTaskDao = db.teamTaskDao()

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
    suspend fun searchMemoriesByKeyword(keyword: String): List<MemoryItem> = memoryItemDao.searchMemoriesByKeyword(keyword)
    suspend fun searchMemoriesByTag(tag: String): List<MemoryItem> = memoryItemDao.searchMemoriesByTag(tag)
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
    val allFetchedModels: Flow<List<FetchedModel>> = fetchedModelDao.getAllFetchedModelsFlow()
    suspend fun getAllFetchedModels(): List<FetchedModel> = fetchedModelDao.getAllFetchedModels()
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

    // Color Scheme Presets
    val allColorSchemePresets: Flow<List<ColorSchemePreset>> = colorSchemePresetDao.getAllPresetsFlow()
    suspend fun getAllColorSchemePresets(): List<ColorSchemePreset> = colorSchemePresetDao.getAllPresets()
    suspend fun getColorSchemePresetCount(): Int = colorSchemePresetDao.getCount()
    suspend fun getColorSchemePresetById(schemeId: String): ColorSchemePreset? = colorSchemePresetDao.getPresetById(schemeId)
    suspend fun insertColorSchemePreset(preset: ColorSchemePreset) = colorSchemePresetDao.insertPreset(preset)
    suspend fun deleteColorSchemePreset(schemeId: String) = colorSchemePresetDao.deletePresetById(schemeId)

    // Agent Presets
    val allAgentPresets: Flow<List<AgentPreset>> = agentPresetDao.getAllPresetsFlow()
    suspend fun getAllAgentPresets(): List<AgentPreset> = agentPresetDao.getAllPresets()
    suspend fun getAgentPresetById(id: Long): AgentPreset? = agentPresetDao.getPresetById(id)
    suspend fun insertAgentPreset(preset: AgentPreset): Long = agentPresetDao.insertPreset(preset)
    suspend fun updateAgentPreset(preset: AgentPreset) = agentPresetDao.updatePreset(preset)
    suspend fun deleteAgentPreset(preset: AgentPreset) = agentPresetDao.deletePreset(preset)

    // Workspace Sessions
    val allWorkspaceSessions: Flow<List<WorkspaceSession>> = workspaceSessionDao.getAllSessionsFlow()
    suspend fun getWorkspaceSessionById(id: Long): WorkspaceSession? = workspaceSessionDao.getById(id)
    suspend fun insertWorkspaceSession(session: WorkspaceSession): Long = workspaceSessionDao.insertSession(session)
    suspend fun updateWorkspaceSessionTitle(id: Long, title: String) = workspaceSessionDao.updateTitle(id, title)
    suspend fun updateWorkspaceSessionStatus(id: Long, isActive: Boolean, lastActiveAt: Long) = 
        workspaceSessionDao.updateStatus(id, isActive, lastActiveAt)
    suspend fun deleteWorkspaceSession(id: Long) {
        // 级联删除：使用事务保证原子性（BUG-003）
        db.withTransaction {
            workspaceMessageDao.deleteByWorkspaceSession(id)
            agentInstanceDao.deleteByWorkspaceSession(id)
            teamTaskDao.deleteAllForTeam("workspace_$id")
            workspaceSessionDao.deleteById(id)
        }
    }

    // Agent Instances
    suspend fun getAgentInstancesByWorkspaceSession(wsId: Long): List<AgentInstance> = 
        agentInstanceDao.getByWorkspaceSession(wsId)
    suspend fun insertAgentInstance(instance: AgentInstance): Long = agentInstanceDao.insertInstance(instance)
    suspend fun deleteAgentInstancesByWorkspaceSession(wsId: Long) = agentInstanceDao.deleteByWorkspaceSession(wsId)

    // Workspace Messages
    fun getWorkspaceMessagesByAgentFlow(agentId: Long): Flow<List<WorkspaceMessage>> = 
        workspaceMessageDao.getMessagesByAgentFlow(agentId)
    suspend fun getWorkspaceMessagesByAgent(agentId: Long): List<WorkspaceMessage> = 
        workspaceMessageDao.getMessagesByAgent(agentId)
    suspend fun insertWorkspaceMessage(message: WorkspaceMessage): Long = workspaceMessageDao.insertMessage(message)
    suspend fun deleteWorkspaceMessagesBySession(wsId: Long) = workspaceMessageDao.deleteByWorkspaceSession(wsId)

    // WHY: 通过 Agent 名称查询消息（transcript 恢复用），JOIN agent_instances 解析名称到 ID
    suspend fun getWorkspaceMessagesForAgent(sessionId: Long, agentName: String): List<WorkspaceMessage> =
        workspaceMessageDao.getMessagesForAgent(sessionId, agentName)

    /**
     * 原子地替换工作区的所有消息（先删除旧消息，再插入新消息）。
     *
     * WHY: 使用 Room 事务保证原子性。如果在 DELETE 完成后 INSERT 前进程被杀或超时，
     * 事务会回滚，避免所有消息永久丢失。
     */
    suspend fun replaceWorkspaceMessages(wsId: Long, messages: List<WorkspaceMessage>) {
        db.withTransaction {
            workspaceMessageDao.deleteByWorkspaceSession(wsId)
            messages.forEach { workspaceMessageDao.insertMessage(it) }
        }
    }

    /**
     * 原子地替换单个 Agent 的消息（COMPLETED 时即时持久化用）。
     *
     * 只删除该 agentInstanceId 的消息，不影响同一 workspace 里其他 Agent 的消息。
     * 比 replaceWorkspaceMessages 更细粒度，避免覆盖其他 Agent 正在写入的消息。
     */
    suspend fun replaceAgentMessages(wsId: Long, agentInstanceId: Long, messages: List<WorkspaceMessage>) {
        db.withTransaction {
            workspaceMessageDao.deleteByAgentInstance(agentInstanceId)
            messages.forEach { workspaceMessageDao.insertMessage(it) }
        }
    }

    // ── Agent Team 任务系统 ─────────────────────────────────────────────

    /**
     * 观察指定团队的任务列表变化。
     *
     * @param teamName 团队名称
     * @return 任务列表的响应式数据流
     */
    fun getTeamTasksFlow(teamName: String): Flow<List<TeamTask>> = teamTaskDao.getTasksFlow(teamName)

    /**
     * 获取指定团队的所有任务（一次性查询）。
     */
    suspend fun getTeamTasks(teamName: String): List<TeamTask> = teamTaskDao.getTasks(teamName)

    /**
     * 查找可认领的任务（带 Agent 匹配，返回候选列表供应用层过滤 blockedBy）。
     */
    suspend fun findClaimableTeamTasks(teamName: String, agentName: String): List<TeamTask> =
        teamTaskDao.findClaimableTasks(teamName, agentName)

    /**
     * 查找可认领的任务（带 Agent 匹配）。
     *
     * 使用 findClaimableTasks（plural）获取候选列表，在应用层过滤 blockedBy。
     * 不再使用已废弃的 findClaimableTask（依赖 SQL 中 `blockedBy = ''` 检查）。
     */
    suspend fun findClaimableTeamTask(teamName: String, agentName: String): TeamTask? =
        teamTaskDao.findClaimableTasks(teamName, agentName).firstOrNull()

    /**
     * 插入新任务。
     */
    suspend fun insertTeamTask(task: TeamTask): Long = teamTaskDao.insert(task)

    /**
     * 按 ID 查询单个任务。
     */
    suspend fun getTeamTaskById(id: Long): TeamTask? = teamTaskDao.getTaskById(id)

    /**
     * 获取指定团队的所有任务（别名，供 TaskTools 使用）。
     */
    suspend fun getTeamTasksByTeam(teamName: String): List<TeamTask> = teamTaskDao.getTasks(teamName)

    /**
     * 更新任务。
     */
    suspend fun updateTeamTask(task: TeamTask) = teamTaskDao.update(task)

    /**
     * 原子认领任务。
     */
    suspend fun claimTeamTask(taskId: Long, agentName: String, now: Long = System.currentTimeMillis()): Int =
        teamTaskDao.claimTask(taskId, agentName, now)

    /**
     * 删除指定团队的所有任务。
     */
    suspend fun deleteAllTeamTasks(teamName: String) = teamTaskDao.deleteAllForTeam(teamName)
}
