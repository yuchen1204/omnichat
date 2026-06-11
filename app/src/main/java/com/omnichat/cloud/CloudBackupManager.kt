package com.omnichat.cloud

import android.content.Context
import com.omnichat.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class CloudBackupManager(private val context: Context) {

    private val repository = CloudBackupRepository(context)

    val isBound: Boolean get() = repository.isBound
    val userId: String? get() = repository.userId

    // --- Binding ---

    suspend fun bindTotp(): Result<BindTotpResponse> {
        return repository.bindTotp()
    }

    suspend fun verifyAndBind(totpSecret: String, totpCode: String): Result<VerifyResponse> {
        return repository.verifyAndBind(totpSecret, totpCode)
    }

    suspend fun verifyForRecovery(totpSecret: String, totpCode: String): Result<VerifyResponse> {
        return repository.verifyForRecovery(totpSecret, totpCode)
    }

    fun unbind() {
        repository.unbind()
    }

    // --- Backup ---

    suspend fun uploadConfigBackup(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Generate config export (same logic as SettingsViewModel.exportToUri)
            val configJson = generateConfigExport()
            val data = configJson.toByteArray(Charsets.UTF_8)
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val filename = "omnichat_config_$timestamp.omniconfig"

            val result = repository.uploadBackup("omniconfig", data, filename)
            result.map { it.backupId }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadDatabaseBackup(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")

            val dbPath = context.getDatabasePath("ai_chat_memory_db")
            val data = dbPath.readBytes()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val filename = "omnichat_db_$timestamp.omnidb"

            val result = repository.uploadBackup("omnidb", data, filename)
            result.map { it.backupId }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadAllBackups(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val configResult = uploadConfigBackup()
        val dbResult = uploadDatabaseBackup()

        if (configResult.isSuccess && dbResult.isSuccess) {
            Result.success(configResult.getOrThrow() to dbResult.getOrThrow())
        } else {
            val error = configResult.exceptionOrNull() ?: dbResult.exceptionOrNull()
            Result.failure(error ?: Exception("Upload failed"))
        }
    }

    // --- Restore ---

    suspend fun listBackups(): Result<List<BackupMeta>> {
        return repository.listBackups()
    }

    suspend fun restoreConfigBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = repository.downloadBackup(backupId).getOrThrow()
            // Save to temp file and return path for SettingsViewModel to import
            val tempFile = File(context.cacheDir, "restore_config.omniconfig")
            tempFile.writeBytes(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreDatabaseBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = repository.downloadBackup(backupId).getOrThrow()

            // Validate header
            if (!String(data, 0, minOf(10, data.size)).startsWith("OMNIDB_V1")) {
                return@withContext Result.failure(Exception("Invalid backup file"))
            }

            // Close database, replace file, restart app
            val db = AppDatabase.getDatabase(context)
            db.close()

            val dbPath = context.getDatabasePath("ai_chat_memory_db")
            dbPath.writeBytes(data)

            // Delete WAL/SHM
            File(dbPath.path + "-wal").delete()
            File(dbPath.path + "-shm").delete()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Settings ---

    fun setWorkersUrl(url: String) {
        repository.setWorkersUrl(url)
    }

    fun getWorkersUrl(): String {
        return repository.getWorkersUrl()
    }

    private fun generateConfigExport(): String {
        val repository = com.omnichat.data.AppRepository(
            com.omnichat.data.AppDatabase.getDatabase(context)
        )

        val root = org.json.JSONObject().apply {
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())

            // Providers
            val providers = org.json.JSONArray()
            val allProviders = runBlocking { repository.getAllModelConfigs() }
            for (p in allProviders) {
                providers.put(org.json.JSONObject().apply {
                    put("name", p.name)
                    put("endpoint", p.endpoint)
                    put("apiKey", p.apiKey)
                    put("selectedModelId", p.selectedModelId)
                    put("memoryModelId", p.memoryModelId)
                    put("memoryProviderId", p.memoryProviderId)
                    put("isDefaultProvider", p.isDefaultProvider)
                    put("enableThinking", p.enableThinking)
                    put("thinkingEffort", p.thinkingEffort)
                    put("customHeaders", p.customHeaders)
                })
            }
            put("providers", providers)

            // MCP Servers
            val mcpServers = org.json.JSONArray()
            val allServers = runBlocking { repository.getAllMcpServers() }
            for (s in allServers) {
                mcpServers.put(org.json.JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("command", s.command)
                    put("args", s.args)
                    put("runtime", s.runtime)
                    put("isEnabled", s.isEnabled)
                    put("autoStart", s.autoStart)
                    put("envVars", s.envVars)
                    put("customHeaders", s.customHeaders)
                })
            }
            put("mcpServers", mcpServers)

            // MCP File Permissions
            val permissions = org.json.JSONArray()
            val allPerms = runBlocking { repository.getAllMcpFilePermissions() }
            for (p in allPerms) {
                permissions.put(org.json.JSONObject().apply {
                    put("path", p.path)
                    put("permission", p.permission)
                    put("allowed", p.allowed)
                })
            }
            put("mcpFilePermissions", permissions)

            // Memories
            val memories = org.json.JSONArray()
            val allMemories = runBlocking { repository.getAllMemoryItems() }
            for (m in allMemories) {
                memories.put(org.json.JSONObject().apply {
                    put("content", m.content)
                    put("confidence", m.confidence)
                    put("pinned", m.pinned)
                    put("tags", m.tags)
                    put("createdAt", m.createdAt)
                    put("updatedAt", m.updatedAt)
                    put("embedding", m.embedding)
                })
            }
            put("memories", memories)

            // Prompt Templates
            val templates = org.json.JSONArray()
            val allTemplates = runBlocking { repository.getAllPromptTemplates() }
            for (t in allTemplates) {
                templates.put(org.json.JSONObject().apply {
                    put("title", t.title)
                    put("promptText", t.promptText)
                    put("isActive", t.isActive)
                })
            }
            put("promptTemplates", templates)

            // UI Settings
            val uiSettings = runBlocking { repository.getUiSettings() }
            if (uiSettings != null) {
                put("uiSettings", org.json.JSONObject().apply {
                    put("primaryColor", uiSettings.primaryColor)
                    put("onPrimaryColor", uiSettings.onPrimaryColor)
                    put("primaryContainerColor", uiSettings.primaryContainerColor)
                    put("onPrimaryContainerColor", uiSettings.onPrimaryContainerColor)
                    put("secondaryColor", uiSettings.secondaryColor)
                    put("onSecondaryColor", uiSettings.onSecondaryColor)
                    put("secondaryContainerColor", uiSettings.secondaryContainerColor)
                    put("onSecondaryContainerColor", uiSettings.onSecondaryContainerColor)
                    put("tertiaryColor", uiSettings.tertiaryColor)
                    put("onTertiaryColor", uiSettings.onTertiaryColor)
                    put("backgroundColor", uiSettings.backgroundColor)
                    put("onBackgroundColor", uiSettings.onBackgroundColor)
                    put("surfaceColor", uiSettings.surfaceColor)
                    put("onSurfaceColor", uiSettings.onSurfaceColor)
                    put("surfaceVariantColor", uiSettings.surfaceVariantColor)
                    put("onSurfaceVariantColor", uiSettings.onSurfaceVariantColor)
                    put("outlineColor", uiSettings.outlineColor)
                    put("outlineVariantColor", uiSettings.outlineVariantColor)
                    put("errorColor", uiSettings.errorColor)
                    put("onErrorColor", uiSettings.onErrorColor)
                    put("errorContainerColor", uiSettings.errorContainerColor)
                    put("onErrorContainerColor", uiSettings.onErrorContainerColor)
                    put("successColor", uiSettings.successColor)
                    put("warningColor", uiSettings.warningColor)
                    put("infoColor", uiSettings.infoColor)
                    put("accentColor", uiSettings.accentColor)
                    put("sidebarBackgroundColor", uiSettings.sidebarBackgroundColor)
                    put("sidebarOnBackgroundColor", uiSettings.sidebarOnBackgroundColor)
                    put("sidebarActiveColor", uiSettings.sidebarActiveColor)
                    put("sidebarOnActiveColor", uiSettings.sidebarOnActiveColor)
                    put("cornerRadiusDp", uiSettings.cornerRadiusDp)
                    put("spacingMultiplier", uiSettings.spacingMultiplier)
                    put("fontSizeScale", uiSettings.fontSizeScale)
                    put("chatFontSizeScale", uiSettings.chatFontSizeScale)
                    put("fontFamily", uiSettings.fontFamily)
                    put("enabledMcpGroups", uiSettings.enabledMcpGroups)
                    put("silentToolCalls", uiSettings.silentToolCalls)
                    put("uiStrings", uiSettings.uiStrings)
                })
            }

            // Color Scheme Presets
            val presets = org.json.JSONArray()
            val allPresets = runBlocking { repository.getAllColorSchemePresets() }
            for (p in allPresets) {
                presets.put(org.json.JSONObject().apply {
                    put("schemeId", p.schemeId)
                    put("name", p.name)
                    put("description", p.description)
                    put("createdAt", p.createdAt)
                    put("primaryColor", p.primaryColor)
                    put("onPrimaryColor", p.onPrimaryColor)
                    put("primaryContainerColor", p.primaryContainerColor)
                    put("onPrimaryContainerColor", p.onPrimaryContainerColor)
                    put("secondaryColor", p.secondaryColor)
                    put("onSecondaryColor", p.onSecondaryColor)
                    put("secondaryContainerColor", p.secondaryContainerColor)
                    put("onSecondaryContainerColor", p.onSecondaryContainerColor)
                    put("tertiaryColor", p.tertiaryColor)
                    put("onTertiaryColor", p.onTertiaryColor)
                    put("backgroundColor", p.backgroundColor)
                    put("onBackgroundColor", p.onBackgroundColor)
                    put("surfaceColor", p.surfaceColor)
                    put("onSurfaceColor", p.onSurfaceColor)
                    put("surfaceVariantColor", p.surfaceVariantColor)
                    put("onSurfaceVariantColor", p.onSurfaceVariantColor)
                    put("outlineColor", p.outlineColor)
                    put("outlineVariantColor", p.outlineVariantColor)
                    put("errorColor", p.errorColor)
                    put("onErrorColor", p.onErrorColor)
                    put("errorContainerColor", p.errorContainerColor)
                    put("onErrorContainerColor", p.onErrorContainerColor)
                    put("successColor", p.successColor)
                    put("warningColor", p.warningColor)
                    put("infoColor", p.infoColor)
                    put("accentColor", p.accentColor)
                    put("sidebarBackgroundColor", p.sidebarBackgroundColor)
                    put("sidebarOnBackgroundColor", p.sidebarOnBackgroundColor)
                    put("sidebarActiveColor", p.sidebarActiveColor)
                    put("sidebarOnActiveColor", p.sidebarOnActiveColor)
                    put("cornerRadiusDp", p.cornerRadiusDp)
                    put("spacingMultiplier", p.spacingMultiplier)
                })
            }
            put("colorSchemePresets", presets)
        }

        return root.toString()
    }
}
