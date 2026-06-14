package com.omnichat.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Omnifile binary format: unified export/import format replacing .omnidb and .omniconfig.
 *
 * Structure:
 * [Header]     10 bytes    "OMNIFILE1\n" (magic + version)
 * [Metadata]   4 bytes     Big-endian JSON length
 *             N bytes     JSON metadata
 * [Payload]    Remaining   SQLite database bytes
 */
object OmnifileFormat {

    const val MAGIC = "OMNIFILE1\n"
    const val HEADER_SIZE = 10 // bytes
    const val LENGTH_FIELD_SIZE = 4 // bytes

    // Section names
    const val SECTION_PROVIDERS = "providers"
    const val SECTION_MCP_SERVERS = "mcpServers"
    const val SECTION_MCP_FILE_PERMISSIONS = "mcpFilePermissions"
    const val SECTION_MEMORIES = "memories"
    const val SECTION_PROMPT_TEMPLATES = "promptTemplates"
    const val SECTION_UI_SETTINGS = "uiSettings"
    const val SECTION_COLOR_SCHEME_PRESETS = "colorSchemePresets"
    const val SECTION_SESSIONS = "sessions"
    const val SECTION_MESSAGES = "messages"
    const val SECTION_SESSION_SUMMARIES = "sessionSummaries"

    // Category groups for UI selection
    val CATEGORY_PROVIDER_MCP = listOf(SECTION_PROVIDERS, SECTION_MCP_SERVERS, SECTION_MCP_FILE_PERMISSIONS)
    val CATEGORY_MEMORY_PROMPTS = listOf(SECTION_MEMORIES, SECTION_PROMPT_TEMPLATES)
    val CATEGORY_THEME_UI = listOf(SECTION_UI_SETTINGS, SECTION_COLOR_SCHEME_PRESETS)
    val CATEGORY_CHAT_HISTORY = listOf(SECTION_SESSIONS, SECTION_MESSAGES, SECTION_SESSION_SUMMARIES)

    enum class ExportType { FULL, SELECTIVE }

    data class OmnifileMetadata(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val exportType: ExportType,
        val includedSections: List<String>,
        val deviceInfo: String = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
        val appVersion: String = "1.0.0"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("version", version)
            put("exportedAt", exportedAt)
            put("exportType", exportType.name.lowercase())
            put("includedSections", JSONArray(includedSections))
            put("deviceInfo", deviceInfo)
            put("appVersion", appVersion)
        }

        companion object {
            fun fromJson(json: JSONObject): OmnifileMetadata = OmnifileMetadata(
                version = json.optInt("version", 1),
                exportedAt = json.optLong("exportedAt", 0L),
                exportType = try {
                    ExportType.valueOf(json.optString("exportType", "full").uppercase())
                } catch (_: Exception) { ExportType.FULL },
                includedSections = json.optJSONArray("includedSections")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                deviceInfo = json.optString("deviceInfo", ""),
                appVersion = json.optString("appVersion", "")
            )
        }
    }

    enum class FileFormat { OMNIFILE, OMNIDB, OMNICONFIG, UNKNOWN }

    /**
     * Detect file format by reading the first 10 bytes.
     * The stream is reset after reading so the caller can read from the beginning.
     */
    fun detectFormat(inputStream: InputStream): FileFormat {
        val header = ByteArray(HEADER_SIZE)
        val bytesRead = inputStream.read(header)
        inputStream.reset() // reset so caller can read from beginning
        if (bytesRead < HEADER_SIZE) {
            // Too short for binary header — might be JSON (omniconfig)
            return FileFormat.OMNICONFIG
        }
        val headerStr = String(header, Charsets.UTF_8)
        return when {
            headerStr == MAGIC -> FileFormat.OMNIFILE
            headerStr == "OMNIDB_V1\n" -> FileFormat.OMNIDB
            else -> FileFormat.OMNICONFIG // Try JSON parse
        }
    }

    /**
     * Write an omnifile to the output stream.
     */
    fun writeOmnifile(
        outputStream: OutputStream,
        metadata: OmnifileMetadata,
        databaseBytes: ByteArray
    ) {
        val metadataJson = metadata.toJson().toString().toByteArray(Charsets.UTF_8)

        // Header
        outputStream.write(MAGIC.toByteArray(Charsets.UTF_8))

        // Metadata length (4 bytes, big-endian)
        val lengthBuffer = ByteBuffer.allocate(LENGTH_FIELD_SIZE).order(ByteOrder.BIG_ENDIAN)
        lengthBuffer.putInt(metadataJson.size)
        outputStream.write(lengthBuffer.array())

        // Metadata JSON
        outputStream.write(metadataJson)

        // Payload (database bytes)
        outputStream.write(databaseBytes)
    }

    /**
     * Read omnifile metadata and database payload from input stream.
     * Assumes the header has already been read and verified.
     */
    fun readOmnifile(inputStream: InputStream): Pair<OmnifileMetadata, ByteArray> {
        // Read metadata length
        val lengthBytes = ByteArray(LENGTH_FIELD_SIZE)
        inputStream.read(lengthBytes)
        val metadataLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int

        // Read metadata JSON
        val metadataBytes = ByteArray(metadataLength)
        inputStream.read(metadataBytes)
        val metadataJson = JSONObject(String(metadataBytes, Charsets.UTF_8))
        val metadata = OmnifileMetadata.fromJson(metadataJson)

        // Read remaining bytes as database payload
        val databaseBytes = inputStream.readBytes()

        return Pair(metadata, databaseBytes)
    }
}
