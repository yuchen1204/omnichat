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
     * FIX (Bug #3): 先在应用层过滤掉 blockedBy 未满足的候选，再用原子单任务 SQL
     * [TeamTaskDao.claimTask] 认领。原实现先调用 [TeamTaskDao.claimNextAvailableTask]
     * （只按 id 升序选最小 PENDING 任务，不看 blockedBy），如果最小 id 任务被阻塞，
     * 会原子认领 → 检测依赖未满足 → 释放为 PENDING → 返回 null。
     * 由于 observeTasks 一旦看到这条任务可认领就 Wakeup Agent，导致 Agent
     * 反复认领-释放同一阻塞任务，永远轮不到后面真正可执行的任务（饥饿）。
     *
     * 新流程：
     *   1. 取所有候选 PENDING 任务（应用层快照）
     *   2. 应用层按 blockedBy / intendedAgent 过滤
     *   3. 按 (intendedAgent 优先) + (id 升序) 排序
     *   4. 用单任务 [TeamTaskDao.claimTask]（WHERE id=? AND owner IS NULL）原子认领
     *   5. 抢占失败（其它 Agent 抢先）则换下一个候选继续尝试
     *
     * 这样既保留单任务原子认领的并发安全，又避免阻塞任务卡住整个队列。
     *
     * @param teamName 团队名称
     * @param agentName 认领者 Agent 名称
     * @return 认领成功返回任务副本（已更新 owner 和 status），否则返回 null
     */
    suspend fun tryClaimNextTask(
        teamName: String,
        agentName: String
    ): TeamTask? {
        // 1. 拉取候选任务和已完成任务 id 集合
        val candidates = dao.findClaimableTasks(teamName, agentName)
        if (candidates.isEmpty()) return null

        val completedTaskIds = dao.getCompletedTaskIds(teamName).map { it.toString() }.toSet()

        // 2. 过滤 blockedBy 未满足的任务，3. 排序：intendedAgent 精确匹配优先，否则按 id
        val eligible = candidates
            .asSequence()
            .filter { it.blockedBy.isEmpty() || completedTaskIds.containsAll(it.blockedBy) }
            .sortedWith(
                compareBy(
                    { if (it.intendedAgent == agentName) 0 else 1 },
                    { it.id }
                )
            )
            .toList()

        if (eligible.isEmpty()) return null

        // 4-5. 逐个尝试原子认领，抢占失败时尝试下一个
        for (candidate in eligible) {
            val affected = dao.claimTask(candidate.id, agentName)
            if (affected == 0) {
                // 被其它 Agent 抢先，尝试下一个候选
                continue
            }
            val claimed = dao.getInProgressTaskByOwner(teamName, agentName)
            if (claimed == null) {
                // 极少数情况下被外部清理，释放孤儿
                android.util.Log.w(
                    "TaskManager",
                    "[$agentName] Task ${candidate.id} claimed but not found via getInProgressTaskByOwner — releasing orphan"
                )
                dao.releaseOrphanTask(teamName, agentName)
                continue
            }
            return claimed
        }

        return null
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
