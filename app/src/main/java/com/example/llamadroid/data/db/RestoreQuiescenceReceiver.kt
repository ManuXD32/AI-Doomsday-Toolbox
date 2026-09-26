package com.example.llamadroid.data.db

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process

/** Explicit control endpoint hosted in each app worker process. */
abstract class RestoreQuiescenceReceiver(private val workerId: String) : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        val operationId = intent.getStringExtra("restore_operation_id") ?: return
        if (!RestoreCoordinator.hasPending(context)) return
        val acknowledged = runCatching {
            RestoreCoordinator.acknowledgeWorkerQuiescence(context, operationId, workerId)
        }.isSuccess
        if (acknowledged) Process.killProcess(Process.myPid())
    }
}

class LlamaRuntimeRestoreReceiver : RestoreQuiescenceReceiver("llama_runtime")
class LlamaSessionsRestoreReceiver : RestoreQuiescenceReceiver("llama_sessions_runtime")
class DistributedLlamaRestoreReceiver : RestoreQuiescenceReceiver("distributed_llama_runtime")
class StableAudioRestoreReceiver : RestoreQuiescenceReceiver("stable_audio_worker")
class OnnxBgrRestoreReceiver : RestoreQuiescenceReceiver("onnx_bgr")
class LiteRtRestoreReceiver : RestoreQuiescenceReceiver("litert_lm")
class AgentRemoteRestoreReceiver : RestoreQuiescenceReceiver("agent_remote")
