package com.omnichat.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * 设置项标签定义
 */
private data class SettingsTab(
    val label: String,
    val icon: ImageVector,
    val contentDescription: String
)

/**
 * 所有设置标签
 */
private val SETTINGS_TABS = listOf(
    SettingsTab("模型配置", Icons.Default.Memory, "模型配置"),
    SettingsTab("MCP 工具", Icons.Default.Hub, "MCP 工具"),
    SettingsTab("长效记忆", Icons.Default.Bookmark, "长效记忆"),
    SettingsTab("数据管理", Icons.Default.Folder, "数据管理"),
    SettingsTab("权限管理", Icons.Default.Lock, "权限管理"),
    SettingsTab("Skill", Icons.Default.Extension, "Skill")
)

/**
 * 重设计后的设置页面。
 *
 * 采用 iOS 风格的分段控制标签栏，每个标签带图标和文字，
 * 内容区域保持原有子页面功能完整。
 */
@Composable
fun SettingsView(
    viewModel: ChatViewModel,
    mcpViewModel: McpViewModel
) {
    var selectedSubTab by remember { mutableStateOf(0) }
    val settingsViewModel: SettingsViewModel = viewModel()
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    Column(modifier = Modifier.fillMaxSize()) {
        // ── iOS 风格分段标签栏 ────────────────────────────────────────
        SettingsTabBar(
            tabs = SETTINGS_TABS,
            selectedIndex = selectedSubTab,
            onTabSelected = { selectedSubTab = it },
            fs = fs,
            fontFamily = resolvedFontFamily
        )

        // ── 内容区域 ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // 使用 AnimatedContent 实现平滑切换
            AnimatedContent(
                targetState = selectedSubTab,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    slideInHorizontally(
                        animationSpec = tween(250),
                        initialOffsetX = { fullWidth -> direction * fullWidth / 4 }
                    ) + fadeIn(animationSpec = tween(200)) togetherWith
                    slideOutHorizontally(
                        animationSpec = tween(200),
                        targetOffsetX = { fullWidth -> -direction * fullWidth / 4 }
                    ) + fadeOut(animationSpec = tween(150))
                },
                label = "settings_tab_content"
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> ModelsConfigView(viewModel)
                    1 -> McpConfigScreen(mcpViewModel = mcpViewModel)
                    2 -> MemoryAndPromptView(viewModel)
                    3 -> ExportImportView(settingsViewModel = settingsViewModel)
                    4 -> {
                        val permissions by settingsViewModel.filePermissions.collectAsStateWithLifecycle()
                        PermissionManagerScreen(
                            permissions = permissions,
                            onDeletePermission = { settingsViewModel.deletePermission(it) },
                            onDeleteAll = { settingsViewModel.deleteAllPermissions() },
                            onDeleteAllowed = { settingsViewModel.deleteAllowedPermissions() }
                        )
                    }
                    5 -> SkillsView(viewModel = viewModel)
                }
            }
        }
    }
}

/**
 * iOS 风格的分段控制标签栏。
 *
 * 每个标签包含图标和文字，选中时标签有高亮背景和下划线指示器。
 * 采用 Apple 风格的设计：圆角背景、柔和阴影、明确的选中态。
 */
@Composable
private fun SettingsTabBar(
    tabs: List<SettingsTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily?
) {
    val customColors = LocalCustomColors.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Column {
            // 标签行
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                items(tabs.size) { index ->
                    val isSelected = selectedIndex == index
                    SettingsTabItem(
                        tab = tabs[index],
                        isSelected = isSelected,
                        onClick = { onTabSelected(index) },
                        fs = fs,
                        fontFamily = fontFamily,
                        selectedColor = MaterialTheme.colorScheme.primary,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }

            // 底部细分隔线
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
        }
    }
}

/**
 * 单个标签项。
 *
 * 选中态：主色背景 + 主色文字 + 底部指示条
 * 非选中态：浅灰背景 + 灰色文字
 */
@Composable
private fun SettingsTabItem(
    tab: SettingsTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    fs: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    selectedColor: androidx.compose.ui.graphics.Color,
    selectedContainerColor: androidx.compose.ui.graphics.Color,
    unselectedColor: androidx.compose.ui.graphics.Color,
    unselectedContainerColor: androidx.compose.ui.graphics.Color
) {
    val bgColor = if (isSelected) selectedContainerColor else unselectedContainerColor
    val contentColor = if (isSelected) selectedColor else unselectedColor
    val cornerRadius = LocalUISettings.current.cornerRadiusDp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // 图标
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.contentDescription,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        // 标签文字
        Text(
            text = tab.label,
            fontSize = (10.5 * fs).sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            fontFamily = fontFamily,
            maxLines = 1
        )
    }
}