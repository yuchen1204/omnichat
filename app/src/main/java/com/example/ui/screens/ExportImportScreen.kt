package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.LocalCustomColors
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ExportImportStatus
import com.example.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExportImportView(
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val status = settingsViewModel.exportImportStatus

    // ── 导出选项状态 ──────────────────────────────────────────────────────
    var exportProviders by remember { mutableStateOf(true) }
    var exportMcp by remember { mutableStateOf(true) }
    var exportMemory by remember { mutableStateOf(false) }
    var exportColorSchemes by remember { mutableStateOf(true) }

    // ── 导入选项状态 ──────────────────────────────────────────────────────
    var importProviders by remember { mutableStateOf(true) }
    var importMcp by remember { mutableStateOf(true) }
    var importMemory by remember { mutableStateOf(false) }
    var importColorSchemes by remember { mutableStateOf(true) }
    var replaceExisting by remember { mutableStateOf(false) }

    // ── 导入确认对话框 ────────────────────────────────────────────────────
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showImportConfirm by remember { mutableStateOf(false) }

    // ── SAF 文件选择器 ────────────────────────────────────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            settingsViewModel.exportToUri(
                context = context,
                uri = uri,
                includeProviders = exportProviders,
                includeMcp = exportMcp,
                includeMemory = exportMemory,
                includeColorSchemes = exportColorSchemes
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirm = true
        }
    }

    // ── 状态提示 ──────────────────────────────────────────────────────────
    LaunchedEffect(status) {
        if (status is ExportImportStatus.Success || status is ExportImportStatus.Error) {
            kotlinx.coroutines.delay(4000)
            settingsViewModel.clearStatus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 状态横幅 ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = status !is ExportImportStatus.Idle,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (status) {
                is ExportImportStatus.Loading -> {
                    StatusBanner(
                        message = "处理中...",
                        color = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = null,
                        showProgress = true,
                        fs = fs
                    )
                }
                is ExportImportStatus.Success -> {
                    StatusBanner(
                        message = status.message,
                        color = LocalCustomColors.current.success.copy(alpha = 0.15f),
                        textColor = LocalCustomColors.current.success,
                        icon = Icons.Default.Check,
                        showProgress = false,
                        fs = fs
                    )
                }
                is ExportImportStatus.Error -> {
                    StatusBanner(
                        message = status.message,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        textColor = MaterialTheme.colorScheme.error,
                        icon = Icons.Default.Warning,
                        showProgress = false,
                        fs = fs
                    )
                }
                else -> {}
            }
        }

        // ── 导出卡片 ──────────────────────────────────────────────────────
        SectionCard(
            title = uiText("export.section.export", "导出配置"),
            icon = Icons.Default.Share,
            iconColor = MaterialTheme.colorScheme.primary,
            fs = fs
        ) {
            Text(
                text = uiText("export.desc", "选择要导出的内容，保存为 JSON 文件，可用于备份或迁移到其他设备。"),
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (17 * fs).sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ExportOptionToggle(
                checked = exportProviders,
                onCheckedChange = { exportProviders = it },
                title = uiText("export.option.providers", "供应商配置"),
                subtitle = uiText("export.option.providers.desc", "API 提供商、Endpoint、API Key、模型列表"),
                icon = Icons.Default.Settings,
                iconColor = MaterialTheme.colorScheme.primary,
                fs = fs
            )
            ExportOptionToggle(
                checked = exportMcp,
                onCheckedChange = { exportMcp = it },
                title = uiText("export.option.mcp", "MCP 配置"),
                subtitle = uiText("export.option.mcp.desc", "MCP 服务器列表、运行时配置、环境变量"),
                icon = Icons.Default.Build,
                iconColor = LocalCustomColors.current.warning,
                fs = fs
            )
            ExportOptionToggle(
                checked = exportMemory,
                onCheckedChange = { exportMemory = it },
                title = uiText("export.option.memory", "长效记忆"),
                subtitle = uiText("export.option.memory.desc", "跨会话记忆条目、系统 Prompt 模板"),
                icon = Icons.Default.Info,
                iconColor = LocalCustomColors.current.info,
                fs = fs
            )
            ExportOptionToggle(
                checked = exportColorSchemes,
                onCheckedChange = { exportColorSchemes = it },
                title = uiText("export.option.colors", "配色方案"),
                subtitle = uiText("export.option.colors.desc", "当前 UI 设置、配色方案预设（最多 5 个）"),
                icon = Icons.Default.Star,
                iconColor = LocalCustomColors.current.accent,
                fs = fs
            )

            Spacer(modifier = Modifier.height(4.dp))

            val nothingSelected = !exportProviders && !exportMcp && !exportMemory && !exportColorSchemes
            val isLoading = status is ExportImportStatus.Loading

            Button(
                onClick = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    exportLauncher.launch("omnichat_config_$timestamp.json")
                },
                enabled = !nothingSelected && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    uiText("export.btn", "选择保存位置并导出"),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── 导入卡片 ──────────────────────────────────────────────────────
        SectionCard(
            title = uiText("import.section.import", "导入配置"),
            icon = Icons.Default.Add,
            iconColor = LocalCustomColors.current.success,
            fs = fs
        ) {
            Text(
                text = uiText("import.desc", "从之前导出的 JSON 文件中恢复配置。选择要导入的内容类型。"),
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (17 * fs).sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ExportOptionToggle(
                checked = importProviders,
                onCheckedChange = { importProviders = it },
                title = uiText("export.option.providers", "供应商配置"),
                subtitle = uiText("import.option.providers.desc", "导入 API 提供商配置"),
                icon = Icons.Default.Settings,
                iconColor = MaterialTheme.colorScheme.primary,
                fs = fs
            )
            ExportOptionToggle(
                checked = importMcp,
                onCheckedChange = { importMcp = it },
                title = uiText("export.option.mcp", "MCP 配置"),
                subtitle = uiText("import.option.mcp.desc", "导入 MCP 服务器配置"),
                icon = Icons.Default.Build,
                iconColor = LocalCustomColors.current.warning,
                fs = fs
            )
            ExportOptionToggle(
                checked = importMemory,
                onCheckedChange = { importMemory = it },
                title = uiText("export.option.memory", "长效记忆"),
                subtitle = uiText("import.option.memory.desc", "导入记忆条目和 Prompt 模板"),
                icon = Icons.Default.Info,
                iconColor = LocalCustomColors.current.info,
                fs = fs
            )
            ExportOptionToggle(
                checked = importColorSchemes,
                onCheckedChange = { importColorSchemes = it },
                title = uiText("export.option.colors", "配色方案"),
                subtitle = uiText("import.option.colors.desc", "导入 UI 设置和配色预设"),
                icon = Icons.Default.Star,
                iconColor = LocalCustomColors.current.accent,
                fs = fs
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 覆盖模式选项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = uiText("import.replace.title", "覆盖现有数据"),
                        fontSize = (13 * fs).sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = uiText("import.replace.desc", "开启后将清空对应类型的现有数据再导入；关闭则追加"),
                        fontSize = (11 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = (15 * fs).sp
                    )
                }
                Switch(
                    checked = replaceExisting,
                    onCheckedChange = { replaceExisting = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        checkedThumbColor = MaterialTheme.colorScheme.onError
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val nothingSelected = !importProviders && !importMcp && !importMemory && !importColorSchemes
            val isLoading = status is ExportImportStatus.Loading

            FilledTonalButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                enabled = !nothingSelected && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    uiText("import.btn", "选择 JSON 文件并导入"),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── 说明卡片 ──────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiText(
                        "export.note",
                        "导出文件为标准 JSON 格式，包含所选配置的完整数据。API Key 会以明文保存在文件中，请妥善保管导出文件。导入时建议先备份现有配置。"
                    ),
                    fontSize = (11 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = (16 * fs).sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // ── 导入确认对话框 ────────────────────────────────────────────────────
    if (showImportConfirm && pendingImportUri != null) {
        ImportConfirmDialog(
            replaceExisting = replaceExisting,
            importProviders = importProviders,
            importMcp = importMcp,
            importMemory = importMemory,
            importColorSchemes = importColorSchemes,
            fs = fs,
            onConfirm = {
                showImportConfirm = false
                settingsViewModel.importFromUri(
                    context = context,
                    uri = pendingImportUri!!,
                    importProviders = importProviders,
                    importMcp = importMcp,
                    importMemory = importMemory,
                    importColorSchemes = importColorSchemes,
                    replaceExisting = replaceExisting
                )
                pendingImportUri = null
            },
            onDismiss = {
                showImportConfirm = false
                pendingImportUri = null
            }
        )
    }
}

// ── 子组件 ────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    fs: Float,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    fontSize = (16 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun ExportOptionToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    fs: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = (13 * fs).sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = (11 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (14 * fs).sp
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun StatusBanner(
    message: String,
    color: Color,
    textColor: Color,
    icon: ImageVector?,
    showProgress: Boolean,
    fs: Float
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = textColor
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                fontSize = (13 * fs).sp,
                color = textColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ImportConfirmDialog(
    replaceExisting: Boolean,
    importProviders: Boolean,
    importMcp: Boolean,
    importMemory: Boolean,
    importColorSchemes: Boolean,
    fs: Float,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = if (replaceExisting) MaterialTheme.colorScheme.error
                       else LocalCustomColors.current.warning
            )
        },
        title = {
            Text(
                uiText("import.confirm.title", "确认导入"),
                fontSize = (16 * fs).sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = uiText("import.confirm.body", "即将导入以下内容："),
                    fontSize = (13 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val items = buildList {
                    if (importProviders) add("• 供应商配置")
                    if (importMcp) add("• MCP 配置")
                    if (importMemory) add("• 长效记忆 & Prompt 模板")
                    if (importColorSchemes) add("• 配色方案")
                }
                items.forEach { item ->
                    Text(
                        text = item,
                        fontSize = (13 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (replaceExisting) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiText("import.confirm.replace_warning", "覆盖模式已开启，现有对应数据将被清空后替换，此操作不可撤销。"),
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = (15 * fs).sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (replaceExisting)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Text(uiText("import.confirm.ok", "确认导入"), fontSize = (13 * fs).sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("import.confirm.cancel", "取消"), fontSize = (13 * fs).sp)
            }
        }
    )
}
