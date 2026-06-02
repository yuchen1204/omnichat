package com.omnichat.hooks

import android.util.Log
import org.json.JSONObject

/**
 * 示例 Hook 实现，用于演示及打印调试日志。
 *
 * 注意：[MessageHook.onAfterReceiveResponse] 仅在普通聊天会话中生效。
 */
object LoggingHooks {
    private const val TAG = "LoggingHooks"

    fun registerAll() {
        // 1. 注册消息 Hook
        HookManager.registerMessageHook(object : MessageHook {
            override suspend fun onBeforeSendMessage(message: String): String? {
                Log.i(TAG, "[MessageHook] onBeforeSendMessage: ${message.take(80)}")
                // 拦截规则演示：如果包含 "block_me"，则拦截此消息的发送
                if (message.contains("block_me", ignoreCase = true)) {
                    Log.w(TAG, "[MessageHook] 消息包含 'block_me'，已被 Hook 拦截")
                    return null
                }
                return message
            }

            override suspend fun onAfterReceiveResponse(response: String): String {
                Log.i(TAG, "[MessageHook] onAfterReceiveResponse length: ${response.length}")
                // WHY: 不修改响应内容，避免污染对话上下文。
                return response
            }
        })

        // 2. 注册 MCP 工具 Hook
        HookManager.registerMcpHook(object : McpHook {
            override suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject? {
                Log.i(TAG, "[McpHook] onBeforeToolExecute: $toolName, args: $args")
                return args
            }

            override suspend fun onAfterToolExecute(toolName: String, result: String): String {
                Log.i(TAG, "[McpHook] onAfterToolExecute: $toolName, result length: ${result.length}")
                return result
            }
        })
    }
}
