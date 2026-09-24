package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val HARNESS_COMMAND_HISTORY_LIMIT = 240
internal const val HARNESS_COMMAND_TEXT_LIMIT = 4_000
internal const val HARNESS_COMMAND_OUTPUT_LIMIT = 16_000
internal const val HARNESS_COMMAND_LIVE_CHUNK_LIMIT_BYTES = 2 * 1024

internal fun isHarnessCommandEvent(type: String?): Boolean = type != null && type in COMMAND_EVENT_TYPES

private val COMMAND_EVENT_TYPES = setOf(
    "tool/call",
    "tool/result",
    "tool/ptc-dispatch-start",
    "tool/ptc-dispatch",
)

internal data class HarnessCommandJobOutputProjection(
    val output: String,
    val status: HarnessCommandRunStatus,
    val observedAtMs: Long?,
    val sequence: Long,
    val outputTruncated: Boolean,
)

internal data class HarnessCommandHistoryPageCursor(
    val throughSeq: Long,
    val beforeSeq: Long?,
    val hasMore: Boolean,
)

internal data class HarnessCommandOutputCursorKey(
    val callId: String,
    val stream: String,
)

/** Projects only model-run shell calls. The source event remains in the live Session stream. */
internal fun mergeNativeHarnessCommandRuns(
    current: List<HarnessCommandRunUi>,
    records: List<JsonObject>,
    jobOutputCalls: MutableMap<String, String> = linkedMapOf(),
    jobOutputById: MutableMap<String, HarnessCommandJobOutputProjection> = linkedMapOf(),
): List<HarnessCommandRunUi> {
    val rows = current.associateByTo(linkedMapOf()) { it.id }
    records.forEach { record ->
        val event = record.objectValue("event") ?: record
        val data = event.objectValue("data") ?: return@forEach
        val type = event.string("type") ?: return@forEach
        val sequence = event.long("seq") ?: record.long("seq") ?: 0L
        val time = commandEventTime(event, data)
        when (type) {
            "tool/call", "tool/ptc-dispatch-start" -> {
                val callId = (data.string("callId") ?: data.string("subCallId"))
                    ?.takeIf(String::isNotBlank) ?: return@forEach
                val toolName = data.string("name").orEmpty().lowercase()
                if (toolName == JOB_OUTPUT_TOOL_NAME) {
                    val arguments = data["arguments"] ?: return@forEach
                    val args = when (arguments) {
                        is JsonObject -> arguments
                        is JsonPrimitive -> arguments.contentOrNull?.let(::parseHarnessJsonValue)?.jsonObjectOrNull()
                        else -> null
                    } ?: return@forEach
                    val jobId = findCommandJobId(args) ?: args.string("id")?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    rememberBounded(jobOutputCalls, callId, jobId)
                    return@forEach
                }
                if (toolName !in SHELL_TOOL_NAMES) return@forEach
                val arguments = data["arguments"] ?: return@forEach
                val args = when (arguments) {
                    is JsonObject -> arguments
                    is JsonPrimitive -> arguments.contentOrNull?.let(::parseHarnessJsonValue)?.jsonObjectOrNull()
                    else -> null
                } ?: return@forEach
                val rawCommand = args.string("command")?.takeIf(String::isNotBlank) ?: return@forEach
                val existing = rows[callId]
                val observed = HarnessCommandRunUi(
                    id = callId,
                    command = rawCommand.take(HARNESS_COMMAND_TEXT_LIMIT),
                    output = existing?.output.orEmpty(),
                    status = existing?.status ?: HarnessCommandRunStatus.RUNNING,
                    timestampMs = existing?.timestampMs ?: commandDisplayTimestamp(event, data),
                    startedAtMs = existing?.startedAtMs ?: time,
                    durationMs = existing?.durationMs,
                    sequence = minOf(existing?.sequence ?: sequence, sequence),
                    jobId = existing?.jobId,
                    outputTruncated = rawCommand.length > HARNESS_COMMAND_TEXT_LIMIT || existing?.outputTruncated == true,
                )
                rows[callId] = observed.withKnownJobOutput(jobOutputById[observed.jobId])
            }

            "tool/result", "tool/ptc-dispatch" -> {
                val message = data.objectValue("message")
                val resultBlock = message?.objectArray("content")?.firstOrNull { it.string("type") == "tool-result" }
                val callId = message?.objectValue("source")?.string("callId")
                    ?: resultBlock?.string("toolCallId")
                    ?: data.string("subCallId")
                    ?: return@forEach
                val jobOutputId = jobOutputCalls.remove(callId)
                if (jobOutputId != null) {
                    val rawOutput = extractCommandOutput(
                        resultBlock?.get("content") ?: message?.get("content") ?: data["content"],
                    ).ifBlank { data.objectValue("error")?.string("message").orEmpty() }
                    val projection = HarnessCommandJobOutputProjection(
                        output = rawOutput.takeLast(HARNESS_COMMAND_OUTPUT_LIMIT),
                        status = commandJobOutputStatus(data, message, resultBlock, rawOutput),
                        observedAtMs = time,
                        sequence = sequence,
                        outputTruncated = rawOutput.length > HARNESS_COMMAND_OUTPUT_LIMIT,
                    )
                    val previous = jobOutputById[jobOutputId]
                    val effectiveProjection = if (previous == null || projection.sequence >= previous.sequence) {
                        rememberBounded(jobOutputById, jobOutputId, projection)
                        projection
                    } else previous
                    rows.values.filter { it.jobId == jobOutputId }.forEach { run ->
                        rows[run.id] = run.withKnownJobOutput(effectiveProjection)
                    }
                    return@forEach
                }
                val existing = rows[callId] ?: return@forEach
                val rawOutput = extractCommandOutput(
                    resultBlock?.get("content")
                        ?: message?.get("content")
                        ?: data["content"],
                ).ifBlank {
                    data.objectValue("error")?.string("message").orEmpty()
                }
                val exitCode = findCommandExitCode(data) ?: commandOutputExitCode(rawOutput)
                val error = data.objectValue("error") != null ||
                    data.boolean("isError") == true || resultBlock?.boolean("isError") == true ||
                    exitCode?.let { it != 0 } == true
                val jobId = findCommandJobId(data) ?: commandOutputBackgroundJobId(rawOutput)
                val backgroundStarted = jobId != null && commandOutputStartedBackgroundJob(rawOutput)
                val duration = time?.let { end ->
                    (existing.startedAtMs ?: existing.timestampMs)?.let { start -> (end - start).coerceAtLeast(0L) }
                }
                rows[callId] = existing.copy(
                    output = rawOutput.takeLast(HARNESS_COMMAND_OUTPUT_LIMIT),
                    status = when {
                        error -> HarnessCommandRunStatus.FAILED
                        jobId != null && backgroundStarted -> HarnessCommandRunStatus.BACKGROUND
                        jobId != null && commandJobIsRunning(data, message, resultBlock) -> HarnessCommandRunStatus.RUNNING
                        else -> HarnessCommandRunStatus.COMPLETED
                    },
                    durationMs = duration ?: existing.durationMs,
                    jobId = jobId ?: existing.jobId,
                    outputTruncated = existing.outputTruncated || rawOutput.length > HARNESS_COMMAND_OUTPUT_LIMIT,
                ).withKnownJobOutput(jobOutputById[jobId ?: existing.jobId])
            }
        }
    }
    return rows.values
        .sortedWith(compareBy<HarnessCommandRunUi> { it.sequence }.thenBy { it.id })
        .takeLast(HARNESS_COMMAND_HISTORY_LIMIT)
}

