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
interface AgentPresetDao {
    @Query("SELECT * FROM agent_presets ORDER BY createdAt ASC")
    fun getAllPresetsFlow(): Flow<List<AgentPreset>>

    @Query("SELECT * FROM agent_presets ORDER BY createdAt ASC")
    suspend fun getAllPresets(): List<AgentPreset>

    @Query("SELECT * FROM agent_presets WHERE id = :id LIMIT 1")
    suspend fun getPresetById(id: Long): AgentPreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: AgentPreset): Long

    @Update
    suspend fun updatePreset(preset: AgentPreset)

    @Delete
    suspend fun deletePreset(preset: AgentPreset)
}

@Dao
interface WorkspaceSessionDao {
    @Query("SELECT * FROM workspace_sessions ORDER BY lastActiveAt DESC")
    fun getAllSessionsFlow(): Flow<List<WorkspaceSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkspaceSession): Long

    @Query("UPDATE workspace_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE workspace_sessions SET isActive = :isActive, lastActiveAt = :lastActiveAt WHERE id = :id")
    suspend fun updateStatus(id: Long, isActive: Boolean, lastActiveAt: Long)

    @Query("DELETE FROM workspace_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM workspace_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WorkspaceSession?
}

@Dao
interface AgentInstanceDao {
    @Query("SELECT * FROM agent_instances WHERE workspaceSessionId = :wsId ORDER BY createdAt ASC")
    suspend fun getByWorkspaceSession(wsId: Long): List<AgentInstance>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstance(instance: AgentInstance): Long

    @Query("DELETE FROM agent_instances WHERE workspaceSessionId = :wsId")
    suspend fun deleteByWorkspaceSession(wsId: Long)
}

@Dao
interface WorkspaceMessageDao {
    @Query("SELECT * FROM workspace_messages WHERE agentInstanceId = :agentId ORDER BY timestamp ASC")
    fun getMessagesByAgentFlow(agentId: Long): Flow<List<WorkspaceMessage>>

    @Query("SELECT * FROM workspace_messages WHERE agentInstanceId = :agentId ORDER BY timestamp ASC")
    suspend fun getMessagesByAgent(agentId: Long): List<WorkspaceMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: WorkspaceMessage): Long

    @Query("DELETE FROM workspace_messages WHERE workspaceSessionId = :wsId")
    suspend fun deleteByWorkspaceSession(wsId: Long)

    @Query("DELETE FROM workspace_messages WHERE agentInstanceId = :agentInstanceId")
    suspend fun deleteByAgentInstance(agentInstanceId: Long)
}

// ═══════════════════════════════════════════════════════════════════════════════
// Agent Team 任务系统 DAO
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 团队任务数据访问对象。
 *
 * 对标 Claude Code 的任务文件操作（~/.claude/tasks/{teamName}/）。
 * 支持任务的创建、查询、认领和清理。
 */
@Dao
interface TeamTaskDao {
    /**
     * 观察指定团队的任务列表变化。
     *
     * @param teamName 团队名称
     * @return 任务列表的响应式数据流，按 ID 升序排列
     */
    @Query("SELECT * FROM team_tasks WHERE teamName = :teamName ORDER BY id ASC")
    fun getTasksFlow(teamName: String): Flow<List<TeamTask>>

    /**
     * 获取指定团队的所有任务（一次性查询）。
     *
     * @param teamName 团队名称
     * @return 任务列表，按 ID 升序排列
     */
    @Query("SELECT * FROM team_tasks WHERE teamName = :teamName ORDER BY id ASC")
    suspend fun getTasks(teamName: String): List<TeamTask>

    /**
     * 查找可认领的任务（带 Agent 匹配）。
     *
     * 查找状态为 PENDING 且无认领者的第一个任务。
     * 优先匹配 intendedAgent 与当前 Agent 名称相同的任务，
     * 如果没有匹配则退化为任意可认领任务（intendedAgent IS NULL）。
     *
     * 注意：blockedBy 的依赖检查在应用层（TaskManager.tryClaimNextTask）完成，
     * 此处只返回 PENDING 且无 owner 的候选任务，由调用方过滤掉仍被阻塞的任务。
     *
     * 对标 inProcessRunner.ts 中 findAvailableTask() 的逻辑。
     *
     * @param teamName 团队名称
     * @param agentName 当前 Agent 名称，用于匹配 intendedAgent
     * @return 可认领的任务列表（未过滤 blockedBy），按优先级排序
     */
    @Query("""
        SELECT * FROM team_tasks 
        WHERE teamName = :teamName 
          AND status = 'PENDING' 
          AND owner IS NULL 
          AND (intendedAgent IS NULL OR intendedAgent = :agentName)
        ORDER BY 
            CASE WHEN intendedAgent = :agentName THEN 0 ELSE 1 END,
            id ASC 
    """)
    suspend fun findClaimableTasks(teamName: String, agentName: String): List<TeamTask>

