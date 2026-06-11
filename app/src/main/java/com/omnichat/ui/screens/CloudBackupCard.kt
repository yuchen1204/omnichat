package com.omnichat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.AnnotatedString

@Composable
fun CloudBackupCard(
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
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
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
                    text = "云备份",
                    fontSize = (16 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!uiState.isBound) {
                // Unbound state
                Text(
                    text = "绑定账号后可自动/手动备份到云端 (Cloudflare R2 存储)",
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
                        Text("绑定账号", fontSize = (14 * fs).sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.showRecoveryDialog() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("恢复数据", fontSize = (14 * fs).sp)
                    }
                }
            } else {
                // Bound state
                Text(
                    text = "用户 ID: ${uiState.userId?.take(8)}...",
                    fontSize = (12 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "自动备份: 每5小时 · 云端保留5份",
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
                        if (uiState.isUploading) "备份中..." else "立即备份",
                        fontSize = (14 * fs).sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.showRecoveryDialog() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("恢复", fontSize = (14 * fs).sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.unbind() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("解绑", fontSize = (14 * fs).sp)
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
            onVerify = { totpSecret, totpCode ->
                viewModel.verifyForRecovery(totpSecret, totpCode)
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
            Text("绑定云备份账号", fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. 打开 Google Authenticator\n2. 扫描下方二维码或手动输入密钥",
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
                            text = "密钥: $totpSecret",
                            fontSize = (12 * fs).sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(totpSecret))
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // TOTP code input
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it },
                    label = { Text("输入验证码确认") },
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
                    Text("确认绑定")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun RecoveryDialog(
    isLoading: Boolean,
    onVerify: (String, String) -> Unit,
    onDismiss: () -> Unit,
    fs: Float
) {
    var totpSecret by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
        title = {
            Text("恢复云备份数据", fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = totpSecret,
                    onValueChange = { totpSecret = it },
                    label = { Text("TOTP 密钥") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it },
                    label = { Text("当前验证码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onVerify(totpSecret, totpCode) },
                enabled = totpSecret.isNotBlank() && totpCode.length == 6 && !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("验证并恢复")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun BackupListDialog(
    backups: List<BackupMeta>,
    isRestoring: Boolean,
    onRestore: (BackupMeta) -> Unit,
    onDismiss: () -> Unit,
    fs: Float
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalUISettings.current.cornerRadiusDp.dp),
        title = {
            Text("选择要恢复的备份", fontSize = (16 * fs).sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (backups.isEmpty()) {
                    Text(
                        text = "暂无备份",
                        fontSize = (12 * fs).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    backups.forEach { backup ->
                        BackupItem(
                            backup = backup,
                            isRestoring = isRestoring,
                            onRestore = { onRestore(backup) },
                            fs = fs
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun BackupItem(
    backup: BackupMeta,
    isRestoring: Boolean,
    onRestore: () -> Unit,
    fs: Float
) {
    val icon = when (backup.type) {
        "omnidb" -> Icons.Default.Storage
        "omniconfig" -> Icons.Default.Settings
        else -> Icons.Default.FilePresent
    }

    val typeLabel = when (backup.type) {
        "omnidb" -> "数据库"
        "omniconfig" -> "配置"
        else -> "未知"
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
                    text = "${backup.filename}",
                    fontSize = (12 * fs).sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$typeLabel · ${formatTimestamp(backup.createdAt)}",
                    fontSize = (10 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onRestore,
                enabled = !isRestoring,
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("恢复", fontSize = (11 * fs).sp)
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
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
