package com.example.workspace

import java.io.File

data class ScratchpadEntry(
    val agentName: String,
    val key: String,
    val content: String,
    val lastModified: Long
)

class Scratchpad(private val basePath: File) {

    init {
        basePath.mkdirs()
    }

    fun write(agentName: String, key: String, content: String) {
        val sanitizedKey = key.replace(Regex("[^A-Za-z0-9]"), "_")
        val file = File(basePath, "${agentName}_${sanitizedKey}.txt")
        file.writeText(content)
    }

    fun read(agentName: String, key: String): String? {
        val sanitizedKey = key.replace(Regex("[^A-Za-z0-9]"), "_")
        val file = File(basePath, "${agentName}_${sanitizedKey}.txt")
        return if (file.exists()) file.readText() else null
    }

    fun list(): List<ScratchpadEntry> {
        return basePath.listFiles { file -> file.extension == "txt" }
            ?.mapNotNull { file ->
                val nameWithoutExt = file.nameWithoutExtension
                val underscoreIndex = nameWithoutExt.indexOf('_')
                if (underscoreIndex > 0) {
                    val agentName = nameWithoutExt.substring(0, underscoreIndex)
                    val key = nameWithoutExt.substring(underscoreIndex + 1)
                    ScratchpadEntry(
                        agentName = agentName,
                        key = key,
                        content = file.readText(),
                        lastModified = file.lastModified()
                    )
                } else null
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    fun cleanup(agentName: String) {
        basePath.listFiles { file -> file.name.startsWith("${agentName}_") && file.extension == "txt" }
            ?.forEach { it.delete() }
    }

    fun clearAll() {
        basePath.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val TAG = "Scratchpad"
    }
}
