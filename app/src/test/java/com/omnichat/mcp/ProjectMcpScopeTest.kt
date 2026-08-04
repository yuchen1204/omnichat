package com.omnichat.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.McpServer
import com.omnichat.data.Project
import com.omnichat.data.Session
import com.omnichat.tool.McpRemoteTool
import com.omnichat.tool.ProjectToolScope
import com.omnichat.tool.ToolExecutor
import com.omnichat.tool.ToolInitializer
import com.omnichat.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for project-level MCP server inheritance and filtering.
 *
 * Contract:
 * - A project's effective MCP server set is the intersection of:
 *     (a) servers that are globally enabled (mcp_servers.isEnabled = 1)
 *     (b) servers that the project has NOT explicitly disabled
 *   (Project.disabledMcpServerIds is subtracted.)
 * - Globally disabled servers cannot be re-enabled by a project — they are
 *   never part of the intersection.
 * - The effective set is recomputed dynamically each time it is queried so
 *   that global state changes take effect immediately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectMcpScopeTest {

    private lateinit var context: Context
    private lateinit var repository: AppRepository
    private lateinit var db: AppDatabase
    private lateinit var runtimeManager: McpRuntimeManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(context)
        repository = AppRepository(db)
        runtimeManager = McpRuntimeManager.getInstance(context)

        // The AppDatabase is a process-wide singleton; clear the tables we
        // touch so tests are independent regardless of execution order.
        // Robolectric runs tests on the main thread, so dispatch the clear
        // on a background dispatcher to satisfy Room's main-thread check.
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
        }

        // Reset ToolRegistry and re-initialize builtins before each test.
        ToolInitializer.reset()
        ToolInitializer.initialize(context)
    }

    @After
    fun tearDown() {
        // Best-effort cleanup; failures here are non-fatal for test correctness.
        try { ToolInitializer.reset() } catch (_: Throwable) {}
    }

    // ═════════════════════════════════════════════════════════════════
    // 1. Repository: getProjectDisabledMcpServerIds / setProjectDisabledMcpServerIds
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun repositoryReturnsEmptySetForNewProject() = runBlocking {
        val projectId = repository.insertProject(Project(name = "Empty"))
        val ids = repository.getProjectDisabledMcpServerIds(projectId)
        assertEquals(emptySet<Long>(), ids)
    }

    @Test
    fun repositoryPersistsDisabledServerIds() = runBlocking {
        val projectId = repository.insertProject(Project(name = "With Disabled"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(11L, 22L, 33L))

        val read = repository.getProjectDisabledMcpServerIds(projectId)
        assertEquals(setOf(11L, 22L, 33L), read)
    }

    @Test
    fun repositoryOverwritesDisabledServerIds() = runBlocking {
        val projectId = repository.insertProject(Project(name = "Overwrite"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(11L))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(99L, 100L))

        assertEquals(setOf(99L, 100L), repository.getProjectDisabledMcpServerIds(projectId))
    }

    @Test
    fun repositoryClearsDisabledServerIdsWithEmptySet() = runBlocking {
        val projectId = repository.insertProject(Project(name = "Clear"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(11L, 22L))
        repository.setProjectDisabledMcpServerIds(projectId, emptySet())

        assertEquals(emptySet<Long>(), repository.getProjectDisabledMcpServerIds(projectId))
    }

    @Test
    fun repositoryFiltersNonNumericEntriesInStoredJson() = runBlocking {
        // Pre-seed an invalid entry by writing a row with non-numeric JSON.
        val projectId = repository.insertProject(Project(name = "Junk"))
        // Write directly through the DAO so we can test that parsing tolerates junk.
        db.projectDao().updateProjectMcpDisabledIds(
            projectId, "[\"abc\", null, 5, \"5.5\"]", System.currentTimeMillis()
        )

        // Parsing should skip non-integer entries and keep only the integer.
        val ids = repository.getProjectDisabledMcpServerIds(projectId)
        assertEquals(setOf(5L), ids)
    }

    @Test
    fun repositoryReturnsEmptySetForInvalidJson() = runBlocking {
        val projectId = repository.insertProject(Project(name = "InvalidJson"))
        db.projectDao().updateProjectMcpDisabledIds(projectId, "not json at all", System.currentTimeMillis())

        val ids = repository.getProjectDisabledMcpServerIds(projectId)
        assertEquals(emptySet<Long>(), ids)
    }

    @Test
    fun repositoryReturnsEmptySetForMissingProject() = runBlocking {
        // No project with id 9999 exists.
        val ids = repository.getProjectDisabledMcpServerIds(9999L)
        assertEquals(emptySet<Long>(), ids)
    }

    @Test
    fun repositoryPersistsCanonicalJsonArray() = runBlocking {
        // After write, the stored JSON must be a valid array of unique numbers
        // (sorted or unsorted is fine — only the parsed set is observable).
        val projectId = repository.insertProject(Project(name = "Canonical"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(3L, 1L, 2L, 1L))

        val raw = db.projectDao().getProjectMcpDisabledIds(projectId)
        assertNotNull(raw)
        val arr = org.json.JSONArray(raw!!)
        val numbers = (0 until arr.length()).map { arr.getLong(it) }.toSet()
        assertEquals(setOf(1L, 2L, 3L), numbers)
    }

    // ═════════════════════════════════════════════════════════════════
    // 2. McpRuntimeManager.enabledServerIdsForProject
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun enabledServerIdsForProjectIsGlobalEnabledMinusProjectDisabled() = runBlocking {
        // Seed three globally enabled servers.
        val s1 = repository.insertMcpServer(McpServer(name = "global-1", command = "http://x/1", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "global-2", command = "http://x/2", isEnabled = true))
        val s3 = repository.insertMcpServer(McpServer(name = "global-3", command = "http://x/3", isEnabled = true))

        // Project disables server 2.
        val projectId = repository.insertProject(Project(name = "Proj-1"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(s2))

        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        assertEquals(setOf(s1, s3), effective)
    }

    @Test
    fun globallyDisabledServerCannotBeReenabledByProject() = runBlocking {
        // Only one globally enabled server.
        val s1 = repository.insertMcpServer(McpServer(name = "global-1", command = "http://x/1", isEnabled = true))
        // A second server exists but is globally disabled.
        val s2 = repository.insertMcpServer(McpServer(name = "global-2", command = "http://x/2", isEnabled = false))

        // Project does not disable anything (the disabled list is empty).
        val projectId = repository.insertProject(Project(name = "Proj-empty-disable"))
        // The project does NOT contain s2 in its disabled list (project-level
        // store cannot re-enable a globally disabled server).
        repository.setProjectDisabledMcpServerIds(projectId, emptySet())

        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        // Only s1 should be present; s2 is globally disabled and must not appear.
        assertEquals(setOf(s1), effective)
        assertFalse(s2 in effective)
    }

    @Test
    fun newlyEnabledGlobalServerIsInheritedImmediately() = runBlocking {
        // Start with one globally enabled server and one disabled.
        val s1 = repository.insertMcpServer(McpServer(name = "global-1", command = "http://x/1", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "global-2", command = "http://x/2", isEnabled = false))

        val projectId = repository.insertProject(Project(name = "Inherit"))
        // No project-level disable; effective set is currently {s1}.
        assertEquals(setOf(s1), runtimeManager.enabledServerIdsForProject(projectId))

        // Globally enable s2 — the effective set must reflect this immediately
        // because the brief says "the effective set must be recomputed from
        // current global state each time rather than copied into the project".
        db.mcpServerDao().updateServer(McpServer(
            id = s2, name = "global-2", command = "http://x/2", isEnabled = true
        ))

        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        assertEquals(setOf(s1, s2), effective)
    }

    @Test
    fun newlyDisabledGlobalServerStopsBeingEffective() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "global-1", command = "http://x/1", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "global-2", command = "http://x/2", isEnabled = true))

        val projectId = repository.insertProject(Project(name = "Disable-Inherit"))
        assertEquals(setOf(s1, s2), runtimeManager.enabledServerIdsForProject(projectId))

        // Globally disable s2 — the effective set must drop s2 immediately.
        db.mcpServerDao().updateServer(McpServer(
            id = s2, name = "global-2", command = "http://x/2", isEnabled = false
        ))

        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        assertEquals(setOf(s1), effective)
    }

    @Test
    fun newlyDisabledProjectServerStopsBeingEffective() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "global-1", command = "http://x/1", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "global-2", command = "http://x/2", isEnabled = true))

        val projectId = repository.insertProject(Project(name = "Disable"))
        assertEquals(setOf(s1, s2), runtimeManager.enabledServerIdsForProject(projectId))

        // Project disables s2 — effective set drops s2.
        repository.setProjectDisabledMcpServerIds(projectId, setOf(s2))
        assertEquals(setOf(s1), runtimeManager.enabledServerIdsForProject(projectId))

        // Re-enable s2 at the project level — s2 returns.
        repository.setProjectDisabledMcpServerIds(projectId, emptySet())
        assertEquals(setOf(s1, s2), runtimeManager.enabledServerIdsForProject(projectId))
    }

    @Test
    fun emptyProjectInheritsAllGloballyEnabledServers() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "g1", command = "http://x/1", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "g2", command = "http://x/2", isEnabled = true))
        val s3 = repository.insertMcpServer(McpServer(name = "g3", command = "http://x/3", isEnabled = false))

        val projectId = repository.insertProject(Project(name = "Inherit All"))
        // No setProjectDisabledMcpServerIds call — defaults to "[]" / empty.
        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        assertEquals(setOf(s1, s2), effective)
    }

    // ═════════════════════════════════════════════════════════════════
    // 3. McpRuntimeManager.getProjectEnabledTools
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun getProjectEnabledToolsFiltersMcpRemoteToolsByEffectiveSet() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "srv-1", command = "http://x/1", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "srv-2", command = "http://x/2", isEnabled = true))
        val s3 = repository.insertMcpServer(McpServer(name = "srv-3", command = "http://x/3", isEnabled = true))

        // Register one remote tool per server directly in the ToolRegistry so
        // we can observe the filter without running a real MCP handshake.
        val tool1 = remoteTool("remote_t1", s1, "srv-1")
        val tool2 = remoteTool("remote_t2", s2, "srv-2")
        val tool3 = remoteTool("remote_t3", s3, "srv-3")
        listOf(tool1, tool2, tool3).forEach(ToolRegistry::register)

        try {
            // Project disables s2.
            val projectId = repository.insertProject(Project(name = "P"))
            repository.setProjectDisabledMcpServerIds(projectId, setOf(s2))

            val names = runtimeManager.getProjectEnabledTools(projectId).map { it.name }.toSet()
            assertTrue(tool1.name in names)
            assertFalse("${tool2.name} should be filtered out", tool2.name in names)
            assertTrue(tool3.name in names)
        } finally {
            listOf(tool1, tool2, tool3).forEach { ToolRegistry.unregister(it.name) }
        }
    }

    @Test
    fun getProjectEnabledToolsForProjectWithoutRemoteServersReturnsEmpty() = runBlocking {
        // No servers exist at all.
        val projectId = repository.insertProject(Project(name = "Bare"))
        val tools = runtimeManager.getProjectEnabledTools(projectId)
        // Built-in tools (project-*, memory, etc.) are not "remote" tools and
        // must not leak into this list — the method returns remote MCP tools
        // filtered by the project's effective server set.
        val onlyRemote = tools.filterIsInstance<McpRemoteTool>()
        assertEquals(0, onlyRemote.size)
    }

    // ═════════════════════════════════════════════════════════════════
    // 4. End-to-end: ToolRegistry.toolsForSession + ToolExecutor
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun projectSessionExposesOnlyEffectiveRemoteMcpTools() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "srv-A", command = "http://x/A", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "srv-B", command = "http://x/B", isEnabled = true))

        val projectId = repository.insertProject(Project(name = "E2E"))
        // Project disables s2; effective set is {s1}.
        repository.setProjectDisabledMcpServerIds(projectId, setOf(s2))

        // Build a ProjectToolScope using the effective IDs as the contract.
        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        val sessionId = repository.insertSession(Session(title = "p", projectId = projectId))
        val scope = ProjectToolScope(sessionId = sessionId, projectId = projectId, allowedMcpServerIds = effective)

        val toolA = remoteTool("remote_tA", s1, "srv-A")
        val toolB = remoteTool("remote_tB", s2, "srv-B")
        listOf(toolA, toolB).forEach(ToolRegistry::register)
        try {
            val names = ToolRegistry.toolsForSession(scope).map { it.name }.toSet()
            assertTrue("remote_tA should be in scope", "remote_tA" in names)
            assertFalse("remote_tB should NOT be in scope (project disabled its server)", "remote_tB" in names)
        } finally {
            listOf(toolA, toolB).forEach { ToolRegistry.unregister(it.name) }
        }
    }

    @Test
    fun toolExecutorRejectsProjectDisabledMcpTool() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "srv-A", command = "http://x/A", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "srv-B", command = "http://x/B", isEnabled = true))

        val projectId = repository.insertProject(Project(name = "Reject"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(s2))

        val effective = runtimeManager.enabledServerIdsForProject(projectId)
        val sessionId = repository.insertSession(Session(title = "p", projectId = projectId))
        val scope = ProjectToolScope(sessionId = sessionId, projectId = projectId, allowedMcpServerIds = effective)

        val toolB = remoteTool("blocked_remote_tB", s2, "srv-B")
        ToolRegistry.register(toolB)
        try {
            val result = ToolExecutor.execute(
                context = context,
                toolName = "blocked_remote_tB",
                arguments = JSONObject(),
                sessionId = sessionId,
                projectScope = scope
            )
            val text = result.toString()
            assertTrue(
                "expected rejection of project-disabled MCP tool, got: $text",
                text.contains("not available in this project session")
            )
        } finally {
            ToolRegistry.unregister(toolB.name)
        }
    }

    @Test
    fun ordinarySessionUnaffectedByProjectMcpScope() = runBlocking {
        val s1 = repository.insertMcpServer(McpServer(name = "srv-A", command = "http://x/A", isEnabled = true))
        val s2 = repository.insertMcpServer(McpServer(name = "srv-B", command = "http://x/B", isEnabled = true))

        // A non-project session has no scope; all registered remote tools
        // should remain available even though a *different* project disabled
        // one of the servers. The project-level disabled IDs must not leak
        // into ordinary sessions.
        val projectId = repository.insertProject(Project(name = "Other"))
        repository.setProjectDisabledMcpServerIds(projectId, setOf(s2))

        val toolA = remoteTool("remote_tA", s1, "srv-A")
        val toolB = remoteTool("remote_tB", s2, "srv-B")
        listOf(toolA, toolB).forEach(ToolRegistry::register)
        try {
            val names = ToolRegistry.toolsForSession(null).map { it.name }.toSet()
            assertTrue("remote_tA should be in ordinary session", "remote_tA" in names)
            assertTrue("remote_tB should be in ordinary session (not project-scoped)", "remote_tB" in names)
        } finally {
            listOf(toolA, toolB).forEach { ToolRegistry.unregister(it.name) }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // helpers
    // ═════════════════════════════════════════════════════════════════

    private fun remoteTool(remoteName: String, serverId: Long, serverName: String): McpRemoteTool {
        // Register the tool under its raw remoteName so tests can assert on
        // predictable names. Production code uses a hashed namespaced name
        // (see McpRuntimeManager.remoteRegistryName), but those tests run
        // against the live runtime and do not need the simplification.
        return McpRemoteTool(
            name = remoteName,
            description = "Remote tool for $serverName",
            inputSchema = JSONObject(),
            serverId = serverId,
            serverName = serverName,
            remoteName = remoteName,
            runtimeManager = runtimeManager
        )
    }
}
