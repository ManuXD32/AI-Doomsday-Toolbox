package com.example.llamadroid.service

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SdProgressSnapshot(
    val currentStep: Int,
    val totalSteps: Int,
    /** Monotonic whole-operation progress. Native diffusion never consumes the final 100%. */
    val progress: Float,
    val iterationSeconds: Double? = null,
    val etaSeconds: Double? = null,
    val statusText: String = "",
    val phase: SdProgressPhase = SdProgressPhase.DIFFUSION,
    val isIndeterminate: Boolean = false,
    val detailPassIndex: Int = 0,
    val detailPassCount: Int = 1
)

enum class SdProgressPhase {
    INSPECTING_MODEL,
    PREPARING,
    LOADING_MODEL,
    LOADING_VAE,
    LOADING_TEXT_ENCODERS,
    LOADING_LORAS,
    VAE_ENCODING,
    CONDITIONING,
    DIFFUSION,
    VAE_DECODING,
    COMPOSITING,
    SAVING;

    val diagnosticName: String
        get() = name.lowercase()

    val isVae: Boolean
        get() = this == VAE_ENCODING || this == VAE_DECODING
}

/**
 * Interprets the native stable-diffusion.cpp stream as a staged operation.
 *
 * `totalStepsHint` is intentionally only a hint. img2img and dedicated
 * ADetailer reduce the native schedule according to denoising strength and
 * report their authoritative totals in the sampling progress stream.
 */
