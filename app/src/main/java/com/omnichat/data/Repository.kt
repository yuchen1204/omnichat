package com.omnichat.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 记忆关联展开结果：包含关联的记忆条目、关系标签和方向。
 * 供 search_memory 的 BFS 遍历使用。
 */
data class RelatedMemoryInfo(
    val memory: MemoryItem,
    val relationLabel: String,
    val direction: String
)

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
    private val mcpFilePermissionDao = db.mcpFilePermissionDao()
    private val memoryAssociationDao = db.memoryAssociationDao()
    private val memoryAuditDao = db.memoryAuditDao()
    private val cloudBackupDao = db.cloudBackupDao()

    // Model Configs
    val allConfigs: Flow<List<ModelConfig>> = modelConfigDao.getAllConfigsFlow()
    suspend fun getAllConfigs(): List<ModelConfig> = modelConfigDao.getAllConfigs()
    suspend fun getConfigById(id: Long): ModelConfig? = modelConfigDao.getConfigById(id)
    suspend fun getDefaultProvider(): ModelConfig? = modelConfigDao.getDefaultProvider()
    suspend fun insertConfig(config: ModelConfig): Long = modelConfigDao.insertConfig(config)
    suspend fun updateConfig(config: ModelConfig) = modelConfigDao.updateConfig(config)
    suspend fun deleteConfig(config: ModelConfig) = modelConfigDao.deleteConfig(config)
    suspend fun setDefaultProvider(id: Long) = modelConfigDao.setDefaultProvider(id)
    suspend fun setDefaultProviderWithModel(id: Long, selectedModelId: String) = modelConfigDao.setDefaultProviderWithModel(id, selectedModelId)

    // Sessions
    val allSessions: Flow<List<Session>> = sessionDao.getAllSessionsFlow()
    suspend fun insertSession(session: Session): Long = sessionDao.insertSession(session)
    suspend fun updateSessionTitle(id: Long, title: String) = sessionDao.updateSessionTitle(id, title)
    suspend fun deleteSession(id: Long) {
        messageDao.deleteMessagesBySession(id)
        sessionSummaryDao.deleteSummaryBySession(id)
        sessionDao.deleteSessionById(id)
    }

    suspend fun updateSessionThinkingEffort(id: Long, effort: String) = sessionDao.updateThinkingEffort(id, effort)

    suspend fun getSessionById(id: Long): Session? {
        return sessionDao.getAllSessionsFlow().first().find { it.id == id }
    }

    // Messages
    fun getMessagesBySessionFlow(sessionId: Long): Flow<List<Message>> = messageDao.getMessagesBySessionFlow(sessionId)
    suspend fun getMessagesBySession(sessionId: Long): List<Message> = messageDao.getMessagesBySession(sessionId)
    suspend fun insertMessage(message: Message): Long = messageDao.insertMessage(message)
    suspend fun deleteMessagesFrom(sessionId: Long, timestamp: Long) = messageDao.deleteMessagesFrom(sessionId, timestamp)
    suspend fun deleteMessagesAfter(sessionId: Long, timestamp: Long) = messageDao.deleteMessagesAfter(sessionId, timestamp)
    suspend fun deleteMessagesByIdAfter(sessionId: Long, afterId: Long) = messageDao.deleteMessagesByIdAfter(sessionId, afterId)
    suspend fun getMessageById(id: Long): Message? = messageDao.getMessageById(id)
    suspend fun updateMessageContent(id: Long, content: String) = messageDao.updateMessageContent(id, content)
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
    suspend fun batchDecayConfidence(now: Long) = memoryItemDao.batchDecayConfidence(now)
    suspend fun getPinnedMemories(): List<MemoryItem> = memoryItemDao.getPinnedMemories()
    suspend fun getTopUnpinnedMemories(limit: Int): List<MemoryItem> = memoryItemDao.getTopUnpinnedMemories(limit)
    suspend fun getMemoryCount(): Int = memoryItemDao.getMemoryCount()

    // Time Reminders
    suspend fun getPendingReminders(todayStr: String): List<MemoryItem> = memoryItemDao.getPendingReminders(todayStr)
    suspend fun markReminded(id: Long) = memoryItemDao.markReminded(id)
    suspend fun autoMarkStaleReminders(cutoffStr: String) = memoryItemDao.autoMarkStaleReminders(cutoffStr)

    // Memory Associations
    suspend fun getRelatedMemories(memoryId: Long): List<RelatedMemoryInfo> {
        val associations = memoryAssociationDao.getAllForMemory(memoryId)
        return associations.mapNotNull { assoc ->
            val relatedId = when {
                assoc.direction == "bidirectional" -> {
                    if (assoc.fromMemoryId == memoryId) assoc.toMemoryId else assoc.fromMemoryId
                }
                assoc.fromMemoryId == memoryId -> assoc.toMemoryId
                else -> return@mapNotNull null  // directed edge, wrong direction
            }
            val mem = memoryItemDao.getMemoryById(relatedId) ?: return@mapNotNull null
            RelatedMemoryInfo(mem, assoc.relationLabel, assoc.direction)
        }
    }

    suspend fun getAssociationsFor(memoryId: Long): List<MemoryAssociation> =
        memoryAssociationDao.getAllForMemory(memoryId)

    suspend fun insertAssociation(assoc: MemoryAssociation): Long {
        // Prevent self-referencing associations
        if (assoc.fromMemoryId == assoc.toMemoryId) return -1L
        // For bidirectional edges, normalize: always store smaller ID as fromMemoryId
        val normalized = if (assoc.direction == "bidirectional" && assoc.fromMemoryId > assoc.toMemoryId) {
            assoc.copy(fromMemoryId = assoc.toMemoryId, toMemoryId = assoc.fromMemoryId)
        } else {
            assoc
        }
        return memoryAssociationDao.insert(normalized)
    }

    suspend fun deleteAssociation(id: Long) = memoryAssociationDao.deleteById(id)
    suspend fun deleteAssociationsForMemory(memoryId: Long) = memoryAssociationDao.deleteAllForMemory(memoryId)
    suspend fun getUnassociatedMemories(limit: Int): List<MemoryItem> = memoryAssociationDao.getUnassociatedMemories(limit)

    suspend fun getOutgoingAssociations(memoryId: Long): List<MemoryAssociation> {
        return memoryAssociationDao.getOutgoing(memoryId)
    }

    suspend fun getIncomingAssociations(memoryId: Long): List<MemoryAssociation> {
        return memoryAssociationDao.getIncoming(memoryId)
    }

    suspend fun getAssociationCount(memoryId: Long): Int {
        return memoryAssociationDao.getAllForMemory(memoryId).size
    }

    suspend fun getTemplateById(id: Long): PromptTemplate? {
        return promptTemplateDao.getTemplateById(id)
    }

    // Memory Audit Log
    suspend fun getAuditHistoryForMemory(memoryId: Long): List<MemoryAuditEntry> = memoryAuditDao.getHistoryForMemory(memoryId)
    suspend fun getRecentAuditActivity(limit: Int = 100): List<MemoryAuditEntry> = memoryAuditDao.getRecentActivity(limit)
    suspend fun insertAuditEntry(entry: MemoryAuditEntry) = memoryAuditDao.insert(entry)
    suspend fun pruneOldAuditEntries(before: Long) = memoryAuditDao.pruneOlderThan(before)

    // Memory FTS (Full-Text Search) — 直接访问 SQLite，绕过 Room 对 FTS5 虚拟表的校验
    suspend fun searchMemoryFts(query: String, limit: Int = 50): List<Long> {
        val db = db.openHelper.readableDatabase
        val cursor = db.query(
            "SELECT rowid FROM memory_items_fts WHERE memory_items_fts MATCH ? ORDER BY rank LIMIT ?",
            arrayOf(query, limit.toString())
        )
        return cursor.use {
            val ids = mutableListOf<Long>()
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
            ids
        }
    }

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

    // MCP File Permissions
    suspend fun getAllMcpFilePermissions(): List<McpFilePermission> = mcpFilePermissionDao.getAllPermissions()
    suspend fun insertMcpFilePermission(perm: McpFilePermission): Long = mcpFilePermissionDao.insertPermission(perm)
    suspend fun deleteAllMcpFilePermissions() = mcpFilePermissionDao.deleteAllPermissions()

    // MCP File Permissions - Reactive & Delete Methods
    fun getAllMcpFilePermissionsFlow(): Flow<List<McpFilePermission>> = mcpFilePermissionDao.getAllPermissionsFlow()
    suspend fun deleteMcpFilePermissionById(id: Long) = mcpFilePermissionDao.deletePermissionById(id)
    suspend fun deleteAllowedMcpFilePermissions() = mcpFilePermissionDao.deleteAllowedPermissions()

    // Cloud Backups
    suspend fun getCloudBackups(userId: String): List<CloudBackupRecord> =
        cloudBackupDao.getBackupsByUser(userId)

    suspend fun getCloudBackupById(backupId: String): CloudBackupRecord? =
        cloudBackupDao.getBackupById(backupId)

    suspend fun insertCloudBackup(record: CloudBackupRecord) =
        cloudBackupDao.insertBackup(record)

    suspend fun deleteCloudBackup(record: CloudBackupRecord) =
        cloudBackupDao.deleteBackup(record)

    suspend fun deleteAllCloudBackups(userId: String) =
        cloudBackupDao.deleteAllBackupsForUser(userId)

    suspend fun getCloudBackupCount(userId: String): Int =
        cloudBackupDao.getBackupCount(userId)
}
