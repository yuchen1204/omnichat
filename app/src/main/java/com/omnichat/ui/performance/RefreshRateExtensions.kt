package com.omnichat.ui.performance

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Remembers and returns the current refresh rate.
 */
@Composable
fun rememberRefreshRate(): RefreshRateManager.RefreshRate {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    return remember(window) {
        window?.let { RefreshRateManager.getCurrentRefreshRate(it) }
            ?: RefreshRateManager.RefreshRate.STANDARD
    }
}

/**
 * Enables the highest available refresh rate for the duration of the composition.
 * Automatically restores the original refresh rate when the composition is disposed.
 */
@Composable
fun EnableHighestRefreshRate() {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window

    DisposableEffect(window) {
        window?.let {
            val originalRate = RefreshRateManager.getCurrentRefreshRate(it)
            RefreshRateManager.enableHighestRefreshRate(it)

            onDispose {
                // Restore original refresh rate
                RefreshRateManager.setPreferredRefreshRate(it, originalRate)
            }
        }
        onDispose { }
    }
}

/**
 * Adjusts animation duration based on the current refresh rate.
 * Higher refresh rates (120Hz) use shorter durations for smoother animations.
 */
@Composable
fun animationDurationForRefreshRate(baseDurationMs: Int = 300): Int {
    val refreshRate = rememberRefreshRate()
    return remember(refreshRate, baseDurationMs) {
        when (refreshRate) {
            RefreshRateManager.RefreshRate.HIGH -> (baseDurationMs * 0.75).toInt()
            RefreshRateManager.RefreshRate.MEDIUM -> (baseDurationMs * 0.85).toInt()
            RefreshRateManager.RefreshRate.STANDARD -> baseDurationMs
            RefreshRateManager.RefreshRate.UNKNOWN -> baseDurationMs
        }
    }
}

/**
 * Creates an animation spec optimized for the current refresh rate.
 */
@Composable
fun optimizedTweenSpec(
    durationMs: Int = 300,
    easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing
): androidx.compose.animation.core.TweenSpec<Int> {
    val optimizedDuration = animationDurationForRefreshRate(durationMs)
    return remember(optimizedDuration, easing) {
        androidx.compose.animation.core.tween(optimizedDuration, easing = easing)
    }
}

/**
 * Returns the optimal frame duration in milliseconds for the current refresh rate.
 * Useful for timing non-Compose animations or calculations.
 */
@Composable
fun optimalFrameDurationMs(): Float {
    val refreshRate = rememberRefreshRate()
    return remember(refreshRate) {
        1000f / refreshRate.fps
    }
}
