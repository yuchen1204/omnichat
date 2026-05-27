package com.example.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

// ═══════════════════════════════════════════════════════════════════════════════
// 任务类型与状态枚举
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 任务类型枚举。
 *
 * 区分工作区中不同角色发起的任务，便于按类型过滤和管理。
 */
enum class TaskType {
    /** Orchestrator 发起的主控任务 */
    ORCHESTRATOR,
    /** Teammate 执行的子任务 */
    TEAMMATE,
    /** 协调器发起的跨 Agent 协调任务 */
    COORDINATOR,
}

/**
 * 任务状态枚举。
 *
 * 表示任务在其生命周期中的当前位置。
 */
enum class TaskStatus {
    /** 已创建，尚未开始执行 */
    CREATED,
    /** 正在执行中 */
    RUNNING,
    /** 已成功完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 已被取消 */
    CANCELLED,
}

// ═══════════════════════════════════════════════════════════════════════════════
// 任务状态变更事件
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 任务状态变更事件。
 *
 * 用于 [TaskRegistry] 的全局状态流，携带完整的变更上下文。
 *
 * @property taskId 任务唯一标识
 * @property taskName 任务显示名称
 * @property oldStatus 变更前的状态
 * @property newStatus 变更后的状态
 * @property timestamp 变更发生的时间戳（毫秒）
 */
data class TaskStatusEvent(
    val taskId: String,
    val taskName: String,
    val oldStatus: TaskStatus,
    val newStatus: TaskStatus,
    val timestamp: Long = System.currentTimeMillis(),
)

// ═══════════════════════════════════════════════════════════════════════════════
// AgentTask 接口
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 工作区任务的统一接口。
 *
 * 所有工作区任务（Orchestrator、Teammate、Coordinator）均实现此接口，
 * 提供统一的生命周期管理和状态变更通知。
 *
 * 实现者应确保：
 * - [execute] 在任务完成或被取消时正常返回（不抛出 [kotlinx.coroutines.CancellationException]）
 * - [kill] 能强制终止正在执行的任务（通常是取消协程作用域）
 * - 状态变更通过 [onStatusChange] 回调通知监听者
 */
interface AgentTask {
    /** 任务唯一标识（UUID 或自定义 ID） */
    val taskId: String

    /** 任务显示名称（用于 UI 展示） */
    val taskName: String

    /** 任务类型 */
    val taskType: TaskType

    /** 当前任务状态 */
    val status: TaskStatus

    /**
     * 执行任务。
     *
     * 挂起当前协程直到任务完成或被取消。实现者应在此方法中执行核心业务逻辑。
     */
    suspend fun execute()

    /**
     * 强制终止任务。
     *
     * 实现者应确保调用后 [status] 变为 [TaskStatus.CANCELLED]，
     * 并释放所有持有的资源（协程作用域、文件句柄等）。
     */
    fun kill()

    /**
     * 注册状态变更监听器。
     *
     * 当任务状态发生变更时，[listener] 会被调用。
     * 监听器在任务线程上同步调用，不应执行耗时操作。
     *
     * @param listener 状态变更回调，接收新的 [TaskStatus]
     */
    fun onStatusChange(listener: (TaskStatus) -> Unit)
}

// ═══════════════════════════════════════════════════════════════════════════════
// TaskRegistry
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 任务注册中心。
 *
 * 管理所有活跃的 [AgentTask] 实例，提供注册、查询、移除和全局状态事件流。
 * 线程安全：内部使用 [ConcurrentHashMap] 和 [MutableSharedFlow]。
 *
 * 典型用法：
 * ```kotlin
 * val registry = TaskRegistry()
 * registry.register(myTask)
 * registry.statusEvents.collect { event ->
 *     println("${event.taskName}: ${event.oldStatus} -> ${event.newStatus}")
 * }
 * ```
 */
