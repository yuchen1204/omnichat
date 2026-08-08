package com.omnichat.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import com.omnichat.util.JsDocumentEditOperation
import com.omnichat.util.JsDocumentReader

/**
 * 记忆关联展开结果：包含关联的记忆条目、关系标签和方向。
 * 供 search_memory 的 BFS 遍历使用。
 */
data class RelatedMemoryInfo(
    val memory: MemoryItem,
    val relationLabel: String,
    val direction: String
)

class AppRepository(private val db: AppDatabase, private val context: android.content.Context? = null) {
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
    private val projectDao = db.projectDao()
    private val projectKnowledgeDao = db.projectKnowledgeDao()
    private val projectKnowledgeEditMutexes = ConcurrentHashMap<Long, Mutex>()

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
        return sessionDao.getSessionById(id)
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

    // Projects (stubs for prototype compatibility)
    val allProjects: Flow<List<Project>> = projectDao.getAllProjectsFlow()
    val recentSessions: Flow<List<Session>> = kotlinx.coroutines.flow.flowOf(emptyList())
    val nonProjectSessions: Flow<List<Session>> = sessionDao.getNonProjectSessionsFlow()
    suspend fun getProjectById(id: Long): Project? = projectDao.getProjectById(id)
    suspend fun insertProject(project: Project): Long = projectDao.insertProject(project)
    suspend fun deleteProject(id: Long) {
        // 1. 删除项目下的会话
        deleteSessionsByProject(id)
        // 2. 删除项目知识文件记录
        deleteKnowledgeByProject(id)
        // 3. 删除项目目录（文件）
        ProjectFileStore.deleteProjectDirectory(id)
        // 4. 删除项目
        projectDao.deleteProjectById(id)
    }
    suspend fun deleteSessionsByProject(projectId: Long) = db.withTransaction {
        sessionDao.getSessionsByProject(projectId).forEach { session ->
            deleteSession(session.id)
        }
    }
    suspend fun deleteKnowledgeByProject(projectId: Long) = projectKnowledgeDao.deleteKnowledgeByProject(projectId)
    fun getSessionsByProjectFlow(projectId: Long): Flow<List<Session>> = sessionDao.getSessionsByProjectFlow(projectId)

    // ═════════════════════════════════════════════════════════════════
    // Project File Asset Lifecycle (Task 2)
    // ═════════════════════════════════════════════════════════════════

    // Project query methods
    suspend fun getAllProjects(): List<Project> = projectDao.getAllProjects()
    suspend fun getKnowledgeByProject(projectId: Long): List<ProjectKnowledge> = projectKnowledgeDao.getKnowledgeByProject(projectId)
    fun getKnowledgeByProjectFlow(projectId: Long): Flow<List<ProjectKnowledge>> = projectKnowledgeDao.getKnowledgeByProjectFlow(projectId)
    suspend fun getKnowledgeById(id: Long): ProjectKnowledge? = projectKnowledgeDao.getKnowledgeById(id)
    suspend fun insertKnowledge(knowledge: ProjectKnowledge): Long = projectKnowledgeDao.insertKnowledge(knowledge)
    suspend fun deleteKnowledge(knowledge: ProjectKnowledge) = projectKnowledgeDao.deleteKnowledge(knowledge)
    suspend fun touchProject(projectId: Long) = projectDao.touchProject(projectId, System.currentTimeMillis())
    suspend fun updateProjectDetails(id: Long, name: String, description: String) = projectDao.updateProjectDetails(id, name, description, System.currentTimeMillis())

    /**
     * Returns the set of MCP server IDs the project has explicitly disabled.
     *
     * The list is stored as a JSON array of integers on [Project.disabledMcpServerIds].
     * Invalid JSON and non-integer entries are tolerated: they produce an empty set
     * (or filter out the offending entry respectively), never an exception.
     *
     * The set is read fresh from the database each call so changes propagate
     * without requiring process restart.
     */
    suspend fun getProjectDisabledMcpServerIds(projectId: Long): Set<Long> {
        val raw = projectDao.getProjectMcpDisabledIds(projectId) ?: return emptySet()
        return parseDisabledMcpServerIdsJson(raw)
    }

    /**
     * Persists the project's disabled MCP server IDs.
     *
     * The IDs are normalized (deduplicated) and serialized as a compact JSON
     * array of integers. An empty set clears the project's disabled list
     * (server is then free to inherit any globally enabled server).
     */
    suspend fun setProjectDisabledMcpServerIds(projectId: Long, ids: Set<Long>) {
        val canonical = canonicalizeDisabledMcpServerIdsJson(ids)
        projectDao.updateProjectMcpDisabledIds(projectId, canonical, System.currentTimeMillis())
    }

