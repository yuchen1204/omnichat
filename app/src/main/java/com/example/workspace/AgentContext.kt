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
 * @property source 消息来源标识："" = 用户真实输入，"orchestrator" = 主控注入，"subagent" = 子Agent上报
 * @property timestamp 消息时间戳
 */
data class AgentMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val isIntervention: Boolean = false,
    val source: String = "",
    val imagePath: String? = null,
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
 * @property teamName 所属团队名称（用于 AgentRunner.getTeamName() 等场景）
 * @property messages 内存中的对话历史（使用 ArrayList + ReentrantReadWriteLock 保证线程安全，见 AgentRunner）
 */
data class AgentContext(
    val agentName: String,
    val isOrchestrator: Boolean,
    val systemPrompt: String,
    val modelConfig: ModelConfig,
    val overrideModelId: String? = null,
    val teamName: String = "",
    // WHY: 移除默认值 ArrayList()，强制调用方显式传入。data class 的默认值会在
    // copy() 时共享同一引用，导致两个 AgentContext 意外共享同一个 messages 列表。
    // 调用方必须传入独立的 ArrayList，由 AgentRunner.messagesLock 保护。
    val messages: MutableList<AgentMessage>,
    // WHY: 传入 AgentDefinition 以便 AgentToolFilter 根据 agent 的 tools/disallowedTools 过滤工具
    val agentDefinition: AgentDefinition? = null,
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
    availableModels: String = "",
    sandboxPath: String? = null,
): String {
    val sandboxSection = if (!sandboxPath.isNullOrBlank()) {
        """

## 文件操作沙盒限制（强制）

用户授权的工作目录为：$sandboxPath
- 所有文件操作（读取、写入、创建、删除、搜索等）必须限制在该目录及其子目录内
- 禁止访问该目录之外的任何路径（包括 /sdcard、/Download、/Documents 等）
- 在分配任务给 Sub-Agent 时，必须明确告知沙盒路径，所有文件路径必须以该目录为根
- 如果任务需要访问沙盒之外的文件，告知用户无法执行并说明原因"""
    } else ""

    var finalPrompt = systemPrompt
        .replace("[CROSS_SESSION_MEMORY]", crossSessionMemory)
        .replace("[MCP_TOOLS]", mcpToolsJson)
        .replace("[AVAILABLE_MODELS]", availableModels)

    if (finalPrompt.contains("[SANDBOX_PATH]")) {
        finalPrompt = finalPrompt.replace("[SANDBOX_PATH]", sandboxSection)
    } else if (sandboxSection.isNotEmpty()) {
        finalPrompt = "$finalPrompt\n$sandboxSection"
    }
    return finalPrompt
}

/**
 * Orchestrator 固定系统提示模板。
 *
 * Orchestrator 通过 agent 工具委派子任务给独立的子 Agent 执行。
 * 简单任务直接完成，复杂任务拆分后通过 agent 工具委派。
 */
const val ORCHESTRATOR_SYSTEM_PROMPT = """你是一个任务编排助手。你的职责是理解用户需求，将复杂任务拆分为子任务，并使用 agent 工具委派给独立的子 Agent 执行。

## 可用工具
你可以直接使用所有可用的工具（文件操作、MCP 工具等），也可以使用 agent 工具委派子任务。

## agent 工具
调用方式：
- description: 3-5 个词的简短任务描述（英文）
- prompt: 完整的任务描述，包含所有必要的上下文和要求（英文）
- model: 可选，指定子 Agent 使用的模型

## 何时委派
- 任务可以拆分为独立的子任务
- 子任务之间没有强依赖
- 需要并行处理以提高效率

## 何时自己做
- 任务简单直接
- 需要与用户交互确认
- 子任务之间有强依赖，必须顺序执行

## 子 Agent 特性
- 子 Agent 有独立的消息历史，无法访问你的对话
- 子 Agent 有自己的工具集（文件操作、MCP 工具等）
- 子 Agent 不能调用 agent 工具（防止递归）
- 子 Agent 完成后返回结果文本

## 收到子 Agent 结果后
- 分析子 Agent 的输出
- 如果结果不完整或有误，可以重新委派或自己补充
- 汇总所有结果后输出【任务完成】标记

## 工作目录
[SANDBOX_PATH]

## 可用模型
[AVAILABLE_MODELS]

## 可用工具
[MCP_TOOLS]"""

// 旧的 Orchestrator 工具（create_agents / assign_task / continue_conversation / peer_message）
// 已被内置 "agent" 工具（McpRuntimeManager 注册）取代，不再需要。

/**
 * Build orchestrator system prompt dynamically with available agent listings.
 *
 * Replaces the static ORCHESTRATOR_SYSTEM_PROMPT with a dynamic version
 * that includes the list of available agent types.
 */
fun buildOrchestratorSystemPrompt(
    agentDefinitions: List<AgentDefinition>,
    sandboxPath: String? = null,
): String {
    val agentList = agentDefinitions.joinToString("\n") { def ->
        "- `${def.agentType}`: ${def.displayName}${if (def.background) " (background)" else ""}"
    }
    return """你是一个任务编排助手。你的职责是理解用户需求，将复杂任务拆分为子任务，并使用 agent 工具委派给独立的子 Agent 执行。

## agent 工具
调用方式：
- description: 3-5 个词的简短任务描述（英文）
- prompt: 完整的任务描述，包含所有必要的上下文和要求（英文）
- subagent_type: 可选，指定 Agent 类型（见下方列表）
- model: 可选，指定子 Agent 使用的模型

## 可用 Agent 类型
$agentList

## 何时委派
- 任务可以拆分为独立的子任务
- 子任务之间没有强依赖
- 需要并行处理以提高效率

## 何时自己做
- 任务简单直接
- 需要与用户交互确认

## 收到子 Agent 结果后
- 分析子 Agent 的输出
- 如果结果不完整或有误，可以重新委派或自己补充
- 汇总所有结果后输出【任务完成】标记

## 工作目录
[SANDBOX_PATH]

## 可用模型
[AVAILABLE_MODELS]

## 可用工具
[MCP_TOOLS]"""
}
