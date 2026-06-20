package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件搜索工具。
 */
object FileSearchTool : BuiltinTool(
    name = "file_search",
    description = """Search for files by name pattern or by text/regex content within device storage. Supports glob-style name matching and full-text or regex content search with context lines.""",
    group = "files",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "search files by name or content"
) {

    override val inputSchema = schema {
        prop("namePattern", "string", "Optional. Glob-style filename pattern, e.g. \"*.txt\", \"report_*\". Matches against the file name only.")
        prop("contentQuery", "string", "Optional. Search string or regex to look for inside file contents. Case-insensitive by default.")
        prop("directory", "string", "Optional. Directory to restrict the search to. Defaults to /sdcard root (recursive).")
        prop("maxResults", "integer", "Maximum number of results to return. Default 20, max 100.")
        prop("isRegex", "boolean", "Treat contentQuery as a regular expression. Default false.")
        prop("contextLines", "integer", "Number of lines to show before and after each match. Default 0, max 10.")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val namePattern = arguments.optString("namePattern").trim()
        val contentQuery = arguments.optString("contentQuery").trim()
        val directory = arguments.optString("directory")

        if (namePattern.isEmpty() && contentQuery.isEmpty()) {
            return "At least one search criterion (namePattern or contentQuery) is required"
        }
        if (directory.contains("..")) return "Path traversal not allowed"

        // 验证正则表达式
        val isRegex = arguments.optBoolean("isRegex", false)
        if (isRegex && contentQuery.isNotEmpty()) {
            try {
                Regex(contentQuery, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return "Invalid regex: ${e.localizedMessage}"
            }
        }

        return null
    }

    override suspend fun checkPermissions(context: Context, arguments: JSONObject): String? {
        val directory = arguments.optString("directory").ifEmpty { "." }
        val file = resolvePath(context, directory, FileAccessType.READ)
            ?: return "Permission denied for directory: $directory"
        if (!file.exists()) return "Directory does not exist: $directory"
        if (!file.isDirectory) return "Not a directory: $directory"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val namePattern = arguments.optString("namePattern").trim().ifEmpty { null }
        val contentQuery = arguments.optString("contentQuery").trim().ifEmpty { null }
        val directory = arguments.optString("directory").ifEmpty { "." }
        val maxResults = arguments.getIntInRange("maxResults", 20, 1, 100)
        val isRegex = arguments.optBoolean("isRegex", false)
        val contextLines = arguments.getIntInRange("contextLines", 0, 0, 10)

        val searchRoot = resolvePath(context, directory, FileAccessType.READ)
            ?: return errorResponse("Permission denied for directory")

        if (!searchRoot.exists()) {
            return errorResponse("Directory does not exist: $directory")
        }

        val contentRegex = if (contentQuery != null && isRegex) {
            Regex(contentQuery, RegexOption.IGNORE_CASE)
        } else null

        val results = mutableListOf<JSONObject>()
        searchFiles(searchRoot, namePattern, contentQuery, contentRegex, contextLines, results, maxResults)

        val text = buildString {
            appendLine("Search scope: ${searchRoot.absolutePath}")
            if (namePattern != null) appendLine("Name pattern: $namePattern")
            if (contentQuery != null) appendLine("Content query: $contentQuery${if (isRegex) " (regex)" else ""}")
            appendLine("Results: ${results.size}${if (results.size >= maxResults) " (limit reached: $maxResults)" else ""}")
            appendLine()

            results.forEach { r ->
                val absPath = r.optString("path")
                append("• $absPath")

                val matchLines = r.optJSONArray("matchLines")
                if (matchLines != null && matchLines.length() > 0) {
                    val lines = (0 until matchLines.length()).map { matchLines.getInt(it) }
                    append("  [matches on lines: ${lines.joinToString(", ")}]")
                }

                val contextSnippets = r.optJSONArray("contextSnippets")
                if (contextSnippets != null && contextSnippets.length() > 0) {
                    appendLine()
                    for (i in 0 until contextSnippets.length()) {
                        appendLine("    ${contextSnippets.getString(i)}")
                    }
                } else {
                    appendLine()
                }
            }
        }

        return successResponse(text)
    }

    private fun searchFiles(
        dir: File,
        namePattern: String?,
        contentQuery: String?,
        contentRegex: Regex?,
        contextLines: Int,
        results: MutableList<JSONObject>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return

        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (results.size >= maxResults) break

            if (entry.isDirectory) {
                searchFiles(entry, namePattern, contentQuery, contentRegex, contextLines, results, maxResults)
            } else {
                val nameMatch = namePattern == null || matchesGlob(entry.name, namePattern)
                if (!nameMatch) continue

                if (contentQuery != null || contentRegex != null) {
                    // 只搜索文本文件（< 2MB）
                    if (entry.length() > 2 * 1024 * 1024) continue

                    val text = try { entry.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
                    val lines = text.lines()

                    val matchedIndices = if (contentRegex != null) {
                        lines.mapIndexedNotNull { idx, line ->
                            if (contentRegex.containsMatchIn(line)) idx else null
                        }
                    } else {
                        lines.mapIndexedNotNull { idx, line ->
                            if (line.contains(contentQuery!!, ignoreCase = true)) idx else null
                        }
                    }

                    if (matchedIndices.isEmpty()) continue

                    val matchLines = matchedIndices.take(3).map { it + 1 }
                    val result = JSONObject().apply {
                        put("path", entry.path)
                        put("matchLines", JSONArray(matchLines))
                    }

                    if (contextLines > 0) {
                        val snippets = JSONArray()
                        for (matchIdx in matchedIndices.take(3)) {
                            val from = (matchIdx - contextLines).coerceAtLeast(0)
                            val to = (matchIdx + contextLines + 1).coerceAtMost(lines.size)
                            for (i in from until to) {
                                val marker = if (i == matchIdx) "→" else " "
                                snippets.put("$marker ${i + 1}: ${lines[i]}")
                            }
                        }
                        result.put("contextSnippets", snippets)
                    }

                    results.add(result)
                } else {
                    results.add(JSONObject().apply { put("path", entry.path) })
                }
            }
        }
    }

    private fun matchesGlob(name: String, pattern: String): Boolean {
        val regex = buildString {
            append("(?i)^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append("$")
        }
        return name.matches(Regex(regex))
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