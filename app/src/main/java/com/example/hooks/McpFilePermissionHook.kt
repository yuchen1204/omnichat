package com.example.hooks

import android.content.Context
import android.util.Log
import com.example.mcp.McpPermissionManager
import org.json.JSONObject

class McpFilePermissionHook(private val context: Context) : McpHook {
    private val fileTools = setOf(
        "read_file", "write_file", "list_directory",
        "create_directory", "delete_file", "move_file", "get_file_info", "search_files",
        "append_file", "copy_file", "search_content", "get_working_directory"
    )

    override suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
        if (toolName !in fileTools) {
            return args
        }

        // 提取所有可能包含路径的参数（内置工具和外部工具的参数名不同）
        val pathsToCheck = mutableListOf<String>()
        for (key in listOf("path", "source", "destination", "sourcePath", "destinationPath", "directory")) {
            if (args.has(key)) {
                val v = args.optString(key)
                if (v.isNotEmpty()) pathsToCheck.add(v)
            }
        }

        for (path in pathsToCheck) {
            val isAllowed = McpPermissionManager.checkAndRequestPermission(context, path)
            if (!isAllowed) {
                Log.w("McpFilePermissionHook", "Access denied to path: $path")
                // Return null to block execution
                return null
            }
        }

        return args
    }

    override suspend fun onAfterToolExecute(toolName: String, result: String): String {
        return result
    }
}
