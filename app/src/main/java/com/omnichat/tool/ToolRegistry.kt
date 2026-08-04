package com.omnichat.tool

import android.util.Log
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 工具注册表，管理所有可用工具。
 *
 * 功能：
 * - 工具注册/注销
 * - 按名称查找（支持别名）
 * - 按分组筛选
 * - 版本追踪（用于缓存失效）
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"

    // 名称/别名 -> Tool 映射
    private val tools = ConcurrentHashMap<String, Tool>()

    // 版本号，每次变更递增
    private val versionCounter = AtomicLong(0)
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun markChanged() {
        _changes.value = versionCounter.incrementAndGet()
    }

    // ══════════════════════════════════════════════════════════════
    // 注册与注销
    // ══════════════════════════════════════════════════════════════

    /**
     * 注册工具。
     * 工具名和所有别名都会被索引。
     */
    fun register(tool: Tool) {
        if (tools.containsKey(tool.name)) {
            Log.w(TAG, "工具 [${tool.name}] 已存在，将被覆盖")
        }
        tools[tool.name] = tool

        // 注册别名
        tool.aliases.forEach { alias ->
            if (tools.containsKey(alias)) {
                Log.w(TAG, "别名 [$alias] 已被其他工具使用，将被覆盖")
            }
            tools[alias] = tool
        }

        markChanged()
        Log.d(TAG, "已注册工具: ${tool.name}${if (tool.aliases.isNotEmpty()) " (别名: ${tool.aliases.joinToString(", ")})" else ""}")
    }

    /**
     * 批量注册工具。
     */
    fun registerAll(vararg tools: Tool) {
        tools.forEach { register(it) }
    }

    /**
     * 批量注册工具。
     */
    fun registerAll(tools: Collection<Tool>) {
        tools.forEach { register(it) }
    }

    /**
     * 注销工具。
     * 同时移除所有别名映射。
     */
    fun unregister(name: String) {
        val tool = tools[name] ?: return

        // 移除主名称
        tools.remove(tool.name)

        // 移除别名
        tool.aliases.forEach { tools.remove(it) }

        markChanged()
        Log.d(TAG, "已注销工具: ${tool.name}")
    }

    /**
     * 清空所有工具。
     */
    fun clear() {
        tools.clear()
        markChanged()
        Log.d(TAG, "已清空所有工具")
    }

    // ══════════════════════════════════════════════════════════════
    // 查询
    // ══════════════════════════════════════════════════════════════

    /**
     * 按名称查找工具。
     * 支持通过别名查找。
     */
    fun get(name: String): Tool? = tools[name]

    /**
     * 检查工具是否存在。
     */
    fun contains(name: String): Boolean = tools.containsKey(name)

    /**
     * 获取所有工具（去重，不包含别名重复项）。
     */
    fun getAll(): List<Tool> = tools.values.distinctBy { it.name }

    /**
     * 按项目会话作用域获取工具。
     * 当 [scope] 为 null 时，返回所有已注册工具（普通会话行为）。
     * 当 [scope] 不为 null 时，只返回白名单中的项目工具和允许的 MCP 远程工具。
     * 非项目工具（如 files、memory、subagent 等）在项目会话中被拒绝。
     */
    fun toolsForSession(scope: ProjectToolScope?): List<Tool> {
        if (scope == null) return getAll()
        return tools.values.distinctBy { it.name }.filter { tool ->
            when {
                tool.name in ProjectToolScope.ALLOWED_PROJECT_TOOLS -> true
                tool.group == "mcp_remote" -> {
                    val mcpTool = tool as? McpRemoteTool
                    mcpTool != null && mcpTool.serverId in scope.allowedMcpServerIds
                }
                else -> false
            }
        }
    }

    /**
     * 按分组获取工具。
     */
    fun getByGroup(group: String): List<Tool> =
        tools.values.filter { it.group == group }.distinctBy { it.name }

    /**
     * 获取所有分组名称。
     */
    fun getAllGroups(): Set<String> =
        tools.values.map { it.group }.toSet()

    /**
     * 获取只读工具列表。
     */
    fun getReadOnlyTools(): List<Tool> =
        tools.values.filter { it.isReadOnly }.distinctBy { it.name }

    /**
     * 获取破坏性工具列表。
     */
    fun getDestructiveTools(): List<Tool> =
        tools.values.filter { it.isDestructive }.distinctBy { it.name }

    // ══════════════════════════════════════════════════════════════
    // 版本
    // ══════════════════════════════════════════════════════════════

    /**
     * 获取当前版本号。
     * 每次注册/注销操作都会递增。
     * 用于判断工具列表是否需要刷新。
     */
    fun version(): Long = versionCounter.get()

    /**
     * 获取工具数量（不含别名重复）。
     */
    fun size(): Int = tools.values.distinctBy { it.name }.size

    // ══════════════════════════════════════════════════════════════
    // 工具过滤
    // ══════════════════════════════════════════════════════════════

    /**
     * 按启用的分组过滤工具。
     * core 分组始终包含。
     */
    fun filterByEnabledGroups(enabledGroups: Set<String>): List<Tool> =
        tools.values
            .distinctBy { it.name }
            .filter { it.group == "core" || it.group in enabledGroups }

    /**
     * 按 deny 规则过滤工具。
     * 返回未被 deny 的工具列表。
     */
    fun filterByDenyRules(denyRules: Set<String>): List<Tool> =
        tools.values
            .distinctBy { it.name }
            .filter { tool ->
                // 检查工具名是否被 deny
                if (tool.name in denyRules) return@filter false
                // 检查分组是否被 deny (格式: "group:*" 或 "*")
                if (denyRules.contains("*")) return@filter false
                if (denyRules.any { it == "${tool.group}:*" || it == "${tool.group}.*" }) return@filter false
                true
            }

    // ══════════════════════════════════════════════════════════════
    // 导出
    // ══════════════════════════════════════════════════════════════

    /**
     * 导出为 OpenAI 兼容的 tools JSON 数组。
     * 用于注入到 LLM 请求中。
     */
    fun toOpenAiToolsJson(): org.json.JSONArray {
        val array = org.json.JSONArray()
        getAll().forEach { tool ->
            val toolObj = org.json.JSONObject().apply {
                put("type", "function")
                put("function", org.json.JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.inputSchema)
                })
            }
            array.put(toolObj)
        }
        return array
    }

    /**
     * 导出为文本描述。
     * 用于注入到 System Prompt 中。
     */
    fun toTextDescription(): String {
        val allTools = getAll()
        if (allTools.isEmpty()) return "无可用工具 (No tools available)"

        return allTools.joinToString("\n\n") { tool ->
            buildString {
                appendLine("工具名: ${tool.name}")
                appendLine("分组: ${tool.group}")
                appendLine("描述: ${tool.description}")
                appendLine("参数架构: ${tool.inputSchema.toString(2)}")
                if (tool.aliases.isNotEmpty()) {
                    appendLine("别名: ${tool.aliases.joinToString(", ")}")
                }
            }
        }
    }
}
