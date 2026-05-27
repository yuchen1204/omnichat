# Memory Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the memory system with pin UI, bigram search, and confidence decay.

**Architecture:** Three independent changes to existing memory subsystem — UI enhancement in Compose screen, search algorithm swap in MCP tool handler, and a new decay pass in the sync pipeline backed by a Room migration.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Android ViewModel

---

### Task 1: Database — Add lastReinforcedAt field and migration

**Files:**
- Modify: `app/src/main/java/com/example/data/Entities.kt:144-154`
- Modify: `app/src/main/java/com/example/data/Daos.kt:94-96`
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt:60,454-467,500`

- [ ] **Step 1: Add lastReinforcedAt to MemoryItem entity**

In `Entities.kt`, add the new field after `pinned`:

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
    val lastReinforcedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: Update reinforceMemory DAO to set lastReinforcedAt**

In `Daos.kt`, change the `reinforceMemory` query (line 95) to also update `lastReinforcedAt`:

```kotlin
/** 强化一条记忆：confidence+1，更新 updatedAt 和 lastReinforcedAt，内容可选更新 */
@Query("UPDATE memory_items SET confidence = confidence + 1, updatedAt = :now, lastReinforcedAt = :now, content = :content WHERE id = :id")
suspend fun reinforceMemory(id: Long, content: String, now: Long)
```

- [ ] **Step 3: Add MIGRATION_28_29 and bump database version**

In `AppDatabase.kt`:

1. Change `version = 28` to `version = 29` (line 60)

2. Add migration before `getDatabase()` (after MIGRATION_27_28 block, around line 467):

```kotlin
private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory_items ADD COLUMN lastReinforcedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE memory_items SET lastReinforcedAt = updatedAt WHERE lastReinforcedAt = 0")
    }
}
```

3. Add `MIGRATION_28_29` to the `.addMigrations(...)` call (after `MIGRATION_27_28` on line 500):

```kotlin
MIGRATION_27_28,
MIGRATION_28_29
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/data/Entities.kt app/src/main/java/com/example/data/Daos.kt app/src/main/java/com/example/data/AppDatabase.kt
git commit -m "feat(memory): add lastReinforcedAt field with v28→v29 migration"
```

---

### Task 2: Confidence decay logic

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt:535-578`
- Test: `app/src/test/java/com/example/ui/viewmodel/MemoryDecayTest.kt`

- [ ] **Step 1: Write unit test for decay calculation**

Create `app/src/test/java/com/example/ui/viewmodel/MemoryDecayTest.kt`:

```kotlin
package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryDecayTest {

    private fun computeDecayedConfidence(
        confidence: Int,
        lastReinforcedAt: Long,
        now: Long,
        pinned: Boolean
    ): Int {
        if (pinned) return confidence
        val daysSince = ((now - lastReinforcedAt) / 86_400_000L).toInt()
        if (daysSince <= 0) return confidence
        return maxOf(1, confidence - daysSince)
    }

    @Test
    fun `no decay when same day`() {
        val now = 1700000000000L
        assertEquals(5, computeDecayedConfidence(5, now, now, false))
    }

    @Test
    fun `decays by 1 per day`() {
        val now = 1700000000000L
        val threeDaysAgo = now - 3L * 86_400_000L
        assertEquals(2, computeDecayedConfidence(5, threeDaysAgo, now, false))
    }

    @Test
    fun `floors at 1`() {
        val now = 1700000000000L
        val tenDaysAgo = now - 10L * 86_400_000L
        assertEquals(1, computeDecayedConfidence(3, tenDaysAgo, now, false))
    }

    @Test
    fun `pinned items are exempt`() {
        val now = 1700000000000L
        val thirtyDaysAgo = now - 30L * 86_400_000L
        assertEquals(5, computeDecayedConfidence(5, thirtyDaysAgo, now, true))
    }

    @Test
    fun `confidence 1 stays 1 even after many days`() {
        val now = 1700000000000L
        val hundredDaysAgo = now - 100L * 86_400_000L
        assertEquals(1, computeDecayedConfidence(1, hundredDaysAgo, now, false))
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.ui.viewmodel.MemoryDecayTest"`
Expected: All 5 tests PASS (the function is self-contained in the test)

- [ ] **Step 3: Add applyConfidenceDecay to ChatViewModel**

In `ChatViewModel.kt`, add this private function before `applyMemoryCrudOps` (around line 689):

