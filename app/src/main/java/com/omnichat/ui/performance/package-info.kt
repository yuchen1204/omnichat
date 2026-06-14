/**
 * UI Performance Optimization Package
 *
 * This package provides utilities for optimizing UI performance on Android devices
 * with different refresh rates (120Hz, 90Hz, 60Hz).
 *
 * Key components:
 *
 * 1. [RefreshRateManager] - Manages display refresh rate detection and optimization
 * 2. [AnimationOptimizer] - Provides optimized animation specifications
 * 3. [FrameRateMonitor] - Monitors actual frame rates during runtime
 * 4. [RefreshRateDebugOverlay] - Debug overlay for visualizing refresh rate info
 *
 * Usage:
 *
 * ```kotlin
 * // Enable highest refresh rate for smooth animations
 * EnableHighestRefreshRate()
 *
 * // Use optimized animation specs
 * val duration = animationDurationForRefreshRate(300)
 * val springSpec = AnimationOptimizer.optimizedSpring()
 *
 * // Monitor frame rates
 * val frameRate = rememberFrameRate()
 * val metrics = rememberFrameMetrics()
 *
 * // Show debug overlay (debug builds only)
 * if (BuildConfig.DEBUG) {
 *     RefreshRateDebugOverlay()
 * }
 * ```
 *
 * Optimization strategies:
 *
 * - **Duration scaling**: Animations are automatically scaled based on refresh rate
 *   - 120Hz: 75% of base duration
 *   - 90Hz: 85% of base duration
 *   - 60Hz: 100% of base duration
 *
 * - **Hardware acceleration**: Use `graphicsLayer` for transformations
 *
 * - **Spring animations**: Prefer springs over tweens for more natural motion
 *
 * - **Frame budget**: At 120Hz, frame budget is 8.3ms; at 60Hz it's 16.7ms
 */
package com.omnichat.ui.performance
