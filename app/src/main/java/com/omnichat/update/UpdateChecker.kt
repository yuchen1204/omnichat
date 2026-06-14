package com.omnichat.update

import android.content.Context
import android.content.SharedPreferences
import com.omnichat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub for new releases by comparing local versionName against
 * remote tags matching the pattern "Release-V*".
 */
object UpdateChecker {

    private const val REPO_OWNER = "yuchen1204"
    private const val REPO_NAME = "omnichat"
    private const val TAG_PREFIX = "Release-V"
    private const val PREFS_NAME = "update_checker"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the latest version tag (e.g. "V1.2.3") from GitHub, or null on failure.
     */
    suspend fun fetchLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/tags?per_page=30"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "OmniChat-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val tags = JSONArray(body)

            // Find the first tag matching our prefix and extract version
            for (i in 0 until tags.length()) {
                val name = tags.getJSONObject(i).optString("name", "")
                if (name.startsWith(TAG_PREFIX)) {
                    return@withContext name.removePrefix(TAG_PREFIX)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compares two version strings (e.g. "1.2.3" vs "1.2.4").
     * Returns true if [remote] is newer than [local].
     */
    fun isNewer(local: String, remote: String): Boolean {
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(localParts.size, remoteParts.size)

        for (i in 0 until maxLen) {
            val l = localParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun isDismissed(context: Context, version: String): Boolean =
        prefs(context).getString(KEY_DISMISSED_VERSION, null) == version

    fun dismiss(context: Context, version: String) {
        prefs(context).edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    fun clearDismissed(context: Context) {
        prefs(context).edit().remove(KEY_DISMISSED_VERSION).apply()
    }
}
