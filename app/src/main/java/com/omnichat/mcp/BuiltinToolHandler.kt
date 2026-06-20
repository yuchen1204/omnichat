package com.omnichat.mcp

import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import com.omnichat.R
import android.util.Base64
import android.webkit.MimeTypeMap
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.FileAccessType
import com.omnichat.data.UISettings
import com.omnichat.agent.SubAgent
import com.omnichat.agent.WorkflowEngine
import com.omnichat.agent.WorkflowStep
import com.omnichat.agent.StepResult
import com.omnichat.agent.WorkflowStepStatus
import com.omnichat.agent.MessageBus
import com.omnichat.data.ColorSchemePreset
import com.omnichat.data.ColorSchemePreset.Companion.toUISettings
import com.omnichat.ui.theme.UiStrings
import com.omnichat.ui.theme.UiStrings.Companion.toJson
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

    private fun str(context: Context, @StringRes resId: Int): String = context.getString(resId)
    private fun str(context: Context, @StringRes resId: Int, vararg args: Any): String = context.getString(resId, *args)

    // 提取公共 Repository 工厂方法，消除 13 处重复的 AppDatabase.getDatabase + AppRepository 实例化
    private fun getRepository(context: Context): AppRepository {
        return AppRepository(AppDatabase.getDatabase(context))
    }

    suspend fun handleBuiltinTool(context: Context, toolName: String, arguments: JSONObject, sessionId: Long? = null): JSONObject {
        return when (toolName) {
            "get_ui_capabilities" -> handleGetUiCapabilities(context)
            "reset_ui_to_default" -> handleAdjustUi(context, JSONObject().apply { put("resetToDefault", true) })
            "adjust_ui" -> handleAdjustUi(context, arguments)
            "get_current_time" -> handleGetCurrentTime(context, arguments)
            "color_scheme" -> handleColorScheme(context, arguments)
            "search_memory" -> handleSearchMemory(context, arguments)
            "mark_reminded" -> handleMarkReminded(context, arguments)
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
            "ask_user" -> handleAskUser(context, arguments, sessionId)
            "create_timer" -> handleCreateTimer(context, arguments, sessionId)
            "cancel_timer" -> handleCancelTimer(context, arguments)
            "list_timers" -> handleListTimers(context)
            "list_mcp_tool_groups" -> handleListMcpToolGroups(context)
            "configure_mcp_tool_groups" -> handleConfigureMcpToolGroups(context, arguments)
            "set_tool_display_mode" -> handleSetToolDisplayMode(context, arguments)
            "delegate_task" -> handleDelegateTask(context, arguments, sessionId)
            "check_task_status" -> handleCheckTaskStatus(context, arguments)
            "send_agent_message" -> handleSendAgentMessage(context, arguments)
            "read_agent_inbox" -> handleReadAgentInbox(context)
            "run_workflow" -> handleRunWorkflow(context, arguments, sessionId)
            else -> errorResponse(str(context, R.string.tool_unknown_builtin, toolName))
        }
    }

    // ── UI 工具 ────────────────────────────────────────────────────────────

    private suspend fun handleGetUiCapabilities(context: Context): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        return buildUiCapabilitiesResponse(context, current)
    }

    // 使用 UiFieldRegistry 循环处理颜色字段，消除 30 行重复的 hex() 调用和变更检测
    private suspend fun handleAdjustUi(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()

        if (arguments.optBoolean("resetToDefault", false)) {
            repository.upsertUISettings(UISettings())
            return successResponse(str(context, R.string.tool_ui_reset))
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

        val text = if (changed.isEmpty()) str(context, R.string.tool_ui_applied_no_changes)
        else str(context, R.string.tool_ui_applied, changed.size, changed.joinToString(", "))
        return successResponse(text)
    }

    private fun handleGetCurrentTime(context: Context, arguments: JSONObject): JSONObject {
        val tzId = arguments.optString("timezone").takeIf { it.isNotBlank() }
        val zone = try {
            if (tzId != null) java.time.ZoneId.of(tzId) else java.time.ZoneId.systemDefault()
        } catch (e: Exception) {
            java.time.ZoneId.systemDefault()
        }
        val now = ZonedDateTime.now(zone)
        val fullFmt = DateTimeFormatter.ofPattern(context.getString(R.string.tool_time_format_pattern), Locale.getDefault())
        val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val result = buildString {
            appendLine("${str(context, R.string.tool_time_info)}：")
            appendLine(str(context, R.string.tool_time_local, now.format(fullFmt)))
            appendLine(str(context, R.string.tool_time_zone, zone.id, now.format(DateTimeFormatter.ofPattern("xxx"))))
            appendLine(str(context, R.string.tool_time_iso, now.format(isoFmt)))
            appendLine(str(context, R.string.tool_time_unix, now.toEpochSecond()))
        }
        return successResponse(result.trim())
    }

    // ── 配色方案工具 ────────────────────────────────────────────────────────

    private suspend fun handleColorScheme(context: Context, arguments: JSONObject): JSONObject {
        val action = arguments.optString("action").trim().lowercase()
        if (action !in listOf("save", "list", "apply", "delete")) {
            return errorResponse(str(context, R.string.tool_color_action_invalid))
        }

        val repository = getRepository(context)

        return when (action) {
            "save" -> {
                val name = arguments.optString("name").trim()
                val desc = arguments.optString("description").trim()
                if (name.isBlank()) return errorResponse(str(context, R.string.tool_color_save_name_empty))
                val count = repository.getColorSchemePresetCount()
                if (count >= ColorSchemePreset.MAX_PRESETS) {
                    val existing = repository.getAllColorSchemePresets()
                    val list = existing.joinToString("\n") { "• [${it.schemeId}] ${it.name}" }
                    return errorResponse(str(context, R.string.tool_color_save_limit, ColorSchemePreset.MAX_PRESETS, list))
                }
                val current = repository.getUISettings() ?: UISettings()
                val schemeId = UUID.randomUUID().toString()
                val preset = ColorSchemePreset.fromUISettings(schemeId, name.take(30), desc.take(100), current)
                repository.insertColorSchemePreset(preset)
                successResponse(str(context, R.string.tool_color_saved, preset.name, schemeId, count + 1, ColorSchemePreset.MAX_PRESETS))
            }
            "list" -> {
                val presets = repository.getAllColorSchemePresets()
                if (presets.isEmpty()) {
                    return successResponse(str(context, R.string.tool_color_list_empty))
                }
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val text = buildString {
                    appendLine(str(context, R.string.tool_color_list_header, presets.size, ColorSchemePreset.MAX_PRESETS))
                    appendLine()
                    presets.forEachIndexed { i, p ->
                        appendLine("${i + 1}. 「${p.name}」")
                        appendLine("   schemeId:    ${p.schemeId}")
                        appendLine("   ${str(context, R.string.tool_color_list_desc, p.description)}")
                        appendLine("   ${str(context, R.string.tool_color_list_saved_at, sdf.format(Date(p.createdAt)))}")
                        appendLine("   ${str(context, R.string.tool_color_list_primary, p.primaryColor, p.backgroundColor)}")
                        appendLine("   ${str(context, R.string.tool_color_list_success, p.successColor, p.cornerRadiusDp, p.spacingMultiplier)}")
                    }
                }
                successResponse(text.trimEnd())
            }
            "apply" -> {
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isBlank()) return errorResponse(str(context, R.string.tool_color_apply_no_id))
                val preset = repository.getColorSchemePresetById(schemeId)
                    ?: return errorResponse(str(context, R.string.tool_color_apply_not_found, schemeId))
                repository.upsertUISettings(preset.toUISettings())
                successResponse(str(context, R.string.tool_color_applied, preset.name, preset.description))
            }
            else -> { // delete
                val schemeId = arguments.optString("schemeId").trim()
                if (schemeId.isBlank()) return errorResponse(str(context, R.string.tool_color_delete_no_id))
                val preset = repository.getColorSchemePresetById(schemeId)
                    ?: return errorResponse(str(context, R.string.tool_color_delete_not_found, schemeId))
                repository.deleteColorSchemePreset(schemeId)
                val remaining = repository.getColorSchemePresetCount()
                successResponse(str(context, R.string.tool_color_deleted, preset.name, remaining, ColorSchemePreset.MAX_PRESETS))
            }
        }
    }

    // ── 记忆工具 ────────────────────────────────────────────────────────────

    private suspend fun handleSearchMemory(context: Context, arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        if (query.isBlank()) {
            return errorResponse(str(context, R.string.tool_memory_query_empty))
        }
        // 中文搜索支持：bigramTokenize 已支持中文字符，无需限制查询语言
        val tagFilter = arguments.optString("tag").trim().lowercase().takeIf { it.isNotBlank() }
        val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
        val repository = getRepository(context)

        // 确定候选集：按 tag 预过滤或全量
        // 支持中英文 tag，统一转小写匹配
        val candidates: List<com.omnichat.data.MemoryItem>
        val totalCount: Int
        if (tagFilter != null) {
            // 直接按 tag 搜索，不再限制于预定义的 validTags
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

        val queryTokens = com.omnichat.memory.MemoryTokenizer.tokenize(query)

        data class ScoredMemory(val memory: com.omnichat.data.MemoryItem, val score: Double)

        val scored = candidates
            .mapNotNull { mem ->
                val memTokens = com.omnichat.memory.MemoryTokenizer.tokenize(mem.content)
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

        // Association expansion via BFS
        val depth = arguments.optInt("depth", 3).coerceIn(1, 5)
        val maxExpand = 10
        val expandedMemories = mutableListOf<Triple<com.omnichat.data.MemoryItem, String, Int>>()  // memory, label, depth
        val visited = scored.map { it.memory.id }.toMutableSet()

        val queue: java.util.LinkedList<Pair<Long, Int>> = java.util.LinkedList()  // memoryId, currentDepth
        for (sm in scored) {
            queue.add(sm.memory.id to 0)
        }

        while (queue.isNotEmpty() && expandedMemories.size < maxExpand) {
            val pollResult = queue.poll() ?: continue
            val (currentId, currentDepth) = pollResult
            if (currentDepth >= depth) continue

            val associations = repository.getAssociationsFor(currentId)
            for (assoc in associations) {
                val relatedId = when {
                    assoc.direction == "bidirectional" -> {
                        if (assoc.fromMemoryId == currentId) assoc.toMemoryId else assoc.fromMemoryId
                    }
                    assoc.fromMemoryId == currentId -> assoc.toMemoryId
                    else -> continue
                }
                if (relatedId in visited) continue
                visited.add(relatedId)

                val relatedMem = repository.getMemoryById(relatedId) ?: continue
                expandedMemories.add(Triple(relatedMem, assoc.relationLabel, currentDepth + 1))
                queue.add(relatedId to currentDepth + 1)
            }
        }

        val filterDesc = if (tagFilter != null) context.getString(R.string.tool_memory_tag_filter, tagFilter) else ""
        val text = buildString {
            appendLine(str(context, R.string.tool_memory_search_results, query, filterDesc, totalCount, scored.size))
            appendLine()
            if (scored.isEmpty()) {
                appendLine(str(context, R.string.tool_memory_no_results))
                appendLine(str(context, R.string.tool_memory_no_results_hint, totalCount))
            } else {
                scored.forEachIndexed { i, sm ->
                    val pinnedTag = if (sm.memory.pinned) str(context, R.string.tool_memory_pinned_tag) else ""
                    val tagsDisplay = if (sm.memory.tags.isNotBlank()) " [${sm.memory.tags}]" else ""
                    // 安全格式化：confidence 是 Int，score 是 Double，确保类型匹配
                    appendLine(str(context, R.string.tool_memory_entry_format, i + 1, sm.memory.id, sm.memory.confidence.toDouble(), sm.score, pinnedTag, tagsDisplay))
                    appendLine("   ${sm.memory.content}")
                }
            }
            if (expandedMemories.isNotEmpty()) {
                appendLine()
                appendLine(str(context, R.string.tool_memory_assoc_expansion_header, depth))
                expandedMemories.forEachIndexed { i, (mem, label, d) ->
                    val pinnedTag = if (mem.pinned) str(context, R.string.tool_memory_pinned_tag) else ""
                    appendLine(str(context, R.string.tool_memory_assoc_entry, label, mem.id, mem.confidence.toFloat(), mem.content))
                }
            }
        }
        return successResponse(text.trimEnd())
    }

    private suspend fun handleMarkReminded(context: Context, arguments: JSONObject): JSONObject {
        val memoryId = arguments.optLong("memory_id", -1L)
        if (memoryId <= 0) {
            return errorResponse(str(context, R.string.tool_mark_reminded_empty))
        }
        val repository = getRepository(context)
        val memory = repository.getMemoryById(memoryId)
            ?: return errorResponse(str(context, R.string.tool_mark_reminded_not_found))
        if (!memory.dueDate.isNullOrBlank()) {
            repository.markReminded(memoryId)
        }
        return successResponse(str(context, R.string.tool_mark_reminded_success))
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
            appendLine(str(context, R.string.tool_ui_text_header))
            if (hasQuery) {
                appendLine(str(context, R.string.tool_ui_text_filter, query))
            } else {
                appendLine(str(context, R.string.tool_ui_text_hint))
            }
            appendLine(str(context, R.string.tool_ui_text_format))
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
                        appendLine(str(context, R.string.tool_ui_text_key_label, key))
                        appendLine(str(context, R.string.tool_ui_text_default, defaultText))
                        appendLine(str(context, R.string.tool_ui_text_override, overrideText))
                    } else {
                        appendLine(str(context, R.string.tool_ui_text_key_label, key))
                        appendLine(str(context, R.string.tool_ui_text_default, defaultText))
                    }
                    appendLine()
                }
            }

            appendLine(str(context, R.string.tool_ui_text_stats_header))
            if (hasQuery) {
                appendLine(str(context, R.string.tool_ui_text_stats_filtered, matchCount, unionKeys.size))
            } else {
                appendLine(str(context, R.string.tool_ui_text_stats_all, unionKeys.size))
            }
            appendLine()
            appendLine(str(context, R.string.tool_ui_text_tips_header))
            appendLine(str(context, R.string.tool_ui_text_tip_modify))
            appendLine(str(context, R.string.tool_ui_text_tip_restore))
            appendLine(str(context, R.string.tool_ui_text_tip_reset_all))
        }

        return successResponse(text)
    }

    private suspend fun handleSetUiTexts(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val currentStrings = UiStrings.fromJson(current.uiStrings)

        if (arguments.optBoolean("resetAll", false)) {
            repository.upsertUISettings(current.copy(uiStrings = "{}", updatedAt = System.currentTimeMillis()))
            return successResponse(str(context, R.string.tool_ui_text_reset_all))
        }

        val updates = arguments.optJSONObject("updates")
        val deletes = arguments.optJSONArray("delete")
        if (updates == null && deletes == null) {
            return errorResponse(str(context, R.string.tool_ui_text_call_failed))
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
            appendLine(str(context, R.string.tool_ui_text_updated))
            if (applied.isNotEmpty()) {
                appendLine()
                appendLine(str(context, R.string.tool_ui_text_set_count, applied.size))
                applied.forEach { appendLine("  • $it") }
            }
            if (removed.isNotEmpty()) {
                appendLine()
                appendLine(str(context, R.string.tool_ui_text_restore_count, removed.size, removed.joinToString(", ")))
            }
            if (applied.isEmpty() && removed.isEmpty()) {
                appendLine()
                appendLine(str(context, R.string.tool_ui_text_no_changes))
            }
            appendLine()
            appendLine(str(context, R.string.tool_ui_text_override_count, merged.size))
        }
        return successResponse(text.trimEnd())
    }

    // ── MCP 工具组管理 ──────────────────────────────────────────────────────

    private suspend fun handleListMcpToolGroups(context: Context): JSONObject {
        val repository = getRepository(context)
        val settings = repository.getUISettings() ?: UISettings()
        val enabledGroups = settings.enabledMcpGroups.split(",").toSet()

        val allGroups = listOf(
            "core" to str(context, R.string.tool_group_desc_core),
            "memory" to str(context, R.string.tool_group_desc_memory),
            "ui_appearance" to str(context, R.string.tool_group_desc_ui_appearance),
            "efficiency" to str(context, R.string.tool_group_desc_efficiency),
            "ui_text" to str(context, R.string.tool_group_desc_ui_text),
            "files" to str(context, R.string.tool_group_desc_files),
            "documents" to str(context, R.string.tool_group_desc_documents)
        )

        val text = buildString {
            appendLine(str(context, R.string.tool_group_header))
            appendLine()
            allGroups.forEach { (id, desc) ->
                val status = if (id == "core" || id in enabledGroups) str(context, R.string.tool_group_enabled) else str(context, R.string.tool_group_disabled)
                appendLine("$status 【$id】")
                appendLine("   $desc")
                appendLine()
            }
            appendLine(str(context, R.string.tool_group_hint))
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
            return successResponse(str(context, R.string.tool_group_no_change))
        }

        val nextGroups = currentGroups.sorted().joinToString(",")
        repository.upsertUISettings(current.copy(enabledMcpGroups = nextGroups, updatedAt = System.currentTimeMillis()))

        val text = buildString {
            appendLine(str(context, R.string.tool_group_updated))
            if (enabledCount.isNotEmpty()) appendLine(str(context, R.string.tool_group_enabled_list, enabledCount.joinToString(", ")))
            if (disabledCount.isNotEmpty()) appendLine(str(context, R.string.tool_group_disabled_list, disabledCount.joinToString(", ")))
            appendLine()
            appendLine(str(context, R.string.tool_group_current, nextGroups))
        }
        return successResponse(text.trimEnd())
    }

    // ── 工具显示模式 ───────────────────────────────────────────────────────

    private suspend fun handleSetToolDisplayMode(context: Context, arguments: JSONObject): JSONObject {
        val repository = getRepository(context)
        val current = repository.getUISettings() ?: UISettings()
        val groups = arguments.optString("groups", "")
        repository.upsertUISettings(current.copy(silentToolGroups = groups, updatedAt = System.currentTimeMillis()))
        return if (groups.isNotEmpty()) {
            successResponse(str(context, R.string.tool_display_silent_on))
        } else {
            successResponse(str(context, R.string.tool_display_silent_off))
        }
    }

    // ── 文件系统工具 ────────────────────────────────────────────────────────

    private suspend fun handleFileWrite(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val content = arguments.optString("content")
        val encoding = arguments.optString("encoding", "utf8")
        if (path.isEmpty()) return errorResponse(str(context, R.string.tool_file_path_empty))
        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        return try {
            file.parentFile?.mkdirs()
            if (encoding == "base64") {
                val bytes = Base64.decode(content, Base64.DEFAULT)
                file.writeBytes(bytes)
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
            successResponse(str(context, R.string.tool_file_written, file.absolutePath, file.length()))
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_write_failed, e.localizedMessage))
        }
    }

    private suspend fun handleFileRead(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val encoding = arguments.optString("encoding", "utf8")
        val maxBytes = arguments.optInt("maxBytes", 1024 * 1024).coerceIn(1, 10 * 1024 * 1024)
        val startLine = arguments.optInt("startLine", 0)
        val endLine = arguments.optInt("endLine", 0)
        if (path.isEmpty()) return errorResponse(str(context, R.string.tool_file_path_empty))
        val file = resolvePath(context, path)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        if (!file.exists()) return errorResponse(str(context, R.string.tool_file_not_exists, path))
        if (!file.isFile) return errorResponse(str(context, R.string.tool_file_not_a_file, path))
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
                val suffix = if (truncated) "\n\n${str(context, R.string.tool_file_truncated, maxBytes, file.length())}" else ""
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
                    successResponse(text.take(maxBytes) + "\n\n${str(context, R.string.tool_file_content_truncated, maxBytes)}")
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
                val suffix = if (truncated) "\n\n${str(context, R.string.tool_file_truncated, maxBytes, file.length())}" else ""
                successResponse(resultText + suffix)
            }
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_read_failed, e.localizedMessage))
        }
    }

    private suspend fun handleFileAppend(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val content = arguments.optString("content")
        if (path.isEmpty()) return errorResponse(str(context, R.string.tool_file_path_empty))
        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path))
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
            successResponse(str(context, R.string.tool_file_appended, file.absolutePath, file.length()))
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_append_failed, e.localizedMessage))
        }
    }

    private suspend fun handleFileDelete(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        val recursive = arguments.optBoolean("recursive", false)
        if (path.isEmpty()) return errorResponse(str(context, R.string.tool_file_path_empty))
        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        if (!file.exists()) return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        return try {
            val success = if (recursive) deleteRecursive(file) else file.delete()
            if (success) successResponse(str(context, R.string.tool_file_deleted, file.absolutePath))
            else errorResponse(str(context, R.string.tool_file_delete_failed))
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_delete_error, e.localizedMessage))
        }
    }

    private suspend fun handleFileList(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path", "").trim()
        val showHidden = arguments.optBoolean("showHidden", false)
        val recursive = arguments.optBoolean("recursive", false)
        val maxDepth = arguments.optInt("maxDepth", 3).coerceIn(1, 10)
        val dir = resolvePath(context, path.ifEmpty { "." })
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path.ifEmpty { "/" }))
        if (!dir.exists()) return errorResponse(str(context, R.string.tool_file_dir_not_exists, path.ifEmpty { "/" }))
        if (!dir.isDirectory) return errorResponse(str(context, R.string.tool_file_not_a_dir, path))
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
            appendLine(str(context, R.string.tool_fileinfo_dir_label, dir.absolutePath))
            if (recursive) appendLine(str(context, R.string.tool_fileinfo_recursive, maxDepth))
            appendLine()
            if (listing.isEmpty()) appendLine(str(context, R.string.tool_file_empty_dir))
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
            return errorResponse(str(context, R.string.tool_file_search_at_least_one))
        }
        val searchRoot = resolvePath(context, directory.ifEmpty { "." })
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, directory.ifEmpty { "/" }))
        if (!searchRoot.exists()) return errorResponse(str(context, R.string.tool_file_dir_not_exists, directory.ifEmpty { "/" }))
        val contentRegex = if (contentQuery != null && isRegex) {
            try { Regex(contentQuery, RegexOption.IGNORE_CASE) } catch (e: Exception) {
                return errorResponse(str(context, R.string.tool_file_search_invalid_regex, e.message ?: "Unknown error"))
            }
        } else null
        val results = mutableListOf<JSONObject>()
        searchFiles(searchRoot, namePattern, contentQuery, contentRegex, contextLines, results, maxResults)
        val text = buildString {
            appendLine(str(context, R.string.tool_search_scope, searchRoot.absolutePath))
            if (namePattern != null) appendLine(str(context, R.string.tool_search_name_pattern, namePattern))
            if (contentQuery != null) appendLine(str(context, R.string.tool_search_content_query, contentQuery, if (isRegex) str(context, R.string.tool_search_regex_tag) else ""))
            appendLine(str(context, R.string.tool_search_results_count, results.size, if (results.size >= maxResults) str(context, R.string.tool_search_limit_reached, maxResults) else ""))
            appendLine()
            results.forEach { r ->
                val absPath = r.optString("path")
                append("• $absPath")
                val matchLines = r.optJSONArray("matchLines")
                if (matchLines != null && matchLines.length() > 0) {
                    val lines = (0 until matchLines.length()).map { matchLines.getInt(it) }
                    append("  " + context.getString(R.string.tool_search_match_lines, lines.joinToString(", ")))
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
        if (path.isEmpty()) return errorResponse(str(context, R.string.tool_file_path_empty))
        val file = resolvePath(context, path)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        if (!file.exists()) return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val ext = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val text = buildString {
            appendLine(str(context, R.string.tool_fileinfo_path, file.absolutePath))
            appendLine(str(context, R.string.tool_fileinfo_type, if (file.isDirectory) str(context, R.string.tool_fileinfo_type_dir) else str(context, R.string.tool_fileinfo_type_file)))
            if (file.isFile) {
                appendLine(str(context, R.string.tool_fileinfo_size, file.length(), file.length() / 1024.0))
                appendLine(str(context, R.string.tool_fileinfo_mime, mimeType))
            } else {
                val childCount = file.listFiles()?.size ?: 0
                appendLine(str(context, R.string.tool_fileinfo_child_count, childCount))
            }
            appendLine(str(context, R.string.tool_fileinfo_last_modified, sdf.format(Date(file.lastModified()))))
            appendLine(str(context, R.string.tool_fileinfo_readable, if (file.canRead()) str(context, R.string.tool_fileinfo_yes) else str(context, R.string.tool_fileinfo_no)))
            appendLine(str(context, R.string.tool_fileinfo_writable, if (file.canWrite()) str(context, R.string.tool_fileinfo_yes) else str(context, R.string.tool_fileinfo_no)))
        }
        return successResponse(text.trimEnd())
    }

    private suspend fun handleFileMove(context: Context, arguments: JSONObject): JSONObject {
        val srcPath = arguments.optString("sourcePath").trim()
        val dstPath = arguments.optString("destinationPath").trim()
        val overwrite = arguments.optBoolean("overwrite", false)
        if (srcPath.isEmpty()) return errorResponse(str(context, R.string.tool_file_source_path_empty))
        if (dstPath.isEmpty()) return errorResponse(str(context, R.string.tool_file_dest_path_empty))
        val src = resolvePath(context, srcPath, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, srcPath))
        val dst = resolvePath(context, dstPath, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, dstPath))
        if (!src.exists()) return errorResponse(str(context, R.string.tool_file_source_not_exists, srcPath))
        if (dst.exists() && !overwrite) return errorResponse(str(context, R.string.tool_file_dest_exists, dstPath))
        return try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val success = src.renameTo(dst)
            if (success) {
                successResponse(str(context, R.string.tool_file_moved, src.absolutePath, dst.absolutePath))
            } else {
                // renameTo 跨文件系统可能失败，回退到复制+删除
                src.copyRecursively(dst, overwrite = true)
                deleteRecursive(src)
                successResponse(str(context, R.string.tool_file_moved_copy, src.absolutePath, dst.absolutePath))
            }
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_move_failed, e.localizedMessage))
        }
    }

    private suspend fun handleFileCopy(context: Context, arguments: JSONObject): JSONObject {
        val srcPath = arguments.optString("sourcePath").trim()
        val dstPath = arguments.optString("destinationPath").trim()
        val overwrite = arguments.optBoolean("overwrite", false)
        if (srcPath.isEmpty()) return errorResponse(str(context, R.string.tool_file_source_path_empty))
        if (dstPath.isEmpty()) return errorResponse(str(context, R.string.tool_file_dest_path_empty))
        val src = resolvePath(context, srcPath, FileAccessType.READ)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, srcPath))
        val dst = resolvePath(context, dstPath, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, dstPath))
        if (!src.exists()) return errorResponse(str(context, R.string.tool_file_source_not_exists, srcPath))
        if (dst.exists() && !overwrite) return errorResponse(str(context, R.string.tool_file_dest_exists, dstPath))
        return try {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = overwrite)
            } else {
                src.copyTo(dst, overwrite = overwrite)
            }
            successResponse(str(context, R.string.tool_file_copied, src.absolutePath, dst.absolutePath))
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_copy_failed, e.localizedMessage))
        }
    }

    private suspend fun handleFileMkdir(context: Context, arguments: JSONObject): JSONObject {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return errorResponse(str(context, R.string.tool_file_path_empty))
        val file = resolvePath(context, path, FileAccessType.WRITE)
            ?: return errorResponse(str(context, R.string.tool_file_path_invalid, path))
        return try {
            if (file.exists()) {
                if (file.isDirectory) successResponse(str(context, R.string.tool_file_dir_exists, file.absolutePath))
                else errorResponse(str(context, R.string.tool_file_path_invalid, path))
            } else if (file.mkdirs()) {
                successResponse(str(context, R.string.tool_file_dir_created, file.absolutePath))
            } else {
                errorResponse(str(context, R.string.tool_file_dir_create_failed, path))
            }
        } catch (e: Exception) {
            errorResponse(str(context, R.string.tool_file_dir_create_failed, e.localizedMessage))
        }
    }

    // ── 用户交互工具 ────────────────────────────────────────────────────────

    private suspend fun handleAskUser(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val question = arguments.optString("question").trim()
        if (question.isEmpty()) {
            return errorResponse(str(context, R.string.tool_ask_user_question_empty))
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
        // 支持新的 hours/minutes/seconds 参数，向后兼容 delay_seconds
        val hours = arguments.optLong("hours", 0L)
        val minutes = arguments.optLong("minutes", 0L)
        val seconds = arguments.optLong("seconds", 0L)
        val hasNewParams = arguments.has("hours") || arguments.has("minutes") || arguments.has("seconds")

        val delaySeconds = if (hasNewParams) {
            hours * 3600 + minutes * 60 + seconds
        } else {
            arguments.optLong("delay_seconds", 0L)
        }

        // 重复间隔同理
        val repeatHours = arguments.optLong("repeat_hours", 0L)
        val repeatMinutes = arguments.optLong("repeat_minutes", 0L)
        val repeatSeconds = arguments.optLong("repeat_seconds", 0L)
        val hasNewRepeatParams = arguments.has("repeat_hours") || arguments.has("repeat_minutes") || arguments.has("repeat_seconds")

        val repeatIntervalSec = if (hasNewRepeatParams) {
            repeatHours * 3600 + repeatMinutes * 60 + repeatSeconds
        } else {
            arguments.optLong("repeat_interval_seconds", 0L)
        }

        val message = arguments.optString("message").trim()
        val label = arguments.optString("label", str(context, R.string.tool_timer_label)).trim()
            .take(30).ifEmpty { str(context, R.string.tool_timer_label) }

        if (delaySeconds < 1) {
            return errorResponse(str(context, R.string.tool_timer_delay_min))
        }
        if (repeatIntervalSec < 0) {
            return errorResponse(str(context, R.string.tool_timer_repeat_negative))
        }
        if (repeatIntervalSec > 0 && repeatIntervalSec < 1) {
            return errorResponse(str(context, R.string.tool_timer_repeat_min))
        }
        if (message.isEmpty()) {
            return errorResponse(str(context, R.string.tool_timer_message_empty))
        }
        if (sessionId == null) {
            return errorResponse(str(context, R.string.tool_timer_no_session))
        }

        val timerId = TimerManager.createTimer(
            context = context,
            sessionId = sessionId,
            delaySeconds = delaySeconds,
            message = message,
            label = label,
            repeatIntervalSec = repeatIntervalSec
        )

        val humanDelay = formatDuration(context, delaySeconds)

        val repeatInfo = if (repeatIntervalSec > 0) {
            str(context, R.string.tool_timer_repeat_info, formatDuration(context, repeatIntervalSec))
        } else ""

        return successResponse(
            str(context, R.string.tool_timer_created, timerId, humanDelay, repeatInfo, message, timerId)
        )
    }

    private fun handleCancelTimer(context: Context, arguments: JSONObject): JSONObject {
        val timerId = arguments.optString("timer_id").trim()
        if (timerId.isEmpty()) {
            return errorResponse(str(context, R.string.tool_timer_cancel_id_empty))
        }
        val cancelled = TimerManager.cancelTimer(context, timerId)
        return if (cancelled) {
            successResponse(str(context, R.string.tool_timer_cancelled, timerId))
        } else {
            errorResponse(str(context, R.string.tool_timer_cancel_not_found, timerId))
        }
    }

    private fun handleListTimers(context: Context): JSONObject {
        val timers = TimerManager.listTimers(context)
        if (timers.isEmpty()) {
            return successResponse(str(context, R.string.tool_timer_list_empty))
        }
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val text = buildString {
            appendLine(str(context, R.string.tool_timer_list_header, timers.size))
            appendLine()
            timers.forEachIndexed { i, t ->
                val remainingMs = (t.fireAtMs - now).coerceAtLeast(0L)
                val remainingSec = remainingMs / 1000
                val humanRemaining = formatDuration(context, remainingSec)
                val fireTime = sdf.format(java.util.Date(t.fireAtMs))
                val type = if (t.repeatIntervalMs > 0) str(context, R.string.tool_timer_type_repeat, formatDuration(context, t.repeatIntervalMs / 1000)) else str(context, R.string.tool_timer_type_once)
                appendLine("${i + 1}. ID: `${t.timerId}` [$type]")
                appendLine(str(context, R.string.tool_timer_list_entry_label, t.label))
                appendLine(str(context, R.string.tool_timer_list_entry_message, t.message))
                appendLine(str(context, R.string.tool_timer_list_entry_remaining, humanRemaining, fireTime))
            }
        }
        return successResponse(text.trimEnd())
    }

    private fun formatDuration(context: Context, totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append(str(context, R.string.tool_duration_hours, hours))
            if (minutes > 0) append(str(context, R.string.tool_duration_minutes, minutes))
            if (seconds > 0 || isEmpty()) append(str(context, R.string.tool_duration_seconds, seconds))
        }.trim()
    }

    // ── 文件系统工具辅助函数 ────────────────────────────────────────────────

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
     * - READ 操作只需 read 权限，WRITE 操作需要 write 权限
     * @param accessType FileAccessType.READ 或 FileAccessType.WRITE
     * @return 解析后的 File，或 null（路径非法或权限被拒绝时）
     */
    private suspend fun resolvePath(
        context: Context,
        path: String,
        accessType: com.omnichat.data.FileAccessType = com.omnichat.data.FileAccessType.READ
    ): File? {
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

        val allowed = McpPermissionManager.checkAndRequestPermission(context, canonicalPath, accessType)
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

    private fun buildUiCapabilitiesResponse(context: Context, current: UISettings): JSONObject {
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
                    put("text", str(context, R.string.tool_ui_capability_desc))
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "JSON_DATA: " + structured.toString())
                })
            })
        }
    }

    // ── SubAgent tools ─────────────────────────────────────────────────

    private suspend fun handleDelegateTask(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val agentType = arguments.optString("agentType", "")
        val task = arguments.optString("task", "")

        if (agentType.isBlank() || task.isBlank()) {
            return errorResponse("agentType and task are required")
        }
        if (agentType !in com.omnichat.agent.AgentPrompts.ALL_TYPES) {
            return errorResponse("Invalid agentType: $agentType. Valid types: ${com.omnichat.agent.AgentPrompts.ALL_TYPES.joinToString()}")
        }
        if (sessionId == null) {
            return errorResponse("sessionId is required for delegate_task")
        }

        return try {
            // Use async version to enable event-driven UI updates
            val taskId = SubAgent.execute(
                context = context,
                agentType = agentType,
                taskDescription = task,
                sessionId = sessionId
            )
            successResponse("Task delegated to $agentType agent.\nTask ID: $taskId\n\nThe result will appear in chat when the sub-agent completes.")
        } catch (e: Exception) {
            errorResponse("Failed to delegate task: ${e.message}")
        }
    }

    private fun handleCheckTaskStatus(context: Context, arguments: JSONObject): JSONObject {
        val taskId = arguments.optString("taskId", "")
        if (taskId.isBlank()) {
            return errorResponse("taskId is required")
        }

        val task = SubAgent.getTask(taskId)
            ?: return errorResponse("Task not found: $taskId")

        val result = buildString {
            appendLine("Task ID: ${task.taskId}")
            appendLine("Agent Type: ${task.agentType}")
            appendLine("Status: ${task.status}")
            appendLine("Created: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(task.createdAt))}")
            task.startedAt?.let {
                appendLine("Started: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}")
            }
            task.completedAt?.let {
                appendLine("Completed: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}")
            }
            task.result?.let { appendLine("Result:\n$it") }
            task.error?.let { appendLine("Error: $it") }
        }
        return successResponse(result)
    }

    private fun handleSendAgentMessage(context: Context, arguments: JSONObject): JSONObject {
        val to = arguments.optString("to", "")
        val content = arguments.optString("content", "")

        if (to.isBlank() || content.isBlank()) {
            return errorResponse("to and content are required")
        }

        MessageBus.send(from = "main", to = to, content = content)
        return successResponse("Message sent to $to")
    }

    private fun handleReadAgentInbox(context: Context): JSONObject {
        val messages = MessageBus.readAndClearInbox("main")
        if (messages.isEmpty()) {
            return successResponse("No pending messages.")
        }

        val text = buildString {
            appendLine("${messages.size} message(s) in inbox:")
            messages.forEachIndexed { index, msg ->
                appendLine("\n--- Message ${index + 1} ---")
                appendLine("From: ${msg.from}")
                appendLine("Content: ${msg.content}")
            }
        }
        return successResponse(text)
    }

    // ── Workflow 工具 ────────────────────────────────────────────────────────

    private suspend fun handleRunWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long?
    ): JSONObject {
        val mode = arguments.optString("mode", "")
        if (mode.isBlank()) {
            return errorResponse("mode is required")
        }
        if (sessionId == null) {
            return errorResponse("sessionId is required for run_workflow")
        }

        return when (mode) {
            "pipeline" -> handlePipelineWorkflow(context, arguments, sessionId)
            "dag" -> handleDagWorkflow(context, arguments, sessionId)
            "conversational" -> handleConversationalWorkflow(context, arguments, sessionId)
            else -> errorResponse("Invalid mode: $mode. Valid modes: pipeline, dag, conversational")
        }
    }

    private suspend fun handlePipelineWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long
    ): JSONObject {
        val stepsArray = arguments.optJSONArray("steps")
        if (stepsArray == null || stepsArray.length() == 0) {
            return errorResponse("steps array is required for pipeline mode")
        }

        val steps = try {
            parseWorkflowSteps(stepsArray)
        } catch (e: IllegalArgumentException) {
            return errorResponse(e.message ?: "Invalid steps format")
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = WorkflowEngine.executePipeline(
                context = context,
                sessionId = sessionId,
                steps = steps
            )
            val duration = (System.currentTimeMillis() - startTime) / 1000
            successResponse(formatWorkflowResult(results, duration))
        } catch (e: Exception) {
            errorResponse("Pipeline workflow failed: ${e.message}")
        }
    }

    private suspend fun handleDagWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long
    ): JSONObject {
        val stepsArray = arguments.optJSONArray("steps")
        if (stepsArray == null || stepsArray.length() == 0) {
            return errorResponse("steps array is required for dag mode")
        }

        val steps = try {
            parseWorkflowSteps(stepsArray)
        } catch (e: IllegalArgumentException) {
            return errorResponse(e.message ?: "Invalid steps format")
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = WorkflowEngine.executeDAG(
                context = context,
                sessionId = sessionId,
                steps = steps
            )
            val duration = (System.currentTimeMillis() - startTime) / 1000
            successResponse(formatWorkflowResult(results, duration))
        } catch (e: Exception) {
            errorResponse("DAG workflow failed: ${e.message}")
        }
    }

    private suspend fun handleConversationalWorkflow(
        context: Context,
        arguments: JSONObject,
        sessionId: Long
    ): JSONObject {
        val agentA = arguments.optString("agentA", "")
        val agentB = arguments.optString("agentB", "")
        val topic = arguments.optString("topic", "")
        val maxRounds = arguments.optInt("maxRounds", 5)

        if (agentA.isBlank() || agentB.isBlank()) {
            return errorResponse("agentA and agentB are required for conversational mode")
        }
        if (topic.isBlank()) {
            return errorResponse("topic is required for conversational mode")
        }
        if (maxRounds <= 0) {
            return errorResponse("maxRounds must be > 0")
        }

        val startTime = System.currentTimeMillis()

        return try {
            val results = WorkflowEngine.executeConversational(
                context = context,
                sessionId = sessionId,
                agentA = agentA,
                agentB = agentB,
                topic = topic,
                maxRounds = maxRounds
            )
            val duration = (System.currentTimeMillis() - startTime) / 1000
            successResponse(formatWorkflowResult(results, duration))
        } catch (e: Exception) {
            errorResponse("Conversational workflow failed: ${e.message}")
        }
    }

    private fun parseWorkflowSteps(stepsArray: JSONArray): List<WorkflowStep> {
        val steps = mutableListOf<WorkflowStep>()
        for (i in 0 until stepsArray.length()) {
            val obj = stepsArray.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val agentType = obj.optString("agentType", "")
            val task = obj.optString("task", "")
            val dependsOnArray = obj.optJSONArray("dependsOn")
            val dependsOn = if (dependsOnArray != null) {
                (0 until dependsOnArray.length()).map { dependsOnArray.optString(it) }
            } else {
                emptyList()
            }
            val resultVariable = obj.optString("resultVariable", "").takeIf { it.isNotBlank() }

            if (id.isBlank() || agentType.isBlank() || task.isBlank()) {
                throw IllegalArgumentException("Step $i: id, agentType, and task are required")
            }

            steps.add(WorkflowStep(id, agentType, task, dependsOn, resultVariable))
        }
        return steps
    }

    private fun formatWorkflowResult(
        results: List<StepResult>,
        durationSeconds: Long
    ): String {
        val successCount = results.count { it.status == WorkflowStepStatus.COMPLETED }
        val failedCount = results.count { it.status == WorkflowStepStatus.FAILED }
        val skippedCount = results.count { it.status == WorkflowStepStatus.SKIPPED }

        val overallStatus = when {
            failedCount > 0 -> "completed with failures"
            skippedCount > 0 -> "completed with skipped steps"
            else -> "completed successfully"
        }

        val sb = StringBuilder()
        sb.appendLine("Workflow $overallStatus ($successCount succeeded, $failedCount failed, $skippedCount skipped in ${durationSeconds}s)")
        sb.appendLine()
        sb.appendLine("Step Summary:")

        for (result in results) {
            val icon = when (result.status) {
                WorkflowStepStatus.COMPLETED -> "✅"
                WorkflowStepStatus.FAILED -> "❌"
                WorkflowStepStatus.SKIPPED -> "⏭️"
                WorkflowStepStatus.RUNNING -> "🔄"
                WorkflowStepStatus.PENDING -> "⏳"
                WorkflowStepStatus.IDLE -> "💤"
                WorkflowStepStatus.PENDING_REVIEW -> "👀"
                WorkflowStepStatus.REVISION -> "✏️"
            }
            val summary = when (result.status) {
                WorkflowStepStatus.COMPLETED -> summarizeResult(result.result)
                WorkflowStepStatus.FAILED -> result.error ?: "Unknown error"
                WorkflowStepStatus.SKIPPED -> result.error ?: "Skipped"
                else -> "In progress"
            }
            sb.appendLine("$icon ${result.stepId}: $summary")
        }

        val lastSuccess = results.lastOrNull { it.status == WorkflowStepStatus.COMPLETED }
        if (lastSuccess != null && lastSuccess.result != null) {
            sb.appendLine()
            sb.appendLine("Final Output (${lastSuccess.stepId}):")
            sb.appendLine(lastSuccess.result)
        }

        val failures = results.filter { it.status == WorkflowStepStatus.FAILED }
        if (failures.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Failure Details:")
            for (f in failures) {
                sb.appendLine("- ${f.stepId}: ${f.error}")
            }
        }

        return sb.toString()
    }

    private fun summarizeResult(result: String?): String {
        if (result.isNullOrBlank()) return "No output"
        val lines = result.lines().filter { it.isNotBlank() }
        val firstLine = lines.firstOrNull()?.take(80) ?: "No output"
        return if (firstLine.length < lines.firstOrNull()?.length ?: 0) "$firstLine..." else firstLine
    }

    private fun successResponse(text: String): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
    }

    private fun errorResponse(message: String): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Error: $message")
            })
        })
        put("isError", true)
    }
}
