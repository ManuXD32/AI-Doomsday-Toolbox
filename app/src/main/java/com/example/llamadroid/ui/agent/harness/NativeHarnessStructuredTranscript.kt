package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Bounds for structured transcript parsing and the expanded card body. */
internal object NativeHarnessStructuredTranscriptLimits {
    const val MAX_ITEMS = 600
    const val MAX_INPUT_RECORDS = MAX_ITEMS * 2
    const val MAX_PARTS = 32
    const val MAX_TEXT = 24_000
    const val MAX_ARGUMENTS = 8_000
    const val MAX_PREVIEW = 4_000
    const val MAX_ROW_PREVIEW_CHARS = 24_000
    const val MAX_RESULT_PREVIEW_CHARS = 16_000
    const val MAX_JSON_DEPTH = 4
    const val MAX_DETAIL_JSON_DEPTH = 12
    const val MAX_JSON_FIELDS = 16
    const val MAX_DETAIL_WIRE_CHARS = 1_048_576
    const val MAX_DETAIL_PARTS = 512
    const val MAX_DETAIL_PAGE_CHARS = 24_000
    const val MAX_DETAIL_PAGE_LINES = 32
}

enum class NativeHarnessStructuredToolStatus {
    RUNNING,
    COMPLETED,
    ERROR,
}

/** Metadata only; attachment bytes remain behind the existing authenticated viewer. */
data class NativeHarnessTranscriptAttachment(
    val attachmentId: String,
    val name: String?,
    val mediaType: String?,
    val bytes: Long?,
    val width: Int? = null,
    val height: Int? = null,
    /** The current Session attachment RPC serves durable images, not files. */
    val canOpen: Boolean,
)

sealed interface NativeHarnessStructuredTranscriptPart {
    data class Text(val value: String) : NativeHarnessStructuredTranscriptPart
    data class Reasoning(val value: String) : NativeHarnessStructuredTranscriptPart
    data class Image(val attachment: NativeHarnessTranscriptAttachment) : NativeHarnessStructuredTranscriptPart
    data class File(val attachment: NativeHarnessTranscriptAttachment) : NativeHarnessStructuredTranscriptPart
    data class Tool(
        val callId: String,
        val name: String,
        val arguments: String,
        val status: NativeHarnessStructuredToolStatus,
        val result: List<NativeHarnessStructuredTranscriptPart> = emptyList(),
        val errorCode: String? = null,
    ) : NativeHarnessStructuredTranscriptPart
    data class Opaque(val type: String, val preview: String) : NativeHarnessStructuredTranscriptPart
    data class Truncated(val omitted: Int) : NativeHarnessStructuredTranscriptPart
}

sealed interface NativeHarnessTranscriptDetailRef {
    val key: String

    data class SessionEvents(val sequences: List<Long>) : NativeHarnessTranscriptDetailRef {
        override val key: String = sequences.joinToString(",")
    }
}

enum class NativeHarnessTranscriptDetailLineKind {
    TEXT,
    REASONING,
    ARGUMENTS,
    RESULT,
    OPAQUE,
}

data class NativeHarnessTranscriptDetailLine(
    val kind: NativeHarnessTranscriptDetailLineKind,
    val text: String,
)

data class NativeHarnessStructuredTranscriptDetailPage(
    val reference: NativeHarnessTranscriptDetailRef,
    val pageIndex: Int,
    val pageCount: Int,
    val lines: List<NativeHarnessTranscriptDetailLine> = emptyList(),
    val errorCode: String? = null,
) {
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex + 1 < pageCount
}

data class NativeHarnessStructuredTranscriptItem(
    val id: String,
    val sequence: Long,
    val role: HarnessTranscriptRole,
    val label: String? = null,
    val messageId: String? = null,
    val parts: List<NativeHarnessStructuredTranscriptPart>,
    val detailRef: NativeHarnessTranscriptDetailRef? = null,
    /** Durable turn coordinates used by copy/branch and inline skill links. */
    val turn: Int? = null,
    val step: Int? = null,
    val skillNames: Set<String> = emptySet(),
    val surfaceOp: String? = null,
    val sourceKind: String? = null,
    /** Source name retained so incremental skill batches can be reprojected. */
    val sourceName: String? = null,
    /** Present only on the final text-bearing assistant row of a closed turn. */
    val turnTail: NativeHarnessTurnTailUi? = null,
    /** Special lifecycle rows are kept separate from ordinary message parts. */
    val retry: NativeHarnessModelRetryUi? = null,
    val compaction: NativeHarnessCompactionUi? = null,
    /** Metadata-only carrier used to finish live turn usage and timing. */
    val turnEvidence: List<NativeHarnessTurnEvidenceUi> = emptyList(),
    val turnEvidenceTruncated: Boolean = false,
    val isMetadataOnly: Boolean = false,
    val isExpandable: Boolean = detailRef != null || parts.any {
        it is NativeHarnessStructuredTranscriptPart.Tool || it is NativeHarnessStructuredTranscriptPart.Opaque
    } || retry != null || compaction?.summary != null,
)

