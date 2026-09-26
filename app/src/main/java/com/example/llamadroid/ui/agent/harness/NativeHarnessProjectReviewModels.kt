package com.example.llamadroid.ui.agent.harness

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Constants copied from the pinned dsh-client-ui-deliverables package. */
internal object NativeHarnessProjectReviewWire {
    const val CHANGES_SUMMARY_PATH = "/api/changes.summary"
    const val CHANGES_DIFF_PATH = "/api/changes.diff"
    const val CHANGES_OPEN_PATH = "/api/changes.open"
    const val CHANGES_REVIEW_ADDRESS_PREFIX = "dsh-resource://changes-review/session/"
    const val MAX_FILE_TEXT = 256 * 1024
    const val FILE_PAGE_LINES = 200
    const val MAX_FILES = 256
    const val MAX_HUNKS = 512
    const val MAX_HUNK_LINES = 4_096
    const val MAX_DIFF_TEXT = 256 * 1024
    const val DIFF_PAGE_TEXT = 24 * 1024
    const val LIST_PAGE_SIZE = 25

    fun query(sessionId: String, seq: Long, index: Int? = null): Map<String, String> = buildMap {
        put("sessionId", sessionId)
        put("seq", seq.toString())
        if (index != null) put("index", index.toString())
    }

    fun reviewAddress(sessionId: String, seq: Long, turn: Int): String =
        CHANGES_REVIEW_ADDRESS_PREFIX + encode(sessionId) + "/$seq/$turn"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

internal data class NativeHarnessProjectReviewCoordinates(
    val sessionId: String,
    val sequence: Long,
    val turn: Int,
)

internal data class NativeHarnessChangedFile(
    val path: String,
    val display: String,
    val added: Int,
    val deleted: Int,
    val binary: Boolean = false,
    val oversized: Boolean = false,
)

internal data class NativeHarnessChangesSummary(
    val coordinates: NativeHarnessProjectReviewCoordinates,
    val files: List<NativeHarnessChangedFile>,
    val total: Int,
    val added: Int,
    val deleted: Int,
    val beforeSnapshot: String? = null,
    val afterSnapshot: String? = null,
)

internal data class NativeHarnessDiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String>,
)

internal sealed interface NativeHarnessFileDiff {
    val path: String
    val display: String

    data class Text(
        override val path: String,
        override val display: String,
        val before: Boolean,
        val after: Boolean,
        val hunks: List<NativeHarnessDiffHunk>,
        val coarse: Boolean,
    ) : NativeHarnessFileDiff

    data class Binary(override val path: String, override val display: String) : NativeHarnessFileDiff
    data class Oversized(override val path: String, override val display: String) : NativeHarnessFileDiff
}

/** A bounded page over a presentation list. The source list remains complete in state. */
internal data class NativeHarnessReviewPage<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
)

internal fun <T> nativeHarnessReviewPage(
    items: List<T>,
    requestedPage: Int,
    pageSize: Int = NativeHarnessProjectReviewWire.LIST_PAGE_SIZE,
): NativeHarnessReviewPage<T> {
    require(pageSize > 0)
    val pageCount = maxOf(1, (items.size + pageSize - 1) / pageSize)
    val pageIndex = requestedPage.coerceIn(0, pageCount - 1)
    val start = (pageIndex * pageSize).coerceAtMost(items.size)
    val end = (start + pageSize).coerceAtMost(items.size)
    return NativeHarnessReviewPage(items.subList(start, end), pageIndex, pageCount)
}

internal data class NativeHarnessDiffTextPage(
    val text: String,
    val pageIndex: Int,
    val pageCount: Int,
)

internal data class NativeHarnessWorkspaceFilePage(
    val path: String,
    val absolutePath: String?,
    val version: String,
    val offset: Int,
    val text: String,
    val lines: Int,
    val eof: Boolean,
    val bytes: Long? = null,
)

internal data class NativeHarnessPresentedFile(
    val sequence: Long,
    val turn: Int,
    val index: Int,
    val path: String,
    val description: String? = null,
)