    /**
     * 查找可认领的任务（带 Agent 匹配，兼容旧调用）。
     *
     * @deprecated 使用 findClaimableTasks 替代，以支持应用层 blockedBy 过滤
     */
    @Query("""
        SELECT * FROM team_tasks 
        WHERE teamName = :teamName 
          AND status = 'PENDING' 
          AND owner IS NULL 
          AND blockedBy = ''
          AND (intendedAgent IS NULL OR intendedAgent = :agentName)
        ORDER BY 
            CASE WHEN intendedAgent = :agentName THEN 0 ELSE 1 END,
            id ASC 
        LIMIT 1
    """)
    suspend fun findClaimableTask(teamName: String, agentName: String): TeamTask?

    /**
     * 插入新任务。
     *
     * @param task 任务实体
     * @return 新插入任务的 ID
     */
    @Insert
    suspend fun insert(task: TeamTask): Long

    /**
     * 更新任务。
     *
     * @param task 任务实体（根据 id 匹配）
     */
    @Update
    suspend fun update(task: TeamTask)

    /**
     * 原子认领任务。
     *
     * 通过 SQL 的 WHERE 条件保证原子性：只有当任务尚未被认领（owner IS NULL）时
     * 才会更新成功。返回值为受影响的行数（0 表示认领失败，1 表示成功）。
     *
     * @param taskId 任务 ID
     * @param agentName 认领者 Agent 名称
     * @param now 更新时间戳
     * @return 受影响的行数（0 或 1）
     */
    @Query("UPDATE team_tasks SET owner = :agentName, status = 'IN_PROGRESS', updatedAt = :now WHERE id = :taskId AND owner IS NULL")
    suspend fun claimTask(taskId: Long, agentName: String, now: Long = System.currentTimeMillis()): Int

    /**
     * 删除指定团队的所有任务。
     *
     * 团队解散时调用，清理关联的任务数据。
     *
     * @param teamName 团队名称
     */
    @Query("DELETE FROM team_tasks WHERE teamName = :teamName")
    suspend fun deleteAllForTeam(teamName: String)

    /**
     * 标记指定 Agent 的 IN_PROGRESS 任务为 COMPLETED。
     *
     * WHY: Agent 执行完任务后必须更新状态，否则任务永远卡在 IN_PROGRESS。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     * @param now 更新时间戳
     */
    @Query("UPDATE team_tasks SET status = 'COMPLETED', updatedAt = :now WHERE teamName = :teamName AND owner = :agentName AND status = 'IN_PROGRESS'")
    suspend fun completeTaskByOwner(teamName: String, agentName: String, now: Long = System.currentTimeMillis())

    /**
     * 标记指定 Agent 的 IN_PROGRESS 任务为 FAILED。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     * @param now 更新时间戳
     */
    @Query("UPDATE team_tasks SET status = 'FAILED', updatedAt = :now WHERE teamName = :teamName AND owner = :agentName AND status = 'IN_PROGRESS'")
    suspend fun failTaskByOwner(teamName: String, agentName: String, now: Long = System.currentTimeMillis())
}

@Dao
interface McpFilePermissionDao {
    @Query("SELECT * FROM mcp_file_permissions WHERE path = :path LIMIT 1")
    suspend fun getPermissionByPath(path: String): McpFilePermission?

    /**
     * 前缀匹配：查找已授权的父目录。
     * 例如用户对 /sdcard/Documents 授权后，访问 /sdcard/Documents/a.txt 也应放行。
     * 用 LIKE 匹配以 path 开头的记录（path 本身是被查路径，记录中存的是父目录）。
     */
    @Query("SELECT * FROM mcp_file_permissions WHERE :path LIKE path || '%' AND path != :path ORDER BY length(path) DESC LIMIT 1")
    suspend fun getPermissionByPathPrefix(path: String): McpFilePermission?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermission(permission: McpFilePermission): Long

    @Query("DELETE FROM mcp_file_permissions WHERE path = :path")
    suspend fun deletePermissionByPath(path: String)
}
