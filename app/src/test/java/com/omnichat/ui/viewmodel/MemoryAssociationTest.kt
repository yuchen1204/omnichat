package com.omnichat.ui.viewmodel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryAssociationTest {

    private val validLabels = setOf("related", "causes", "part_of", "contrasts", "belongs_to", "implies")

    /**
     * Simplified version of the association parsing logic from ChatViewModel.applyAssociationsFromJson().
     * Returns list of (from, to, label) triples for valid associations.
     */
    private fun parseAssociations(json: String, validIds: Set<Long>): List<Triple<Long, Long, String>> {
        val cleaned = json.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try { JSONObject(cleaned) } catch (e: Exception) { return emptyList() }
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

    @Test
    fun `handles completely invalid json`() {
        val json = "not json at all"
        val result = parseAssociations(json, setOf(1, 2))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalizes label to lowercase`() {
        val json = """{"associations": [{"from": 1, "to": 2, "label": "CAUSES"}]}"""
        val result = parseAssociations(json, setOf(1, 2))
        assertEquals(1, result.size)
        assertEquals("causes", result[0].third)
    }
}