    private fun parseDisabledMcpServerIdsJson(raw: String): Set<Long> {
        if (raw.isBlank()) return emptySet()
        return try {
            val arr = org.json.JSONArray(raw)
            val result = linkedSetOf<Long>()
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                // Accept only integer values; skip strings, doubles, nulls, etc.
                if (v is Int || v is Long) {
                    result.add(v.toLong())
                }
            }
            result
        } catch (_: Exception) {
            // Defensive: invalid JSON must not crash callers. Returning empty
            // is the safe default — the project then inherits all globally
            // enabled servers until a valid value is written.
            emptySet()
        }
    }

    private fun canonicalizeDisabledMcpServerIdsJson(ids: Set<Long>): String {
        val arr = org.json.JSONArray()
        // Sort for stable on-disk representation (helps with diffs and tests).
        ids.toSortedSet().forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * 复制 Uri 内容到项目私有目录并插入知识元数据。
     * 失败时清理临时文件和已插入的元数据行。
     *
     * 流程：先完成所有文件操作，文件操作成功后再插入数据库。
     * 若任何步骤失败，清理临时文件，不留下数据库孤儿行。
     */
    suspend fun createProjectAssetFromUri(
        context: android.content.Context,
        projectId: Long,
        sourceUri: android.net.Uri,
        originalName: String,
        source: String = "USER_UPLOAD"
    ): ProjectKnowledge {
        val remainingBytes = remainingProjectKnowledgeBytes(projectId)
        val tmp = ProjectFileStore.copyIntoProject(
            context = context,
            projectId = projectId,
            sourceUri = sourceUri,
            originalName = originalName,
            source = source,
            maxBytes = remainingBytes
        )
        var assetId: Long = -1L
        var finalFile: java.io.File? = null
        return try {
            val insertedId = projectKnowledgeDao.insertKnowledge(
                ProjectKnowledge(
                    projectId = projectId,
                    fileName = sanitizeFileName(originalName),
                    fileType = classifyFileType(originalName),
                    fileSize = tmp.length(),
                    localFileName = "",
                    source = source
                )
            )
            assetId = insertedId
            finalFile = ProjectFileStore.renameToFinal(tmp, projectId, assetId, originalName)
            val finalRow = ProjectKnowledge(
                id = assetId,
                projectId = projectId,
                fileName = sanitizeFileName(originalName),
                fileType = classifyFileType(originalName),
                fileSize = finalFile.length(),
                localFileName = finalFile.name,
                source = source
            )
            projectKnowledgeDao.insertKnowledge(finalRow)
            finalRow
        } catch (e: Exception) {
            finalFile?.delete()
            tmp.delete()
            if (assetId != -1L) {
                projectKnowledgeDao.deleteKnowledgeById(assetId)
            }
            throw e
        }
    }

    /**
     * Agent 直接写入字节到项目私有目录。
     * 不接受外部路径，只接受原始内容。
     *
     * 流程：先完成所有文件操作，文件操作成功后再插入数据库。
     * 若任何步骤失败，清理临时文件，不留下数据库孤儿行。
     *
     * 禁止覆盖已有同名文件；若存在同名文件则抛出异常。
     */
    suspend fun createAgentProjectAsset(
        projectId: Long,
        fileName: String,
        content: ByteArray,
        fileType: String,
        source: String = "AGENT_CREATED"
    ): ProjectKnowledge {
        val existing = projectKnowledgeDao.getKnowledgeByProject(projectId)
            .find { it.fileName == sanitizeFileName(fileName) }
        if (existing != null) {
            throw IllegalStateException("Asset with name '${sanitizeFileName(fileName)}' already exists in project $projectId")
        }

        val remainingBytes = remainingProjectKnowledgeBytes(projectId)
        if (content.size.toLong() > remainingBytes) {
            throw IllegalStateException(ProjectContentLimits.knowledgeLimitError(remainingBytes))
        }

        val tmp = ProjectFileStore.copyIntoProject(null, projectId, null, fileName, source)
        var assetId: Long = -1L
        var finalFile: java.io.File? = null
        return try {
            tmp.writeBytes(content)
            val insertedId = projectKnowledgeDao.insertKnowledge(
                ProjectKnowledge(
                    projectId = projectId,
                    fileName = sanitizeFileName(fileName),
                    fileType = fileType,
                    fileSize = tmp.length(),
                    localFileName = "",
                    source = source
                )
            )
            assetId = insertedId
            finalFile = ProjectFileStore.renameToFinal(tmp, projectId, assetId, fileName)
            val finalRow = ProjectKnowledge(
                id = assetId,
                projectId = projectId,
                fileName = sanitizeFileName(fileName),
                fileType = fileType,
                fileSize = finalFile.length(),
                localFileName = finalFile.name,
                source = source
            )
            projectKnowledgeDao.insertKnowledge(finalRow)
            finalRow
        } catch (e: Exception) {
            finalFile?.delete()
            tmp.delete()
            if (assetId != -1L) {
                projectKnowledgeDao.deleteKnowledgeById(assetId)
            }
            throw e
        }
    }

    /** 读取项目资产文件。 */
    fun readProjectAssetFile(asset: ProjectKnowledge): java.io.File =
        ProjectFileStore.assetFile(asset)

    /** 追加文本内容到项目资产，并同步数据库文件大小。 */
    suspend fun appendProjectKnowledge(assetId: Long, projectId: Long, content: String) =
        withKnowledgeEditLock(assetId) {
            val asset = projectKnowledgeDao.getKnowledgeById(assetId)
                ?: throw IllegalArgumentException("Knowledge asset not found: $assetId")
            if (asset.projectId != projectId) throw IllegalArgumentException("Knowledge asset does not belong to project $projectId")
            if (asset.fileType == "docx") {
                val appContext = context ?: throw IllegalStateException("DOCX editing requires an application context")
                val file = ProjectFileStore.assetFile(asset)
                val edited = JsDocumentReader(appContext).edit(file, JsDocumentEditOperation.Append, content = content)
                ensureKnowledgeSize(projectId, asset.id, edited.bytes)
                ProjectFileStore.writeBinaryAsset(asset, edited.bytes)
                projectKnowledgeDao.updateKnowledgeFileSize(asset.id, edited.bytes.size.toLong())
                return@withKnowledgeEditLock
            }
            val current = ProjectFileStore.readTextAsset(asset)
            val updated = if (current.isEmpty()) content else "$current\n\n$content"
            ensureKnowledgeSize(projectId, asset.id, updated)
            ProjectFileStore.writeTextAsset(asset, updated)
            projectKnowledgeDao.updateKnowledgeFileSize(asset.id, updated.toByteArray(Charsets.UTF_8).size.toLong())
        }

    /** 按 oldText 替换项目资产中的文本，并同步数据库文件大小。 */
    suspend fun editProjectKnowledge(assetId: Long, projectId: Long, oldText: String, content: String) =
        withKnowledgeEditLock(assetId) {
            if (oldText.isEmpty()) throw IllegalArgumentException("oldText cannot be empty")
            val asset = projectKnowledgeDao.getKnowledgeById(assetId)
                ?: throw IllegalArgumentException("Knowledge asset not found: $assetId")
            if (asset.projectId != projectId) throw IllegalArgumentException("Knowledge asset does not belong to project $projectId")
            if (asset.fileType == "docx") {
                val appContext = context ?: throw IllegalStateException("DOCX editing requires an application context")
                val file = ProjectFileStore.assetFile(asset)
                val edited = JsDocumentReader(appContext).edit(file, JsDocumentEditOperation.Replace, oldText, content)
                ensureKnowledgeSize(projectId, asset.id, edited.bytes)
                ProjectFileStore.writeBinaryAsset(asset, edited.bytes)
                projectKnowledgeDao.updateKnowledgeFileSize(asset.id, edited.bytes.size.toLong())
                return@withKnowledgeEditLock
            }
            val current = ProjectFileStore.readTextAsset(asset)
            if (!current.contains(oldText)) throw IllegalArgumentException("oldText not found in knowledge asset")
            val updated = current.replace(oldText, content)
            ensureKnowledgeSize(projectId, asset.id, updated)
            ProjectFileStore.writeTextAsset(asset, updated)
            projectKnowledgeDao.updateKnowledgeFileSize(asset.id, updated.toByteArray(Charsets.UTF_8).size.toLong())
        }

    private suspend fun <T> withKnowledgeEditLock(assetId: Long, block: suspend () -> T): T {
        val mutex = projectKnowledgeEditMutexes.getOrPut(assetId) { Mutex() }
        mutex.lock()
        return try { block() } finally { mutex.unlock() }
    }

    private suspend fun ensureKnowledgeSize(projectId: Long, assetId: Long, bytes: ByteArray) {
        val assets = projectKnowledgeDao.getKnowledgeByProject(projectId)
        val current = assets.sumOf { it.fileSize.coerceAtLeast(0) }
        val oldSize = assets.firstOrNull { it.id == assetId }?.fileSize ?: 0L
        if (current - oldSize + bytes.size > ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT) {
            throw IllegalStateException(ProjectContentLimits.knowledgeLimitError(
                ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT - (current - oldSize)
            ))
        }
    }

    private suspend fun ensureKnowledgeSize(projectId: Long, assetId: Long, content: String) {
        val assets = projectKnowledgeDao.getKnowledgeByProject(projectId)
        val current = assets.sumOf { it.fileSize.coerceAtLeast(0) }
        val oldSize = assets.firstOrNull { it.id == assetId }?.fileSize ?: 0L
        val newSize = content.toByteArray(Charsets.UTF_8).size.toLong()
        if (current - oldSize + newSize > ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT) {
            throw IllegalStateException(ProjectContentLimits.knowledgeLimitError(
                ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT - (current - oldSize)
            ))
        }
    }

    /** 删除项目资产（数据库行 + 文件）。先删 DB 再删文件。 */
    suspend fun deleteUserProjectAsset(asset: ProjectKnowledge) {
        projectKnowledgeDao.deleteKnowledge(asset)
        ProjectFileStore.deleteAsset(asset)
    }

    /** 读取项目记忆内容（不存在时返回空字符串）。 */
    fun readProjectMemory(projectId: Long): String =
        ProjectFileStore.readMemory(projectId)

    /** 原子更新项目记忆内容。 */
    suspend fun updateProjectMemory(projectId: Long, content: String) {
        ProjectFileStore.writeMemory(projectId, content)
    }

    /** 追加内容到项目记忆末尾。 */
    suspend fun appendProjectMemory(projectId: Long, content: String) {
        requireMemoryCanBeModified(projectId)
        val current = readProjectMemory(projectId)
        val newContent = if (current.isNotBlank()) "$current\n\n$content" else content
        ProjectFileStore.writeMemory(projectId, newContent)
    }

    /** 按内容范围替换项目记忆（将 oldText 替换为 newText）。 */
    suspend fun replaceProjectMemoryRange(projectId: Long, oldText: String, newText: String) {
        requireMemoryCanBeModified(projectId)
        val current = readProjectMemory(projectId)
        if (oldText.isEmpty()) {
            throw IllegalArgumentException("oldText cannot be empty")
        }
        if (!current.contains(oldText)) {
            throw IllegalArgumentException("oldText not found in project memory")
        }
        val updated = current.replace(oldText, newText)
        ProjectFileStore.writeMemory(projectId, updated)
    }

    /** 删除项目记忆中包含指定文本的段落。 */
    suspend fun deleteProjectMemorySection(projectId: Long, sectionText: String) {
        requireMemoryCanBeModified(projectId)
        val current = readProjectMemory(projectId)
        if (sectionText.isEmpty()) {
            throw IllegalArgumentException("sectionText cannot be empty")
        }
        if (!current.contains(sectionText)) {
            throw IllegalArgumentException("sectionText not found in project memory")
        }
        val updated = current.replace(sectionText, "").replace(Regex("\n{3,}"), "\n\n").trim()
        ProjectFileStore.writeMemory(projectId, updated)
    }

    private suspend fun remainingProjectKnowledgeBytes(projectId: Long): Long {
        val usedBytes = projectKnowledgeDao.getKnowledgeByProject(projectId)
            .fold(0L) { total, asset -> total + asset.fileSize.coerceAtLeast(0) }
        return (ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT - usedBytes).coerceAtLeast(0)
    }

    private fun requireMemoryCanBeModified(projectId: Long) {
        require(ProjectFileStore.isMemoryWithinLimit(projectId)) {
            "Project memory is larger than 128 KiB and was read only partially; refusing this edit to preserve existing content."
        }
    }

    private fun classifyFileType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".bmp") || lower.endsWith(".webp") -> "image"
            lower.endsWith(".pdf") -> "pdf"
            lower.endsWith(".docx") -> "docx"
            lower.endsWith(".md") -> "md"
            lower.endsWith(".txt") -> "txt"
            lower.endsWith(".doc") -> "doc"
            else -> "other"
        }
    }

    /** 清理文件名中的路径分隔符和路径遍历字符。 */
    private fun sanitizeFileName(originalName: String): String {
        return originalName.replace(Regex("[\\\\/<>|?*]"), "_").replace(Regex("\\.{2,}"), "_")
    }
}
