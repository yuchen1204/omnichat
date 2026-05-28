package com.example.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置工具共享的工具函数。
 */
object ToolUtils {

    /** 构造统一的成功响应 */
    fun successResponse(text: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
    }

    /** 构造统一的错误响应 */
    fun errorResponse(message: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", message)
            })
        })
        put("isError", true)
    }

    /** 验证 HEX 颜色格式 #RRGGBB 或 #RRGGBBAA */
    fun isValidHex(hex: String?): Boolean {
        if (hex == null) return false
        return Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$").matches(hex)
    }

    /**
     * 将文本拆分为 token 集合：中文字符 + 中文字符 bigram + 英文/数字整词。
     * 用于 search_memory 的中文友好匹配。
     */
    fun bigramTokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val cjkRange = '一'..'鿿'
        val buffer = StringBuilder()

        for (ch in text) {
            if (ch in cjkRange) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch in "，。！？、；：\u201C\u201D\u2018\u2019（）【】《》,.!?;:\"'()[]<>") {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString().lowercase())
        }

        // 中文字符 bigram（仅对原文中相邻的 CJK 字符生成）
        var prevCjk: Char? = null
        for (ch in text) {
            if (ch in cjkRange) {
                if (prevCjk != null) {
                    tokens.add("$prevCjk$ch")
                }
                prevCjk = ch
            } else {
                prevCjk = null
            }
        }

        return tokens
    }
}
