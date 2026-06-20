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
 * 创建目录工具。
 */
object FileMkdirTool : BuiltinTool(
    name = "file_mkdir",
    description = """Create a directory (and any missing parent directories) on device storage. Returns an error if the path exists and is not a directory.""",
    group = "files",
    isReadOnly = false,
    isDestructive = false,
    isConcurrencySafe = false,
    searchHint = "create a directory"
) {

    override val inputSchema = schema {
        prop("path", "string", "Directory path to create.")
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
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val path = arguments.optString("path")

        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse("Permission denied or invalid path")

        return try {
            if (file.exists()) {
                if (file.isDirectory) {
                    successResponse("Directory already exists: ${file.absolutePath}")
                } else {
                    errorResponse("Path exists but is not a directory: $path")
                }
            } else if (file.mkdirs()) {
                successResponse("Directory created: ${file.absolutePath}")
            } else {
                errorResponse("Failed to create directory: $path")
            }
        } catch (e: Exception) {
            errorResponse("Failed to create directory: ${e.localizedMessage}")
        }
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