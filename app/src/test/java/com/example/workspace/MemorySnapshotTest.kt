package com.example.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MemorySnapshotTest {

    @Test
    fun testMemoryScopeEnum() {
        assertEquals(3, MemorySnapshotManager.MemoryScope.values().size)
        assertTrue(MemorySnapshotManager.MemoryScope.values().contains(MemorySnapshotManager.MemoryScope.USER))
        assertTrue(MemorySnapshotManager.MemoryScope.values().contains(MemorySnapshotManager.MemoryScope.PROJECT))
        assertTrue(MemorySnapshotManager.MemoryScope.values().contains(MemorySnapshotManager.MemoryScope.LOCAL))
    }

    @Test
    fun testSnapshotCheckResultEnum() {
        assertEquals(4, MemorySnapshotManager.SnapshotCheckResult.values().size)
        assertTrue(MemorySnapshotManager.SnapshotCheckResult.values().contains(MemorySnapshotManager.SnapshotCheckResult.NO_PROJECT_SNAPSHOT))
        assertTrue(MemorySnapshotManager.SnapshotCheckResult.values().contains(MemorySnapshotManager.SnapshotCheckResult.INITIALIZE_FROM_PROJECT))
        assertTrue(MemorySnapshotManager.SnapshotCheckResult.values().contains(MemorySnapshotManager.SnapshotCheckResult.PROJECT_NEWER))
        assertTrue(MemorySnapshotManager.SnapshotCheckResult.values().contains(MemorySnapshotManager.SnapshotCheckResult.USER_CURRENT))
    }

    @Test
    fun testGetMemoryFilePathUserScope() {
        val file = MemorySnapshotManager.getMemoryFilePath("test-agent", MemorySnapshotManager.MemoryScope.USER, null)
        assertNotNull(file)
        assertTrue(file!!.absolutePath.contains(".claude"))
        assertTrue(file.absolutePath.contains("agents"))
        assertTrue(file.absolutePath.contains("test-agent"))
        assertEquals("memory.json", file.name)
    }

    @Test
    fun testGetMemoryFilePathProjectScope() {
        val sandboxPath = "/tmp/test_project"
        val file = MemorySnapshotManager.getMemoryFilePath("test-agent", MemorySnapshotManager.MemoryScope.PROJECT, sandboxPath)
        assertNotNull(file)
        assertTrue(file!!.absolutePath.contains(sandboxPath))
        assertTrue(file.absolutePath.contains(".claude"))
        assertTrue(file.absolutePath.contains("agents"))
        assertTrue(file.absolutePath.contains("test-agent"))
        assertEquals("memory.json", file.name)
    }

    @Test
    fun testGetMemoryFilePathProjectScopeNullSandbox() {
        val file = MemorySnapshotManager.getMemoryFilePath("test-agent", MemorySnapshotManager.MemoryScope.PROJECT, null)
        assertNull(file)
    }

    @Test
    fun testGetMemoryFilePathLocalScope() {
        val file = MemorySnapshotManager.getMemoryFilePath("test-agent", MemorySnapshotManager.MemoryScope.LOCAL, "/tmp")
        assertNull(file)
    }

    @Test
    fun testSaveAndLoadSnapshot() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "memory_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val agentType = "test-agent-${System.currentTimeMillis()}"
        val content = """{"memories": ["fact1", "fact2"]}"""

        // Save
        val saved = MemorySnapshotManager.saveSnapshot(
            agentType = agentType,
            scope = MemorySnapshotManager.MemoryScope.PROJECT,
            sandboxPath = tempDir.absolutePath,
            content = content,
        )
        assertTrue(saved)

        // Load
        val loaded = MemorySnapshotManager.loadSnapshot(
            agentType = agentType,
            scope = MemorySnapshotManager.MemoryScope.PROJECT,
            sandboxPath = tempDir.absolutePath,
        )
        assertNotNull(loaded)
        assertEquals(content, loaded)

        // Cleanup
        tempDir.deleteRecursively()
    }

    @Test
    fun testLoadNonexistentSnapshot() {
        val loaded = MemorySnapshotManager.loadSnapshot(
            agentType = "nonexistent-agent",
            scope = MemorySnapshotManager.MemoryScope.USER,
            sandboxPath = null,
        )
        assertNull(loaded)
    }

    @Test
    fun testSaveLocalScopeReturnsFalse() {
        val saved = MemorySnapshotManager.saveSnapshot(
            agentType = "test-agent",
            scope = MemorySnapshotManager.MemoryScope.LOCAL,
            sandboxPath = "/tmp",
            content = "test content",
        )
        assertFalse(saved)
    }

    @Test
    fun testCheckSnapshotUpdateNoProject() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "no_project_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val result = MemorySnapshotManager.checkSnapshotUpdate(
            agentType = "nonexistent-agent",
            sandboxPath = tempDir.absolutePath,
        )
        assertEquals(MemorySnapshotManager.SnapshotCheckResult.NO_PROJECT_SNAPSHOT, result)

        tempDir.deleteRecursively()
    }

    @Test
    fun testInitializeFromProject() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "init_project_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val agentType = "init-agent-${System.currentTimeMillis()}"
        val projectContent = """{"memories": ["project fact"]}"""

        // Create project snapshot
        MemorySnapshotManager.saveSnapshot(
            agentType = agentType,
            scope = MemorySnapshotManager.MemoryScope.PROJECT,
            sandboxPath = tempDir.absolutePath,
            content = projectContent,
        )

        // Initialize user from project
        val initialized = MemorySnapshotManager.initializeFromProject(
            agentType = agentType,
            sandboxPath = tempDir.absolutePath,
        )
        assertTrue(initialized)

        // Verify user snapshot now has project content
        val userContent = MemorySnapshotManager.loadSnapshot(
            agentType = agentType,
            scope = MemorySnapshotManager.MemoryScope.USER,
            sandboxPath = null,
        )
        assertNotNull(userContent)
        assertEquals(projectContent, userContent)

        tempDir.deleteRecursively()
    }
}