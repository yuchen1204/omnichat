package com.example.hooks

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局 Hook 管理器，用于注册和分发各类 Hook。
 */
object HookManager {
    private const val TAG = "HookManager"

    private val messageHooks = CopyOnWriteArrayList<MessageHook>()
    private val mcpHooks = CopyOnWriteArrayList<McpHook>()
    private val agentHooks = CopyOnWriteArrayList<AgentHook>()

    // ── 注册与反注册 ────────────────────────────────────────────────────────

    fun registerMessageHook(hook: MessageHook) {
        if (!messageHooks.contains(hook)) {
            messageHooks.add(hook)
            Log.d(TAG, "已注册 MessageHook: ${hook.javaClass.simpleName}")
        }
    }

    fun unregisterMessageHook(hook: MessageHook) {
        messageHooks.remove(hook)
        Log.d(TAG, "已注销 MessageHook: ${hook.javaClass.simpleName}")
    }

    fun registerMcpHook(hook: McpHook) {
        if (!mcpHooks.contains(hook)) {
            mcpHooks.add(hook)
            Log.d(TAG, "已注册 McpHook: ${hook.javaClass.simpleName}")
        }
    }

    fun unregisterMcpHook(hook: McpHook) {
        mcpHooks.remove(hook)
        Log.d(TAG, "已注销 McpHook: ${hook.javaClass.simpleName}")
    }

    fun registerAgentHook(hook: AgentHook) {
        if (!agentHooks.contains(hook)) {
            agentHooks.add(hook)
            Log.d(TAG, "已注册 AgentHook: ${hook.javaClass.simpleName}")
        }
    }

    fun unregisterAgentHook(hook: AgentHook) {
        agentHooks.remove(hook)
        Log.d(TAG, "已注销 AgentHook: ${hook.javaClass.simpleName}")
    }

    // ── 消息分发 ──────────────────────────────────────────────────────────

    /**
     * 分发发送消息前事件。
     * 所有 MessageHook 会链式执行，如果某个 Hook 返回 null，整个链中断并返回 null。
     */
    suspend fun dispatchBeforeSendMessage(message: String): String? {
        var currentMsg = message
        for (hook in messageHooks) {
            try {
                val nextMsg = hook.onBeforeSendMessage(currentMsg)
                if (nextMsg == null) {
                    Log.d(TAG, "发送消息 [${currentMsg.take(20)}...] 被 Hook [${hook.javaClass.simpleName}] 拦截并取消")
                    return null
                }
                currentMsg = nextMsg
            } catch (e: Exception) {
                Log.e(TAG, "MessageHook.onBeforeSendMessage 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
        return currentMsg
    }

    /**
     * 分发接收响应后事件。
     * 所有 MessageHook 链式处理，返回最终清洗后的响应。
     */
    suspend fun dispatchAfterReceiveResponse(response: String): String {
        var currentResponse = response
        for (hook in messageHooks) {
            try {
                currentResponse = hook.onAfterReceiveResponse(currentResponse)
            } catch (e: Exception) {
                Log.e(TAG, "MessageHook.onAfterReceiveResponse 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
        return currentResponse
    }

    // ── MCP 分发 ───────────────────────────────────────────────────────────

    /**
     * 分发 MCP 工具执行前事件。
     * 所有 McpHook 链式执行，如果某个 Hook 返回 null，则中断执行并返回 null。
     */
    suspend fun dispatchBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
        var currentArgs = args
        for (hook in mcpHooks) {
            try {
                val nextArgs = hook.onBeforeToolExecute(toolName, currentArgs)
                if (nextArgs == null) {
                    Log.d(TAG, "MCP 工具 [$toolName] 执行被 Hook [${hook.javaClass.simpleName}] 拦截并取消")
                    return null
                }
                currentArgs = nextArgs
            } catch (e: Exception) {
                Log.e(TAG, "McpHook.onBeforeToolExecute 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
        return currentArgs
    }

    /**
     * 分发 MCP 工具执行后事件。
     */
    suspend fun dispatchAfterToolExecute(toolName: String, result: String): String {
        var currentResult = result
        for (hook in mcpHooks) {
            try {
                currentResult = hook.onAfterToolExecute(toolName, currentResult)
            } catch (e: Exception) {
                Log.e(TAG, "McpHook.onAfterToolExecute 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
        return currentResult
    }

    // ── Agent 分发 ─────────────────────────────────────────────────────────

    /**
     * 分发 Agent 单轮对话开始事件。
     */
    suspend fun dispatchAgentTurnStart(
        agentId: String,
        agentType: String,
        teamName: String,
        task: String,
    ) {
        for (hook in agentHooks) {
            try {
                hook.onAgentTurnStart(agentId, agentType, teamName, task)
                // 向后兼容：同时触发旧接口
                @Suppress("DEPRECATION")
                hook.onAgentStart(agentId, agentType, task)
            } catch (e: Exception) {
                Log.e(TAG, "AgentHook.onAgentTurnStart 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
    }

    /**
     * 分发 Agent 单轮对话结束事件。
     */
    suspend fun dispatchAgentTurnEnd(
        agentId: String,
        agentType: String,
        teamName: String,
        result: String,
        toolUseCount: Int,
        durationMs: Long,
    ) {
        for (hook in agentHooks) {
            try {
                hook.onAgentTurnEnd(agentId, agentType, teamName, result, toolUseCount, durationMs)
                // 向后兼容：同时触发旧接口
                @Suppress("DEPRECATION")
                hook.onAgentEnd(agentId, result)
            } catch (e: Exception) {
                Log.e(TAG, "AgentHook.onAgentTurnEnd 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
    }

    /**
     * 分发 Sub-Agent 创建事件。
     */
    suspend fun dispatchTeammateSpawned(
        agentName: String,
        role: String,
        teamName: String,
        color: String,
    ) {
        for (hook in agentHooks) {
            try {
                hook.onTeammateSpawned(agentName, role, teamName, color)
            } catch (e: Exception) {
                Log.e(TAG, "AgentHook.onTeammateSpawned 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
    }

    /**
     * 分发 Sub-Agent 销毁事件。
     */
    suspend fun dispatchTeammateKilled(
        agentName: String,
        teamName: String,
        reason: String,
    ) {
        for (hook in agentHooks) {
            try {
                hook.onTeammateKilled(agentName, teamName, reason)
            } catch (e: Exception) {
                Log.e(TAG, "AgentHook.onTeammateKilled 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
    }

    /**
     * 分发工作区完成事件。
     */
    suspend fun dispatchWorkspaceComplete(
        teamName: String,
        agentCount: Int,
        totalDurationMs: Long,
        orchestratorSummary: String,
    ) {
        for (hook in agentHooks) {
            try {
                hook.onWorkspaceComplete(teamName, agentCount, totalDurationMs, orchestratorSummary)
            } catch (e: Exception) {
                Log.e(TAG, "AgentHook.onWorkspaceComplete 抛出异常, hook=${hook.javaClass.name}", e)
            }
        }
    }
}
