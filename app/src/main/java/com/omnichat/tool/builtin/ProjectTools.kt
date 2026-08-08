package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.ProjectContentLimits
import com.omnichat.data.ProjectFileStore
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import com.omnichat.util.JsDocumentReader
import org.json.JSONObject

/**
 * 从 sessionId 推导所属项目的 projectId。
 * 若 session 无 projectId 则返回 null。
 */
private suspend fun resolveProjectId(repository: AppRepository, sessionId: Long?): Long? {
    if (sessionId == null) return null
    return repository.getSessionById(sessionId)?.projectId
}

/**
 * 列出项目知识文件工具。
 *
 * 在项目会话中，Agent 通过此工具了解项目知识库中有哪些文件。
 * 结果包含文件名、类型和大小，但不暴露文件系统路径。
 */
object ProjectListKnowledgeTool : BuiltinTool(
    name = "project_list_knowledge",
    description = "List all knowledge files in the current project. Returns file names, types, and sizes. Use this to discover what documents are available in the project knowledge base before reading them.",
    group = "project",
    isReadOnly = true,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "list project knowledge files"
) {
    override val inputSchema = schema {}

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")

        val project = repository.getProjectById(projectId)
            ?: return errorResponse("Project not found: $projectId")

        val files = repository.getKnowledgeByProject(projectId)

        if (files.isEmpty()) {
            return successResponse("No knowledge files in project: ${project.name}")
        }

        val text = buildString {
            appendLine("Knowledge files for project '${project.name}' (${files.size}):")
            appendLine()
            files.forEachIndexed { i, file ->
                val sizeStr = when {
                    file.fileSize < 1024 -> "${file.fileSize} B"
                    file.fileSize < 1024 * 1024 -> "${file.fileSize / 1024} KB"
                    else -> "${file.fileSize / (1024 * 1024)} MB"
                }
                appendLine("${i + 1}. [${file.id}] ${file.fileName} ($sizeStr, ${file.source})")
            }
        }

        return successResponse(text.trimEnd())
    }
}

/**
 * 读取项目知识文件工具。
 *
 * 通过 [knowledge_id] 读取项目知识库中的文件内容。
 * 支持文本文件（md, txt）、PDF、DOCX 和图片。
 * 使用 [ProjectFileStore] 解析文件路径，不暴露绝对路径。
 */
object ProjectReadKnowledgeTool : BuiltinTool(
    name = "project_read_knowledge",
    description = "Read the content of a knowledge file in the current project. Supports text files (md, txt), PDF, DOCX, and images. For PDF and DOCX files, extracts and returns the text content. Use [project_list_knowledge] to get the knowledge_id for each file.",
    group = "project",
    isReadOnly = true,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "read project knowledge file"
) {
    override val inputSchema = schema {
        prop("knowledge_id", "integer", "The ID of the knowledge file to read. Use project_list_knowledge to get IDs.")
        required("knowledge_id")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val id = arguments.optLong("knowledge_id", -1L)
        if (id <= 0) return "Valid knowledge_id is required"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")

        val knowledgeId = arguments.optLong("knowledge_id")

        // 验证 asset 属于当前项目
        val knowledge = repository.getKnowledgeById(knowledgeId)
            ?: return errorResponse("Knowledge file not found: $knowledgeId")
        if (knowledge.projectId != projectId) {
            return errorResponse("Knowledge file does not belong to the current project")
        }

        // 通过 ProjectFileStore 解析文件路径（优先使用 localFileName 或回退到旧规则）
        val file = ProjectFileStore.assetFile(knowledge)
        if (!file.exists()) {
            return errorResponse("Actual file not found on disk for: ${knowledge.fileName}")
        }

        if (knowledge.fileType == "image") {
            if (file.length() > ProjectContentLimits.MAX_IMAGE_DATA_URL_BYTES) {
                return errorResponse(ProjectContentLimits.imageDataUrlLimitError())
            }
            // 返回 data URL 而不是文件系统路径
            val dataUrl = buildImageDataUrl(file, knowledge.fileType)
            return successResponse("Image file: ${knowledge.fileName}\n\n$dataUrl")
        }

        // PDF 和 DOCX 使用 JsDocumentReader 解析
        if (knowledge.fileType in listOf("pdf", "docx")) {
            return try {
                val reader = JsDocumentReader(context)
                val result = reader.parse(file)
                val summary = buildString {
                    appendLine("File: ${knowledge.fileName}")
                    appendLine()
                    append(result.text)
                    if (result.warnings.isNotEmpty()) {
                        appendLine()
                        appendLine("Warnings:")
                        result.warnings.forEach { appendLine("  - $it") }
                    }
                }
                successResponse(ProjectContentLimits.truncateToolText(summary.trimEnd()))
            } catch (e: Exception) {
                errorResponse("Failed to parse document: ${e.localizedMessage}")
            }
        }

        // 读取文本文件
        return try {
            val content = file.readText()
            successResponse(ProjectContentLimits.truncateToolText("File: ${knowledge.fileName}\n\n$content"))
        } catch (e: Exception) {
            successResponse("File: ${knowledge.fileName}\n\n(Binary file, cannot display as text)")
        }
    }

    private fun buildImageDataUrl(file: java.io.File, fileType: String): String {
        val mime = when (fileType) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        val bytes = file.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:$mime;base64,$base64"
    }
}

