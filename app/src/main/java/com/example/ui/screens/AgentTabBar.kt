package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.AgentTabState
import com.example.workspace.AgentStatus
import com.example.workspace.TeamState

// ═══════════════════════════════════════════════════════════════════════════════
// Agent Tab 栏
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Agent Tab 栏。
 *
 * 展示所有 Agent 的 Tab，包含：
 * - 主控 Agent 用皇冠图标突出
 * - 实时状态图标（流式/等待工具/完成/错误）
 * - 后台繁忙提示：选中其他 tab 时，busy 的 tab 在右上角显示一个小红点
 *
 * @param onTabSelected 选择 Tab 回调，接受新的 selectedTabIndex
 */
@Composable
fun AgentTabBar(
    agentTabs: List<AgentTabState>,
    selectedTabIndex: Int,
    teamState: TeamState?,
    agentStatuses: Map<String, AgentStatus>,
    onTabSelected: (Int) -> Unit,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    ScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        edgePadding = 0.dp,
        divider = {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    ) {
        agentTabs.forEachIndexed { index, tab ->
            val status = agentStatuses[tab.agentName] ?: tab.status
            val isSelected = selectedTabIndex == index
            val isBusy = status == AgentStatus.STREAMING || status == AgentStatus.WAITING_TOOL

            Tab(
                selected = isSelected,
                onClick = { onTabSelected(index) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Orchestrator -> 皇冠；其他 Agent -> 颜色点
                        if (tab.isOrchestrator) {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = uiText("workspace.tab.orchestrator", "主控 Agent"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        } else {
                            // 查找 sub-agent 的颜色（如果有的话）
                            val subAgent = teamState?.activeSubAgents?.find { it.name == tab.agentName }
                            if (subAgent != null) {
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                        }

                        Text(
                            text = tab.agentName,
                            fontSize = (14 * fs).sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = resolvedFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // 状态指示：未选中且 busy 时显示一个跳动小点提示后台仍在工作
                        Box(contentAlignment = Alignment.TopEnd) {
                            AgentStatusIcon(status)
                            if (!isSelected && isBusy) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .offset(x = 3.dp, y = (-3).dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}
