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
import androidx.compose.ui.graphics.asImageBitmap

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
