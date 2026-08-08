package com.omnichat.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Unified export/import format for database backups and optional project files. */
object OmnifileFormat {

    const val MAGIC = "OMNIFILE2\n"
    private const val LEGACY_MAGIC = "OMNIFILE1\n"
    const val HEADER_SIZE = 10
    const val LENGTH_FIELD_SIZE = 4
    private const val MAX_METADATA_BYTES = 1 * 1024 * 1024
    private const val MAX_DATABASE_BYTES = 512 * 1024 * 1024
    private const val MAX_PROJECT_ZIP_BYTES = 256 * 1024 * 1024
    private const val MAX_PROJECT_FILES = 10_000
    private const val MAX_PROJECT_FILE_BYTES = 64 * 1024 * 1024
    private const val MAX_PROJECT_TOTAL_BYTES = 512 * 1024 * 1024

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

    val CATEGORY_PROVIDER_MCP = listOf(SECTION_PROVIDERS, SECTION_MCP_SERVERS, SECTION_MCP_FILE_PERMISSIONS)
    val CATEGORY_MEMORY_PROMPTS = listOf(SECTION_MEMORIES, SECTION_PROMPT_TEMPLATES)
    val CATEGORY_THEME_UI = listOf(SECTION_UI_SETTINGS, SECTION_COLOR_SCHEME_PRESETS)
    val CATEGORY_CHAT_HISTORY = listOf(SECTION_SESSIONS, SECTION_MESSAGES, SECTION_SESSION_SUMMARIES)

    enum class ExportType { FULL, SELECTIVE }

