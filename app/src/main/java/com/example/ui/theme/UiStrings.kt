package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject

/**
 * AI 可调整的 UI 文字标签字典。
 *
 * 设计原则：
 * - 不预定义字段，任何 UI 字符串都能在调用处通过 `uiText(key, default)` 注册并被 AI 覆盖
 * - 每条 UI 字符串在代码中以 `uiText("scope.key", "默认中文")` 的形式出现
 * - AI 通过 `set_ui_texts({"scope.key": "Chat"})` 工具覆盖任意 key，未覆盖的 key 自动回退到默认中文
 * - [overrides] 由 [UISettings.uiStrings] 反序列化得到，并通过 [LocalUiStrings] 注入整个 Compose 树
 *
 * 命名约定：用 `.` 分隔的小写命名空间，例如：
 *   topbar.title.chat / sidebar.title / chat.input.hint / action.confirm / dialog.delete.title
 */
data class UiStrings(
    /** key → AI 自定义文本。仅包含 AI 主动覆盖过的 key，未覆盖的 key 回退到调用处的默认值。 */
    val overrides: Map<String, String> = emptyMap()
) {
    /**
     * 取一个 key 的当前显示文本：有覆盖则返回覆盖值，否则返回 [defaultText]。
     */
    fun text(key: String, defaultText: String): String = overrides[key] ?: defaultText

    companion object {
        val Default = UiStrings()

        /** 从 JSON 对象字符串反序列化。空字符串/`{}`/格式错误均返回 [Default]。 */
        fun fromJson(json: String): UiStrings {
            if (json.isBlank()) return Default
            return try {
                val obj = JSONObject(json)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key ->
                    val value = obj.optString(key)
                    if (value.isNotEmpty()) map[key] = value
                }
                if (map.isEmpty()) Default else UiStrings(map)
            } catch (e: Exception) {
                Default
            }
        }

        /** 将 [UiStrings.overrides] 序列化为 JSON 字符串。 */
        fun UiStrings.toJson(): String {
            val obj = JSONObject()
            overrides.forEach { (k, v) -> obj.put(k, v) }
            return obj.toString()
        }
    }
}

/** CompositionLocal，在 [MyApplicationTheme] 中注入。 */
val LocalUiStrings = staticCompositionLocalOf { UiStrings.Default }

/**
 * Compose 中获取一个 UI 文本：有 AI 覆盖时返回覆盖值，否则返回默认值。
 *
 * 用法：把硬编码的 `Text("会话")` 改为 `Text(uiText("topbar.title.chat", "会话"))`。
 *
 * 推荐 key 命名空间约定（仅约定，AI 可自由使用任意 key）：
 *   topbar.*    顶部栏
 *   sidebar.*   侧边栏
 *   nav.*       导航
 *   tab.*       Tab
 *   chat.*      聊天界面
 *   models.*    模型配置页
 *   memory.*    长效记忆页
 *   mcp.*       MCP 配置页
 *   dialog.*    对话框
 *   action.*    通用按钮 / 操作
 *   status.*    状态文字
 *   hint.*      占位提示
 *   icon.*      图标 contentDescription
 */
@Composable
@ReadOnlyComposable
fun uiText(key: String, default: String): String =
    LocalUiStrings.current.text(key, default)
