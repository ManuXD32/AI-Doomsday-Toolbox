package com.example.llamadroid.service.compaction

import com.example.llamadroid.service.OllamaService.ChatMessage

data class CompactionPolicy(
    val thresholdRatio: Double = 0.8,
    val retainRatio: Double = 0.16,
    val minRetainedExchanges: Int = 2,
    val pruneThresholdChars: Int = 8192,
    val pruneHeadChars: Int = 4096,
    val pruneTailChars: Int = 1024
)

/** Selects the summary section list and instruction wording. */
enum class CompactionProfile { MAIN_CHAT, SUBAGENT }

data class CompactionPlan(
    /** Leading system messages, kept as-is at the top. */
    val leadingSystem: List<ChatMessage>,
    /** Older messages that the summary replaces. */
    val toSummarize: List<ChatMessage>,
    /** Newest tail kept verbatim; starts at a user turn. */
    val kept: List<ChatMessage>,
    /** Index in the input list where [kept] begins. */
    val keptStartIndex: Int,
    val estimatedTokensBefore: Int
)

/**
 * Pure compaction building blocks mirroring the DeepSeek Harness basic compactor.
 *
 * Design rules:
 * - The kept tail always begins at a user message, so an assistant `tool_calls` message is never
 *   separated from its `tool` results (they always sit inside the same user turn).
 * - The summary request replays the full prior prompt (system + history) unchanged and only
 *   appends one instruction. The server's warm KV prefix is therefore reused and only the
 *   instruction is new prompt work.
 * - The summary is merged into the first kept user message (not a separate message) so roles
 *   keep alternating, which strict chat templates require.
 */
object ConversationCompactor {
    const val SUMMARY_OPEN = "<compacted-summary>"
    const val SUMMARY_CLOSE = "</compacted-summary>"
    private const val CHECKPOINT_PREAMBLE =
        "Checkpoint: earlier conversation was compacted. The summary below replaces it; treat it as accurate prior context."
    private const val MIN_VALID_SUMMARY_CHARS = 80

    private val SUMMARY_BLOCK =
        Regex("""${Regex.escape(SUMMARY_OPEN)}.*?${Regex.escape(SUMMARY_CLOSE)}\s*""", RegexOption.DOT_MATCHES_ALL)

    private val MAIN_SECTIONS = listOf(
        "Primary requests", "Key facts and decisions", "Files and artifacts", "Background tasks",
        "Errors and fixes", "Current topic", "Next step", "Critical context"
    )
    private val SUBAGENT_SECTIONS = listOf(
        "Primary request", "Harness project state", "Files and changes", "Approvals and decisions",
        "Errors and fixes", "Pending work", "Next step", "Critical context"
    )

    fun sections(profile: CompactionProfile): List<String> =
        if (profile == CompactionProfile.MAIN_CHAT) MAIN_SECTIONS else SUBAGENT_SECTIONS

    fun outputReserve(contextTokens: Int, maxOutputTokens: Int?): Int =
        if (maxOutputTokens != null && maxOutputTokens > 0) maxOutputTokens
        else (contextTokens / 8).coerceIn(512, 4096)

    fun summaryMaxTokens(contextTokens: Int): Int = (contextTokens / 8).coerceIn(512, 2048)

    fun shouldCompact(
        estimatedPromptTokens: Int, contextTokens: Int, outputReserve: Int,
        policy: CompactionPolicy = CompactionPolicy()
    ): Boolean {
        if (contextTokens <= 0) return false
        val usable = (contextTokens - outputReserve).coerceAtLeast(1)
        return estimatedPromptTokens >= policy.thresholdRatio * usable
    }

    /** Shrinks oversized `tool` results to head + marker + tail. Deterministic and idempotent. */
    fun pruneToolResults(messages: List<ChatMessage>, policy: CompactionPolicy = CompactionPolicy()): List<ChatMessage> =
        messages.map { m ->
            val c = m.content
            if (m.role != "tool" || c.length <= policy.pruneThresholdChars) m
            else {
                val removed = c.length - policy.pruneHeadChars - policy.pruneTailChars
                m.copy(
                    content = c.substring(0, policy.pruneHeadChars) +
                        "\n…[$removed chars omitted]…\n" +
                        c.substring(c.length - policy.pruneTailChars)
                )
            }
        }

    fun plan(
        messages: List<ChatMessage>, contextTokens: Int, estimator: TokenEstimator,
        policy: CompactionPolicy = CompactionPolicy()
    ): CompactionPlan? {
        val firstBody = messages.indexOfFirst { it.role != "system" }.let { if (it < 0) messages.size else it }
        val userIdx = (firstBody until messages.size).filter { messages[it].role == "user" }
        // Need more than minRetainedExchanges turns so something older exists to summarize.
        if (userIdx.size < policy.minRetainedExchanges + 1) return null

        val total = estimator.estimateMessages(messages)
        val bodyTokens = estimator.estimateMessages(messages.subList(firstBody, messages.size))
        // Cap by the window too, so a pathologically long history cannot keep a tail that alone fills it.
        val budget = minOf(bodyTokens, contextTokens.coerceAtLeast(1)).times(policy.retainRatio).toInt()

        // Walk user-turn starts newest -> oldest, widening the kept tail while it fits the budget.
        var cut = userIdx.last()
        var turns = 1
        for (k in userIdx.size - 2 downTo 1) { // k = 0 is excluded: something must stay summarizable
            val candidate = userIdx[k]
            val candTokens = estimator.estimateMessages(messages.subList(candidate, messages.size))
            if (turns < policy.minRetainedExchanges || candTokens <= budget) {
                cut = candidate; turns++
            } else break
        }
        if (cut <= userIdx.first() || cut <= firstBody) return null
        return CompactionPlan(
            leadingSystem = messages.subList(0, firstBody).toList(),
            toSummarize = messages.subList(firstBody, cut).toList(),
            kept = messages.subList(cut, messages.size).toList(),
            keptStartIndex = cut,
            estimatedTokensBefore = total
        )
    }

