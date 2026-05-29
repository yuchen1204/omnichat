# Memory Tagging & Search Optimization Design

Date: 2026-05-29

## Overview

Add an LLM-generated semantic tag system to memories and enhance `search_memory` with tag-aware filtering.

## Approach

**Approve A: Tag as Metadata + Tag-Aware Search**

- Add `tags` column to `memory_items` (comma-separated string)
- LLM assigns 1-2 tags per memory during sync
- `search_memory` gets a `tag` filter parameter
- Tag match provides a relevance scoring boost

## Tag Vocabulary

| Tag | Meaning |
|-----|---------|
| `preference` | User's likes, dislikes, style choices |
| `fact` | Objective info about the user (job, location, tech stack) |
| `instruction` | How the user wants the AI to behave |
| `habit` | Recurring patterns, routines |
| `context` | Project-specific or temporal context |

## Data Model

**`MemoryItem` entity — add one field:**

```kotlin
@ColumnInfo(name = "tags", defaultValue = "")
val tags: String = ""  // comma-separated, e.g. "preference,fact"
```

**Migration:** v29 → v30

```sql
ALTER TABLE memory_items ADD COLUMN tags TEXT NOT NULL DEFAULT ''
```

## LLM Integration

### TriggerMemorySync Step 2 — Updated Prompt

LLM returns tags in CRUD ops:

```json
{
  "ops": [
    {"op": "ADD", "content": "...", "tags": ["preference"]},
    {"op": "UPDATE", "id": 7, "content": "...", "tags": ["fact"]},
    {"op": "REINFORCE", "id": 3},
    {"op": "DELETE", "id": 12}
  ]
}
```

### Rules

- ADD: LLM assigns 1-2 tags from the vocabulary
- UPDATE: LLM can revise tags alongside content
- REINFORCE / DELETE: tags field ignored
- Invalid or missing tags → default to empty string

### `applyMemoryCrudOps()` Changes

- Extract `tags` array from ADD/UPDATE ops
- Join with comma, store in `MemoryItem.tags`
- For UPDATE: overwrite existing tags with new ones

## Search Tool Enhancement

### New Parameter

```
tag: string (optional) — filter memories by tag before text search
```

### Revised Flow in `handleSearchMemory()`

1. If tag filter provided → SQL `WHERE tags LIKE '%preference%'` pre-filter
2. If no tag filter → all memories (current behavior)
3. Bigram Jaccard text matching on filtered set
4. Final score = Jaccard × confidence (unchanged)
5. Tag match bonus: if memory has the queried tag, score × 1.2
6. Return top N with tags included in output

### Output Format

```json
{
  "id": 5,
  "content": "...",
  "confidence": 8,
  "relevance": 0.72,
  "pinned": false,
  "tags": "preference,fact"
}
```

## UI Changes

### Memory List Items — Show Tags as Chips

- Tags displayed as small `AssistChip` / `SuggestionChip` below content text
- Chips use muted color scheme (display-only, not interactive)
- No tag editing UI — tags are managed by the LLM

## Error Handling

- **LLM returns invalid tags**: ignore invalid entries, keep valid ones
- **LLM omits tags field**: store empty string, no crash
- **Tag string too long**: cap at 100 characters
- **Existing memories with `tags = ""`**: search still works, no backfill needed
- **Search with non-existent tag**: returns empty results gracefully

## Files to Modify

| File | Change |
|------|--------|
| `Entities.kt` | Add `tags: String` field to `MemoryItem` |
| `AppDatabase.kt` | Migration v29→v30 |
| `ChatViewModel.kt` | Update Step 2 prompt to request tags, parse in `applyMemoryCrudOps()` |
| `BuiltinToolHandler.kt` | Add `tag` param to `search_memory`, tag-aware filtering + score boost |
| `MemoryAndPromptScreen.kt` | Show tag chips on memory items |

## Scope

~100-150 lines of new code across 5 files. No new screens, no new tables, no breaking changes.
