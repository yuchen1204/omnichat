package com.example.network

import com.example.data.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    /**
     * Helper to check if a response body is actually HTML (common error with wrong endpoints)
     */
    private fun checkHtmlResponse(body: String) {
        val trimmed = body.trim()
        if (trimmed.startsWith("<!doctype", ignoreCase = true) || 
            trimmed.startsWith("<html", ignoreCase = true) || 
            trimmed.startsWith("<head", ignoreCase = true)) {
            throw IOException("API 接口返回了 HTML 网页而非 JSON。这通常是由于：\n1. Endpoint 地址配置错误（例如漏掉了 /v1）\n2. 网络需要网页登录认证 (Captive Portal)\n3. 代理服务器拦截了请求。")
        }
    }

    /**
     * Gets available model IDs from standard OpenAI compatibility GET /v1/models endpoint.
     */
    suspend fun fetchOpenAIModels(endpoint: String, apiKey: String): List<JSONObject> = withContext(Dispatchers.IO) {
        var sanitizedUrl = endpoint.trim().removeSuffix("/")
        
        // 纠错：处理常见的 Endpoint 错误
        if (sanitizedUrl.endsWith("/chat/completions")) {
            sanitizedUrl = sanitizedUrl.removeSuffix("/chat/completions")
        }
        if (sanitizedUrl == "https://api.openai.com") {
            sanitizedUrl = "https://api.openai.com/v1"
        }
        
        val url = if (sanitizedUrl.endsWith("/models")) sanitizedUrl else "$sanitizedUrl/models"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                // 即便失败了，也检查一下是不是返回了 HTML
                checkHtmlResponse(bodyStr)
                throw IOException("HTTP 错误: ${response.code} ${response.message}\n$bodyStr")
            }

            checkHtmlResponse(bodyStr)
            
            val json = try {
                JSONObject(bodyStr)
            } catch (e: Exception) {
                throw IOException("JSON 解析失败。请检查 Endpoint 是否正确。\n返回内容：${if(bodyStr.length > 200) bodyStr.take(200)+"..." else bodyStr}")
            }

            val dataArray = json.optJSONArray("data")
            val modelsList = mutableListOf<JSONObject>()
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i)
                    if (item != null && !item.optString("id").isNullOrBlank()) {
                        modelsList.add(item)
                    }
                }
            }
            modelsList.sortedBy { it.optString("id") }
        }
    }

    /**
     * Non-streaming API completion for memory manager or text responses
     */
    suspend fun executeCompletion(
        config: ModelConfig,
        systemPrompt: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val endpoint = config.endpoint.trim().removeSuffix("/")
        val apiKey = config.apiKey.trim()
        val model = config.selectedModelId

        val body = JSONObject().apply {
            put("model", model)
            
            // Inject thinking configuration parameters
            if (config.enableThinking) {
                val effort = config.thinkingEffort
                put("reasoning_effort", if (effort == "xhigh") "high" else effort)
                
                put("thinking", JSONObject().apply {
                    put("type", "enabled")
                    put("budget_tokens", when(effort) {
                        "low" -> 1024
                        "medium" -> 2048
                        "high" -> 4096
                        "xhigh" -> 8192
                        else -> 2048
                    })
                })
            } else {
                put("exclude_thinking", true)
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            val messagesArray = JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            put("messages", messagesArray)
        }
        val requestBodyJson = body.toString()

        val url = if (endpoint.endsWith("/chat/completions")) endpoint else "$endpoint/chat/completions"
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(mediaTypeJson))
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    checkHtmlResponse(bodyStr)
                    println("API Completion Code: ${response.code} error message: $bodyStr")
                    return@withContext null
                }
                
                checkHtmlResponse(bodyStr)
                val json = JSONObject(bodyStr)
                val choicesArr = json.optJSONArray("choices")
                if (choicesArr != null && choicesArr.length() > 0) {
                    val messageObj = choicesArr.optJSONObject(0)?.optJSONObject("message")
                    return@withContext messageObj?.optString("content")
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Executes SSE streaming completions and emits incremental text updates as Flow
     */
    fun executeStreamingChat(
        config: ModelConfig,
        systemPrompt: String,
        history: List<com.example.data.Message>
    ): Flow<String> = flow {
        val endpoint = config.endpoint.trim().removeSuffix("/")
        val apiKey = config.apiKey.trim()
        val model = config.selectedModelId

        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            
            // Inject thinking configuration parameters
            if (config.enableThinking) {
                val effort = config.thinkingEffort
                put("reasoning_effort", if (effort == "xhigh") "high" else effort)
                
                put("thinking", JSONObject().apply {
                    put("type", "enabled")
                    put("budget_tokens", when(effort) {
                        "low" -> 1024
                        "medium" -> 2048
                        "high" -> 4096
                        "xhigh" -> 8192
                        else -> 2048
                    })
                })
            } else {
                put("exclude_thinking", true)
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }

            val messagesArray = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            history.forEach { msg ->
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            put("messages", messagesArray)
        }
        val requestBodyJson = body.toString()

        val url = if (endpoint.endsWith("/chat/completions")) endpoint else "$endpoint/chat/completions"
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(mediaTypeJson))
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                val errorMsg = response.body?.string() ?: "Unknown API response"
                emit("ERROR: HTTP error code ${response.code} Details: $errorMsg")
                response.close()
                return@flow
            }

            val source = response.body?.source() ?: return@flow
            val reader = BufferedReader(source.inputStream().reader())

            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isEmpty()) continue

                if (currentLine.startsWith("data:")) {
                    val dataContent = currentLine.substringAfter("data:").trim()
                    if (dataContent == "[DONE]") {
                        break
                    }
                    try {
                        val dataJson = JSONObject(dataContent)
                        val choices = dataJson.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.optJSONObject(0)?.optJSONObject("delta")
                            val content = delta?.optString("content")
                            if (!content.isNullOrEmpty()) {
                                emit(content)
                            }
                        }
                    } catch (e: Exception) {
                        // Handle line fragmentation
                    }
                }
            }
            response.close()
        } catch (e: IOException) {
            emit("ERROR: API stream read connection error - ${e.localizedMessage}")
        } catch (e: Exception) {
            emit("ERROR: Streaming failure - ${e.localizedMessage}")
        }
    }.flowOn(Dispatchers.IO)
}
