package com.omnichat.mcp

import android.content.Context
import android.util.Log
import com.omnichat.data.AppDatabase
import com.omnichat.data.FileAccessType
import com.omnichat.data.McpFilePermission
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
    val accessType: FileAccessType,
    val onResult: (PermissionResult) -> Unit
)

object McpPermissionManager {
    private const val TAG = "McpPermissionManager"

    private val _permissionRequestFlow = MutableStateFlow<PermissionRequest?>(null)
    val permissionRequestFlow: StateFlow<PermissionRequest?> = _permissionRequestFlow.asStateFlow()

    /**
     * Checks if the given path is inside the app's private sandbox.
     * If it is, no permission is needed.
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
            return false
        }
    }

    /**
     * 检查路径的权限。
     * @param accessType READ = 只读（查看/读取），WRITE = 读写（修改/删除/创建）
     *
     * 权限逻辑：
     * - READ 操作：只需 read 权限（write 权限隐含 read）
     * - WRITE 操作：需要 write 权限
     */
    suspend fun checkAndRequestPermission(
        context: Context,
        path: String,
        accessType: FileAccessType = FileAccessType.READ
    ): Boolean {
        // Auto Mode: bypass all permission checks for SubAgents
        val callerCtx = kotlin.coroutines.coroutineContext[com.omnichat.agent.AgentCallerContext.Key]
        if (callerCtx != null && callerCtx.agentMode == "AUTO") {
            Log.d(TAG, "Auto-approving path $path for ${callerCtx.agentType} in AUTO mode")
            return true
        }

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
        val permType = accessType.name.lowercase()

        // 1. 精确匹配
        val exactPerm = dao.getPermissionByPath(canonicalPath, permType)
        if (exactPerm != null) {
            return exactPerm.isAllowed
        }

        // 2. 前缀匹配（write 权限隐含 read，DAO 查询已处理）
        val parentPerm = dao.getPermissionByPathPrefix(canonicalPath, permType)
        if (parentPerm != null) {
            return parentPerm.isAllowed
        }

        // 3. 没有任何已有授权，向用户请求
        val result = suspendCancellableCoroutine<PermissionResult> { continuation ->
            val request = PermissionRequest(canonicalPath, accessType) { res ->
                if (continuation.isActive) {
                    continuation.resume(res)
                }
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
                dao.insertPermission(McpFilePermission(path = canonicalPath, isAllowed = true, permissionType = permType))
                true
            }
            PermissionResult.DONT_ASK_AGAIN -> {
                dao.insertPermission(McpFilePermission(path = canonicalPath, isAllowed = false, permissionType = permType))
                false
            }
        }
    }
}
