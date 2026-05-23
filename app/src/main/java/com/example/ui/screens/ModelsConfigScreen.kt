package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ModelConfig
import com.example.data.FetchedModel
import com.example.ui.theme.LocalCustomColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.LocalUiStrings
import com.example.ui.viewmodel.ChatViewModel

@Composable
fun ModelsConfigView(viewModel: ChatViewModel) {
    val configs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    var isAddingNew by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<ModelConfig?>(null) }
    val strings = LocalUiStrings.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "API 提供商管理库",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Button(
                    onClick = { isAddingNew = true },
                    modifier = Modifier.testTag("add_config_btn")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = strings.models_add_provider)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.models_add_provider, fontSize = 13.sp)
                }
            }

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = strings.models_empty_hint,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(configs) { config ->
                        ModelConfigCard(
                            config = config,
                            onEdit = { configToEdit = config },
                            onDelete = { viewModel.deleteConfig(config) },
                            onCheckPrimary = { viewModel.setDefaultProvider(config.id) },
                            onCheckMemory = {}
                        )
                    }
                }
            }
        }

        if (isAddingNew) {
            ModelConfigDialog(
                viewModel = viewModel,
                config = null,
                onDismiss = {
                    isAddingNew = false
                    viewModel.clearFetchedModels()
                },
                onSave = { updated, models ->
                    viewModel.createOrUpdateConfig(updated, models)
                    isAddingNew = false
                    viewModel.clearFetchedModels()
                }
            )
        }

        if (configToEdit != null) {
            ModelConfigDialog(
                viewModel = viewModel,
                config = configToEdit,
                onDismiss = {
                    configToEdit = null
                    viewModel.clearFetchedModels()
                },
                onSave = { updated, models ->
                    viewModel.createOrUpdateConfig(updated, models)
                    configToEdit = null
                    viewModel.clearFetchedModels()
                }
            )
        }
    }
}

