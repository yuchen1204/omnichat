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
     */
    private fun isPathInSandbox(context: Context, path: String): Boolean {
        try {
            val file = File(path)
            
            // If the path is not absolute, it is treated as relative to the tool's sandbox root.
            // As long as it doesn't contain "..", it is safely inside the sandbox.
            if (!file.isAbsolute) {
                if (path.contains("..")) {
                    return false // Potential escape attempt
                }
                return true
            }

            val absolutePath = file.canonicalPath

            val filesDir = context.filesDir.canonicalPath
            val cacheDir = context.cacheDir.canonicalPath
            val externalFilesDirs = context.getExternalFilesDirs(null).mapNotNull { it?.canonicalPath }
            val externalCacheDirs = context.externalCacheDirs.mapNotNull { it?.canonicalPath }
            
            // Add OmniChat/files to sandbox whitelist
            val omniChatFiles = File(android.os.Environment.getExternalStorageDirectory(), "OmniChat/files").canonicalPath

            if (absolutePath.startsWith(filesDir)) return true
            if (absolutePath.startsWith(cacheDir)) return true
            if (externalFilesDirs.any { absolutePath.startsWith(it) }) return true
            if (externalCacheDirs.any { absolutePath.startsWith(it) }) return true
            if (absolutePath.startsWith(omniChatFiles)) return true

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving path canonical path: $path", e)
            return false // Assume outside if error
        }
    }

    /**
     * Suspend function to request permission from the user if the path is outside the sandbox.
     * @return true if access is allowed, false if denied.
     */
    suspend fun checkAndRequestPermission(context: Context, path: String): Boolean {
        if (isPathInSandbox(context, path)) {
            return true
        }

        val db = AppDatabase.getDatabase(context)
        val dao = db.mcpFilePermissionDao()

        // 1. Check existing permission
        val existingPerm = dao.getPermissionByPath(path)
        if (existingPerm != null) {
            return existingPerm.isAllowed
        }

        // 2. Request from UI
        val result = suspendCancellableCoroutine<PermissionResult> { continuation ->
            val request = PermissionRequest(path) { res ->
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

        // 3. Handle result
        return when (result) {
            PermissionResult.ALLOW_ONCE -> true
            PermissionResult.DENY -> false
            PermissionResult.ALLOW_ALWAYS -> {
                dao.insertPermission(McpFilePermission(path = path, isAllowed = true))
                true
            }
            PermissionResult.DONT_ASK_AGAIN -> {
                dao.insertPermission(McpFilePermission(path = path, isAllowed = false))
                false
            }
        }
    }
}
