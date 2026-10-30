package com.example.llamadroid.service

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.example.llamadroid.data.model.LlamaServerCardEntity

@Keep
enum class LlamaServerSessionStatus {
    @SerializedName(value = "STOPPED", alternate = ["b"]) STOPPED,
    @SerializedName(value = "STARTING", alternate = ["c"]) STARTING,
    @SerializedName(value = "LOADING", alternate = ["d"]) LOADING,
    @SerializedName(value = "RUNNING", alternate = ["f"]) RUNNING,
    @SerializedName(value = "ERROR", alternate = ["g"]) ERROR
}

@Keep
data class LlamaServerSessionSnapshot(
    @SerializedName(value = "sessionId", alternate = ["a"]) val sessionId: String,
    @SerializedName(value = "status", alternate = ["b"]) val status: LlamaServerSessionStatus = LlamaServerSessionStatus.STOPPED,
    @SerializedName(value = "port", alternate = ["c"]) val port: Int? = null,
    @SerializedName(value = "pid", alternate = ["d"]) val pid: Int? = null,
    @SerializedName(value = "error", alternate = ["e"]) val error: String? = null,
    @SerializedName(value = "progress", alternate = ["f"]) val progress: Float? = null,
    @SerializedName(value = "statusText", alternate = ["g"]) val statusText: String? = null,
    @SerializedName(value = "command", alternate = ["h"]) val command: String? = null,
    @SerializedName(value = "logLineCount", alternate = ["i"]) val logLineCount: Int = 0,
    @SerializedName(value = "updatedAt", alternate = ["j"]) val updatedAt: Long = System.currentTimeMillis(),
    /** Localized by consumers; no raw native output is needed for this explanation. */
    @SerializedName(value = "stopReason", alternate = ["k"]) val stopReason: String? = null,
    /** Same-boot activity checkpoint; keeps service adoption from restarting the idle countdown. */
    @SerializedName(value = "idleActivityAtElapsedMs", alternate = ["l"]) val idleActivityAtElapsedMs: Long? = null,
    @SerializedName(value = "idleActivityFingerprint", alternate = ["m"]) val idleActivityFingerprint: String? = null
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
