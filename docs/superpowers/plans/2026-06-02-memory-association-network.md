# Memory Association Network Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a graph-like association network between memories, with LLM auto-creation during sync and BFS traversal in search_memory.

**Architecture:** New `memory_associations` Room table with foreign keys to `memory_items`. Associations are created in two places: (1) alongside existing CRUD ops in `triggerMemorySync()` Step 2, and (2) a new Step 2.5 that backfills associations for unassociated memories. `search_memory` gains a `depth` parameter and performs BFS to expand results through the association graph.

**Tech Stack:** Kotlin, Room, Android ViewModel, org.json

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/java/com/example/data/Entities.kt` | Modify | Add `MemoryAssociation` entity |
| `app/src/main/java/com/example/data/Daos.kt` | Modify | Add `MemoryAssociationDao` |
| `app/src/main/java/com/example/data/AppDatabase.kt` | Modify | Register entity+DAO, migration 34→35 |
| `app/src/main/java/com/example/data/Repository.kt` | Modify | Expose association methods + `RelatedMemoryInfo` data class |
| `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt` | Modify | Step 2 prompt+parse, Step 2.5 cold-start |
| `app/src/main/java/com/example/mcp/McpRuntimeManager.kt` | Modify | Add `depth` param to search_memory schema |
| `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` | Modify | BFS traversal in handleSearchMemory |
| `app/src/main/res/values/strings.xml` | Modify | New association-related strings |
| `app/src/main/res/values-zh-rCN/strings.xml` | Modify | Chinese translations |
| `app/src/test/java/com/example/ui/viewmodel/MemoryAssociationTest.kt` | Create | Unit tests for association logic |

---

### Task 1: Entity + DAO + Database Migration

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt`
- Modify: `app/src/main/java/com/example/data/Daos.kt`
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt`

- [ ] **Step 1: Add `MemoryAssociation` entity to `Entities.kt`**

Add after the `MemoryItem` entity (after line 230):

```kotlin
@Entity(
    tableName = "memory_associations",
    indices = [
        Index(value = ["fromMemoryId"]),
        Index(value = ["toMemoryId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = MemoryItem::class,
            parentColumns = ["id"],
            childColumns = ["fromMemoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MemoryItem::class,
            parentColumns = ["id"],
            childColumns = ["toMemoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MemoryAssociation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromMemoryId: Long,
    val toMemoryId: Long,
    /** LLM-generated relation label: related, causes, part_of, contrasts, belongs_to, implies */
    val relationLabel: String,
    /** "bidirectional" (default) or "directed" */
    val direction: String = "bidirectional",
    val createdAt: Long = System.currentTimeMillis()
)
```

Also add the `ForeignKey` import at the top of `Entities.kt`:
```kotlin
import androidx.room.ForeignKey
```

- [ ] **Step 2: Add `MemoryAssociationDao` to `Daos.kt`**

Add after the `MemoryItemDao` interface (after line 117):

```kotlin
@Dao
interface MemoryAssociationDao {
    @Query("SELECT * FROM memory_associations WHERE fromMemoryId = :memoryId")
    suspend fun getOutgoing(memoryId: Long): List<MemoryAssociation>

    @Query("SELECT * FROM memory_associations WHERE toMemoryId = :memoryId")
    suspend fun getIncoming(memoryId: Long): List<MemoryAssociation>

    @Query("SELECT * FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun getAllForMemory(memoryId: Long): List<MemoryAssociation>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(assoc: MemoryAssociation): Long

    @Query("DELETE FROM memory_associations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun deleteAllForMemory(memoryId: Long)

    @Query("SELECT COUNT(*) FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun countForMemory(memoryId: Long): Int

    @Query("""
        SELECT m.* FROM memory_items m
        LEFT JOIN memory_associations a1 ON m.id = a1.fromMemoryId
        LEFT JOIN memory_associations a2 ON m.id = a2.toMemoryId
        WHERE a1.id IS NULL AND a2.id IS NULL
        ORDER BY m.confidence DESC, m.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getUnassociatedMemories(limit: Int): List<MemoryItem>
}
```

- [ ] **Step 3: Register entity + DAO in `AppDatabase.kt`**

Add `MemoryAssociation::class` to the `entities` array in the `@Database` annotation (after `McpFilePermission::class`):
```kotlin
MemoryAssociation::class,
```

Add the abstract DAO function in `AppDatabase` (after `mcpFilePermissionDao()`):
```kotlin
abstract fun memoryAssociationDao(): MemoryAssociationDao
```

Add migration script before `fun getDatabase()`:
```kotlin
/** v34→v35：新增 memory_associations 表（记忆关联网络） */
private val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_associations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                fromMemoryId INTEGER NOT NULL,
                toMemoryId INTEGER NOT NULL,
                relationLabel TEXT NOT NULL,
                direction TEXT NOT NULL DEFAULT 'bidirectional',
                createdAt INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (fromMemoryId) REFERENCES memory_items(id) ON DELETE CASCADE,
                FOREIGN KEY (toMemoryId) REFERENCES memory_items(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_associations_fromMemoryId ON memory_associations(fromMemoryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_associations_toMemoryId ON memory_associations(toMemoryId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_associations_from_to ON memory_associations(fromMemoryId, toMemoryId)")
    }
}
```

Update `version = 34` to `version = 35` in the `@Database` annotation.

Add `MIGRATION_34_35` to the `.addMigrations(...)` call in `getDatabase()`.

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/data/Entities.kt app/src/main/java/com/example/data/Daos.kt app/src/main/java/com/example/data/AppDatabase.kt
git commit -m "feat(memory): add MemoryAssociation entity, DAO, and migration 34→35"
```

---

### Task 2: Repository Layer

**Files:**
- Modify: `app/src/main/java/com/example/data/Repository.kt`

- [ ] **Step 1: Add `RelatedMemoryInfo` data class**

Add at the top of `Repository.kt` (before the `AppRepository` class):

```kotlin
/**
 * 记忆关联展开结果：包含关联的记忆条目、关系标签和方向。
 * 供 search_memory 的 BFS 遍历使用。
 */
data class RelatedMemoryInfo(
    val memory: MemoryItem,
    val relationLabel: String,
    val direction: String
)
```

- [ ] **Step 2: Add DAO field and repository methods**

Add DAO field in `AppRepository` (after `mcpFilePermissionDao`):
```kotlin
private val memoryAssociationDao = db.memoryAssociationDao()
```

Add methods after the existing Memories section (after `deleteAllMemories()`):

```kotlin
// Memory Associations
suspend fun getRelatedMemories(memoryId: Long): List<RelatedMemoryInfo> {
    val associations = memoryAssociationDao.getAllForMemory(memoryId)
    return associations.mapNotNull { assoc ->
        val relatedId = when {
            assoc.direction == "bidirectional" -> {
                if (assoc.fromMemoryId == memoryId) assoc.toMemoryId else assoc.fromMemoryId
            }
            assoc.fromMemoryId == memoryId -> assoc.toMemoryId
            else -> return@mapNotNull null  // directed edge, wrong direction
        }
        val mem = memoryItemDao.getMemoryById(relatedId) ?: return@mapNotNull null
        RelatedMemoryInfo(mem, assoc.relationLabel, assoc.direction)
    }
}
suspend fun getAssociationsFor(memoryId: Long): List<MemoryAssociation> = memoryAssociationDao.getAllForMemory(memoryId)
suspend fun insertAssociation(assoc: MemoryAssociation): Long = memoryAssociationDao.insert(assoc)
suspend fun deleteAssociation(id: Long) = memoryAssociationDao.deleteById(id)
suspend fun deleteAssociationsForMemory(memoryId: Long) = memoryAssociationDao.deleteAllForMemory(memoryId)
suspend fun getUnassociatedMemories(limit: Int): List<MemoryItem> = memoryAssociationDao.getUnassociatedMemories(limit)
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/data/Repository.kt
git commit -m "feat(memory): add repository methods for memory associations"
```

---

### Task 3: ChatViewModel — Step 2 Association Output

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: Add association constants to companion object**

In the `companion object` (after `MEMORY_INJECT_LIMIT`), add:

```kotlin
private const val COLD_START_ASSOC_LIMIT = 20
private val ASSOC_LABEL_VOCABULARY = setOf("related", "causes", "part_of", "contrasts", "belongs_to", "implies")
```

- [ ] **Step 2: Extend Step 2 system prompt to request associations**

In `triggerMemorySync()`, find the `factsSystemPrompt` string (the one starting with `"You are a User Preference & Persona Synthesizer."`). Append the following block before the closing `""".trimIndent()`:

```
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
```

- [ ] **Step 3: Add `applyAssociationsFromJson` helper method**

Add this method after `applyMemoryCrudOps()` (after line ~803):

```kotlin
/**
 * 解析 associations JSON 数组并存入数据库。
 * 跳过无效的 id、自关联和未知标签。
 */
private suspend fun applyAssociationsFromJson(json: String, validIds: Set<Long>) {
    try {
        val cleaned = json
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val root = org.json.JSONObject(cleaned)
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
        e.printStackTrace()
    }
}
```

- [ ] **Step 4: Call `applyAssociationsFromJson` at end of `applyMemoryCrudOps`**

At the end of `applyMemoryCrudOps()` (before the catch block's closing brace), add:

```kotlin
// Parse and apply associations from the same JSON response
val existingIds = existingMemories.map { it.id }.toSet()
applyAssociationsFromJson(json, existingIds)
```

- [ ] **Step 5: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(memory): extend Step 2 to parse LLM-generated associations"
```

---

### Task 4: ChatViewModel — Step 2.5 Cold-Start Backfill

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: Add Step 2.5 after `applyMemoryCrudOps` call in `triggerMemorySync()`**

Find the line `applyMemoryCrudOps(crudJson, currentMemories, now)` (around line 692). After it, add:

```kotlin
// ── Step 2.5：冷启动补关联 — 为无关联边的记忆生成关联 ──────────────
try {
    val unassociated = repository.getUnassociatedMemories(COLD_START_ASSOC_LIMIT)
    if (unassociated.size >= 2) {
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
        val backfillJson = ApiClient.executeCompletion(memoryConfig, backfillSystemPrompt, backfillQuery)
            ?.trim()

        if (backfillJson != null) {
            applyAssociationsFromJson(backfillJson, unassociated.map { it.id }.toSet())
        }
    }
} catch (e: Exception) {
    e.printStackTrace()
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(memory): add Step 2.5 cold-start association backfill"
```

---

### Task 5: search_memory Tool — Schema Extension

**Files:**
- Modify: `app/src/main/java/com/example/mcp/McpRuntimeManager.kt`

- [ ] **Step 1: Add `depth` parameter to search_memory schema**

Find the `search_memory` tool definition (around line 561-568). Add the `depth` property inside the `schema { }` block, after the `prop("limit", ...)` line:

```kotlin
prop("depth", "integer", "Association traversal depth (1-5). Default 3. When set, the tool traverses the memory association graph to find related memories beyond direct keyword matches.")
```

- [ ] **Step 2: Update search_memory description**

Update the `description` string to mention association expansion:

```kotlin
description = "Search the long-term memory store for entries related to a keyword. Call this tool when you need to recall a specific user preference, habit, or historical detail that is not present in the current context. The system automatically injects the top 30 highest-confidence memories; all other memories must be retrieved proactively via this tool. Results include automatic traversal of the memory association network (controlled by the 'depth' parameter).",
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/mcp/McpRuntimeManager.kt
git commit -m "feat(memory): add depth parameter to search_memory tool schema"
```

---

### Task 6: search_memory Tool — BFS Traversal Implementation

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Add i18n strings to `values/strings.xml`**

Add before the closing `</resources>` tag:

```xml
    <string name="tool_memory_assoc_expansion_header">--- Association Expansion (depth=%d) ---</string>
    <string name="tool_memory_assoc_entry_format">[关联: %s] #%d (confidence=%.2f) %s</string>
```

- [ ] **Step 2: Add i18n strings to `values-zh-rCN/strings.xml`**

Add before the closing `</resources>` tag:

```xml
    <string name="tool_memory_assoc_expansion_header">--- 关联网络展开（深度=%d）---</string>
    <string name="tool_memory_assoc_entry_format">[关联: %s] #%d（置信度=%.2f）%s</string>
```

- [ ] **Step 3: Add BFS traversal logic to `handleSearchMemory()`**

In `handleSearchMemory()`, after the `scored` list is computed and before the `val text = buildString {` block (around line 398), add:

```kotlin
// Association expansion via BFS
val depth = arguments.optInt("depth", 3).coerceIn(1, 5)
val maxExpand = 10
val expandedMemories = mutableListOf<Triple<MemoryItem, String, Int>>()  // memory, label, depth
val visited = scored.map { it.memory.id }.toMutableSet()

data class AssocQueueItem(val memoryId: Long, val currentDepth: Int)
val queue: java.util.LinkedList<AssocQueueItem> = java.util.LinkedList()

for (sm in scored) {
    queue.add(AssocQueueItem(sm.memory.id, 0))
}

while (queue.isNotEmpty() && expandedMemories.size < maxExpand) {
    val item = queue.poll()
    if (item.currentDepth >= depth) continue

    val associations = repository.getAssociationsFor(item.memoryId)
    for (assoc in associations) {
        val relatedId = when {
            assoc.direction == "bidirectional" -> {
                if (assoc.fromMemoryId == item.memoryId) assoc.toMemoryId else assoc.fromMemoryId
            }
            assoc.fromMemoryId == item.memoryId -> assoc.toMemoryId
            else -> continue
        }
        if (relatedId in visited) continue
        visited.add(relatedId)

        val relatedMem = repository.getMemoryById(relatedId) ?: continue
        expandedMemories.add(Triple(relatedMem, assoc.relationLabel, item.currentDepth + 1))
        queue.add(AssocQueueItem(relatedId, item.currentDepth + 1))
    }
}
```

- [ ] **Step 4: Append expanded memories to search output**

In the `val text = buildString {` block, after the existing `scored.forEachIndexed` loop and before the closing `}`, add:

```kotlin
if (expandedMemories.isNotEmpty()) {
    appendLine()
    appendLine(str(context, R.string.tool_memory_assoc_expansion_header, depth))
    expandedMemories.forEachIndexed { i, (mem, label, d) ->
        val pinnedTag = if (mem.pinned) str(context, R.string.tool_memory_pinned_tag) else ""
        appendLine(str(context, R.string.tool_memory_assoc_entry_format, label, mem.id, mem.confidence.toFloat(), mem.content))
    }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(memory): add BFS association traversal to search_memory tool"
```

---

### Task 7: Unit Tests

**Files:**
- Create: `app/src/test/java/com/example/ui/viewmodel/MemoryAssociationTest.kt`

- [ ] **Step 1: Write association parsing tests**

```kotlin
package com.omnichat.ui.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryAssociationTest {

    private val validLabels = setOf("related", "causes", "part_of", "contrasts", "belongs_to", "implies")

    private fun parseAssociations(json: String, validIds: Set<Long>): List<Triple<Long, Long, String>> {
        val cleaned = json.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = JSONObject(cleaned)
        val associations = root.optJSONArray("associations") ?: return emptyList()
        val result = mutableListOf<Triple<Long, Long, String>>()
        for (i in 0 until associations.length()) {
            val assoc = associations.optJSONObject(i) ?: continue
            val from = assoc.optLong("from", -1L)
            val to = assoc.optLong("to", -1L)
            val label = assoc.optString("label", "related").trim().lowercase()
            val direction = assoc.optString("direction", "bidirectional").trim().lowercase()
            if (from !in validIds || to !in validIds || from == to) continue
            if (label !in validLabels) continue
            if (direction !in setOf("bidirectional", "directed")) continue
            result.add(Triple(from, to, label))
        }
        return result
    }

    @Test
    fun `parses valid associations`() {
        val json = """{"associations": [{"from": 1, "to": 2, "label": "related"}, {"from": 3, "to": 4, "label": "causes", "direction": "directed"}]}"""
        val result = parseAssociations(json, setOf(1, 2, 3, 4))
        assertEquals(2, result.size)
        assertEquals(Triple(1L, 2L, "related"), result[0])
        assertEquals(Triple(3L, 4L, "causes"), result[1])
    }

    @Test
    fun `skips self-referencing associations`() {
        val json = """{"associations": [{"from": 1, "to": 1, "label": "related"}]}"""
        val result = parseAssociations(json, setOf(1))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips associations with invalid ids`() {
        val json = """{"associations": [{"from": 1, "to": 99, "label": "related"}]}"""
        val result = parseAssociations(json, setOf(1, 2, 3))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips associations with unknown labels`() {
        val json = """{"associations": [{"from": 1, "to": 2, "label": "invalid_label"}]}"""
        val result = parseAssociations(json, setOf(1, 2))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `defaults to bidirectional direction`() {
        val json = """{"associations": [{"from": 1, "to": 2, "label": "related"}]}"""
        // Parse should succeed (direction defaults to bidirectional which is valid)
        val result = parseAssociations(json, setOf(1, 2))
        assertEquals(1, result.size)
    }

    @Test
    fun `handles missing associations array`() {
        val json = """{"ops": [{"op": "ADD", "content": "test"}]}"""
        val result = parseAssociations(json, setOf(1, 2))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles empty associations array`() {
        val json = """{"associations": []}"""
        val result = parseAssociations(json, setOf(1, 2))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles json wrapped in markdown fences`() {
        val json = "```json\n{\"associations\": [{\"from\": 1, \"to\": 2, \"label\": \"causes\"}]}\n```"
        val result = parseAssociations(json, setOf(1, 2))
        assertEquals(1, result.size)
        assertEquals("causes", result[0].third)
    }

    @Test
    fun `rejects invalid direction values`() {
        val json = """{"associations": [{"from": 1, "to": 2, "label": "related", "direction": "upward"}]}"""
        val result = parseAssociations(json, setOf(1, 2))
        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.omnichat.ui.viewmodel.MemoryAssociationTest"`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/ui/viewmodel/MemoryAssociationTest.kt
git commit -m "test(memory): add unit tests for association parsing logic"
```

---

### Task 8: Final Verification

- [ ] **Step 1: Run full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 2: Run debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify i18n key generation**

Run: `./gradlew generateUiTextKeys`
Expected: No errors, `ui_text_keys.json` updated

- [ ] **Step 4: Final commit if needed**

```bash
git status
# If any generated files changed:
git add -A
git commit -m "chore: update generated files for memory association network"
```
