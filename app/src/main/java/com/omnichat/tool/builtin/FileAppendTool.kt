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
 * 文件追加工具。
 *
 * 功能：
 * - 向现有文件追加内容
 * - 自动插入换行符（如果文件末尾没有）
 */
object FileAppendTool : BuiltinTool(
    name = "file_append",
    description = """Append text to the end of an existing file on device storage. If the file does not exist it is created. A newline is automatically inserted before the appended content when the file already has content and does not end with a newline.

**Path rules**: Relative paths resolve under /sdcard. Absolute paths accepted.""",
    group = "files",
    isReadOnly = false,
    isDestructive = false,
    isConcurrencySafe = false,
    searchHint = "append text to a file"
) {

    override val inputSchema = schema {
        prop("path", "string", "File path. Relative paths resolve under /sdcard.")
        prop("content", "string", "Text to append. Saved as UTF-8.")
        required("path", "content")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return "Path is required"
        if (path.contains("..")) return "Path traversal not allowed"
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

        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse("Permission denied or invalid path")

        return try {
            file.parentFile?.mkdirs()

            // 检查文件是否需要前置换行符
            val needsNewline = if (file.exists() && file.length() > 0) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(maxOf(0, file.length() - 4))
                    val tail = ByteArray(minOf(4, file.length().toInt()))
                    raf.readFully(tail)
                    !String(tail, Charsets.UTF_8).endsWith("\n")
                }
            } else false

            file.appendText(if (needsNewline) "\n$content" else content, Charsets.UTF_8)

            successResponse("Content appended to: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            errorResponse("Failed to append to file: ${e.localizedMessage}")
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