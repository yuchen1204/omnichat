package com.example.workspace

import android.content.Context
import com.example.data.AgentPreset
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AgentRegistry(private val context: Context) {
    val definitions = ConcurrentHashMap<String, AgentDefinition>()

    fun loadAll() {
        loadFromAssets()
        loadFromExternalStorage()
        // 注册内置类型，优先级最低——文件预设和 DB 预设通过 putIfAbsent 覆盖
        registerBuiltinTypes()
    }

    fun loadFromPresets(presets: List<AgentPreset>) {
        for (preset in presets) {
            val isOrch = preset.name.lowercase().contains("orchestrator")
            val def = AgentDefinition(
                name = preset.name,
                displayName = preset.name,
                systemPrompt = preset.systemPrompt,
                isOrchestrator = isOrch,
                description = preset.description
            )
            definitions.putIfAbsent(def.name, def)
        }
    }

    fun get(name: String): AgentDefinition? = definitions[name]

    fun getAll(): List<AgentDefinition> = definitions.values.toList()

    fun getByHint(hint: ModelHint): List<AgentDefinition> =
        definitions.values.filter { it.modelHint == hint }

    fun getOrchestrator(): AgentDefinition? =
        definitions.values.firstOrNull { it.isOrchestrator }

    fun contains(name: String): Boolean = definitions.containsKey(name)

    private fun loadFromAssets() {
        try {
            val files = context.assets.list("workspace/agents") ?: return
            for (fileName in files) {
                if (!fileName.endsWith(".json")) continue
                try {
                    val json = context.assets.open("workspace/agents/")
                        .bufferedReader().use { it.readText() }
                    val def = parseDefinitionJson(json) ?: continue
                    definitions.putIfAbsent(def.name, def)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadFromExternalStorage() {
        try {
            val dir = File(context.getExternalFilesDir(null), "OmniChat/workspace/agents")
            if (!dir.exists() || !dir.isDirectory) return
            val files = dir.listFiles { file -> file.extension == "json" } ?: return
            for (file in files) {
                try {
                    val json = file.readText()
                    val def = parseDefinitionJson(json) ?: continue
                    definitions.putIfAbsent(def.name, def)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Register built-in agent types.
     *
     * These are the lowest priority definitions — file-based presets and DB presets
     * override them via putIfAbsent.
     */
    private fun registerBuiltinTypes() {
        val builtinTypes = listOf(
            AgentDefinition(
                name = "general",
                displayName = "通用 Agent",
                systemPrompt = "",
                mode = AgentMode.NORMAL,
                description = "Multi-step research and implementation, all tools available"
            ),
            AgentDefinition(
                name = "explorer",
                displayName = "探索者",
                systemPrompt = "",
                modelHint = ModelHint.FAST,
                allowedTools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time"),
                mode = AgentMode.NORMAL,
                maxToolIterations = 30,
                description = "Fast read-only search and analysis"
            ),
            AgentDefinition(
                name = "verifier",
                displayName = "验证者",
                systemPrompt = "",
                modelHint = ModelHint.REASONING,
                allowedTools = listOf("read_file", "list_directory", "search_files", "get_file_info", "search_memory", "get_current_time", "execute_command"),
                mode = AgentMode.NORMAL,
                maxToolIterations = 30,
                description = "Implementation verification, read-only + bash"
            ),
            AgentDefinition(
                name = "coordinator",
                displayName = "协调者",
                systemPrompt = "",
                mode = AgentMode.COORDINATOR,
                description = "Pure orchestration mode, only dispatch tools"
            )
        )
        for (def in builtinTypes) {
            definitions.putIfAbsent(def.name, def)
        }
    }

    internal fun parseDefinitionJson(json: String): AgentDefinition? {
        return try {
            val obj = JSONObject(json)
            val name = obj.optString("name", "").trim()
            if (name.isBlank()) return null
            val displayName = obj.optString("displayName", name)
            val systemPrompt = obj.optString("systemPrompt", "")
            val modelHintStr = obj.optString("modelHint", "")
            val modelHint = if (modelHintStr.isNotBlank()) {
                try { ModelHint.valueOf(modelHintStr) } catch (_: Exception) { null }
            } else null
            val allowedTools = if (obj.has("allowedTools") && !obj.isNull("allowedTools")) {
                val arr = obj.getJSONArray("allowedTools")
                (0 until arr.length()).map { arr.getString(it) }
            } else null
            val disallowedTools = if (obj.has("disallowedTools") && !obj.isNull("disallowedTools")) {
                val arr = obj.getJSONArray("disallowedTools")
                (0 until arr.length()).map { arr.getString(it) }
            } else null
            val isOrchestrator = obj.optBoolean("isOrchestrator", false)
            val maxToolIterations = obj.optInt("maxToolIterations", 50)
            val description = obj.optString("description", "")
            AgentDefinition(
                name = name,
                displayName = displayName,
                systemPrompt = systemPrompt,
                modelHint = modelHint,
                allowedTools = allowedTools,
                disallowedTools = disallowedTools,
                isOrchestrator = isOrchestrator,
                maxToolIterations = maxToolIterations,
                description = description
            )
        } catch (_: Exception) {
            null
        }
    }
}