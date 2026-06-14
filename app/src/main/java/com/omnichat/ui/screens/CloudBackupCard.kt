package com.omnichat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
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
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
            if (!uiState.isBound) {
                // Unbound state
                Text(
                    text = stringResource(R.string.cloud_backup_desc_unbound),
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
                        Text(stringResource(R.string.cloud_bind_account), fontSize = (14 * fs).sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.showRecoveryDialog() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.cloud_restore_data), fontSize = (14 * fs).sp)
                    }
                }
            } else {
                // Bound state
                Text(
                    text = stringResource(R.string.cloud_user_id, uiState.userId?.take(8) ?: ""),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.cloud_backup_desc_bound, "5h", 5),
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
                        if (uiState.isUploading) stringResource(R.string.cloud_backing_up) else stringResource(R.string.cloud_backup_now),
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
                        Text(stringResource(R.string.cloud_recover), fontSize = (14 * fs).sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.unbind() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.cloud_unbind), fontSize = (14 * fs).sp)
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
            Text(stringResource(R.string.cloud_bind_title), fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.cloud_bind_instructions),
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
                            text = stringResource(R.string.cloud_secret_key, totpSecret),
                            fontSize = (12 * fs).sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(totpSecret))
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.cloud_copy),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // TOTP code input
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it },
                    label = { Text(stringResource(R.string.cloud_input_code_confirm)) },
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
                    Text(stringResource(R.string.cloud_confirm_bind))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
            Text(stringResource(R.string.cloud_recover_title), fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.cloud_recover_desc),
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { if (it.length <= 6) totpCode = it },
                    label = { Text(stringResource(R.string.cloud_totp_code)) },
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
                    Text(stringResource(R.string.cloud_find_and_restore))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
            Text(stringResource(R.string.cloud_select_backup), fontSize = (16 * fs).sp)
        },
        text = {
            if (backups.isEmpty()) {
                Text(
                    text = stringResource(R.string.cloud_no_backups),
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
                Text(stringResource(R.string.cloud_close))
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
        "omnidb" -> stringResource(R.string.cloud_type_database)
        "omniconfig" -> stringResource(R.string.cloud_type_config)
        else -> stringResource(R.string.cloud_type_unknown)
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
                    contentDescription = stringResource(R.string.cloud_delete),
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
                Text(stringResource(R.string.cloud_recover), fontSize = (11 * fs).sp)
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
