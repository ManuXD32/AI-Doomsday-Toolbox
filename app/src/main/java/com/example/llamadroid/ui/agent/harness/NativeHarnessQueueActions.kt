package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/** Limits for the Host-owned inbox projection at the Android presentation boundary. */
internal const val MAX_HARNESS_QUEUE_ITEMS = 64
internal const val MAX_HARNESS_QUEUE_CONTENT_BLOCKS = 64
internal const val MAX_HARNESS_QUEUE_ATTACHMENTS = 16
internal const val MAX_HARNESS_QUEUE_PREVIEW_CHARS = 200
internal const val MAX_HARNESS_QUEUE_EDIT_CHARS = 64_000

/** Metadata for one durable queued image/file reference. Bytes are never loaded here. */
data class HarnessQueueAttachmentUi(
    val id: String,
    val name: String? = null,
    val mediaType: String? = null,
    val bytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isImage: Boolean = false,
    /** Queue attachments use the existing authenticated OpenAttachment route. */
    val canOpen: Boolean = true,
)

/**
 * Host-authoritative inbox projection cache. The Session control stream and the
 * session.list/follow snapshots can race, so every session keeps the highest
 * accepted Host sequence. Raw JSON and unbounded message content are discarded
 * after the bounded presentation model is built.
 */
internal class NativeHarnessQueueStore {
    private data class SessionValue(
        val sequence: Long,
        val baseItems: List<HarnessQueueItemUi>,
    )

    private val lock = Any()
    private val values = mutableMapOf<String, SessionValue>()
    private val mutableBySession = mutableMapOf<String, Boolean>()
    private val runningBySession = mutableMapOf<String, Boolean>()

    fun resetClient() = synchronized(lock) {
        values.clear()
        mutableBySession.clear()
        runningBySession.clear()
    }

    fun resetSession(sessionId: String) = synchronized(lock) {
        values.remove(sessionId)
        mutableBySession.remove(sessionId)
        runningBySession.remove(sessionId)
    }

    /** Optional exact Session snapshot capability; list.origin is not a subagent mode. */
    fun setSessionCapabilities(
        sessionId: String,
        queueMutable: Boolean? = null,
        running: Boolean? = null,
    ) = synchronized(lock) {
        if (queueMutable != null) mutableBySession[sessionId] = queueMutable
        if (running != null) runningBySession[sessionId] = running
    }

    fun items(sessionId: String): List<HarnessQueueItemUi> = synchronized(lock) {
        values[sessionId]?.baseItems?.map { applyCapabilities(sessionId, it) }.orEmpty()
    }

    fun applyListSnapshot(sessionId: String, row: JsonObject): Boolean {
        val projection = row.objectValue("projections") ?: return false
        val sequence = projection.long("asOfSeq") ?: row.long("asOfSeq") ?: return false
        val values = projection.objectValue("values") ?: return false
        val inbox = values["inbox"] ?: return false
        return applyInbox(sessionId, sequence, inbox)
    }

    fun applyBaseline(sessionId: String, sequence: Long, projectionValues: JsonObject): Boolean {
        val inbox = projectionValues["inbox"] ?: return false
        return applyInbox(sessionId, sequence, inbox)
    }

    fun applyProjection(sessionId: String, sequence: Long, key: String, value: JsonElement): Boolean {
        if (key != "inbox") return false
        return applyInbox(sessionId, sequence, value)
    }

    fun applyFollowSnapshot(sessionId: String, cursor: Long, projections: JsonObject?): Boolean {
        val envelope = projections ?: return false
        val sequence = envelope.long("asOfSeq") ?: cursor
        val projectionValues = envelope.objectValue("values") ?: return false
        val inbox = projectionValues["inbox"] ?: return false
        return applyInbox(sessionId, sequence, inbox)
    }

    private fun applyInbox(sessionId: String, sequence: Long, value: JsonElement): Boolean {
        if (sessionId.isBlank() || sequence < 0L) return false
        val parsed = parseInbox(value)
        return synchronized(lock) {
            val previous = values[sessionId]
            if (previous != null && sequence < previous.sequence) return@synchronized false
            values[sessionId] = SessionValue(sequence, parsed)
            true
        }
    }

    private fun applyCapabilities(sessionId: String, item: HarnessQueueItemUi): HarnessQueueItemUi {
        val mutable = mutableBySession[sessionId] ?: true
        val running = runningBySession[sessionId]
        return item.copy(
            canEdit = item.canEdit && mutable,
            canRemove = item.canRemove && mutable,
            canSteer = item.canSteer && mutable && running != false,
        )
    }

    private fun parseInbox(value: JsonElement): List<HarnessQueueItemUi> {
        val inbox = value.jsonObjectOrNull() ?: return emptyList()
        return inbox["next-turn"]?.jsonArrayOrNull()
            ?.take(MAX_HARNESS_QUEUE_ITEMS)
            ?.mapNotNull(::parseItem)
            .orEmpty()
    }

