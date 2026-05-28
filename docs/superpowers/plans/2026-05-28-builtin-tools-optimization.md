# BuiltInTools 全面优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 BuiltinToolHandler 和 McpRuntimeManager，消除重复代码、拆分巨大函数、引入 Schema DSL、优化性能

**Architecture:** 渐进式重构 — 保持 `object` 结构，引入 `UiFieldRegistry` 集中字段元数据、`ToolSchemaDsl` 简化 schema 构建、拆分 `when` 为独立方法、统一响应构建

**Tech Stack:** Kotlin, Room, JSONObject, Android Context

---

## 文件变更总览

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/src/main/java/com/example/mcp/UiFieldRegistry.kt` | 新建 | 颜色/布局/字体字段元数据 |
| `app/src/main/java/com/example/mcp/ToolSchemaDsl.kt` | 新建 | Schema builder DSL |
| `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` | 重构 | 拆分 when + 使用 registry + 统一响应 |
| `app/src/main/java/com/example/mcp/McpRuntimeManager.kt` | 重构 | Schema 改用 DSL |
| `app/src/main/java/com/example/data/Daos.kt` | 小改 | 新增 searchMemoriesByKeyword |
| `app/src/main/java/com/example/data/Repository.kt` | 小改 | 暴露 searchMemoriesByKeyword |

---

### Task 1: 创建 UiFieldRegistry

**Files:**
- Create: `app/src/main/java/com/example/mcp/UiFieldRegistry.kt`

- [ ] **Step 1: 创建 UiFieldRegistry 文件**

```kotlin
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
            "Primary color (buttons / selected / brand color), e.g. #6750A4",
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
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 2: 创建 ToolSchemaDsl

**Files:**
- Create: `app/src/main/java/com/example/mcp/ToolSchemaDsl.kt`

- [ ] **Step 1: 创建 ToolSchemaDsl 文件**

```kotlin
package com.example.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量 Schema Builder DSL，用于替代手写 JSONObject 嵌套构建。
 *
 * 使用前（20 行）:
 *   McpTool(..., inputSchema = JSONObject().apply {
 *       put("type", "object")
 *       put("properties", JSONObject().apply {
 *           put("path", JSONObject().apply { put("type", "string"); put("description", "...") })
 *       })
 *       put("required", JSONArray().apply { put("path") })
 *   })
 *
 * 使用后（6 行）:
 *   McpTool(..., inputSchema = schema {
 *       prop("path", "string", "Relative file path...")
 *       prop("content", "string", "Text content to write")
 *       required("path", "content")
 *   })
 */
object ToolSchemaDsl {

    fun schema(block: SchemaBuilder.() -> Unit): JSONObject {
        return SchemaBuilder().apply(block).build()
    }

    class SchemaBuilder {
        private val properties = JSONObject()
        private val requiredList = mutableListOf<String>()

        fun prop(name: String, type: String, desc: String, block: (PropBuilder.() -> Unit)? = null) {
            properties.put(name, PropBuilder(type, desc).apply { block?.invoke(this) }.build())
        }

        fun required(vararg names: String) {
            requiredList.addAll(names)
        }

        fun build(): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (requiredList.isNotEmpty()) {
                put("required", JSONArray(requiredList))
            }
        }
    }

    class PropBuilder(private val type: String, private val desc: String) {
        private val json = JSONObject().apply {
            put("type", type)
            put("description", desc)
        }

        fun enum(vararg values: String) {
            json.put("enum", JSONArray(values.toList()))
        }

        fun pattern(regex: String) {
            json.put("pattern", regex)
        }

        fun items(block: PropBuilder.() -> Unit) {
            json.put("items", PropBuilder("string", "").apply(block).build())
        }

        fun properties(block: SchemaBuilder.() -> Unit) {
            val nested = SchemaBuilder().apply(block).build()
            json.put("properties", nested.getJSONObject("properties"))
            if (nested.has("required")) {
                json.put("required", nested.getJSONArray("required"))
            }
        }

        fun additionalProperties(block: PropBuilder.() -> Unit) {
            json.put("additionalProperties", PropBuilder("string", "").apply(block).build())
        }

        fun build(): JSONObject = json
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Daos.kt — 新增 searchMemoriesByKeyword

**Files:**
- Modify: `app/src/main/java/com/example/data/Daos.kt:77-112` (MemoryItemDao)
- Modify: `app/src/main/java/com/example/data/Repository.kt:52-53`

- [ ] **Step 1: 在 MemoryItemDao 中添加查询方法**

在 `Daos.kt` 的 `MemoryItemDao` 接口中，在 `getAllMemories()` 方法后添加：

```kotlin
    @Query("SELECT * FROM memory_items WHERE content LIKE '%' || :keyword || '%' ORDER BY pinned DESC, confidence DESC, updatedAt DESC")
    suspend fun searchMemoriesByKeyword(keyword: String): List<MemoryItem>
