# Memory Association Network Design

**Date**: 2026-06-02
**Status**: Approved

## Overview

Add a memory association network to OmniChat's existing memory system. Memories can be linked to each other with typed, optionally directed edges. When `search_memory` returns a result, the tool automatically traverses the association graph to surface related memories. Associations are created automatically by the LLM during memory sync, with a cold-start backfill step to ensure old memories get linked.

## Data Layer

### New Entity: `MemoryAssociation`

```kotlin
@Entity(
    tableName = "memory_associations",
    indices = [
        Index(value = ["fromMemoryId"]),
        Index(value = ["toMemoryId"]),
        Index(value = ["fromMemoryId", "toMemoryId"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(entity = MemoryItem::class, parentColumns = ["id"], childColumns = ["fromMemoryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MemoryItem::class, parentColumns = ["id"], childColumns = ["toMemoryId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class MemoryAssociation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromMemoryId: Long,
    val toMemoryId: Long,
    val relationLabel: String,          // LLM-generated: related, causes, part_of, contrasts, belongs_to, implies
    val direction: String = "bidirectional",  // "bidirectional" | "directed"
    val createdAt: Long = System.currentTimeMillis()
)
```

- **CASCADE**: deleting a `MemoryItem` automatically removes its edges
- **Unique constraint**: `(fromMemoryId, toMemoryId)` prevents duplicate edges
- DB version: 34 → 35, with `MIGRATION_34_35`

### DAO: `MemoryAssociationDao`

```kotlin
@Dao
interface MemoryAssociationDao {
    // Get all outgoing edges from a memory
    @Query("SELECT * FROM memory_associations WHERE fromMemoryId = :memoryId")
    suspend fun getOutgoing(memoryId: Long): List<MemoryAssociation>

    // Get all incoming edges to a memory
    @Query("SELECT * FROM memory_associations WHERE toMemoryId = :memoryId")
    suspend fun getIncoming(memoryId: Long): List<MemoryAssociation>

    // Get all edges involving a memory (both directions)
    @Query("""
        SELECT * FROM memory_associations
        WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId
    """)
    suspend fun getAllForMemory(memoryId: Long): List<MemoryAssociation>

    // Get all edges involving a memory (both directions) with label info
    @Query("""
        SELECT * FROM memory_associations
        WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId
    """)
    suspend fun getAllForMemory(memoryId: Long): List<MemoryAssociation>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssociation(assoc: MemoryAssociation): Long

    @Query("DELETE FROM memory_associations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_associations WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId")
    suspend fun deleteAllForMemory(memoryId: Long)

    // Count associations for a memory (used by cold-start detection)
    @Query("""
        SELECT COUNT(*) FROM memory_associations
        WHERE fromMemoryId = :memoryId OR toMemoryId = :memoryId
    """)
    suspend fun countForMemory(memoryId: Long): Int

    // Find memories with zero associations (cold-start candidates)
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

Repository exposes `getRelatedMemories(memoryId)` which internally:
1. Calls `getAllForMemory(memoryId)` to get associations
2. Filters directed edges (only follow `from→to` direction for `directed` edges; follow both for `bidirectional`)
3. Fetches the related `MemoryItem` by ID
4. Returns `List<RelatedMemoryInfo>` with the memory and its relation label

### Repository

`AppRepository` exposes:
- `getRelatedMemories(memoryId): List<RelatedMemoryInfo>` — resolves direction and returns memories with labels
- `getAssociationsFor(memoryId): List<MemoryAssociation>`
- `insertAssociation(assoc)`
- `deleteAssociation(id)`
- `getUnassociatedMemories(limit): List<MemoryItem>`

```kotlin
data class RelatedMemoryInfo(
    val memory: MemoryItem,
    val relationLabel: String,
    val direction: String
)
```

## Memory Sync: Step 2 Extension

### LLM Output Format Change

The `associations` array is added alongside `ops` in the Step 2 response:

```json
{
  "ops": [
    {"op": "ADD", "content": "...", "tags": ["preference"]},
    {"op": "REINFORCE", "id": 3}
  ],
  "associations": [
    {"from": 3, "to": 7, "label": "related"},
    {"from": 5, "to": 12, "label": "causes", "direction": "directed"},
    {"from": 8, "to": 9, "label": "contrasts"}
  ]
}
```

- `from`/`to`: existing memory IDs
- `label`: one of `related`, `causes`, `part_of`, `contrasts`, `belongs_to`, `implies`
- `direction`: optional, defaults to `"bidirectional"`. `"directed"` means the edge is one-way (from → to)

### System Prompt Addition

Append to the existing `factsSystemPrompt` in Step 2:

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

### Client-Side Parsing

In `applyMemoryCrudOps()`, after processing all ops:

```kotlin
val associations = root.optJSONArray("associations")
if (associations != null) {
    val validIds = existingMemories.map { it.id }.toSet()
    for (i in 0 until associations.length()) {
        val assoc = associations.optJSONObject(i) ?: continue
        val from = assoc.optLong("from", -1)
        val to = assoc.optLong("to", -1)
        val label = assoc.optString("label", "related").trim().lowercase()
        val direction = assoc.optString("direction", "bidirectional").trim().lowercase()

        if (from !in validIds || to !in validIds || from == to) continue
        if (label !in validLabels) continue
        if (direction !in setOf("bidirectional", "directed")) continue

        repository.insertAssociation(
            MemoryAssociation(fromMemoryId = from, toMemoryId = to, relationLabel = label, direction = direction)
        )
    }
}
```

## Cold-Start Backfill: Step 2.5

After Step 2 completes, check for memories without associations:

```kotlin
// Step 2.5: Cold-start association backfill
val unassociated = repository.getUnassociatedMemories(limit = 20)
if (unassociated.size >= 2) {
    val candidatesFormatted = unassociated.joinToString("\n") {
        "${it.id}. (confidence=${it.confidence}) ${it.content}"
    }
    val backfillPrompt = """
You are a memory graph builder. Given a list of facts, identify meaningful connections between them.
Output a JSON object with an "associations" array:
  {"from": <id>, "to": <id>, "label": "<label>"}
Label vocabulary: related, causes, part_of, contrasts, belongs_to, implies.
Only link facts with genuine semantic connections. If none qualify, return {"associations": []}.
Return ONLY the raw JSON object.
""".trimIndent()

    val backfillQuery = "Facts:\n###\n$candidatesFormatted\n###\n\nOutput associations JSON now."
    val backfillJson = ApiClient.executeCompletion(memoryConfig, backfillPrompt, backfillQuery)
        ?.trim()

    if (backfillJson != null) {
        applyAssociationsFromJson(backfillJson, unassociated.map { it.id }.toSet())
    }
}
```

- Only runs when there are 2+ unassociated memories
- Processes up to 20 memories per sync
- Uses the same `applyAssociationsFromJson()` helper as Step 2

## search_memory Tool Extension

### New Parameter

Add `depth` parameter to the `search_memory` tool schema:

```json
{
  "name": "depth",
  "type": "integer",
  "description": "Association traversal depth (1-5). Default 3. Higher values retrieve more related memories.",
  "minimum": 1,
  "maximum": 5
}
```

### Traversal Logic

After the existing search scoring returns top results:

```kotlin
// Association expansion
val depth = arguments.optInt("depth", 3).coerceIn(1, 5)
val expandedMemories = mutableListOf<ExpandedMemory>()
val visited = scored.map { it.memory.id }.toMutableSet()
val maxExpand = 10

