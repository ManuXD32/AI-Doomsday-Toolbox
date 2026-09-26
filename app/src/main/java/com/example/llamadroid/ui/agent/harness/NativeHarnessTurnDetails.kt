package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The durable identity of a completed turn tail.  The upstream Chat surface
 * uses the turn/end sequence for the tail node, but forks at the final
 * content-bearing assistant/message sequence.  Keeping both values prevents
 * a native row from accidentally forking after a tool result or retry.
 */
internal data class NativeHarnessTurnActionTarget(
    val sessionId: String?,
    val turn: Int,
    val tailSequence: Long,
    val branchSequence: Long?,
    val fallbackPreview: String,
)

internal data class NativeHarnessTranscriptActionHooks(
    val onCopyTurn: (NativeHarnessTurnActionTarget) -> Unit = {},
    val onBranchTurn: (NativeHarnessTurnActionTarget) -> Unit = {},
    val onCopyMessage: (NativeHarnessMessageActionTarget) -> Unit = {},
    val isCopying: Boolean = false,
    val onCancelCopy: () -> Unit = {},
)

/** A single user/message copy target; the host performs the paged fetch. */
internal data class NativeHarnessMessageActionTarget(
    val sessionId: String?,
    val turn: Int?,
    val messageSequence: Long,
    val messageId: String?,
    val fallbackPreview: String,
)

data class NativeHarnessTurnTimingUi(
    val runMs: Long? = null,
    val tokensPerSecond: Double? = null,
    val ttftMs: Long? = null,
)

data class NativeHarnessTurnTailUi(
    val turn: Int,
    val tailSequence: Long,
    val branchSequence: Long?,
    val branchUnavailable: Boolean,
    val text: String,
    val usage: HarnessTokenUsageUi?,
    val timing: NativeHarnessTurnTimingUi?,
)

data class NativeHarnessRetryAttemptUi(
    val retryId: String,
    val turn: Int,
    val step: Int,
    val retry: Int,
    val mode: String,
    val maxRetries: Int?,
    val delayMs: Long,
    val provider: String?,
    val policyKey: String?,
    val failureCode: String?,
    val failureMessage: String,
    val state: NativeHarnessRetryState,
    val sequence: Long,
)

enum class NativeHarnessRetryState {
    SCHEDULED,
    STARTED,
    CANCELLED,
}

data class NativeHarnessModelRetryUi(
    val retryId: String,
    val turn: Int,
    val step: Int,
    val attempts: List<NativeHarnessRetryAttemptUi>,
) {
    val current: NativeHarnessRetryAttemptUi get() = attempts.last()
}

enum class NativeHarnessCompactionState {
    STARTED,
    SUMMARIZED,
    COMPLETED,
    FAILED,
}

data class NativeHarnessCompactionUi(
    val compactionId: String?,
    val turn: Int?,
    val sequence: Long,
    val checkpointSequence: Long?,
    val summary: String?,
    val shadowedItemCount: Int?,
    val shadowedTokenCount: Long?,
    val state: NativeHarnessCompactionState,
)

/** Bounded non-content evidence retained while a live turn is being folded. */
data class NativeHarnessTurnUsageEvidenceUi(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val reasoningTokens: Long?,
)

/** A live event's metadata only; prompts, tool arguments, and message text are excluded. */
data class NativeHarnessTurnEvidenceUi(
    val sequence: Long,
    val time: Long?,
    val type: String,
    val turn: Int?,
    val step: Int?,
    val surfaceOp: String?,
    val usage: NativeHarnessTurnUsageEvidenceUi? = null,
    val usageMalformed: Boolean = false,
    val firstTokenTime: Long? = null,
)

/**
 * Adds turn-tail metadata and lifecycle rows after the bounded content parser
 * has converted records to transcript items.  This second pass mirrors the
 * pinned Chat definitions while retaining only the fields native controls
 * need.  It is deliberately independent from transport and controller state.
 */
