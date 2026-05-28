package com.example.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量 Schema Builder DSL，用于替代手写 JSONObject 嵌套构建。
 *
 * 使用前（20 行）:
 *   McpTool(..., inputSchema = JSONObject().apply {
 *       put("type", "object")
 *       put("properties", JSONObject().apply {
 *           put("path", JSONObject().apply { put("type", "string"); put("description", "...") })
 *       })
 *       put("required", JSONArray().apply { put("path") })
 *   })
 *
 * 使用后（6 行）:
 *   McpTool(..., inputSchema = schema {
 *       prop("path", "string", "Relative file path...")
 *       prop("content", "string", "Text content to write")
 *       required("path", "content")
 *   })
 */
object ToolSchemaDsl {

    fun schema(block: SchemaBuilder.() -> Unit): JSONObject {
        return SchemaBuilder().apply(block).build()
    }

    class SchemaBuilder {
        private val properties = JSONObject()
        private val requiredList = mutableListOf<String>()

        fun prop(name: String, type: String, desc: String, block: (PropBuilder.() -> Unit)? = null) {
            properties.put(name, PropBuilder(type, desc).apply { block?.invoke(this) }.build())
        }

        /** 直接插入预构建的属性 JSONObject（如带 pattern 约束的 colorProp） */
        fun put(name: String, json: JSONObject) {
            properties.put(name, json)
        }

        fun required(vararg names: String) {
            requiredList.addAll(names)
        }

        fun build(): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (requiredList.isNotEmpty()) {
                put("required", JSONArray(requiredList))
            }
        }
    }

    class PropBuilder(private val type: String, private val desc: String) {
        private val json = JSONObject().apply {
            put("type", type)
            put("description", desc)
        }

        fun enum(vararg values: String) {
            json.put("enum", JSONArray(values.toList()))
        }

        fun pattern(regex: String) {
            json.put("pattern", regex)
        }

        fun items(block: PropBuilder.() -> Unit) {
            json.put("items", PropBuilder("string", "").apply(block).build())
        }

        fun properties(block: SchemaBuilder.() -> Unit) {
            val nested = SchemaBuilder().apply(block).build()
            json.put("properties", nested.getJSONObject("properties"))
            if (nested.has("required")) {
                json.put("required", nested.getJSONArray("required"))
            }
        }

        fun additionalProperties(block: PropBuilder.() -> Unit) {
            json.put("additionalProperties", PropBuilder("string", "").apply(block).build())
        }

        fun build(): JSONObject = json
    }
}