// BFS traversal
data class QueueItem(val memoryId: Long, val currentDepth: Int)
val queue: Queue<QueueItem> = LinkedList()

// Seed with search results
for (sm in scored) {
    queue.add(QueueItem(sm.memory.id, 0))
}

while (queue.isNotEmpty() && expandedMemories.size < maxExpand) {
    val item = queue.poll()
    if (item.currentDepth >= depth) continue

    val related = repository.getRelatedMemories(item.memoryId)
    for (rel in related) {
        if (rel.memory.id in visited) continue
        visited.add(rel.memory.id)
        expandedMemories.add(ExpandedMemory(rel.memory, rel.relationLabel, item.currentDepth + 1))
        queue.add(QueueItem(rel.memory.id, item.currentDepth + 1))
    }
}
```

### Output Format

Search results section stays as-is. Expanded memories are appended:

```
--- Association Expansion (depth={depth}) ---
[关联: causes] #15 (confidence=3) User prefers async/await over callbacks
   → related to #7
[关联: part_of] #22 (confidence=2) User uses OkHttp for networking
   → related to #5
```

- No scoring for expanded memories (they're relevance-anchored to the search results)
- Depth shown in output header
- Each expanded memory shows its relation label and source

## File Changes Summary

| File | Change |
|------|--------|
| `data/Entities.kt` | Add `MemoryAssociation` entity |
| `data/Daos.kt` | Add `MemoryAssociationDao` |
| `data/AppDatabase.kt` | Register entity + DAO, version 35, MIGRATION_34_35 |
| `data/Repository.kt` | Expose association methods |
| `ui/viewmodel/ChatViewModel.kt` | Step 2 prompt expansion, parse `associations` array, Step 2.5 cold-start backfill |
| `mcp/BuiltinToolHandler.kt` | `handleSearchMemory()` add `depth` parameter, BFS traversal, expanded output |
| `res/values/strings.xml` | New strings for association-related UI text |
| `res/values-zh-rCN/strings.xml` | Chinese translations |

## Constants

```kotlin
// In ChatViewModel
companion object {
    const val COLD_START_ASSOC_LIMIT = 20       // max unassociated memories per sync
    const val ASSOC_LABEL_VOCABULARY = setOf("related", "causes", "part_of", "contrasts", "belongs_to", "implies")
}

// In BuiltinToolHandler
companion object {
    const val ASSOC_MAX_DEPTH = 5               // max traversal depth
    const val ASSOC_DEFAULT_DEPTH = 3           // default depth
    const val ASSOC_MAX_EXPAND = 10             // max expanded memories
}
```