    private fun parseItem(row: JsonElement): HarnessQueueItemUi? {
        val objectRow = row.jsonObjectOrNull() ?: return null
        val id = objectRow.string("id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val content = objectRow["content"]?.jsonArrayOrNull() ?: return null
        val contentTruncated = content.size > MAX_HARNESS_QUEUE_CONTENT_BLOCKS
        val blocks = content.take(MAX_HARNESS_QUEUE_CONTENT_BLOCKS)

        val preview = StringBuilder()
        val fullText = StringBuilder()
        val attachments = ArrayList<HarnessQueueAttachmentUi>()
        var textOnly = !contentTruncated
        var fullTextTooLarge = false
        for (block in blocks) {
            val type = block.string("type")?.lowercase(Locale.ROOT)
            when (type) {
                "text" -> {
                    val text = block.string("text").orEmpty()
                    appendPreview(preview, text)
                    if (!fullTextTooLarge) {
                        val remaining = MAX_HARNESS_QUEUE_EDIT_CHARS + 1 - fullText.length
                        if (remaining > 0) fullText.append(text.take(remaining))
                        if (fullText.length > MAX_HARNESS_QUEUE_EDIT_CHARS) fullTextTooLarge = true
                    }
                }
                "image", "file" -> {
                    textOnly = false
                    if (attachments.size < MAX_HARNESS_QUEUE_ATTACHMENTS) {
                        parseAttachment(block.objectValue("attachment"), image = type == "image")
                            ?.let(attachments::add)
                    }
                }
                else -> {
                    textOnly = false
                    appendPreview(preview, "[${type ?: "content"}]")
                }
            }
        }
        val normalizedPreview = collapseWhitespace(preview.toString())
        val isTruncated = contentTruncated || normalizedPreview.length > MAX_HARNESS_QUEUE_PREVIEW_CHARS
        val boundedPreview = if (isTruncated) {
            normalizedPreview.take(MAX_HARNESS_QUEUE_PREVIEW_CHARS) + "…"
        } else {
            normalizedPreview
        }
        val editText = if (textOnly && !fullTextTooLarge) fullText.toString() else null
        return HarnessQueueItemUi(
            id = id,
            text = boundedPreview,
            editText = editText,
            textTruncated = isTruncated,
            attachments = attachments,
            canEdit = textOnly && editText != null,
            canRemove = true,
            canSteer = true,
        )
    }

    private fun parseAttachment(value: JsonObject?, image: Boolean): HarnessQueueAttachmentUi? {
        val attachment = value ?: return null
        val id = attachment.string("attachmentId")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        return HarnessQueueAttachmentUi(
            id = id,
            name = attachment.string("name")?.bounded(512),
            mediaType = attachment.string("mediaType")?.bounded(128),
            bytes = attachment.long("bytes")?.takeIf { it >= 0L },
            width = attachment.int("width")?.takeIf { it in 1..100_000 },
            height = attachment.int("height")?.takeIf { it in 1..100_000 },
            isImage = image,
            canOpen = true,
        )
    }

    private fun appendPreview(builder: StringBuilder, value: String) {
        val remaining = (MAX_HARNESS_QUEUE_PREVIEW_CHARS * 4 + 1 - builder.length).coerceAtLeast(0)
        if (remaining > 0) builder.append(value.take(remaining))
    }

    private fun collapseWhitespace(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.bounded(limit: Int): String = take(limit)
}

/** Queue operations are transport-only; the Host projection remains the UI authority. */
internal class NativeHarnessQueueActions(
    private val clientProvider: () -> HarnessClient?,
    private val selectedSessionProvider: () -> String?,
    private val reportFailure: suspend (String, String) -> Unit,
) {
    suspend fun edit(itemId: String, content: String) {
        if (content.trim().isBlank()) return
        dispatch(itemId, buildJsonObject {
            put("kind", "edit")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
            }
        })
    }

    suspend fun remove(itemId: String) {
        dispatch(itemId, buildJsonObject { put("kind", "remove") })
    }

    suspend fun steer(itemId: String) {
        dispatch(itemId, buildJsonObject { put("kind", "steer") })
    }

    private suspend fun dispatch(itemId: String, action: JsonObject) {
        val sessionId = selectedSessionProvider()?.takeIf { it.isNotBlank() } ?: return
        val client = clientProvider() ?: return
        when (val result = client.call(
            "session",
            "updateQueue",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", sessionId)
                    put("itemId", itemId)
                    put("action", action)
                }
            },
            HarnessCallPolicy.NoRetry,
        )) {
            is HarnessRpcResult.Failure -> {
                if (selectedSessionProvider() == sessionId) {
                    reportFailure(result.error.code, result.error.message)
                }
            }
            is HarnessRpcResult.Success -> Unit
        }
    }
}