internal fun decorateNativeHarnessStructuredRows(
    rows: List<NativeHarnessStructuredTranscriptItem>,
    records: List<JsonObject>,
): List<NativeHarnessStructuredTranscriptItem> {
    val events = records.takeLast(NativeHarnessStructuredTranscriptLimits.MAX_INPUT_RECORDS)
        .mapNotNull(::nativeHarnessEventEnvelope)
        .sortedBy { it.sequence }
    if (events.isEmpty()) return rows
    val tails = projectNativeHarnessTurnTails(events)
    val bySequence = events.associateBy { it.sequence }
    val skillNamesBySequence = projectNativeHarnessSkillNames(events)
    val decorated = rows.map { row ->
        val sequences = (row.detailRef as? NativeHarnessTranscriptDetailRef.SessionEvents)
            ?.sequences.orEmpty()
        val event = sequences.asReversed().firstNotNullOfOrNull { sequence -> bySequence[sequence] }
        val turn = event?.data?.long("turn")
            ?: sequences.asReversed().firstNotNullOfOrNull { sequence ->
                events.firstOrNull { it.sequence == sequence }?.data?.long("turn")
            }
            ?: event?.let { nativeHarnessOpenTurnForSequence(events, it.sequence)?.toLong() }
        val step = event?.data?.long("step")?.toIntSafely()
        val skillNames = sequences.asSequence()
            .flatMap { sequence -> skillNamesBySequence[sequence].orEmpty().asSequence() }
            .toSet()
        val tail = turn?.toIntSafely()?.let(tails::get)
        row.copy(
            turn = turn?.toIntSafely(),
            step = step,
            skillNames = skillNames,
            turnTail = if (row.role == HarnessTranscriptRole.ASSISTANT && tail != null &&
                tail.branchSequence == row.sequence
            ) tail else null,
        )
    }
    val special = projectNativeHarnessLifecycleRows(events)
    val compactCheckpointSequences = special.mapNotNull { it.compaction?.checkpointSequence }.toSet()
    val withoutCompactionCheckpoint = decorated.filterNot { row ->
        row.detailRef is NativeHarnessTranscriptDetailRef.SessionEvents &&
            row.detailRef.sequences.any(compactCheckpointSequences::contains)
    }
    val attachedTurns = withoutCompactionCheckpoint.mapNotNull { it.turnTail?.turn }.toSet()
    val syntheticTails = tails.values
        .filterNot { it.turn in attachedTurns }
        .map { tail ->
            NativeHarnessStructuredTranscriptItem(
                id = "structured-turn-tail-${tail.turn}-${tail.tailSequence}",
                sequence = tail.tailSequence,
                role = HarnessTranscriptRole.ASSISTANT,
                turn = tail.turn,
                parts = tail.text.takeIf { it.isNotBlank() }
                    ?.let { listOf(NativeHarnessStructuredTranscriptPart.Text(it)) }
                    .orEmpty(),
                turnTail = tail,
            )
        }
    return (withoutCompactionCheckpoint + special + syntheticTails)
        .distinctBy { it.id }
        .sortedBy { it.sequence }
        .takeLast(NativeHarnessStructuredTranscriptLimits.MAX_ITEMS)
}

/** User messages are logged before step data and therefore usually carry no turn field. */
private fun nativeHarnessOpenTurnForSequence(
    events: List<NativeHarnessEventEnvelope>,
    sequence: Long,
): Int? {
    val start = events.asSequence()
        .filter { it.type == "turn/start" && it.sequence <= sequence }
        .maxByOrNull { it.sequence }
        ?: return null
    val endedBeforeMessage = events.any { event ->
        event.type == "turn/end" && event.sequence > start.sequence && event.sequence <= sequence
    }
    return if (endedBeforeMessage) null else start.data.long("turn")?.toIntSafely()
}

/**
 * Applies the small amount of turn metadata that can be proven from one live
 * event and the already bounded rows. Full snapshots use the richer pass
 * above; this keeps a live turn tail and retry state correct between them.
 */
internal fun mergeNativeHarnessTurnDetails(
    rows: List<NativeHarnessStructuredTranscriptItem>,
    record: JsonObject,
): List<NativeHarnessStructuredTranscriptItem> {
    val event = nativeHarnessEventEnvelope(record) ?: return rows
    var next = upsertNativeHarnessTurnEvidence(rows, event)
    val turn = event.data.long("turn")?.toIntSafely()
    if (turn != null && (event.type == "tool/call" ||
            event.type == "tool/result" && event.surfaceOp == "append" ||
            event.type == "llm/retry")) {
        next = updateTurnBranchState(next, turn)
    }
    if (turn != null && event.type == "turn/end") {
        val latest = next.filter { it.turn == turn && !it.isMetadataOnly }.maxOfOrNull { it.sequence }
        val closing = next.filter { row ->
                row.turn == turn &&
                row.role == HarnessTranscriptRole.ASSISTANT &&
                row.surfaceOp == "append" &&
                row.detailRef is NativeHarnessTranscriptDetailRef.SessionEvents &&
                row.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Text>()
                    .joinToString("") { it.value }
                    .isNotBlank()
        }
            .maxByOrNull { it.sequence }
        if (closing != null) {
            val text = closing.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Text>()
                .joinToString("") { it.value }
            val projected = projectNativeHarnessLiveTail(next, turn)
            val tail = NativeHarnessTurnTailUi(
                turn = turn,
                tailSequence = event.sequence,
                branchSequence = closing.sequence,
                branchUnavailable = latest != null && latest != closing.sequence,
                text = text.bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT),
                usage = projected.first ?: closing.turnTail?.usage,
                timing = projected.second ?: closing.turnTail?.timing,
            )
            next = next.map { row ->
                if (row.id == closing.id) row.copy(turnTail = tail) else row
            }
        }
    }
    if (event.type == "llm/retry-started") {
        val retryId = event.data.string("retryId")
        val retryNumber = event.data.long("retry")?.toIntSafely()
        if (retryId != null && retryNumber != null) {
            next = next.map { row ->
                val retry = row.retry ?: return@map row
                if (retry.retryId != retryId) return@map row
                row.copy(retry = retry.copy(attempts = retry.attempts.map { attempt ->
                    if (attempt.retry == retryNumber) attempt.copy(state = NativeHarnessRetryState.STARTED) else attempt
                }))
            }
        }
    }
    if (event.type == "step/end" || event.type == "turn/end") {
        val retryTurn = event.data.long("turn")?.toIntSafely()
        val retryStep = event.data.long("step")?.toIntSafely()
        if (retryTurn != null) {
            next = next.map { row ->
                val retry = row.retry ?: return@map row
                if (retry.turn != retryTurn || retryStep != null && retry.step != retryStep) return@map row
                row.copy(retry = retry.copy(attempts = retry.attempts.map { attempt ->
                    if (attempt.state == NativeHarnessRetryState.SCHEDULED) {
                        attempt.copy(state = NativeHarnessRetryState.CANCELLED)
                    } else attempt
                }))
            }
        }
    }
    val lifecycleCompaction = projectNativeHarnessLifecycleRows(listOf(event))
        .firstOrNull { it.compaction != null }
    if (lifecycleCompaction?.compaction != null) {
        val incomingCompaction = lifecycleCompaction.compaction
        val index = next.indexOfFirst { it.compaction?.compactionId == incomingCompaction.compactionId }
        if (index >= 0) {
            val existing = next[index].compaction ?: incomingCompaction
            next = next.toMutableList().also { mutable ->
                mutable[index] = mutable[index].copy(
                    sequence = incomingCompaction.sequence,
                    compaction = existing.mergeNativeHarnessCompaction(incomingCompaction),
                )
            }
        } else {
            next = (next + lifecycleCompaction)
                .sortedBy { it.sequence }
                .takeLast(NativeHarnessStructuredTranscriptLimits.MAX_ITEMS)
        }
    }
    return next
}

