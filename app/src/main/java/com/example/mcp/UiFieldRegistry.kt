package com.example.mcp

import com.example.data.UISettings

/**
 * 集中定义所有 UI 可调整字段的元数据。
 * 消除 McpRuntimeManager（schema）、BuiltinToolHandler（赋值/变更检测/capabilities）中的重复定义。
 */
object UiFieldRegistry {

    data class ColorField(
        val key: String,
        val getter: (UISettings) -> String,
        val setter: (UISettings, String) -> UISettings,
        val desc: String,
        val purpose: String,
    )

    data class LayoutField(
        val key: String,
        val type: String,
        val desc: String,
        val purpose: String,
        val constraint: String,
    )

    data class FontField(
        val key: String,
        val type: String,
        val desc: String,
        val purpose: String,
        val constraint: String,
        val enumValues: List<String>? = null,
    )

    val colorFields = listOf(
        ColorField("primaryColor", { it.primaryColor }, { s, v -> s.copy(primaryColor = v) },
            "Primary color (buttons / selected / brand color), e.g. #007AFF",
            "主色调（按钮、链接、选中态、品牌色）"),
        ColorField("onPrimaryColor", { it.onPrimaryColor }, { s, v -> s.copy(onPrimaryColor = v) },
            "Text and icon color on primary (high contrast against primaryColor), e.g. #FFFFFF",
            "主色之上的文字与图标颜色（应与 primaryColor 形成 ≥4.5:1 对比度）"),
        ColorField("primaryContainerColor", { it.primaryContainerColor }, { s, v -> s.copy(primaryContainerColor = v) },
            "Primary container (e.g. default provider badge background), a lighter variant of the primary color",
            "主色容器（如默认提供商徽章、激活会话项背景）"),
        ColorField("onPrimaryContainerColor", { it.onPrimaryContainerColor }, { s, v -> s.copy(onPrimaryContainerColor = v) },
            "Text color on primary container, high contrast against primaryContainerColor",
            "主色容器上的文字色"),
        ColorField("secondaryColor", { it.secondaryColor }, { s, v -> s.copy(secondaryColor = v) },
            "Secondary color",
            "次要色调"),
        ColorField("onSecondaryColor", { it.onSecondaryColor }, { s, v -> s.copy(onSecondaryColor = v) },
            "Text color on secondary",
            "次色上的文字色"),
        ColorField("secondaryContainerColor", { it.secondaryContainerColor }, { s, v -> s.copy(secondaryContainerColor = v) },
            "Secondary container background",
            "次色容器"),
        ColorField("onSecondaryContainerColor", { it.onSecondaryContainerColor }, { s, v -> s.copy(onSecondaryContainerColor = v) },
            "Text color on secondary container",
            "次色容器上的文字色"),
        ColorField("tertiaryColor", { it.tertiaryColor }, { s, v -> s.copy(tertiaryColor = v) },
            "Tertiary color (accent highlight), often used for special badges",
            "第三色（强调点缀、特殊徽章）"),
        ColorField("onTertiaryColor", { it.onTertiaryColor }, { s, v -> s.copy(onTertiaryColor = v) },
            "Text color on tertiary",
            "第三色上的文字色"),
        ColorField("backgroundColor", { it.backgroundColor }, { s, v -> s.copy(backgroundColor = v) },
            "Full-page background color",
            "整页背景色"),
        ColorField("onBackgroundColor", { it.onBackgroundColor }, { s, v -> s.copy(onBackgroundColor = v) },
            "Body text color on background",
            "背景上的正文文字色"),
        ColorField("surfaceColor", { it.surfaceColor }, { s, v -> s.copy(surfaceColor = v) },
            "Surface color for cards / dialogs / input fields",
            "卡片、对话框、TopAppBar、输入框等表面颜色"),
        ColorField("onSurfaceColor", { it.onSurfaceColor }, { s, v -> s.copy(onSurfaceColor = v) },
            "Primary text color on surface (e.g. headings)",
            "表面上的主要文字色（标题、正文）"),
        ColorField("surfaceVariantColor", { it.surfaceVariantColor }, { s, v -> s.copy(surfaceVariantColor = v) },
            "Secondary surface (aggregated tool messages, thinking panel background)",
            "次级表面（思考面板背景、聚合工具消息背景、工具栏抽屉）"),
        ColorField("onSurfaceVariantColor", { it.onSurfaceVariantColor }, { s, v -> s.copy(onSurfaceVariantColor = v) },
            "Secondary text color on surface variant",
            "次级表面上的辅助文字色"),
        ColorField("outlineColor", { it.outlineColor }, { s, v -> s.copy(outlineColor = v) },
            "Primary divider / border color",
            "边框、描边主色"),
        ColorField("outlineVariantColor", { it.outlineVariantColor }, { s, v -> s.copy(outlineVariantColor = v) },
            "Lighter divider / border color",
            "更轻的分隔线和边框色"),
        ColorField("errorColor", { it.errorColor }, { s, v -> s.copy(errorColor = v) },
            "Error state color (delete buttons, error messages)",
            "错误状态色（删除按钮、错误提示文字、危险操作）"),
        ColorField("onErrorColor", { it.onErrorColor }, { s, v -> s.copy(onErrorColor = v) },
            "Text color on error",
            "错误色上的文字色"),
        ColorField("errorContainerColor", { it.errorContainerColor }, { s, v -> s.copy(errorContainerColor = v) },
            "Error container background (error message bubbles)",
            "错误容器背景（提示气泡）"),
        ColorField("onErrorContainerColor", { it.onErrorContainerColor }, { s, v -> s.copy(onErrorContainerColor = v) },
            "Text color inside error container",
            "错误容器内的文字色"),
        ColorField("successColor", { it.successColor }, { s, v -> s.copy(successColor = v) },
            "Success color (running status, green badges); iOS-style #34C759 is the default",
            "成功色（运行中状态、记忆同步成功、绿色徽章）"),
        ColorField("warningColor", { it.warningColor }, { s, v -> s.copy(warningColor = v) },
            "Warning color (starting up, orange hints); #FF9800 is the default",
            "警告色（启动中状态、警告提示）"),
        ColorField("infoColor", { it.infoColor }, { s, v -> s.copy(infoColor = v) },
            "Info color (visual / blue badges); #007AFF is the default",
            "信息色（视觉能力徽章等蓝色点缀）"),
        ColorField("accentColor", { it.accentColor }, { s, v -> s.copy(accentColor = v) },
            "Accent color (thinking-process star, orange highlight); #FF9500 is the default",
            "强调色（思考过程中的星标等橙色点缀）"),
        ColorField("sidebarBackgroundColor", { it.sidebarBackgroundColor }, { s, v -> s.copy(sidebarBackgroundColor = v) },
            "Sidebar background color, e.g. #FFFBFE",
            "侧边栏背景色"),
        ColorField("sidebarOnBackgroundColor", { it.sidebarOnBackgroundColor }, { s, v -> s.copy(sidebarOnBackgroundColor = v) },
            "Sidebar text and secondary icon color, e.g. #1C1B1F",
            "侧边栏文字与辅助图标颜色"),
        ColorField("sidebarActiveColor", { it.sidebarActiveColor }, { s, v -> s.copy(sidebarActiveColor = v) },
            "Sidebar active item background color, e.g. #EADDFF",
            "侧边栏激活项背景色"),
        ColorField("sidebarOnActiveColor", { it.sidebarOnActiveColor }, { s, v -> s.copy(sidebarOnActiveColor = v) },
            "Sidebar active item text and icon color, e.g. #21005D",
            "侧边栏激活项文字与图标颜色"),
    )