/**
 * Correlates durable tool/call and tool/result events while preserving every
 * attachment as metadata. Unknown content remains a bounded opaque preview so
 * a plugin cannot turn one transcript row into an unbounded Compose tree.
 */
internal fun parseNativeHarnessStructuredTranscript(
    records: List<JsonObject>,
): List<NativeHarnessStructuredTranscriptItem> {
    val boundedRecords = records.takeLast(NativeHarnessStructuredTranscriptLimits.MAX_INPUT_RECORDS)
    val rows = ArrayList<NativeHarnessStructuredTranscriptItem>(
        NativeHarnessStructuredTranscriptLimits.MAX_ITEMS,
    )
    val toolRows = mutableMapOf<String, Int>()
    boundedRecords
        .forEachIndexed { fallbackSequence, record ->
            val event = record.objectValue("event") ?: record
            val type = event.string("type") ?: return@forEachIndexed
            val data = event.objectValue("data") ?: return@forEachIndexed
            val sequence = event.long("seq") ?: record.long("seq") ?: fallbackSequence.toLong()
            when (type) {
                "tool/call" -> {
                    val callId = data.string("callId")?.boundedToken() ?: return@forEachIndexed
                    val name = data.string("name")?.boundedToken() ?: "tool"
                    val arguments = data.string("arguments").orEmpty().bounded(
                        NativeHarnessStructuredTranscriptLimits.MAX_ARGUMENTS,
                    )
                    val row = NativeHarnessStructuredTranscriptItem(
                        id = "structured-tool-$callId",
                        sequence = sequence,
                        role = HarnessTranscriptRole.TOOL,
                        label = name,
                        parts = listOf(
                            NativeHarnessStructuredTranscriptPart.Tool(
                                callId = callId,
                                name = name,
                                arguments = arguments,
                                status = NativeHarnessStructuredToolStatus.RUNNING,
                            ),
                        ),
                        detailRef = NativeHarnessTranscriptDetailRef.SessionEvents(listOf(sequence)),
                        isExpandable = true,
                    )
                    appendStructuredRow(rows, toolRows, callId, row)
                }

                "tool/result" -> {
                    val message = data.objectValue("message")
                    val source = message?.objectValue("source")
                    val resultBlock = message?.objectValueArray("content")
                        ?.firstOrNull { it.string("type") == "tool-result" }
                    val callId = source?.string("callId")?.boundedToken()
                        ?: resultBlock?.string("toolCallId")?.boundedToken()
                        ?: return@forEachIndexed
                    val resultParts = parseStructuredContent(
                        resultBlock?.get("content") ?: message?.get("content"),
                        NativeHarnessStructuredTranscriptLimits.MAX_RESULT_PREVIEW_CHARS,
                    )
                    val error = data.objectValue("error")
                    val isError = data.boolean("isError") == true ||
                        resultBlock?.boolean("isError") == true ||
                        error != null
                    val index = toolRows[callId]
                    if (index != null && index in rows.indices) {
                        val existing = rows[index]
                        val tool = existing.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Tool>().firstOrNull()
                        if (tool != null) {
                            rows[index] = existing.copy(
                                sequence = sequence,
                                parts = listOf(
                                    tool.copy(
                                        status = if (isError) NativeHarnessStructuredToolStatus.ERROR
                                        else NativeHarnessStructuredToolStatus.COMPLETED,
                                        result = resultParts,
                                        errorCode = error?.string("code")?.boundedToken(),
                                    ),
                                ),
                                detailRef = when (val reference = existing.detailRef) {
                                    is NativeHarnessTranscriptDetailRef.SessionEvents ->
                                        NativeHarnessTranscriptDetailRef.SessionEvents(
                                            (reference.sequences + sequence).distinct().takeLast(2),
                                        )
                                    else -> NativeHarnessTranscriptDetailRef.SessionEvents(listOf(sequence))
                                },
                            )
                            toolRows.remove(callId)
                            return@forEachIndexed
                        }
                    }
                    val fallbackName = resultBlock?.string("name")?.boundedToken() ?: "tool"
                    val fallbackTool = NativeHarnessStructuredTranscriptPart.Tool(
                        callId = callId,
                        name = fallbackName,
                        arguments = "",
                        status = if (isError) NativeHarnessStructuredToolStatus.ERROR
                        else NativeHarnessStructuredToolStatus.COMPLETED,
                        result = resultParts,
                        errorCode = error?.string("code")?.boundedToken(),
                    )
                    appendStructuredRow(
                        rows,
                        toolRows,
                        callId,
                        NativeHarnessStructuredTranscriptItem(
                            id = "structured-tool-result-$callId-$sequence",
                            sequence = sequence,
                            role = HarnessTranscriptRole.TOOL,
                            label = fallbackName,
                            parts = listOf(fallbackTool),
                            detailRef = NativeHarnessTranscriptDetailRef.SessionEvents(listOf(sequence)),
                            isExpandable = true,
                        ),
                    )
                }

                "assistant/message", "user/message", "system/message" -> {
                    val message = data.objectValue("message") ?: data
                    val content = message["content"] ?: data["content"]
                    val parts = parseStructuredContent(content)
                    if (parts.isEmpty()) return@forEachIndexed
                    val role = when (type) {
                        "assistant/message" -> HarnessTranscriptRole.ASSISTANT
                        "user/message" -> HarnessTranscriptRole.USER
                        else -> HarnessTranscriptRole.SYSTEM
                    }
                    val source = message.objectValue("source")
                    val label = when {
                        role == HarnessTranscriptRole.ASSISTANT -> source?.string("model")?.boundedToken()
                        source?.string("kind") == "plugin" -> source.string("plugin")?.boundedToken()
                        else -> null
                    }
                    val messageId = message.string("id")?.boundedToken()
                    val surfaceOp = event["surfaceOp"]?.let { value ->
                        value.jsonPrimitiveOrNull()?.contentOrNull ?: value.string("op")
                    }
                    appendStructuredRow(
                        rows,
                        toolRows,
                        null,
                        NativeHarnessStructuredTranscriptItem(
                            id = "structured-event-$sequence",
                            sequence = sequence,
                            role = role,
                            label = label,
                            messageId = messageId,
                            parts = parts,
                            detailRef = NativeHarnessTranscriptDetailRef.SessionEvents(listOf(sequence)),
                            surfaceOp = surfaceOp,
                            sourceKind = source?.string("kind")?.boundedToken(),
                            sourceName = source?.string("name")?.boundedToken(),
                            isExpandable = true,
                        ),
                    )
                }
            }
        }
    return decorateNativeHarnessStructuredRows(rows, boundedRecords)
}

