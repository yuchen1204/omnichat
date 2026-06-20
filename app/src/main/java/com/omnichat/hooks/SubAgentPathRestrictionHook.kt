package com.omnichat.hooks

import android.content.Context
import android.util.Log
import com.omnichat.agent.ApprovalResult
import com.omnichat.agent.OperationType
import com.omnichat.agent.PathInfo
import com.omnichat.agent.SubAgent
import com.omnichat.agent.SubAgentApprovalManager
import com.omnichat.agent.SubAgentApprovalRequest
import org.json.JSONObject
import java.io.File

/**
 * SubAgent 文件路径限制 Hook。
 *
 * 当 SubAgent 执行文件操作时：
 * 1. 检测是否是 SubAgent 上下文
 * 2. 检查并纠正路径到允许目录
 * 3. 调用 MainAgent 审核
 * 4. 根据审核结果决定是否放行
 */
class SubAgentPathRestrictionHook : McpHook {

    companion object {
        private const val TAG = "SubAgentPathHook"

        /** 允许的工作目录 */
        private const val ALLOWED_BASE_PATH = "/sdcard/omnichat"

        /** 需要路径检查的文件工具 */
        private val pathTools = setOf(
            "file_read", "file_write", "file_append", "file_delete",
            "file_move", "file_copy", "file_mkdir", "file_list",
            "file_info", "file_search"
        )

        /** 操作类型映射 */
        private val operationTypeMap = mapOf(
            "file_write" to OperationType.CREATE,
            "file_mkdir" to OperationType.CREATE,
            "file_copy" to OperationType.CREATE,
            "file_read" to OperationType.READ,
            "file_list" to OperationType.READ,
            "file_info" to OperationType.READ,
            "file_search" to OperationType.READ,
            "file_append" to OperationType.UPDATE,
            "file_move" to OperationType.UPDATE,
            "file_delete" to OperationType.DELETE
        )

        /** 包含路径参数的 key */
        private val pathKeys = listOf("path", "sourcePath", "destinationPath", "directory")
    }

    /** Context 用于调用 MainAgent API */
    private var context: Context? = null

    /**
     * 初始化 Hook，注入 Context。
     * 必须在注册前调用。
     */
    fun initialize(context: Context) {
        this.context = context
        Log.d(TAG, "SubAgentPathRestrictionHook initialized")
    }

    override suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
        // 只处理 SubAgent 上下文
        val subAgentContext = SubAgent.getCurrentContext()
        if (subAgentContext == null) {
            // MainAgent 上下文，不做限制
            return args
        }

        // 只处理文件工具
        if (toolName !in pathTools) {
            return args
        }

        // 确保 Context 已初始化
        val ctx = context
        if (ctx == null) {
            Log.e(TAG, "[${subAgentContext.taskId}] Context not initialized, rejecting")
            return null
        }

        // 1. 收集并纠正路径
        val pathInfos = collectAndCorrectPaths(args, toolName)
        val anyCorrected = pathInfos.any { it.corrected != null }

        if (anyCorrected) {
            Log.d(TAG, "[${subAgentContext.taskId}][$toolName] Paths corrected")
        }

        // 2. 构建审核请求
        val request = SubAgentApprovalRequest(
            taskId = subAgentContext.taskId,
            taskDescription = subAgentContext.taskDescription,
            agentType = subAgentContext.agentType,
            toolName = toolName,
            operationType = operationTypeMap[toolName] ?: OperationType.READ,
            originalPaths = pathInfos.map { it.copy(corrected = null) },
            correctedPaths = if (anyCorrected) pathInfos else null
        )

        // 3. 请求审核（阻塞等待）
        Log.d(TAG, "[${subAgentContext.taskId}][$toolName] Requesting approval from MainAgent")
        val result = SubAgentApprovalManager.requestApproval(ctx, request)

        return when (result) {
            is ApprovalResult.Approved -> {
                Log.d(TAG, "[${subAgentContext.taskId}][$toolName] Approved: ${result.message}")
                // 返回纠正后的 args
                buildCorrectedArgs(args, pathInfos)
            }
            is ApprovalResult.Rejected -> {
                Log.w(TAG, "[${subAgentContext.taskId}][$toolName] Rejected: ${result.reason}")
                null  // 阻止执行
            }
            is ApprovalResult.Timeout -> {
                Log.w(TAG, "[${subAgentContext.taskId}][$toolName] Approval timeout")
                null  // 阻止执行
            }
        }
    }

    override suspend fun onAfterToolExecute(toolName: String, result: String): String {
        return result
    }

    /**
     * 收集并纠正路径参数。
     */
    private fun collectAndCorrectPaths(args: JSONObject, toolName: String): List<PathInfo> {
        val pathInfos = mutableListOf<PathInfo>()

        for (key in pathKeys) {
            if (args.has(key)) {
                val originalPath = args.optString(key)
                if (originalPath.isNotEmpty()) {
                    val correctedPath = correctPath(originalPath, toolName)
                    pathInfos.add(PathInfo(
                        original = originalPath,
                        corrected = if (correctedPath != originalPath) correctedPath else null,
                        paramKey = key
                    ))
                }
            }
        }

        return pathInfos
    }

    /**
     * 构建纠正后的 args。
     */
    private fun buildCorrectedArgs(args: JSONObject, pathInfos: List<PathInfo>): JSONObject {
        val correctedArgs = JSONObject(args.toString())

        for (pathInfo in pathInfos) {
            val corrected = pathInfo.corrected ?: pathInfo.original
            correctedArgs.put(pathInfo.paramKey, corrected)
        }

        return correctedArgs
    }

    /**
     * 纠正路径到允许的目录。
     * 只计算纠正后的路径，不创建目录。
     */
    private fun correctPath(originalPath: String, toolName: String): String {
        val file = File(originalPath)
        val canonicalPath = try {
            file.canonicalPath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get canonical path: $originalPath", e)
            originalPath
        }

        // 检查是否已在允许目录内
        if (canonicalPath.startsWith(ALLOWED_BASE_PATH)) {
            return originalPath
        }

        // 构建纠正后的路径（不创建目录，由工具执行时创建）
        val baseDir = File(ALLOWED_BASE_PATH)

        // 对于绝对路径，尝试保留部分目录结构
        return if (file.isAbsolute) {
            val relativePart = canonicalPath
                .removePrefix("/sdcard/")
                .removePrefix("/storage/emulated/0/")

            File(baseDir, relativePart).path
        } else {
            File(baseDir, originalPath).path
        }
    }
}