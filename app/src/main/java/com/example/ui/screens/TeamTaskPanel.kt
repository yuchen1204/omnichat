package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TeamTask
import com.example.ui.theme.LocalCustomColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.workspace.AgentStatus
import com.example.workspace.TeamState

// ═══════════════════════════════════════════════════════════════════════════════
// 右侧任务面板
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 任务面板：展示当前工作区的任务列表和 Agent 状态概览。
 *
 * 包含：
 * - Agent 列表：点击切换到对应 Tab，主控带皇冠图标，颜色点 + 状态图标
 * - 任务列表：按状态分组（执行中 / 等待 / 完成 / 失败），显示认领者、被阻塞的依赖、年龄
 *
 * @param onAgentClick 点击 Agent 行回调，返回 agentName，由调用方负责切换 Tab
 */
@Composable
fun TeamTaskPanel(
    teamTasks: List<TeamTask>,
    teamState: TeamState?,
    agentStatuses: Map<String, AgentStatus>,
    modifier: Modifier = Modifier,
    onAgentClick: (agentName: String) -> Unit = {},
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacingMultiplier = uiSettings.spacingMultiplier
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    val now = System.currentTimeMillis()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp * spacingMultiplier)
        ) {
            // ── Agent 概览 ──
            val teammates = teamState?.teammates ?: emptyMap()
            if (teammates.isNotEmpty()) {
                item(key = "header_agents") {
                    SectionHeader(
                        icon = Icons.Default.Groups,
                        text = uiText("workspace.panel.agents", "Agent 列表"),
                        countSuffix = teammates.size,
                        fs = fs,
                        fontFamily = resolvedFontFamily
                    )
                }

                // 主控排在最上
                val sortedTeammates = teammates.values.sortedByDescending { it.isOrchestrator }
                items(sortedTeammates, key = { it.identity.agentName }) { teammate ->
                    val agentName = teammate.identity.agentName
                    val status = agentStatuses[agentName] ?: teammate.status

                    AgentRow(
                        name = agentName,
                        colorHex = teammate.identity.color,
                        isOrchestrator = teammate.isOrchestrator,
                        status = status,
                        fs = fs,
                        fontFamily = resolvedFontFamily,
                        onClick = { onAgentClick(agentName) }
                    )
                }
            }

            // ── 任务列表（按状态分组）──
            if (teamTasks.isNotEmpty()) {
                val grouped = teamTasks.groupBy { it.status }
                val orderedStatuses = listOf("IN_PROGRESS", "PENDING", "COMPLETED", "FAILED")

                item(key = "header_tasks") {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader(
                        icon = Icons.AutoMirrored.Filled.Assignment,
                        text = uiText("workspace.panel.tasks", "任务列表"),
                        countSuffix = teamTasks.size,
                        fs = fs,
                        fontFamily = resolvedFontFamily
                    )
                }

                for (statusKey in orderedStatuses) {
                    val tasksInGroup = grouped[statusKey].orEmpty()
                    if (tasksInGroup.isEmpty()) continue

                    item(key = "status_${statusKey}_label") {
                        TaskStatusGroupLabel(statusKey, tasksInGroup.size, fs, resolvedFontFamily)
                    }

                    items(tasksInGroup, key = { task -> "task_${task.id}" }) { task ->
                        TaskCard(
                            task = task,
                            now = now,
                            fs = fs,
                            fontFamily = resolvedFontFamily,
                            onOwnerClick = if (task.owner != null) {
                                { onAgentClick(task.owner!!) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    countSuffix: Int,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            fontSize = (12.5f * fs).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = fontFamily
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = countSuffix.toString(),
            fontSize = (10 * fs).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontFamily = fontFamily
        )
    }
}

@Composable
private fun AgentRow(
    name: String,
    colorHex: String,
    isOrchestrator: Boolean,
    status: AgentStatus,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    onClick: () -> Unit,
) {
    val isBusy = status == AgentStatus.STREAMING || status == AgentStatus.WAITING_TOOL
    val rowColor = if (isBusy) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                   else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (isOrchestrator) {
            Icon(
                imageVector = Icons.Default.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
        } else {
            AgentColorDot(colorHex)
        }
        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = name,
            fontSize = (12 * fs).sp,
            fontWeight = if (isOrchestrator) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = fontFamily,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 状态文字 + 图标
        Text(
            text = statusLabel(status),
            fontSize = (10 * fs).sp,
            color = statusColor(status),
            fontFamily = fontFamily
        )
        Spacer(modifier = Modifier.width(5.dp))
        AgentStatusIcon(status, size = 12.dp)
    }
}

@Composable
private fun TaskStatusGroupLabel(
    statusKey: String,
    count: Int,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
) {
    val (label, color) = taskStatusBadge(statusKey)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "$label · $count",
            fontSize = (10.5f * fs).sp,
            fontWeight = FontWeight.Medium,
            color = color,
            fontFamily = fontFamily
        )
    }
}

@Composable
private fun TaskCard(
    task: TeamTask,
    now: Long,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    onOwnerClick: (() -> Unit)?,
) {
    val (badgeText, badgeColor) = taskStatusBadge(task.status)
    val ownerColorHex = task.owner?.let { LocalAgentColorLookup.current(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = (10 * fs).sp,
                    fontWeight = FontWeight.Medium,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = task.subject,
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = fontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // 元信息行：认领者 + 年龄 + blockedBy
        val meta = mutableListOf<@Composable () -> Unit>()

        if (task.owner != null) {
            meta.add {
                val mod = if (onOwnerClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onOwnerClick)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                } else Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = mod
                ) {
                    if (ownerColorHex != null) {
                        AgentColorDot(ownerColorHex, size = 6.dp)
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = task.owner!!,
                        fontSize = (10 * fs).sp,
                        color = ownerColorHex?.let { parseColor(it) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = fontFamily
                    )
                }
            }
        }

        // 任务年龄
        meta.add {
            Text(
                text = formatTaskAge(now - task.createdAt),
                fontSize = (10 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontFamily = fontFamily
            )
        }

        if (meta.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                meta.forEachIndexed { idx, item ->
                    if (idx > 0) {
                        Text(
                            text = "·",
                            fontSize = (10 * fs).sp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    item()
                }
            }
        }

        // blockedBy 依赖
        if (task.blockedBy.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = uiText("workspace.task.blocked_by", "依赖：%s")
                            .format(task.blockedBy.joinToString(", ")),
                        fontSize = (10 * fs).sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun taskStatusBadge(status: String): Pair<String, Color> {
    return when (status) {
        "PENDING" -> uiText("workspace.task.status.pending", "等待") to
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        "IN_PROGRESS" -> uiText("workspace.task.status.in_progress", "执行中") to
            MaterialTheme.colorScheme.primary
        "COMPLETED" -> uiText("workspace.task.status.completed", "完成") to
            LocalCustomColors.current.success
        "FAILED" -> uiText("workspace.task.status.failed", "失败") to
            MaterialTheme.colorScheme.error
        else -> status to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun statusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.IDLE -> uiText("workspace.agent.status.idle", "空闲")
    AgentStatus.STREAMING -> uiText("workspace.agent.status.streaming", "输出中")
    AgentStatus.WAITING_TOOL -> uiText("workspace.agent.status.tool", "工具中")
    AgentStatus.COMPLETED -> uiText("workspace.agent.status.done", "完成")
    AgentStatus.ERROR -> uiText("workspace.agent.status.error", "错误")
}

@Composable
private fun statusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.STREAMING, AgentStatus.WAITING_TOOL -> MaterialTheme.colorScheme.primary
    AgentStatus.COMPLETED -> LocalCustomColors.current.success
    AgentStatus.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
}

private fun formatTaskAge(deltaMs: Long): String {
    if (deltaMs < 0) return "刚刚"
    return when {
        deltaMs < 60_000 -> "${deltaMs / 1000}s 前"
        deltaMs < 3_600_000 -> "${deltaMs / 60_000}m 前"
        deltaMs < 86_400_000 -> "${deltaMs / 3_600_000}h 前"
        else -> "${deltaMs / 86_400_000}d 前"
    }
}