/**
 * Applies one live or paged event without retaining its raw JSON. Tool results
 * are correlated with the already parsed running call by call id; all other
 * rows are appended with the same bounded item cap as a full parse.
 */
internal fun mergeNativeHarnessStructuredTranscript(
    current: List<NativeHarnessStructuredTranscriptItem>,
    record: JsonObject,
): List<NativeHarnessStructuredTranscriptItem> {
    val event = record.objectValue("event") ?: record
    val sequence = event.long("seq") ?: record.long("seq")
    if (sequence != null && current.any { item ->
            (item.detailRef as? NativeHarnessTranscriptDetailRef.SessionEvents)
                ?.sequences?.contains(sequence) == true
        }
    ) return current
    val incoming = parseNativeHarnessStructuredTranscript(listOf(record)).filterNot { row ->
        // A single turn/end cannot see its closing message. Use the already folded
        // message instead of appending a second empty tail at the boundary seq.
        row.turnTail != null && row.detailRef == null && current.any { existing ->
            existing.turn == row.turn && existing.role == HarnessTranscriptRole.ASSISTANT &&
                existing.surfaceOp == "append" && existing.detailRef != null &&
                existing.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Text>()
                    .any { it.value.isNotBlank() }
        }
    }
    if (incoming.isEmpty()) {
        return mergeNativeHarnessTurnDetails(
            reprojectNativeHarnessLiveSkillBatch(current, record),
            record,
        )
    }
    val type = event.string("type")
    val incomingRow = incoming.singleOrNull()
    val incomingTool = incomingRow?.parts
        ?.filterIsInstance<NativeHarnessStructuredTranscriptPart.Tool>()
        ?.singleOrNull()
    if (type == "tool/result" && incomingRow != null && incomingTool != null) {
        val index = current.indexOfFirst { row ->
            row.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Tool>()
                .any { it.callId == incomingTool.callId }
        }
        if (index >= 0) {
            val existing = current[index]
            val existingTool = existing.parts
                .filterIsInstance<NativeHarnessStructuredTranscriptPart.Tool>()
                .firstOrNull()
            if (existingTool != null) {
                val references = mergeTranscriptDetailReferences(existing.detailRef, incomingRow.detailRef)
                return mergeNativeHarnessTurnDetails(current.toMutableList().also { rows ->
                    rows[index] = existing.copy(
                        sequence = incomingRow.sequence,
                        parts = listOf(
                            incomingTool.copy(
                                name = existingTool.name,
                                arguments = existingTool.arguments,
                            ),
                        ),
                        detailRef = references,
                        isExpandable = true,
                    )
                }, record)
            }
        }
    }
    val rows = current.toMutableList()
    incoming.forEach { row ->
        if (rows.none { it.id == row.id }) rows += row
    }
    return mergeNativeHarnessTurnDetails(
        reprojectNativeHarnessLiveSkillBatch(
            rows.takeLast(NativeHarnessStructuredTranscriptLimits.MAX_ITEMS),
            record,
        ),
        record,
    )
}

