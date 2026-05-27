package com.example.workspace

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolOrchestratorTest {

    // ─── partitionToolCalls ────────────────────────────────────────────────

    @Test
    fun `partitionToolCalls separates read and write tools`() {
        val calls = listOf(
            ToolCall("read_file", JSONObject(), "c1"),
            ToolCall("read_file", JSONObject(), "c2"),
            ToolCall("write_file", JSONObject(), "c3"),
            ToolCall("read_file", JSONObject(), "c4"),
            ToolCall("search_files", JSONObject(), "c5"),
        )

        val batches = ToolOrchestrator.partitionToolCalls(calls)

        // 3 batches: [c1,c2] parallel, [c3] serial, [c4,c5] parallel
        assertEquals(3, batches.size)
        assertTrue(batches[0].isParallel)
        assertEquals(2, batches[0].calls.size)
        assertEquals("c1", batches[0].calls[0].callId)
        assertEquals("c2", batches[0].calls[1].callId)

        // write_file 独立为 serial batch
        assertFalse(batches[1].isParallel)
        assertEquals(1, batches[1].calls.size)
        assertEquals("c3", batches[1].calls[0].callId)

        // 后续 read_file + search_files 合并为 parallel batch
        assertTrue(batches[2].isParallel)
        assertEquals(2, batches[2].calls.size)
        assertEquals("c4", batches[2].calls[0].callId)
        assertEquals("c5", batches[2].calls[1].callId)
    }

    @Test
    fun `partitionToolCalls all read tools become single parallel batch`() {
        val calls = listOf(
            ToolCall("read_file", JSONObject(), "c1"),
            ToolCall("list_directory", JSONObject(), "c2"),
            ToolCall("get_file_info", JSONObject(), "c3"),
        )

        val batches = ToolOrchestrator.partitionToolCalls(calls)

        assertEquals(1, batches.size)
        assertTrue(batches[0].isParallel)
        assertEquals(3, batches[0].calls.size)
    }

    @Test
    fun `partitionToolCalls all write tools become individual serial batches`() {
        val calls = listOf(
            ToolCall("write_file", JSONObject(), "c1"),
            ToolCall("edit_file", JSONObject(), "c2"),
            ToolCall("delete_file", JSONObject(), "c3"),
        )

        val batches = ToolOrchestrator.partitionToolCalls(calls)

        assertEquals(3, batches.size)
        batches.forEach { batch ->
            assertFalse(batch.isParallel)
            assertEquals(1, batch.calls.size)
        }
    }

    @Test
    fun `partitionToolCalls empty input returns empty`() {
        val batches = ToolOrchestrator.partitionToolCalls(emptyList())
        assertTrue(batches.isEmpty())
    }

    // ─── isReadOnlyTool ────────────────────────────────────────────────────

    @Test
    fun `isReadOnlyTool returns correct results`() {
        assertTrue(ToolOrchestrator.isReadOnlyTool("read_file"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("list_directory"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("search_files"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("get_file_info"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("search_memory"))
        assertTrue(ToolOrchestrator.isReadOnlyTool("get_current_time"))

        assertFalse(ToolOrchestrator.isReadOnlyTool("write_file"))
        assertFalse(ToolOrchestrator.isReadOnlyTool("edit_file"))
        assertFalse(ToolOrchestrator.isReadOnlyTool("delete_file"))
        assertFalse(ToolOrchestrator.isReadOnlyTool("unknown_tool"))
    }

    // ─── executeToolCalls ──────────────────────────────────────────────────

    @Test
    fun `executeToolCalls preserves result order`() = runBlocking {
        val executionOrder = mutableListOf<String>()

        // 模拟工具执行延迟，验证结果仍按原始顺序返回
        val orchestrator = ToolOrchestrator { _, toolName, _, callId ->
            executionOrder.add(callId)
            "result:$toolName"
        }

        val calls = listOf(
            ToolCall("read_file", JSONObject(), "c1"),
            ToolCall("read_file", JSONObject(), "c2"),
            ToolCall("read_file", JSONObject(), "c3"),
        )

        val results = orchestrator.executeToolCalls(calls, "testAgent")

        assertEquals(3, results.size)
        assertEquals("c1", results[0].callId)
        assertEquals("result:read_file", results[0].content)
        assertFalse(results[0].isError)

        assertEquals("c2", results[1].callId)
        assertEquals("result:read_file", results[1].content)

        assertEquals("c3", results[2].callId)
        assertEquals("result:read_file", results[2].content)
    }

    @Test
    fun `executeToolCalls handles tool failure gracefully`() = runBlocking {
        val orchestrator = ToolOrchestrator { _, toolName, _, callId ->
            if (toolName == "fail_tool") {
                throw RuntimeException("tool exploded")
            }
            "ok:$callId"
        }

        val calls = listOf(
            ToolCall("read_file", JSONObject(), "c1"),
            ToolCall("fail_tool", JSONObject(), "c2"),
            ToolCall("write_file", JSONObject(), "c3"),
        )

        val results = orchestrator.executeToolCalls(calls, "testAgent")

        assertEquals(3, results.size)

        // 第一个成功
        assertEquals("c1", results[0].callId)
        assertEquals("ok:c1", results[0].content)
        assertFalse(results[0].isError)

        // 第二个失败，返回错误信息
        assertEquals("c2", results[1].callId)
        assertTrue(results[1].isError)
        assertTrue(results[1].content.contains("tool exploded"))

        // 第三个成功
        assertEquals("c3", results[2].callId)
        assertEquals("ok:c3", results[2].content)
        assertFalse(results[2].isError)
    }
}
