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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape

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
    fetchedModels: List<com.example.data.FetchedModel>,
    onSubmit: (String, Long, String?, String?) -> Unit,
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val spacingMultiplier = uiSettings.spacingMultiplier
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    var taskText by remember { mutableStateOf("") }

    val defaultModelConfig = modelConfigs.find { it.isDefaultProvider } ?: modelConfigs.firstOrNull()
    var selectedConfigId by remember { mutableStateOf<Long?>(defaultModelConfig?.id) }
    var selectedModelId by remember { mutableStateOf<String?>(defaultModelConfig?.selectedModelId) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    
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

    val cameraPermissionState = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionState.value = isGranted
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val tempFile = java.io.File(
                context.cacheDir,
                "camera_${System.currentTimeMillis()}.jpg"
            )
            java.io.FileOutputStream(tempFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            selectedImagePath = tempFile.absolutePath
            selectedImageUri = Uri.fromFile(tempFile)
        }
    }

    var showToolbar by remember { mutableStateOf(false) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(modelConfigs) {
        if (selectedConfigId == null && modelConfigs.isNotEmpty()) {
            val cfg = modelConfigs.find { it.isDefaultProvider } ?: modelConfigs.first()
            selectedConfigId = cfg.id
            selectedModelId = cfg.selectedModelId
        }
    }

    val selectedConfig = modelConfigs.find { it.id == selectedConfigId }
    val selectedModelName = if (selectedConfig != null) {
        "${selectedConfig.name} - ${selectedModelId ?: selectedConfig.selectedModelId}"
    } else {
        uiText("workspace.setup.model.none", "选择模型")
    }

    // 预处理数据以按 Provider 分组展示
    val modelsByProvider = remember(fetchedModels) {
        fetchedModels.groupBy { it.providerId }
    }

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

        // Send Area (Material design)
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f),
                    thickness = 0.5.dp
                )

                // ── 工具栏（展开时显示）──────────────────────────────
                androidx.compose.animation.AnimatedVisibility(
                    visible = showToolbar,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // 当前模型状态行
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(com.example.ui.theme.LocalCustomColors.current.success)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiText("chat.current.model", "当前模型: %s  ·  %s").format(
                                    selectedModelId ?: "",
                                    selectedConfig?.name ?: ""
                                ),
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // 工具按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val toolBtnShape = RoundedCornerShape(uiSettings.cornerRadiusDp.coerceIn(6, 16).dp)
                            val toolBtnBorder = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            val toolBtnColors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f))

                            // 切换模型按钮
                            Box {
                                OutlinedCard(
                                    modifier = Modifier
                                        .androidx.compose.foundation.clickable { dropdownExpanded = true },
                                    shape = toolBtnShape,
                                    border = toolBtnBorder,
                                    colors = toolBtnColors
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Build,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = uiText("chat.57841df8", "切换模型"),
                                            fontSize = (12 * fs).sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                                ) {
                                    modelConfigs.forEach { config ->
                                        val providerModels = modelsByProvider[config.id] ?: emptyList()
                                        DropdownMenuItem(
                                            text = { 
                                                Text(
                                                    config.name, 
                                                    fontSize = (13 * fs).sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                ) 
                                            },
                                            onClick = {
                                                selectedConfigId = config.id
                                                selectedModelId = config.selectedModelId
                                                dropdownExpanded = false
                                            }
                                        )
                                        if (providerModels.isNotEmpty()) {
                                            providerModels.forEach { fm ->
                                                DropdownMenuItem(
                                                    text = { 
                                                        Text(
                                                            "  ${fm.modelId}", 
                                                            fontSize = (13 * fs).sp,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        ) 
                                                    },
                                                    onClick = {
                                                        selectedConfigId = config.id
                                                        selectedModelId = fm.modelId
                                                        dropdownExpanded = false
                                                    }
                                                )
                                            }
                                        } else {
                                            DropdownMenuItem(
                                                text = { 
                                                    Text(
                                                        "  ${config.selectedModelId} (默认)", 
                                                        fontSize = (13 * fs).sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    ) 
                                                },
                                                onClick = {
                                                    selectedConfigId = config.id
                                                    selectedModelId = config.selectedModelId
                                                    dropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 图片选择按钮
                            OutlinedCard(
                                modifier = Modifier
                                    .androidx.compose.foundation.clickable {
                                        imagePickerLauncher.launch("image/*")
                                    },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = toolBtnColors
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

                            // 拍照按钮
                            OutlinedCard(
                                modifier = Modifier
                                    .androidx.compose.foundation.clickable {
                                        if (cameraPermissionState.value) {
                                            cameraLauncher.launch(null)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                shape = toolBtnShape,
                                border = toolBtnBorder,
                                colors = toolBtnColors
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiText("chat.take_photo", "拍照"),
                                        fontSize = (12 * fs).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 已选图片预览 ────────────────────────────────────────────
                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedImageUri != null || selectedImagePath != null,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 图片预览
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .androidx.compose.foundation.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(8.dp)
                                )
                        ) {
                            if (selectedImagePath != null || selectedImageUri != null) {
                                AsyncImage(
                                    model = selectedImageUri ?: selectedImagePath,
                                    contentDescription = uiText("chat.selected_image", "已选图片"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = uiText("chat.image_attached", "已添加图片"),
                            fontSize = (12 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // 清除图片按钮
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

                // ── 输入行 ────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // + 按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (showToolbar) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .androidx.compose.foundation.clickable { showToolbar = !showToolbar },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showToolbar) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (showToolbar) uiText("chat.toolbar.collapse", "收起") else uiText("chat.toolbar.expand", "展开工具"),
                            tint = if (showToolbar) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Material styled text input block
                    OutlinedTextField(
                        value = taskText,
                        onValueChange = { taskText = it },
                        placeholder = {
                            val hint = if (selectedImagePath != null || selectedImageUri != null) {
                                uiText("chat.input.hint_with_image", "添加描述（可选）...")
                            } else {
                                uiText("workspace.setup.task_placeholder", "例如：请帮我写一段 Kotlin 代码，并创建子 Agent 进行 Code Review...")
                            }
                            Text(hint, fontSize = (15 * fs).sp)
                        },
                        maxLines = 4,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = (15 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                            val toSend = taskText.trim()
                            val hasImage = selectedImagePath != null || selectedImageUri != null
                            if ((toSend.isNotBlank() || hasImage) && selectedConfigId != null) {
                                onSubmit(toSend, selectedConfigId!!, selectedModelId, selectedImagePath)
                                taskText = ""
                                selectedImageUri = null
                                selectedImagePath = null
                                showToolbar = false
                                keyboardController?.hide()
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Material filled icon button
                    val canSend = (taskText.isNotBlank() || selectedImagePath != null || selectedImageUri != null) && selectedConfigId != null
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .androidx.compose.foundation.clickable(enabled = canSend) {
                                val toSend = taskText.trim()
                                val hasImage = selectedImagePath != null || selectedImageUri != null
                                if ((toSend.isNotBlank() || hasImage) && selectedConfigId != null) {
                                    onSubmit(toSend, selectedConfigId!!, selectedModelId, selectedImagePath)
                                    taskText = ""
                                    selectedImageUri = null
                                    selectedImagePath = null
                                    showToolbar = false
                                    keyboardController?.hide()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = uiText("chat.send.contentDescription", "发送"),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
