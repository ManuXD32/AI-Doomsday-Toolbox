package com.example.llamadroid.service.compaction

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.modelReference
import com.example.llamadroid.service.delegation.NativeChatDelegation
import com.example.llamadroid.service.delegation.NativeChatDelegationConfig
import com.example.llamadroid.util.DebugLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Per-turn, in-memory rewrites of the history sent to the model: binds pending user attachments,
 * applies the stored compaction boundary, lists attachments as a deterministic manifest line and
 * keeps roles alternating. Room message rows are never modified; only attachment `messageId`
 * bindings are written. Failures degrade to the unmodified history.
 */
object NativeChatTurnDecorators {

    suspend fun apply(context: Context, chatId: Long, history: List<LlamaMessageEntity>): List<LlamaMessageEntity> {
        if (history.isEmpty()) return history
        return try {
            val db = AppDatabase.getDatabase(context.applicationContext)
            val attachmentDao = db.llamaMessageAttachmentDao()

            // (a) bind pending USER attachments to the newest unanswered user message.
            val target = NativeChatTurnDecoratorLogic.bindTargetMessageId(history)
            if (target != null) {
                val pending = attachmentDao.getPending(chatId, LlamaMessageAttachmentSource.USER)
                if (pending.isNotEmpty()) attachmentDao.bind(pending.map { it.id }, target)
            }

            // (b) latest compaction boundary.
            var list = history
            db.nativeChatCompactionDao().getLatest(chatId)?.let { compaction ->
                list = NativeChatTurnDecoratorLogic.applyBoundary(
                    list, chatId, compaction.boundaryMessageId, compaction.summary
                )
            }

            // (c) manifest lines for user entities that own attachments.
            val byMessage = attachmentDao.getForChat(chatId)
                .filter { it.messageId != null }
                .groupBy { it.messageId!! }
            val delegationOn = delegationEnabled(db, chatId)
            list = list.map { entity ->
                if (entity.role != "user" || entity.id <= 0L) return@map entity
                val items = buildList {
                    byMessage[entity.id].orEmpty().sortedBy { it.id }.forEach {
                        add(AttachmentManifestItem(it.modelReference, it.displayName, it.sizeBytes))
                    }
                    val image = entity.imagePath
                    if (delegationOn && !image.isNullOrBlank()) {
                        val file = File(image)
                        add(AttachmentManifestItem("img${entity.id}", file.name.ifBlank { "image" }, file.length()))
                    }
                }
                if (items.isEmpty()) entity
                else entity.copy(content = NativeChatTurnDecoratorLogic.appendManifest(entity.content, items))
            }

            // (d) keep strict templates alternating.
            NativeChatTurnDecoratorLogic.mergeConsecutiveUsers(list)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.log("NativeChatTurnDecorators: apply failed ${error.javaClass.simpleName}")
            history
        }
    }

    /** Binds delivered DeepSeek result files to the finished assistant reply. */
    suspend fun onTurnCompleted(context: Context, chatId: Long, assistantMessageId: Long) {
        try {
            val dao = AppDatabase.getDatabase(context.applicationContext).llamaMessageAttachmentDao()
            val pending = dao.getPending(chatId, LlamaMessageAttachmentSource.DEEPSEEK)
            if (pending.isEmpty()) return
            if (dao.bind(pending.map { it.id }, assistantMessageId) > 0) {
                NativeChatDelegation.get(context).markResultDelivered(chatId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.log("NativeChatTurnDecorators: onTurnCompleted failed ${error.javaClass.simpleName}")
        }
    }

    private suspend fun delegationEnabled(db: AppDatabase, chatId: Long): Boolean = runCatching {
        val json = db.llamaChatDao().getChatById(chatId)?.apiParams ?: return false
        @Suppress("UNCHECKED_CAST")
        val map = Gson().fromJson(json, Map::class.java) as? Map<String, Any?> ?: return false
        NativeChatDelegationConfig.fromParams(map).enabled
    }.getOrDefault(false)
}
