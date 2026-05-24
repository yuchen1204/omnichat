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
     * @return 新创建的 TeamTask 对象
     */
    suspend fun createTask(
        teamName: String,
        subject: String,
        description: String = ""
    ): TeamTask {
        val task = TeamTask(
            teamName = teamName,
            subject = subject,
            description = description,
        )
        val id = dao.insert(task)
        return task.copy(id = id)
    }

    /**
     * 尝试认领下一个可执行的任务。
     *
     * 查找团队中状态为 PENDING 且无认领者的第一个任务，然后通过原子更新将其
     * 分配给指定 Agent。如果在查找和认领之间被其他 Agent 抢占，返回 null。
     *
     * 对标 inProcessRunner.ts 中的 tryClaimNextTask() 逻辑。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     * @return 认领成功返回任务副本（已更新 owner 和 status），否则返回 null
     */
    suspend fun tryClaimNextTask(
        teamName: String,
        agentName: String
    ): TeamTask? {
        val task = dao.findClaimableTask(teamName) ?: return null
        val claimed = dao.claimTask(task.id, agentName)
        return if (claimed > 0) {
            task.copy(owner = agentName, status = "IN_PROGRESS")
        } else {
            null
        }
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
}