private const val MAX_LIVE_TURN_EVIDENCE = 128
private val LIVE_TURN_EVIDENCE_TYPES = setOf(
    "turn/start",
    "step/start",
    "assistant/attempt",
    "assistant/message",
    "assistant/live-chunk",
    "llm/retry",
    "llm/retry-started",
    "step/end",
    "turn/end",
)

private fun upsertNativeHarnessTurnEvidence(
    rows: List<NativeHarnessStructuredTranscriptItem>,
    event: NativeHarnessEventEnvelope,
): List<NativeHarnessStructuredTranscriptItem> {
    val evidence = nativeHarnessTurnEvidence(event) ?: return rows
    val turn = evidence.turn ?: return rows
    val carrierIndex = rows.indexOfFirst { it.isMetadataOnly && it.turn == turn }
    val targetIndex = rows.indexOfFirst { !it.isMetadataOnly && it.turn == turn }
    val index = carrierIndex.takeIf { it >= 0 } ?: targetIndex
    val previousEvidence = rows.getOrNull(index)?.turnEvidence.orEmpty()
    if (event.type !in LIVE_TURN_EVIDENCE_TYPES ||
        event.type == "assistant/live-chunk" &&
            (evidence.firstTokenTime == null || previousEvidence.any {
                it.step == evidence.step && it.firstTokenTime != null
            })
    ) return rows
    val combinedEvidence = (previousEvidence + evidence)
        .distinctBy { it.sequence }
        .sortedBy { it.sequence }
    val nextEvidence = if (index >= 0) {
        boundNativeHarnessTurnEvidence(combinedEvidence)
    } else {
        listOf(evidence)
    }
    val evidenceTruncated = rows.getOrNull(index)?.turnEvidenceTruncated == true ||
        nextEvidence.size < combinedEvidence.size
    if (targetIndex >= 0) {
        val updated = rows.toMutableList()
        updated[targetIndex] = updated[targetIndex].copy(
            turnEvidence = nextEvidence,
            turnEvidenceTruncated = evidenceTruncated,
        )
        if (carrierIndex >= 0) updated.removeAt(carrierIndex)
        return updated
    }
    val carrier = NativeHarnessStructuredTranscriptItem(
        id = "structured-turn-evidence-$turn",
        sequence = evidence.sequence,
        role = HarnessTranscriptRole.SYSTEM,
        turn = turn,
        parts = emptyList(),
        turnEvidence = nextEvidence,
        turnEvidenceTruncated = evidenceTruncated,
        isMetadataOnly = true,
    )
    return if (carrierIndex >= 0) rows.toMutableList().also { it[carrierIndex] = carrier }
        else (rows + carrier).takeLast(NativeHarnessStructuredTranscriptLimits.MAX_ITEMS)
}

private fun boundNativeHarnessTurnEvidence(
    evidence: List<NativeHarnessTurnEvidenceUi>,
): List<NativeHarnessTurnEvidenceUi> {
    val ordered = evidence.distinctBy { it.sequence }.sortedBy { it.sequence }
    if (ordered.size <= MAX_LIVE_TURN_EVIDENCE) return ordered
    val latest = ordered.last()
    val anchors = ordered.filter { it.type == "turn/start" || it.type == "step/start" }
        .filterNot { it.sequence == latest.sequence }
        .takeLast((MAX_LIVE_TURN_EVIDENCE - 1).coerceAtLeast(0))
    val remaining = ordered.filterNot { candidate ->
        candidate.sequence == latest.sequence || anchors.any { it.sequence == candidate.sequence }
    }.takeLast((MAX_LIVE_TURN_EVIDENCE - anchors.size - 1).coerceAtLeast(0))
    return (anchors + remaining + latest).distinctBy { it.sequence }.sortedBy { it.sequence }
}

private fun nativeHarnessTurnEvidence(event: NativeHarnessEventEnvelope): NativeHarnessTurnEvidenceUi? {
    val turn = event.data.long("turn")?.toIntSafely() ?: return null
    val usagePresent = event.data.containsKey("usage") || event.data.objectArray("stream").any {
        it.objectValue("chunk")?.string("type") == "usage"
    }
    val rawUsage = nativeHarnessUsageFromEvent(event)
    return NativeHarnessTurnEvidenceUi(
        sequence = event.sequence,
        time = event.time,
        type = event.type,
        turn = turn,
        step = event.data.long("step")?.toIntSafely(),
        surfaceOp = event.surfaceOp,
        usage = rawUsage?.let {
            NativeHarnessTurnUsageEvidenceUi(
                inputTokens = it.input,
                outputTokens = it.output,
                totalTokens = it.total,
                cacheReadTokens = it.cacheRead,
                cacheWriteTokens = it.cacheWrite,
                reasoningTokens = it.reasoning,
            )
        },
        usageMalformed = usagePresent && rawUsage == null,
        firstTokenTime = nativeHarnessFirstTokenTime(event),
    )
}