/**
 * Reprojects the small skill-name batch around one live event.  A skill
 * injection can arrive after its authored user row, so decorating only the
 * newly parsed row leaves the already rendered bubble without its genuine
 * `/skill` target.  The bounded resident rows are cheap to scan and retain
 * the upstream rule that non-skill context is transparent while assistant,
 * tool, retry, step, and turn events close a batch.
 */
private fun reprojectNativeHarnessLiveSkillBatch(
    rows: List<NativeHarnessStructuredTranscriptItem>,
    record: JsonObject,
): List<NativeHarnessStructuredTranscriptItem> {
    val event = record.objectValue("event") ?: record
    val eventType = event.string("type") ?: return rows
    val sequence = event.long("seq") ?: record.long("seq")
    val eventEntry = when (eventType) {
        "user/message" -> {
            val message = event.objectValue("data")?.let { data ->
                data.objectValue("message") ?: data
            }
            val source = message?.objectValue("source")
            NativeHarnessLiveSkillEntry(
                sequence = sequence,
                rowIndex = null,
                kind = when (source?.string("kind")) {
                    "skill-invocation" -> NativeHarnessLiveSkillEntryKind.SKILL
                    "user", null -> NativeHarnessLiveSkillEntryKind.MESSAGE
                    else -> NativeHarnessLiveSkillEntryKind.TRANSPARENT
                },
                name = source?.string("name")?.boundedToken(),
            )
        }
        "assistant/message", "assistant/live-chunk", "tool/call", "llm/retry",
        "turn/end", "step/end" -> NativeHarnessLiveSkillEntry(
            sequence = sequence,
            rowIndex = null,
            kind = NativeHarnessLiveSkillEntryKind.BOUNDARY,
            name = null,
        )
        "tool/result" -> event.string("surfaceOp")?.takeIf { it == "append" }?.let {
            NativeHarnessLiveSkillEntry(
                sequence = sequence,
                rowIndex = null,
                kind = NativeHarnessLiveSkillEntryKind.BOUNDARY,
                name = null,
            )
        }
        else -> null
    }
    if (eventEntry == null) return rows

    val entries = rows.mapIndexed { index, row ->
        val kind = if (row.role != HarnessTranscriptRole.USER) {
            NativeHarnessLiveSkillEntryKind.BOUNDARY
        } else {
            when (row.sourceKind) {
                "skill-invocation" -> NativeHarnessLiveSkillEntryKind.SKILL
                "user", null -> NativeHarnessLiveSkillEntryKind.MESSAGE
                else -> NativeHarnessLiveSkillEntryKind.TRANSPARENT
            }
        }
        NativeHarnessLiveSkillEntry(
            sequence = row.sequence,
            rowIndex = index,
            kind = kind,
            name = row.sourceName,
        )
    }.toMutableList()
    if (sequence == null || entries.none { it.sequence == sequence }) entries += eventEntry
    entries.sortWith(compareBy<NativeHarnessLiveSkillEntry> { it.sequence ?: Long.MAX_VALUE }
        .thenBy { it.rowIndex == null })

    val namesByRow = mutableMapOf<Int, Set<String>>()
    val directRows = mutableListOf<Int>()
    val names = linkedSetOf<String>()

    fun flushBatch() {
        val batch = names.toSet()
        directRows.forEach { rowIndex -> namesByRow[rowIndex] = batch }
        directRows.clear()
        names.clear()
    }

    entries.forEach { entry ->
        when (entry.kind) {
            NativeHarnessLiveSkillEntryKind.MESSAGE -> entry.rowIndex?.let(directRows::add)
            NativeHarnessLiveSkillEntryKind.SKILL -> entry.name?.let(names::add)
            NativeHarnessLiveSkillEntryKind.TRANSPARENT -> Unit
            NativeHarnessLiveSkillEntryKind.BOUNDARY -> flushBatch()
        }
    }
    flushBatch()

    return rows.mapIndexed { index, row ->
        when {
            row.role != HarnessTranscriptRole.USER -> row
            row.sourceKind == "user" || row.sourceKind == null ->
                row.copy(skillNames = namesByRow[index].orEmpty())
            else -> row.copy(skillNames = emptySet())
        }
    }
}

private enum class NativeHarnessLiveSkillEntryKind {
    MESSAGE,
    SKILL,
    TRANSPARENT,
    BOUNDARY,
}

private data class NativeHarnessLiveSkillEntry(
    val sequence: Long?,
    val rowIndex: Int?,
    val kind: NativeHarnessLiveSkillEntryKind,
    val name: String?,
)

internal fun mergeNativeHarnessStructuredHistory(
    current: List<NativeHarnessStructuredTranscriptItem>,
    records: List<JsonObject>,
): List<NativeHarnessStructuredTranscriptItem> = records.fold(current) { rows, record ->
    mergeNativeHarnessStructuredTranscript(rows, record)
}