internal enum class NativeHarnessProjectFileAction { OPEN, REVEAL }

/** The pinned upstream UI has no revert/undo endpoint. */
internal enum class NativeHarnessProjectMutationSupport { NONE }

internal enum class NativeHarnessWorkflowStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

internal data class NativeHarnessWorkflowMember(
    val sequence: Int,
    val label: String,
    val childId: String,
    val status: NativeHarnessWorkflowStatus,
)

internal data class NativeHarnessWorkflowPhase(
    val key: String,
    val phase: String?,
    val members: List<NativeHarnessWorkflowMember>,
)

internal data class NativeHarnessWorkflowRun(
    val runId: String,
    val name: String,
    val status: NativeHarnessWorkflowStatus,
    val phases: List<NativeHarnessWorkflowPhase>,
)

internal enum class NativeHarnessTodoStatus { PENDING, IN_PROGRESS, COMPLETED }

internal data class NativeHarnessTodoItem(val content: String, val status: NativeHarnessTodoStatus)

internal sealed interface NativeHarnessScheduleRecord {
    val id: String
    val prompt: String
    val scheduledAt: String

    data class After(
        override val id: String,
        override val prompt: String,
        val afterSeconds: Long,
        override val scheduledAt: String,
    ) : NativeHarnessScheduleRecord

    data class At(
        override val id: String,
        override val prompt: String,
        override val scheduledAt: String,
    ) : NativeHarnessScheduleRecord

    data class Every(
        override val id: String,
        override val prompt: String,
        val everySeconds: Long,
        override val scheduledAt: String,
    ) : NativeHarnessScheduleRecord
}

internal data class NativeHarnessProjectReviewState(
    val agentId: String? = null,
    val loading: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val changes: NativeHarnessChangesSummary? = null,
    val selectedDiff: NativeHarnessFileDiff? = null,
    val filePage: NativeHarnessWorkspaceFilePage? = null,
    val presentedFiles: List<NativeHarnessPresentedFile> = emptyList(),
    val workflows: List<NativeHarnessWorkflowRun> = emptyList(),
    val todos: List<NativeHarnessTodoItem>? = null,
    val schedules: List<NativeHarnessScheduleRecord> = emptyList(),
    val projectionAvailable: Boolean = false,
    val historyClosed: Boolean = false,
) {
    val revertSupported: Boolean get() = false
}

/** Authenticated same-origin transport supplied by the host integration. */
internal interface NativeHarnessProjectReviewTransport {
    suspend fun get(path: String, query: Map<String, String>): JsonElement

    suspend fun post(path: String, query: Map<String, String>, body: JsonObject): JsonElement

    suspend fun readWorkspaceFile(
        sessionId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): JsonElement
}

internal fun parseNativeHarnessChangesSummary(
    value: JsonElement,
    coordinates: NativeHarnessProjectReviewCoordinates,
): NativeHarnessChangesSummary? {
    val root = value.jsonObjectOrNull() ?: return null
    val turn = root.int("turn")?.takeIf { it >= 1 } ?: return null
    if (turn != coordinates.turn) return null
    val files = root["files"]?.jsonArrayOrNull()?.takeIf { it.size <= NativeHarnessProjectReviewWire.MAX_FILES }
        ?.map { parseNativeHarnessChangedFile(it) ?: return null } ?: return null
    val total = root.int("total") ?: return null
    val added = root.int("added") ?: return null
    val deleted = root.int("deleted") ?: return null
    if (total < 0 || added < 0 || deleted < 0 || total != files.size) return null
    val snapshot = root.objectValue("snapshot")
    return NativeHarnessChangesSummary(
        coordinates = coordinates.copy(turn = turn),
        files = files,
        total = total,
        added = added,
        deleted = deleted,
        beforeSnapshot = snapshot?.string("before"),
        afterSnapshot = snapshot?.string("after"),
    )
}

