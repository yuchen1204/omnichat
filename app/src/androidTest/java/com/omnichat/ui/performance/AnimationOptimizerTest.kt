package com.omnichat.ui.performance

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AnimationOptimizerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAnimationDurationForRefreshRate() {
        var duration = 0

        composeTestRule.setContent {
            duration = animationDurationForRefreshRate(300)
        }

        composeTestRule.waitForIdle()
        assertTrue("Duration should be positive", duration > 0)
        assertTrue("Duration should be reasonable", duration <= 300)
    }

    @Test
    fun testOptimizedSpring() {
        var springSpec: androidx.compose.animation.core.Spring<Float>? = null

        composeTestRule.setContent {
            springSpec = AnimationOptimizer.optimizedSpring()
        }

        composeTestRule.waitForIdle()
        assertNotNull("Spring spec should not be null", springSpec)
    }

    @Test
    fun testOptimizedTween() {
        var tweenSpec: androidx.compose.animation.core.TweenSpec<Float>? = null

        composeTestRule.setContent {
            tweenSpec = AnimationOptimizer.optimizedTween(durationMs = 300)
        }

        composeTestRule.waitForIdle()
        assertNotNull("Tween spec should not be null", tweenSpec)
    }

    @Test
    fun testOptimizedPulseTransition() {
        var pulseValue by mutableFloatStateOf(0f)

        composeTestRule.setContent {
            val pulse by optimizedPulseTransition(
                initialValue = 0.8f,
                targetValue = 1.2f,
                durationMs = 100,
                label = "test_pulse"
            )
            pulseValue = pulse
        }

        composeTestRule.waitForIdle()
        assertTrue("Pulse value should be within range", pulseValue in 0.8f..1.2f)
    }

    @Test
    fun testOptimizedBlinkTransition() {
        var blinkValue by mutableFloatStateOf(0f)

        composeTestRule.setContent {
            val blink by optimizedBlinkTransition(
                initialValue = 0.3f,
                targetValue = 1.0f,
                durationMs = 100,
                label = "test_blink"
            )
            blinkValue = blink
        }

        composeTestRule.waitForIdle()
        assertTrue("Blink value should be within range", blinkValue in 0.3f..1.0f)
    }

    @Test
    fun testRememberRefreshRate() {
        var refreshRate: RefreshRateManager.RefreshRate? = null

        composeTestRule.setContent {
            refreshRate = rememberRefreshRate()
        }

        composeTestRule.waitForIdle()
        assertNotNull("Refresh rate should not be null", refreshRate)
    }

    @Test
    fun testOptimalFrameDurationMs() {
        var frameDuration = 0f

        composeTestRule.setContent {
            frameDuration = optimalFrameDurationMs()
        }

        composeTestRule.waitForIdle()
        assertTrue("Frame duration should be positive", frameDuration > 0)
        assertTrue("Frame duration should be reasonable", frameDuration <= 20f)
    }

    @Test
    fun testFrameRateMonitor() {
        var fps = 0f

        composeTestRule.setContent {
            val frameRate = rememberFrameRate()
            LaunchedEffect(frameRate) {
                fps = frameRate.intValue
            }
        }

        composeTestRule.waitForIdle()
        // FPS should be measured after some time
        // Just verify it doesn't crash
    }

    @Test
    fun testFrameMetrics() {
        var metrics: FrameMetrics? = null

        composeTestRule.setContent {
            val frameMetrics = rememberFrameMetrics()
            LaunchedEffect(frameMetrics) {
                metrics = frameMetrics.value
            }
        }

        composeTestRule.waitForIdle()
        // Metrics should be measured after some time
        // Just verify it doesn't crash
    }

    @Test
    fun testRefreshRateDebugOverlay() {
        composeTestRule.setContent {
            RefreshRateDebugOverlay()
        }

        composeTestRule.waitForIdle()
        // Just verify it doesn't crash
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
