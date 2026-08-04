package com.omnichat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnichat.data.Project
import com.omnichat.data.Session
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 项目详情概览页。
 *
 * 提供 Project Knowledge、Project Memory、MCP 设置入口和项目会话列表。
 * 右下角放置创建新会话按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    project: Project,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onKnowledge: () -> Unit = {},
    onMemory: () -> Unit = {},
    onMcpSettings: () -> Unit = {},
    onCreateProjectSession: () -> Unit = {},
    onSessionSelected: () -> Unit = {}
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    val scope = rememberCoroutineScope()

    val projectSessions by viewModel.projectSessions.collectAsStateWithLifecycle()
    val selectedSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.name,
                            fontSize = (17 * fs).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = resolvedFontFamily
                        )
                        if (project.description.isNotBlank()) {
                            Text(
                                text = project.description,
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = resolvedFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateProjectSession,
                shape = RoundedCornerShape(cornerRadius.coerceIn(6.dp, 16.dp)),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建新会话", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── 功能入口卡片 ──────────────────────────────────────────────
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "项目功能",
                        fontSize = (14 * fs).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = resolvedFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EntryCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Description,
                            title = "Project Knowledge",
                            subtitle = "知识文件",
                            fs = fs,
                            resolvedFontFamily = resolvedFontFamily,
                            cornerRadius = cornerRadius,
                            onClick = onKnowledge
                        )
                        EntryCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Bookmark,
                            title = "Project Memory",
                            subtitle = "项目记忆",
                            fs = fs,
                            resolvedFontFamily = resolvedFontFamily,
                            cornerRadius = cornerRadius,
                            onClick = onMemory
                        )
                    }

                    EntryCard(
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.Settings,
                        title = "MCP 设置",
                        subtitle = "项目级 MCP 服务器筛选",
                        fs = fs,
                        resolvedFontFamily = resolvedFontFamily,
                        cornerRadius = cornerRadius,
                        onClick = onMcpSettings
                    )
                }
            }

            // ── 会话列表 ──────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "项目会话",
                    fontSize = (14 * fs).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (projectSessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无会话，点击右下角按钮创建",
                            fontSize = (13 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontFamily = resolvedFontFamily
                        )
                    }
                }
            } else {
                items(projectSessions, key = { "pds_${it.id}" }) { session ->
                    SessionRow(
                        session = session,
                        isActive = session.id == selectedSessionId,
                        fs = fs,
                        resolvedFontFamily = resolvedFontFamily,
                        cornerRadius = cornerRadius,
                        onClick = {
                            viewModel.selectSession(session.id)
                            onSessionSelected()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = (13 * fs).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = resolvedFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = (10.5f * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = resolvedFontFamily
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: Session,
    isActive: Boolean,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = session.title,
                fontSize = (13 * fs).sp,
                fontFamily = resolvedFontFamily,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
