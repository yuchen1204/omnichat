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

    // WHY: 由 WorkspaceViewModel 在创建/清理 TeamManager 时设置，供 scratchpad 工具访问
    @Volatile
    var teamManager: com.example.workspace.TeamManager? = null

    // ── 共享 Scratchpad（跨 Agent 协作的内存 KV 存储）──────────────────
    private val scratchpad = java.util.concurrent.ConcurrentHashMap<String, ScratchpadEntry>()
    data class ScratchpadEntry(val agentName: String, val key: String, val content: String, val timestamp: Long = System.currentTimeMillis())

    // 提取公共 Repository 工厂方法，消除 13 处重复的 AppDatabase.getDatabase + AppRepository 实例化
    private fun getRepository(context: Context): AppRepository {
        return AppRepository(AppDatabase.getDatabase(context))
    }

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
            "cancel_timer" -> handleCancelTimer(arguments)
            "list_timers" -> handleListTimers()
            "list_mcp_tool_groups" -> handleListMcpToolGroups(context)
            "configure_mcp_tool_groups" -> handleConfigureMcpToolGroups(context, arguments)
            "agent" -> handleAgentTool(arguments)
            com.example.workspace.SendMessageTool.TOOL_NAME -> handleSendMessage(arguments)
            com.example.workspace.TaskTools.TASK_CREATE -> handleTaskTool(context, toolName, arguments)
            com.example.workspace.TaskTools.TASK_GET -> handleTaskTool(context, toolName, arguments)
            com.example.workspace.TaskTools.TASK_LIST -> handleTaskTool(context, toolName, arguments)
            com.example.workspace.TaskTools.TASK_UPDATE -> handleTaskTool(context, toolName, arguments)
            "scratchpad_write" -> handleScratchpadWrite(arguments)
            "scratchpad_read" -> handleScratchpadRead(arguments)
            "scratchpad_list" -> handleScratchpadList()
            else -> errorResponse("未知的内置工具: $toolName")
        }
    }

    // ── Agent 工具 ──────────────────────────────────────────────────────────

    private suspend fun handleAgentTool(arguments: JSONObject): JSONObject {
        val agentTool = teamManager?.getAgentTool()
            ?: return errorResponse("AgentTool not available: no active workspace")
        val parentContext = teamManager?.getOrchestratorContext()
            ?: return errorResponse("AgentTool not available: no orchestrator context")
        val sandboxPath = teamManager?.getSandboxPath() ?: ""
        return agentTool.call(arguments, parentContext, sandboxPath)
    }

    // ── SendMessage 工具 ────────────────────────────────────────────────────

    private suspend fun handleSendMessage(arguments: JSONObject): JSONObject {
        val manager = teamManager
            ?: return errorResponse("SendMessage not available: no active workspace")
        val sendTool = com.example.workspace.SendMessageTool(manager)
        return sendTool.call(arguments)
    }

    // ── Task 管理工具 ─────────────────────────────────────────────────────

    private suspend fun handleTaskTool(context: Context, toolName: String, arguments: JSONObject): JSONObject {
        val manager = teamManager
            ?: return errorResponse("Task tools not available: no active workspace")
        val teamName = manager.teamState?.value?.teamName ?: ""
        val repository = getRepository(context)
        val taskTools = com.example.workspace.TaskTools(repository, teamName)
        return taskTools.callTool(toolName, arguments)
    }

    // ── Scratchpad 工具 ────────────────────────────────────────────────────

    private fun handleScratchpadWrite(arguments: JSONObject): JSONObject {
        val agentName = teamManager?.teamState?.value?.orchestratorName ?: "unknown"
        val key = arguments.optString("key", "").replace(Regex("[^a-zA-Z0-9_]"), "_")
        val content = arguments.optString("content", "")
        if (key.isEmpty()) return errorResponse("Missing 'key'")
        if (content.isEmpty()) return errorResponse("Missing 'content'")
        scratchpad[key] = ScratchpadEntry(agentName, key, content)
        return successResponse("Wrote '$key' to scratchpad")
    }

    private fun handleScratchpadRead(arguments: JSONObject): JSONObject {
        val agentName = arguments.optString("agentName", "")
        val key = arguments.optString("key", "")
        if (key.isEmpty()) return errorResponse("Missing 'key'")
        val entry = scratchpad[key]
            ?: return errorResponse("No scratchpad entry for key '$key'")
        if (agentName.isNotEmpty() && entry.agentName != agentName) {
            return errorResponse("Entry '$key' was written by '${entry.agentName}', not '$agentName'")
        }
        return successResponse(entry.content)
    }

    private fun handleScratchpadList(): JSONObject {
        if (scratchpad.isEmpty()) return successResponse("Scratchpad is empty")
        val list = scratchpad.values.joinToString("\n") { entry ->
            "- [${entry.agentName}] ${entry.key}: ${entry.content.take(100)}${if (entry.content.length > 100) "..." else ""}"
        }
        return successResponse(list)
    }

    // ── UI 工具 ────────────────────────────────────────────────────────────

    private suspend fun handleGetUiCapabilities(context: Context): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        return buildUiCapabilitiesResponse(current)
    }

    private suspend fun handleResetUiToDefault(context: Context): JSONObject {
        val repository = getRepository(context)
        repository.upsertUISettings(UISettings())
        return successResponse("UI 设置已成功恢复为默认。")
    }

    // 使用 UiFieldRegistry 循环处理颜色字段，消除 30 行重复的 hex() 调用和变更检测
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

    // ── 配色方案工具 ────────────────────────────────────────────────────────

    private suspend fun handleSaveColorScheme(context: Context, arguments: JSONObject): JSONObject {
        val name = arguments.optString("name").trim()
        val desc = arguments.optString("description").trim()
        if (name.isBlank()) {
            return errorResponse("保存失败：name 不能为空。")
        }
        val repository = getRepository(context)
        val count = repository.getColorSchemePresetCount()
        if (count >= ColorSchemePreset.MAX_PRESETS) {
            val existing = repository.getAllColorSchemePresets()
            val list = existing.joinToString("\n") { "• [${it.schemeId}] ${it.name}" }
            return errorResponse("保存失败：已达到最多 ${ColorSchemePreset.MAX_PRESETS} 个方案的上限。\n\n当前已保存的方案：\n$list\n\n请先调用 delete_color_scheme 删除一个不需要的方案，再重试。")
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
        return successResponse("配色方案「${preset.name}」已保存。\nschemeId: $schemeId\n当前已保存 ${count + 1}/${ColorSchemePreset.MAX_PRESETS} 个方案。")
    }

    private suspend fun handleListColorSchemes(context: Context): JSONObject {
        val repository = getRepository(context)
        val presets = repository.getAllColorSchemePresets()
        if (presets.isEmpty()) {
            return successResponse("当前没有已保存的配色方案。可以先用 adjust_ui 调整配色，再调用 save_color_scheme 保存。")
        }
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
        return successResponse(text.trimEnd())
    }

    private suspend fun handleApplyColorScheme(context: Context, arguments: JSONObject): JSONObject {
        val schemeId = arguments.optString("schemeId").trim()
        if (schemeId.isBlank()) {
            return errorResponse("应用失败：schemeId 不能为空。请先调用 list_color_schemes 获取可用的 schemeId。")
        }
        val repository = getRepository(context)
        val preset = repository.getColorSchemePresetById(schemeId)
        if (preset == null) {
            return errorResponse("应用失败：找不到 schemeId=$schemeId 的方案。请调用 list_color_schemes 确认可用的 schemeId。")
        }
        repository.upsertUISettings(preset.toUISettings())
        return successResponse("配色方案「${preset.name}」已应用，界面立即生效。\n概述：${preset.description}")
    }

    private suspend fun handleDeleteColorScheme(context: Context, arguments: JSONObject): JSONObject {
        val schemeId = arguments.optString("schemeId").trim()
        if (schemeId.isBlank()) {
            return errorResponse("删除失败：schemeId 不能为空。")
        }
        val repository = getRepository(context)
        val preset = repository.getColorSchemePresetById(schemeId)
        if (preset == null) {
            return errorResponse("删除失败：找不到 schemeId=$schemeId 的方案。")
        }
        repository.deleteColorSchemePreset(schemeId)
        val remaining = repository.getColorSchemePresetCount()
        return successResponse("配色方案「${preset.name}」已删除。当前剩余 $remaining/${ColorSchemePreset.MAX_PRESETS} 个方案。")
    }

    // ── 记忆工具 ────────────────────────────────────────────────────────────

    private suspend fun handleSearchMemory(context: Context, arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return errorResponse("搜索失败：query 不能为空。")
        }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
        val repository = getRepository(context)

        // DB 预过滤：先用 SQL LIKE 缩小候选集，再做 Jaccard 相似度
        val keywords = query.split(" ").filter { it.isNotBlank() }
        val totalCount: Int
        val candidates: List<com.example.data.MemoryItem>
        if (keywords.isNotEmpty()) {
            totalCount = repository.getAllMemories().size
            candidates = keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
        } else {
            candidates = repository.getAllMemories()
            totalCount = candidates.size
        }

        val queryTokens = bigramTokenize(query)

        data class ScoredMemory(val memory: com.example.data.MemoryItem, val score: Double)

        val scored = candidates
            .mapNotNull { mem ->
                val memTokens = bigramTokenize(mem.content)
                val intersection = queryTokens.intersect(memTokens).size
                val union = queryTokens.union(memTokens).size
                if (union == 0 || intersection == 0) return@mapNotNull null
                val jaccard = intersection.toDouble() / union.toDouble()
                ScoredMemory(mem, jaccard * mem.confidence)
            }
            .sortedByDescending { it.score }
            .take(limit)
        val text = buildString {
            appendLine("记忆库搜索结果（关键词：「$query」，共 $totalCount 条记忆，命中 ${scored.size} 条）：")
            appendLine()
            if (scored.isEmpty()) {
                appendLine("未找到与关键词相关的记忆。")
                appendLine("提示：可以尝试更换关键词，或直接浏览全部记忆（记忆库共 $totalCount 条）。")
            } else {
                scored.forEachIndexed { i, sm ->
                    val pinnedTag = if (sm.memory.pinned) " [已锁定]" else ""
                    appendLine("${i + 1}. [id=${sm.memory.id}, 置信度=${sm.memory.confidence}, 相关度=${String.format("%.2f", sm.score)}$pinnedTag]")
                    appendLine("   ${sm.memory.content}")
                }
            }
        }
        return successResponse(text.trimEnd())
    }

    // ── 字体工具 ────────────────────────────────────────────────────────────

    private suspend fun handleAdjustFont(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
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
        return successResponse("字体设置已应用。$summary")
    }

    private suspend fun handleResetFontToDefault(context: Context): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val next = current.copy(
            fontSizeScale = 1.0f,
            chatFontSizeScale = 1.0f,
            fontFamily = "default",
            updatedAt = System.currentTimeMillis()
        )
        repository.upsertUISettings(next)
        return successResponse("字体设置已恢复为默认：fontSizeScale=1.0，chatFontSizeScale=1.0，fontFamily=\"default\"。")
    }

    // ── UI 文字工具 ─────────────────────────────────────────────────────────

    private suspend fun handleListUiTexts(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
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

        return successResponse(text)
    }

    private suspend fun handleSetUiTexts(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val currentStrings = UiStrings.fromJson(current.uiStrings)

        if (arguments.optBoolean("resetAll", false)) {
            repository.upsertUISettings(current.copy(uiStrings = "{}", updatedAt = System.currentTimeMillis()))
            return successResponse("已重置全部 UI 文字标签为默认中文。")
        }

        val updates = arguments.optJSONObject("updates")
        val deletes = arguments.optJSONArray("delete")
        if (updates == null && deletes == null) {
            return errorResponse("调用失败：必须提供 updates（要设置的键值对）或 delete（要删除的 key 列表）至少一项，或传 resetAll=true 重置全部。")
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
        return successResponse(text.trimEnd())
    }

    // ── MCP 工具组管理 ──────────────────────────────────────────────────────

    private suspend fun handleListMcpToolGroups(context: Context): JSONObject {
        val repository = getRepository(context)
        val settings = repository.getUISettings() ?: UISettings()
        val enabledGroups = settings.enabledMcpGroups.split(",").toSet()

        val allGroups = listOf(
            "core" to "核心工具：基础时间、提问、运行时管理 (始终开启)",
            "memory" to "长效记忆：搜索历史偏好事实 (默认开启)",
            "ui_appearance" to "界面外观：调色、圆角、间距、字体 (默认开启)",
            "efficiency" to "效率提醒：创建和管理定时器 (默认开启)",
            "ui_text" to "界面文字：修改 App 内部所有的文案标签 (默认关闭)",
            "files" to "文件管理：读写外部存储文件 (默认关闭)",
            "documents" to "文档创作：生成 PDF/Excel/Word/PPT (默认关闭)"
        )

        val text = buildString {
            appendLine("=== 内置 MCP 工具组状态 ===")
            appendLine()
            allGroups.forEach { (id, desc) ->
                val status = if (id == "core" || id in enabledGroups) "✅ 已启用" else "❌ 已禁用"
                appendLine("$status 【$id】")
                appendLine("   $desc")
                appendLine()
            }
            appendLine("提示：如需启用某个功能，请调用 configure_mcp_tool_groups(enable=[\"group_id\"])")
        }
        return successResponse(text.trimEnd())
    }

    private suspend fun handleConfigureMcpToolGroups(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val currentGroups = current.enabledMcpGroups.split(",").toMutableSet()

        val toEnable = arguments.optJSONArray("enable")
        val toDisable = arguments.optJSONArray("disable")

        val enabledCount = mutableListOf<String>()
        val disabledCount = mutableListOf<String>()

        if (toEnable != null) {
            for (i in 0 until toEnable.length()) {
                val g = toEnable.optString(i)
                if (g.isNotEmpty() && g != "core" && currentGroups.add(g)) {
                    enabledCount += g
                }
            }
        }
        if (toDisable != null) {
            for (i in 0 until toDisable.length()) {
                val g = toDisable.optString(i)
                if (g.isNotEmpty() && g != "core" && currentGroups.remove(g)) {
                    disabledCount += g
                }
            }
        }

        if (enabledCount.isEmpty() && disabledCount.isEmpty()) {
            return successResponse("未执行任何更改（组已处于目标状态或参数为空）。")
        }

        val nextGroups = currentGroups.sorted().joinToString(",")
        repository.upsertUISettings(current.copy(enabledMcpGroups = nextGroups, updatedAt = System.currentTimeMillis()))

        val text = buildString {
            appendLine("✅ MCP 工具组配置已更新。")
            if (enabledCount.isNotEmpty()) appendLine("已启用：${enabledCount.joinToString(", ")}")
            if (disabledCount.isNotEmpty()) appendLine("已禁用：${disabledCount.joinToString(", ")}")
            appendLine()
            appendLine("当前启用的组：$nextGroups")
        }
        return successResponse(text.trimEnd())
    }

    // ── 文件系统工具 ────────────────────────────────────────────────────────

    private fun handleFileWrite(context: Context, arguments: JSONObject): JSONObject {
        val relativePath = arguments.optString("path").trim()
        val content = arguments.optString("content")
        val encoding = arguments.optString("encoding", "utf8")
        if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolveSafePath(context, relativePath)
            ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
        return try {
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

    private fun handleFileRead(context: Context, arguments: JSONObject): JSONObject {
        val relativePath = arguments.optString("path").trim()
        val encoding = arguments.optString("encoding", "utf8")
        val maxBytes = arguments.optInt("maxBytes", 1024 * 1024).coerceIn(1, 10 * 1024 * 1024)
        if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolveSafePath(context, relativePath)
            ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
        if (!file.exists()) return errorResponse("文件不存在：$relativePath")
        if (!file.isFile) return errorResponse("路径指向的不是文件：$relativePath")
        return try {
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

    private fun handleFileAppend(context: Context, arguments: JSONObject): JSONObject {
        val relativePath = arguments.optString("path").trim()
        val content = arguments.optString("content")
        if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolveSafePath(context, relativePath)
            ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
        return try {
            file.parentFile?.mkdirs()
            val needsNewline = if (file.exists() && file.length() > 0) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(maxOf(0, file.length() - 4))
                    val tail = ByteArray(minOf(4, file.length().toInt()))
                    raf.readFully(tail)
                    !String(tail, Charsets.UTF_8).endsWith("\n")
                }
            } else false
            file.appendText(if (needsNewline) "\n$content" else content, Charsets.UTF_8)
            successResponse("内容已追加到：${file.absolutePath}\n当前文件大小：${file.length()} 字节")
        } catch (e: Exception) {
            errorResponse("追加文件失败：${e.localizedMessage}")
        }
    }

    private fun handleFileDelete(context: Context, arguments: JSONObject): JSONObject {
        val relativePath = arguments.optString("path").trim()
        val recursive = arguments.optBoolean("recursive", false)
        if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolveSafePath(context, relativePath)
            ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")
        if (!file.exists()) return errorResponse("路径不存在：$relativePath")
        return try {
            val success = if (recursive) deleteRecursive(file) else file.delete()
            if (success) successResponse("已删除：${file.absolutePath}")
            else errorResponse("删除失败，目录可能不为空（如需递归删除请传 recursive=true）。")
        } catch (e: Exception) {
            errorResponse("删除失败：${e.localizedMessage}")
        }
    }

    private fun handleFileList(context: Context, arguments: JSONObject): JSONObject {
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
        return successResponse(text.trimEnd())
    }

    private fun handleFileSearch(context: Context, arguments: JSONObject): JSONObject {
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
        return successResponse(text.trimEnd())
    }

    private fun handleFileInfo(context: Context, arguments: JSONObject): JSONObject {
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
        return successResponse(text.trimEnd())
    }

    private fun handleFileMove(context: Context, arguments: JSONObject): JSONObject {
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
        return try {
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

    // ── 文档创建工具 ────────────────────────────────────────────────────────

    private fun handleCreateDocument(context: Context, arguments: JSONObject): JSONObject {
        val relativePath = arguments.optString("path").trim()
        val format = arguments.optString("format").trim().lowercase()
        val title = arguments.optString("title", "").trim()

        // 样式解析
        val styleObj = arguments.optJSONObject("style")
        val themeColor = styleObj?.optString("themeColor", "#4682B4") ?: "#4682B4"
        val preset = styleObj?.optString("preset", "business") ?: "business"

        // 章节解析
        val sections = mutableListOf<DocSection>()
        val sectionsArray = arguments.optJSONArray("sections")
        if (sectionsArray != null) {
            for (i in 0 until sectionsArray.length()) {
                val obj = sectionsArray.optJSONObject(i) ?: continue
                sections.add(parseSection(obj))
            }
        } else {
            // 兼容旧版参数
            val paragraphsArray = arguments.optJSONArray("paragraphs")
            if (paragraphsArray != null) {
                for (i in 0 until paragraphsArray.length()) {
                    paragraphsArray.optString(i).takeIf { it.isNotEmpty() }?.let {
                        sections.add(DocSection(type = "text", content = it))
                    }
                }
            }
            val tableObj = arguments.optJSONObject("table")
            if (tableObj != null) {
                sections.add(parseSection(JSONObject().apply { put("type", "table"); put("table", tableObj) }))
            }
            val slidesArray = arguments.optJSONArray("slides")
            if (slidesArray != null && format == "pptx") {
                for (i in 0 until slidesArray.length()) {
                    val slideObj = slidesArray.optJSONObject(i) ?: continue
                    sections.add(DocSection(type = "page_break"))
                    sections.add(DocSection(type = "heading", content = slideObj.optString("title", ""), level = 1))
                    val contentArr = slideObj.optJSONArray("content")
                    if (contentArr != null) {
                        for (j in 0 until contentArr.length()) {
                            sections.add(DocSection(type = "text", content = contentArr.optString(j)))
                        }
                    }
                }
            }
        }

        if (relativePath.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        if (format !in listOf("pdf", "xlsx", "docx", "pptx")) {
            return errorResponse("参数 'format' 必须是 pdf、xlsx、docx 或 pptx 之一。")
        }

        val file = resolveSafePath(context, relativePath)
            ?: return errorResponse("非法路径：路径不能包含 '..' 或超出沙盒范围。")

        return try {
            file.parentFile?.mkdirs()

            when (format) {
                "pdf" -> createPdfDocument(file, title, sections, themeColor, preset, context)
                "xlsx" -> createXlsxDocument(file, title, sections, themeColor)
                "docx" -> createDocxDocument(file, title, sections, themeColor, preset, context)
                "pptx" -> createPptxDocument(file, title, sections, themeColor, preset, context)
            }

            successResponse("✅ 精致文档已创建：${file.absolutePath}\n格式：${format.uppercase()}\n大小：${file.length()} 字节")
        } catch (e: Throwable) {
            errorResponse("创建精致文档失败：${e.localizedMessage}")
        }
    }

    // ── 用户交互工具 ────────────────────────────────────────────────────────

    private suspend fun handleAskUser(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
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
        val multiSelect = arguments.optBoolean("multi_select", false)
        val response = AskUserManager.askUser(question, options, multiSelect)
        return successResponse(response)
    }

    // ── 定时器工具 ──────────────────────────────────────────────────────────

    private suspend fun handleCreateTimer(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val delaySeconds = arguments.optLong("delay_seconds", 0L)
        val message = arguments.optString("message").trim()
        val label = arguments.optString("label", "AI 定时提醒").trim()
            .take(30).ifEmpty { "AI 定时提醒" }

        if (delaySeconds < 1 || delaySeconds > 86400) {
            return errorResponse("参数 'delay_seconds' 必须在 1 到 86400（24小时）之间。")
        }
        if (message.isEmpty()) {
            return errorResponse("参数 'message' 不能为空。")
        }
        if (sessionId == null) {
            return errorResponse("无法创建定时器：当前没有活跃的聊天 session。")
        }

        val timerId = TimerManager.createTimer(
            context = context,
            sessionId = sessionId,
            delaySeconds = delaySeconds,
            message = message,
            label = label
        )

        val minutes = delaySeconds / 60
        val seconds = delaySeconds % 60
        val humanDelay = when {
            minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
            minutes > 0 -> "${minutes} 分钟 ${if (seconds > 0) "${seconds} 秒" else ""}"
            else -> "${seconds} 秒"
        }.trim()

        return successResponse(
            "✅ 定时器已创建！\n\n" +
            "• 定时器 ID：`$timerId`\n" +
            "• 触发时间：$humanDelay 后\n" +
            "• 提醒内容：$message\n\n" +
            "到时间后会在聊天中插入提醒消息，并发送系统通知。\n" +
            "如需取消，请调用 cancel_timer 并传入 timer_id: \"$timerId\""
        )
    }

    private fun handleCancelTimer(arguments: JSONObject): JSONObject {
        val timerId = arguments.optString("timer_id").trim()
        if (timerId.isEmpty()) {
            return errorResponse("参数 'timer_id' 不能为空。请先调用 list_timers 查看当前待触发的定时器。")
        }
        val cancelled = TimerManager.cancelTimer(timerId)
        return if (cancelled) {
            successResponse("✅ 定时器 `$timerId` 已成功取消。")
        } else {
            errorResponse("找不到 timer_id=\"$timerId\" 的定时器。它可能已经触发或不存在。\n\n调用 list_timers 可查看当前所有待触发的定时器。")
        }
    }

    private fun handleListTimers(): JSONObject {
        val timers = TimerManager.listTimers()
        if (timers.isEmpty()) {
            return successResponse("当前没有待触发的定时器。")
        }
        val now = System.currentTimeMillis()
        val text = buildString {
            appendLine("当前待触发的定时器（共 ${timers.size} 个）：")
            appendLine()
            timers.forEachIndexed { i, t ->
                val remainingMs = (t.fireAtMs - now).coerceAtLeast(0L)
                val remainingSec = remainingMs / 1000
                val remainingMin = remainingSec / 60
                val humanRemaining = when {
                    remainingMin >= 60 -> "${remainingMin / 60} 小时 ${remainingMin % 60} 分钟"
                    remainingMin > 0 -> "${remainingMin} 分钟 ${remainingSec % 60} 秒"
                    else -> "${remainingSec} 秒"
                }.trim()
                appendLine("${i + 1}. ID: `${t.timerId}`")
                appendLine("   标签：${t.label}")
                appendLine("   内容：${t.message}")
                appendLine("   剩余：$humanRemaining")
            }
        }
        return successResponse(text.trimEnd())
    }

    // ── 文件系统工具辅助函数 ────────────────────────────────────────────────

    // ── 文档创建辅助函数 ────────────────────────────────────────────────────

    data class DocSection(
        val type: String,
        val content: String = "",
        val level: Int = 1,
        val markdown: Boolean = false,
        val tableHeaders: List<String> = emptyList(),
        val tableRows: List<List<String>> = emptyList()
    )

    private fun parseSection(obj: JSONObject): DocSection {
        val type = obj.optString("type", "text")
        val content = obj.optString("content", "")
        val level = obj.optInt("level", 1)
        val markdown = obj.optBoolean("markdown", false)

        val tableHeaders = mutableListOf<String>()
        val tableRows = mutableListOf<List<String>>()
        val tableObj = obj.optJSONObject("table")
        if (tableObj != null) {
            val headersArr = tableObj.optJSONArray("headers")
            if (headersArr != null) {
                for (i in 0 until headersArr.length()) tableHeaders.add(headersArr.optString(i))
            }
            val rowsArr = tableObj.optJSONArray("rows")
            if (rowsArr != null) {
                for (i in 0 until rowsArr.length()) {
                    val rowArr = rowsArr.optJSONArray(i) ?: continue
                    val row = mutableListOf<String>()
                    for (j in 0 until rowArr.length()) row.add(rowArr.optString(j))
                    tableRows.add(row)
                }
            }
        }

        return DocSection(type, content, level, markdown, tableHeaders, tableRows)
    }

    private fun hexToColor(hex: String, default: Int): Int {
        return try {
            android.graphics.Color.parseColor(hex)
        } catch (e: Exception) {
            default
        }
    }

    /** 创建 PDF 文档（使用 Android 原生 PdfDocument API） */
    private fun createPdfDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String,
        preset: String,
        context: android.content.Context
    ) {
        val doc = android.graphics.pdf.PdfDocument()
        val pageWidth = 595  // A4 宽度 (points)
        val pageHeight = 842 // A4 高度 (points)
        val margin = 50f
        val themeColor = hexToColor(themeHex, android.graphics.Color.parseColor("#4682B4"))
        var y = margin

        val textPaint = android.text.TextPaint().apply {
            textSize = 12f
            color = android.graphics.Color.BLACK
            isAntiAlias = true
        }

        fun newPage(): android.graphics.pdf.PdfDocument.Page {
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create()
            val page = doc.startPage(pageInfo)

            // 绘制页眉背景 (如果是首页且有标题，绘制大封面)
            if (doc.pages.size == 0 && title.isNotEmpty()) {
                val coverPaint = android.graphics.Paint().apply { color = themeColor }
                page.canvas.drawRect(0f, 0f, pageWidth.toFloat(), 200f, coverPaint)
            }

            // 绘制页脚
            val footerPaint = android.graphics.Paint().apply {
                textSize = 10f
                color = android.graphics.Color.GRAY
            }
            val footerText = "Generated by OmniChat • Page ${doc.pages.size + 1}"
            page.canvas.drawText(footerText, margin, pageHeight - 20f, footerPaint)
            return page
        }

        var page = newPage()
        var canvas = page.canvas

        fun checkNewPage(neededHeight: Float) {
            if (y + neededHeight > pageHeight - margin - 30f) {
                doc.finishPage(page)
                page = newPage()
                canvas = page.canvas
                y = margin + 20f
            }
        }

        // 标题 (封面样式)
        if (title.isNotEmpty()) {
            val titlePaint = android.text.TextPaint().apply {
                textSize = 32f
                isFakeBoldText = true
                color = android.graphics.Color.WHITE
                isAntiAlias = true
            }
            val titleLayout = android.text.StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, (pageWidth - margin * 2).toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()

            y = 80f // 首页标题位置
            canvas.save()
            canvas.translate(margin, y)
            titleLayout.draw(canvas)
            canvas.restore()
            y = 230f // 标题后正文开始位置
        }

        for (section in sections) {
            when (section.type) {
                "heading" -> {
                    val hSize = when (section.level) {
                        1 -> 20f
                        2 -> 16f
                        else -> 14f
                    }
                    val hPaint = android.text.TextPaint().apply {
                        textSize = hSize
                        isFakeBoldText = true
                        color = if (section.level == 1) themeColor else android.graphics.Color.BLACK
                        isAntiAlias = true
                    }
                    val layout = android.text.StaticLayout.Builder.obtain(section.content, 0, section.content.length, hPaint, (pageWidth - margin * 2).toInt()).build()
                    checkNewPage(layout.height.toFloat() + 10f)
                    canvas.save()
                    canvas.translate(margin, y)
                    layout.draw(canvas)
                    canvas.restore()
                    y += layout.height + 10f
                }
                "text" -> {
                    val layout = android.text.StaticLayout.Builder.obtain(section.content, 0, section.content.length, textPaint, (pageWidth - margin * 2).toInt())
                        .setLineSpacing(0f, 1.2f)
                        .build()

                    for (i in 0 until layout.lineCount) {
                        val h = layout.getLineBottom(i) - layout.getLineTop(i).toFloat()
                        checkNewPage(h)
                        val lineText = section.content.substring(layout.getLineStart(i), layout.getLineEnd(i))
                        canvas.drawText(lineText, margin, y + textPaint.textSize, textPaint)
                        y += h
                    }
                    y += 10f
                }
                "image" -> {
                    val imgFile = resolveSafePath(context, section.content)
                    if (imgFile?.exists() == true) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath)
                        if (bitmap != null) {
                            val targetWidth = pageWidth - margin * 2
                            val targetHeight = bitmap.height * (targetWidth / bitmap.width.toFloat())
                            checkNewPage(targetHeight + 10f)
                            canvas.drawBitmap(bitmap, null, android.graphics.RectF(margin, y, margin + targetWidth, y + targetHeight), null)
                            y += targetHeight + 10f
                        }
                    }
                }
                "table" -> {
                    if (section.tableHeaders.isNotEmpty() || section.tableRows.isNotEmpty()) {
                        val allRows = mutableListOf<List<String>>()
                        if (section.tableHeaders.isNotEmpty()) allRows.add(section.tableHeaders)
                        allRows.addAll(section.tableRows)

                        val colCount = allRows.maxOfOrNull { it.size } ?: 1
                        val colWidth = (pageWidth - margin * 2) / colCount
                        val tablePaint = android.graphics.Paint(textPaint).apply { textSize = 10f }

                        for ((rowIdx, row) in allRows.withIndex()) {
                            val isHeader = rowIdx == 0 && section.tableHeaders.isNotEmpty()
                            val rowHeight = 25f
                            checkNewPage(rowHeight)

                            if (isHeader) {
                                tablePaint.color = themeColor
                                canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tablePaint)
                                tablePaint.color = android.graphics.Color.WHITE
                                tablePaint.isFakeBoldText = true
                            } else {
                                if (rowIdx % 2 == 0) {
                                    tablePaint.color = android.graphics.Color.rgb(245, 245, 245)
                                    canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tablePaint)
                                }
                                tablePaint.color = android.graphics.Color.BLACK
                                tablePaint.isFakeBoldText = false
                            }

                            for ((colIdx, cell) in row.withIndex()) {
                                val x = margin + colIdx * colWidth + 5f
                                val truncated = tablePaint.breakText(cell, true, colWidth - 10f, null)
                                val text = if (truncated < cell.length) cell.substring(0, truncated.toInt()) + "…" else cell
                                canvas.drawText(text, x, y + rowHeight * 0.7f, tablePaint)
                            }

                            tablePaint.style = android.graphics.Paint.Style.STROKE
                            tablePaint.color = android.graphics.Color.LTGRAY
                            canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tablePaint)
                            tablePaint.style = android.graphics.Paint.Style.FILL
                            y += rowHeight
                        }
                        y += 10f
                    }
                }
                "page_break" -> {
                    doc.finishPage(page)
                    page = newPage()
                    canvas = page.canvas
                    y = margin
                }
            }
        }

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    /** 创建 Excel (.xlsx) 文档 */
    private fun createXlsxDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String
    ) {
        val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val sheetName = title.take(31).ifEmpty { "Sheet1" }.replace(Regex("[/\\\\?*\\[\\]]"), " ")
        val sheet = workbook.createSheet(sheetName)
        val themeColorInt = hexToColor(themeHex, android.graphics.Color.parseColor("#4682B4"))
        val themeRgb = bytearrayOf(
            (android.graphics.Color.red(themeColorInt)).toByte(),
            (android.graphics.Color.green(themeColorInt)).toByte(),
            (android.graphics.Color.blue(themeColorInt)).toByte()
        )
        val xssfColor = org.apache.poi.xssf.usermodel.XSSFColor(themeRgb, null)

        var rowIdx = 0

        // 标题样式
        if (title.isNotEmpty()) {
            val titleStyle = workbook.createCellStyle().apply {
                alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
                val font = workbook.createFont().apply {
                    bold = true
                    fontHeightInPoints = 18
                }
                setFont(font)
            }
            val row = sheet.createRow(rowIdx++)
            val cell = row.createCell(0)
            cell.setCellValue(title)
            cell.cellStyle = titleStyle
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 5))
            rowIdx++
        }

        for (section in sections) {
            when (section.type) {
                "heading" -> {
                    val hRow = sheet.createRow(rowIdx++)
                    val cell = hRow.createCell(0)
                    cell.setCellValue(section.content)
                    val font = workbook.createFont().apply { bold = true; fontHeightInPoints = 14 }
                    val style = workbook.createCellStyle().apply { setFont(font) }
                    cell.cellStyle = style
                }
                "text" -> {
                    val tRow = sheet.createRow(rowIdx++)
                    val cell = tRow.createCell(0)
                    cell.setCellValue(section.content)
                }
                "table" -> {
                    val headerStyle = workbook.createCellStyle().apply {
                        (this as org.apache.poi.xssf.usermodel.XSSFCellStyle).setFillForegroundColor(xssfColor)
                        fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
                        val font = workbook.createFont().apply { bold = true; color = org.apache.poi.ss.usermodel.IndexedColors.WHITE.index }
                        setFont(font)
                    }
                    if (section.tableHeaders.isNotEmpty()) {
                        val hRow = sheet.createRow(rowIdx++)
                        section.tableHeaders.forEachIndexed { col, h ->
                            val cell = hRow.createCell(col)
                            cell.setCellValue(h)
                            cell.cellStyle = headerStyle
                        }
                    }
                    section.tableRows.forEach { rData ->
                        val r = sheet.createRow(rowIdx++)
                        rData.forEachIndexed { col, v ->
                            val cell = r.createCell(col)
                            val dVal = v.toDoubleOrNull()
                            if (dVal != null) cell.setCellValue(dVal) else cell.setCellValue(v)
                        }
                    }
                    rowIdx++
                }
            }
        }

        for (i in 0 until 10) sheet.autoSizeColumn(i)
        file.outputStream().use { workbook.write(it) }
        workbook.close()
    }

    private fun bytearrayOf(vararg bytes: Byte) = bytes

    /** 创建 Word (.docx) 文档 */
    private fun createDocxDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String,
        preset: String,
        context: android.content.Context
    ) {
        val doc = org.apache.poi.xwpf.usermodel.XWPFDocument()
        val themeColor = themeHex.replace("#", "")

        if (title.isNotEmpty()) {
            val p = doc.createParagraph()
            p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
            val r = p.createRun()
            r.setText(title)
            r.isBold = true
            r.fontSize = 28
            r.color = themeColor
        }

        for (section in sections) {
            when (section.type) {
                "heading" -> {
                    val p = doc.createParagraph()
                    p.spacingBefore = 200
                    val r = p.createRun()
                    r.setText(section.content)
                    r.isBold = true
                    r.fontSize = if (section.level == 1) 18 else 14
                    if (section.level == 1) r.color = themeColor
                }
                "text" -> {
                    val p = doc.createParagraph()
                    p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH
                    val r = p.createRun()
                    r.setText(section.content)
                    r.fontSize = 11
                }
                "image" -> {
                    val imgFile = resolveSafePath(context, section.content)
                    if (imgFile?.exists() == true) {
                        imgFile.inputStream().use { stream ->
                            val p = doc.createParagraph()
                            p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                            val r = p.createRun()
                            val format = if (section.content.endsWith(".png", true)) org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG else org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG
                            r.addPicture(stream, format, section.content, org.apache.poi.util.Units.toEMU(400.0), org.apache.poi.util.Units.toEMU(300.0))
                        }
                    }
                }
                "table" -> {
                    val totalRows = (if (section.tableHeaders.isNotEmpty()) 1 else 0) + section.tableRows.size
                    val colCount = maxOf(section.tableHeaders.size, section.tableRows.maxOfOrNull { it.size } ?: 0)
                    if (totalRows > 0 && colCount > 0) {
                        val table = doc.createTable(totalRows, colCount)
                        table.setWidth("100%")
                        var rIdx = 0
                        if (section.tableHeaders.isNotEmpty()) {
                            val hRow = table.getRow(rIdx++)
                            section.tableHeaders.forEachIndexed { c, h ->
                                val cell = hRow.getCell(c) ?: hRow.addNewTableCell()
                                cell.setColor(themeColor)
                                val p = cell.addParagraph()
                                p.createRun().apply { setText(h); isBold = true; color = "FFFFFF" }
                                if (cell.paragraphs.size > 1) cell.removeParagraph(0)
                            }
                        }
                        section.tableRows.forEach { rData ->
                            val row = table.getRow(rIdx++) ?: table.createRow()
                            rData.forEachIndexed { c, v ->
                                val cell = row.getCell(c) ?: row.addNewTableCell()
                                val p = cell.addParagraph()
                                p.createRun().setText(v)
                                if (cell.paragraphs.size > 1) cell.removeParagraph(0)
                            }
                        }
                    }
                }
                "page_break" -> {
                    doc.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
                }
            }
        }

        file.outputStream().use { doc.write(it) }
        doc.close()
    }

    /** 创建 PowerPoint (.pptx) 文档 */
    private fun createPptxDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String,
        preset: String,
        context: android.content.Context
    ) {
        val ppt = org.apache.poi.xslf.usermodel.XMLSlideShow()
        val themeColor = hexToColor(themeHex, android.graphics.Color.parseColor("#4682B4"))

        // 封面
        val titleLayout = ppt.slideMasters[0].getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE)
        val titleSlide = ppt.createSlide(titleLayout)
        titleSlide.placeholders[0].text = title

        var currentSlide: org.apache.poi.xslf.usermodel.XSLFSlide? = null
        val contentLayout = ppt.slideMasters[0].getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT)

        for (section in sections) {
            when (section.type) {
                "page_break" -> {
                    currentSlide = ppt.createSlide(contentLayout)
                }
                "heading" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    currentSlide?.placeholders?.getOrNull(0)?.text = section.content
                }
                "text" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    val ph = currentSlide?.placeholders?.getOrNull(1)
                    if (ph != null) {
                        val p = ph.addNewTextParagraph()
                        p.isBullet = true
                        p.addNewTextRun().setText(section.content)
                    }
                }
                "image" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    val imgFile = resolveSafePath(context, section.content)
                    if (imgFile?.exists() == true) {
                        val data = imgFile.readBytes()
                        val format = if (section.content.endsWith(".png", true)) org.apache.poi.sl.usermodel.PictureData.PictureType.PNG else org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG
                        val pic = ppt.addPicture(data, format)
                        currentSlide?.createPicture(pic)
                    }
                }
                "table" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    try {
                        val table = currentSlide?.createTable()
                        if (table != null) {
                            if (section.tableHeaders.isNotEmpty()) {
                                val hRow = table.addRow()
                                section.tableHeaders.forEach { h ->
                                    val cell = hRow.addCell()
                                    cell.text = h
                                }
                            }
                            section.tableRows.forEach { rData ->
                                val row = table.addRow()
                                rData.forEach { v -> row.addCell().text = v }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("BuiltinToolHandler", "Failed to create table in PPTX due to missing AWT on Android, falling back to text representation", t)
                        val ph = currentSlide?.placeholders?.getOrNull(1)
                        if (ph != null) {
                            val p = ph.addNewTextParagraph()
                            p.addNewTextRun().setText("[表格数据 (暂不支持直接渲染表格)]:")
                            if (section.tableHeaders.isNotEmpty()) {
                                val hParagraph = ph.addNewTextParagraph()
                                hParagraph.addNewTextRun().setText(section.tableHeaders.joinToString(" | "))
                            }
                            section.tableRows.forEach { rData ->
                                val rParagraph = ph.addNewTextParagraph()
                                rParagraph.addNewTextRun().setText(rData.joinToString(" | "))
                            }
                        }
                    }
                }
            }
        }

        file.outputStream().use { ppt.write(it) }
        ppt.close()
    }

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
     * 将用户提供的路径解析为绝对路径，并验证它在沙盒内。
     * - 相对路径：resolve 到 OmniChat/files/ 下，并检查不越界
     * - 绝对路径：必须在沙盒白名单内（OmniChat/files/ 或 OmniChat/mcp/），否则拒绝
     *   注意：沙盒外的绝对路径访问应通过 McpFilePermissionHook 在调用前弹窗授权，
     *   但 resolveSafePath 本身不做异步弹窗，只做最终的安全兜底。
     * @return 解析后的 File，或 null（路径非法时）
     */
    private fun resolveSafePath(context: Context, path: String): File? {
        if (path.contains("..")) return null

        val file = File(path)
        if (file.isAbsolute) {
            // 绝对路径：只允许访问沙盒白名单目录
            val canonical = try { file.canonicalPath } catch (_: Exception) { return null }
            val externalDir = Environment.getExternalStorageDirectory()
            val allowedRoots = listOf(
                File(externalDir, "OmniChat/files").canonicalPath,
                File(externalDir, "OmniChat/mcp").canonicalPath,
                context.filesDir.canonicalPath,
                context.cacheDir.canonicalPath
            )
            val inSandbox = allowedRoots.any { canonical.startsWith(it) }
            return if (inSandbox) file.canonicalFile else null
        }

        val root = getFilesRoot(context)
        val normalized = path.ifEmpty { "." }
        val resolved = File(root, normalized).canonicalFile
        // 确保解析后的相对路径仍在沙盒内
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

    // 使用 UiFieldRegistry 生成 capabilities 响应，消除硬编码字段列表
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

    /**
     * 将文本拆分为 token 集合：中文字符 + 中文字符 bigram + 英文/数字整词。
     * 用于 search_memory 的中文友好匹配。
     */
    private fun bigramTokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val cjkRange = '一'..'鿿'
        val buffer = StringBuilder()

        for (ch in text) {
            if (ch in cjkRange) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch in "，。！？、；：\u201c\u201d\u2018\u2019（）【】《》,.!?;:\"'()[]<>") {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString().lowercase())
        }

        // 中文字符 bigram（仅对原文中相邻的 CJK 字符生成）
        // WHY: 原实现用 text.filter 提取所有 CJK 字符再拼接生成 bigram，
        // 导致 "用户Kotlin编程" 产生虚假 bigram "户编"（"户"和"编"被 "Kotlin" 隔开）。
        // 改为遍历原文，只对连续的 CJK 字符生成 bigram。
        var prevCjk: Char? = null
        for (ch in text) {
            if (ch in cjkRange) {
                if (prevCjk != null) {
                    tokens.add("$prevCjk$ch")
                }
                prevCjk = ch
            } else {
                prevCjk = null
            }
        }

        return tokens
    }
}
