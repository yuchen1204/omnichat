package com.example.workspace

import android.util.Log
import java.io.File

/**
 * Markdown Agent 定义加载器。
 *
 * 解析 .claude/agents/*.md 文件，从 YAML frontmatter 提取 Agent 定义。
 * 对齐 Claude Code 的 parseAgentFromMarkdown。
 */
object MarkdownAgentLoader {
    private const val TAG = "MarkdownAgentLoader"

    /**
     * 从目录加载所有 Markdown Agent 定义。
     *
     * @param baseDir Agent 定义目录路径（如 .claude/agents）
     * @return 解析的 Agent 定义列表
     */
    fun loadFromDirectory(baseDir: File): List<AgentDefinition> {
        if (!baseDir.exists() || !baseDir.isDirectory) {
            Log.w(TAG, "Directory does not exist: ${baseDir.absolutePath}")
            return emptyList()
        }

        val agents = mutableListOf<AgentDefinition>()

        val mdFiles = baseDir.listFiles { file ->
            file.extension == "md" && file.nameWithoutExtension != "README"
        } ?: return emptyList()

        for (mdFile in mdFiles) {
            val agent = parseMarkdownFile(mdFile, baseDir.absolutePath)
            if (agent != null) {
                agents.add(agent)
                Log.d(TAG, "Loaded agent '${agent.agentType}' from ${mdFile.name}")
            }
        }

        return agents
    }

    /**
     * 解析单个 Markdown 文件。
     */
    private fun parseMarkdownFile(file: File, baseDir: String): AgentDefinition? {
        val content = file.readText()

        // 提取 frontmatter（--- 之间的 YAML）
        val frontmatter = extractFrontmatter(content)
        if (frontmatter == null) {
            Log.w(TAG, "No frontmatter found in ${file.name}")
            return null
        }

        val systemPrompt = extractBodyContent(content)

        return parseAgentFromFrontmatter(
            frontmatter = frontmatter,
            systemPrompt = systemPrompt,
            filePath = file.absolutePath,
            baseDir = baseDir,
            filename = file.nameWithoutExtension,
        )
    }

    /**
     * 提取 YAML frontmatter。
     */
    private fun extractFrontmatter(content: String): Map<String, Any>? {
        val lines = content.lines()

        // 检查首行是否为 ---
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return null
        }

        // 找到结束的 ---
        val endIdx = lines.indexOf("---", 1)
        if (endIdx == -1) {
            return null
        }

        // 解析 YAML（简化实现，仅支持基本类型）
        val frontmatterLines = lines.subList(1, endIdx)
        return parseYaml(frontmatterLines)
    }

    /**
     * 提取 Markdown body（frontmatter 之后的内容）。
     */
    private fun extractBodyContent(content: String): String {
        val lines = content.lines()
        val startIdx = lines.indexOf("---", 1)
        if (startIdx == -1) return content.trim()

        val bodyLines = lines.subList(startIdx + 1, lines.size)
        return bodyLines.joinToString("\n").trim()
    }

    /**
     * 简化 YAML 解析（支持 key: value 格式）。
     */
    private fun parseYaml(lines: List<String>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val colonIdx = trimmed.indexOf(":")
            if (colonIdx == -1) continue

            val key = trimmed.substring(0, colonIdx).trim()
            val valueStr = trimmed.substring(colonIdx + 1).trim()

            // 解析值类型
            val value = parseYamlValue(valueStr)
            result[key] = value
        }

        return result
    }

    /**
     * 解析 YAML 值。
     */
    private fun parseYamlValue(valueStr: String): Any {
        // 布尔值
        if (valueStr == "true") return true
        if (valueStr == "false") return false

        // 数字
        valueStr.toIntOrNull()?.let { return it }
        valueStr.toLongOrNull()?.let { return it }

        // 字符串（移除引号）
        if (valueStr.startsWith("\"") && valueStr.endsWith("\"")) {
            return valueStr.substring(1, valueStr.length - 1)
        }
        if (valueStr.startsWith("'") && valueStr.endsWith("'")) {
            return valueStr.substring(1, valueStr.length - 1)
        }

        // 数组（简化，仅支持 ["a", "b"] 格式）
        if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
            val inner = valueStr.substring(1, valueStr.length - 1)
            return inner.split(",").map { it.trim().removeSurrounding("\"") }
        }

        // 默认为字符串
        return valueStr
    }

    /**
     * 从 frontmatter 解析 AgentDefinition。
     */
    private fun parseAgentFromFrontmatter(
        frontmatter: Map<String, Any>,
        systemPrompt: String,
        filePath: String,
        baseDir: String,
        filename: String,
    ): AgentDefinition? {
        // 必需字段
        val name = frontmatter["name"] as? String
        val description = frontmatter["description"] as? String

        if (name == null || name.isBlank()) {
            Log.w(TAG, "Missing 'name' in frontmatter of $filePath")
            return null
        }
        if (description == null || description.isBlank()) {
            Log.w(TAG, "Missing 'description' in frontmatter of $filePath")
            return null
        }

        // 可选字段
        @Suppress("UNCHECKED_CAST")
        val tools = (frontmatter["tools"] as? List<String>)
        @Suppress("UNCHECKED_CAST")
        val disallowedTools = (frontmatter["disallowedTools"] as? List<String>)
        val model = frontmatter["model"] as? String
        val color = frontmatter["color"] as? String
        val background = frontmatter["background"] as? Boolean ?: false
        val maxTurns = (frontmatter["maxTurns"] as? Number)?.toInt()
        val memory = frontmatter["memory"] as? String
        val permissionMode = frontmatter["permissionMode"] as? String
        val effort = frontmatter["effort"] as? String
        val initialPrompt = frontmatter["initialPrompt"] as? String
        val omitClaudeMd = frontmatter["omitClaudeMd"] as? Boolean ?: false

        return AgentDefinition(
            agentType = name,
            displayName = name,
            whenToUse = description.replace("\\n", "\n"),
            systemPrompt = systemPrompt,
            tools = tools,
            disallowedTools = disallowedTools,
            modelHint = model,
            color = color,
            background = background,
            maxTurns = maxTurns ?: AgentRunner.MAX_TOOL_CALL_ITERATIONS,
            memory = memory,
            permissionMode = permissionMode,
            effort = effort,
            initialPrompt = initialPrompt,
            omitClaudeMd = omitClaudeMd,
            isBuiltIn = false,
            source = "markdown",
            filename = filename,
            baseDir = baseDir,
        )
    }
}
