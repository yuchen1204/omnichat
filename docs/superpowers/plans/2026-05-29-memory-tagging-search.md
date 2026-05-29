# Memory Tagging & Search Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add LLM-generated semantic tags to memories and enhance `search_memory` with tag-aware filtering.

**Architecture:** Add a `tags` comma-separated string field to `MemoryItem`. The LLM assigns 1-2 tags per memory during the existing sync flow. `search_memory` gains a `tag` filter parameter that pre-filters by tag before text matching, with a relevance boost for tag matches.

**Tech Stack:** Kotlin, Room, Android Compose, org.json

---

## File Structure

| File | Responsibility | Action |
|------|---------------|--------|
| `app/src/main/java/com/example/data/Entities.kt` | `MemoryItem` data class | Modify: add `tags` field |
| `app/src/main/java/com/example/data/AppDatabase.kt` | Room DB + migrations | Modify: version 29→30, add migration |
| `app/src/main/java/com/example/data/Daos.kt` | `MemoryItemDao` | Modify: add `searchMemoriesByTag()` query |
| `app/src/main/java/com/example/data/Repository.kt` | Data access facade | Modify: expose `searchMemoriesByTag()` |
| `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt` | Memory sync algorithm | Modify: update prompt + `applyMemoryCrudOps()` |
| `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` | `search_memory` tool handler | Modify: add `tag` param, tag-aware filtering |
| `app/src/main/java/com/example/mcp/McpRuntimeManager.kt` | Tool schema registration | Modify: add `tag` property to `search_memory` schema |
| `app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt` | Memory UI | Modify: show tag chips |
| `app/src/test/java/com/example/ui/viewmodel/MemoryTagTest.kt` | Tag parsing tests | Create |

---

### Task 1: Data Model — Add `tags` to `MemoryItem` + Migration

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt:144-156`
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt:60,469-475,509`
- Modify: `app/src/main/java/com/example/data/Daos.kt:77-114`
- Modify: `app/src/main/java/com/example/data/Repository.kt:51-62`

- [ ] **Step 1: Add `tags` field to `MemoryItem`**

In `Entities.kt`, add `tags` field after `lastReinforcedAt`:

```kotlin
@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["confidence"]),
        Index(value = ["updatedAt"])
    ]
)
data class MemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 置信度：每次被 LLM 确认/强化时 +1，初始为 1。越高越稳定。 */
    val confidence: Int = 1,
    /** 最近一次被更新（新增或强化）的时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),
    /** 用户手动锁定：pinned=true 时 LLM 不可删除或覆盖此条目 */
    val pinned: Boolean = false,
    /** 最近一次被强化的时间戳，用于置信度衰减计算 */
    val lastReinforcedAt: Long = System.currentTimeMillis(),
    /** LLM 生成的语义标签，逗号分隔，如 "preference,fact" */
    val tags: String = ""
)
```

- [ ] **Step 2: Add Room migration v29→v30**

In `AppDatabase.kt`, change `version = 29` to `version = 30` (line 60).

Add migration after `MIGRATION_28_29` (after line 475):

```kotlin
/** v29→v30：memory_items 增加 tags 字段，用于 LLM 生成的语义标签 */
private val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory_items ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
    }
}
```

Add `MIGRATION_29_30` to the migration list in `getDatabase()` (after `MIGRATION_28_29`):

```kotlin
MIGRATION_28_29,
MIGRATION_29_30
```

- [ ] **Step 3: Add `searchMemoriesByTag()` to DAO**

In `Daos.kt`, add after `searchMemoriesByKeyword` (after line 86):

```kotlin
@Query("SELECT * FROM memory_items WHERE tags LIKE '%' || :tag || '%' ORDER BY pinned DESC, confidence DESC, updatedAt DESC")
suspend fun searchMemoriesByTag(tag: String): List<MemoryItem>
```

- [ ] **Step 4: Expose in Repository**

In `Repository.kt`, add after `searchMemoriesByKeyword` (after line 54):

```kotlin
suspend fun searchMemoriesByTag(tag: String): List<MemoryItem> = memoryItemDao.searchMemoriesByTag(tag)
```

