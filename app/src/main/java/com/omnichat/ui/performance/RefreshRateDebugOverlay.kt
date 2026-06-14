package com.omnichat.ui.performance

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Debug overlay that displays current refresh rate information.
 * Only visible in debug builds.
 */
@Composable
fun RefreshRateDebugOverlay(
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    val refreshRate = rememberRefreshRate()
    val supportedRates = remember(window) {
        window?.let { RefreshRateManager.getSupportedRefreshRates(it) }
            ?: emptyList()
    }

    // Animated dot to visualize refresh rate
    val infiniteTransition = rememberInfiniteTransition(label = "debug_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1000 / refreshRate.fps * 2,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Box(
        modifier = modifier
            .zIndex(1000f) // Ensure it's on top
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Current refresh rate
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "FPS",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = when (refreshRate) {
                                RefreshRateManager.RefreshRate.HIGH -> Color.Green
                                RefreshRateManager.RefreshRate.MEDIUM -> Color.Yellow
                                RefreshRateManager.RefreshRate.STANDARD -> Color.White
                                RefreshRateManager.RefreshRate.UNKNOWN -> Color.Gray
                            }.copy(alpha = dotAlpha),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Text(
                    text = "${refreshRate.fps}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Supported rates
            if (supportedRates.isNotEmpty()) {
                Text(
                    text = "Supported: ${supportedRates.joinToString { "${it.fps}Hz" }}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Device info
            Text(
                text = "API ${Build.VERSION.SDK_INT}",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Debug overlay that can be toggled on/off.
 */
@Composable
fun ToggleableRefreshRateDebugOverlay(
    enabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (enabled) {
        RefreshRateDebugOverlay(modifier)
    }
}
