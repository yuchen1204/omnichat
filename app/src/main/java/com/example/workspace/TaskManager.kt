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
     * 查找团队中状态为 PENDING 且无认领者的第一个任务，然后通过原子更新将其
     * 分配给指定 Agent。如果在查找和认领之间被其他 Agent 抢占，返回 null。
     *
     * 认领时优先匹配 intendedAgent 与当前 Agent 相同的任务，
     * 如果没有则退化为任意可认领任务（intendedAgent IS NULL）。
     *
     * blockedBy 依赖检查在应用层完成：查询所有候选任务后，过滤掉仍有未完成
     * 依赖的任务（依赖任务的 status != COMPLETED），再取第一个尝试认领。
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
        // 获取所有候选任务（PENDING、无 owner、intendedAgent 匹配）
        val candidates = dao.findClaimableTasks(teamName, agentName)
        if (candidates.isEmpty()) return null

        // 获取当前团队所有已完成任务的 ID 集合，用于 blockedBy 检查
        val allTasks = dao.getTasks(teamName)
        val completedIds = allTasks
            .filter { it.status == "COMPLETED" }
            .map { it.id.toString() }
            .toSet()

        // 找到第一个 blockedBy 全部已完成的任务
        val task = candidates.firstOrNull { candidate ->
            candidate.blockedBy.isEmpty() || completedIds.containsAll(candidate.blockedBy)
        } ?: return null

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
}