private fun projectNativeHarnessLiveTail(
    rows: List<NativeHarnessStructuredTranscriptItem>,
    turn: Int,
): Pair<HarnessTokenUsageUi?, NativeHarnessTurnTimingUi?> {
    val evidence = rows.asSequence()
        .filter { it.turn == turn }
        .flatMap { it.turnEvidence.asSequence() }
        .distinctBy { it.sequence }
        .sortedBy { it.sequence }
        .toList()
    if (evidence.isEmpty()) return null to null
    val envelopes = evidence.map(::nativeHarnessEvidenceEnvelope)
    val usage = if (rows.any { it.turn == turn && it.turnEvidenceTruncated } ||
        evidence.any { it.usageMalformed }
    ) {
        null
    } else {
        projectNativeHarnessTurnUsage(envelopes)
    }
    val assistants = envelopes.filter { it.type == "assistant/message" && it.surfaceOp == "append" }
        .map { it to "assistant" }
    val timing = projectNativeHarnessTurnTiming(envelopes, assistants)
    return usage to timing
}

private fun nativeHarnessEvidenceEnvelope(
    evidence: NativeHarnessTurnEvidenceUi,
): NativeHarnessEventEnvelope = NativeHarnessEventEnvelope(
    sequence = evidence.sequence,
    time = evidence.time,
    type = evidence.type,
    data = buildJsonObject {
        evidence.turn?.let { put("turn", it) }
        evidence.step?.let { put("step", it) }
        evidence.usage?.let { usage ->
            putJsonObject("usage") {
                put("uncachedInputTokens", usage.inputTokens)
                put("outputTokens", usage.outputTokens)
                usage.cacheReadTokens?.let { put("cacheReadTokens", it) }
                usage.cacheWriteTokens?.let { put("cacheWriteTokens", it) }
                usage.reasoningTokens?.let { put("reasoningTokens", it) }
                put("totalTokens", usage.totalTokens)
            }
        }
        evidence.firstTokenTime?.let { firstToken ->
            putJsonArray("stream") {
                add(buildJsonObject {
                    put("type", "chunk")
                    put("time", firstToken)
                    putJsonObject("chunk") {
                        put("type", "text-delta")
                        put("text", "·")
                    }
                })
            }
        }
    },
    surfaceOp = evidence.surfaceOp,
)

private fun NativeHarnessCompactionUi.mergeNativeHarnessCompaction(
    incoming: NativeHarnessCompactionUi,
): NativeHarnessCompactionUi = copy(
    turn = incoming.turn ?: turn,
    sequence = incoming.sequence,
    checkpointSequence = incoming.checkpointSequence ?: checkpointSequence,
    summary = incoming.summary ?: summary,
    shadowedItemCount = incoming.shadowedItemCount ?: shadowedItemCount,
    shadowedTokenCount = incoming.shadowedTokenCount ?: shadowedTokenCount,
        state = when {
            incoming.state == NativeHarnessCompactionState.FAILED -> NativeHarnessCompactionState.FAILED
            state == NativeHarnessCompactionState.FAILED -> NativeHarnessCompactionState.FAILED
            incoming.state == NativeHarnessCompactionState.COMPLETED -> NativeHarnessCompactionState.COMPLETED
            incoming.summary != null -> NativeHarnessCompactionState.SUMMARIZED
            incoming.state == NativeHarnessCompactionState.STARTED -> NativeHarnessCompactionState.STARTED
        else -> state
    },
)

private fun updateTurnBranchState(
    rows: List<NativeHarnessStructuredTranscriptItem>,
    turn: Int,
): List<NativeHarnessStructuredTranscriptItem> = rows.map { row ->
    val tail = row.turnTail ?: return@map row
    if (tail.turn != turn) row else row.copy(turnTail = tail.copy(branchUnavailable = true))
}

private data class NativeHarnessEventEnvelope(
    val sequence: Long,
    val time: Long?,
    val type: String,
    val data: JsonObject,
    val surfaceOp: String?,
)

private fun nativeHarnessEventEnvelope(record: JsonObject): NativeHarnessEventEnvelope? {
    val event = record.objectValue("event") ?: record
    val type = event.string("type") ?: return null
    val data = event.objectValue("data") ?: return null
    val sequence = event.long("seq") ?: record.long("seq") ?: return null
    val surfaceOp = event["surfaceOp"]?.let { value ->
        value.jsonPrimitiveOrNull()?.contentOrNull ?: value.string("op")
    }
    return NativeHarnessEventEnvelope(sequence, event.long("time"), type, data, surfaceOp)
}

