package com.omnichat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnichat.cloud.CloudBackupViewModel
import com.omnichat.cloud.BackupMeta
import com.omnichat.ui.theme.LocalUISettings
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.omnichat.R
import com.omnichat.ui.theme.uiText

@Composable
fun CloudBackupCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onFrequencyChange: (String) -> Unit = {},
    onSectionToggle: (String) -> Unit = {},
    viewModel: CloudBackupViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header (clickable to toggle)
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
                        .padding(0.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = uiText("cloud.title", R.string.cloud_backup, "云备份"),
                    fontSize = (16 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) uiText("cloud.collapse", R.string.collapse, "折叠") else uiText("cloud.expand", R.string.expand, "展开"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
            if (!uiState.isBound) {
                // Unbound state
                Text(
                    text = uiText("cloud.desc_unbound", R.string.cloud_backup_desc_unbound, "绑定账号后可自动/手动备份到云端 (Cloudflare R2 存储)"),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.showBindDialog() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(uiText("cloud.bind_account", R.string.cloud_bind_account, "绑定账号"), fontSize = (14 * fs).sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.showRecoveryDialog() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(uiText("cloud.restore_data", R.string.cloud_restore_data, "恢复数据"), fontSize = (14 * fs).sp)
                    }
                }
            } else {
                // Bound state
                Text(
                    text = uiText("cloud.user_id", R.string.cloud_user_id, "用户 ID: %1\$s…").format(uiState.userId?.take(8) ?: ""),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiText("cloud.desc_bound", R.string.cloud_backup_desc_bound, "自动备份: 每%1\$s · 云端保留%2\$d份").format("5h", 5),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Button(
                    onClick = { viewModel.uploadBackup() },
                    enabled = !uiState.isUploading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (uiState.isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (uiState.isUploading) uiText("cloud.backing_up", R.string.cloud_backing_up, "备份中…") else uiText("cloud.backup_now", R.string.cloud_backup_now, "立即备份"),
                        fontSize = (14 * fs).sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.loadBackupsAndShow() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(uiText("cloud.recover", R.string.cloud_recover, "恢复"), fontSize = (14 * fs).sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.unbind() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(uiText("cloud.unbind", R.string.cloud_unbind, "解绑"), fontSize = (14 * fs).sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Backup frequency selector
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = uiText("cloud.frequency", R.string.cloud_backup_frequency, "自动备份频率"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val frequencies = listOf(
                            "MANUAL" to Triple("cloud.freq_manual", R.string.cloud_freq_manual, "手动"),
                            "H3" to Triple("cloud.freq_3h", R.string.cloud_freq_3h, "3小时"),
                            "H6" to Triple("cloud.freq_6h", R.string.cloud_freq_6h, "6小时"),
                            "H12" to Triple("cloud.freq_12h", R.string.cloud_freq_12h, "12小时"),
                            "H24" to Triple("cloud.freq_24h", R.string.cloud_freq_24h, "24小时")
                        )
                        frequencies.forEach { (freq, triple) ->
                            val isSelected = uiState.backupFrequency == freq
                            val bgColor = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(bgColor)
                                    .clickable { onFrequencyChange(freq) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = uiText(triple.first, triple.second, triple.third),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Backup content checkboxes
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = uiText("cloud.content", R.string.cloud_backup_content, "备份内容"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val sections = listOf(
                        "providers" to R.string.cloud_section_provider_mcp,
                        "memories" to R.string.cloud_section_memory_prompts,
                        "uiSettings" to R.string.cloud_section_theme_ui,
                        "sessions" to R.string.cloud_section_chat_history
                    )
                    sections.forEach { (section, resId) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = uiState.backupSections.contains(section),
                                onCheckedChange = { onSectionToggle(section) }
                            )
                            Text(text = uiText("cloud.section_" + section, resId, section),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Error/Success messages
            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = (12 * fs).sp
                    )
                }
            }

            uiState.success?.let { success ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = success,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = (12 * fs).sp
                    )
                }
            }
                } // inner Column
            } // AnimatedVisibility
        }
    }

    // Dialogs
    if (uiState.showBindDialog) {
        BindTotpDialog(
            totpSecret = uiState.totpSecret,
            qrCodeUrl = uiState.qrCodeUrl,
            isLoading = uiState.isLoading,
            onRequestTotp = { viewModel.bindTotp() },
            onVerify = { code ->
                uiState.totpSecret?.let { secret ->
                    viewModel.verifyAndBind(secret, code)
                }
            },
            onDismiss = { viewModel.hideBindDialog() },
            fs = fs
        )
    }

    if (uiState.showRecoveryDialog) {
        RecoveryDialog(
            isLoading = uiState.isLoading,
            onRecover = { totpCode ->
                viewModel.recoverByTotpCode(totpCode)
            },
            onDismiss = { viewModel.hideRecoveryDialog() },
            fs = fs
        )
    }

    if (uiState.showBackupListDialog) {
        BackupListDialog(
            backups = uiState.backups,
            isRestoring = uiState.isRestoring,
            onRestore = { viewModel.restoreBackup(it) },
            onDelete = { viewModel.deleteBackup(it) },
            onDismiss = { viewModel.hideBackupListDialog() },
            fs = fs
        )
    }
}

@Composable
private fun BindTotpDialog(
    totpSecret: String?,
    qrCodeUrl: String?,
    isLoading: Boolean,
    onRequestTotp: () -> Unit,
    onVerify: (String) -> Unit,
    onDismiss: () -> Unit,
    fs: Float
) {
    var totpCode by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    // Request TOTP when dialog first appears
    LaunchedEffect(Unit) {
        if (totpSecret == null && !isLoading) {
            onRequestTotp()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
        title = {
            Text(uiText("cloud.bind_title", R.string.cloud_bind_title, "绑定云备份账号"), fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = uiText("cloud.bind_instructions", R.string.cloud_bind_instructions, "1. 打开 Google Authenticator\n2. 扫描下方二维码或手动输入密钥"),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // QR Code
                if (qrCodeUrl != null) {
                    val qrCodeBitmap = remember(qrCodeUrl) {
                        generateQrCodeBitmap(qrCodeUrl)
                    }
                    Image(
                        bitmap = qrCodeBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(16.dp)
                    )
                }

                // Manual secret
                if (totpSecret != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiText("cloud.secret_key", R.string.cloud_secret_key, "密钥: %1\$s").format(totpSecret),
                            fontSize = (12 * fs).sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(totpSecret))
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = uiText("cloud.copy", R.string.cloud_copy, "复制"),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // TOTP code input
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it },
                    label = { Text(uiText("cloud.input_code_confirm", R.string.cloud_input_code_confirm, "输入验证码确认")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onVerify(totpCode) },
                enabled = totpCode.length == 6 && !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(uiText("cloud.confirm_bind", R.string.cloud_confirm_bind, "确认绑定"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("cloud.cancel", R.string.cancel, "取消"))
            }
        }
    )
}

@Composable
private fun RecoveryDialog(
    isLoading: Boolean,
    onRecover: (String) -> Unit,
    onDismiss: () -> Unit,
    fs: Float
) {
    var totpCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
        title = {
            Text(uiText("cloud.recover_title", R.string.cloud_recover_title, "恢复云备份数据"), fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = uiText("cloud.recover_desc", R.string.cloud_recover_desc, "输入绑定账号时的 TOTP 验证码，系统将自动匹配您的备份"),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { if (it.length <= 6) totpCode = it },
                    label = { Text(uiText("cloud.totp_code", R.string.cloud_totp_code, "TOTP 验证码")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRecover(totpCode) },
                enabled = totpCode.length == 6 && !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(uiText("cloud.find_and_restore", R.string.cloud_find_and_restore, "查找并恢复"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("cloud.cancel", R.string.cancel, "取消"))
            }
        }
    )
}

@Composable
private fun BackupListDialog(
    backups: List<BackupMeta>,
    isRestoring: Boolean,
    onRestore: (BackupMeta) -> Unit,
    onDelete: (BackupMeta) -> Unit,
    onDismiss: () -> Unit,
    fs: Float
) {
    val context = LocalContext.current
    // Group backups by time period (within 1 hour of each other)
    val grouped = remember(backups) {
        backups.sortedByDescending { it.createdAt }.groupBy { backup ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = backup.createdAt }
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
        title = {
            Text(uiText("cloud.select_backup", R.string.cloud_select_backup, "选择要恢复的备份"), fontSize = (16 * fs).sp)
        },
        text = {
            if (backups.isEmpty()) {
                Text(
                    text = uiText("cloud.no_backups", R.string.cloud_no_backups, "暂无备份"),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (groupKey, group) ->
                        val timeLabel = formatRelativeTime(context, group.first().createdAt)
                        Text(
                            text = timeLabel,
                            fontSize = (11 * fs).sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = if (grouped.keys.first() == groupKey) 0.dp else 4.dp)
                        )
                        group.forEach { backup ->
                            BackupItem(
                                backup = backup,
                                isRestoring = isRestoring,
                                onRestore = { onRestore(backup) },
                                onDelete = { onDelete(backup) },
                                fs = fs
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText("cloud.close", R.string.cloud_close, "关闭"))
            }
        }
    )
}

@Composable
private fun BackupItem(
    backup: BackupMeta,
    isRestoring: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    fs: Float
) {
    val icon = when (backup.type) {
        "omnidb" -> Icons.Default.Storage
        "omniconfig" -> Icons.Default.Settings
        else -> Icons.Default.FilePresent
    }

    val typeLabel = when (backup.type) {
        "omnidb" -> uiText("cloud.type_database", R.string.cloud_type_database, "数据库")
        "omniconfig" -> uiText("cloud.type_config", R.string.cloud_type_config, "配置")
        else -> uiText("cloud.type_unknown", R.string.cloud_type_unknown, "未知")
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.filename,
                    fontSize = (12 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = typeLabel,
                    fontSize = (10 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = !isRestoring,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = uiText("cloud.delete", R.string.cloud_delete, "删除"),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = onRestore,
                enabled = !isRestoring,
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(uiText("cloud.recover", R.string.cloud_recover, "恢复"), fontSize = (11 * fs).sp)
            }
        }
    }
}

private fun formatRelativeTime(context: android.content.Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = diff / (1000 * 60)
    val hours = diff / (1000 * 60 * 60)
    val days = diff / (1000 * 60 * 60 * 24)

    return when {
        minutes < 1 -> context.getString(R.string.cloud_time_just_now)
        minutes < 60 -> context.getString(R.string.cloud_time_minutes_ago, minutes)
        hours < 24 -> context.getString(R.string.cloud_time_hours_ago, hours)
        days < 7 -> context.getString(R.string.cloud_time_days_ago, days)
        else -> {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

private fun generateQrCodeBitmap(content: String): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
