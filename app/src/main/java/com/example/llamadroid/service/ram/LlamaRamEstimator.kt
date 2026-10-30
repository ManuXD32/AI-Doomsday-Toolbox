package com.example.llamadroid.service.ram

import kotlin.math.max
import kotlin.math.min

/**
 * Attention shape of a GGUF model, as far as the KV cache size depends on it.
 *
 * @property kvHeads average number of KV heads per layer (hybrid models have layers without KV).
 * @property slidingWindow SWA window in tokens, 0 when the model has no sliding-window layers.
 * @property swaLayerFraction share of the layers that use the sliding window (0..1).
 */
data class LlamaModelShape(
    val layers: Int,
    val kvHeads: Double,
    val headDimK: Int,
    val headDimV: Int,
    val slidingWindow: Int = 0,
    val swaLayerFraction: Double = 0.0
)

/** Everything the RAM estimate depends on. [totalContext] is the server's `-c` (all slots together). */
data class LlamaRamInputs(
    val modelFileBytes: Long,
    val mmprojBytes: Long = 0L,
    val totalContext: Int,
    val slots: Int = 1,
    val physicalBatch: Int = 512,
    val kvTypeK: String = "f16",
    val kvTypeV: String = "f16",
    /** True with `--mlock` / `--no-mmap` / direct-io: the whole file is resident. */
    val fullyResident: Boolean = false,
    val swaFull: Boolean = false,
    val shape: LlamaModelShape? = null
)

data class LlamaRamEstimate(
    val weightsBytes: Long,
    val kvBytes: Long,
    val mmprojBytes: Long,
    val computeBytes: Long,
    /** Always 0: mmap weights are counted in full (kept so existing readers still compile). */
    val pageableWeightBytes: Long,
    val usedShape: Boolean
) {
    val totalBytes: Long get() = weightsBytes + kvBytes + mmprojBytes + computeBytes
}

data class LlamaRamDecision(
    val ok: Boolean,
    val requiredBytes: Long,
    val availBytes: Long,
    val marginBytes: Long,
    val lowMemory: Boolean
)

/**
 * Pure RAM model for one llama-server process.
 *
 * - **Weights:** the whole file counts, mmap or not. CPU decode touches every weight for each
 *   token, so mapped pages are in the working set and the kernel cannot drop them without thrashing.
 * - **KV cache:** `context x layers x kvHeads x (headDimK x bytesK + headDimV x bytesV)`. The server's
 *   `-c` is the total over all slots (unified or not), so the slot count only matters for SWA.
 *   Sliding-window layers hold `window x slots + ubatch` tokens unless `--swa-full`.
 *   Without GGUF shape data a conservative bytes-per-token heuristic from the file size is used.
 * - **Extras:** mmproj file (always counted) and a fixed compute-buffer allowance.
 */
object LlamaRamEstimator {
    const val COMPUTE_BUFFER_BYTES = 256L * 1024L * 1024L
    const val SAFETY_MARGIN_BYTES = 768L * 1024L * 1024L

    /** F16 KV bytes per token per GB of model file when the GGUF shape is unknown (conservative). */
    private const val HEURISTIC_F16_BYTES_PER_TOKEN_PER_GB = 40L * 1024L
    private const val HEURISTIC_MIN_F16_BYTES_PER_TOKEN = 32L * 1024L
    private const val GB = 1024.0 * 1024.0 * 1024.0

    fun kvBytesPerElement(type: String): Double = when (type.trim().lowercase()) {
        "f32" -> 4.0
        "f16", "bf16" -> 2.0
        "q8_0" -> 34.0 / 32.0
        "q5_1" -> 24.0 / 32.0
        "q5_0" -> 22.0 / 32.0
        "q4_1" -> 20.0 / 32.0
        "q4_0", "iq4_nl" -> 18.0 / 32.0
        else -> 2.0
    }

    fun estimate(inputs: LlamaRamInputs): LlamaRamEstimate {
        val file = inputs.modelFileBytes.coerceAtLeast(0L)
        val weights = file
        val context = inputs.totalContext.coerceAtLeast(0)
        val bytesK = kvBytesPerElement(inputs.kvTypeK)
        val bytesV = kvBytesPerElement(inputs.kvTypeV)
        val shape = inputs.shape?.takeIf { it.layers > 0 && it.kvHeads > 0.0 && it.headDimK > 0 && it.headDimV > 0 }
        val kv = if (shape != null) {
            val perTokenPerLayer = shape.kvHeads * (shape.headDimK * bytesK + shape.headDimV * bytesV)
            val swaFraction = if (!inputs.swaFull && shape.slidingWindow > 0) {
                shape.swaLayerFraction.coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val swaTokens = min(
                context.toLong(),
                shape.slidingWindow.toLong() * inputs.slots.coerceAtLeast(1) + inputs.physicalBatch.coerceAtLeast(1)
            ).toDouble()
            val effectiveTokens = (1.0 - swaFraction) * context + swaFraction * swaTokens
            (perTokenPerLayer * shape.layers * effectiveTokens).toLong()
        } else {
            val f16PerToken = max(
                HEURISTIC_MIN_F16_BYTES_PER_TOKEN.toDouble(),
                file / GB * HEURISTIC_F16_BYTES_PER_TOKEN_PER_GB
            )
            (f16PerToken * ((bytesK + bytesV) / 4.0) * context).toLong()
        }
        return LlamaRamEstimate(
            weightsBytes = weights,
            kvBytes = kv.coerceAtLeast(0L),
            mmprojBytes = inputs.mmprojBytes.coerceAtLeast(0L),
            computeBytes = COMPUTE_BUFFER_BYTES,
            pageableWeightBytes = 0L,
            usedShape = shape != null
        )
    }

    /**
     * Start if the estimate fits `availMem - margin`. [reclaimableCacheBytes] is accepted for
     * source compatibility but ignored: no page-cache credit is given (see class KDoc).
     */
    fun decide(
        estimate: LlamaRamEstimate,
        availBytes: Long,
        lowMemory: Boolean,
        @Suppress("UNUSED_PARAMETER") reclaimableCacheBytes: Long = 0L,
        marginBytes: Long = SAFETY_MARGIN_BYTES
    ): LlamaRamDecision {
        val required = estimate.totalBytes
        val ok = !lowMemory && estimate.totalBytes > 0L && required + marginBytes <= availBytes
        return LlamaRamDecision(ok, required + marginBytes, availBytes, marginBytes, lowMemory)
    }
}
