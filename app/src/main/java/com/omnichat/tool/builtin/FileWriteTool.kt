package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.io.File
import android.util.Base64

/**
 * 文件写入工具。
 *
 * 功能：
 * - 写入文本或二进制内容到文件
 * - 自动创建父目录
 * - 支持 UTF-8 和 Base64 编码
 */
object FileWriteTool : BuiltinTool(
    name = "file_write",
    description = """Write content to a file on device storage. Creates the file (and any missing parent directories) if it does not exist, or overwrites it if it does. Use this to save notes, generated code, configuration snippets, or any text data the user wants to persist.

**Path rules**: Relative paths (e.g. `notes/todo.txt`) resolve under /sdcard. Absolute paths (e.g. `/sdcard/Documents/file.txt`) are also accepted. A permission popup may appear for paths outside the app sandbox.""",
    group = "files",
    isReadOnly = false,
    isDestructive = true,  // 覆盖现有文件
    isConcurrencySafe = false,  // 文件写入需要串行
    searchHint = "write content to a file"
) {

    override val inputSchema = schema {
        prop("path", "string", "File path. Relative paths resolve under /sdcard. Absolute paths accepted. Parent directories are created automatically.")
        prop("content", "string", "Text content to write. The file is saved as UTF-8.")
        prop("encoding", "string", "Content encoding. \"utf8\" (default) writes the string as-is; \"base64\" decodes the string first (useful for binary files).") {
            enum("utf8", "base64")
        }
        required("path", "content")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return "Path is required"
        if (path.contains("..")) return "Path traversal not allowed"

        // content 可以为空字符串，但不能缺失
        if (!arguments.has("content")) return "Content is required"

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
        val content = arguments.optString("content")
        val encoding = arguments.getOptionalString("encoding", "utf8")

        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse("Permission denied or invalid path")

        return try {
            // 确保父目录存在
            file.parentFile?.mkdirs()

            when (encoding) {
                "base64" -> {
                    val bytes = Base64.decode(content, Base64.DEFAULT)
                    file.writeBytes(bytes)
                }
                else -> {
                    file.writeText(content, Charsets.UTF_8)
                }
            }

            successResponse("File written successfully: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            errorResponse("Failed to write file: ${e.localizedMessage}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 路径解析
    // ══════════════════════════════════════════════════════════════

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
