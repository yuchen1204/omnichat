package com.omnichat.tool

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.ProjectContentLimits
import com.omnichat.data.ProjectFileStore
import com.omnichat.data.ProjectKnowledge
import com.omnichat.data.Session
import com.omnichat.mcp.McpRuntimeManager
import com.omnichat.tool.builtin.ProjectListKnowledgeTool
import com.omnichat.tool.builtin.ProjectReadKnowledgeTool
import com.omnichat.tool.builtin.ProjectCreateKnowledgeTool
import com.omnichat.tool.builtin.ProjectAppendKnowledgeTool
import com.omnichat.tool.builtin.ProjectEditKnowledgeTool
import com.omnichat.tool.builtin.ProjectReadMemoryTool
import com.omnichat.tool.builtin.ProjectUpdateMemoryTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectToolScopeTest {

    private lateinit var context: Context
    private lateinit var repository: AppRepository
    private lateinit var db: AppDatabase
    private lateinit var projectRoot: File

    companion object {
        private val PROJECT_TOOLS = setOf(
            "project_list_knowledge",
            "project_read_knowledge",
            "project_create_knowledge",
            "project_append_knowledge",
            "project_edit_knowledge",
            "project_read_memory",
            "project_update_memory"
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(context)
        repository = AppRepository(db)

        // Initialize ProjectFileStore with a temp directory
        projectRoot = File(System.getProperty("java.io.tmpdir"),
            "project_tool_test_${System.currentTimeMillis()}_${System.nanoTime()}")
        projectRoot.mkdirs()
        ProjectFileStore.initForTest(projectRoot)

        // Register project tools
        ToolInitializer.reset()
        ToolInitializer.initialize(context)
    }

    @After
    fun tearDown() {
        ToolInitializer.reset()
        ProjectFileStore.resetForTest()
        projectRoot.deleteRecursively()
    }

    // ═════════════════════════════════════════════════════════════════
    // 1. ProjectToolScope 基础
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun projectToolScopeHasCorrectConstants() {
        assertEquals(
            setOf(
                "project_list_knowledge",
                "project_read_knowledge",
                "project_create_knowledge",
                "project_append_knowledge",
                "project_edit_knowledge",
                "project_read_memory",
                "project_update_memory"
            ),
            ProjectToolScope.ALLOWED_PROJECT_TOOLS
        )
    }

    @Test
    fun projectToolScopeStoresFields() {
        val scope = ProjectToolScope(
            sessionId = 1L,
            projectId = 42L,
            allowedMcpServerIds = setOf(10L, 20L)
        )
        assertEquals(1L, scope.sessionId)
        assertEquals(42L, scope.projectId)
        assertEquals(setOf(10L, 20L), scope.allowedMcpServerIds)
    }

    @Test
    fun projectToolScopeDefaultsToEmptyMcpServerIds() {
        val scope = ProjectToolScope(sessionId = 1L, projectId = 42L)
        assertTrue(scope.allowedMcpServerIds.isEmpty())
    }

    // ═════════════════════════════════════════════════════════════════
    // 2. ToolRegistry.toolsForSession 过滤
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun toolsForSessionWithNullScopeReturnsAllTools() {
        val allTools = ToolRegistry.toolsForSession(null)
        val allNames = allTools.map { it.name }.toSet()

        // All registered tools should be present
        assertTrue(allNames.contains("get_current_time"))
        assertTrue(allNames.contains("file_read"))
        assertTrue(allNames.contains("project_list_knowledge"))
        assertTrue(allNames.contains("project_read_knowledge"))
        assertTrue(allNames.contains("project_create_knowledge"))
        assertTrue(allNames.contains("project_append_knowledge"))
        assertTrue(allNames.contains("project_edit_knowledge"))
        assertTrue(allNames.contains("project_read_memory"))
        assertTrue(allNames.contains("project_update_memory"))
    }

    @Test
    fun projectSessionExposesOnlyProjectTools() {
        val scope = ProjectToolScope(sessionId = 1L, projectId = 42L)
        val names = ToolRegistry.toolsForSession(scope).map { it.name }.toSet()

        // In a project session, only the 5 project tools should be exposed
        // (no MCP tools registered in this test, so only project tools)
        assertEquals(PROJECT_TOOLS, names)
    }

    @Test
    fun projectSessionExcludesNonProjectTools() {
        val scope = ProjectToolScope(sessionId = 1L, projectId = 42L)
        val names = ToolRegistry.toolsForSession(scope).map { it.name }.toSet()

        // Non-project tools should be excluded
        assertFalse(names.contains("get_current_time"))
        assertFalse(names.contains("file_read"))
        assertFalse(names.contains("file_write"))
        assertFalse(names.contains("search_memory"))
        assertFalse(names.contains("delegate_task"))
        assertFalse(names.contains("adjust_ui"))
        assertFalse(names.contains("create_document"))
    }

    @Test
    fun projectSessionAllowsMcpToolsWhenServerIsWhitelisted() {
        // Create a mock MCP remote tool
        val mcpTool = McpRemoteTool(
            name = "mcp__test_server__test_tool",
            description = "Test MCP tool",
            inputSchema = JSONObject(),
            serverId = 10L,
            serverName = "test_server",
            remoteName = "test_tool",
            runtimeManager = McpRuntimeManager.getInstance(context)
        )
        ToolRegistry.register(mcpTool)

        try {
            // Scope that allows server 10
            val scope = ProjectToolScope(
                sessionId = 1L,
                projectId = 42L,
                allowedMcpServerIds = setOf(10L)
            )
            val names = ToolRegistry.toolsForSession(scope).map { it.name }.toSet()

            assertTrue(names.contains("mcp__test_server__test_tool"))
            assertEquals(PROJECT_TOOLS.size + 1, names.size)
        } finally {
            ToolRegistry.unregister("mcp__test_server__test_tool")
        }
    }

    @Test
    fun projectSessionExcludesMcpToolsWhenServerNotWhitelisted() {
        val mcpTool = McpRemoteTool(
            name = "mcp__other_server__other_tool",
            description = "Other MCP tool",
            inputSchema = JSONObject(),
            serverId = 20L,
            serverName = "other_server",
            remoteName = "other_tool",
            runtimeManager = McpRuntimeManager.getInstance(context)
        )
        ToolRegistry.register(mcpTool)

        try {
            // Scope only allows server 10, not 20
            val scope = ProjectToolScope(
                sessionId = 1L,
                projectId = 42L,
                allowedMcpServerIds = setOf(10L)
            )
            val names = ToolRegistry.toolsForSession(scope).map { it.name }.toSet()

            assertFalse(names.contains("mcp__other_server__other_tool"))
        } finally {
            ToolRegistry.unregister("mcp__other_server__other_tool")
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 3. ToolExecutor.projectScope 拒绝越权工具
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun executorRejectsNonProjectToolWithProjectScope() = runBlocking {
        val scope = ProjectToolScope(sessionId = 1L, projectId = 42L)

        val result = ToolExecutor.execute(
            context = context,
            toolName = "file_read",
            arguments = JSONObject(),
            sessionId = 1L,
            projectScope = scope
        )

        assertTrue(result.optString("isError").isNotEmpty() || result.optBoolean("isError", false))
        val errorText = result.toString()
        assertTrue(errorText.contains("is not available in project sessions"))
    }

    @Test
    fun executorRejectsMcpToolNotInProjectScope() = runBlocking {
        val mcpTool = McpRemoteTool(
            name = "mcp__blocked_server__blocked_tool",
            description = "Blocked MCP tool",
            inputSchema = JSONObject(),
            serverId = 99L,
            serverName = "blocked_server",
            remoteName = "blocked_tool",
            runtimeManager = McpRuntimeManager.getInstance(context)
        )
        ToolRegistry.register(mcpTool)

        try {
            val scope = ProjectToolScope(
                sessionId = 1L,
                projectId = 42L,
                allowedMcpServerIds = setOf(10L)
            )

            val result = ToolExecutor.execute(
                context = context,
                toolName = "mcp__blocked_server__blocked_tool",
                arguments = JSONObject(),
                sessionId = 1L,
                projectScope = scope
            )

            assertTrue(result.optString("isError").isNotEmpty() || result.optBoolean("isError", false))
            val errorText = result.toString()
            assertTrue(errorText.contains("not available in this project session"))
        } finally {
            ToolRegistry.unregister("mcp__blocked_server__blocked_tool")
        }
    }

    @Test
    fun executorAllowsProjectToolWithProjectScope() = runBlocking {
        val scope = ProjectToolScope(sessionId = 1L, projectId = 42L)

        // project_read_memory is in the whitelist - should pass scope check
        // (it will fail with "not associated with a project" since there's no session, but that's expected)
        val result = ToolExecutor.execute(
            context = context,
            toolName = "project_read_memory",
            arguments = JSONObject(),
            sessionId = 1L,
            projectScope = scope
        )

        // Should NOT get "not available in project sessions" error
        val errorText = result.toString()
        assertFalse(errorText.contains("not available in project sessions"))
    }

    // ═════════════════════════════════════════════════════════════════
    // 4. 项目工具从 session 推导 projectId
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun projectToolRejectsSessionWithoutProjectId() = runBlocking {
        // Create a session without projectId
        val sessionId = repository.insertSession(
            Session(title = "Test Session", projectId = null)
        )

        // project_read_memory should fail because session has no projectId
        val result = ProjectReadMemoryTool.call(context, JSONObject(), sessionId)
        val errorText = result.toString()
        assertTrue(errorText.contains("not associated with a project"))
    }

    @Test
    fun projectToolReadsMemoryFromSessionProject() = runBlocking {
        // Create a project
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Test Project", description = "Test")
        )

        // Create a session with projectId
        val sessionId = repository.insertSession(
            Session(title = "Project Session", projectId = projectId)
        )

        // Write some memory
        repository.updateProjectMemory(projectId, "# Test Memory\n\nHello World")

        // Read memory via tool
        val result = ProjectReadMemoryTool.call(context, JSONObject(), sessionId)
        val text = result.optString("content", "")
        assertTrue(text.contains("Test Memory"))
        assertTrue(text.contains("Hello World"))
    }

    // ═════════════════════════════════════════════════════════════════
    // 5. 跨项目资产拒绝
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun assetFromAnotherProjectIsRejected() = runBlocking {
        // Create project 1
        val projectId1 = repository.insertProject(
            com.omnichat.data.Project(name = "Project 1")
        )
        // Create project 2
        val projectId2 = repository.insertProject(
            com.omnichat.data.Project(name = "Project 2")
        )

        // Create an asset in project 1
        val asset = repository.createAgentProjectAsset(
            projectId = projectId1,
            fileName = "notes.md",
            content = "Project 1 notes".toByteArray(),
            fileType = "md",
            source = "AGENT_CREATED"
        )

        // Create a session in project 2
        val sessionId2 = repository.insertSession(
            Session(title = "Project 2 Session", projectId = projectId2)
        )

        // Try to read project 1's asset from project 2's session
        val arguments = JSONObject().apply {
            put("knowledge_id", asset.id)
        }
        val result = ProjectReadKnowledgeTool.call(context, arguments, sessionId2)
        val errorText = result.toString()
        assertTrue(errorText.contains("does not belong to the current project"))
    }

    // ═════════════════════════════════════════════════════════════════
    // 6. 项目工具基本功能性测试
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun projectListKnowledgeReturnsEmptyForNewProject() = runBlocking {
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Empty Project")
        )
        val sessionId = repository.insertSession(
            Session(title = "Session", projectId = projectId)
        )

        val result = ProjectListKnowledgeTool.call(context, JSONObject(), sessionId)
        val text = result.optString("content", "")
        assertTrue(text.contains("No knowledge files"))
    }

    @Test
    fun projectCreateKnowledgeCreatesAsset() = runBlocking {
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Create Test")
        )
        val sessionId = repository.insertSession(
            Session(title = "Session", projectId = projectId)
        )

        val arguments = JSONObject().apply {
            put("file_name", "test.md")
            put("content", "# Test Content\n\nHello from agent")
            put("file_type", "md")
        }

        val result = ProjectCreateKnowledgeTool.call(context, arguments, sessionId)
        val text = result.optString("content", "")
        assertTrue(text.contains("Knowledge file created"))

        // Verify the asset exists
        val assets = repository.getKnowledgeByProject(projectId)
        assertEquals(1, assets.size)
        assertEquals("test.md", assets[0].fileName)
        assertEquals("AGENT_CREATED", assets[0].source)
    }

    @Test
    fun projectCreateKnowledgeRejectsAssetPastProjectBudget() = runBlocking {
        val projectId = repository.insertProject(com.omnichat.data.Project(name = "Budget Test"))
        val sessionId = repository.insertSession(Session(title = "Session", projectId = projectId))
        repository.insertKnowledge(
            ProjectKnowledge(
                projectId = projectId,
                fileName = "existing.md",
                fileType = "md",
                fileSize = ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT
            )
        )

        val result = ProjectCreateKnowledgeTool.call(
            context,
            JSONObject().apply {
                put("file_name", "new.md")
                put("content", "new content")
                put("file_type", "md")
            },
            sessionId
        )

        assertTrue(result.toString().contains("knowledge asset limit"))
        assertEquals(1, repository.getKnowledgeByProject(projectId).size)
    }

    @Test
    fun projectReadKnowledgeTruncatesTextOutput() = runBlocking {
        val projectId = repository.insertProject(com.omnichat.data.Project(name = "Read Limit Test"))
        val sessionId = repository.insertSession(Session(title = "Session", projectId = projectId))
        val asset = repository.createAgentProjectAsset(
            projectId = projectId,
            fileName = "large.txt",
            content = "x".repeat(ProjectContentLimits.MAX_TOOL_TEXT_CHARS + 100).toByteArray(),
            fileType = "txt"
        )

        val result = ProjectReadKnowledgeTool.call(
            context,
            JSONObject().put("knowledge_id", asset.id),
            sessionId
        )

        assertTrue(result.optString("content").contains("Output truncated"))
    }

    @Test
    fun projectReadKnowledgeRejectsImageOverDataUrlLimit() = runBlocking {
        val projectId = repository.insertProject(com.omnichat.data.Project(name = "Image Limit Test"))
        val sessionId = repository.insertSession(Session(title = "Session", projectId = projectId))
        val asset = repository.createAgentProjectAsset(
            projectId = projectId,
            fileName = "large.png",
            content = ByteArray(ProjectContentLimits.MAX_IMAGE_DATA_URL_BYTES.toInt() + 1),
            fileType = "image"
        )

        val result = ProjectReadKnowledgeTool.call(
            context,
            JSONObject().put("knowledge_id", asset.id),
            sessionId
        )

        assertTrue(result.toString().contains("maximum 5 MiB"))
    }

    @Test
    fun projectCreateKnowledgeRejectsDuplicateName() = runBlocking {
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Duplicate Test")
        )
        val sessionId = repository.insertSession(
            Session(title = "Session", projectId = projectId)
        )

        // Create first asset
        repository.createAgentProjectAsset(
            projectId = projectId,
            fileName = "test.md",
            content = "First".toByteArray(),
            fileType = "md",
            source = "AGENT_CREATED"
        )

        // Try to create duplicate
        val arguments = JSONObject().apply {
            put("file_name", "test.md")
            put("content", "Second".toByteArray().toString())
            put("file_type", "md")
        }

        val result = ProjectCreateKnowledgeTool.call(context, arguments, sessionId)
        val errorText = result.toString()
        assertTrue(errorText.contains("already exists"))
    }

    @Test
    fun projectUpdateMemoryAppendWorks() = runBlocking {
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Append Test")
        )
        val sessionId = repository.insertSession(
            Session(title = "Session", projectId = projectId)
        )

        repository.updateProjectMemory(projectId, "# Initial Memory")

        val arguments = JSONObject().apply {
            put("action", "append")
            put("content", "Appended content")
        }

        val result = ProjectUpdateMemoryTool.call(context, arguments, sessionId)
        val text = result.optString("content", "")
        assertTrue(text.contains("Content appended"))

        val memory = repository.readProjectMemory(projectId)
        assertTrue(memory.contains("Initial Memory"))
        assertTrue(memory.contains("Appended content"))
    }

    @Test
    fun projectUpdateMemoryReplaceWorks() = runBlocking {
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Replace Test")
        )
        val sessionId = repository.insertSession(
            Session(title = "Session", projectId = projectId)
        )

        repository.updateProjectMemory(projectId, "# Old Title\n\nSome content")

        val arguments = JSONObject().apply {
            put("action", "replace")
            put("old_text", "Old Title")
            put("content", "New Title")
        }

        val result = ProjectUpdateMemoryTool.call(context, arguments, sessionId)
        val text = result.optString("content", "")
        assertTrue(text.contains("Project memory updated"))

        val memory = repository.readProjectMemory(projectId)
        assertTrue(memory.contains("New Title"))
        assertFalse(memory.contains("Old Title"))
    }

    @Test
    fun projectUpdateMemoryDeleteWorks() = runBlocking {
        val projectId = repository.insertProject(
            com.omnichat.data.Project(name = "Delete Test")
        )
        val sessionId = repository.insertSession(
            Session(title = "Session", projectId = projectId)
        )

        repository.updateProjectMemory(projectId, "# Keep\n\nThis stays\n\n# Remove\n\nThis goes")

        val arguments = JSONObject().apply {
            put("action", "delete")
            put("section_text", "Remove")
        }

        val result = ProjectUpdateMemoryTool.call(context, arguments, sessionId)
        val text = result.optString("content", "")
        assertTrue(text.contains("Section deleted"))

        val memory = repository.readProjectMemory(projectId)
        assertTrue(memory.contains("Keep"))
        assertFalse(memory.contains("Remove"))
    }

    @Test
    fun projectKnowledgeAppendAndEditSynchronizeFileSize() = runBlocking {
        val projectId = repository.insertProject(com.omnichat.data.Project(name = "Text Edit Test"))
        val sessionId = repository.insertSession(Session(title = "Session", projectId = projectId))
        val asset = repository.createAgentProjectAsset(projectId, "notes.md", "before".toByteArray(), "md")

        val append = ProjectAppendKnowledgeTool.call(context, JSONObject()
            .put("knowledge_id", asset.id).put("content", "after"), sessionId)
        assertFalse(append.optBoolean("isError"))
        assertEquals("before\n\nafter".toByteArray().size.toLong(), repository.getKnowledgeById(asset.id)!!.fileSize)

        val edit = ProjectEditKnowledgeTool.call(context, JSONObject()
            .put("knowledge_id", asset.id).put("old_text", "before").put("content", "changed"), sessionId)
        assertFalse(edit.optBoolean("isError"))
        assertEquals("changed\n\nafter", ProjectFileStore.readTextAsset(repository.getKnowledgeById(asset.id)!!))
        assertEquals("changed\n\nafter".toByteArray().size.toLong(), repository.getKnowledgeById(asset.id)!!.fileSize)
    }

    @Test
    fun projectKnowledgeRejectsNonTextAndMissingOldText() = runBlocking {
        val projectId = repository.insertProject(com.omnichat.data.Project(name = "Reject Test"))
        val sessionId = repository.insertSession(Session(title = "Session", projectId = projectId))
        val image = repository.createAgentProjectAsset(projectId, "image.png", ByteArray(1), "image")
        val text = repository.createAgentProjectAsset(projectId, "notes.txt", "hello".toByteArray(), "txt")

        val imageResult = ProjectAppendKnowledgeTool.call(context, JSONObject()
            .put("knowledge_id", image.id).put("content", "x"), sessionId)
        assertTrue(imageResult.toString().contains("not a text file"))
        val missingResult = ProjectEditKnowledgeTool.call(context, JSONObject()
            .put("knowledge_id", text.id).put("old_text", "missing").put("content", "x"), sessionId)
        assertTrue(missingResult.toString().contains("oldText not found"))
    }

    @Test
    fun projectKnowledgeRejectsAppendPastProjectBudget() = runBlocking {
        val projectId = repository.insertProject(com.omnichat.data.Project(name = "Budget Edit Test"))
        val sessionId = repository.insertSession(Session(title = "Session", projectId = projectId))
        val asset = repository.createAgentProjectAsset(projectId, "notes.txt", "small".toByteArray(), "txt")
        repository.insertKnowledge(ProjectKnowledge(
            projectId = projectId,
            fileName = "full.bin",
            fileType = "other",
            fileSize = ProjectContentLimits.MAX_KNOWLEDGE_BYTES_PER_PROJECT
        ))

        val result = ProjectAppendKnowledgeTool.call(context, JSONObject()
            .put("knowledge_id", asset.id).put("content", "too much"), sessionId)
        assertTrue(result.toString().contains("knowledge asset limit"))
        assertEquals("small", ProjectFileStore.readTextAsset(repository.getKnowledgeById(asset.id)!!))
    }

    @Test
    fun projectReadKnowledgeRejectsAssetFromAnotherProject() = runBlocking {
        val projectId1 = repository.insertProject(
            com.omnichat.data.Project(name = "Project 1")
        )
        val projectId2 = repository.insertProject(
            com.omnichat.data.Project(name = "Project 2")
        )

        // Create asset in project 1
        val asset = repository.createAgentProjectAsset(
            projectId = projectId1,
            fileName = "test.txt",
            content = "Project 1 data".toByteArray(),
            fileType = "txt",
            source = "AGENT_CREATED"
        )

        // Session in project 2
        val sessionId2 = repository.insertSession(
            Session(title = "Session 2", projectId = projectId2)
        )

        val arguments = JSONObject().apply {
            put("knowledge_id", asset.id)
        }
        val result = ProjectReadKnowledgeTool.call(context, arguments, sessionId2)
        assertTrue(result.toString().contains("does not belong to the current project"))
    }
}