package com.omnichat.hooks

import org.json.JSONObject

/**
 * 消息/会话处理 Hook 接口
 */
interface MessageHook {
    /**
     * 发送用户消息前的 Hook。
     * 可以对文本进行修改（如敏感词过滤、格式化等），如果返回 null，则表示拦截并取消发送。
     */
    suspend fun onBeforeSendMessage(message: String): String?

    /**
     * 接收到大模型响应后的 Hook。
     * 可以对大模型的响应文本进行后处理（如追加免责声明、内容清洗等），如果返回 null，则可丢弃响应或做其他处理（此处返回 String 代表处理后的文本）。
     */
    suspend fun onAfterReceiveResponse(response: String): String
}

/**
 * MCP 工具执行 Hook 接口
 */
interface McpHook {
    /**
     * MCP 工具执行前的 Hook。
     * 可以修改传入的参数 [args]。如果返回 null，则表示拦截并取消该工具的执行。
     */
    suspend fun onBeforeToolExecute(toolName: String, args: JSONObject): JSONObject?

    /**
     * MCP 工具执行后的 Hook。
     * 可以修改或封装执行结果。
     */
    suspend fun onAfterToolExecute(toolName: String, result: String): String
}
