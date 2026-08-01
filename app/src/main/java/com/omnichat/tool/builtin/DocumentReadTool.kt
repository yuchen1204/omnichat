package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import com.omnichat.util.JsDocumentReader
import org.json.JSONObject
import java.io.File

/**
 * 文档阅读工具。
 *
 * 功能：
 * - 读取 PDF/DOCX 文件内容，返回提取的正文和表格文本
 * - 使用嵌入式 JavaScript 运行时（QuickJS）和内置插件进行解析
 * - 自动权限检查
 */
object DocumentReadTool : BuiltinTool(
    name = "document_read",
    description = """Read a PDF or DOCX document and return its extracted text content. Supports tables, headings, and page markers.

**Path rules**: Relative paths resolve under /sdcard. Absolute paths accepted. A permission popup may appear for paths outside the app sandbox.

**Supported formats**: PDF (.pdf), DOCX (.docx)
**Size limit**: 4 MB input, 4 MB output""",
    group = "documents",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "read pdf docx document content from storage"
) {

    override val inputSchema = schema {
        prop("path", "string", "File path to the PDF or DOCX document. Relative paths resolve under /sdcard.")
        required("path")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return "Path is required"
        if (path.contains("..")) return "Path traversal not allowed"
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("pdf", "docx")) return "Unsupported format: .$ext. Only PDF and DOCX are supported."
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
        val path = arguments.getRequiredString("path").getOrElse {
            return errorResponse(it.message ?: "Path is required")
        }

        val file = resolvePath(context, path, FileAccessType.READ)
            ?: return errorResponse("Permission denied or invalid path: $path")

        return try {
            val reader = JsDocumentReader(context)
            val result = reader.parse(file)
            val summary = buildString {
                appendLine("<document name=\"${file.name}\">")
                append(result.text)
                if (!result.text.endsWith("\n")) appendLine()
                append("</document>")
                if (result.warnings.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("Warnings:")
                    result.warnings.forEach { appendLine("  - $it") }
                }
            }
            successResponse(summary)
        } catch (e: com.omnichat.util.DocumentParseException) {
            errorResponse("Failed to parse document: ${e.category} - ${e.localizedMessage}")
        } catch (e: Exception) {
            errorResponse("Failed to read document: ${e.localizedMessage}")
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