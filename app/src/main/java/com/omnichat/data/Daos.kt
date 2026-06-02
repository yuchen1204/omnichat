package com.omnichat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelConfigDao {
    @Query("SELECT * FROM model_configs")
    fun getAllConfigsFlow(): Flow<List<ModelConfig>>

    @Query("SELECT * FROM model_configs")
    suspend fun getAllConfigs(): List<ModelConfig>

    @Query("SELECT * FROM model_configs WHERE id = :id")
    suspend fun getConfigById(id: Long): ModelConfig?

    @Query("SELECT * FROM model_configs WHERE isDefaultProvider = 1 LIMIT 1")
    suspend fun getDefaultProvider(): ModelConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ModelConfig): Long

    @Update
    suspend fun updateConfig(config: ModelConfig)

    @Delete
    suspend fun deleteConfig(config: ModelConfig)

    @Query("UPDATE model_configs SET isDefaultProvider = 0")
    suspend fun clearDefaultProvider()

    @Transaction
    suspend fun setDefaultProvider(id: Long) {
        val c = getConfigById(id) ?: return
        val wasDefault = c.isDefaultProvider
        clearDefaultProvider()
        if (!wasDefault) {
            updateConfig(c.copy(isDefaultProvider = true))
        }
    }
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessionsFlow(): Flow<List<Session>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session): Long

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySessionFlow(sessionId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySession(sessionId: Long): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun deleteMessagesFrom(sessionId: Long, timestamp: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: Long)
}

@Dao
interface MemoryItemDao {
    // 按置信度降序、再按更新时间降序排列，让最稳定的事实排在前面
    @Query("SELECT * FROM memory_items ORDER BY pinned DESC, confidence DESC, updatedAt DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memory_items ORDER BY pinned DESC, confidence DESC, updatedAt DESC")
    suspend fun getAllMemories(): List<MemoryItem>

    @Query("SELECT * FROM memory_items WHERE content LIKE '%' || :keyword || '%' ORDER BY pinned DESC, confidence DESC, updatedAt DESC")
    suspend fun searchMemoriesByKeyword(keyword: String): List<MemoryItem>

    @Query("SELECT * FROM memory_items WHERE tags LIKE '%' || :tag || '%' ORDER BY pinned DESC, confidence DESC, updatedAt DESC")
    suspend fun searchMemoriesByTag(tag: String): List<MemoryItem>

    @Query("SELECT * FROM memory_items WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: Long): MemoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryItem): Long

    @Update
    suspend fun updateMemory(memory: MemoryItem)

    /** 强化一条记忆：confidence+1，更新 updatedAt 和 lastReinforcedAt，内容可选更新 */
    @Query("UPDATE memory_items SET confidence = confidence + 1, updatedAt = :now, lastReinforcedAt = :now, content = :content WHERE id = :id")
    suspend fun reinforceMemory(id: Long, content: String, now: Long)

    /** 切换 pinned 状态 */
    @Query("UPDATE memory_items SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM memory_items WHERE id = :id AND pinned = 0")
    suspend fun deleteMemoryById(id: Long)

    /** 仅删除未被 pin 的条目 */
    @Query("DELETE FROM memory_items WHERE pinned = 0")
    suspend fun deleteAllUnpinnedMemories()

    @Query("DELETE FROM memory_items")
    suspend fun deleteAllMemories()

    /** 批量衰减置信度：每行独立计算衰减天数，confidence 减去该行的天数差（下限 1） */
    @Query("UPDATE memory_items SET confidence = MAX(1, confidence - MAX(0, CAST((:now - lastReinforcedAt) / 86400000 AS INT))), lastReinforcedAt = :now WHERE pinned = 0 AND lastReinforcedAt < :now AND confidence > 1")
    suspend fun batchDecayConfidence(now: Long)
}

@Dao
interface MemoryAssociationDao {
    @Query("SELECT * FROM memory_associations WHERE fromMemoryId = :memoryId")
    suspend fun getOutgoing(memoryId: Long): List<MemoryAssociation>

    @Query("SELECT * FROM memory_associations WHERE toMemoryId = :memoryId")
    suspend fun getIncoming(memoryId: Long): List<MemoryAssociation>