```

- [ ] **Step 2: 在 Repository 中暴露方法**

在 `Repository.kt` 的 `getAllMemories()` 方法后添加：

```kotlin
    suspend fun searchMemoriesByKeyword(keyword: String): List<MemoryItem> = memoryItemDao.searchMemoriesByKeyword(keyword)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 4: 重构 McpRuntimeManager — Schema 改用 DSL

**Files:**
- Modify: `app/src/main/java/com/example/mcp/McpRuntimeManager.kt:496-1209`

- [ ] **Step 1: 添加 ToolSchemaDsl 导入**

在文件顶部 import 区域添加：

```kotlin
import com.example.mcp.ToolSchemaDsl.schema
```

- [ ] **Step 2: 重构 get_current_time schema**

将 lines 497-512 的 `get_current_time` 定义改为：

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_current_time",
            description = "Get the current real date and time (including timezone). Call this tool whenever you need to know today's date, the current time, the day of the week, or perform any reasoning that depends on the current time.",
            inputSchema = schema {
                prop("timezone", "string", "Optional. IANA timezone name, e.g. Asia/Shanghai or America/New_York. Leave empty to use the device's local timezone.")
            }
        ),
```

- [ ] **Step 3: 重构 get_ui_capabilities schema**

将 lines 513-522 改为：

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "get_ui_capabilities",
            description = "Query the capability manifest and current values of the app's UI theme configuration. **Call this tool before calling adjust_ui** to learn all adjustable fields, their semantics, constraints, and current effective values. The response includes: color field list (primary palette / status colors / extended colors), layout parameters (corner radius / spacing), valid value constraints (HEX range), and recommended color combination suggestions.",
            inputSchema = schema {}
        ),
```

- [ ] **Step 4: 重构 adjust_ui schema — 使用 UiFieldRegistry**

将 lines 523-580 的 `adjust_ui` 定义改为：

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_ui",
            description = "Adjust the app's complete color scheme and layout. Covers the full Material 3 color palette (primary / secondary / tertiary + their container and on-colors), surface and outline colors, error / success / warning / info / accent colors, as well as corner radius and spacing multiplier.\n\n**Important**: Call get_ui_capabilities first to see the current values and constraints for all adjustable fields. All colors must be in #RRGGBB or #RRGGBBAA format. Fields not provided will retain their current values (incremental update). Changes take effect immediately across the entire app without a restart.",
            inputSchema = schema {
                for (f in UiFieldRegistry.colorFields) {
                    put(f.key, colorProp(f.desc))
                }
                for (f in UiFieldRegistry.layoutFields) {
                    prop(f.key, f.type, f.desc)
                }
                prop("resetToDefault", "boolean", "Pass true to immediately reset all UI to defaults (other fields are ignored).")
            }
        ),
```

- [ ] **Step 5: 重构其余简单工具 schema（reset_ui_to_default, save_color_scheme, list_color_schemes, apply_color_scheme, search_memory, delete_color_scheme）**

将 lines 581-675 的 6 个工具定义改用 DSL。每个工具的改动模式相同 — 将 `JSONObject().apply { ... }` 替换为 `schema { ... }`。例如 `save_color_scheme`：

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "save_color_scheme",
            description = "Save the current app color scheme as a named preset for easy restoration later. Up to ${com.example.data.ColorSchemePreset.MAX_PRESETS} presets can be saved; if the limit is reached an error is returned — call delete_color_scheme to free up a slot first. Returns the unique schemeId of the newly saved preset.",
            inputSchema = schema {
                prop("name", "string", "Preset name — short and memorable, e.g. \"Deep Ocean Blue\" or \"Minimal White\". Max 30 characters.")
                prop("description", "string", "Preset summary describing the color style or use case, e.g. \"An immersive dark night theme with deep blue as the primary color\". Max 100 characters.")
                required("name", "description")
            }
        ),
```

按同样模式重构 `list_color_schemes`、`apply_color_scheme`、`search_memory`、`delete_color_scheme`、`reset_ui_to_default`。

- [ ] **Step 6: 重构 adjust_font schema — 使用 UiFieldRegistry**

将 lines 676-703 改为：

