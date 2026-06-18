package com.example.llamadroid.service

import kotlin.math.ceil

data class PdfTokenBudget(
    val contextTokens: Int,
    val promptOverheadTokens: Int,
    val requestedOutputTokens: Int,
    val outputTokens: Int,
    val inputTokens: Int,
    val inputCharBudget: Int
)

object PDFSummaryLogic {
    private const val APPROX_CHARS_PER_TOKEN = 4
    private const val MIN_INPUT_TOKENS = 256
    private const val MIN_OUTPUT_TOKENS = 64
    private const val MIN_SEGMENT_TOKENS = 16
    private const val SAFETY_OVERHEAD_TOKENS = 96
    private const val DEFAULT_SEPARATOR = "\n\n"

    private val trailingCutMarkers = listOf(
        "common_perf_print:",
        "llama_perf_",
        "llama_memory_breakdown",
        "sampling time =",
        "samplers time =",
        "prompt eval time =",
        "eval time =",
        "load time =",
        "total time =",
        "ms per token",
        "tokens per second",
        "Exiting..."
    )

    private val filteredLinePatterns = listOf(
        Regex("""^\s*\[Start thinking]\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\[End thinking]\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*Thinking Process:\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\[\s*Prompt:.*\|\s*Generation:.*]\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\|[\s\d|]+\s*$"""),
        Regex("""^\s*>\s*$"""),
        Regex("""^\s*(build:|main:|print_info:|load:|generate:|common_|ggml_|llama_|system_info:).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(sampling time =|samplers time =|prompt eval time =|eval time =|load time =|total time =|common_perf_print:).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\d+(\.\d+)?\s*ms per token.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\d+(\.\d+)?\s*tokens per second.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(interactive mode|press ctrl|press return|more info:).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(n_ctx|n_batch|n_ubatch|n_predict|n_keep|n_seq|n_layer|n_head|n_embd|n_ff|n_vocab|n_expert|n_rot|n_swa|n_gqa|n_merges|n_group|flash_attn|kv_|cache|warmup).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(top_k|top_p|min_p|typical|temp|mirostat|frequency_penalty|presence_penalty|repeat_last_n|repeat_penalty|dry_).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(model type|model params|vocab type|arch|quant|graph nodes|graph splits|file format|file type|file size).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(EOG|EOS|BOS|EOT|UNK token|PAD token|LF token|special tokens|token to piece|max token).*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(llama_memory_breakdown|memory breakdown|graphs reused|unaccounted).*$""", RegexOption.IGNORE_CASE)
    )

    private val sentenceBoundaryRegex = Regex("""(?<=[.!?])\s+""")
    private val errorLineRegexes = listOf(
        Regex("""\berror\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfailed\b""", RegexOption.IGNORE_CASE),
        Regex("""\bexception\b""", RegexOption.IGNORE_CASE),
        Regex("""\binvalid\b""", RegexOption.IGNORE_CASE),
        Regex("""\btimeout\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcontext capacity\b""", RegexOption.IGNORE_CASE),
        Regex("""\bout of context\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstopped due to\b""", RegexOption.IGNORE_CASE)
    )

