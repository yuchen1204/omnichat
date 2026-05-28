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
    val messages: MutableList<AgentMessage>
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
        put("description", "创建子 Agent 团队。支持三种模式：direct（你通过 assign_task 为每个 Agent 写完整任务 prompt，适合精确控制）；claim（role 字段直接作为任务 prompt 发给认领的 Agent，必须完全自包含，适合并发独立任务）；dependsOn（声明 Agent 间依赖关系，系统自动串行执行，工具返回时已包含所有结果）。")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("taskMode", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().put("direct").put("claim"))
                    put("description", "任务分配模式。direct=精确控制，创建后需调用 assign_task 分配完整任务；claim=自动认领，role 字段直接作为任务 prompt 发给 Agent（必须完全自包含：含路径、输入、输出格式、完成标准）。默认 claim。")
                })
                put("agents", JSONObject().apply {
                    put("type", "array")
                    put("description", "要创建的 Agent 列表")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("name", JSONObject().apply {
                                put("type", "string")
                                put("description", "Agent 名称，用英文驼峰描述职责，如 FileScanner、DataAnalyzer、ReportWriter。避免泛化名称如 Agent1、Worker。")
                            })
                            put("role", JSONObject().apply {
                                put("type", "string")
                                put("description", "任务描述。【direct 模式】角色说明，可简短，实际任务通过 assign_task 发送。【claim 模式】直接作为任务 prompt 发给 Agent，必须完全自包含（含具体路径、输入数据、输出格式、完成标准清单），❌错误：\"分析文件\"，❌错误：\"创建一个网页\"（没有列出具体文件），✅正确：\"扫描 /Download 目录（不递归），统计每种扩展名数量，输出格式：每行「扩展名: 数量」，无扩展名归类为「无扩展名」。完成标准：1) 已列出所有文件 2) 已统计每种扩展名 3) 已输出总数\"。【dependsOn 模式】描述本 Agent 职责及如何使用上游产出。")
                            })
                            put("systemPrompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "覆盖 Agent 默认行为的系统提示（可选）。适合需要特定专业角色时使用，如「你是数据分析专家，回答时优先给出数字结论」。不填则使用默认子 Agent 提示。")
                            })
                            put("dependsOn", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply { put("type", "string") })
                                put("description", "依赖的其他 Agent 名称列表（可选）。填写后系统自动串行执行：上游完成后其结果自动注入本 Agent 上下文，create_agents 工具等待整条依赖链全部完成后才返回。")
                            })
                            put("modelConfigId", JSONObject().apply {
                                put("type", "integer")
                                put("description", "如果要为本 Agent 精确指定模型，请填写该模型所属的 Provider ID（参考 [AVAILABLE_MODELS] 中的 modelConfigId）。优先级高于 modelHint。")
                            })
                            put("modelId", JSONObject().apply {
                                put("type", "string")
                                put("description", "如果要为本 Agent 精确指定模型，请填写该模型的标识（参考 [AVAILABLE_MODELS] 中的具体名称，例如 'gpt-4o'）。必须与 modelConfigId 配合使用。")
                            })
                            put("modelHint", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray().apply {
                                    put("reasoning").put("vision").put("fast").put("large-context").put("tools")
                                })
                                put("description", "模型能力提示（推荐）。系统根据任务类型自动选择最合适的模型：reasoning=推理/编程任务（选择支持 thinking 的模型）；vision=图像理解（选择支持视觉的模型）；fast=快速响应（选择轻量模型）；large-context=长文档处理（选择最大上下文窗口）；tools=工具调用（选择支持 tool use 的模型）。无需手动指定 modelConfigId/modelId。")
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
        put("description", "向指定的 Sub-Agent 分配具体任务。仅在 direct 模式下使用。task 字段是 Agent 收到的完整任务 prompt，必须完全自包含（含具体路径、操作目标、输出格式、完成标准清单），Agent 看不到你的对话历史。❌错误：\"扫描下载目录\"（太模糊）；❌错误：\"创建一个网页\"（没有列出具体要创建的文件）；✅正确：\"创建一个网页项目，包含三个文件：1) index.html（主页面，包含导航和内容区）2) style.css（样式文件，定义布局和颜色）3) script.js（交互逻辑，实现按钮点击事件）。完成后报告每个文件的路径和内容摘要。\"")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("to", JSONObject().apply {
                    put("type", "string")
                    put("description", "目标 Agent 名称（必须是已通过 create_agents 创建的 Agent）")
                })
                put("task", JSONObject().apply {
                    put("type", "string")
                    put("description", "完整任务 prompt。必须自包含：具体操作目标 + 输入路径/数据 + 期望输出格式 + 完成标准清单（逐项列出）。Agent 没有你的上下文，不能引用「之前的分析」「上面的结果」等。")
                })
                put("context", JSONObject().apply {
                    put("type", "string")
                    put("description", "补充上下文（可选）。可用于传递前序 Agent 的关键输出，作为本次任务的输入数据。")
                })
            })
            put("required", JSONArray().put("to").put("task"))
        })
    })
}

val CONTINUE_CONVERSATION_TOOL = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", "continue_conversation")
        put("description", "继续与已有 Sub-Agent 对话，利用其已加载的上下文继续工作，避免重新创建。适用场景：1) 研究完成后让同一 Agent 执行实施（高上下文重叠）；2) 修正失败的实现（Agent 有错误上下文）；3) 追加更多任务；4) 补完不完整的结果（如 Agent 只创建了部分文件）。message 必须完全自包含，包含具体文件路径、行号和要做什么。")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("to", JSONObject().apply {
                    put("type", "string")
                    put("description", "目标 Agent 名称（必须是当前仍活跃的 Agent）")
                })
                put("message", JSONObject().apply {
                    put("type", "string")
                    put("description", "要发送的消息内容。必须完全自包含，包含具体文件路径、行号和要做什么改动。Agent 虽有历史上下文，但仍需明确指出操作目标。如果是补完任务，明确列出还缺少什么。")
                })
            })
            put("required", JSONArray().put("to").put("message"))
        })
    })
}

val PEER_MESSAGE_TOOL = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", "peer_message")
        put("description", """向其他 Sub-Agent 发送消息，实现 Agent 间直接协作。消息会投递到目标 Agent 的收件箱，它会在下一次轮询时收到并处理。

使用场景：
1. 请求帮助："我需要你帮忙分析这个文件的内容"
2. 传递中间结果："代码分析完成，以下是关键发现..."
3. 协调分工："我已经处理了 A 部分，请你处理 B 部分"
4. 广播通知："所有 Agent 请注意，需求已变更"

注意：消息是异步投递的，目标 Agent 不会立即收到。如果需要等待响应，请在后续轮次中检查收件箱。""")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("to", JSONObject().apply {
                    put("type", "string")
                    put("description", "目标 Agent 名称（如 'researcher'、'coder'）。使用 '*' 可广播给团队中所有其他 Sub-Agent（不包括自己）。")
                })
                put("message", JSONObject().apply {
                    put("type", "string")
                    put("description", "消息正文。可以包含代码、文件路径、问题描述等任何文本内容。")
                })
                put("summary", JSONObject().apply {
                    put("type", "string")
                    put("description", "消息摘要（5-10 词），用于 UI 预览。例如：'请求代码审查'、'返回分析结果'")
                })
            })
            put("required", JSONArray().put("to").put("message"))
        })
    })
}

// ORCHESTRATOR_ONLY_TOOLS 和 COLLABORATION_TOOLS 已迁移至 WorkspaceModels.kt
