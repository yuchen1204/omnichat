package com.omnichat.tool

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 工具执行器。
 *
 * 负责：
 * - 工具调用路由
 * - 并发控制
 * - 权限检查
 * - 执行超时
 */
object ToolExecutor {
    private const val TAG = "ToolExecutor"

    // 全局并发限制
    private const val MAX_GLOBAL_PARALLELISM = 5
    private val globalSemaphore = Semaphore(MAX_GLOBAL_PARALLELISM)

    // 非并发安全工具的串行执行锁
    private val serialExecutionLocks = ConcurrentHashMap<String, Semaphore>()

    // ══════════════════════════════════════════════════════════════
    // 执行入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行工具。
     *
     * 执行流程：
     * 1. 查找工具（支持别名）
     * 2. 验证输入参数
     * 3. 检查权限
     * 4. 获取执行许可（并发控制）
     * 5. 执行工具
     * 6. 释放许可
     *
     * @param context Android Context
     * @param toolName 工具名称（支持别名）
     * @param arguments 工具参数
     * @param sessionId 会话 ID（可选）
     * @return 执行结果
     */
    suspend fun execute(
        context: Context,
        toolName: String,
        arguments: JSONObject,
        sessionId: Long? = null
    ): JSONObject {
        // 1. 查找工具
        val tool = ToolRegistry.get(toolName)
            ?: return errorResponse("Unknown tool: $toolName")

        return executeTool(context, tool, arguments, sessionId)
    }

    /**
     * 执行已知工具。
     */
    suspend fun executeTool(
        context: Context,
        tool: Tool,
        arguments: JSONObject,
        sessionId: Long? = null
    ): JSONObject {
        val startTime = System.currentTimeMillis()

        try {
            // 2. 验证输入参数
            val validationError = tool.validateInput(arguments)
            if (validationError != null) {
                android.util.Log.w(TAG, "[${tool.name}] Input validation failed: $validationError")
                return errorResponse(validationError)
            }

            // 3. 检查权限
            val permissionError = tool.checkPermissions(context, arguments)
            if (permissionError != null) {
                android.util.Log.w(TAG, "[${tool.name}] Permission denied: $permissionError")
                return errorResponse("Permission denied: $permissionError")
            }

            // 4. 会话检查
            if (tool.requiresSession && sessionId == null) {
                return errorResponse("Tool ${tool.name} requires a session context")
            }

            // 5. 获取执行许可
            acquireExecutionPermit(tool)

            // 6. 执行工具
            return try {
                val result = tool.call(context, arguments, sessionId)

                val duration = System.currentTimeMillis() - startTime
                android.util.Log.d(TAG, "[${tool.name}] Completed in ${duration}ms")

                result
            } finally {
                releaseExecutionPermit(tool)
            }

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.e(TAG, "[${tool.name}] Failed after ${duration}ms", e)
            return errorResponse("Tool execution failed: ${e.localizedMessage}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 并发控制
    // ══════════════════════════════════════════════════════════════

    /**
     * 获取执行许可。
     * - 并发安全工具：获取全局许可
     * - 非并发安全工具：获取全局许可 + 工具专属串行锁
     */
    private suspend fun acquireExecutionPermit(tool: Tool) {
        // 非并发安全工具需要先获取专属锁
        if (!tool.isConcurrencySafe) {
            val lock = serialExecutionLocks.getOrPut(tool.name) { Semaphore(1) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                lock.acquire()
            }
        }

        // 获取全局许可
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            globalSemaphore.acquire()
        }
    }

    /**
     * 释放执行许可。
     */
    private fun releaseExecutionPermit(tool: Tool) {
        globalSemaphore.release()

        if (!tool.isConcurrencySafe) {
            serialExecutionLocks[tool.name]?.release()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════

    /**
     * 创建成功响应。
     */
    fun successResponse(text: String): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
    }

    /**
     * 创建错误响应。
     */
    fun errorResponse(message: String): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Error: $message")
            })
        })
        put("isError", true)
    }

    /**
     * 创建结构化响应。
     */
    fun structuredResponse(text: String, structuredData: JSONObject): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", "JSON_DATA: " + structuredData.toString())
            })
        })
    }
}
