package com.omnichat.memory

import android.util.Log
import com.omnichat.data.*
import com.omnichat.network.ApiClient
import org.json.JSONObject

/**
 * 记忆引擎：封装所有记忆业务逻辑，从 ChatViewModel 中提取。
 *
 * 职责：
 * - 会话滚动摘要生成
 * - 增量记忆 CRUD（ADD/UPDATE/REINFORCE/DELETE）
 * - 置信度衰减
 * - 记忆搜索（bigram Jaccard + 关联图谱 BFS）
 * - 系统提示词记忆注入
 * - 标签回填
 *
 * 设计：MemoryEngine 是无状态的（所有状态在 Repository/DB 中），
 * 仅持有 repository 和 apiClient 引用，便于测试和未来被 Workspace 模块复用。
 */
class MemoryEngine(
    private val repository: AppRepository,
    private val apiClient: ApiClient  // 当前为 object 单例，传入仅为明确依赖
) {
    companion object {
        private const val TAG = "MemoryEngine"
        private const val MEMORY_INTERVAL_MS = 15 * 60 * 1000L  // 15 分钟
        private const val NEW_MESSAGES_THRESHOLD = 10
        private const val MEMORY_WINDOW_CHARS = 12_000
        private const val MEMORY_RECENT_RAW_COUNT = 20
        private const val MIN_NEW_CHARS_THRESHOLD = 200
        private const val DEDUP_SIMILARITY_THRESHOLD = 0.55
        internal const val MEMORY_INJECT_LIMIT = 30
        private const val COLD_START_ASSOC_LIMIT = 20
        val ASSOC_LABEL_VOCABULARY = setOf("related", "causes", "part_of", "contrasts", "belongs_to", "implies")
    }

    // ── 系统提示词记忆注入 ─────────────────────────────────────────────

    /**
     * 选择注入系统提示词的记忆列表。
     *
     * 策略：
     * 1. 置钉记忆始终注入（最多 limit 条）
     * 2. 剩余槽位用 embedding 语义相关度 × confidence 排序填充
     * 3. 无 embedding 时降级为 confidence 排序（兼容旧行为）
     *
     * @param userMessage 当前用户消息，用于计算语义相关度
     * @param limit 最大注入数量
     */
    suspend fun selectRelevantMemories(userMessage: String = "", limit: Int = MEMORY_INJECT_LIMIT): List<MemoryItem> {
        val allMemories = try {
            repository.getAllMemories()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to load memories for injection: ${e.message}")
            return emptyList()
        }
        val pinned = allMemories.filter { it.pinned }
        val unpinned = allMemories.filter { !it.pinned }

        if (unpinned.isEmpty()) return pinned.take(limit)

        // 从用户消息中提取可能的主题关键词（用于标签匹配加成）
        val messageKeywords = extractKeywordsFromMessage(userMessage)

        // 尝试 embedding 语义排序
        val config = getMemoryModelConfig()
        val embeddingModelId = config?.embeddingModelId?.takeIf { it.isNotBlank() }
        val queryEmbedding = if (!userMessage.isBlank() && embeddingModelId != null) {
            computeEmbedding(userMessage, config, embeddingModelId)
        } else null

        val ranked = unpinned.map { mem ->
            val baseScore = if (queryEmbedding != null) {
                val memEmbedding = MemoryTokenizer.parseEmbedding(mem.embedding)
                if (memEmbedding != null) {
                    MemoryTokenizer.cosineSimilarity(queryEmbedding, memEmbedding) * mem.confidence
                } else {
                    mem.confidence.toDouble()
                }
            } else {
                mem.confidence.toDouble()
            }

            // 标签匹配加成：如果记忆标签与用户消息主题匹配，得分 ×1.3
            val tagBoost = if (messageKeywords.isNotEmpty() && mem.tags.isNotBlank()) {
                val memTags = mem.tags.split(",").map { it.trim().lowercase() }
                if (memTags.any { tag -> messageKeywords.any { kw -> tag.contains(kw) || kw.contains(tag) } }) {
                    1.3
                } else 1.0
            } else 1.0

            mem to baseScore * tagBoost
        }.sortedByDescending { it.second }

        val remaining = limit - pinned.size
        return pinned + ranked.take(remaining).map { it.first }
    }

    /**
     * 从用户消息中提取可能的主题关键词。
     * 简单策略：取最长的几个词作为主题候选。
     */
    private fun extractKeywordsFromMessage(message: String): Set<String> {
        if (message.isBlank()) return emptySet()
        val tokens = MemoryTokenizer.tokenize(message)
        return tokens.filter { it.length >= 2 }.take(5).toSet()
    }

    /**
     * 获取记忆总数（用于判断是否注入 SEARCH HINT）。
     */
    suspend fun getTotalMemoryCount(): Int {
        return repository.getAllMemories().size
    }

    // ── 置信度衰减 ─────────────────────────────────────────────────────

    /**
     * 批量置信度衰减：每行独立计算衰减天数（SQL 中按 lastReinforcedAt 计算）。
     * 使用单条 SQL UPDATE 替代逐条更新，大幅提升性能。
     */
    suspend fun applyConfidenceDecay(now: Long) {
        try {
            repository.batchDecayConfidence(now)
        } catch (e: Exception) {
            Log.w(TAG, "Batch confidence decay failed, falling back to per-item: ${e.message}")
            // 降级为逐条更新
            val allMemories = repository.getAllMemories()
            for (memory in allMemories) {
                if (memory.pinned) continue
                val daysSince = ((now - memory.lastReinforcedAt) / 86_400_000L).toInt()
                if (daysSince <= 0) continue
                val newConfidence = maxOf(1, memory.confidence - daysSince)
                if (newConfidence != memory.confidence) {
                    repository.updateMemory(memory.copy(confidence = newConfidence, lastReinforcedAt = now))
                }
            }
        }
    }

    // ── 记忆同步主流程 ──────────────────────────────────────────────────

    /**
     * 判断是否应该运行记忆同步。
     */
    fun shouldRunSync(
        force: Boolean,
        timeSinceLast: Long,
        newMsgCount: Int,
        newCharsTotal: Int
    ): Boolean {
        if (force) return true
        if (timeSinceLast < MEMORY_INTERVAL_MS && newMsgCount < NEW_MESSAGES_THRESHOLD) return false
        if (newCharsTotal < MIN_NEW_CHARS_THRESHOLD) return false
        return true
    }

    /**
     * 生成本会话的新滚动摘要。
     * 返回 null 表示 API 调用失败。
     */
    suspend fun generateSessionSummary(
        recentMessages: List<Message>,
        previousSummary: String?,
        memoryConfig: ModelConfig
    ): String? {
        val dialogueFormatted = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }

        val systemPrompt =
            "You are a conversation analyst. Produce a compact summary of the conversation. " +
            "Cover two aspects:\n" +
            "1. Topics & conclusions: what was discussed, decisions made, problems solved.\n" +
            "2. User signals: any preferences, habits, skills, tools, dislikes, or personal context " +
            "the user revealed — even implicitly (e.g. choice of language, frustration with a tool, " +
            "repeated patterns). Be specific, not generic.\n" +
            "Aim for 4-10 sentences total. No filler or meta-commentary."

        val userQuery = buildString {
            if (!previousSummary.isNullOrBlank()) {
                append("Previous summary (earlier in this session):\n###\n$previousSummary\n###\n\n")
            }
            append("Recent messages (last ${recentMessages.size}):\n###\n$dialogueFormatted\n###\n\n")
            append("Produce an updated summary incorporating both. Return ONLY the summary text.")
        }

        return ApiClient.executeCompletion(memoryConfig, systemPrompt, userQuery)?.trim()
    }

    /**
     * 生成增量 CRUD 操作 JSON。
     * 返回 null 表示 API 调用失败。
     */
    suspend fun generateCrudOps(
        currentMemories: List<MemoryItem>,
        summaryText: String,
        recentRawMessages: List<Message>,
        memoryConfig: ModelConfig
    ): String? {
        val memoriesFormatted = if (currentMemories.isEmpty()) {
            "No existing facts recorded."
        } else {
            currentMemories.joinToString("\n") { item ->
                val pinnedTag = if (item.pinned) " [PINNED]" else ""
                "${item.id}. (confidence=${item.confidence}${pinnedTag}) ${item.content}"
            }
        }

        val recentRawSnippet = recentRawMessages.joinToString("\n") { "${it.role}: ${it.content}" }

        val factsSystemPrompt = """
You are a User Preference & Persona Synthesizer.
Your job: maintain a list of durable, cross-session personal facts about the user.
Focus ONLY on stable, reusable facts: preferences, skills, habits, goals, dislikes, setup/environment details.
Ignore transient topics (e.g., a one-off question with no lasting relevance).

You will receive:
- Existing facts (each with an id and confidence score; [PINNED] items must NOT be deleted or updated)
- A conversation summary (may compress details)
- Recent raw messages (ground truth — use these to catch signals the summary may have missed)

Output a JSON object with an "ops" array. Each op must be one of:
  {"op": "ADD",       "content": "<one short sentence>", "tags": ["<tag>"]}
  {"op": "UPDATE",    "id": <existing_id>, "content": "<revised sentence>", "tags": ["<tag>"]}
  {"op": "REINFORCE", "id": <existing_id>}
  {"op": "DELETE",    "id": <existing_id>}

Tag rules (assign 1-2 tags per ADD/UPDATE):
  - Tags should be short, descriptive keywords in Chinese or English
  - English tags: max 10 characters (e.g., "preference", "coding", "workflow")
  - Chinese tags: max 5 characters (e.g., "偏好", "技能", "项目")
  - Choose tags that best describe the fact's semantic category
  - You may create new tags or use existing ones for consistency

Rules:
- ADD new facts not yet captured. IMPORTANT: before adding, check if an existing fact already covers the same information — if so, use REINFORCE or UPDATE instead of ADD. Avoid semantic duplicates.
- UPDATE facts that need revision (do NOT update [PINNED] items).
- REINFORCE facts confirmed again without change (boosts confidence).
- DELETE facts that are clearly contradicted or permanently irrelevant (do NOT delete [PINNED] items).
- If nothing changed, return {"ops": []}.
- Return ONLY the raw JSON object, no markdown fences, no commentary.

If two existing facts are meaningfully connected, output an "associations" array alongside "ops":
  {"from": <id>, "to": <id>, "label": "<label>", "direction": "directed"|"bidirectional"}
Label vocabulary: related, causes, part_of, contrasts, belongs_to, implies.
- "related": general semantic connection
- "causes": one fact leads to or results from another
- "part_of": one fact is a component/detail of another
- "contrasts": facts that oppose or conflict
- "belongs_to": one fact categorizes or contextualizes another
- "implies": one fact logically implies another
Only link facts that have a genuine semantic connection. When in doubt, skip.
Do NOT create associations for newly added facts (they don't have stable IDs yet).
""".trimIndent()

        val userQuery = buildString {
            append("Existing facts:\n###\n$memoriesFormatted\n###\n\n")
            append("Conversation summary:\n###\n$summaryText\n###\n\n")
            append("Recent raw messages (last ${recentRawSnippet.lines().size} lines):\n###\n$recentRawSnippet\n###\n\n")
            append("Output the ops JSON now.")
        }

        return ApiClient.executeCompletion(memoryConfig, factsSystemPrompt, userQuery)?.trim()
    }

    /**
     * 解析 LLM 返回的 CRUD JSON 并事务性地 apply 到数据库。
     * 任何解析异常都会被捕获并静默忽略，确保旧记忆不被破坏。
     */
    suspend fun applyMemoryCrudOps(
        json: String,
        existingMemories: List<MemoryItem>,
        now: Long
    ) {
        try {
            val cleaned = json
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val root = JSONObject(cleaned)
            val ops = root.optJSONArray("ops") ?: return

            val existingById = existingMemories.associateBy { it.id }

            for (i in 0 until ops.length()) {
                try {
                    val op = ops.optJSONObject(i) ?: continue
                    when (op.optString("op").uppercase()) {
                        "ADD" -> {
                            val content = op.optString("content").trim()
                            if (content.isNotBlank()) {
                                val duplicate = existingMemories.firstOrNull { existing ->
                                    MemoryTokenizer.jaccardSimilarity(content, existing.content) >= DEDUP_SIMILARITY_THRESHOLD
                                }
                                if (duplicate != null) {
                                    repository.reinforceMemory(duplicate.id, duplicate.content, now)
                                    logAudit(duplicate.id, "REINFORCE", duplicate.content, "sync", duplicate.confidence, duplicate.confidence + 1, now)
                                } else {
                                    val tags = parseTagsFromJson(op.optJSONArray("tags"))
                                    val newId = repository.insertMemory(
                                        MemoryItem(content = content, createdAt = now, updatedAt = now, confidence = 1, tags = tags)
                                    )
                                    logAudit(newId, "ADD", content, "sync", null, 1, now)
                                    if (newId > 0) {
                                        try {
                                            computeAndStoreEmbedding(newId, content)
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            Log.w(TAG, "Embedding computation failed for new memory id=$newId: ${e.message}")
                                        }
                                        try {
                                            createImmediateAssociations(newId, content, existingMemories)
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            Log.w(TAG, "Immediate association creation failed for memory id=$newId: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                        "UPDATE" -> {
                            val id = op.optLong("id", -1L)
                            val content = op.optString("content").trim()
                            val existing = existingById[id]
                            if (existing == null) {
                                Log.w(TAG, "Memory UPDATE ignored: id=$id not found")
                            } else if (existing.pinned) {
                                Log.d(TAG, "Memory UPDATE ignored: id=$id is pinned")
                            } else if (content.isBlank()) {
                                Log.w(TAG, "Memory UPDATE ignored: id=$id has blank content")
                            } else {
                                val tags = parseTagsFromJson(op.optJSONArray("tags"))
                                val newConfidence = existing.confidence + 1
                                repository.updateMemory(
                                    existing.copy(content = content, updatedAt = now, confidence = newConfidence, tags = tags)
                                )
                                logAudit(id, "UPDATE", content, "sync", existing.confidence, newConfidence, now)
                                try {
                                    computeAndStoreEmbedding(id, content)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "Embedding update failed for memory id=$id after UPDATE: ${e.message}")
                                }
                            }
                        }
                        "REINFORCE" -> {
                            val id = op.optLong("id", -1L)
                            val existing = existingById[id]
                            if (existing != null) {
                                repository.reinforceMemory(id, existing.content, now)
                                logAudit(id, "REINFORCE", existing.content, "sync", existing.confidence, existing.confidence + 1, now)
                            } else {
                                Log.w(TAG, "Memory REINFORCE ignored: id=$id not found")
                            }
                        }
                        "DELETE" -> {
                            val id = op.optLong("id", -1L)
                            val existing = existingById[id]
                            if (existing == null) {
                                Log.w(TAG, "Memory DELETE ignored: id=$id not found")
                            } else if (existing.pinned) {
                                Log.d(TAG, "Memory DELETE ignored: id=$id is pinned")
                            } else {
                                logAudit(id, "DELETE", existing.content, "sync", existing.confidence, null, now)
                                repository.deleteMemoryById(id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to apply CRUD op at index $i: ${e.message}", e)
                }
            }

            // 解析并 apply associations
            val existingIds = existingMemories.map { it.id }.toSet()
            applyAssociationsFromJson(json, existingIds)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "applyMemoryCrudOps failed for json length=${json.length}: ${e.message}", e)
        }
    }

    /**
     * 解析 associations JSON 数组并存入数据库。
     * 跳过无效的 id、自关联和未知标签。
     */
    suspend fun applyAssociationsFromJson(json: String, validIds: Set<Long>) {
        try {
            val cleaned = json
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val root = JSONObject(cleaned)
            val associations = root.optJSONArray("associations") ?: return

            for (i in 0 until associations.length()) {
                val assoc = associations.optJSONObject(i) ?: continue
                val from = assoc.optLong("from", -1L)
                val to = assoc.optLong("to", -1L)
                val label = assoc.optString("label", "related").trim().lowercase()
                val direction = assoc.optString("direction", "bidirectional").trim().lowercase()

                if (from !in validIds || to !in validIds || from == to) continue
                if (label !in ASSOC_LABEL_VOCABULARY) continue
                if (direction !in setOf("bidirectional", "directed")) continue

                repository.insertAssociation(
                    MemoryAssociation(
                        fromMemoryId = from,
                        toMemoryId = to,
                        relationLabel = label,
                        direction = direction
                    )
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "applyAssociationsFromJson failed: ${e.message}", e)
        }
    }

    // ── 冷启动关联回填 ──────────────────────────────────────────────────

    /**
     * 生成冷启动关联回填的 LLM prompt 并执行。
     */
    suspend fun generateAssociationsForUnassociated(
        unassociated: List<MemoryItem>,
        memoryConfig: ModelConfig
    ): String? {
        val candidatesFormatted = unassociated.joinToString("\n") { mem ->
            "${mem.id}. (confidence=${mem.confidence}) ${mem.content}"
        }
        val backfillSystemPrompt = """
You are a memory graph builder. Given a list of facts, identify meaningful connections between them.
Output a JSON object with an "associations" array:
  {"from": <id>, "to": <id>, "label": "<label>"}
Label vocabulary: related, causes, part_of, contrasts, belongs_to, implies.
- "related": general semantic connection
- "causes": one fact leads to or results from another
- "part_of": one fact is a component/detail of another
- "contrasts": facts that oppose or conflict
- "belongs_to": one fact categorizes or contextualizes another
- "implies": one fact logically implies another
Only link facts with genuine semantic connections. If none qualify, return {"associations": []}.
Return ONLY the raw JSON object, no markdown fences, no commentary.
""".trimIndent()

        val backfillQuery = "Facts:\n###\n$candidatesFormatted\n###\n\nOutput associations JSON now."
        return ApiClient.executeCompletion(memoryConfig, backfillSystemPrompt, backfillQuery)?.trim()
    }

    // ── 记忆搜索 ───────────────────────────────────────────────────────

    /**
     * 执行记忆搜索：embedding 语义评分（优先） + bigram Jaccard 降级 + 关联图谱 BFS 展开。
     */
    suspend fun searchMemory(
        query: String,
        tagFilter: String?,
        limit: Int,
        depth: Int,
        totalCount: Int
    ): MemorySearchResult {
        // 候选集获取
        val candidates: List<MemoryItem>
        if (tagFilter != null) {
            candidates = repository.searchMemoriesByTag(tagFilter)
        } else {
            // 优先用 FTS5 全文检索，降级为 SQL LIKE
            val ftsIds = try {
                repository.searchMemoryFts(query, limit = 100)
            } catch (e: Exception) {
                Log.w(TAG, "FTS5 search failed, falling back to keyword search: ${e.message}")
                emptyList()
            }
            if (ftsIds.isNotEmpty()) {
                candidates = ftsIds.mapNotNull { repository.getMemoryById(it) }
            } else {
                val keywords = query.split(" ").filter { it.isNotBlank() }
                if (keywords.isNotEmpty()) {
                    candidates = keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
                } else {
                    candidates = repository.getAllMemories()
                }
            }
        }

        // 尝试 embedding 语义评分
        val config = getMemoryModelConfig()
        val embeddingModelId = config?.embeddingModelId?.takeIf { it.isNotBlank() }
        val queryEmbedding = if (embeddingModelId != null) {
            computeEmbedding(query, config, embeddingModelId)
        } else null

        val scored = if (queryEmbedding != null) {
            // Embedding 语义评分：cosine × confidence
            candidates.map { mem ->
                val memEmbedding = MemoryTokenizer.parseEmbedding(mem.embedding)
                val score = if (memEmbedding != null) {
                    MemoryTokenizer.cosineSimilarity(queryEmbedding, memEmbedding) * mem.confidence
                } else {
                    // 无 embedding 的记忆：bigram Jaccard 降级
                    val memTokens = MemoryTokenizer.tokenize(mem.content)
                    val queryTokens = MemoryTokenizer.tokenize(query)
                    val intersection = queryTokens.intersect(memTokens).size
                    val union = queryTokens.union(memTokens).size
                    if (union == 0 || intersection == 0) return@map null
                    (intersection.toDouble() / union) * mem.confidence
                }
                var finalScore = score
                if (tagFilter != null && mem.tags.split(",").contains(tagFilter)) {
                    finalScore *= 1.2
                }
                ScoredMemoryItem(mem, finalScore)
            }.filterNotNull()
                .sortedByDescending { it.score }
                .take(limit)
        } else {
            // 无 embedding 能力：bigram Jaccard 评分
            val queryTokens = MemoryTokenizer.tokenize(query)
            candidates.mapNotNull { mem ->
                val memTokens = MemoryTokenizer.tokenize(mem.content)
                val intersection = queryTokens.intersect(memTokens).size
                val union = queryTokens.union(memTokens).size
                if (union == 0 || intersection == 0) return@mapNotNull null
                val jaccard = intersection.toDouble() / union.toDouble()
                var score = jaccard * mem.confidence
                if (tagFilter != null && mem.tags.split(",").contains(tagFilter)) {
                    score *= 1.2
                }
                ScoredMemoryItem(mem, score)
            }.sortedByDescending { it.score }
                .take(limit)
        }

        // BFS 关联展开
        val expandedMemories = mutableListOf<Triple<MemoryItem, String, Int>>()
        val visited = scored.map { it.memory.id }.toMutableSet()
        val queue: java.util.LinkedList<Pair<Long, Int>> = java.util.LinkedList()
        for (sm in scored) {
            queue.add(sm.memory.id to 0)
        }

        while (queue.isNotEmpty() && expandedMemories.size < 10) {
            val pollResult = queue.poll() ?: continue
            val (currentId, currentDepth) = pollResult
            if (currentDepth >= depth) continue

            try {
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "BFS expansion failed for memory id=$currentId: ${e.message}")
            }
        }

        return MemorySearchResult(
            scored = scored,
            expandedMemories = expandedMemories,
            totalCount = totalCount
        )
    }

    // ── 标签工具方法 ─────────────────────────────────────────────────────

    /**
     * 从 LLM 返回的 tags JSON 数组中解析标签。
     */
    fun parseTagsFromJson(tagsArray: org.json.JSONArray?): String {
        if (tagsArray == null) return ""
        val tags = (0 until tagsArray.length())
            .map { tagsArray.optString(it, "").trim() }
            .filter { tag ->
                if (tag.isBlank()) return@filter false
                val isEnglish = tag.all { it.code < 128 }
                if (isEnglish) tag.length <= 10 else tag.length <= 5
            }
        return tags.joinToString(",").take(100)
    }

    /**
     * 获取内存模型配置。
     * 从默认 Provider 读取 memoryModelId / memoryProviderId。
     */
    suspend fun getMemoryModelConfig(): ModelConfig? {
        val defaultProvider = repository.getDefaultProvider() ?: return null
        val memoryProviderId = defaultProvider.memoryProviderId
        val memoryProvider = if (memoryProviderId > 0L) {
            repository.getConfigById(memoryProviderId) ?: defaultProvider
        } else {
            defaultProvider
        }
        return memoryProvider.copy(
            selectedModelId = defaultProvider.memoryModelId.takeIf { it.isNotBlank() }
                ?: defaultProvider.selectedModelId
        )
    }

    // ── Embedding 工具方法 ──────────────────────────────────────────────

    /**
     * 计算单条文本的 embedding 向量。
     * 返回 null 表示 embedding 模型未配置或 API 调用失败。
     */
    suspend fun computeEmbedding(
        text: String,
        config: ModelConfig,
        embeddingModelId: String
    ): FloatArray? {
        if (text.isBlank()) return null
        return try {
            val results = ApiClient.executeEmbedding(config, listOf(text), embeddingModelId)
            results?.firstOrNull()?.toFloatArray()
        } catch (e: Exception) {
            Log.w(TAG, "Embedding computation failed: ${e.message}")
            null
        }
    }

    /**
     * 批量计算 embedding 向量。
     * 每批 20 条，避免超出 API 限制。
     */
    suspend fun computeEmbeddingsBatch(
        texts: List<String>,
        config: ModelConfig,
        embeddingModelId: String
    ): List<FloatArray?> {
        val results = mutableListOf<FloatArray?>()
        for (batch in texts.chunked(20)) {
            try {
                val batchResults = ApiClient.executeEmbedding(config, batch, embeddingModelId)
                if (batchResults != null) {
                    results.addAll(batchResults.map { it.toFloatArray() })
                    // API 返回数量不足时填充 null，保持与输入对齐
                    if (batchResults.size < batch.size) {
                        repeat(batch.size - batchResults.size) { results.add(null) }
                    }
                } else {
                    results.addAll(batch.map { null })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Batch embedding failed: ${e.message}")
                results.addAll(batch.map { null })
            }
        }
        return results
    }

    /**
     * 为指定记忆计算并存储 embedding。
     * 用于新记忆写入时和批量回填。
     */
    suspend fun computeAndStoreEmbedding(memoryId: Long, content: String) {
        val config = getMemoryModelConfig() ?: return
        val embeddingModelId = config.embeddingModelId.takeIf { it.isNotBlank() } ?: return
        val embedding = computeEmbedding(content, config, embeddingModelId) ?: return
        val memory = repository.getMemoryById(memoryId) ?: return
        repository.updateMemory(memory.copy(embedding = MemoryTokenizer.embeddingToJson(embedding)))
    }

    // ── 审计日志 ────────────────────────────────────────────────────────

    // ── 即时关联发现 ────────────────────────────────────────────────────

    /**
     * 为新添加的记忆立即创建关联边。
     * 基于 embedding 余弦相似度找到 top-3 最相似的已有记忆，创建 "related" 关联。
     * 无 embedding 时降级为 bigram Jaccard 相似度。
     * 阈值：embedding cosine ≥ 0.7 或 Jaccard ≥ 0.4。
     */
    private suspend fun createImmediateAssociations(
        newMemoryId: Long,
        newContent: String,
        existingMemories: List<MemoryItem>
    ) {
        if (existingMemories.isEmpty()) return

        val config = getMemoryModelConfig()
        val embeddingModelId = config?.embeddingModelId?.takeIf { it.isNotBlank() }
        val newEmbedding = if (embeddingModelId != null) {
            MemoryTokenizer.parseEmbedding(
                repository.getMemoryById(newMemoryId)?.embedding ?: ""
            )
        } else null

        data class SimilarCandidate(val memory: MemoryItem, val score: Double)

        val candidates = existingMemories
            .filter { it.id != newMemoryId }
            .map { mem ->
                val score = if (newEmbedding != null) {
                    val memEmbedding = MemoryTokenizer.parseEmbedding(mem.embedding)
                    if (memEmbedding != null) {
                        MemoryTokenizer.cosineSimilarity(newEmbedding, memEmbedding)
                    } else {
                        MemoryTokenizer.jaccardSimilarity(newContent, mem.content)
                    }
                } else {
                    MemoryTokenizer.jaccardSimilarity(newContent, mem.content)
                }
                SimilarCandidate(mem, score)
            }
            .filter { it.score >= if (newEmbedding != null) 0.7 else 0.4 }
            .sortedByDescending { it.score }
            .take(3)

        for (candidate in candidates) {
            repository.insertAssociation(
                MemoryAssociation(
                    fromMemoryId = minOf(newMemoryId, candidate.memory.id),
                    toMemoryId = maxOf(newMemoryId, candidate.memory.id),
                    relationLabel = "related",
                    direction = "bidirectional"
                )
            )
        }
    }

    /**
     * 记录一条审计日志。
     */
    private suspend fun logAudit(
        memoryId: Long,
        opType: String,
        contentSnapshot: String,
        triggerReason: String,
        confidenceBefore: Int?,
        confidenceAfter: Int?,
        timestamp: Long
    ) {
        try {
            repository.insertAuditEntry(
                MemoryAuditEntry(
                    memoryId = memoryId,
                    opType = opType,
                    contentSnapshot = contentSnapshot.take(500),
                    triggerReason = triggerReason,
                    confidenceBefore = confidenceBefore,
                    confidenceAfter = confidenceAfter,
                    timestamp = timestamp
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Audit log write failed for memoryId=$memoryId op=$opType: ${e.message}")
        }
    }

    /**
     * 裁剪 30 天前的审计日志。
     */
    suspend fun pruneOldAuditLogs() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        try {
            repository.pruneOldAuditEntries(thirtyDaysAgo)
        } catch (e: Exception) {
            Log.w(TAG, "Audit log pruning failed: ${e.message}", e)
        }
    }

    /**
     * 获取指定记忆的审计历史。
     */
    suspend fun getAuditHistory(memoryId: Long): List<MemoryAuditEntry> {
        return repository.getAuditHistoryForMemory(memoryId)
    }

    /**
     * 获取最近的审计活动。
     */
    suspend fun getRecentAuditActivity(limit: Int = 100): List<MemoryAuditEntry> {
        return repository.getRecentAuditActivity(limit)
    }
}

/**
 * 记忆搜索结果。
 */
data class MemorySearchResult(
    val scored: List<ScoredMemoryItem>,
    val expandedMemories: List<Triple<MemoryItem, String, Int>>,
    val totalCount: Int
)

data class ScoredMemoryItem(
    val memory: MemoryItem,
    val score: Double
)
