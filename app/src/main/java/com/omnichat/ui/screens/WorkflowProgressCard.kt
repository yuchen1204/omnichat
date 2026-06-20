package com.omnichat.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnichat.agent.*
import com.omnichat.ui.theme.LocalChatFontScale
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 聚合显示 Workflow 进度的卡片组件。
 *
 * 支持三种模式：
 * - Pipeline: 线性进度条 + 步骤列表
 * - DAG: 依赖图简化视图 + 并行进度
 * - Conversational: 对话轮次 + Agent 头像
 */
@Composable
fun WorkflowProgressCard(
    workflow: WorkflowUiState,
    modifier: Modifier = Modifier,
    onCancelClick: (() -> Unit)? = null
) {
    val uiSettings = LocalUISettings.current
    val chatFs = LocalChatFontScale.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    // 计算运行时间
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(workflow.startedAt, workflow.status) {
        if (workflow.status == WorkflowStatus.RUNNING) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - workflow.startedAt) / 1000
                delay(1000)
            }
        } else {
            elapsedSeconds = (System.currentTimeMillis() - workflow.startedAt) / 1000
        }
    }

    val cardColor = when (workflow.status) {
        WorkflowStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        WorkflowStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        WorkflowStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        WorkflowStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (workflow.status) {
        WorkflowStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        WorkflowStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
        WorkflowStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        WorkflowStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = cardColor,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Mode + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode icon
                val modeIcon = when (workflow.mode) {
                    WorkflowMode.PIPELINE -> Icons.Default.AccountTree
                    WorkflowMode.DAG -> Icons.Default.Hub
                    WorkflowMode.CONVERSATIONAL -> Icons.Default.Forum
                }
                val modeLabel = when (workflow.mode) {
                    WorkflowMode.PIPELINE -> "Pipeline"
                    WorkflowMode.DAG -> "DAG"
                    WorkflowMode.CONVERSATIONAL -> "Conversation"
                }

                Icon(
                    imageVector = modeIcon,
                    contentDescription = modeLabel,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Workflow: $modeLabel",
                    fontSize = (13 * chatFs * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily,
                    color = contentColor
                )

                Spacer(modifier = Modifier.weight(1f))

                // Cancel button (if running and callback provided)
                if (workflow.status == WorkflowStatus.RUNNING && onCancelClick != null) {
                    IconButton(
                        onClick = onCancelClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Cancel workflow",
                            tint = contentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Status indicator
                if (workflow.status == WorkflowStatus.RUNNING) {
                    // Spinning progress indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "spin")
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rotation"
                    )

                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Running",
                        tint = contentColor,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(rotation)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    Text(
                        text = "${minutes}m ${seconds}s",
                        fontSize = (11 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                } else {
                    StatusChip(
                        status = workflow.status,
                        contentColor = contentColor,
                        fs = fs,
                        chatFs = chatFs
                    )
                }
            }

            // Progress Section
            when (workflow.mode) {
                WorkflowMode.PIPELINE -> PipelineProgressSection(
                    workflow = workflow,
                    contentColor = contentColor,
                    fs = fs,
                    chatFs = chatFs
                )
                WorkflowMode.DAG -> DagProgressSection(
                    workflow = workflow,
                    contentColor = contentColor,
                    fs = fs,
                    chatFs = chatFs
                )
                WorkflowMode.CONVERSATIONAL -> ConversationalProgressSection(
                    workflow = workflow,
                    contentColor = contentColor,
                    fs = fs,
                    chatFs = chatFs
                )
            }

            // Error Message
            if (workflow.status == WorkflowStatus.FAILED && workflow.error != null) {
                Text(
                    text = "Error: ${workflow.error}",
                    fontSize = (11 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // IDLE timeout warnings
            if (workflow.idleWarnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                workflow.idleWarnings.forEach { warning ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = Color(0xFFE65100),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = warning.message,
                                fontSize = (11 * chatFs * fs).sp,
                                fontFamily = resolvedFontFamily,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }
            }

            // Message routing errors
            if (workflow.messageErrors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                workflow.messageErrors.forEach { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text(
                                text = "消息发送失败: ${error.from} → ${error.to}",
                                fontSize = (10 * chatFs * fs).sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = resolvedFontFamily,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = error.error,
                                fontSize = (9 * chatFs * fs).sp,
                                fontFamily = resolvedFontFamily,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "可用目标: ${error.availableTargets.joinToString(", ")}",
                                fontSize = (9 * chatFs * fs).sp,
                                fontFamily = resolvedFontFamily,
                                color = contentColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    status: WorkflowStatus,
    contentColor: Color,
    fs: Float,
    chatFs: Float
) {
    val (icon, label) = when (status) {
        WorkflowStatus.COMPLETED -> Icons.Default.CheckCircle to "Done"
        WorkflowStatus.FAILED -> Icons.Default.Cancel to "Failed"
        WorkflowStatus.CANCELLED -> Icons.Default.Cancel to "Cancelled"
        WorkflowStatus.RUNNING -> Icons.Default.Sync to "Running"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = (11 * chatFs * fs).sp,
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun PipelineProgressSection(
    workflow: WorkflowUiState,
    contentColor: Color,
    fs: Float,
    chatFs: Float
) {
    val resolvedFontFamily = resolveFontFamily(LocalUISettings.current.fontFamily)

    // Progress bar
    val completedCount = workflow.steps.count {
        it.status == WorkflowStepStatus.COMPLETED
    }
    val totalSteps = workflow.steps.size
    val progress = if (totalSteps > 0) completedCount.toFloat() / totalSteps else 0f

    Column(modifier = Modifier.padding(top = 12.dp)) {
        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.2f),
            strokeCap = StrokeCap.Round
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Step count
        Text(
            text = "Step $completedCount / $totalSteps",
            fontSize = (11 * chatFs * fs).sp,
            fontFamily = resolvedFontFamily,
            color = contentColor.copy(alpha = 0.7f)
        )

        // Current running step with spinning indicator
        val currentStep = workflow.steps.getOrNull(workflow.currentStepIndex)
        if (currentStep != null && currentStep.status == WorkflowStepStatus.RUNNING) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Spinning indicator for running step
                SpinningIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    size = 14.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentStep.agentType,
                    fontSize = (11 * chatFs * fs).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = resolvedFontFamily,
                    color = contentColor
                )
                Text(
                    text = ": ${currentStep.task.take(40)}${if (currentStep.task.length > 40) "..." else ""}",
                    fontSize = (11 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Expandable step list with summaries
        if (workflow.steps.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = if (expanded) "Hide steps" else "Show all steps",
                    fontSize = (11 * chatFs * fs).sp,
                    color = contentColor.copy(alpha = 0.7f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    workflow.steps.forEachIndexed { index, step ->
                        StepItemWithSummary(
                            step = step,
                            index = index + 1,
                            contentColor = contentColor,
                            fs = fs,
                            chatFs = chatFs
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DagProgressSection(
    workflow: WorkflowUiState,
    contentColor: Color,
    fs: Float,
    chatFs: Float
) {
    val resolvedFontFamily = resolveFontFamily(LocalUISettings.current.fontFamily)

    val completedCount = workflow.steps.count {
        it.status == WorkflowStepStatus.COMPLETED
    }
    val runningCount = workflow.steps.count {
        it.status == WorkflowStepStatus.RUNNING
    }
    val failedCount = workflow.steps.count {
        it.status == WorkflowStepStatus.FAILED
    }
    val totalSteps = workflow.steps.size

    Column(modifier = Modifier.padding(top = 12.dp)) {
        // Status summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCount(label = "Done", count = completedCount, color = contentColor)
            StatusCount(label = "Running", count = runningCount, color = MaterialTheme.colorScheme.primary)
            if (failedCount > 0) {
                StatusCount(label = "Failed", count = failedCount, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$totalSteps total",
                fontSize = (11 * chatFs * fs).sp,
                fontFamily = resolvedFontFamily,
                color = contentColor.copy(alpha = 0.5f)
            )
        }

        // Running steps with spinning indicators
        val runningSteps = workflow.steps.filter { it.status == WorkflowStepStatus.RUNNING }
        if (runningSteps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Running in parallel:",
                fontSize = (11 * chatFs * fs).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = resolvedFontFamily,
                color = contentColor
            )
            runningSteps.forEach { step ->
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpinningIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        size = 12.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${step.agentType}: ${step.task.take(30)}...",
                        fontSize = (10 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Expandable step list with summaries
        var expanded by remember { mutableStateOf(false) }
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                text = if (expanded) "Hide steps" else "Show all steps",
                fontSize = (11 * chatFs * fs).sp,
                color = contentColor.copy(alpha = 0.7f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                workflow.steps.forEachIndexed { index, step ->
                    StepItemWithSummary(
                        step = step,
                        index = index + 1,
                        contentColor = contentColor,
                        fs = fs,
                        chatFs = chatFs,
                        showDeps = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationalProgressSection(
    workflow: WorkflowUiState,
    contentColor: Color,
    fs: Float,
    chatFs: Float
) {
    val resolvedFontFamily = resolveFontFamily(LocalUISettings.current.fontFamily)

    Column(modifier = Modifier.padding(top = 12.dp)) {
        // Topic display
        if (!workflow.topic.isNullOrBlank()) {
            Text(
                text = "\"${workflow.topic?.take(60)}${if ((workflow.topic?.length ?: 0) > 60) "..." else ""}\"",
                fontSize = (11 * chatFs * fs).sp,
                fontFamily = resolvedFontFamily,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Agent avatars with round indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Agent A
            AgentAvatar(
                agentType = workflow.agentA ?: "agentA",
                isActive = workflow.steps.lastOrNull()?.stepId?.contains(workflow.agentA ?: "") == true,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Round indicator
            Text(
                text = "Round ${workflow.currentRound}/${workflow.maxRounds}",
                fontSize = (12 * chatFs * fs).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = resolvedFontFamily,
                color = contentColor
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Agent B
            AgentAvatar(
                agentType = workflow.agentB ?: "agentB",
                isActive = workflow.steps.lastOrNull()?.stepId?.contains(workflow.agentB ?: "") == true,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // Latest message preview
        val lastStep = workflow.steps.lastOrNull()
        if (lastStep != null && lastStep.status == WorkflowStepStatus.COMPLETED) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Latest: ${lastStep.agentType}",
                fontSize = (10 * chatFs * fs).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = resolvedFontFamily,
                color = contentColor.copy(alpha = 0.6f)
            )
            Text(
                text = lastStep.result?.take(80) ?: "",
                fontSize = (10 * chatFs * fs).sp,
                fontFamily = resolvedFontFamily,
                color = contentColor.copy(alpha = 0.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Spinning progress indicator for running steps.
 */
@Composable
private fun SpinningIndicator(
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Icon(
        imageVector = Icons.Default.Sync,
        contentDescription = "Running",
        tint = color,
        modifier = modifier
            .size(size)
            .rotate(rotation)
    )
}

@Composable
private fun AgentAvatar(
    agentType: String,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val fs = LocalUISettings.current.fontSizeScale
    val chatFs = LocalChatFontScale.current

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isActive) color else color.copy(alpha = 0.3f))
            .then(
                if (isActive) {
                    Modifier.border(2.dp, color, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // First letter of agent type
        Text(
            text = agentType.first().uppercaseChar().toString(),
            fontSize = (14 * chatFs * fs).sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.onPrimary else color
        )
    }
}

/**
 * Step item with expandable summary for completed steps.
 */
@Composable
private fun StepItemWithSummary(
    step: WorkflowStepUiState,
    index: Int,
    contentColor: Color,
    fs: Float,
    chatFs: Float,
    showDeps: Boolean = false
) {
    val resolvedFontFamily = resolveFontFamily(LocalUISettings.current.fontFamily)
    var summaryExpanded by remember { mutableStateOf(false) }

    val statusColor = when (step.status) {
        WorkflowStepStatus.PENDING -> contentColor.copy(alpha = 0.5f)
        WorkflowStepStatus.IDLE -> contentColor.copy(alpha = 0.4f)
        WorkflowStepStatus.RUNNING -> MaterialTheme.colorScheme.primary
        WorkflowStepStatus.PENDING_REVIEW -> MaterialTheme.colorScheme.tertiary
        WorkflowStepStatus.REVISION -> MaterialTheme.colorScheme.tertiary
        WorkflowStepStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
        WorkflowStepStatus.FAILED -> MaterialTheme.colorScheme.error
        WorkflowStepStatus.SKIPPED -> contentColor.copy(alpha = 0.3f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(contentColor.copy(alpha = 0.05f))
    ) {
        // Main row with status icon and step info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon: spinning for running, check for completed
            when (step.status) {
                WorkflowStepStatus.RUNNING -> {
                    SpinningIndicator(
                        color = statusColor,
                        size = 14.dp
                    )
                }
                WorkflowStepStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                else -> {
                    val statusIcon: ImageVector = when (step.status) {
                        WorkflowStepStatus.PENDING -> Icons.Default.Schedule
                        WorkflowStepStatus.IDLE -> Icons.Default.PauseCircle
                        WorkflowStepStatus.PENDING_REVIEW -> Icons.Default.Visibility
                        WorkflowStepStatus.REVISION -> Icons.Default.Edit
                        WorkflowStepStatus.FAILED -> Icons.Default.Cancel
                        WorkflowStepStatus.SKIPPED -> Icons.Default.SkipNext
                        else -> Icons.Default.Circle
                    }
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "$index.",
                fontSize = (10 * chatFs * fs).sp,
                fontFamily = resolvedFontFamily,
                color = contentColor.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = step.agentType,
                fontSize = (10 * chatFs * fs).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = resolvedFontFamily,
                color = contentColor
            )

            if (showDeps && step.dependsOn.isNotEmpty()) {
                Text(
                    text = " (depends: ${step.dependsOn.joinToString(", ")})",
                    fontSize = (9 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Show IDLE duration
            if (step.status == WorkflowStepStatus.IDLE && step.idleSince != null) {
                val idleMinutes = remember(step.idleSince) {
                    (System.currentTimeMillis() - step.idleSince) / 60000
                }
                Text(
                    text = "等待 ${idleMinutes}分钟",
                    fontSize = (9 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // Show revision count
            if (step.status == WorkflowStepStatus.REVISION && step.revisionCount > 0) {
                Text(
                    text = "(第${step.revisionCount}次修改)",
                    fontSize = (9 * chatFs * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // Expand button for completed steps with results
            if (step.status == WorkflowStepStatus.COMPLETED && step.result != null) {
                Icon(
                    imageVector = if (summaryExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (summaryExpanded) "Collapse" else "Expand",
                    tint = contentColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { summaryExpanded = !summaryExpanded }
                )
            }

            // Error indicator
            if (step.status == WorkflowStepStatus.FAILED && step.error != null) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Expandable summary for completed steps
        if (summaryExpanded && step.status == WorkflowStepStatus.COMPLETED && step.result != null) {
            StepSummaryCard(
                resultJson = step.result,
                contentColor = contentColor,
                fs = fs,
                chatFs = chatFs,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Parse and display structured summary from step result JSON.
 */
@Composable
private fun StepSummaryCard(
    resultJson: String,
    contentColor: Color,
    fs: Float,
    chatFs: Float,
    modifier: Modifier = Modifier
) {
    val resolvedFontFamily = resolveFontFamily(LocalUISettings.current.fontFamily)
    val summaryData = remember(resultJson) {
        try {
            val json = JSONObject(resultJson)
            StepSummaryData(
                summary = json.optString("summary", ""),
                keyFindings = json.optJSONArray("key_findings")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                deliverables = json.optJSONArray("deliverables")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                confidence = json.optString("confidence", ""),
                notes = json.optString("notes", "")
            )
        } catch (_: Exception) {
            // If not valid JSON, just show the raw result
            StepSummaryData(
                summary = resultJson.take(200),
                keyFindings = emptyList(),
                deliverables = emptyList(),
                confidence = "",
                notes = ""
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = contentColor.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Summary (most important)
            if (summaryData.summary.isNotBlank()) {
                Text(
                    text = "Summary: ${summaryData.summary}",
                    fontSize = (10 * chatFs * fs).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = resolvedFontFamily,
                    color = contentColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Key findings
            if (summaryData.keyFindings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Key Findings:",
                    fontSize = (9 * chatFs * fs).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.7f)
                )
                summaryData.keyFindings.forEach { finding ->
                    Text(
                        text = "- $finding",
                        fontSize = (9 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Deliverables
            if (summaryData.deliverables.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Deliverables:",
                    fontSize = (9 * chatFs * fs).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = resolvedFontFamily,
                    color = contentColor.copy(alpha = 0.7f)
                )
                summaryData.deliverables.forEach { deliverable ->
                    Text(
                        text = "- $deliverable",
                        fontSize = (9 * chatFs * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = contentColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Confidence + Notes row
            if (summaryData.confidence.isNotBlank() || summaryData.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summaryData.confidence.isNotBlank()) {
                        Text(
                            text = "Confidence: ${summaryData.confidence}",
                            fontSize = (9 * chatFs * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = contentColor.copy(alpha = 0.5f)
                        )
                    }
                    if (summaryData.notes.isNotBlank()) {
                        Text(
                            text = "Notes: ${summaryData.notes.take(50)}",
                            fontSize = (9 * chatFs * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = contentColor.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Data class to hold parsed summary information.
 */
private data class StepSummaryData(
    val summary: String,
    val keyFindings: List<String>,
    val deliverables: List<String>,
    val confidence: String,
    val notes: String
)

@Composable
private fun StatusCount(
    label: String,
    count: Int,
    color: Color
) {
    val fs = LocalUISettings.current.fontSizeScale
    val chatFs = LocalChatFontScale.current
    val resolvedFontFamily = resolveFontFamily(LocalUISettings.current.fontFamily)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = count.toString(),
            fontSize = (12 * chatFs * fs).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = resolvedFontFamily,
            color = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = (11 * chatFs * fs).sp,
            fontFamily = resolvedFontFamily,
            color = color.copy(alpha = 0.7f)
        )
    }
}