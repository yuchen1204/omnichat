package com.omnichat.ui.performance

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnichat.ui.theme.LocalCustomColors

/**
 * Example: Optimized MCP loading indicator
 *
 * Original code:
 * ```kotlin
 * val infiniteTransition = rememberInfiniteTransition(label = "mcp_blink")
 * val alpha by infiniteTransition.animateFloat(
 *     initialValue = 0.3f, targetValue = 1.0f,
 *     animationSpec = infiniteRepeatable(
 *         animation = tween(700, easing = LinearEasing),
 *         repeatMode = RepeatMode.Reverse
 *     ), label = "mcp_alpha"
 * )
 * ```
 *
 * Optimized version:
 */
@Composable
fun OptimizedMcpLoadingIndicator(
    modifier: Modifier = Modifier
) {
    val alpha by optimizedBlinkTransition(
        initialValue = 0.3f,
        targetValue = 1.0f,
        durationMillis = 700,
        label = "mcp_blink"
    )

    Box(
        modifier = modifier
            .size(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .graphicsLayer { this.alpha = alpha }
            .background(LocalCustomColors.current.warning.copy(alpha = alpha))
    )
}

/**
 * Example: Optimized thinking pulse animation
 *
 * Original code:
 * ```kotlin
 * val infiniteTransition = rememberInfiniteTransition(label = "think_pulse")
 * val pulseScale by infiniteTransition.animateFloat(
 *     initialValue = 0.8f,
 *     targetValue = 1.2f,
 *     animationSpec = infiniteRepeatable(
 *         animation = tween(800, easing = FastOutSlowInEasing),
 *         repeatMode = RepeatMode.Reverse
 *     ),
 *     label = "pulseScale"
 * )
 * ```
 *
 * Optimized version:
 */
@Composable
fun OptimizedThinkingPulse(
    modifier: Modifier = Modifier
) {
    val pulseScale by optimizedPulseTransition(
        initialValue = 0.8f,
        targetValue = 1.2f,
        durationMillis = 800,
        label = "think_pulse"
    )

    Box(
        modifier = modifier
            .size(16.dp)
            .graphicsLayer(
                scaleX = pulseScale,
                scaleY = pulseScale
            )
            .background(LocalCustomColors.current.accent)
    )
}

/**
 * Example: Optimized color animation
 *
 * Original code:
 * ```kotlin
 * val bgColor by animateColorAsState(
 *     targetValue = if (isActive) sidebarColors.activeBackground else Color.Transparent,
 *     animationSpec = tween(200),
 *     label = "itemBg"
 * )
 * ```
 *
 * Optimized version:
 */
@Composable
fun OptimizedColorAnimation(
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color
): Color {
    val optimizedDuration = animationDurationForRefreshRate(200)

    return animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        animationSpec = tween(optimizedDuration),
        label = "color_animation"
    ).value
}

/**
 * Example: Optimized elevation animation
 *
 * Original code:
 * ```kotlin
 * val elevation by animateDpAsState(
 *     targetValue = if (isActive) 1.dp else 0.dp,
 *     animationSpec = tween(200),
 *     label = "itemElevation"
 * )
 * ```
 *
 * Optimized version:
 */
@Composable
fun OptimizedElevationAnimation(
    isActive: Boolean
): androidx.compose.ui.unit.Dp {
    val optimizedDuration = animationDurationForRefreshRate(200)

    return animateDpAsState(
        targetValue = if (isActive) 1.dp else 0.dp,
        animationSpec = tween(optimizedDuration),
        label = "elevation_animation"
    ).value
}

/**
 * Example: Optimized scale animation using spring
 *
 * This uses a spring animation instead of tween for more natural motion.
 */
@Composable
fun OptimizedScaleAnimation(
    isPressed: Boolean
): Float {
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = AnimationOptimizer.optimizedSpring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale_animation"
    )
    return scale
}

/**
 * Example: Using the debug overlay for performance monitoring
 */
@Composable
fun PerformanceMonitoringExample() {
    val frameRate = rememberFrameRate()
    val metrics = rememberFrameMetrics()

    // Show frame rate in a debug panel
    Column(
        modifier = Modifier
            .padding(8.dp)
            .background(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = "Performance Metrics",
            color = Color.White,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "FPS: ${frameRate.value.toInt()}",
            color = if (metrics.value.isSmooth) {
                Color.Green
            } else {
                Color.Red
            },
            fontSize = 12.sp
        )
        Text(
            text = "Frame Time: ${"%.2f".format(metrics.value.frameTimeMs)}ms",
            color = Color.White,
            fontSize = 12.sp
        )
        Text(
            text = "Smooth: ${if (metrics.value.isSmooth) "✓" else "✗"}",
            color = if (metrics.value.isSmooth) {
                Color.Green
            } else {
                Color.Red
            },
            fontSize = 12.sp
        )
    }
}
