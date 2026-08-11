package com.example.llamadroid.service

import android.content.Context
import android.os.Parcelable
import com.example.llamadroid.R
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

@Parcelize
data class SdADetailerConfig(
    val modelPath: String,
    val prompt: String = "",
    val negativePrompt: String = "",
    val confidence: Float = 0.30f,
    val denoisingStrength: Float = 0.40f,
    val maskBlur: Int = 4,
    val padding: Int = 32,
    val maxDetections: Int = 8,
    val inpaintWidth: Int? = null,
    val inpaintHeight: Int? = null,
    val detailSteps: Int? = null,
    val detailCfgScale: Float? = null,
    val advancedArgs: String = ""
) : Parcelable

enum class SdADetailerConfigurationIssue {
    MISSING_DETECTOR,
    UNSUPPORTED_FILE_TYPE,
    INCOMPATIBLE_DETECTOR,
    INVALID_CONFIDENCE,
    INVALID_DENOISING,
    INVALID_GEOMETRY,
    INVALID_RESOLUTION,
    INVALID_STEPS,
    INVALID_CFG,
    INVALID_ADVANCED_ARGUMENTS
}

class SdADetailerConfigurationException(
    val issue: SdADetailerConfigurationIssue,
    message: String
) : IllegalArgumentException(message)

private val typedAdKeys = setOf(
    "confidence", "denoising_strength", "mask_blur", "inpaint_padding", "max_detections",
    "inpaint_width", "inpaint_height", "steps", "cfg_scale"
)

private val supportedAdvancedAdKeys = setOf(
    "input_size", "nms", "nms_threshold", "mask_k_largest", "mask_min_ratio",
    "mask_max_ratio", "dilate_erode", "x_offset", "y_offset", "mask_mode",
    "merge_masks", "invert_mask", "sample_method", "scheduler", "sort_by"
)

private val requiredYoloV8TensorNames = setOf(
    "model.0.conv.weight",
    "model.22.cv2.0.2.weight",
    "model.22.cv3.0.2.weight"
)

private const val MAX_SAFETENSORS_HEADER_BYTES = 16 * 1024 * 1024

fun validateSdADetailerConfig(config: SdADetailerConfig): SdADetailerConfig {
    val model = File(config.modelPath)
    if (!model.isFile || !model.canRead()) {
        throw SdADetailerConfigurationException(
            SdADetailerConfigurationIssue.MISSING_DETECTOR,
            "ADetailer detector is missing or unreadable"
        )
    }
    if (!model.name.endsWith(".safetensors", ignoreCase = true)) {
        throw SdADetailerConfigurationException(
            SdADetailerConfigurationIssue.UNSUPPORTED_FILE_TYPE,
            "ADetailer supports converted .safetensors detectors only"
        )
    }
    if (!isCompatibleSdADetailerDetector(model)) {
        throw SdADetailerConfigurationException(
            SdADetailerConfigurationIssue.INCOMPATIBLE_DETECTOR,
            "ADetailer detector is not a stable-diffusion.cpp YOLOv8 detection conversion"
        )
    }
    if (!config.confidence.isFinite() || config.confidence !in 0f..1f) {
        throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_CONFIDENCE, "ADetailer confidence must be between 0 and 1")
    }
    if (!config.denoisingStrength.isFinite() || config.denoisingStrength !in 0f..1f) {
        throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_DENOISING, "ADetailer denoising strength must be between 0 and 1")
    }
    if (config.maskBlur !in 0..128 || config.padding !in 0..2048 || config.maxDetections !in 1..128) {
        throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_GEOMETRY, "ADetailer mask, padding or detection count is invalid")
    }
    listOfNotNull(config.inpaintWidth, config.inpaintHeight).forEach {
        if (it !in 64..4096 || it % 8 != 0) {
            throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_RESOLUTION, "ADetailer inpaint resolution must be 64..4096 and divisible by 8")
        }
    }
    config.detailSteps?.let {
        if (it !in 1..200) throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_STEPS, "ADetailer steps must be 1..200")
    }
    config.detailCfgScale?.let {
        if (!it.isFinite() || it !in 0f..50f) throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_CFG, "ADetailer CFG is invalid")
    }
    parseSdADetailerAdvancedArgs(config.advancedArgs).keys.forEach { key ->
        if (key in typedAdKeys) {
            throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_ADVANCED_ARGUMENTS, "Advanced ADetailer argument '$key' duplicates a typed setting")
        }
        if (key !in supportedAdvancedAdKeys) {
            throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_ADVANCED_ARGUMENTS, "Unknown ADetailer argument '$key'")
        }
    }
    return config
}

