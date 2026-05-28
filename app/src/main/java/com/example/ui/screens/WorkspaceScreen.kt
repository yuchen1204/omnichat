package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.uiText
import com.example.ui.viewmodel.WorkspaceViewModel
import com.example.workspace.AgentStatus
import com.example.workspace.ORCHESTRATOR_NAME
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// 工作区主界面（薄壳）
//
// 只负责状态收集、阶段推断和组件装配，具体组件在各自文件中。
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 多 Agent 工作区主界面。
 */
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
    val fetchedModels by workspaceViewModel.fetchedModels.collectAsStateWithLifecycle()
    val teamState by workspaceViewModel.teamState.collectAsStateWithLifecycle()
    val exportLogStatus by workspaceViewModel.exportLogStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // 通知权限请求（Android 13+）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 权限结果无需特殊处理，有权限则通知显示，无则静默 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            workspaceViewModel.exportWorkspaceLogs(context, uri)
        }
    }

    // 未选择会话 → 空白占位
    if (currentSession == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = uiText("workspace.select.hint", "请在侧边栏选择或新建一个工作区开始协作"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        return
    }

    val session = currentSession!!

    // 当前选中的 Tab 索引
    var selectedTabIndex by remember { mutableStateOf(0) }
    var showTaskPanel by remember { mutableStateOf(false) }

    LaunchedEffect(session.id) { selectedTabIndex = 0 }
    LaunchedEffect(agentTabs.size) {
        if (selectedTabIndex >= agentTabs.size && agentTabs.isNotEmpty()) {
            selectedTabIndex = agentTabs.size - 1
        }
    }

    // 任务还未提交 → 显示就绪界面
    if (agentTabs.isEmpty() && session.isActive) {
        WorkspaceReadyView(
            sessionTitle = session.title,
            modelConfigs = modelConfigs,
            fetchedModels = fetchedModels,
            onSubmit = { taskText, configId, modelId, imagePath, sandboxPath ->
                workspaceViewModel.submitTask(taskText, configId, modelId, imagePath, sandboxPath)
            }
        )
        return
    }

    val activeTab = agentTabs.getOrNull(selectedTabIndex)
    val activeAgentName = activeTab?.agentName ?: ""

    // 工作流阶段推断
    val hasSubAgents = agentTabs.any { !it.isOrchestrator }
    val phase = resolveWorkflowPhase(
        isActive = session.isActive,
        agentStatuses = agentStatuses,
        hasSubAgents = hasSubAgents,
    )

    // Agent 颜色查找：把 teamState 转成 (name) -> color 函数，
    // 通过 CompositionLocal 暴露给所有子组件
    val agentColorLookup: (String) -> String? = remember(teamState) {
        { _ -> null }
    }

    val activeAgentStatus = agentStatuses[activeAgentName] ?: activeTab?.status ?: AgentStatus.IDLE
    val activeAgentStreaming = activeAgentStatus == AgentStatus.STREAMING ||
        activeAgentStatus == AgentStatus.WAITING_TOOL

    CompositionLocalProvider(LocalAgentColorLookup provides agentColorLookup) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 顶部工具栏（含工作流阶段 Pill）
            WorkspaceToolbar(
                phase = phase,
                agentCount = agentTabs.size,
                showTaskPanel = showTaskPanel,
                hasTaskContent = teamState != null,
                exportLogStatus = exportLogStatus,
                onToggleTaskPanel = { showTaskPanel = !showTaskPanel },
                onExportLog = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    exportLogLauncher.launch("workspace_log_$timestamp.txt")
                },
                onClearExportStatus = { workspaceViewModel.clearExportLogStatus() }
            )

            // Tab 栏
            if (agentTabs.isNotEmpty()) {
                AgentTabBar(
                    agentTabs = agentTabs,
                    selectedTabIndex = selectedTabIndex,
                    teamState = teamState,
                    agentStatuses = agentStatuses,
                    onTabSelected = { selectedTabIndex = it }
                )
            }

            // 消息区域 + 任务面板
            if (activeTab != null) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        val streamingText = agentStreamBuffers[activeAgentName] ?: ""
                        AgentMessageArea(
                            activeTab = activeTab,
                            streamingText = streamingText,
                            agentStatus = activeAgentStatus
                        )
                    }

                    if (showTaskPanel && teamState != null) {
                        TeamTaskPanel(
                            teamTasks = emptyList(),
                            teamState = teamState,
                            agentStatuses = agentStatuses,
                            modifier = Modifier
                                .width(260.dp)
                                .fillMaxHeight(),
                            onAgentClick = { name ->
                                val newIndex = agentTabs.indexOfFirst { it.agentName == name }
                                if (newIndex >= 0) selectedTabIndex = newIndex
                            }
                        )
                    }
                }

                // 底部干预输入
                if (session.isActive && activeAgentName.isNotEmpty()) {
                    InterventionInputArea(
                        agentName = activeAgentName,
                        onSend = { text, imagePath -> workspaceViewModel.sendIntervention(activeAgentName, text, imagePath) },
                        isStreaming = activeAgentStreaming,
                    )
                }
            }
        }
    }
}