- [ ] **Step 5: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/data/Entities.kt app/src/main/java/com/example/data/AppDatabase.kt app/src/main/java/com/example/data/Daos.kt app/src/main/java/com/example/data/Repository.kt
git commit -m "feat(memory): add tags field to MemoryItem with v29→v30 migration"
```

---

### Task 2: Tag Parsing — Unit Tests

**Files:**
- Create: `app/src/test/java/com/example/ui/viewmodel/MemoryTagTest.kt`

- [ ] **Step 1: Write tag parsing tests**

```kotlin
package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTagTest {

    private val validTags = setOf("preference", "fact", "instruction", "habit", "context")

    private fun parseTags(tagsArray: org.json.JSONArray?): String {
        if (tagsArray == null) return ""
        val tags = (0 until tagsArray.length())
            .map { tagsArray.optString(it, "").trim().lowercase() }
            .filter { it in validTags }
        return tags.joinToString(",").take(100)
    }

    @Test
    fun `parses valid tags`() {
        val arr = org.json.JSONArray().apply { put("preference"); put("fact") }
        assertEquals("preference,fact", parseTags(arr))
    }

    @Test
    fun `filters invalid tags`() {
        val arr = org.json.JSONArray().apply { put("preference"); put("invalid_tag"); put("fact") }
        assertEquals("preference,fact", parseTags(arr))
    }

    @Test
    fun `returns empty for null`() {
        assertEquals("", parseTags(null))
    }

    @Test
    fun `returns empty for empty array`() {
        assertEquals("", parseTags(org.json.JSONArray()))
    }

    @Test
    fun `handles case insensitivity`() {
        val arr = org.json.JSONArray().apply { put("Preference"); put("FACT") }
        assertEquals("preference,fact", parseTags(arr))
    }

    @Test
    fun `truncates to 100 characters`() {
        val arr = org.json.JSONArray().apply {
            repeat(20) { put("preference") }
        }
        val result = parseTags(arr)
        assert(result.length <= 100)
    }

    @Test
    fun `ignores blank entries`() {
        val arr = org.json.JSONArray().apply { put("preference"); put(""); put("  "); put("fact") }
        assertEquals("preference,fact", parseTags(arr))
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.example.ui.viewmodel.MemoryTagTest"`
Expected: All tests PASS (the parsing logic is standalone, no Android dependencies)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/ui/viewmodel/MemoryTagTest.kt
git commit -m "test(memory): add tag parsing unit tests"
```

---

### Task 3: LLM Integration — Update Prompt + applyMemoryCrudOps

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt:641-665,710-788`

- [ ] **Step 1: Update the Step 2 system prompt to request tags**

In `ChatViewModel.kt`, replace the `factsSystemPrompt` string (lines 641-665) with:

```kotlin
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

Tag vocabulary (assign 1-2 tags per ADD/UPDATE):
  - "preference": user's likes, dislikes, style choices
  - "fact": objective info about the user (job, location, tech stack)
  - "instruction": how the user wants the AI to behave
  - "habit": recurring patterns, routines
  - "context": project-specific or temporal context

Rules:
- ADD new facts not yet captured. IMPORTANT: before adding, check if an existing fact already covers the same information — if so, use REINFORCE or UPDATE instead of ADD. Avoid semantic duplicates.
- UPDATE facts that need revision (do NOT update [PINNED] items).
- REINFORCE facts confirmed again without change (boosts confidence).
- DELETE facts that are clearly contradicted or permanently irrelevant (do NOT delete [PINNED] items).
- If nothing changed, return {"ops": []}.
- Return ONLY the raw JSON object, no markdown fences, no commentary.
""".trimIndent()
```

- [ ] **Step 2: Update `applyMemoryCrudOps()` to handle tags**

In `ChatViewModel.kt`, replace the `applyMemoryCrudOps` method (lines 710-788) with:

```kotlin
private suspend fun applyMemoryCrudOps(
    json: String,
    existingMemories: List<MemoryItem>,
    now: Long
) {
    try {
        // 清理 LLM 可能输出的 markdown 代码块包裹
        val cleaned = json
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val root = org.json.JSONObject(cleaned)
        val ops = root.optJSONArray("ops") ?: return  // ops 缺失 → 放弃

        val existingById = existingMemories.associateBy { it.id }

        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op").uppercase()) {
                "ADD" -> {
                    val content = op.optString("content").trim()
                    if (content.isNotBlank()) {
                        val tags = parseTagsFromJson(op.optJSONArray("tags"))
                        // 本地去重：若现有记忆中已有语义相近的条目（词级 Jaccard ≥ 0.55），
                        // 则改为 REINFORCE 而非重复插入
                        val duplicate = existingMemories.firstOrNull { existing ->
                            jaccardSimilarity(content, existing.content) >= DEDUP_SIMILARITY_THRESHOLD
                        }
                        if (duplicate != null) {
                            repository.reinforceMemory(duplicate.id, duplicate.content, now)
                        } else {
                            repository.insertMemory(
                                MemoryItem(content = content, createdAt = now, updatedAt = now, confidence = 1, tags = tags)
                            )
                        }
                    }
                }
                "UPDATE" -> {
                    val id = op.optLong("id", -1L)
                    val content = op.optString("content").trim()
                    val existing = existingById[id]
                    if (existing == null) {
                        Log.w("ChatViewModel", "Memory UPDATE ignored: id=$id not found")
                    } else if (existing.pinned) {
                        Log.d("ChatViewModel", "Memory UPDATE ignored: id=$id is pinned")
                    } else if (content.isBlank()) {
                        Log.w("ChatViewModel", "Memory UPDATE ignored: id=$id has blank content")
                    } else {
                        val tags = parseTagsFromJson(op.optJSONArray("tags"))
                        repository.updateMemory(
                            existing.copy(content = content, updatedAt = now, confidence = existing.confidence + 1, tags = tags)
                        )
                    }
                }
                "REINFORCE" -> {
                    val id = op.optLong("id", -1L)
                    val existing = existingById[id]
                    if (existing != null) {
                        repository.reinforceMemory(id, existing.content, now)
                    } else {
                        Log.w("ChatViewModel", "Memory REINFORCE ignored: id=$id not found")
                    }
                }
                "DELETE" -> {
                    val id = op.optLong("id", -1L)
                    val existing = existingById[id]
                    if (existing == null) {
                        Log.w("ChatViewModel", "Memory DELETE ignored: id=$id not found")
                    } else if (existing.pinned) {
                        Log.d("ChatViewModel", "Memory DELETE ignored: id=$id is pinned")
                    } else {
                        repository.deleteMemoryById(id)
                    }
                }
            }
        }
    } catch (e: Exception) {
        // JSON 解析失败或任何异常 → 静默忽略，旧记忆完整保留
        e.printStackTrace()
    }
}
```

- [ ] **Step 3: Add `parseTagsFromJson()` helper method**

In `ChatViewModel.kt`, add after `jaccardSimilarity()` (after line 802):

```kotlin
/**
 * 从 LLM 返回的 tags JSON 数组中解析标签。
 * 只保留预定义词汇表中的标签，忽略无效条目，最长 100 字符。
 */
private fun parseTagsFromJson(tagsArray: org.json.JSONArray?): String {
    if (tagsArray == null) return ""
    val validTags = setOf("preference", "fact", "instruction", "habit", "context")
    val tags = (0 until tagsArray.length())
        .map { tagsArray.optString(it, "").trim().lowercase() }
        .filter { it in validTags }
    return tags.joinToString(",").take(100)
}
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(memory): integrate tag assignment into memory sync LLM prompt"
```

---

### Task 4: Search Tool — Add `tag` Parameter + Tag-Aware Filtering

**Files:**
- Modify: `app/src/main/java/com/example/mcp/McpRuntimeManager.kt:566-576`
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt:303-353`

- [ ] **Step 1: Update `search_memory` tool schema**

In `McpRuntimeManager.kt`, replace the `search_memory` McpTool definition (lines 566-576) with:

```kotlin
McpTool(
    serverId = BUILTIN_SERVER_ID,
    serverName = BUILTIN_SERVER_NAME,
    name = "search_memory",
    description = "Search the long-term memory store for entries related to a keyword. Call this tool when you need to recall a specific user preference, habit, or historical detail that is not present in the current context. The system automatically injects the top 30 highest-confidence memories; all other memories must be retrieved proactively via this tool.",
    inputSchema = schema {
        prop("query", "string", "Search keywords; multiple words are supported (space-separated), e.g. \"programming language Kotlin\" or \"dietary preference\". The search performs fuzzy matching against memory content.")
        prop("tag", "string", "Optional tag filter. Valid values: preference, fact, instruction, habit, context. When provided, only memories with this tag are searched.")
        prop("limit", "integer", "Maximum number of results to return. Default 10, max 50.")
        required("query")
    }
),
```

- [ ] **Step 2: Update `handleSearchMemory()` with tag filtering + score boost**

In `BuiltinToolHandler.kt`, replace `handleSearchMemory()` (lines 303-353) with:

```kotlin
private suspend fun handleSearchMemory(context: Context, arguments: JSONObject): JSONObject {
    val query = arguments.optString("query").trim()
    if (query.isBlank()) {
        return errorResponse("搜索失败：query 不能为空。")
    }
    val tagFilter = arguments.optString("tag").trim().lowercase().takeIf { it.isNotBlank() }
    val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
    val repository = getRepository(context)

    // 确定候选集：按 tag 预过滤或全量
    val validTags = setOf("preference", "fact", "instruction", "habit", "context")
    val candidates: List<com.example.data.MemoryItem>
    val totalCount: Int
    if (tagFilter != null && tagFilter in validTags) {
        candidates = repository.searchMemoriesByTag(tagFilter)
        totalCount = candidates.size
    } else {
        // 无 tag 过滤时用 SQL LIKE 缩小候选集
        val keywords = query.split(" ").filter { it.isNotBlank() }
        if (keywords.isNotEmpty()) {
            totalCount = repository.getAllMemories().size
            candidates = keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
        } else {
            candidates = repository.getAllMemories()
            totalCount = candidates.size
        }
    }

    val queryTokens = bigramTokenize(query)

    data class ScoredMemory(val memory: com.example.data.MemoryItem, val score: Double)

    val scored = candidates
        .mapNotNull { mem ->
            val memTokens = bigramTokenize(mem.content)
            val intersection = queryTokens.intersect(memTokens).size
            val union = queryTokens.union(memTokens).size
            if (union == 0 || intersection == 0) return@mapNotNull null
            val jaccard = intersection.toDouble() / union.toDouble()
            var score = jaccard * mem.confidence
            // tag 匹配加成：如果记忆包含查询的 tag，相关度 ×1.2
            if (tagFilter != null && mem.tags.split(",").contains(tagFilter)) {
                score *= 1.2
            }
            ScoredMemory(mem, score)
        }
        .sortedByDescending { it.score }
        .take(limit)

    val filterDesc = if (tagFilter != null) "，标签过滤：$tagFilter" else ""
    val text = buildString {
        appendLine("记忆库搜索结果（关键词：「$query」$filterDesc，共 $totalCount 条记忆，命中 ${scored.size} 条）：")
        appendLine()
        if (scored.isEmpty()) {
            appendLine("未找到与关键词相关的记忆。")
            appendLine("提示：可以尝试更换关键词，或直接浏览全部记忆（记忆库共 $totalCount 条）。")
        } else {
            scored.forEachIndexed { i, sm ->
                val pinnedTag = if (sm.memory.pinned) " [已锁定]" else ""
                val tagsDisplay = if (sm.memory.tags.isNotBlank()) " [${sm.memory.tags}]" else ""
                appendLine("${i + 1}. [id=${sm.memory.id}, 置信度=${sm.memory.confidence}, 相关度=${String.format("%.2f", sm.score)}$pinnedTag$tagsDisplay]")
                appendLine("   ${sm.memory.content}")
            }
        }
    }
    return successResponse(text.trimEnd())
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/McpRuntimeManager.kt app/src/main/java/com/example/mcp/BuiltinToolHandler.kt
git commit -m "feat(memory): add tag filter to search_memory tool with relevance boost"
```

---

### Task 5: UI — Show Tag Chips on Memory Items

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt:185-258`

- [ ] **Step 1: Add tag chips below memory content**

In `MemoryAndPromptScreen.kt`, replace the memory item Card content (lines 186-257). The key change is replacing the single `Text` with a `Column` that has text + tag chips:

Replace the `Row` content inside the Card (lines 205-256) with:

```kotlin
Row(
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.Top
) {
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (memory.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
    )
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = memory.content,
            fontSize = (13 * fs).sp,
            fontFamily = resolvedFontFamily,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (memory.tags.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                memory.tags.split(",").filter { it.isNotBlank() }.forEach { tag ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = tag,
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        },
                        modifier = Modifier.height(20.dp),
                        border = null,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    )
                }
            }
        }
    }
    Column {
        IconButton(
            onClick = { viewModel.togglePinMemory(memory) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (memory.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (memory.pinned)
                    uiText("memory.unpin", "取消锁定")
                else
                    uiText("memory.pin", "锁定"),
                tint = if (memory.pinned)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = { viewModel.deleteMemoryItem(memory.id) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = uiText("memory.63e4284a", "删除"),
                tint = MaterialTheme.colorScheme.error.copy(
                    alpha = if (memory.pinned) 0.3f else 0.7f
                ),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt
git commit -m "feat(memory): display tag chips on memory items in UI"
```

---

### Task 6: Integration Test — Build & Run All Tests

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 2: Final commit if needed**

If any fixes were needed, commit them:

```bash
git add -A
git commit -m "fix(memory): address test failures from tag integration"
```
