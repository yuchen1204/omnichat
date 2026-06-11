package com.omnichat.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.omnichat.data.AppDatabase
import com.omnichat.data.CloudBackupRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class CloudBackupRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "cloud_backup"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_WORKERS_URL = "workers_url"
        private const val DEFAULT_WORKERS_URL = "https://omnichat-cloud-backup.xiaoyuchen031204.workers.dev"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val database = AppDatabase.getDatabase(context)
    private val cloudBackupDao = database.cloudBackupDao()

    private var api: CloudBackupApi? = null

    private fun getApi(): CloudBackupApi {
        val baseUrl = prefs.getString(KEY_WORKERS_URL, DEFAULT_WORKERS_URL) ?: DEFAULT_WORKERS_URL

        if (api == null) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            api = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(CloudBackupApi::class.java)
        }

        return api!!
    }

    // --- User Management ---

    val userId: String?
        get() = prefs.getString(KEY_USER_ID, null)

    val sessionToken: String?
        get() = prefs.getString(KEY_SESSION_TOKEN, null)

    val isBound: Boolean
        get() = userId != null && sessionToken != null

    fun setWorkersUrl(url: String) {
        prefs.edit().putString(KEY_WORKERS_URL, url).apply()
        api = null // Reset API client
    }

    fun getWorkersUrl(): String {
        return prefs.getString(KEY_WORKERS_URL, DEFAULT_WORKERS_URL) ?: DEFAULT_WORKERS_URL
    }

    suspend fun bindTotp(): Result<BindTotpResponse> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().bindTotp()
            if (response.isSuccessful) {
                val body = response.body()!!
                prefs.edit()
                    .putString(KEY_USER_ID, body.userId)
                    .apply()
                Result.success(body)
            } else {
                Result.failure(Exception("Bind failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyAndBind(totpSecret: String, totpCode: String): Result<VerifyResponse> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().verify(VerifyRequest(totpSecret, totpCode))
            if (response.isSuccessful) {
                val body = response.body()!!
                prefs.edit()
                    .putString(KEY_SESSION_TOKEN, body.token)
                    .putString(KEY_USER_ID, body.userId)
                    .apply()
                Result.success(body)
            } else {
                Result.failure(Exception("Verify failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyForRecovery(totpSecret: String, totpCode: String): Result<VerifyResponse> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().verify(VerifyRequest(totpSecret, totpCode))
            if (response.isSuccessful) {
                val body = response.body()!!
                prefs.edit()
                    .putString(KEY_SESSION_TOKEN, body.token)
                    .putString(KEY_USER_ID, body.userId)
                    .apply()
                Result.success(body)
            } else {
                Result.failure(Exception("Verify failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun unbind() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_SESSION_TOKEN)
            .apply()
    }

    // --- Backup Operations ---

    suspend fun uploadBackup(
        type: String,
        data: ByteArray,
        filename: String
    ): Result<UploadResponse> = withContext(Dispatchers.IO) {
        try {
            val token = sessionToken ?: return@withContext Result.failure(Exception("Not bound"))
            val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)

            val response = getApi().upload(
                token = "Bearer $token",
                request = UploadRequest(type, base64Data, filename)
            )

            if (response.isSuccessful) {
                val body = response.body()!!

                // Save to local database
                val userId = userId ?: return@withContext Result.failure(Exception("No user ID"))
                cloudBackupDao.insertBackup(
                    CloudBackupRecord(
                        backupId = body.backupId,
                        type = type,
                        filename = filename,
                        createdAt = body.createdAt,
                        userId = userId
                    )
                )

                Result.success(body)
            } else if (response.code() == 401) {
                // Token expired, clear it
                prefs.edit().remove(KEY_SESSION_TOKEN).apply()
                Result.failure(Exception("Session expired"))
            } else {
                Result.failure(Exception("Upload failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listBackups(): Result<List<BackupMeta>> = withContext(Dispatchers.IO) {
        try {
            val token = sessionToken ?: return@withContext Result.failure(Exception("Not bound"))
            val response = getApi().list("Bearer $token")

            if (response.isSuccessful) {
                Result.success(response.body()?.backups ?: emptyList())
            } else if (response.code() == 401) {
                prefs.edit().remove(KEY_SESSION_TOKEN).apply()
                Result.failure(Exception("Session expired"))
            } else {
                Result.failure(Exception("List failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadBackup(backupId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val token = sessionToken ?: return@withContext Result.failure(Exception("Not bound"))
            val response = getApi().download("Bearer $token", backupId)

            if (response.isSuccessful) {
                Result.success(response.body()?.bytes() ?: ByteArray(0))
            } else if (response.code() == 401) {
                prefs.edit().remove(KEY_SESSION_TOKEN).apply()
                Result.failure(Exception("Session expired"))
            } else {
                Result.failure(Exception("Download failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = sessionToken ?: return@withContext Result.failure(Exception("Not bound"))
            val response = getApi().delete("Bearer $token", backupId)

            if (response.isSuccessful) {
                cloudBackupDao.deleteBackup(
                    CloudBackupRecord(
                        backupId = backupId,
                        type = "",
                        filename = "",
                        createdAt = 0,
                        userId = userId ?: ""
                    )
                )
                Result.success(Unit)
            } else if (response.code() == 401) {
                prefs.edit().remove(KEY_SESSION_TOKEN).apply()
                Result.failure(Exception("Session expired"))
            } else {
                Result.failure(Exception("Delete failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Local Backup Records ---

    fun getLocalBackups(): Flow<List<CloudBackupRecord>> {
        val uid = userId ?: return flowOf(emptyList())
        return cloudBackupDao.getBackupsByUser(uid)
    }

    suspend fun clearLocalBackups() {
        val uid = userId ?: return
        cloudBackupDao.deleteAllBackupsForUser(uid)
    }
}
