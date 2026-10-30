package com.example.llamadroid.service.compaction

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.modelReference
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.NativeChatWorkspaceSupport
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
        // Read only in the catch below; kept in a holder so the compiler sees the assignments as used.
        val progress = DecoratorProgress(stage = "bind", fallback = history)
        return try {
            val db = AppDatabase.getDatabase(context.applicationContext)
            val attachmentDao = db.llamaMessageAttachmentDao()

            // (a) bind pending USER attachments to the newest unanswered user message.
            val target = NativeChatTurnDecoratorLogic.bindTargetMessageId(history)
            if (target != null) {
                val pending = attachmentDao.getPending(chatId, LlamaMessageAttachmentSource.USER)
                if (pending.isNotEmpty()) attachmentDao.bind(pending.map { it.id }, target)
            }
            progress.stage = "compaction"

            // (b) latest compaction boundary.
            var list = history
            db.nativeChatCompactionDao().getLatest(chatId)?.let { compaction ->
                list = NativeChatTurnDecoratorLogic.applyBoundary(
                    list, chatId, compaction.boundaryMessageId, compaction.summary
                )
            }

            progress.fallback = list
            progress.stage = "manifest"
            // (c) manifest lines for user turns (files, images) and assistant turns (DeepSeek result files).
            val byMessage = attachmentDao.getForChat(chatId)
                .filter { it.messageId != null }
                .groupBy { it.messageId!! }
            val delegationOn = attachmentsListed(db, chatId)
            val workspaceRoot = NativeChatWorkspaceSupport.rootIfPresent(context, chatId)
            fun manifestItem(row: LlamaMessageAttachmentEntity) = AttachmentManifestItem(
                row.modelReference,
                NativeChatTurnDecoratorLogic.workspaceRelativePath(workspaceRoot, File(row.path)) ?: row.displayName,
                row.sizeBytes
            )
            list = list.map { entity ->
                if (entity.id <= 0L) return@map entity
                if (entity.role == "assistant") {
                    // DeepSeek result files are bound to the reply that relayed them; listing them there
                    // keeps their ids visible for follow-ups even after the finished notice is compacted away.
                    val results = byMessage[entity.id].orEmpty().sortedBy { it.id }
                        .map { manifestItem(it) }
                    return@map if (results.isEmpty()) entity
                    else entity.copy(content = NativeChatTurnDecoratorLogic.appendManifest(entity.content, results))
                }
                if (entity.role != "user") return@map entity
                val items = buildList {
                    byMessage[entity.id].orEmpty().sortedBy { it.id }.forEach {
                        add(manifestItem(it))
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
            progress.stage = "merge"
            progress.fallback = list
            NativeChatTurnDecoratorLogic.mergeConsecutiveUsers(list)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Never silent: a failure here would otherwise drop the [Attached files: ...] lines unnoticed.
            DebugLog.log(
                "NativeChatTurnDecorators: apply failed at stage=${progress.stage} (${error.javaClass.simpleName}); " +
                    "sending the history without the remaining rewrites, attachment manifests may be missing"
            )
            progress.fallback
        }
    }

    private class DecoratorProgress(var stage: String, var fallback: List<LlamaMessageEntity>)

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

    /** Image attachments are listed (`imgNN`) whenever the chat can read them: tools or delegation on. */
    private suspend fun attachmentsListed(db: AppDatabase, chatId: Long): Boolean = runCatching {
        val json = db.llamaChatDao().getChatById(chatId)?.apiParams ?: return false
        @Suppress("UNCHECKED_CAST")
        val map = Gson().fromJson(json, Map::class.java) as? Map<String, Any?> ?: return false
        NativeChatDelegationConfig.fromParams(map).enabled ||
            map[NativeChatToolConfig.KEY_TOOLS_ENABLED] == true
    }.getOrDefault(false)
}
