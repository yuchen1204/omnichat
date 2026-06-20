package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/**
 * 记忆搜索工具。
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

        // 确定候选集
        val candidates: List<com.omnichat.data.MemoryItem>
        val totalCount: Int

        if (tagFilter != null) {
            candidates = repository.searchMemoriesByTag(tagFilter)
            totalCount = candidates.size
        } else {
            val keywords = query.split(" ").filter { it.isNotBlank() }
            if (keywords.isNotEmpty()) {
                totalCount = repository.getAllMemories().size
                candidates = keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
            } else {
                candidates = repository.getAllMemories()
                totalCount = candidates.size
            }
        }

        // Jaccard 相似度计算
        val queryTokens = com.omnichat.memory.MemoryTokenizer.tokenize(query)

        data class ScoredMemory(val memory: com.omnichat.data.MemoryItem, val score: Double)

        val scored = candidates
            .mapNotNull { mem ->
                val memTokens = com.omnichat.memory.MemoryTokenizer.tokenize(mem.content)
                val intersection = queryTokens.intersect(memTokens).size
                val union = queryTokens.union(memTokens).size
                if (union == 0 || intersection == 0) return@mapNotNull null

                var score = intersection.toDouble() / union.toDouble() * mem.confidence

                // tag 匹配加成
                if (tagFilter != null && mem.tags.split(",").contains(tagFilter)) {
                    score *= 1.2
                }

                ScoredMemory(mem, score)
            }
            .sortedByDescending { it.score }
            .take(limit)

        // Association expansion via BFS
        val maxExpand = 10
        val expandedMemories = mutableListOf<Triple<com.omnichat.data.MemoryItem, String, Int>>()
        val visited = scored.map { it.memory.id }.toMutableSet()

        val queue: java.util.LinkedList<Pair<Long, Int>> = java.util.LinkedList()
        for (sm in scored) {
            queue.add(sm.memory.id to 0)
        }

        while (queue.isNotEmpty() && expandedMemories.size < maxExpand) {
            val pollResult = queue.poll() ?: continue
            val (currentId, currentDepth) = pollResult
            if (currentDepth >= depth) continue

            val associations = repository.getAssociationsFor(currentId)
            for (assoc in associations) {
                val relatedId = when {
                    assoc.direction == "bidirectional" -> {
                        if (assoc.fromMemoryId == currentId) assoc.toMemoryId else assoc.fromMemoryId
                    }
                    assoc.fromMemoryId == currentId -> assoc.toMemoryId
                    else -> continue
                }
                if (relatedId in visited) continue
                visited.add(relatedId)

                val relatedMem = repository.getMemoryById(relatedId) ?: continue
                expandedMemories.add(Triple(relatedMem, assoc.relationLabel, currentDepth + 1))
                queue.add(relatedId to currentDepth + 1)
            }
        }

        // 构建响应
        val text = buildString {
            appendLine("Memory search: \"$query\"${if (tagFilter != null) " [tag: $tagFilter]" else ""}")
            appendLine("Found: ${scored.size} of $totalCount total")

            if (scored.isEmpty()) {
                appendLine()
                appendLine("No matching memories found.")
            } else {
                appendLine()
                scored.forEachIndexed { i, sm ->
                    val pinnedTag = if (sm.memory.pinned) " [PINNED]" else ""
                    val tagsDisplay = if (sm.memory.tags.isNotBlank()) " [${sm.memory.tags}]" else ""
                    appendLine("${i + 1}. [${sm.memory.id}] confidence=${sm.memory.confidence}, score=${String.format("%.3f", sm.score)}$pinnedTag$tagsDisplay")
                    appendLine("   ${sm.memory.content}")
                }
            }

            if (expandedMemories.isNotEmpty()) {
                appendLine()
                appendLine("Related via associations (depth $depth):")
                expandedMemories.forEachIndexed { i, (mem, label, d) ->
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