private fun projectNativeHarnessTurnTails(
    events: List<NativeHarnessEventEnvelope>,
): Map<Int, NativeHarnessTurnTailUi> {
    val turns = events.mapNotNull { event ->
        event.data.long("turn")?.toIntSafely()?.let { it to event }
    }.groupBy({ it.first }, { it.second })
    val projected = turns.mapNotNull { (turn, turnEvents) ->
        val end = turnEvents.lastOrNull { it.type == "turn/end" } ?: return@mapNotNull null
        val assistants = turnEvents.filter {
            it.type == "assistant/message" && it.surfaceOp == "append"
        }
            .mapNotNull assistantEvent@{ event ->
                val message = event.data.objectValue("message") ?: return@assistantEvent null
                val content = message["content"] ?: return@assistantEvent null
                val text = nativeHarnessAssistantText(content)
                if (text.isBlank()) null else event to text
            }
        val closing = assistants.maxByOrNull { it.first.sequence }
        val latestTranscriptSequence = turnEvents.asSequence()
            .filter { event ->
                event.type == "assistant/message" && event.surfaceOp == "append" || event.type == "tool/call" ||
                    event.type == "tool/result" && event.surfaceOp == "append" ||
                    event.type == "llm/retry" ||
                    (event.type == "turn/end" && event.data.objectValue("reason")?.string("kind") == "error")
            }
            .map { it.sequence }
            .maxOrNull()
        val usage = projectNativeHarnessTurnUsage(turnEvents)
        val timing = projectNativeHarnessTurnTiming(turnEvents, assistants)
        NativeHarnessTurnTailUi(
            turn = turn,
            tailSequence = end.sequence,
            branchSequence = closing?.first?.sequence,
            branchUnavailable = closing == null || latestTranscriptSequence != closing.first.sequence,
            text = closing?.second.orEmpty().bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT),
            usage = usage,
            timing = timing,
        ).let { turn to it }
    }.toMap()
    return projected
}

private fun projectNativeHarnessTurnTiming(
    events: List<NativeHarnessEventEnvelope>,
    assistants: List<Pair<NativeHarnessEventEnvelope, String>>,
): NativeHarnessTurnTimingUi? {
    val start = events.firstOrNull { it.type == "turn/start" }?.time
    val end = events.lastOrNull { it.type == "turn/end" }?.time
    val runMs = if (start != null && end != null) (end - start).coerceAtLeast(0L) else null
    val settled = assistants.mapNotNull { (event, _) ->
        val output = nativeHarnessUsageFromEvent(event)?.output ?: return@mapNotNull null
        val step = event.data.long("step")?.toIntSafely() ?: return@mapNotNull null
        val stepStart = events.firstOrNull {
            it.type == "step/start" && it.data.long("step")?.toIntSafely() == step
        }?.time
        val firstToken = events.asSequence()
            .filter { candidate ->
                candidate.data.long("turn")?.toIntSafely() == event.data.long("turn")?.toIntSafely() &&
                    candidate.data.long("step")?.toIntSafely() == step &&
                    candidate.type in setOf("assistant/live-chunk", "assistant/attempt", "assistant/message")
            }
            .mapNotNull(::nativeHarnessFirstTokenTime)
            .minOrNull()
        val ttft = if (stepStart != null && firstToken != null) (firstToken - stepStart).coerceAtLeast(0L) else null
        val decode = if (firstToken != null && event.time != null) (event.time - firstToken).coerceAtLeast(0L) else null
        Triple(step, output, TimingReading(ttft, decode))
    }
    val ttft = settled.minByOrNull { it.first }?.third?.ttftMs
    val timed = settled.filter { (it.third.decodeMs ?: 0L) > 0L }
    val tokensPerSecond = timed.takeIf { it.isNotEmpty() }?.let { readings ->
        val output = readings.sumOf { it.second.toDouble() }
        val decodeMs = readings.sumOf { it.third.decodeMs!!.toDouble() }
        if (decodeMs > 0.0) output / (decodeMs / 1_000.0) else null
    }
    if (runMs == null && ttft == null && tokensPerSecond == null) return null
    return NativeHarnessTurnTimingUi(runMs, tokensPerSecond, ttft)
}

private data class TimingReading(val ttftMs: Long?, val decodeMs: Long?)

private fun projectNativeHarnessTurnUsage(
    events: List<NativeHarnessEventEnvelope>,
): HarnessTokenUsageUi? {
    if (events.none { it.type == "turn/start" } || events.none { it.type == "turn/end" }) return null
    val attempts = mutableListOf<NativeHarnessRawUsage>()
    var turn: Int? = null
    var step: Int? = null
    var pending: NativeHarnessRawUsage? = null
    var sawInvalid = false
    var ended = false
    events.forEach { event ->
        val eventTurn = event.data.long("turn")?.toIntSafely()
        val eventStep = event.data.long("step")?.toIntSafely()
        when (event.type) {
            "turn/start" -> {
                if (turn != null || step != null || eventTurn == null) sawInvalid = true
                else turn = eventTurn
            }
            "turn/end" -> {
                if (eventTurn != turn || step != null || ended) sawInvalid = true
                ended = true
            }
            "step/start" -> {
                if (eventTurn != turn || step != null || ended) sawInvalid = true
                step = eventStep
                if (step == null) sawInvalid = true
                pending = null
            }
            "assistant/attempt" -> {
                if (eventTurn != turn || eventStep != step || step == null || ended) sawInvalid = true
                val usage = nativeHarnessUsageFromEvent(event)
                if (usage == null) sawInvalid = true else pending = usage
                if (usage != null) attempts += usage
            }
            "assistant/message" -> {
                if (eventTurn != turn || eventStep != step || step == null || ended) sawInvalid = true
                val usage = nativeHarnessUsageFromEvent(event)
                if (usage == null && pending == null) sawInvalid = true
                if (usage != null) {
                    if (pending != null && attempts.isNotEmpty()) attempts.removeAt(attempts.lastIndex)
                    attempts += usage
                    pending = usage
                }
            }
            "llm/retry" -> {
                if (eventTurn != turn || eventStep != step || step == null || ended) sawInvalid = true
                pending = null
            }
            "llm/retry-started" -> {
                if (eventTurn != turn || eventStep != step || step == null || ended) sawInvalid = true
                pending = null
            }
            "step/end" -> {
                if (eventTurn != turn || eventStep != step || step == null || ended) sawInvalid = true
                step = null
                pending = null
            }
        }
    }
    if (sawInvalid || !ended || attempts.isEmpty()) return null
    return aggregateNativeHarnessUsage(attempts)
}

