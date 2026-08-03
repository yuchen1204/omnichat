package com.omnichat.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 集中式项目文件存储。
 *
 * 所有项目私有文件（资产、记忆）均通过此存储管理。
 * 文件路径由数据库 ID 和内部文件名生成；UI/工具层不直接构造路径。
 *
 * 通过 [init] 注入生产 root（context.filesDir），通过 [initForTest] 注入临时 root。
 * 调用任何方法前必须先初始化。
 */
object ProjectFileStore {

    private val ALLOWED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "pdf", "doc", "docx", "txt", "md"
    )

    private val memoryMutexes = ConcurrentHashMap<Long, Mutex>()

    @Volatile
    private var rootDir: File? = null

    /** 生产环境初始化。 */
    fun init(context: Context) {
        rootDir = File(context.filesDir, "projects")
        rootDir?.mkdirs()
    }

    /** 测试环境初始化（注入临时目录）。 */
    fun initForTest(root: File) {
        rootDir = root
        rootDir?.mkdirs()
    }

    /** 清除初始化（仅测试用）。 */
    fun resetForTest() {
        rootDir = null
    }

    /** 获取当前 root（仅测试用）。 */
    fun rootForTest(): File = rootDir ?: throw IllegalStateException("ProjectFileStore not initialized")

    private fun requireRoot(): File = rootDir ?: throw IllegalStateException("ProjectFileStore not initialized")

    /** 获取项目根目录。 */
    fun projectDir(projectId: Long): File {
        val dir = File(requireRoot(), "project_$projectId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取资产文件路径（基于数据库 ID 和原始文件名）。 */
    fun assetFile(projectId: Long, assetId: Long, originalName: String): File {
        val ext = extractExtension(originalName)
        require(ext in ALLOWED_EXTENSIONS) {
            "Unsupported file extension: $ext. Allowed: $ALLOWED_EXTENSIONS"
        }
        val dir = File(projectDir(projectId), "knowledge")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "asset_$assetId.$ext")
    }

    /** 获取项目记忆文件。 */
    fun memoryFile(projectId: Long): File {
        val dir = projectDir(projectId)
        return File(dir, "project_memory.md")
    }

    /** 读取项目记忆内容（不存在时返回空字符串）。 */
    fun readMemory(projectId: Long): String {
        val file = memoryFile(projectId)
        return if (file.exists()) file.readText() else ""
    }

    /** 原子替换项目记忆内容（写入 .tmp 后重命名）。 */
    suspend fun writeMemory(projectId: Long, content: String) {
        val mutex = memoryMutexes.getOrPut(projectId) { Mutex() }
        mutex.lock()
        try {
            val target = memoryFile(projectId)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // renameTo 在某些 Android 文件系统上跨挂载可能失败，回退到 copy+delete
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            mutex.unlock()
        }
    }

    /** 测试中直接同步替换（不原子，但简单）。 */
    fun writeMemoryBlocking(projectId: Long, content: String) {
        val target = memoryFile(projectId)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 删除资产文件。 */
    fun deleteAsset(asset: ProjectKnowledge) {
        val file = assetFile(asset.projectId, asset.id, asset.fileName)
        if (file.exists()) file.delete()
    }

    /** 删除整个项目目录。 */
    fun deleteProjectDirectory(projectId: Long) {
        val dir = projectDir(projectId)
        if (dir.exists()) dir.deleteRecursively()
    }

    /**
     * 从 sourceUri 复制内容到项目目录临时文件，返回临时文件。
     * 复制失败时删除临时文件并抛出异常。
     *
     * 当 context 为 null 时，不允许通过 URI 路径访问文件。
     * 仅允许在 context != null 时使用 ContentResolver 访问 URI。
     */
    fun copyIntoProject(
        context: Context?,
        projectId: Long,
        sourceUri: Uri?,
        originalName: String,
        source: String
    ): File {
        val ext = extractExtension(originalName)
        require(ext in ALLOWED_EXTENSIONS) {
            "Unsupported file extension: $ext"
        }
        val dir = File(projectDir(projectId), "knowledge")
        if (!dir.exists()) dir.mkdirs()

        val tmpFile = File(dir, "tmp_${System.currentTimeMillis()}_${System.nanoTime()}")
        try {
            when {
                sourceUri != null && context != null -> {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Failed to open input stream for $sourceUri")
                }
                sourceUri != null -> {
                    throw IllegalArgumentException("Context is required to access URI-based files")
                }
                else -> {
                    tmpFile.createNewFile()
                }
            }
            return tmpFile
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    /** 重命名临时文件为最终 ID 文件名。 */
    fun renameToFinal(tmpFile: File, projectId: Long, assetId: Long, originalName: String): File {
        val finalFile = assetFile(projectId, assetId, originalName)
        if (tmpFile.exists()) {
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
        }
        return finalFile
    }

    /** 提取并标准化扩展名（过滤路径分隔符等）。 */
    private fun extractExtension(originalName: String): String {
        val sanitized = originalName.replace(Regex("[\\\\/<>|?*]"), "_")
        val idx = sanitized.lastIndexOf('.')
        return if (idx > 0 && idx < sanitized.length - 1) {
            sanitized.substring(idx + 1).lowercase()
        } else {
            ""
        }
    }
}
