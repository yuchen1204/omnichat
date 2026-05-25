package com.example.mcp

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.McpFilePermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

enum class PermissionResult {
    ALLOW_ONCE,
    ALLOW_ALWAYS,
    DENY,
    DONT_ASK_AGAIN
}

data class PermissionRequest(
    val path: String,
    val onResult: (PermissionResult) -> Unit
)

object McpPermissionManager {
    private const val TAG = "McpPermissionManager"

    private val _permissionRequestFlow = MutableStateFlow<PermissionRequest?>(null)
    val permissionRequestFlow: StateFlow<PermissionRequest?> = _permissionRequestFlow.asStateFlow()

    /**
     * Checks if the given path is inside the app's private sandbox.
     * If it is, no permission is needed.
     *
     * 注意：相对路径不能直接放行——mcp_filesystem.js 会把相对路径 resolve 到
     * rootDir（默认 /sdcard），实际访问的是外部存储，必须走权限弹窗。
     * 这里将相对路径视为"不在沙盒内"，让调用方触发权限请求。
     */
    private fun isPathInSandbox(context: Context, path: String): Boolean {
        try {
            val file = File(path)

            // 相对路径：无法确定实际访问位置，一律要求用户授权
            if (!file.isAbsolute) {
                return false
            }

            val absolutePath = file.canonicalPath

            val filesDir = context.filesDir.canonicalPath
            val cacheDir = context.cacheDir.canonicalPath
            val externalFilesDirs = context.getExternalFilesDirs(null).mapNotNull { it?.canonicalPath }
            val externalCacheDirs = context.externalCacheDirs.mapNotNull { it?.canonicalPath }

            // OmniChat/mcp 工作目录（脚本部署目录）也视为沙盒内，无需额外授权
            val omniChatMcp = File(android.os.Environment.getExternalStorageDirectory(), "OmniChat/mcp").canonicalPath
            val omniChatFiles = File(android.os.Environment.getExternalStorageDirectory(), "OmniChat/files").canonicalPath

            if (absolutePath.startsWith(filesDir)) return true
            if (absolutePath.startsWith(cacheDir)) return true
            if (externalFilesDirs.any { absolutePath.startsWith(it) }) return true
            if (externalCacheDirs.any { absolutePath.startsWith(it) }) return true
            if (absolutePath.startsWith(omniChatMcp)) return true
            if (absolutePath.startsWith(omniChatFiles)) return true

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving path canonical path: $path", e)
            return false // 出错时保守处理，要求授权
        }
    }

    /**
     * Suspend function to request permission from the user if the path is outside the sandbox.
     * @return true if access is allowed, false if denied.
     */
    suspend fun checkAndRequestPermission(context: Context, path: String): Boolean {
        // 先将路径规范化为绝对路径，保证与数据库中存储的路径格式一致
        val canonicalPath = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve canonical path: $path", e)
            path
        }

        if (isPathInSandbox(context, canonicalPath)) {
            return true
        }

        val db = AppDatabase.getDatabase(context)
        val dao = db.mcpFilePermissionDao()

        // 1. 精确匹配：检查该路径本身是否已有授权记录
        val exactPerm = dao.getPermissionByPath(canonicalPath)
        if (exactPerm != null) {
            return exactPerm.isAllowed
        }

        // 2. 前缀匹配：检查是否有已授权的父目录（父目录授权覆盖所有子路径）
        val parentPerm = dao.getPermissionByPathPrefix(canonicalPath)
        if (parentPerm != null) {
            return parentPerm.isAllowed
        }

        // 3. 没有任何已有授权，向用户请求
        val result = suspendCancellableCoroutine<PermissionResult> { continuation ->
            val request = PermissionRequest(canonicalPath) { res ->
                if (continuation.isActive) {
                    continuation.resume(res)
                }
                // Clear the request flow after handling
                _permissionRequestFlow.value = null
            }
            _permissionRequestFlow.value = request

            continuation.invokeOnCancellation {
                if (_permissionRequestFlow.value == request) {
                    _permissionRequestFlow.value = null
                }
            }
        }

        // 4. Handle result
        return when (result) {
            PermissionResult.ALLOW_ONCE -> true
            PermissionResult.DENY -> false
            PermissionResult.ALLOW_ALWAYS -> {
                dao.insertPermission(McpFilePermission(path = canonicalPath, isAllowed = true))
                true
            }
            PermissionResult.DONT_ASK_AGAIN -> {
                dao.insertPermission(McpFilePermission(path = canonicalPath, isAllowed = false))
                false
            }
        }
    }
}