@Composable
fun ModelConfigCard(
    config: ModelConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCheckPrimary: () -> Unit,
    onCheckMemory: () -> Unit
) {
    val cardBackground = MaterialTheme.colorScheme.surface
    val borderStrokeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    var isExpanded by remember(config.isDefaultProvider) { mutableStateOf(config.isDefaultProvider) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${config.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.3).sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Default status indicator
                    if (config.isDefaultProvider) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "默认提供商",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Endpoint display
                    Row {
                        Text(
                            text = "Endpoint: ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = config.endpoint,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Masked API Key display
                    Row {
                        Text(
                            text = "API Key: ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val maskedKey = if (config.apiKey.length > 8) {
                            config.apiKey.take(4) + "••••••••" + config.apiKey.takeLast(4)
                        } else {
                            "••••••••"
                        }
                        Text(
                            text = maskedKey,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Custom headers count display
                    val headerCount = try {
                        val obj = org.json.JSONObject(config.customHeaders)
                        obj.length()
                    } catch (e: Exception) { 0 }
                    if (headerCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            Text(
                                text = "自定义请求头: ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$headerCount 个",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // iOS-Style Toggles (Grouped Settings Rows)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCheckPrimary() }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp))
                                        .padding(6.dp)
                                  ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "设为默认配置",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "将此 API 提供商作为全局使用",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Switch(
                                checked = config.isDefaultProvider,
                                onCheckedChange = { onCheckPrimary() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = LocalCustomColors.current.success,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    uncheckedBorderColor = Color.Transparent,
                                    checkedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier.scale(0.82f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Edit / Delete Actions row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onEdit,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("编辑", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelConfigDialog(
    viewModel: ChatViewModel,
    config: ModelConfig?,
    onDismiss: () -> Unit,
    onSave: (ModelConfig, List<FetchedModel>) -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "") }
    var endpoint by remember { mutableStateOf(config?.endpoint ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var selectedModelId by remember { mutableStateOf(config?.selectedModelId?.takeIf { it.isNotBlank() } ?: "gpt-4o") }

    var isApiKeyVisible by remember { mutableStateOf(false) }

    // Custom headers: list of (key, value) pairs for editing
    var headerPairs by remember {
        mutableStateOf<List<Pair<String, String>>>(
            try {
                val json = org.json.JSONObject(config?.customHeaders ?: "{}")
                json.keys().asSequence().map { k: String -> k to json.optString(k) }.toList()
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    val fetchedModels = viewModel.fetchedModels
    val isFetching = viewModel.isFetchingModels
    val fetchError = viewModel.modelFetchError

    // Query previously saved models from DB
    val savedModelsState = remember(config?.id) {
        if (config != null && config.id > 0) {
            viewModel.getModelsByProviderFlow(config.id)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    val storedModels = savedModelsState.value
    var dialogModels by remember { mutableStateOf<List<FetchedModel>>(emptyList()) }

    // Sync dialogModels with fetchedModels or storedModels
    LaunchedEffect(fetchedModels, storedModels) {
        dialogModels = if (fetchedModels.isNotEmpty()) {
            fetchedModels
        } else {
            storedModels
        }
    }

    // Clear fetched list upon entering dialog just to start fresh
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearFetchedModels()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (config == null) "新增提供商配置" else "编辑提供商配置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Provider ID Input (Name field)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("提供商 ID (Provider ID)") },
                    placeholder = { Text("如: openai, deepseek, silicon-flow") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                // Base Endpoint URL Input
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("OpenAI 兼容 Endpoint") },
                    placeholder = { Text("如: https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                // API Key Input
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.Info else Icons.Default.Lock,
                                contentDescription = if (isApiKeyVisible) "隐藏密钥" else "显示密钥"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Custom Headers Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义请求头 (Custom Headers):",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = { headerPairs = headerPairs + ("" to "") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("添加", fontSize = 12.sp)
                    }
                }

                if (headerPairs.isEmpty()) {
                    Text(
                        text = "暂无自定义请求头",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        headerPairs.forEachIndexed { index, pair ->
                            val hKey = pair.first
                            val hVal = pair.second
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = hKey,
                                    onValueChange = { newKey ->
                                        val updated = headerPairs.toMutableList()
                                        updated[index] = newKey to hVal
                                        headerPairs = updated
                                    },
                                    label = { Text("Header 名", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                                OutlinedTextField(
                                    value = hVal,
                                    onValueChange = { newVal ->
                                        val updated = headerPairs.toMutableList()
                                        updated[index] = hKey to newVal
                                        headerPairs = updated
                                    },
                                    label = { Text("值", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                                IconButton(
                                    onClick = {
                                        val updated = headerPairs.toMutableList()
                                        updated.removeAt(index)
                                        headerPairs = updated
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Auto-fetching section
                Text(
                    text = "自动拉取并解析可用模型:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Button(
                    onClick = {
                        val headersJson = org.json.JSONObject().apply {
                            headerPairs.filter { it.first.isNotBlank() }.forEach { put(it.first, it.second) }
                        }.toString()
                        viewModel.fetchModelsAndSave(endpoint, apiKey, config?.id ?: 0L, headersJson)
                    },
                    enabled = !isFetching && endpoint.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("正在拉取中...", fontSize = 12.sp)
                    } else {
                        Text("一键获取当前 Endpoint 的可用模型", fontSize = 12.sp)
                    }
                }

                if (fetchError != null) {
                    Text(
                        text = "拉取错误: $fetchError",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // If fetched models exist, list them as clickable cards
                if (dialogModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "可用模型列表 (点击模型名选择；点击下方标签可**手动修正** 思考/视觉/工具 调用能力):",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = LocalCustomColors.current.success,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dialogModels.take(60).forEach { m ->
                            val isSelected = selectedModelId == m.modelId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedModelId = m.modelId },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                ),
                                border = if (isSelected) BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary
                                ) else null
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = m.modelId,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = if (isSelected) 
                                                MaterialTheme.colorScheme.onPrimaryContainer 
                                            else 
                                                MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        
                                        // Context Badge
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (isSelected) 
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                                                    else 
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "Context: ${m.contextSize}",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) 
                                                    MaterialTheme.colorScheme.onPrimaryContainer 
                                                else 
                                                    MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    // Capabilities indicators (Interactive checkboxes/badges style)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        InteractiveCapabilityBadge(
                                            text = "💭 思考/推理",
                                            enabled = m.hasThinking,
                                            color = LocalCustomColors.current.success,
                                            onClick = {
                                                val index = dialogModels.indexOf(m)
                                                if (index != -1) {
                                                    val updatedList = dialogModels.toMutableList()
                                                    updatedList[index] = m.copy(hasThinking = !m.hasThinking)
                                                    dialogModels = updatedList
                                                }
                                            }
                                        )
                                        InteractiveCapabilityBadge(
                                            text = "👁️ 视觉",
                                            enabled = m.hasVision,
                                            color = MaterialTheme.colorScheme.primary,
                                            onClick = {
                                                val index = dialogModels.indexOf(m)
                                                if (index != -1) {
                                                    val updatedList = dialogModels.toMutableList()
                                                    updatedList[index] = m.copy(hasVision = !m.hasVision)
                                                    dialogModels = updatedList
                                                }
                                            }
                                        )
                                        InteractiveCapabilityBadge(
                                            text = "🛠️ 工具调用",
                                            enabled = m.hasToolUse,
                                            color = LocalCustomColors.current.warning,
                                            onClick = {
                                                val index = dialogModels.indexOf(m)
                                                if (index != -1) {
                                                    val updatedList = dialogModels.toMutableList()
                                                    updatedList[index] = m.copy(hasToolUse = !m.hasToolUse)
                                                    dialogModels = updatedList
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank()) name = "custom-provider"
                            if (selectedModelId.isBlank()) selectedModelId = "gpt-4o"
                            val headersJson = org.json.JSONObject().apply {
                                headerPairs.filter { it.first.isNotBlank() }.forEach { put(it.first, it.second) }
                            }.toString()
                            onSave(
                                ModelConfig(
                                    id = config?.id ?: 0,
                                    name = name,
                                    endpoint = endpoint,
                                    apiKey = apiKey,
                                    selectedModelId = selectedModelId,
                                    memoryModelId = config?.memoryModelId ?: selectedModelId,
                                    memoryProviderId = config?.memoryProviderId ?: 0L,
                                    isDefaultProvider = config?.isDefaultProvider ?: false,
                                    enableThinking = config?.enableThinking ?: false,
                                    thinkingEffort = config?.thinkingEffort ?: "medium",
                                    customHeaders = headersJson
                                ),
                                dialogModels
                            )
                        }
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveCapabilityBadge(text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (enabled) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                RoundedCornerShape(4.dp)
            )
            .border(
                width = 0.5.dp,
                color = if (enabled) color.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun CapabilityBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/**
 * Layout helper flow row for chips wrapping
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    itemSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
    ) {
        content()
    }
}

/**
 * 通用的两步模型选择器：先选 Provider，再选模型。
 * 用于副模型配置和聊天临时切换。
 */
@Composable
fun ProviderModelPicker(
    allConfigs: List<ModelConfig>,
    allModelsFlow: (Long) -> kotlinx.coroutines.flow.Flow<List<FetchedModel>>,
    currentProviderId: Long,   // 0 = 未选 / 与主相同
    currentModelId: String,
    onConfirm: (provider: ModelConfig, modelId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val successColor = LocalCustomColors.current.success
    val infoColor = LocalCustomColors.current.info
    val accentColor = LocalCustomColors.current.accent

    // 步骤：0 = 选 Provider，1 = 选模型
    var step by remember { mutableStateOf(0) }
    var pickedProvider by remember {
        mutableStateOf(allConfigs.find { it.id == currentProviderId } ?: allConfigs.firstOrNull())
    }

    val modelsState = remember(pickedProvider?.id) {
        val id = pickedProvider?.id ?: 0L
        if (id > 0L) allModelsFlow(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val models = modelsState.value

    var pickedModelId by remember { mutableStateOf(currentModelId) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step == 1) {
                        IconButton(
                            onClick = { step = 0 },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (step == 0) "选择 Provider" else "选择模型",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (step == 1 && pickedProvider != null) {
                            Text(
                                text = pickedProvider!!.name,
                                fontSize = 11.sp,
                                color = mutedTextColor
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = mutedTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = dividerColor,
                    thickness = 0.5.dp
                )

                if (step == 0) {
                    // ── 步骤 0：Provider 列表 ──────────────────────────
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allConfigs) { config ->
                            val isSelected = config.id == pickedProvider?.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pickedProvider = config
                                        pickedModelId = if (config.id == currentProviderId) currentModelId else ""
                                        step = 1
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else cardBg
                                ),
                                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = config.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (config.isDefaultProvider) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "默认",
                                                    fontSize = 9.sp,
                                                    color = successColor,
                                                    modifier = Modifier
                                                        .background(successColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = config.endpoint,
                                            fontSize = 10.sp,
                                            color = mutedTextColor,
                                            maxLines = 1
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = mutedTextColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── 步骤 1：模型列表 ──────────────────────────────
                    if (models.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = mutedTextColor,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "该 Provider 暂无已保存的模型列表",
                                    fontSize = 13.sp,
                                    color = mutedTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "请先在「模型配置」中拉取模型",
                                    fontSize = 11.sp,
                                    color = mutedTextColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(models) { model ->
                                val isSelected = model.modelId == pickedModelId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pickedModelId = model.modelId },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else cardBg
                                    ),
                                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.modelId,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                // Context badge
                                                Text(
                                                    text = model.contextSize,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (model.hasThinking) Text(
                                                    "💭 思考", fontSize = 9.sp, color = successColor,
                                                    modifier = Modifier.background(successColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (model.hasVision) Text(
                                                    "👁️ 视觉", fontSize = 9.sp, color = infoColor,
                                                    modifier = Modifier.background(infoColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (model.hasToolUse) Text(
                                                    "🛠️ 工具", fontSize = 9.sp, color = accentColor,
                                                    modifier = Modifier.background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "已选",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 确认按钮
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val p = pickedProvider ?: return@Button
                                val m = pickedModelId.takeIf { it.isNotBlank() } ?: return@Button
                                onConfirm(p, m)
                            },
                            enabled = pickedProvider != null && pickedModelId.isNotBlank()
                        ) {
                            Text("确认选择")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryModelSelectorCard(
    defaultProvider: ModelConfig?,
    allConfigs: List<ModelConfig>,
    allModelsFlow: (Long) -> kotlinx.coroutines.flow.Flow<List<FetchedModel>>,
    onModelSelected: (provider: ModelConfig, modelId: String) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val borderCol = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    
    val currentMemoryModelId = defaultProvider?.memoryModelId 
        ?: allConfigs.firstOrNull { it.memoryModelId.isNotBlank() }?.memoryModelId 
        ?: ""
    val currentMemoryProviderId = defaultProvider?.memoryProviderId 
        ?: allConfigs.firstOrNull { it.memoryModelId.isNotBlank() }?.memoryProviderId 
        ?: 0L

    var showPicker by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // 找到副模型所属的 Provider 名称
    val memoryProviderName = if (currentMemoryProviderId > 0L) {
        allConfigs.find { it.id == currentMemoryProviderId }?.name ?: "未知 Provider"
    } else {
        defaultProvider?.name ?: allConfigs.firstOrNull()?.name ?: ""
    }

    // 找到副模型的能力信息（从对应 Provider 的模型列表里查）
    val memoryProviderModels = remember(currentMemoryProviderId, defaultProvider?.id, allConfigs.size) {
        val id = if (currentMemoryProviderId > 0L) {
            currentMemoryProviderId
        } else {
            defaultProvider?.id ?: allConfigs.firstOrNull()?.id ?: 0L
        }
        if (id > 0L) allModelsFlow(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val currentModelInfo = memoryProviderModels.value.find { it.modelId == currentMemoryModelId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = surface
        ),
        border = BorderStroke(0.5.dp, borderCol)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(primaryColor, RoundedCornerShape(7.dp))
                        .padding(5.dp)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("副模型配置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Text("用于记忆优化分析 & 会话标题生成", fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f))
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = onSurface.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = borderCol, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 10.dp))

                    if (allConfigs.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("请先添加提供商", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("当前副模型", fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 4.dp))

                        // 当前选择展示 + 点击打开选择器
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPicker = true },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (currentMemoryModelId.isBlank()) {
                                        Text("未选择（将使用主模型）", fontSize = 13.sp, color = onSurface.copy(alpha = 0.5f))
                                    } else {
                                        Text(
                                            text = currentMemoryModelId,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = onSurface,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            // Provider 来源标签
                                            Text(
                                                text = memoryProviderName,
                                                fontSize = 9.sp,
                                                color = primaryColor,
                                                modifier = Modifier
                                                    .background(primaryColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                            if (currentModelInfo != null) {
                                                Text(
                                                    text = currentModelInfo.contextSize,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                                if (currentModelInfo.hasThinking) Text("💭", fontSize = 9.sp)
                                                if (currentModelInfo.hasVision) Text("👁️", fontSize = 9.sp)
                                                if (currentModelInfo.hasToolUse) Text("🛠️", fontSize = 9.sp)
                                            }
                                        }
                                    }
                                }
                                Icon(Icons.Default.Edit, contentDescription = "选择", tint = primaryColor, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "副模型在每次对话后台运行，负责提炼记忆条目并生成会话标题。可选择任意 Provider 的模型，建议用速度快、成本低的小模型。",
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.6f),
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ProviderModelPicker(
            allConfigs = allConfigs,
            allModelsFlow = allModelsFlow,
            currentProviderId = currentMemoryProviderId.takeIf { it > 0L } ?: (defaultProvider?.id ?: allConfigs.firstOrNull()?.id ?: 0L),
            currentModelId = currentMemoryModelId,
            onConfirm = { provider, modelId ->
                onModelSelected(provider, modelId)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
