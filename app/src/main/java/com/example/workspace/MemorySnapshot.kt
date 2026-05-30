package com.example.workspace

import android.util.Log
import java.io.File

/**
 * Agent Memory Snapshot 管理。
 *
 * 支持 Agent 级别持久化记忆：
 * - user scope: 用户级记忆，跨项目共享
 * - project scope: 项目级记忆，团队成员共享
 * - local scope: 本地记忆，仅当前 Agent 实例
 *
 * 对齐 Claude Code 的 agentMemorySnapshot.ts。
 */
object MemorySnapshotManager {
    private const val TAG = "MemorySnapshot"

    /**
     * Memory scope 类型。
     */
    enum class MemoryScope {
        USER,    // ~/.claude/agents/<agentType>/memory.json
        PROJECT, // .claude/agents/<agentType>/memory.json
        LOCAL,   // 临时目录，不持久化
    }

    /**
     * 获取 memory 文件路径。
     */
    fun getMemoryFilePath(
        agentType: String,
        scope: MemoryScope,
        sandboxPath: String?,
    ): File? {
        return when (scope) {
            MemoryScope.USER -> {
                val userDir = File(System.getProperty("user.home"), ".claude/agents/$agentType")
                userDir.mkdirs()
                File(userDir, "memory.json")
            }
            MemoryScope.PROJECT -> {
                if (sandboxPath == null) return null
                val projectDir = File(sandboxPath, ".claude/agents/$agentType")
                projectDir.mkdirs()
                File(projectDir, "memory.json")
            }
            MemoryScope.LOCAL -> null  // 不持久化
        }
    }

    /**
     * 加载 memory snapshot。
     */
    fun loadSnapshot(
        agentType: String,
        scope: MemoryScope,
        sandboxPath: String?,
    ): String? {
        val file = getMemoryFilePath(agentType, scope, sandboxPath)
        if (file == null || !file.exists()) {
            Log.d(TAG, "No memory snapshot for $agentType (scope=$scope)")
            return null
        }

        return try {
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memory snapshot for $agentType", e)
            null
        }
    }

    /**
     * 保存 memory snapshot。
     */
    fun saveSnapshot(
        agentType: String,
        scope: MemoryScope,
        sandboxPath: String?,
        content: String,
    ): Boolean {
        val file = getMemoryFilePath(agentType, scope, sandboxPath)
        if (file == null) {
            Log.d(TAG, "Skipping save for local scope $agentType")
            return false
        }

        return try {
            file.writeText(content)
            Log.d(TAG, "Saved memory snapshot for $agentType to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save memory snapshot for $agentType", e)
            false
        }
    }

    /**
     * 检查是否有更新的 project snapshot。
     *
     * 用于 agent memory 初始化：
     * - 如果 user scope 无 memory，从 project scope 复制
     * - 如果 project snapshot 更新，提示用户
     */
    fun checkSnapshotUpdate(
        agentType: String,
        sandboxPath: String?,
    ): SnapshotCheckResult {
        val userFile = getMemoryFilePath(agentType, MemoryScope.USER, null)
        val projectFile = getMemoryFilePath(agentType, MemoryScope.PROJECT, sandboxPath)

        if (projectFile == null || !projectFile.exists()) {
            return SnapshotCheckResult.NO_PROJECT_SNAPSHOT
        }

        if (userFile == null || !userFile.exists()) {
            return SnapshotCheckResult.INITIALIZE_FROM_PROJECT
        }

        val projectTimestamp = projectFile.lastModified()
        val userTimestamp = userFile.lastModified()

        if (projectTimestamp > userTimestamp) {
            return SnapshotCheckResult.PROJECT_NEWER
        }

        return SnapshotCheckResult.USER_CURRENT
    }

    enum class SnapshotCheckResult {
        NO_PROJECT_SNAPSHOT,
        INITIALIZE_FROM_PROJECT,
        PROJECT_NEWER,
        USER_CURRENT,
    }

    /**
     * 从 project snapshot 初始化 user memory。
     */
    fun initializeFromProject(
        agentType: String,
        sandboxPath: String?,
    ): Boolean {
        val projectContent = loadSnapshot(agentType, MemoryScope.PROJECT, sandboxPath)
        if (projectContent == null) return false

        return saveSnapshot(agentType, MemoryScope.USER, null, projectContent)
    }
}