private fun mergeTranscriptDetailReferences(
    first: NativeHarnessTranscriptDetailRef?,
    second: NativeHarnessTranscriptDetailRef?,
): NativeHarnessTranscriptDetailRef? {
    val sequences = listOfNotNull(first, second)
        .flatMap { (it as? NativeHarnessTranscriptDetailRef.SessionEvents)?.sequences.orEmpty() }
        .distinct()
        .takeLast(2)
    return sequences.takeIf { it.isNotEmpty() }
        ?.let { NativeHarnessTranscriptDetailRef.SessionEvents(it) }
}

private fun appendStructuredRow(
    rows: MutableList<NativeHarnessStructuredTranscriptItem>,
    toolRows: MutableMap<String, Int>,
    callId: String?,
    row: NativeHarnessStructuredTranscriptItem,
) {
    rows += row
    if (callId != null) toolRows[callId] = rows.lastIndex
    while (rows.size > NativeHarnessStructuredTranscriptLimits.MAX_ITEMS) {
        rows.removeAt(0)
        toolRows.entries.toList().forEach { entry ->
            if (entry.value == 0) toolRows.remove(entry.key)
            else toolRows[entry.key] = entry.value - 1
        }
    }
}

private fun parseStructuredContent(
    value: JsonElement?,
    budget: Int = NativeHarnessStructuredTranscriptLimits.MAX_ROW_PREVIEW_CHARS,
    depth: Int = 0,
): List<NativeHarnessStructuredTranscriptPart> {
    val array = value?.jsonArrayOrNull() ?: return emptyList()
    if (array.isEmpty()) return emptyList()
    if (depth > NativeHarnessStructuredTranscriptLimits.MAX_JSON_DEPTH) {
        return listOf(
            NativeHarnessStructuredTranscriptPart.Opaque(
                type = "nested-content",
                preview = boundedJsonPreview(value),
            ),
        )
    }
    val parts = mutableListOf<NativeHarnessStructuredTranscriptPart>()
    var used = 0
    var omitted = 0
    val parseLimit = array.size.coerceAtMost(NativeHarnessStructuredTranscriptLimits.MAX_PARTS)
    for (index in 0 until parseLimit) {
        val part = parseStructuredPart(array[index], depth) ?: continue
        val cost = structuredPartPreviewChars(part)
        val previewTruncated = structuredPartPreviewIsTruncated(array[index])
        if (cost > (budget - used).coerceAtLeast(0)) {
            omitted = array.size - index
            break
        }
        parts += part
        used += cost
        if (previewTruncated) {
            omitted = array.size - index
            break
        }
    }
    if (omitted == 0 && array.size > parseLimit) {
        omitted = array.size - parseLimit
    }
    if (omitted > 0) {
        parts += NativeHarnessStructuredTranscriptPart.Truncated(omitted)
    }
    return parts
}

private fun structuredPartPreviewChars(part: NativeHarnessStructuredTranscriptPart): Int = when (part) {
    is NativeHarnessStructuredTranscriptPart.Text -> part.value.length
    is NativeHarnessStructuredTranscriptPart.Reasoning -> part.value.length
    is NativeHarnessStructuredTranscriptPart.Image,
    is NativeHarnessStructuredTranscriptPart.File -> 256
    is NativeHarnessStructuredTranscriptPart.Tool -> {
        (part.arguments.length + part.result.sumOf(::structuredPartPreviewChars) + 128)
            .coerceAtMost(NativeHarnessStructuredTranscriptLimits.MAX_ROW_PREVIEW_CHARS + 1)
    }
    is NativeHarnessStructuredTranscriptPart.Opaque -> part.type.length + part.preview.length + 32
    is NativeHarnessStructuredTranscriptPart.Truncated -> 32
}

private fun structuredPartPreviewIsTruncated(value: JsonElement): Boolean {
    val row = value.jsonObjectOrNull() ?: return false
    return when (row.string("type")) {
        "text", "reasoning" -> row.string("text")?.length
            ?.let { it > NativeHarnessStructuredTranscriptLimits.MAX_TEXT } == true
        "tool-call" -> row.string("arguments")?.length
            ?.let { it > NativeHarnessStructuredTranscriptLimits.MAX_ARGUMENTS } == true
        else -> false
    }
}

/**
 * Parse one exact Session event window for the on-demand detail viewer. The
 * caller fetches the event by sequence through the authenticated bounded
 * session source available for that event; this function retains only one
 * bounded page and never stores the original event.
 */
