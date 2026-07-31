package com.omnichat.util

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsSmokeAdapterTest {
    @Test
    fun evaluatesBinaryLengthAndJsonResultOnDedicatedContext() {
        val context = RecordingContext()
        val adapter = QuickJsSmokeAdapter(QuickJsSmokeContextFactory { context })
        val input = byteArrayOf(0, 1, -1, 127)

        try {
            val result = adapter.probe(input)

            assertEquals(QuickJsSmokeResult(ok = true, length = input.size), result)
            assertNotSame(input, context.receivedBytes)
            assertArrayEquals(input, context.receivedBytes)
            assertTrue(context.evaluatedScript.contains("JSON.stringify"))
            assertTrue(context.evaluatedScript.contains("Uint8Array"))
            assertTrue(context.evaluatedScript.contains("byteLength"))
            assertTrue(context.closed.get())
        } finally {
            adapter.close()
        }
    }

    @Test
    fun closesContextWhenEvaluationFails() {
        val context = RecordingContext(evaluationFailure = IllegalStateException("script failed"))
        val adapter = QuickJsSmokeAdapter(QuickJsSmokeContextFactory { context })

        try {
            try {
                adapter.probe(byteArrayOf(42))
                throw AssertionError("probe should fail")
            } catch (error: Exception) {
                assertTrue(error.message.orEmpty().contains("script failed"))
            }
            assertTrue(context.closed.get())
        } finally {
            adapter.close()
        }
    }

    private class RecordingContext(
        private val evaluationFailure: Exception? = null
    ) : QuickJsSmokeContext {
        val closed = AtomicBoolean(false)
        lateinit var receivedBytes: ByteArray
        lateinit var evaluatedScript: String

        override fun setInput(bytes: ByteArray) {
            receivedBytes = bytes
        }

        override fun evaluate(script: String): String {
            evaluatedScript = script
            evaluationFailure?.let { throw it }
            return "{\"ok\":true,\"length\":${receivedBytes.size}}"
        }

        override fun close() {
            assertFalse("context must be closed only once", closed.getAndSet(true))
        }
    }
}
