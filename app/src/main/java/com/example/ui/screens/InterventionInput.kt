package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.uiText
import com.example.workspace.ORCHESTRATOR_NAME

// ═══════════════════════════════════════════════════════════════════════════════
// 底部干预输入区
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 底部用户干预输入区。
 *
 * 顶部显示一个上下文芯片，告诉用户当前消息会发给谁，以及什么时候发出（流式期间排队）。
 *
 * @param agentName 目标 Agent 名称（用 [ORCHESTRATOR_NAME] 做主控判断）
 * @param isStreaming 当前 Agent 是否正在流式输出，用于显示「将在本轮结束后送达」的提示
 */
@Composable
fun InterventionInputArea(
    agentName: String,
    onSend: (String) -> Unit,
    isStreaming: Boolean = false,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale

    var textInput by remember { mutableStateOf("") }
    val isOrchestrator = agentName == ORCHESTRATOR_NAME
    val agentColorHex = LocalAgentColorLookup.current(agentName)

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )

            // 上下文行：「你正在向 X 发送消息」+ 状态提示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiText("workspace.intervention.target_prefix", "发送给 "),
                    fontSize = (10 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (isOrchestrator) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                } else if (agentColorHex != null) {
                    AgentColorDot(agentColorHex, size = 7.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = agentName,
                    fontSize = (10 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = agentColorHex?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )

                if (isStreaming) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiText("workspace.intervention.queued_hint", "· 将在本轮结束后送达"),
                        fontSize = (9.5f * fs).sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val placeholder = if (isOrchestrator)
                    uiText("workspace.intervention.hint.orchestrator", "向主控 Agent 提问、调整方向或追加需求…")
                else
                    uiText("workspace.intervention.hint.sub", "向 %s 发送干预消息…").format(agentName)

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text(placeholder, fontSize = (14 * fs).sp) },
                    maxLines = 3,
                    textStyle = LocalTextStyle.current.copy(fontSize = (14 * fs).sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                IconButton(
                    onClick = {
                        val toSend = textInput.trim()
                        if (toSend.isNotEmpty()) {
                            onSend(toSend)
                            textInput = ""
                        }
                    },
                    enabled = textInput.isNotBlank(),
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (textInput.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = uiText("action.send", "发送"),
                        tint = if (textInput.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