```kotlin
        McpTool(
            serverId = BUILTIN_SERVER_ID,
            serverName = BUILTIN_SERVER_NAME,
            name = "adjust_font",
            description = "Adjust the app's font settings, including global font size scale, chat bubble font size scale, and font family. Changes take effect globally and immediately without a restart.\n\n**Adjustable fields:**\n• fontSizeScale — Global UI font size scale (0.75–1.5, default 1.0)\n• chatFontSizeScale — Chat bubble body font size scale (0.75–1.5, default 1.0)\n• fontFamily — Font family (\"default\" / \"serif\" / \"monospace\" / \"cursive\")\n\nFields not provided retain their current values (incremental update).",
            inputSchema = schema {
                for (f in UiFieldRegistry.fontFields) {
                    prop(f.key, f.type, f.desc) {
                        if (f.enumValues != null) enum(*f.enumValues.toTypedArray())
                    }
                }
            }
        ),
```

- [ ] **Step 7: 重构剩余工具 schema（reset_font_to_default, list_ui_texts, set_ui_texts, file_*, create_document, ask_user, timer_*, scratchpad_*, tool_groups）**

按同样模式将 lines 705-1165 的所有工具定义改用 DSL。每个工具保持原有 description 和 required 不变，只改 schema 构建方式。

- [ ] **Step 8: 删除 colorProp 辅助方法（如果不再需要）**

检查 `colorProp()` 是否还被其他地方使用。如果只在 `adjust_ui` schema 中使用，且已改用 `put(f.key, colorProp(f.desc))`，则保留。如果完全不再使用则删除。

- [ ] **Step 9: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 5: 重构 BuiltinToolHandler — 提取 getRepository + 统一响应

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

- [ ] **Step 1: 添加 UiFieldRegistry 导入**

在文件顶部 import 区域添加：

```kotlin
import com.example.mcp.UiFieldRegistry
```

- [ ] **Step 2: 添加 getRepository 辅助方法**

在 `BuiltinToolHandler` object 中，在 `teamManager` 声明之后添加：

```kotlin
    /** 共享的 Repository 实例工厂，避免每次工具调用都重复创建 */
    private fun getRepository(context: Context): AppRepository {
        return AppRepository(AppDatabase.getDatabase(context))
    }
```

- [ ] **Step 3: 替换所有 DB/Repository 实例化**

将文件中所有出现的：
```kotlin
val db = AppDatabase.getDatabase(context)
val repository = AppRepository(db)
```
替换为：
```kotlin
val repository = getRepository(context)
```

共 13 处需要替换。

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 6: 重构 BuiltinToolHandler — 拆分 when 表达式

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt:30-1126`

- [ ] **Step 1: 重构 handleBuiltinTool 为分发表**

将 lines 30-1126 的整个 `when` 表达式替换为简洁的分发表，每个分支委托给独立的私有方法：

```kotlin
    suspend fun handleBuiltinTool(context: Context, toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject {
        return when (toolName) {
            "get_ui_capabilities" -> handleGetUiCapabilities(context)
            "reset_ui_to_default" -> handleResetUiToDefault(context)
            "adjust_ui" -> handleAdjustUi(context, arguments)
            "get_current_time" -> handleGetCurrentTime(arguments)
            "save_color_scheme" -> handleSaveColorScheme(context, arguments)
            "list_color_schemes" -> handleListColorSchemes(context)
            "apply_color_scheme" -> handleApplyColorScheme(context, arguments)
            "delete_color_scheme" -> handleDeleteColorScheme(context, arguments)
            "search_memory" -> handleSearchMemory(context, arguments)
            "adjust_font" -> handleAdjustFont(context, arguments)
            "reset_font_to_default" -> handleResetFontToDefault(context)
            "list_ui_texts" -> handleListUiTexts(context, arguments)
            "set_ui_texts" -> handleSetUiTexts(context, arguments)
            "file_write" -> handleFileWrite(context, arguments)
            "file_read" -> handleFileRead(context, arguments)
            "file_append" -> handleFileAppend(context, arguments)
            "file_delete" -> handleFileDelete(context, arguments)
            "file_list" -> handleFileList(context, arguments)
            "file_search" -> handleFileSearch(context, arguments)
            "file_info" -> handleFileInfo(context, arguments)
            "file_move" -> handleFileMove(context, arguments)
            "create_document" -> handleCreateDocument(context, arguments)
            "ask_user" -> handleAskUser(context, arguments, sessionId)
            "create_timer" -> handleCreateTimer(context, arguments, sessionId)
            "cancel_timer" -> handleCancelTimer(context, arguments)
            "list_timers" -> handleListTimers(context)
            "scratchpad_write" -> handleScratchpadWrite(arguments)
            "scratchpad_read" -> handleScratchpadRead(arguments)
            "scratchpad_list" -> handleScratchpadList()
            "list_mcp_tool_groups" -> handleListMcpToolGroups(context)
            "configure_mcp_tool_groups" -> handleConfigureMcpToolGroups(context, arguments)
            else -> errorResponse("未知的内置工具: $toolName")
        }
    }
```

- [ ] **Step 2: 提取 handleGetCurrentTime**

```kotlin
    private fun handleGetCurrentTime(arguments: JSONObject): JSONObject {
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
        return successResponse(result.trim())
    }
```

- [ ] **Step 3: 提取 handleGetUiCapabilities**

```kotlin
    private suspend fun handleGetUiCapabilities(context: Context): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        return buildUiCapabilitiesResponse(current)
    }
