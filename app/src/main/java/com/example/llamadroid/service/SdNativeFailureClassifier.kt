package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import java.util.ArrayDeque

enum class SdFailureCategory {
    MISSING_VAE,
    MODEL_LAYOUT_MISMATCH,
    TENSOR_MISMATCH,
    MODEL_METADATA_INVALID,
    MODEL_FILE_CORRUPT,
    UNSUPPORTED_MODEL,
    UNSUPPORTED_BINARY_FLAG,
    OUT_OF_MEMORY,
    NATIVE_ABORT,
    ACCELERATOR_FAILURE,
    PROCESS_KILLED,
    UNKNOWN_NATIVE_FAILURE
}

data class SdFailureReport(
    val category: SdFailureCategory,
    val exitCode: Int,
    val signal: Int? = null,
    val stage: SdProgressPhase? = null,
    val technicalSummary: String,
    val recentNativeOutput: List<String> = emptyList()
)

class SdNativeFailureException(
    val report: SdFailureReport,
    message: String
) : RuntimeException(message)

fun sdNativeFailureMessage(context: Context, report: SdFailureReport): String = context.getString(
    when (report.category) {
        SdFailureCategory.MISSING_VAE -> R.string.imagegen_error_sd_missing_vae
        SdFailureCategory.MODEL_LAYOUT_MISMATCH -> R.string.imagegen_error_sd_model_layout
        SdFailureCategory.TENSOR_MISMATCH -> R.string.imagegen_error_sd_metadata_mismatch
        SdFailureCategory.MODEL_METADATA_INVALID -> R.string.imagegen_error_sd_metadata_mismatch
        SdFailureCategory.MODEL_FILE_CORRUPT,
        SdFailureCategory.UNSUPPORTED_MODEL -> R.string.imagegen_error_sd_corrupt_model
        SdFailureCategory.UNSUPPORTED_BINARY_FLAG -> R.string.imagegen_error_sd_unsupported_cli
        SdFailureCategory.OUT_OF_MEMORY -> R.string.imagegen_error_sd_out_of_memory
        SdFailureCategory.NATIVE_ABORT -> R.string.imagegen_error_sd_sigabrt
        SdFailureCategory.PROCESS_KILLED -> R.string.imagegen_error_sd_process_killed
        SdFailureCategory.ACCELERATOR_FAILURE -> R.string.imagegen_error_sd_accelerator_failure
        SdFailureCategory.UNKNOWN_NATIVE_FAILURE -> R.string.imagegen_error_sd_native_failure
    }
)

/**
 * Retains only a small tail of native output for failure classification. Paths are redacted before
 * the lines leave the process runner so diagnostics never need the complete command or model path.
 */
