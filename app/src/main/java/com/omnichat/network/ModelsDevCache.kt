package com.omnichat.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * models.dev 模型能力数据的获取、缓存和查找。
 *
 * 数据来源: https://models.dev/api.json (静态 JSON, ~400KB)
 * 用途: 作为模型能力检测的补充数据源，优先级低于 provider API 的元数据，高于启发式规则。
 */
object ModelsDevCache {

    private const val API_URL = "https://models.dev/api.json"
    private val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(1)

    data class ModelDevInfo(
        val reasoning: Boolean,
        val toolCall: Boolean,
        val hasVision: Boolean,
        val contextSize: Int,
        val outputLimit: Int,
        val displayName: String
    )

    // normalizedModelId -> ModelDevInfo (扁平化所有 provider 的模型)
    private var catalog: Map<String, ModelDevInfo> = emptyMap()
    private var lastFetchTime: Long = 0L

    fun needsRefresh(): Boolean =
        catalog.isEmpty() || System.currentTimeMillis() - lastFetchTime > CACHE_TTL_MS

    /**
     * 从 models.dev 拉取全量模型数据并缓存。
     */
    suspend fun fetchAndCache(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/json")
                .build()
            val body = ApiClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("models.dev HTTP ${resp.code}")
                resp.body?.string() ?: throw RuntimeException("Empty response")
            }
            val root = JSONObject(body)
            val flat = mutableMapOf<String, ModelDevInfo>()
            for (providerKey in root.keys()) {
                val provider = root.getJSONObject(providerKey)
                if (!provider.has("models")) continue
                val models = provider.getJSONObject("models")
                for (modelKey in models.keys()) {
                    val m = models.getJSONObject(modelKey)
                    val info = ModelDevInfo(
                        reasoning = m.optBoolean("reasoning", false),
                        toolCall = m.optBoolean("tool_call", false),
                        hasVision = run {
                            val mods = m.optJSONObject("modalities")
                            val input = mods?.optJSONArray("input")
                            input != null && (0 until input.length()).any {
                                input.optString(it).equals("image", ignoreCase = true)
                            }
                        },
                        contextSize = m.optJSONObject("limit")?.optInt("context", 0) ?: 0,
                        outputLimit = m.optJSONObject("limit")?.optInt("output", 0) ?: 0,
                        displayName = m.optString("name", modelKey)
                    )
                    // 使用归一化 ID 作为 key
                    flat[normalizeModelId(modelKey)] = info
                    // 同时用 provider/model 格式存储一份
                    flat[normalizeModelId("$providerKey/$modelKey")] = info
                }
            }
            catalog = flat
            lastFetchTime = System.currentTimeMillis()
        }
    }

    /**
     * 根据模型 ID 查找能力信息。
     * 匹配策略: 归一化后精确匹配 > 归一化后互相包含匹配（取最长匹配）。
     */
    fun lookup(modelId: String): ModelDevInfo? {
        if (catalog.isEmpty()) return null
        val normalized = normalizeModelId(modelId)
        if (normalized.isEmpty()) return null

        // 1. 精确匹配
        catalog[normalized]?.let { return it }

        // 2. 最长后缀匹配: catalog 的 key 与 normalized 互相包含
        var bestMatch: ModelDevInfo? = null
        var bestLen = 0
        for ((key, info) in catalog) {
            if (normalized.endsWith(key) && key.length > bestLen) {
                bestMatch = info
                bestLen = key.length
            } else if (key.endsWith(normalized) && normalized.length > bestLen) {
                bestMatch = info
                bestLen = normalized.length
            }
        }
        return bestMatch
    }

    /**
     * 归一化模型 ID:
     * - 移除 provider/ 前缀
     * - 移除日期版本后缀 (如 -20250514, -2024-08-06)
     * - 移除点分版本号后缀 (如 -4.0 → -4, -3.5 → -3.5 保留一位小数)
     * - 转小写
     */
    private fun normalizeModelId(modelId: String): String {
        var id = modelId.lowercase()
        // 移除 provider/ 前缀 (openai/gpt-4o → gpt-4o)
        val slash = id.lastIndexOf('/')
        if (slash >= 0) id = id.substring(slash + 1)
        // 移除日期版本后缀: -20250514, -2024-08-06, -20240806
        id = id.replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
        id = id.replace(Regex("-\\d{8}$"), "")
        // 移除末尾点分版本的小数零 (4.0 → 4, 但 3.5 → 3.5)
        id = id.replace(Regex("([^.\\d])\\.0$"), "$1")
        return id
    }
}
