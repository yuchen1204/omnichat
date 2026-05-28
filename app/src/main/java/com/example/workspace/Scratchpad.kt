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

    /**
     * FIX (Bug #19): 使用 "__" 作为 agentName 与 key 的分隔符，并对两者做 sanitize。
     * 原实现仅 sanitize key 并用单个下划线分隔，agentName 含 "_" 时（例如 code_writer）
     * list() 会把首个下划线之前作为 agentName，剩下都归为 key，导致 agentName/key 错位。
     * sanitize 也防止了用户提供的 agentName 中含路径分隔符等危险字符。
     */
    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9]"), "-")

    private fun fileNameFor(agentName: String, key: String): String =
        "${sanitize(agentName)}__${sanitize(key)}.txt"

    fun write(agentName: String, key: String, content: String) {
        val file = File(basePath, fileNameFor(agentName, key))
        file.writeText(content)
    }

    fun read(agentName: String, key: String): String? {
        val file = File(basePath, fileNameFor(agentName, key))
        return if (file.exists()) file.readText() else null
    }

    fun list(): List<ScratchpadEntry> {
        return basePath.listFiles { file -> file.extension == "txt" }
            ?.mapNotNull { file ->
                val nameWithoutExt = file.nameWithoutExtension
                val sepIndex = nameWithoutExt.indexOf("__")
                if (sepIndex > 0) {
                    val agentName = nameWithoutExt.substring(0, sepIndex)
                    val key = nameWithoutExt.substring(sepIndex + 2)
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
        val prefix = "${sanitize(agentName)}__"
        basePath.listFiles { file -> file.name.startsWith(prefix) && file.extension == "txt" }
            ?.forEach { it.delete() }
    }

    fun clearAll() {
        basePath.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val TAG = "Scratchpad"
    }
}
