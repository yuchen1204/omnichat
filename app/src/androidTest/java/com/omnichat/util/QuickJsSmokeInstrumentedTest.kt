package com.omnichat.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsSmokeInstrumentedTest {
    @Test
    fun nativeQuickJsLoadsAssetEvaluatesBinaryBufferAndClosesContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetScript = context.assets.open("quickjs_smoke.js").use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }

        QuickJsSmokeAdapter().use { adapter ->
            val result = adapter.probe(byteArrayOf(0, 1, 2, 3, -1), assetScript)

            assertTrue(result.ok)
            assertEquals(5, result.length)
        }
    }
}