private fun parseNativeHarnessChangedFile(value: JsonElement): NativeHarnessChangedFile? {
    val row = value.jsonObjectOrNull() ?: return null
    val path = row.string("path")?.takeIf { it.isNotBlank() } ?: return null
    val display = row.string("display")?.takeIf { it.isNotBlank() } ?: return null
    val added = row.int("added") ?: return null
    val deleted = row.int("deleted") ?: return null
    if (added < 0 || deleted < 0) return null
    if (row["binary"] != null && row.boolean("binary") != true) return null
    if (row["oversized"] != null && row.boolean("oversized") != true) return null
    return NativeHarnessChangedFile(path, display, added, deleted, row.boolean("binary") == true, row.boolean("oversized") == true)
}

internal fun parseNativeHarnessFileDiff(value: JsonElement): NativeHarnessFileDiff? {
    val root = value.jsonObjectOrNull() ?: return null
    val path = root.string("path")?.takeIf { it.isNotBlank() } ?: return null
    val display = root.string("display")?.takeIf { it.isNotBlank() } ?: return null
    return when (root.string("kind")) {
        "binary" -> NativeHarnessFileDiff.Binary(path, display)
        "oversized" -> NativeHarnessFileDiff.Oversized(path, display)
        "text" -> {
            val hunkArray = root["hunks"]?.jsonArrayOrNull()
                ?.takeIf { it.size <= NativeHarnessProjectReviewWire.MAX_HUNKS }
                ?: return null
            val hunks = ArrayList<NativeHarnessDiffHunk>(hunkArray.size)
            var renderedLength = 0L
            for (item in hunkArray) {
                val hunk = parseNativeHarnessHunk(item) ?: return null
                renderedLength += nativeHarnessDiffHunkTextLength(hunk)
                if (renderedLength > NativeHarnessProjectReviewWire.MAX_DIFF_TEXT) {
                    return NativeHarnessFileDiff.Oversized(path, display)
                }
                hunks += hunk
            }
            NativeHarnessFileDiff.Text(
                path = path,
                display = display,
                before = root.boolean("before") ?: return null,
                after = root.boolean("after") ?: return null,
                hunks = hunks,
                coarse = root.boolean("coarse") ?: return null,
            )
        }
        else -> null
    }
}

private fun parseNativeHarnessHunk(value: JsonElement): NativeHarnessDiffHunk? {
    val row = value.jsonObjectOrNull() ?: return null
    val lines = row["lines"]?.jsonArrayOrNull()?.takeIf { it.size <= NativeHarnessProjectReviewWire.MAX_HUNK_LINES }
        ?.map {
            val line = it.jsonPrimitiveOrNull()?.contentOrNull ?: return null
            line.takeIf { value -> value.isNotEmpty() && value[0] in charArrayOf('+', '-', ' ') } ?: return null
        } ?: return null
    val oldStart = row.int("oldStart") ?: return null
    val oldLines = row.int("oldLines") ?: return null
    val newStart = row.int("newStart") ?: return null
    val newLines = row.int("newLines") ?: return null
    if (oldStart < 0 || oldLines < 0 || newStart < 0 || newLines < 0) return null
    return NativeHarnessDiffHunk(oldStart, oldLines, newStart, newLines, lines)
}

internal fun parseNativeHarnessWorkspaceFilePage(value: JsonElement, path: String): NativeHarnessWorkspaceFilePage? {
    val root = value.jsonObjectOrNull() ?: return null
    val text = root.string("text") ?: return null
    if (text.length > NativeHarnessProjectReviewWire.MAX_FILE_TEXT) return null
    val version = root.string("version")?.takeIf { it.isNotBlank() } ?: return null
    val offset = root.int("offset") ?: return null
    val lines = root.int("lines") ?: return null
    val eof = root.boolean("eof") ?: return null
    if (offset < 1 || lines !in 0..NativeHarnessProjectReviewWire.FILE_PAGE_LINES) return null
    return NativeHarnessWorkspaceFilePage(path, root.string("absolutePath"), version, offset, text, lines, eof, root.long("bytes"))
}

