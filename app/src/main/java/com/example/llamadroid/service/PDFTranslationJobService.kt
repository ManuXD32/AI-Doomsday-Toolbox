package com.example.llamadroid.service

import android.content.Context
import android.net.Uri
import com.example.llamadroid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PdfTranslationJobState(
    val isRunning: Boolean = false,
    val kind: PdfTranslationJobKind? = null,
    val cancelled: Boolean = false,
    val progressMessage: String = "",
    val progressFraction: Float = 0f,
    val successMessage: String? = null,
    val errorMessage: String? = null,
    val errorDetails: String? = null,
    val mangaResults: List<MangaTranslationFileResult> = emptyList()
)

enum class PdfTranslationJobKind {
    OCR_PDF,
    TEXT_LAYER_PDF,
    MANGA_BATCH
}

object PDFTranslationJobService {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private var currentController: PDFTranslationExecutionController? = null

    private val _state = MutableStateFlow(PdfTranslationJobState())
    val state: StateFlow<PdfTranslationJobState> = _state.asStateFlow()

    fun startOcrPdfTranslation(context: Context, pdfUri: Uri): Boolean {
        return startTranslation(
            context = context,
            kind = PdfTranslationJobKind.OCR_PDF,
            successMessage = context.getString(R.string.pdf_ocr_translated_pdf_export_success)
        ) { service, appContext, controller ->
            service.exportTranslatedOcrPdf(pdfUri, executionController = controller) { progress ->
                publishProgress(appContext, progress)
            }
        }
    }

    fun startTextLayerPdfTranslation(context: Context, pdfUri: Uri): Boolean {
        return startTranslation(
            context = context,
            kind = PdfTranslationJobKind.TEXT_LAYER_PDF,
            successMessage = context.getString(R.string.pdf_translate_ocr_success)
        ) { service, appContext, controller ->
            service.exportTranslatedTextLayerPdf(pdfUri, executionController = controller) { progress ->
                publishProgress(appContext, progress)
            }
        }
    }

