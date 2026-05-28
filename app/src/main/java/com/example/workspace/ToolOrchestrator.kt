package com.example.workspace

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 工具调用描述。
 *
 * @property name 工具名称
 * @property args 工具参数 JSON
 * @property callId 工具调用 ID，用于匹配结果
 */
data class ToolCall(
    val name: String,
    val args: JSONObject,
    val callId: String,
)

/**
 * 工具执行结果。
 *
 * @property callId 对应的工具调用 ID
 * @property content 结果内容（成功时为工具返回值，失败时为错误信息）
 * @property isError 是否为错误结果
 */
data class ToolResult(
    val callId: String,
    val content: String,
    val isError: Boolean = false,
)

/**
 * 工具调用批次：连续的只读工具可并行执行，写入工具必须串行执行。
 *
 * @property calls 批次内的工具调用列表
 * @property isParallel 是否为并行执行批次（只读工具）
 */
data class ToolBatch(
    val calls: List<ToolCall>,
    val isParallel: Boolean,
)

/**
 * 工具并发执行引擎。
 *
 * 将工具调用分区为只读批次（并行）和写入批次（串行），
 * 在保证正确性的前提下最大化并发执行效率。
 *
 * @property onToolCall 工具执行回调，签名与 [AgentRunner.onToolCall] 一致
 */
class ToolOrchestrator(
    private val onToolCall: suspend (agentName: String, toolName: String, args: JSONObject, callId: String) -> String,
) {
    companion object {
        private const val TAG = "ToolOrchestrator"

        /** 只读工具集合，这些工具可以安全地并行执行 */
        val READ_ONLY_TOOLS: Set<String> = setOf(
            "read_file",
            "list_directory",
            "search_files",
            "get_file_info",
            "search_memory",
            "get_current_time",
        )

        /**
         * 判断工具是否为只读工具。
         *
         * @param toolName 工具名称
         * @return 如果是只读工具返回 true
         */
        fun isReadOnlyTool(toolName: String): Boolean = toolName in READ_ONLY_TOOLS

        /**
         * 将工具调用列表分区为执行批次。
         *
         * 连续的只读工具合并为一个并行批次，每个写入工具独立为一个串行批次。
         *
         * @param calls 工具调用列表
         * @return 执行批次列表
         */
        fun partitionToolCalls(calls: List<ToolCall>): List<ToolBatch> {
            if (calls.isEmpty()) return emptyList()

            val batches = mutableListOf<ToolBatch>()
            val readBuffer = mutableListOf<ToolCall>()

            for (call in calls) {
                if (isReadOnlyTool(call.name)) {
                    // 只读工具累积到缓冲区，后续合并为并行批次
                    readBuffer.add(call)
                } else {
                    // 写入工具前，先 flush 已累积的只读工具
                    if (readBuffer.isNotEmpty()) {
                        batches.add(ToolBatch(readBuffer.toList(), isParallel = true))
                        readBuffer.clear()
                    }
                    // 写入工具独立为串行批次
                    batches.add(ToolBatch(listOf(call), isParallel = false))
                }
            }

            // flush 剩余的只读工具
            if (readBuffer.isNotEmpty()) {
                batches.add(ToolBatch(readBuffer.toList(), isParallel = true))
            }

            return batches
        }
    }

    /**
     * 执行一批工具调用，只读工具并行、写入工具串行。
     *
     * @param calls 待执行的工具调用列表
     * @param agentName 调用者 Agent 名称（传递给 onToolCall 回调）
     * @return 与输入顺序一致的工具执行结果列表
     */
    suspend fun executeToolCalls(calls: List<ToolCall>, agentName: String): List<ToolResult> {
        if (calls.isEmpty()) return emptyList()

        val batches = partitionToolCalls(calls)
        val results = mutableListOf<ToolResult>()

        for (batch in batches) {
            if (batch.isParallel) {
                // 并行执行只读工具，使用 coroutineScope 确保所有子协程完成后才继续
                val batchResults = coroutineScope {
                    batch.calls.map { call ->
                        async { executeSingle(call, agentName) }
                    }.awaitAll()
                }
                results.addAll(batchResults)
            } else {
                // 串行执行写入工具
                for (call in batch.calls) {
                    results.add(executeSingle(call, agentName))
                }
            }
        }

        return results
    }

    /**
     * 执行单个工具调用，捕获异常并转换为错误结果。
     *
     * FIX (Bug #5): 必须显式重新抛出 [kotlinx.coroutines.CancellationException]，
     * 否则协程取消信号会被吞掉转为普通错误结果，runTurn 会继续循环，
     * killTeammate 的 5 秒 join 超时只能强制终止，
     * 无法让 Agent 在工具调用层面响应取消。
     */
    private suspend fun executeSingle(call: ToolCall, agentName: String): ToolResult {
        return try {
            val content = onToolCall(agentName, call.name, call.args, call.callId)
            ToolResult(callId = call.callId, content = content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消信号必须传播，不能转换为普通错误
            throw e
        } catch (e: Exception) {
            // 捕获工具执行异常，转换为错误结果而非崩溃整个批次
            Log.e(TAG, "Tool '${call.name}' (callId=${call.callId}) failed: ${e.message}", e)
            ToolResult(
                callId = call.callId,
                content = "Error: ${e.message ?: "unknown error"}",
                isError = true,
            )
        }
    }
}