    fun approximateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return ceil(text.length / APPROX_CHARS_PER_TOKEN.toDouble()).toInt()
    }

    fun computeBudget(
        contextTokens: Int,
        requestedOutputTokens: Int,
        promptOverheadTokens: Int
    ): PdfTokenBudget {
        val safeContext = contextTokens.coerceAtLeast(MIN_INPUT_TOKENS + MIN_OUTPUT_TOKENS + SAFETY_OVERHEAD_TOKENS)
        val safePromptOverhead = (promptOverheadTokens + SAFETY_OVERHEAD_TOKENS).coerceAtLeast(SAFETY_OVERHEAD_TOKENS)
        val maxOutputTokens = (safeContext - safePromptOverhead - MIN_INPUT_TOKENS).coerceAtLeast(MIN_OUTPUT_TOKENS)
        val outputTokens = requestedOutputTokens.coerceAtLeast(MIN_OUTPUT_TOKENS).coerceAtMost(maxOutputTokens)
        val inputTokens = (safeContext - safePromptOverhead - outputTokens).coerceAtLeast(MIN_INPUT_TOKENS)
        return PdfTokenBudget(
            contextTokens = safeContext,
            promptOverheadTokens = safePromptOverhead,
            requestedOutputTokens = requestedOutputTokens,
            outputTokens = outputTokens,
            inputTokens = inputTokens,
            inputCharBudget = inputTokens * APPROX_CHARS_PER_TOKEN
        )
    }

    fun splitIntoChunks(text: String, chunkTokenBudget: Int): List<String> {
        return splitIntoChunks(text, chunkTokenBudget) { candidate ->
            approximateTokens(candidate) <= chunkTokenBudget.coerceAtLeast(MIN_SEGMENT_TOKENS)
        }
    }

    fun splitIntoChunks(
        text: String,
        chunkTokenBudget: Int,
        chunkFits: (String) -> Boolean
    ): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val safeBudget = chunkTokenBudget.coerceAtLeast(MIN_SEGMENT_TOKENS)
        if (chunkFits(normalized)) return listOf(normalized)

        val segments = normalized
            .split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { splitOversizedSegment(it, safeBudget) }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var currentTokens = 0
        val separatorTokens = approximateTokens(DEFAULT_SEPARATOR)

        for (segment in segments) {
            val segmentTokens = approximateTokens(segment)
            val candidate = if (currentChunk.isEmpty()) {
                segment
            } else {
                currentChunk.toString() + DEFAULT_SEPARATOR + segment
            }
            val projectedTokens = if (currentChunk.isEmpty()) segmentTokens else currentTokens + separatorTokens + segmentTokens

            if ((projectedTokens > safeBudget || !chunkFits(candidate)) && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
                currentChunk.append(segment)
                currentTokens = segmentTokens
            } else {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(DEFAULT_SEPARATOR)
                }
                currentChunk.append(segment)
                currentTokens = projectedTokens
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.isNotBlank() }
    }

    fun buildChunkPrompt(
        chunkText: String,
        chunkIndex: Int,
        totalChunks: Int,
        targetLanguage: String = "source language"
    ): String {
        return buildString {
            appendLine("Document chunk $chunkIndex of $totalChunks.")
            appendLine("You only have access to this chunk.")
            appendLine("Previous or later chunks are unavailable right now.")
            appendLine("Do not assume or invent information outside the visible text.")
            appendLine("A later merge step will combine all chunk summaries, so repetition cleanup can happen there.")
            appendLine("Write the summary in: $targetLanguage.")
            appendLine()
            appendLine(chunkText.trim())
        }.trim()
    }

    fun buildUnificationPrompt(
        summaries: List<String>,
        batchIndex: Int,
        totalBatches: Int,
        depth: Int,
        targetLanguage: String = "source language"
    ): String {
        return buildString {
            appendLine("Summary merge batch $batchIndex of $totalBatches.")
            if (depth > 0) {
                appendLine("This is an intermediate merge step for a large document.")
            }
            appendLine("These inputs are summaries, not the original source text.")
            appendLine("Merge only the information that is explicitly present here.")
            if (totalBatches > 1) {
                appendLine("A later merge step may combine this output with other batches.")
            }
            appendLine("Write the merged summary in: $targetLanguage.")
            appendLine()
            append(summaries.joinToString(DEFAULT_SEPARATOR))
        }.trim()
    }

    fun batchTextsForBudget(items: List<String>, inputTokenBudget: Int): List<List<String>> {
        return batchTextsForBudget(items, inputTokenBudget) { candidate ->
            approximateTokens(candidate.joinToString(DEFAULT_SEPARATOR)) <= inputTokenBudget.coerceAtLeast(MIN_SEGMENT_TOKENS)
        }
    }

    fun batchTextsForBudget(
        items: List<String>,
        inputTokenBudget: Int,
        batchFits: (List<String>) -> Boolean
    ): List<List<String>> {
        if (items.isEmpty()) return emptyList()
        val safeBudget = inputTokenBudget.coerceAtLeast(MIN_SEGMENT_TOKENS)
        val normalizedItems = items
            .flatMap { splitIntoChunks(it.trim(), safeBudget) }
            .filter { it.isNotBlank() }
        if (normalizedItems.isEmpty()) return emptyList()

        val batches = mutableListOf<MutableList<String>>()
        var currentBatch = mutableListOf<String>()
        var currentTokens = 0
        val separatorTokens = approximateTokens(DEFAULT_SEPARATOR)

        for (item in normalizedItems) {
            val normalized = item.trim()
            if (normalized.isEmpty()) continue
            val itemTokens = approximateTokens(normalized)
            if (currentBatch.isEmpty()) {
                currentBatch.add(normalized)
                currentTokens = itemTokens
                continue
            }

            val projectedTokens = currentTokens + separatorTokens + itemTokens
            val candidate = currentBatch + normalized
            if (projectedTokens > safeBudget || !batchFits(candidate)) {
                batches.add(currentBatch)
                currentBatch = mutableListOf(normalized)
                currentTokens = itemTokens
            } else {
                currentBatch.add(normalized)
                currentTokens = projectedTokens
            }
        }

        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }
        return batches
    }

    fun shouldRetryFailure(
        attemptNumber: Int,
        maxAttempts: Int,
        isCancelled: Boolean,
        timedOut: Boolean,
        output: String?,
        errorMessage: String?
    ): Boolean {
        if (isCancelled || attemptNumber >= maxAttempts) return false
        return timedOut || output.isNullOrBlank() || !errorMessage.isNullOrBlank()
    }

    fun cleanLlamaOutput(output: String): String {
        val normalizedOutput = stripThinkingBlocks(extractAfterTruncatedMarker(output)).trim()
        if (normalizedOutput.isBlank()) return ""

        val cleanedLines = mutableListOf<String>()
        for (line in normalizedOutput.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                if (cleanedLines.isNotEmpty() && cleanedLines.last().isNotBlank()) {
                    cleanedLines.add("")
                }
                continue
            }
            if (filteredLinePatterns.any { it.matches(trimmed) }) {
                continue
            }
            cleanedLines.add(trimmed)
        }

        return cleanedLines
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    fun extractRelevantErrorMessage(rawOutput: String, exitCode: Int? = null): String? {
        val lines = rawOutput
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val explicitError = lines.lastOrNull { line ->
            errorLineRegexes.any { regex -> regex.containsMatchIn(line) }
        }
        if (!explicitError.isNullOrBlank()) {
            return explicitError
        }

        if (exitCode != null && exitCode != 0) {
            return lines
                .takeLast(5)
                .joinToString(" ")
                .take(300)
                .ifBlank { null }
        }

        return null
    }

    private fun extractAfterTruncatedMarker(output: String): String {
        val truncatedIndex = output.indexOf("(truncated)")
        val candidate = if (truncatedIndex >= 0) {
            output.substring(truncatedIndex + "(truncated)".length)
        } else {
            output
        }

        var responseEnd = candidate.length
        for (marker in trailingCutMarkers) {
            val markerIndex = candidate.indexOf(marker)
            if (markerIndex > 0) {
                responseEnd = minOf(responseEnd, markerIndex)
            }
        }
        return candidate.substring(0, responseEnd)
    }

    private fun stripThinkingBlocks(output: String): String {
        return output
            .replace(Regex("""<think>.*?</think>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("""\[Start thinking].*?\[End thinking]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .trim()
    }

    private fun splitOversizedSegment(segment: String, tokenBudget: Int): List<String> {
        val normalized = segment.trim()
        if (normalized.isEmpty()) return emptyList()
        if (approximateTokens(normalized) <= tokenBudget) return listOf(normalized)

        val sentences = sentenceBoundaryRegex
            .split(normalized)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sentences.size <= 1) {
            return hardSplitSegment(normalized, tokenBudget)
        }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var currentTokens = 0
        val separatorTokens = approximateTokens(" ")

        for (sentence in sentences) {
            if (approximateTokens(sentence) > tokenBudget) {
                if (currentChunk.isNotBlank()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.clear()
                    currentTokens = 0
                }
                chunks.addAll(hardSplitSegment(sentence, tokenBudget))
                continue
            }

            val sentenceTokens = approximateTokens(sentence)
            val projectedTokens = if (currentChunk.isEmpty()) {
                sentenceTokens
            } else {
                currentTokens + separatorTokens + sentenceTokens
            }

            if (projectedTokens > tokenBudget && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
                currentChunk.append(sentence)
                currentTokens = sentenceTokens
            } else {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(' ')
                }
                currentChunk.append(sentence)
                currentTokens = projectedTokens
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun hardSplitSegment(segment: String, tokenBudget: Int): List<String> {
        val charBudget = (tokenBudget * APPROX_CHARS_PER_TOKEN).coerceAtLeast(APPROX_CHARS_PER_TOKEN * 32)
        val chunks = mutableListOf<String>()
        var remaining = segment.trim()

        while (remaining.isNotBlank()) {
            if (remaining.length <= charBudget) {
                chunks.add(remaining)
                break
            }

            var breakPoint = charBudget.coerceAtMost(remaining.length)
            val lastWhitespace = remaining.lastIndexOf(' ', breakPoint)
            if (lastWhitespace > charBudget / 2) {
                breakPoint = lastWhitespace
            }

            chunks.add(remaining.substring(0, breakPoint).trim())
            remaining = remaining.substring(breakPoint).trim()
        }

        return chunks.filter { it.isNotBlank() }
    }
}