    fun startTextLayerPdfTranslationBatch(context: Context, pdfUris: List<Uri>): Boolean {
        val pendingPdfs = pdfUris.distinct()
        if (pendingPdfs.isEmpty()) return false
        if (pendingPdfs.size == 1) return startTextLayerPdfTranslation(context, pendingPdfs.first())
        if (currentJob?.isActive == true) return false

        val appContext = context.applicationContext
        _state.value = PdfTranslationJobState(
            isRunning = true,
            kind = PdfTranslationJobKind.TEXT_LAYER_PDF,
            progressMessage = appContext.getString(R.string.pdf_translation_background_started)
        )
        RemoteSummaryProtection.acquire(appContext)
        val controller = PDFTranslationExecutionController()
        currentController = controller

        currentJob = serviceScope.launch {
            val service = PDFService(appContext)
            var completed = 0
            var failed = 0
            var wasCancelled = false

            pendingPdfs.forEachIndexed { index, pdfUri ->
                if (controller.isCancelled()) {
                    wasCancelled = true
                    return@forEachIndexed
                }
                val result = try {
                    service.exportTranslatedTextLayerPdf(pdfUri, executionController = controller) { progress ->
                        val fileProgress = progressFraction(progress)
                        _state.update {
                            it.copy(
                                isRunning = true,
                                cancelled = false,
                                progressMessage = appContext.getString(
                                    R.string.pdf_translation_batch_progress,
                                    index + 1,
                                    pendingPdfs.size,
                                    formatTranslationProgress(appContext, progress)
                                ),
                                progressFraction = ((index.toFloat() + fileProgress) / pendingPdfs.size.toFloat()).coerceIn(0f, 1f),
                                successMessage = null,
                                errorMessage = null
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    wasCancelled = true
                    Result.failure(cancelled)
                } catch (error: Exception) {
                    Result.failure(error)
                }

                if (result.isSuccess) {
                    completed++
                } else if (result.exceptionOrNull() is CancellationException || controller.isCancelled()) {
                    wasCancelled = true
                    return@forEachIndexed
                } else {
                    failed++
                }
            }

            _state.value = if (wasCancelled || controller.isCancelled()) {
                PdfTranslationJobState(
                    isRunning = false,
                    kind = PdfTranslationJobKind.TEXT_LAYER_PDF,
                    cancelled = true,
                    progressFraction = if (pendingPdfs.isNotEmpty()) {
                        completed.toFloat() / pendingPdfs.size.toFloat()
                    } else {
                        0f
                    }
                )
            } else if (completed > 0) {
                val message = if (failed == 0) {
                    appContext.getString(R.string.pdf_translation_batch_complete, completed)
                } else {
                    appContext.getString(R.string.pdf_translation_batch_partial, completed, failed)
                }
                PdfTranslationJobState(
                    isRunning = false,
                    kind = PdfTranslationJobKind.TEXT_LAYER_PDF,
                    progressFraction = 1f,
                    successMessage = message
                )
            } else {
                PdfTranslationJobState(
                    isRunning = false,
                    kind = PdfTranslationJobKind.TEXT_LAYER_PDF,
                    errorMessage = appContext.getString(R.string.pdf_translation_batch_failed, failed)
                )
            }
            RemoteSummaryProtection.release()
            currentJob = null
            currentController = null
        }

        return true
    }

    fun startMangaCbzBatchTranslation(
        context: Context,
        cbzUris: List<Uri>,
        exportPdf: Boolean = true,
        exportCbz: Boolean = true
    ): Boolean {
        if (currentJob?.isActive == true) return false

        val appContext = context.applicationContext
        _state.value = PdfTranslationJobState(
            isRunning = true,
            kind = PdfTranslationJobKind.MANGA_BATCH,
            progressMessage = appContext.getString(R.string.pdf_translation_background_started)
        )
        RemoteSummaryProtection.acquire(appContext)
        val controller = PDFTranslationExecutionController()
        currentController = controller

        currentJob = serviceScope.launch {
            val result = try {
                PDFService(appContext).translateMangaCbzBatch(
                    cbzUris = cbzUris,
                    exportPdf = exportPdf,
                    exportCbz = exportCbz,
                    executionController = controller
                ) { progress ->
                        publishProgress(appContext, progress)
                    }
            } catch (cancelled: CancellationException) {
                Result.failure(cancelled)
            } catch (error: Exception) {
                Result.failure(error)
            }

            result.fold(
                onSuccess = { results ->
                    val failed = results.count { !it.isSuccess }
                    val message = if (failed == 0) {
                        appContext.getString(R.string.workflow_manga_notification_complete, results.size)
                    } else {
                        appContext.getString(R.string.workflow_manga_notification_partial, results.size - failed, failed)
                    }
                    _state.value = PdfTranslationJobState(
                        isRunning = false,
                        kind = PdfTranslationJobKind.MANGA_BATCH,
                        successMessage = message,
                        mangaResults = results
                    )
                },
                onFailure = { error ->
                    _state.value = if (error is PDFTranslationCancelledException || error is CancellationException || controller.isCancelled()) {
                        PdfTranslationJobState(
                            isRunning = false,
                            kind = PdfTranslationJobKind.MANGA_BATCH,
                            cancelled = true,
                            mangaResults = (error as? PDFTranslationCancelledException)?.mangaResults.orEmpty()
                        )
                    } else {
                        val display = displayError(error, appContext)
                        PdfTranslationJobState(
                            isRunning = false,
                            kind = PdfTranslationJobKind.MANGA_BATCH,
                            errorMessage = display.first,
                            errorDetails = display.second
                        )
                    }
                }
            )
            RemoteSummaryProtection.release()
            currentJob = null
            currentController = null
        }

        return true
    }

    fun clearTerminalMessages() {
        _state.update { it.copy(successMessage = null, errorMessage = null, errorDetails = null, cancelled = false) }
    }

    fun cancel() {
        currentController?.cancel()
        currentJob?.cancel(CancellationException("PDF translation cancelled"))
    }

    private fun startTranslation(
        context: Context,
        kind: PdfTranslationJobKind,
        successMessage: String,
        work: suspend (PDFService, Context, PDFTranslationExecutionController) -> Result<Uri>
    ): Boolean {
        if (currentJob?.isActive == true) return false

        val appContext = context.applicationContext
        _state.value = PdfTranslationJobState(
            isRunning = true,
            kind = kind,
            progressMessage = appContext.getString(R.string.pdf_translation_background_started)
        )
        RemoteSummaryProtection.acquire(appContext)
        val controller = PDFTranslationExecutionController()
        currentController = controller

        currentJob = serviceScope.launch {
            val result = try {
                work(PDFService(appContext), appContext, controller)
            } catch (cancelled: CancellationException) {
                Result.failure(cancelled)
            } catch (error: Exception) {
                Result.failure(error)
            }

            result.fold(
                onSuccess = {
                    _state.value = PdfTranslationJobState(
                        isRunning = false,
                        kind = kind,
                        successMessage = successMessage
                    )
                },
                onFailure = { error ->
                    _state.value = if (error is CancellationException || controller.isCancelled()) {
                        PdfTranslationJobState(
                            isRunning = false,
                            kind = kind,
                            cancelled = true
                        )
                    } else {
                        val display = displayError(error, appContext)
                        PdfTranslationJobState(
                            isRunning = false,
                            kind = kind,
                            errorMessage = display.first,
                            errorDetails = display.second
                        )
                    }
                }
            )
            RemoteSummaryProtection.release()
            currentJob = null
            currentController = null
        }

        return true
    }

    private fun publishProgress(context: Context, progress: PdfOcrTranslationProgress) {
        _state.update {
            it.copy(
                isRunning = true,
                cancelled = false,
                progressMessage = formatTranslationProgress(context, progress),
                progressFraction = progressFraction(progress),
                successMessage = null,
                errorMessage = null,
                errorDetails = null
            )
        }
    }

    private fun displayError(error: Throwable, context: Context): Pair<String, String?> {
        if (error is PDFTranslationDisplayException) {
            return error.displayMessage to error.displayDetails
        }
        val message = error.message ?: context.getString(R.string.error_generic)
        return message to null
    }

    private fun progressFraction(progress: PdfOcrTranslationProgress): Float {
        return when {
            progress.totalPages > 0 -> progress.processedPages.toFloat() / progress.totalPages.toFloat()
            progress.totalBlocks > 0 -> progress.translatedBlocks.toFloat() / progress.totalBlocks.toFloat()
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    private fun formatTranslationProgress(context: Context, progress: PdfOcrTranslationProgress): String {
        return when (progress.stage) {
            PdfOcrTranslationStage.READING_TEXT -> context.getString(
                R.string.pdf_translate_ocr_progress_read_text,
                progress.processedPages,
                progress.totalPages
            )
            PdfOcrTranslationStage.OCR -> context.getString(
                R.string.pdf_ocr_translate_progress_ocr,
                progress.processedPages,
                progress.totalPages
            )
            PdfOcrTranslationStage.TRANSLATING -> context.getString(
                R.string.pdf_ocr_translate_progress_translate,
                progress.translatedBlocks,
                progress.totalBlocks
            )
            PdfOcrTranslationStage.CORRECTING -> context.getString(
                R.string.pdf_ocr_translate_progress_correct,
                progress.processedPages,
                progress.totalPages
            )
            PdfOcrTranslationStage.WRITING -> context.getString(
                R.string.pdf_ocr_translate_progress_write,
                progress.processedPages,
                progress.totalPages
            )
            PdfOcrTranslationStage.EXTRACTING -> context.getString(R.string.workflow_manga_stage_extracting)
            PdfOcrTranslationStage.PDF_CREATION -> context.getString(R.string.workflow_manga_stage_pdf)
            PdfOcrTranslationStage.RENDERING -> context.getString(R.string.workflow_manga_stage_rendering)
            PdfOcrTranslationStage.PACKING -> context.getString(R.string.workflow_manga_stage_packing)
        }
    }
}
