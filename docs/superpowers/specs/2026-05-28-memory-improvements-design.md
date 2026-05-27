# Memory Architecture Improvements

Date: 2026-05-28

## Summary

Three improvements to the existing dual-layer memory system:
1. Wire up pin toggle UI in MemoryAndPromptScreen
2. Replace keyword-contains search with bigram Jaccard for Chinese-friendly matching
3. Add confidence decay mechanism to prevent stale memories from staying high-ranked

## 1. Pin UI

**File:** `app/src/main/java/com/example/ui/screens/MemoryAndPromptScreen.kt`

The ViewModel method `togglePinMemory()` (line 988) and DAO method `setPinned()` already exist. The UI is missing the toggle.

**Changes:**
- In the memory list item Row (line 193), add a pin IconButton before the delete IconButton
- Icon: `Icons.Filled.PushPin` when pinned, `Icons.Outlined.PushPin` when not pinned
- Tint: `MaterialTheme.colorScheme.primary` when pinned, `onSurfaceVariant` when not
- Click handler: `viewModel.togglePinMemory(memory)`
- Pinned items get a subtle background tint: `primaryContainer.copy(alpha = 0.15f)` on the Card
- Delete button stays visible (pinned deletion is blocked at DAO level with `WHERE pinned = 0`, giving user feedback via no-op)

## 2. Bigram Search

**File:** `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` (search_memory handler, line 473)

Replace `keywords.count { kw -> lower.contains(kw) }` with bigram Jaccard scoring.

**Algorithm:**
1. Tokenize both query and memory content into a set of tokens:
   - Chinese characters (Unicode range 一-鿿): extract character bigrams ("用户习惯" -> {"用户", "有习", "习惯"})
   - English/numbers/other: split on whitespace and punctuation, keep as whole words lowercased
2. Compute Jaccard similarity: `|intersection| / |union|`
3. Final score: `jaccardScore * confidence`
4. Filter: `score > 0` (any overlap)
5. Sort descending by score, take top `limit`

**Why bigrams:** Chinese has no spaces between words. Character bigrams are a zero-dependency way to approximate word-level segmentation. English words are kept whole since spaces already delimit them.

**Helper function:** `bigramTokenize(text: String): Set<String>` - new private function in BuiltinToolHandler.

## 3. Confidence Decay

### Entity Change

**File:** `app/src/main/java/com/example/data/Entities.kt` (MemoryItem, line 137)

Add field:
```kotlin
val lastReinforcedAt: Long = System.currentTimeMillis()
```

### Room Migration

**File:** `app/src/main/java/com/example/data/AppDatabase.kt`

- Bump version from 28 to 29
- Add migration MIGRATION_28_29:
  - `ALTER TABLE memory_items ADD COLUMN lastReinforcedAt INTEGER NOT NULL DEFAULT 0`
  - `UPDATE memory_items SET lastReinforcedAt = updatedAt WHERE lastReinforcedAt = 0`

### DAO Update

**File:** `app/src/main/java/com/example/data/Daos.kt`

- Update `reinforceMemory` query to also set `lastReinforcedAt = :now`:
  ```sql
  UPDATE memory_items SET confidence = confidence + 1, updatedAt = :now, lastReinforcedAt = :now, content = :content WHERE id = :id
  ```

### Decay Logic

**File:** `app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt`

New private function `applyConfidenceDecay(now: Long)`:
1. Fetch all memories via `repository.getAllMemories()`
2. For each non-pinned memory:
   - `daysSince = (now - memory.lastReinforcedAt) / 86_400_000`
   - If `daysSince > 0`:
     - `newConfidence = max(1, memory.confidence - daysSince)`
     - If `newConfidence != memory.confidence`: update via `repository.updateMemory(memory.copy(confidence = newConfidence))`
3. Memories where confidence would drop to 0 are set to 1 (floor). Deletion is not automatic — the LLM DELETE path handles removal of truly irrelevant memories.

**Trigger:** Called at the start of `triggerMemorySync()`, before Step 1 (session summary generation).

**Why floor at 1 instead of delete:** Automatic deletion risks removing memories the user hasn't seen yet. Confidence = 1 means the memory ranks last in injection order and can be naturally pruned by the LLM via DELETE ops or manually by the user.

**Why pinned items are exempt:** Pinned = user-confirmed important. Time should not erode explicit user intent.

## Files Changed Summary

| File | Change |
|------|--------|
| `MemoryAndPromptScreen.kt` | Add pin toggle button + pinned visual style |
| `BuiltinToolHandler.kt` | Replace contains-search with bigram Jaccard |
| `Entities.kt` | Add `lastReinforcedAt` field to MemoryItem |
| `Daos.kt` | Update `reinforceMemory` query to set `lastReinforcedAt` |
| `AppDatabase.kt` | Version 29 + MIGRATION_28_29 |
| `ChatViewModel.kt` | Add `applyConfidenceDecay()`, call from `triggerMemorySync()` |
| `Repository.kt` | No changes needed (uses existing `updateMemory`) |