private data class NativeHarnessRawUsage(
    val input: Long,
    val output: Long,
    val total: Long,
    val cacheRead: Long?,
    val cacheWrite: Long?,
    val reasoning: Long?,
)

private fun aggregateNativeHarnessUsage(
    attempts: List<NativeHarnessRawUsage>,
): HarnessTokenUsageUi? {
    fun safeSum(values: List<Long>): Long? {
        var total = 0L
        values.forEach { value ->
            total = total.checkedAdd(value) ?: return null
        }
        return total
    }
    val input = safeSum(attempts.map { it.input }) ?: return null
    val output = safeSum(attempts.map { it.output }) ?: return null
    val total = safeSum(attempts.map { it.total }) ?: return null
    val cacheRead = attempts.map { it.cacheRead }.takeIf { it.all { value -> value != null } }
        ?.map { it!! }?.let(::safeSum) ?: 0L
    val cacheWrite = attempts.map { it.cacheWrite }.takeIf { it.all { value -> value != null } }
        ?.map { it!! }?.let(::safeSum) ?: 0L
    val cacheMetricsKnown = attempts.all { it.cacheRead != null && it.cacheWrite != null }
    val reasoning = attempts.map { it.reasoning }.takeIf { it.all { value -> value != null } }
        ?.map { it!! }?.let(::safeSum)
    return HarnessTokenUsageUi(
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        cacheMetricsKnown = cacheMetricsKnown,
        reasoningTokens = reasoning,
        totalTokens = total,
    )
}

private fun nativeHarnessUsageFromEvent(event: NativeHarnessEventEnvelope): NativeHarnessRawUsage? {
    val usage = event.data["usage"]?.jsonObjectOrNull()
        ?: event.data.objectArray("stream").asReversed().firstNotNullOfOrNull { record ->
            val chunk = record.objectValue("chunk")
            if (record.string("type") == "chunk" && chunk?.string("type") == "usage") {
                chunk.objectValue("usage")
            } else null
        }
        ?: return null
    val input = usage.long("uncachedInputTokens") ?: usage.long("inputTokens") ?: return null
    val output = usage.long("outputTokens") ?: return null
    if (input < 0L || output < 0L) return null
    val cacheRead = if (usage.containsKey("cacheReadTokens")) {
        usage.long("cacheReadTokens")?.takeIf { it >= 0L } ?: return null
    } else null
    val cacheWrite = if (usage.containsKey("cacheWriteTokens")) {
        usage.long("cacheWriteTokens")?.takeIf { it >= 0L } ?: return null
    } else null
    val reasoning = if (usage.containsKey("reasoningTokens")) {
        usage.long("reasoningTokens")?.takeIf { it >= 0L && it <= output } ?: return null
    } else null
    val knownPrompt = listOfNotNull(input, cacheRead, cacheWrite).fold(0L) { total, value ->
        total.checkedAdd(value) ?: return null
    }
    val total = if (usage.containsKey("totalTokens")) {
        val exact = usage.long("totalTokens")?.takeIf { it >= 0L } ?: return null
        val exactPrompt = exact - output
        if (exactPrompt < 0L || exactPrompt < knownPrompt) return null
        if (cacheRead != null && cacheWrite != null && exactPrompt != knownPrompt) return null
        exact
    } else {
        if (cacheRead == null || cacheWrite == null) return null
        knownPrompt.checkedAdd(output) ?: return null
    }
    if (total < output) return null
    return NativeHarnessRawUsage(input, output, total, cacheRead, cacheWrite, reasoning)
}

private fun nativeHarnessFirstTokenTime(event: NativeHarnessEventEnvelope): Long? {
    val directChunk = event.data.objectValue("chunk")
    if (directChunk != null && nativeHarnessChunkCarriesToken(directChunk)) return event.time
    return event.data.objectArray("stream").mapNotNull(::nativeHarnessStreamRecordFirstTokenTime).minOrNull()
}

private fun nativeHarnessStreamRecordFirstTokenTime(record: JsonObject): Long? {
    val type = record.string("type")
    if (type == "chunk") {
        val time = record.long("time") ?: return null
        return record.objectValue("chunk")?.takeIf(::nativeHarnessChunkCarriesToken)?.let { time }
    }
    val time0 = record.long("time0") ?: return null
    return when (type) {
        "text-chunks", "reasoning-chunks" -> {
            val fragments = record["texts"]?.jsonArrayOrNull().orEmpty()
            fragments.indexOfFirst { it.jsonPrimitiveOrNull()?.contentOrNull?.isNotEmpty() == true }
                .takeIf { it >= 0 }?.let { index -> nativeHarnessPackedTime(record, time0, index) }
        }
        "tool-call-chunks" -> {
            if (record.containsKey("name")) time0
            else {
                val fragments = record["args"]?.jsonArrayOrNull().orEmpty()
                fragments.indexOfFirst { it.jsonPrimitiveOrNull()?.contentOrNull?.isNotEmpty() == true }
                    .takeIf { it >= 0 }?.let { index -> nativeHarnessPackedTime(record, time0, index) }
            }
        }
        else -> null
    }
}

