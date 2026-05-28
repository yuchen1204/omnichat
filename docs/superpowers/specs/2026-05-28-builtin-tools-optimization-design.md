# BuiltInTools 全面优化设计

## 背景

当前 `BuiltinToolHandler.kt`（1942 行）和 `McpRuntimeManager.kt`（2374 行）存在以下问题：

- `handleBuiltinTool` 单个 `when` 表达式 1096 行，31 个分支
- 30 个颜色字段在 4 处重复定义（schema、赋值、变更检测、capabilities）
- DB/Repository 实例化重复 13 次
- Schema 定义 670 行纯手写 JSONObject
- `successResponse`/`errorResponse` 未统一使用
- `search_memory` 全表加载到内存做 Jaccard
- `file_append` 读整个文件检查换行符

## 目标

- 消除字段定义重复（1 处维护代替 4 处）
- 拆分巨大 when 表达式为独立方法
- 引入 Schema DSL 减少 60% schema 代码
- 统一响应构建
- 优化 `search_memory` 和 `file_append` 性能
- **外部行为完全不变**（所有工具 API、schema、LLM 调用方式不变）

## 方案：渐进式重构

保持 `BuiltinToolHandler` 为 `object`，不引入新类/接口体系。通过提取元数据、拆分方法、引入 DSL 工具实现优化。

---

## 1. UiFieldRegistry — 集中字段元数据

### 新文件：`mcp/UiFieldRegistry.kt`

```kotlin
package com.example.mcp

import com.example.data.UISettings

object UiFieldRegistry {

    data class ColorField(
        val key: String,                          // JSON key
        val getter: (UISettings) -> String,       // 读取当前值
        val setter: (UISettings, String) -> UISettings, // 设置新值
        val desc: String,                         // 英文 schema description
        val purpose: String,                      // 中文用途说明
    )

    val colorFields = listOf(
        ColorField("primaryColor", { it.primaryColor }, { s, v -> s.copy(primaryColor = v) },
            "Primary color (buttons / selected / brand color), e.g. #6750A4",
            "主色调（按钮、链接、选中态、品牌色）"),
        ColorField("onPrimaryColor", { it.onPrimaryColor }, { s, v -> s.copy(onPrimaryColor = v) },
            "Text and icon color on primary (high contrast against primaryColor), e.g. #FFFFFF",
            "主色之上的文字与图标颜色（应与 primaryColor 形成 ≥4.5:1 对比度）"),
        // ... 其余 28 个颜色字段，每个只写一次
    )

    data class LayoutField(
        val key: String,
        val type: String,       // "integer" | "number"
        val desc: String,       // 英文 schema description
        val purpose: String,    // 中文用途
        val constraint: String, // 约束说明
    )

    val layoutFields = listOf(
        LayoutField("cornerRadiusDp", "integer",
            "Global corner radius in dp, range 0-32. Affects cards, buttons, and other rounded elements.",
            "全局圆角大小（dp）。影响所有卡片、按钮、对话框、徽章圆角",
            "整数 0-32，默认 12"),
        LayoutField("spacingMultiplier", "number",
            "Global spacing multiplier, range 0.5-2.0. 1.0 is the default.",
            "全局间距倍数。1.0=默认，>1 更宽松，<1 更紧凑",
            "浮点数 0.5-2.0，默认 1.0"),
    )

    data class FontField(
        val key: String,
        val type: String,
        val desc: String,
        val purpose: String,
        val constraint: String,
        val enumValues: List<String>? = null,
    )

    val fontFields = listOf(
        FontField("fontSizeScale", "number",
            "Global UI font size scale, range 0.75-1.5. 1.0 is the default.",
            "全局 UI 字体大小缩放比例。影响标题、按钮、标签等所有 UI 文字",
            "浮点数 0.75-1.5，默认 1.0"),
        FontField("chatFontSizeScale", "number",
            "Chat bubble body font size scale, range 0.75-1.5.",
            "聊天气泡正文字体大小缩放比例。独立于全局缩放",
            "浮点数 0.75-1.5，默认 1.0"),
        FontField("fontFamily", "string",
            "Font family. \"default\" = system default, \"serif\" = serif, \"monospace\" = monospace, \"cursive\" = cursive.",
            "字体族",
            "\"default\" | \"serif\" | \"monospace\" | \"cursive\"",
            enumValues = listOf("default", "serif", "monospace", "cursive")),
    )
}
```

### 使用方式

