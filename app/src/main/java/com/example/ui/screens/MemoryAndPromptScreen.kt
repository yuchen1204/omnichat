package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.viewmodel.ChatViewModel

@Composable
fun MemoryAndPromptView(viewModel: ChatViewModel) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val templates by viewModel.promptTemplates.collectAsStateWithLifecycle()
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val isBackfillingTags = viewModel.isBackfillingTags

    var manualMemoryText by remember { mutableStateOf("") }
    var activeSubTab by remember { mutableStateOf("memory") }
    var isManualInputExpanded by remember { mutableStateOf(false) }

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
                Text(stringResource(R.string.memory_tab_memory_library, memories.size), modifier = Modifier.padding(12.dp), fontSize = (14 * fs).sp)
            }
            Tab(selected = activeSubTab == "prompts", onClick = { activeSubTab = "prompts" }) {
                Text(stringResource(R.string.memory_system_prompt_templates), modifier = Modifier.padding(12.dp), fontSize = (14 * fs).sp)
            }
        }

        if (activeSubTab == "memory") {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp * spacingMultiplier)
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

                // 2. 手动新增偏好/记忆卡片（可折叠）
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp * spacingMultiplier),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isManualInputExpanded = !isManualInputExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.memory_manual_input_title),
                                    fontSize = (12 * fs).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = resolvedFontFamily,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (isManualInputExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(
                                visible = isManualInputExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.height(10.dp * spacingMultiplier))
                                    OutlinedTextField(
                                        value = manualMemoryText,
                                        onValueChange = { manualMemoryText = it },
                                        placeholder = { Text(stringResource(R.string.memory_input_hint), fontSize = (12 * fs).sp) },
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
                                    Spacer(modifier = Modifier.height(10.dp * spacingMultiplier))
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
                                            Text(stringResource(R.string.memory_add_preference), fontSize = (12 * fs).sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 列表标题和操作按钮（两行布局，避免溢出）
                item {
                    val untaggedCount = memories.count { it.tags.isBlank() }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.memory_list_title, memories.size),
                            fontSize = (12 * fs).sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (untaggedCount > 0) {
                                TextButton(
                                    onClick = { viewModel.manualBackfillTags() },
                                    enabled = !isBackfillingTags
                                ) {
                                    if (isBackfillingTags) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        stringResource(R.string.memory_backfill_tags, untaggedCount),
                                        fontSize = (12 * fs).sp
                                    )
                                }
                            }
                            if (memories.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.clearAllMemories() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text(stringResource(R.string.memory_clear_all), fontSize = (12 * fs).sp)
                                }
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
                                    text = stringResource(R.string.memory_empty_hint),
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
                            colors = CardDefaults.cardColors(
                                containerColor = if (memory.pinned)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            ),
                            border = BorderStroke(
                                0.5.dp,
                                if (memory.pinned)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(10.dp * spacingMultiplier)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (memory.pinned) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = memory.content,
                                        fontSize = (13 * fs).sp,
                                        fontFamily = resolvedFontFamily,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (memory.tags.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            memory.tags.split(",").filter { it.isNotBlank() }.forEach { tag ->
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = {
                                                        Text(
                                                            text = tag,
                                                            fontSize = 10.sp,
                                                            lineHeight = 12.sp
                                                        )
                                                    },
                                                    modifier = Modifier.height(20.dp),
                                                    border = null,
                                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                Column {
                                    IconButton(
                                        onClick = { viewModel.togglePinMemory(memory) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (memory.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                            contentDescription = if (memory.pinned)
                                                stringResource(R.string.memory_unpin)
                                            else
                                                stringResource(R.string.memory_pin),
                                            tint = if (memory.pinned)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteMemoryItem(memory.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.memory_delete),
                                            tint = MaterialTheme.colorScheme.error.copy(
                                                alpha = if (memory.pinned) 0.3f else 0.7f
                                            ),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
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
                        Text(stringResource(R.string.memory_create_template), fontWeight = FontWeight.Bold, fontSize = (14 * fs).sp, fontFamily = resolvedFontFamily)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newTemplateName,
                            onValueChange = { newTemplateName = it },
                            label = { Text(stringResource(R.string.memory_template_title_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newTemplateText,
                            onValueChange = { newTemplateText = it },
                            label = { Text(stringResource(R.string.memory_template_prompt_text)) },
                            placeholder = { Text(stringResource(R.string.memory_template_placeholder_hint)) },
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { isCreatingTemp = false }) {
                                Text(stringResource(R.string.memory_cancel))
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
                                Text(stringResource(R.string.memory_save_template))
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
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.memory_add))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.memory_add_system_template), fontSize = (13 * fs).sp)
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
                                            contentDescription = stringResource(R.string.memory_delete),
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
