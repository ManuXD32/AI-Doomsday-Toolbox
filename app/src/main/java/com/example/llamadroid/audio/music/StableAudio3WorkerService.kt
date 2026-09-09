package com.example.llamadroid.audio.music

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.example.llamadroid.audio.AudioFileInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Isolated Stable Audio process. It has no Room access and owns no queue state;
 * the main-process audio foreground service is the sole owner of retry/output
 * persistence. This service only runs one validated native request at a time.
 */
class StableAudio3WorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = IncomingHandler()
    private val messenger = Messenger(handler)

    @Volatile private var activeJob: Job? = null
    @Volatile private var activeRequestId: String? = null
    @Volatile private var activeReply: Messenger? = null
    @Volatile private var cancellationRequested = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { StableAudio3Native.cancel() }
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!StableAudio3WorkerProtocol.accepted(message)) {
                sendError(message.replyTo, StableAudio3WorkerProtocol.requestId(message),
                    StableAudio3WorkerProtocol.ERROR_BAD_REQUEST)
                return
            }
            when (message.what) {
                StableAudio3WorkerProtocol.MSG_START -> start(message)
                StableAudio3WorkerProtocol.MSG_CANCEL -> cancel(message)
                else -> sendError(message.replyTo, StableAudio3WorkerProtocol.requestId(message),
                    StableAudio3WorkerProtocol.ERROR_BAD_REQUEST)
            }
        }
    }

    private fun start(message: Message) {
        val requestId = StableAudio3WorkerProtocol.requestId(message)
        val reply = message.replyTo
        if (reply == null) {
            sendError(null, requestId, StableAudio3WorkerProtocol.ERROR_BAD_REQUEST)
            return
        }
        // The coroutine may report inactive while JNI is still unwinding. The
        // request lease, rather than Job.isActive, serializes native calls.
        if (activeRequestId != null) {
            sendError(reply, requestId, StableAudio3WorkerProtocol.ERROR_BUSY)
            return
        }
        val raw = message.data.getString(StableAudio3WorkerProtocol.KEY_REQUEST_JSON)
        if (raw.isNullOrBlank()) {
            sendError(reply, requestId, StableAudio3WorkerProtocol.ERROR_BAD_REQUEST)
            return
        }
        val request = try {
            StableAudio3Request.fromJsonString(raw).validate(supportsExtend = true)
        } catch (_: Throwable) {
            sendError(reply, requestId, StableAudio3WorkerProtocol.ERROR_BAD_REQUEST)
            return
        }
        activeRequestId = requestId
        activeReply = reply
        cancellationRequested = false
        activeJob = scope.launch {
            var lastStage = "validating"
            try {
                reply.send(
                    StableAudio3WorkerProtocol.newMessage(
                        StableAudio3WorkerProtocol.MSG_PROGRESS, requestId
                    ) {
                        putString(StableAudio3WorkerProtocol.KEY_STAGE, "validating")
                        putInt(StableAudio3WorkerProtocol.KEY_WORKER_PID, Process.myPid())
                        putInt(StableAudio3WorkerProtocol.KEY_COMPLETED, 0)
                        putInt(StableAudio3WorkerProtocol.KEY_TOTAL, 0)
                    }
                )
                withContext(Dispatchers.IO) {
                    verifyComponents(request)
                }
                if (cancellationRequested) throw CancellationException("Audio cancelled")
                lastStage = "loading"
                val result = StableAudio3Native.run(request, object : StableAudio3Native.ProgressSink {
                    override fun onProgress(stage: String, completed: Int, total: Int) {
                        lastStage = stage
                        val progress = StableAudio3WorkerProtocol.newMessage(
                            StableAudio3WorkerProtocol.MSG_PROGRESS, requestId
                        ) {
                            putString(StableAudio3WorkerProtocol.KEY_STAGE, stage)
                            putInt(StableAudio3WorkerProtocol.KEY_COMPLETED, completed)
                            putInt(StableAudio3WorkerProtocol.KEY_TOTAL, total)
                        }
                        runCatching { reply.send(progress) }
                    }
                })
                ensureActive()
                reply.send(
                    StableAudio3WorkerProtocol.newMessage(
                        StableAudio3WorkerProtocol.MSG_COMPLETE, requestId
                    ) { putString(StableAudio3WorkerProtocol.KEY_RESULT_JSON, result) }
                )
            } catch (cancelled: CancellationException) {
                sendError(reply, requestId, StableAudio3WorkerProtocol.ERROR_CANCELLED, lastStage)
            } catch (_: IllegalArgumentException) {
                sendError(reply, requestId, StableAudio3WorkerProtocol.ERROR_BAD_REQUEST, lastStage)
            } catch (error: Throwable) {
                val failure = when (error) {
                    is OutOfMemoryError -> StableAudio3Failure.fromNative("native_memory_exhausted", lastStage)
                    is LinkageError -> StableAudio3Failure.fromNative("native_pipeline_unavailable", lastStage)
                    is IllegalStateException -> StableAudio3Failure.fromNative(error.message, lastStage)
                    else -> StableAudio3Failure.fromNative(null, lastStage)
                }
                sendError(reply, requestId, failure.code, failure.stage, failure.nativeStatus)
            } finally {
                activeJob = null
                activeReply = null
                cancellationRequested = false
                // Clear the lease last. A new START can then never observe a
                // partially-cleared request and have its reply state erased by
                // this old coroutine's finally block.
                if (activeRequestId == requestId) activeRequestId = null
            }
        }
    }

    private fun cancel(message: Message) {
        val requestId = StableAudio3WorkerProtocol.requestId(message)
        if (requestId.isBlank() || requestId != activeRequestId) return
        cancellationRequested = true
        StableAudio3Native.cancel()
    }

    private fun sendError(reply: Messenger?, requestId: String, code: String,
                          stage: String = "starting", nativeStatus: Int? = null) {
        if (reply == null) return
        runCatching {
            reply.send(
                StableAudio3WorkerProtocol.newMessage(
                    StableAudio3WorkerProtocol.MSG_ERROR, requestId
                ) {
                    putString(StableAudio3WorkerProtocol.KEY_ERROR_CODE, code)
                    putString(StableAudio3WorkerProtocol.KEY_STAGE, stage)
                    nativeStatus?.let { putInt(StableAudio3WorkerProtocol.KEY_NATIVE_STATUS, it) }
                }
            )
        }
    }

    private suspend fun verifyComponents(request: StableAudio3Request) {
        checkCancellation()
        val needsEncoder = request.operation != StableAudio3Operation.GENERATE
        request.components.validate(requireEncoder = needsEncoder)
        if (request.operation == StableAudio3Operation.EXTEND) {
            require(request.maskStartSeconds != null && request.maskEndSeconds != null) {
                "extension mask must be prepared before worker start"
            }
        }
        for ((role, component) in request.components.all()) {
            coroutineContext.ensureActive()
            checkCancellation()
            val file = File(component.path)
            require(file.isFile && file.length() > 0L) { "$role component is unavailable" }
            component.sizeBytes?.let { require(file.length() == it) { "$role component size changed" } }
            component.sha256?.let { require(digest(file) == it.lowercase()) { "$role component digest changed" } }
        }
        request.initAudioPath?.let {
            val file = File(it)
            require(file.isFile && file.length() > 44L) { "input audio is unavailable" }
            // Native consumes only this bounded PCM16/44.1kHz/stereo contract.
            val info = AudioFileInspector.inspect(file)
            require(info.sampleRate == StableAudio3Ids.SAMPLE_RATE && info.channels == 2 &&
                info.bitsPerSample == 16 && info.format == "wav") {
                "input audio must be PCM16 44.1 kHz stereo"
            }
        }
        File(request.outputPath).parentFile?.mkdirs()
    }

    private fun checkCancellation() {
        if (cancellationRequested) throw CancellationException("Audio cancelled")
    }

    private suspend fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