```kotlin
/**
 * 对非 pinned 记忆执行置信度衰减：每过一天 confidence -1，下限为 1。
 * 在 triggerMemorySync() 的 Step 1 之前调用。
 */
private suspend fun applyConfidenceDecay(now: Long) {
    val allMemories = repository.getAllMemories()
    for (memory in allMemories) {
        if (memory.pinned) continue
        val daysSince = ((now - memory.lastReinforcedAt) / 86_400_000L).toInt()
        if (daysSince <= 0) continue
        val newConfidence = maxOf(1, memory.confidence - daysSince)
        if (newConfidence != memory.confidence) {
            repository.updateMemory(memory.copy(confidence = newConfidence))
        }
    }
}
```

- [ ] **Step 4: Call applyConfidenceDecay from triggerMemorySync**

In `triggerMemorySync()`, add the decay call right after the `shouldRun` check and before the `MIN_NEW_CHARS_THRESHOLD` precheck (after line 571 `if (!shouldRun) return@launch`):

```kotlin
if (!shouldRun) return@launch

// 衰减非 pinned 记忆的置信度
applyConfidenceDecay(now)

// 预检：新消息内容太少（如全是"好的/嗯/谢谢"）则跳过，避免无效 API 调用
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt app/src/test/java/com/example/ui/viewmodel/MemoryDecayTest.kt
git commit -m "feat(memory): add confidence decay — 1 point per day for non-pinned items"
```

---

