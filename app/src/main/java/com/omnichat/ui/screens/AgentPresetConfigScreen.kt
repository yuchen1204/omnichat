package com.omnichat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnichat.R
import com.omnichat.ui.theme.uiText
import com.omnichat.data.AgentPreset
import com.omnichat.data.ModelConfig
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.viewmodel.WorkspaceViewModel
import androidx.compose.ui.res.stringResource

/**
 * Agent 预设管理界面。
 *
 * 允许用户创建、修改和删除 Agent 预设模板，以在工作区创建子 Agent 时匹配应用。
 *
 * 需求：1.1–1.7
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPresetConfigScreen(
    workspaceViewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val presets by workspaceViewModel.agentPresets.collectAsStateWithLifecycle()
    val modelConfigs by workspaceViewModel.modelConfigs.collectAsStateWithLifecycle()

    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    var showForm by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<AgentPreset?>(null) }

    // 表单状态
    var nameInput by remember { mutableStateOf("") }
    var descriptionInput by remember { mutableStateOf("") }
    var systemPromptInput by remember { mutableStateOf("") }
    var selectedModelId by remember { mutableStateOf<Long?>(null) }
    
    // 校验与删除提示
    var nameError by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<AgentPreset?>(null) }

    // 下拉框展开状态
    var dropdownExpanded by remember { mutableStateOf(false) }

    // 辅助动作：启动新增或编辑模式
    val startEditing = { preset: AgentPreset? ->
        editingPreset = preset
        if (preset != null) {
            nameInput = preset.name
            descriptionInput = preset.description
            systemPromptInput = preset.systemPrompt
            selectedModelId = preset.modelConfigId
        } else {
            nameInput = ""
            descriptionInput = ""
            systemPromptInput = ""
            selectedModelId = null
        }
        nameError = null
        showForm = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (!showForm) {
            // 新建预设按钮
            Button(
                onClick = { startEditing(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp * spacingMultiplier)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiText("preset.button.new", R.string.preset_button_new),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 预设列表
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp * spacingMultiplier)
            ) {
                if (presets.isEmpty()) {
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
                                    text = uiText("preset.list.empty", R.string.preset_list_empty),
                                    fontSize = (12 * fs).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(presets, key = { it.id }) { preset ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { startEditing(preset) }
                                .testTag("preset_item_${preset.id}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(10.dp * spacingMultiplier)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = preset.name,
                                        fontSize = (15 * fs).sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = resolvedFontFamily,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row {
                                        IconButton(
                                            onClick = { startEditing(preset) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = uiText("preset.action.edit", R.string.preset_action_edit),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { showDeleteDialog = preset },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = uiText("preset.action.delete", R.string.preset_action_delete),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                if (preset.description.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (preset.description.length > 50) preset.description.take(50) + "..." else preset.description,
                                        fontSize = (12 * fs).sp,
                                        fontFamily = resolvedFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                val matchedModel = modelConfigs.find { it.id == preset.modelConfigId }
                                if (matchedModel != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = uiText("preset.model.tag", R.string.preset_model_tag).format(matchedModel.name),
                                        fontSize = (11 * fs).sp,
                                        fontFamily = resolvedFontFamily,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 表单编辑器卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp * spacingMultiplier),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (editingPreset == null) uiText("preset.form.new.title", R.string.preset_form_new_title) else uiText("preset.form.edit.title", R.string.preset_form_edit_title),
                        fontSize = (14 * fs).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp * spacingMultiplier))

                    // 名称输入框（必填校验）
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { 
                            nameInput = it
                            if (it.trim().isNotEmpty()) nameError = null
                        },
                        label = { Text(uiText("preset.form.name", R.string.preset_form_name), fontSize = (12 * fs).sp) },
                        isError = nameError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (nameError != null) {
                        Text(
                            text = nameError!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = (11 * fs).sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp * spacingMultiplier))

                    // 描述
                    OutlinedTextField(
                        value = descriptionInput,
                        onValueChange = { descriptionInput = it },
                        label = { Text(uiText("preset.form.desc", R.string.preset_form_desc), fontSize = (12 * fs).sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp * spacingMultiplier))

                    // 系统提示词 System Prompt
                    OutlinedTextField(
                        value = systemPromptInput,
                        onValueChange = { systemPromptInput = it },
                        label = { Text(uiText("preset.form.system.prompt", R.string.preset_form_system_prompt), fontSize = (12 * fs).sp) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp * spacingMultiplier))

                    // 模型配置选择器 (下拉框)
                    val selectedModelName = modelConfigs.find { it.id == selectedModelId }?.name
                        ?: uiText("preset.form.model.default", R.string.preset_form_model_default)

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedModelName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(uiText("preset.form.model", R.string.preset_form_model), fontSize = (12 * fs).sp) },
                            trailingIcon = {
                                IconButton(onClick = { dropdownExpanded = !dropdownExpanded }) {
                                    Icon(imageVector = if (dropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.coerceIn(8, 16).dp),
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            DropdownMenuItem(
                                text = { Text(uiText("preset.form.model.default", R.string.preset_form_model_default)) },
                                onClick = {
                                    selectedModelId = null
                                    dropdownExpanded = false
                                }
                            )
                            modelConfigs.forEach { config ->
                                DropdownMenuItem(
                                    text = { Text(config.name) },
                                    onClick = {
                                        selectedModelId = config.id
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp * spacingMultiplier))

                    // 控制按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showForm = false }) {
                            Text(uiText("action.cancel", R.string.action_cancel), fontSize = (13 * fs).sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        val presetNameEmptyError = uiText("preset.name.empty.error", R.string.preset_name_empty_error)
                        Button(
                            onClick = {
                                val trimmedName = nameInput.trim()
                                if (trimmedName.isEmpty()) {
                                    nameError = presetNameEmptyError
                                } else {
                                    val toSave = AgentPreset(
                                        id = editingPreset?.id ?: 0,
                                        name = trimmedName,
                                        description = descriptionInput.trim(),
                                        systemPrompt = systemPromptInput.trim(),
                                        modelConfigId = selectedModelId,
                                        createdAt = editingPreset?.createdAt ?: System.currentTimeMillis()
                                    )
                                    workspaceViewModel.saveAgentPreset(toSave)
                                    showForm = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp * spacingMultiplier)
                        ) {
                            Text(uiText("action.save", R.string.action_save), fontSize = (13 * fs).sp)
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteDialog?.let { preset ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(uiText("preset.dialog.delete.title", R.string.preset_dialog_delete_title)) },
            text = {
                Text(
                    text = uiText("preset.dialog.delete.body", R.string.preset_dialog_delete_body).format(preset.name),
                    fontSize = (14 * fs).sp,
                    lineHeight = (20 * fs).sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        workspaceViewModel.deleteAgentPreset(preset)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape((LocalUISettings.current.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) {
                    Text(uiText("action.delete", R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = null },
                    shape = RoundedCornerShape((LocalUISettings.current.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                ) {
                    Text(uiText("action.cancel", R.string.action_cancel))
                }
            }
        )
    }
}
