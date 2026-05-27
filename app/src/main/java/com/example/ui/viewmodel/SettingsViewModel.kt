package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.ColorSchemePreset
import com.example.data.McpServer
import com.example.data.MemoryItem
import com.example.data.ModelConfig
import com.example.data.PromptTemplate
import com.example.data.UISettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    fun updateUISettings(settings: UISettings) {
        viewModelScope.launch {
            repository.upsertUISettings(settings)
        }
    }

    // ── 导出/导入状态 ────────────────────────────────────────────────────

    var exportImportStatus by mutableStateOf<ExportImportStatus>(ExportImportStatus.Idle)
        private set

    fun clearStatus() {
        exportImportStatus = ExportImportStatus.Idle
    }

    // ── 导出 ─────────────────────────────────────────────────────────────

    /**
     * 将选中的配置项序列化为 JSON 字符串。
     * 调用方负责将字符串写入用户选择的文件 URI。
     */
    suspend fun buildExportJson(
        includeProviders: Boolean,
        includeMcp: Boolean,
        includeMemory: Boolean,
        includeColorSchemes: Boolean
    ): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        if (includeProviders) {
            val configs = repository.getAllConfigs()
            val arr = JSONArray()
            for (c in configs) {
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("endpoint", c.endpoint)
                    put("apiKey", c.apiKey)
                    put("selectedModelId", c.selectedModelId)
                    put("memoryModelId", c.memoryModelId)
                    put("memoryProviderId", c.memoryProviderId)
                    put("isDefaultProvider", c.isDefaultProvider)
                    put("enableThinking", c.enableThinking)
                    put("thinkingEffort", c.thinkingEffort)
                    put("customHeaders", c.customHeaders)
                })
            }
            root.put("providers", arr)
        }

        if (includeMcp) {
            val servers = repository.getAllMcpServers()
            val arr = JSONArray()
            for (s in servers) {
                arr.put(JSONObject().apply {
                    put("name", s.name)
                    put("runtime", s.runtime)
                    put("command", s.command)
                    put("args", s.args)
                    put("env", s.env)
                    put("isEnabled", s.isEnabled)
                })
            }
            root.put("mcpServers", arr)
        }

        if (includeMemory) {
            val memories = repository.getAllMemories()
            val arr = JSONArray()
            for (m in memories) {
                arr.put(JSONObject().apply {
                    put("content", m.content)
                    put("confidence", m.confidence)
                    put("pinned", m.pinned)
                    put("createdAt", m.createdAt)
                    put("updatedAt", m.updatedAt)
                    put("lastReinforcedAt", m.lastReinforcedAt)
                })
            }
            root.put("memories", arr)

            // 同时导出 Prompt 模板
            val templates = repository.getAllTemplates()
            val tArr = JSONArray()
            for (t in templates) {
                tArr.put(JSONObject().apply {
                    put("name", t.name)
                    put("templateText", t.templateText)
                    put("isActive", t.isActive)
                })
            }
            root.put("promptTemplates", tArr)
        }

        if (includeColorSchemes) {
            val uiSettings = repository.getUISettings()
            if (uiSettings != null) {
                root.put("uiSettings", uiSettingsToJson(uiSettings))
            }
            val presets = repository.getAllColorSchemePresets()
            val arr = JSONArray()
            for (p in presets) {
                arr.put(colorPresetToJson(p))
            }
            root.put("colorSchemePresets", arr)
        }

        root.toString(2)
    }

    fun exportToUri(
        context: Context,
        uri: Uri,
        includeProviders: Boolean,
        includeMcp: Boolean,
        includeMemory: Boolean,
        includeColorSchemes: Boolean
    ) {
        viewModelScope.launch {
            exportImportStatus = ExportImportStatus.Loading
            try {
                val json = buildExportJson(includeProviders, includeMcp, includeMemory, includeColorSchemes)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                exportImportStatus = ExportImportStatus.Success("导出成功")
            } catch (e: Exception) {
                exportImportStatus = ExportImportStatus.Error("导出失败: ${e.message}")
            }
        }
    }

    // ── 导入 ─────────────────────────────────────────────────────────────

    fun importFromUri(
        context: Context,
        uri: Uri,
        importProviders: Boolean,
        importMcp: Boolean,
        importMemory: Boolean,
        importColorSchemes: Boolean,
        replaceExisting: Boolean
    ) {
        viewModelScope.launch {
            exportImportStatus = ExportImportStatus.Loading
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        inp.readBytes().toString(Charsets.UTF_8)
                    } ?: throw Exception("无法读取文件")
                }
                val root = JSONObject(json)
                val version = root.optInt("version", 1)

                var importedCount = 0

                if (importProviders && root.has("providers")) {
                    val arr = root.getJSONArray("providers")
                    if (replaceExisting) {
                        // 清空现有配置（保留默认标记逻辑）
                        val existing = repository.getAllConfigs()
                        for (c in existing) repository.deleteConfig(c)
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        repository.insertConfig(
                            ModelConfig(
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
                        importedCount++
                    }
                }

                if (importMcp && root.has("mcpServers")) {
                    val arr = root.getJSONArray("mcpServers")
                    if (replaceExisting) {
                        val existing = repository.getAllMcpServers()
                        for (s in existing) repository.deleteMcpServer(s)
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        repository.insertMcpServer(
                            McpServer(
                                name = obj.optString("name", "server-$i"),
                                runtime = obj.optString("runtime", "node"),
                                command = obj.optString("command", ""),
                                args = obj.optString("args", "[]"),
                                env = obj.optString("env", "{}"),
                                isEnabled = obj.optBoolean("isEnabled", true)
                            )
                        )
                        importedCount++
                    }
                }

                if (importMemory && root.has("memories")) {
                    val arr = root.getJSONArray("memories")
                    if (replaceExisting) {
                        repository.deleteAllMemories()
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        repository.insertMemory(
                            MemoryItem(
                                content = obj.optString("content", ""),
                                confidence = obj.optInt("confidence", 1),
                                pinned = obj.optBoolean("pinned", false),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                                lastReinforcedAt = obj.optLong("lastReinforcedAt", System.currentTimeMillis())
                            )
                        )
                        importedCount++
                    }
                }

                if (importMemory && root.has("promptTemplates")) {
                    val arr = root.getJSONArray("promptTemplates")
                    if (replaceExisting) {
                        val existing = repository.getAllTemplates()
                        for (t in existing) repository.deleteTemplate(t)
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        repository.insertTemplate(
                            PromptTemplate(
                                name = obj.optString("name", "template-$i"),
                                templateText = obj.optString("templateText", ""),
                                isActive = obj.optBoolean("isActive", false)
                            )
                        )
                        importedCount++
                    }
                }

                if (importColorSchemes && root.has("uiSettings")) {
                    val obj = root.getJSONObject("uiSettings")
                    val settings = jsonToUiSettings(obj)
                    repository.upsertUISettings(settings)
                    importedCount++
                }

                if (importColorSchemes && root.has("colorSchemePresets")) {
                    val arr = root.getJSONArray("colorSchemePresets")
                    if (replaceExisting) {
                        val existing = repository.getAllColorSchemePresets()
                        for (p in existing) repository.deleteColorSchemePreset(p.schemeId)
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        // 最多 5 个预设
                        if (repository.getColorSchemePresetCount() >= ColorSchemePreset.MAX_PRESETS) break
                        repository.insertColorSchemePreset(jsonToColorPreset(obj))
                        importedCount++
                    }
                }

                exportImportStatus = ExportImportStatus.Success("导入成功，共处理 $importedCount 条记录")
            } catch (e: Exception) {
                exportImportStatus = ExportImportStatus.Error("导入失败: ${e.message}")
            }
        }
    }

    // ── JSON 序列化辅助 ──────────────────────────────────────────────────

    private fun uiSettingsToJson(s: UISettings): JSONObject = JSONObject().apply {
        put("primaryColor", s.primaryColor)
        put("onPrimaryColor", s.onPrimaryColor)
        put("primaryContainerColor", s.primaryContainerColor)
        put("onPrimaryContainerColor", s.onPrimaryContainerColor)
        put("secondaryColor", s.secondaryColor)
        put("onSecondaryColor", s.onSecondaryColor)
        put("secondaryContainerColor", s.secondaryContainerColor)
        put("onSecondaryContainerColor", s.onSecondaryContainerColor)
        put("tertiaryColor", s.tertiaryColor)
        put("onTertiaryColor", s.onTertiaryColor)
        put("backgroundColor", s.backgroundColor)
        put("onBackgroundColor", s.onBackgroundColor)
        put("surfaceColor", s.surfaceColor)
        put("onSurfaceColor", s.onSurfaceColor)
        put("surfaceVariantColor", s.surfaceVariantColor)
        put("onSurfaceVariantColor", s.onSurfaceVariantColor)
        put("outlineColor", s.outlineColor)
        put("outlineVariantColor", s.outlineVariantColor)
        put("errorColor", s.errorColor)
        put("onErrorColor", s.onErrorColor)
        put("errorContainerColor", s.errorContainerColor)
        put("onErrorContainerColor", s.onErrorContainerColor)
        put("successColor", s.successColor)
        put("warningColor", s.warningColor)
        put("infoColor", s.infoColor)
        put("accentColor", s.accentColor)
        put("sidebarBackgroundColor", s.sidebarBackgroundColor)
        put("sidebarOnBackgroundColor", s.sidebarOnBackgroundColor)
        put("sidebarActiveColor", s.sidebarActiveColor)
        put("sidebarOnActiveColor", s.sidebarOnActiveColor)
        put("cornerRadiusDp", s.cornerRadiusDp)
        put("spacingMultiplier", s.spacingMultiplier)
        put("fontSizeScale", s.fontSizeScale)
        put("chatFontSizeScale", s.chatFontSizeScale)
        put("fontFamily", s.fontFamily)
        put("isNodeEnabled", s.isNodeEnabled)
        put("isPythonEnabled", s.isPythonEnabled)
        put("enabledMcpGroups", s.enabledMcpGroups)
        put("uiStrings", s.uiStrings)
    }

    private fun jsonToUiSettings(obj: JSONObject): UISettings {
        val defaults = UISettings()
        return UISettings(
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
            isNodeEnabled = obj.optBoolean("isNodeEnabled", defaults.isNodeEnabled),
            isPythonEnabled = obj.optBoolean("isPythonEnabled", defaults.isPythonEnabled),
            enabledMcpGroups = obj.optString("enabledMcpGroups", defaults.enabledMcpGroups),
            uiStrings = obj.optString("uiStrings", "{}"),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun colorPresetToJson(p: ColorSchemePreset): JSONObject = JSONObject().apply {
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
    }

    private fun jsonToColorPreset(obj: JSONObject): ColorSchemePreset {
        val defaults = UISettings()
        return ColorSchemePreset(
            schemeId = obj.optString("schemeId", java.util.UUID.randomUUID().toString()),
            name = obj.optString("name", "导入方案"),
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
    }
}

sealed class ExportImportStatus {
    object Idle : ExportImportStatus()
    object Loading : ExportImportStatus()
    data class Success(val message: String) : ExportImportStatus()
    data class Error(val message: String) : ExportImportStatus()
}
