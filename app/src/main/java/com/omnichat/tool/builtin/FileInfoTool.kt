package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件信息工具。
 */
object FileInfoTool : BuiltinTool(
    name = "file_info",
    description = """Get metadata for a file or directory on device storage: absolute path, size in bytes, last-modified timestamp, MIME type guess, whether it is readable/writable, and (for directories) the number of direct children.""",
    group = "files",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "get file metadata and info"
) {

    override val inputSchema = schema {
        prop("path", "string", "File or directory path.")
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
        val file = resolvePath(context, path, FileAccessType.READ)
            ?: return "Permission denied or invalid path: $path"
        if (!file.exists()) return "File does not exist: $path"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val path = arguments.optString("path")

        val file = resolvePath(context, path, FileAccessType.READ)
            ?: return errorResponse("Permission denied or invalid path")

        if (!file.exists()) {
            return errorResponse("File does not exist: $path")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val ext = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val text = buildString {
            appendLine("Path:           ${file.absolutePath}")
            appendLine("Type:           ${if (file.isDirectory) "Directory" else "File"}")
            if (file.isFile) {
                appendLine("Size:           ${file.length()} bytes (${file.length() / 1024.0} KB)")
                appendLine("MIME type:      $mimeType")
            } else {
                val childCount = file.listFiles()?.size ?: 0
                appendLine("Children:       $childCount")
            }
            appendLine("Last modified:  ${sdf.format(Date(file.lastModified()))}")
            appendLine("Readable:       ${if (file.canRead()) "Yes" else "No"}")
            appendLine("Writable:       ${if (file.canWrite()) "Yes" else "No"}")
        }

        return successResponse(text)
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