/**
 * 创建项目知识文件工具。
 *
 * Agent 通过此工具在项目知识库中创建新的知识文件。
 * 支持 TXT、MD、DOC 格式，DOCX 仅支持上传后编辑，避免生成无效 OOXML。
 * 创建的资产标记为 [AGENT_CREATED] 来源。
 */
object ProjectCreateKnowledgeTool : BuiltinTool(
    name = "project_create_knowledge",
    description = "Create a new knowledge file in the current project. Supported formats: txt, md, doc. DOCX files must be uploaded first, then edited with project_append_knowledge or project_edit_knowledge. The file content is provided directly by the agent." ,
    group = "project",
    isReadOnly = false,
    isConcurrencySafe = false,
    requiresSession = true,
    searchHint = "create project knowledge file"
) {
    override val inputSchema = schema {
        prop("file_name", "string", "The file name with extension, e.g. 'analysis.md', 'summary.txt'.")
        prop("content", "string", "The text content of the file. For TXT and MD files, content is saved as UTF-8 text.")
        prop("file_type", "string", "File type.") {
            enum("txt", "md", "doc")
        }
        required("file_name", "content", "file_type")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val fileName = arguments.optString("file_name").trim()
        if (fileName.isEmpty()) return "file_name is required"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("txt", "md", "doc")) {
            return "Unsupported file extension: .$ext. Supported: .txt, .md, .doc"
        }
        val content = arguments.optString("content").trim()
        if (content.isEmpty()) return "content is required"
        val fileType = arguments.optString("file_type").trim()
        if (fileType !in setOf("txt", "md", "doc")) {
            return "Unsupported file_type: $fileType. Supported: txt, md, doc"
        }
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")

        val project = repository.getProjectById(projectId)
            ?: return errorResponse("Project not found: $projectId")

        val fileName = arguments.optString("file_name").trim()
        val content = arguments.optString("content")
        val fileType = arguments.optString("file_type").trim()

        return try {
            val asset = repository.createAgentProjectAsset(
                projectId = projectId,
                fileName = fileName,
                content = content.toByteArray(Charsets.UTF_8),
                fileType = fileType,
                source = "AGENT_CREATED"
            )
            successResponse("Knowledge file created in project '${project.name}'.\n\nFile: ${asset.fileName}\nID: ${asset.id}\nType: ${asset.fileType}")
        } catch (e: IllegalStateException) {
            errorResponse(e.message ?: "Asset with the same name already exists")
        } catch (e: Exception) {
            errorResponse("Failed to create knowledge file: ${e.localizedMessage}")
        }
    }
}

abstract class ProjectKnowledgeTextTool(
    name: String,
    description: String
) : BuiltinTool(
    name = name,
    description = description,
    group = "project",
    isReadOnly = false,
    isConcurrencySafe = false,
    requiresSession = true,
    searchHint = "edit project knowledge file"
) {
    override val inputSchema = schema {
        prop("knowledge_id", "integer", "The ID of an existing text knowledge file.")
        prop("content", "string", "The text to append or the replacement text.")
        required("knowledge_id", "content")
    }

    override fun validateInput(arguments: JSONObject): String? {
        if (arguments.optLong("knowledge_id", -1L) <= 0) return "Valid knowledge_id is required"
        if (arguments.optString("content").isEmpty()) return "content is required"
        return null
    }
}

object ProjectAppendKnowledgeTool : ProjectKnowledgeTextTool(
    "project_append_knowledge",
    "Append content to an existing txt, md, or docx knowledge file in the current project."
) {
    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")
        return try {
            repository.appendProjectKnowledge(arguments.optLong("knowledge_id"), projectId, arguments.optString("content"))
            successResponse("Knowledge file updated")
        } catch (e: IllegalArgumentException) {
            errorResponse(e.message ?: "Append failed")
        } catch (e: IllegalStateException) {
            errorResponse(e.message ?: "Append failed")
        }
    }
}

