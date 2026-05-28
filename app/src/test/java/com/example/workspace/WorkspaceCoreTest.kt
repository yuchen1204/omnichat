package com.example.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.mcp.McpRuntimeManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceCoreTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var mcpRuntimeManager: McpRuntimeManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(db)
        mcpRuntimeManager = McpRuntimeManager.getInstance(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── TeamManager ─────────────────────────────────────────────────────

    /**
     * 创建一个仅用于测试的 TeamManager（不启动任何协程）。
     */
    private fun makeTeamManager(): TeamManager {
        val config = ModelConfig(
            id = 1,
            name = "Test Model",
            endpoint = "http://localhost",
            apiKey = "test",
            selectedModelId = "gpt",
            memoryModelId = "gpt"
        )
        return TeamManager(
            repository = repository,
            mcpRuntimeManager = mcpRuntimeManager,
            parentScope = kotlinx.coroutines.MainScope(),
            onAgentCreated = { _, _ -> },
            onStreamChunk = { _, _ -> },
            onAgentStatusChanged = { _, _ -> },
            onWorkspaceComplete = {},
            onError = {}
        )
    }

    @Test
    fun testCompletionMarkerDetection() {
        val teamManager = makeTeamManager()

        assertFalse(teamManager.isCompletionMarker("【任务完成】 已经搞定了"))
        assertFalse(teamManager.isCompletionMarker("  【任务完成】搞定了 "))
        assertTrue(teamManager.isCompletionMarker("搞定了 【任务完成】"))
        assertTrue(teamManager.isCompletionMarker("【任务完成】"))
        assertTrue(teamManager.isCompletionMarker("全部完成。【任务完成】"))
        assertTrue(teamManager.isCompletionMarker("全部完成。【任务完成】。"))
        assertFalse(teamManager.isCompletionMarker("当你完成时输出【任务完成】然后继续"))
    }

    // ─── AgentRunner 系统提示与上下文 ────────────────────────────────────

    @Test
    fun testAgentRunnerSystemPromptAndContext() {
        val config = ModelConfig(
            id = 1,
            name = "Test Model",
            endpoint = "http://localhost",
            apiKey = "test",
            selectedModelId = "gpt",
            memoryModelId = "gpt"
        )

        val systemPromptTemplate = "Prompt: [CROSS_SESSION_MEMORY] with tools [MCP_TOOLS]"
        val agentContext = AgentContext(
            agentName = "TestAgent",
            isOrchestrator = false,
            systemPrompt = systemPromptTemplate,
            modelConfig = config,
            messages = ArrayList(),
        )

        // 1. 验证占位符替换（带记忆文本）
        val toolsJson = "[{\"type\":\"function\",\"function\":{\"name\":\"tool1\"}}]"
        val memoryText = "用户偏好 Kotlin"
        val compiledPrompt = agentContext.buildSystemPrompt(toolsJson, memoryText)
        assertEquals("Prompt: $memoryText with tools $toolsJson", compiledPrompt)

        // 2. 验证占位符替换（无记忆文本）
        val compiledPromptNoMemory = agentContext.buildSystemPrompt(toolsJson, "")
        assertEquals("Prompt:  with tools $toolsJson", compiledPromptNoMemory)

        // 3. 上下文消息构建（不应触发任何记忆同步）
        val runner = AgentRunner(
            context = agentContext,
            mcpRuntimeManager = mcpRuntimeManager,
            onStreamChunk = { _, _ -> },
            onToolCall = { _, _, _, _ -> "" },
        )

        runner.injectMessage("user", "Hello agent")
        runner.injectMessage("assistant", "Hello user")

        val history = runner.getHistory()
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hello agent", history[0].content)
        assertEquals("assistant", history[1].role)
        assertEquals("Hello user", history[1].content)
    }

    // ─── getFilteredTools：agent 工具路由验证 ────────────────────────────

    @Test
    fun testOrchestratorFilteredToolsIncludesAgent() {
        val config = ModelConfig(
            id = 1,
            name = "Test Model",
            endpoint = "http://localhost",
            apiKey = "test",
            selectedModelId = "gpt",
            memoryModelId = "gpt"
        )

        val agentContext = AgentContext(
            agentName = ORCHESTRATOR_NAME,
            isOrchestrator = true,
            systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT,
            modelConfig = config,
            messages = ArrayList(),
        )

        val runner = AgentRunner(
            context = agentContext,
            mcpRuntimeManager = mcpRuntimeManager,
            onStreamChunk = { _, _ -> },
            onToolCall = { _, _, _, _ -> "" },
        )

        val tools = runner.getFilteredTools()
        val toolNames = (0 until tools.length()).map { i ->
            tools.getJSONObject(i).getJSONObject("function").getString("name")
        }

        // agent 工具应包含在 Orchestrator 的工具列表中
        assertTrue("Orchestrator should have 'agent' tool", "agent" in toolNames)

        // 旧的编排工具不应出现
        assertFalse("Orchestrator should NOT have 'create_agents' tool", "create_agents" in toolNames)
        assertFalse("Orchestrator should NOT have 'assign_task' tool", "assign_task" in toolNames)
        assertFalse("Orchestrator should NOT have 'continue_conversation' tool", "continue_conversation" in toolNames)
        assertFalse("Orchestrator should NOT have 'peer_message' tool", "peer_message" in toolNames)
    }

    @Test
    fun testSubAgentFilteredToolsExcludesAgent() {
        val config = ModelConfig(
            id = 1,
            name = "Test Model",
            endpoint = "http://localhost",
            apiKey = "test",
            selectedModelId = "gpt",
            memoryModelId = "gpt"
        )

        val agentContext = AgentContext(
            agentName = "TestSubAgent",
            isOrchestrator = false,
            systemPrompt = "You are a sub-agent.",
            modelConfig = config,
            messages = ArrayList(),
        )

        // SubAgent 使用 disallowedTools = setOf("agent")
        val runner = AgentRunner(
            context = agentContext,
            mcpRuntimeManager = mcpRuntimeManager,
            disallowedTools = setOf("agent"),
            onStreamChunk = { _, _ -> },
            onToolCall = { _, _, _, _ -> "" },
        )

        val tools = runner.getFilteredTools()
        val toolNames = (0 until tools.length()).map { i ->
            tools.getJSONObject(i).getJSONObject("function").getString("name")
        }

        // agent 工具不应出现在 SubAgent 的工具列表中
        assertFalse("SubAgent should NOT have 'agent' tool", "agent" in toolNames)

        // SubAgent 不应有旧的编排工具
        assertFalse("SubAgent should NOT have 'create_agents' tool", "create_agents" in toolNames)
        assertFalse("SubAgent should NOT have 'assign_task' tool", "assign_task" in toolNames)
        assertFalse("SubAgent should NOT have 'continue_conversation' tool", "continue_conversation" in toolNames)
        assertFalse("SubAgent should NOT have 'peer_message' tool", "peer_message" in toolNames)
    }

    // ─── AgentTool schema 验证 ──────────────────────────────────────────

    @Test
    fun testAgentToolSchemaIsValid() {
        val schema = AgentTool.TOOL_SCHEMA
        assertNotNull("AgentTool schema should not be null", schema)

        // 验证必需参数存在
        val properties = schema.optJSONObject("properties")
        assertNotNull("Schema should have properties", properties)
        assertTrue("Schema should have 'description' property", properties!!.has("description"))
        assertTrue("Schema should have 'prompt' property", properties.has("prompt"))

        val required = schema.optJSONArray("required")
        assertNotNull("Schema should have required array", required)
        val requiredList = (0 until required!!.length()).map { required.getString(it) }
        assertTrue("'description' should be required", "description" in requiredList)
        assertTrue("'prompt' should be required", "prompt" in requiredList)
    }

    @Test
    fun testAgentToolNameConstant() {
        assertEquals("agent", AgentTool.TOOL_NAME)
    }

    // ─── WorkspaceConfig 配置验证 ────────────────────────────────────────

    @Test
    fun testWorkspaceConfigDefaults() {
        val config = WorkspaceConfig()
        assertEquals(10, config.maxSubAgents)
        assertEquals(50, config.maxAgentNameLength)
        assertEquals(2000, config.maxSystemPromptLength)
        assertEquals(3, config.maxParseRetries)
        assertEquals(20, config.memoryInjectLimit)
        assertTrue(config.enableCrossSessionMemory)
    }

    // ─── 标题截断 ─────────────────────────────────────────────────────────

    @Test
    fun testWorkspaceTitleTruncation() {
        val longTaskWithNewlines = "   This is a very \n  long task description \t that needs to be truncated   "
        val expectedTitle = "This is a very long "

        val processedTitle = longTaskWithNewlines.trim().replace(Regex("\\s+"), " ").take(20)
        assertEquals(expectedTitle, processedTitle)
    }

    // ─── Orchestrator 系统提示验证 ────────────────────────────────────────

    @Test
    fun testOrchestratorSystemPromptReferencesAgentTool() {
        // 系统提示应引用 agent 工具，而非旧的 create_agents
        assertTrue(
            "Orchestrator prompt should mention 'agent' tool",
            ORCHESTRATOR_SYSTEM_PROMPT.contains("agent 工具")
        )
        assertFalse(
            "Orchestrator prompt should NOT mention 'create_agents'",
            ORCHESTRATOR_SYSTEM_PROMPT.contains("create_agents")
        )
    }
}
