package com.example.llamadroid.service

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PDFSummaryService {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val DEFAULT_SUMMARY_PROMPT = """You are an expert document summarizer. Create a concise summary of this text excerpt.

Instructions:
- Identify the main points and key information
- Extract important facts, findings, or conclusions
- Use bullet points for clarity
- Keep it under 500 words

Summary:"""

    const val DEFAULT_UNIFICATION_PROMPT = """You are an expert at combining multiple summaries into one comprehensive document summary.

Instructions:
1. Merge these chunk summaries into a single coherent summary
2. Start with a clear TITLE that captures the document's main topic
3. Remove redundancy while preserving all unique information
4. Organize by themes with clear sections

Format:
# [Document Title]

## Overview
[Brief intro]

## Key Points
[Main content]

## Conclusions
[Final takeaways]

Chunk summaries to unify:"""

    private var currentJob: Job? = null
    private var currentClient: RemoteSummaryClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationTaskId: Int? = null
    private var isCancelled = false

    private val _result = MutableStateFlow<Result<String>?>(null)
    val result: StateFlow<Result<String>?> = _result.asStateFlow()

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    private val _currentPhase = MutableStateFlow("")
    val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _partialChunkSummaries = MutableStateFlow<List<String>>(emptyList())
    val partialChunkSummaries: StateFlow<List<String>> = _partialChunkSummaries.asStateFlow()

    private fun progressLabel(context: Context, progress: RemoteSummaryProgress): String {
        return when (progress.phase) {
            RemoteSummaryPhase.SINGLE_SUMMARY -> context.getString(R.string.summary_progress_single)
            RemoteSummaryPhase.CHUNK_SUMMARY -> context.getString(
                R.string.summary_progress_chunk,
                progress.currentChunk,
                progress.totalChunks
            )
            RemoteSummaryPhase.MERGE_SUMMARY -> {
                if (progress.mergeBatch > 0 && progress.mergeBatchCount > 0) {
                    context.getString(
                        R.string.summary_progress_merge_batch,
                        progress.mergeBatch,
                        progress.mergeBatchCount
                    )
                } else {
                    context.getString(R.string.summary_progress_merge)
                }
            }
        }
    }

    suspend fun refreshBackendMetadata(context: Context): Result<RemoteSummaryMetadata> = withContext(Dispatchers.IO) {
        val settingsRepo = SettingsRepository(context)
        val client = RemoteSummaryClientFactory.fromSnapshot(context, settingsRepo.pdfSummarySettings.snapshot())
        client.fetchMetadata().onSuccess { metadata ->
            persistMetadata(settingsRepo, metadata)
            PdfSummaryStateHolder.setMetadataMessage(
                when (SettingsRepository.normalizeOllamaOrLlamaBackend(metadata.backend)) {
                    SettingsRepository.PDF_BACKEND_OLLAMA ->
                        context.getString(R.string.pdf_metadata_ollama_loaded, metadata.availableModels.size)
                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP ->
                        context.getString(R.string.pdf_metadata_llama_swap_loaded, metadata.availableModels.size)
                    else -> {
                        val modelText = metadata.serverModelLabel ?: context.getString(R.string.pdf_server_value_unavailable)
                        val contextText = metadata.serverContextLabel ?: context.getString(R.string.pdf_server_value_unavailable)
                        context.getString(R.string.pdf_metadata_llama_loaded, modelText, contextText)
                    }
                }
            )
        }
    }

    suspend fun estimateChunkCount(context: Context, text: String): Result<RemoteSummaryPlanEstimate> = withContext(Dispatchers.IO) {
        val settingsRepo = SettingsRepository(context)
        val snapshot = settingsRepo.pdfSummarySettings.snapshot()
        val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
        val orchestrator = RemoteSummaryOrchestrator(client)
        val summaryPrompt = snapshot.summaryPrompt ?: DEFAULT_SUMMARY_PROMPT
        runCatching {
            orchestrator.estimateChunkPlan(
                summaryPrompt = summaryPrompt,
                text = text,
                chunkContext = snapshot.chunkContext,
                chunkMaxTokens = snapshot.chunkMaxTokens,
                targetLanguage = snapshot.targetLanguage
            )
        }.onSuccess {
            PdfSummaryStateHolder.setProjectedChunkCount(it.chunkCount)
        }
    }

    fun startSummarization(
        context: Context,
        text: String,
        pdfFileName: String = "PDF",
        settingsOverride: RemoteSummarySettingsSnapshot? = null
    ) {
        currentJob?.cancel()
        isCancelled = false
        _result.value = null
        _partialChunkSummaries.value = emptyList()
        _isSummarizing.value = true
        PdfSummaryStateHolder.setIsSummarizing(true)
        PdfSummaryStateHolder.setCancelled(false)
        PdfSummaryStateHolder.setError(null)
        PdfSummaryStateHolder.setSummary("")
        PdfSummaryStateHolder.setPartialSummaries(emptyList())

        notificationTaskId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.PDF_SUMMARY,
            context.getString(R.string.pdf_notification_summarizing, pdfFileName)
        )
        acquireWakeLock(context)
        RemoteSummaryProtection.acquire(context)

        currentJob = serviceScope.launch {
            val settingsRepo = SettingsRepository(context)
            val snapshot = settingsOverride ?: settingsRepo.pdfSummarySettings.snapshot()
            val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
            val orchestrator = RemoteSummaryOrchestrator(client)
            currentClient = client

            val summaryPrompt = snapshot.summaryPrompt ?: DEFAULT_SUMMARY_PROMPT
            val mergePrompt = snapshot.mergePrompt ?: DEFAULT_UNIFICATION_PROMPT

            val result = try {
                val metadata = client.fetchMetadata().getOrNull()
                if (metadata != null) {
                    persistMetadata(settingsRepo, metadata)
                }
                if (SettingsRepository.requiresSelectedRemoteModel(snapshot.backend) &&
                    selectedSummaryModel(snapshot).isNullOrBlank()
                ) {
                    Result.failure(Exception(missingModelMessage(context, snapshot.backend)))
                } else {
                    val execution = orchestrator.summarize(
                        sourceText = text,
                        summaryPrompt = summaryPrompt,
                        mergePrompt = mergePrompt,
                        chunkContext = snapshot.chunkContext,
                        chunkMaxTokens = snapshot.chunkMaxTokens,
                        mergeContext = snapshot.mergeContext,
                        mergeMaxTokens = snapshot.mergeMaxTokens,
                        temperature = snapshot.temperature,
                        thinkingEnabled = snapshot.thinkingEnabled,
                        targetLanguage = snapshot.targetLanguage,
                        isCancelled = { isCancelled },
                        onProgress = { progress ->
                            val label = progressLabel(context, progress)
                            _currentChunk.value = progress.currentChunk
                            _totalChunks.value = progress.totalChunks
                            _currentPhase.value = label
                            _partialChunkSummaries.value = progress.partialSummaries
                            PdfSummaryStateHolder.setCurrentChunk(progress.currentChunk)
                            PdfSummaryStateHolder.setTotalChunks(progress.totalChunks)
                            PdfSummaryStateHolder.setProgressMessage(label)
                            PdfSummaryStateHolder.setPartialSummaries(progress.partialSummaries)
                            notificationTaskId?.let { taskId ->
                                val fraction = if (progress.totalChunks > 0 && progress.currentChunk > 0) {
                                    progress.currentChunk.toFloat() / progress.totalChunks
                                } else {
                                    0.9f
                                }
                                UnifiedNotificationManager.updateProgress(taskId, fraction.coerceIn(0f, 1f), label)
                            }
                        }
                    )

                    when {
                        execution.cancelled -> Result.failure(Exception(context.getString(R.string.action_cancelled)))
                        execution.summary != null -> Result.success(execution.summary)
                        else -> Result.failure(Exception(execution.errorMessage ?: context.getString(R.string.pdf_error_summary_failed)))
                    }
                }
            } catch (e: CancellationException) {
                Result.failure(Exception(context.getString(R.string.action_cancelled)))
            } catch (e: Exception) {
                DebugLog.log("[PDF-AI] Error: ${e.message}")
                Result.failure(e)
            }

            result.onSuccess { summaryText ->
                PdfSummaryStateHolder.setSummary(summaryText)
                PdfSummaryStateHolder.setPartialSummaries(emptyList())
                _partialChunkSummaries.value = emptyList()
                autoSaveSummary(context, pdfFileName, summaryText)
                notificationTaskId?.let { UnifiedNotificationManager.completeTask(it, context.getString(R.string.pdf_notification_complete)) }
            }.onFailure { failure ->
                if (failure.message == context.getString(R.string.action_cancelled)) {
                    PdfSummaryStateHolder.setCancelled(true)
                    notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                } else {
                    PdfSummaryStateHolder.setError(failure.message)
                    notificationTaskId?.let {
                        UnifiedNotificationManager.failTask(it, failure.message ?: context.getString(R.string.error_generic))
                    }
                }
            }

            _result.value = result
            _isSummarizing.value = false
            _currentChunk.value = 0
            _totalChunks.value = 0
            _currentPhase.value = ""
            PdfSummaryStateHolder.setIsSummarizing(false)
            currentClient = null
            releaseWakeLock()
            RemoteSummaryProtection.release()
            notificationTaskId = null
        }
    }

    fun cancel() {
        isCancelled = true
        currentClient?.cancelActiveCall()
        currentJob?.cancel()
        currentJob = null
        _isSummarizing.value = false
        _currentChunk.value = 0
        _totalChunks.value = 0
        _currentPhase.value = ""
        PdfSummaryStateHolder.setIsSummarizing(false)
        PdfSummaryStateHolder.setCancelled(true)
        PdfSummaryStateHolder.setProgressMessage("")
        releaseWakeLock()
        RemoteSummaryProtection.release()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
    }

    fun clearResult() {
        _result.value = null
    }

    fun clearPartialChunkSummaries() {
        _partialChunkSummaries.value = emptyList()
        PdfSummaryStateHolder.setPartialSummaries(emptyList())
    }

    private fun persistMetadata(settingsRepo: SettingsRepository, metadata: RemoteSummaryMetadata) {
        if (metadata.backend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER) {
            settingsRepo.setPdfSummaryLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setPdfSummaryLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setPdfSummaryLlamaServerContextLabel(metadata.serverContextLabel)
        }
    }

    private fun selectedSummaryModel(snapshot: com.example.llamadroid.data.RemoteSummarySettingsSnapshot): String? =
        when (SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> snapshot.llamaSwapModel
            SettingsRepository.PDF_BACKEND_OLLAMA -> snapshot.ollamaModel
            else -> snapshot.llamaServerModelLabel
        }

    private fun missingModelMessage(context: Context, backend: String): String =
        if (SettingsRepository.isLlamaSwapBackend(backend)) {
            context.getString(R.string.pdf_error_missing_llama_swap_model)
        } else {
            context.getString(R.string.pdf_error_missing_ollama_model)
        }

    private suspend fun autoSaveSummary(context: Context, pdfFileName: String, summaryText: String) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.noteDao().insert(
                NoteEntity(
                    title = context.getString(R.string.pdf_summary_note_title, pdfFileName),
                    content = summaryText,
                    type = NoteType.PDF_SUMMARY,
                    sourceFile = pdfFileName
                )
            )
            DebugLog.log("[PDF-AI] Summary auto-saved: $pdfFileName (${summaryText.length} chars)")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.pdf_summary_saved), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF-AI] Failed to auto-save: ${e.message}")
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaDroid:PDFSummary")
            wakeLock?.acquire(60 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}
