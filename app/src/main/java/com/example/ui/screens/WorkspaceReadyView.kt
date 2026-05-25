package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ModelConfig
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText

// ═══════════════════════════════════════════════════════════════════════════════
// 工作区就绪界面（空状态 + 任务输入）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 工作区就绪界面。
 *
 * 不弹窗、不填写标题，直接进入「空状态 + 底部输入框」交互模式：
 * - 顶部：图标 + 欢迎文案（轻量空状态）
 * - 底部：模型选择器小 Chip + 任务输入框 + 发送按钮
 */
@Composable
fun WorkspaceReadyView(
    sessionTitle: String,
    modelConfigs: List<ModelConfig>,
    onSubmit: (String, Long) -> Unit,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacingMultiplier = uiSettings.spacingMultiplier
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    var taskText by remember { mutableStateOf("") }

    val defaultModel = modelConfigs.find { it.isDefaultProvider } ?: modelConfigs.firstOrNull()
    var selectedModelId by remember { mutableStateOf<Long?>(defaultModel?.id) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(modelConfigs) {
        if (selectedModelId == null && modelConfigs.isNotEmpty()) {
            selectedModelId = modelConfigs.find { it.isDefaultProvider }?.id ?: modelConfigs.first().id
        }
    }

    val selectedModelName = modelConfigs.find { it.id == selectedModelId }?.name
        ?: uiText("workspace.setup.model.none", "选择模型")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部空状态区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp * spacingMultiplier))
                Text(
                    text = uiText("workspace.ready.title", "准备好了"),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiText("workspace.ready.hint", "描述你的任务，AI 将自动分析并派遣多个 Agent 协作完成"),
                    fontSize = (13 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = resolvedFontFamily,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = (19 * fs).sp
                )
            }
        }

        // 底部输入区
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 模型选择器（小 Chip 风格）
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { dropdownExpanded = true },
                        label = {
                            Text(
                                text = selectedModelName,
                                fontSize = (12 * fs).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = if (dropdownExpanded) Icons.Default.KeyboardArrowUp
                                              else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.height(32.dp)
                    )
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        modelConfigs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(config.name, fontSize = (14 * fs).sp) },
                                onClick = {
                                    selectedModelId = config.id
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 任务输入 + 发送
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = taskText,
                        onValueChange = { taskText = it },
                        placeholder = {
                            Text(
                                text = uiText("workspace.setup.task_placeholder", "例如：请帮我写一段 Kotlin 代码，并创建子 Agent 进行 Code Review..."),
                                fontSize = (13 * fs).sp
                            )
                        },
                        minLines = 1,
                        maxLines = 5,
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = (14 * fs).sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val toSend = taskText.trim()
                            val modelId = selectedModelId
                            if (toSend.isNotEmpty() && modelId != null) {
                                onSubmit(toSend, modelId)
                                taskText = ""
                            }
                        },
                        enabled = taskText.isNotBlank() && selectedModelId != null,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (taskText.isNotBlank() && selectedModelId != null)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = uiText("workspace.setup.submit", "提交并启动"),
                            tint = if (taskText.isNotBlank() && selectedModelId != null)
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
