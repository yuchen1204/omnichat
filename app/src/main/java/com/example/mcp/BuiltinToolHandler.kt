package com.example.mcp

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.UISettings
import com.example.data.ColorSchemePreset
import com.example.data.ColorSchemePreset.Companion.toUISettings
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
            appendLine("== 重置 ==")
            appendLine("调用 reset_ui_to_default 或在 adjust_ui 中传 resetToDefault=true 可还原所有字段为默认。")
            appendLine()
            appendLine("== 推荐做法 ==")
            appendLine("1. 修改 primary 时，记得同步更新 onPrimary（保证文字可读）。primaryContainer / onPrimaryContainer 同理成对调整。")
            appendLine("2. 浅色主题：background/surface 用接近 #FFFFFF 的浅色，onBackground/onSurface 用近黑色。")
            appendLine("   深色主题：background/surface 用接近 #121212 的深色，onBackground/onSurface 用近白色。")
            appendLine("3. surfaceVariant 应介于 surface 和 background 之间，用作次级容器。")
            appendLine("4. 状态色（success/warning/info/accent）建议保持高饱和度，无须配套 'on*' 颜色（系统会用图标着色）。")
            appendLine("5. 一次调整可同时传多个字段，避免分多次调用。")
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