```

- [ ] **Step 4: 提取 handleResetUiToDefault**

```kotlin
    private suspend fun handleResetUiToDefault(context: Context): JSONObject {
        val repository = getRepository(context)
        repository.upsertUISettings(UISettings())
        return successResponse("UI 设置已成功恢复为默认。")
    }
```

- [ ] **Step 5: 提取 handleAdjustUi — 使用 UiFieldRegistry 循环**

```kotlin
    private suspend fun handleAdjustUi(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()

        if (arguments.optBoolean("resetToDefault", false)) {
            repository.upsertUISettings(UISettings())
            return successResponse("UI 已重置为默认设置。")
        }

        var next = current
        val changed = mutableListOf<String>()

        for (f in UiFieldRegistry.colorFields) {
            val v = arguments.optString(f.key).takeIf { UiFieldRegistry.isValidHex(it) }
            if (v != null) {
                next = f.setter(next, v)
                changed += f.key
            }
        }

        if (arguments.has("cornerRadiusDp")) {
            val cr = arguments.optInt("cornerRadiusDp", current.cornerRadiusDp).coerceIn(0, 32)
            if (cr != current.cornerRadiusDp) {
                next = next.copy(cornerRadiusDp = cr)
                changed += "cornerRadiusDp"
            }
        }
        if (arguments.has("spacingMultiplier")) {
            val sp = arguments.optDouble("spacingMultiplier", current.spacingMultiplier.toDouble())
                .toFloat().coerceIn(0.5f, 2.0f)
            if (sp != current.spacingMultiplier) {
                next = next.copy(spacingMultiplier = sp)
                changed += "spacingMultiplier"
            }
        }

        repository.upsertUISettings(next.copy(updatedAt = System.currentTimeMillis()))

        val summary = if (changed.isEmpty()) "未检测到任何字段变化（输入可能为空或全部无效）。"
        else "已更新 ${changed.size} 项：${changed.joinToString(", ")}"
        return successResponse("UI 设置已应用。$summary")
    }
```

- [ ] **Step 6: 提取剩余 UI 工具方法（save_color_scheme, list_color_schemes, apply_color_scheme, delete_color_scheme, adjust_font, reset_font_to_default, set_ui_texts, list_ui_texts）**

每个方法从原 `when` 分支中提取，保持逻辑不变，只将 `AppDatabase.getDatabase(context)` + `AppRepository(db)` 替换为 `getRepository(context)`，将手写 JSON response 替换为 `successResponse()`/`errorResponse()`。

- [ ] **Step 7: 提取文件工具方法（file_write, file_read, file_append, file_delete, file_list, file_search, file_info, file_move）**

每个方法从原 `when` 分支中提取，逻辑不变。

- [ ] **Step 8: 提取 create_document 方法**

从原 `when` 分支提取，逻辑不变。

- [ ] **Step 9: 提取 ask_user 方法**

```kotlin
    private suspend fun handleAskUser(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val question = arguments.optString("question").trim()
        if (question.isEmpty()) return errorResponse("参数 'question' 不能为空。")
        val optionsArray = arguments.optJSONArray("options")
        val options = mutableListOf<String>()
        if (optionsArray != null) {
            for (i in 0 until optionsArray.length()) {
                val opt = optionsArray.optString(i).trim()
                if (opt.isNotEmpty()) options.add(opt)
            }
        }
        val response = AskUserManager.askUser(question, options)
        return successResponse(response)
    }
```

- [ ] **Step 10: 提取定时器方法（create_timer, cancel_timer, list_timers）**

每个方法从原 `when` 分支提取，逻辑不变，统一使用 `successResponse()`/`errorResponse()`。

- [ ] **Step 11: 提取 scratchpad 方法（scratchpad_write, scratchpad_read, scratchpad_list）**

每个方法从原 `when` 分支提取，逻辑不变。

- [ ] **Step 12: 提取 tool_groups 方法（list_mcp_tool_groups, configure_mcp_tool_groups）**

每个方法从原 `when` 分支提取，逻辑不变。

- [ ] **Step 13: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 7: 重构 BuiltinToolHandler — 优化 adjust_ui 变更检测

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt`

