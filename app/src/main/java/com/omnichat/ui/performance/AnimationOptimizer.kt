package com.omnichat.ui.performance

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

/**
 * Provides optimized animation specifications based on the current refresh rate.
 */
object AnimationOptimizer {

    /**
     * Standard animation durations optimized for different refresh rates.
     * At 120Hz, animations can be faster while maintaining smoothness.
     */
    object Durations {
        @Composable
        fun fast(): Int = animationDurationForRefreshRate(150)

        @Composable
        fun normal(): Int = animationDurationForRefreshRate(300)

        @Composable
        fun slow(): Int = animationDurationForRefreshRate(500)

        @Composable
        fun verySlow(): Int = animationDurationForRefreshRate(800)
    }

    /**
     * Creates a spring animation spec optimized for the current refresh rate.
     */
    @Composable
    fun optimizedSpring(
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
        stiffness: Float = Spring.StiffnessLow
    ): SpringSpec<Float> {
        val refreshRate = rememberRefreshRate()
        val adjustedStiffness = remember(refreshRate, stiffness) {
            when (refreshRate) {
                RefreshRateManager.RefreshRate.HIGH -> stiffness * 1.2f
                RefreshRateManager.RefreshRate.MEDIUM -> stiffness * 1.1f
                RefreshRateManager.RefreshRate.STANDARD -> stiffness
                RefreshRateManager.RefreshRate.UNKNOWN -> stiffness
            }
        }
        return remember(dampingRatio, adjustedStiffness) {
            spring(dampingRatio = dampingRatio, stiffness = adjustedStiffness)
        }
    }

    /**
     * Creates a tween animation spec optimized for the current refresh rate.
     */
    @Composable
    fun optimizedTween(
        durationMillis: Int = 300,
        delayMillis: Int = 0,
        easing: Easing = FastOutSlowInEasing
    ): TweenSpec<Float> {
        val optimizedDuration = animationDurationForRefreshRate(durationMillis)
        val optimizedDelay = animationDurationForRefreshRate(delayMillis)
        return remember(optimizedDuration, optimizedDelay, easing) {
            tween(durationMillis = optimizedDuration, delayMillis = optimizedDelay, easing = easing)
        }
    }

}

/**
 * Modifier that applies optimized graphics layer transformations.
 * Uses hardware acceleration and avoids unnecessary recompositions.
 */
fun Modifier.optimizedGraphicsLayer(
    block: GraphicsLayerScope.() -> Unit
): Modifier = this.composed {
    // Use graphicsLayer for hardware-accelerated transformations
    graphicsLayer(block = block)
}

/**
 * Modifier that applies an optimized alpha animation.
 */
@Composable
fun Modifier.optimizedAlpha(alpha: Float): Modifier {
    return this.graphicsLayer {
        this.alpha = alpha
    }
}

/**
 * Modifier that applies optimized scale transformations.
 */
@Composable
fun Modifier.optimizedScale(
    scaleX: Float,
    scaleY: Float = scaleX
): Modifier {
    return this.graphicsLayer {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }
}

/**
 * Modifier that applies optimized translation transformations.
 */
@Composable
fun Modifier.optimizedTranslation(
    translationX: Float = 0f,
    translationY: Float = 0f
): Modifier {
    return this.graphicsLayer {
        this.translationX = translationX
        this.translationY = translationY
    }
}

/**
 * Modifier that applies optimized rotation transformations.
 */
@Composable
fun Modifier.optimizedRotation(
    rotationX: Float = 0f,
    rotationY: Float = 0f,
    rotationZ: Float = 0f
): Modifier {
    return this.graphicsLayer {
        this.rotationX = rotationX
        this.rotationY = rotationY
        this.rotationZ = rotationZ
    }
}

/**
 * Creates an optimized infinite transition for pulsing animations.
 */
@Composable
fun optimizedPulseTransition(
    initialValue: Float = 0.8f,
    targetValue: Float = 1.2f,
    durationMillis: Int = 800,
    label: String = "pulse"
): State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = label)
    val optimizedDuration = animationDurationForRefreshRate(durationMillis)

    return infiniteTransition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = optimizedDuration,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}_value"
    )
}

/**
 * Creates an optimized infinite transition for blinking animations.
 */
@Composable
fun optimizedBlinkTransition(
    initialValue: Float = 0.3f,
    targetValue: Float = 1.0f,
    durationMillis: Int = 700,
    label: String = "blink"
): State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = label)
    val optimizedDuration = animationDurationForRefreshRate(durationMillis)

    return infiniteTransition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = optimizedDuration,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}_value"
    )
}
