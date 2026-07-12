package com.example.llamadroid.service

import com.example.llamadroid.data.dao.LlamaSpeculativeRunDao
import com.example.llamadroid.data.model.LlamaSpeculativeRunEntity
import java.io.File
import java.util.Locale

data class LlamaSpeculativeRunMetrics(
    val acceptanceRate: Double?,
    val promptTokensPerSecond: Double?,
    val generationTokensPerSecond: Double?,
    val rawMetrics: String
)

class LlamaSpeculativeMetricsCollector {
    private val rawLines = ArrayDeque<String>()
    private var runPromptTokens = 0.0
    private var runPromptMs = 0.0
    private var runGenerationTokens = 0.0
    private var runGenerationMs = 0.0
    private var runAcceptedDraftTokens = 0.0
    private var runGeneratedDraftTokens = 0.0
    private var acceptanceRateSamples = 0
    private var sampledAcceptanceRate = 0.0
    private var lastStatsAcceptedTokens = 0.0
    private var lastStatsGeneratedTokens = 0.0

    private var blockPromptTokens: Double? = null
    private var blockPromptMs: Double? = null
    private var blockGenerationTokens: Double? = null
    private var blockGenerationMs: Double? = null
    private var blockAcceptedDraftTokens: Double? = null
    private var blockGeneratedDraftTokens: Double? = null
    private var blockAcceptanceRate: Double? = null
    private var blockStatsAcceptedTokens = 0.0
    private var blockStatsGeneratedTokens = 0.0

    fun onLogLine(line: String): LlamaSpeculativeRunMetrics? {
        val cleanLine = line.trim()
        if (cleanLine.isBlank()) return null

        val lower = cleanLine.lowercase(Locale.US)
        val isMetricsLine = lower.contains("slot") ||
            lower.contains("prompt eval") ||
            lower.contains("eval time") ||
            lower.contains("spec") ||
            lower.contains("draft") ||
            lower.contains("accept") ||
            lower.contains("statistics")

        if (isMetricsLine) {
            rememberRawLine(cleanLine)
            parseAcceptance(cleanLine, lower)
            parseTiming(cleanLine, lower)
        }

        if (lower.contains("total time") &&
            (blockGenerationMs != null || blockAcceptanceRate != null || blockGeneratedDraftTokens != null || blockStatsGeneratedTokens > 0.0)
        ) {
            rememberRawLine(cleanLine)
            commitBlock()
            val result = LlamaSpeculativeRunMetrics(
                acceptanceRate = computeRunAcceptanceRate(),
                promptTokensPerSecond = computeTokensPerSecond(runPromptTokens, runPromptMs),
                generationTokensPerSecond = computeTokensPerSecond(runGenerationTokens, runGenerationMs),
                rawMetrics = rawLines.joinToString("\n")
            )
            resetBlock()
            return result
        }

        return null
    }

    private fun rememberRawLine(line: String) {
        rawLines.addLast(line.take(260))
        while (rawLines.size > 10) {
            rawLines.removeFirst()
        }
    }

    private fun parseAcceptance(line: String, lower: String) {
        if (!lower.contains("accept") && !lower.contains("statistics")) return

        DRAFT_ACCEPTANCE_WITH_COUNTS_REGEX.find(line)?.let { match ->
            blockAcceptanceRate = normalizeAcceptanceRate(match.groupValues[1].toDoubleOrNull())
            blockAcceptedDraftTokens = match.groupValues[2].toDoubleOrNull()
            blockGeneratedDraftTokens = match.groupValues[3].toDoubleOrNull()
            return
        }

        STATISTICS_TOKENS_REGEX.find(line)?.let { match ->
            blockStatsGeneratedTokens += match.groupValues[1].toDoubleOrNull() ?: 0.0
            blockStatsAcceptedTokens += match.groupValues[2].toDoubleOrNull() ?: 0.0
            return
        }

        ACCEPTANCE_PERCENT_REGEX.find(line)?.let { match ->
            blockAcceptanceRate = match.groupValues[1].toDoubleOrNull()
            return
        }

        ACCEPTANCE_RATIO_REGEX.find(line)?.let { match ->
            blockAcceptanceRate = normalizeAcceptanceRate(match.groupValues[1].toDoubleOrNull())
            return
        }

        ACCEPTED_OVER_GENERATED_REGEX.find(line)?.let { match ->
            blockAcceptedDraftTokens = match.groupValues[1].toDoubleOrNull()
            blockGeneratedDraftTokens = match.groupValues[2].toDoubleOrNull()
        }
    }

    private fun parseTiming(line: String, lower: String) {
        val match = TIMING_REGEX.find(line) ?: return
        val ms = match.groupValues[1].toDoubleOrNull() ?: return
        val tokens = match.groupValues[2].toDoubleOrNull() ?: return
        when {
            lower.contains("prompt eval") || lower.contains("prompt processing") -> {
                blockPromptMs = ms
                blockPromptTokens = tokens
            }
            lower.contains("eval time") || lower.contains("generation") -> {
                blockGenerationMs = ms
                blockGenerationTokens = tokens
            }
        }
    }

