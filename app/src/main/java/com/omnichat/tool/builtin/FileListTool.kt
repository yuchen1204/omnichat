package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
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
 * 文件列表工具。
 */
object FileListTool : BuiltinTool(
    name = "file_list",
    description = """List the contents of a directory on device storage. Returns file names, types, sizes, and last-modified timestamps. Supports recursive listing with configurable depth. Pass an empty string or "." to list /sdcard root.

**Path rules**: Relative paths resolve under /sdcard. Absolute paths accepted.""",
    group = "files",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "list files in a directory"
) {

    override val inputSchema = schema {
        prop("path", "string", "Directory path. Use \"\" or \".\" for /sdcard root. Relative paths resolve under /sdcard.")
        prop("showHidden", "boolean", "Include entries whose names start with a dot. Default false.")
        prop("recursive", "boolean", "List subdirectories recursively. Default false.")
        prop("maxDepth", "integer", "Maximum recursion depth (1-10). Default 3. Only effective when recursive=true.")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val path = arguments.optString("path")
        if (path.contains("..")) return "Path traversal not allowed"
        return null
    }

    override suspend fun checkPermissions(context: Context, arguments: JSONObject): String? {
        val path = arguments.optString("path").ifEmpty { "." }
        val file = resolvePath(context, path, FileAccessType.READ)
            ?: return "Permission denied or invalid path: $path"
        if (!file.exists()) return "Directory does not exist: $path"
        if (!file.isDirectory) return "Not a directory: $path"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val path = arguments.optString("path").ifEmpty { "." }
        val showHidden = arguments.optBoolean("showHidden", false)
        val recursive = arguments.optBoolean("recursive", false)
        val maxDepth = arguments.getIntInRange("maxDepth", 3, 1, 10)

        val dir = resolvePath(context, path, FileAccessType.READ)
            ?: return errorResponse("Permission denied or invalid path")

        if (!dir.exists()) {
            return errorResponse("Directory does not exist: $path")
        }

        if (!dir.isDirectory) {
            return errorResponse("Not a directory: $path")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun listDir(d: File, depth: Int): String {
            val entries = d.listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            return buildString {
                entries.forEach { entry ->
                    val indent = "  ".repeat(depth)
                    val type = if (entry.isDirectory) "📁" else "📄"
                    val size = if (entry.isFile) " (${entry.length()} B)" else ""
                    val modified = sdf.format(Date(entry.lastModified()))
                    appendLine("$indent$type ${entry.name}$size  [$modified]")
                    if (recursive && entry.isDirectory && depth + 1 < maxDepth) {
                        append(listDir(entry, depth + 1))
                    }
                }
            }
        }

        val listing = listDir(dir, 0)

        val text = buildString {
            appendLine("Directory: ${dir.absolutePath}")
            if (recursive) appendLine("Recursive depth: $maxDepth")
            appendLine()
            if (listing.isEmpty()) appendLine("(empty directory)")
            else append(listing.trimEnd())
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