package com.example.mcp

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

private const val TAG = "McpScriptManager"

object McpScriptManager {

    private val BUILTIN_SCRIPTS = mapOf(
        "node/mcp_filesystem.js" to "mcp_filesystem.js",
        "node/mcp_fetch.js" to "mcp_fetch.js",
        "node/mcp_socket_bridge.js" to "mcp_socket_bridge.js",
        "node/mcp_multi_bridge.js" to "mcp_multi_bridge.js",
        "node/mcp_pkg_manager.js" to "mcp_pkg_manager.js"
    )

    fun getMcpDir(context: Context): File {
        val external = Environment.getExternalStorageDirectory()
        return if (external != null && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(external, "OmniChat/mcp")
        } else {
            File(context.filesDir, "mcp")
        }
    }

    fun ensureScriptsDeployed(context: Context): File {
        val mcpDir = getMcpDir(context)
        mcpDir.mkdirs()
        for ((assetPath, fileName) in BUILTIN_SCRIPTS) {
            val destFile = File(mcpDir, fileName)
            deployScript(context, assetPath, destFile)
        }
        Log.i(TAG, "MCP dir: ${mcpDir.absolutePath}")
        return mcpDir
    }

    private fun deployScript(context: Context, assetPath: String, destFile: File) {
        try {
            val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
            if (destFile.exists() && destFile.readBytes().contentEquals(assetBytes)) return
            destFile.parentFile?.mkdirs()
            destFile.writeBytes(assetBytes)
            Log.d(TAG, "Deployed: ${destFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Deploy failed: $assetPath", e)
        }
    }

    fun getScriptPath(context: Context, scriptName: String): String {
        return File(getMcpDir(context), scriptName).absolutePath
    }

    fun listUserScripts(context: Context): List<File> {
        val mcpDir = getMcpDir(context)
        if (!mcpDir.exists()) return emptyList()
        return mcpDir.listFiles { f -> f.isFile && (f.name.endsWith(".js") || f.name.endsWith(".py")) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
}
