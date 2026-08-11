package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import java.io.File

internal enum class SdLaunchIssue {
    MISSING_BINARY,
    MISSING_MODEL,
    UNREADABLE_MODEL,
    MISSING_INPUT_IMAGE,
    UNREADABLE_INPUT_IMAGE
}

internal const val SD_POSTPROCESS_MAX_PIXELS: Long = 16_777_216L
private const val UPSCALE_RESTORE_FIRST_SKIP_REASON = "restore_first_upscale_skip"

internal fun validateSdLaunchInputs(
    mode: SDMode,
    modelPath: String?,
    inputImagePath: String?,
    sdBinaryPath: String?
): SdLaunchIssue? {
    if (sdBinaryPath.isNullOrBlank() || !isReadableRegularFile(sdBinaryPath)) {
        return SdLaunchIssue.MISSING_BINARY
    }
    if (modelPath.isNullOrBlank()) {
        return SdLaunchIssue.MISSING_MODEL
    }
    if (!isReadableRegularFile(modelPath)) {
        return SdLaunchIssue.UNREADABLE_MODEL
    }
    if (mode == SDMode.IMG2IMG || mode == SDMode.UPSCALE ||
        (mode == SDMode.ADETAILER && !inputImagePath.isNullOrBlank())
    ) {
        if (inputImagePath.isNullOrBlank()) {
            return SdLaunchIssue.MISSING_INPUT_IMAGE
        }
        if (!isReadableRegularFile(inputImagePath)) {
            return SdLaunchIssue.UNREADABLE_INPUT_IMAGE
        }
    }
    return null
}

internal fun sdLaunchIssueMessage(
    context: Context,
    mode: SDMode,
    issue: SdLaunchIssue
): String = when (issue) {
    SdLaunchIssue.MISSING_BINARY -> context.getString(R.string.imagegen_error_sd_binary_missing)
    SdLaunchIssue.MISSING_MODEL -> context.getString(R.string.imagegen_error_missing_model)
    SdLaunchIssue.UNREADABLE_MODEL -> context.getString(R.string.imagegen_error_unreadable_model)
    SdLaunchIssue.MISSING_INPUT_IMAGE -> context.getString(
        if (mode == SDMode.UPSCALE) {
            R.string.imagegen_error_missing_upscale_input_image
        } else {
            R.string.imagegen_error_missing_init_image
        }
    )
    SdLaunchIssue.UNREADABLE_INPUT_IMAGE -> context.getString(R.string.imagegen_error_unreadable_input_image)
}

internal fun buildSdLaunchBreadcrumbDetails(config: SDConfig): String =
    buildList {
        add("mode=${config.mode.name}")
        add("model=${File(config.modelPath).name}")
        add("inputImage=${!config.initImage.isNullOrBlank()}")
        add("output=${File(config.outputPath).name}")
        if (config.mode == SDMode.UPSCALE) {
            add("repeats=${config.upscaleRepeats}")
            add("upscaleModel=${config.upscaleModel?.let { File(it).name } ?: "none"}")
        } else {
            add("size=${config.width}x${config.height}")
            add("steps=${config.steps}")
        }
    }.joinToString(" ")

internal fun buildSdLaunchBreadcrumbDetails(config: SDUpscaleConfig): String =
    buildList {
        add("mode=UPSCALE")
        add("model=${File(config.modelPath).name}")
        add("inputImage=${File(config.inputImagePath).name}")
        add("output=${File(config.outputPath).name}")
        add("repeats=${config.upscaleRepeats}")
        add("threads=${config.threads}")
    }.joinToString(" ")

internal fun resolveSdAutoCropSkipReason(
    mode: SDMode,
    outputWidth: Int,
    outputHeight: Int,
    maxPixels: Long = SD_POSTPROCESS_MAX_PIXELS
): String? {
    if (mode == SDMode.UPSCALE) {
        return UPSCALE_RESTORE_FIRST_SKIP_REASON
    }
    if (outputWidth <= 0 || outputHeight <= 0) {
        return "invalid_output_bounds"
    }
    val pixelCount = outputWidth.toLong() * outputHeight.toLong()
    if (pixelCount > maxPixels) {
        return "oversized_output_${outputWidth}x${outputHeight}"
    }
    return null
}

private fun isReadableRegularFile(path: String): Boolean {
    val file = File(path)
    return file.exists() && file.isFile && file.canRead()
}