/** Appends one authenticated live delta to an active run, deduplicated by byte offset. */
internal fun appendNativeHarnessCommandLiveOutput(
    runs: List<HarnessCommandRunUi>,
    callId: String,
    stream: String,
    offset: Long,
    text: String,
    offsets: MutableMap<HarnessCommandOutputCursorKey, Long>,
): List<HarnessCommandRunUi> {
    if (callId.isBlank() || stream !in LIVE_COMMAND_STREAMS || offset < 0L || text.isEmpty()) return runs
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size > HARNESS_COMMAND_LIVE_CHUNK_LIMIT_BYTES) return runs
    val index = runs.indexOfFirst { it.id == callId }
    if (index < 0) return runs
    val run = runs[index]
    if (run.status != HarnessCommandRunStatus.RUNNING) return runs

    val key = HarnessCommandOutputCursorKey(callId, stream)
    val previousOffset = offsets[key]
    if (previousOffset != null && offset < previousOffset) return runs
    val hasGap = if (previousOffset == null) offset > 0L else offset > previousOffset
    rememberLiveOutputOffset(offsets, key, offset + bytes.size)

    val combined = run.output + text
    val updated = run.copy(
        output = combined.takeLast(HARNESS_COMMAND_OUTPUT_LIMIT),
        outputTruncated = run.outputTruncated || hasGap || combined.length > HARNESS_COMMAND_OUTPUT_LIMIT,
    )
    return runs.toMutableList().also { it[index] = updated }
}

