package com.omnichat.cloud

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.omnichat.MainActivity
import androidx.lifecycle.viewModelScope
import com.omnichat.R
import com.omnichat.data.AppDatabase
import com.omnichat.worker.CloudBackupWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val COLD_RESTART_DELAY_MS = 100L

data class CloudBackupUiState(
    val isBound: Boolean = false,
    val userId: String? = null,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val isRestoring: Boolean = false,
    val error: String? = null,
    val success: String? = null,
    val totpSecret: String? = null,
    val qrCodeUrl: String? = null,
    val backups: List<BackupMeta> = emptyList(),
    val showBindDialog: Boolean = false,
    val showRecoveryDialog: Boolean = false,
    val showBackupListDialog: Boolean = false,
    val backupFrequency: String = "H6",
    val backupSections: Set<String> = setOf(
        "providers", "mcpServers", "mcpFilePermissions",
        "memories", "promptTemplates", "uiSettings", "colorSchemePresets"
    )
)

class CloudBackupViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = CloudBackupManager(application)

    private val _uiState = MutableStateFlow(CloudBackupUiState())
    val uiState: StateFlow<CloudBackupUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                isBound = manager.isBound,
                userId = manager.userId
            )
        }

        viewModelScope.launch {
            val database = AppDatabase.getDatabase(application)
            val settings = database.uiSettingsDao().getSettings()
            settings?.let {
                val frequency = it.cloudBackupFrequency
                val sections = try {
                    org.json.JSONArray(it.cloudBackupSections).let { arr ->
                        (0 until arr.length()).map { i -> arr.getString(i) }.toSet()
                    }
                } catch (_: Exception) { _uiState.value.backupSections }

                _uiState.update { state ->
                    state.copy(
                        backupFrequency = frequency,
                        backupSections = sections
                    )
                }
            }
        }
    }

    fun showBindDialog() {
        _uiState.update { it.copy(showBindDialog = true) }
    }

    fun hideBindDialog() {
        _uiState.update { it.copy(showBindDialog = false, totpSecret = null, qrCodeUrl = null) }
    }

    fun showRecoveryDialog() {
        _uiState.update { it.copy(showRecoveryDialog = true) }
    }

    fun hideRecoveryDialog() {
        _uiState.update { it.copy(showRecoveryDialog = false) }
    }

    fun hideBackupListDialog() {
        _uiState.update { it.copy(showBackupListDialog = false) }
    }

    fun bindTotp() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = manager.bindTotp()
            result.fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            totpSecret = response.totpSecret,
                            qrCodeUrl = response.qrCodeUrl
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun verifyAndBind(totpSecret: String, totpCode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = manager.verifyAndBind(totpSecret, totpCode)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBound = true,
                            userId = manager.userId,
                            showBindDialog = false,
                            totpSecret = null,
                            qrCodeUrl = null,
                            success = getApplication<Application>().getString(R.string.cloud_bind_success)
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun verifyForRecovery(totpSecret: String, totpCode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = manager.verifyForRecovery(totpSecret, totpCode)
            result.fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBound = true,
                            userId = response.userId,
                            showRecoveryDialog = false,
                            showBackupListDialog = true
                        )
                    }
                    loadBackups()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun recoverByTotpCode(totpCode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = manager.recover(totpCode)
            result.fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBound = true,
                            userId = response.userId,
                            backups = response.backups,
                            showRecoveryDialog = false,
                            showBackupListDialog = true
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun unbind() {
        manager.unbind()
        _uiState.update {
            it.copy(
                isBound = false,
                userId = null,
                backups = emptyList()
            )
        }
    }

    fun uploadBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, error = null) }
            val sections = _uiState.value.backupSections.toList()
            val result = manager.uploadOmnifileBackup(sections = sections)
            result.onSuccess {
                loadBackups()
                _uiState.update { state ->
                    state.copy(
                        isUploading = false,
                        success = getApplication<Application>().getString(R.string.cloud_backup_success)
                    )
                }
            }.onFailure { e ->
                _uiState.update { state ->
                    state.copy(isUploading = false, error = e.message)
                }
            }
        }
    }

    fun updateBackupFrequency(frequency: String) {
        _uiState.update { it.copy(backupFrequency = frequency) }
        viewModelScope.launch {
            val database = AppDatabase.getDatabase(getApplication())
            val current = database.uiSettingsDao().getSettings()
            database.uiSettingsDao().upsertSettings(
                (current ?: com.omnichat.data.UISettings()).copy(cloudBackupFrequency = frequency)
            )
            CloudBackupWorker.reconcilePeriodicWork(getApplication())
        }
    }

    fun toggleBackupSection(section: String) {
        _uiState.update { state ->
            val newSections = if (state.backupSections.contains(section)) {
                state.backupSections - section
            } else {
                state.backupSections + section
            }
            state.copy(backupSections = newSections)
        }
        viewModelScope.launch {
            val database = AppDatabase.getDatabase(getApplication())
            val current = database.uiSettingsDao().getSettings()
            current?.let {
                val sectionsJson = org.json.JSONArray(_uiState.value.backupSections.toList()).toString()
                database.uiSettingsDao().upsertSettings(it.copy(cloudBackupSections = sectionsJson))
            }
        }
    }

    fun loadBackups() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = manager.listBackups()
            result.fold(
                onSuccess = { backups ->
                    _uiState.update {
                        it.copy(isLoading = false, backups = backups)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun loadBackupsAndShow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = manager.listBackups()
            result.fold(
                onSuccess = { backups ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backups = backups,
                            showBackupListDialog = true
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun restoreBackup(backup: BackupMeta) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, error = null) }
            val result = when (backup.type) {
                "omnifile" -> manager.restoreOmnifileBackup(backup.id)
                else -> Result.failure(Exception("仅支持 omnifile 格式的恢复"))
            }
            result.fold(
                onSuccess = {
                    try {
                        scheduleColdRestart()
                        _uiState.update {
                            it.copy(
                                isRestoring = false,
                                success = getApplication<Application>().getString(R.string.cloud_restore_success)
                            )
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(isRestoring = false, error = e.message)
                        }
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isRestoring = false, error = e.message)
                    }
                }
            )
        }
    }

    private fun scheduleColdRestart() {
        val application = getApplication<Application>()
        val restartIntent = Intent.makeRestartActivityTask(
            ComponentName(application, MainActivity::class.java)
        )
        val restartPendingIntent = PendingIntent.getActivity(
            application,
            0,
            restartIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = application.getSystemService(AlarmManager::class.java)
            ?: throw IllegalStateException("AlarmManager unavailable")

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + COLD_RESTART_DELAY_MS,
            restartPendingIntent
        )
    }

    fun deleteBackup(backup: BackupMeta) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = manager.deleteBackup(backup.id)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false)
                    }
                    loadBackups()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(success = null) }
    }
}
