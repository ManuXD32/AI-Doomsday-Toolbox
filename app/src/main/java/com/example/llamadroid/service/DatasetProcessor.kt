package com.example.llamadroid.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.llamadroid.data.api.LlamaServerApi
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.llamadroid.R

/**
 * Processes dataset chunks through LLM pipeline:
 * Clean → Question (5x) → Answer → Rate
 * 
 * Uses companion object for static state so processing survives navigation.
 */
class DatasetProcessor(
    context: Context,
    private val dao: DatasetDao
) {
    private val context = context.applicationContext
    private val db = AppDatabase.getDatabase(context)
    
    // Progress state
    data class Progress(
        val stage: String,
        val current: Int,
        val total: Int,
        val currentItem: String = "",
        val projectId: Long = 0
    )
    
    // Job types for queue
    sealed class Job(val name: String, val projectId: Long) {
        class RegenAnswer(val qaId: Long, projectId: Long, val answerPrompt: String, name: String) : Job(name, projectId)
        class RegenRating(val qaId: Long, projectId: Long, val reviewPrompt: String, name: String) : Job(name, projectId)
        class RegenAnswers(val qaIds: Set<Long>, projectId: Long, val answerPrompt: String, name: String) : Job(name, projectId)
        class RegenRatings(val qaIds: Set<Long>, projectId: Long, val reviewPrompt: String, name: String) : Job(name, projectId)
        class RegenQuestions(val chunkIds: Set<Long>, projectId: Long, val questionPrompt: String, name: String) : Job(name, projectId)
        class RegenClean(val chunkId: Long, projectId: Long, val cleanPrompt: String, name: String) : Job(name, projectId)
        class Clean(projectId: Long, val cleanPrompt: String, name: String) : Job(name, projectId)
        class Questions(projectId: Long, val questionPrompt: String, name: String) : Job(name, projectId)
        class Answers(projectId: Long, val answerPrompt: String, name: String) : Job(name, projectId)
        class Rating(projectId: Long, val reviewPrompt: String, name: String) : Job(name, projectId)
        class ImportPdf(projectId: Long, val sourceUri: String, val sourceName: String, name: String) : Job(name, projectId)
        class ImportTxt(projectId: Long, val sourceUri: String, val sourceName: String, name: String) : Job(name, projectId)
    }

    enum class JobOutcome {
        SUCCESS,
        FAILED,
        CANCELLED
    }

    interface RuntimeHooks {
        fun onJobStarted(job: Job)
        fun onJobFinished(job: Job?, outcome: JobOutcome, errorMessage: String? = null)
    }
    
    companion object {
        private val _progress = MutableStateFlow<Progress?>(null)
        val progress = _progress.asStateFlow()
        
        private val _isProcessing = MutableStateFlow(false)
        val isProcessing = _isProcessing.asStateFlow()
        
        // Job queue
        private val _jobQueue = MutableStateFlow<List<Job>>(emptyList())
        val jobQueue = _jobQueue.asStateFlow()

        private val _activeJob = MutableStateFlow<Job?>(null)
        val activeJob = _activeJob.asStateFlow()
        
        private var processingJob: kotlinx.coroutines.Job? = null
        private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        // Queue processing is process-scoped and DatasetProcessor stores applicationContext only.
        @SuppressLint("StaticFieldLeak")
        private var processorInstance: DatasetProcessor? = null
        private var runtimeHooks: RuntimeHooks? = null
        
        fun setProcessor(processor: DatasetProcessor) {
            processorInstance = processor
        }

        fun setRuntimeHooks(hooks: RuntimeHooks?) {
            runtimeHooks = hooks
        }
        
        fun queueJob(job: Job) {
            queueJobs(listOf(job))
        }

        fun queueJobs(jobs: List<Job>) {
            if (jobs.isEmpty()) return
            _jobQueue.value = _jobQueue.value + jobs
            // Start processing only when there is no active handoff in flight.
            if (!_isProcessing.value && _activeJob.value == null) {
                processNextJob()
            }
        }

        fun restoreQueuedJobs(jobs: List<Job>) {
            _jobQueue.value = jobs
            if (!_isProcessing.value && _activeJob.value == null) {
                processNextJob()
            }
        }
        
        fun removeJob(index: Int) {
            val current = _jobQueue.value.toMutableList()
            if (index in current.indices) {
                current.removeAt(index)
                _jobQueue.value = current
            }
        }
        
        fun moveJobUp(index: Int) {
            if (index > 0) {
                val current = _jobQueue.value.toMutableList()
                val item = current.removeAt(index)
                current.add(index - 1, item)
                _jobQueue.value = current
            }
        }
        
        fun moveJobDown(index: Int) {
            val current = _jobQueue.value.toMutableList()
            if (index < current.size - 1) {
                val item = current.removeAt(index)
                current.add(index + 1, item)
                _jobQueue.value = current
            }
        }
        
        private fun processNextJob() {
            val queue = _jobQueue.value
            if (queue.isEmpty()) return
            
            val job = queue.first()
            _jobQueue.value = queue.drop(1)
            _activeJob.value = job
            runtimeHooks?.onJobStarted(job)
            processorInstance?.executeJob(job)
        }
        
        private fun completeActiveJob(
            outcome: JobOutcome,
            errorMessage: String? = null,
            advanceQueue: Boolean = false
        ) {
            val finishedJob = _activeJob.value
            _activeJob.value = null
            runtimeHooks?.onJobFinished(finishedJob, outcome, errorMessage)
            if (advanceQueue && _jobQueue.value.isNotEmpty()) {
                processNextJob()
            }
        }
        
        fun cancel() {
            processingJob?.cancel()
        }

        fun cancelAll() {
            _jobQueue.value = emptyList()
            processingJob?.cancel()
        }

        fun cancelAllImmediately() {
            _jobQueue.value = emptyList()
            processingJob?.cancel()
            processingJob = null
            _progress.value = null
            _isProcessing.value = false
            _activeJob.value = null
        }
        
        private fun updateProgressInternal(progress: Progress?) {
            _progress.value = progress
        }

        private fun finishCurrentJob(
            outcome: JobOutcome,
            errorMessage: String? = null,
            advanceQueue: Boolean = false
        ) {
            completeActiveJob(
                outcome = outcome,
                errorMessage = errorMessage,
                advanceQueue = advanceQueue
            )
        }
    }
    
    // Instance method that updates static state and triggers notification
    private fun updateProgress(progress: Progress?) {
        updateProgressInternal(progress)
    }
    
    private fun setProcessing(value: Boolean) {
        _isProcessing.value = value
    }

    private fun createBackend(project: DatasetProjectEntity): DatasetLlmBackend {
        return when (normalizeDatasetBackend(project.backend)) {
            SettingsRepository.PDF_BACKEND_OLLAMA -> {
                if (project.ollamaModel.isNullOrBlank()) {
                    throw IllegalStateException(context.getString(R.string.dataset_ollama_model_required))
                }
                OllamaDatasetLlmBackend(project)
            }

            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> {
                if (project.ollamaModel.isNullOrBlank()) {
                    throw IllegalStateException(context.getString(R.string.dataset_llama_swap_model_required))
                }
                LlamaSwapDatasetLlmBackend(project)
            }

            else -> LlamaServerDatasetLlmBackend(createApi(project.serverUrl))
        }
    }

    private suspend fun showProcessingError(projectId: Long, throwable: Throwable) {
        updateProgress(
            Progress(
                stage = context.getString(R.string.dataset_stage_error),
                current = 0,
                total = 0,
                currentItem = context.getString(
                    R.string.dataset_server_error_param,
                    throwable.message?.take(80) ?: context.getString(R.string.error_generic)
                ),
                projectId = projectId
            )
        )
        delay(3000)
    }
    
    // Create Retrofit with custom server URL
    private fun createApi(serverUrl: String): LlamaServerApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(1200, TimeUnit.SECONDS)  // 20 min for slow LLM on mobile
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        
        // Ensure URL ends with /
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlamaServerApi::class.java)
    }
    
    // ========== Chunking ==========
    
    fun chunkText(text: String, chunkSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    private fun importFileLabel(typeLabel: String, fileName: String): String =
        context.getString(R.string.dataset_import_file_diag, typeLabel, fileName)

    private fun pdfImportDiagnostics(
        fileName: String,
        extraction: PdfExtractionResult? = null,
        progress: PdfExtractionProgress? = null,
        chunkCount: Int? = null
    ): String = buildString {
        append(importFileLabel(context.getString(R.string.file_type_pdf), fileName))
        progress?.let {
            append('\n')
            append(
                context.getString(
                    R.string.dataset_import_pdf_pages_diag,
                    it.totalPages,
                    it.textLayerPages,
                    it.ocrPages,
                    it.emptyPages
                )
            )
            append('\n')
            append(context.getString(R.string.dataset_import_pdf_page_progress_diag, it.processedPages, it.totalPages))
            append('\n')
            append(context.getString(R.string.dataset_import_text_chars_diag, it.textCharacters))
        } ?: extraction?.let {
            append('\n')
            append(
                context.getString(
                    R.string.dataset_import_pdf_pages_diag,
                    it.totalPages,
                    it.textLayerPages,
                    it.ocrPages,
                    it.emptyPages
                )
            )
            append('\n')
            append(context.getString(R.string.dataset_import_text_chars_diag, it.text.length))
        }
        chunkCount?.let {
            append('\n')
            append(context.getString(R.string.dataset_import_chunks_diag, it))
        }
    }

    private fun txtImportDiagnostics(
        fileName: String,
        textCharacters: Int? = null,
        chunkCount: Int? = null
    ): String = buildString {
        append(importFileLabel(context.getString(R.string.file_type_text), fileName))
        textCharacters?.let {
            append('\n')
            append(context.getString(R.string.dataset_import_text_chars_diag, it))
        }
        chunkCount?.let {
            append('\n')
            append(context.getString(R.string.dataset_import_chunks_diag, it))
        }
    }

    private suspend fun saveImportedSource(
        projectId: Long,
        sourceType: SourceType,
        sourceUri: String,
        sourceName: String,
        chunkTexts: List<String>
    ) {
        db.withTransaction {
            val existingSource = dao.getSourceByProjectAndUri(projectId, sourceUri)
            if (existingSource != null) {
                if (dao.getChunkCountForSource(existingSource.id) > 0) {
                    return@withTransaction
                }
                dao.deleteChunksForSource(existingSource.id)
                dao.deleteSource(existingSource)
            }
            val sourceId = dao.insertSource(
                DatasetSourceEntity(
                    projectId = projectId,
                    type = sourceType,
                    uri = sourceUri,
                    name = sourceName,
                    extractedText = null
                )
            )
            dao.insertChunks(
                chunkTexts.mapIndexed { index, chunkText ->
                    DatasetChunkEntity(
                        projectId = projectId,
                        sourceId = sourceId,
                        chunkIndex = index,
                        originalText = chunkText
                    )
                }
            )
        }
    }

    fun runImportPdf(project: DatasetProjectEntity, sourceUri: String, sourceName: String) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            val fileName = sourceName.ifBlank { context.getString(R.string.file_type_pdf) }

            try {
                val uri = Uri.parse(sourceUri)
                val pdfService = PDFService(context)
                var extractionProgressTotal = 4

                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_preparing),
                        1,
                        4,
                        pdfImportDiagnostics(fileName),
                        project.id
                    )
                )

                ensureActive()
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_extracting_pdf),
                        2,
                        4,
                        pdfImportDiagnostics(fileName),
                        project.id
                    )
                )
                val extraction = pdfService.extractTextDetailed(uri) { pageProgress ->
                    extractionProgressTotal = pageProgress.totalPages + 3
                    updateProgress(
                        Progress(
                            context.getString(R.string.dataset_import_stage_extracting_pdf),
                            current = 1 + pageProgress.processedPages,
                            total = extractionProgressTotal,
                            currentItem = pdfImportDiagnostics(fileName, progress = pageProgress),
                            projectId = project.id
                        )
                    )
                }.getOrThrow()

                ensureActive()
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_chunking),
                        extractionProgressTotal - 1,
                        extractionProgressTotal,
                        pdfImportDiagnostics(fileName, extraction),
                        project.id
                    )
                )
                val chunkTexts = withContext(Dispatchers.Default) {
                    chunkText(extraction.text, project.chunkSize)
                }

                ensureActive()
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_saving),
                        extractionProgressTotal,
                        extractionProgressTotal,
                        pdfImportDiagnostics(fileName, extraction = extraction, chunkCount = chunkTexts.size),
                        project.id
                    )
                )
                saveImportedSource(
                    projectId = project.id,
                    sourceType = SourceType.PDF,
                    sourceUri = sourceUri,
                    sourceName = fileName,
                    chunkTexts = chunkTexts
                )
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }

    fun runImportTxt(project: DatasetProjectEntity, sourceUri: String, sourceName: String) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            val fileName = sourceName.ifBlank { context.getString(R.string.file_type_text) }

            try {
                val uri = Uri.parse(sourceUri)
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_preparing),
                        1,
                        4,
                        txtImportDiagnostics(fileName),
                        project.id
                    )
                )

                ensureActive()
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_reading_txt),
                        2,
                        4,
                        txtImportDiagnostics(fileName),
                        project.id
                    )
                )
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: error(context.getString(R.string.dataset_import_error_cannot_open_file))
                }

                ensureActive()
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_chunking),
                        3,
                        4,
                        txtImportDiagnostics(fileName, text.length),
                        project.id
                    )
                )
                val chunkTexts = withContext(Dispatchers.Default) {
                    chunkText(text, project.chunkSize)
                }

                ensureActive()
                updateProgress(
                    Progress(
                        context.getString(R.string.dataset_import_stage_saving),
                        4,
                        4,
                        txtImportDiagnostics(fileName, text.length, chunkTexts.size),
                        project.id
                    )
                )
                saveImportedSource(
                    projectId = project.id,
                    sourceType = SourceType.TXT,
                    sourceUri = sourceUri,
                    sourceName = fileName,
                    chunkTexts = chunkTexts
                )
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    // Build context (50% of prev and next chunks)
    fun buildContext(chunks: List<String>, index: Int): Triple<String, String, String> {
        val prevChunk = if (index > 0) {
            val prev = chunks[index - 1]
            prev.takeLast(prev.length / 2)
        } else ""
        
        val nextChunk = if (index < chunks.size - 1) {
            val next = chunks[index + 1]
            next.take(next.length / 2)
        } else ""
        
        return Triple(prevChunk, chunks[index], nextChunk)
    }
    
    // ========== LLM Calls ==========
    
    private suspend fun callLLM(
        backend: DatasetLlmBackend,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stop: List<String> = emptyList()
    ): String {
        return backend.complete(prompt, maxTokens, temperature, stop)
    }
    
    private suspend fun cleanChunk(
        backend: DatasetLlmBackend,
        chunk: String,
        prevContext: String,
        nextContext: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val formattedPrompt = prompt
            .replace("{prev_chunk_50%}", prevContext)
            .replace("{chunk}", chunk)
            .replace("{next_chunk_50%}", nextContext)
        
        return callLLM(backend, formattedPrompt, maxTokens, temperature)
    }

    private fun applyFinalLanguageInstruction(prompt: String, finalLanguage: String): String {
        val language = finalLanguage.trim()
        val instruction = if (language.isBlank()) {
            "Final language: use the same language as the source/context. Keep required fixed phrases and machine-readable tokens exactly as written."
        } else {
            "Final language: write the final dataset output in $language. Keep required fixed phrases and machine-readable tokens exactly as written."
        }
        return if (prompt.contains("{final_language_instruction}")) {
            prompt.replace("{final_language_instruction}", instruction)
        } else {
            "$instruction\n\n$prompt"
        }
    }
    
    private suspend fun generateQuestion(
        backend: DatasetLlmBackend,
        chunk: String,
        prevContext: String,
        nextContext: String,
        prompt: String,
        finalLanguage: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val formattedPrompt = applyFinalLanguageInstruction(prompt, finalLanguage)
            .replace("{prev_chunk_50%}", prevContext)
            .replace("{chunk}", chunk)
            .replace("{next_chunk_50%}", nextContext)
        
        val response = callLLM(backend, formattedPrompt, maxTokens, temperature, listOf("\n"))
        return backend.normalizeQuestionOutput(response)
    }
    
    private suspend fun generateAnswer(
        backend: DatasetLlmBackend,
        question: String,
        context: String,
        prompt: String,
        finalLanguage: String,
        useCoT: Boolean,
        maxTokens: Int,
        temperature: Float
    ): String {
        var formattedPrompt = applyFinalLanguageInstruction(prompt, finalLanguage)
            .replace("{question}", question)
            .replace("{context}", context)
        
        if (useCoT) {
            formattedPrompt = formattedPrompt.replace("{if CoT: Think step by step before answering.}", 
                "Think step by step before answering.")
        } else {
            formattedPrompt = formattedPrompt.replace("{if CoT: Think step by step before answering.}", "")
        }
        
        return callLLM(backend, formattedPrompt, maxTokens, temperature)
    }
    
    private suspend fun rateQA(
        backend: DatasetLlmBackend,
        question: String,
        answer: String,
        context: String,
        prompt: String,
        finalLanguage: String
    ): Pair<Int, String> {
        val formattedPrompt = applyFinalLanguageInstruction(prompt, finalLanguage)
            .replace("{question}", question)
            .replace("{answer}", answer)
            .replace("{context}", context)
        
        // Allow more tokens for justification
        val response = callLLM(backend, formattedPrompt, 250, 0.1f)
        
        // Extract FINAL score from response - look for specific patterns in priority order
        val score = extractFinalScore(response)
        
        return Pair(score, response.trim())
    }
    
    /**
     * Extract the final/total score from LLM response.
     * Priority: FINAL_SCORE: X > Total Score: X > Total: X/5 > last digit
     */
    private fun extractFinalScore(response: String): Int {
        // Try FINAL_SCORE: X pattern (most strict)
        val finalScoreMatch = Regex("FINAL_SCORE:\\s*(\\d)").find(response)
        if (finalScoreMatch != null) {
            return finalScoreMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        // Try Total Score: X pattern
        val totalScoreMatch = Regex("(?i)total\\s*score:?\\s*(\\d)").find(response)
        if (totalScoreMatch != null) {
            return totalScoreMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        // Try Total: X/5 or Total: X pattern
        val totalMatch = Regex("(?i)total:?\\s*(\\d)/?\\d?").find(response)
        if (totalMatch != null) {
            return totalMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        // Fallback: find the LAST digit 0-5 in response (likely the final score)
        val allDigits = Regex("[0-5]").findAll(response).toList()
        if (allDigits.isNotEmpty()) {
            return allDigits.last().value.toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        return 3 // Default
    }
    
    // ========== Pipeline ==========
    
    fun runCleaningStage(
        project: DatasetProjectEntity,
        cleanPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            com.example.llamadroid.util.DebugLog.log("[Dataset] Starting cleaning stage for project ${project.id}")
            
            try {
                val backend = createBackend(project)
                val pendingChunks = dao.getChunksByStatus(project.id, ChunkStatus.PENDING)
                val total = pendingChunks.size
                com.example.llamadroid.util.DebugLog.log("[Dataset] Found $total pending chunks to clean")
                
                if (total == 0) {
                    updateProgress(Progress(context.getString(R.string.dataset_stage_done), 0, 0, context.getString(R.string.dataset_no_chunks_clean), project.id))
                    com.example.llamadroid.util.DebugLog.log("[Dataset] No chunks to clean, done")
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                
                pendingChunks.forEachIndexed { index, chunk ->
                    ensureActive()
                    com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning chunk ${index + 1}/$total (id=${chunk.id})")
                    
                    updateProgress(Progress(context.getString(R.string.dataset_stage_cleaning), index + 1, total, context.getString(R.string.dataset_chunk_param, chunk.chunkIndex), project.id))
                    
                    try {
                        // Get all chunks for context
                        val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.PENDING) + 
                            dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                        val sortedChunks = allChunks.sortedBy { it.chunkIndex }
                        val chunkTexts = sortedChunks.map { it.cleanedText ?: it.originalText }
                        val currentIndex = sortedChunks.indexOfFirst { it.id == chunk.id }
                        if (currentIndex < 0) {
                            com.example.llamadroid.util.DebugLog.log(
                                "[Dataset] Skipping deleted chunk ${chunk.id} during cleaning"
                            )
                            return@forEachIndexed
                        }
                        
                        val (prev, _, next) = buildContext(chunkTexts, currentIndex)
                        
                        com.example.llamadroid.util.DebugLog.log("[Dataset] Calling LLM for chunk ${chunk.id}...")
                        val cleanedText = cleanChunk(
                            backend, chunk.originalText, prev, next,
                            cleanPrompt, project.maxTokens, project.temperature
                        )
                        com.example.llamadroid.util.DebugLog.log("[Dataset] LLM response received, length=${cleanedText.length}")
                        
                        // Update chunk with cleaned text
                        dao.updateChunk(chunk.copy(
                            cleanedText = cleanedText,
                            status = ChunkStatus.CLEANED
                        ))
                        com.example.llamadroid.util.DebugLog.log("[Dataset] Chunk ${chunk.id} updated to CLEANED")
                    } catch (e: Exception) {
                        com.example.llamadroid.util.DebugLog.log("[Dataset] ERROR cleaning chunk ${chunk.id}: ${e.message}")
                        updateProgress(Progress(context.getString(R.string.dataset_stage_error), index + 1, total, context.getString(R.string.dataset_error_param, e.message), project.id))
                        throw e
                    }
                }
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage COMPLETED for all $total chunks")
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage CANCELLED")
            } catch (e: Exception) {
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage ERROR: ${e.message}")
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage FINISHED, resetting state")
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    fun runQuestionStage(
        project: DatasetProjectEntity,
        questionPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            
            try {
                val backend = createBackend(project)
                val cleanedChunks = dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                val total = cleanedChunks.size * project.questionsPerChunk
                var current = 0
                
                cleanedChunks.forEach { chunk ->
                    ensureActive()
                    
                    val cleanedText = chunk.cleanedText ?: return@forEach
                    
                    // Get context
                    val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                    val sortedChunks = allChunks.sortedBy { it.chunkIndex }
                    val chunkTexts = sortedChunks.map { it.cleanedText ?: it.originalText }
                    val currentIndex = sortedChunks.indexOfFirst { it.id == chunk.id }
                    if (currentIndex < 0) {
                        com.example.llamadroid.util.DebugLog.log(
                            "[Dataset] Skipping deleted chunk ${chunk.id} during question generation"
                        )
                        return@forEach
                    }
                    val (prev, _, next) = buildContext(chunkTexts, currentIndex)
                    
                    // Generate N questions per chunk
                    val generatedQuestions = mutableSetOf<String>()
                    repeat(project.questionsPerChunk) { i ->
                        ensureActive()
                        current++
                        updateProgress(Progress(context.getString(R.string.dataset_stage_questions), current, total, context.getString(R.string.dataset_chunk_q_param, chunk.chunkIndex, i+1), project.id))
                        
                        val question = generateQuestion(
                            backend, cleanedText, prev, next,
                            questionPrompt, project.finalLanguage, project.maxTokens, project.temperature
                        )
                        
                        // Only add unique questions
                        if (question.isNotBlank() && question !in generatedQuestions) {
                            generatedQuestions.add(question)
                            dao.insertQA(DatasetQAEntity(
                                projectId = project.id,
                                chunkId = chunk.id,
                                question = question,
                                status = QAStatus.QUESTIONED
                            ))
                        }
                    }
                }
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    fun runAnswerStage(
        project: DatasetProjectEntity,
        answerPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            
            try {
                val backend = createBackend(project)
                val unanswered = dao.getQAByStatus(project.id, QAStatus.QUESTIONED)
                val total = unanswered.size
                
                unanswered.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_stage_answers), index + 1, total, qa.question.take(30) + "...", project.id))
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val answer = generateAnswer(
                        backend, qa.question, context,
                        answerPrompt, project.finalLanguage, project.useCoT, project.maxTokens, project.temperature
                    )
                    
                    dao.updateQA(qa.copy(
                        answer = answer,
                        status = QAStatus.ANSWERED
                    ))
                }
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    fun runRatingStage(
        project: DatasetProjectEntity,
        reviewPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            
            try {
                val backend = createBackend(project)
                val answered = dao.getQAByStatus(project.id, QAStatus.ANSWERED)
                val total = answered.size
                
                answered.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_stage_rating), index + 1, total, qa.question.take(30) + "...", project.id))
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val (score, justification) = rateQA(
                        backend, qa.question, qa.answer ?: "", context,
                        reviewPrompt, project.finalLanguage
                    )
                    
                    dao.updateQA(qa.copy(
                        score = score,
                        scoreJustification = justification,
                        status = QAStatus.REVIEWED
                    ))
                }
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    // ========== Regeneration Stages (for batch regen with progress) ==========
    
    fun runRegenAnswers(
        project: DatasetProjectEntity,
        qaIds: Set<Long>,
        answerPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting regenerate answers for ${qaIds.size} items")
            
            try {
                val backend = createBackend(project)
                val qaList = qaIds.mapNotNull { id -> dao.getQA(id) }
                val total = qaList.size
                
                qaList.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_job_regen_answers_param, total), index + 1, total, qa.question.take(30) + "...", project.id))
                    com.example.llamadroid.util.DebugLog.log("[Regen] Regenerating answer ${index + 1}/$total")
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val newAnswer = generateAnswer(
                        backend, qa.question, context, answerPrompt,
                        project.finalLanguage, project.useCoT, project.maxTokens, project.temperature
                    )
                    
                    dao.updateQA(qa.copy(
                        answer = newAnswer,
                        score = null,
                        scoreJustification = null,
                        status = QAStatus.ANSWERED
                    ))
                }
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed regenerate answers")
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    fun runRegenRatings(
        project: DatasetProjectEntity,
        qaIds: Set<Long>,
        reviewPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting regenerate ratings for ${qaIds.size} items")
            
            try {
                val backend = createBackend(project)
                val qaList = qaIds.mapNotNull { id -> dao.getQA(id) }.filter { it.answer != null }
                val total = qaList.size
                
                qaList.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_job_regen_ratings_param, total), index + 1, total, qa.question.take(30) + "...", project.id))
                    com.example.llamadroid.util.DebugLog.log("[Regen] Regenerating rating ${index + 1}/$total")
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val (score, justification) = rateQA(
                        backend, qa.question, qa.answer ?: "", context,
                        reviewPrompt, project.finalLanguage
                    )
                    
                    dao.updateQA(qa.copy(
                        score = score,
                        scoreJustification = justification,
                        status = QAStatus.REVIEWED
                    ))
                }
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed regenerate ratings")
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    fun runRegenQuestions(
        project: DatasetProjectEntity,
        chunkIds: Set<Long>,
        questionPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting regenerate questions for ${chunkIds.size} chunks")
            
            try {
                val backend = createBackend(project)
                val chunks = chunkIds.mapNotNull { id -> dao.getChunk(id) }
                val total = chunks.size
                
                chunks.forEachIndexed { index, chunk ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_job_regen_questions_param, total), index + 1, total, chunk.originalText.take(30) + "...", project.id))
                    com.example.llamadroid.util.DebugLog.log("[Regen] Regenerating questions for chunk ${index + 1}/$total")
                    
                    // Delete existing QAs for this chunk
                    dao.deleteQAForChunk(chunk.id)
                    
                    // Get context from surrounding chunks
                    val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                    val chunkIdx = allChunks.indexOfFirst { c -> c.id == chunk.id }
                    if (chunkIdx < 0) {
                        com.example.llamadroid.util.DebugLog.log(
                            "[Regen] Skipping deleted chunk ${chunk.id} during question regeneration"
                        )
                        return@forEachIndexed
                    }
                    val prevContext = if (chunkIdx > 0) allChunks[chunkIdx - 1].originalText.takeLast(500) else ""
                    val nextContext = if (chunkIdx < allChunks.size - 1) allChunks[chunkIdx + 1].originalText.take(500) else ""
                    val text = chunk.cleanedText ?: chunk.originalText
                    
                    // Generate new questions
                    repeat(project.questionsPerChunk) {
                        ensureActive()
                        val question = generateQuestion(
                            backend, text, prevContext, nextContext,
                            questionPrompt, project.finalLanguage, project.maxTokens, project.temperature
                        )
                        if (question.isNotBlank()) {
                            dao.insertQA(DatasetQAEntity(
                                projectId = project.id,
                                chunkId = chunk.id,
                                question = question.trim(),
                                status = QAStatus.QUESTIONED
                            ))
                        }
                    }
                }
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed regenerate questions")
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(project.id, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    // ========== Single Item Regeneration ==========
    
    fun runRegenSingleAnswer(projectId: Long, qaId: Long, answerPrompt: String) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting single answer regen for QA $qaId")
            
            try {
                val project = dao.getProject(projectId) ?: run {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single answer regen: project $projectId no longer exists"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                val qa = dao.getQA(qaId) ?: run {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single answer regen: QA $qaId no longer exists"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                val backend = createBackend(project)
                
                updateProgress(Progress(context.getString(R.string.dataset_job_regen_answer), 1, 1, qa.question.take(30) + "...", projectId))
                
                val chunk = dao.getChunk(qa.chunkId) ?: run {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single answer regen: chunk ${qa.chunkId} no longer exists"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                val context = chunk.cleanedText ?: chunk.originalText
                
                val newAnswer = generateAnswer(
                    backend, qa.question, context, answerPrompt,
                    project.finalLanguage, project.useCoT, project.maxTokens, project.temperature
                )
                
                dao.updateQA(qa.copy(
                    answer = newAnswer,
                    score = null,
                    scoreJustification = null,
                    status = QAStatus.ANSWERED
                ))
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed single answer regen")
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(projectId, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    fun runRegenSingleRating(projectId: Long, qaId: Long, reviewPrompt: String) {
        processingJob = processingScope.launch {
            setProcessing(true)
            var outcome = JobOutcome.CANCELLED
            var errorMessage: String? = null
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting single rating regen for QA $qaId")
            
            try {
                val project = dao.getProject(projectId) ?: run {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single rating regen: project $projectId no longer exists"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                val qa = dao.getQA(qaId) ?: run {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single rating regen: QA $qaId no longer exists"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                if (qa.answer == null) {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single rating regen: QA $qaId has no answer"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                val backend = createBackend(project)
                
                updateProgress(Progress(context.getString(R.string.dataset_job_regen_rating), 1, 1, qa.question.take(30) + "...", projectId))
                
                val chunk = dao.getChunk(qa.chunkId) ?: run {
                    com.example.llamadroid.util.DebugLog.log(
                        "[Regen] Skipping single rating regen: chunk ${qa.chunkId} no longer exists"
                    )
                    outcome = JobOutcome.SUCCESS
                    return@launch
                }
                val context = chunk.cleanedText ?: chunk.originalText
                
                val (score, justification) = rateQA(
                    backend, qa.question, qa.answer, context,
                    reviewPrompt, project.finalLanguage
                )
                
                dao.updateQA(qa.copy(
                    score = score,
                    scoreJustification = justification,
                    status = QAStatus.REVIEWED
                ))
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed single rating regen")
                outcome = JobOutcome.SUCCESS
            } catch (cancelled: CancellationException) {
                errorMessage = cancelled.message
            } catch (e: Exception) {
                outcome = JobOutcome.FAILED
                errorMessage = e.message
                showProcessingError(projectId, e)
            } finally {
                setProcessing(false)
                updateProgress(null)
                finishCurrentJob(
                    outcome = outcome,
                    errorMessage = errorMessage,
                    advanceQueue = outcome == JobOutcome.SUCCESS
                )
            }
        }
    }
    
    // ========== Job Dispatcher ==========
    
    fun executeJob(job: Job) {
        processingScope.launch {
            val project = dao.getProject(job.projectId) ?: run {
                com.example.llamadroid.util.DebugLog.log(
                    "[Dataset] Skipping job ${job.name}: project ${job.projectId} no longer exists"
                )
                finishCurrentJob(
                    outcome = JobOutcome.SUCCESS,
                    errorMessage = null,
                    advanceQueue = true
                )
                return@launch
            }
            
            when (job) {
                is Job.RegenAnswer -> runRegenSingleAnswer(job.projectId, job.qaId, job.answerPrompt)
                is Job.RegenRating -> runRegenSingleRating(job.projectId, job.qaId, job.reviewPrompt)
                is Job.RegenAnswers -> runRegenAnswers(project, job.qaIds, job.answerPrompt)
                is Job.RegenRatings -> runRegenRatings(project, job.qaIds, job.reviewPrompt)
                is Job.RegenQuestions -> runRegenQuestions(project, job.chunkIds, job.questionPrompt)
                is Job.RegenClean -> runRegenSingleClean(project, job.chunkId, job.cleanPrompt)
                is Job.Clean -> runCleaningStage(project, job.cleanPrompt)
                is Job.Questions -> runQuestionStage(project, job.questionPrompt)
                is Job.Answers -> runAnswerStage(project, job.answerPrompt)
                is Job.Rating -> runRatingStage(project, job.reviewPrompt)
                is Job.ImportPdf -> runImportPdf(project, job.sourceUri, job.sourceName)
                is Job.ImportTxt -> runImportTxt(project, job.sourceUri, job.sourceName)
            }
        }
    }
    
    private suspend fun runRegenSingleClean(project: DatasetProjectEntity, chunkId: Long, cleanPrompt: String) {
        var outcome = JobOutcome.CANCELLED
        var errorMessage: String? = null
        try {
            _isProcessing.value = true
            val chunk = dao.getChunk(chunkId) ?: run {
                com.example.llamadroid.util.DebugLog.log(
                    "[Dataset] Skipping single clean regen: chunk $chunkId no longer exists"
                )
                outcome = JobOutcome.SUCCESS
                return
            }
            _progress.value = Progress(context.getString(R.string.dataset_job_regen_clean), 1, 1, chunk.originalText.take(50) + "...", project.id)
            
            val backend = createBackend(project)
            
            // Get all chunks for context
            val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.PENDING) + 
                dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
            val sortedChunks = allChunks.sortedBy { it.chunkIndex }
            val chunkTexts = sortedChunks.map { it.cleanedText ?: it.originalText }
            val currentIndex = sortedChunks.indexOfFirst { it.id == chunk.id }
            
            val (prev, _, next) = buildContext(chunkTexts, if (currentIndex >= 0) currentIndex else 0)
            
            // Use default clean prompt if not provided
            val prompt = if (cleanPrompt.isBlank()) """Clean this text by removing formatting artifacts, OCR errors, and noise.

Previous context: {prev_chunk_50%}
Text to clean: {chunk}
Next context: {next_chunk_50%}

Cleaned text:""" else cleanPrompt
            
            val cleanedText = cleanChunk(backend, chunk.originalText, prev, next, prompt, project.maxTokens, project.temperature)
            
            if (cleanedText.isNotBlank()) {
                dao.updateChunk(chunk.copy(cleanedText = cleanedText.trim(), status = ChunkStatus.CLEANED))
            }
            outcome = JobOutcome.SUCCESS
        } catch (cancelled: CancellationException) {
            errorMessage = cancelled.message
        } catch (e: Exception) {
            com.example.llamadroid.util.DebugLog.log("[Dataset] ERROR in runRegenSingleClean: ${e.message}")
            outcome = JobOutcome.FAILED
            errorMessage = e.message
            showProcessingError(project.id, e)
        } finally {
            _isProcessing.value = false
            _progress.value = null
            finishCurrentJob(
                outcome = outcome,
                errorMessage = errorMessage,
                advanceQueue = outcome == JobOutcome.SUCCESS
            )
        }
    }
}
