package com.omnichat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnichat.data.SkillEntity
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily

/**
 * 获取 Skill 的图标。
 */
@Composable
fun getSkillIcon(skillId: String) = when {
    skillId.contains("weekly") || skillId.contains("report") -> Icons.Default.Description
    skillId.contains("research") || skillId.contains("deep") -> Icons.Default.Search
    skillId.contains("ui") || skillId.contains("theme") -> Icons.Default.Palette
    else -> Icons.Default.Extension
}

/**
 * Skill 卡片组件，用于在设置页中展示单个 Skill。
 */
@Composable
fun SkillCard(
    skill: SkillEntity,
    onToggleEnabled: (Long, Boolean) -> Unit,
    onDelete: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val corner = uiSettings.cornerRadiusDp.dp
    val fontFamily = resolveFontFamily(uiSettings.fontFamily)
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 头部：图标 + 名称 + 开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 图标
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (skill.isEnabled) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getSkillIcon(skill.skillId),
                        contentDescription = null,
                        tint = if (skill.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 名称和描述
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        fontSize = (14 * fs).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = skill.description,
                        fontSize = (12 * fs).sp,
                        fontFamily = fontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 展开/折叠按钮
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 启用/禁用开关
                Switch(
                    checked = skill.isEnabled,
                    onCheckedChange = { onToggleEnabled(skill.id, it) }
                )
            }

            // 展开详情
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 触发词
                    val patterns = skill.getTriggerPatternList()
                    if (patterns.isNotEmpty()) {
                        DetailRow("触发词", patterns.joinToString(", "))
                    }

                    // 工具组
                    val groups = skill.getRequiredToolGroupList()
                    if (groups.isNotEmpty()) {
                        DetailRow("所需工具组", groups.joinToString(", "))
                    }

                    // 版本和作者
                    DetailRow("版本", skill.version)
                    DetailRow("作者", skill.author)

                    // 内置标识
                    if (skill.isBuiltin) {
                        DetailRow("类型", "内置（不可删除）")
                    }

                    // 提示词预览
                    if (skill.systemPrompt.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "系统提示词",
                            fontSize = (12 * fs).sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = fontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = skill.systemPrompt.take(300) + if (skill.systemPrompt.length > 300) "..." else "",
                                fontSize = (11 * fs).sp,
                                fontFamily = fontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    // 删除按钮（仅非内置 Skill）
                    if (!skill.isBuiltin && onDelete != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { onDelete(skill.id) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除此 Skill", fontSize = (12 * fs).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val fontFamily = resolveFontFamily(uiSettings.fontFamily)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "$label：",
            fontSize = (12 * fs).sp,
            fontFamily = fontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            fontSize = (12 * fs).sp,
            fontFamily = fontFamily,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}