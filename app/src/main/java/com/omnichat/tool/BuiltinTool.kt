package com.omnichat.tool

import android.content.Context
import com.omnichat.mcp.ToolSchemaDsl
import org.json.JSONObject

/**
 * 内置工具抽象基类。
 *
 * 提供常用功能的默认实现，简化具体工具类的编写。
 *
 * 使用示例：
 * ```kotlin
 * object FileReadTool : BuiltinTool(
 *     name = "file_read",
 *     description = "Read the content of a file...",
 *     group = "files",
 *     isReadOnly = true,
 *     isConcurrencySafe = true
 * ) {
 *     override val inputSchema = schema {
 *         prop("path", "string", "File path")
 *         required("path")
 *     }
 *
 *     override fun validateInput(arguments: JSONObject): String? {
 *         if (arguments.optString("path").isBlank()) return "Path is required"
 *         return null
 *     }
 *
 *     override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
 *         val path = arguments.getString("path")
 *         // ... 实际逻辑 ...
 *         return successResponse(content)
 *     }
 * }
 * ```
 */
abstract class BuiltinTool(
    override val name: String,
    override val description: String,
    override val group: String = "core",
    override val isReadOnly: Boolean = false,
    override val isDestructive: Boolean = false,
    override val isConcurrencySafe: Boolean = true,
    override val requiresSession: Boolean = false,
    override val aliases: List<String> = emptyList(),
    override val searchHint: String? = null
) : Tool {

    // ══════════════════════════════════════════════════════════════
    // Schema 构建辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * Schema DSL 构建器。
     * 子类可使用此方法构建 inputSchema。
     */
    protected fun schema(block: ToolSchemaDsl.SchemaBuilder.() -> Unit): JSONObject =
        ToolSchemaDsl.schema(block)

    /**
     * 创建颜色属性 Schema（HEX 格式）。
     */
    protected fun colorProp(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
        put("pattern", "^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    }

    // ══════════════════════════════════════════════════════════════
    // 响应构建辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * 创建成功响应（纯文本）。
     */
    protected fun successResponse(text: String): JSONObject = JSONObject().apply {
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
    protected fun errorResponse(message: String): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Error: $message")
            })
        })
        put("isError", true)
    }

    /**
     * 创建结构化响应（文本 + JSON 数据）。
     */
    protected fun structuredResponse(text: String, structuredData: JSONObject): JSONObject = JSONObject().apply {
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

    /**
     * 创建多段内容响应。
     */
    protected fun multiPartResponse(vararg parts: String): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            parts.forEach { part ->
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", part)
                })
            }
        })
    }

    // ══════════════════════════════════════════════════════════════
    // 参数提取辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * 获取字符串参数，若为空则返回错误响应。
     */
    protected fun JSONObject.getRequiredString(key: String): Result<String> {
        val value = optString(key, "").trim()
        return if (value.isNotEmpty()) Result.success(value)
        else Result.failure(IllegalArgumentException("Parameter '$key' is required"))
    }

    /**
     * 获取可选字符串参数，支持默认值。
     */
    protected fun JSONObject.getOptionalString(key: String, default: String = ""): String =
        optString(key, default).trim().ifEmpty { default }

    /**
     * 获取整数参数，支持范围约束。
     */
    protected fun JSONObject.getIntInRange(key: String, default: Int, min: Int, max: Int): Int =
        optInt(key, default).coerceIn(min, max)

    /**
     * 获取长整数参数，支持范围约束。
     */
    protected fun JSONObject.getLongInRange(key: String, default: Long, min: Long, max: Long): Long =
        optLong(key, default).coerceIn(min, max)

    /**
     * 获取浮点数参数，支持范围约束。
     */
    protected fun JSONObject.getDoubleInRange(key: String, default: Double, min: Double, max: Double): Double =
        optDouble(key, default).coerceIn(min, max)

    /**
     * 获取布尔参数。
     */
    protected fun JSONObject.getBoolean(key: String, default: Boolean = false): Boolean =
        optBoolean(key, default)

    // ══════════════════════════════════════════════════════════════
    // 执行流程
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行工具的入口方法。
     * 调用 doExecute 并处理异常。
     */
    final override suspend fun call(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        return try {
            doExecute(context, arguments, sessionId)
        } catch (e: Exception) {
            android.util.Log.e(name, "Tool execution failed", e)
            errorResponse(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * 实际执行逻辑，由子类实现。
     */
    protected abstract suspend fun doExecute(
        context: Context,
        arguments: JSONObject,
        sessionId: Long?
    ): JSONObject

    // ══════════════════════════════════════════════════════════════
    // 默认实现
    // ══════════════════════════════════════════════════════════════

    override fun userFacingName(): String = name

    override fun validateInput(arguments: JSONObject): String? = null

    override suspend fun checkPermissions(context: Context, arguments: JSONObject): String? = null
}
