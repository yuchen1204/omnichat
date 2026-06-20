package com.omnichat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnichat.ui.screens.MainScreen
import com.omnichat.ui.theme.MyApplicationTheme
import com.omnichat.ui.viewmodel.ChatViewModel
import com.omnichat.ui.viewmodel.SettingsViewModel

import com.omnichat.mcp.TimerManager
import com.omnichat.StreamingForegroundService
import com.omnichat.worker.CloudBackupWorker
import com.omnichat.data.AppDatabase
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : AppCompatActivity() {

    private val mcpRuntimeManager by lazy { com.omnichat.mcp.McpRuntimeManager.getInstance(applicationContext) }

    // Android 13 以下的运行时权限请求（READ_EXTERNAL_STORAGE）
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限结果无需处理，UI 层按需检查 */ }

    // Android 11+ 的 MANAGE_EXTERNAL_STORAGE 需要跳转系统设置页
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 从设置页返回后无需处理，UI 层按需检查 */ }

    // Android 13+ 通知权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知权限结果无需处理，用户拒绝时定时器仍可在聊天中插入消息 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        Log.i("MainActivity", "[onCreate] hasStorageAccess=$hasStorageAccess, SDK=${Build.VERSION.SDK_INT}")
        requestStoragePermissions()
        requestNotificationPermission()
        // 初始化定时器通知 Channel
        TimerManager.initNotificationChannel(applicationContext)
        // 初始化流式回复前台服务通知 Channel
        StreamingForegroundService.initChannel(applicationContext)
        // 从磁盘恢复待触发的定时器（进程重启后不丢失）
        TimerManager.restoreFromDisk(applicationContext)
        
        // 注册全局 Hook 示例
        com.omnichat.hooks.LoggingHooks.registerAll()

        // 注册 MCP 文件访问权限 Hook
        com.omnichat.hooks.HookManager.registerMcpHook(
            com.omnichat.hooks.McpFilePermissionHook(applicationContext)
        )

        // 注册 SubAgent 文件路径限制 Hook（必须在 McpFilePermissionHook 之后注册）
        com.omnichat.hooks.HookManager.registerMcpHook(
            com.omnichat.hooks.SubAgentPathRestrictionHook()
        )

        // 初始化内置工具注册表
        com.omnichat.tool.ToolInitializer.initialize(applicationContext)

        // Schedule cloud backup based on saved settings
        lifecycleScope.launch {
            val database = AppDatabase.getDatabase(applicationContext)
            val settings = database.uiSettingsDao().getSettings()
            val frequency = settings?.cloudBackupFrequency ?: "H6"
            CloudBackupWorker.enqueuePeriodicWork(applicationContext, frequency)
        }

        // 在 Activity 创建时立即触发 MCP 运行时自动启动（已启用的 server 在数据库就绪后会被自动启动）。
        // 这样不依赖任何 ViewModel 或 Composable 的初始化时机，确保 MCP 在应用启动后第一时间运行。
        mcpRuntimeManager
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val uiSettings by settingsViewModel.uiSettings.collectAsState()
            val windowSizeClass = calculateWindowSizeClass(this)

            MyApplicationTheme(uiSettings = uiSettings) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.omnichat.ui.theme.LocalWindowSizeClass provides windowSizeClass
                ) {
                    val viewModel: ChatViewModel = viewModel()
                    MainScreen(
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        Log.i("MainActivity", "[onResume] hasStorageAccess=$hasStorageAccess")
        // 外部存储权限可能在 onCreate 时还未授予，此时 MCP 脚本部署和 server 启动会失败。
        // 在 onResume 中重试，确保权限授予后 MCP 能正常启动。
        mcpRuntimeManager.ensureAutoStarted()
    }

    private fun requestStoragePermissions() {
        when {
            // Android 11+（API 30+）：请求 MANAGE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    Log.i("MainActivity", "[requestStoragePermissions] 请求 MANAGE_EXTERNAL_STORAGE 权限")
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    manageStorageLauncher.launch(intent)
                } else {
                    Log.i("MainActivity", "[requestStoragePermissions] MANAGE_EXTERNAL_STORAGE 已授予")
                }
            }
            // Android 9-10（API 28-29）：请求 READ_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val permissions = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (permissions.isNotEmpty()) {
                    storagePermissionLauncher.launch(permissions.toTypedArray())
                }
            }
            // Android 6 以下：Manifest 声明即可，无需运行时请求
        }
    }

    private fun requestNotificationPermission() {
        // Android 13+（API 33+）需要运行时请求 POST_NOTIFICATIONS 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i("MainActivity", "[requestNotificationPermission] 请求 POST_NOTIFICATIONS 权限")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
