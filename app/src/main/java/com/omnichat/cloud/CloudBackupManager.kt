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

    suspend fun recover(totpCode: String): Result<RecoverResponse> {
        return repository.recover(totpCode)
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
            val dbPath = context.getDatabasePath("ai_chat_memory_db")
            val rawData = dbPath.readBytes()
            // Add OMNIDB_V1 header to match local export format
            val header = "OMNIDB_V1\n".toByteArray()
            val data = header + rawData
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

    suspend fun deleteBackup(backupId: String): Result<Unit> {
        return repository.deleteBackup(backupId)
    }

    suspend fun restoreConfigBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = repository.downloadBackup(backupId).getOrThrow()
            val json = data.toString(Charsets.UTF_8)
            val root = org.json.JSONObject(json)

            val appRepo = com.omnichat.data.AppRepository(
                com.omnichat.data.AppDatabase.getDatabase(context)
            )

            // Import providers
            if (root.has("providers")) {
                val arr = root.getJSONArray("providers")
                val existing = appRepo.getAllConfigs()
                for (c in existing) appRepo.deleteConfig(c)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    appRepo.insertConfig(
                        com.omnichat.data.ModelConfig(
                            name = obj.optString("name", "provider-$i"),
                            endpoint = obj.optString("endpoint", ""),
                            apiKey = obj.optString("apiKey", ""),
                            selectedModelId = obj.optString("selectedModelId", ""),
                            memoryModelId = obj.optString("memoryModelId", ""),
                            memoryProviderId = obj.optLong("memoryProviderId", 0L),
                            isDefaultProvider = obj.optBoolean("isDefaultProvider", false),
                            enableThinking = obj.optBoolean("enableThinking", true),
                            thinkingEffort = obj.optString("thinkingEffort", "medium"),
                            customHeaders = obj.optString("customHeaders", "{}")
                        )
                    )
                }
            }

            // Import MCP servers
            if (root.has("mcpServers")) {
                val arr = root.getJSONArray("mcpServers")
                val existing = appRepo.getAllMcpServers()
                for (s in existing) appRepo.deleteMcpServer(s)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    appRepo.insertMcpServer(
                        com.omnichat.data.McpServer(
                            name = obj.optString("name", "server-$i"),
                            command = obj.optString("command", ""),
                            args = obj.optString("args", "[]"),
                            env = obj.optString("env", "{}"),
                            isEnabled = obj.optBoolean("isEnabled", true)
                        )
                    )
                }
            }

            // Import MCP file permissions
            if (root.has("mcpFilePermissions")) {
                val arr = root.getJSONArray("mcpFilePermissions")
                appRepo.deleteAllMcpFilePermissions()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    appRepo.insertMcpFilePermission(
                        com.omnichat.data.McpFilePermission(
                            path = obj.optString("path", ""),
                            isAllowed = obj.optBoolean("isAllowed", false)
                        )
                    )
                }
            }

            // Import memories
            if (root.has("memories")) {
                val arr = root.getJSONArray("memories")
                appRepo.deleteAllMemories()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    appRepo.insertMemory(
                        com.omnichat.data.MemoryItem(
                            content = obj.optString("content", ""),
                            confidence = obj.optInt("confidence", 1),
                            pinned = obj.optBoolean("pinned", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                            lastReinforcedAt = obj.optLong("lastReinforcedAt", System.currentTimeMillis()),
                            tags = obj.optString("tags", "")
                        )
                    )
                }
            }

            // Import prompt templates
            if (root.has("promptTemplates")) {
                val arr = root.getJSONArray("promptTemplates")
                val existing = appRepo.getAllTemplates()
                for (t in existing) appRepo.deleteTemplate(t)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    appRepo.insertTemplate(
                        com.omnichat.data.PromptTemplate(
                            name = obj.optString("name", "template-$i"),
                            templateText = obj.optString("templateText", ""),
                            isActive = obj.optBoolean("isActive", false)
                        )
                    )
                }
            }

            // Import UI settings
            if (root.has("uiSettings")) {
                val obj = root.getJSONObject("uiSettings")
                val defaults = com.omnichat.data.UISettings()
                val settings = com.omnichat.data.UISettings(
                    id = 1L,
                    primaryColor = obj.optString("primaryColor", defaults.primaryColor),
                    onPrimaryColor = obj.optString("onPrimaryColor", defaults.onPrimaryColor),
                    primaryContainerColor = obj.optString("primaryContainerColor", defaults.primaryContainerColor),
                    onPrimaryContainerColor = obj.optString("onPrimaryContainerColor", defaults.onPrimaryContainerColor),
                    secondaryColor = obj.optString("secondaryColor", defaults.secondaryColor),
                    onSecondaryColor = obj.optString("onSecondaryColor", defaults.onSecondaryColor),
                    secondaryContainerColor = obj.optString("secondaryContainerColor", defaults.secondaryContainerColor),
                    onSecondaryContainerColor = obj.optString("onSecondaryContainerColor", defaults.onSecondaryContainerColor),
                    tertiaryColor = obj.optString("tertiaryColor", defaults.tertiaryColor),
                    onTertiaryColor = obj.optString("onTertiaryColor", defaults.onTertiaryColor),
                    backgroundColor = obj.optString("backgroundColor", defaults.backgroundColor),
                    onBackgroundColor = obj.optString("onBackgroundColor", defaults.onBackgroundColor),
                    surfaceColor = obj.optString("surfaceColor", defaults.surfaceColor),
                    onSurfaceColor = obj.optString("onSurfaceColor", defaults.onSurfaceColor),
                    surfaceVariantColor = obj.optString("surfaceVariantColor", defaults.surfaceVariantColor),
                    onSurfaceVariantColor = obj.optString("onSurfaceVariantColor", defaults.onSurfaceVariantColor),
                    outlineColor = obj.optString("outlineColor", defaults.outlineColor),
                    outlineVariantColor = obj.optString("outlineVariantColor", defaults.outlineVariantColor),
                    errorColor = obj.optString("errorColor", defaults.errorColor),
                    onErrorColor = obj.optString("onErrorColor", defaults.onErrorColor),
                    errorContainerColor = obj.optString("errorContainerColor", defaults.errorContainerColor),
                    onErrorContainerColor = obj.optString("onErrorContainerColor", defaults.onErrorContainerColor),
                    successColor = obj.optString("successColor", defaults.successColor),
                    warningColor = obj.optString("warningColor", defaults.warningColor),
                    infoColor = obj.optString("infoColor", defaults.infoColor),
                    accentColor = obj.optString("accentColor", defaults.accentColor),
                    sidebarBackgroundColor = obj.optString("sidebarBackgroundColor", defaults.sidebarBackgroundColor),
                    sidebarOnBackgroundColor = obj.optString("sidebarOnBackgroundColor", defaults.sidebarOnBackgroundColor),
                    sidebarActiveColor = obj.optString("sidebarActiveColor", defaults.sidebarActiveColor),
                    sidebarOnActiveColor = obj.optString("sidebarOnActiveColor", defaults.sidebarOnActiveColor),
                    cornerRadiusDp = obj.optInt("cornerRadiusDp", defaults.cornerRadiusDp),
                    spacingMultiplier = obj.optDouble("spacingMultiplier", defaults.spacingMultiplier.toDouble()).toFloat(),
                    fontSizeScale = obj.optDouble("fontSizeScale", defaults.fontSizeScale.toDouble()).toFloat(),
                    chatFontSizeScale = obj.optDouble("chatFontSizeScale", defaults.chatFontSizeScale.toDouble()).toFloat(),
                    fontFamily = obj.optString("fontFamily", defaults.fontFamily),
                    enabledMcpGroups = obj.optString("enabledMcpGroups", defaults.enabledMcpGroups),
                    silentToolCalls = obj.optBoolean("silentToolCalls", defaults.silentToolCalls),
                    uiStrings = obj.optString("uiStrings", "{}"),
                    updatedAt = System.currentTimeMillis()
                )
                appRepo.upsertUISettings(settings)
            }

            // Import color scheme presets
            if (root.has("colorSchemePresets")) {
                val arr = root.getJSONArray("colorSchemePresets")
                val existing = appRepo.getAllColorSchemePresets()
                for (p in existing) appRepo.deleteColorSchemePreset(p.schemeId)
                val defaults = com.omnichat.data.UISettings()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (appRepo.getColorSchemePresetCount() >= com.omnichat.data.ColorSchemePreset.MAX_PRESETS) break
                    val preset = com.omnichat.data.ColorSchemePreset(
                        schemeId = obj.optString("schemeId", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", "Imported"),
                        description = obj.optString("description", ""),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        primaryColor = obj.optString("primaryColor", defaults.primaryColor),
                        onPrimaryColor = obj.optString("onPrimaryColor", defaults.onPrimaryColor),
                        primaryContainerColor = obj.optString("primaryContainerColor", defaults.primaryContainerColor),
                        onPrimaryContainerColor = obj.optString("onPrimaryContainerColor", defaults.onPrimaryContainerColor),
                        secondaryColor = obj.optString("secondaryColor", defaults.secondaryColor),
                        onSecondaryColor = obj.optString("onSecondaryColor", defaults.onSecondaryColor),
                        secondaryContainerColor = obj.optString("secondaryContainerColor", defaults.secondaryContainerColor),
                        onSecondaryContainerColor = obj.optString("onSecondaryContainerColor", defaults.onSecondaryContainerColor),
                        tertiaryColor = obj.optString("tertiaryColor", defaults.tertiaryColor),
                        onTertiaryColor = obj.optString("onTertiaryColor", defaults.onTertiaryColor),
                        backgroundColor = obj.optString("backgroundColor", defaults.backgroundColor),
                        onBackgroundColor = obj.optString("onBackgroundColor", defaults.onBackgroundColor),
                        surfaceColor = obj.optString("surfaceColor", defaults.surfaceColor),
                        onSurfaceColor = obj.optString("onSurfaceColor", defaults.onSurfaceColor),
                        surfaceVariantColor = obj.optString("surfaceVariantColor", defaults.surfaceVariantColor),
                        onSurfaceVariantColor = obj.optString("onSurfaceVariantColor", defaults.onSurfaceVariantColor),
                        outlineColor = obj.optString("outlineColor", defaults.outlineColor),
                        outlineVariantColor = obj.optString("outlineVariantColor", defaults.outlineVariantColor),
                        errorColor = obj.optString("errorColor", defaults.errorColor),
                        onErrorColor = obj.optString("onErrorColor", defaults.onErrorColor),
                        errorContainerColor = obj.optString("errorContainerColor", defaults.errorContainerColor),
                        onErrorContainerColor = obj.optString("onErrorContainerColor", defaults.onErrorContainerColor),
                        successColor = obj.optString("successColor", defaults.successColor),
                        warningColor = obj.optString("warningColor", defaults.warningColor),
                        infoColor = obj.optString("infoColor", defaults.infoColor),
                        accentColor = obj.optString("accentColor", defaults.accentColor),
                        sidebarBackgroundColor = obj.optString("sidebarBackgroundColor", defaults.sidebarBackgroundColor),
                        sidebarOnBackgroundColor = obj.optString("sidebarOnBackgroundColor", defaults.sidebarOnBackgroundColor),
                        sidebarActiveColor = obj.optString("sidebarActiveColor", defaults.sidebarActiveColor),
                        sidebarOnActiveColor = obj.optString("sidebarOnActiveColor", defaults.sidebarOnActiveColor),
                        cornerRadiusDp = obj.optInt("cornerRadiusDp", defaults.cornerRadiusDp),
                        spacingMultiplier = obj.optDouble("spacingMultiplier", defaults.spacingMultiplier.toDouble()).toFloat()
                    )
                    appRepo.insertColorSchemePreset(preset)
                }
            }

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

    private suspend fun generateConfigExport(): String {
        val repository = com.omnichat.data.AppRepository(
            com.omnichat.data.AppDatabase.getDatabase(context)
        )

        val root = org.json.JSONObject().apply {
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())

            // Providers
            val providers = org.json.JSONArray()
            val allProviders = repository.getAllConfigs()
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
            val allServers = repository.getAllMcpServers()
            for (s in allServers) {
                mcpServers.put(org.json.JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("command", s.command)
                    put("args", s.args)
                    put("env", s.env)
                    put("isEnabled", s.isEnabled)
                })
            }
            put("mcpServers", mcpServers)

            // MCP File Permissions
            val permissions = org.json.JSONArray()
            val allPerms = repository.getAllMcpFilePermissions()
            for (p in allPerms) {
                permissions.put(org.json.JSONObject().apply {
                    put("path", p.path)
                    put("isAllowed", p.isAllowed)
                    put("permissionType", p.permissionType)
                })
            }
            put("mcpFilePermissions", permissions)

            // Memories
            val memories = org.json.JSONArray()
            val allMemories = repository.getAllMemories()
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
            val allTemplates = repository.getAllTemplates()
            for (t in allTemplates) {
                templates.put(org.json.JSONObject().apply {
                    put("name", t.name)
                    put("templateText", t.templateText)
                    put("isActive", t.isActive)
                })
            }
            put("promptTemplates", templates)

            // UI Settings
            val uiSettings = repository.getUISettings()
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
            val allPresets = repository.getAllColorSchemePresets()
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
