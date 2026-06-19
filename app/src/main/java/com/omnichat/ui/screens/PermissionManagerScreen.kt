package com.omnichat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.omnichat.R
import com.omnichat.data.McpFilePermission
import com.omnichat.ui.theme.LocalCustomColors
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.uiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PermissionManagerScreen(
    permissions: List<McpFilePermission>,
    onDeletePermission: (Long) -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteAllowed: () -> Unit
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val customColors = LocalCustomColors.current

    // 分组：已允许 vs 已禁止
    val allowedPermissions = permissions.filter { it.isAllowed }
    val deniedPermissions = permissions.filter { !it.isAllowed }

    // 折叠状态
    var allowedExpanded by remember { mutableStateOf(true) }
    var deniedExpanded by remember { mutableStateOf(true) }

    // 确认对话框状态
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteAllowedDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Text(
            text = uiText("permission_title", R.string.permission_title),
            fontSize = (18 * fs).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = resolvedFontFamily,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 批量操作按钮
        if (permissions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showDeleteAllDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(cornerRadius - 2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        uiText("permission_delete_all", R.string.permission_delete_all),
                        fontSize = (13 * fs).sp,
                        fontFamily = resolvedFontFamily
                    )
                }
                OutlinedButton(
                    onClick = { showDeleteAllowedDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(cornerRadius - 2.dp),
                    border = BorderStroke(1.dp, customColors.success)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = customColors.success
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        uiText("permission_delete_allowed", R.string.permission_delete_allowed),
                        fontSize = (13 * fs).sp,
                        fontFamily = resolvedFontFamily
                    )
                }
            }
        }

        // 空状态
        if (permissions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiText("permission_empty", R.string.permission_empty),
                        fontSize = (14 * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // 已允许分组
            if (allowedPermissions.isNotEmpty()) {
                PermissionGroup(
                    title = uiText("permission_allowed_group", R.string.permission_allowed_group),
                    count = allowedPermissions.size,
                    expanded = allowedExpanded,
                    onToggle = { allowedExpanded = !allowedExpanded },
                    permissions = allowedPermissions,
                    onDelete = { id -> pendingDeleteId = id },
                    cornerRadius = cornerRadius,
                    fs = fs,
                    resolvedFontFamily = resolvedFontFamily,
                    successColor = customColors.success,
                    errorColor = MaterialTheme.colorScheme.error
                )
            }

            // 已禁止分组
            if (deniedPermissions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                PermissionGroup(
                    title = uiText("permission_denied_group", R.string.permission_denied_group),
                    count = deniedPermissions.size,
                    expanded = deniedExpanded,
                    onToggle = { deniedExpanded = !deniedExpanded },
                    permissions = deniedPermissions,
                    onDelete = { id -> pendingDeleteId = id },
                    cornerRadius = cornerRadius,
                    fs = fs,
                    resolvedFontFamily = resolvedFontFamily,
                    successColor = customColors.success,
                    errorColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // 单条删除确认对话框
    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = {
                Text(
                    uiText("permission_delete_confirm_title", R.string.permission_delete_confirm_title),
                    fontFamily = resolvedFontFamily
                )
            },
            text = {
                Text(
                    uiText("permission_delete_confirm_message", R.string.permission_delete_confirm_message),
                    fontFamily = resolvedFontFamily
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId?.let { onDeletePermission(it) }
                        pendingDeleteId = null
                    }
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        fontFamily = resolvedFontFamily
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(
                        stringResource(R.string.action_cancel),
                        fontFamily = resolvedFontFamily
                    )
                }
            }
        )
    }

    // 清空所有确认对话框
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = {
                Text(
                    uiText("permission_delete_all_confirm_title", R.string.permission_delete_all_confirm_title),
                    fontFamily = resolvedFontFamily
                )
            },
            text = {
                Text(
                    uiText("permission_delete_all_confirm_message", R.string.permission_delete_all_confirm_message),
                    fontFamily = resolvedFontFamily
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        fontFamily = resolvedFontFamily
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(
                        stringResource(R.string.action_cancel),
                        fontFamily = resolvedFontFamily
                    )
                }
            }
        )
    }

    // 仅清空已允许确认对话框
    if (showDeleteAllowedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllowedDialog = false },
            title = {
                Text(
                    uiText("permission_delete_allowed_confirm_title", R.string.permission_delete_allowed_confirm_title),
                    fontFamily = resolvedFontFamily
                )
            },
            text = {
                Text(
                    uiText("permission_delete_allowed_confirm_message", R.string.permission_delete_allowed_confirm_message),
                    fontFamily = resolvedFontFamily
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAllowed()
                        showDeleteAllowedDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        fontFamily = resolvedFontFamily
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllowedDialog = false }) {
                    Text(
                        stringResource(R.string.action_cancel),
                        fontFamily = resolvedFontFamily
                    )
                }
            }
        )
    }
}

@Composable
private fun PermissionGroup(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    permissions: List<McpFilePermission>,
    onDelete: (Long) -> Unit,
    cornerRadius: Dp,
    fs: Float,
    resolvedFontFamily: FontFamily,
    successColor: Color,
    errorColor: Color
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "arrow_rotation"
    )

    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            // 分组头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$title ($count)",
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 分组内容
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    permissions.forEach { permission ->
                        PermissionCard(
                            permission = permission,
                            onDelete = { onDelete(permission.id) },
                            cornerRadius = cornerRadius - 4.dp,
                            fs = fs,
                            resolvedFontFamily = resolvedFontFamily,
                            successColor = successColor,
                            errorColor = errorColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permission: McpFilePermission,
    onDelete: () -> Unit,
    cornerRadius: Dp,
    fs: Float,
    resolvedFontFamily: FontFamily,
    successColor: Color,
    errorColor: Color
) {
    var pathExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (permission.isAllowed) successColor.copy(alpha = 0.15f)
                        else errorColor.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (permission.isAllowed) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (permission.isAllowed) successColor else errorColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 路径和详情
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.path,
                    fontSize = (12 * fs).sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (pathExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { pathExpanded = !pathExpanded }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 权限类型标签
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (permission.permissionType == "write")
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = if (permission.permissionType == "write")
                                uiText("permission_write", R.string.permission_write)
                            else
                                uiText("permission_read", R.string.permission_read),
                            fontSize = (10 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = if (permission.permissionType == "write")
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // 授权时间
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    Text(
                        text = dateFormat.format(Date(permission.createdAt)),
                        fontSize = (10 * fs).sp,
                        fontFamily = resolvedFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // 删除按钮
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = uiText("permission_delete", R.string.permission_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}
