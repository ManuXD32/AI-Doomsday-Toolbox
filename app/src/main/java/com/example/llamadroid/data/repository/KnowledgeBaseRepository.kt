package com.example.llamadroid.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseDao
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeBaseSourceType
import com.example.llamadroid.data.db.KnowledgeChunkEntity
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.service.LlamaConfig
import com.example.llamadroid.service.KnowledgeBaseDiagnostics
import com.example.llamadroid.service.DeepResearchSupport
import com.example.llamadroid.service.ProcessController
import com.example.llamadroid.service.extractNativePdfTextFromBytes
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.NativeProcessCleanup
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.io.Reader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.sqrt

data class KnowledgeBaseSearchResult(
    val chunkId: Long,
    val knowledgeBaseId: Long,
    val knowledgeBaseName: String,
    val sourceId: Long,
    val sourceTitle: String,
    val chunkIndex: Int,
    val score: Float,
    val text: String
) {
    val citationMarkdown: String
        get() = KnowledgeBaseRepository.chunkCitationMarkdown(sourceTitle, chunkIndex, chunkId)
}

data class KnowledgeChunkWindowItem(
    val chunkId: Long,
    val chunkIndex: Int,
    val text: String,
    val isTarget: Boolean
) {
    val citationMarkdown: String
        get() = KnowledgeBaseRepository.chunkCitationMarkdown("", chunkIndex, chunkId)
}

data class KnowledgeChunkWindow(
    val knowledgeBaseId: Long,
    val knowledgeBaseName: String,
    val sourceId: Long,
    val sourceTitle: String,
    val targetChunkId: Long,
    val targetChunkIndex: Int,
    val chunks: List<KnowledgeChunkWindowItem>
)

data class KnowledgeBaseStats(
    val baseCount: Int,
    val sourceCount: Int,
    val chunkCount: Int,
    val pendingSourceCount: Int,
    val errorSourceCount: Int
)

data class KnowledgeBaseIndexProgress(
    val sourceId: Long,
    val sourceTitle: String,
    val stage: String,
    val done: Int,
    val total: Int
)

data class KnowledgeEmbeddingConfig(
    val backend: String,
    val label: String,
    val localModelPath: String?,
    val url: String?,
    val remoteModel: String?
) {
    val isConfigured: Boolean
        get() = when (backend) {
            SettingsRepository.KB_EMBED_BACKEND_LOCAL -> !localModelPath.isNullOrBlank()
            SettingsRepository.KB_EMBED_BACKEND_LLAMA_SERVER -> !url.isNullOrBlank()
            SettingsRepository.KB_EMBED_BACKEND_OLLAMA,
            SettingsRepository.KB_EMBED_BACKEND_LLAMA_SWAP -> !url.isNullOrBlank() && !remoteModel.isNullOrBlank()
            else -> false
        }

    val hash: String
        get() = KnowledgeBaseRepository.sha256(
            listOf(backend, localModelPath.orEmpty(), url.orEmpty(), remoteModel.orEmpty())
                .joinToString("|")
        )
}

data class KnowledgeEmbeddingServerStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val modelLabel: String? = null,
    val binaryName: String? = null,
    val port: Int = KnowledgeEmbeddingService.EMBEDDING_PORT,
    val host: String = KnowledgeEmbeddingService.LOCALHOST,
    val message: String? = null
)

class KnowledgeBaseRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val dao: KnowledgeBaseDao = database.knowledgeBaseDao(),
    private val embeddingService: KnowledgeEmbeddingService = KnowledgeEmbeddingService(context),
    private val progressReporter: ((KnowledgeBaseIndexProgress) -> Unit)? = null
) {
    fun observeKnowledgeBases() = dao.observeKnowledgeBases()
        .map { bases -> bases.filterNot { isChatDocumentKnowledgeBaseName(it.name) } }
    fun observeSourceCount() = dao.observeSourceCountExcluding(CHAT_DOCUMENT_BASE_GLOB)
    fun observeChunkCount() = dao.observeChunkCountExcluding(CHAT_DOCUMENT_BASE_GLOB)
    fun observeErrorSourceCount() = dao.observeErrorSourceCountExcluding(CHAT_DOCUMENT_BASE_GLOB)
    fun observePendingSourceCount() = dao.observePendingSourceCountExcluding(CHAT_DOCUMENT_BASE_GLOB)
    fun observeSources(knowledgeBaseId: Long) = dao.observeSources(knowledgeBaseId)
    fun observeEmbeddingServerStatus() = KnowledgeEmbeddingService.serverStatus

    suspend fun getKnowledgeBasesOnce(): List<KnowledgeBaseEntity> =
        dao.getKnowledgeBasesOnce().filterNot { isChatDocumentKnowledgeBaseName(it.name) }

    fun currentEmbeddingConfig(): KnowledgeEmbeddingConfig =
        embeddingService.currentConfig()

    suspend fun testEmbedding(text: String = "ping"): Int =
        embeddingService.embed(text).size

    suspend fun startManagedEmbeddingServer(): Int =
        embeddingService.startLocalServerForCurrentConfig()

    suspend fun stopManagedEmbeddingServer(reason: String = "user") {
        embeddingService.stopLocalServer(reason)
    }

    private fun reportIndexProgress(
        sourceId: Long,
        sourceTitle: String,
        stage: String,
        done: Int,
        total: Int
    ) {
        progressReporter?.invoke(
            KnowledgeBaseIndexProgress(
                sourceId = sourceId,
                sourceTitle = sourceTitle,
                stage = stage,
                done = done.coerceAtLeast(0),
                total = total.coerceAtLeast(0)
            )
        )
    }

    suspend fun markIndexedSourcesStaleForCurrentConfig() {
        dao.markIndexedSourcesStaleForConfig(currentEmbeddingConfig().hash)
    }

    suspend fun repairAllSourceProgress() = withContext(Dispatchers.IO) {
        dao.getAllSourcesOnce().forEach { source ->
            refreshDerivedSourceProgress(source.id)
        }
    }

    suspend fun createKnowledgeBase(name: String, description: String = ""): Long {
        val now = System.currentTimeMillis()
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Knowledge base name is required." }
        return dao.insertKnowledgeBase(
            KnowledgeBaseEntity(
                name = cleanName,
                description = description.trim(),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun ensureChatDocumentKnowledgeBase(chatId: Long, chatTitle: String = ""): Long = withContext(Dispatchers.IO) {
        require(chatId > 0L) { "Chat id is required for chat documents." }
        val name = chatDocumentKnowledgeBaseName(chatId)
        dao.getKnowledgeBaseByName(name)?.id ?: run {
            val now = System.currentTimeMillis()
            val displayTitle = chatTitle.trim().take(80)
            runCatching {
                dao.insertKnowledgeBase(
                    KnowledgeBaseEntity(
                        name = name,
                        description = buildString {
                            append(CHAT_DOCUMENT_BASE_DESCRIPTION)
                            if (displayTitle.isNotBlank()) append(": ").append(displayTitle)
                        },
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }.getOrElse { error ->
                dao.getKnowledgeBaseByName(name)?.id ?: throw error
            }
        }
    }

    suspend fun renameKnowledgeBase(id: Long, name: String, description: String = "") {
        val existing = dao.getKnowledgeBase(id) ?: return
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Knowledge base name is required." }
        dao.updateKnowledgeBase(
            existing.copy(
                name = cleanName,
                description = description.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteKnowledgeBase(id: Long) {
        dao.deleteKnowledgeBaseById(id)
    }

    suspend fun queueFile(knowledgeBaseId: Long, uri: Uri, displayName: String): Long = withContext(Dispatchers.IO) {
        val title = displayName.ifBlank { uri.lastPathSegment ?: "Document" }
        val sourceRef = uri.toString()
        val now = System.currentTimeMillis()
        val existing = dao.getSourceByRef(knowledgeBaseId, sourceRef)
        val queuedSource = (existing ?: KnowledgeSourceEntity(
            knowledgeBaseId = knowledgeBaseId,
            type = KnowledgeBaseSourceType.FILE,
            sourceRef = sourceRef,
            title = title,
            createdAt = now
        )).copy(
            title = title,
            contentHash = "",
            status = KnowledgeBaseSourceStatus.QUEUED,
            processingStage = KnowledgeBaseSourceStatus.QUEUED,
            errorMessage = null,
            embeddingModelPath = null,
            embeddingBackend = "",
            embeddingConfigHash = "",
            embeddingDim = 0,
            chunkCount = 0,
            embeddedChunkCount = 0,
            progressDone = 0,
            progressTotal = 0,
            indexedAt = null,
            updatedAt = now,
            progressUpdatedAt = now
        )
        if (existing == null) {
            dao.insertSource(queuedSource)
        } else {
            dao.updateSource(queuedSource)
            existing.id
        }
    }

    suspend fun importFile(knowledgeBaseId: Long, uri: Uri, displayName: String): Long = withContext(Dispatchers.IO) {
        val sourceId = queueFile(knowledgeBaseId, uri, displayName)
        importQueuedFile(sourceId)
    }

    suspend fun importQueuedFile(sourceId: Long): Long = withContext(Dispatchers.IO) {
        val source = dao.getSource(sourceId) ?: return@withContext sourceId
        require(source.type == KnowledgeBaseSourceType.FILE) { context.getString(R.string.kb_error_queued_source_not_file) }
        val now = System.currentTimeMillis()
        dao.updateSource(
            source.copy(
                status = KnowledgeBaseSourceStatus.EXTRACTING,
                processingStage = KnowledgeBaseSourceStatus.EXTRACTING,
                errorMessage = null,
                progressDone = 0,
                progressTotal = 0,
                updatedAt = now,
                progressUpdatedAt = now
            )
        )
        val extractedTextFile = try {
            extractFileTextToCache(Uri.parse(source.sourceRef), source.title)
        } catch (error: Throwable) {
            val latest = dao.getSource(sourceId) ?: source
            dao.updateSource(
                latest.copy(
                    status = KnowledgeBaseSourceStatus.ERROR,
                    processingStage = KnowledgeBaseSourceStatus.ERROR,
                    errorMessage = error.message ?: error::class.java.simpleName,
                    progressDone = 0,
                    progressTotal = 0,
                    updatedAt = System.currentTimeMillis(),
                    progressUpdatedAt = System.currentTimeMillis()
                )
            )
            throw error
        }
        try {
            indexSourceFromTextFile(
                knowledgeBaseId = source.knowledgeBaseId,
                type = source.type,
                sourceRef = source.sourceRef,
                title = source.title,
                textFile = extractedTextFile
            )
        } finally {
            runCatching { extractedTextFile.delete() }
        }
    }

    suspend fun importNote(knowledgeBaseId: Long, note: NoteEntity): Long {
        require(note.isLlmWhitelisted) { "Only LLM-whitelisted notes can be indexed." }
        return indexSource(
            knowledgeBaseId = knowledgeBaseId,
            type = KnowledgeBaseSourceType.NOTE,
            sourceRef = "note:${note.id}",
            title = note.title,
            text = note.content
        )
    }

    suspend fun importWebUrl(knowledgeBaseId: Long, url: String, title: String? = null): Long = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val fetched = DeepResearchSupport.fetchReadableSource(
            url = url,
            pdfTextExtractor = { bytes, maxChars -> extractNativePdfTextFromBytes(bytes, maxChars) }
        )
        importWebSource(
            knowledgeBaseId = knowledgeBaseId,
            finalUrl = fetched.finalUrl,
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: fetched.title,
            text = fetched.text
        )
    }

    suspend fun importWebSource(
        knowledgeBaseId: Long,
        finalUrl: String,
        title: String,
        text: String
    ): Long {
        val normalizedUrl = DeepResearchSupport.normalizeUrl(finalUrl)
        return indexSource(
            knowledgeBaseId = knowledgeBaseId,
            type = KnowledgeBaseSourceType.WEB,
            sourceRef = normalizedUrl,
            title = title.ifBlank { DeepResearchSupport.titleFromUrl(normalizedUrl) },
            text = text
        )
    }

    suspend fun reindexSource(sourceId: Long) {
        val source = dao.getSource(sourceId) ?: return
        if (source.type == KnowledgeBaseSourceType.FILE) {
            val extractedTextFile = extractFileTextToCache(Uri.parse(source.sourceRef), source.title)
            try {
                indexSourceFromTextFile(
                    knowledgeBaseId = source.knowledgeBaseId,
                    type = source.type,
                    sourceRef = source.sourceRef,
                    title = source.title,
                    textFile = extractedTextFile
                )
            } finally {
                runCatching { extractedTextFile.delete() }
            }
            return
        }
        val text = when (source.type) {
            KnowledgeBaseSourceType.NOTE -> {
                val noteId = source.sourceRef.removePrefix("note:").toIntOrNull()
                val note = noteId?.let { database.noteDao().getNoteById(it) }
                requireNotNull(note) { "Note is no longer available." }
                require(note.isLlmWhitelisted) { "Note is no longer LLM-whitelisted." }
                note.content
            }
            KnowledgeBaseSourceType.WEB -> {
                val existingChunks = dao.getChunksForSource(source.id)
                if (existingChunks.isNotEmpty()) {
                    existingChunks.sortedBy { it.chunkIndex }.joinToString("\n\n") { it.text }
                } else {
                    DeepResearchSupport.fetchReadableSource(
                        url = source.sourceRef,
                        pdfTextExtractor = { bytes, maxChars -> extractNativePdfTextFromBytes(bytes, maxChars) }
                    ).text
                }
            }
            else -> error("Unsupported knowledge source type: ${source.type}")
        }
        indexSource(
            knowledgeBaseId = source.knowledgeBaseId,
            type = source.type,
            sourceRef = source.sourceRef,
            title = source.title,
            text = text
        )
    }

    suspend fun resumeSourceEmbeddings(sourceId: Long): Boolean = withContext(Dispatchers.IO) {
        val source = dao.getSource(sourceId) ?: return@withContext false
        val embeddingConfig = embeddingService.currentConfig()
        require(embeddingConfig.isConfigured) { "Select and test a knowledge-base embedding backend before continuing embeddings." }

        val textChunks = dao.getTextChunkCountForSource(sourceId)
        val embeddedChunks = dao.getEmbeddedChunkCountForSource(sourceId)
        if (textChunks <= 0) {
            KnowledgeBaseDiagnostics.log("Cannot continue '${source.title}': no saved text chunks were found. Re-embed the source instead.")
            return@withContext false
        }
        if (embeddedChunks >= textChunks) {
            KnowledgeBaseDiagnostics.log("No missing embeddings for '${source.title}'.")
            refreshDerivedSourceProgress(sourceId)
            return@withContext false
        }
        if (source.embeddingConfigHash != embeddingConfig.hash) {
            KnowledgeBaseDiagnostics.log("Cannot continue '${source.title}': embedding backend changed. Re-embed the source instead.")
            return@withContext false
        }

        val missingChunks = dao.getChunksMissingEmbedding(sourceId)
        if (missingChunks.isEmpty()) {
            refreshDerivedSourceProgress(sourceId)
            return@withContext false
        }

        KnowledgeBaseDiagnostics.log(
            "Continuing embeddings for '${source.title}' from $embeddedChunks/$textChunks chunks."
        )
        dao.updateSource(
            source.copy(
                status = KnowledgeBaseSourceStatus.EMBEDDING,
                processingStage = KnowledgeBaseSourceStatus.EMBEDDING,
                errorMessage = null,
                chunkCount = textChunks,
                embeddedChunkCount = embeddedChunks,
                progressDone = embeddedChunks,
                progressTotal = textChunks,
                updatedAt = System.currentTimeMillis(),
                progressUpdatedAt = System.currentTimeMillis()
            )
        )

        var embedded = embeddedChunks
        var embeddingDim = source.embeddingDim
        try {
            reportIndexProgress(sourceId, source.title, KnowledgeBaseSourceStatus.EMBEDDING, embeddedChunks, textChunks)
            missingChunks.forEach { chunk ->
                val embedding = embeddingService.embed(chunk.text)
                embeddingDim = embedding.size
                val updatedRows = dao.updateChunkEmbedding(
                    sourceId = sourceId,
                    chunkIndex = chunk.chunkIndex,
                    embedding = embedding.toBlob(),
                    embeddingNorm = embedding.norm()
                )
                if (updatedRows > 0) embedded += 1
                KnowledgeBaseDiagnostics.log(
                    "Embedded missing chunk ${chunk.chunkIndex + 1}/$textChunks for '${source.title}' (${embedding.size} dimensions)."
                )
                dao.updateSourceProgress(
                    id = sourceId,
                    status = KnowledgeBaseSourceStatus.EMBEDDING,
                    stage = KnowledgeBaseSourceStatus.EMBEDDING,
                    done = embedded,
                    total = textChunks,
                    embeddedChunks = embedded
                )
                reportIndexProgress(sourceId, source.title, KnowledgeBaseSourceStatus.EMBEDDING, embedded, textChunks)
            }

            val completedAt = System.currentTimeMillis()
            val finalTextChunks = dao.getTextChunkCountForSource(sourceId)
            val finalEmbeddedChunks = dao.getEmbeddedChunkCountForSource(sourceId)
            val finalStatus = if (finalTextChunks > 0 && finalEmbeddedChunks >= finalTextChunks) {
                KnowledgeBaseSourceStatus.INDEXED
            } else {
                KnowledgeBaseSourceStatus.EMBEDDING
            }
            val latest = dao.getSource(sourceId) ?: source
            dao.updateSource(
                latest.copy(
                    status = finalStatus,
                    processingStage = finalStatus,
                    embeddingDim = embeddingDim,
                    chunkCount = finalTextChunks,
                    embeddedChunkCount = finalEmbeddedChunks,
                    progressDone = finalEmbeddedChunks,
                    progressTotal = finalTextChunks,
                    indexedAt = if (finalStatus == KnowledgeBaseSourceStatus.INDEXED) completedAt else latest.indexedAt,
                    errorMessage = null,
                    updatedAt = completedAt,
                    progressUpdatedAt = completedAt
                )
            )
            KnowledgeBaseDiagnostics.log(
                "Finished continuing '${source.title}': $finalEmbeddedChunks/$finalTextChunks chunks have vectors."
            )
            true
        } catch (error: Throwable) {
            KnowledgeBaseDiagnostics.log("Continuing embeddings failed for '${source.title}': ${error.message ?: error::class.java.simpleName}.")
            val finalTextChunks = runCatching { dao.getTextChunkCountForSource(sourceId) }.getOrDefault(textChunks)
            val finalEmbeddedChunks = runCatching { dao.getEmbeddedChunkCountForSource(sourceId) }.getOrDefault(embedded)
            val latest = dao.getSource(sourceId) ?: source
            dao.updateSource(
                latest.copy(
                    status = KnowledgeBaseSourceStatus.ERROR,
                    processingStage = KnowledgeBaseSourceStatus.ERROR,
                    errorMessage = error.message ?: error::class.java.simpleName,
                    chunkCount = finalTextChunks,
                    embeddedChunkCount = finalEmbeddedChunks,
                    progressDone = finalEmbeddedChunks,
                    progressTotal = finalTextChunks,
                    updatedAt = System.currentTimeMillis(),
                    progressUpdatedAt = System.currentTimeMillis()
                )
            )
            throw error
        }
    }

    suspend fun resumeKnowledgeBase(knowledgeBaseId: Long): Int = withContext(Dispatchers.IO) {
        val embeddingConfig = embeddingService.currentConfig()
        require(embeddingConfig.isConfigured) { "Select and test a knowledge-base embedding backend before continuing embeddings." }
        var resumed = 0
        dao.getSourcesOnce(knowledgeBaseId).forEach { source ->
            val textChunks = dao.getTextChunkCountForSource(source.id)
            val embeddedChunks = dao.getEmbeddedChunkCountForSource(source.id)
            val canContinue = textChunks > 0 &&
                embeddedChunks < textChunks &&
                source.embeddingConfigHash == embeddingConfig.hash
            if (canContinue && resumeSourceEmbeddings(source.id)) {
                resumed += 1
            } else if (textChunks > 0 && embeddedChunks < textChunks && source.embeddingConfigHash != embeddingConfig.hash) {
                KnowledgeBaseDiagnostics.log("Skipped '${source.title}' while continuing: embedding backend changed, re-embed required.")
            }
        }
        if (resumed == 0) {
            KnowledgeBaseDiagnostics.log("No resumable partial embeddings found for knowledge base $knowledgeBaseId.")
        }
        resumed
    }

    suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        dao.setSourceEnabled(sourceId, enabled)
    }

    suspend fun deleteSource(sourceId: Long) {
        dao.deleteSourceById(sourceId)
    }

    suspend fun search(
        query: String,
        knowledgeBaseIds: List<Long>,
        maxResults: Int = DEFAULT_SEARCH_RESULTS
    ): List<KnowledgeBaseSearchResult> = withContext(Dispatchers.IO) {
        val selectedIds = knowledgeBaseIds.distinct().filter { it > 0L }
        require(selectedIds.isNotEmpty()) { "Select at least one knowledge base before searching." }
        val cleanQuery = query.trim()
        require(cleanQuery.isNotBlank()) { "Search query is required." }

        val embeddingConfig = embeddingService.currentConfig()
        require(embeddingConfig.isConfigured) { "Select and test a knowledge-base embedding backend before searching." }
        val chunks = dao.getSearchableChunks(selectedIds, embeddingConfig.hash)
        if (chunks.isEmpty()) return@withContext emptyList()

        val queryEmbedding = embeddingService.embed(cleanQuery)
        val scored = rankChunksByQueryEmbedding(queryEmbedding, chunks, maxResults)

        val sourceMap = dao.getSourcesByIds(scored.map { it.first.sourceId }.distinct()).associateBy { it.id }
        val baseMap = dao.getKnowledgeBasesByIds(scored.map { it.first.knowledgeBaseId }.distinct()).associateBy { it.id }

        scored.map { (chunk, score) ->
            val source = sourceMap[chunk.sourceId]
            val base = baseMap[chunk.knowledgeBaseId]
            KnowledgeBaseSearchResult(
                chunkId = chunk.id,
                knowledgeBaseId = chunk.knowledgeBaseId,
                knowledgeBaseName = base?.name.orEmpty().ifBlank { "Knowledge base ${chunk.knowledgeBaseId}" },
                sourceId = chunk.sourceId,
                sourceTitle = source?.title.orEmpty().ifBlank { "Source ${chunk.sourceId}" },
                chunkIndex = chunk.chunkIndex,
                score = score,
                text = chunk.text
            )
        }
    }

    suspend fun readChunk(
        chunkId: Long,
        includeNeighbors: Boolean = false,
        knowledgeBaseIds: List<Long>? = null
    ): String = withContext(Dispatchers.IO) {
        val chunk = dao.getChunk(chunkId) ?: return@withContext "Chunk not found."
        val allowedIds = knowledgeBaseIds?.distinct()?.filter { it > 0L }
        if (allowedIds != null && allowedIds.isEmpty()) {
            return@withContext "Select at least one knowledge base before reading chunks."
        }
        if (allowedIds != null && chunk.knowledgeBaseId !in allowedIds) {
            return@withContext "Chunk is outside the knowledge bases selected for this chat or project."
        }
        val source = dao.getSource(chunk.sourceId)
        val base = dao.getKnowledgeBase(chunk.knowledgeBaseId)
        val chunks = if (includeNeighbors) {
            dao.getNeighborChunks(chunk.sourceId, chunk.chunkIndex - 1, chunk.chunkIndex + 1)
        } else {
            listOf(chunk)
        }
        buildString {
            appendLine("Knowledge base: ${base?.name ?: chunk.knowledgeBaseId}")
            appendLine("Source: ${source?.title ?: chunk.sourceId}")
            appendLine("Chunk: ${chunk.chunkIndex}")
            appendLine("Citation: ${chunkCitationMarkdown(source?.title.orEmpty(), chunk.chunkIndex, chunk.id)}")
            appendLine()
            chunks.forEach { item ->
                if (includeNeighbors) appendLine("[chunk ${item.chunkIndex}]")
                appendLine(item.text.trim())
                appendLine()
            }
        }.trim()
    }

    suspend fun getChunkWindow(
        chunkId: Long,
        radius: Int = 1,
        knowledgeBaseIds: List<Long>? = null
    ): KnowledgeChunkWindow? = withContext(Dispatchers.IO) {
        val chunk = dao.getChunk(chunkId) ?: return@withContext null
        val allowedIds = knowledgeBaseIds?.distinct()?.filter { it > 0L }
        if (allowedIds != null && (allowedIds.isEmpty() || chunk.knowledgeBaseId !in allowedIds)) {
            return@withContext null
        }
        val source = dao.getSource(chunk.sourceId)
        val base = dao.getKnowledgeBase(chunk.knowledgeBaseId)
        val safeRadius = radius.coerceIn(0, 5)
        val neighbors = dao.getNeighborChunks(
            sourceId = chunk.sourceId,
            fromIndex = chunk.chunkIndex - safeRadius,
            toIndex = chunk.chunkIndex + safeRadius
        )
        KnowledgeChunkWindow(
            knowledgeBaseId = chunk.knowledgeBaseId,
            knowledgeBaseName = base?.name.orEmpty().ifBlank { "Knowledge base ${chunk.knowledgeBaseId}" },
            sourceId = chunk.sourceId,
            sourceTitle = source?.title.orEmpty().ifBlank { "Source ${chunk.sourceId}" },
            targetChunkId = chunk.id,
            targetChunkIndex = chunk.chunkIndex,
            chunks = neighbors.map { item ->
                KnowledgeChunkWindowItem(
                    chunkId = item.id,
                    chunkIndex = item.chunkIndex,
                    text = item.text,
                    isTarget = item.id == chunk.id
                )
            }
        )
    }

    suspend fun listSources(knowledgeBaseIds: List<Long>): String = withContext(Dispatchers.IO) {
        val selectedIds = knowledgeBaseIds.distinct().filter { it > 0L }
        require(selectedIds.isNotEmpty()) { "Select at least one knowledge base before listing sources." }
        val bases = dao.getKnowledgeBasesByIds(selectedIds).associateBy { it.id }
        selectedIds.map { baseId ->
            val base = bases[baseId]
            val sources = dao.getSourcesOnce(baseId)
            buildString {
                appendLine("${base?.name ?: "Knowledge base $baseId"} (${sources.size} sources)")
                sources.forEach { source ->
                    appendLine("- #${source.id} ${source.title} [${source.status}], textChunks=${source.chunkCount}, embedded=${source.embeddedChunkCount}, enabled=${source.enabled}")
                }
            }.trim()
        }.joinToString("\n\n")
    }

    suspend fun readSourcesForSummary(
        knowledgeBaseIds: List<Long>,
        sourceId: Long? = null,
        startChunk: Int = 0,
        maxChunksPerSource: Int = 18,
        maxCharsPerChunk: Int = 900
    ): String = withContext(Dispatchers.IO) {
        val selectedIds = knowledgeBaseIds.distinct().filter { it > 0L }
        require(selectedIds.isNotEmpty()) { "Select at least one knowledge base before reading sources." }
        val embeddingHash = embeddingService.currentConfig().hash
        val sources = selectedIds
            .flatMap { dao.getSourcesOnce(it) }
            .filter { source ->
                source.enabled &&
                    source.status == KnowledgeBaseSourceStatus.INDEXED &&
                    source.embeddingConfigHash == embeddingHash &&
                    (sourceId == null || source.id == sourceId)
            }
            .sortedWith(compareBy<KnowledgeSourceEntity> { it.knowledgeBaseId }.thenBy { it.id })
        require(sources.isNotEmpty()) {
            if (sourceId != null) {
                "No indexed source #$sourceId is available in the selected knowledge-base scope."
            } else {
                "No indexed sources are available in the selected knowledge-base scope."
            }
        }
        val safeStart = startChunk.coerceAtLeast(0)
        val safeMaxChunks = maxChunksPerSource.coerceIn(1, 40)
        val safeMaxChars = maxCharsPerChunk.coerceIn(300, 2_000)
        val sections = mutableListOf<String>()
        for (source in sources) {
            val chunks = dao.getChunksForSource(source.id)
                .filter { it.embedding != null && it.chunkIndex >= safeStart }
                .take(safeMaxChunks)
            sections += buildString {
                appendLine("[source_id=${source.id}] ${source.title}")
                appendLine("status=${source.status}, chunks=${source.chunkCount}, embedded=${source.embeddedChunkCount}, returned_start_chunk=${safeStart}, returned_chunks=${chunks.size}")
                if (source.chunkCount > safeStart + chunks.size) {
                    appendLine("more_chunks_available=true; call kb_read_source with start_chunk=${safeStart + chunks.size} to continue.")
                }
                chunks.forEach { chunk ->
                    appendLine()
                    appendLine("[chunk_id=${chunk.id} chunk=${chunk.chunkIndex + 1}]")
                    appendLine("Citation: ${chunkCitationMarkdown(source.title, chunk.chunkIndex, chunk.id)}")
                    appendLine(chunk.text.take(safeMaxChars))
                }
            }.trim()
        }
        sections.joinToString("\n\n")
    }

    suspend fun getStats(): KnowledgeBaseStats = withContext(Dispatchers.IO) {
        val userBases = dao.getKnowledgeBasesOnce().filterNot { isChatDocumentKnowledgeBaseName(it.name) }
        KnowledgeBaseStats(
            baseCount = userBases.size,
            sourceCount = userBases.sumOf { dao.getSourcesOnce(it.id).size },
            chunkCount = userBases.sumOf { dao.getChunkCountForBase(it.id) },
            pendingSourceCount = userBases.sumOf { base ->
                dao.getSourcesOnce(base.id).count {
                    it.status in setOf(
                        KnowledgeBaseSourceStatus.QUEUED,
                        KnowledgeBaseSourceStatus.EXTRACTING,
                        KnowledgeBaseSourceStatus.CHUNKING,
                        KnowledgeBaseSourceStatus.EMBEDDING,
                        KnowledgeBaseSourceStatus.STALE
                    )
                }
            },
            errorSourceCount = userBases.sumOf { base ->
                dao.getSourcesOnce(base.id).count { it.status == KnowledgeBaseSourceStatus.ERROR }
            }
        )
    }

    private suspend fun indexSource(
        knowledgeBaseId: Long,
        type: String,
        sourceRef: String,
        title: String,
        text: String
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cleanText = normalizeText(text)
        val contentHash = sha256(cleanText)
        val embeddingConfig = embeddingService.currentConfig()
        val settings = SettingsRepository(context)
        val chunkSize = SettingsRepository.normalizeKnowledgeBaseChunkSize(settings.knowledgeBaseChunkSize.value)
        val embeddingBatchSize = SettingsRepository.normalizeKnowledgeEmbeddingBatchSize(
            settings.knowledgeEmbeddingBatchSize.value
        )
        KnowledgeBaseDiagnostics.log("Preparing source '$title' for indexing (${cleanText.length} characters).")
        val existing = dao.getSourceByRef(knowledgeBaseId, sourceRef)
        val indexingSource = (existing ?: KnowledgeSourceEntity(
            knowledgeBaseId = knowledgeBaseId,
            type = type,
            sourceRef = sourceRef,
            title = title,
            createdAt = now
        )).copy(
            title = title,
            contentHash = contentHash,
            status = KnowledgeBaseSourceStatus.CHUNKING,
            processingStage = KnowledgeBaseSourceStatus.CHUNKING,
            errorMessage = null,
            embeddingBackend = embeddingConfig.backend,
            embeddingModelPath = embeddingConfig.localModelPath ?: embeddingConfig.remoteModel,
            embeddingConfigHash = embeddingConfig.hash,
            embeddingDim = 0,
            chunkCount = 0,
            embeddedChunkCount = 0,
            progressDone = 0,
            progressTotal = 0,
            progressUpdatedAt = now,
            updatedAt = now
        )

        val sourceId = dao.insertSource(indexingSource)
        try {
            val chunks = chunkTextWithOffsets(cleanText, chunkSize, embeddingBatchSize)
            KnowledgeBaseDiagnostics.log("Source '$title' produced ${chunks.size} text chunks.")
            val textOnlyChunks = chunks.mapIndexed { index, chunk ->
                KnowledgeChunkEntity(
                    knowledgeBaseId = knowledgeBaseId,
                    sourceId = sourceId,
                    chunkIndex = index,
                    text = chunk.text,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset
                )
            }
            database.withTransaction {
                dao.deleteChunksForSource(sourceId)
                if (textOnlyChunks.isNotEmpty()) dao.insertChunks(textOnlyChunks)
                dao.updateSource(
                    indexingSource.copy(
                        id = sourceId,
                        status = KnowledgeBaseSourceStatus.EMBEDDING,
                        processingStage = KnowledgeBaseSourceStatus.EMBEDDING,
                        chunkCount = textOnlyChunks.size,
                        progressTotal = textOnlyChunks.size,
                        progressDone = 0,
                        embeddedChunkCount = 0,
                        indexedAt = null,
                        updatedAt = System.currentTimeMillis(),
                        progressUpdatedAt = System.currentTimeMillis(),
                        errorMessage = null
                    )
                )
            }
            reportIndexProgress(sourceId, title, KnowledgeBaseSourceStatus.EMBEDDING, 0, chunks.size)
            require(embeddingConfig.isConfigured) { "Select and test a knowledge-base embedding backend before indexing." }
            KnowledgeBaseDiagnostics.log("Embedding source '$title' with backend ${embeddingConfig.label.ifBlank { embeddingConfig.backend }}.")

            var embedded = 0
            var embeddingDim = 0
            chunks.forEachIndexed { index, chunk ->
                val embedding = embeddingService.embed(chunk.text)
                embeddingDim = embedding.size
                val embeddingBlob = embedding.toBlob()
                val embeddingNorm = embedding.norm()
                val updatedRows = dao.updateChunkEmbedding(
                    sourceId = sourceId,
                    chunkIndex = index,
                    embedding = embeddingBlob,
                    embeddingNorm = embeddingNorm
                )
                if (updatedRows == 0) {
                    dao.insertChunks(
                        listOf(
                            KnowledgeChunkEntity(
                                knowledgeBaseId = knowledgeBaseId,
                                sourceId = sourceId,
                                chunkIndex = index,
                                text = chunk.text,
                                startOffset = chunk.startOffset,
                                endOffset = chunk.endOffset,
                                embedding = embeddingBlob,
                                embeddingNorm = embeddingNorm
                            )
                        )
                    )
                }
                embedded += 1
                KnowledgeBaseDiagnostics.log("Embedded chunk ${index + 1}/${chunks.size} for '$title' (${embedding.size} dimensions).")
                dao.updateSourceProgress(
                    id = sourceId,
                    status = KnowledgeBaseSourceStatus.EMBEDDING,
                    stage = KnowledgeBaseSourceStatus.EMBEDDING,
                    done = embedded,
                    total = chunks.size,
                    embeddedChunks = embedded
                )
                reportIndexProgress(sourceId, title, KnowledgeBaseSourceStatus.EMBEDDING, embedded, chunks.size)
            }

            val completedAt = System.currentTimeMillis()
            val persistedTextChunks = dao.getTextChunkCountForSource(sourceId)
            val persistedEmbeddedChunks = dao.getEmbeddedChunkCountForSource(sourceId)
            val finalStatus = if (persistedTextChunks > 0 && persistedEmbeddedChunks >= persistedTextChunks) {
                KnowledgeBaseSourceStatus.INDEXED
            } else {
                KnowledgeBaseSourceStatus.EMBEDDING
            }
            dao.updateSource(
                indexingSource.copy(
                    id = sourceId,
                    status = finalStatus,
                    processingStage = finalStatus,
                    embeddingDim = embeddingDim,
                    chunkCount = persistedTextChunks,
                    embeddedChunkCount = persistedEmbeddedChunks,
                    progressDone = persistedEmbeddedChunks,
                    progressTotal = persistedTextChunks,
                    indexedAt = if (finalStatus == KnowledgeBaseSourceStatus.INDEXED) completedAt else null,
                    updatedAt = completedAt,
                    progressUpdatedAt = completedAt,
                    errorMessage = null
                )
            )
            KnowledgeBaseDiagnostics.log(
                "Finished source '$title': $persistedEmbeddedChunks/$persistedTextChunks chunks have vectors."
            )
            sourceId
        } catch (error: Throwable) {
            KnowledgeBaseDiagnostics.log("Indexing failed for '$title': ${error.message ?: error::class.java.simpleName}.")
            val textChunks = runCatching { dao.getTextChunkCountForSource(sourceId) }.getOrDefault(0)
            val embeddedChunks = runCatching { dao.getEmbeddedChunkCountForSource(sourceId) }.getOrDefault(0)
            dao.updateSource(
                indexingSource.copy(
                    id = sourceId,
                    status = KnowledgeBaseSourceStatus.ERROR,
                    processingStage = KnowledgeBaseSourceStatus.ERROR,
                    errorMessage = error.message ?: error::class.java.simpleName,
                    chunkCount = textChunks,
                    embeddedChunkCount = embeddedChunks,
                    progressDone = embeddedChunks,
                    progressTotal = textChunks,
                    progressUpdatedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            throw error
        }
    }

    private suspend fun indexSourceFromTextFile(
        knowledgeBaseId: Long,
        type: String,
        sourceRef: String,
        title: String,
        textFile: File
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val contentHash = sha256File(textFile)
        val embeddingConfig = embeddingService.currentConfig()
        val settings = SettingsRepository(context)
        val chunkSize = SettingsRepository.normalizeKnowledgeBaseChunkSize(settings.knowledgeBaseChunkSize.value)
        val embeddingBatchSize = SettingsRepository.normalizeKnowledgeEmbeddingBatchSize(
            settings.knowledgeEmbeddingBatchSize.value
        )
        KnowledgeBaseDiagnostics.log("Preparing source '$title' for indexing (${textFile.length()} cached text bytes).")
        val existing = dao.getSourceByRef(knowledgeBaseId, sourceRef)
        val indexingSource = (existing ?: KnowledgeSourceEntity(
            knowledgeBaseId = knowledgeBaseId,
            type = type,
            sourceRef = sourceRef,
            title = title,
            createdAt = now
        )).copy(
            title = title,
            contentHash = contentHash,
            status = KnowledgeBaseSourceStatus.CHUNKING,
            processingStage = KnowledgeBaseSourceStatus.CHUNKING,
            errorMessage = null,
            embeddingBackend = embeddingConfig.backend,
            embeddingModelPath = embeddingConfig.localModelPath ?: embeddingConfig.remoteModel,
            embeddingConfigHash = embeddingConfig.hash,
            embeddingDim = 0,
            chunkCount = 0,
            embeddedChunkCount = 0,
            progressDone = 0,
            progressTotal = 0,
            progressUpdatedAt = now,
            updatedAt = now
        )

        val sourceId = dao.insertSource(indexingSource)
        try {
            val textChunkCount = database.withTransaction {
                dao.deleteChunksForSource(sourceId)
                insertChunksFromTextFile(
                    knowledgeBaseId = knowledgeBaseId,
                    sourceId = sourceId,
                    textFile = textFile,
                    chunkSize = chunkSize,
                    embeddingBatchSize = embeddingBatchSize
                )
            }
            KnowledgeBaseDiagnostics.log("Source '$title' produced $textChunkCount text chunks.")
            dao.updateSource(
                indexingSource.copy(
                    id = sourceId,
                    status = KnowledgeBaseSourceStatus.EMBEDDING,
                    processingStage = KnowledgeBaseSourceStatus.EMBEDDING,
                    chunkCount = textChunkCount,
                    progressTotal = textChunkCount,
                    progressDone = 0,
                    embeddedChunkCount = 0,
                    indexedAt = null,
                    updatedAt = System.currentTimeMillis(),
                    progressUpdatedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            )
            reportIndexProgress(sourceId, title, KnowledgeBaseSourceStatus.EMBEDDING, 0, textChunkCount)
            require(embeddingConfig.isConfigured) { "Select and test a knowledge-base embedding backend before indexing." }
            KnowledgeBaseDiagnostics.log("Embedding source '$title' with backend ${embeddingConfig.label.ifBlank { embeddingConfig.backend }}.")

            var embedded = 0
            var embeddingDim = 0
            var offset = 0
            while (true) {
                val batch = dao.getChunksForSourcePaged(sourceId, EMBEDDING_DB_PAGE_SIZE, offset)
                if (batch.isEmpty()) break
                batch.forEach { chunk ->
                    val embedding = embeddingService.embed(chunk.text)
                    embeddingDim = embedding.size
                    dao.updateChunkEmbedding(
                        sourceId = sourceId,
                        chunkIndex = chunk.chunkIndex,
                        embedding = embedding.toBlob(),
                        embeddingNorm = embedding.norm()
                    )
                    embedded += 1
                    KnowledgeBaseDiagnostics.log("Embedded chunk ${chunk.chunkIndex + 1}/$textChunkCount for '$title' (${embedding.size} dimensions).")
                    dao.updateSourceProgress(
                        id = sourceId,
                        status = KnowledgeBaseSourceStatus.EMBEDDING,
                        stage = KnowledgeBaseSourceStatus.EMBEDDING,
                        done = embedded,
                        total = textChunkCount,
                        embeddedChunks = embedded
                    )
                    reportIndexProgress(sourceId, title, KnowledgeBaseSourceStatus.EMBEDDING, embedded, textChunkCount)
                }
                offset += batch.size
            }

            val completedAt = System.currentTimeMillis()
            val persistedTextChunks = dao.getTextChunkCountForSource(sourceId)
            val persistedEmbeddedChunks = dao.getEmbeddedChunkCountForSource(sourceId)
            val finalStatus = if (persistedTextChunks > 0 && persistedEmbeddedChunks >= persistedTextChunks) {
                KnowledgeBaseSourceStatus.INDEXED
            } else {
                KnowledgeBaseSourceStatus.EMBEDDING
            }
            dao.updateSource(
                indexingSource.copy(
                    id = sourceId,
                    status = finalStatus,
                    processingStage = finalStatus,
                    embeddingDim = embeddingDim,
                    chunkCount = persistedTextChunks,
                    embeddedChunkCount = persistedEmbeddedChunks,
                    progressDone = persistedEmbeddedChunks,
                    progressTotal = persistedTextChunks,
                    indexedAt = if (finalStatus == KnowledgeBaseSourceStatus.INDEXED) completedAt else null,
                    updatedAt = completedAt,
                    progressUpdatedAt = completedAt,
                    errorMessage = null
                )
            )
            KnowledgeBaseDiagnostics.log(
                "Finished source '$title': $persistedEmbeddedChunks/$persistedTextChunks chunks have vectors."
            )
            sourceId
        } catch (error: Throwable) {
            val textChunks = runCatching { dao.getTextChunkCountForSource(sourceId) }.getOrDefault(0)
            val embeddedChunks = runCatching { dao.getEmbeddedChunkCountForSource(sourceId) }.getOrDefault(0)
            dao.updateSource(
                indexingSource.copy(
                    id = sourceId,
                    status = KnowledgeBaseSourceStatus.ERROR,
                    processingStage = KnowledgeBaseSourceStatus.ERROR,
                    errorMessage = error.message ?: error::class.java.simpleName,
                    chunkCount = textChunks,
                    embeddedChunkCount = embeddedChunks,
                    progressDone = embeddedChunks,
                    progressTotal = textChunks,
                    progressUpdatedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            throw error
        }
    }

    private suspend fun refreshDerivedSourceProgress(sourceId: Long) {
        val source = dao.getSource(sourceId) ?: return
        val textChunks = dao.getTextChunkCountForSource(sourceId)
        val embeddedChunks = dao.getEmbeddedChunkCountForSource(sourceId)
        if (textChunks <= 0) return

        val now = System.currentTimeMillis()
        val repairedStatus = when {
            embeddedChunks >= textChunks && source.embeddingConfigHash.isNotBlank() -> KnowledgeBaseSourceStatus.INDEXED
            source.status == KnowledgeBaseSourceStatus.ERROR -> KnowledgeBaseSourceStatus.ERROR
            embeddedChunks > 0 -> KnowledgeBaseSourceStatus.EMBEDDING
            else -> source.status
        }
        val repairedIndexedAt = if (repairedStatus == KnowledgeBaseSourceStatus.INDEXED) {
            source.indexedAt ?: now
        } else {
            source.indexedAt
        }

        if (source.chunkCount != textChunks ||
            source.embeddedChunkCount != embeddedChunks ||
            source.progressTotal != textChunks ||
            source.progressDone != embeddedChunks ||
            source.status != repairedStatus ||
            source.processingStage != repairedStatus ||
            source.indexedAt != repairedIndexedAt
        ) {
            dao.updateSource(
                source.copy(
                    status = repairedStatus,
                    processingStage = repairedStatus,
                    chunkCount = textChunks,
                    embeddedChunkCount = embeddedChunks,
                    progressTotal = textChunks,
                    progressDone = embeddedChunks,
                    indexedAt = repairedIndexedAt,
                    errorMessage = if (repairedStatus == KnowledgeBaseSourceStatus.INDEXED) null else source.errorMessage,
                    updatedAt = now,
                    progressUpdatedAt = now
                )
            )
        }
    }

    private fun extractFileTextToCache(uri: Uri, title: String): File {
        val outputFile = File.createTempFile("kb_extract_", ".txt", knowledgeCacheDir())
        try {
            val lower = title.lowercase(Locale.US)
            if (lower.endsWith(".pdf")) {
                PDFBoxResourceLoader.init(context)
                val cachedPdf = File.createTempFile("kb_source_", ".pdf", knowledgeCacheDir())
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cachedPdf.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open PDF.")
                    PDDocument.load(cachedPdf).use { document ->
                        if (document.isEncrypted) {
                            runCatching { document.setAllSecurityToBeRemoved(true) }
                        }
                        outputFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                            val stripper = PDFTextStripper()
                            for (pageNumber in 1..document.numberOfPages) {
                                stripper.startPage = pageNumber
                                stripper.endPage = pageNumber
                                val pageText = stripper.getText(document)
                                if (pageText.isNotBlank()) {
                                    writer.appendLine(pageText)
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { cachedPdf.delete() }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    outputFile.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                } ?: error("Cannot open file.")
            }
            return outputFile
        } catch (error: Throwable) {
            runCatching { outputFile.delete() }
            throw error
        }
    }

    private data class TextChunk(val text: String, val startOffset: Int, val endOffset: Int)

    private fun knowledgeCacheDir(): File =
        File(context.cacheDir, "knowledge_extraction").apply { mkdirs() }

    private suspend fun insertChunksFromTextFile(
        knowledgeBaseId: Long,
        sourceId: Long,
        textFile: File,
        chunkSize: Int,
        embeddingBatchSize: Int
    ): Int {
        var chunkIndex = 0
        textFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            chunkIndex = insertChunksFromReader(
                knowledgeBaseId = knowledgeBaseId,
                sourceId = sourceId,
                reader = reader,
                chunkSize = chunkSize,
                embeddingBatchSize = embeddingBatchSize
            )
        }
        return chunkIndex
    }

    private suspend fun insertChunksFromReader(
        knowledgeBaseId: Long,
        sourceId: Long,
        reader: Reader,
        chunkSize: Int,
        embeddingBatchSize: Int
    ): Int {
        val safeChunkSize = SettingsRepository.normalizeKnowledgeBaseChunkSize(chunkSize)
        val tokenBudget = SettingsRepository.knowledgeEmbeddingTokenBudgetForBatchSize(embeddingBatchSize)
        val buffer = CharArray(TEXT_IMPORT_BUFFER_CHARS)
        val carry = StringBuilder()
        var globalOffset = 0
        var chunkIndex = 0
        var endOfFile = false

        while (!endOfFile) {
            while (carry.length < safeChunkSize * 2) {
                val read = reader.read(buffer)
                if (read <= 0) {
                    endOfFile = true
                    break
                }
                carry.append(buffer, 0, read)
            }
            val normalized = normalizeText(carry.toString())
            if (normalized.isBlank()) {
                carry.clear()
                continue
            }
            val hardEnd = if (endOfFile) {
                normalized.length
            } else {
                safeChunkSize.coerceAtMost(normalized.length)
            }
            if (!endOfFile && normalized.length < safeChunkSize) {
                carry.clear()
                carry.append(normalized)
                continue
            }
            val end = constrainChunkEndToEmbeddingBudget(
                text = normalized,
                start = 0,
                proposedEnd = chooseChunkEnd(normalized, 0, hardEnd, safeChunkSize),
                requestedChunkSize = safeChunkSize,
                tokenBudget = tokenBudget
            )
            val chunkText = normalized.substring(0, end).trim()
            if (chunkText.isNotBlank()) {
                dao.insertChunks(
                    listOf(
                        KnowledgeChunkEntity(
                            knowledgeBaseId = knowledgeBaseId,
                            sourceId = sourceId,
                            chunkIndex = chunkIndex,
                            text = chunkText,
                            startOffset = globalOffset,
                            endOffset = globalOffset + chunkText.length
                        )
                    )
                )
                chunkIndex += 1
            }
            if (end >= normalized.length && endOfFile) break
            val nextStart = (end - CHUNK_OVERLAP).coerceAtLeast(0)
            val nextCarry = normalized.substring(nextStart)
            globalOffset += nextStart
            carry.clear()
            carry.append(nextCarry)
            if (endOfFile && carry.isBlank()) break
        }
        return chunkIndex
    }

    companion object {
        const val DEFAULT_SEARCH_RESULTS = 6
        const val MAX_SEARCH_RESULTS = 20
        const val CHAT_DOCUMENT_BASE_PREFIX = "__chat_documents_"
        private const val CHAT_DOCUMENT_BASE_GLOB = "__chat_documents_*"
        private const val CHAT_DOCUMENT_BASE_DESCRIPTION = "Private chat document index"
        private const val DEFAULT_CHUNK_SIZE = SettingsRepository.KB_DEFAULT_CHUNK_SIZE
        private const val CHUNK_OVERLAP = 120
        private const val MIN_CHUNK_SIZE = 240
        private const val TEXT_IMPORT_BUFFER_CHARS = 16 * 1024
        private const val EMBEDDING_DB_PAGE_SIZE = 32
        private const val CHUNK_SENTENCE_LOOKAHEAD_RATIO = 1.15
        private const val CHUNK_WORD_LOOKAHEAD_CHARS = 96
        private val TOKEN_LIKE_REGEX = Regex("""[\p{L}\p{N}]+|[^\s\p{L}\p{N}]""")

        fun selectedKnowledgeBaseIdsFromCsv(value: String?): List<Long> =
            value.orEmpty()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0L }
                .distinct()

        fun selectedKnowledgeBaseIdsToCsv(ids: List<Long>): String =
            ids.distinct().filter { it > 0L }.joinToString(",")

        fun chatDocumentKnowledgeBaseName(chatId: Long): String =
            "$CHAT_DOCUMENT_BASE_PREFIX$chatId"

        fun isChatDocumentKnowledgeBaseName(name: String): Boolean =
            name.startsWith(CHAT_DOCUMENT_BASE_PREFIX)

        fun chunkCitationMarkdown(sourceTitle: String, chunkIndex: Int, chunkId: Long): String {
            val labelSource = markdownLinkLabel(sourceTitle.trim().ifBlank { "Knowledge base" })
            return "[$labelSource chunk ${chunkIndex + 1}](kb://chunk/$chunkId)"
        }

        private fun markdownLinkLabel(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")

        fun normalizeText(text: String): String =
            text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(Regex("""[ \t\f]+"""), " ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()

        fun chunkText(
            text: String,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
            embeddingBatchSize: Int = SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(chunkSize)
        ): List<String> =
            chunkTextWithOffsets(text, chunkSize, embeddingBatchSize).map { it.text }

        private fun chunkTextWithOffsets(
            text: String,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
            embeddingBatchSize: Int = SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(chunkSize)
        ): List<TextChunk> {
            val clean = normalizeText(text)
            if (clean.isBlank()) return emptyList()
            val chunks = mutableListOf<TextChunk>()
            val safeChunkSize = SettingsRepository.normalizeKnowledgeBaseChunkSize(chunkSize)
            val tokenBudget = SettingsRepository.knowledgeEmbeddingTokenBudgetForBatchSize(embeddingBatchSize)
            var start = 0
            while (start < clean.length) {
                val hardEnd = (start + safeChunkSize).coerceAtMost(clean.length)
                val end = constrainChunkEndToEmbeddingBudget(
                    text = clean,
                    start = start,
                    proposedEnd = chooseChunkEnd(clean, start, hardEnd, safeChunkSize),
                    requestedChunkSize = safeChunkSize,
                    tokenBudget = tokenBudget
                )
                val chunk = clean.substring(start, end).trim()
                if (chunk.isNotBlank()) chunks += TextChunk(chunk, start, end)
                if (end >= clean.length) break
                start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
            }
            return chunks
        }

        private fun chooseChunkEnd(text: String, start: Int, hardEnd: Int, requestedChunkSize: Int): Int {
            if (hardEnd >= text.length) return hardEnd
            val boundaryFloor = start + requestedChunkSize / 2
            val paragraph = text.lastIndexOf("\n\n", hardEnd).takeIf { it > boundaryFloor }
            val sentenceBefore = findSentenceBoundaryBefore(text, hardEnd, boundaryFloor)
            val sentenceLookaheadEnd = (start + ceil(requestedChunkSize * CHUNK_SENTENCE_LOOKAHEAD_RATIO).toInt())
                .coerceAtMost(text.length)
            val sentenceAfter = findSentenceBoundaryAfter(text, hardEnd, sentenceLookaheadEnd)
            val wordAfter = findWordBoundaryAfter(
                text = text,
                start = hardEnd,
                limit = minOf(sentenceLookaheadEnd, hardEnd + CHUNK_WORD_LOOKAHEAD_CHARS)
            )
            val wordBefore = findWordBoundaryBefore(text, hardEnd, boundaryFloor)
            return paragraph ?: sentenceAfter ?: sentenceBefore ?: wordAfter ?: wordBefore ?: hardEnd
        }

        private fun findSentenceBoundaryBefore(text: String, end: Int, floor: Int): Int? {
            val safeEnd = end.coerceAtMost(text.length - 1)
            for (index in safeEnd downTo floor.coerceAtLeast(0)) {
                if (isSentenceEndAt(text, index)) return index + 1
            }
            return null
        }

        private fun findSentenceBoundaryAfter(text: String, start: Int, limit: Int): Int? {
            val safeLimit = limit.coerceAtMost(text.length - 1)
            for (index in start.coerceAtLeast(0)..safeLimit) {
                if (isSentenceEndAt(text, index)) return index + 1
            }
            return null
        }

        private fun isSentenceEndAt(text: String, index: Int): Boolean {
            val char = text.getOrNull(index) ?: return false
            if (char != '.' && char != '!' && char != '?' && char != '…') return false
            val next = text.getOrNull(index + 1)
            return next == null || next.isWhitespace() || next == '"' || next == '\'' || next == ')' || next == ']'
        }

        private fun findWordBoundaryAfter(text: String, start: Int, limit: Int): Int? {
            val safeLimit = limit.coerceAtMost(text.length)
            for (index in start.coerceAtLeast(1) until safeLimit) {
                if (text[index].isWhitespace()) return index
            }
            return null
        }

        private fun findWordBoundaryBefore(text: String, end: Int, floor: Int): Int? {
            val safeEnd = end.coerceAtMost(text.length)
            for (index in safeEnd downTo floor.coerceAtLeast(1)) {
                if (text.getOrNull(index - 1)?.isWhitespace() == true) return index
            }
            return null
        }

        private fun constrainChunkEndToEmbeddingBudget(
            text: String,
            start: Int,
            proposedEnd: Int,
            requestedChunkSize: Int,
            tokenBudget: Int
        ): Int {
            var end = proposedEnd.coerceIn(start + 1, text.length)
            val minSpan = minOf(MIN_CHUNK_SIZE, requestedChunkSize).coerceAtLeast(1)
            while (
                end > start + minSpan &&
                estimateEmbeddingTokens(text.substring(start, end)) > tokenBudget
            ) {
                val span = end - start
                val reducedHardEnd = (start + ceil(span * 0.75).toInt())
                    .coerceAtLeast(start + minSpan)
                    .coerceAtMost(end - 1)
                end = chooseChunkEnd(text, start, reducedHardEnd, reducedHardEnd - start)
                    .coerceIn(start + 1, text.length)
            }
            return end
        }

        fun estimateEmbeddingTokens(text: String): Int {
            val clean = text.trim()
            if (clean.isBlank()) return 0
            var tokens = 2
            TOKEN_LIKE_REGEX.findAll(clean).forEach { match ->
                val piece = match.value
                tokens += if (piece.length == 1 && !piece.first().isLetterOrDigit()) {
                    1
                } else {
                    ceil(piece.length / 6.0).toInt().coerceAtLeast(1)
                }
            }
            return tokens
        }

        fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun sha256File(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun tokenize(text: String): Set<String> =
            text.lowercase(Locale.US)
                .split(Regex("""[^a-z0-9áéíóúüñ]+"""))
                .filter { it.length >= 3 }
                .toSet()

        fun lexicalScore(queryTerms: Set<String>, text: String): Float {
            if (queryTerms.isEmpty()) return 0f
            val textTerms = tokenize(text)
            if (textTerms.isEmpty()) return 0f
            val hits = queryTerms.count { it in textTerms }
            return hits.toFloat() / queryTerms.size.toFloat()
        }

        fun vectorScore(queryEmbedding: List<Float>?, queryNorm: Float?, chunk: KnowledgeChunkEntity): Float? {
            val blob = chunk.embedding ?: return null
            val q = queryEmbedding ?: return null
            val qNorm = queryNorm ?: return null
            val cNorm = chunk.embeddingNorm ?: return null
            val c = blob.toFloatList()
            if (c.size != q.size || qNorm <= 0f || cNorm <= 0f) return null
            var dot = 0f
            for (i in q.indices) dot += q[i] * c[i]
            return dot / (qNorm * cNorm)
        }

        fun rankChunksByQueryEmbedding(
            queryEmbedding: List<Float>,
            chunks: List<KnowledgeChunkEntity>,
            maxResults: Int = MAX_SEARCH_RESULTS
        ): List<Pair<KnowledgeChunkEntity, Float>> {
            val queryNorm = queryEmbedding.norm()
            return chunks.mapNotNull { chunk ->
                val score = vectorScore(queryEmbedding, queryNorm, chunk) ?: return@mapNotNull null
                if (score <= 0f) null else chunk to score
            }
                .sortedByDescending { it.second }
                .take(maxResults.coerceIn(1, MAX_SEARCH_RESULTS))
        }

        fun List<Float>.norm(): Float = sqrt(sumOf { (it * it).toDouble() }).toFloat()

        fun List<Float>.toBlob(): ByteArray {
            val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        fun ByteArray.toFloatList(): List<Float> {
            val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
            val values = ArrayList<Float>(size / 4)
            while (buffer.remaining() >= 4) values += buffer.float
            return values
        }
    }
}

class KnowledgeEmbeddingService(private val context: Context) {
    private val gson = Gson()

    fun currentConfig(): KnowledgeEmbeddingConfig {
        val settings = SettingsRepository(context)
        val backend = SettingsRepository.normalizeKnowledgeEmbeddingBackend(settings.knowledgeEmbeddingBackend.value)
        return when (backend) {
            SettingsRepository.KB_EMBED_BACKEND_LLAMA_SERVER -> {
                val url = settings.knowledgeEmbeddingLlamaServerUrl.value.trim().ifBlank { "http://127.0.0.1:8081" }
                KnowledgeEmbeddingConfig(
                    backend = backend,
                    label = url,
                    localModelPath = null,
                    url = url,
                    remoteModel = null
                )
            }
            SettingsRepository.KB_EMBED_BACKEND_OLLAMA -> {
                val url = settings.knowledgeEmbeddingOllamaUrl.value.trim().ifBlank { AIConstants.Urls.OLLAMA_DEFAULT }
                val model = settings.knowledgeEmbeddingOllamaModel.value?.trim()?.takeIf { it.isNotBlank() }
                KnowledgeEmbeddingConfig(
                    backend = backend,
                    label = model ?: url,
                    localModelPath = null,
                    url = url,
                    remoteModel = model
                )
            }
            SettingsRepository.KB_EMBED_BACKEND_LLAMA_SWAP -> {
                val url = settings.knowledgeEmbeddingLlamaSwapUrl.value.trim().ifBlank { SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL }
                val model = settings.knowledgeEmbeddingLlamaSwapModel.value?.trim()?.takeIf { it.isNotBlank() }
                KnowledgeEmbeddingConfig(
                    backend = backend,
                    label = model ?: url,
                    localModelPath = null,
                    url = url,
                    remoteModel = model
                )
            }
            else -> {
                val modelPath = settings.selectedEmbeddingModelPath.value?.trim()?.takeIf { it.isNotBlank() }
                KnowledgeEmbeddingConfig(
                    backend = SettingsRepository.KB_EMBED_BACKEND_LOCAL,
                    label = modelPath?.substringAfterLast("/") ?: "",
                    localModelPath = modelPath,
                    url = "http://127.0.0.1:$EMBEDDING_PORT",
                    remoteModel = null
                )
            }
        }
    }

    suspend fun embedOrNull(text: String): List<Float>? = withContext(Dispatchers.IO) {
        runCatching { embed(text) }
            .onFailure {
                KnowledgeBaseDiagnostics.log("Embedding failed: ${it.message}")
                DebugLog.log("[KnowledgeEmbedding] Embedding failed: ${it.message}")
            }
            .getOrNull()
    }

    suspend fun startLocalServerForCurrentConfig(): Int = withContext(Dispatchers.IO) {
        val config = currentConfig()
        require(config.backend == SettingsRepository.KB_EMBED_BACKEND_LOCAL) {
            context.getString(R.string.kb_error_embedding_requires_local_backend)
        }
        require(config.isConfigured) { context.getString(R.string.kb_upload_needs_embedding) }
        ensureServerStarted(requireNotNull(config.localModelPath))
        EMBEDDING_PORT
    }

    suspend fun stopLocalServer(reason: String = "user") = withContext(Dispatchers.IO) {
        startMutex.withLock {
            stopLocalServerLocked(reason)
        }
    }

    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
        val cleanText = text.take(MAX_EMBED_CHARS).trim()
        require(cleanText.isNotBlank()) { "Embedding text is empty." }
        val config = currentConfig()
        require(config.isConfigured) { "Select and test a knowledge-base embedding backend before indexing." }
        val embedding = when (config.backend) {
            SettingsRepository.KB_EMBED_BACKEND_LOCAL -> requestLocalEmbeddingWithRetry(config, cleanText)
            SettingsRepository.KB_EMBED_BACKEND_LLAMA_SERVER ->
                requestLlamaServerEmbedding(requireNotNull(config.url), cleanText)
            SettingsRepository.KB_EMBED_BACKEND_OLLAMA ->
                requestOllamaEmbedding(requireNotNull(config.url), requireNotNull(config.remoteModel), cleanText)
            SettingsRepository.KB_EMBED_BACKEND_LLAMA_SWAP ->
                requestOpenAiEmbedding(requireNotNull(config.url), requireNotNull(config.remoteModel), cleanText)
            else -> error("Unsupported embedding backend: ${config.backend}")
        }
        require(embedding.isNotEmpty()) { "Embedding backend returned an empty vector." }
        embedding
    }

    private suspend fun requestLocalEmbeddingWithRetry(
        config: KnowledgeEmbeddingConfig,
        cleanText: String
    ): List<Float> {
        val modelPath = requireNotNull(config.localModelPath)
        ensureServerStarted(modelPath)
        var url = localServerBaseUrl()
        return try {
            requestLlamaServerEmbedding(url, cleanText)
        } catch (error: Throwable) {
            if (!isTransientLocalEmbeddingFailure(error)) throw error
            val message = error.message ?: error::class.java.simpleName
            KnowledgeBaseDiagnostics.log("Local embedding request failed transiently ($message); restarting CPU embedding server and retrying once.")
            DebugLog.log("[KnowledgeEmbedding] Transient local embedding failure; retrying once after CPU restart: $message")
            startMutex.withLock {
                startLocalServerLocked(modelPath, preferAccelerator = false)
            }
            if (waitForServerReady()) {
                markServerReady(modelPath)
            } else {
                error(context.getString(R.string.kb_error_embedding_server_not_ready, EMBEDDING_PORT))
            }
            url = localServerBaseUrl()
            requestLlamaServerEmbedding(url, cleanText)
        }
    }

    private fun isTransientLocalEmbeddingFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase(Locale.US)
        return listOf(
            "unexpected end of stream",
            "connection reset",
            "connection refused",
            "socket closed",
            "broken pipe",
            "stream was reset",
            "timeout",
            "failed to connect"
        ).any { it in message }
    }

    private suspend fun ensureServerStarted(modelPath: String) {
        val runtimeConfig = currentLocalRuntimeConfig()
        if (
            isRunningWith(modelPath, runtimeConfig) &&
            _serverStatus.value.running &&
            processController.isAlive()
        ) {
            return
        }

        if (isRunningWith(modelPath, runtimeConfig) && probeServer()) {
            _serverStatus.value = _serverStatus.value.copy(
                running = true,
                starting = false,
                modelLabel = File(modelPath).name,
                port = EMBEDDING_PORT,
                host = runningHost ?: currentLocalBindHost(),
                message = "Ready"
            )
            return
        }

        startMutex.withLock {
            val sameModelAlreadyStarting = isRunningWith(modelPath, runtimeConfig) && serverJob?.isActive == true
            if (!sameModelAlreadyStarting) {
                startLocalServerLocked(modelPath, preferAccelerator = true)
            }
        }

        if (waitForServerReady()) {
            markServerReady(modelPath)
            return
        }

        val attemptedAccelerator = activeBinaryPath
            ?.let { DeviceAcceleration.isAcceleratorBinary(File(it)) }
            ?: false
        val acceleratorExited = attemptedAccelerator && serverJob?.isActive != true
        if (acceleratorExited) {
            KnowledgeBaseDiagnostics.log("Accelerator embedding server failed before readiness; retrying CPU fallback.")
            startMutex.withLock {
                startLocalServerLocked(modelPath, preferAccelerator = false)
            }
            if (waitForServerReady()) {
                markServerReady(modelPath)
                return
            }
        }

        error(context.getString(R.string.kb_error_embedding_server_not_ready, EMBEDDING_PORT))
    }

    private suspend fun startLocalServerLocked(modelPath: String, preferAccelerator: Boolean) {
        stopLocalServerLocked(if (preferAccelerator) "restart-accelerator" else "restart-cpu")
        val runtimeConfig = currentLocalRuntimeConfig()
        ensureEmbeddingPortAvailableOrThrow(
            reason = if (preferAccelerator) "knowledge embedding accelerator start" else "knowledge embedding CPU start",
            host = runtimeConfig.host
        )

        val binaryRepository = BinaryRepository(context)
        val binary = if (preferAccelerator) {
            binaryRepository.getExecutable()
        } else {
            binaryRepository.getCpuExecutable()
        } ?: error(context.getString(R.string.kb_error_embedding_binary_missing))

        runningModelPath = modelPath
        activeBinaryPath = binary.absolutePath
        runningBatchSize = runtimeConfig.batchSize
        runningContextSize = runtimeConfig.contextSize
        runningThreads = runtimeConfig.threads
        runningHost = runtimeConfig.host
        _serverStatus.value = KnowledgeEmbeddingServerStatus(
            running = false,
            starting = true,
            modelLabel = File(modelPath).name,
            binaryName = binary.name,
            port = EMBEDDING_PORT,
            host = runtimeConfig.host,
            message = "Starting"
        )
        KnowledgeBaseDiagnostics.log(
            context.getString(
                R.string.kb_log_starting_embedding_server,
                EMBEDDING_PORT,
                binary.name,
                File(modelPath).name,
                runtimeConfig.chunkSize,
                runtimeConfig.batchSize,
                runtimeConfig.threads
            )
        )
        val config = LlamaConfig(
            modelPath = modelPath,
            isEmbedding = true,
            contextSize = runtimeConfig.contextSize,
            threads = runtimeConfig.threads,
            batchSize = runtimeConfig.batchSize,
            physicalBatchSize = runtimeConfig.batchSize,
            port = EMBEDDING_PORT,
            host = runtimeConfig.host,
            parallel = 1,
            cacheRam = 0
        )
        serverJob = scope.launch {
            runCatching {
                processController.start(
                    binaryPath = binary.absolutePath,
                    config = config,
                    filesDir = context.filesDir,
                    onLog = { line ->
                        KnowledgeBaseDiagnostics.log("server: $line")
                    },
                    onReady = {
                        _serverStatus.value = KnowledgeEmbeddingServerStatus(
                            running = true,
                            starting = false,
                            modelLabel = File(modelPath).name,
                            binaryName = binary.name,
                            port = EMBEDDING_PORT,
                            host = runtimeConfig.host,
                            message = "Ready"
                        )
                        KnowledgeBaseDiagnostics.log("Local embedding server is ready on ${runtimeConfig.host}:$EMBEDDING_PORT.")
                    },
                    onState = null,
                    onClearServerLogs = null,
                    onServerLog = null
                )
            }.onSuccess { result ->
                if (!result.becameReady && !result.stoppedIntentionally) {
                    val message = "Embedding server exited before readiness: ${binary.name}, code=${result.exitCode}"
                    KnowledgeBaseDiagnostics.log(message)
                    DebugLog.log("[KnowledgeEmbedding] $message")
                    _serverStatus.value = _serverStatus.value.copy(
                        running = false,
                        starting = false,
                        message = message
                    )
                } else if (result.stoppedIntentionally) {
                    _serverStatus.value = _serverStatus.value.copy(
                        running = false,
                        starting = false,
                        message = "Stopped"
                    )
                }
            }.onFailure {
                val message = "Embedding server failed: ${it.message}"
                KnowledgeBaseDiagnostics.log(message)
                DebugLog.log("[KnowledgeEmbedding] $message")
                _serverStatus.value = _serverStatus.value.copy(
                    running = false,
                    starting = false,
                    message = message
                )
            }
        }
    }

    private fun stopLocalServerLocked(reason: String) {
        val hadServer = processController.isAlive() || serverJob?.isActive == true || runningModelPath != null
        if (hadServer) {
            KnowledgeBaseDiagnostics.log("Stopping local embedding server ($reason).")
        }
        processController.stop()
        NativeProcessCleanup.cleanupSameUidLlamaServersSync(
            reason = "Knowledge embedding stop: $reason",
            graceMs = 500L,
            forceMs = 500L,
            port = EMBEDDING_PORT
        )
        NativeProcessCleanup.cleanupSameUidPortListenersSync(
            reason = "Knowledge embedding stop: $reason",
            port = EMBEDDING_PORT,
            graceMs = 500L,
            forceMs = 500L
        )
        serverJob?.cancel()
        serverJob = null
        runningModelPath = null
        runningBatchSize = null
        runningContextSize = null
        runningThreads = null
        runningHost = null
        activeBinaryPath = null
        _serverStatus.value = KnowledgeEmbeddingServerStatus(
            running = false,
            starting = false,
            port = EMBEDDING_PORT,
            host = currentLocalBindHost(),
            message = if (hadServer) "Stopped" else "Not running"
        )
    }

    private fun markServerReady(modelPath: String) {
        _serverStatus.value = _serverStatus.value.copy(
            running = true,
            starting = false,
            modelLabel = File(modelPath).name,
            port = EMBEDDING_PORT,
            host = runningHost ?: currentLocalBindHost(),
            message = "Ready"
        )
    }

    private suspend fun waitForServerReady(): Boolean {
        repeat(EMBEDDING_STARTUP_PROBES) {
            if (probeServer()) return true
            if (serverJob?.isActive != true && it >= MIN_EXIT_PROBES_BEFORE_FALLBACK) return false
            delay(EMBEDDING_STARTUP_PROBE_DELAY_MS)
        }
        return probeServer()
    }

    private suspend fun probeServer(): Boolean =
        runCatching { requestLlamaServerEmbedding(localServerBaseUrl(), "ping").isNotEmpty() }.getOrDefault(false)

    private fun localServerBaseUrl(): String = "http://127.0.0.1:$EMBEDDING_PORT"

    private suspend fun ensureEmbeddingPortAvailableOrThrow(reason: String, host: String) {
        if (canBindEmbeddingPort(host, EMBEDDING_PORT)) return
        KnowledgeBaseDiagnostics.log(context.getString(R.string.kb_log_embedding_port_busy_cleanup, EMBEDDING_PORT))
        NativeProcessCleanup.cleanupSameUidLlamaServers(reason = reason, port = EMBEDDING_PORT)
        NativeProcessCleanup.cleanupSameUidPortListeners(reason = reason, port = EMBEDDING_PORT)
        if (waitForEmbeddingPortAvailable(host = host, port = EMBEDDING_PORT)) return

        KnowledgeBaseDiagnostics.log(context.getString(R.string.kb_log_embedding_port_retry_cleanup, EMBEDDING_PORT))
        NativeProcessCleanup.cleanupSameUidLlamaServersForStuckPort(
            reason = "$reason, stuck port fallback",
            port = EMBEDDING_PORT
        )
        NativeProcessCleanup.cleanupSameUidLlamaServers(reason = "$reason, retry after stuck port", port = EMBEDDING_PORT)
        NativeProcessCleanup.cleanupSameUidPortListeners(reason = "$reason, retry after stuck port", port = EMBEDDING_PORT)
        if (waitForEmbeddingPortAvailable(timeoutMs = 20_000L, host = host, port = EMBEDDING_PORT)) return

        val diagnostic = NativeProcessCleanup.describeSameUidPortOccupationSync(EMBEDDING_PORT)
        KnowledgeBaseDiagnostics.log(
            context.getString(
                R.string.kb_log_embedding_port_still_busy,
                EMBEDDING_PORT,
                diagnostic.ifBlank { context.getString(R.string.kb_log_embedding_port_no_visible_owner) }
            )
        )
        error(context.getString(R.string.kb_error_embedding_port_busy, EMBEDDING_PORT))
    }

    private suspend fun waitForEmbeddingPortAvailable(timeoutMs: Long = 15_000L, host: String, port: Int): Boolean {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (canBindEmbeddingPort(host, port)) return true
            delay(250L)
        }
        return canBindEmbeddingPort(host, port)
    }

    private fun currentLocalRuntimeConfig(): LocalEmbeddingRuntimeConfig {
        val settings = SettingsRepository(context)
        val chunkSize = SettingsRepository.normalizeKnowledgeBaseChunkSize(settings.knowledgeBaseChunkSize.value)
        val batchSize = SettingsRepository.normalizeKnowledgeEmbeddingBatchSize(settings.knowledgeEmbeddingBatchSize.value)
        val threads = SettingsRepository.normalizeKnowledgeEmbeddingThreads(settings.knowledgeEmbeddingThreads.value)
        return LocalEmbeddingRuntimeConfig(
            chunkSize = chunkSize,
            batchSize = batchSize,
            contextSize = SettingsRepository.knowledgeEmbeddingContextSizeForBatchSize(batchSize),
            threads = threads,
            host = currentLocalBindHost()
        )
    }

    private fun isRunningWith(modelPath: String, runtimeConfig: LocalEmbeddingRuntimeConfig): Boolean =
            runningModelPath == modelPath &&
            runningBatchSize == runtimeConfig.batchSize &&
            runningContextSize == runtimeConfig.contextSize &&
            runningThreads == runtimeConfig.threads &&
            runningHost == runtimeConfig.host

    private fun currentLocalBindHost(): String =
        if (SettingsRepository(context).knowledgeEmbeddingNetworkVisible.value) PUBLIC_HOST else LOCALHOST

    private suspend fun requestLlamaServerEmbedding(baseUrl: String, text: String): List<Float> =
        withContext(Dispatchers.IO) {
            val response = postJson(
                baseUrl = baseUrl,
                path = "/embedding",
                body = mapOf("content" to text)
            )
            parseEmbeddingResponse(response)
        }

    private fun requestOllamaEmbedding(baseUrl: String, model: String, text: String): List<Float> {
        val modern = runCatching {
            val response = postJson(
                baseUrl = baseUrl,
                path = "/api/embed",
                body = OllamaEmbedRequest(model = model, input = listOf(text))
            )
            gson.fromJson(response, OllamaEmbedResponse::class.java)
                .embeddings
                ?.firstOrNull()
                .orEmpty()
        }.getOrElse { emptyList() }
        if (modern.isNotEmpty()) return modern

        val legacy = postJson(
            baseUrl = baseUrl,
            path = "/api/embeddings",
            body = OllamaLegacyEmbeddingRequest(model = model, prompt = text)
        )
        return gson.fromJson(legacy, OllamaLegacyEmbeddingResponse::class.java).embedding.orEmpty()
    }

    private fun requestOpenAiEmbedding(baseUrl: String, model: String, text: String): List<Float> {
        val response = postJson(
            baseUrl = baseUrl,
            path = "/v1/embeddings",
            body = OpenAiEmbeddingRequest(model = model, input = text)
        )
        return gson.fromJson(response, OpenAiEmbeddingResponse::class.java)
            .data
            .firstOrNull()
            ?.embedding
            .orEmpty()
    }

    private fun postJson(baseUrl: String, path: String, body: Any): String {
        val requestBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(joinUrl(baseUrl, path))
            .post(requestBody)
            .build()
        httpClient().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Embedding backend returned HTTP ${response.code}: ${responseBody.take(240)}")
            }
            return responseBody
        }
    }

    private fun httpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    companion object {
        const val EMBEDDING_PORT = 8081
        const val LOCALHOST = "127.0.0.1"
        const val PUBLIC_HOST = "0.0.0.0"
        private const val MAX_EMBED_CHARS = 8_000
        private const val EMBEDDING_STARTUP_PROBES = 240
        private const val MIN_EXIT_PROBES_BEFORE_FALLBACK = 4
        private const val EMBEDDING_STARTUP_PROBE_DELAY_MS = 250L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val processController = ProcessController()
        private val startMutex = Mutex()
        private val _serverStatus = MutableStateFlow(KnowledgeEmbeddingServerStatus())
        val serverStatus = _serverStatus.asStateFlow()
        @Volatile private var runningModelPath: String? = null
        @Volatile private var serverJob: Job? = null
        @Volatile private var activeBinaryPath: String? = null
        @Volatile private var runningBatchSize: Int? = null
        @Volatile private var runningContextSize: Int? = null
        @Volatile private var runningThreads: Int? = null
        @Volatile private var runningHost: String? = null

        private fun joinUrl(baseUrl: String, path: String): String =
            baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

        private fun canBindEmbeddingPort(host: String = LOCALHOST, port: Int = EMBEDDING_PORT): Boolean =
            runCatching {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(host, port))
                }
            }.isSuccess

        internal fun parseEmbeddingResponse(response: String): List<Float> {
            val root = runCatching { JsonParser().parse(response) }.getOrNull() ?: return emptyList()
            return extractEmbeddingVector(root).orEmpty()
        }

        private fun extractEmbeddingVector(element: JsonElement?): List<Float>? {
            if (element == null || element.isJsonNull) return null
            if (element.isJsonArray) {
                val array = element.asJsonArray
                if (array.size() == 0) return emptyList()
                if (array.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }) {
                    return array.map { it.asFloat }
                }
                array.forEach { child ->
                    extractEmbeddingVector(child)?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                return emptyList()
            }
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                listOf("embedding", "embeddings", "data").forEach { key ->
                    if (obj.has(key)) {
                        extractEmbeddingVector(obj.get(key))?.takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
            }
            return null
        }
    }

    private data class OllamaEmbedRequest(val model: String, val input: List<String>)
    private data class OllamaEmbedResponse(val embeddings: List<List<Float>>?)
    private data class OllamaLegacyEmbeddingRequest(val model: String, val prompt: String)
    private data class OllamaLegacyEmbeddingResponse(val embedding: List<Float>?)
    private data class OpenAiEmbeddingRequest(val model: String, val input: String)
    private data class OpenAiEmbeddingData(val embedding: List<Float>)
    private data class OpenAiEmbeddingResponse(val data: List<OpenAiEmbeddingData>)
    private data class LocalEmbeddingRuntimeConfig(
        val chunkSize: Int,
        val batchSize: Int,
        val contextSize: Int,
        val threads: Int,
        val host: String
    )
}
