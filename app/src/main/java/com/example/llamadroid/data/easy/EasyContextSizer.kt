package com.example.llamadroid.data.easy

import android.app.ActivityManager
import android.content.Context
import com.example.llamadroid.service.ram.GgufModelShapeReader
import com.example.llamadroid.service.ram.LlamaRamEstimator
import com.example.llamadroid.service.ram.LlamaRamInputs
import com.example.llamadroid.util.GGUFParser
import java.io.File

/**
 * Result of [chooseEasyContext].
 *
 * @property contextSize context to launch with.
 * @property recommendedMax the recommendation for this phone (same as [contextSize]); anything above is flagged.
 * @property fitsBudget false when even the floor does not fit the RAM budget (still used, never blocked).
 */
data class EasyContextChoice(val contextSize: Int, val recommendedMax: Int, val fitsBudget: Boolean)

/** Verdict for a user-chosen context size. It drives a warning only, never a block. */
data class EasyContextOverrideEvaluation(
    val exceedsRecommendation: Boolean,
    val exceedsTrainedContext: Boolean,
    val fitsBudget: Boolean
)

internal const val EASY_CONTEXT_FLOOR = 8192
internal val EASY_CONTEXT_CANDIDATES = listOf(32768, 24576, 16384, 12288, 8192)

/**
 * Picks the largest candidate (32k..8k, capped at [trainedCtx]) whose [estimateBytes] fits [budgetBytes].
 * The floor is 8192, or [trainedCtx] when smaller. If nothing fits, the floor is used with `fitsBudget = false`.
 */
fun chooseEasyContext(
    trainedCtx: Int?,
    budgetBytes: Long,
    estimateBytes: (Int) -> Long
): EasyContextChoice {
    val cap = trainedCtx?.takeIf { it > 0 }
    val floor = if (cap != null) minOf(EASY_CONTEXT_FLOOR, cap) else EASY_CONTEXT_FLOOR
    val candidates = EASY_CONTEXT_CANDIDATES.filter { cap == null || it <= cap }
    val fitting = candidates.firstOrNull { it >= floor && estimateBytes(it) <= budgetBytes }
    return if (fitting != null) {
        EasyContextChoice(fitting, fitting, true)
    } else {
        EasyContextChoice(floor, floor, estimateBytes(floor) <= budgetBytes)
    }
}

/** Flags a user-chosen [ctx] that exceeds what [chooseEasyContext] recommends for this phone. */
fun evaluateOverride(
    ctx: Int,
    trainedCtx: Int?,
    budgetBytes: Long,
    estimateBytes: (Int) -> Long
): EasyContextOverrideEvaluation {
    val recommended = chooseEasyContext(trainedCtx, budgetBytes, estimateBytes)
    val cap = trainedCtx?.takeIf { it > 0 }
    return EasyContextOverrideEvaluation(
        exceedsRecommendation = ctx > recommended.recommendedMax,
        exceedsTrainedContext = cap != null && ctx > cap,
        fitsBudget = estimateBytes(ctx) <= budgetBytes
    )
}

/** Android wrapper: reads the GGUF and the live RAM state and feeds the pure core. */
class EasyContextSizer(context: Context) {
    private val app = context.applicationContext

    /**
     * RAM budget: `totalMem * 0.55`. It deliberately ignores the live free-memory reading so the
     * automatic context stays the same between launches; a changing context would invalidate the
     * chat's saved KV cache. The launch-time `LlamaRamGate` check still guards the live state.
     */
    fun budgetBytes(): Long {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val total = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }.totalMem
        return (total * BUDGET_TOTAL_FRACTION).toLong()
    }

    /** Blocking (reads the GGUF header); call from an IO dispatcher. */
    fun recommend(modelPath: String, mmprojBytes: Long = 0L): EasyContextChoice {
        val (trained, estimator) = prepare(modelPath, mmprojBytes)
        return chooseEasyContext(trained, budgetBytes(), estimator)
    }

    /** Blocking (reads the GGUF header); call from an IO dispatcher. */
    fun evaluate(modelPath: String, ctx: Int, mmprojBytes: Long = 0L): EasyContextOverrideEvaluation {
        val (trained, estimator) = prepare(modelPath, mmprojBytes)
        return evaluateOverride(ctx, trained, budgetBytes(), estimator)
    }

    private fun prepare(modelPath: String, mmprojBytes: Long): Pair<Int?, (Int) -> Long> {
        val file = File(modelPath)
        val shape = GgufModelShapeReader.read(file)
        val info = GGUFParser.readModelInfo(modelPath)
        val trained = info?.takeIf { it.contextLengthDetected }?.contextLength
        val size = file.length()
        val estimator: (Int) -> Long = { ctx ->
            val inputs = LlamaRamInputs(
                modelFileBytes = size,
                mmprojBytes = mmprojBytes,
                totalContext = ctx,
                shape = shape
            )
            LlamaRamEstimator.estimate(inputs).totalBytes + LlamaRamEstimator.SAFETY_MARGIN_BYTES
        }
        return trained to estimator
    }

    private companion object {
        const val BUDGET_TOTAL_FRACTION = 0.55
    }
}
