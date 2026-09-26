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
        private val nativeOperations = setOf("create_environment", "create_options", "set_cpu_options",
            "load_model", "map_model_for_buffer", "load_model_from_buffer", "compile_model",
            "set_cancellation", "query_inputs", "query_outputs", "query_input_name",
            "query_input_tensor", "query_input_type", "resize_input", "query_input_layout",
            "query_output_layouts", "query_output_tensor", "query_output_type", "create_input_buffer",
            "map_input_buffer", "create_output_buffer", "run_model", "read_output", "query_signature",
            "query_signatures", "query_signature_key")
        private val stages = setOf("starting", "validating", "loading", "tokenizing",
            "conditioning", "audio_encoding", "sampling", "decoding")

        fun fromNative(message: String?, stage: String): StableAudio3Failure {
            val parts = message.orEmpty().split(':', limit = 3)
            return fromWire(parts[0], stage, parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2))
        }

        fun fromWire(code: String?, stage: String?, status: Int? = null,
                     nativeOperation: String? = null): StableAudio3Failure {
            val safeCode = code?.takeIf { codePattern.matches(it) } ?: "native_pipeline_failed"
            val legacyOperation = operationPattern.matchEntire(safeCode)?.groupValues?.get(1)
            val operation = if (legacyOperation != null) {
                nativeOperation?.takeIf { it in nativeOperations } ?: legacyOperation
            } else null
            return StableAudio3Failure(safeCode, stage?.takeIf { it in stages } ?: "starting",
                operation, status.takeIf { operation != null })
        }
    }
}
