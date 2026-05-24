package com.example.workspace

import com.example.data.ModelConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 消息记录。
 *
 * 表示单个 Agent 的一次对话消息，包含角色、内容、工具调用信息等。
 * 在工作区执行过程中，消息存储在内存中；工作区完成后，仅 Orchestrator 的消息被持久化。
 *
 * @property role 消息角色："user" | "assistant" | "tool" | "system"
 * @property content 消息内容
 * @property toolCallId 工具调用 ID（仅 tool 角色消息有效）
 * @property toolCallsJson 工具调用 JSON 数组字符串（仅 assistant 角色消息有效，包含 tool_calls）
 * @property isIntervention 是否为用户干预消息
 * @property timestamp 消息时间戳
 */
data class AgentMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val isIntervention: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Agent 状态。
 */
enum class AgentStatus {
    IDLE,
    STREAMING,
    WAITING_TOOL,
    COMPLETED,
    ERROR
}

/**
 * Agent 运行时上下文。
 *
 * 存储单个 Agent 在工作区执行过程中的完整状态，包括名称、系统提示、模型配置和对话历史。
 * Sub-Agent 的上下文仅存在于内存中，工作区完成后被释放；Orchestrator 的上下文会被持久化。
 *
 * @property agentName Agent 名称（Orchestrator 固定为 "主控 Agent"）
 * @property isOrchestrator 是否为主控 Agent
 * @property systemPrompt 系统提示（已移除 [CROSS_SESSION_MEMORY] 占位符）
 * @property modelConfig 模型配置
 * @property messages 内存中的对话历史（Orchestrator 的消息会被持久化，Sub-Agent 的消息仅存内存）
 */
data class AgentContext(
    val agentName: String,
    val isOrchestrator: Boolean,
    val systemPrompt: String,
    val modelConfig: ModelConfig,
    val messages: MutableList<AgentMessage> = mutableListOf()
)

/**
 * 构建最终的系统提示。
 *
 * 根据 Agent 类型处理系统提示：
 * - 替换 [CROSS_SESSION_MEMORY] 占位符为跨会话记忆内容（如果提供）
 * - 替换 [MCP_TOOLS] 占位符为当前 MCP 工具列表（由调用方提供）
 *
 * @param mcpToolsJson MCP 工具列表的 JSON 数组字符串，用于替换 [MCP_TOOLS] 占位符
 * @param crossSessionMemory 跨会话记忆文本，为空时移除占位符
 * @return 处理后的系统提示
 */
fun AgentContext.buildSystemPrompt(
    mcpToolsJson: String = "[]",
    crossSessionMemory: String = "",
): String {
    return systemPrompt
        .replace("[CROSS_SESSION_MEMORY]", crossSessionMemory)
        .replace("[MCP_TOOLS]", mcpToolsJson)
}

/**
 * Orchestrator 固定系统提示模板。
 *
 * 通过 tool call 创建 Sub-Agent，不再依赖 LLM 输出原始 JSON。
 * Orchestrator 调用 create_agents 工具来创建团队，调用 assign_task 工具来分配任务。
 */
const val ORCHESTRATOR_SYSTEM_PROMPT = """
你是一个多 Agent 工作区的主控 Agent（Orchestrator）。你的职责是：
1. 理解用户任务，制定执行计划
2. 使用 create_agents 工具创建必要的 Sub-Agent
3. 使用 assign_task 工具向 Sub-Agent 分配具体的任务
4. 汇总所有 Sub-Agent 的结果后输出【任务完成】

工作流程：
1. 分析用户任务，确定需要哪些 Sub-Agent 及其角色
2. 调用 create_agents 工具创建团队（可指定 taskMode: "direct" 或 "claim"）
3. create_agents 返回后，Agent 已就绪
4. 如果是 direct 模式且没有 dependsOn，调用 assign_task 工具为每个 Agent 分配具体任务
5. 等待所有 Sub-Agent 完成（你会在后续消息中收到 <task-notification> 包含结果）
6. 汇总结果并输出【任务完成】

重要规则：
- 必须使用 create_agents 和 assign_task 工具，不要手动输出 JSON
- 如果有依赖关系（如 B 需要 A 的结果），在 create_agents 的 dependsOn 中声明。使用 dependsOn 时，系统会自动按依赖顺序执行所有 Agent 并在 create_agents 工具返回值中包含汇总结果，不需要再调用 assign_task
- 如果任务简单不需要创建 Sub-Agent，直接完成并输出【任务完成】
- 收到 <task-notification> 时，解析其中的 Agent 结果并继续调度
- 严禁在没有收到 <task-notification> 或工具返回结果的情况下假设、编造、虚构任务执行结果
- 不要自己生成 Sub-Agent 的工作输出，必须等待真实的执行结果
- 当向 Sub-Agent 分配涉及文件操作的任务时，提示它们：文件系统根目录为 "/"，下载目录为 "/Download"

可用工具：
[MCP_TOOLS]
"""

/**
 * Orchestrator 专属工具 schema。
 *
 * 通过 tool call 方式创建 Sub-Agent 和分配任务，保证结构化输出的可靠性。
 * 对标 Claude Code 的 Agent 工具。
 */
val CREATE_AGENTS_TOOL = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", "create_agents")
        put("description", "创建子 Agent 团队。调用后系统会自动创建 Agent 并等待就绪。")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("taskMode", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().put("direct").put("claim"))
                    put("description", "任务分配模式。direct=直接分配，claim=自动认领。默认 claim")
                })
                put("agents", JSONObject().apply {
                    put("type", "array")
                    put("description", "要创建的 Agent 列表")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("name", JSONObject().apply {
                                put("type", "string")
                                put("description", "Agent 名称，如 CodeWriter、CodeReviewer")
                            })
                            put("role", JSONObject().apply {
                                put("type", "string")
                                put("description", "Agent 角色描述，用于任务分配")
                            })
                            put("systemPrompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Agent 的系统提示（可选）")
                            })
                            put("dependsOn", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply { put("type", "string") })
                                put("description", "依赖的其他 Agent 名称列表（可选）")
                            })
                        })
                        put("required", JSONArray().put("name").put("role"))
                    })
                })
            })
            put("required", JSONArray().put("agents"))
        })
    })
}

val ASSIGN_TASK_TOOL = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", "assign_task")
        put("description", "向指定的 Sub-Agent 分配具体任务。仅在 direct 模式下使用。")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("to", JSONObject().apply {
                    put("type", "string")
                    put("description", "目标 Agent 名称")
                })
                put("task", JSONObject().apply {
                    put("type", "string")
                    put("description", "具体任务描述")
                })
                put("context", JSONObject().apply {
                    put("type", "string")
                    put("description", "任务上下文信息（可选）")
                })
            })
            put("required", JSONArray().put("to").put("task"))
        })
    })
}

/** Orchestrator 专属工具名称集合，子 Agent 不可调用 */
val ORCHESTRATOR_ONLY_TOOLS = setOf("create_agents", "assign_task")