    private fun commitBlock() {
        blockPromptMs?.let { ms ->
            val tokens = blockPromptTokens
            if (ms > 0.0 && tokens != null && tokens > 0.0) {
                runPromptMs += ms
                runPromptTokens += tokens
            }
        }
        blockGenerationMs?.let { ms ->
            val tokens = blockGenerationTokens
            if (ms > 0.0 && tokens != null && tokens > 0.0) {
                runGenerationMs += ms
                runGenerationTokens += tokens
            }
        }

        val explicitGenerated = blockGeneratedDraftTokens
        val explicitAccepted = blockAcceptedDraftTokens
        if (explicitGenerated != null && explicitGenerated > 0.0 && explicitAccepted != null) {
            runGeneratedDraftTokens += explicitGenerated
            runAcceptedDraftTokens += explicitAccepted
            return
        }

        if (blockStatsGeneratedTokens > 0.0) {
            val deltaGenerated = (blockStatsGeneratedTokens - lastStatsGeneratedTokens).coerceAtLeast(0.0)
            val deltaAccepted = (blockStatsAcceptedTokens - lastStatsAcceptedTokens).coerceAtLeast(0.0)
            lastStatsGeneratedTokens = blockStatsGeneratedTokens
            lastStatsAcceptedTokens = blockStatsAcceptedTokens
            if (deltaGenerated > 0.0) {
                runGeneratedDraftTokens += deltaGenerated
                runAcceptedDraftTokens += deltaAccepted
                return
            }
        }

        blockAcceptanceRate?.let { rate ->
            acceptanceRateSamples += 1
            sampledAcceptanceRate += (rate - sampledAcceptanceRate) / acceptanceRateSamples.toDouble()
        }
    }

    private fun computeRunAcceptanceRate(): Double? {
        if (runGeneratedDraftTokens > 0.0) {
            return runAcceptedDraftTokens / runGeneratedDraftTokens * 100.0
        }
        return sampledAcceptanceRate.takeIf { acceptanceRateSamples > 0 }
    }

    private fun computeTokensPerSecond(tokens: Double, ms: Double): Double? =
        if (tokens > 0.0 && ms > 0.0) tokens / ms * 1000.0 else null

    private fun normalizeAcceptanceRate(value: Double?): Double? =
        value?.let { if (it in 0.0..1.0) it * 100.0 else it }

    private fun resetBlock() {
        blockPromptTokens = null
        blockPromptMs = null
        blockGenerationTokens = null
        blockGenerationMs = null
        blockAcceptedDraftTokens = null
        blockGeneratedDraftTokens = null
        blockAcceptanceRate = null
        blockStatsAcceptedTokens = 0.0
        blockStatsGeneratedTokens = 0.0
    }

    companion object {
        private val DRAFT_ACCEPTANCE_WITH_COUNTS_REGEX = Regex("""draft\s+acceptance(?:\s+rate)?\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*\(\s*(\d+(?:\.\d+)?)\s+accepted\s*/\s*(\d+(?:\.\d+)?)\s+generated""", RegexOption.IGNORE_CASE)
        private val STATISTICS_TOKENS_REGEX = Regex("""#gen\s+tokens\s*=\s*(\d+(?:\.\d+)?),\s*#acc\s+tokens\s*=\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val ACCEPTANCE_PERCENT_REGEX = Regex("""accept(?:ance)?[^0-9%]*([0-9]+(?:\.[0-9]+)?)\s*%""", RegexOption.IGNORE_CASE)
        private val ACCEPTANCE_RATIO_REGEX = Regex("""draft\s+acceptance(?:\s+rate)?\s*=\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        private val ACCEPTED_OVER_GENERATED_REGEX = Regex("""(\d+(?:\.\d+)?)\s+accepted\s*/\s*(\d+(?:\.\d+)?)\s+generated""", RegexOption.IGNORE_CASE)
        private val TIMING_REGEX = Regex("""=\s*([0-9]+(?:\.[0-9]+)?)\s*ms\s*/\s*([0-9]+(?:\.[0-9]+)?)\s*(?:tokens?|runs?)""", RegexOption.IGNORE_CASE)
    }
}

object LlamaSpeculativeRunStore {
    private const val MAX_RECENT_UNSAVED_RUNS = 10

    suspend fun createRunAndPrune(
        dao: LlamaSpeculativeRunDao,
        modelPath: String,
        speculativeMode: LlamaSpeculativeMode,
        draftModelPath: String?
    ): Long {
        val id = dao.insertRun(
            LlamaSpeculativeRunEntity(
                modelPath = modelPath,
                modelName = File(modelPath).name,
                speculativeMode = speculativeMode.flagValue,
                draftModelPath = draftModelPath,
                draftModelName = draftModelPath?.let { File(it).name }
            )
        )
        prune(dao)
        return id
    }

    suspend fun recordPromptMetricsAndPrune(
        dao: LlamaSpeculativeRunDao,
        runId: Long,
        metrics: LlamaSpeculativeRunMetrics
    ) {
        val current = dao.getRunById(runId) ?: return
        val newCount = current.sampleCount.coerceAtLeast(0) + 1
        dao.updateRunMetrics(
            id = runId,
            sampleCount = newCount,
            acceptanceRate = metrics.acceptanceRate ?: current.acceptanceRate,
            promptTokensPerSecond = metrics.promptTokensPerSecond ?: current.promptTokensPerSecond,
            generationTokensPerSecond = metrics.generationTokensPerSecond ?: current.generationTokensPerSecond,
            rawMetrics = metrics.rawMetrics.take(2_000)
        )
        prune(dao)
    }

    private suspend fun prune(dao: LlamaSpeculativeRunDao) {
        val prunableIds = dao.getPrunableRunIdsNewestFirst()
        if (prunableIds.size > MAX_RECENT_UNSAVED_RUNS) {
            dao.deleteRuns(prunableIds.drop(MAX_RECENT_UNSAVED_RUNS))
        }
    }
}
