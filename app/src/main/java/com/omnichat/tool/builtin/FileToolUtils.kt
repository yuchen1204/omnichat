package com.omnichat.tool.builtin

import android.content.Context
import android.os.Environment
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.McpPermissionManager
import java.io.File

/**
 * 文件工具公共方法。
 *
 * 提供路径解析等共享功能。
 */
object FileToolUtils {

    /**
     * 解析路径并检查权限。
     *
     * @param context Android Context
     * @param path 用户提供的路径
     * @param accessType 访问类型
     * @return 解析后的 File，或 null（路径非法或权限被拒绝时）
     */
    suspend fun resolvePath(context: Context, path: String, accessType: FileAccessType): File? {
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

    /**
     * 递归删除目录。
     */
    fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    /**
     * Glob 模式匹配。
     * 支持 * 和 ? 通配符。
     */
    fun matchesGlob(name: String, pattern: String): Boolean {
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

    /**
     * 获取外部存储根目录。
     */
    fun getFilesRoot(): File = Environment.getExternalStorageDirectory()
}