此任务已在 Task 6 Step 5 中完成 — `handleAdjustUi` 使用 `UiFieldRegistry.colorFields` 循环替代了 30 行逐字段比较。

- [ ] **Step 1: 确认 adjust_ui 的变更检测已使用循环实现**

检查 `handleAdjustUi` 方法确认不再有逐行 `if (next.xxx != current.xxx) changed += "xxx"` 的代码。

---

### Task 8: 重构 BuiltinToolHandler — 优化 buildUiCapabilitiesResponse

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt:1753-1893`

- [ ] **Step 1: 重构 buildUiCapabilitiesResponse 使用 UiFieldRegistry**

将 lines 1753-1893 的 `buildUiCapabilitiesResponse` 方法改为从 `UiFieldRegistry` 生成：

```kotlin
    private fun buildUiCapabilitiesResponse(current: UISettings): JSONObject {
        data class Field(val name: String, val currentValue: Any?, val purpose: String, val constraint: String)

        val colorFields = UiFieldRegistry.colorFields.map { f ->
            Field(f.key, f.getter(current), f.purpose, "HEX #RRGGBB(AA)")
        }
        val layoutFields = UiFieldRegistry.layoutFields.map { f ->
            Field(f.key, when (f.key) {
                "cornerRadiusDp" -> current.cornerRadiusDp
                "spacingMultiplier" -> current.spacingMultiplier
                else -> null
            }, f.purpose, f.constraint)
        }
        val fontFields = UiFieldRegistry.fontFields.map { f ->
            Field(f.key, when (f.key) {
                "fontSizeScale" -> current.fontSizeScale
                "chatFontSizeScale" -> current.chatFontSizeScale
                "fontFamily" -> current.fontFamily
                else -> null
            }, f.purpose, f.constraint)
        }

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
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 9: 优化 file_append — 只读尾部检查换行符

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` (handleFileAppend 方法)

- [ ] **Step 1: 优化 file_append 的换行符检查**

在 `handleFileAppend` 方法中，将：
```kotlin
val needsNewline = file.exists() && file.length() > 0 && !file.readText(Charsets.UTF_8).endsWith("\n")
```
替换为：
```kotlin
val needsNewline = if (file.exists() && file.length() > 0) {
    java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(maxOf(0, file.length() - 4))
        val tail = ByteArray(minOf(4, file.length().toInt()))
        raf.readFully(tail)
        !String(tail, Charsets.UTF_8).endsWith("\n")
    }
} else false
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 10: 优化 search_memory — DB 预过滤

**Files:**
- Modify: `app/src/main/java/com/example/mcp/BuiltinToolHandler.kt` (handleSearchMemory 方法)

- [ ] **Step 1: 修改 handleSearchMemory 使用 DB 预过滤**

将 `handleSearchMemory` 方法中的：
```kotlin
val allMemories = repository.getAllMemories()
```
替换为：
```kotlin
val keywords = query.split(" ").filter { it.isNotBlank() }
val allMemories = if (keywords.isNotEmpty()) {
    keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
} else {
    repository.getAllMemories()
}
```

同时将 `val totalCount = allMemories.size` 改为获取全表总数：
```kotlin
val totalCount = repository.getAllMemories().size
```

注意：`totalCount` 应在搜索前获取，避免额外查询。重构为：

```kotlin
val keywords = query.split(" ").filter { it.isNotBlank() }
val totalCount: Int
val candidates: List<com.example.data.MemoryItem>
if (keywords.isNotEmpty()) {
    // 先获取总数用于显示
    totalCount = repository.getAllMemories().size
    candidates = keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
} else {
    candidates = repository.getAllMemories()
    totalCount = candidates.size
}
```

然后将后续的 `allMemories` 引用改为 `candidates`。

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 11: 最终验证

- [ ] **Step 1: 完整编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行单元测试**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 3: 检查代码行数变化**

Run: `wc -l app/src/main/java/com/example/mcp/BuiltinToolHandler.kt app/src/main/java/com/example/mcp/McpRuntimeManager.kt app/src/main/java/com/example/mcp/UiFieldRegistry.kt app/src/main/java/com/example/mcp/ToolSchemaDsl.kt`

Expected:
- BuiltinToolHandler.kt: ~1200 行 (原 1942)
- McpRuntimeManager.kt: schema 部分 ~250 行 (原 670)
- UiFieldRegistry.kt: ~120 行 (新建)
- ToolSchemaDsl.kt: ~60 行 (新建)
