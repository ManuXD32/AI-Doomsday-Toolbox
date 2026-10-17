package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.modelReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Files the main LLM forgot to list in `attachments`: the files and image attached to the chat's newest
 * user message are handed to DeepSeek automatically (ids as in [DelegationFileBridge]: `f<id>`, `img<id>`).
 */
object DelegationAutoAttach {
    /** Pure selection: attachment refs first, then the message image as `img<messageId>`. */
    fun refsFor(messageId: Long, imagePath: String?, attachments: List<LlamaMessageAttachmentEntity>): List<String> =
        (attachments.map { it.modelReference } + listOfNotNull(imagePath?.takeIf { it.isNotBlank() }?.let { "img$messageId" }))
            .distinct()
            .take(DelegationToolArgs.MAX_ATTACHMENTS)

    /** True when a follow-up message explicitly refers to attached files ("attached", "attachment", "adjunto"). */
    fun mentionsAttached(message: String): Boolean =
        message.lowercase(Locale.ROOT).let { "attach" in it || "adjunt" in it }

    /** Refs of the newest user message of [chatId]; empty when it has none. */
    suspend fun newestUserRefs(context: Context, chatId: Long): List<String> = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val message = db.llamaMessageDao().getMessagesOnce(chatId).lastOrNull { it.role == "user" }
            ?: return@withContext emptyList()
        refsFor(message.id, message.imagePath, db.llamaMessageAttachmentDao().getForMessage(message.id))
    }

    /** "Files given to DeepSeek: f5 report.zip, img9 photo.jpg" or a sentence saying none were. */
    suspend fun describe(context: Context, chatId: Long, refs: List<String>): String {
        if (refs.isEmpty()) return "No files were handed over to DeepSeek (the newest user message has no attachments)."
        val names = DelegationFileBridge(context).resolve(chatId, refs).inputs
        if (names.isEmpty()) return "No files were handed over to DeepSeek (the attached files are no longer available)."
        return "Files given to DeepSeek: " + names.joinToString(", ") { "${it.ref} ${it.displayName}" }
    }
}
