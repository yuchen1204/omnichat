package com.example.workspace

import android.util.Log

/**
 * 进度摘要生成器：每隔固定次数的工具调用自动生成一条摘要，供 UI 或日志使用。
 */
class AgentProgressSummarizer(
    private val agentName: String,
    private val messageHistory: () -> List<AgentMessage>
) {
    companion object {
        const val SUMMARY_INTERVAL = 5
        private const val TAG = "ProgressSummary"
    }

    private var lastSummaryIndex: Int = 0
    private var toolCallCount: Int = 0

    /**
     * 每次工具调用完成后调用；达到 SUMMARY_INTERVAL 次时返回摘要字符串，否则返回 null。
     */
    fun onToolCallCompleted(): String? {
        toolCallCount++
        return if (toolCallCount % SUMMARY_INTERVAL == 0) {
            generateSummary()
        } else {
            null
        }
    }

    /**
     * 无条件生成摘要，用于 Agent 结束或用户主动查询进度时。
     */
    fun forceSummarize(): String {
        return generateSummary()
    }

    /**
     * 从 lastSummaryIndex 之后的消息中提取关键动作，拼装摘要。
     */
    private fun generateSummary(): String {
        val messages = messageHistory()
        val recent = if (lastSummaryIndex < messages.size) {
            messages.subList(lastSummaryIndex, messages.size)
        } else {
            emptyList()
        }
        lastSummaryIndex = messages.size

        val actions = extractKeyActions(recent)
        val sb = StringBuilder("[$agentName 进度摘要]")
        if (actions.isEmpty()) {
            sb.append("\n- (无关键动作)")
        } else {
            for (action in actions) {
                sb.append("\n- ").append(action)
            }
        }
        val summary = sb.toString()
        Log.d(TAG, summary)
        return summary
    }

    /**
     * 从消息列表中提取最多 5 条关键动作：
     * - assistant 消息包含 创建/写入/读取/搜索/分析/完成
     * - tool 消息包含 success/error
     * - system 消息包含 新任务开始
     */
    private fun extractKeyActions(messages: List<AgentMessage>): List<String> {
        val keywords = listOf("创建", "写入", "读取", "搜索", "分析", "完成")
        val results = mutableListOf<String>()

        for (msg in messages) {
            if (results.size >= 5) break
            when (msg.role) {
                "assistant" -> {
                    // 从 assistant 消息中提取包含关键动词的句子片段
                    for (kw in keywords) {
                        if (msg.content.contains(kw)) {
                            val snippet = msg.content
                                .lineSequence()
                                .firstOrNull { it.contains(kw) }
                                ?.trim()
                                ?.take(80)
                                ?: kw
                            results.add(snippet)
                            break
                        }
                    }
                }
                "tool" -> {
                    // 工具消息：标记成功或失败
                    when {
                        msg.content.contains("success", ignoreCase = true) ->
                            results.add("工具完成: ${msg.content.take(60)}")
                        msg.content.contains("error", ignoreCase = true) ->
                            results.add("工具出错: ${msg.content.take(60)}")
                    }
                }
                "system" -> {
                    // 系统消息：标记新任务开始
                    if (msg.content.contains("新任务开始")) {
                        results.add("新任务开始: ${msg.content.take(60)}")
                    }
                }
            }
        }
        return results
    }

    /**
     * 重置计数器，用于 Agent 重启或切换任务时。
     */
    fun reset() {
        lastSummaryIndex = 0
        toolCallCount = 0
    }
}
