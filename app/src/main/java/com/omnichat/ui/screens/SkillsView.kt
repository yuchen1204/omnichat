package com.omnichat.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnichat.data.SkillEntity
import com.omnichat.skill.SkillManager
import com.omnichat.ui.components.SkillCard
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Skill 管理页面。
 *
 * 显示已安装的 Skill 列表，支持启用/禁用、安装、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsView(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val corner = uiSettings.cornerRadiusDp.dp
    val fontFamily = resolveFontFamily(uiSettings.fontFamily)

    // 从数据库加载 Skill 列表
    val skills by viewModel.skillManager.getAllSkillsFlow()
        .collectAsState(initial = emptyList())

    var showInstallInfo by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    // 文件选择器：安装 .skill.md 文件
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = viewModel.skillManager.installFromUri(uri)
                result.fold(
                    onSuccess = {
                        showInstallInfo = true
                        installError = null
                    },
                    onFailure = {
                        installError = it.message ?: "安装失败"
                    }
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部提示区
        AnimatedVisibility(visible = showInstallInfo || installError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(corner),
                colors = CardDefaults.cardColors(
                    containerColor = if (installError != null)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = if (installError != null)
                            MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (installError != null) installError!! else "Skill 安装成功！",
                        fontSize = (13 * fs).sp,
                        fontFamily = fontFamily,
                        color = if (installError != null)
                            MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        showInstallInfo = false
                        installError = null
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 内容区
        if (skills.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无 Skill",
                        fontSize = (16 * fs).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右下角 + 按钮安装 .skill.md 文件",
                        fontSize = (13 * fs).sp,
                        fontFamily = fontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // 内置 Skill 提示
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(corner),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "匹配到对应关键词时，Skill 会自动激活并增强 LLM 的能力。",
                                fontSize = (12 * fs).sp,
                                fontFamily = fontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggleEnabled = { id, enabled ->
                            scope.launch {
                                viewModel.skillManager.setEnabled(id, enabled)
                            }
                        },
                        onDelete = if (!skill.isBuiltin) { id ->
                            scope.launch {
                                viewModel.skillManager.delete(id)
                            }
                        } else null
                    )
                }
            }
        }

        // 浮动按钮：安装 Skill
        FloatingActionButton(
            onClick = { filePickerLauncher.launch("*/*") },
            modifier = Modifier
                .align(Alignment.End)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "安装 Skill"
            )
        }
    }
}