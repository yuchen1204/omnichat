package com.omnichat.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnichat.data.Project
import com.omnichat.data.ProjectKnowledge
import com.omnichat.ui.theme.LocalUISettings
import com.omnichat.ui.theme.resolveFontFamily
import com.omnichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Project Knowledge 资源列表页面。
 *
 * 展示项目知识文件：名称/类型/来源/创建时间，不显示大小。
 * 支持用户上传（Android 文件选择器，限定支持的 MIME 类型）和删除确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectKnowledgeScreen(
    project: Project,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val knowledgeFiles by viewModel.repository.getKnowledgeByProjectFlow(project.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showDeleteConfirm by remember { mutableStateOf<ProjectKnowledge?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                try {
                    uris.forEach { uri ->
                        val cursor = context.contentResolver.query(uri, null, null, null, null)
                        val originalName = cursor?.use {
                            if (it.moveToFirst()) {
                                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0) it.getString(nameIndex) else null
                            } else null
                        } ?: "file_${System.currentTimeMillis()}"

                        viewModel.repository.createProjectAssetFromUri(
                            context, project.id, uri, originalName, "USER_UPLOAD"
                        )
                    }
                    viewModel.repository.touchProject(project.id)
                } catch (e: Exception) {
                    errorMessage = "上传失败: ${e.localizedMessage}"
                }
            }
        }
    }

    val supportedMimeTypes = arrayOf(
        "image/*",
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/markdown"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Project Knowledge",
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(supportedMimeTypes) },
                shape = RoundedCornerShape(cornerRadius.coerceIn(6.dp, 16.dp)),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Upload, contentDescription = "上传文件", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (knowledgeFiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "暂无知识文件",
                            fontSize = (15 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "点击右下角按钮上传文件",
                            fontSize = (12 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(knowledgeFiles, key = { "pk_${it.id}" }) { file ->
                        KnowledgeFileRow(
                            file = file,
                            fs = fs,
                            resolvedFontFamily = resolvedFontFamily,
                            cornerRadius = cornerRadius,
                            onDelete = { showDeleteConfirm = file }
                        )
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    showDeleteConfirm?.let { file ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(cornerRadius),
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除文件", fontFamily = resolvedFontFamily) },
            text = {
                Text(
                    text = "确定要删除「${file.fileName}」吗？此操作不可撤销。",
                    fontSize = (14 * fs).sp,
                    fontFamily = resolvedFontFamily
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                viewModel.repository.deleteUserProjectAsset(file)
                            } catch (e: Exception) {
                                errorMessage = "删除失败: ${e.localizedMessage}"
                            }
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("删除", fontFamily = resolvedFontFamily) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null },
                    shape = RoundedCornerShape((cornerRadius.value - 2).coerceAtLeast(0f).dp)
                ) { Text("取消", fontFamily = resolvedFontFamily) }
            }
        )
    }

    // 错误提示
    errorMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            errorMessage = null
        }
    }
}

@Composable
private fun KnowledgeFileRow(
    file: ProjectKnowledge,
    fs: Float,
    resolvedFontFamily: androidx.compose.ui.text.font.FontFamily,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onDelete: () -> Unit
) {
    val typeIcon = when (file.fileType) {
        "image" -> Icons.Default.Image
        "pdf" -> Icons.Default.PictureAsPdf
        "docx", "doc" -> Icons.Default.Description
        "md" -> Icons.Default.Description
        "txt" -> Icons.Default.TextSnippet
        else -> Icons.Default.AttachFile
    }

    val sourceLabel = when (file.source) {
        "USER_UPLOAD" -> "用户上传"
        "AGENT_CREATED" -> "Agent 创建"
        else -> file.source
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = typeIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    fontSize = (13.5f * fs).sp,
                    fontFamily = resolvedFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$sourceLabel · ${file.fileType.uppercase()} · ${dateFormat.format(Date(file.createdAt))}",
                    fontSize = (10.5f * fs).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = resolvedFontFamily
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
