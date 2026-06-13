package com.omnichat.hooks

import android.content.Context
import android.util.Log
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import org.json.JSONObject

class McpFilePermissionHook(private val context: Context) : McpHook {
    /** 只读操作：查看/读取 */
    private val readTools = setOf(
        "read_file", "list_directory", "get_file_info", "search_files",
        "search_content", "get_working_directory"
    )

    /** 写操作：修改/删除/创建 */
    private val writeTools = setOf(
        "write_file", "append_file", "delete_file", "move_file",
        "copy_file", "create_directory"
    )

    override suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
        // Auto Mode: bypass all permission hooks for SubAgents
        val callerCtx = kotlin.coroutines.coroutineContext[com.omnichat.agent.AgentCallerContext.Key]
        if (callerCtx != null && callerCtx.agentMode == "AUTO") {
            Log.d("McpFilePermissionHook", "Bypassing permission check for ${callerCtx.agentType} in AUTO mode")
            return args
        }

        val accessType = when (toolName) {
            in readTools -> FileAccessType.READ
            in writeTools -> FileAccessType.WRITE
            else -> return args // 不是文件工具，直接放行
        }

        val pathsToCheck = mutableListOf<String>()
        for (key in listOf("path", "source", "destination", "sourcePath", "destinationPath", "directory")) {
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
