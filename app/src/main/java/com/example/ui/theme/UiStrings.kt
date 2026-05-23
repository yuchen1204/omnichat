package com.example.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject

/**
 * AI 可调整的 UI 文字标签。
 *
 * 所有字段均有默认中文值，AI 可通过 `adjust_ui_strings` 工具修改任意字段。
 * 修改后通过 [LocalUiStrings] 注入整个 Compose 树，无需重启即可生效。
 *
 * 字段命名规则：`<位置/功能>_<具体含义>`，全部小写下划线。
 */
data class UiStrings(
    // ── 顶部导航栏 ──────────────────────────────────────────────
    val topbar_title_chat: String = "会话",
    val topbar_title_settings: String = "设置",
    val topbar_provider_prefix: String = "提供商: ",
    val topbar_memory_syncing: String = "记忆同步中",
    val topbar_menu_open: String = "打开菜单",
    val topbar_sync_memory: String = "同步记忆",

    // ── 底部导航 / 主 Tab ────────────────────────────────────────
    val nav_chat: String = "会话",
    val nav_settings: String = "设置",

    // ── 设置页三个子 Tab ─────────────────────────────────────────
    val settings_tab_models: String = "模型配置",
    val settings_tab_mcp: String = "MCP工具",
    val settings_tab_memory: String = "长效记忆",

    // ── 侧边栏 ───────────────────────────────────────────────────
    val sidebar_title: String = "对话列表",
    val sidebar_settings: String = "设置",
    val sidebar_delete_confirm: String = "确定要删除「%s」吗？\n该会话的所有消息记录将被永久清除，无法恢复。",

    // ── 聊天界面 ─────────────────────────────────────────────────
    val chat_no_provider_warning: String = "需设置全局自动提供商才能正常会话！",
    val chat_memory_injected: String = "已动态融合共 %d 条长效学习偏好记忆",
    val chat_current_model: String = "当前模型: %s  ·  %s",
    val chat_input_hint: String = "输入消息…",
    val chat_send: String = "发送",
    val chat_stop: String = "停止",
    val chat_new_session: String = "新建会话",
    val chat_thinking: String = "思考中",
    val chat_tool_calling: String = "调用工具",

    // ── 模型配置页 ───────────────────────────────────────────────
    val models_empty_hint: String = "当前未配置任何 API 提供商。\n点击右上角"新增提供商"开始添加！",
    val models_default_badge: String = "默认提供商",
    val models_set_default: String = "设为默认配置",
    val models_set_default_desc: String = "将此 API 提供商作为全局使用",
    val models_custom_headers: String = "自定义请求头: ",
    val models_no_headers: String = "暂无自定义请求头",
    val models_add_provider: String = "新增提供商",
    val models_fetch_models: String = "自动拉取并解析可用模型:",
    val models_fetch_error_prefix: String = "拉取错误: ",
    val models_list_hint: String = "可用模型列表 (点击模型名选择；点击下方标签可手动修正 思考/视觉/工具 调用能力):",
    val models_no_saved_models: String = "该 Provider 暂无已保存的模型列表",
    val models_fetch_first: String = "请先在「模型配置」中拉取模型",
    val models_memory_model_desc: String = "副模型在每次对话后台运行，负责提炼记忆条目并生成会话标题。可选择任意 Provider 的模型，建议用速度快、成本低的小模型。",

    // ── MCP 配置页 ───────────────────────────────────────────────
    val mcp_empty_hint: String = "暂无 MCP 服务",
    val mcp_empty_desc: String = "点击右上角「添加服务」配置 Node.js 或 Python MCP server",
    val mcp_examples_title: String = "常用示例",
    val mcp_builtin_title: String = "内置工具",
    val mcp_builtin_status: String = "运行中",
    val mcp_view_tools: String = "查看工具",
    val mcp_remote_http_support: String = "支持远程 HTTP MCP",
    val mcp_import_title: String = "导入 MCP 配置 (JSON)",
    val mcp_import_desc: String = "粘贴标准的 mcpServers JSON 配置。导入后将自动添加并尝试启动服务。",
    val mcp_runtime_label: String = "运行时",
    val mcp_auto_start: String = "启动时自动运行",

    // ── 长效记忆页 ───────────────────────────────────────────────
    val memory_manual_input: String = "手动录入长效记忆 / 用户首选项偏好",
    val memory_empty_hint: String = "暂无长效记忆。你可以直接在聊天中告诉 AI 你的习惯偏好，或者等待 AI 在对话后台分析后自动提炼生成！",

    // ── 通用操作 ─────────────────────────────────────────────────
    val action_confirm: String = "确定",
    val action_cancel: String = "取消",
    val action_delete: String = "删除",
    val action_edit: String = "编辑",
    val action_save: String = "保存",
    val action_add: String = "添加",
    val action_close: String = "关闭",
    val action_reset: String = "重置",
) {
    companion object {
        /** 默认实例（全中文） */
        val Default = UiStrings()

        /**
         * 从 JSON 字符串解析，缺失字段使用默认值。
         * JSON 格式：`{"topbar_title_chat": "Chat", "nav_settings": "Settings", ...}`
         */
        fun fromJson(json: String): UiStrings {
            if (json.isBlank()) return Default
            return try {
                val obj = JSONObject(json)
                val d = Default
                UiStrings(
                    topbar_title_chat = obj.optString("topbar_title_chat", d.topbar_title_chat),
                    topbar_title_settings = obj.optString("topbar_title_settings", d.topbar_title_settings),
                    topbar_provider_prefix = obj.optString("topbar_provider_prefix", d.topbar_provider_prefix),
                    topbar_memory_syncing = obj.optString("topbar_memory_syncing", d.topbar_memory_syncing),
                    topbar_menu_open = obj.optString("topbar_menu_open", d.topbar_menu_open),
                    topbar_sync_memory = obj.optString("topbar_sync_memory", d.topbar_sync_memory),
                    nav_chat = obj.optString("nav_chat", d.nav_chat),
                    nav_settings = obj.optString("nav_settings", d.nav_settings),
                    settings_tab_models = obj.optString("settings_tab_models", d.settings_tab_models),
                    settings_tab_mcp = obj.optString("settings_tab_mcp", d.settings_tab_mcp),
                    settings_tab_memory = obj.optString("settings_tab_memory", d.settings_tab_memory),
                    sidebar_title = obj.optString("sidebar_title", d.sidebar_title),
                    sidebar_settings = obj.optString("sidebar_settings", d.sidebar_settings),
                    sidebar_delete_confirm = obj.optString("sidebar_delete_confirm", d.sidebar_delete_confirm),
                    chat_no_provider_warning = obj.optString("chat_no_provider_warning", d.chat_no_provider_warning),
                    chat_memory_injected = obj.optString("chat_memory_injected", d.chat_memory_injected),
                    chat_current_model = obj.optString("chat_current_model", d.chat_current_model),
                    chat_input_hint = obj.optString("chat_input_hint", d.chat_input_hint),
                    chat_send = obj.optString("chat_send", d.chat_send),
                    chat_stop = obj.optString("chat_stop", d.chat_stop),
                    chat_new_session = obj.optString("chat_new_session", d.chat_new_session),
                    chat_thinking = obj.optString("chat_thinking", d.chat_thinking),
                    chat_tool_calling = obj.optString("chat_tool_calling", d.chat_tool_calling),
                    models_empty_hint = obj.optString("models_empty_hint", d.models_empty_hint),
                    models_default_badge = obj.optString("models_default_badge", d.models_default_badge),
                    models_set_default = obj.optString("models_set_default", d.models_set_default),
                    models_set_default_desc = obj.optString("models_set_default_desc", d.models_set_default_desc),
                    models_custom_headers = obj.optString("models_custom_headers", d.models_custom_headers),
                    models_no_headers = obj.optString("models_no_headers", d.models_no_headers),
                    models_add_provider = obj.optString("models_add_provider", d.models_add_provider),
                    models_fetch_models = obj.optString("models_fetch_models", d.models_fetch_models),
                    models_fetch_error_prefix = obj.optString("models_fetch_error_prefix", d.models_fetch_error_prefix),
                    models_list_hint = obj.optString("models_list_hint", d.models_list_hint),
                    models_no_saved_models = obj.optString("models_no_saved_models", d.models_no_saved_models),
                    models_fetch_first = obj.optString("models_fetch_first", d.models_fetch_first),
                    models_memory_model_desc = obj.optString("models_memory_model_desc", d.models_memory_model_desc),
                    mcp_empty_hint = obj.optString("mcp_empty_hint", d.mcp_empty_hint),
                    mcp_empty_desc = obj.optString("mcp_empty_desc", d.mcp_empty_desc),
                    mcp_examples_title = obj.optString("mcp_examples_title", d.mcp_examples_title),
                    mcp_builtin_title = obj.optString("mcp_builtin_title", d.mcp_builtin_title),
                    mcp_builtin_status = obj.optString("mcp_builtin_status", d.mcp_builtin_status),
                    mcp_view_tools = obj.optString("mcp_view_tools", d.mcp_view_tools),
                    mcp_remote_http_support = obj.optString("mcp_remote_http_support", d.mcp_remote_http_support),
                    mcp_import_title = obj.optString("mcp_import_title", d.mcp_import_title),
                    mcp_import_desc = obj.optString("mcp_import_desc", d.mcp_import_desc),
                    mcp_runtime_label = obj.optString("mcp_runtime_label", d.mcp_runtime_label),
                    mcp_auto_start = obj.optString("mcp_auto_start", d.mcp_auto_start),
                    memory_manual_input = obj.optString("memory_manual_input", d.memory_manual_input),
                    memory_empty_hint = obj.optString("memory_empty_hint", d.memory_empty_hint),
                    action_confirm = obj.optString("action_confirm", d.action_confirm),
                    action_cancel = obj.optString("action_cancel", d.action_cancel),
                    action_delete = obj.optString("action_delete", d.action_delete),
                    action_edit = obj.optString("action_edit", d.action_edit),
                    action_save = obj.optString("action_save", d.action_save),
                    action_add = obj.optString("action_add", d.action_add),
                    action_close = obj.optString("action_close", d.action_close),
                    action_reset = obj.optString("action_reset", d.action_reset),
                )
            } catch (e: Exception) {
                Default
            }
        }

        /**
         * 将 [UiStrings] 序列化为 JSON 字符串（仅输出与默认值不同的字段）。
         */
        fun UiStrings.toJson(): String {
            val d = Default
            val obj = JSONObject()
            if (topbar_title_chat != d.topbar_title_chat) obj.put("topbar_title_chat", topbar_title_chat)
            if (topbar_title_settings != d.topbar_title_settings) obj.put("topbar_title_settings", topbar_title_settings)
            if (topbar_provider_prefix != d.topbar_provider_prefix) obj.put("topbar_provider_prefix", topbar_provider_prefix)
            if (topbar_memory_syncing != d.topbar_memory_syncing) obj.put("topbar_memory_syncing", topbar_memory_syncing)
            if (topbar_menu_open != d.topbar_menu_open) obj.put("topbar_menu_open", topbar_menu_open)
            if (topbar_sync_memory != d.topbar_sync_memory) obj.put("topbar_sync_memory", topbar_sync_memory)
            if (nav_chat != d.nav_chat) obj.put("nav_chat", nav_chat)
            if (nav_settings != d.nav_settings) obj.put("nav_settings", nav_settings)
            if (settings_tab_models != d.settings_tab_models) obj.put("settings_tab_models", settings_tab_models)
            if (settings_tab_mcp != d.settings_tab_mcp) obj.put("settings_tab_mcp", settings_tab_mcp)
            if (settings_tab_memory != d.settings_tab_memory) obj.put("settings_tab_memory", settings_tab_memory)
            if (sidebar_title != d.sidebar_title) obj.put("sidebar_title", sidebar_title)
            if (sidebar_settings != d.sidebar_settings) obj.put("sidebar_settings", sidebar_settings)
            if (sidebar_delete_confirm != d.sidebar_delete_confirm) obj.put("sidebar_delete_confirm", sidebar_delete_confirm)
            if (chat_no_provider_warning != d.chat_no_provider_warning) obj.put("chat_no_provider_warning", chat_no_provider_warning)
            if (chat_memory_injected != d.chat_memory_injected) obj.put("chat_memory_injected", chat_memory_injected)
            if (chat_current_model != d.chat_current_model) obj.put("chat_current_model", chat_current_model)
            if (chat_input_hint != d.chat_input_hint) obj.put("chat_input_hint", chat_input_hint)
            if (chat_send != d.chat_send) obj.put("chat_send", chat_send)
            if (chat_stop != d.chat_stop) obj.put("chat_stop", chat_stop)
            if (chat_new_session != d.chat_new_session) obj.put("chat_new_session", chat_new_session)
            if (chat_thinking != d.chat_thinking) obj.put("chat_thinking", chat_thinking)
            if (chat_tool_calling != d.chat_tool_calling) obj.put("chat_tool_calling", chat_tool_calling)
            if (models_empty_hint != d.models_empty_hint) obj.put("models_empty_hint", models_empty_hint)
            if (models_default_badge != d.models_default_badge) obj.put("models_default_badge", models_default_badge)
            if (models_set_default != d.models_set_default) obj.put("models_set_default", models_set_default)
            if (models_set_default_desc != d.models_set_default_desc) obj.put("models_set_default_desc", models_set_default_desc)
            if (models_custom_headers != d.models_custom_headers) obj.put("models_custom_headers", models_custom_headers)
            if (models_no_headers != d.models_no_headers) obj.put("models_no_headers", models_no_headers)
            if (models_add_provider != d.models_add_provider) obj.put("models_add_provider", models_add_provider)
            if (models_fetch_models != d.models_fetch_models) obj.put("models_fetch_models", models_fetch_models)
            if (models_fetch_error_prefix != d.models_fetch_error_prefix) obj.put("models_fetch_error_prefix", models_fetch_error_prefix)
            if (models_list_hint != d.models_list_hint) obj.put("models_list_hint", models_list_hint)
            if (models_no_saved_models != d.models_no_saved_models) obj.put("models_no_saved_models", models_no_saved_models)
            if (models_fetch_first != d.models_fetch_first) obj.put("models_fetch_first", models_fetch_first)
            if (models_memory_model_desc != d.models_memory_model_desc) obj.put("models_memory_model_desc", models_memory_model_desc)
            if (mcp_empty_hint != d.mcp_empty_hint) obj.put("mcp_empty_hint", mcp_empty_hint)
            if (mcp_empty_desc != d.mcp_empty_desc) obj.put("mcp_empty_desc", mcp_empty_desc)
            if (mcp_examples_title != d.mcp_examples_title) obj.put("mcp_examples_title", mcp_examples_title)
            if (mcp_builtin_title != d.mcp_builtin_title) obj.put("mcp_builtin_title", mcp_builtin_title)
            if (mcp_builtin_status != d.mcp_builtin_status) obj.put("mcp_builtin_status", mcp_builtin_status)
            if (mcp_view_tools != d.mcp_view_tools) obj.put("mcp_view_tools", mcp_view_tools)
            if (mcp_remote_http_support != d.mcp_remote_http_support) obj.put("mcp_remote_http_support", mcp_remote_http_support)
            if (mcp_import_title != d.mcp_import_title) obj.put("mcp_import_title", mcp_import_title)
            if (mcp_import_desc != d.mcp_import_desc) obj.put("mcp_import_desc", mcp_import_desc)
            if (mcp_runtime_label != d.mcp_runtime_label) obj.put("mcp_runtime_label", mcp_runtime_label)
            if (mcp_auto_start != d.mcp_auto_start) obj.put("mcp_auto_start", mcp_auto_start)
            if (memory_manual_input != d.memory_manual_input) obj.put("memory_manual_input", memory_manual_input)
            if (memory_empty_hint != d.memory_empty_hint) obj.put("memory_empty_hint", memory_empty_hint)
            if (action_confirm != d.action_confirm) obj.put("action_confirm", action_confirm)
            if (action_cancel != d.action_cancel) obj.put("action_cancel", action_cancel)
            if (action_delete != d.action_delete) obj.put("action_delete", action_delete)
            if (action_edit != d.action_edit) obj.put("action_edit", action_edit)
            if (action_save != d.action_save) obj.put("action_save", action_save)
            if (action_add != d.action_add) obj.put("action_add", action_add)
            if (action_close != d.action_close) obj.put("action_close", action_close)
            if (action_reset != d.action_reset) obj.put("action_reset", action_reset)
            return obj.toString()
        }
    }
}

/** CompositionLocal，在 [MyApplicationTheme] 中注入，所有子 Composable 可直接读取。 */
val LocalUiStrings = staticCompositionLocalOf { UiStrings.Default }
