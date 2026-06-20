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
 * 文件复制工具。
 */
object FileCopyTool : BuiltinTool(
    name = "file_copy",
    description = """Copy a file or directory on device storage. Directories are copied recursively. The destination parent directory is created automatically if it does not exist.""",
    group = "files",
    isReadOnly = false,
    isDestructive = false,
    isConcurrencySafe = false,
    searchHint = "copy a file or directory"
) {

    override val inputSchema = schema {
        prop("sourcePath", "string", "Source path.")
        prop("destinationPath", "string", "Destination path.")
        prop("overwrite", "boolean", "If true, overwrite the destination if it already exists. Default false.")
        required("sourcePath", "destinationPath")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val srcPath = arguments.optString("sourcePath").trim()
        val dstPath = arguments.optString("destinationPath").trim()
        if (srcPath.isEmpty()) return "Source path is required"
        if (dstPath.isEmpty()) return "Destination path is required"
        if (srcPath.contains("..") || dstPath.contains("..")) return "Path traversal not allowed"
        return null
    }

    override suspend fun checkPermissions(context: Context, arguments: JSONObject): String? {
        val srcPath = arguments.optString("sourcePath")
        val dstPath = arguments.optString("destinationPath")

        val srcFile = resolvePath(context, srcPath, FileAccessType.READ)
            ?: return "Permission denied for source path: $srcPath"
        if (!srcFile.exists()) return "Source does not exist: $srcPath"

        val dstFile = resolvePath(context, dstPath, FileAccessType.WRITE)
            ?: return "Permission denied for destination path: $dstPath"

        val overwrite = arguments.optBoolean("overwrite", false)
        if (dstFile.exists() && !overwrite) return "Destination already exists: $dstPath"

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val srcPath = arguments.optString("sourcePath")
        val dstPath = arguments.optString("destinationPath")
        val overwrite = arguments.optBoolean("overwrite", false)

        val src = resolvePath(context, srcPath, FileAccessType.READ)
            ?: return errorResponse("Permission denied for source path")
        val dst = resolvePath(context, dstPath, FileAccessType.WRITE)
            ?: return errorResponse("Permission denied for destination path")

        if (!src.exists()) {
            return errorResponse("Source does not exist: $srcPath")
        }

        if (dst.exists() && !overwrite) {
            return errorResponse("Destination already exists: $dstPath (use overwrite=true to replace)")
        }

        return try {
            dst.parentFile?.mkdirs()

            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = overwrite)
            } else {
                src.copyTo(dst, overwrite = overwrite)
            }

            successResponse("Copied: ${src.absolutePath} → ${dst.absolutePath}")
        } catch (e: Exception) {
            errorResponse("Failed to copy: ${e.localizedMessage}")
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