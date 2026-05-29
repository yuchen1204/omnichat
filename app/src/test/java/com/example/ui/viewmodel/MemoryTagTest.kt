package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTagTest {

    private val validTags = setOf("preference", "fact", "instruction", "habit", "context")

    private fun parseTagsFromJson(tagsArray: org.json.JSONArray?): String {
        if (tagsArray == null) return ""
        val tags = (0 until tagsArray.length())
            .map { tagsArray.optString(it, "").trim().lowercase() }
            .filter { it in validTags }
        return tags.joinToString(",").take(100)
    }

    @Test
    fun `parses valid tags`() {
        val arr = org.json.JSONArray().apply { put("preference"); put("fact") }
        assertEquals("preference,fact", parseTagsFromJson(arr))
    }

    @Test
    fun `filters invalid tags`() {
        val arr = org.json.JSONArray().apply { put("preference"); put("invalid_tag"); put("fact") }
        assertEquals("preference,fact", parseTagsFromJson(arr))
    }

    @Test
    fun `returns empty for null`() {
        assertEquals("", parseTagsFromJson(null))
    }

    @Test
    fun `returns empty for empty array`() {
        assertEquals("", parseTagsFromJson(org.json.JSONArray()))
    }

    @Test
    fun `handles case insensitivity`() {
        val arr = org.json.JSONArray().apply { put("Preference"); put("FACT") }
        assertEquals("preference,fact", parseTagsFromJson(arr))
    }

    @Test
    fun `truncates to 100 characters`() {
        val arr = org.json.JSONArray().apply {
            repeat(20) { put("preference") }
        }
        val result = parseTagsFromJson(arr)
        assert(result.length <= 100)
    }

    @Test
    fun `ignores blank entries`() {
        val arr = org.json.JSONArray().apply { put("preference"); put(""); put("  "); put("fact") }
        assertEquals("preference,fact", parseTagsFromJson(arr))
    }
}
