package com.example.workspace

import com.example.data.TeamTask
import com.example.data.TeamTaskDao
import kotlinx.coroutines.flow.Flow

/**
 * 任务管理器，支持空闲 Agent 自动认领。
 *
 * 对标 Claude Code 的任务系统（~/.claude/tasks/{teamName}/）。
 * 提供任务的创建、认领和观察能力。
 *
 * 核心机制：
 * - [createTask]：创建待分配的任务
 * - [tryClaimNextTask]：空闲 Agent 自动认领未分配的任务
 * - [observeTasks]：观察团队的任务列表变化
 *
 * 认领逻辑保证原子性：通过 [TeamTaskDao.claimTask] 的 `WHERE owner IS NULL`
 * 条件确保同一任务不会被多个 Agent 重复认领。
 *
 * @property dao 任务数据访问对象
 */
class TaskManager(private val dao: TeamTaskDao) {

    /**
     * 创建新任务。
     *
     * 任务初始状态为 AVAILABLE、无认领者。空闲 Agent 可通过 [tryClaimNextTask] 自动认领。
     *
     * @param teamName 所属团队名称
     * @param subject 任务主题（简短描述）
     * @param description 任务详细描述（可选）
     * @param intendedAgent 预期执行的 Agent 名称（可选，null 表示任意 Agent 可认领）
     * @param blockedBy 被阻塞的任务 ID 列表，所有依赖任务完成后才能认领（可选）
     * @return 新创建的 TeamTask 对象
     */
    suspend fun createTask(
        teamName: String,
        subject: String,
        description: String = "",
        intendedAgent: String? = null,
        blockedBy: List<String> = emptyList()
    ): TeamTask {
        val task = TeamTask(
            teamName = teamName,
            subject = subject,
            description = description,
            intendedAgent = intendedAgent,
            blockedBy = blockedBy,
        )
        val id = dao.insert(task)
        return task.copy(id = id)
    }

    /**
     * 尝试认领下一个可执行的任务。
     *
     * 使用单条原子 SQL（[TeamTaskDao.claimNextAvailableTask]）直接认领，
     * 消除旧实现中查询与认领之间的竞态窗口。认领成功后在应用层验证 blockedBy
     * 依赖是否已全部完成；若依赖未满足则释放任务（重置为 PENDING）并返回 null。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     * @return 认领成功返回任务副本（已更新 owner 和 status），否则返回 null
     */
    suspend fun tryClaimNextTask(
        teamName: String,
        agentName: String
    ): TeamTask? {
        // 原子认领：单条 SQL 完成查找 + 更新，无竞态窗口
        val affected = dao.claimNextAvailableTask(teamName, agentName)
        if (affected == 0) return null

        // 取回刚认领的任务（owner = agentName, status = IN_PROGRESS）
        // WHY: 极少数情况下（如 DB 写入后立即被另一线程清理），getInProgressTaskByOwner
        // 可能返回 null，此时任务已被标记为 IN_PROGRESS 但无人处理，成为"孤儿任务"。
        // 必须调用 releaseTask 将其重置为 PENDING，否则依赖链永久卡住（Bug #15）。
        val claimed = dao.getInProgressTaskByOwner(teamName, agentName)
        if (claimed == null) {
            android.util.Log.w("TaskManager", "[$agentName] Task claimed (affected=$affected) but not found via getInProgressTaskByOwner — releasing orphan")
            // 尝试按 owner+status 释放，防止孤儿任务永久卡在 IN_PROGRESS
            dao.releaseOrphanTask(teamName, agentName)
            return null
        }

        // 应用层 blockedBy 检查（单条 SQL 无法高效内联此逻辑）
        if (claimed.blockedBy.isNotEmpty()) {
            val completedTaskIds = dao.getCompletedTaskIds(teamName).map { it.toString() }.toSet()
            if (!completedTaskIds.containsAll(claimed.blockedBy)) {
                // 依赖未满足，释放任务
                dao.releaseTask(claimed.id)
                return null
            }
        }

        return claimed
    }

    /**
     * 观察团队的任务列表变化。
     *
     * 返回一个 Flow，当任务被创建、认领或完成时自动发出更新。
     * 适用于 UI 层实时展示任务进度。
     *
     * @param teamName 团队名称
     * @return 任务列表的响应式数据流
     */
    fun observeTasks(teamName: String): Flow<List<TeamTask>> = dao.getTasksFlow(teamName)

    /**
     * 标记指定 Agent 的任务为完成。
     *
     * WHY: executeTask 完成后必须更新任务状态，否则任务永远卡在 IN_PROGRESS，
     * UI 显示异常且依赖链无法推进。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     */
    suspend fun completeTask(teamName: String, agentName: String) {
        dao.completeTaskByOwner(teamName, agentName)
    }

    /**
     * 标记指定 Agent 的任务为失败。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     */
    suspend fun failTask(teamName: String, agentName: String) {
        dao.failTaskByOwner(teamName, agentName)
    }

    /**
     * 放弃任务。
     *
     * 将 Agent 当前正在执行的任务重置为 PENDING 状态。
     * 适用于 Agent 收到了 ShutdownRequest 或者在竞态条件下需要回滚任务认领。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     */
    suspend fun abandonTask(teamName: String, agentName: String) {
        dao.releaseOrphanTask(teamName, agentName)
    }
}
