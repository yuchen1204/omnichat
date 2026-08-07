package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.memory.MemoryEngine
import com.omnichat.memory.MemorySearchResult
import com.omnichat.network.ApiClient
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/**
 * 记忆搜索工具。
 *
 * 委托给 MemoryEngine.searchMemory 执行搜索，支持 embedding 语义评分（当配置了 embedding 模型时）
 * 和 bigram Jaccard 降级评分，以及关联图谱 BFS 展开。
 */
object SearchMemoryTool : BuiltinTool(
    name = "search_memory",
    description = """Search the long-term memory store for entries related to a keyword. Call this tool when you need to recall a specific user preference, habit, or historical detail that is not present in the current context. The system automatically injects the top 30 highest-confidence memories; all other memories must be retrieved proactively via this tool. Results include automatic traversal of the memory association network (controlled by the 'depth' parameter).""",
    group = "memory",
    isReadOnly = true,
    isConcurrencySafe = true,
    searchHint = "search long-term memory"
) {

    override val inputSchema = schema {
        prop("query", "string", "Search keywords; multiple words are supported (space-separated).")
        prop("tag", "string", "Optional tag filter. Valid values: preference, fact, instruction, habit, context. When provided, only memories with this tag are searched.")
        prop("limit", "integer", "Maximum number of results to return. Default 10, max 50.")
        prop("depth", "integer", "Association traversal depth (1-5). Default 3. When set, the tool traverses the memory association network to find related memories.")
        required("query")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val query = arguments.optString("query").trim()
        if (query.isEmpty()) return "Query is required"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val query = arguments.optString("query")
        val tagFilter = arguments.optString("tag").trim().lowercase().takeIf { it.isNotBlank() }
        val limit = arguments.getIntInRange("limit", 10, 1, 50)
        val depth = arguments.getIntInRange("depth", 3, 1, 5)

        val repository = AppRepository(AppDatabase.getDatabase(context))
        val memoryEngine = MemoryEngine(repository, ApiClient)

        // 计算总记忆数（用于搜索结果的 totalCount）
        val totalCount = repository.getMemoryCount()

        // 委托给 MemoryEngine 执行搜索（支持 embedding 语义评分 + Jaccard 降级 + BFS 展开）
        val result = memoryEngine.searchMemory(
            query = query,
            tagFilter = tagFilter,
            limit = limit,
            depth = depth,
            totalCount = totalCount
        )

        // 构建响应
        val text = buildString {
            appendLine("Memory search: \"$query\"${if (tagFilter != null) " [tag: $tagFilter]" else ""}")
            appendLine("Found: ${result.scored.size} of $totalCount total")

            if (result.scored.isEmpty()) {
                appendLine()
                appendLine("No matching memories found.")
            } else {
                appendLine()
                result.scored.forEachIndexed { i, sm ->
                    val pinnedTag = if (sm.memory.pinned) " [PINNED]" else ""
                    val tagsDisplay = if (sm.memory.tags.isNotBlank()) " [${sm.memory.tags}]" else ""
                    appendLine("${i + 1}. [${sm.memory.id}] confidence=${sm.memory.confidence}, score=${String.format("%.3f", sm.score)}$pinnedTag$tagsDisplay")
                    appendLine("   ${sm.memory.content}")
                }
            }

            if (result.expandedMemories.isNotEmpty()) {
                appendLine()
                appendLine("Related via associations (depth $depth):")
                result.expandedMemories.forEachIndexed { i, (mem, label, d) ->
                    appendLine("  • [$label] [${mem.id}] ${mem.content}")
                }
            }
        }

        return successResponse(text.trimEnd())
    }
}

/**
 * 标记提醒工具。
 */
object MarkRemindedTool : BuiltinTool(
    name = "mark_reminded",
    description = "Mark a time reminder as reminded to prevent repeat reminders. Call this after you have naturally mentioned a pending reminder to the user in your response.",
    group = "memory",
    isReadOnly = false,
    isConcurrencySafe = true,
    searchHint = "mark memory reminder as reminded"
) {

    override val inputSchema = schema {
        prop("memory_id", "integer", "The ID of the memory reminder to mark as reminded")
        required("memory_id")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val memoryId = arguments.optLong("memory_id", -1L)
        if (memoryId <= 0) return "Valid memory_id is required"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val memoryId = arguments.optLong("memory_id")

        val repository = AppRepository(AppDatabase.getDatabase(context))
        val memory = repository.getMemoryById(memoryId)
            ?: return errorResponse("Memory not found: $memoryId")

        if (!memory.dueDate.isNullOrBlank()) {
            repository.markReminded(memoryId)
        }

        return successResponse("Memory $memoryId marked as reminded.")
    }
}

/**
 * 记忆整合优化工具。
 *
 * 使用副模型对所有记忆进行全量分析、去重、合并、分类、打置信分。
 * 避免因记忆条目过多导致 Agent 记忆错乱。
 */
object ConsolidateMemoryTool : BuiltinTool(
    name = "consolidate_memory",
    description = """Analyze, deduplicate, merge, categorize, and re-score all long-term memories using the memory model. This reduces memory clutter and prevents the agent from getting confused by too many redundant or contradictory entries. Pinned memories are protected. Call this when memory count is high or the agent seems to be acting on stale/conflicting information.""",
    group = "memory",
    isReadOnly = false,
    isConcurrencySafe = true,
    searchHint = "consolidate and optimize long-term memory"
) {

    override val inputSchema = schema {
        prop("force", "boolean", "Force consolidation even if memory count is low. Default: false.")
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val force = arguments.optBoolean("force", false)

        val repository = AppRepository(AppDatabase.getDatabase(context))
        val memoryEngine = MemoryEngine(repository, ApiClient)

        val result = memoryEngine.consolidateMemories(force = force)

        val text = buildString {
            appendLine("Memory consolidation result:")
            appendLine("  Before: ${result.totalBefore} memories")
            appendLine("  After:  ${result.totalAfter} memories")
            if (result.addedCount > 0) appendLine("  + Added: ${result.addedCount} (categories/merged entries)")
            if (result.updatedCount > 0) appendLine("  ~ Updated: ${result.updatedCount} (confidence/tags)")
            if (result.deletedCount > 0) appendLine("  - Deleted: ${result.deletedCount} (redundant/outdated)")
            appendLine()
            appendLine(result.summary)
        }

        return successResponse(text.trimEnd())
    }
}