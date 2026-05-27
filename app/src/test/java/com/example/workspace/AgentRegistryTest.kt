package com.example.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AgentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentRegistry unit tests.
 *
 * Uses Robolectric to test JSON parsing, tool filtering, and preset loading logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRegistryTest {
    private lateinit var context: Context
    private lateinit var registry: AgentRegistry

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        registry = AgentRegistry(context)
    }

    @Test
    fun `parseDefinitionJson parses valid JSON`() {
        // Parsing complete JSON should return correct AgentDefinition
        val json = """
            {
                "name": "test_agent",
                "displayName": "Test Agent",
                "systemPrompt": "You are helpful",
                "modelHint": "FAST",
                "allowedTools": ["read_file", "write_file"],
                "disallowedTools": ["delete_file"],
                "isOrchestrator": false,
                "maxToolIterations": 25,
                "description": "A test agent"
            }
        """.trimIndent()
        val def = registry.parseDefinitionJson(json)
        assertNotNull(def)
        assertEquals("test_agent", def!!.name)
        assertEquals("Test Agent", def.displayName)
        assertEquals("You are helpful", def.systemPrompt)
        assertEquals(ModelHint.FAST, def.modelHint)
        assertEquals(listOf("read_file", "write_file"), def.allowedTools)
        assertEquals(listOf("delete_file"), def.disallowedTools)
        assertFalse(def.isOrchestrator)
        assertEquals(25, def.maxToolIterations)
        assertEquals("A test agent", def.description)
    }

    @Test
    fun `parseDefinitionJson returns null for missing name`() {
        // Missing name field should return null
        val json = """{"displayName": "No Name"}"""
        val def = registry.parseDefinitionJson(json)
        assertNull(def)
    }

    @Test
    fun `parseDefinitionJson handles blank name`() {
        // Blank name should return null
        val json = """{"name": "   ", "displayName": "Blank"}"""
        val def = registry.parseDefinitionJson(json)
        assertNull(def)
    }

    @Test
    fun `filterTools with allowedTools acts as whitelist`() {
        // Whitelist mode: only retain tools in whitelist
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            allowedTools = listOf("read_file", "write_file")
        )
        val allTools = setOf("read_file", "write_file", "delete_file", "execute_command")
        val filtered = def.filterTools(allTools)
        assertEquals(setOf("read_file", "write_file"), filtered)
    }

    @Test
    fun `filterTools with disallowedTools acts as blacklist`() {
        // Blacklist mode: remove tools in blacklist
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            disallowedTools = listOf("delete_file")
        )
        val allTools = setOf("read_file", "write_file", "delete_file", "execute_command")
        val filtered = def.filterTools(allTools)
        assertEquals(setOf("read_file", "write_file", "execute_command"), filtered)
    }

    @Test
    fun `filterTools with null allows all`() {
        // When both whitelist and blacklist are null, retain all tools
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = ""
        )
        val allTools = setOf("read_file", "write_file", "delete_file")
        val filtered = def.filterTools(allTools)
        assertEquals(allTools, filtered)
    }

    @Test
    fun `isToolAllowed respects allowedTools`() {
        // Whitelist check: tools not in whitelist should be rejected
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            allowedTools = listOf("read_file", "write_file")
        )
        assertTrue(def.isToolAllowed("read_file"))
        assertTrue(def.isToolAllowed("write_file"))
        assertFalse(def.isToolAllowed("delete_file"))
    }

    @Test
    fun `isToolAllowed respects disallowedTools`() {
        // Blacklist check: tools in blacklist should be rejected
        val def = AgentDefinition(
            name = "test",
            displayName = "Test",
            systemPrompt = "",
            disallowedTools = listOf("delete_file")
        )
        assertTrue(def.isToolAllowed("read_file"))
        assertTrue(def.isToolAllowed("write_file"))
        assertFalse(def.isToolAllowed("delete_file"))
    }

    @Test
    fun `loadFromPresets adds presets without overriding existing`() {
        // Preset loading should not override existing definitions with same name
        val existingDef = AgentDefinition(
            name = "existing",
            displayName = "Existing From File",
            systemPrompt = "From file",
            description = "File definition"
        )
        registry.definitions[existingDef.name] = existingDef
        val presets = listOf(
            AgentPreset(
                id = 1,
                name = "existing",
                systemPrompt = "From preset",
                description = "Preset definition"
            ),
            AgentPreset(
                id = 2,
                name = "new_preset",
                systemPrompt = "New agent",
                description = "New from preset"
            )
        )
        registry.loadFromPresets(presets)
        // Existing definition should not be overridden
        assertEquals("Existing From File", registry.get("existing")?.displayName)
        assertEquals("From file", registry.get("existing")?.systemPrompt)
        // New preset should be added
        assertNotNull(registry.get("new_preset"))
        assertEquals("new_preset", registry.get("new_preset")?.displayName)
    }
}