class SdNativeOutputBuffer(
    private val maxLines: Int = 100,
    private val maxChars: Int = 32 * 1024
) {
    private val lines = ArrayDeque<String>()
    private var totalChars = 0

    @Synchronized
    fun add(line: String) {
        val sanitized = sanitizeSdNativeOutput(line).take(maxChars)
        lines.addLast(sanitized)
        totalChars += sanitized.length
        while (lines.size > maxLines || totalChars > maxChars) {
            totalChars -= lines.removeFirst().length
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}

fun sanitizeSdNativeOutput(line: String): String = line
    .replace(ANDROID_STORAGE_PATH_REGEX, "<path>")
    .take(SANITIZED_NATIVE_LINE_LIMIT)

object SdNativeFailureClassifier {
    fun classify(
        exitCode: Int,
        recentOutput: List<String>,
        stage: SdProgressPhase? = null,
        acceleratorBinary: Boolean = false
    ): SdFailureReport {
        val sanitized = recentOutput.map(::sanitizeSdNativeOutput)
        val joined = sanitized.joinToString("\n").lowercase()
        val signal = exitCode.takeIf { it in 129..255 }?.minus(128)

        val category = when {
            exitCode == 134 || signal == 6 -> SdFailureCategory.NATIVE_ABORT
            hasMissingVaeSignature(joined) -> SdFailureCategory.MISSING_VAE
            hasOutOfMemorySignature(joined) -> SdFailureCategory.OUT_OF_MEMORY
            hasUnsupportedFlagSignature(joined) -> SdFailureCategory.UNSUPPORTED_BINARY_FLAG
            exitCode == 137 || signal == 9 || exitCode == 143 || signal == 15 ->
                SdFailureCategory.PROCESS_KILLED
            hasCorruptFileSignature(joined) -> SdFailureCategory.MODEL_FILE_CORRUPT
            hasLayoutSignature(joined) -> SdFailureCategory.MODEL_LAYOUT_MISMATCH
            hasTensorMismatchSignature(joined) -> SdFailureCategory.TENSOR_MISMATCH
            hasMetadataSignature(joined) -> SdFailureCategory.MODEL_METADATA_INVALID
            hasUnsupportedModelSignature(joined) -> SdFailureCategory.UNSUPPORTED_MODEL
            acceleratorBinary && hasAcceleratorSignature(joined) ->
                SdFailureCategory.ACCELERATOR_FAILURE
            else -> SdFailureCategory.UNKNOWN_NATIVE_FAILURE
        }

        return SdFailureReport(
            category = category,
            exitCode = exitCode,
            signal = signal,
            stage = stage,
            technicalSummary = technicalSummary(category, exitCode, signal),
            recentNativeOutput = sanitized
        )
    }

    private fun hasMissingVaeSignature(output: String): Boolean =
        ("vae tensor" in output && "not in model metadata" in output) ||
            ("first_stage_model" in output &&
                "model metadata validation failed" in output &&
                "new_sd_ctx_t failed" in output)

    private fun hasLayoutSignature(output: String): Boolean =
        ("model metadata validation failed" in output &&
            ("new_sd_ctx_t failed" in output || "not in model metadata" in output)) ||
            ("diffusion model tensor" in output && "not in model metadata" in output)

    private fun hasMetadataSignature(output: String): Boolean =
        "invalid model metadata" in output ||
            "metadata validation failed" in output ||
            "not in model metadata" in output

    private fun hasTensorMismatchSignature(output: String): Boolean =
        "unexpected tensor" in output ||
            "dimension mismatch" in output ||
            "tensor shape mismatch" in output ||
            "tensor type mismatch" in output ||
            "tensor mismatch" in output

    private fun hasCorruptFileSignature(output: String): Boolean =
        "invalid safetensors" in output ||
            "invalid gguf" in output ||
            "truncated" in output ||
            "failed to read model" in output ||
            "failed to parse model" in output ||
            "invalid model header" in output

    private fun hasUnsupportedModelSignature(output: String): Boolean =
        "unsupported model" in output ||
            "unknown model type" in output ||
            "model format is not supported" in output

    private fun hasUnsupportedFlagSignature(output: String): Boolean =
        "unknown argument" in output ||
            "unrecognized option" in output ||
            "unknown option" in output ||
            "invalid parameter:" in output

    private fun hasOutOfMemorySignature(output: String): Boolean =
        "out of memory" in output ||
            "std::bad_alloc" in output ||
            "failed to allocate" in output ||
            "cannot allocate memory" in output ||
            "ggml_backend_alloc" in output ||
            "mmap failed" in output

    private fun hasAcceleratorSignature(output: String): Boolean =
        "device lost" in output ||
            "vulkan error" in output ||
            "opencl error" in output ||
            "failed to create vulkan" in output ||
            "failed to create opencl" in output ||
            "backend device error" in output

    private fun technicalSummary(
        category: SdFailureCategory,
        exitCode: Int,
        signal: Int?
    ): String = buildString {
        append("category=")
        append(category.name)
        append(" exitCode=")
        append(exitCode)
        signal?.let {
            append(" signal=")
            append(it)
        }
    }
}

private val ANDROID_STORAGE_PATH_REGEX = Regex(
    """/(?:storage|data|mnt|sdcard)/[^\s'\"]+""",
    RegexOption.IGNORE_CASE
)
private const val SANITIZED_NATIVE_LINE_LIMIT = 2 * 1024
