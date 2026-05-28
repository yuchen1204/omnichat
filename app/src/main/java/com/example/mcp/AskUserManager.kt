package com.example.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

object AskUserManager {
    data class AskUserRequest(
        val id: String = UUID.randomUUID().toString(),
        val question: String,
        val options: List<String>,
        val multiSelect: Boolean = false,
        val deferred: CompletableDeferred<String>
    )

    private val _requests = MutableStateFlow<List<AskUserRequest>>(emptyList())
    val requests = _requests.asStateFlow()

    suspend fun askUser(question: String, options: List<String>, multiSelect: Boolean = false): String {
        val deferred = CompletableDeferred<String>()
        val request = AskUserRequest(question = question, options = options, multiSelect = multiSelect, deferred = deferred)
        _requests.update { it + request }
        try {
            return deferred.await()
        } finally {
            _requests.update { list -> list.filter { it.id != request.id } }
        }
    }

    fun respond(requestId: String, response: String) {
        _requests.value.find { it.id == requestId }?.deferred?.complete(response)
    }

    fun clearAll() {
        _requests.value.forEach { 
            if (!it.deferred.isCompleted) {
                it.deferred.complete("User cancelled the clarification request.")
            }
        }
        _requests.update { emptyList() }
    }
}