class SdProgressTracker(
    private val totalStepsHint: Int,
    private val startedAtMs: Long,
    private val smoothingWindow: Int = 5
) {
    private var nativeCurrentStep = 0
    private var nativeTotalSteps = totalStepsHint.coerceAtLeast(1)
    private var lastObservedStep = 0
    private var lastObservedTimestampMs: Long? = null
    private val recentIterationSeconds = ArrayDeque<Double>()
    private var lastSnapshot: SdProgressSnapshot? = null
    private var estimatedCompletionAtMs: Long? = null
    private var activePhase = SdProgressPhase.PREPARING
    private var detectedDetailPasses = 1
    private var currentDetailPassIndex = 0
    private var samplingPassCompleted = false
    private var lastOverallProgress = 0f

    @Synchronized
    fun update(line: String, nowMs: Long): SdProgressSnapshot? {
        val normalized = line.lowercase()

        DETECTED_OBJECTS_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { count ->
            detectedDetailPasses = count.coerceAtLeast(1)
            currentDetailPassIndex = currentDetailPassIndex.coerceAtMost(detectedDetailPasses - 1)
        }

        if (SAMPLING_COMPLETED_REGEX.containsMatchIn(line)) {
            samplingPassCompleted = true
            estimatedCompletionAtMs = null
        }

        if (("img2img" in normalized || "target t_enc" in normalized) && samplingPassCompleted) {
            advanceDetailPass()
        }

        parseSamplingProgress(line)?.let { (currentStep, totalSteps) ->
            if (samplingPassCompleted && currentStep <= lastObservedStep) {
                advanceDetailPass()
            }
            samplingPassCompleted = false
            return buildDiffusionSnapshot(currentStep, totalSteps, line, nowMs)
        }

        val phase = recognizePhase(line) ?: return null
        return buildStageSnapshot(phase)
    }

    @Synchronized
    fun tick(nowMs: Long): SdProgressSnapshot? {
        val snapshot = lastSnapshot ?: return null
        if (snapshot.phase != SdProgressPhase.DIFFUSION) return null
        val completionAtMs = estimatedCompletionAtMs ?: return null
        val remainingMs = completionAtMs - nowMs
        return if (remainingMs > 0L) {
            snapshot.copy(etaSeconds = remainingMs.toDouble() / 1000.0)
        } else {
            snapshot.copy(etaSeconds = null, isIndeterminate = true)
        }
    }

    @Synchronized
    fun currentSnapshot(): SdProgressSnapshot? = lastSnapshot

    private fun buildDiffusionSnapshot(
        currentStep: Int,
        totalSteps: Int,
        line: String,
        nowMs: Long
    ): SdProgressSnapshot {
        activePhase = SdProgressPhase.DIFFUSION
        nativeTotalSteps = totalSteps.coerceAtLeast(1)
        nativeCurrentStep = currentStep.coerceIn(0, nativeTotalSteps)

        val sampleSeconds = extractIterationSeconds(line)
            ?: fallbackIterationSeconds(nativeCurrentStep, nowMs)
        sampleSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let(::recordIterationSample)

        if (nativeCurrentStep > lastObservedStep) {
            lastObservedStep = nativeCurrentStep
            lastObservedTimestampMs = nowMs
        }

        val smoothedIteration = recentIterationSeconds.takeIf { it.isNotEmpty() }?.average()
        val remainingCurrentPass = max(nativeTotalSteps - nativeCurrentStep, 0)
        val remainingPasses = max(detectedDetailPasses - currentDetailPassIndex - 1, 0)
        val remainingSteps = remainingCurrentPass + remainingPasses * nativeTotalSteps
        val etaSeconds = if (smoothedIteration != null && remainingSteps > 0) {
            remainingSteps * smoothedIteration
        } else {
            null
        }
        estimatedCompletionAtMs = etaSeconds?.let { nowMs + (it * 1000.0).roundToLong() }

        val nativeFraction = nativeCurrentStep.toFloat() / nativeTotalSteps.toFloat()
        return publishSnapshot(
            phase = SdProgressPhase.DIFFUSION,
            progress = aggregatePassProgress(DIFFUSION_START + nativeFraction * DIFFUSION_SPAN),
            iterationSeconds = smoothedIteration,
            etaSeconds = etaSeconds,
            isIndeterminate = etaSeconds == null
        )
    }

    private fun buildStageSnapshot(phase: SdProgressPhase): SdProgressSnapshot {
        activePhase = phase
        estimatedCompletionAtMs = null
        val progress = when (phase) {
            SdProgressPhase.INSPECTING_MODEL -> aggregatePassProgress(INSPECTING_PROGRESS)
            SdProgressPhase.PREPARING -> aggregatePassProgress(PREPARING_PROGRESS)
            SdProgressPhase.LOADING_MODEL -> aggregatePassProgress(LOADING_MODEL_PROGRESS)
            SdProgressPhase.LOADING_VAE -> aggregatePassProgress(LOADING_VAE_PROGRESS)
            SdProgressPhase.LOADING_TEXT_ENCODERS -> aggregatePassProgress(LOADING_TEXT_ENCODERS_PROGRESS)
            SdProgressPhase.LOADING_LORAS -> aggregatePassProgress(LOADING_LORAS_PROGRESS)
            SdProgressPhase.VAE_ENCODING -> aggregatePassProgress(VAE_ENCODING_PROGRESS)
            SdProgressPhase.CONDITIONING -> aggregatePassProgress(CONDITIONING_PROGRESS)
            SdProgressPhase.DIFFUSION -> aggregatePassProgress(DIFFUSION_START)
            SdProgressPhase.VAE_DECODING -> aggregatePassProgress(VAE_DECODING_PROGRESS)
            SdProgressPhase.COMPOSITING -> COMPOSITING_PROGRESS
            SdProgressPhase.SAVING -> SAVING_PROGRESS
        }
        return publishSnapshot(
            phase = phase,
            progress = progress,
            iterationSeconds = if (phase == SdProgressPhase.DIFFUSION) {
                recentIterationSeconds.takeIf { it.isNotEmpty() }?.average()
            } else {
                null
            },
            etaSeconds = null,
            isIndeterminate = true
        )
    }

    private fun publishSnapshot(
        phase: SdProgressPhase,
        progress: Float,
        iterationSeconds: Double?,
        etaSeconds: Double?,
        isIndeterminate: Boolean
    ): SdProgressSnapshot {
        lastOverallProgress = max(lastOverallProgress, progress.coerceIn(0f, SAVING_PROGRESS))
        return SdProgressSnapshot(
            currentStep = nativeCurrentStep,
            totalSteps = nativeTotalSteps,
            progress = lastOverallProgress,
            iterationSeconds = iterationSeconds,
            etaSeconds = etaSeconds,
            phase = phase,
            isIndeterminate = isIndeterminate,
            detailPassIndex = currentDetailPassIndex,
            detailPassCount = detectedDetailPasses
        ).also { lastSnapshot = it }
    }

    private fun aggregatePassProgress(withinPass: Float): Float {
        val passCount = detectedDetailPasses.coerceAtLeast(1)
        val passIndex = currentDetailPassIndex.coerceIn(0, passCount - 1)
        return (passIndex + withinPass.coerceIn(0f, 1f)) / passCount.toFloat()
    }

    private fun advanceDetailPass() {
        if (currentDetailPassIndex < detectedDetailPasses - 1) {
            currentDetailPassIndex += 1
        }
        nativeCurrentStep = 0
        nativeTotalSteps = totalStepsHint.coerceAtLeast(1)
        lastObservedStep = 0
        lastObservedTimestampMs = null
        samplingPassCompleted = false
        estimatedCompletionAtMs = null
    }

    private fun recognizePhase(line: String): SdProgressPhase? {
        val normalized = line.lowercase()
        return when {
            SAVE_RESULT_REGEX.containsMatchIn(line) || IMAGES_SAVED_REGEX.containsMatchIn(line) ->
                SdProgressPhase.SAVING
            ADETAILER_APPLIED_REGEX.containsMatchIn(line) -> SdProgressPhase.COMPOSITING
            "loading vae from" in normalized ||
                "loading tae from" in normalized ||
                "loading taesd from" in normalized -> SdProgressPhase.LOADING_VAE
            "loading clip_l from" in normalized ||
                "loading clip_g from" in normalized ||
                "loading t5xxl from" in normalized ||
                "loading llm from" in normalized ||
                "loading text encoder" in normalized -> SdProgressPhase.LOADING_TEXT_ENCODERS
            "loading lora" in normalized || "applying lora" in normalized ->
                SdProgressPhase.LOADING_LORAS
            "loading model from" in normalized ||
                "loading diffusion model from" in normalized -> SdProgressPhase.LOADING_MODEL
            DECODING_LATENTS_REGEX.containsMatchIn(line) ||
                "vae decode graph" in normalized ||
                "decode_first_stage" in normalized ||
                "latent 1 decoded" in normalized -> SdProgressPhase.VAE_DECODING
            "img2img" in normalized ||
                "target t_enc" in normalized ||
                "vae encode graph" in normalized ||
                "encode_first_stage" in normalized -> SdProgressPhase.VAE_ENCODING
            "condition graph" in normalized || "get_learned_condition" in normalized ->
                SdProgressPhase.CONDITIONING
            DETECTED_OBJECTS_REGEX.containsMatchIn(line) -> SdProgressPhase.PREPARING
            else -> null
        }
    }

    private fun fallbackIterationSeconds(currentStep: Int, nowMs: Long): Double? {
        if (currentStep <= 0) return null
        val previousTimestamp = lastObservedTimestampMs
        val previousStep = lastObservedStep
        return when {
            currentStep > previousStep && previousTimestamp != null -> {
                ((nowMs - previousTimestamp).coerceAtLeast(1L).toDouble() / 1000.0) /
                    (currentStep - previousStep).coerceAtLeast(1)
            }
            else -> ((nowMs - startedAtMs).coerceAtLeast(1L).toDouble() / 1000.0) / currentStep
        }
    }

    private fun recordIterationSample(sampleSeconds: Double) {
        recentIterationSeconds.addLast(sampleSeconds)
        while (recentIterationSeconds.size > smoothingWindow) {
            recentIterationSeconds.removeFirst()
        }
    }

    companion object {
        private const val INSPECTING_PROGRESS = 0.01f
        private const val PREPARING_PROGRESS = 0.02f
        private const val LOADING_MODEL_PROGRESS = 0.03f
        private const val LOADING_VAE_PROGRESS = 0.05f
        private const val LOADING_TEXT_ENCODERS_PROGRESS = 0.07f
        private const val LOADING_LORAS_PROGRESS = 0.08f
        private const val VAE_ENCODING_PROGRESS = 0.09f
        private const val CONDITIONING_PROGRESS = 0.10f
        private const val DIFFUSION_START = 0.12f
        private const val DIFFUSION_SPAN = 0.70f
        private const val VAE_DECODING_PROGRESS = 0.90f
        private const val COMPOSITING_PROGRESS = 0.96f
        private const val SAVING_PROGRESS = 0.98f

        private val PIPE_PROGRESS_REGEX = Regex("""\|\s*(\d+)/(\d+)\s*-""")
        private val STEP_PROGRESS_REGEX = Regex("""step\s+(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private val RATE_S_PER_IT_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(?:s|sec)/it""", RegexOption.IGNORE_CASE)
        private val RATE_IT_PER_S_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*it/s""", RegexOption.IGNORE_CASE)
        private val DATA_RATE_REGEX = Regex("""(?:[kmgt]i?b|bytes?)/s\b""", RegexOption.IGNORE_CASE)
        private val DETECTED_OBJECTS_REGEX = Regex("""ADetailer detected\s+(\d+)\s+object""", RegexOption.IGNORE_CASE)
        private val ADETAILER_APPLIED_REGEX = Regex("""ADetailer applied\s+\d+\s+mask""", RegexOption.IGNORE_CASE)
        private val DECODING_LATENTS_REGEX = Regex("""\bdecoding\s+\d+\s+latents?\b""", RegexOption.IGNORE_CASE)
        private val SAMPLING_COMPLETED_REGEX = Regex("""\bsampling completed\b""", RegexOption.IGNORE_CASE)
        private val SAVE_RESULT_REGEX = Regex("""\bsave result image\b""", RegexOption.IGNORE_CASE)
        private val IMAGES_SAVED_REGEX = Regex("""\b\d+/\d+\s+images? saved\b""", RegexOption.IGNORE_CASE)

        fun buildStartingSnapshot(totalSteps: Int, statusText: String): SdProgressSnapshot =
            SdProgressSnapshot(
                currentStep = 0,
                totalSteps = totalSteps.coerceAtLeast(1),
                progress = 0f,
                statusText = statusText,
                phase = SdProgressPhase.PREPARING,
                isIndeterminate = true
            )

        fun parseStepProgress(line: String): Pair<Int, Int>? {
            val match = PIPE_PROGRESS_REGEX.find(line) ?: STEP_PROGRESS_REGEX.find(line) ?: return null
            val current = match.groupValues[1].toIntOrNull() ?: return null
            val total = match.groupValues[2].toIntOrNull() ?: return null
            return current to total
        }

        /** Reject tensor-loading throughput bars while accepting native sampling totals. */
        fun parseSamplingProgress(line: String): Pair<Int, Int>? {
            if (DATA_RATE_REGEX.containsMatchIn(line)) return null
            val hasSamplingRate = RATE_S_PER_IT_REGEX.containsMatchIn(line) ||
                RATE_IT_PER_S_REGEX.containsMatchIn(line)
            val hasExplicitStep = STEP_PROGRESS_REGEX.containsMatchIn(line)
            if (!hasSamplingRate && !hasExplicitStep) return null
            return parseStepProgress(line)
        }

        fun extractIterationSeconds(line: String): Double? {
            RATE_S_PER_IT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { secondsPerIt ->
                if (secondsPerIt > 0.0) return secondsPerIt
            }
            RATE_IT_PER_S_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { itPerSecond ->
                if (itPerSecond > 0.0) return 1.0 / itPerSecond
            }
            return null
        }

        fun isVaeProgressLine(line: String): Boolean {
            val normalized = line.lowercase()
            return "vae encode" in normalized || "vae decode" in normalized ||
                DECODING_LATENTS_REGEX.containsMatchIn(line)
        }

        fun progressPercent(snapshot: SdProgressSnapshot): Int =
            (snapshot.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    }
}

/** Native stdout can remain quiet for minutes during healthy CPU kernels. */
object SdNativeLivenessPolicy {
    private const val DEFAULT_COMPUTE_WINDOW_MS = 5 * 60_000L
    private const val SAVING_WINDOW_MS = 2 * 60_000L
    private const val ITERATION_MULTIPLIER = 3.0

    fun expectedNoOutputWindowMs(snapshot: SdProgressSnapshot): Long = when (snapshot.phase) {
        SdProgressPhase.SAVING -> SAVING_WINDOW_MS
        SdProgressPhase.DIFFUSION -> max(
            DEFAULT_COMPUTE_WINDOW_MS,
            ((snapshot.iterationSeconds ?: 0.0) * ITERATION_MULTIPLIER * 1_000.0).roundToLong()
        )
        else -> DEFAULT_COMPUTE_WINDOW_MS
    }

    fun noOutputBucket(gapMs: Long, expectedWindowMs: Long): Int {
        val window = expectedWindowMs.coerceAtLeast(1L)
        return when {
            gapMs >= window * 4 -> 3
            gapMs >= window * 2 -> 2
            gapMs >= window -> 1
            else -> 0
        }
    }

    fun shouldReportNoOutput(snapshot: SdProgressSnapshot, gapMs: Long): Boolean =
        gapMs >= expectedNoOutputWindowMs(snapshot)
}