    val layoutFields = listOf(
        LayoutField("cornerRadiusDp", "integer",
            "Global corner radius in dp, range 0-32. Affects cards, buttons, and other rounded elements.",
            "全局圆角大小（dp）。影响所有卡片、按钮、对话框、徽章圆角",
            "整数 0-32，默认 12"),
        LayoutField("spacingMultiplier", "number",
            "Global spacing multiplier, range 0.5-2.0. 1.0 is the default; >1 is more spacious, <1 is more compact.",
            "全局间距倍数。1.0=默认，>1 更宽松，<1 更紧凑",
            "浮点数 0.5-2.0，默认 1.0"),
    )

    val fontFields = listOf(
        FontField("fontSizeScale", "number",
            "Global UI font size scale, range 0.75-1.5. 1.0 is the default (100%); 1.2 enlarges by 20%, 0.9 reduces by 10%. Affects all UI text including headings, buttons, and labels.",
            "全局 UI 字体大小缩放比例。影响标题、按钮、标签等所有 UI 文字（聊天气泡除外）",
            "浮点数 0.75-1.5，默认 1.0"),
        FontField("chatFontSizeScale", "number",
            "Chat bubble body font size scale, range 0.75-1.5. Independent of the global scale — you can increase chat content font size without affecting other UI elements.",
            "聊天气泡正文字体大小缩放比例。独立于全局缩放，可单独调大聊天内容字号",
            "浮点数 0.75-1.5，默认 1.0"),
        FontField("fontFamily", "string",
            "Font family. \"default\" = system default (Roboto), \"serif\" = serif font (Noto Serif), \"monospace\" = monospace font (Noto Sans Mono), \"cursive\" = handwriting-style font.",
            "字体族。\"default\"=系统默认(Roboto)，\"serif\"=衬线字体，\"monospace\"=等宽字体，\"cursive\"=手写风格",
            "\"default\" | \"serif\" | \"monospace\" | \"cursive\"",
            enumValues = listOf("default", "serif", "monospace", "cursive")),
    )

    /** HEX 颜色校验正则 */
    val HEX_PATTERN = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$"

    fun isValidHex(hex: String?): Boolean {
        if (hex == null) return false
        return Regex(HEX_PATTERN).matches(hex)
    }
}
