package com.omnichat.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnichat.ui.theme.LocalChatFontScale
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import kotlinx.coroutines.delay

/**
 * UI state for an active SubAgent task.
 */
data class SubAgentTaskUiState(
    val taskId: String,
    val sessionId: Long,
    val taskType: String,
    val description: String,
    val status: TaskStatus,
    val progressMessage: String?,
    val result: String?,
    val startedAtMs: Long
)

enum class TaskStatus { RUNNING, COMPLETED, FAILED }

/**
 * In-chat card showing SubAgent task status.
 * Rendered as an item in the ChatScreen LazyColumn.
 */
@Composable
fun SubAgentTaskCard(
    task: SubAgentTaskUiState,
    modifier: Modifier = Modifier
) {
    val uiSettings = LocalUISettings.current
    val chatFs = LocalChatFontScale.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    // Elapsed time tracking
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(task.startedAtMs, task.status) {
        if (task.status == TaskStatus.RUNNING) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - task.startedAtMs) / 1000
                delay(1000)
            }
        } else {
            elapsedSeconds = (System.currentTimeMillis() - task.startedAtMs) / 1000
        }
    }

    val cardColor = when (task.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        TaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (task.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
        TaskStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (task.status) {
        TaskStatus.RUNNING -> Icons.Default.PlayArrow
        TaskStatus.COMPLETED -> Icons.Default.Check
        TaskStatus.FAILED -> Icons.Default.Close
    }
    val statusLabel = when (task.status) {
        TaskStatus.RUNNING -> "Running"
        TaskStatus.COMPLETED -> "Complete"
        TaskStatus.FAILED -> "Failed"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = cardColor,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Icon(
                imageVector = icon,
                contentDescription = statusLabel,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Header: status + agent type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "SubAgent ($statusLabel)",
                        fontSize = (13 * chatFs * fs).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = resolvedFontFamily,
                        color = contentColor
                    )
                    if (task.status == TaskStatus.RUNNING) {
                        Spacer(modifier = Modifier.width(8.dp))
                        // Pulsing dot
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(700, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(contentColor.copy(alpha = alpha))
                            )
                        }
                    }
                }

                // Description
                Text(
                    text = task.description.take(80) + if (task.description.length > 80) "..." else "",
                    fontSize = (12 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 1
                )

                // Elapsed time (for running tasks)
                if (task.status == TaskStatus.RUNNING) {
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    Text(
                        text = "⏱ ${minutes}m ${seconds}s",
                        fontSize = (11 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }

                // Progress message
                if (task.progressMessage != null && task.status == TaskStatus.RUNNING) {
                    Text(
                        text = task.progressMessage.take(60),
                        fontSize = (11 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }

                // Error message (for failed tasks)
                if (task.status == TaskStatus.FAILED && task.result != null) {
                    Text(
                        text = task.result.take(100),
                        fontSize = (11 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}
