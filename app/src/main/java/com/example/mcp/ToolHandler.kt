package com.example.mcp

import android.content.Context
import com.example.data.AppRepository
import org.json.JSONObject

/**
 * 内置工具处理器接口。每个工具组（core, memory, ui_appearance 等）实现此接口。
 */
interface ToolHandler {
    suspend fun handle(
        toolName: String,
        arguments: JSONObject,
        context: Context,
        repository: AppRepository,
        sessionId: Long?
    ): JSONObject
}
