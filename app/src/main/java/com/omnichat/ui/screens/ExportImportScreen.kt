package com.omnichat.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.omnichat.R
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.theme.LocalCustomColors
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.viewmodel.ExportImportStatus
import com.omnichat.ui.viewmodel.SettingsViewModel
import androidx.compose.ui.res.stringResource
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

    // ── 数据库备份状态 ──────────────────────────────────────────────────
    var pendingDbImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showDbImportConfirm by remember { mutableStateOf(false) }

    // ── 卡片展开/收起状态 ──────────────────────────────────────────────
    var exportExpanded by remember { mutableStateOf(false) }
    var importExpanded by remember { mutableStateOf(false) }
    var dbBackupExpanded by remember { mutableStateOf(false) }
    var cloudBackupExpanded by remember { mutableStateOf(false) }

    // ── SAF 文件选择器 ────────────────────────────────────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
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
            // Validate .omniconfig extension
            val fileName = uri.lastPathSegment ?: ""
            if (!fileName.endsWith(".omniconfig", ignoreCase = true)) {
                settingsViewModel.setError(
                    context.getString(R.string.import_invalid_file)
                )
            } else {
                pendingImportUri = uri
                showImportConfirm = true
            }
        }
    }

    // ── 数据库备份 SAF 文件选择器 ──────────────────────────────────────
    val dbExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            settingsViewModel.exportDatabaseBackup(
                context = context,
                uri = uri
            )
        }
    }

    val dbImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: ""
            if (!fileName.endsWith(".omnidb", ignoreCase = true)) {
                settingsViewModel.setError(
                    context.getString(R.string.db_backup_import_invalid_file)
                )
            } else {
                pendingDbImportUri = uri
                showDbImportConfirm = true
            }
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
                        message = uiText("processing", R.string.processing),
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
            title = uiText("export.section.export", R.string.export_section_export),
            icon = Icons.Default.Share,
            iconColor = MaterialTheme.colorScheme.primary,
            fs = fs,
            expanded = exportExpanded,
            onToggle = { exportExpanded = !exportExpanded }
        ) {
            Text(
                text = uiText("export.desc", R.string.export_desc),
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (17 * fs).sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ExportOptionToggle(
                checked = exportProviders,
                onCheckedChange = { exportProviders = it },
                title = uiText("export.option.providers", R.string.export_option_providers),
                subtitle = uiText("export.option.providers.desc", R.string.export_option_providers_desc),
                icon = Icons.Default.Settings,
                iconColor = MaterialTheme.colorScheme.primary,
                fs = fs
            )
            ExportOptionToggle(
                checked = exportMcp,
                onCheckedChange = { exportMcp = it },
                title = uiText("export.option.mcp", R.string.export_option_mcp),
                subtitle = uiText("export.option.mcp.desc", R.string.export_option_mcp_desc),
                icon = Icons.Default.Build,
                iconColor = LocalCustomColors.current.warning,
                fs = fs
            )
            ExportOptionToggle(
                checked = exportMemory,
                onCheckedChange = { exportMemory = it },
                title = uiText("export.option.memory", R.string.export_option_memory),
                subtitle = uiText("export.option.memory.desc", R.string.export_option_memory_desc),
                icon = Icons.Default.Info,
                iconColor = LocalCustomColors.current.info,
                fs = fs
            )
            ExportOptionToggle(
                checked = exportColorSchemes,
                onCheckedChange = { exportColorSchemes = it },
                title = uiText("export.option.colors", R.string.export_option_colors),
                subtitle = uiText("export.option.colors.desc", R.string.export_option_colors_desc),
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
                    exportLauncher.launch("omnichat_config_$timestamp.omniconfig")
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
                    uiText("export.btn", R.string.export_btn),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── 导入卡片 ──────────────────────────────────────────────────────
        SectionCard(
            title = uiText("import.section.import", R.string.import_section_import),
            icon = Icons.Default.Add,
            iconColor = LocalCustomColors.current.success,
            fs = fs,
            expanded = importExpanded,
            onToggle = { importExpanded = !importExpanded }
        ) {
            Text(
                text = uiText("import.desc", R.string.import_desc),
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (17 * fs).sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ExportOptionToggle(
                checked = importProviders,
                onCheckedChange = { importProviders = it },
                title = uiText("export.option.providers", R.string.export_option_providers),
                subtitle = uiText("import.option.providers.desc", R.string.import_option_providers_desc),
                icon = Icons.Default.Settings,
                iconColor = MaterialTheme.colorScheme.primary,
                fs = fs
            )
            ExportOptionToggle(
                checked = importMcp,
                onCheckedChange = { importMcp = it },
                title = uiText("export.option.mcp", R.string.export_option_mcp),
                subtitle = uiText("import.option.mcp.desc", R.string.import_option_mcp_desc),
                icon = Icons.Default.Build,
                iconColor = LocalCustomColors.current.warning,
                fs = fs
            )
            ExportOptionToggle(
                checked = importMemory,
                onCheckedChange = { importMemory = it },
                title = uiText("export.option.memory", R.string.export_option_memory),
                subtitle = uiText("import.option.memory.desc", R.string.import_option_memory_desc),
                icon = Icons.Default.Info,
                iconColor = LocalCustomColors.current.info,
                fs = fs
            )
            ExportOptionToggle(
                checked = importColorSchemes,
                onCheckedChange = { importColorSchemes = it },
                title = uiText("export.option.colors", R.string.export_option_colors),
                subtitle = uiText("import.option.colors.desc", R.string.import_option_colors_desc),
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
                        text = uiText("import.replace.title", R.string.import_replace_title),
                        fontSize = (13 * fs).sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = uiText("import.replace.desc", R.string.import_replace_desc),
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
                onClick = { importLauncher.launch(arrayOf("*/*")) },
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
                    uiText("import.btn", R.string.import_btn),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── 数据库备份卡片 ────────────────────────────────────────────────
        SectionCard(
            title = uiText("db_backup.section", R.string.db_backup_section),
            icon = Icons.Default.Storage,
            iconColor = MaterialTheme.colorScheme.tertiary,
            fs = fs,
            expanded = dbBackupExpanded,
            onToggle = { dbBackupExpanded = !dbBackupExpanded }
        ) {
            Text(
                text = uiText("db_backup.desc", R.string.db_backup_desc),
                fontSize = (12 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (17 * fs).sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = uiText("db_backup.includes", R.string.db_backup_includes),
                fontSize = (11 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val isLoading = status is ExportImportStatus.Loading

            Button(
                onClick = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    dbExportLauncher.launch("omnichat_db_$timestamp.omnidb")
                },
                enabled = !isLoading,
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
                    uiText("db_backup.export_btn", R.string.db_backup_export_btn),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            FilledTonalButton(
                onClick = { dbImportLauncher.launch(arrayOf("*/*")) },
                enabled = !isLoading,
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
                    uiText("db_backup.import_btn", R.string.db_backup_import_btn),
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── 云备份卡片 ────────────────────────────────────────────────
        CloudBackupCard(
            expanded = cloudBackupExpanded,
            onToggle = { cloudBackupExpanded = !cloudBackupExpanded }
        )

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
                    text = uiText("export.note", R.string.export_note),
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

    // ── 数据库导入确认对话框 ─────────────────────────────────────────────
    if (showDbImportConfirm && pendingDbImportUri != null) {
        DbImportConfirmDialog(
            fs = fs,
            onConfirm = {
                showDbImportConfirm = false
                settingsViewModel.importDatabaseBackup(
                    context = context,
                    uri = pendingDbImportUri!!
                )
                pendingDbImportUri = null
            },
            onDismiss = {
                showDbImportConfirm = false
                pendingDbImportUri = null
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
    expanded: Boolean,
    onToggle: () -> Unit,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(bottom = if (expanded) 12.dp else 0.dp)
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
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column { content() }
            }
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
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
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
                uiText("import.confirm.title", R.string.import_confirm_title),
                fontSize = (16 * fs).sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = uiText("import.confirm.body", R.string.import_confirm_body),
                    fontSize = (13 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val items = buildList {
                    if (importProviders) add(uiText("export.option.providers", R.string.export_option_providers))
                    if (importMcp) add(uiText("export.option.mcp", R.string.export_option_mcp))
                    if (importMemory) add(uiText("export.option.memory", R.string.export_option_memory))
                    if (importColorSchemes) add(uiText("export.option.colors", R.string.export_option_colors))
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
                                text = uiText("import.confirm.replace.warning", R.string.import_confirm_replace_warning),
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
                shape = RoundedCornerShape((LocalUISettings.current.cornerRadiusDp - 2).coerceAtLeast(0).dp),
                colors = if (replaceExisting)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Text(uiText("import.confirm.ok", R.string.import_confirm_ok), fontSize = (13 * fs).sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape((LocalUISettings.current.cornerRadiusDp - 2).coerceAtLeast(0).dp)
            ) {
                Text(uiText("import.confirm.cancel", R.string.import_confirm_cancel), fontSize = (13 * fs).sp)
            }
        }
    )
}

@Composable
private fun DbImportConfirmDialog(
    fs: Float,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                uiText("db_backup.import.confirm.title", R.string.db_backup_import_confirm_title),
                fontSize = (16 * fs).sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = uiText("db_backup.import.confirm.body", R.string.db_backup_import_confirm_body),
                fontSize = (13 * fs).sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = (18 * fs).sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape((LocalUISettings.current.cornerRadiusDp - 2).coerceAtLeast(0).dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    uiText("db_backup.import.confirm.ok", R.string.db_backup_import_confirm_ok),
                    fontSize = (13 * fs).sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape((LocalUISettings.current.cornerRadiusDp - 2).coerceAtLeast(0).dp)
            ) {
                Text(
                    uiText("db_backup.import.confirm.cancel", R.string.db_backup_import_confirm_cancel),
                    fontSize = (13 * fs).sp
                )
            }
        }
    )
}
