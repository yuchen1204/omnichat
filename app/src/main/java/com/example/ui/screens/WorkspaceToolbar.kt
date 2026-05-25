package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalCustomColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ExportImportStatus

// ═══════════════════════════════════════════════════════════════════════════════
// 顶部工具栏
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 工作区顶部工具栏。
 *
 * 包含：工作流阶段 Pill（Planning / Working / Awaiting / Synthesizing / Completed）、
 * Agent 数量、导出日志按钮、任务面板开关。
 */
@Composable
fun WorkspaceToolbar(
    phase: WorkflowPhase,
    agentCount: Int,
    showTaskPanel: Boolean,
    hasTaskContent: Boolean,
    exportLogStatus: ExportImportStatus?,
    onToggleTaskPanel: () -> Unit,
    onExportLog: () -> Unit,
    onClearExportStatus: () -> Unit,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    LaunchedEffect(exportLogStatus) {
        if (exportLogStatus is ExportImportStatus.Success || exportLogStatus is ExportImportStatus.Error) {
            kotlinx.coroutines.delay(3000)
            onClearExportStatus()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WorkflowPhasePill(phase = phase, fs = fs, fontFamily = resolvedFontFamily)

            Spacer(modifier = Modifier.weight(1f))

            if (agentCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = uiText("workspace.agents.count", "%d 个 Agent").format(agentCount),
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = resolvedFontFamily
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 导出日志按钮
            IconButton(onClick = onExportLog, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = uiText("workspace.export_log", "导出日志"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 导出状态提示
            when (val status = exportLogStatus) {
                is ExportImportStatus.Success -> {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = status.message,
                        fontSize = (10 * fs).sp,
                        color = LocalCustomColors.current.success
                    )
                }
                is ExportImportStatus.Error -> {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = status.message,
                        fontSize = (10 * fs).sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }

            // 任务面板开关
            if (hasTaskContent) {
                IconButton(onClick = onToggleTaskPanel, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (showTaskPanel) Icons.AutoMirrored.Filled.ViewSidebar else Icons.Default.Dashboard,
                        contentDescription = uiText("workspace.toggle_task_panel", "任务面板"),
                        tint = if (showTaskPanel) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 工作流阶段 Pill。
 *
 * 每个阶段对应不同的图标、颜色和文案：
 *   - PLANNING       Orchestrator 正在思考/编排（脉冲圆点）
 *   - WORKING        有 Sub-Agent 正在执行（脉冲圆点 + 进度图标）
 *   - SYNTHESIZING   Orchestrator 正在汇总（脉冲圆点 + 汇总图标）
 *   - AWAITING_USER  全员 IDLE 等用户输入（静态等待图标）
 *   - COMPLETED      工作区已结束（成功图标）
 */
@Composable
private fun WorkflowPhasePill(
    phase: WorkflowPhase,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
) {
    val customColors = LocalCustomColors.current
    val (label, icon, color, isLive) = when (phase) {
        WorkflowPhase.PLANNING -> WorkflowPhaseStyle(
            label = uiText("workspace.phase.planning", "正在规划"),
            icon = Icons.Default.Lightbulb,
            color = MaterialTheme.colorScheme.primary,
            isLive = true,
        )
        WorkflowPhase.WORKING -> WorkflowPhaseStyle(
            label = uiText("workspace.phase.working", "Agent 执行中"),
            icon = Icons.Default.AutoAwesome,
            color = customColors.success,
            isLive = true,
        )
        WorkflowPhase.SYNTHESIZING -> WorkflowPhaseStyle(
            label = uiText("workspace.phase.synthesizing", "汇总结果"),
            icon = Icons.Default.AutoAwesomeMosaic,
            color = MaterialTheme.colorScheme.tertiary,
            isLive = true,
        )
        WorkflowPhase.AWAITING_USER -> WorkflowPhaseStyle(
            label = uiText("workspace.phase.awaiting", "等待中"),
            icon = Icons.Default.Schedule,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            isLive = false,
        )
        WorkflowPhase.COMPLETED -> WorkflowPhaseStyle(
            label = uiText("workspace.status.completed", "已完成"),
            icon = Icons.Default.CheckCircle,
            color = MaterialTheme.colorScheme.primary,
            isLive = false,
        )
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (isLive) {
                LivePulseDot(color)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = (12 * fs).sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                fontFamily = fontFamily
            )
        }
    }
}

private data class WorkflowPhaseStyle(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val isLive: Boolean,
)

@Composable
private fun LivePulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "phase_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase_pulse_alpha"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(color.copy(alpha = alpha), androidx.compose.foundation.shape.CircleShape)
    )
}
