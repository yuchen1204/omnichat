package com.example.mcp

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.UISettings
import com.example.data.ColorSchemePreset
import com.example.data.ColorSchemePreset.Companion.toUISettings
import com.example.ui.theme.UiStrings
import com.example.ui.theme.UiStrings.Companion.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date

object BuiltinToolHandler {

    suspend fun handleBuiltinTool(context: Context, toolName: String, arguments: JSONObject): JSONObject {
        return when (toolName) {
            "get_ui_capabilities" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                buildUiCapabilitiesResponse(current)
            }
            "reset_ui_to_default" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                repository.upsertUISettings(UISettings())
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "UI 设置已成功恢复为默认。")
                        })
                    })
                }
            }
            "adjust_ui" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()

                if (arguments.optBoolean("resetToDefault", false)) {
                    repository.upsertUISettings(UISettings())
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "UI 已重置为默认设置。")
                            })
                        })
                    }
                }

                fun isValidHex(hex: String?): Boolean {
                    if (hex == null) return false
                    return Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$").matches(hex)
                }

                fun hex(key: String, fallback: String): String =
                    arguments.optString(key).takeIf { isValidHex(it) } ?: fallback

                val cornerRadius = arguments.optInt("cornerRadiusDp", current.cornerRadiusDp).coerceIn(0, 32)
                val spacing = arguments.optDouble("spacingMultiplier", current.spacingMultiplier.toDouble())
                    .toFloat().coerceIn(0.5f, 2.0f)

                val next = current.copy(
                    primaryColor = hex("primaryColor", current.primaryColor),
                    onPrimaryColor = hex("onPrimaryColor", current.onPrimaryColor),
                    primaryContainerColor = hex("primaryContainerColor", current.primaryContainerColor),
                    onPrimaryContainerColor = hex("onPrimaryContainerColor", current.onPrimaryContainerColor),
                    secondaryColor = hex("secondaryColor", current.secondaryColor),
                    onSecondaryColor = hex("onSecondaryColor", current.onSecondaryColor),
                    secondaryContainerColor = hex("secondaryContainerColor", current.secondaryContainerColor),
                    onSecondaryContainerColor = hex("onSecondaryContainerColor", current.onSecondaryContainerColor),
                    tertiaryColor = hex("tertiaryColor", current.tertiaryColor),
                    onTertiaryColor = hex("onTertiaryColor", current.onTertiaryColor),
                    backgroundColor = hex("backgroundColor", current.backgroundColor),
                    onBackgroundColor = hex("onBackgroundColor", current.onBackgroundColor),
                    surfaceColor = hex("surfaceColor", current.surfaceColor),
                    onSurfaceColor = hex("onSurfaceColor", current.onSurfaceColor),
                    surfaceVariantColor = hex("surfaceVariantColor", current.surfaceVariantColor),
                    onSurfaceVariantColor = hex("onSurfaceVariantColor", current.onSurfaceVariantColor),
                    outlineColor = hex("outlineColor", current.outlineColor),
                    outlineVariantColor = hex("outlineVariantColor", current.outlineVariantColor),
                    errorColor = hex("errorColor", current.errorColor),
                    onErrorColor = hex("onErrorColor", current.onErrorColor),
                    errorContainerColor = hex("errorContainerColor", current.errorContainerColor),
                    onErrorContainerColor = hex("onErrorContainerColor", current.onErrorContainerColor),
                    successColor = hex("successColor", current.successColor),
                    warningColor = hex("warningColor", current.warningColor),
                    infoColor = hex("infoColor", current.infoColor),
                    accentColor = hex("accentColor", current.accentColor),
                    sidebarBackgroundColor = hex("sidebarBackgroundColor", current.sidebarBackgroundColor),
                    sidebarOnBackgroundColor = hex("sidebarOnBackgroundColor", current.sidebarOnBackgroundColor),
                    sidebarActiveColor = hex("sidebarActiveColor", current.sidebarActiveColor),
                    sidebarOnActiveColor = hex("sidebarOnActiveColor", current.sidebarOnActiveColor),
                    cornerRadiusDp = cornerRadius,
                    spacingMultiplier = spacing,
                    updatedAt = System.currentTimeMillis()
                )

                repository.upsertUISettings(next)

                // 计算实际有变化的字段，便于反馈
                val changed = mutableListOf<String>()
                if (next.primaryColor != current.primaryColor) changed += "primaryColor"
                if (next.onPrimaryColor != current.onPrimaryColor) changed += "onPrimaryColor"
                if (next.primaryContainerColor != current.primaryContainerColor) changed += "primaryContainerColor"
                if (next.onPrimaryContainerColor != current.onPrimaryContainerColor) changed += "onPrimaryContainerColor"
                if (next.secondaryColor != current.secondaryColor) changed += "secondaryColor"
                if (next.onSecondaryColor != current.onSecondaryColor) changed += "onSecondaryColor"
                if (next.secondaryContainerColor != current.secondaryContainerColor) changed += "secondaryContainerColor"
                if (next.onSecondaryContainerColor != current.onSecondaryContainerColor) changed += "onSecondaryContainerColor"
                if (next.tertiaryColor != current.tertiaryColor) changed += "tertiaryColor"
                if (next.onTertiaryColor != current.onTertiaryColor) changed += "onTertiaryColor"
                if (next.backgroundColor != current.backgroundColor) changed += "backgroundColor"
                if (next.onBackgroundColor != current.onBackgroundColor) changed += "onBackgroundColor"
                if (next.surfaceColor != current.surfaceColor) changed += "surfaceColor"
                if (next.onSurfaceColor != current.onSurfaceColor) changed += "onSurfaceColor"
                if (next.surfaceVariantColor != current.surfaceVariantColor) changed += "surfaceVariantColor"
                if (next.onSurfaceVariantColor != current.onSurfaceVariantColor) changed += "onSurfaceVariantColor"
                if (next.outlineColor != current.outlineColor) changed += "outlineColor"
                if (next.outlineVariantColor != current.outlineVariantColor) changed += "outlineVariantColor"
                if (next.errorColor != current.errorColor) changed += "errorColor"
                if (next.onErrorColor != current.onErrorColor) changed += "onErrorColor"
                if (next.errorContainerColor != current.errorContainerColor) changed += "errorContainerColor"
                if (next.onErrorContainerColor != current.onErrorContainerColor) changed += "onErrorContainerColor"
                if (next.successColor != current.successColor) changed += "successColor"
                if (next.warningColor != current.warningColor) changed += "warningColor"
                if (next.infoColor != current.infoColor) changed += "infoColor"
                if (next.accentColor != current.accentColor) changed += "accentColor"
                if (next.sidebarBackgroundColor != current.sidebarBackgroundColor) changed += "sidebarBackgroundColor"
                if (next.sidebarOnBackgroundColor != current.sidebarOnBackgroundColor) changed += "sidebarOnBackgroundColor"
                if (next.sidebarActiveColor != current.sidebarActiveColor) changed += "sidebarActiveColor"
                if (next.sidebarOnActiveColor != current.sidebarOnActiveColor) changed += "sidebarOnActiveColor"
                if (next.cornerRadiusDp != current.cornerRadiusDp) changed += "cornerRadiusDp"
                if (next.spacingMultiplier != current.spacingMultiplier) changed += "spacingMultiplier"

                val summary = if (changed.isEmpty()) "未检测到任何字段变化（输入可能为空或全部无效）。" else "已更新 ${changed.size} 项：${changed.joinToString(", ")}"
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "UI 设置已应用。$summary")
                        })
                    })
                }
            }
            "get_current_time" -> {
                val tzId = arguments.optString("timezone").takeIf { it.isNotBlank() }
                val zone = try {
                    if (tzId != null) java.time.ZoneId.of(tzId) else java.time.ZoneId.systemDefault()
                } catch (e: Exception) {
                    java.time.ZoneId.systemDefault()
                }
                val now = ZonedDateTime.now(zone)
                val fullFmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)", Locale.CHINESE)
                val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                val result = buildString {
                    appendLine("当前时间信息：")
                    appendLine("• 本地时间：${now.format(fullFmt)}")
                    appendLine("• 时区：${zone.id} (UTC${now.format(DateTimeFormatter.ofPattern("xxx"))})")
                    appendLine("• ISO 8601：${now.format(isoFmt)}")
                    appendLine("• Unix 时间戳：${now.toEpochSecond()}")
                }
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", result.trim())
                        })
                    })
                }
            }
            "save_color_scheme" -> {
                val name = arguments.optString("name").trim()
                val desc = arguments.optString("description").trim()
                if (name.isBlank()) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "保存失败：name 不能为空。") })
                        })
                        put("isError", true)
                    }
                }
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val count = repository.getColorSchemePresetCount()
                if (count >= ColorSchemePreset.MAX_PRESETS) {
                    val existing = repository.getAllColorSchemePresets()
                    val list = existing.joinToString("\n") { "• [${it.schemeId}] ${it.name}" }
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "保存失败：已达到最多 ${ColorSchemePreset.MAX_PRESETS} 个方案的上限。\n\n当前已保存的方案：\n$list\n\n请先调用 delete_color_scheme 删除一个不需要的方案，再重试。")
                            })
                        })
                        put("isError", true)
                    }
                }
                val current = repository.getUISettings() ?: UISettings()
                val schemeId = UUID.randomUUID().toString()
                val preset = ColorSchemePreset.fromUISettings(
                    schemeId = schemeId,
                    name = name.take(30),
                    description = desc.take(100),
                    s = current
                )
                repository.insertColorSchemePreset(preset)
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "配色方案「${preset.name}」已保存。\nschemeId: $schemeId\n当前已保存 ${count + 1}/${ColorSchemePreset.MAX_PRESETS} 个方案。")
                        })
                    })
                }
            }
            "list_color_schemes" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val presets = repository.getAllColorSchemePresets()
                if (presets.isEmpty()) {
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "当前没有已保存的配色方案。可以先用 adjust_ui 调整配色，再调用 save_color_scheme 保存。")
                            })
                        })
                    }
                } else {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINESE)
                    val text = buildString {
                        appendLine("已保存的配色方案（${presets.size}/${ColorSchemePreset.MAX_PRESETS}）：")
                        appendLine()
                        presets.forEachIndexed { i, p ->
                            appendLine("${i + 1}. 「${p.name}」")
                            appendLine("   schemeId:    ${p.schemeId}")
                            appendLine("   概述:        ${p.description}")
                            appendLine("   保存时间:    ${sdf.format(Date(p.createdAt))}")
                            appendLine("   主色:        ${p.primaryColor}  背景色: ${p.backgroundColor}")
                            appendLine("   成功色:      ${p.successColor}  圆角: ${p.cornerRadiusDp}dp  间距: ${p.spacingMultiplier}x")
                        }
                    }
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", text.trimEnd()) })
                        })
                    }
                }
            }
            "apply_color_scheme" -> {
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isBlank()) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "应用失败：schemeId 不能为空。请先调用 list_color_schemes 获取可用的 schemeId。") })
                        })
                        put("isError", true)
                    }
                }
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val preset = repository.getColorSchemePresetById(schemeId)
                if (preset == null) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "应用失败：找不到 schemeId=$schemeId 的方案。请调用 list_color_schemes 确认可用的 schemeId。") })
                        })
                        put("isError", true)
                    }
                }
                repository.upsertUISettings(preset.toUISettings())
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "配色方案「${preset.name}」已应用，界面立即生效。\n概述：${preset.description}")
                        })
                    })
                }
            }
            "delete_color_scheme" -> {
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isBlank()) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "删除失败：schemeId 不能为空。") })
                        })
                        put("isError", true)
                    }
                }
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val preset = repository.getColorSchemePresetById(schemeId)
                if (preset == null) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "删除失败：找不到 schemeId=$schemeId 的方案。") })
                        })
                        put("isError", true)
                    }
                }
                repository.deleteColorSchemePreset(schemeId)
                val remaining = repository.getColorSchemePresetCount()
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "配色方案「${preset.name}」已删除。当前剩余 $remaining/${ColorSchemePreset.MAX_PRESETS} 个方案。")
                        })
                    })
                }
            }
            "adjust_font" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()

                val validFontFamilies = setOf("default", "serif", "monospace", "cursive")

                val newFontSizeScale = if (arguments.has("fontSizeScale")) {
                    arguments.optDouble("fontSizeScale", current.fontSizeScale.toDouble())
                        .toFloat().coerceIn(0.75f, 1.5f)
                } else current.fontSizeScale

                val newChatFontSizeScale = if (arguments.has("chatFontSizeScale")) {
                    arguments.optDouble("chatFontSizeScale", current.chatFontSizeScale.toDouble())
                        .toFloat().coerceIn(0.75f, 1.5f)
                } else current.chatFontSizeScale

                val newFontFamily = arguments.optString("fontFamily").trim().lowercase().let {
                    if (it.isNotEmpty() && it in validFontFamilies) it else current.fontFamily
                }

                val next = current.copy(
                    fontSizeScale = newFontSizeScale,
                    chatFontSizeScale = newChatFontSizeScale,
                    fontFamily = newFontFamily,
                    updatedAt = System.currentTimeMillis()
                )
                repository.upsertUISettings(next)

                val changed = mutableListOf<String>()
                if (next.fontSizeScale != current.fontSizeScale) changed += "fontSizeScale: ${current.fontSizeScale} → ${next.fontSizeScale}"
                if (next.chatFontSizeScale != current.chatFontSizeScale) changed += "chatFontSizeScale: ${current.chatFontSizeScale} → ${next.chatFontSizeScale}"
                if (next.fontFamily != current.fontFamily) changed += "fontFamily: \"${current.fontFamily}\" → \"${next.fontFamily}\""

                val summary = if (changed.isEmpty()) {
                    "未检测到任何字段变化（输入可能为空或超出范围）。\n当前值：fontSizeScale=${current.fontSizeScale}, chatFontSizeScale=${current.chatFontSizeScale}, fontFamily=\"${current.fontFamily}\""
                } else {
                    "已更新 ${changed.size} 项：\n${changed.joinToString("\n")}"
                }
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "字体设置已应用。$summary")
                        })
                    })
                }
            }
            "reset_font_to_default" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                val next = current.copy(
                    fontSizeScale = 1.0f,
                    chatFontSizeScale = 1.0f,
                    fontFamily = "default",
                    updatedAt = System.currentTimeMillis()
                )
                repository.upsertUISettings(next)
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "字体设置已恢复为默认：fontSizeScale=1.0，chatFontSizeScale=1.0，fontFamily=\"default\"。")
                        })
                    })
                }
            }
            "search_memory" -> {
                val query = arguments.optString("query").trim()
                if (query.isBlank()) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "搜索失败：query 不能为空。")
                            })
                        })
                        put("isError", true)
                    }
                }
                val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val allMemories = repository.getAllMemories()

                // 将 query 拆分为多个关键词（空格/逗号分隔），全部转小写
                val keywords = query.lowercase()
                    .split(Regex("[\\s,，]+"))
                    .filter { it.isNotBlank() }

                // 对每条记忆计算匹配分：命中的关键词数量 × confidence 加权
                data class ScoredMemory(val memory: com.example.data.MemoryItem, val score: Int)

                val scored = allMemories
                    .mapNotNull { mem ->
                        val lower = mem.content.lowercase()
                        val hitCount = keywords.count { kw -> lower.contains(kw) }
                        if (hitCount > 0) ScoredMemory(mem, hitCount * mem.confidence) else null
                    }
                    .sortedByDescending { it.score }
                    .take(limit)

                val totalCount = allMemories.size
                val text = buildString {
                    appendLine("记忆库搜索结果（关键词：「$query」，共 $totalCount 条记忆，命中 ${scored.size} 条）：")
                    appendLine()
                    if (scored.isEmpty()) {
                        appendLine("未找到与关键词相关的记忆。")
                        appendLine("提示：可以尝试更换关键词，或直接浏览全部记忆（记忆库共 $totalCount 条）。")
                    } else {
                        scored.forEachIndexed { i, sm ->
                            val pinnedTag = if (sm.memory.pinned) " [已锁定]" else ""
                            appendLine("${i + 1}. [id=${sm.memory.id}, 置信度=${sm.memory.confidence}$pinnedTag]")
                            appendLine("   ${sm.memory.content}")
                        }
                    }
                }
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", text.trimEnd())
                        })
                    })
                }
            }
            "adjust_ui_strings" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                val currentStrings = UiStrings.fromJson(current.uiStrings)

                if (arguments.optBoolean("resetToDefault", false)) {
                    repository.upsertUISettings(current.copy(uiStrings = "{}", updatedAt = System.currentTimeMillis()))
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "UI 文字标签已重置为默认中文。")
                            })
                        })
                    }
                }

                // 逐字段合并：只更新 arguments 中明确提供的非空字段
                val d = UiStrings.Default
                fun str(key: String, fallback: String): String {
                    val v = arguments.optString(key)
                    return if (v.isNotBlank()) v else fallback
                }

                val next = UiStrings(
                    topbar_title_chat = str("topbar_title_chat", currentStrings.topbar_title_chat),
                    topbar_title_settings = str("topbar_title_settings", currentStrings.topbar_title_settings),
                    topbar_provider_prefix = str("topbar_provider_prefix", currentStrings.topbar_provider_prefix),
                    topbar_memory_syncing = str("topbar_memory_syncing", currentStrings.topbar_memory_syncing),
                    topbar_menu_open = str("topbar_menu_open", currentStrings.topbar_menu_open),
                    topbar_sync_memory = str("topbar_sync_memory", currentStrings.topbar_sync_memory),
                    nav_chat = str("nav_chat", currentStrings.nav_chat),
                    nav_settings = str("nav_settings", currentStrings.nav_settings),
                    settings_tab_models = str("settings_tab_models", currentStrings.settings_tab_models),
                    settings_tab_mcp = str("settings_tab_mcp", currentStrings.settings_tab_mcp),
                    settings_tab_memory = str("settings_tab_memory", currentStrings.settings_tab_memory),
                    sidebar_title = str("sidebar_title", currentStrings.sidebar_title),
                    sidebar_settings = str("sidebar_settings", currentStrings.sidebar_settings),
                    sidebar_delete_confirm = str("sidebar_delete_confirm", currentStrings.sidebar_delete_confirm),
                    chat_no_provider_warning = str("chat_no_provider_warning", currentStrings.chat_no_provider_warning),
                    chat_memory_injected = str("chat_memory_injected", currentStrings.chat_memory_injected),
                    chat_current_model = str("chat_current_model", currentStrings.chat_current_model),
                    chat_input_hint = str("chat_input_hint", currentStrings.chat_input_hint),
                    chat_send = str("chat_send", currentStrings.chat_send),
                    chat_stop = str("chat_stop", currentStrings.chat_stop),
                    chat_new_session = str("chat_new_session", currentStrings.chat_new_session),
                    chat_thinking = str("chat_thinking", currentStrings.chat_thinking),
                    chat_tool_calling = str("chat_tool_calling", currentStrings.chat_tool_calling),
                    models_empty_hint = str("models_empty_hint", currentStrings.models_empty_hint),
                    models_default_badge = str("models_default_badge", currentStrings.models_default_badge),
                    models_set_default = str("models_set_default", currentStrings.models_set_default),
                    models_set_default_desc = str("models_set_default_desc", currentStrings.models_set_default_desc),
                    models_custom_headers = str("models_custom_headers", currentStrings.models_custom_headers),
                    models_no_headers = str("models_no_headers", currentStrings.models_no_headers),
                    models_add_provider = str("models_add_provider", currentStrings.models_add_provider),
                    models_fetch_models = str("models_fetch_models", currentStrings.models_fetch_models),
                    models_fetch_error_prefix = str("models_fetch_error_prefix", currentStrings.models_fetch_error_prefix),
                    models_list_hint = str("models_list_hint", currentStrings.models_list_hint),
                    models_no_saved_models = str("models_no_saved_models", currentStrings.models_no_saved_models),
                    models_fetch_first = str("models_fetch_first", currentStrings.models_fetch_first),
                    models_memory_model_desc = str("models_memory_model_desc", currentStrings.models_memory_model_desc),
                    mcp_empty_hint = str("mcp_empty_hint", currentStrings.mcp_empty_hint),
                    mcp_empty_desc = str("mcp_empty_desc", currentStrings.mcp_empty_desc),
                    mcp_examples_title = str("mcp_examples_title", currentStrings.mcp_examples_title),
                    mcp_builtin_title = str("mcp_builtin_title", currentStrings.mcp_builtin_title),
                    mcp_builtin_status = str("mcp_builtin_status", currentStrings.mcp_builtin_status),
                    mcp_view_tools = str("mcp_view_tools", currentStrings.mcp_view_tools),
                    mcp_remote_http_support = str("mcp_remote_http_support", currentStrings.mcp_remote_http_support),
                    mcp_import_title = str("mcp_import_title", currentStrings.mcp_import_title),
                    mcp_import_desc = str("mcp_import_desc", currentStrings.mcp_import_desc),
                    mcp_runtime_label = str("mcp_runtime_label", currentStrings.mcp_runtime_label),
                    mcp_auto_start = str("mcp_auto_start", currentStrings.mcp_auto_start),
                    memory_manual_input = str("memory_manual_input", currentStrings.memory_manual_input),
                    memory_empty_hint = str("memory_empty_hint", currentStrings.memory_empty_hint),
                    action_confirm = str("action_confirm", currentStrings.action_confirm),
                    action_cancel = str("action_cancel", currentStrings.action_cancel),
                    action_delete = str("action_delete", currentStrings.action_delete),
                    action_edit = str("action_edit", currentStrings.action_edit),
                    action_save = str("action_save", currentStrings.action_save),
                    action_add = str("action_add", currentStrings.action_add),
                    action_close = str("action_close", currentStrings.action_close),
                    action_reset = str("action_reset", currentStrings.action_reset),
                )

                repository.upsertUISettings(current.copy(uiStrings = next.toJson(), updatedAt = System.currentTimeMillis()))

                // 统计实际变化的字段
                val changed = mutableListOf<String>()
                if (next.topbar_title_chat != currentStrings.topbar_title_chat) changed += "topbar_title_chat"
                if (next.topbar_title_settings != currentStrings.topbar_title_settings) changed += "topbar_title_settings"
                if (next.topbar_provider_prefix != currentStrings.topbar_provider_prefix) changed += "topbar_provider_prefix"
                if (next.topbar_memory_syncing != currentStrings.topbar_memory_syncing) changed += "topbar_memory_syncing"
                if (next.nav_chat != currentStrings.nav_chat) changed += "nav_chat"
                if (next.nav_settings != currentStrings.nav_settings) changed += "nav_settings"
                if (next.settings_tab_models != currentStrings.settings_tab_models) changed += "settings_tab_models"
                if (next.settings_tab_mcp != currentStrings.settings_tab_mcp) changed += "settings_tab_mcp"
                if (next.settings_tab_memory != currentStrings.settings_tab_memory) changed += "settings_tab_memory"
                if (next.sidebar_title != currentStrings.sidebar_title) changed += "sidebar_title"
                if (next.sidebar_settings != currentStrings.sidebar_settings) changed += "sidebar_settings"
                if (next.chat_no_provider_warning != currentStrings.chat_no_provider_warning) changed += "chat_no_provider_warning"
                if (next.chat_input_hint != currentStrings.chat_input_hint) changed += "chat_input_hint"
                if (next.chat_send != currentStrings.chat_send) changed += "chat_send"
                if (next.chat_stop != currentStrings.chat_stop) changed += "chat_stop"
                if (next.chat_new_session != currentStrings.chat_new_session) changed += "chat_new_session"
                if (next.chat_thinking != currentStrings.chat_thinking) changed += "chat_thinking"
                if (next.chat_tool_calling != currentStrings.chat_tool_calling) changed += "chat_tool_calling"
                if (next.models_empty_hint != currentStrings.models_empty_hint) changed += "models_empty_hint"
                if (next.models_default_badge != currentStrings.models_default_badge) changed += "models_default_badge"
                if (next.mcp_empty_hint != currentStrings.mcp_empty_hint) changed += "mcp_empty_hint"
                if (next.mcp_builtin_title != currentStrings.mcp_builtin_title) changed += "mcp_builtin_title"
                if (next.memory_manual_input != currentStrings.memory_manual_input) changed += "memory_manual_input"
                if (next.action_confirm != currentStrings.action_confirm) changed += "action_confirm"
                if (next.action_cancel != currentStrings.action_cancel) changed += "action_cancel"
                if (next.action_delete != currentStrings.action_delete) changed += "action_delete"
                if (next.action_save != currentStrings.action_save) changed += "action_save"

                val summary = if (changed.isEmpty()) {
                    "未检测到任何字段变化（输入可能为空）。"
                } else {
                    "已更新 ${changed.size} 项：${changed.joinToString(", ")}"
                }
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "UI 文字标签已应用，界面立即生效。$summary")
                        })
                    })
                }
            }
            "reset_ui_strings" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                repository.upsertUISettings(current.copy(uiStrings = "{}", updatedAt = System.currentTimeMillis()))
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "UI 文字标签已全部恢复为默认中文。")
                        })
                    })
                }
            }
            "get_ui_strings" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                val strings = UiStrings.fromJson(current.uiStrings)
                val d = UiStrings.Default
                val text = buildString {
                    appendLine("=== 当前 UI 文字标签清单（用于配合 adjust_ui_strings 工具使用）===")
                    appendLine()
                    appendLine("用法：调用 adjust_ui_strings 时只需传想改的字段；未传字段保持当前值。")
                    appendLine("含 %s / %d 的字段为格式化字符串，请保留占位符。")
                    appendLine()
                    appendLine("── 顶部导航栏 ──")
                    appendLine("• topbar_title_chat        当前: \"${strings.topbar_title_chat}\"  默认: \"${d.topbar_title_chat}\"")
                    appendLine("• topbar_title_settings    当前: \"${strings.topbar_title_settings}\"  默认: \"${d.topbar_title_settings}\"")
                    appendLine("• topbar_provider_prefix   当前: \"${strings.topbar_provider_prefix}\"  默认: \"${d.topbar_provider_prefix}\"")
                    appendLine("• topbar_memory_syncing    当前: \"${strings.topbar_memory_syncing}\"  默认: \"${d.topbar_memory_syncing}\"")
                    appendLine()
                    appendLine("── 底部导航 ──")
                    appendLine("• nav_chat                 当前: \"${strings.nav_chat}\"  默认: \"${d.nav_chat}\"")
                    appendLine("• nav_settings             当前: \"${strings.nav_settings}\"  默认: \"${d.nav_settings}\"")
                    appendLine()
                    appendLine("── 设置子 Tab ──")
                    appendLine("• settings_tab_models      当前: \"${strings.settings_tab_models}\"  默认: \"${d.settings_tab_models}\"")
                    appendLine("• settings_tab_mcp         当前: \"${strings.settings_tab_mcp}\"  默认: \"${d.settings_tab_mcp}\"")
                    appendLine("• settings_tab_memory      当前: \"${strings.settings_tab_memory}\"  默认: \"${d.settings_tab_memory}\"")
                    appendLine()
                    appendLine("── 侧边栏 ──")
                    appendLine("• sidebar_title            当前: \"${strings.sidebar_title}\"  默认: \"${d.sidebar_title}\"")
                    appendLine("• sidebar_settings         当前: \"${strings.sidebar_settings}\"  默认: \"${d.sidebar_settings}\"")
                    appendLine()
                    appendLine("── 聊天界面 ──")
                    appendLine("• chat_no_provider_warning 当前: \"${strings.chat_no_provider_warning}\"")
                    appendLine("• chat_memory_injected     当前: \"${strings.chat_memory_injected}\"  (含 %d 占位符)")
                    appendLine("• chat_input_hint          当前: \"${strings.chat_input_hint}\"  默认: \"${d.chat_input_hint}\"")
                    appendLine("• chat_send                当前: \"${strings.chat_send}\"  默认: \"${d.chat_send}\"")
                    appendLine("• chat_stop                当前: \"${strings.chat_stop}\"  默认: \"${d.chat_stop}\"")
                    appendLine("• chat_new_session         当前: \"${strings.chat_new_session}\"  默认: \"${d.chat_new_session}\"")
                    appendLine("• chat_thinking            当前: \"${strings.chat_thinking}\"  默认: \"${d.chat_thinking}\"")
                    appendLine("• chat_tool_calling        当前: \"${strings.chat_tool_calling}\"  默认: \"${d.chat_tool_calling}\"")
                    appendLine()
                    appendLine("── 模型配置页 ──")
                    appendLine("• models_empty_hint        当前: \"${strings.models_empty_hint}\"")
                    appendLine("• models_default_badge     当前: \"${strings.models_default_badge}\"  默认: \"${d.models_default_badge}\"")
                    appendLine("• models_set_default       当前: \"${strings.models_set_default}\"  默认: \"${d.models_set_default}\"")
                    appendLine("• models_add_provider      当前: \"${strings.models_add_provider}\"  默认: \"${d.models_add_provider}\"")
                    appendLine()
                    appendLine("── MCP 配置页 ──")
                    appendLine("• mcp_empty_hint           当前: \"${strings.mcp_empty_hint}\"  默认: \"${d.mcp_empty_hint}\"")
                    appendLine("• mcp_builtin_title        当前: \"${strings.mcp_builtin_title}\"  默认: \"${d.mcp_builtin_title}\"")
                    appendLine("• mcp_builtin_status       当前: \"${strings.mcp_builtin_status}\"  默认: \"${d.mcp_builtin_status}\"")
                    appendLine("• mcp_view_tools           当前: \"${strings.mcp_view_tools}\"  默认: \"${d.mcp_view_tools}\"")
                    appendLine()
                    appendLine("── 长效记忆页 ──")
                    appendLine("• memory_manual_input      当前: \"${strings.memory_manual_input}\"")
                    appendLine("• memory_empty_hint        当前: \"${strings.memory_empty_hint}\"")
                    appendLine()
                    appendLine("── 通用操作 ──")
                    appendLine("• action_confirm           当前: \"${strings.action_confirm}\"  默认: \"${d.action_confirm}\"")
                    appendLine("• action_cancel            当前: \"${strings.action_cancel}\"  默认: \"${d.action_cancel}\"")
                    appendLine("• action_delete            当前: \"${strings.action_delete}\"  默认: \"${d.action_delete}\"")
                    appendLine("• action_edit              当前: \"${strings.action_edit}\"  默认: \"${d.action_edit}\"")
                    appendLine("• action_save              当前: \"${strings.action_save}\"  默认: \"${d.action_save}\"")
                    appendLine("• action_add               当前: \"${strings.action_add}\"  默认: \"${d.action_add}\"")
                    appendLine("• action_close             当前: \"${strings.action_close}\"  默认: \"${d.action_close}\"")
                    appendLine("• action_reset             当前: \"${strings.action_reset}\"  默认: \"${d.action_reset}\"")
                }
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", text.trimEnd())
                        })
                    })
                }
            }
            else -> JSONObject().apply {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "未知的内置工具: $toolName")
                    })
                })
                put("isError", true)
            }
        }
    }

    private fun buildUiCapabilitiesResponse(current: UISettings): JSONObject {
        // 用 LinkedHashMap 维持顺序
        data class Field(val name: String, val currentValue: Any?, val purpose: String, val constraint: String)

        val colorFields = listOf(
            Field("primaryColor", current.primaryColor, "主色调（按钮、链接、选中态、品牌色）", "HEX #RRGGBB(AA)"),
            Field("onPrimaryColor", current.onPrimaryColor, "主色之上的文字与图标颜色（应与 primaryColor 形成 ≥4.5:1 对比度）", "HEX #RRGGBB(AA)"),
            Field("primaryContainerColor", current.primaryContainerColor, "主色容器（如默认提供商徽章、激活会话项背景）", "HEX #RRGGBB(AA)"),
            Field("onPrimaryContainerColor", current.onPrimaryContainerColor, "主色容器上的文字色", "HEX #RRGGBB(AA)"),
            Field("secondaryColor", current.secondaryColor, "次要色调", "HEX #RRGGBB(AA)"),
            Field("onSecondaryColor", current.onSecondaryColor, "次色上的文字色", "HEX #RRGGBB(AA)"),
            Field("secondaryContainerColor", current.secondaryContainerColor, "次色容器", "HEX #RRGGBB(AA)"),
            Field("onSecondaryContainerColor", current.onSecondaryContainerColor, "次色容器上的文字色", "HEX #RRGGBB(AA)"),
            Field("tertiaryColor", current.tertiaryColor, "第三色（强调点缀、特殊徽章）", "HEX #RRGGBB(AA)"),
            Field("onTertiaryColor", current.onTertiaryColor, "第三色上的文字色", "HEX #RRGGBB(AA)"),
            Field("backgroundColor", current.backgroundColor, "整页背景色", "HEX #RRGGBB(AA)"),
            Field("onBackgroundColor", current.onBackgroundColor, "背景上的正文文字色", "HEX #RRGGBB(AA)"),
            Field("surfaceColor", current.surfaceColor, "卡片、对话框、TopAppBar、输入框等表面颜色", "HEX #RRGGBB(AA)"),
            Field("onSurfaceColor", current.onSurfaceColor, "表面上的主要文字色（标题、正文）", "HEX #RRGGBB(AA)"),
            Field("surfaceVariantColor", current.surfaceVariantColor, "次级表面（思考面板背景、聚合工具消息背景、工具栏抽屉）", "HEX #RRGGBB(AA)"),
            Field("onSurfaceVariantColor", current.onSurfaceVariantColor, "次级表面上的辅助文字色", "HEX #RRGGBB(AA)"),
            Field("outlineColor", current.outlineColor, "边框、描边主色", "HEX #RRGGBB(AA)"),
            Field("outlineVariantColor", current.outlineVariantColor, "更轻的分隔线和边框色", "HEX #RRGGBB(AA)"),
            Field("errorColor", current.errorColor, "错误状态色（删除按钮、错误提示文字、危险操作）", "HEX #RRGGBB(AA)"),
            Field("onErrorColor", current.onErrorColor, "错误色上的文字色", "HEX #RRGGBB(AA)"),
            Field("errorContainerColor", current.errorContainerColor, "错误容器背景（提示气泡）", "HEX #RRGGBB(AA)"),
            Field("onErrorContainerColor", current.onErrorContainerColor, "错误容器内的文字色", "HEX #RRGGBB(AA)"),
            Field("successColor", current.successColor, "成功色（运行中状态、记忆同步成功、绿色徽章）", "HEX #RRGGBB(AA)"),
            Field("warningColor", current.warningColor, "警告色（启动中状态、警告提示）", "HEX #RRGGBB(AA)"),
            Field("infoColor", current.infoColor, "信息色（视觉能力徽章等蓝色点缀）", "HEX #RRGGBB(AA)"),
            Field("accentColor", current.accentColor, "强调色（思考过程中的星标等橙色点缀）", "HEX #RRGGBB(AA)"),
            Field("sidebarBackgroundColor", current.sidebarBackgroundColor, "侧边栏背景色", "HEX #RRGGBB(AA)"),
            Field("sidebarOnBackgroundColor", current.sidebarOnBackgroundColor, "侧边栏文字与辅助图标颜色", "HEX #RRGGBB(AA)"),
            Field("sidebarActiveColor", current.sidebarActiveColor, "侧边栏激活项背景色", "HEX #RRGGBB(AA)"),
            Field("sidebarOnActiveColor", current.sidebarOnActiveColor, "侧边栏激活项文字与图标颜色", "HEX #RRGGBB(AA)"),
        )

        val layoutFields = listOf(
            Field("cornerRadiusDp", current.cornerRadiusDp, "全局圆角大小（dp）。影响所有卡片、按钮、对话框、徽章圆角", "整数 0-32，默认 12"),
            Field("spacingMultiplier", current.spacingMultiplier, "全局间距倍数。1.0=默认，>1 更宽松，<1 更紧凑", "浮点数 0.5-2.0，默认 1.0"),
        )

        val fontFields = listOf(
            Field("fontSizeScale", current.fontSizeScale, "全局 UI 字体大小缩放比例。影响标题、按钮、标签等所有 UI 文字（聊天气泡除外）", "浮点数 0.75-1.5，默认 1.0"),
            Field("chatFontSizeScale", current.chatFontSizeScale, "聊天气泡正文字体大小缩放比例。独立于全局缩放，可单独调大聊天内容字号", "浮点数 0.75-1.5，默认 1.0"),
            Field("fontFamily", current.fontFamily, "字体族。\"default\"=系统默认(Roboto)，\"serif\"=衬线字体，\"monospace\"=等宽字体，\"cursive\"=手写风格", "\"default\" | \"serif\" | \"monospace\" | \"cursive\""),
        )

        val text = buildString {
            appendLine("=== 应用 UI 主题能力清单（用于配合 adjust_ui 工具使用）===")
            appendLine()
            appendLine("总览：当前主题已被自定义=${current.updatedAt > 0}，最后修改时间戳=${current.updatedAt}")
            appendLine("用法：调用 adjust_ui 时只需要传想改的字段；未传字段保持当前值。")
            appendLine()
            appendLine("== 颜色字段（共 ${colorFields.size} 个） ==")
            colorFields.forEach { f ->
                appendLine("• ${f.name}")
                appendLine("    当前值: ${f.currentValue}")
                appendLine("    用途:   ${f.purpose}")
                appendLine("    约束:   ${f.constraint}")
            }
            appendLine()
            appendLine("== 布局字段（共 ${layoutFields.size} 个） ==")
            layoutFields.forEach { f ->
                appendLine("• ${f.name}")
                appendLine("    当前值: ${f.currentValue}")
                appendLine("    用途:   ${f.purpose}")
                appendLine("    约束:   ${f.constraint}")
            }
            appendLine()
            appendLine("== 字体字段（共 ${fontFields.size} 个，通过 adjust_font / reset_font_to_default 工具修改） ==")
            fontFields.forEach { f ->
                appendLine("• ${f.name}")
                appendLine("    当前值: ${f.currentValue}")
                appendLine("    用途:   ${f.purpose}")
                appendLine("    约束:   ${f.constraint}")
            }
            appendLine()
            appendLine("== 重置 ==")
            appendLine("调用 reset_ui_to_default 或在 adjust_ui 中传 resetToDefault=true 可还原所有颜色/布局字段为默认。")
            appendLine("调用 reset_font_to_default 可单独还原字体设置为默认。")
            appendLine()
            appendLine("== 推荐做法 ==")
            appendLine("1. 修改 primary 时，记得同步更新 onPrimary（保证文字可读）。primaryContainer / onPrimaryContainer 同理成对调整。")
            appendLine("2. 浅色主题：background/surface 用接近 #FFFFFF 的浅色，onBackground/onSurface 用近黑色。")
            appendLine("   深色主题：background/surface 用接近 #121212 的深色，onBackground/onSurface 用近白色。")
            appendLine("3. surfaceVariant 应介于 surface 和 background 之间，用作次级容器。")
            appendLine("4. 状态色（success/warning/info/accent）建议保持高饱和度，无须配套 'on*' 颜色（系统会用图标着色）。")
            appendLine("5. 一次调整可同时传多个字段，避免分多次调用。")
            appendLine("6. 字体调整建议：fontSizeScale 和 chatFontSizeScale 超过 1.3 可能导致部分 UI 文字截断，建议先小幅调整。")
        }

        // 同时把结构化数据放在 JSON 中，便于 AI 程序化解析
        val structured = JSONObject().apply {
            put("hasUserOverride", current.updatedAt > 0)
            put("updatedAt", current.updatedAt)
            put("colorFields", JSONArray().apply {
                colorFields.forEach { f ->
                    put(JSONObject().apply {
                        put("name", f.name)
                        put("currentValue", f.currentValue?.toString())
                        put("purpose", f.purpose)
                        put("constraint", f.constraint)
                    })
                }
            })
            put("layoutFields", JSONArray().apply {
                layoutFields.forEach { f ->
                    put(JSONObject().apply {
                        put("name", f.name)
                        put("currentValue", f.currentValue)
                        put("purpose", f.purpose)
                        put("constraint", f.constraint)
                    })
                }
            })
            put("fontFields", JSONArray().apply {
                fontFields.forEach { f ->
                    put(JSONObject().apply {
                        put("name", f.name)
                        put("currentValue", f.currentValue)
                        put("purpose", f.purpose)
                        put("constraint", f.constraint)
                    })
                }
            })
        }

        return JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text.trimEnd())
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "JSON_DATA: " + structured.toString())
                })
            })
        }
    }
}
