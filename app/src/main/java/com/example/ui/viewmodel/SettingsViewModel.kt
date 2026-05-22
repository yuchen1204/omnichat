package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.UISettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(database)

    // 解决启动时主题闪烁：同步加载初始设置作为 StateFlow 的初始值
    private val initialSettings = runBlocking {
        repository.getUISettings()
    }

    val uiSettings: StateFlow<UISettings?> = repository.uiSettings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = initialSettings
        )
}
