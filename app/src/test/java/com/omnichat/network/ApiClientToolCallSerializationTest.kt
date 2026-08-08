package com.omnichat.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientToolCallSerializationTest {

    @Test
    fun nonGeminiToolHistoryContainsOnlyStandardChatCompletionFields() {
        val source = JSONArray().put(JSONObject().apply {
            put("id", "call_123")
            put("type", "function")
            put("thought_signature", "gemini-signature")
            put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", "gemini-signature")))
            put("function", JSONObject().apply {
                put("name", "get_weather")
                put("arguments", "{\"city\":\"Paris\"}")
                put("thought", "provider-only")
            })
        })

        val result = ApiClient.toolCallsForRequest(source.toString(), isGemini = false)
        val toolCall = result.getJSONObject(0)

        assertEquals("call_123", toolCall.getString("id"))
        assertEquals("function", toolCall.getString("type"))
        assertEquals("get_weather", toolCall.getJSONObject("function").getString("name"))
        assertFalse(toolCall.has("thought_signature"))
        assertFalse(toolCall.has("extra_content"))
        assertFalse(toolCall.getJSONObject("function").has("thought"))
    }

    @Test
    fun geminiToolHistoryPreservesAndCompletesThoughtSignature() {
        val source = JSONArray().put(JSONObject().apply {
            put("id", "call_456")
            put("function", JSONObject().apply {
                put("name", "get_weather")
                put("arguments", "{}")
            })
        })

        val result = ApiClient.toolCallsForRequest(source.toString(), isGemini = true)
        val toolCall = result.getJSONObject(0)

        assertEquals("function", toolCall.getString("type"))
        assertEquals("skip_thought_signature_validator", toolCall.getString("thought_signature"))
        assertEquals(
            "skip_thought_signature_validator",
            toolCall.getJSONObject("extra_content")
                .getJSONObject("google")
                .getString("thought_signature")
        )
        assertTrue(toolCall.has("function"))
    }
}
