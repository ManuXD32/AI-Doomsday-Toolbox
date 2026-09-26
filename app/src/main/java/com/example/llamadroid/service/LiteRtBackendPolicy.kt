package com.example.llamadroid.service

import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.model.normalizeLiteRtBackend

internal const val LITERT_GPU_SAFE_CONTEXT_TOKENS = 4_096
internal const val LITERT_GPU_SAFE_MAX_OUTPUT_TOKENS = 1_024

internal data class LiteRtBackendResolution(
    val requestedBackend: String,
    val effectiveBackend: String,
    val contextTokens: Int,
    val outputTokens: Int,
    val gpuWouldReduceRequest: Boolean,
    val autoChoseCpuForCapacity: Boolean,
)

internal fun resolveLiteRtRequestedOutputTokens(
    requestedOutputTokens: Int?,
    perModelMaximum: Long?,
    globalMaximum: Int,
): Int? {
    val configuredMaximum = perModelMaximum
        ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: globalMaximum.takeIf { it > 0 }
    val request = requestedOutputTokens?.takeIf { it > 0 }
    return when {
        configuredMaximum != null && request != null -> minOf(request, configuredMaximum)
        configuredMaximum != null -> configuredMaximum
        else -> request
    }
}

/**
 * Resolve the backend and limits shown to users from the same policy used by
 * Harness and direct LiteRT generation. Auto avoids GPU when its fixed safety
 * ceilings would silently shrink either requested limit.
 */
internal fun resolveLiteRtBackend(
    model: LiteRtModelEntity,
    requestedBackend: String?,
    requestedContextTokens: Int,
    requestedOutputTokens: Int,
): LiteRtBackendResolution {
    val normalized = normalizeLiteRtBackend(requestedBackend)
    val context = requestedContextTokens.coerceAtLeast(1)
    val output = requestedOutputTokens.coerceAtLeast(1)
    val modelContextLimit = model.defaultLiteRtEngineMaxTokens()
        ?.takeIf { it > 0 }
        ?.let { minOf(context, it) }
        ?: context
    val modelOutputLimit = minOf(output, (modelContextLimit / 2).coerceAtLeast(1))
    val gpuAvailable = model.supportsGpu && model.isLikelyLiteRtGpuPackage()
    val gpuWouldReduceRequest = modelContextLimit > LITERT_GPU_SAFE_CONTEXT_TOKENS ||
        modelOutputLimit > LITERT_GPU_SAFE_MAX_OUTPUT_TOKENS
    val autoChoseCpuForCapacity = normalized == LITERT_BACKEND_AUTO && gpuAvailable &&
        gpuWouldReduceRequest && model.supportsCpu
    val effectiveBackend = when (normalized) {
        LITERT_BACKEND_CPU -> LITERT_BACKEND_CPU
        LITERT_BACKEND_GPU -> LITERT_BACKEND_GPU
        else -> when {
            autoChoseCpuForCapacity -> LITERT_BACKEND_CPU
            gpuAvailable -> LITERT_BACKEND_GPU
            model.supportsCpu -> LITERT_BACKEND_CPU
            else -> LITERT_BACKEND_GPU
        }
    }
    val contextLimit = if (effectiveBackend == LITERT_BACKEND_GPU) {
        minOf(modelContextLimit, LITERT_GPU_SAFE_CONTEXT_TOKENS)
    } else {
        modelContextLimit
    }
    val outputLimit = if (effectiveBackend == LITERT_BACKEND_GPU) {
        minOf(modelOutputLimit, LITERT_GPU_SAFE_MAX_OUTPUT_TOKENS, (contextLimit / 2).coerceAtLeast(1))
    } else {
        modelOutputLimit
    }
    return LiteRtBackendResolution(
        requestedBackend = normalized,
        effectiveBackend = effectiveBackend,
        contextTokens = contextLimit,
        outputTokens = outputLimit,
        gpuWouldReduceRequest = gpuWouldReduceRequest,
        autoChoseCpuForCapacity = autoChoseCpuForCapacity,
    )
}
