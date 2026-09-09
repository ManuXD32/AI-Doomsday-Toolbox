package com.example.llamadroid.audio.music

/** Bounded machine metadata only: never native messages, paths or input text. */
internal class StableAudio3Failure private constructor(
    val code: String,
    val stage: String,
    val operation: String?,
    val nativeStatus: Int?
) : IllegalStateException(code) {
    fun diagnosticMetadata(): String =
        "code=$code stage=$stage operation=${operation ?: "unknown"} status=${nativeStatus ?: "unknown"}"

    companion object {
        private val codePattern = Regex("[a-z][a-z0-9_]{0,79}")
        private val operationPattern = Regex("litert_([a-z0-9_]+)_failed")
        private val stages = setOf("starting", "validating", "loading", "tokenizing",
            "conditioning", "audio_encoding", "sampling", "decoding")

        fun fromNative(message: String?, stage: String): StableAudio3Failure {
            val parts = message.orEmpty().split(':', limit = 2)
            return fromWire(parts[0], stage, parts.getOrNull(1)?.toIntOrNull())
        }

        fun fromWire(code: String?, stage: String?, status: Int? = null): StableAudio3Failure {
            val safeCode = code?.takeIf { codePattern.matches(it) } ?: "native_pipeline_failed"
            val operation = operationPattern.matchEntire(safeCode)?.groupValues?.get(1)
            return StableAudio3Failure(safeCode, stage?.takeIf { it in stages } ?: "starting",
                operation, status.takeIf { operation != null })
        }
    }
}
