package com.example.mcp

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.UISettings
import com.example.data.ColorSchemePreset
import com.example.data.ColorSchemePreset.Companion.toUISettings
import com.example.ui.theme.UiStrings
import com.example.ui.theme.UiStrings.Companion.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

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
            "set_ui_texts" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                val currentStrings = UiStrings.fromJson(current.uiStrings)

                if (arguments.optBoolean("resetAll", false)) {
                    repository.upsertUISettings(current.copy(uiStrings = "{}", updatedAt = System.currentTimeMillis()))
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "已重置全部 UI 文字标签为默认中文。")
                            })
                        })
                    }
                }

                val updates = arguments.optJSONObject("updates")
                val deletes = arguments.optJSONArray("delete")
                if (updates == null && deletes == null) {
                    return JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "调用失败：必须提供 updates（要设置的键值对）或 delete（要删除的 key 列表）至少一项，或传 resetAll=true 重置全部。")
                            })
                        })
                        put("isError", true)
                    }
                }

                val merged = currentStrings.overrides.toMutableMap()
                val applied = mutableListOf<String>()
                val removed = mutableListOf<String>()

                if (updates != null) {
                    val it = updates.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = updates.optString(k)
                        if (v.isNotEmpty()) {
                            merged[k] = v
                            applied += "$k = \"$v\""
                        }
                    }
                }
                if (deletes != null) {
                    for (i in 0 until deletes.length()) {
                        val k = deletes.optString(i)
                        if (k.isNotEmpty() && merged.remove(k) != null) {
                            removed += k
                        }
                    }
                }

                val newJson = UiStrings(merged).toJson()
                repository.upsertUISettings(current.copy(uiStrings = newJson, updatedAt = System.currentTimeMillis()))

                val text = buildString {
                    appendLine("UI 文字已更新，界面立即生效。")
                    if (applied.isNotEmpty()) {
                        appendLine()
                        appendLine("已设置 ${applied.size} 项：")
                        applied.forEach { appendLine("  • $it") }
                    }
                    if (removed.isNotEmpty()) {
                        appendLine()
                        appendLine("已恢复默认（删除覆盖）${removed.size} 项：${removed.joinToString(", ")}")
                    }
                    if (applied.isEmpty() && removed.isEmpty()) {
                        appendLine()
                        appendLine("未检测到任何变化（输入可能为空）。")
                    }
                    appendLine()
                    appendLine("当前共有 ${merged.size} 个被覆盖的 key。")
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
            "list_ui_texts" -> {
                val db = AppDatabase.getDatabase(context)
                val repository = AppRepository(db)
                val current = repository.getUISettings() ?: UISettings()
                val strings = UiStrings.fromJson(current.uiStrings)
                
                val allKeys = try {
                    context.assets.open("ui_text_keys.json").use { input ->
                        val jsonStr = input.bufferedReader().use { it.readText() }
                        val obj = JSONObject(jsonStr)
                        val map = mutableMapOf<String, String>()
                        obj.keys().forEach { key ->
                            map[key] = obj.getString(key)
                        }
                        map
                    }
                } catch (e: Exception) {
                    emptyMap<String, String>()
                }

                val query = arguments.optString("query").trim()
                val hasQuery = query.isNotEmpty()
                
                val text = buildString {
                    appendLine("=== App 可调整/翻译的 UI 文字列表 ===")
                    if (hasQuery) {
                        appendLine("过滤关键词：「$query」")
                    } else {
                        appendLine("提示：当前返回所有文字。可以调用 list_ui_texts 时提供 query 参数进行模糊匹配过滤。")
                    }
                    appendLine("格式说明：【Key】 = \"默认值\" -> 如果有覆盖则显示 [当前覆盖: \"新值\"]")
                    appendLine()
                    
                    val unionKeys = (allKeys.keys + strings.overrides.keys).sorted()
                    var matchCount = 0
                    
                    unionKeys.forEach { key ->
                        val defaultText = allKeys[key] ?: ""
                        val overrideText = strings.overrides[key]
                        
                        val matchesQuery = !hasQuery || 
                            key.contains(query, ignoreCase = true) || 
                            defaultText.contains(query, ignoreCase = true) ||
                            (overrideText != null && overrideText.contains(query, ignoreCase = true))
                            
                        if (matchesQuery) {
                            matchCount++
                            if (overrideText != null) {
                                appendLine("• Key: $key")
                                appendLine("  默认: \"$defaultText\"")
                                appendLine("  当前已覆盖为: \"$overrideText\"")
                            } else {
                                appendLine("• Key: $key")
                                appendLine("  默认: \"$defaultText\"")
                            }
                            appendLine()
                        }
                    }
                    
                    appendLine("== 统计 ==")
                    if (hasQuery) {
                        appendLine("符合过滤条件的文字：$matchCount / ${unionKeys.size} 项")
                    } else {
                        appendLine("全部可调整的文字：${unionKeys.size} 项")
                    }
                    appendLine()
                    appendLine("== 提示 ==")
                    appendLine("• 修改/翻译某些 key：set_ui_texts({\"updates\": {\"key1\": \"new1\", \"key2\": \"new2\"}})")
                    appendLine("• 恢复某些 key 为默认：set_ui_texts({\"delete\": [\"key1\", \"key2\"]})")
                    appendLine("• 一键全部重置：set_ui_texts({\"resetAll\": true})")
                }
                
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", text)
                        })
                    })
                }
            }
            // ── 文件系统工具 ──────────────────────────────────────────────
            "file_write" -> {
                val relativePath = arguments.optString("path").trim()
                val content = arguments.optString("content")
                val encoding = arguments.optString("encoding", "utf8")
                if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
                val file = resolveSafePath(context, relativePath)
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                try {
                    file.parentFile?.mkdirs()
                    if (encoding == "base64") {
                        val bytes = Base64.decode(content, Base64.DEFAULT)
                        file.writeBytes(bytes)
                    } else {
                        file.writeText(content, Charsets.UTF_8)
                    }
                    successResponse("文件已写入：${file.absolutePath}\n大小：${file.length()} 字节")
                } catch (e: Exception) {
                    errorResponse("写入文件失败：${e.localizedMessage}")
                }
            }
            "file_read" -> {
                val relativePath = arguments.optString("path").trim()
                val encoding = arguments.optString("encoding", "utf8")
                val maxBytes = arguments.optInt("maxBytes", 1024 * 1024).coerceIn(1, 10 * 1024 * 1024)
                if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
                val file = resolveSafePath(context, relativePath)
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                if (!file.exists()) return errorResponse("文件不存在：$relativePath")
                if (!file.isFile) return errorResponse("路径指向的不是文件：$relativePath")
                try {
                    val bytes = file.inputStream().use { stream ->
                        val buf = ByteArray(maxBytes)
                        val read = stream.read(buf)
                        if (read <= 0) ByteArray(0) else buf.copyOf(read)
                    }
                    val truncated = file.length() > maxBytes
                    val resultText = if (encoding == "base64") {
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                    val suffix = if (truncated) "\n\n[文件已截断，仅显示前 $maxBytes 字节，完整大小：${file.length()} 字节]" else ""
                    successResponse(resultText + suffix)
                } catch (e: Exception) {
                    errorResponse("读取文件失败：${e.localizedMessage}")
                }
            }
            "file_append" -> {
                val relativePath = arguments.optString("path").trim()
                val content = arguments.optString("content")
                if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
                val file = resolveSafePath(context, relativePath)
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                try {
                    file.parentFile?.mkdirs()
                    val needsNewline = file.exists() && file.length() > 0 && !file.readText(Charsets.UTF_8).endsWith("\n")
                    file.appendText(if (needsNewline) "\n$content" else content, Charsets.UTF_8)
                    successResponse("内容已追加到：${file.absolutePath}\n当前文件大小：${file.length()} 字节")
                } catch (e: Exception) {
                    errorResponse("追加文件失败：${e.localizedMessage}")
                }
            }
            "file_delete" -> {
                val relativePath = arguments.optString("path").trim()
                val recursive = arguments.optBoolean("recursive", false)
                if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
                val file = resolveSafePath(context, relativePath)
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                if (!file.exists()) return errorResponse("路径不存在：$relativePath")
                try {
                    val success = if (recursive) deleteRecursive(file) else file.delete()
                    if (success) successResponse("已删除：${file.absolutePath}")
                    else errorResponse("删除失败，目录可能不为空（如需递归删除请传 recursive=true）。")
                } catch (e: Exception) {
                    errorResponse("删除失败：${e.localizedMessage}")
                }
            }
            "file_list" -> {
                val relativePath = arguments.optString("path", "").trim()
                val showHidden = arguments.optBoolean("showHidden", false)
                val dir = resolveSafePath(context, relativePath.ifEmpty { "." })
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                if (!dir.exists()) return errorResponse("目录不存在：${relativePath.ifEmpty { "(根目录)" }}")
                if (!dir.isDirectory) return errorResponse("路径指向的不是目录：$relativePath")
                val entries = dir.listFiles()
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val text = buildString {
                    appendLine("目录：${dir.absolutePath}")
                    appendLine("共 ${entries.size} 项")
                    appendLine()
                    if (entries.isEmpty()) {
                        appendLine("（空目录）")
                    } else {
                        entries.forEach { entry ->
                            val type = if (entry.isDirectory) "📁" else "📄"
                            val size = if (entry.isFile) " (${entry.length()} B)" else ""
                            val modified = sdf.format(Date(entry.lastModified()))
                            appendLine("$type ${entry.name}$size  [$modified]")
                        }
                    }
                }
                successResponse(text.trimEnd())
            }
            "file_search" -> {
                val namePattern = arguments.optString("namePattern").trim().ifEmpty { null }
                val contentQuery = arguments.optString("contentQuery").trim().ifEmpty { null }
                val directory = arguments.optString("directory").trim()
                val maxResults = arguments.optInt("maxResults", 20).coerceIn(1, 100)
                if (namePattern == null && contentQuery == null) {
                    return errorResponse("请至少提供 namePattern 或 contentQuery 之一。")
                }
                val searchRoot = resolveSafePath(context, directory.ifEmpty { "." })
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                if (!searchRoot.exists()) return errorResponse("搜索目录不存在：${directory.ifEmpty { "(根目录)" }}")
                val results = mutableListOf<JSONObject>()
                searchFiles(searchRoot, namePattern, contentQuery, results, maxResults)
                val filesRoot = getFilesRoot(context)
                val text = buildString {
                    appendLine("搜索范围：${searchRoot.absolutePath}")
                    if (namePattern != null) appendLine("文件名模式：$namePattern")
                    if (contentQuery != null) appendLine("内容关键词：$contentQuery")
                    appendLine("找到 ${results.size} 个结果${if (results.size >= maxResults) "（已达上限 $maxResults）" else ""}：")
                    appendLine()
                    results.forEach { r ->
                        val absPath = r.optString("path")
                        val relPath = try { File(absPath).relativeTo(filesRoot).path } catch (_: Exception) { absPath }
                        append("• $relPath")
                        val matchLines = r.optJSONArray("matchLines")
                        if (matchLines != null && matchLines.length() > 0) {
                            val lines = (0 until matchLines.length()).map { matchLines.getInt(it) }
                            append("  (匹配行: ${lines.joinToString(", ")})")
                        }
                        appendLine()
                    }
                }
                successResponse(text.trimEnd())
            }
            "file_info" -> {
                val relativePath = arguments.optString("path").trim()
                if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
                val file = resolveSafePath(context, relativePath)
                    ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
                if (!file.exists()) return errorResponse("路径不存在：$relativePath")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val ext = file.extension.lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val text = buildString {
                    appendLine("路径：${file.absolutePath}")
                    appendLine("类型：${if (file.isDirectory) "目录" else "文件"}")
                    if (file.isFile) {
                        appendLine("大小：${file.length()} 字节 (${String.format("%.2f", file.length() / 1024.0)} KB)")
                        appendLine("MIME 类型：$mimeType")
                    } else {
                        val childCount = file.listFiles()?.size ?: 0
                        appendLine("子项数量：$childCount")
                    }
                    appendLine("最后修改：${sdf.format(Date(file.lastModified()))}")
                    appendLine("可读：${file.canRead()}")
                    appendLine("可写：${file.canWrite()}")
                }
                successResponse(text.trimEnd())
            }
            "file_move" -> {
                val srcPath = arguments.optString("sourcePath").trim()
                val dstPath = arguments.optString("destinationPath").trim()
                val overwrite = arguments.optBoolean("overwrite", false)
                if (srcPath.isEmpty()) return errorResponse("参数 'sourcePath' 不能为空。")
                if (dstPath.isEmpty()) return errorResponse("参数 'destinationPath' 不能为空。")
                val src = resolveSafePath(context, srcPath)
                    ?: return errorResponse("非法源路径：路径不能包含 '..' 或超出沙盒范围。")
                val dst = resolveSafePath(context, dstPath)
                    ?: return errorResponse("非法目标路径：路径不能包含 '..' 或超出沙盒范围。")
                if (!src.exists()) return errorResponse("源路径不存在：$srcPath")
                if (dst.exists() && !overwrite) return errorResponse("目标路径已存在：$dstPath（如需覆盖请传 overwrite=true）。")
                try {
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.delete()
                    val success = src.renameTo(dst)
                    if (success) {
                        successResponse("已移动：\n  从：${src.absolutePath}\n  到：${dst.absolutePath}")
                    } else {
                        // renameTo 跨文件系统可能失败，回退到复制+删除
                        src.copyRecursively(dst, overwrite = true)
                        deleteRecursive(src)
                        successResponse("已移动（复制+删除）：\n  从：${src.absolutePath}\n  到：${dst.absolutePath}")
                    }
                } catch (e: Exception) {
                    errorResponse("移动失败：${e.localizedMessage}")
                }
            }
            "ask_user" -> {
                val question = arguments.optString("question").trim()
                if (question.isEmpty()) {
                    return errorResponse("参数 'question' 不能为空。")
                }
                val optionsArray = arguments.optJSONArray("options")
                val options = mutableListOf<String>()
                if (optionsArray != null) {
                    for (i in 0 until optionsArray.length()) {
                        val opt = optionsArray.optString(i).trim()
                        if (opt.isNotEmpty()) {
                            options.add(opt)
                        }
                    }
                }
                val response = AskUserManager.askUser(question, options)
                JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", response)
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

    // ── 文件系统工具辅助函数 ──────────────────────────────────────────────

    /**
     * 返回 OmniChat/files/ 根目录，并确保它存在。
     * 所有文件工具的路径都相对于此目录。
     */
    private fun getFilesRoot(context: Context): File {
        val externalDir = Environment.getExternalStorageDirectory()
        val root = File(externalDir, "OmniChat/files")
        if (!root.exists()) root.mkdirs()
        return root
    }

    /**
     * 将用户提供的相对路径解析为绝对路径，并验证它在沙盒内。
     * 拒绝包含 ".." 的路径以防止目录遍历攻击。
     * @return 解析后的 File，或 null（路径非法时）
     */
    private fun resolveSafePath(context: Context, relativePath: String): File? {
        if (relativePath.contains("..")) return null
        val root = getFilesRoot(context)
        val normalized = relativePath.trimStart('/', '\\').ifEmpty { "." }
        val resolved = File(root, normalized).canonicalFile
        // 确保解析后的路径仍在沙盒内
        return if (resolved.canonicalPath.startsWith(root.canonicalPath)) resolved else null
    }

    /** 递归删除目录 */
    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    /** 递归搜索文件 */
    private fun searchFiles(
        dir: File,
        namePattern: String?,
        contentQuery: String?,
        results: MutableList<JSONObject>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (results.size >= maxResults) break
            if (entry.isDirectory) {
                searchFiles(entry, namePattern, contentQuery, results, maxResults)
            } else {
                val nameMatch = namePattern == null || matchesGlob(entry.name, namePattern)
                if (!nameMatch) continue
                if (contentQuery != null) {
                    // 只搜索文本文件（< 2MB）
                    if (entry.length() > 2 * 1024 * 1024) continue
                    val text = try { entry.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
                    if (!text.contains(contentQuery, ignoreCase = true)) continue
                    // 找到匹配行
                    val lines = text.lines()
                    val matchLines = lines.mapIndexedNotNull { idx, line ->
                        if (line.contains(contentQuery, ignoreCase = true)) idx + 1 else null
                    }.take(3)
                    results.add(JSONObject().apply {
                        put("path", entry.path)
                        put("matchLines", JSONArray(matchLines))
                    })
                } else {
                    results.add(JSONObject().apply { put("path", entry.path) })
                }
            }
        }
    }

    /** 简单 glob 匹配（仅支持 * 和 ?） */
    private fun matchesGlob(name: String, pattern: String): Boolean {
        val regex = buildString {
            append("(?i)^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append("$")
        }
        return name.matches(Regex(regex))
    }

    /** 构造统一的成功响应 */
    private fun successResponse(text: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
    }

    /** 构造统一的错误响应 */
    private fun errorResponse(message: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", message)
            })
        })
        put("isError", true)
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