    @Query("SELECT * FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun getAllForMemory(memoryId: Long): List<MemoryAssociation>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(assoc: MemoryAssociation): Long

    @Query("DELETE FROM memory_associations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun deleteAllForMemory(memoryId: Long)

    @Query("SELECT COUNT(*) FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun countForMemory(memoryId: Long): Int

    @Query("""
        SELECT * FROM memory_items
        WHERE id NOT IN (SELECT fromMemoryId FROM memory_associations)
          AND id NOT IN (SELECT toMemoryId FROM memory_associations)
        ORDER BY confidence DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getUnassociatedMemories(limit: Int): List<MemoryItem>
}

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates")
    fun getAllTemplatesFlow(): Flow<List<PromptTemplate>>

    @Query("SELECT * FROM prompt_templates ORDER BY id ASC")
    suspend fun getAllTemplates(): List<PromptTemplate>

    @Query("SELECT * FROM prompt_templates WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTemplate(): PromptTemplate?

    @Query("SELECT * FROM prompt_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): PromptTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: PromptTemplate): Long

    @Query("UPDATE prompt_templates SET isActive = 0")
    suspend fun clearActiveTemplates()

    @Transaction
    suspend fun setActiveTemplate(id: Long) {
        clearActiveTemplates()
        val t = getTemplateById(id)
        if (t != null) {
            insertTemplate(t.copy(isActive = true))
        }
    }

    @Delete
    suspend fun deleteTemplate(template: PromptTemplate)
}

@Dao
interface FetchedModelDao {
    @Query("SELECT * FROM fetched_models ORDER BY providerId ASC, modelId ASC")
    fun getAllFetchedModelsFlow(): Flow<List<FetchedModel>>

    @Query("SELECT * FROM fetched_models ORDER BY providerId ASC, modelId ASC")
    suspend fun getAllFetchedModels(): List<FetchedModel>

    @Query("SELECT * FROM fetched_models WHERE providerId = :providerId ORDER BY modelId ASC")
    fun getModelsByProviderFlow(providerId: Long): Flow<List<FetchedModel>>

    @Query("SELECT * FROM fetched_models WHERE providerId = :providerId ORDER BY modelId ASC")
    suspend fun getModelsByProvider(providerId: Long): List<FetchedModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFetchedModel(model: FetchedModel): Long

    @Query("DELETE FROM fetched_models WHERE providerId = :providerId")
    suspend fun deleteModelsByProvider(providerId: Long)
}

@Dao
interface SessionSummaryDao {
    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSummaryBySession(sessionId: Long): SessionSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: SessionSummary)

    @Query("DELETE FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun deleteSummaryBySession(sessionId: Long)
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    fun getAllServersFlow(): Flow<List<McpServer>>

    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    suspend fun getAllServers(): List<McpServer>

    @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): McpServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: McpServer): Long

    @Update
    suspend fun updateServer(server: McpServer)

    @Delete
    suspend fun deleteServer(server: McpServer)

    @Query("SELECT * FROM mcp_servers WHERE isEnabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabledServers(): List<McpServer>
}

@Dao
interface UISettingsDao {
    @Query("SELECT * FROM ui_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<UISettings?>

    @Query("SELECT * FROM ui_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): UISettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: UISettings)
}

@Dao
interface ColorSchemePresetDao {
    @Query("SELECT * FROM color_scheme_presets ORDER BY createdAt ASC")
    fun getAllPresetsFlow(): Flow<List<ColorSchemePreset>>

    @Query("SELECT * FROM color_scheme_presets ORDER BY createdAt ASC")
    suspend fun getAllPresets(): List<ColorSchemePreset>

    @Query("SELECT COUNT(*) FROM color_scheme_presets")
    suspend fun getCount(): Int

    @Query("SELECT * FROM color_scheme_presets WHERE schemeId = :schemeId LIMIT 1")
    suspend fun getPresetById(schemeId: String): ColorSchemePreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: ColorSchemePreset)

    @Query("DELETE FROM color_scheme_presets WHERE schemeId = :schemeId")
    suspend fun deletePresetById(schemeId: String)
}

@Dao
interface McpFilePermissionDao {
    @Query("SELECT * FROM mcp_file_permissions WHERE path = :path AND permissionType = :permissionType LIMIT 1")
    suspend fun getPermissionByPath(path: String, permissionType: String): McpFilePermission?

    /**
     * 前缀匹配：查找已授权的父目录。
     * 例如用户对 /sdcard/Documents 授权后，访问 /sdcard/Documents/a.txt 也应放行。
     * 用 LIKE 匹配以 path 开头的记录（path 本身是被查路径，记录中存的是父目录）。
     * write 权限隐含 read：read 检查时同时匹配 read 和 write 记录。
     */
    @Query("SELECT * FROM mcp_file_permissions WHERE :path LIKE path || '%' AND path != :path AND (permissionType = :permissionType OR permissionType = 'write') ORDER BY length(path) DESC LIMIT 1")
    suspend fun getPermissionByPathPrefix(path: String, permissionType: String): McpFilePermission?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermission(permission: McpFilePermission): Long

    @Query("DELETE FROM mcp_file_permissions WHERE path = :path AND permissionType = :permissionType")
    suspend fun deletePermissionByPath(path: String, permissionType: String)

    @Query("SELECT * FROM mcp_file_permissions")
    suspend fun getAllPermissions(): List<McpFilePermission>

    @Query("DELETE FROM mcp_file_permissions")
    suspend fun deleteAllPermissions()
}

@Dao
interface MemoryAuditDao {
    @Query("SELECT * FROM memory_audit_log WHERE memoryId = :memoryId ORDER BY timestamp DESC")
    suspend fun getHistoryForMemory(memoryId: Long): List<MemoryAuditEntry>

    @Query("SELECT * FROM memory_audit_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentActivity(limit: Int = 100): List<MemoryAuditEntry>

    @Insert
    suspend fun insert(entry: MemoryAuditEntry)

    @Query("DELETE FROM memory_audit_log WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}
