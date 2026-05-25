package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.PromptTemplate
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ChatViewModel

@Composable
fun MemoryAndPromptView(viewModel: ChatViewModel) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val templates by viewModel.promptTemplates.collectAsStateWithLifecycle()
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()

    var manualMemoryText by remember { mutableStateOf("") }
    var activeSubTab by remember { mutableStateOf("memory") }

    val defaultProvider = modelConfigs.find { it.isDefaultProvider }
    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Tab selectors
        TabRow(
            selectedTabIndex = if (activeSubTab == "memory") 0 else 1,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Tab(selected = activeSubTab == "memory", onClick = { activeSubTab = "memory" }) {
                Text(uiText("memory.tab.memory_library", "长效对话记忆库 (%d)").format(memories.size), modifier = Modifier.padding(12.dp), fontSize = (14 * fs).sp)
            }
            Tab(selected = activeSubTab == "prompts", onClick = { activeSubTab = "prompts" }) {
                Text(uiText("memory.bbbf4e03", "系统Prompt模板"), modifier = Modifier.padding(12.dp), fontSize = (14 * fs).sp)
            }
        }

        if (activeSubTab == "memory") {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp * spacingMultiplier)
            ) {
                // 1. 副模型配置卡片
                item {
                    MemoryModelSelectorCard(
                        defaultProvider = defaultProvider,
                        allConfigs = modelConfigs,
                        allModelsFlow = { viewModel.getModelsByProviderFlow(it) },
                        onModelSelected = { provider, modelId ->
                            viewModel.updateMemoryModelId(modelId, provider.id)
                        }
                    )
                }

                // 2. 手动新增偏好/记忆卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp * spacingMultiplier),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = uiText("memory.manual.input.title", "手动录入长效记忆 / 用户首选项偏好"),
                                fontSize = (12 * fs).sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = resolvedFontFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp * spacingMultiplier))
                            OutlinedTextField(
                                value = manualMemoryText,
                                onValueChange = { manualMemoryText = it },
                                placeholder = { Text(uiText("memory.eaea8fbf", "例如：用户习惯在提问时使用 Kotlin；希望 AI 回答尽量精炼..."), fontSize = (12 * fs).sp) },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = (13 * fs).sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp * spacingMultiplier))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        if (manualMemoryText.isNotBlank()) {
                                            viewModel.insertManualMemory(manualMemoryText.trim())
                                            manualMemoryText = ""
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp * spacingMultiplier)
                                ) {
                                    Text(uiText("memory.51c52a46", "添加偏好"), fontSize = (12 * fs).sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                // 3. 列表标题和清空按钮
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiText("memory.list.title", "AI 自我反省提炼的长效记忆 (%d):").format(memories.size),
                            fontSize = (12 * fs).sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (memories.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearAllMemories() },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(uiText("memory.7c4e09ff", "清空全部"), fontSize = (12 * fs).sp)
                            }
                        }
                    }
                }

                // 4. 记忆列表项
                if (memories.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp * spacingMultiplier),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = uiText("memory.empty.hint", "暂无长效记忆。你可以直接在聊天中告诉 AI 你的习惯偏好，或者等待 AI 在对话后台分析后自动提炼生成！"),
                                    textAlign = TextAlign.Center,
                                    fontSize = (12 * fs).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    lineHeight = (16 * fs).sp
                                )
                            }
                        }
                    }
                } else {
                    items(memories) { memory ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("memory_item_${memory.id}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(10.dp * spacingMultiplier)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = memory.content,
                                    fontSize = (13 * fs).sp,
                                    fontFamily = resolvedFontFamily,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = { viewModel.deleteMemoryItem(memory.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = uiText("memory.63e4284a", "删除"),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Prompt custom templates section
            var newTemplateName by remember { mutableStateOf("") }
            var newTemplateText by remember { mutableStateOf("") }
            var isCreatingTemp by remember { mutableStateOf(false) }

            if (isCreatingTemp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(uiText("memory.d1ce90a9", "创建自定义 System 模板"), fontWeight = FontWeight.Bold, fontSize = (14 * fs).sp, fontFamily = resolvedFontFamily)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newTemplateName,
                            onValueChange = { newTemplateName = it },
                            label = { Text(uiText("memory.1866d6dc", "模版说明/标题 (如: Kotlin 写法大师)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newTemplateText,
                            onValueChange = { newTemplateText = it },
                            label = { Text(uiText("memory.8278e828", "System Prompt 文字")) },
                            placeholder = { Text(uiText("memory.20754aa0", "必须包含占位符 [CROSS_SESSION_MEMORY] 自动编排记忆数据")) },
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { isCreatingTemp = false }) {
                                Text(uiText("memory.b9716387", "取消"))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newTemplateName.isNotBlank() && newTemplateText.isNotBlank()) {
                                        viewModel.insertTemplate(
                                            PromptTemplate(
                                                name = newTemplateName.trim(),
                                                templateText = newTemplateText.trim()
                                            )
                                        )
                                        newTemplateName = ""
                                        newTemplateText = ""
                                        isCreatingTemp = false
                                    }
                                }
                            ) {
                                Text(uiText("memory.f489c32f", "保存模板"))
                            }
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        isCreatingTemp = true
                        newTemplateText = "You are an AI Coding master.\n\nHere are historical preferences about the user:\n[CROSS_SESSION_MEMORY]"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = uiText("memory.3f63a83c", "新增"))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(uiText("memory.8bed6b57", "新增 System 模版"), fontSize = (13 * fs).sp)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(templates) { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
                            .testTag("prompt_template_${template.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = template.isActive,
                                        onClick = { viewModel.selectTemplate(template.id) },
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = template.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (14 * fs).sp,
                                        fontFamily = resolvedFontFamily,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (!template.isActive && templates.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.deleteTemplate(template) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = uiText("memory.63e4284a", "删除"),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = template.templateText,
                                    fontSize = (11 * fs).sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 5
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