internal fun parseNativeHarnessTranscriptDetailPage(
    reference: NativeHarnessTranscriptDetailRef,
    records: List<JsonObject>,
    requestedPage: Int,
): NativeHarnessStructuredTranscriptDetailPage {
    if (requestedPage < 0) return NativeHarnessStructuredTranscriptDetailPage(
        reference = reference,
        pageIndex = 0,
        pageCount = 1,
        errorCode = "TRANSCRIPT_DETAIL_PAGE_INVALID",
    )
    val expected = (reference as? NativeHarnessTranscriptDetailRef.SessionEvents)?.sequences.orEmpty()
    if (expected.isEmpty()) return NativeHarnessStructuredTranscriptDetailPage(
        reference = reference,
        pageIndex = 0,
        pageCount = 1,
        errorCode = "TRANSCRIPT_DETAIL_REFERENCE_INVALID",
    )
    val selected = records.mapNotNull { record ->
        val event = record.objectValue("event") ?: record
        val sequence = event.long("seq") ?: record.long("seq") ?: return@mapNotNull null
        if (sequence in expected) sequence to event else null
    }.sortedBy { it.first }
    if (selected.map { it.first }.distinct().size != expected.distinct().size) {
        return NativeHarnessStructuredTranscriptDetailPage(
            reference = reference,
            pageIndex = 0,
            pageCount = 1,
            errorCode = "TRANSCRIPT_DETAIL_EVENT_MISSING",
        )
    }
    val estimatedWireChars = selected.fold(0) { total, (_, event) ->
        (total + estimateJsonChars(
            event,
            NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_WIRE_CHARS - total,
            maxDepth = NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_JSON_DEPTH,
        ))
            .coerceAtMost(NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_WIRE_CHARS + 1)
    }
    if (estimatedWireChars > NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_WIRE_CHARS) {
        return NativeHarnessStructuredTranscriptDetailPage(
            reference = reference,
            pageIndex = 0,
            pageCount = 1,
            errorCode = "TRANSCRIPT_DETAIL_TOO_LARGE",
        )
    }
    val lines = mutableListOf<NativeHarnessTranscriptDetailLine>()
    try {
        selected.forEach { (_, event) -> appendDetailEvent(event, lines) }
    } catch (_: NativeHarnessDetailLimitException) {
        return NativeHarnessStructuredTranscriptDetailPage(
            reference = reference,
            pageIndex = 0,
            pageCount = 1,
            errorCode = "TRANSCRIPT_DETAIL_TOO_MANY_PARTS",
        )
    }
    val pages = paginateDetailLines(lines)
    val pageIndex = requestedPage.coerceIn(0, pages.lastIndex)
    return NativeHarnessStructuredTranscriptDetailPage(
        reference = reference,
        pageIndex = pageIndex,
        pageCount = pages.size,
        lines = pages[pageIndex],
    )
}

private class NativeHarnessDetailLimitException : RuntimeException()

private fun appendDetailEvent(
    event: JsonObject,
    lines: MutableList<NativeHarnessTranscriptDetailLine>,
    depth: Int = 0,
) {
    if (lines.size >= NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PARTS) {
        throw NativeHarnessDetailLimitException()
    }
    val type = event.string("type") ?: return
    val data = event.objectValue("data") ?: return
    when (type) {
        "tool/call" -> lines += NativeHarnessTranscriptDetailLine(
            NativeHarnessTranscriptDetailLineKind.ARGUMENTS,
            data.string("arguments").orEmpty(),
        )
        "tool/result" -> {
            val message = data.objectValue("message")
            val resultBlock = message?.objectValueArray("content")
                ?.firstOrNull { it.string("type") == "tool-result" }
            appendDetailContent(
                resultBlock?.get("content") ?: message?.get("content"),
                lines,
                NativeHarnessTranscriptDetailLineKind.RESULT,
                depth,
            )
            data.objectValue("error")?.let { error ->
                val errorText = listOfNotNull(
                    error.string("name") ?: "error",
                    error.string("code"),
                    error.string("reason")
                        ?.bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT),
                ).joinToString(": ")
                if (errorText.isNotBlank()) {
                    lines += NativeHarnessTranscriptDetailLine(
                        NativeHarnessTranscriptDetailLineKind.RESULT,
                        errorText,
                    )
                }
            }
            data["meta"]?.let { meta ->
                lines += NativeHarnessTranscriptDetailLine(
                    NativeHarnessTranscriptDetailLineKind.OPAQUE,
                    boundedJsonPreview(meta),
                )
            }
        }
        "assistant/message", "user/message", "system/message" -> {
            val message = data.objectValue("message") ?: data
            appendDetailContent(message["content"] ?: data["content"], lines, null, depth)
        }
    }
}

