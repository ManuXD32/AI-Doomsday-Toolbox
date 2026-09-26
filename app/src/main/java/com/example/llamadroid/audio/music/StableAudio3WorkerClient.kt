package com.example.llamadroid.audio.music

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.UUID

/** The foreground coordinator owns this connection; native work remains in its own process. */
internal class StableAudio3WorkerClient(private val context: Context) {
    suspend fun run(
        request: StableAudio3Request,
        onProgress: suspend (String, Int, Int) -> Unit,
        isCancelled: () -> Boolean
    ): StableAudio3Result = coroutineScope {
        val id = UUID.randomUUID().toString()
        val connected = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<StableAudio3Result>()
        val progress = Channel<Triple<String, Int, Int>>(Channel.CONFLATED)
        var remote: Messenger? = null
        var bound = false
        val workerPid = java.util.concurrent.atomic.AtomicInteger(0)
        var lastStage = "starting"
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (!StableAudio3WorkerProtocol.accepted(message) || StableAudio3WorkerProtocol.requestId(message) != id) return
                val data = message.data
                data.getInt(StableAudio3WorkerProtocol.KEY_WORKER_PID, 0)
                    .takeIf { it > 0 && it != Process.myPid() }
                    ?.let { workerPid.set(it) }
                when (message.what) {
                    StableAudio3WorkerProtocol.MSG_PROGRESS -> {
                        lastStage = data.getString(StableAudio3WorkerProtocol.KEY_STAGE).orEmpty()
                        progress.trySend(Triple(lastStage,
                        data.getInt(StableAudio3WorkerProtocol.KEY_COMPLETED).coerceAtLeast(0),
                        data.getInt(StableAudio3WorkerProtocol.KEY_TOTAL).coerceAtLeast(0)))
                    }
                    StableAudio3WorkerProtocol.MSG_COMPLETE -> try {
                        result.complete(StableAudio3Result.fromJson(JSONObject(
                            data.getString(StableAudio3WorkerProtocol.KEY_RESULT_JSON) ?: error("output_invalid"))))
                    } catch (_: Exception) { result.completeExceptionally(StableAudio3Failure.fromNative("output_invalid", lastStage)) }
                    StableAudio3WorkerProtocol.MSG_ERROR -> {
                        val code = data.getString(StableAudio3WorkerProtocol.KEY_ERROR_CODE).orEmpty()
                        if (code == StableAudio3WorkerProtocol.ERROR_CANCELLED) result.completeExceptionally(CancellationException("Audio cancelled"))
                        else result.completeExceptionally(StableAudio3Failure.fromWire(code,
                            data.getString(StableAudio3WorkerProtocol.KEY_STAGE) ?: lastStage,
                            if (data.containsKey(StableAudio3WorkerProtocol.KEY_NATIVE_STATUS))
                                data.getInt(StableAudio3WorkerProtocol.KEY_NATIVE_STATUS) else null,
                            data.getString(StableAudio3WorkerProtocol.KEY_NATIVE_OPERATION)))
                    }
                }
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { disconnected() }
            override fun onBindingDied(name: ComponentName) { disconnected() }
            override fun onNullBinding(name: ComponentName) { disconnected() }
            private fun disconnected() {
                val error = StableAudio3Failure.fromNative("worker_process_died", lastStage)
                connected.completeExceptionally(error)
                result.completeExceptionally(error)
            }
        }
        val progressJob = launch {
            for ((stage, completed, total) in progress) onProgress(stage, completed, total)
        }
        try {
            bound = withContext(Dispatchers.Main.immediate) {
                context.bindService(Intent(context, StableAudio3WorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            check(bound) { "worker_bind_failed" }
            val worker = withTimeout(30_000) {
                // Cancellation is a persisted service flag rather than a
                // coroutine cancellation, so poll while Android is bringing
                // up the secondary process as well as during inference.
                while (!connected.isCompleted) {
                    ensureActive()
                    if (isCancelled()) throw CancellationException("Audio cancelled")
                    delay(100)
                }
                connected.await()
            }
            remote = worker
            worker.send(StableAudio3WorkerProtocol.newMessage(StableAudio3WorkerProtocol.MSG_START, id) {
                putString(StableAudio3WorkerProtocol.KEY_REQUEST_JSON, request.toJsonString())
            }.apply { replyTo = reply })
            withTimeout(2 * 60 * 60 * 1000L) {
                while (!result.isCompleted) {
                    ensureActive()
                    if (isCancelled()) throw CancellationException("Audio cancelled")
                    delay(200)
                }
                result.await()
            }
        } finally {
            withContext(NonCancellable) {
                if (!result.isCompleted && remote != null) {
                    runCatching { remote?.send(StableAudio3WorkerProtocol.newMessage(StableAudio3WorkerProtocol.MSG_CANCEL, id)) }
                    withTimeoutOrNull(5_000) { runCatching { result.await() } }
                    if (!result.isCompleted) {
                        // Only this application's dedicated audio worker can be terminated.
                        // Ending a stuck native call prevents the next queued job overlapping it.
                        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val worker = manager.runningAppProcesses.orEmpty().firstOrNull {
                            it.pid == workerPid.get() && it.uid == Process.myUid() &&
                                it.processName == "${context.packageName}:stable_audio_worker"
                        }
                        if (worker != null) Process.killProcess(worker.pid)
                    }
                }
                if (bound) withContext(Dispatchers.Main.immediate) { runCatching { context.unbindService(connection) } }
                progress.close()
                progressJob.cancelAndJoin()
            }
        }
    }
}