private fun nativeHarnessPackedTime(record: JsonObject, time0: Long, index: Int): Long? {
    var time = time0
    val gaps = record["dt"]?.jsonArrayOrNull().orEmpty()
    repeat(index) { gapIndex ->
        val gap = gaps.getOrNull(gapIndex)?.jsonPrimitiveOrNull()?.longOrNull ?: return null
        if (gap < 0L) return null
        time = time.checkedAdd(gap) ?: return null
    }
    return time
}

private fun nativeHarnessChunkCarriesToken(chunk: JsonObject): Boolean = when (chunk.string("type")) {
    "text-delta", "reasoning-delta" -> chunk.string("text")?.isNotEmpty() == true
    "tool-call-delta" -> chunk.string("argumentsDelta")?.isNotEmpty() == true || chunk.containsKey("name")
    else -> false
}

private fun nativeHarnessAssistantText(value: JsonElement): String =
    value.jsonArrayOrNull().orEmpty().take(NativeHarnessStructuredTranscriptLimits.MAX_PARTS).mapNotNull { part ->
        val row = part.jsonObjectOrNull() ?: return@mapNotNull null
        if (row.string("type") != "text") return@mapNotNull null
        row.string("text")?.bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT)
    }.joinToString("").bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT)

private fun nativeHarnessMessageSource(event: NativeHarnessEventEnvelope): JsonObject? {
    if (event.type != "user/message") return null
    val message = event.data.objectValue("message") ?: event.data
    return message.objectValue("source")
}

/**
 * Mirrors the pinned SkillNameProjector: a direct authored message and its
 * contiguous skill-invocation context share one batch. Names are attached to
 * the authored message, so a /skill link is only enabled when that skill was
 * actually loaded for that message.
 */
private fun projectNativeHarnessSkillNames(
    events: List<NativeHarnessEventEnvelope>,
): Map<Long, Set<String>> {
    val assigned = mutableMapOf<Long, Set<String>>()
    val directMessages = mutableListOf<Long>()
    val names = linkedSetOf<String>()

    fun flush() {
        if (names.isNotEmpty()) {
            val batch = names.toSet()
            directMessages.forEach { sequence -> assigned[sequence] = batch }
        }
        directMessages.clear()
        names.clear()
    }

    events.sortedBy { it.sequence }.forEach { event ->
        when {
            event.type == "user/message" -> {
                when (nativeHarnessMessageSource(event)?.string("kind")) {
                    "skill-invocation" -> nativeHarnessMessageSource(event)
                        ?.string("name")
                        ?.boundedToken()
                        ?.let(names::add)
                    "user", null -> directMessages += event.sequence
                    else -> Unit
                }
            }
            event.type == "assistant/message" ||
                event.type == "assistant/live-chunk" ||
                event.type == "tool/call" ||
                event.type == "tool/result" && event.surfaceOp == "append" ||
                event.type == "llm/retry" ||
                event.type == "turn/end" ||
                event.type == "step/end" -> flush()
        }
    }
    flush()
    return assigned
}

private fun projectNativeHarnessLifecycleRows(
    events: List<NativeHarnessEventEnvelope>,
): List<NativeHarnessStructuredTranscriptItem> {
    val retries = projectNativeHarnessRetries(events)
    val compactions = projectNativeHarnessCompactions(events)
    return (retries.map { retry ->
        NativeHarnessStructuredTranscriptItem(
            id = "structured-retry-${retry.retryId}-${retry.attempts.first().sequence}",
            sequence = retry.attempts.first().sequence,
            role = HarnessTranscriptRole.SYSTEM,
            label = "retry",
            parts = emptyList(),
            retry = retry,
            isExpandable = true,
        )
    } + compactions.map { compaction ->
        NativeHarnessStructuredTranscriptItem(
            id = "structured-compaction-${compaction.compactionId ?: compaction.sequence}",
            sequence = compaction.sequence,
            role = HarnessTranscriptRole.SYSTEM,
            label = "compaction",
            parts = emptyList(),
            compaction = compaction,
            isExpandable = compaction.summary != null,
        )
    }).sortedBy { it.sequence }
}