**adjust_ui 赋值**（30 行 → 循环）：
```kotlin
var next = current
val changed = mutableListOf<String>()
for (f in UiFieldRegistry.colorFields) {
    val v = arguments.optString(f.key).takeIf { hex -> isValidHex(hex) }
    if (v != null) {
        next = f.setter(next, v)
        changed += f.key
    }
}
```

**buildUiCapabilitiesResponse**（140 行 → 从 registry 生成）：
```kotlin
val colorFields = UiFieldRegistry.colorFields.map { f ->
    Field(f.key, f.getter(current), f.purpose, "HEX #RRGGBB(AA)")
}
```

**Schema 生成**（从 registry 驱动）：
```kotlin
for (f in UiFieldRegistry.colorFields) {
    put(f.key, colorProp(f.desc))
}
```

---

## 2. ToolSchemaDsl — Schema Builder DSL

### 新文件：`mcp/ToolSchemaDsl.kt`

```kotlin
package com.example.mcp

import org.json.JSONArray
import org.json.JSONObject

object ToolSchemaDsl {

    fun schema(block: SchemaBuilder.() -> Unit): JSONObject {
        return SchemaBuilder().apply(block).build()
    }

    class SchemaBuilder {
        private val properties = JSONObject()
        private val required = mutableListOf<String>()

        fun prop(name: String, type: String, desc: String, block: PropBuilder.() -> Unit = {}) {
            properties.put(name, PropBuilder(type, desc).apply(block).build())
        }

        fun required(vararg names: String) {
            required.addAll(names)
        }

        fun build(): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) {
                put("required", JSONArray(required))
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
            json.put("properties", SchemaBuilder().apply(block).build().getJSONObject("properties"))
        }

        fun build(): JSONObject = json
    }
}
```

### 使用示例

```kotlin
// 之前（20 行）:
McpTool(..., inputSchema = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject().apply {
        put("path", JSONObject().apply {
            put("type", "string")
            put("description", "Relative file path inside OmniChat/files/")
        })
        put("encoding", JSONObject().apply {
            put("type", "string")
            put("enum", JSONArray().apply { put("utf8"); put("base64") })
        })
    })
    put("required", JSONArray().apply { put("path"); put("content") })
})

// 之后（6 行）:
McpTool(..., inputSchema = schema {
    prop("path", "string", "Relative file path inside OmniChat/files/")
    prop("content", "string", "Text content to write")
    prop("encoding", "string", "Content encoding") { enum("utf8", "base64") }
    required("path", "content")
})
```

---

## 3. 拆分 when 表达式 + 统一响应

### BuiltinToolHandler 重构

将 31 个分支提取为独立私有方法：

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
        else -> errorResponse("未知工具: $toolName")
    }
}
```

### 统一响应

所有工具统一使用 `successResponse()` / `errorResponse()`，消除手写 JSON 嵌套。

---

## 4. 性能优化

### 4.1 search_memory — DB 预过滤

在 `Daos.kt` 新增查询：
```kotlin
@Query("SELECT * FROM memory_items WHERE content LIKE '%' || :keyword || '%'")
suspend fun searchMemoriesByKeyword(keyword: String): List<MemoryItem>
```

在 `Repository.kt` 暴露方法。`handleSearchMemory` 改为：
```kotlin
val keywords = query.split(" ").filter { it.isNotBlank() }
val candidates = if (keywords.isNotEmpty()) {
    keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
} else {
    repository.getAllMemories()
}
// 只对 candidates 做 Jaccard
```

### 4.2 file_append — 只读尾部

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

### 4.3 getRepository 辅助方法

```kotlin
private fun getRepository(context: Context): AppRepository {
    return AppRepository(AppDatabase.getDatabase(context))
}
```

---

## 5. 文件变更清单

| 文件 | 操作 | 预估行数变化 |
|------|------|-------------|
| `mcp/UiFieldRegistry.kt` | 新建 | +120 |
| `mcp/ToolSchemaDsl.kt` | 新建 | +60 |
| `mcp/BuiltinToolHandler.kt` | 重构 | 1942 → ~1200 (-742) |
| `mcp/McpRuntimeManager.kt` | 重构 schema | 670 → ~250 (-420) |
| `data/Daos.kt` | 新增查询 | +3 |
| `data/Repository.kt` | 新增方法 | +3 |

**净减少约 980 行代码。**

---

## 6. 验证策略

1. **编译验证**：`./gradlew assembleDebug` 确保无编译错误
2. **单元测试**：`./gradlew testDebugUnitTest` 确保现有测试通过
3. **功能验证**：所有 31 个工具的输入输出行为不变，可通过 app 内 MCP 工具调用验证
4. **Schema 对比**：重构前后导出所有工具 schema JSON，diff 确保完全一致