/** Returns the exact rendered diff size without allocating a second full diff string. */
internal fun nativeHarnessDiffTextLength(diff: NativeHarnessFileDiff.Text): Int {
    val total = diff.hunks.sumOf(::nativeHarnessDiffHunkTextLength)
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun nativeHarnessDiffHunkTextLength(hunk: NativeHarnessDiffHunk): Long =
    "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@\n".length.toLong() +
        hunk.lines.sumOf { it.length.toLong() + 1L }

/** Extracts one 24 KiB diff page on demand; no page builds the full rendered diff. */
internal fun nativeHarnessDiffPage(
    diff: NativeHarnessFileDiff.Text,
    requestedPage: Int,
): NativeHarnessDiffTextPage {
    val totalLength = nativeHarnessDiffTextLength(diff)
    val pageCount = maxOf(1, (totalLength + NativeHarnessProjectReviewWire.DIFF_PAGE_TEXT - 1) /
        NativeHarnessProjectReviewWire.DIFF_PAGE_TEXT)
    val pageIndex = requestedPage.coerceIn(0, pageCount - 1)
    val start = pageIndex * NativeHarnessProjectReviewWire.DIFF_PAGE_TEXT
    val end = minOf(totalLength, start + NativeHarnessProjectReviewWire.DIFF_PAGE_TEXT)
    val output = StringBuilder(end - start)
    var cursor = 0

    fun appendSegment(segment: String) {
        val segmentStart = cursor
        val segmentEnd = cursor + segment.length
        val overlapStart = maxOf(start, segmentStart)
        val overlapEnd = minOf(end, segmentEnd)
        if (overlapStart < overlapEnd) {
            output.append(segment, overlapStart - segmentStart, overlapEnd - segmentStart)
        }
        cursor = segmentEnd
    }

    diff.hunks.forEach { hunk ->
        appendSegment("@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@\n")
        hunk.lines.forEach { line -> appendSegment("$line\n") }
    }
    return NativeHarnessDiffTextPage(output.toString(), pageIndex, pageCount)
}

internal fun parseNativeHarnessPresentedFiles(records: Iterable<JsonObject>): List<NativeHarnessPresentedFile> {
    val files = mutableListOf<NativeHarnessPresentedFile>()
    records.forEach { record ->
        val event = record.objectValue("event") ?: record
        if (event.string("type") != "deliverables/presented") return@forEach
        val sequence = event.long("seq") ?: record.long("seq") ?: return@forEach
        val data = event.objectValue("data") ?: return@forEach
        val turn = data.int("turn")?.takeIf { it >= 1 } ?: return@forEach
        val declared = data["files"]?.jsonArrayOrNull() ?: return@forEach
        declared.forEachIndexed { index, item ->
            val row = item.jsonObjectOrNull() ?: return@forEachIndexed
            val path = row.string("path")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val descriptionValue = row["description"]
            if (descriptionValue != null &&
                (descriptionValue as? JsonPrimitive)?.isString != true
            ) return@forEachIndexed
            val description = (descriptionValue as? JsonPrimitive)?.contentOrNull
            files += NativeHarnessPresentedFile(sequence, turn, index, path, description)
        }
    }
    return files.takeLast(NativeHarnessProjectReviewWire.MAX_FILES)
}

internal fun parseNativeHarnessChangesCoordinates(sessionId: String, record: JsonObject): NativeHarnessProjectReviewCoordinates? {
    if (sessionId.isBlank()) return null
    val event = record.objectValue("event") ?: record
    if (event.string("type") != "workspace/changes") return null
    val data = event.objectValue("data") ?: return null
    val sequence = event.long("seq") ?: record.long("seq") ?: return null
    val turn = data.int("turn")?.takeIf { it >= 1 } ?: return null
    if (sequence < 0L) return null
    return NativeHarnessProjectReviewCoordinates(sessionId, sequence, turn)
}

internal fun parseNativeHarnessTodos(value: JsonElement): List<NativeHarnessTodoItem>? {
    if (value is JsonNull) return null
    val array = value.jsonArrayOrNull() ?: return null
    return array.map { item ->
        val row = item.jsonObjectOrNull() ?: return null
        val content = row.string("content")?.takeIf { it.isNotBlank() && it == it.trim() } ?: return null
        val status = when (row.string("status")) {
            "pending" -> NativeHarnessTodoStatus.PENDING
            "in_progress" -> NativeHarnessTodoStatus.IN_PROGRESS
            "completed" -> NativeHarnessTodoStatus.COMPLETED
            else -> return null
        }
        NativeHarnessTodoItem(content, status)
    }
}

internal fun parseNativeHarnessSchedules(value: JsonElement): List<NativeHarnessScheduleRecord>? {
    val array = value.jsonArrayOrNull() ?: return null
    return array.map { item ->
        val row = item.jsonObjectOrNull() ?: return null
        val id = row.string("id")?.takeIf { it.isNotBlank() && it == it.trim() } ?: return null
        val prompt = row.string("prompt")?.takeIf { it.isNotBlank() && it == it.trim() } ?: return null
        val scheduledAt = row.string("scheduledAt")?.takeIf { isCanonicalInstant(it) } ?: return null
        when (row.string("kind")) {
            "after" -> {
                if (row.keys != setOf("id", "kind", "prompt", "afterSeconds", "scheduledAt")) return null
                NativeHarnessScheduleRecord.After(id, prompt, row.long("afterSeconds")?.takeIf { it > 0 } ?: return null, scheduledAt)
            }
            "at" -> {
                if (row.keys != setOf("id", "kind", "prompt", "scheduledAt")) return null
                NativeHarnessScheduleRecord.At(id, prompt, scheduledAt)
            }
            "every" -> {
                if (row.keys != setOf("id", "kind", "prompt", "everySeconds", "scheduledAt")) return null
                NativeHarnessScheduleRecord.Every(id, prompt, row.long("everySeconds")?.takeIf { it >= 300 } ?: return null, scheduledAt)
            }
            else -> return null
        }
    }
}

/**
 * Mirrors the pinned conversation location lifecycle: a closed step or turn
 * leaves the last active location closed until a new boundary starts. This is
 * used only as the global fallback; workflow folding also tracks the owning
 * turn/step so an older run remains interrupted after a later step opens.
 */
internal fun nativeHarnessHistoryClosed(records: Iterable<JsonObject>): Boolean {
    var closed = false
    records.forEach { record ->
        val event = record.objectValue("event") ?: record
        when (event.string("type")) {
            "turn/start", "step/start" -> closed = false
            "turn/end", "step/end" -> closed = true
        }
    }
    return closed
}

private val canonicalUtcInstant = Regex(
    "^(?!0000)\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d\\.\\d{3}Z$"
)

private fun isCanonicalInstant(value: String): Boolean =
    canonicalUtcInstant.matches(value) && runCatching { Instant.parse(value) }.isSuccess

internal fun nativeHarnessWorkflowPhaseKey(phase: String?): String =
    phase?.let { "value:${it.length}:$it" } ?: "missing"

internal fun foldNativeHarnessWorkflows(
    records: Iterable<JsonObject>,
    historyClosed: Boolean,
): List<NativeHarnessWorkflowRun> {
    data class Member(val sequence: Int, val label: String, val childId: String, var outcome: String? = null, var phase: String? = null, var phasePresent: Boolean = false)
    data class Run(
        val id: String,
        val name: String,
        val turn: Int?,
        val step: Int?,
        val members: MutableList<Member> = mutableListOf(),
        var stopReason: String? = null,
        /** True when the bounded history closed after this run's opening event was pruned. */
        var closedByBoundary: Boolean = false,
    )
    val runs = linkedMapOf<String, Run>()
    val closedTurns = mutableSetOf<Int>()
    val closedSteps = mutableSetOf<Pair<Int, Int>>()
    var currentTurn: Int? = null
    var currentStep: Int? = null
    fun markUnboundRunsClosed() {
        runs.values.forEach { run ->
            if (run.turn == null && run.stopReason == null) run.closedByBoundary = true
        }
    }
    records.forEach { record ->
        val event = record.objectValue("event") ?: record
        val data = event.objectValue("data") ?: return@forEach
        when (event.string("type")) {
            "turn/start" -> {
                currentTurn = data.int("turn")
                currentStep = null
            }
            "step/start" -> {
                currentTurn = data.int("turn")
                currentStep = data.int("step")
            }
            "step/end" -> {
                val turn = data.int("turn")
                val step = data.int("step")
                if (turn != null && step != null) closedSteps.add(turn to step)
                markUnboundRunsClosed()
                if (currentTurn == turn && currentStep == step) currentStep = null
            }
            "turn/end" -> {
                data.int("turn")?.let(closedTurns::add)
                markUnboundRunsClosed()
                if (currentTurn == data.int("turn")) {
                    currentTurn = null
                    currentStep = null
                }
            }
            "tool-workflow/run-start" -> {
                val id = data.string("runId")?.takeIf { it.isNotBlank() } ?: return@forEach
                if (runs[id] == null) {
                    data.string("name")?.takeIf { it.isNotBlank() }?.let {
                        runs[id] = Run(
                            id = id,
                            name = it,
                            turn = data.int("turn") ?: currentTurn,
                            step = data.int("step") ?: currentStep,
                        )
                    }
                }
            }
            "tool-workflow/agent-start" -> {
                val id = data.string("runId") ?: return@forEach
                val run = runs[id] ?: return@forEach
                val sequence = data.int("seq") ?: return@forEach
                val label = data.string("label")?.takeIf { it.isNotBlank() } ?: return@forEach
                val childId = data.string("childId")?.takeIf { it.isNotBlank() } ?: return@forEach
                val phase = data.string("phase")
                run.members += Member(sequence, label, childId, phase = phase, phasePresent = data["phase"] != null)
            }
            "tool-workflow/agent-end" -> {
                val run = runs[data.string("runId")] ?: return@forEach
                val sequence = data.int("seq") ?: return@forEach
                run.members.firstOrNull { it.sequence == sequence }?.outcome = data.string("outcome")
            }
            "tool-workflow/run-end" -> runs[data.string("runId")]?.stopReason = data.string("stopReason")
        }
    }
    return runs.values.map { run ->
        val turn = run.turn
        val step = run.step
        val owningLocationClosed = when {
            turn == null -> false
            step != null -> (turn to step) in closedSteps || turn in closedTurns
            else -> turn in closedTurns
        }
        val runInterrupted = run.stopReason == null &&
            (historyClosed || owningLocationClosed || run.closedByBoundary)
        val grouped = linkedMapOf<String, Pair<String?, MutableList<NativeHarnessWorkflowMember>>>()
        run.members.forEach { member ->
            val phase = if (member.phasePresent) member.phase else null
            val key = nativeHarnessWorkflowPhaseKey(phase)
            val group = grouped.getOrPut(key) { phase to mutableListOf() }
            val status = when (member.outcome) {
                "completed" -> NativeHarnessWorkflowStatus.COMPLETED
                "cancelled" -> NativeHarnessWorkflowStatus.CANCELLED
                "failed" -> NativeHarnessWorkflowStatus.FAILED
                null -> if (runInterrupted) NativeHarnessWorkflowStatus.INTERRUPTED else NativeHarnessWorkflowStatus.RUNNING
                else -> NativeHarnessWorkflowStatus.FAILED
            }
            group.second += NativeHarnessWorkflowMember(member.sequence, member.label, member.childId, status)
        }
        val status = when (run.stopReason) {
            "completed" -> NativeHarnessWorkflowStatus.COMPLETED
            "cancelled" -> NativeHarnessWorkflowStatus.CANCELLED
            "error" -> NativeHarnessWorkflowStatus.FAILED
            null -> if (runInterrupted) NativeHarnessWorkflowStatus.INTERRUPTED else NativeHarnessWorkflowStatus.RUNNING
            else -> NativeHarnessWorkflowStatus.FAILED
        }
        NativeHarnessWorkflowRun(
            runId = run.id,
            name = run.name,
            status = status,
            phases = grouped.map { (key, value) -> NativeHarnessWorkflowPhase(key, value.first, value.second.toList()) },
        )
    }
}
