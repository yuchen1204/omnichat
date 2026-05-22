package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.SettingsViewModel

class MainActivity : AppCompatActivity() {

    // Android 13 以下的运行时权限请求（READ_EXTERNAL_STORAGE）
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限结果无需处理，UI 层按需检查 */ }

    // Android 11+ 的 MANAGE_EXTERNAL_STORAGE 需要跳转系统设置页
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 从设置页返回后无需处理，UI 层按需检查 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermissions()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val uiSettings by settingsViewModel.uiSettings.collectAsState()
            
            MyApplicationTheme(uiSettings = uiSettings) {
                val viewModel: ChatViewModel = viewModel()
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestStoragePermissions() {
        when {
            // Android 11+（API 30+）：请求 MANAGE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    manageStorageLauncher.launch(intent)
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
}
