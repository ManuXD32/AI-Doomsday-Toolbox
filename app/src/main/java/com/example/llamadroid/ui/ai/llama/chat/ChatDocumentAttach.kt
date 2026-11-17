package com.example.llamadroid.ui.ai.llama.chat

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeChunkEntity
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.service.KnowledgeBaseIndexingService
import com.example.llamadroid.service.NativeChatAttachmentSupport
import com.example.llamadroid.service.NativeChatWorkspaceSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Attach document" in the native chat. The picked file is copied into the chat workspace
 * (`attachments/<name>`) and registered as a pending USER attachment, exactly like the composer's "Attach file", so the
 * model gets an `[Attached files: f12 attachments/<name> (size)]` line on the next sent message and can read it with
 * `chat_file_read` (and with `read_workspace_file` when workspace tools are on). The same copy is queued into the chat's
 * document knowledge base (the source reference is the copy's `file:` URI, which links source and attachment row).
 * Once the indexer is done, the text it embedded is written to `attachments/<name>.txt` and registered as well.
 */

/** Sources still being processed longer than this without a progress update stop blocking Send (killed indexer). */
internal const val DOCUMENT_INDEX_STALE_MS: Long = 10L * 60L * 1000L

private val DOCUMENT_PENDING_STATUSES = setOf(
    KnowledgeBaseSourceStatus.QUEUED,
    KnowledgeBaseSourceStatus.EXTRACTING,
    KnowledgeBaseSourceStatus.CHUNKING,
    KnowledgeBaseSourceStatus.EMBEDDING,
    KnowledgeBaseSourceStatus.INDEXING
)

/** Aggregated state of the chat's document sources; [canSend] is false while any source is still being indexed. */
internal data class DocumentIndexSummary(val total: Int, val finished: Int, val failed: Int) {
    val pending: Int get() = total - finished
    val canSend: Boolean get() = pending <= 0
}

internal fun summarizeDocumentIndexing(
    sources: List<KnowledgeSourceEntity>,
    nowMs: Long,
    staleAfterMs: Long = DOCUMENT_INDEX_STALE_MS
): DocumentIndexSummary {
    var pending = 0
    var failed = 0
    sources.forEach { source ->
        val lastSignal = maxOf(source.progressUpdatedAt ?: 0L, source.updatedAt)
        val stale = nowMs - lastSignal > staleAfterMs
        if (source.status in DOCUMENT_PENDING_STATUSES && !stale) pending++
        if (source.status == KnowledgeBaseSourceStatus.ERROR) failed++
    }
    return DocumentIndexSummary(total = sources.size, finished = sources.size - pending, failed = failed)
}

/** Knowledge-source reference of a workspace copy; the same string is used to find the attachment row again. */
internal fun chatDocumentSourceRef(path: String): String = File(path).toURI().toString()

/** Path of the extracted-text companion of an attachment copy. */
internal fun chatDocumentCompanionPath(path: String): String = "$path.txt"

/** Text files are already readable as they are; every other type gets an extracted-text companion. */
internal fun chatDocumentNeedsCompanion(displayName: String, mime: String): Boolean =
    !NativeChatAttachmentSupport.isTextLike(displayName, mime)

/**
 * Rebuilds the indexed text chunk by chunk (feed chunks in index order). A chunk's `startOffset` before the previous
 * `endOffset` marks an overlap; the overlapping prefix is dropped so the result reads as the document, not as repeats.
 */
internal class ChunkTextStitcher {
    private var previousEnd = 0
    private var started = false

    /** Text to append for [chunk] (a newline separates chunks that do not overlap). */
    fun next(chunk: KnowledgeChunkEntity): String {
        val overlap = if (started) (previousEnd - chunk.startOffset).coerceIn(0, chunk.text.length) else 0
        val piece = (if (started && overlap == 0) "\n" else "") + chunk.text.substring(overlap)
        started = true
        previousEnd = maxOf(previousEnd, chunk.endOffset)
        return piece
    }
}

internal fun reassembleChunkText(chunks: List<KnowledgeChunkEntity>): String {
    val stitcher = ChunkTextStitcher()
    return chunks.sortedBy { it.chunkIndex }.joinToString("") { stitcher.next(it) }
}

/**
 * Document mode only turns the document knowledge base and its tools ON; every other tool setting is left untouched.
 */
internal fun applyDocumentModeSettings(settings: ChatSettingsState, knowledgeBaseId: Long) {
    with(settings) {
        chatDocumentKnowledgeBaseId = knowledgeBaseId
        toolsEnabled = true
        knowledgeBaseEnabled = true
        knowledgeBaseAutoContextEnabled = true
    }
}

/**
 * Picker handler. [switchToDocumentMode] applies the chat settings for document mode (kept in `LlamaChatScreen`) and
 * persists them for the given knowledge base id. Failures never leave a half-attached file: a copy whose queueing fails
 * is removed again.
 */
internal suspend fun handlePickedChatDocuments(
    context: Context,
    chatId: Long,
    uris: List<Uri>,
    knowledgeBaseRepository: KnowledgeBaseRepository,
    existingKnowledgeBaseId: Long?,
    chatTitle: String,
    strings: LlamaChatLocalizedStrings,
    onNeedsEmbedding: () -> Unit,
    switchToDocumentMode: suspend (knowledgeBaseId: Long) -> Unit
) {
    try {
        if (!knowledgeBaseRepository.currentEmbeddingConfig().isConfigured) {
            // The caller shows the shared embedding chooser dialog and re-runs the attach with the same URIs.
            onNeedsEmbedding()
            return
        }
        val knowledgeBaseId = existingKnowledgeBaseId
            ?: knowledgeBaseRepository.ensureChatDocumentKnowledgeBase(chatId = chatId, chatTitle = chatTitle)
        switchToDocumentMode(knowledgeBaseId)

        val results = importChatFiles(context, chatId, uris)
        toastChatFileImportErrors(context, results)
        val dao = AppDatabase.getDatabase(context).llamaMessageAttachmentDao()
        var queued = 0
        results.filterIsInstance<ChatFileImportResult.Added>().forEach { added ->
            val path = added.path ?: return@forEach
            val queuedId = runCatching {
                knowledgeBaseRepository.queueFile(
                    knowledgeBaseId = knowledgeBaseId,
                    uri = chatDocumentSourceRef(path).toUri(),
                    displayName = added.displayName
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    formatLlamaChatString(strings.documentQueueFailedFormat, error.message ?: strings.errorGeneric),
                    Toast.LENGTH_LONG
                ).show()
                dao.getPending(chatId, LlamaMessageAttachmentSource.USER).firstOrNull { it.path == path }
                    ?.let { removePendingChatFile(context, it) }
            }.getOrNull()
            if (queuedId != null) {
                KnowledgeBaseIndexingService.enqueueQueuedFile(context, queuedId)
                queued++
            }
        }
        if (queued > 0) {
            Toast.makeText(context, formatLlamaChatString(strings.documentQueuedForChatFormat, queued), Toast.LENGTH_SHORT).show()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Toast.makeText(
            context,
            formatLlamaChatString(strings.documentQueueFailedFormat, e.message ?: strings.errorGeneric),
            Toast.LENGTH_LONG
        ).show()
    }
}

/** Indexing summary of the chat's document sources; re-evaluated every 30 s while something is pending (stale rule). */
@Composable
internal fun rememberDocumentIndexSummary(sources: List<KnowledgeSourceEntity>): DocumentIndexSummary {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val summary = remember(sources, now) { summarizeDocumentIndexing(sources, now) }
    LaunchedEffect(sources, summary.pending > 0) {
        while (summary.pending > 0) {
            kotlinx.coroutines.delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    return summary
}

/**
 * Follows the document sources of the chat (state comes from Room, so it survives leaving the chat): writes the
 * extracted-text companion of each freshly indexed document and drops the attachment of each failed one (a failed
 * document is not announced to the model). Send gating uses [rememberDocumentIndexSummary].
 */
@Composable
internal fun ChatDocumentIndexFollower(chatId: Long, sources: List<KnowledgeSourceEntity>) {
    val context = LocalContext.current.applicationContext
    val signature = remember(sources) { sources.joinToString("|") { "${it.id}:${it.status}" } }
    LaunchedEffect(chatId, signature) {
        if (chatId <= 0L || sources.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) { finalizeChatDocuments(context, chatId, sources) }
    }
}

private suspend fun finalizeChatDocuments(context: Context, chatId: Long, sources: List<KnowledgeSourceEntity>) {
    val db = AppDatabase.getDatabase(context)
    val dao = db.llamaMessageAttachmentDao()
    val attachmentsDir = NativeChatWorkspaceSupport.rootIfPresent(context, chatId)
        .resolve(NativeChatWorkspaceSupport.ATTACHMENTS_DIR)
    sources.filter { it.sourceRef.startsWith("file:") }.forEach { source ->
        val path = runCatching { File(java.net.URI(source.sourceRef)) }.getOrNull() ?: return@forEach
        if (!NativeChatAttachmentSupport.isInside(attachmentsDir, path)) return@forEach
        val rows = dao.getForChat(chatId)
        val row = rows.firstOrNull { it.path == path.absolutePath && it.source == LlamaMessageAttachmentSource.USER }
            ?: return@forEach
        when (source.status) {
            KnowledgeBaseSourceStatus.ERROR -> if (row.messageId == null) {
                removePendingChatFile(context, row)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.native_chat_document_index_failed, row.displayName, source.errorMessage.orEmpty()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            KnowledgeBaseSourceStatus.INDEXED -> {
                if (!chatDocumentNeedsCompanion(row.displayName, row.mimeType)) return@forEach
                val companion = File(chatDocumentCompanionPath(path.absolutePath))
                if (companion.exists() || rows.any { it.path == companion.absolutePath }) return@forEach
                if (!writeCompanion(db, source.id, companion)) return@forEach
                dao.insert(
                    LlamaMessageAttachmentEntity(
                        messageId = null,
                        chatId = chatId,
                        path = companion.absolutePath,
                        displayName = "${row.displayName}.txt",
                        mimeType = "text/plain",
                        sizeBytes = companion.length(),
                        source = LlamaMessageAttachmentSource.USER
                    )
                )
            }
        }
    }
}

/** Streams the indexed chunks of [sourceId] into [target] (paged, so large documents stay out of memory); false when empty or binary. */
private suspend fun writeCompanion(db: AppDatabase, sourceId: Long, target: File): Boolean {
    val tmp = File(target.path + ".part")
    var sniffed = false
    var wrote = false
    try {
        tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
            val stitcher = ChunkTextStitcher()
            var offset = 0
            while (true) {
                val page = db.knowledgeBaseDao().getChunksForSourcePaged(sourceId, COMPANION_PAGE, offset)
                if (page.isEmpty()) break
                page.forEach { chunk ->
                    val piece = stitcher.next(chunk)
                    if (!sniffed) {
                        sniffed = true
                        if (NativeChatAttachmentSupport.looksBinary(piece.toByteArray(Charsets.UTF_8))) return false
                    }
                    writer.write(piece)
                    wrote = true
                }
                offset += page.size
            }
        }
        if (!wrote || tmp.length() == 0L) return false
        return tmp.renameTo(target)
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

private const val COMPANION_PAGE = 200