private fun projectNativeHarnessRetries(
    events: List<NativeHarnessEventEnvelope>,
): List<NativeHarnessModelRetryUi> {
    val grouped = events.filter { it.type == "llm/retry" || it.type == "llm/retry-started" }
        .mapNotNull { event -> event.data.string("retryId")?.let { it to event } }
        .groupBy({ it.first }, { it.second })
    return grouped.mapNotNull { (retryId, retryEvents) ->
        val attempts = retryEvents.filter { it.type == "llm/retry" }.mapNotNull retryEvent@{ event ->
            val turn = event.data.long("turn")?.toIntSafely() ?: return@retryEvent null
            val step = event.data.long("step")?.toIntSafely() ?: return@retryEvent null
            val failure = event.data.objectValue("failure")
            val retry = event.data.long("retry")?.toIntSafely() ?: return@retryEvent null
            val started = retryEvents.any { other ->
                other.type == "llm/retry-started" && other.data.long("retry") == retry.toLong()
            }
            val closed = events.any { other ->
                other.sequence > event.sequence &&
                    (other.type == "turn/end" || other.type == "step/end") &&
                    other.data.long("turn") == turn.toLong() && other.data.long("step") == step.toLong()
            }
            NativeHarnessRetryAttemptUi(
                retryId = retryId,
                turn = turn,
                step = step,
                retry = retry,
                mode = event.data.string("mode") ?: "normal",
                maxRetries = event.data.long("maxRetries")?.toIntSafely(),
                delayMs = event.data.long("delayMs")?.coerceAtLeast(0L) ?: 0L,
                provider = event.data.string("provider")?.boundedToken(),
                policyKey = event.data.string("policyKey")?.boundedToken(),
                failureCode = failure?.string("code")?.boundedToken(),
                failureMessage = failure?.string("message")?.bounded(MAX_RETRY_FAILURE) ?: "",
                state = when {
                    started -> NativeHarnessRetryState.STARTED
                    closed -> NativeHarnessRetryState.CANCELLED
                    else -> NativeHarnessRetryState.SCHEDULED
                },
                sequence = event.sequence,
            )
        }.sortedBy { it.sequence }
        attempts.takeIf { it.isNotEmpty() }?.let {
            NativeHarnessModelRetryUi(retryId, it.first().turn, it.first().step, it)
        }
    }
}

private fun projectNativeHarnessCompactions(
    events: List<NativeHarnessEventEnvelope>,
): List<NativeHarnessCompactionUi> {
    val ids = events.filter {
        it.type == "compaction/start" || it.type == "compaction/summary" ||
            it.type == "compaction/end" || nativeHarnessIsCompactionCheckpoint(it)
    }.mapNotNull { it.data.string("compactionId") ?: nativeHarnessCheckpointId(it) }.distinct()
    return ids.mapNotNull { id ->
        val related = events.filter { event ->
            event.data.string("compactionId") == id || nativeHarnessCheckpointId(event) == id
        }
        val start = related.firstOrNull { it.type == "compaction/start" }
        val summaryEvent = related.firstOrNull { it.type == "compaction/summary" }
        val end = related.firstOrNull { it.type == "compaction/end" }
        val checkpoint = related.firstOrNull(::nativeHarnessIsCompactionCheckpoint)
        val summary = summaryEvent?.data?.get("summary")?.let(::nativeHarnessTextBlocks)
            ?.takeIf { it.isNotBlank() }?.bounded(MAX_COMPACTION_SUMMARY)
        val shadowedItems = summaryEvent?.data?.get("shadowedSeqs")?.jsonArrayOrNull()
            ?.takeIf { values -> values.all { it.jsonPrimitiveOrNull()?.longOrNull?.let { value -> value >= 0L } == true } }
            ?.size
        val shadowedTokens = summaryEvent?.data?.long("shadowedTokenCount")?.takeIf { it >= 0L }
        val sequence = checkpoint?.sequence ?: summaryEvent?.sequence ?: end?.sequence ?: start?.sequence
            ?: return@mapNotNull null
        NativeHarnessCompactionUi(
            compactionId = id,
            turn = (checkpoint ?: summaryEvent ?: start)?.data?.long("turn")?.toIntSafely(),
            sequence = sequence,
            checkpointSequence = checkpoint?.sequence,
            summary = summary,
            shadowedItemCount = shadowedItems,
            shadowedTokenCount = shadowedTokens,
            state = when {
                end != null && end.data.containsKey("error") -> NativeHarnessCompactionState.FAILED
                end != null -> NativeHarnessCompactionState.COMPLETED
                summaryEvent != null -> NativeHarnessCompactionState.SUMMARIZED
                start != null -> NativeHarnessCompactionState.STARTED
                else -> NativeHarnessCompactionState.FAILED
            },
        )
    }
}

private fun nativeHarnessIsCompactionCheckpoint(event: NativeHarnessEventEnvelope): Boolean =
    event.type == "user/message" && nativeHarnessCheckpointId(event) != null

private fun nativeHarnessCheckpointId(event: NativeHarnessEventEnvelope): String? {
    if (event.type != "user/message" || event.surfaceOp == "append" || event.surfaceOp == null) return null
    val message = event.data.objectValue("message") ?: event.data
    val source = message.objectValue("source") ?: return null
    return source.string("compactionId")?.takeIf { source.string("kind") == "plugin" && source.string("plugin") == "compact" }
}

private fun nativeHarnessTextBlocks(value: JsonElement): String =
    value.jsonArrayOrNull().orEmpty().take(NativeHarnessStructuredTranscriptLimits.MAX_PARTS).mapNotNull { block ->
        block.jsonObjectOrNull()?.takeIf { it.string("type") == "text" }
            ?.string("text")?.bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT)
    }.joinToString("").bounded(MAX_COMPACTION_SUMMARY)

private fun Long.toIntSafely(): Int? = takeIf { it in 0L..Int.MAX_VALUE }?.toInt()

private fun Long.checkedAdd(other: Long): Long? =
    if (other > 0L && this > Long.MAX_VALUE - other) null else this + other

private fun String.bounded(limit: Int): String = if (length <= limit) this else take(limit - 1) + "…"

private fun String.boundedToken(): String = trim().take(4_000).takeIf { it.isNotBlank() } ?: "?"

private const val MAX_RETRY_FAILURE = 4_000
private const val MAX_COMPACTION_SUMMARY = 24_000
