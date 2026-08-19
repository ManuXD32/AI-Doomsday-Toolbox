package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerCardEntity

enum class LlamaServerSessionStatus {
    STOPPED,
    STARTING,
    LOADING,
    RUNNING,
    ERROR
}

data class LlamaServerSessionSnapshot(
    val sessionId: String,
    val status: LlamaServerSessionStatus = LlamaServerSessionStatus.STOPPED,
    val port: Int? = null,
    val pid: Int? = null,
    val error: String? = null,
    val progress: Float? = null,
    val statusText: String? = null,
    val command: String? = null,
    val logLineCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean get() = status == LlamaServerSessionStatus.RUNNING
    val isBusy: Boolean get() = status == LlamaServerSessionStatus.STARTING || status == LlamaServerSessionStatus.LOADING
}

fun ServerState.toLlamaServerSessionStatus(): LlamaServerSessionStatus = when (this) {
    ServerState.Stopped -> LlamaServerSessionStatus.STOPPED
    ServerState.Starting -> LlamaServerSessionStatus.STARTING
    is ServerState.Loading -> LlamaServerSessionStatus.LOADING
    is ServerState.Running -> LlamaServerSessionStatus.RUNNING
    is ServerState.Error -> LlamaServerSessionStatus.ERROR
}

fun LlamaServerCardEntity.sessionIdForRuntime(): String = LlamaServerCardEntity.sessionIdForCard(id)