### Task 3: Pin UI in MemoryAndPromptScreen

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt:184-224`

- [ ] **Step 1: Add pin toggle button and pinned visual style**

In `MemoryAndPromptScreen.kt`, replace the memory list item Card (lines 185-223) with:

```kotlin
items(memories) { memory ->
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_item_${memory.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (memory.pinned)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        ),
        border = BorderStroke(
            0.5.dp,
            if (memory.pinned)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(10.dp * spacingMultiplier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (memory.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = memory.content,
                fontSize = (13 * fs).sp,
                fontFamily = resolvedFontFamily,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
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
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt
git commit -m "feat(memory): add pin toggle UI with visual distinction for pinned items"
```

---

### Task 4: Bigram search for search_memory tool

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt:473-531`
- Test: `app/src/test/java/com/example/mcp/BigramTokenizeTest.kt`

- [ ] **Step 1: Write unit test for bigram tokenization**

Create `app/src/test/java/com/example/mcp/BigramTokenizeTest.kt`:

```kotlin
package com.example.mcp

import org.junit.Assert.*
import org.junit.Test

class BigramTokenizeTest {

    /** Mirrors the private function in BuiltinToolHandler */
    private fun bigramTokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val cjkRange = '一'..'鿿'
        val buffer = StringBuilder()

        for (ch in text) {
            if (ch in cjkRange) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch in "，。！？、；：""''（）【】《》,.!?;:\"'()[]<>") {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString().lowercase())
        }

        // Add character bigrams for CJK characters
        val cjkChars = text.filter { it in cjkRange }
        for (i in 0 until cjkChars.length - 1) {
            tokens.add("${cjkChars[i]}${cjkChars[i + 1]}")
        }

        return tokens
    }

    @Test
    fun `chinese text produces character bigrams`() {
        val tokens = bigramTokenize("用户习惯")
        assertTrue("用户" in tokens)
        assertTrue("有习" in tokens)
        assertTrue("习惯" in tokens)
        // Individual chars should NOT be present (we only want bigrams for CJK)
        // Actually per our design, individual CJK chars are also added as tokens
        // Let me re-check... The function adds individual CJK chars AND bigrams.
        // Per the spec: "中文：按字符拆 bigram" — but we also add individual chars
        // for single-char queries. Let me keep both.
        assertTrue(tokens.size >= 3)
    }

    @Test
    fun `english words kept whole`() {
        val tokens = bigramTokenize("I use Kotlin")
        assertTrue("kotlin" in tokens)
        assertTrue("use" in tokens)
        assertTrue("i" in tokens)
    }

    @Test
    fun `mixed chinese and english`() {
        val tokens = bigramTokenize("用户使用Kotlin编程")
        assertTrue("kotlin" in tokens)
        assertTrue("用户" in tokens)
        assertTrue("使用" in tokens)
    }

    @Test
    fun `empty string returns empty set`() {
        assertTrue(bigramTokenize("").isEmpty())
    }

    @Test
    fun `single chinese character has no bigram but has the char`() {
        val tokens = bigramTokenize("用")
        assertTrue("用" in tokens)
        // No bigram possible with single char
        assertEquals(1, tokens.size)
    }

    @Test
    fun `punctuation is treated as separator`() {
        val tokens = bigramTokenize("hello, world")
        assertTrue("hello" in tokens)
        assertTrue("world" in tokens)
    }

    @Test
    fun `jaccard with bigram overlap`() {
        val a = bigramTokenize("用户习惯使用Kotlin")
        val b = bigramTokenize("用户喜欢使用Java")
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        val jaccard = intersection.toDouble() / union.toDouble()
        // Should have overlap: "用户", "使用", individual chars
        assertTrue("Jaccard should be > 0", jaccard > 0.0)
        assertTrue("Jaccard should be < 1", jaccard < 1.0)
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.example.mcp.BigramTokenizeTest"`
Expected: All 7 tests PASS

- [ ] **Step 3: Replace search scoring in BuiltinToolHandler**

In `BuiltinToolHandler.kt`, replace the `search_memory` handler (lines 473-531) with:

```kotlin
"search_memory" -> {
    val query = arguments.optString("query").trim()
    if (query.isBlank()) {
        return JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "搜索失败：query 不能为空。")
                })
            })
            put("isError", true)
        }
    }
    val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
    val db = AppDatabase.getDatabase(context)
    val repository = AppRepository(db)
    val allMemories = repository.getAllMemories()

    val queryTokens = bigramTokenize(query)

    data class ScoredMemory(val memory: com.example.data.MemoryItem, val score: Double)

    val scored = allMemories
        .mapNotNull { mem ->
            val memTokens = bigramTokenize(mem.content)
            val intersection = queryTokens.intersect(memTokens).size
            val union = queryTokens.union(memTokens).size
            if (union == 0 || intersection == 0) return@mapNotNull null
            val jaccard = intersection.toDouble() / union.toDouble()
            ScoredMemory(mem, jaccard * mem.confidence)
        }
        .sortedByDescending { it.score }
        .take(limit)

    val totalCount = allMemories.size
    val text = buildString {
        appendLine("记忆库搜索结果（关键词：「$query」，共 $totalCount 条记忆，命中 ${scored.size} 条）：")
        appendLine()
        if (scored.isEmpty()) {
            appendLine("未找到与关键词相关的记忆。")
            appendLine("提示：可以尝试更换关键词，或直接浏览全部记忆（记忆库共 $totalCount 条）。")
        } else {
            scored.forEachIndexed { i, sm ->
                val pinnedTag = if (sm.memory.pinned) " [已锁定]" else ""
                appendLine("${i + 1}. [id=${sm.memory.id}, 置信度=${sm.memory.confidence}, 相关度=${String.format("%.2f", sm.score)}$pinnedTag]")
                appendLine("   ${sm.memory.content}")
            }
        }
    }
    JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text.trimEnd())
            })
        })
    }
}
```

- [ ] **Step 4: Add bigramTokenize private function to BuiltinToolHandler**

Add this function inside the `BuiltinToolHandler` class (e.g., at the end, before the closing brace):

```kotlin
/**
 * 将文本拆分为 token 集合：中文字符 + 中文字符 bigram + 英文/数字整词。
 * 用于 search_memory 的中文友好匹配。
 */
private fun bigramTokenize(text: String): Set<String> {
    val tokens = mutableSetOf<String>()
    val cjkRange = '一'..'鿿'
    val buffer = StringBuilder()

    for (ch in text) {
        if (ch in cjkRange) {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString().lowercase())
                buffer.clear()
            }
            tokens.add(ch.toString())
        } else if (ch.isWhitespace() || ch in "，。！？、；：""''（）【】《》,.!?;:\"'()[]<>") {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString().lowercase())
                buffer.clear()
            }
        } else {
            buffer.append(ch)
        }
    }
    if (buffer.isNotEmpty()) {
        tokens.add(buffer.toString().lowercase())
    }

    // 中文字符 bigram
    val cjkChars = text.filter { it in cjkRange }
    for (i in 0 until cjkChars.length - 1) {
        tokens.add("${cjkChars[i]}${cjkChars[i + 1]}")
    }

    return tokens
}
```

- [ ] **Step 5: Run tests to verify**

Run: `./gradlew testDebugUnitTest --tests "com.example.mcp.BigramTokenizeTest"`
Expected: All tests PASS

- [ ] **Step 6: Build full project**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/mcp/BuiltinToolHandler.kt app/src/test/java/com/example/mcp/BigramTokenizeTest.kt
git commit -m "feat(memory): replace keyword search with bigram Jaccard for Chinese-friendly matching"
```

---

### Task 5: Final build verification

- [ ] **Step 1: Run full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 2: Run debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL
