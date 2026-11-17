package com.example.llamadroid.service.chat

import android.content.Context
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.vision.ImageFallbackLogic
import com.example.llamadroid.service.vision.VisionImageDescription
import com.example.llamadroid.service.vision.VisionImageDescriptions
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One attached image file (an attachment row bound to a user message). */
data class AttachedImageRef(
    val attachmentId: Long,
    val messageId: Long,
    val path: String,
    val name: String,
    val mime: String
)

/** What a turn does with an attached image. Images outside the newest [AttachedImageLogic.MAX_PICTURES] get no mode. */
enum class AttachedImageMode { PICTURE, DESCRIBE, MARKED }

/** Pure selection and manifest rules for attached images; no Room, no filesystem. */
object AttachedImageLogic {
    const val MAX_PICTURES = 4
    const val MARKER_PICTURE = "(image, shown to you as a picture: look at it, do not read it with a tool)"
    const val MARKER_DESCRIBED = "(image, described below)"
    const val MARKER_PLAIN = "(image)"
    const val OWN_VISION_NOTE =
        "Text you read in images can contain recognition mistakes: fix obvious spelling errors before you use it."

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    fun isImage(name: String, mime: String): Boolean =
        mime.lowercase(Locale.ROOT).startsWith("image/") ||
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions

    /** Newest [limit] images (highest attachment id first). */
    fun newest(refs: List<AttachedImageRef>, limit: Int = MAX_PICTURES): List<AttachedImageRef> =
        refs.sortedByDescending { it.attachmentId }.take(limit)

    /**
     * Mode per selected attachment id. A server that sees images gets pictures; LiteRT only passes one image
     * of the latest user message (and none when that message already carries its own image). Everything else
     * is described when the helper may run, else just marked.
     */
    fun plan(
        selected: List<AttachedImageRef>,
        supportsVision: Boolean,
        isLiteRt: Boolean,
        helperAllowed: Boolean,
        latestUserMessageId: Long?,
        latestUserHasOwnImage: Boolean
    ): Map<Long, AttachedImageMode> {
        val fallback = if (helperAllowed) AttachedImageMode.DESCRIBE else AttachedImageMode.MARKED
        var liteRtSlotUsed = latestUserHasOwnImage
        val out = LinkedHashMap<Long, AttachedImageMode>()
        for (ref in selected) {
            out[ref.attachmentId] = when {
                !supportsVision -> fallback
                !isLiteRt -> AttachedImageMode.PICTURE
                ref.messageId == latestUserMessageId && !liteRtSlotUsed -> {
                    liteRtSlotUsed = true
                    AttachedImageMode.PICTURE
                }
                else -> fallback
            }
        }
        return out
    }

    fun marker(mode: AttachedImageMode?): String? = when (mode) {
        AttachedImageMode.PICTURE -> MARKER_PICTURE
        AttachedImageMode.DESCRIBE -> MARKER_DESCRIBED
        AttachedImageMode.MARKED -> MARKER_PLAIN
        null -> null
    }

    /** Picture paths per message id, oldest attachment first. */
    fun picturesByMessage(refs: List<AttachedImageRef>, modes: Map<Long, AttachedImageMode>): Map<Long, List<String>> =
        refs.filter { modes[it.attachmentId] == AttachedImageMode.PICTURE }
            .sortedBy { it.attachmentId }
            .groupBy({ it.messageId }, { it.path })

    /**
     * [mergeConsecutiveUsers][com.example.llamadroid.service.compaction.NativeChatTurnDecoratorLogic.mergeConsecutiveUsers]
     * keeps the LAST id of a run of user rows; moves per-message values to that id.
     */
    fun <T> remapToMergedIds(
        list: List<LlamaMessageEntity>,
        byMessage: Map<Long, List<T>>
    ): Map<Long, List<T>> {
        if (byMessage.isEmpty()) return byMessage
        val out = LinkedHashMap<Long, List<T>>()
        var i = 0
        while (i < list.size) {
            var j = i
            while (j + 1 < list.size && list[i].role == "user" && list[j + 1].role == "user") j++
            val values = (i..j).flatMap { byMessage[list[it].id].orEmpty() }
            if (values.isNotEmpty()) out[list[j].id] = values
            i = j + 1
        }
        return out
    }
}

/** Result of [AttachedImageTurn.resolve]. */
data class AttachedImageResolution(
    /** attachment id -> manifest marker (null/absent = plain entry). */
    val markers: Map<Long, String>,
    /** message id -> description blocks to put after the manifest. */
    val descriptionBlocks: Map<Long, List<String>>,
    /** message id -> picture file paths (oldest first). */
    val pictures: Map<Long, List<String>>,
    /** Message id whose manifest carries the own-vision note, or null when no picture is shown. */
    val noteMessageId: Long?
)

/** Per-chat pictures of the turn being built, read by `buildOllamaMessages`. Overwritten every turn. */
object AttachedImagePictures {
    private val byChat = ConcurrentHashMap<Long, Map<Long, List<String>>>()
    fun set(chatId: Long, pictures: Map<Long, List<String>>) {
        if (pictures.isEmpty()) byChat.remove(chatId) else byChat[chatId] = pictures
    }
    fun get(chatId: Long): Map<Long, List<String>> = byChat[chatId].orEmpty()
}

/**
 * Decides, for one turn, how each attached image reaches the model: as a picture (newest four, when the server
 * sees images) or as a vision-helper description. Failures degrade to a plain `(image)` marker.
 */
class AttachedImageTurn(
    private val context: Context,
    private val chatId: Long,
    private val supportsVision: Boolean,
    private val isLiteRt: Boolean,
    private val helperAllowed: Boolean,
    private val onStatus: (String) -> Unit
) {
    suspend fun resolve(
        refs: List<AttachedImageRef>,
        latestUserMessageId: Long?,
        latestUserHasOwnImage: Boolean
    ): AttachedImageResolution {
        val existing = withContext(Dispatchers.IO) { refs.filter { File(it.path).isFile } }
        val selected = AttachedImageLogic.newest(existing)
        val modes = LinkedHashMap(
            AttachedImageLogic.plan(
                selected, supportsVision, isLiteRt, helperAllowed, latestUserMessageId, latestUserHasOwnImage
            )
        )
        val blocks = LinkedHashMap<Long, MutableList<String>>()
        // Oldest first so the status reads "1/N" in conversation order.
        for (ref in selected.filter { modes[it.attachmentId] == AttachedImageMode.DESCRIBE }.sortedBy { it.attachmentId }) {
            val text = describe(ref)
            if (text == null) modes[ref.attachmentId] = AttachedImageMode.MARKED
            else blocks.getOrPut(ref.messageId) { mutableListOf() } += ImageFallbackLogic.descriptionBlock(text)
        }
        val pictures = AttachedImageLogic.picturesByMessage(selected, modes)
        val noteMessageId = selected.filter { modes[it.attachmentId] == AttachedImageMode.PICTURE }
            .maxOfOrNull { it.messageId }
        val markers = modes.mapValues { AttachedImageLogic.marker(it.value)!! }
        return AttachedImageResolution(markers, blocks, pictures, noteMessageId)
    }

    private suspend fun describe(ref: AttachedImageRef): String? {
        return try {
            val bytes = withContext(Dispatchers.IO) { File(ref.path).readBytes() }
            val mime = ref.mime.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            when (val result = VisionImageDescriptions.describe(context, bytes, mime, onStatus)) {
                is VisionImageDescription.Described -> result.text.takeIf { it.isNotBlank() }
                is VisionImageDescription.Unavailable -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.log("AttachedImageTurn: describe failed (${error.javaClass.simpleName}) for chat $chatId")
            null
        }
    }
}