class TaskRegistry {
    // WHY: ConcurrentHashMap 保证多线程并发注册/查询的安全性
    private val tasks = ConcurrentHashMap<String, AgentTask>()

    // WHY: 每个任务可能有多个状态监听器，需要线程安全的列表容器
    private val statusListeners = ConcurrentHashMap<String, MutableList<(TaskStatus) -> Unit>>()

    // WHY: extraBufferCapacity=64 防止高频状态变更时背压导致挂起
    private val _statusFlow = MutableSharedFlow<TaskStatusEvent>(extraBufferCapacity = 64)

    /**
     * 全局任务状态变更事件流。
     *
     * 收集此 Flow 可获取所有已注册任务的状态变更事件，
     * 适用于 UI 层的全局状态监控。
     */
    val statusEvents: Flow<TaskStatusEvent> = _statusFlow.asSharedFlow()

    /**
     * 注册任务并设置状态变更转发。
     *
     * 注册后会自动监听任务的状态变更，转发到 [statusEvents] 流。
     * 如果任务 ID 已存在，旧任务会被覆盖（不 kill 旧任务，由调用者负责）。
     *
     * @param task 要注册的任务
     */
    fun register(task: AgentTask) {
        tasks[task.taskId] = task
        statusListeners[task.taskId] = mutableListOf()

        // WHY: 拦截任务自身的 onStatusChange，同步转发到全局事件流和监听器列表
        task.onStatusChange { newStatus ->
            val event = TaskStatusEvent(
                taskId = task.taskId,
                taskName = task.taskName,
                oldStatus = task.status, // 注意：此处 oldStatus 可能已被更新，精确值由实现者维护
                newStatus = newStatus,
            )
            // 转发到全局 SharedFlow（非阻塞，dropIfBufferFull）
            _statusFlow.tryEmit(event)
            // 通知所有注册的监听器
            statusListeners[task.taskId]?.forEach { listener ->
                try {
                    listener(newStatus)
                } catch (_: Exception) {
                    // WHY: 单个监听器异常不应影响其他监听器
                }
            }
        }
    }

    /**
     * 根据任务 ID 获取任务。
     *
     * @param taskId 任务唯一标识
     * @return 对应的 [AgentTask]，不存在时返回 null
     */
    fun get(taskId: String): AgentTask? = tasks[taskId]

    /**
     * 按类型过滤任务。
     *
     * @param type 任务类型
     * @return 匹配类型的任务列表
     */
    fun getByType(type: TaskType): List<AgentTask> =
        tasks.values.filter { it.taskType == type }

    /**
     * 获取所有已注册的任务。
     *
     * @return 所有任务的列表（快照）
     */
    fun getAll(): List<AgentTask> = tasks.values.toList()

    /**
     * 移除任务及其所有监听器。
     *
     * @param taskId 要移除的任务 ID
     * @return 被移除的任务，不存在时返回 null
     */
    fun remove(taskId: String): AgentTask? {
        statusListeners.remove(taskId)
        return tasks.remove(taskId)
    }

    /**
     * 终止所有任务。
     *
     * 逐个调用每个任务的 [AgentTask.kill]，单个任务的异常不会阻止其他任务的终止。
     */
    fun killAll() {
        tasks.values.forEach { task ->
            try {
                task.kill()
            } catch (_: Exception) {
                // WHY: 单个任务 kill 失败不应阻止其他任务的终止
            }
        }
    }

    /**
     * 清空所有任务和监听器。
     *
     * 注意：此方法不 kill 任务，仅清除注册信息。调用者应先 [killAll]。
     */
    fun clear() {
        tasks.clear()
        statusListeners.clear()
    }

    /**
     * 获取已注册任务数量。
     */
    fun size(): Int = tasks.size

    /**
     * 检查任务是否已注册。
     *
     * @param taskId 任务唯一标识
     * @return 如果任务已注册返回 true
     */
    fun contains(taskId: String): Boolean = tasks.containsKey(taskId)
}
