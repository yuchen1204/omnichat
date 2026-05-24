package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ModelConfig
import com.example.data.TeamTask
import com.example.ui.components.ChunkedStreamingText
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.ExportImportStatus
import com.example.ui.viewmodel.WorkspaceViewModel
import com.example.workspace.AgentMessage
import com.example.workspace.AgentStatus
import com.example.workspace.TeamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 多 Agent 工作区主界面。
 *
 * 需求：3.1、3.2、7.1–7.6、8.1–8.6、9.4、9.5
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(
    workspaceViewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val currentSession by workspaceViewModel.selectedWorkspaceSession.collectAsStateWithLifecycle()
    val agentTabs by workspaceViewModel.agentTabs.collectAsStateWithLifecycle()
    val agentStreamBuffers by workspaceViewModel.agentStreamBuffers.collectAsStateWithLifecycle()
    val agentStatuses by workspaceViewModel.agentStatuses.collectAsStateWithLifecycle()
    val modelConfigs by workspaceViewModel.modelConfigs.collectAsStateWithLifecycle()
    val teamState by workspaceViewModel.teamState.collectAsStateWithLifecycle()
    val teamTasks by workspaceViewModel.teamTasks.collectAsStateWithLifecycle()

    val uiSettings = LocalUISettings.current
    val spacingMultiplier = uiSettings.spacingMultiplier
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── 导出日志 SAF launcher ──
    val exportLogStatus by workspaceViewModel.exportLogStatus.collectAsStateWithLifecycle()
    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            workspaceViewModel.exportWorkspaceLogs(context, uri)
        }
    }

    // 导出状态提示自动消失
    LaunchedEffect(exportLogStatus) {
        if (exportLogStatus is ExportImportStatus.Success || exportLogStatus is ExportImportStatus.Error) {
            delay(3000)
            workspaceViewModel.clearExportLogStatus()
        }
    }

    // 如果未选择会话，显示空白占位
    if (currentSession == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = uiText("workspace.select.hint", "请在侧边栏选择或新建一个工作区开始协作"),
                fontSize = (15 * fs).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = resolvedFontFamily
            )
        }
        return
    }

    val session = currentSession!!

    // 当前选中的 Tab 索引
    var selectedTabIndex by remember { mutableStateOf(0) }

    // 任务面板展开状态
    var showTaskPanel by remember { mutableStateOf(false) }

    // 重置 selectedTabIndex 当 session ID 改变时，防止越界或显示错乱
    LaunchedEffect(session.id) {
        selectedTabIndex = 0
    }

    // 确保 selectedTabIndex 在列表范围内
    LaunchedEffect(agentTabs.size) {
        if (selectedTabIndex >= agentTabs.size && agentTabs.isNotEmpty()) {
            selectedTabIndex = agentTabs.size - 1
        }
    }

    // 如果任务还未提交（tabs 为空），显示「直接输入任务」界面
    if (agentTabs.isEmpty() && session.isActive) {
        WorkspaceReadyView(
            sessionTitle = session.title,
            modelConfigs = modelConfigs,
            onSubmit = { taskText, modelId ->
                workspaceViewModel.submitTask(taskText, modelId)
            },
            fs = fs,
            spacingMultiplier = spacingMultiplier,
            resolvedFontFamily = resolvedFontFamily
        )
    } else {
        // 当前选中的 Tab 状态
        val activeTab = agentTabs.getOrNull(selectedTabIndex)
        val activeAgentName = activeTab?.agentName ?: ""

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 顶部工具栏：状态标识 + 任务面板开关
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 已完成状态标识
                    if (!session.isActive) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = uiText("workspace.status.completed", "已完成"),
                                fontSize = (12 * fs).sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        // 活跃状态小圆点
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    com.example.ui.theme.LocalCustomColors.current.success,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = uiText("workspace.status.running", "运行中"),
                            fontSize = (12 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = resolvedFontFamily
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Agent 数量指示
                    if (agentTabs.size > 1) {
                        Text(
                            text = uiText("workspace.agents.count", "%d 个 Agent").format(agentTabs.size),
                            fontSize = (11 * fs).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontFamily = resolvedFontFamily
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // 导出日志按钮
                    IconButton(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            exportLogLauncher.launch("workspace_log_$timestamp.txt")
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = uiText("workspace.export_log", "导出日志"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 导出状态提示
                    when (val status = exportLogStatus) {
                        is ExportImportStatus.Success -> {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = status.message,
                                fontSize = (10 * fs).sp,
                                color = com.example.ui.theme.LocalCustomColors.current.success
                            )
                        }
                        is ExportImportStatus.Error -> {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = status.message,
                                fontSize = (10 * fs).sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }

                    // 任务面板开关按钮
                    if (teamTasks.isNotEmpty() || teamState != null) {
                        IconButton(
                            onClick = { showTaskPanel = !showTaskPanel },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (showTaskPanel) Icons.Default.ViewSidebar else Icons.Default.Dashboard,
                                contentDescription = uiText("workspace.toggle_task_panel", "任务面板"),
                                tint = if (showTaskPanel) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Tab 栏
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 0.dp,
                divider = { HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) }
            ) {
                agentTabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Agent 颜色标识点
                                val agentColor = teamState?.teammates?.get(tab.agentName)?.identity?.color
                                if (agentColor != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                parseColor(agentColor),
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                }

                                Text(
                                    text = tab.agentName,
                                    fontSize = (14 * fs).sp,
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = resolvedFontFamily
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                // 状态指示器
                                val status = agentStatuses[tab.agentName] ?: tab.status
                                when (status) {
                                    AgentStatus.STREAMING, AgentStatus.WAITING_TOOL -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    AgentStatus.IDLE -> {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    AgentStatus.COMPLETED -> {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = com.example.ui.theme.LocalCustomColors.current.success,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    AgentStatus.ERROR -> {
                                        Icon(
                                            imageVector = Icons.Default.Error,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // 消息列表与输入框（+ 可选任务面板）
            if (activeTab != null) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // 主消息区域
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                    val listState = rememberLazyListState()

                    // 观察消息长度，有新消息时滚动到底部
                    val messages = activeTab.messages
                    LaunchedEffect(messages.size, agentStreamBuffers[activeAgentName]) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp * spacingMultiplier),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        items(messages) { msg ->
                            WorkspaceBubbleMessage(
                                message = msg,
                                fs = fs,
                                spacingMultiplier = spacingMultiplier,
                                resolvedFontFamily = resolvedFontFamily
                            )
                        }

                        // 流式渲染输出
                        val streamingText = agentStreamBuffers[activeAgentName] ?: ""
                        val status = agentStatuses[activeAgentName] ?: activeTab.status
                        if (status == AgentStatus.STREAMING && streamingText.isNotEmpty()) {
                            item {
                                WorkspaceStreamingBubble(
                                    text = streamingText,
                                    fs = fs,
                                    resolvedFontFamily = resolvedFontFamily
                                )
                            }
                        }
                    }
                    } // close Box (main message area)

                    // 任务面板（右侧）
                    if (showTaskPanel && (teamTasks.isNotEmpty() || teamState != null)) {
                        TeamTaskPanel(
                            teamTasks = teamTasks,
                            teamState = teamState,
                            agentStatuses = agentStatuses,
                            fs = fs,
                            spacingMultiplier = spacingMultiplier,
                            resolvedFontFamily = resolvedFontFamily,
                            modifier = Modifier
                                .width(260.dp)
                                .fillMaxHeight()
                        )
                    }
                } // close Row

                // 底部干预输入区：当会话活跃时，允许在任何 Agent 视图中发送干预消息
                if (session.isActive && activeAgentName.isNotEmpty()) {
                    InterventionInputArea(
                        agentName = activeAgentName,
                        onSend = { text ->
                            workspaceViewModel.sendIntervention(activeAgentName, text)
                        },
                        fs = fs,
                        spacingMultiplier = spacingMultiplier
                    )
                }
            }
        }
    }
}

/**
 * 工作区就绪界面（替代旧的 TaskSetupView 卡片）。
 *
 * 不弹窗、不填写标题，直接进入「空状态 + 底部输入框」交互模式：
 * - 顶部：图标 + 欢迎文案（轻量空状态）
 * - 底部：模型选择器小 Chip + 任务输入框 + 发送按钮
 *
 * 用户输入任务描述后按发送，立即提交并启动多 Agent 协作。
 */
@Composable
fun WorkspaceReadyView(
    sessionTitle: String,
    modelConfigs: List<ModelConfig>,
    onSubmit: (String, Long) -> Unit,
    fs: Float,
    spacingMultiplier: Float,
    resolvedFontFamily: FontFamily
) {
    var taskText by remember { mutableStateOf("") }

    val defaultModel = modelConfigs.find { it.isDefaultProvider } ?: modelConfigs.firstOrNull()
    var selectedModelId by remember { mutableStateOf<Long?>(defaultModel?.id) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(modelConfigs) {
        if (selectedModelId == null && modelConfigs.isNotEmpty()) {
            selectedModelId = modelConfigs.find { it.isDefaultProvider }?.id ?: modelConfigs.first().id
        }
    }

    val selectedModelName = modelConfigs.find { it.id == selectedModelId }?.name
        ?: uiText("workspace.setup.model.none", "选择模型")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部空状态区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                // 图标
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp * spacingMultiplier))
                Text(
                    text = uiText("workspace.ready.title", "准备好了"),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = resolvedFontFamily
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiText("workspace.ready.hint", "描述你的任务，AI 将自动分析并派遣多个 Agent 协作完成"),
                    fontSize = (13 * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = resolvedFontFamily,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = (19 * fs).sp
                )
            }
        }

        // 底部输入区
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 模型选择器（小 Chip 风格）
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { dropdownExpanded = true },
                        label = {
                            Text(
                                text = selectedModelName,
                                fontSize = (12 * fs).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = if (dropdownExpanded) Icons.Default.KeyboardArrowUp
                                              else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.height(32.dp)
                    )
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        modelConfigs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(config.name, fontSize = (14 * fs).sp) },
                                onClick = {
                                    selectedModelId = config.id
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 任务输入 + 发送
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = taskText,
                        onValueChange = { taskText = it },
                        placeholder = {
                            Text(
                                text = uiText("workspace.setup.task_placeholder", "例如：请帮我写一段 Kotlin 代码，并创建子 Agent 进行 Code Review..."),
                                fontSize = (13 * fs).sp
                            )
                        },
                        minLines = 1,
                        maxLines = 5,
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = (14 * fs).sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val toSend = taskText.trim()
                            val modelId = selectedModelId
                            if (toSend.isNotEmpty() && modelId != null) {
                                onSubmit(toSend, modelId)
                                taskText = ""
                            }
                        },
                        enabled = taskText.isNotBlank() && selectedModelId != null,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (taskText.isNotBlank() && selectedModelId != null)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = uiText("workspace.setup.submit", "提交并启动"),
                            tint = if (taskText.isNotBlank() && selectedModelId != null)
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部用户干预输入区。
 */
@Composable
fun InterventionInputArea(
    agentName: String,
    onSend: (String) -> Unit,
    fs: Float,
    spacingMultiplier: Float
) {
    var textInput by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text(uiText("workspace.intervention.hint", "发送消息以干预 %s...").format(agentName), fontSize = (14 * fs).sp) },
                    maxLines = 3,
                    textStyle = LocalTextStyle.current.copy(fontSize = (14 * fs).sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                IconButton(
                    onClick = {
                        val toSend = textInput.trim()
                        if (toSend.isNotEmpty()) {
                            onSend(toSend)
                            textInput = ""
                        }
                    },
                    enabled = textInput.isNotBlank(),
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (textInput.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = uiText("action.send", "发送"),
                        tint = if (textInput.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 消息气泡。
 */
private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

@Composable
fun WorkspaceBubbleMessage(
    message: AgentMessage,
    fs: Float,
    spacingMultiplier: Float,
    resolvedFontFamily: FontFamily
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val isTool = message.role == "tool"

    when {
        isSystem -> {
            // 系统级别消息或干预通知 (支持 JSON 解析和 Markdown)
            val formattedContent = formatAgentJsonMessage(message.content)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    dev.jeziellago.compose.markdowntext.MarkdownText(
                        markdown = formattedContent,
                        style = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = (11 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Text(
                        text = formatTimestamp(message.timestamp),
                        fontSize = (9 * fs).sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        isTool -> {
            // 工具调用结果展示
            var isExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { isExpanded = !isExpanded },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiText("workspace.tool.called", "工具执行结果 (ID: %s)").format(message.toolCallId?.take(8) ?: "unknown"),
                                fontSize = (11 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatTimestamp(message.timestamp),
                                fontSize = (9 * fs).sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(0.95f)
                        ) {
                            Text(
                                text = message.content,
                                fontSize = (11 * fs).sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
        else -> {
            // 普通对话气泡 (包含 JSON 消息的过滤与格式化)
            val formattedContent = formatAgentJsonMessage(message.content)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    if (message.isIntervention) {
                        Text(
                            text = uiText("workspace.intervention.label", "【用户干预】"),
                            fontSize = (10 * fs).sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ),
                        tonalElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 290.dp)
                    ) {
                        // 使用 Compose MarkdownText 支持 Markdown 渲染，符合聊天要求
                        dev.jeziellago.compose.markdowntext.MarkdownText(
                            markdown = formattedContent,
                            style = androidx.compose.ui.text.TextStyle(
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = (14.5f * fs).sp,
                                lineHeight = (21 * fs).sp,
                                fontFamily = resolvedFontFamily
                            ),
                            syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                            syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp, 8.dp)
                        )
                    }
                    Text(
                        text = formatTimestamp(message.timestamp),
                        fontSize = (9 * fs).sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * 流式消息气泡。
 */
@Composable
fun WorkspaceStreamingBubble(
    text: String,
    fs: Float,
    resolvedFontFamily: FontFamily
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp, 8.dp)) {
                ChunkedStreamingText(
                    text = text,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (14.5f * fs).sp,
                    lineHeight = (21 * fs).sp,
                    fontFamily = resolvedFontFamily,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

/**
 * 将结构化的 Agent JSON 消息转化为美观、易读的 Markdown 文本卡片。
 * 支持：任务分配 (task/context)、结果上报 (agentName/result)、跨 Agent 消息副本 (from/message)。
 */
/**
 * 解析十六进制颜色字符串为 Compose Color。
 *
 * 支持 #RGB、#RRGGBB、#AARRGGBB、#RRGGBBAA 格式。
 * 解析失败时返回灰色。
 */
fun parseColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    return try {
        when (clean.length) {
            3 -> {
                val r = clean[0].toString().repeat(2).toInt(16)
                val g = clean[1].toString().repeat(2).toInt(16)
                val b = clean[2].toString().repeat(2).toInt(16)
                Color(0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
            }
            6 -> {
                val v = clean.toLong(16)
                Color(0xFF000000 or v)
            }
            8 -> {
                // Try #RRGGBBAA first
                val v = clean.toLong(16)
                Color((v shr 24) or ((v and 0xFFFFFF) shl 8) or 0xFF)
            }
            else -> Color.Gray
        }
    } catch (_: Exception) {
        Color.Gray
    }
}

/**
 * 任务面板：展示当前工作区的任务列表和 Agent 状态概览。
 *
 * 以侧边栏形式呈现，包含：
 * - Agent 列表（带颜色标识和当前状态）
 * - 任务列表（按状态分组，显示认领者）
 */
@Composable
fun TeamTaskPanel(
    teamTasks: List<TeamTask>,
    teamState: TeamState?,
    agentStatuses: Map<String, AgentStatus>,
    fs: Float,
    spacingMultiplier: Float,
    resolvedFontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp * spacingMultiplier)
        ) {
            // ── Agent 概览 ──
            item {
                Text(
                    text = uiText("workspace.panel.agents", "Agent 列表"),
                    fontSize = (13 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = resolvedFontFamily
                )
            }

            val teammates = teamState?.teammates ?: emptyMap()
            if (teammates.isNotEmpty()) {
                items(teammates.values.toList()) { teammate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        // 颜色点
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    parseColor(teammate.identity.color),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = teammate.identity.agentName,
                            fontSize = (12 * fs).sp,
                            fontWeight = if (teammate.isOrchestrator) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = resolvedFontFamily,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        // 状态小图标
                        val status = agentStatuses[teammate.identity.agentName] ?: teammate.status
                        val (icon, tint) = when (status) {
                            AgentStatus.STREAMING, AgentStatus.WAITING_TOOL ->
                                Icons.Default.Refresh to MaterialTheme.colorScheme.primary
                            AgentStatus.IDLE ->
                                Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            AgentStatus.COMPLETED ->
                                Icons.Default.CheckCircle to com.example.ui.theme.LocalCustomColors.current.success
                            AgentStatus.ERROR ->
                                Icons.Default.Error to MaterialTheme.colorScheme.error
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // ── 任务列表 ──
            if (teamTasks.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiText("workspace.panel.tasks", "任务列表"),
                        fontSize = (13 * fs).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = resolvedFontFamily
                    )
                }

                // 按状态分组排序：IN_PROGRESS > PENDING > COMPLETED > FAILED
                val sortedTasks = teamTasks.sortedBy { task ->
                    when (task.status) {
                        "IN_PROGRESS" -> 0
                        "PENDING" -> 1
                        "COMPLETED" -> 2
                        "FAILED" -> 3
                        else -> 4
                    }
                }

                items(sortedTasks) { task ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 状态徽章
                            val (badgeText, badgeColor) = when (task.status) {
                                "PENDING" -> "等待" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                "IN_PROGRESS" -> "执行中" to MaterialTheme.colorScheme.primary
                                "COMPLETED" -> "完成" to com.example.ui.theme.LocalCustomColors.current.success
                                "FAILED" -> "失败" to MaterialTheme.colorScheme.error
                                else -> task.status to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                color = badgeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = (10 * fs).sp,
                                    fontWeight = FontWeight.Medium,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = task.subject,
                                fontSize = (12 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = resolvedFontFamily,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 认领者
                        if (task.owner != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiText("workspace.task.owner", "认领者：%s").format(task.owner),
                                fontSize = (10 * fs).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontFamily = resolvedFontFamily
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatAgentJsonMessage(content: String): String {
    val trimmed = content.trim()
    try {
        val json = org.json.JSONObject(trimmed)
        if (json.has("task") && json.has("context")) {
            val task = json.optString("task")
            val context = json.optString("context")
            return "📋 **【任务分配】**\n\n**任务内容**：$task\n\n**上下文信息**：$context"
        }
        if (json.has("agentName") && json.has("result")) {
            val agentName = json.optString("agentName")
            val result = json.optString("result")
            return "📤 **【结果上报】**\n\n**上报 Agent**：$agentName\n\n**执行结果**：$result"
        }
        if (json.has("from") && json.has("message")) {
            val from = json.optString("from")
            val msg = json.optString("message")
            return "🔄 **【跨 Agent 消息副本】**\n\n**发送方**：$from\n\n**消息内容**：$msg"
        }
    } catch (e: Exception) {
        // 忽略解析错误，返回原始文本
    }
    return content
}
