package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.ConcurrentHashMap

/** Raw history cursors must survive transcript filtering and bounded rendering. */
internal data class HarnessSessionPageState(
    val throughSeq: Long,
    val beforeSeq: Long?
)

internal fun harnessRecordSequence(record: JsonObject): Long? =
    record.long("seq") ?: record.objectValue("event")?.long("seq")

internal fun harnessMinimumRecordSequence(records: List<JsonObject>): Long? =
    records.mapNotNull(::harnessRecordSequence).minOrNull()

/** Replays the official compact baseline without turning metadata into chat text. */
internal fun harnessCompactedStreamText(stream: List<JsonObject>): String =
    stream.joinToString("") { record ->
        when (record.string("type")) {
            "text-chunks", "reasoning-chunks" -> record.stringArray("texts").joinToString("")
            "chunk" -> harnessAssistantChunkText(record.objectValue("chunk"))
            else -> ""
        }
    }

/** Only text/reasoning blocks belong in the live assistant preview. */
internal fun harnessAssistantChunkText(chunk: JsonElement?): String {
    val value = chunk?.jsonObjectOrNull() ?: return ""
    return when (value.string("type")) {
        "text-delta", "reasoning-delta" -> value.string("text").orEmpty()
        // block-end carries the complete assembled block after its deltas. The
        // native preview already appended those deltas, so appending this value
        // would duplicate every streamed sentence.
        "block-end" -> ""
        else -> ""
    }
}

internal enum class HarnessAssistantFrameResult {
    START,
    CHUNK,
    END,
    IGNORE,
    INVALID
}

/** Validates the revision/attempt/index contract before mutating the transcript. */
internal class HarnessAssistantStreamTracker {
    private var revision: Int? = null
    private var attemptId: String? = null
    private var nextIndex: Int? = null

    fun reset(baseline: JsonObject?) {
        revision = baseline?.int("revision")
        val attempt = baseline?.objectValue("activeAttempt")
        attemptId = attempt?.string("attemptId")
        nextIndex = attempt?.int("nextIndex")
    }

    fun accept(frame: JsonObject): HarnessAssistantFrameResult {
        val type = frame.string("type") ?: return HarnessAssistantFrameResult.INVALID
        val frameRevision = frame.int("revision") ?: return HarnessAssistantFrameResult.INVALID
        val previousRevision = revision
        if (previousRevision != null && frameRevision != previousRevision + 1) {
            return HarnessAssistantFrameResult.INVALID
        }
        val frameAttempt = frame.string("attemptId")
        return when (type) {
            "start" -> {
                val startAttempt = frameAttempt?.takeIf(String::isNotBlank)
                    ?: return HarnessAssistantFrameResult.INVALID
                if (attemptId != null) return HarnessAssistantFrameResult.INVALID
                revision = frameRevision
                attemptId = startAttempt
                nextIndex = 0
                HarnessAssistantFrameResult.START
            }
            "chunk" -> {
                revision = frameRevision
                if (attemptId == null) return HarnessAssistantFrameResult.IGNORE
                if (frameAttempt != attemptId || frame.int("index") != nextIndex) {
                    return HarnessAssistantFrameResult.INVALID
                }
                nextIndex = requireNotNull(nextIndex) + 1
                HarnessAssistantFrameResult.CHUNK
            }
            "end" -> {
                revision = frameRevision
                if (attemptId == null) return HarnessAssistantFrameResult.IGNORE
                if (frameAttempt != attemptId || frame.int("index") != nextIndex) {
                    return HarnessAssistantFrameResult.INVALID
                }
                attemptId = null
                nextIndex = null
                HarnessAssistantFrameResult.END
            }
            else -> HarnessAssistantFrameResult.INVALID
        }
    }
}

/** Keeps stream-frame parsing out of the controller's lifecycle and action code. */
internal class NativeHarnessAssistantStreamUi(
    private val trackers: ConcurrentHashMap<String, HarnessAssistantStreamTracker>,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit
) {
    suspend fun showBaseline(sessionId: String, text: String) {
        if (text.isNotBlank()) update(sessionId, text, isStreaming = true)
    }

    suspend fun apply(sessionId: String, frame: JsonObject) {
        val tracker = trackers.computeIfAbsent(sessionId) { HarnessAssistantStreamTracker() }
        when (tracker.accept(frame)) {
            HarnessAssistantFrameResult.INVALID -> throw IllegalStateException("assistant stream continuity error")
            HarnessAssistantFrameResult.IGNORE -> return
            else -> Unit
        }
        when (frame.string("type")) {
            "start" -> update(sessionId, "", isStreaming = true)
            "chunk" -> harnessAssistantChunkText(frame["chunk"] ?: JsonNull)
                .takeIf(String::isNotEmpty)
                ?.let { update(sessionId, it, isStreaming = true, append = true) }
            "end" -> mutate { current ->
                current.copy(
                    canCancelTurn = false,
                    transcript = current.transcript.map { item ->
                        if (item.id.startsWith("stream-$sessionId")) item.copy(isStreaming = false) else item
                    }
                )
            }
        }
    }

    private suspend fun update(
        sessionId: String,
        text: String,
        isStreaming: Boolean,
        append: Boolean = false
    ) {
        mutate { current ->
            val id = "stream-$sessionId"
            val existing = current.transcript.firstOrNull { it.id == id }
            val nextText = if (append) (existing?.text.orEmpty() + text) else text
            val item = HarnessTranscriptItem(
                id = id,
                role = HarnessTranscriptRole.ASSISTANT,
                text = boundedHarnessMessage(nextText),
                label = "Harness",
                isStreaming = isStreaming,
                isExpandable = nextText.length > 2_000,
                isExpanded = existing?.isExpanded ?: false
            )
            current.copy(
                transcript = boundedHarnessTranscript(current.transcript.filterNot { it.id == id } + item),
                canCancelTurn = isStreaming
            )
        }
    }
}