internal fun parseSdADetailerAdvancedArgs(value: String): Map<String, String> {
    if (value.isBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    value.lineSequence()
        .flatMap { it.split(',').asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) {
                throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_ADVANCED_ARGUMENTS, "Advanced ADetailer arguments use key=value")
            }
            val key = token.substring(0, separator).trim().lowercase(Locale.US)
            val item = token.substring(separator + 1).trim()
            if (!key.matches(Regex("[a-z][a-z0-9_]*")) || item.any { it.code < 0x20 }) {
                throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_ADVANCED_ARGUMENTS, "Unsafe ADetailer advanced argument")
            }
            if (result.put(key, item) != null) {
                throw SdADetailerConfigurationException(SdADetailerConfigurationIssue.INVALID_ADVANCED_ARGUMENTS, "Duplicate ADetailer advanced argument '$key'")
            }
        }
    return result
}

fun serializeSdADetailerExtraArgs(config: SdADetailerConfig): String {
    validateSdADetailerConfig(config)
    val values = sortedMapOf<String, String>()
    values["confidence"] = formatSdAdFloat(config.confidence)
    values["denoising_strength"] = formatSdAdFloat(config.denoisingStrength)
    values["mask_blur"] = config.maskBlur.toString()
    values["inpaint_padding"] = config.padding.toString()
    values["max_detections"] = config.maxDetections.toString()
    config.inpaintWidth?.let { values["inpaint_width"] = it.toString() }
    config.inpaintHeight?.let { values["inpaint_height"] = it.toString() }
    config.detailSteps?.let { values["steps"] = it.toString() }
    config.detailCfgScale?.let { values["cfg_scale"] = formatSdAdFloat(it) }
    values.putAll(parseSdADetailerAdvancedArgs(config.advancedArgs))
    return values.entries.joinToString(separator = ",") { (key, value) -> "$key=$value" }
}

/** Fast preflight for the converter-specific YOLOv8 SafeTensors contract used by sd.cpp. */
internal fun isCompatibleSdADetailerDetector(file: File): Boolean = runCatching {
    if (!file.isFile || !file.canRead() || file.length() < 10L) return@runCatching false
    RandomAccessFile(file, "r").use { input ->
        val lengthBytes = ByteArray(Long.SIZE_BYTES)
        input.readFully(lengthBytes)
        val headerLength = ByteBuffer.wrap(lengthBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
        if (headerLength !in 2L..MAX_SAFETENSORS_HEADER_BYTES.toLong() ||
            Long.SIZE_BYTES + headerLength > file.length()
        ) return@use false
        val headerBytes = ByteArray(headerLength.toInt())
        input.readFully(headerBytes)
        val header = String(headerBytes, StandardCharsets.UTF_8)
        val isDetectionConversion = Regex(
            "\\\"yolov8\\.variant\\\"\\s*:\\s*\\\"detect\\\"",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(header)
        isDetectionConversion && requiredYoloV8TensorNames.all { tensor ->
            header.contains("\"$tensor\"")
        }
    }
}.getOrDefault(false)

fun sdADetailerErrorMessage(
    context: Context,
    error: SdADetailerConfigurationException
): String = when (error.issue) {
    SdADetailerConfigurationIssue.MISSING_DETECTOR -> context.getString(R.string.imagegen_adetailer_error_missing_detector)
    SdADetailerConfigurationIssue.UNSUPPORTED_FILE_TYPE,
    SdADetailerConfigurationIssue.INCOMPATIBLE_DETECTOR -> context.getString(R.string.imagegen_adetailer_error_incompatible_detector)
    else -> context.getString(R.string.imagegen_adetailer_error_invalid_settings, error.message.orEmpty())
}

private fun formatSdAdFloat(value: Float): String =
    String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
