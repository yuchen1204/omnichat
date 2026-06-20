package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.io.File

/**
 * 文件删除工具。
 */
object FileDeleteTool : BuiltinTool(
    name = "file_delete",
    description = """Delete a file or an empty directory from device storage. To delete a directory and all its contents recursively, set recursive to true.

**Safety**: Deletion is permanent.""",
    group = "files",
    isReadOnly = false,
    isDestructive = true,
    isConcurrencySafe = false,
    searchHint = "delete a file or directory"
) {

    override val inputSchema = schema {
        prop("path", "string", "Path of the file or directory to delete.")
        prop("recursive", "boolean", "If true, delete the directory and all its contents recursively. Default false (only deletes empty directories or files).")
        required("path")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return "Path is required"
        if (path.contains("..")) return "Path traversal not allowed"
        return null
    }

    override suspend fun checkPermissions(context: Context, arguments: JSONObject): String? {
        val path = arguments.optString("path")
        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return "Permission denied or invalid path: $path"
        if (!file.exists()) return "Path does not exist: $path"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val path = arguments.optString("path")
        val recursive = arguments.optBoolean("recursive", false)

        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse("Permission denied or invalid path")

        if (!file.exists()) {
            return errorResponse("Path does not exist: $path")
        }

        return try {
            val success = if (recursive) deleteRecursive(file) else file.delete()
            if (success) {
                successResponse("Deleted: ${file.absolutePath}")
            } else {
                errorResponse("Failed to delete: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            errorResponse("Failed to delete: ${e.localizedMessage}")
        }
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    private suspend fun resolvePath(context: Context, path: String, accessType: FileAccessType): File? {
        if (path.contains("..")) return null

        val root = Environment.getExternalStorageDirectory()
        val file = File(path)
        val resolved = if (file.isAbsolute) {
            file.canonicalFile
        } else {
            File(root, path.ifEmpty { "." }).canonicalFile
        }

        val canonicalPath = try {
            resolved.canonicalPath
        } catch (_: Exception) {
            return null
        }

        val allowed = McpPermissionManager.checkAndRequestPermission(context, canonicalPath, accessType)
        return if (allowed) resolved else null
    }
}