private fun rememberLiveOutputOffset(
    offsets: MutableMap<HarnessCommandOutputCursorKey, Long>,
    key: HarnessCommandOutputCursorKey,
    value: Long,
) {
    offsets.remove(key)
    offsets[key] = value
    val limit = HARNESS_COMMAND_HISTORY_LIMIT * LIVE_COMMAND_STREAMS.size
    while (offsets.size > limit) offsets.keys.firstOrNull()?.let(offsets::remove) ?: break
}

internal fun commandHistoryCanLoadOlder(
    records: List<JsonObject>,
    hasMore: Boolean,
): Boolean = hasMore && records.isNotEmpty()

internal fun maximumHarnessCommandRecordSequence(records: List<JsonObject>): Long? = records.maxOfOrNull { record ->
    val event = record.objectValue("event") ?: record
    event.long("seq") ?: record.long("seq") ?: Long.MIN_VALUE
}?.takeUnless { it == Long.MIN_VALUE }

private val SHELL_TOOL_NAMES = setOf("bash", "shell")
private val LIVE_COMMAND_STREAMS = setOf("stdout", "stderr")
private const val JOB_OUTPUT_TOOL_NAME = "job_output"

private fun commandEventTime(event: JsonObject, data: JsonObject): Long? =
    event.long("time") ?: event.long("timestampMs") ?: event.long("timestamp") ?: data.long("time")

private fun commandDisplayTimestamp(event: JsonObject, data: JsonObject): Long? = commandEventTime(event, data)
    ?.takeIf { it >= MIN_DISPLAYABLE_EPOCH_MS }

private const val MIN_DISPLAYABLE_EPOCH_MS = 946_684_800_000L
private val EXIT_CODE_OUTPUT_MARKER = Regex("""\[exit code:\s*(-?\d+)\]\s*$""", RegexOption.IGNORE_CASE)
private val BACKGROUND_JOB_OUTPUT_MARKER = Regex("""started background job\s+(\S+)\s*$""", RegexOption.IGNORE_CASE)
private val JOB_STATUS_OUTPUT_MARKER = Regex("""\[status:\s*([a-z_-]+)\]\s*$""", RegexOption.IGNORE_CASE)

