package com.omnichat.ui.performance

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Manages display refresh rate optimization for smooth animations.
 * Supports 120Hz, 90Hz, and 60Hz displays.
 */
object RefreshRateManager {

    /**
     * Represents the available refresh rates on the device.
     */
    enum class RefreshRate(val fps: Int) {
        HIGH(120),
        MEDIUM(90),
        STANDARD(60),
        UNKNOWN(60);

        companion object {
            fun fromFps(fps: Int): RefreshRate = when {
                fps >= 120 -> HIGH
                fps >= 90 -> MEDIUM
                fps >= 60 -> STANDARD
                else -> UNKNOWN
            }
        }
    }

    /**
     * Gets the supported refresh rates for the device.
     */
    fun getSupportedRefreshRates(window: Window): List<RefreshRate> {
        val display = window.windowManager.defaultDisplay
        val supportedModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.supportedModes.map { it.refreshRate }.distinct().sorted()
        } else {
            emptyList()
        }

        return supportedModes.map { RefreshRate.fromFps(it.toInt()) }
            .distinct()
            .sortedByDescending { it.fps }
    }

    /**
     * Gets the current refresh rate.
     */
    fun getCurrentRefreshRate(window: Window): RefreshRate {
        val display = window.windowManager.defaultDisplay
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.mode
        } else {
            null
        }
        return RefreshRate.fromFps(mode?.refreshRate?.toInt() ?: 60)
    }

    /**
     * Sets the preferred refresh rate for the window.
     * This is a hint to the system; the actual rate may vary.
     */
    fun setPreferredRefreshRate(window: Window, rate: RefreshRate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val layoutParams = window.attributes
            // Use WindowManager.LayoutParams.MATCH_REFRESH_RATE if available (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutParams.preferredDisplayModeId = getModeIdForRate(window, rate)
            }
            window.attributes = layoutParams
        }
    }

    /**
     * Gets the mode ID for the desired refresh rate.
     */
    private fun getModeIdForRate(window: Window, rate: RefreshRate): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = window.windowManager.defaultDisplay
            val modes = display.supportedModes
            // Find the mode with the closest refresh rate
            val targetRate = rate.fps.toFloat()
            val bestMode = modes.minByOrNull { kotlin.math.abs(it.refreshRate - targetRate) }
            return bestMode?.modeId ?: 0
        }
        return 0
    }

    /**
     * Enables the highest available refresh rate for smooth animations.
     */
    fun enableHighestRefreshRate(window: Window) {
        val supportedRates = getSupportedRefreshRates(window)
        val highestRate = supportedRates.firstOrNull() ?: RefreshRate.STANDARD
        setPreferredRefreshRate(window, highestRate)
    }
}
