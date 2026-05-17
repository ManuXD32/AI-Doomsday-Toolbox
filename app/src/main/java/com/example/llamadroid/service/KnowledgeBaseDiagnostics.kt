package com.example.llamadroid.service

import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object KnowledgeBaseDiagnostics {
    private const val MAX_LOGS = 1_000
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    fun log(message: String) {
        val entry = LogEntry(System.currentTimeMillis(), message)
        _logs.value = (_logs.value + entry).takeLast(MAX_LOGS)
        DebugLog.log("[KnowledgeBase] $message")
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