object ProjectEditKnowledgeTool : ProjectKnowledgeTextTool(
    "project_edit_knowledge",
    "Replace old_text in an existing txt, md, or docx knowledge file in the current project."
) {
    override val inputSchema = schema {
        prop("knowledge_id", "integer", "The ID of an existing text knowledge file.")
        prop("content", "string", "Replacement text.")
        prop("old_text", "string", "Text to replace.")
        required("knowledge_id", "content", "old_text")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val base = super.validateInput(arguments)
        if (base != null) return base
        if (arguments.optString("old_text").isEmpty()) return "old_text is required"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")
        return try {
            repository.editProjectKnowledge(arguments.optLong("knowledge_id"), projectId, arguments.optString("old_text"), arguments.optString("content"))
            successResponse("Knowledge file updated")
        } catch (e: IllegalArgumentException) {
            errorResponse(e.message ?: "Edit failed")
        } catch (e: IllegalStateException) {
            errorResponse(e.message ?: "Edit failed")
        }
    }
}

/**
 * 读取项目 Memory 文件工具。
 *
 * 读取当前项目的 project_memory.md 文件内容。
 * 项目 Memory 是存储项目上下文、指南和笔记的持久化 Markdown 文件。
 */
object ProjectReadMemoryTool : BuiltinTool(
    name = "project_read_memory",
    description = "Read the project memory file for the current project. The project memory is a persistent markdown file that stores project context, guidelines, and notes. Call this tool when you need to understand the project context, goals, or any project-specific information.",
    group = "project",
    isReadOnly = true,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "read project memory file"
) {
    override val inputSchema = schema {}

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")

        val project = repository.getProjectById(projectId)
            ?: return errorResponse("Project not found: $projectId")

        val content = repository.readProjectMemory(projectId)

        if (content.isBlank()) {
            return successResponse("Project memory is empty for: ${project.name}")
        }

        return successResponse("Project: ${project.name}\n\n$content")
    }
}

/**
 * 更新项目 Memory 文件工具。
 *
 * 支持以下操作：
 * - append: 追加内容到当前 Memory 末尾
 * - replace: 替换指定文本范围
 * - delete: 删除包含指定文本的段落
 * 使用串行原子写入，防止并发冲突。
 */
object ProjectUpdateMemoryTool : BuiltinTool(
    name = "project_update_memory",
    description = "Update the project memory file. Supports append (add content to end), replace (replace a specific text range with new text), and delete (remove a section containing specific text). The project memory is a persistent markdown file that stores project context, guidelines, and notes across sessions.",
    group = "project",
    isReadOnly = false,
    isConcurrencySafe = false,
    requiresSession = true,
    searchHint = "update project memory file"
) {
    override val inputSchema = schema {
        prop("action", "string", "The update action to perform.") {
            enum("append", "replace", "delete")
        }
        prop("content", "string", "New content. Required for 'append' and 'replace' actions.")
        prop("old_text", "string", "The text to replace. Required for 'replace' action.")
        prop("section_text", "string", "The text to delete. Required for 'delete' action.")
        required("action")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val action = arguments.optString("action").trim()
        if (action !in listOf("append", "replace", "delete")) {
            return "Invalid action: $action. Must be append, replace, or delete."
        }
        when (action) {
            "append" -> {
                val content = arguments.optString("content").trim()
                if (content.isEmpty()) return "content is required for append action"
            }
            "replace" -> {
                val content = arguments.optString("content").trim()
                if (content.isEmpty()) return "content is required for replace action"
                val oldText = arguments.optString("old_text").trim()
                if (oldText.isEmpty()) return "old_text is required for replace action"
            }
            "delete" -> {
                val sectionText = arguments.optString("section_text").trim()
                if (sectionText.isEmpty()) return "section_text is required for delete action"
            }
        }
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val repository = AppRepository(AppDatabase.getDatabase(context), context)
        val projectId = resolveProjectId(repository, sessionId)
            ?: return errorResponse("Current session is not associated with a project")

        val project = repository.getProjectById(projectId)
            ?: return errorResponse("Project not found: $projectId")

        val action = arguments.optString("action").trim()

        return try {
            when (action) {
                "append" -> {
                    val content = arguments.optString("content")
                    repository.appendProjectMemory(projectId, content)
                    successResponse("Content appended to project memory for: ${project.name}")
                }
                "replace" -> {
                    val content = arguments.optString("content")
                    val oldText = arguments.optString("old_text")
                    repository.replaceProjectMemoryRange(projectId, oldText, content)
                    successResponse("Project memory updated for: ${project.name}")
                }
                "delete" -> {
                    val sectionText = arguments.optString("section_text")
                    repository.deleteProjectMemorySection(projectId, sectionText)
                    successResponse("Section deleted from project memory for: ${project.name}")
                }
                else -> errorResponse("Invalid action: $action")
            }
        } catch (e: IllegalArgumentException) {
            errorResponse(e.message ?: "Operation failed")
        }
    }
}