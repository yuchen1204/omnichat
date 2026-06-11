package com.omnichat.cloud

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

// Request/Response data classes
data class BindTotpResponse(
    val userId: String,
    val totpSecret: String,
    val qrCodeUrl: String
)

data class VerifyRequest(
    val totpSecret: String,
    val totpCode: String
)

data class VerifyResponse(
    val userId: String,
    val token: String,
    val expiresIn: Long
)

data class UploadRequest(
    val type: String,        // "omnidb" | "omniconfig"
    val data: String,        // base64 encoded
    val filename: String
)

data class UploadResponse(
    val backupId: String,
    val createdAt: Long
)

data class BackupMeta(
    val id: String,
    val type: String,
    val size: Long,
    val filename: String,
    val createdAt: Long
)

data class ListResponse(
    val backups: List<BackupMeta>
)

data class RecoverRequest(
    val totpCode: String
)

data class RecoverResponse(
    val userId: String,
    val totpSecret: String,
    val token: String,
    val expiresIn: Long,
    val backups: List<BackupMeta>
)

interface CloudBackupApi {

    @POST("/api/bindtotp")
    suspend fun bindTotp(): Response<BindTotpResponse>

    @POST("/api/verify")
    suspend fun verify(@Body request: VerifyRequest): Response<VerifyResponse>

    @POST("/api/recover")
    suspend fun recover(@Body request: RecoverRequest): Response<RecoverResponse>

    @POST("/api/upload")
    suspend fun upload(
        @Header("Authorization") token: String,
        @Body request: UploadRequest
    ): Response<UploadResponse>

    @GET("/api/list")
    suspend fun list(
        @Header("Authorization") token: String
    ): Response<ListResponse>

    @GET("/api/download/{backupId}")
    suspend fun download(
        @Header("Authorization") token: String,
        @Path("backupId") backupId: String
    ): Response<ResponseBody>

    @DELETE("/api/delete/{backupId}")
    suspend fun delete(
        @Header("Authorization") token: String,
        @Path("backupId") backupId: String
    ): Response<Unit>
}