private fun appendDetailContent(
    value: JsonElement?,
    lines: MutableList<NativeHarnessTranscriptDetailLine>,
    resultKind: NativeHarnessTranscriptDetailLineKind?,
    depth: Int,
) {
    if (depth > NativeHarnessStructuredTranscriptLimits.MAX_JSON_DEPTH) {
        throw NativeHarnessDetailLimitException()
    }
    val array = value?.jsonArrayOrNull() ?: return
    if (array.size > NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PARTS) {
        throw NativeHarnessDetailLimitException()
    }
    array.forEach { raw ->
        if (lines.size >= NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PARTS) {
            throw NativeHarnessDetailLimitException()
        }
        val row = raw.jsonObjectOrNull()
        when (row?.string("type")) {
            "text" -> lines += NativeHarnessTranscriptDetailLine(
                resultKind ?: NativeHarnessTranscriptDetailLineKind.TEXT,
                row.string("text").orEmpty(),
            )
            "reasoning" -> lines += NativeHarnessTranscriptDetailLine(
                NativeHarnessTranscriptDetailLineKind.REASONING,
                row.string("text").orEmpty(),
            )
            "image", "file" -> {
                val attachment = row.objectValue("attachment")
                val name = attachment?.string("name") ?: attachment?.string("attachmentId") ?: "attachment"
                val mediaType = attachment?.string("mediaType")
                val bytes = attachment?.long("bytes")
                lines += NativeHarnessTranscriptDetailLine(
                    resultKind ?: NativeHarnessTranscriptDetailLineKind.TEXT,
                    listOfNotNull(name, mediaType, bytes?.let { "$it bytes" }).joinToString(" · "),
                )
            }
            "tool-call" -> lines += NativeHarnessTranscriptDetailLine(
                NativeHarnessTranscriptDetailLineKind.ARGUMENTS,
                row.string("arguments").orEmpty(),
            )
            "tool-result" -> appendDetailContent(
                row["content"],
                lines,
                resultKind ?: NativeHarnessTranscriptDetailLineKind.RESULT,
                depth + 1,
            )
            else -> lines += NativeHarnessTranscriptDetailLine(
                NativeHarnessTranscriptDetailLineKind.OPAQUE,
                raw.toString(),
            )
        }
    }
}

private fun paginateDetailLines(
    lines: List<NativeHarnessTranscriptDetailLine>,
): List<List<NativeHarnessTranscriptDetailLine>> {
    if (lines.isEmpty()) return listOf(emptyList())
    val pages = mutableListOf<MutableList<NativeHarnessTranscriptDetailLine>>()
    var current = mutableListOf<NativeHarnessTranscriptDetailLine>()
    var currentChars = 0
    fun flush() {
        if (current.isNotEmpty()) pages += current
        current = mutableListOf()
        currentChars = 0
    }
    lines.forEach { line ->
        var remaining = line.text
        if (remaining.isEmpty()) {
            if (current.size >= NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PAGE_LINES) flush()
            current += line
            return@forEach
        }
        while (remaining.isNotEmpty()) {
            if (current.size >= NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PAGE_LINES ||
                currentChars >= NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PAGE_CHARS
            ) flush()
            val room = (NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PAGE_CHARS - currentChars)
                .coerceAtLeast(1)
            val take = remaining.length.coerceAtMost(room)
            current += line.copy(text = remaining.take(take))
            currentChars += take
            remaining = remaining.drop(take)
            if (remaining.isNotEmpty()) flush()
        }
    }
    flush()
    return pages.ifEmpty { listOf(emptyList()) }
}

/** Stops measuring as soon as the caller's wire cap is exceeded. */
private fun estimateJsonChars(
    value: JsonElement,
    remaining: Int,
    depth: Int = 0,
    maxDepth: Int = NativeHarnessStructuredTranscriptLimits.MAX_JSON_DEPTH,
): Int {
    if (remaining <= 0) return 1
    if (depth > maxDepth) return remaining + 1
    return when (value) {
        is JsonPrimitive -> (value.contentOrNull?.length?.plus(2) ?: 4)
            .coerceAtMost(remaining + 1)
        is JsonArray -> {
            var total = 2
            value.forEach { child ->
                if (total > remaining) return remaining + 1
                total += estimateJsonChars(child, remaining - total, depth + 1, maxDepth) + 1
            }
            total.coerceAtMost(remaining + 1)
        }
        is JsonObject -> {
            var total = 2
            value.entries.forEach { (key, child) ->
                if (total > remaining) return remaining + 1
                total += key.length + estimateJsonChars(child, remaining - total, depth + 1, maxDepth) + 4
            }
            total.coerceAtMost(remaining + 1)
        }
    }
}

private fun parseStructuredPart(value: JsonElement, depth: Int = 0): NativeHarnessStructuredTranscriptPart? {
    val row = value.jsonObjectOrNull() ?: return NativeHarnessStructuredTranscriptPart.Opaque(
        type = "unknown",
        preview = boundedJsonPreview(value),
    )
    return when (row.string("type")) {
        "text" -> NativeHarnessStructuredTranscriptPart.Text(
            row.string("text").orEmpty().bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT),
        )
        "reasoning" -> NativeHarnessStructuredTranscriptPart.Reasoning(
            row.string("text").orEmpty().bounded(NativeHarnessStructuredTranscriptLimits.MAX_TEXT),
        )
        "image" -> parseAttachment(row.objectValue("attachment"), image = true)?.let {
            NativeHarnessStructuredTranscriptPart.Image(it)
        }
        "file" -> parseAttachment(row.objectValue("attachment"), image = false)?.let {
            NativeHarnessStructuredTranscriptPart.File(it)
        }
        "tool-call" -> {
            val callId = row.string("id")?.boundedToken() ?: return null
            NativeHarnessStructuredTranscriptPart.Tool(
                callId = callId,
                name = row.string("name")?.boundedToken() ?: "tool",
                arguments = row.string("arguments").orEmpty().bounded(
                    NativeHarnessStructuredTranscriptLimits.MAX_ARGUMENTS,
                ),
                status = NativeHarnessStructuredToolStatus.RUNNING,
            )
        }
        "tool-result" -> {
            val callId = row.string("toolCallId")?.boundedToken() ?: return null
            NativeHarnessStructuredTranscriptPart.Tool(
                callId = callId,
                name = row.string("name")?.boundedToken() ?: "tool",
                arguments = "",
                status = if (row.boolean("isError") == true) NativeHarnessStructuredToolStatus.ERROR
                else NativeHarnessStructuredToolStatus.COMPLETED,
                result = parseStructuredContent(row["content"], depth = depth + 1),
            )
        }
        else -> NativeHarnessStructuredTranscriptPart.Opaque(
            type = row.string("type")?.boundedToken() ?: "unknown",
            preview = boundedJsonPreview(row),
        )
    }
}

