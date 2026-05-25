package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.uiText
import com.example.workspace.ORCHESTRATOR_NAME
import java.io.File

// ═══════════════════════════════════════════════════════════════════════════════
// 底部干预输入区
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 底部用户干预输入区。
 *
 * 布局与普通对话输入框保持一致：
 * - 左侧圆形 + 按钮，点击展开工具栏（含图片选择）
 * - 中间无边框圆角输入框
 * - 右侧圆形发送按钮
 *
 * 顶部显示一个上下文芯片，告诉用户当前消息会发给谁，以及什么时候发出（流式期间排队）。
 *
 * @param agentName 目标 Agent 名称（用 [ORCHESTRATOR_NAME] 做主控判断）
 * @param isStreaming 当前 Agent 是否正在流式输出，用于显示「将在本轮结束后送达」的提示
 */
@Composable
fun InterventionInputArea(
    agentName: String,
    onSend: (String, String?) -> Unit,
    isStreaming: Boolean = false,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale

    var textInput by remember { mutableStateOf("") }
    var showToolbar by remember { mutableStateOf(false) }
    val isOrchestrator = agentName == ORCHESTRATOR_NAME
    val agentColorHex = LocalAgentColorLookup.current(agentName)

    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImagePath by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = File(context.cacheDir, "workspace_upload_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(it)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            selectedImageUri = it
            selectedImagePath = file.absolutePath
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )

            // ── 上下文行：「你正在向 X 发送消息」+ 状态提示 ──────────────
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

            // ── 工具栏（展开时显示）──────────────────────────────────────
            AnimatedVisibility(
                visible = showToolbar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 图片选择按钮
                    OutlinedCard(
                        modifier = Modifier.clickable {
                            imagePickerLauncher.launch("image/*")
                            showToolbar = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiText("chat.select_image", "选择图片"),
                                fontSize = (12 * fs).sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── 已选图片预览 ──────────────────────────────────────────────
            AnimatedVisibility(
                visible = selectedImageUri != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = uiText("chat.selected_image", "已选图片"),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiText("chat.image_attached", "已添加图片"),
                        fontSize = (12 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            selectedImageUri = null
                            selectedImagePath = null
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = uiText("chat.remove_image", "移除图片"),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ── 输入行 ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧圆形 + / × 按钮
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (showToolbar) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { showToolbar = !showToolbar },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (showToolbar) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (showToolbar)
                            uiText("chat.toolbar.collapse", "收起")
                        else
                            uiText("chat.toolbar.expand", "展开工具"),
                        tint = if (showToolbar) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 输入框
                val placeholder = if (isOrchestrator)
                    uiText("workspace.intervention.hint.orchestrator", "向主控 Agent 提问、调整方向或追加需求…")
                else
                    uiText("workspace.intervention.hint.sub", "向 %s 发送干预消息…").format(agentName)

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = {
                        Text(
                            if (selectedImagePath != null)
                                uiText("chat.input.hint_with_image", "添加描述（可选）...")
                            else
                                placeholder,
                            fontSize = (15 * fs).sp
                        )
                    },
                    maxLines = 4,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = (15 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 右侧圆形发送按钮
                val canSend = textInput.isNotBlank() || selectedImageUri != null
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable(enabled = canSend) {
                            val toSend = textInput.trim()
                            if (toSend.isNotEmpty() || selectedImageUri != null) {
                                onSend(toSend, selectedImagePath)
                                textInput = ""
                                selectedImageUri = null
                                selectedImagePath = null
                                showToolbar = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = uiText("action.send", "发送"),
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
