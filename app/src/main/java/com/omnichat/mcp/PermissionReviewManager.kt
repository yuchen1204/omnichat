package com.omnichat.mcp

import android.util.Log
import com.omnichat.data.FileAccessType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 AgentMode 下 SubAgent 的权限审核请求。
 *
 * 当 AgentMode 开启时，SubAgent 的破坏性工具操作不直接弹窗询问用户，
 * 而是通过此管理器发送给 MainAgent（LLM）审核。
 *
 * 流程：
 * 1. 调用 [requestReview] 挂起 SubAgent 协程，同时发射审核事件
 * 3. ChatViewModel 观察事件，插入提示消息让 LLM 审核
 * 4. LLM 决定 APPROVE 或 DENY，调用 [resolveReview]
 * 5. SubAgent 协程恢复，收到审核结果
 */
object PermissionReviewManager {

    private const val TAG = "PermissionReviewManager"

    data class ReviewRequest(
        val requestId: String,
        val toolName: String,
        val path: String,
        val accessType: FileAccessType,
        val taskContext: String?,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingReviews = MutableSharedFlow<ReviewRequest>(
        extraBufferCapacity = 8
    )
    val pendingReviews: SharedFlow<ReviewRequest> = _pendingReviews.asSharedFlow()

    /** 挂起 SubAgent 协程，等待 MainAgent 审核结果 */
    suspend fun requestReview(
        toolName: String,
        path: String,
        accessType: FileAccessType,
        taskContext: String?
    ): Boolean {
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()

        val request = ReviewRequest(
            requestId = requestId,
            toolName = toolName,
            path = path,
            accessType = accessType,
            taskContext = taskContext,
            deferred = deferred
        )

        Log.i(TAG, "[requestReview] id=$requestId, tool=$toolName, path=$path, type=$accessType")
        _pendingReviews.emit(request)

        // 挂起直到 MainAgent 审核完成
        return try {
            deferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "[requestReview] 等待审核结果异常", e)
            false // 异常时默认拒绝
        }
    }

    /** MainAgent 审核完成，恢复 SubAgent 协程 */
    fun resolveReview(requestId: String, approved: Boolean): Boolean {
        // 从 pending 中查找（可能已被消费）
        val pending = activeRequests.remove(requestId)
        if (pending != null) {
            pending.deferred.complete(approved)
            Log.i(TAG, "[resolveReview] id=$requestId, approved=$approved")
            return true
        }
        Log.w(TAG, "[resolveReview] id=$requestId 未找到（可能已超时）")
        return false
    }

    /** 活跃的审核请求，供 resolveReview 查找 */
    private val activeRequests = ConcurrentHashMap<String, ReviewRequest>()

    fun registerActiveRequest(request: ReviewRequest) {
        activeRequests[request.requestId] = request
    }
}
