package com.omnichat.skill

import android.content.Context
import android.net.Uri
import com.omnichat.data.SkillEntity
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Skill 安装器。
 *
 * 支持两种安装格式：
 * 1. .skill.md 单文件（Markdown + YAML frontmatter）
 * 2. .zip 压缩包（内含一个或多个 .skill.md 文件）
 *
 * .skill.md 文件格式：
 * ```markdown
 * ---
 * id: weekly-report
 * name: 周报生成器
 * description: 自动收集本周工作记录，生成结构化周报
 * version: 1.0.0
 * author: omnichat
 * triggerPatterns: ["周报", "weekly report", "写周报"]
 * requiredToolGroups: ["files", "memory"]
 * workflowTemplateId: null
 * ---
 * 系统提示词内容...
 * ```
 */
object SkillInstaller {

    /**
     * 从 Uri 安装 Skill，自动识别 .md 和 .zip 文件。
     */
    suspend fun installFromUri(context: Context, uri: Uri): Result<List<SkillEntity>> {
        return try {
            val uriStr = uri.toString().lowercase()
            if (uriStr.endsWith(".zip")) {
                installFromZipUri(context, uri)
            } else {
                // 尝试作为 .md 文件解析
                val content = readUriContent(context, uri)
                val skill = parseSkillMarkdown(content)
                if (skill != null) {
                    Result.success(listOf(skill))
                } else {
                    // 如果解析失败，也尝试作为 ZIP 处理（某些文件选择器可能丢失扩展名）
                    try {
                        installFromZipUri(context, uri)
                    } catch (_: Exception) {
                        Result.failure(IllegalArgumentException("无法解析文件：格式不正确。请使用 .skill.md 或 .zip 文件。"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 Zip 压缩包中安装 Skill。
     * 扫描包内所有 .skill.md 文件并解析。
     */
    private suspend fun installFromZipUri(context: Context, uri: Uri): Result<List<SkillEntity>> {
        val skills = mutableListOf<SkillEntity>()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".skill.md")) {
                        val content = zis.readBytes().toString(Charsets.UTF_8)
                        val skill = parseSkillMarkdown(content)
                        if (skill != null) {
                            skills.add(skill)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("无法读取文件")

        if (skills.isEmpty()) {
            return Result.failure(IllegalArgumentException("ZIP 文件中未找到任何 .skill.md 文件"))
        }

        return Result.success(skills)
    }

    /**
     * 从字符串内容解析单个 .skill.md 文件。
     */
    fun installFromContent(content: String): Result<SkillEntity> {
        return try {
            val skill = parseSkillMarkdown(content)
            if (skill != null) {
                Result.success(skill)
            } else {
                Result.failure(IllegalArgumentException("无法解析 .skill.md 文件：格式不正确"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析 Markdown + YAML frontmatter 格式的 Skill 文件。
     *
     * 格式：
     * ---
     * key: value
     * ---
     * body text
     */
    private fun parseSkillMarkdown(content: String): SkillEntity? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("---")) return null

        // 分割 frontmatter 和 body
        val firstSep = trimmed.indexOf("---")
        val secondSep = trimmed.indexOf("---", firstSep + 3)
        if (secondSep == -1) return null

        val yamlBlock = trimmed.substring(firstSep + 3, secondSep).trim()
        val body = trimmed.substring(secondSep + 3).trim()

        // 解析 YAML 键值对
        val fields = parseYamlBlock(yamlBlock)
        if (fields.isEmpty()) return null

        val skillId = fields["id"] ?: return null
        val name = fields["name"] ?: return null
        val description = fields["description"] ?: ""

        return SkillEntity(
            skillId = skillId,
            name = name,
            description = description,
            version = fields["version"] ?: "1.0.0",
            author = fields["author"] ?: "unknown",
            triggerPatterns = parseJsonArrayField(fields["triggerPatterns"]),
            systemPrompt = body,
            requiredToolGroups = parseJsonArrayField(fields["requiredToolGroups"]),
            workflowTemplateId = fields["workflowTemplateId"]?.takeIf { it != "null" && it.isNotBlank() },
            isBuiltin = false,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 解析简单的 YAML 键值对（不支持嵌套，只支持 key: value 格式）。
     */
    private fun parseYamlBlock(yaml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = yaml.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex == -1) continue

            val key = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1).trim()

            // 处理引号
            val cleanValue = value
                .removeSurrounding("\"")
                .removeSurrounding("'")

            result[key] = cleanValue
        }

        return result
    }

    /**
     * 解析 JSON 数组字段，如 `["a", "b", "c"]`。
     * 如果解析失败，尝试解析为逗号分隔的字符串。
     */
    private fun parseJsonArrayField(value: String?): String {
        if (value.isNullOrBlank() || value == "null" || value == "[]") return "[]"

        return try {
            // 尝试解析为 JSON 数组
            val arr = JSONArray(value)
            // 验证所有元素都是字符串
            for (i in 0 until arr.length()) {
                arr.getString(i)
            }
            value
        } catch (_: Exception) {
            // 尝试解析为逗号分隔的字符串
            try {
                val items = value.split(",").map { it.trim().removeSurrounding("\"") }
                JSONArray(items).toString()
            } catch (_: Exception) {
                "[]"
            }
        }
    }

    /**
     * 从 Uri 读取文本内容。
     */
    private fun readUriContent(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        } ?: throw IllegalArgumentException("无法读取文件")
    }
}