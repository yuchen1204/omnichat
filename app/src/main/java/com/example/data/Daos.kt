package com.example.data

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

    @Query("SELECT * FROM memory_items WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: Long): MemoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryItem): Long

    @Update
    suspend fun updateMemory(memory: MemoryItem)

    /** 强化一条记忆：confidence+1，更新 updatedAt，内容可选更新 */
    @Query("UPDATE memory_items SET confidence = confidence + 1, updatedAt = :now, content = :content WHERE id = :id")
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
