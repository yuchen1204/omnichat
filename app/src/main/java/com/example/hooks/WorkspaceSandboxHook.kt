package com.example.hooks

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 工作区文件操作沙盒 Hook。
 *
 * 拦截所有文件工具调用，拒绝访问沙盒目录之外的路径。
 * 仅在工作区设置了 [sandboxPath] 时生效。
 *
 * @property sandboxPath 用户授权的沙盒目录绝对路径
 */
class WorkspaceSandboxHook(val sandboxPath: String) : McpHook {
    companion object {
        private const val TAG = "WorkspaceSandboxHook"
        private val FILE_TOOLS = setOf(
            "read_file", "write_file", "file_read", "file_write", "file_append", "file_delete",
            "file_list", "file_search", "file_info", "file_move", "list_directory",
            "create_directory", "delete_file", "move_file", "get_file_info", "search_files",
            "append_file", "copy_file", "search_content", "get_working_directory"
        )
        private val PATH_KEYS = listOf(
            "path", "source", "destination", "sourcePath", "destinationPath", "directory"
        )
    }

    /** 规范化后的沙盒路径（不带尾部分隔符） */
    private val normalizedSandbox: String = try {
        val f = File(sandboxPath)
        val canonical = f.canonicalPath
        if (canonical.endsWith("/")) canonical.dropLast(1) else canonical
    } catch (e: Exception) {
        Log.w(TAG, "Failed to resolve sandbox path canonical: $sandboxPath", e)
        sandboxPath.trimEnd('/')
    }

    override suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
        if (toolName !in FILE_TOOLS) return args

        for (key in PATH_KEYS) {
            if (!args.has(key)) continue
            val rawPath = args.optString(key)
            if (rawPath.isEmpty()) continue

            if (!isPathInsideSandbox(rawPath)) {
                Log.w(TAG, "Blocked: '$rawPath' is outside sandbox '$normalizedSandbox'")
                return null
            }
        }
        return args
    }

    override suspend fun onAfterToolExecute(toolName: String, result: String): String = result

    private fun isPathInsideSandbox(path: String): Boolean {
        try {
            val file = File(path)
            // 相对路径：mcp_filesystem.js 会 resolve 到 rootDir（/sdcard），
            // 我们无法在此处确定最终路径，保守放行（由 McpFilePermissionHook 兜底）
            if (!file.isAbsolute) return true

            val canonical = file.canonicalPath

            // 直接匹配
            if (canonical == normalizedSandbox || canonical.startsWith("$normalizedSandbox/")) {
                return true
            }

            // MCP 文件系统 rootDir 为 /sdcard，等价于 /storage/emulated/0。
            // Agent 传入的绝对路径 /Download/... 会被 MCP 解析为 /sdcard/Download/...，
            // 但 Hook 层拿到的是原始路径，需要尝试两种前缀匹配。
            val altPrefixes = listOf("/sdcard", "/storage/emulated/0")
            for (prefix in altPrefixes) {
                val resolved = prefix + canonical
                if (resolved == normalizedSandbox || resolved.startsWith("$normalizedSandbox/")) {
                    return true
                }
            }
            // 反向：sandbox 用 /sdcard/... 而路径用 /storage/emulated/0/...（或反过来）
            for (prefix in altPrefixes) {
                val sandboxWithPrefix = if (normalizedSandbox.startsWith(prefix)) {
                    normalizedSandbox
                } else {
                    prefix + normalizedSandbox
                }
                if (canonical == sandboxWithPrefix || canonical.startsWith("$sandboxWithPrefix/")) {
                    return true
                }
            }

            Log.w(TAG, "Blocked: '$path' (canonical: '$canonical') is outside sandbox '$normalizedSandbox'")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve path: $path", e)
            return false
        }
    }
}
