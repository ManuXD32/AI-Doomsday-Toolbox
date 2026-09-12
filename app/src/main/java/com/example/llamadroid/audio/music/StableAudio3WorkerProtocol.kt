package com.example.llamadroid.audio.music

import android.os.Bundle
import android.os.Message

/**
 * Small, versioned protocol between the audio foreground service and the
 * isolated Stable Audio process. The worker never owns the Room queue: the
 * foreground service remains the owner of retries, cancellation, and output
 * persistence.
 */
internal object StableAudio3WorkerProtocol {
    const val PROTOCOL_VERSION = 1

    const val MSG_START = 1
    const val MSG_PROGRESS = 2
    const val MSG_COMPLETE = 3
    const val MSG_ERROR = 4
    const val MSG_CANCEL = 5

    const val KEY_PROTOCOL_VERSION = "protocol_version"
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_REQUEST_JSON = "request_json"
    const val KEY_STAGE = "stage"
    const val KEY_WORKER_PID = "worker_pid"
    const val KEY_COMPLETED = "completed"
    const val KEY_TOTAL = "total"
    const val KEY_RESULT_JSON = "result_json"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_NATIVE_STATUS = "native_status"

    const val ERROR_BAD_REQUEST = "bad_request"
    const val ERROR_MODEL_STALE = "stable_audio_model_stale"
    const val ERROR_BUSY = "worker_busy"
    const val ERROR_CANCELLED = "cancelled"
    const val ERROR_NATIVE_UNAVAILABLE = "native_pipeline_unavailable"
    const val ERROR_NATIVE_FAILURE = "native_pipeline_failed"
    const val ERROR_OUTPUT_INVALID = "output_invalid"
    const val ERROR_TIMEOUT = "native_timeout"
    const val ERROR_PROCESS_DIED = "worker_process_died"

    fun newMessage(what: Int, requestId: String, block: Bundle.() -> Unit = {}): Message =
        Message.obtain().apply {
            this.what = what
            data = Bundle().apply {
                putInt(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION)
                putString(KEY_REQUEST_ID, requestId)
                block()
            }
        }

    fun requestId(message: Message): String =
        message.data.getString(KEY_REQUEST_ID).orEmpty()

    fun accepted(message: Message): Boolean =
        message.data.containsKey(KEY_PROTOCOL_VERSION) &&
            message.data.getInt(KEY_PROTOCOL_VERSION, -1) == PROTOCOL_VERSION
}
