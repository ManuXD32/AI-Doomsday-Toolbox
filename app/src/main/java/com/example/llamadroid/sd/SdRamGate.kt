package com.example.llamadroid.sd

import android.content.Context
import com.example.llamadroid.service.ram.LlamaRamGate
import com.example.llamadroid.service.ram.RamSnapshot

/** Coarse memory class of an image pipeline; selects the working-set row of [SdRamGate]. */
enum class SdRamClass { SD15, SDXL, FLUX }

/** Outcome of [SdRamGate.decide]. */
sealed interface SdRamDecision {
    /** The run fits at the requested canvas. */
    data object Fits : SdRamDecision

    /** The run only fits at [width]x[height] (with VAE tiling when [vaeTiling]). */
    data class Downscale(val width: Int, val height: Int, val vaeTiling: Boolean) : SdRamDecision

    /** Even the smallest canvas does not fit: [neededBytes] required, [availableBytes] free. */
    data class Refuse(val neededBytes: Long, val availableBytes: Long) : SdRamDecision
}

/**
 * Pre-launch memory check for Easy-mode stable-diffusion.cpp runs.
 *
 * Estimate = model weight bytes (diffusion + text encoders + VAE, counted at 100% even with mmap,
 * the same policy as the LLM gate) + a working set that depends on family and canvas. Available =
 * free memory plus reclaimable page cache.
 */
object SdRamGate {
    private const val GB = 1_000_000_000L
    private const val FALLBACK_SIDE = 512

    /**
     * Conservative activation/working-set estimates in bytes, per [SdRamClass]. Index 0 = longest
     * side <= 512, 1 = <= 768, 2 = larger. Rough measurements, rounded up on purpose: refusing a
     * run that would have fit is cheaper than an out-of-memory kill.
     */
    private val workingSet: Map<SdRamClass, LongArray> = mapOf(
        SdRamClass.SD15 to longArrayOf(600_000_000L, 1_100_000_000L, 2_000_000_000L),
        SdRamClass.SDXL to longArrayOf(1_000_000_000L, 1_800_000_000L, 1_800_000_000L),
        SdRamClass.FLUX to longArrayOf(1_500_000_000L, 2_200_000_000L, 3 * GB)
    )

    fun workingSetBytes(ramClass: SdRamClass, width: Int, height: Int): Long {
        val side = maxOf(width, height)
        val tier = when {
            side <= 512 -> 0
            side <= 768 -> 1
            else -> 2
        }
        return workingSet.getValue(ramClass)[tier]
    }

    fun estimateBytes(weightBytes: Long, ramClass: SdRamClass, width: Int, height: Int): Long =
        weightBytes.coerceAtLeast(0L) + workingSetBytes(ramClass, width, height)

    /** Pure decision: try the current size, then 512x512 with VAE tiling, else refuse. */
    fun decide(
        weightBytes: Long,
        ramClass: SdRamClass,
        width: Int,
        height: Int,
        snapshot: RamSnapshot
    ): SdRamDecision {
        val available = snapshot.availBytes + snapshot.reclaimableCacheBytes
        if (estimateBytes(weightBytes, ramClass, width, height) <= available) return SdRamDecision.Fits
        val canShrink = maxOf(width, height) > FALLBACK_SIDE
        val fallbackNeeded = estimateBytes(weightBytes, ramClass, FALLBACK_SIDE, FALLBACK_SIDE)
        if (canShrink && fallbackNeeded <= available) {
            return SdRamDecision.Downscale(FALLBACK_SIDE, FALLBACK_SIDE, vaeTiling = true)
        }
        return SdRamDecision.Refuse(fallbackNeeded.takeIf { canShrink } ?: estimateBytes(weightBytes, ramClass, width, height), available)
    }

    /** Thin Android reader: live free-memory reading shared with the LLM gate. */
    fun snapshot(context: Context): RamSnapshot = LlamaRamGate(context).snapshot()
}
