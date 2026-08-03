package com.example.llamadroid.service

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SdProgressSnapshot(
    val currentStep: Int,
    val totalSteps: Int,
    val progress: Float,
    val iterationSeconds: Double? = null,
    val etaSeconds: Double? = null,
    val statusText: String = "",
    val phase: SdProgressPhase = SdProgressPhase.DIFFUSION
)

enum class SdProgressPhase {
    DIFFUSION,
    VAE
}

class SdProgressTracker(
    private val totalStepsHint: Int,
    private val startedAtMs: Long,
    private val smoothingWindow: Int = 5
) {
    private var lastObservedStep = 0
    private var lastObservedTimestampMs: Long? = null
    private val recentIterationSeconds = ArrayDeque<Double>()
    private var lastSnapshot: SdProgressSnapshot? = null
    private var estimatedCompletionAtMs: Long? = null
    private var activePhase = SdProgressPhase.DIFFUSION

    @Synchronized
    fun update(line: String, nowMs: Long): SdProgressSnapshot? {
        val (currentStep, totalSteps) = parseStepProgress(line) ?: return null
        val phase = if (isVaeProgressLine(line)) SdProgressPhase.VAE else SdProgressPhase.DIFFUSION
        if (phase == SdProgressPhase.DIFFUSION && totalSteps != totalStepsHint) return null
        if (phase != activePhase) {
            activePhase = phase
            lastObservedStep = 0
            lastObservedTimestampMs = null
            recentIterationSeconds.clear()
            estimatedCompletionAtMs = null
        }

        val normalizedTotal = totalSteps.coerceAtLeast(1)
        val normalizedCurrent = currentStep.coerceIn(0, normalizedTotal)

        val sampleSeconds = extractIterationSeconds(line)
            ?: fallbackIterationSeconds(normalizedCurrent, nowMs)

        sampleSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let(::recordIterationSample)

        if (normalizedCurrent > lastObservedStep) {
            lastObservedStep = normalizedCurrent
            lastObservedTimestampMs = nowMs
        }

        val smoothedIteration = recentIterationSeconds.takeIf { it.isNotEmpty() }?.average()
        val remainingSteps = max(normalizedTotal - normalizedCurrent, 0)
        val etaSeconds = if (smoothedIteration != null) {
            remainingSteps * smoothedIteration
        } else {
            null
        }

        estimatedCompletionAtMs = etaSeconds?.let { nowMs + (it * 1000.0).roundToLong() }

        return SdProgressSnapshot(
            currentStep = normalizedCurrent,
            totalSteps = normalizedTotal,
            progress = normalizedCurrent.toFloat() / normalizedTotal.toFloat(),
            iterationSeconds = smoothedIteration,
            etaSeconds = etaSeconds,
            phase = phase
        ).also { lastSnapshot = it }
    }

    @Synchronized
    fun tick(nowMs: Long): SdProgressSnapshot? {
        val snapshot = lastSnapshot ?: return null
        val completionAtMs = estimatedCompletionAtMs ?: return null
        val remainingMs = (completionAtMs - nowMs).coerceAtLeast(0L)
        return snapshot.copy(etaSeconds = remainingMs.toDouble() / 1000.0)
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
        private val PIPE_PROGRESS_REGEX = Regex("""\|\s*(\d+)/(\d+)\s*-""")
        private val STEP_PROGRESS_REGEX = Regex("""step\s+(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private val RATE_S_PER_IT_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(?:s|sec)/it""", RegexOption.IGNORE_CASE)
        private val RATE_IT_PER_S_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*it/s""", RegexOption.IGNORE_CASE)
        private val VAE_PROGRESS_REGEX = Regex("""\b(?:vae|decode|decoding|decoded)\b""", RegexOption.IGNORE_CASE)

        fun buildStartingSnapshot(totalSteps: Int, statusText: String): SdProgressSnapshot =
            SdProgressSnapshot(
                currentStep = 0,
                totalSteps = totalSteps.coerceAtLeast(1),
                progress = 0f,
                statusText = statusText
            )

        fun parseStepProgress(line: String): Pair<Int, Int>? {
            val pipeMatch = PIPE_PROGRESS_REGEX.find(line)
            if (pipeMatch != null) {
                val current = pipeMatch.groupValues[1].toIntOrNull() ?: return null
                val total = pipeMatch.groupValues[2].toIntOrNull() ?: return null
                return current to total
            }
            val stepMatch = STEP_PROGRESS_REGEX.find(line)
            if (stepMatch != null) {
                val current = stepMatch.groupValues[1].toIntOrNull() ?: return null
                val total = stepMatch.groupValues[2].toIntOrNull() ?: return null
                return current to total
            }
            return null
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

        fun isVaeProgressLine(line: String): Boolean =
            VAE_PROGRESS_REGEX.containsMatchIn(line)

        fun progressPercent(snapshot: SdProgressSnapshot): Int =
            (snapshot.progress.coerceIn(0f, 1f) * 100f).roundToInt()
    }
}
