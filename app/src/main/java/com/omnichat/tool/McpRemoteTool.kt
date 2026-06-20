package com.omnichat.tool

import com.omnichat.mcp.McpRuntimeManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import org.json.JSONObject

/**
 * MCP 远程工具包装器。
 *
 * 将远程 MCP 服务器的工具适配到 Tool 接口，
 * 使内置工具和远程工具使用统一的调用方式。
 */
class McpRemoteTool(
    override val name: String,
    override val description: String,
    override val inputSchema: JSONObject,
    val serverId: Long,
    val serverName: String,
    private val runtimeManager: McpRuntimeManager
) : Tool {

    // ══════════════════════════════════════════════════════════════
    // 元信息
    // ══════════════════════════════════════════════════════════════

    override val group: String = "mcp_remote"

    // 远程工具无法确定其行为，采用保守策略
    override val isReadOnly: Boolean = false
    override val isDestructive: Boolean = false
    override val isConcurrencySafe: Boolean = false
    override val requiresSession: Boolean = false

    override fun userFacingName(): String = "${serverName}/${name}"

    // ══════════════════════════════════════════════════════════════
    // 执行
    // ══════════════════════════════════════════════════════════════

    override suspend fun call(
        context: android.content.Context,
        arguments: JSONObject,
        sessionId: Long?
    ): JSONObject {
        // 通过 McpRuntimeManager 调用远程工具
        val result = runtimeManager.callRemoteTool(serverId, name, arguments, sessionId)

        return result ?: ToolExecutor.errorResponse("Remote tool $name returned null")
    }
}