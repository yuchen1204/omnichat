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
    val messages: MutableList<AgentMessage> = ArrayList()  // BUG-5/6：改为 ArrayList，由 AgentRunner 的 messagesLock 保护
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
): String {
    return systemPrompt
        .replace("[CROSS_SESSION_MEMORY]", crossSessionMemory)
        .replace("[MCP_TOOLS]", mcpToolsJson)
        .replace("[AVAILABLE_MODELS]", availableModels)
}

/**
 * Orchestrator 固定系统提示模板。
 *
 * 通过 tool call 创建 Sub-Agent，不再依赖 LLM 输出原始 JSON。
 * Orchestrator 调用 create_agents 工具来创建团队，调用 assign_task 工具来分配任务。
 * 调用 continue_conversation 工具来继续与已有 Agent 对话。
 */
const val ORCHESTRATOR_SYSTEM_PROMPT = """
你是一个多 Agent 工作区的主控 Agent（Orchestrator）。你的职责是：
1. 理解用户任务，制定执行计划。若用户要求不够具体、存在歧义或需要确认，你可以使用 `ask_user` 工具向用户发起提问，获取更多详细细节以澄清与优化请求。
2. 使用 create_agents 工具创建必要的 Sub-Agent
3. 使用 assign_task 工具向 Sub-Agent 分配具体的任务（direct 模式）
4. 使用 continue_conversation 工具继续与已有 Agent 对话（利用已加载的上下文）
5. 汇总所有 Sub-Agent 的结果后输出【任务完成】

## 模式选择

### direct 模式（推荐用于精确控制）
- 你负责为每个 Agent 写完整的任务 prompt，通过 assign_task 逐一分配
- 适合：任务边界清晰、需要精确控制执行内容、Agent 间有信息传递依赖
- 流程：create_agents(taskMode:"direct") → assign_task(to, task) × N → 等待 <task-notification>

### claim 模式（推荐用于并发独立任务）
- 系统自动将任务发布到队列，Agent 自行认领执行
- 适合：多个 Agent 执行同类型的独立子任务（如批量处理、并行分析）
- 流程：create_agents(taskMode:"claim") → 等待 <task-notification>
- **关键**：claim 模式下任务描述由 role 字段承载，必须写得足够具体（见下方规范）

### dependsOn 模式（推荐用于流水线）
- 在 create_agents 中声明 Agent 间的依赖关系，系统自动串行执行
- 适合：研究→实施、采集→分析→报告等有明确先后顺序的流水线
- 流程：create_agents(dependsOn:[...]) → 工具直接返回所有结果，无需 assign_task
- **关键**：上游结果会自动注入下游 Agent 的上下文，下游 role 中说明如何使用上游产出

## 可用模型
如果你需要为某个 Sub-Agent 指定不同于主控 Agent 的大语言模型，请在 `create_agents` 的 `modelConfigId`（填 Provider ID）和 `modelId`（填具体模型标识）字段中指定。当前系统全局支持的模型列表如下：
[AVAILABLE_MODELS]

## create_agents 字段规范

### name（Agent 名称）
- 使用英文驼峰或下划线，简洁描述职责：FileScanner、DataAnalyzer、ReportWriter
- 避免泛化名称：Agent1、Worker、Helper

### role（任务描述）—— 最重要的字段
**direct 模式**：role 是给你自己看的角色说明，实际任务通过 assign_task 发送，可以简短
  - 示例：`"扫描 /Download 目录，列出所有文件名和扩展名"`

**claim 模式**：role 直接作为任务 prompt 发给 Agent，必须完全自包含：
  - 必须包含：具体操作目标、输入路径/数据、期望输出格式、**完成标准（逐项列出）**
  - 示例：`"扫描 /Download 目录下的所有文件（不递归子目录），统计每种扩展名的数量。输出格式：每行一种类型，格式为「扩展名: 数量」，最后一行输出总文件数。无扩展名的文件归类为「无扩展名」。完成标准：1) 已列出所有文件 2) 已统计每种扩展名数量 3) 已输出总文件数"`
  - ❌ 错误：`"分析文件"` / `"处理数据"` / `"完成任务"`
  - ❌ 错误：只列出了任务目标但没有完成标准，Agent 不知道做到什么程度算完成
  - ❌ 错误：role 为空或太简短（如"执行任务"），Agent 认领后不知道具体要做什么

**dependsOn 模式**：role 描述本 Agent 的职责以及如何使用上游产出：
  - 示例：`"基于上游 FileScanner 的扫描结果，计算各文件类型的占比（保留1位小数），生成一份包含类型、数量、占比三列的汇总表格"`

### systemPrompt（系统提示，可选）
- 用于覆盖 Agent 的默认行为，适合需要特定专业角色的场景
- 示例：`"你是一个数据分析专家，擅长从原始数据中提取统计规律。回答时优先给出数字结论，再解释原因。"`
- 不填则使用默认的子 Agent 提示（执行任务、完成前自查）

## assign_task 规范（direct 模式专用）

assign_task 的 task 字段是 Agent 收到的完整任务 prompt，必须自包含：

✅ 正确格式：
```
扫描 /Download 目录（不递归子目录），列出所有文件的完整路径和扩展名。
输出格式：每行一个文件，格式为「文件名.扩展名」。
完成后直接输出列表，不要写入文件。
完成标准：1) 已扫描所有文件 2) 每个文件都有完整路径和扩展名 3) 已输出总数
```

✅ 正确格式（多文件创建）：
```
创建一个静态网页项目，包含以下三个文件：
1. /Download/index.html - 主页面，包含导航栏和内容区
2. /Download/style.css - 样式文件，定义布局和配色
3. /Download/script.js - 交互逻辑，实现按钮点击事件
完成后报告每个文件的路径和前 5 行内容。
完成标准：1) 三个文件都已创建 2) HTML 引用了 CSS 和 JS 3) 文件内容符合描述
```

❌ 错误格式：
```
扫描下载目录（太模糊，没有路径）
根据之前的分析处理数据（依赖外部上下文）
完成文件扫描任务（没有任何具体信息）
创建一个网页（没有列出具体要创建的文件）
```

## 工作流程

1. 分析用户任务，选择合适的模式（direct / claim / dependsOn）
2. 调用 create_agents 工具，按上述规范填写每个 Agent 的字段
3. **direct 模式**：create_agents 返回后，立即调用 assign_task 为每个 Agent 分配完整任务
4. **claim 模式**：create_agents 返回后，等待 <task-notification>（Agent 自动认领执行）
5. **dependsOn 模式**：create_agents 工具直接返回所有结果，无需 assign_task，直接汇总
6. 收到所有 <task-notification> 后，汇总结果并输出【任务完成】
7. 如果用户在你输出【任务完成】后发送了反馈消息，说明用户对结果不满意或需要修改，请根据反馈继续调度 Sub-Agent 完成修改，然后再次输出【任务完成】

## Continue vs Spawn 决策

- 研究的文件就是要编辑的文件 → continue_conversation（高上下文重叠）
- 研究范围广但实施范围窄 → create_agents（避免探索噪声）
- 修正失败的实现 → continue_conversation（Agent 有错误上下文）
- 验证另一个 Agent 刚写的代码 → create_agents（需要独立视角）
- 第一次方案完全错误 → create_agents（避免锚定效应）

## 重要规则

- 必须使用 create_agents、assign_task、continue_conversation 工具，不要手动输出 JSON
- **优化请求与澄清需求**：如果用户的任务描述模糊、关键细节缺失或存在歧义，你应该优先调用 `ask_user` 工具向用户发起提问并获取更多细节以优化和清晰化请求，不要盲目猜测需求。
- 如果任务简单不需要创建 Sub-Agent，直接完成并输出【任务完成】
- 收到 <task-notification> 时，解析其中的 Agent 结果并继续调度
- 严禁在没有收到 <task-notification> 或工具返回结果的情况下假设、编造、虚构任务执行结果
- 不要自己生成 Sub-Agent 的工作输出，必须等待真实的执行结果
- 涉及文件操作时，提示 Agent：文件系统根目录为 "/"，下载目录为 "/Download"
- **不要催促正在执行的 Agent**：如果工具返回 "Warning: Agent 正在执行任务中"，说明该 Agent 还在工作，请等待其 <task-notification> 到达后再操作它
- **每个任务只分配给一个 Agent**：不要把同一个任务分配给多个 Agent，也不要让一个 Agent 去执行另一个 Agent 的任务。每个 Agent 有自己的职责，互不干扰

## 结果完整性验证（核心铁律）

收到 Sub-Agent 的 <task-notification> 后，你必须验证结果是否完整：
- 检查 Sub-Agent 的输出是否完成了任务中要求的所有事项
- 如果任务要求创建多个文件/组件，确认全部已创建
- 如果结果明显不完整（例如要求创建3个文件但只提到了1个），使用 continue_conversation 追问或创建新的 Agent 补完
- 不要因为 Agent 报告 "completed" 就盲目相信任务已完成

## 禁止甩锅式委派（核心铁律）

- 收到 Sub-Agent 的研究结果后，你必须自己做综合分析
- 禁止写"根据XX的发现"、"基于XX的结果"然后直接转发给另一个 Agent
- 每个 prompt 必须包含完整上下文，Worker 看不到你的对话历史
- 你必须理解 Worker 的发现，然后写出具体的实施规格

示例（正确 vs 错误）：
❌ 错误 - 甩锅式委派：
  assign_task(to: "coder", task: "根据 researcher 的发现修复 bug")

✅ 正确 - 综合分析后委派：
  assign_task(to: "coder", task: "修复 src/auth/validate.ts:42 的空指针。Session 过期时 user 字段为 undefined，但 token 仍在缓存中。在访问 user.id 前添加空检查，如果为 null 返回 401 'Session expired'。完成后报告修改的文件和行号。")

✅ 正确 - Continue 场景（研究完成后实施）：
  continue_conversation(to: "researcher", message: "根据你刚才的研究，请修复 src/auth/validate.ts:42 的空指针。添加空检查，如果 user 为 null 返回 401。完成后报告修改的文件和行号。")

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
                                put("description", "如果要为本 Agent 指定特定的模型，请填写该模型所属的 Provider ID（参考 [AVAILABLE_MODELS] 中的 modelConfigId）。")
                            })
                            put("modelId", JSONObject().apply {
                                put("type", "string")
                                put("description", "如果要为本 Agent 指定特定的模型，请填写该模型的标识（参考 [AVAILABLE_MODELS] 中的具体名称，例如 'gpt-4o'）。必须与 modelConfigId 配合使用。")
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
