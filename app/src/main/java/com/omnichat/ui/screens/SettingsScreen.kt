package com.omnichat.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnichat.mcp.McpViewModel
import com.omnichat.ui.theme.LocalCustomColors
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.theme.uiText
import com.omnichat.ui.viewmodel.ChatViewModel
import com.omnichat.ui.viewmodel.SettingsViewModel

/**
 * 设置卡片数据
 */
private data class SettingsCard(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private val SETTINGS_CARDS = listOf(
    SettingsCard("models", "模型配置", Icons.Default.Memory, "管理 AI 提供者、模型和 API 密钥"),
    SettingsCard("mcp", "MCP 工具", Icons.Default.Hub, "配置 MCP 服务器和工具"),
    SettingsCard("memory", "长效记忆", Icons.Default.Bookmark, "管理记忆、提示词模板"),
    SettingsCard("skills", "Skill", Icons.Default.Extension, "安装和管理 Skill 扩展"),
    SettingsCard("permissions", "权限管理", Icons.Default.Lock, "查看和管理文件访问权限"),
    SettingsCard("data", "数据管理", Icons.Default.Folder, "导入/导出数据、云备份")
)

/**
 * 设置页面。
 *
 * 主页显示卡片列表，点击卡片进入对应的子页面。
 * 子页面顶部有返回按钮，可回到设置主页。
 */
@Composable
fun SettingsView(
    viewModel: ChatViewModel,
    mcpViewModel: McpViewModel
) {
    var selectedSubPage by remember { mutableStateOf<String?>(null) }
    val settingsViewModel: SettingsViewModel = viewModel()
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp

    if (selectedSubPage == null) {
        // ── 设置主页：卡片列表 ────────────────────────────────────
        SettingsHomePage(
            settingsCards = SETTINGS_CARDS,
            fs = fs,
            resolvedFontFamily = resolvedFontFamily,
            cornerRadius = cornerRadius,
            onCardClick = { selectedSubPage = it }
        )
    } else {
        // ── 子页面 ────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部导航栏
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedSubPage = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = SETTINGS_CARDS.firstOrNull { it.id == selectedSubPage }?.label ?: "",
                        fontSize = (18 * fs).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = resolvedFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            // 子页面内容
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedSubPage) {
                    "models" -> ModelsConfigView(viewModel)
                    "mcp" -> McpConfigScreen(mcpViewModel = mcpViewModel, settingsViewModel = settingsViewModel)
                    "memory" -> MemoryAndPromptView(viewModel)
                    "skills" -> SkillsView(viewModel = viewModel)
                    "permissions" -> {
                        val permissions by settingsViewModel.filePermissions.collectAsStateWithLifecycle()
                        PermissionManagerScreen(
                            permissions = permissions,
                            onDeletePermission = { settingsViewModel.deletePermission(it) },
                            onDeleteAll = { settingsViewModel.deleteAllPermissions() },
                            onDeleteAllowed = { settingsViewModel.deleteAllowedPermissions() }
                        )
                    }
                    "data" -> ExportImportView(settingsViewModel = settingsViewModel)
                }
            }
        }
    }
}

/**
 * 设置主页：显示所有设置的卡片入口。
 */
@Composable
private fun SettingsHomePage(
    settingsCards: List<SettingsCard>,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily?,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onCardClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "设置",
                fontSize = (22 * fs).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = resolvedFontFamily,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp
        )

        // 卡片列表
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            settingsCards.forEach { card ->
                SettingsCardItem(
                    card = card,
                    fs = fs,
                    resolvedFontFamily = resolvedFontFamily,
                    cornerRadius = cornerRadius,
                    onClick = { onCardClick(card.id) }
                )
            }
        }
    }
}

/**
 * 单个设置卡片。
 */
@Composable
private fun SettingsCardItem(
    card: SettingsCard,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily?,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val customColors = LocalCustomColors.current

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(cornerRadius.coerceIn(8.dp, 20.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标容器
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.label,
                    fontSize = (15 * fs).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.description,
                    fontSize = (12 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}