package com.example.network

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
import java.io.File
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
     * 将图片转换为 Base64 Data URL。
     * 支持本地 file 路径、file:// URI、content:// URI 和已有的 Data URL。
     * 自动压缩大图片以避免超过 API 限制。
     *
     * @param context android.content.Context 用于解析 URI
     * @param imagePath 本地文件路径、URI 或 Data URL
     * @param maxWidth 最大宽度，超过会压缩
     * @param quality JPEG 压缩质量 (0-100)
     * @return Base64 Data URL 或 null（转换失败时）
     */
    private fun imageToBase64DataUrl(
        context: Context?,
        imagePath: String,
        maxWidth: Int = 1024,
        quality: Int = 85
    ): String? {
        // 如果已经是 Data URL，直接返回
        if (imagePath.startsWith("data:image/")) {
            return imagePath
        }

        return try {
            val uri = Uri.parse(imagePath)
            val scheme = uri.scheme
            val isContentUri = scheme == "content" || scheme == "android.resource"

            // 1. 读取图片尺寸 (inJustDecodeBounds = true)
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            if (isContentUri) {
                if (context == null) return null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            } else {
                val filePath = if (scheme == "file") uri.path ?: imagePath else imagePath
                val file = File(filePath)
                if (!file.exists()) return null
                java.io.FileInputStream(file).use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            }

            // 计算采样率
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxWidth * 2) {
                sampleSize *= 2
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            // 2. 解码图片
            val bitmap = if (isContentUri) {
                if (context == null) return null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                }
            } else {
                val filePath = if (scheme == "file") uri.path ?: imagePath else imagePath
                val file = File(filePath)
                java.io.FileInputStream(file).use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                }
            } ?: return null

            // 如果仍然太大，进一步缩放
            val scaledBitmap = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val newHeight = (bitmap.height * ratio).toInt()
                android.graphics.Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
                    .also { if (it != bitmap) bitmap.recycle() }
            } else {
                bitmap
            }

            // 压缩为 JPEG
            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
            scaledBitmap.recycle()

            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 构建消息内容。
     * 如果消息包含图片，返回 multimodal content 数组；否则返回纯文本字符串。
     */
    private fun buildMessageContent(context: Context?, msg: com.example.data.Message): Any {
        // 如果没有图片，直接返回文本
        if (msg.imagePath.isNullOrBlank()) {
            return msg.content
        }

        // 有图片，构建 multimodal content 数组
        val imageUrl = imageToBase64DataUrl(context, msg.imagePath)
        
        // 如果图片转换失败，直接退化为纯文本
        if (imageUrl == null) {
            return msg.content
        }

        val contentArray = JSONArray()

        // 添加文本部分
        if (msg.content.isNotBlank()) {
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", msg.content)
            })
        }

        // 添加图片部分
        contentArray.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", imageUrl)
            })
        })

        return contentArray
    }

    /**
     * Parses a JSON object string into a map of header name → value.
     * Returns an empty map on blank input or parse errors.
     */
    private fun parseCustomHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank() || headersJson.trim() == "{}") return emptyMap()
        return try {
            val obj = JSONObject(headersJson)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key) }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Applies custom headers from a ModelConfig to a Request.Builder.
     */
    private fun Request.Builder.applyCustomHeaders(config: ModelConfig): Request.Builder {
        parseCustomHeaders(config.customHeaders).forEach { (name, value) ->
            addHeader(name, value)
        }
        return this
    }

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
    suspend fun fetchOpenAIModels(endpoint: String, apiKey: String, customHeaders: String = "{}"): List<JSONObject> = withContext(Dispatchers.IO) {
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

        // 注入自定义请求头
        parseCustomHeaders(customHeaders).forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
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
        userPrompt: String,
        tools: JSONArray? = null
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

            if (tools != null && tools.length() > 0) {
                put("tools", tools)
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
        requestBuilder.applyCustomHeaders(config)

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
        history: List<com.example.data.Message>,
        tools: JSONArray? = null,
        context: Context? = null
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

            if (tools != null && tools.length() > 0) {
                put("tools", tools)
            }

            val messagesArray = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            history.forEach { msg ->
                val messageObj = JSONObject().apply {
                    put("role", msg.role)
                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                    if (msg.toolCallsJson != null) {
                        put("tool_calls", JSONArray(msg.toolCallsJson))
                    }
                }

                // 处理 multimodal 内容
                val content = buildMessageContent(context, msg)
                if (content is JSONArray) {
                    messageObj.put("content", content)
                } else {
                    messageObj.put("content", content as String)
                }

                messagesArray.put(messageObj)
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
        requestBuilder.applyCustomHeaders(config)

        var retryCount = 0
        val maxRetries = 3
        var success = false

        while (retryCount <= maxRetries && !success) {
            var response: okhttp3.Response? = null
            try {
                if (retryCount > 0) {
                    emit("INFO: 正在尝试第 $retryCount 次重连...")
                    kotlinx.coroutines.delay(2000) // 等待 2 秒后重试
                    emit("RETRY_RESET:") // 通知 UI 重置当前累积状态
                }

                response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: "Unknown API response"
                    emit("ERROR: HTTP error code ${response.code} Details: $errorMsg")
                    return@flow
                }

                val source = response.body?.source() ?: return@flow
                val reader = BufferedReader(source.inputStream().reader())

                var line: String?
                success = true // 成功建立连接并获取到流

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
                                
                                // 1. Handle content
                                val content = delta?.optString("content")
                                if (!content.isNullOrEmpty() && content != "null") {
                                    emit(content)
                                }
                                
                                // 2. Handle tool_calls
                                val toolCalls = delta?.optJSONArray("tool_calls")
                                if (toolCalls != null && toolCalls.length() > 0) {
                                    // We prefix tool calls with a special marker for the ViewModel to catch
                                    emit("TOOL_CALL_DELTA:${toolCalls.toString()}")
                                }
                            }
                        } catch (e: Exception) {
                            // Handle line fragmentation
                        }
                    }
                }
            } catch (e: IOException) {
                retryCount++
                if (retryCount > maxRetries) {
                    emit("ERROR: API stream read connection error - ${e.localizedMessage}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit("ERROR: Streaming failure - ${e.localizedMessage}")
                break
            } finally {
                response?.close()
            }
        }
    }.flowOn(Dispatchers.IO)
}