private fun parseAttachment(
    value: JsonObject?,
    image: Boolean,
): NativeHarnessTranscriptAttachment? {
    val attachment = value ?: return null
    val id = attachment.string("attachmentId")?.boundedToken() ?: return null
    val mediaType = attachment.string("mediaType")?.boundedToken()
    val bytes = attachment.long("bytes")?.takeIf { it >= 0L }
    val width = attachment.int("width")?.takeIf { it > 0 }
    val height = attachment.int("height")?.takeIf { it > 0 }
    return NativeHarnessTranscriptAttachment(
        attachmentId = id,
        name = attachment.string("name")?.boundedToken(),
        mediaType = mediaType,
        bytes = bytes,
        width = width,
        height = height,
        canOpen = image && mediaType != null && mediaType in setOf(
            "image/png", "image/jpeg", "image/webp", "image/gif",
        ),
    )
}

private fun String.bounded(limit: Int): String =
    if (length <= limit) this else take(limit - 1) + "…"

private fun String.boundedToken(): String =
    take(NativeHarnessStructuredTranscriptLimits.MAX_PREVIEW)
        .trim()
        .takeIf { it.isNotBlank() }
        ?: "?"

/** Bounded JSON preview that never calls JsonElement.toString() on a large subtree. */
private fun boundedJsonPreview(value: JsonElement, depth: Int = 0): String {
    val builder = StringBuilder(NativeHarnessStructuredTranscriptLimits.MAX_PREVIEW)
    appendBoundedJsonPreview(value, builder, depth)
    return builder.toString()
}

private fun appendBoundedJsonPreview(
    value: JsonElement,
    output: StringBuilder,
    depth: Int,
) {
    if (output.length >= NativeHarnessStructuredTranscriptLimits.MAX_PREVIEW) return
    if (depth >= NativeHarnessStructuredTranscriptLimits.MAX_JSON_DEPTH) {
        appendBoundedPreviewText("…", output)
        return
    }
    when (value) {
        is JsonPrimitive -> appendBoundedPreviewText(value.contentOrNull.orEmpty(), output)
        is JsonArray -> {
            appendBoundedPreviewText("[", output)
            value.take(NativeHarnessStructuredTranscriptLimits.MAX_JSON_FIELDS)
                .forEachIndexed { index, child ->
                    if (index > 0) appendBoundedPreviewText(", ", output)
                    appendBoundedJsonPreview(child, output, depth + 1)
                }
            appendBoundedPreviewText("]", output)
        }
        is JsonObject -> {
            appendBoundedPreviewText("{", output)
            value.entries.take(NativeHarnessStructuredTranscriptLimits.MAX_JSON_FIELDS)
                .forEachIndexed { index, (key, child) ->
                    if (index > 0) appendBoundedPreviewText(", ", output)
                    appendBoundedPreviewText(key, output, 256)
                    appendBoundedPreviewText(": ", output)
                    appendBoundedJsonPreview(child, output, depth + 1)
                }
            appendBoundedPreviewText("}", output)
        }
    }
}

private fun appendBoundedPreviewText(text: String, output: StringBuilder, limit: Int? = null) {
    if (output.length >= NativeHarnessStructuredTranscriptLimits.MAX_PREVIEW) return
    val sourceLength = text.length.coerceAtMost(limit ?: text.length)
    val room = NativeHarnessStructuredTranscriptLimits.MAX_PREVIEW - output.length
    if (sourceLength <= room) {
        output.append(text, 0, sourceLength)
    } else if (room == 1) {
        output.append('…')
    } else {
        output.append(text, 0, room - 1).append('…')
    }
}

private fun JsonObject.objectValueArray(key: String): List<JsonObject> =
    this[key]?.jsonArrayOrNull()
        ?.take(NativeHarnessStructuredTranscriptLimits.MAX_PARTS)
        ?.mapNotNull { it.jsonObjectOrNull() }
        .orEmpty()