private fun commandJobOutputStatus(
    data: JsonObject,
    message: JsonObject?,
    resultBlock: JsonObject?,
    output: String,
): HarnessCommandRunStatus {
    val status = JOB_STATUS_OUTPUT_MARKER.find(output.takeLast(512))?.groupValues?.getOrNull(1)?.lowercase()
        ?: run {
            sequenceOf(data, message, resultBlock)
                .filterNotNull()
                .mapNotNull { it.string("status") }
                .firstOrNull()
                ?.lowercase()
        }
    return when {
        (findCommandExitCode(data) ?: commandOutputExitCode(output))?.let { it != 0 } == true ->
            HarnessCommandRunStatus.FAILED
        status in setOf("running", "pending", "starting", "stopping") -> HarnessCommandRunStatus.RUNNING
        status in setOf("failed", "error", "cancelled", "canceled", "killed", "aborted", "stopped", "terminated", "timed_out") ->
            HarnessCommandRunStatus.FAILED
        status in setOf("completed", "complete", "succeeded", "success", "finished", "done", "exited") ->
            HarnessCommandRunStatus.COMPLETED
        else -> HarnessCommandRunStatus.BACKGROUND
    }
}

private fun HarnessCommandRunUi.withKnownJobOutput(
    output: HarnessCommandJobOutputProjection?,
): HarnessCommandRunUi {
    if (output == null) return this
    val duration = output.observedAtMs?.let { observedAt ->
        (startedAtMs ?: timestampMs)?.let { startedAt -> (observedAt - startedAt).coerceAtLeast(0L) }
    }
    return copy(
        output = output.output,
        status = output.status,
        durationMs = duration ?: durationMs,
        outputTruncated = outputTruncated || output.outputTruncated,
    )
}

private fun <V> rememberBounded(target: MutableMap<String, V>, key: String, value: V) {
    target.remove(key)
    target[key] = value
    while (target.size > HARNESS_COMMAND_HISTORY_LIMIT) {
        target.keys.firstOrNull()?.let(target::remove) ?: break
    }
}

private fun commandJobIsRunning(
    data: JsonObject,
    message: JsonObject?,
    resultBlock: JsonObject?,
): Boolean {
    val status = sequenceOf(data, message, resultBlock)
        .filterNotNull()
        .mapNotNull { it.string("status") }
        .firstOrNull()
        ?.lowercase()
    return status in setOf("running", "pending", "starting", "stopping") ||
        data.boolean("running") == true || message?.boolean("running") == true || resultBlock?.boolean("running") == true
}

private fun findCommandJobId(value: JsonElement?, depth: Int = 0): String? {
    if (depth > 4) return null
    return when (value) {
        is JsonObject -> {
            value.string("jobId")?.takeIf(String::isNotBlank)
                ?: value.string("job_id")?.takeIf(String::isNotBlank)
                ?: value.values.firstNotNullOfOrNull { findCommandJobId(it, depth + 1) }
        }
        is JsonArray -> value.firstNotNullOfOrNull { findCommandJobId(it, depth + 1) }
        else -> null
    }
}

private fun findCommandExitCode(value: JsonElement?, depth: Int = 0): Int? {
    if (depth > 5) return null
    return when (value) {
        is JsonObject -> value.int("exitCode")
            ?: value.int("exit_code")
            ?: value.values.firstNotNullOfOrNull { findCommandExitCode(it, depth + 1) }
        is JsonArray -> value.firstNotNullOfOrNull { findCommandExitCode(it, depth + 1) }
        else -> null
    }
}

private fun commandOutputExitCode(output: String): Int? =
    EXIT_CODE_OUTPUT_MARKER.find(output.takeLast(512))?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun commandOutputBackgroundJobId(output: String): String? =
    BACKGROUND_JOB_OUTPUT_MARKER.find(output.takeLast(512))?.groupValues?.getOrNull(1)

private fun commandOutputStartedBackgroundJob(output: String): Boolean =
    BACKGROUND_JOB_OUTPUT_MARKER.containsMatchIn(output.takeLast(512))

private fun extractCommandOutput(value: JsonElement?, depth: Int = 0): String {
    if (depth > 4 || value == null) return ""
    return when (value) {
        is JsonArray -> value.joinToString(separator = "") { extractCommandOutput(it, depth + 1) }
        is JsonObject -> {
            value.string("text")
                ?: value["content"]?.let { extractCommandOutput(it, depth + 1) }
                ?: value.string("output")
                ?: ""
        }
        is JsonPrimitive -> value.contentOrNull.orEmpty()
    }
}
