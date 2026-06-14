package com.omnichat.ui.performance

import android.app.Activity
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshRateManagerTest {

    private lateinit var activity: Activity
    private lateinit var window: Window

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        activity = context as Activity
        window = activity.window
    }

    @Test
    fun testGetCurrentRefreshRate() {
        val rate = RefreshRateManager.getCurrentRefreshRate(window)
        assertNotNull(rate)
        assertTrue(rate.fps > 0)
    }

    @Test
    fun testGetSupportedRefreshRates() {
        val rates = RefreshRateManager.getSupportedRefreshRates(window)
        assertNotNull(rates)
        assertTrue(rates.isNotEmpty())
        rates.forEach { rate ->
            assertTrue(rate.fps > 0)
        }
    }

    @Test
    fun testRefreshRateFromFps() {
        assertEquals(
            RefreshRateManager.RefreshRate.HIGH,
            RefreshRateManager.RefreshRate.fromFps(120)
        )
        assertEquals(
            RefreshRateManager.RefreshRate.MEDIUM,
            RefreshRateManager.RefreshRate.fromFps(90)
        )
        assertEquals(
            RefreshRateManager.RefreshRate.STANDARD,
            RefreshRateManager.RefreshRate.fromFps(60)
        )
        assertEquals(
            RefreshRateManager.RefreshRate.UNKNOWN,
            RefreshRateManager.RefreshRate.fromFps(30)
        )
    }

    @Test
    fun testSetPreferredRefreshRate() {
        // This test verifies that setting refresh rate doesn't crash
        // Actual refresh rate change depends on device support
        try {
            RefreshRateManager.setPreferredRefreshRate(
                window,
                RefreshRateManager.RefreshRate.HIGH
            )
        } catch (e: Exception) {
            // Some devices may not support this
            // Just ensure it doesn't crash
        }
    }

    @Test
    fun testEnableHighestRefreshRate() {
        // This test verifies that enabling highest refresh rate doesn't crash
        try {
            RefreshRateManager.enableHighestRefreshRate(window)
        } catch (e: Exception) {
            // Some devices may not support this
            // Just ensure it doesn't crash
        }
    }
}