    data class OmnifileMetadata(
        val version: Int = 2,
        val exportedAt: Long = System.currentTimeMillis(),
        val exportType: ExportType,
        val includedSections: List<String>,
        val deviceInfo: String = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
        val appVersion: String = "1.0.0",
        val databaseLength: Long = 0,
        val projectsZipLength: Long = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("version", version)
            put("exportedAt", exportedAt)
            put("exportType", exportType.name.lowercase())
            put("includedSections", JSONArray(includedSections))
            put("deviceInfo", deviceInfo)
            put("appVersion", appVersion)
            put("databaseLength", databaseLength)
            put("projectsZipLength", projectsZipLength)
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
                appVersion = json.optString("appVersion", ""),
                databaseLength = json.optLong("databaseLength", 0L),
                projectsZipLength = json.optLong("projectsZipLength", 0L)
            )
        }
    }

    data class OmnifilePayload(
        val metadata: OmnifileMetadata,
        val databaseBytes: ByteArray,
        val projectsZip: ByteArray?
    )

    enum class FileFormat { OMNIFILE, OMNIDB, OMNICONFIG, UNKNOWN }

    fun detectFormat(inputStream: InputStream): FileFormat {
        val header = ByteArray(HEADER_SIZE)
        val bytesRead = inputStream.read(header)
        inputStream.reset()
        if (bytesRead < HEADER_SIZE) return FileFormat.OMNICONFIG
        return when (String(header, Charsets.UTF_8)) {
            MAGIC, LEGACY_MAGIC -> FileFormat.OMNIFILE
            "OMNIDB_V1\n" -> FileFormat.OMNIDB
            else -> FileFormat.OMNICONFIG
        }
    }

    fun writeOmnifile(
        outputStream: OutputStream,
        metadata: OmnifileMetadata,
        databaseBytes: ByteArray,
        projectsZip: ByteArray? = null
    ) {
        require(databaseBytes.size <= MAX_DATABASE_BYTES) { "Database payload is too large" }
        require((projectsZip?.size ?: 0) <= MAX_PROJECT_ZIP_BYTES) { "Project archive is too large" }
        val resolvedMetadata = metadata.copy(
            version = 2,
            databaseLength = databaseBytes.size.toLong(),
            projectsZipLength = projectsZip?.size?.toLong() ?: 0L
        )
        val metadataJson = resolvedMetadata.toJson().toString().toByteArray(Charsets.UTF_8)
        require(metadataJson.size <= MAX_METADATA_BYTES) { "Metadata is too large" }
        outputStream.write(MAGIC.toByteArray(Charsets.UTF_8))
        outputStream.write(ByteBuffer.allocate(LENGTH_FIELD_SIZE).order(ByteOrder.BIG_ENDIAN).putInt(metadataJson.size).array())
        outputStream.write(metadataJson)
        outputStream.write(databaseBytes)
        projectsZip?.let(outputStream::write)
    }

    /** Reads an omnifile from its beginning, including and validating the header. */
    fun readOmnifile(inputStream: InputStream): OmnifilePayload {
        val header = readExactly(inputStream, HEADER_SIZE)
        val isLegacy = String(header, Charsets.UTF_8) == LEGACY_MAGIC
        if (!isLegacy && !header.contentEquals(MAGIC.toByteArray(Charsets.UTF_8))) {
            throw IOException("Invalid omnifile header")
        }
        val metadataLength = ByteBuffer.wrap(readExactly(inputStream, LENGTH_FIELD_SIZE)).order(ByteOrder.BIG_ENDIAN).int
        if (metadataLength !in 1..MAX_METADATA_BYTES) throw IOException("Invalid omnifile metadata length")
        val metadata = OmnifileMetadata.fromJson(JSONObject(String(readExactly(inputStream, metadataLength), Charsets.UTF_8)))
        return if (isLegacy) {
            OmnifilePayload(metadata, readRemaining(inputStream, MAX_DATABASE_BYTES), null)
        } else {
            if (metadata.databaseLength !in 1..MAX_DATABASE_BYTES.toLong() ||
                metadata.projectsZipLength !in 0..MAX_PROJECT_ZIP_BYTES.toLong()
            ) throw IOException("Invalid omnifile payload lengths")
            val database = readExactly(inputStream, metadata.databaseLength.toInt())
            val projectsZip = if (metadata.projectsZipLength == 0L) null else readExactly(inputStream, metadata.projectsZipLength.toInt())
            if (inputStream.read() != -1) throw IOException("Unexpected omnifile trailing data")
            OmnifilePayload(metadata, database, projectsZip)
        }
    }

    fun zipProjectsDirectory(projectsDir: File): ByteArray? {
        if (!projectsDir.isDirectory) return null
        val output = ByteArrayOutputStream()
        var files = 0
        ZipOutputStream(output).use { zip ->
            projectsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                if (++files > MAX_PROJECT_FILES) throw IOException("Too many project files")
                if (file.length() > MAX_PROJECT_FILE_BYTES) throw IOException("Project file is too large: ${file.name}")
                val path = file.relativeTo(projectsDir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (output.size() > MAX_PROJECT_ZIP_BYTES) throw IOException("Project archive is too large")
            }
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** Extracts a project archive into an empty temporary directory. */
    fun extractProjectsZip(projectsZip: ByteArray, parentDir: File): File {
        if (projectsZip.size > MAX_PROJECT_ZIP_BYTES) throw IOException("Project archive is too large")
        val destination = File(parentDir, "projects-restore-${System.nanoTime()}")
        if (!destination.mkdirs()) throw IOException("Unable to create project restore directory")
        var files = 0
        var totalBytes = 0L
        try {
            ZipInputStream(ByteArrayInputStream(projectsZip)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(destination, entry.name)
                    val destinationPath = destination.canonicalPath + File.separator
                    if (entry.name.isBlank() || entry.name.startsWith('/') || entry.name.startsWith('\\') ||
                        !target.canonicalPath.startsWith(destinationPath)
                    ) throw IOException("Unsafe project archive path")
                    if (!entry.isDirectory) {
                        if (++files > MAX_PROJECT_FILES) throw IOException("Too many project files")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalBytes += count
                                if (entryBytes > MAX_PROJECT_FILE_BYTES || totalBytes > MAX_PROJECT_TOTAL_BYTES) {
                                    throw IOException("Project archive expands beyond allowed size")
                                }
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            return destination
        } catch (e: Exception) {
            destination.deleteRecursively()
            throw e
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw IOException("Unexpected end of omnifile")
            offset += count
        }
        return result
    }

    private fun readRemaining(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() > maxBytes - count) throw IOException("Omnifile payload is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
