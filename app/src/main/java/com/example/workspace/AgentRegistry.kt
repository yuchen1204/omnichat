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