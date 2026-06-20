package com.omnichat.agent

import android.content.Context
import android.util.Log
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * SubAgent 文件操作审核管理器。
 *
 * 负责向 MainAgent 发送审核请求，处理超时和重试逻辑。
 */
object SubAgentApprovalManager {
    private const val TAG = "SubAgentApproval"
    private const val TIMEOUT_MS = 60_000L  // 60 秒
    private const val MAX_RETRIES = 1       // 最多重试一次

    /**
     * 请求 MainAgent 审核文件操作。
     * 阻塞等待结果，超时后重试一次。
     *
     * @param context Android Context
     * @param request 审核请求
     * @return 审核结果
     */
    suspend fun requestApproval(
        context: Context,
        request: SubAgentApprovalRequest
    ): ApprovalResult = withContext(Dispatchers.Default) {
        var lastResult: ApprovalResult = ApprovalResult.Timeout

        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                Log.d(TAG, "[${request.requestId}] Retry attempt $attempt")
            }

            lastResult = try {
                withTimeout(TIMEOUT_MS) {
                    callMainAgentForApproval(context, request)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "[${request.requestId}] Approval timeout on attempt ${attempt + 1}")
                ApprovalResult.Timeout
            }

            if (lastResult !is ApprovalResult.Timeout) {
                return@withContext lastResult
            }
        }

        // 两次超时后默认拒绝
        ApprovalResult.Rejected("Approval timeout after ${MAX_RETRIES + 1} attempts")
    }

    /**
     * 调用 MainAgent 独立审核 API。
     * 不走对话流，直接调用 LLM 做快速判断。
     */
    private suspend fun callMainAgentForApproval(
        context: Context,
        request: SubAgentApprovalRequest
    ): ApprovalResult = withContext(Dispatchers.IO) {
        val repository = AppRepository(AppDatabase.getDatabase(context))
        val config = repository.getDefaultProvider()
            ?: return@withContext ApprovalResult.Rejected("No default model provider")

        val systemPrompt = buildApprovalPrompt(request)
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "请审核以下文件操作请求。")
            })
        }

        val response = ApiClient.executeMessageCompletion(
            config = config,
            systemPrompt = systemPrompt,
            messages = messages,
            tools = null  // 审核不需要工具
        ) ?: return@withContext ApprovalResult.Rejected("Empty response from MainAgent")

        parseApprovalResponse(response.optString("content", ""))
    }

    /**
     * 构建审核提示词。
     */
    private fun buildApprovalPrompt(request: SubAgentApprovalRequest): String {
        val originalPathsText = request.originalPaths.joinToString("\n") { path ->
            "- ${path.paramKey}: ${path.original}"
        }

        val correctedPathsText = request.correctedPaths?.let { corrected ->
            "\n\n纠正后路径:\n" + corrected.joinToString("\n") { path ->
                val corrected = path.corrected ?: path.original
                "- ${path.paramKey}: $corrected"
            }
        } ?: ""

        return """
你是 MainAgent，负责审核 SubAgent 的文件操作请求。

## 任务背景
SubAgent 类型: ${request.agentType}
任务描述: ${request.taskDescription}

## 待审核操作
工具: ${request.toolName}
操作类型: ${request.operationType.name}

原始路径:
$originalPathsText
$correctedPathsText

## 审核标准
1. **路径合理性**: 路径是否在预期范围内？是否访问敏感目录？
2. **操作匹配性**: 操作类型是否符合任务描述？
3. **风险判断**: 该操作是否可能造成不可逆的损害？

## 输出格式
返回 JSON：
{
  "decision": "approve" | "reject",
  "reason": "简短说明理由",
  "confidence": "high" | "medium" | "low"
}
""".trimIndent()
    }

    /**
     * 解析 MainAgent 审核响应。
     */
    private fun parseApprovalResponse(content: String): ApprovalResult {
        return try {
            // 尝试提取 JSON 块
            val jsonStr = extractJsonFromResponse(content)
            val json = JSONObject(jsonStr)

            when (json.optString("decision")) {
                "approve" -> ApprovalResult.Approved(json.optString("reason"))
                "reject" -> ApprovalResult.Rejected(json.optString("reason", "Rejected by MainAgent"))
                else -> ApprovalResult.Rejected("Invalid decision in response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse approval response: $content", e)
            // 如果无法解析，检查是否包含 approve 关键词
            if (content.contains("approve", ignoreCase = true)) {
                ApprovalResult.Approved()
            } else {
                ApprovalResult.Rejected("Failed to parse MainAgent response")
            }
        }
    }

    /**
     * 从响应中提取 JSON 块。
     * 支持纯 JSON 和 ```json ... ``` 包裹的格式。
     */
    private fun extractJsonFromResponse(content: String): String {
        val trimmed = content.trim()

        // 尝试提取 ```json ... ``` 块
        val jsonBlockRegex = Regex("""```json\s*([\s\S]*?)\s*```""", RegexOption.MULTILINE)
        val match = jsonBlockRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 尝试提取 { ... } 块
        val startIndex = trimmed.indexOf('{')
        val endIndex = trimmed.lastIndexOf('}')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return trimmed.substring(startIndex, endIndex + 1)
        }

        return trimmed
    }
}