package com.omnichat.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledQuickJsDocumentRuntimeInstrumentedTest {
    @Test
    fun realQuickJsAdapterEvaluatesBundledSynchronousPluginAndCloses() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        BundledQuickJsDocumentRuntime(testAssets).use { runtime ->
            val result = runtime.parse(
                pluginAsset = "document_plugins/test.js",
                input = JsDocumentInput(
                    name = "fixture.bin",
                    mimeType = "application/octet-stream",
                    bytes = byteArrayOf(4, 5, -1)
                )
            )

            assertEquals("fixture.bin:application/octet-stream:3:255", result.text)
            assertEquals(listOf("real-adapter"), result.warnings)
            assertTrue(result.text.isNotBlank())
        }
    }
}
