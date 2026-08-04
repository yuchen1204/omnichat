package com.omnichat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnichat.data.McpServer
import com.omnichat.data.Project
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Project MCP 设置页面。
 *
 * 列出所有全局启用的 MCP 服务器，默认全部选中。
 * 用户可以在项目级别禁用特定服务器（从全局启用集合中减去）。
 * 全局禁用的服务器始终不显示——项目无法重新启用它们。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectMcpSettingsScreen(
    project: Project,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    val scope = rememberCoroutineScope()

    var globallyEnabledServers by remember { mutableStateOf<List<McpServer>>(emptyList()) }
    var projectDisabledIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(project.id) {
        isLoading = true
        try {
            globallyEnabledServers = viewModel.repository.getEnabledMcpServers()
            projectDisabledIds = viewModel.repository.getProjectDisabledMcpServerIds(project.id)
        } catch (_: Exception) {
            globallyEnabledServers = emptyList()
            projectDisabledIds = emptySet()
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "MCP 设置",
                        fontSize = (17 * fs).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = resolvedFontFamily
                    )
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(12.dp))

            Text(
                text = "项目级 MCP 服务器筛选",
                fontSize = (14 * fs).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = resolvedFontFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "仅列出全局启用的 MCP 服务器。取消勾选以在项目会话中禁用该服务器。全局禁用的服务器不会出现在此列表中。",
                fontSize = (11 * fs).sp,
                fontFamily = resolvedFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                globallyEnabledServers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有全局启用的 MCP 服务器",
                            fontSize = (13 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                else -> {
                    globallyEnabledServers.forEach { server ->
                        val isChecked = server.id !in projectDisabledIds
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        val newDisabled = if (checked) {
                                            projectDisabledIds - server.id
                                        } else {
                                            projectDisabledIds + server.id
                                        }
                                        projectDisabledIds = newDisabled
                                        scope.launch {
                                            viewModel.repository.setProjectDisabledMcpServerIds(project.id, newDisabled)
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = server.name,
                                        fontSize = (13.5f * fs).sp,
                                        fontFamily = resolvedFontFamily,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = server.command,
                                        fontSize = (10.5f * fs).sp,
                                        fontFamily = resolvedFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
