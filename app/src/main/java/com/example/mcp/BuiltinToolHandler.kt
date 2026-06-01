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

    /**
     * Interface for accessing workspace state from MCP tools.
     * Decouples MCP runtime from concrete TeamManager implementation.
     */
    interface WorkspaceProvider {
        fun getAgentTool(): com.example.workspace.AgentTool?
        fun getOrchestratorContext(): com.example.workspace.AgentContext?
        fun getSandboxPath(): String?
        fun getAgentRegistry(): com.example.workspace.AgentRegistry
        fun getMailboxService(): com.example.workspace.mailbox.MailboxService
        fun getTeamName(): String?
    }

    @Volatile
    var workspaceProvider: WorkspaceProvider? = null

    // WHY: 由 WorkspaceViewModel 在创建/清理 TeamManager 时设置，供 scratchpad 工具访问
    @Volatile
    var teamManager: com.example.workspace.TeamManager? = null
        set(value) {
            field = value
            workspaceProvider = if (value != null) {
                object : WorkspaceProvider {
                    override fun getAgentTool() = value.getAgentTool()
                    override fun getOrchestratorContext() = value.getOrchestratorContext()
                    override fun getSandboxPath() = value.getSandboxPath()
                    override fun getAgentRegistry() = value.agentRegistry
                    override fun getMailboxService() = value.mailboxService
                    override fun getTeamName() = value.teamState.value?.teamName
                }
            } else null
        }

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
            "reset_ui_to_default" -> handleAdjustUi(context, JSONObject().apply { put("resetToDefault", true) })
            "adjust_ui" -> handleAdjustUi(context, arguments)
            "get_current_time" -> handleGetCurrentTime(arguments)
            "color_scheme" -> handleColorScheme(context, arguments)
            "search_memory" -> handleSearchMemory(context, arguments)
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
            "file_copy" -> handleFileCopy(context, arguments)
            "file_mkdir" -> handleFileMkdir(context, arguments)
            "create_document" -> handleCreateDocument(context, arguments)
            "ask_user" -> handleAskUser(context, arguments, sessionId)
            "create_timer" -> handleCreateTimer(context, arguments, sessionId)
            "cancel_timer" -> handleCancelTimer(context, arguments)
            "list_timers" -> handleListTimers(context)
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
            "set_tool_display_mode" -> handleSetToolDisplayMode(context, arguments)
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
        val sendTool = com.example.workspace.SendMessageTool(manager.agentRegistry, manager.mailboxService)
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

        // ── 字体字段（原 adjust_font 合并至此） ─────────────────────────────
        val validFontFamilies = setOf("default", "serif", "monospace", "cursive")

        if (arguments.has("fontSizeScale")) {
            val fs = arguments.optDouble("fontSizeScale", current.fontSizeScale.toDouble())
                .toFloat().coerceIn(0.75f, 1.5f)
            if (fs != current.fontSizeScale) {
                next = next.copy(fontSizeScale = fs)
                changed += "fontSizeScale"
            }
        }
        if (arguments.has("chatFontSizeScale")) {
            val cfs = arguments.optDouble("chatFontSizeScale", current.chatFontSizeScale.toDouble())
                .toFloat().coerceIn(0.75f, 1.5f)
            if (cfs != current.chatFontSizeScale) {
                next = next.copy(chatFontSizeScale = cfs)
                changed += "chatFontSizeScale"
            }
        }
        if (arguments.has("fontFamily")) {
            val ff = arguments.optString("fontFamily", "").trim().lowercase()
            if (ff.isNotEmpty() && ff in validFontFamilies && ff != current.fontFamily) {
                next = next.copy(fontFamily = ff)
                changed += "fontFamily"
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

    private suspend fun handleColorScheme(context: Context, arguments: JSONObject): JSONObject {
        val action = arguments.optString("action").trim().lowercase()
        if (action !in listOf("save", "list", "apply", "delete")) {
            return errorResponse("参数 'action' 必须是 save、list、apply 或 delete 之一。")
        }

        val repository = getRepository(context)

        return when (action) {
            "save" -> {
                val name = arguments.optString("name").trim()
                val desc = arguments.optString("description").trim()
                if (name.isBlank()) return errorResponse("保存失败：name 不能为空。")
                val count = repository.getColorSchemePresetCount()
                if (count >= ColorSchemePreset.MAX_PRESETS) {
                    val existing = repository.getAllColorSchemePresets()
                    val list = existing.joinToString("\n") { "• [${it.schemeId}] ${it.name}" }
                    return errorResponse("保存失败：已达到最多 ${ColorSchemePreset.MAX_PRESETS} 个方案上限。\n\n当前已保存：\n$list\n\n请先用 action=\"delete\" 删除一个方案。")
                }
                val current = repository.getUISettings() ?: UISettings()
                val schemeId = UUID.randomUUID().toString()
                val preset = ColorSchemePreset.fromUISettings(schemeId, name.take(30), desc.take(100), current)
                repository.insertColorSchemePreset(preset)
                successResponse("配色方案「${preset.name}」已保存。\nschemeId: $schemeId\n当前已保存 ${count + 1}/${ColorSchemePreset.MAX_PRESETS} 个方案。")
            }
            "list" -> {
                val presets = repository.getAllColorSchemePresets()
                if (presets.isEmpty()) {
                    return successResponse("当前没有已保存的配色方案。可以先用 adjust_ui 调整配色，再用 color_scheme(action=\"save\") 保存。")
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
                successResponse(text.trimEnd())
            }
            "apply" -> {
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isBlank()) return errorResponse("应用失败：schemeId 不能为空。请先用 action=\"list\" 获取可用的 schemeId。")
                val preset = repository.getColorSchemePresetById(schemeId)
                    ?: return errorResponse("应用失败：找不到 schemeId=$schemeId 的方案。请用 action=\"list\" 确认可用的 schemeId。")
                repository.upsertUISettings(preset.toUISettings())
                successResponse("配色方案「${preset.name}」已应用，界面立即生效。\n概述：${preset.description}")
            }
            else -> { // delete
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isBlank()) return errorResponse("删除失败：schemeId 不能为空。")
                val preset = repository.getColorSchemePresetById(schemeId)
                    ?: return errorResponse("删除失败：找不到 schemeId=$schemeId 的方案。")
                repository.deleteColorSchemePreset(schemeId)
                val remaining = repository.getColorSchemePresetCount()
                successResponse("配色方案「${preset.name}」已删除。当前剩余 $remaining/${ColorSchemePreset.MAX_PRESETS} 个方案。")
            }
        }
    }

    // ── 记忆工具 ────────────────────────────────────────────────────────────

    private suspend fun handleSearchMemory(context: Context, arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return errorResponse("搜索失败：query 不能为空。")
        }
        if (query.any { it.code > 127 }) {
            return errorResponse("search_memory only supports English queries. Please search using English keywords.")
        }
        val tagFilter = arguments.optString("tag").trim().lowercase().takeIf { it.isNotBlank() }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
        val repository = getRepository(context)

        // 确定候选集：按 tag 预过滤或全量
        val validTags = setOf("preference", "fact", "instruction", "habit", "context")
        val candidates: List<com.example.data.MemoryItem>
        val totalCount: Int
        if (tagFilter != null && tagFilter in validTags) {
            candidates = repository.searchMemoriesByTag(tagFilter)
            totalCount = candidates.size
        } else {
            // 无 tag 过滤时用 SQL LIKE 缩小候选集
            val keywords = query.split(" ").filter { it.isNotBlank() }
            if (keywords.isNotEmpty()) {
                totalCount = repository.getAllMemories().size
                candidates = keywords.flatMap { repository.searchMemoriesByKeyword(it) }.distinctBy { it.id }
            } else {
                candidates = repository.getAllMemories()
                totalCount = candidates.size
            }
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
                var score = jaccard * mem.confidence
                // tag 匹配加成：如果记忆包含查询的 tag，相关度 ×1.2
                if (tagFilter != null && mem.tags.split(",").contains(tagFilter)) {
                    score *= 1.2
                }
                ScoredMemory(mem, score)
            }
            .sortedByDescending { it.score }
            .take(limit)

        val filterDesc = if (tagFilter != null) "，标签过滤：$tagFilter" else ""
        val text = buildString {
            appendLine("记忆库搜索结果（关键词：「$query」$filterDesc，共 $totalCount 条记忆，命中 ${scored.size} 条）：")
            appendLine()
            if (scored.isEmpty()) {
                appendLine("未找到与关键词相关的记忆。")
                appendLine("提示：可以尝试更换关键词，或直接浏览全部记忆（记忆库共 $totalCount 条）。")
            } else {
                scored.forEachIndexed { i, sm ->
                    val pinnedTag = if (sm.memory.pinned) " [已锁定]" else ""
                    val tagsDisplay = if (sm.memory.tags.isNotBlank()) " [${sm.memory.tags}]" else ""
                    appendLine("${i + 1}. [id=${sm.memory.id}, 置信度=${sm.memory.confidence}, 相关度=${String.format("%.2f", sm.score)}$pinnedTag$tagsDisplay]")
                    appendLine("   ${sm.memory.content}")
                }
            }
        }
        return successResponse(text.trimEnd())
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

    // ── 工具显示模式 ───────────────────────────────────────────────────────

    private suspend fun handleSetToolDisplayMode(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val silent = arguments.optBoolean("silent", false)
        repository.upsertUISettings(current.copy(silentToolCalls = silent, updatedAt = System.currentTimeMillis()))
        return if (silent) {
            successResponse("已开启静默模式。后续工具调用将以紧凑方式显示，不再展开详情卡片。如需恢复，请调用 set_tool_display_mode(silent=false)。")
        } else {
            successResponse("已关闭静默模式。后续工具调用将以正常详情卡片显示。")
        }
    }

    // ── 文件系统工具 ────────────────────────────────────────────────────────

    private suspend fun handleFileWrite(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val content = arguments.optString("content")
        val encoding = arguments.optString("encoding", "utf8")
        if (path.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolvePath(context, path)
            ?: return errorResponse("路径无效或权限被拒绝：$path")
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

    private suspend fun handleFileRead(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val encoding = arguments.optString("encoding", "utf8")
        val maxBytes = arguments.optInt("maxBytes", 1024 * 1024).coerceIn(1, 10 * 1024 * 1024)
        val startLine = arguments.optInt("startLine", 0)
        val endLine = arguments.optInt("endLine", 0)
        if (path.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolvePath(context, path)
            ?: return errorResponse("路径无效或权限被拒绝：$path")
        if (!file.exists()) return errorResponse("文件不存在：$path")
        if (!file.isFile) return errorResponse("路径指向的不是文件：$path")
        return try {
            if (encoding == "base64") {
                // base64 模式：按字节读取
                val bytes = file.inputStream().use { stream ->
                    val buf = ByteArray(maxBytes)
                    val read = stream.read(buf)
                    if (read <= 0) ByteArray(0) else buf.copyOf(read)
                }
                val truncated = file.length() > maxBytes
                val resultText = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val suffix = if (truncated) "\n\n[文件已截断，仅显示前 $maxBytes 字节，完整大小：${file.length()} 字节]" else ""
                successResponse(resultText + suffix)
            } else if (startLine > 0 || endLine > 0) {
                // 按行范围读取
                val lines = file.readLines(Charsets.UTF_8)
                val from = (startLine - 1).coerceIn(0, lines.size)
                val to = if (endLine > 0) endLine.coerceIn(from, lines.size) else lines.size
                val selected = lines.subList(from, to)
                val text = selected.joinToString("\n")
                val byteSize = text.toByteArray(Charsets.UTF_8).size
                if (byteSize > maxBytes) {
                    successResponse(text.take(maxBytes) + "\n\n[内容已截断，超过 $maxBytes 字节限制]")
                } else {
                    successResponse(text)
                }
            } else {
                // 按字节读取（默认）
                val bytes = file.inputStream().use { stream ->
                    val buf = ByteArray(maxBytes)
                    val read = stream.read(buf)
                    if (read <= 0) ByteArray(0) else buf.copyOf(read)
                }
                val truncated = file.length() > maxBytes
                val resultText = String(bytes, Charsets.UTF_8)
                val suffix = if (truncated) "\n\n[文件已截断，仅显示前 $maxBytes 字节，完整大小：${file.length()} 字节]" else ""
                successResponse(resultText + suffix)
            }
        } catch (e: Exception) {
            errorResponse("读取文件失败：${e.localizedMessage}")
        }
    }

    private suspend fun handleFileAppend(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val content = arguments.optString("content")
        if (path.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolvePath(context, path)
            ?: return errorResponse("路径无效或权限被拒绝：$path")
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

    private suspend fun handleFileDelete(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val recursive = arguments.optBoolean("recursive", false)
        if (path.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolvePath(context, path)
            ?: return errorResponse("路径无效或权限被拒绝：$path")
        if (!file.exists()) return errorResponse("路径不存在：$path")
        return try {
            val success = if (recursive) deleteRecursive(file) else file.delete()
            if (success) successResponse("已删除：${file.absolutePath}")
            else errorResponse("删除失败，目录可能不为空（如需递归删除请传 recursive=true）。")
        } catch (e: Exception) {
            errorResponse("删除失败：${e.localizedMessage}")
        }
    }

    private suspend fun handleFileList(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path", "").trim()
        val showHidden = arguments.optBoolean("showHidden", false)
        val recursive = arguments.optBoolean("recursive", false)
        val maxDepth = arguments.optInt("maxDepth", 3).coerceIn(1, 10)
        val dir = resolvePath(context, path.ifEmpty { "." })
            ?: return errorResponse("路径无效或权限被拒绝：${path.ifEmpty { "/" }}")
        if (!dir.exists()) return errorResponse("目录不存在：${path.ifEmpty { "/" }}")
        if (!dir.isDirectory) return errorResponse("路径指向的不是目录：$path")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun listDir(d: File, depth: Int): String {
            val entries = d.listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            return buildString {
                entries.forEach { entry ->
                    val indent = "  ".repeat(depth)
                    val type = if (entry.isDirectory) "📁" else "📄"
                    val size = if (entry.isFile) " (${entry.length()} B)" else ""
                    val modified = sdf.format(Date(entry.lastModified()))
                    appendLine("$indent$type ${entry.name}$size  [$modified]")
                    if (recursive && entry.isDirectory && depth + 1 < maxDepth) {
                        append(listDir(entry, depth + 1))
                    }
                }
            }
        }

        val listing = listDir(dir, 0)
        val text = buildString {
            appendLine("目录：${dir.absolutePath}")
            if (recursive) appendLine("（递归，最大深度 $maxDepth）")
            appendLine()
            if (listing.isEmpty()) appendLine("（空目录）")
            else append(listing.trimEnd())
        }
        return successResponse(text.trimEnd())
    }

    private suspend fun handleFileSearch(context: Context, arguments: JSONObject): JSONObject {
        val namePattern = arguments.optString("namePattern").trim().ifEmpty { null }
        val contentQuery = arguments.optString("contentQuery").trim().ifEmpty { null }
        val directory = arguments.optString("directory").trim()
        val maxResults = arguments.optInt("maxResults", 20).coerceIn(1, 100)
        val isRegex = arguments.optBoolean("isRegex", false)
        val contextLines = arguments.optInt("contextLines", 0).coerceIn(0, 10)
        if (namePattern == null && contentQuery == null) {
            return errorResponse("请至少提供 namePattern 或 contentQuery 之一。")
        }
        val searchRoot = resolvePath(context, directory.ifEmpty { "." })
            ?: return errorResponse("路径无效或权限被拒绝：${directory.ifEmpty { "/" }}")
        if (!searchRoot.exists()) return errorResponse("搜索目录不存在：${directory.ifEmpty { "/" }}")
        val contentRegex = if (contentQuery != null && isRegex) {
            try { Regex(contentQuery, RegexOption.IGNORE_CASE) } catch (e: Exception) {
                return errorResponse("无效的正则表达式：${e.message}")
            }
        } else null
        val results = mutableListOf<JSONObject>()
        searchFiles(searchRoot, namePattern, contentQuery, contentRegex, contextLines, results, maxResults)
        val text = buildString {
            appendLine("搜索范围：${searchRoot.absolutePath}")
            if (namePattern != null) appendLine("文件名模式：$namePattern")
            if (contentQuery != null) appendLine("内容关键词：$contentQuery${if (isRegex) " (正则)" else ""}")
            appendLine("找到 ${results.size} 个结果${if (results.size >= maxResults) "（已达上限 $maxResults）" else ""}：")
            appendLine()
            results.forEach { r ->
                val absPath = r.optString("path")
                append("• $absPath")
                val matchLines = r.optJSONArray("matchLines")
                if (matchLines != null && matchLines.length() > 0) {
                    val lines = (0 until matchLines.length()).map { matchLines.getInt(it) }
                    append("  (匹配行: ${lines.joinToString(", ")})")
                }
                val contextSnippets = r.optJSONArray("contextSnippets")
                if (contextSnippets != null && contextSnippets.length() > 0) {
                    appendLine()
                    for (i in 0 until contextSnippets.length()) {
                        appendLine("    ${contextSnippets.getString(i)}")
                    }
                } else {
                    appendLine()
                }
            }
        }
        return successResponse(text.trimEnd())
    }

    private suspend fun handleFileInfo(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolvePath(context, path)
            ?: return errorResponse("路径无效或权限被拒绝：$path")
        if (!file.exists()) return errorResponse("路径不存在：$path")
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

    private suspend fun handleFileMove(context: Context, arguments: JSONObject): JSONObject {
        val srcPath = arguments.optString("sourcePath").trim()
        val dstPath = arguments.optString("destinationPath").trim()
        val overwrite = arguments.optBoolean("overwrite", false)
        if (srcPath.isEmpty()) return errorResponse("参数 'sourcePath' 不能为空。")
        if (dstPath.isEmpty()) return errorResponse("参数 'destinationPath' 不能为空。")
        val src = resolvePath(context, srcPath)
            ?: return errorResponse("源路径无效或权限被拒绝：$srcPath")
        val dst = resolvePath(context, dstPath)
            ?: return errorResponse("目标路径无效或权限被拒绝：$dstPath")
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

    private suspend fun handleFileCopy(context: Context, arguments: JSONObject): JSONObject {
        val srcPath = arguments.optString("sourcePath").trim()
        val dstPath = arguments.optString("destinationPath").trim()
        val overwrite = arguments.optBoolean("overwrite", false)
        if (srcPath.isEmpty()) return errorResponse("参数 'sourcePath' 不能为空。")
        if (dstPath.isEmpty()) return errorResponse("参数 'destinationPath' 不能为空。")
        val src = resolvePath(context, srcPath)
            ?: return errorResponse("源路径无效或权限被拒绝：$srcPath")
        val dst = resolvePath(context, dstPath)
            ?: return errorResponse("目标路径无效或权限被拒绝：$dstPath")
        if (!src.exists()) return errorResponse("源路径不存在：$srcPath")
        if (dst.exists() && !overwrite) return errorResponse("目标路径已存在：$dstPath（如需覆盖请传 overwrite=true）。")
        return try {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = overwrite)
            } else {
                src.copyTo(dst, overwrite = overwrite)
            }
            successResponse("已复制：\n  从：${src.absolutePath}\n  到：${dst.absolutePath}")
        } catch (e: Exception) {
            errorResponse("复制失败：${e.localizedMessage}")
        }
    }

    private suspend fun handleFileMkdir(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return errorResponse("参数 'path' 不能为空。")
        val file = resolvePath(context, path)
            ?: return errorResponse("路径无效或权限被拒绝：$path")
        return try {
            if (file.exists()) {
                if (file.isDirectory) successResponse("目录已存在：${file.absolutePath}")
                else errorResponse("路径已存在且不是目录：$path")
            } else if (file.mkdirs()) {
                successResponse("目录已创建：${file.absolutePath}")
            } else {
                errorResponse("创建目录失败：$path")
            }
        } catch (e: Exception) {
            errorResponse("创建目录失败：${e.localizedMessage}")
        }
    }

    // ── 文档创建工具 ────────────────────────────────────────────────────────

    private suspend fun handleCreateDocument(context: Context, arguments: JSONObject): JSONObject {
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

        val file = resolvePath(context, relativePath)
            ?: return errorResponse("路径无效或权限被拒绝：$relativePath")

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
        val repeatIntervalSec = arguments.optLong("repeat_interval_seconds", 0L)

        if (delaySeconds < 1) {
            return errorResponse("参数 'delay_seconds' 必须 ≥ 1。")
        }
        if (repeatIntervalSec < 0) {
            return errorResponse("参数 'repeat_interval_seconds' 不能为负数。")
        }
        if (repeatIntervalSec > 0 && repeatIntervalSec < 1) {
            return errorResponse("参数 'repeat_interval_seconds' 如提供则必须 ≥ 1。")
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
            label = label,
            repeatIntervalSec = repeatIntervalSec
        )

        val humanDelay = formatDuration(delaySeconds)

        val repeatInfo = if (repeatIntervalSec > 0) {
            "\n• 重复间隔：每 ${formatDuration(repeatIntervalSec)}"
        } else ""

        return successResponse(
            "✅ 定时器已创建！\n\n" +
            "• 定时器 ID：`$timerId`\n" +
            "• 触发时间：$humanDelay 后" +
            repeatInfo + "\n" +
            "• 提醒内容：$message\n\n" +
            "到时间后会在聊天中插入提醒消息，并发送系统通知。\n" +
            "定时器在应用关闭或设备重启后仍然有效。\n" +
            "如需取消，请调用 cancel_timer 并传入 timer_id: \"$timerId\""
        )
    }

    private fun handleCancelTimer(context: Context, arguments: JSONObject): JSONObject {
        val timerId = arguments.optString("timer_id").trim()
        if (timerId.isEmpty()) {
            return errorResponse("参数 'timer_id' 不能为空。请先调用 list_timers 查看当前待触发的定时器。")
        }
        val cancelled = TimerManager.cancelTimer(context, timerId)
        return if (cancelled) {
            successResponse("✅ 定时器 `$timerId` 已成功取消。")
        } else {
            errorResponse("找不到 timer_id=\"$timerId\" 的定时器。它可能已经触发或不存在。\n\n调用 list_timers 可查看当前所有待触发的定时器。")
        }
    }

    private fun handleListTimers(context: Context): JSONObject {
        val timers = TimerManager.listTimers(context)
        if (timers.isEmpty()) {
            return successResponse("当前没有待触发的定时器。")
        }
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val text = buildString {
            appendLine("当前待触发的定时器（共 ${timers.size} 个）：")
            appendLine()
            timers.forEachIndexed { i, t ->
                val remainingMs = (t.fireAtMs - now).coerceAtLeast(0L)
                val remainingSec = remainingMs / 1000
                val humanRemaining = formatDuration(remainingSec)
                val fireTime = sdf.format(java.util.Date(t.fireAtMs))
                val type = if (t.repeatIntervalMs > 0) "🔁 重复(每 ${formatDuration(t.repeatIntervalMs / 1000)})" else "⏳ 单次"
                appendLine("${i + 1}. ID: `${t.timerId}` [$type]")
                appendLine("   标签：${t.label}")
                appendLine("   内容：${t.message}")
                appendLine("   剩余：$humanRemaining（$fireTime 触发）")
            }
        }
        return successResponse(text.trimEnd())
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours} 小时 ")
            if (minutes > 0) append("${minutes} 分钟 ")
            if (seconds > 0 || isEmpty()) append("${seconds} 秒")
        }.trim()
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
    private suspend fun createPdfDocument(
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
                    val imgFile = resolvePath(context, section.content)
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
    private suspend fun createXlsxDocument(
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
    private suspend fun createDocxDocument(
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
                    val imgFile = resolvePath(context, section.content)
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
    private suspend fun createPptxDocument(
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
                    val imgFile = resolvePath(context, section.content)
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
     * 返回外部存储根目录 (/sdcard)。
     * 相对路径基于此目录解析。
     */
    private fun getFilesRoot(context: Context): File {
        return Environment.getExternalStorageDirectory()
    }

    /**
     * 将用户提供的路径解析为绝对路径，并通过 McpPermissionManager 进行权限检查。
     * - 相对路径：resolve 到 /sdcard/ 下
     * - 绝对路径：直接使用
     * - 拒绝 '..' 路径穿越
     * - 通过权限弹窗授权沙盒外路径访问
     * @return 解析后的 File，或 null（路径非法或权限被拒绝时）
     */
    private suspend fun resolvePath(context: Context, path: String): File? {
        if (path.contains("..")) return null

        val root = getFilesRoot(context)
        val file = File(path)
        val resolved = if (file.isAbsolute) {
            file.canonicalFile
        } else {
            File(root, path.ifEmpty { "." }).canonicalFile
        }

        val canonicalPath = try {
            resolved.canonicalPath
        } catch (_: Exception) { return null }

        val allowed = McpPermissionManager.checkAndRequestPermission(context, canonicalPath)
        return if (allowed) resolved else null
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
        contentRegex: Regex?,
        contextLines: Int,
        results: MutableList<JSONObject>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (results.size >= maxResults) break
            if (entry.isDirectory) {
                searchFiles(entry, namePattern, contentQuery, contentRegex, contextLines, results, maxResults)
            } else {
                val nameMatch = namePattern == null || matchesGlob(entry.name, namePattern)
                if (!nameMatch) continue
                if (contentQuery != null || contentRegex != null) {
                    // 只搜索文本文件（< 2MB）
                    if (entry.length() > 2 * 1024 * 1024) continue
                    val text = try { entry.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
                    val lines = text.lines()
                    val matchedIndices = if (contentRegex != null) {
                        lines.mapIndexedNotNull { idx, line ->
                            if (contentRegex.containsMatchIn(line)) idx else null
                        }
                    } else {
                        lines.mapIndexedNotNull { idx, line ->
                            if (line.contains(contentQuery!!, ignoreCase = true)) idx else null
                        }
                    }
                    if (matchedIndices.isEmpty()) continue
                    val matchLines = matchedIndices.take(3).map { it + 1 }
                    val result = JSONObject().apply {
                        put("path", entry.path)
                        put("matchLines", JSONArray(matchLines))
                    }
                    if (contextLines > 0) {
                        val snippets = JSONArray()
                        for (matchIdx in matchedIndices.take(3)) {
                            val from = (matchIdx - contextLines).coerceAtLeast(0)
                            val to = (matchIdx + contextLines + 1).coerceAtMost(lines.size)
                            for (i in from until to) {
                                val marker = if (i == matchIdx) "→" else " "
                                snippets.put("$marker ${i + 1}: ${lines[i]}")
                            }
                        }
                        result.put("contextSnippets", snippets)
                    }
                    results.add(result)
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
                    put("text", "UI 主题能力清单。使用 adjust_ui 传入想改的字段即可，未传字段保持当前值。传 resetToDefault=true 可还原全部设置。")
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
