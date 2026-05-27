package com.example.workspace

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ScratchpadTest {

    private lateinit var tempDir: File
    private lateinit var scratchpad: Scratchpad

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "scratchpad_test_${System.nanoTime()}")
        tempDir.mkdirs()
        scratchpad = Scratchpad(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `write and read works correctly`() {
        scratchpad.write("agent1", "notes", "hello world")
        assertEquals("hello world", scratchpad.read("agent1", "notes"))
    }

    @Test
    fun `read returns null for non-existent key`() {
        assertNull(scratchpad.read("agent1", "missing"))
    }

    @Test
    fun `write overwrites existing content`() {
        scratchpad.write("agent1", "notes", "first")
        scratchpad.write("agent1", "notes", "second")
        assertEquals("second", scratchpad.read("agent1", "notes"))
    }

    @Test
    fun `list returns all entries`() {
        scratchpad.write("agent1", "notes", "a")
        scratchpad.write("agent2", "tasks", "b")
        val entries = scratchpad.list()
        assertEquals(2, entries.size)
        val keys = entries.map { it.key }.toSet()
        assertTrue(keys.containsAll(setOf("notes", "tasks")))
    }

    @Test
    fun `cleanup removes only specified agent data`() {
        scratchpad.write("agent1", "notes", "a")
        scratchpad.write("agent2", "tasks", "b")
        scratchpad.cleanup("agent1")
        assertNull(scratchpad.read("agent1", "notes"))
        assertEquals("b", scratchpad.read("agent2", "tasks"))
    }

    @Test
    fun `clearAll removes everything`() {
        scratchpad.write("agent1", "notes", "a")
        scratchpad.write("agent2", "tasks", "b")
        scratchpad.clearAll()
        assertTrue(scratchpad.list().isEmpty())
    }

    @Test
    fun `special characters in key are sanitized`() {
        scratchpad.write("agent1", "my-key/with.special@chars", "content")
        val result = scratchpad.read("agent1", "my-key/with.special@chars")
        assertEquals("content", result)
        val entries = scratchpad.list()
        assertEquals(1, entries.size)
        assertEquals("my_key_with_special_chars", entries[0].key)
    }
}
