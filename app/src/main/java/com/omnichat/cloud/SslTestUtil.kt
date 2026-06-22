package com.omnichat.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * SSL 证书链测试工具
 * 用于诊断 Cloudflare Workers HTTPS 连接问题
 */
object SslTestUtil {
    private const val TAG = "SslTestUtil"
    private const val WORKERS_URL = "https://omnichat-cloud-backup.xiaoyuchen031204.workers.dev"

    /**
     * 测试证书链验证
     * @return 测试结果，包含证书详情和验证状态
     */
    suspend fun testCertificateChain(context: Context): TestResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$WORKERS_URL/api/bindtotp")
            val connection = url.openConnection() as HttpsURLConnection

            connection.apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            // 发送空 POST 请求
            connection.outputStream.use { os ->
                os.write("{}".toByteArray())
            }

            // 触发连接
            connection.connect()

            // 获取服务器证书链
            val serverCerts = connection.serverCertificates
            val certInfo = (serverCerts as Array<X509Certificate>).mapIndexed { index, cert ->
                CertificateInfo(
                    index = index,
                    type = cert.type,
                    subject = cert.subjectX500Principal.name,
                    issuer = cert.issuerX500Principal.name,
                    notBefore = cert.notBefore.toString(),
                    notAfter = cert.notAfter.toString()
                )
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            TestResult(
                success = true,
                message = "证书链验证成功",
                responseCode = responseCode,
                certificates = certInfo,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Certificate chain test failed", e)
            TestResult(
                success = false,
                message = "证书链验证失败: ${e.message}",
                responseCode = -1,
                certificates = emptyList(),
                error = e.stackTraceToString()
            )
        }
    }

    data class TestResult(
        val success: Boolean,
        val message: String,
        val responseCode: Int,
        val certificates: List<CertificateInfo>,
        val error: String?
    )

    data class CertificateInfo(
        val index: Int,
        val type: String,
        val subject: String,
        val issuer: String,
        val notBefore: String,
        val notAfter: String
    )

    /**
     * 格式化输出测试结果
     */
    fun formatResult(result: TestResult): String {
        return buildString {
            appendLine("=== SSL 证书链测试结果 ===")
            appendLine("状态: ${if (result.success) "✅ 成功" else "❌ 失败"}")
            appendLine("消息: ${result.message}")
            appendLine("HTTP 状态码: ${result.responseCode}")
            appendLine()

            if (result.certificates.isNotEmpty()) {
                appendLine("证书链 (${result.certificates.size} 个证书):")
                result.certificates.forEach { cert ->
                    appendLine("  [${cert.index}] ${cert.type}")
                    appendLine("      Subject: ${cert.subject}")
                    appendLine("      Issuer: ${cert.issuer}")
                    appendLine("      Valid: ${cert.notBefore} → ${cert.notAfter}")
                }
            }

            if (result.error != null) {
                appendLine()
                appendLine("错误详情:")
                appendLine(result.error)
            }
        }
    }
}
