package com.omnichat.agent

import java.util.UUID

/**
 * SubAgent 文件操作审核请求。
 */
data class SubAgentApprovalRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val taskDescription: String,
    val agentType: String,
    val toolName: String,
    val operationType: OperationType,
    val originalPaths: List<PathInfo>,
    val correctedPaths: List<PathInfo>?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 路径信息。
 */
data class PathInfo(
    val original: String,
    val corrected: String?,  // null 表示无需纠正
    val paramKey: String     // "path", "sourcePath", "destinationPath", "directory"
)

/**
 * 操作类型枚举。
 */
enum class OperationType {
    CREATE,   // file_write, file_mkdir, file_copy
    READ,     // file_read, file_list, file_info, file_search
    UPDATE,   // file_append, file_move
    DELETE    // file_delete
}

/**
 * 审核结果密封类。
 */
sealed class ApprovalResult {
    data class Approved(val message: String? = null) : ApprovalResult()
    data class Rejected(val reason: String) : ApprovalResult()
    object Timeout : ApprovalResult()
}
