package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.io.File

/**
 * 文件读取工具。
 *
 * 功能：
 * - 读取文件内容（文本或 Base64）
 * - 支持字节限制和行范围读取
 * - 自动权限检查
 */
object FileReadTool : BuiltinTool(
    name = "file_read",
    description = """Read the content of a file from device storage. Returns the file content as a UTF-8 string (or Base64 if encoding is "base64"). Supports byte-based truncation and line-range reading.

**Path rules**: Relative paths resolve under /sdcard. Absolute paths accepted. A permission popup may appear for paths outside the app sandbox.""",
    group = "files",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "read file contents from storage"
) {

    override val inputSchema = schema {
        prop("path", "string", "File path. Relative paths resolve under /sdcard.")
        prop("encoding", "string", "\"utf8\" (default) returns plain string; \"base64\" returns Base64-encoded bytes.") {
            enum("utf8", "base64")
        }
        prop("maxBytes", "integer", "Maximum bytes to read. Default: 1 MB. Max: 10 MB.")
        prop("startLine", "integer", "Start line number (1-based). When provided, reads by line range.")
        prop("endLine", "integer", "End line number (1-based). If omitted, reads to end of file.")
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
        if (!file.exists()) return "File not found: $path"
        if (!file.isFile) return "Not a file: $path"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val path = arguments.optString("path")
        val encoding = arguments.getOptionalString("encoding", "utf8")
        val maxBytes = arguments.getIntInRange("maxBytes", 1024 * 1024, 1, 10 * 1024 * 1024)
        val startLine = arguments.getIntInRange("startLine", 0, 0, Int.MAX_VALUE)
        val endLine = arguments.getIntInRange("endLine", 0, 0, Int.MAX_VALUE)

        val file = resolvePath(context, path, FileAccessType.READ)
            ?: return errorResponse("Permission denied or invalid path")

        return try {
            when {
                encoding == "base64" -> readAsBase64(file, maxBytes)
                startLine > 0 -> readByLines(file, startLine, endLine, maxBytes)
                else -> readAsText(file, maxBytes)
            }
        } catch (e: Exception) {
            errorResponse("Failed to read file: ${e.localizedMessage}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 读取方法
    // ══════════════════════════════════════════════════════════════

    private fun readAsBase64(file: File, maxBytes: Int): JSONObject {
        val bytes = file.inputStream().use { stream ->
            val buf = ByteArray(maxBytes)
            val read = stream.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }

        val truncated = file.length() > maxBytes
        val resultText = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val suffix = if (truncated) "\n\n[Truncated: read $maxBytes of ${file.length()} bytes]" else ""

        return successResponse(resultText + suffix)
    }

    private fun readByLines(file: File, startLine: Int, endLine: Int, maxBytes: Int): JSONObject {
        val lines = file.readLines(Charsets.UTF_8)
        val from = (startLine - 1).coerceIn(0, lines.size)
        val to = if (endLine > 0) endLine.coerceIn(from, lines.size) else lines.size
        val selected = lines.subList(from, to)
        val text = selected.joinToString("\n")

        val byteSize = text.toByteArray(Charsets.UTF_8).size
        return if (byteSize > maxBytes) {
            successResponse(text.take(maxBytes) + "\n\n[Content truncated at $maxBytes bytes]")
        } else {
            successResponse(text)
        }
    }

    private fun readAsText(file: File, maxBytes: Int): JSONObject {
        val bytes = file.inputStream().use { stream ->
            val buf = ByteArray(maxBytes)
            val read = stream.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }

        val truncated = file.length() > maxBytes
        val resultText = String(bytes, Charsets.UTF_8)
        val suffix = if (truncated) "\n\n[Truncated: read $maxBytes of ${file.length()} bytes]" else ""

        return successResponse(resultText + suffix)
    }

    // ══════════════════════════════════════════════════════════════
    // 路径解析（复用现有逻辑）
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
