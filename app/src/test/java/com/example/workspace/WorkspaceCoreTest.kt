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

    // TeamManager 需要的最小依赖
    private lateinit var messageBus: MessageBus
    private lateinit var taskManager: TaskManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(db)
        mcpRuntimeManager = McpRuntimeManager.getInstance(context)
        messageBus = MessageBus()
        taskManager = TaskManager(repository.teamTaskDao)
    }

    @After
    fun tearDown() {
        messageBus.clear()
        db.close()
    }

    // ─── TeamManager：解析与完成标记 ─────────────────────────────────────────

    /**
     * 创建一个仅用于解析测试的 TeamManager（不启动任何协程）。
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
            messageBus = messageBus,
            taskManager = taskManager,
            parentScope = kotlinx.coroutines.MainScope(),
            onAgentCreated = { _, _ -> },
            onStreamChunk = { _, _ -> },
            onAgentStatusChanged = { _, _ -> },
            onWorkspaceComplete = {},
            onError = {}
        )
    }

    @Test
    fun testTeamManagerParsingAndMarkers() {
        val teamManager = makeTeamManager()

        // 完成标记检测（要求标记出现在末尾，避免中间偶然包含时误触发）
        assertFalse(teamManager.isCompletionMarker("【任务完成】 已经搞定了"))
        assertFalse(teamManager.isCompletionMarker("  【任务完成】搞定了 "))
        assertTrue(teamManager.isCompletionMarker("搞定了 【任务完成】"))
        assertTrue(teamManager.isCompletionMarker("【任务完成】"))
        assertTrue(teamManager.isCompletionMarker("全部完成。【任务完成】"))
        assertTrue(teamManager.isCompletionMarker("全部完成。【任务完成】。"))
        assertFalse(teamManager.isCompletionMarker("当你完成时输出【任务完成】然后继续"))

        // 默认 taskMode = CLAIM
        val directiveJson = """
            Here is the instruction:
            {"action": "create_agents", "agents": [{"name": "Coder", "role": "Code Writer", "systemPrompt": "Write Kotlin code", "modelConfigId": 1}]}
            Please proceed.
        """.trimIndent()
        val parsedDirective = teamManager.parseOrchestratorOutput(directiveJson)
        assertNotNull(parsedDirective)
        assertEquals(1, parsedDirective?.agents?.size)
        assertEquals("Coder", parsedDirective?.agents?.get(0)?.name)
        assertEquals("Code Writer", parsedDirective?.agents?.get(0)?.role)
        assertEquals("Write Kotlin code", parsedDirective?.agents?.get(0)?.systemPrompt)
        assertEquals(TaskMode.CLAIM, parsedDirective?.taskMode)

        // taskMode = direct
        val directDirectiveJson = """
            {"action": "create_agents", "taskMode": "direct", "agents": [{"name": "Researcher", "role": "Research", "systemPrompt": "Do research"}]}
        """.trimIndent()
        val directParsedDirective = teamManager.parseOrchestratorOutput(directDirectiveJson)
        assertNotNull(directParsedDirective)
        assertEquals(TaskMode.DIRECT, directParsedDirective?.taskMode)
        assertEquals("Researcher", directParsedDirective?.agents?.get(0)?.name)
    }

    @Test
    fun testTeamManagerParsingWithDependsOn() {
        val teamManager = makeTeamManager()

        val json = """
            {"action": "create_agents", "taskMode": "direct", "agents": [
              {"name": "A", "role": "First", "systemPrompt": "Do A", "dependsOn": []},
              {"name": "B", "role": "Second", "systemPrompt": "Do B", "dependsOn": ["A"]}
            ]}
        """.trimIndent()
        val directive = teamManager.parseOrchestratorOutput(json)
        assertNotNull(directive)
        assertEquals(2, directive?.agents?.size)
        assertEquals(emptyList<String>(), directive?.agents?.get(0)?.dependsOn)
        assertEquals(listOf("A"), directive?.agents?.get(1)?.dependsOn)
    }

    // ─── 增强解析：多策略 fallback ──────────────────────────────────────────

    @Test
    fun testRobustParsingFromCodeBlock() {
        val teamManager = makeTeamManager()

        // 从 markdown 代码块中提取 JSON
        val outputWithCodeBlock = """
我来创建一个 Sub-Agent 来完成任务：

```json
{"action": "create_agents", "taskMode": "direct", "agents": [{"name": "Writer", "role": "写作", "systemPrompt": "写文章"}]}
```

请开始执行。
        """.trimIndent()
        val directive = teamManager.parseOrchestratorOutputRobust(outputWithCodeBlock)
        assertNotNull(directive)
        assertEquals(1, directive?.agents?.size)
        assertEquals("Writer", directive?.agents?.get(0)?.name)
    }

    @Test
    fun testRobustParsingFromNaturalLanguage() {
        val teamManager = makeTeamManager()

        // 从自然语言中提取 Agent 规格
        val naturalOutput = """
我将创建以下 Agent 来协作完成任务：
- Coder: 负责编写 Kotlin 代码
- Reviewer: 负责代码审查
        """.trimIndent()
        val directive = teamManager.parseOrchestratorOutputRobust(naturalOutput)
        assertNotNull(directive)
        assertEquals(2, directive?.agents?.size)
        assertEquals("Coder", directive?.agents?.get(0)?.name)
        assertEquals("Reviewer", directive?.agents?.get(1)?.name)
    }

    // ─── AgentRunner 系统提示与上下文 ────────────────────────────────────────

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

    // ─── ORCHESTRATOR_SYSTEM_PROMPT 格式验证 ─────────────────────────────────

    @Test
    fun testOrchestratorSystemPromptJsonIsValid() {
        val teamManager = makeTeamManager()

        // 系统提示中的 JSON 示例应能被 parseOrchestratorOutput 正确解析
        val sampleOutput = """
            {"action": "create_agents", "taskMode": "direct", "agents": [{"name": "Worker", "role": "执行者", "systemPrompt": "完成任务", "dependsOn": []}]}
        """.trimIndent()
        val directive = teamManager.parseOrchestratorOutput(sampleOutput)
        assertNotNull("系统提示中的 JSON 示例应能被正确解析", directive)
        assertEquals(1, directive?.agents?.size)
        assertEquals("Worker", directive?.agents?.get(0)?.name)
        assertEquals(TaskMode.DIRECT, directive?.taskMode)
    }

    // ─── WorkspaceConfig 配置验证 ────────────────────────────────────────────

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

    // ─── 标题截断 ─────────────────────────────────────────────────────────────

    @Test
    fun testWorkspaceTitleTruncation() {
        val longTaskWithNewlines = "   This is a very \n  long task description \t that needs to be truncated   "
        val expectedTitle = "This is a very long "

        val processedTitle = longTaskWithNewlines.trim().replace(Regex("\\s+"), " ").take(20)
        assertEquals(expectedTitle, processedTitle)
    }
}
