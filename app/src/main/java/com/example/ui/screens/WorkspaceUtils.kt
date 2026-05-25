package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalCustomColors
import com.example.workspace.AgentStatus
import com.example.workspace.ORCHESTRATOR_NAME
import com.example.workspace.TeammateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// 共享工具函数和组件
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 解析十六进制颜色字符串为 Compose Color。
 *
 * 支持 #RGB、#RRGGBB、#AARRGGBB、#RRGGBBAA 格式。
 * 解析失败时返回灰色。
 */
fun parseColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    return try {
        when (clean.length) {
            3 -> {
                val r = clean[0].toString().repeat(2).toInt(16)
                val g = clean[1].toString().repeat(2).toInt(16)
                val b = clean[2].toString().repeat(2).toInt(16)
                Color(0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
            }
            6 -> {
                val v = clean.toLong(16)
                Color(0xFF000000 or v)
            }
            8 -> {
                val v = clean.toLong(16)
                Color((v shr 24) or ((v and 0xFFFFFF) shl 8) or 0xFF)
            }
            else -> Color.Gray
        }
    } catch (_: Exception) {
        Color.Gray
    }
}

/**
 * 格式化时间戳为 HH:mm:ss。
 */
fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Agent 状态图标（共享组件）。
 *
 * 在 Tab 栏、任务面板等多处复用，统一视觉风格。
 *
 * @param status Agent 当前状态
 * @param size 图标大小，默认 14.dp
 */
@Composable
fun AgentStatusIcon(status: AgentStatus, size: Dp = 14.dp) {
    when (status) {
        AgentStatus.STREAMING, AgentStatus.WAITING_TOOL -> {
            CircularProgressIndicator(
                modifier = Modifier.size(size),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        AgentStatus.IDLE -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(size)
            )
        }
        AgentStatus.COMPLETED -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = LocalCustomColors.current.success,
                modifier = Modifier.size(size)
            )
        }
        AgentStatus.ERROR -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(size)
            )
        }
    }
}

/**
 * Agent 颜色标识点（共享组件）。
 */
@Composable
fun AgentColorDot(colorHex: String, size: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(parseColor(colorHex), androidx.compose.foundation.shape.CircleShape)
    )
}


// ═══════════════════════════════════════════════════════════════════════════════
// 工作流阶段（Workflow Phase）
//
// 工作区的整体阶段，比单 Agent 状态更高层，用于顶部 Pill 提示用户：
//   - PLANNING       Orchestrator 正在思考/编排（流式但还没创建 Sub-Agent）
//   - WORKING        有 Sub-Agent 正在执行
//   - AWAITING_USER  全员 IDLE，正在等用户输入或 Sub-Agent 上报
//   - SYNTHESIZING   Sub-Agent 已 idle，Orchestrator 正在汇总
//   - COMPLETED      工作区已结束
// ═══════════════════════════════════════════════════════════════════════════════

enum class WorkflowPhase {
    PLANNING,
    WORKING,
    AWAITING_USER,
    SYNTHESIZING,
    COMPLETED,
}

/**
 * 推断当前工作区处于哪个工作流阶段。
 *
 * @param isActive 当前会话是否处于 active 状态
 * @param agentStatuses Agent 状态映射
 * @param hasSubAgents 当前是否有任何 Sub-Agent（不含 Orchestrator）
 */
fun resolveWorkflowPhase(
    isActive: Boolean,
    agentStatuses: Map<String, AgentStatus>,
    hasSubAgents: Boolean,
): WorkflowPhase {
    if (!isActive) return WorkflowPhase.COMPLETED

    val orchestratorStatus = agentStatuses[ORCHESTRATOR_NAME] ?: AgentStatus.IDLE
    val orchestratorBusy = orchestratorStatus == AgentStatus.STREAMING ||
        orchestratorStatus == AgentStatus.WAITING_TOOL

    val subAgentBusy = agentStatuses
        .filterKeys { it != ORCHESTRATOR_NAME }
        .values
        .any { it == AgentStatus.STREAMING || it == AgentStatus.WAITING_TOOL }

    return when {
        subAgentBusy -> WorkflowPhase.WORKING
        orchestratorBusy && hasSubAgents -> WorkflowPhase.SYNTHESIZING
        orchestratorBusy -> WorkflowPhase.PLANNING
        else -> WorkflowPhase.AWAITING_USER
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Agent 颜色查找（供子组件着色 Agent 名称使用）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 提供根据 agentName 查询 #RRGGBB 颜色字符串的能力。
 * 在 [WorkspaceScreen] 顶层注入，子组件（消息卡片、任务面板等）
 * 通过 `LocalAgentColorLookup.current("agentName")` 获取颜色，
 * 无需层层向下传递 [TeammateState] 列表。
 */
val LocalAgentColorLookup = staticCompositionLocalOf<(String) -> String?> { { null } }
