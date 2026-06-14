package com.omnichat.ui.performance

import android.view.Choreographer
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Monitors actual frame rates during composition and animation.
 * Useful for identifying performance issues.
 */
object FrameRateMonitor {
    private val frameTimes = CopyOnWriteArrayList<Long>()
    private var isMonitoring = false
    private var choreographerCallback: Choreographer.FrameCallback? = null
    private var callbackCount = 0

    /**
     * Callback interface for frame rate updates.
     */
    interface FrameRateCallback {
        fun onFrameRateUpdate(fps: Float, frameTimeMs: Float)
    }

    private val callbacks = CopyOnWriteArrayList<FrameRateCallback>()

    /**
     * Starts monitoring frame rates.
     */
    fun startMonitoring() {
        if (isMonitoring) return

        isMonitoring = true
        frameTimes.clear()

        choreographerCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameTimes.add(frameTimeNanos / 1_000_000) // Convert to milliseconds

                // Keep only last 60 frames for calculation
                while (frameTimes.size > 60) {
                    frameTimes.removeAt(0)
                }

                // Calculate FPS from recent frames
                if (frameTimes.size >= 2) {
                    val recentFrames = frameTimes.takeLast(10)
                    val timeDiff = recentFrames.last() - recentFrames.first()
                    val fps = if (timeDiff > 0) {
                        (recentFrames.size - 1) * 1000f / timeDiff
                    } else {
                        0f
                    }
                    val frameTimeMs = if (timeDiff > 0) {
                        timeDiff.toFloat() / (recentFrames.size - 1)
                    } else {
                        0f
                    }

                    callbacks.forEach { it.onFrameRateUpdate(fps, frameTimeMs) }
                }

                if (isMonitoring) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(choreographerCallback!!)
    }

    /**
     * Stops monitoring frame rates.
     */
    fun stopMonitoring() {
        isMonitoring = false
        choreographerCallback?.let {
            Choreographer.getInstance().removeFrameCallback(it)
        }
        choreographerCallback = null
        frameTimes.clear()
    }

    /**
     * Adds a callback to receive frame rate updates.
     */
    fun addCallback(callback: FrameRateCallback) {
        callbacks.add(callback)
        callbackCount++
    }

    /**
     * Removes a callback.
     */
    fun removeCallback(callback: FrameRateCallback) {
        callbacks.remove(callback)
        callbackCount--
        if (callbackCount <= 0) {
            stopMonitoring()
        }
    }

    /**
     * Gets the current average FPS.
     */
    fun getCurrentFps(): Float {
        if (frameTimes.size < 2) return 0f

        val recentFrames = frameTimes.takeLast(10)
        val timeDiff = recentFrames.last() - recentFrames.first()
        return if (timeDiff > 0) {
            (recentFrames.size - 1) * 1000f / timeDiff
        } else {
            0f
        }
    }

    /**
     * Gets the average frame time in milliseconds.
     */
    fun getAverageFrameTimeMs(): Float {
        if (frameTimes.size < 2) return 0f

        val recentFrames = frameTimes.takeLast(10)
        val timeDiff = recentFrames.last() - recentFrames.first()
        return if (timeDiff > 0) {
            timeDiff.toFloat() / (recentFrames.size - 1)
        } else {
            0f
        }
    }
}

/**
 * Composable that monitors frame rates and provides the current FPS.
 */
@Composable
fun rememberFrameRate(): State<Float> {
    val frameRate = remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val callback = object : FrameRateMonitor.FrameRateCallback {
            override fun onFrameRateUpdate(fps: Float, frameTimeMs: Float) {
                frameRate.floatValue = fps
            }
        }

        FrameRateMonitor.addCallback(callback)
        FrameRateMonitor.startMonitoring()

        onDispose {
            FrameRateMonitor.removeCallback(callback)
        }
    }

    return frameRate
}

/**
 * Composable that monitors frame rates and provides detailed metrics.
 */
@Composable
fun rememberFrameMetrics(): State<FrameMetrics> {
    val metrics = remember { mutableStateOf(FrameMetrics()) }

    DisposableEffect(Unit) {
        val callback = object : FrameRateMonitor.FrameRateCallback {
            override fun onFrameRateUpdate(fps: Float, frameTimeMs: Float) {
                metrics.value = FrameMetrics(
                    fps = fps,
                    frameTimeMs = frameTimeMs,
                    isSmooth = fps >= 55f, // Consider smooth if >= 55 FPS
                    timestamp = System.currentTimeMillis()
                )
            }
        }

        FrameRateMonitor.addCallback(callback)
        FrameRateMonitor.startMonitoring()

        onDispose {
            FrameRateMonitor.removeCallback(callback)
        }
    }

    return metrics
}

/**
 * Data class for frame rate metrics.
 */
data class FrameMetrics(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val isSmooth: Boolean = true,
    val timestamp: Long = 0L
)
