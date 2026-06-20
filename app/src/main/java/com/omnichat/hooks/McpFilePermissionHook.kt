package com.omnichat.hooks

import android.content.Context
import android.util.Log
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import org.json.JSONObject

class McpFilePermissionHook(private val context: Context) : McpHook {
    /** 只读操作：查看/读取 */
    private val readTools = setOf(
        "file_read", "file_list", "file_info", "file_search"
    )

    /** 写操作：修改/删除/创建 */
    private val writeTools = setOf(
        "file_write", "file_append", "file_delete", "file_move",
        "file_copy", "file_mkdir"
    )

    override suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
        // 检测是否是 SubAgent 上下文
        val subAgentContext = com.omnichat.agent.SubAgent.getCurrentContext()
        if (subAgentContext != null) {
            // SubAgent 的文件操作已由 SubAgentPathRestrictionHook 处理，直接放行
            return args
        }

        // MainAgent 的文件操作，继续原有逻辑
        val accessType = when (toolName) {
            in readTools -> FileAccessType.READ
            in writeTools -> FileAccessType.WRITE
            else -> return args // 不是文件工具，直接放行
        }

        val pathsToCheck = mutableListOf<String>()
        for (key in listOf("path", "sourcePath", "destinationPath", "directory")) {
            if (args.has(key)) {
                val v = args.optString(key)
                if (v.isNotEmpty()) pathsToCheck.add(v)
            }
        }

        for (path in pathsToCheck) {
            val isAllowed = McpPermissionManager.checkAndRequestPermission(context, path, accessType)
            if (!isAllowed) {
                Log.w("McpFilePermissionHook", "Access denied to path: $path (type=$accessType)")
                return null
            }
        }

        return args
    }

    override suspend fun onAfterToolExecute(toolName: String, result: String): String {
        return result
    }
}
