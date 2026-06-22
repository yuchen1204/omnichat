package com.omnichat.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * 云备份诊断工具 ViewModel
 * 用于测试 SSL 连接和排查证书问题
 */
class CloudBackupDiagnosticViewModel(
    private val context: Context
) : ViewModel() {

    private val _diagnosticState = MutableStateFlow<DiagnosticState>(DiagnosticState.Idle)
    val diagnosticState: StateFlow<DiagnosticState> = _diagnosticState.asStateFlow()

    fun runDiagnostic() {
        viewModelScope.launch {
            _diagnosticState.value = DiagnosticState.Running

            val sslResult = SslTestUtil.testCertificateChain(context)
            val report = SslTestUtil.formatResult(sslResult)

            _diagnosticState.value = DiagnosticState.Completed(
                sslSuccess = sslResult.success,
                report = report,
                certificates = sslResult.certificates,
                error = sslResult.error
            )
        }
    }

    fun clearState() {
        _diagnosticState.value = DiagnosticState.Idle
    }
}

sealed class DiagnosticState {
    object Idle : DiagnosticState()
    object Running : DiagnosticState()
    data class Completed(
        val sslSuccess: Boolean,
        val report: String,
        val certificates: List<SslTestUtil.CertificateInfo>,
        val error: String?
    ) : DiagnosticState()
}