    /**
     * Full prior prompt replayed verbatim (leading system + older + kept) plus one trailing
     * instruction, so the warm KV prefix is reused. Callers should send it with tool choice
     * "none" and [summaryMaxTokens].
     */
    fun buildSummaryRequest(plan: CompactionPlan, profile: CompactionProfile): List<ChatMessage> {
        val replay = plan.leadingSystem + plan.toSummarize + plan.kept
        val instruction = summaryInstruction(profile)
        val last = replay.lastOrNull()
        // Strict templates reject two consecutive user messages; append to a trailing user turn instead.
        return if (last != null && last.role == "user") {
            replay.dropLast(1) + last.copy(content = last.content.trimEnd() + "\n\n" + instruction)
        } else {
            replay + ChatMessage(role = "user", content = instruction)
        }
    }

    fun summaryInstruction(profile: CompactionProfile): String {
        val list = sections(profile).joinToString("\n") { "## $it" }
        return buildString {
            append("Pause the task and write a compact checkpoint summary of the conversation so far. ")
            append("Summarize only what is actually in the conversation above; do not invent anything. ")
            append("Keep identifiers, file paths, ids, commands and numbers exact. ")
            append("Write these headings exactly, in this order, each followed by concise content ")
            append("(write \"None\" if a section has nothing):\n")
            append(list)
            append("\nBe concise. Do not call any tools. Reply with the summary only.")
        }
    }

    fun isValidSummary(text: String, profile: CompactionProfile): Boolean {
        if (text.trim().length < MIN_VALID_SUMMARY_CHARS) return false
        val lower = text.lowercase()
        return sections(profile).all { lower.contains(it.lowercase()) }
    }

    /** Rule-based fallback when the model summary fails or lacks sections. Bounded by [maxChars]. */
    fun deterministicSummary(toSummarize: List<ChatMessage>, profile: CompactionProfile, maxChars: Int = 6000): String {
        val names = sections(profile)
        val perSection = ((maxChars - names.sumOf { it.length + 6 }) / names.size).coerceAtLeast(40)
        val users = toSummarize.filter { it.role == "user" }
        // Token-wise scan (bounded input) keeps this linear even on huge tool outputs.
        val pathRx = Regex("""^(?:[\w.-]+/)+[\w.-]+\.\w{1,8}$|^f\d+$|^img\d+$""")
        val paths = toSummarize.asSequence()
            .flatMap { m ->
                val args = m.toolCalls?.joinToString(" ") { c -> c.arguments.values.joinToString(" ") }.orEmpty()
                (m.content.take(20_000) + " " + args.take(4_000)).split(Regex("[\\s,;()\\[\\]\"']+")).asSequence()
            }
            .filter { it.length in 2..200 && pathRx.matches(it) }
            .distinct().take(30).toList()
        val errors = toSummarize.filter { it.role == "tool" }
            .flatMap { it.content.lines() }
            .filter { l -> l.contains("error", true) || l.contains("failed", true) || l.contains("exception", true) }
            .map { it.trim() }.distinct().takeLast(5)
        fun clip(s: String) = if (s.length <= perSection) s else s.take(perSection - 1) + "…"
        val first = users.firstOrNull()?.content?.trim().orEmpty()
        val last = users.lastOrNull()?.content?.trim().orEmpty()
        val body = names.map { name ->
            val text = when (name) {
                "Primary requests", "Primary request" -> first
                "Current topic" -> last
                "Files and artifacts", "Files and changes" -> paths.joinToString(", ")
                "Errors and fixes" -> errors.joinToString("\n")
                "Next step" -> "Continue from the most recent user request."
                else -> ""
            }
            "## $name\n" + clip(text.ifBlank { "None recorded (automatic fallback summary)." })
        }
        return body.joinToString("\n\n").take(maxChars)
    }

    /**
     * leadingSystem + kept, with the summary block merged into the first kept user message.
     * Any existing summary block in that message is replaced, never nested.
     */
    fun applySummary(plan: CompactionPlan, summary: String): List<ChatMessage> {
        val block = summaryBlock(summary)
        val kept = plan.kept
        val first = kept.firstOrNull()
        val merged = if (first != null && first.role == "user") {
            val rest = stripSummaryFromText(first.content)
            listOf(first.copy(content = if (rest.isBlank()) block else "$block\n\n$rest")) + kept.drop(1)
        } else {
            listOf(ChatMessage(role = "user", content = block)) + kept
        }
        return plan.leadingSystem + merged
    }

    /** The exact `<compacted-summary>` block text used by [applySummary]. */
    fun summaryBlock(summary: String): String =
        "$SUMMARY_OPEN\n$CHECKPOINT_PREAMBLE\n\n${summary.trim()}\n$SUMMARY_CLOSE"

    /** Prepends the summary block to user text, replacing any existing block (never nested). */
    fun mergeSummaryIntoUserText(text: String, summary: String): String {
        val rest = stripSummaryFromText(text)
        val block = summaryBlock(summary)
        return if (rest.isBlank()) block else "$block\n\n$rest"
    }

    fun stripSummaryFromText(text: String): String = text.replace(SUMMARY_BLOCK, "").trim()
}
