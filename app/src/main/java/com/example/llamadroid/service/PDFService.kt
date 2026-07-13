package com.example.llamadroid.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.PdfTranslationQualityMode
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.util.DebugLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import com.tom_roush.pdfbox.text.TextPosition

data class PdfExtractionResult(
    val text: String,
    val totalPages: Int,
    val textLayerPages: Int,
    val ocrPages: Int,
    val emptyPages: Int
)

data class PdfExtractionProgress(
    val processedPages: Int,
    val totalPages: Int,
    val textLayerPages: Int,
    val ocrPages: Int,
    val emptyPages: Int,
    val textCharacters: Int
)

data class PdfOcrTranslationProgress(
    val stage: PdfOcrTranslationStage,
    val processedPages: Int,
    val totalPages: Int,
    val translatedBlocks: Int,
    val totalBlocks: Int
)

enum class PdfOcrTranslationStage {
    READING_TEXT,
    OCR,
    TRANSLATING,
    CORRECTING,
    WRITING,
    EXTRACTING,
    PDF_CREATION,
    RENDERING,
    PACKING
}

private data class PdfOcrDocumentResult(
    val pages: List<PdfOcrPageResult>,
    val text: String,
    val totalPages: Int,
    val ocrPages: Int,
    val emptyPages: Int
) {
    val pageTexts: List<String> = pages.map { page ->
        page.blocks.joinToString("\n\n") { it.text }.trim()
    }

    val blocks: List<PdfOcrBlock> = pages.flatMap { it.blocks }
}

private data class PdfOcrPageResult(
    val pageIndex: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val pdfWidth: Float,
    val pdfHeight: Float,
    val blocks: List<PdfOcrBlock>
)

private data class PdfOcrBlock(
    val pageIndex: Int,
    val blockIndex: Int,
    val text: String,
    val box: PdfOcrBox,
    val lineBoxes: List<PdfOcrLine>,
    val backgroundColor: Int,
    val textColor: Int
)

private data class PdfOcrLine(
    val text: String,
    val box: PdfOcrBox
)

private data class PdfTextLayerDocumentResult(
    val pages: List<PdfTextLayerPageResult>,
    val totalPages: Int
) {
    val blocks: List<PdfTextLayerBlock> = pages.flatMap { it.blocks }
}

private data class PdfTextLayerPageResult(
    val pageIndex: Int,
    val pdfWidth: Float,
    val pdfHeight: Float,
    val blocks: List<PdfTextLayerBlock>
)

private data class PdfTextLayerBlock(
    val pageIndex: Int,
    val blockIndex: Int,
    val text: String,
    val rect: PdfMappedRect,
    val backgroundColor: Int,
    val textColor: Int
)

private data class PdfTextLayerLine(
    val text: String,
    val rect: PdfMappedRect
)

private data class PageTranslationChunk(
    val units: List<PdfTranslationUnit>,
    val index: Int,
    val totalChunks: Int,
    val totalPageUnits: Int
)

private data class RawTextLayerPosition(
    val text: String,
    val x: Float,
    val yFromTop: Float,
    val width: Float,
    val height: Float
)

private data class PdfTranslationSourceBlock(
    val sourceId: String,
    val pageIndex: Int,
    val sourceIndex: Int,
    val text: String,
    val rect: PdfMappedRect,
    val backgroundColor: Int,
    val textColor: Int,
    val lineCount: Int,
    val sourceLines: List<String>
)

private data class PdfTranslationUnit(
    val id: String,
    val pageIndex: Int,
    val unitIndex: Int,
    val text: String,
    val rect: PdfMappedRect,
    val backgroundColor: Int,
    val textColor: Int,
    val sourceBlockIds: List<String>,
    val sourceLineCount: Int,
    val sourceLines: List<String>
) {
    val sourceBlockCount: Int get() = sourceBlockIds.size
}

private data class PreparedOcrTranslation(
    val pageUnits: Map<Int, List<PdfTranslationUnit>>,
    val translations: LinkedHashMap<String, String>,
    val totalSourceBlocks: Int
)

private data class PdfTranslationDisplayError(
    val message: String,
    val details: String? = null
)

private data class PdfFontSelection(
    val font: PDFont,
    val sourceLabel: String,
    val usesBundledFallback: Boolean
)

private data class BitmapTypefaceSelection(
    val typeface: Typeface,
    val sourceLabel: String,
    val usesBundledFallback: Boolean
)

data class MangaTranslationFileResult(
    val sourceName: String,
    val pdfUri: Uri? = null,
    val cbzUri: Uri? = null,
    val errorMessage: String? = null,
    val errorDetails: String? = null
) {
    val isSuccess: Boolean get() = (pdfUri != null || cbzUri != null) && errorMessage == null
}

internal class PDFTranslationCancelledException(
    message: String = "PDF translation cancelled",
    val mangaResults: List<MangaTranslationFileResult> = emptyList()
) : CancellationException(message)

internal class PDFTranslationDisplayException(
    val displayMessage: String,
    val displayDetails: String? = null,
    cause: Throwable? = null
) : Exception(displayMessage, cause)

private const val BUNDLED_JAPANESE_FONT_ASSET = "fonts/DroidSansJapanese.ttf"
private const val BUNDLED_JAPANESE_FONT_LABEL = "bundled:DroidSansJapanese.ttf"

/**
 * Service for PDF operations: merge, split, extract text
 */
class PDFService(private val context: Context) {
    
    private val settingsRepo = SettingsRepository(context)
    private val textDrawingOperatorsToReplace = setOf("Tj", "TJ", "'", "\"")
    
    init {
        // Initialize PDFBox
        PDFBoxResourceLoader.init(context)
    }
    
    /**
     * Merge multiple PDFs into one
     */
    suspend fun mergePdfs(pdfUris: List<Uri>): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Merging ${pdfUris.size} PDFs")
            
            val mergedDoc = PDDocument()
            try {
                pdfUris.forEach { uri ->
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val doc = PDDocument.load(inputStream)
                        try {
                            for (i in 0 until doc.numberOfPages) {
                                mergedDoc.importPage(doc.getPage(i))
                            }
                        } finally {
                            doc.close()
                        }
                    } ?: throw IllegalStateException("Could not open PDF")
                }

                // Save to output folder
                val outputUri = saveToOutputFolder(mergedDoc, "merged_${System.currentTimeMillis()}.pdf")

                DebugLog.log("[PDF] Merge complete: $outputUri")
                Result.success(outputUri)
            } finally {
                mergedDoc.close()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] Merge failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun resolveDocumentSize(pdfUri: Uri): Long {
        val assetLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(pdfUri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        if (assetLength != null) return assetLength

        val documentLength = DocumentFile.fromSingleUri(context, pdfUri)?.length()
        if (documentLength != null && documentLength >= 0L) {
            return documentLength
        }

        return 0L
    }

    private fun measureDocumentSize(doc: PDDocument): Long {
        val tempFile = File.createTempFile("pdf_measure_", ".pdf", context.cacheDir)
        return try {
            doc.save(tempFile)
            tempFile.length()
        } finally {
            tempFile.delete()
        }
    }

    private fun importPageRange(sourceDoc: PDDocument, targetDoc: PDDocument, startPage: Int, endPageInclusive: Int) {
        for (pageIndex in startPage..endPageInclusive) {
            targetDoc.importPage(sourceDoc.getPage(pageIndex))
        }
    }

    /**
     * Split PDF by extracting specific pages
     * @param pageRange Format: "1-3, 5, 7-10" 
     */
    suspend fun splitPdf(pdfUri: Uri, pageRange: String): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Splitting PDF: $pageRange")
            
            val pages = parsePageRange(pageRange)
            if (pages.isEmpty()) {
                return@withContext Result.failure(Exception("Invalid page range"))
            }
            
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val sourceDoc = PDDocument.load(inputStream)
                val newDoc = PDDocument()
                try {
                    val maxPage = sourceDoc.numberOfPages
                    pages.filter { it in 1..maxPage }.forEach { pageNum ->
                        val page = sourceDoc.getPage(pageNum - 1) // 0-indexed
                        newDoc.importPage(page)
                    }

                    if (newDoc.numberOfPages == 0) {
                        return@withContext Result.failure(Exception("No valid pages in range"))
                    }

                    val outputUri = saveToOutputFolder(newDoc, "split_${System.currentTimeMillis()}.pdf")

                    DebugLog.log("[PDF] Split complete: $outputUri")
                    return@withContext Result.success(outputUri)
                } finally {
                    newDoc.close()
                    sourceDoc.close()
                }
            }
            
            Result.failure(Exception("Could not open PDF"))
        } catch (e: Exception) {
            DebugLog.log("[PDF] Split failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Extract all text from PDF
     */
    suspend fun extractText(pdfUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        extractTextDetailed(pdfUri).map { it.text }
    }

    suspend fun extractTextDetailed(
        pdfUri: Uri,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfExtractionResult> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Extracting text from PDF with text-layer + OCR fallback")

            val cachedPdf = copyPdfToCache(pdfUri)
            try {
                val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val recognizers = createOcrRecognizers()
                val doc = runCatching { PDDocument.load(cachedPdf) }.getOrNull()

                try {
                    if (doc?.isEncrypted == true) {
                        runCatching { doc.setAllSecurityToBeRemoved(true) }
                    }

                    val totalPages = renderer.pageCount
                    if (totalPages == 0) {
                        return@withContext Result.failure(Exception("PDF has no pages"))
                    }
                    onProgress?.invoke(
                        PdfExtractionProgress(
                            processedPages = 0,
                            totalPages = totalPages,
                            textLayerPages = 0,
                            ocrPages = 0,
                            emptyPages = 0,
                            textCharacters = 0
                        )
                    )

                    val pageTexts = mutableListOf<String>()
                    var textLayerPages = 0
                    var ocrPages = 0
                    var emptyPages = 0
                    var textCharacters = 0

                    for (pageIndex in 0 until totalPages) {
                        currentCoroutineContext().ensureActive()
                        val pageNumber = pageIndex + 1
                        val textLayerText = if (doc != null && pageNumber <= doc.numberOfPages) {
                            runCatching { extractTextFromPage(doc, pageNumber) }
                                .onFailure { DebugLog.log("[PDF] Page $pageNumber text-layer extract failed: ${it.message}") }
                                .getOrDefault("")
                        } else {
                            ""
                        }

                        val normalizedTextLayer = normalizeExtractedText(textLayerText)
                        val finalText = if (shouldUseOcrFallback(normalizedTextLayer)) {
                            val ocrText = runCatching { extractTextWithOcr(renderer, recognizers, pageIndex) }
                                .onFailure { DebugLog.log("[PDF] Page $pageNumber OCR failed: ${it.message}") }
                                .getOrDefault("")
                            val normalizedOcr = normalizeExtractedText(ocrText)
                            when {
                                normalizedOcr.isNotBlank() -> {
                                    ocrPages += 1
                                    normalizedOcr
                                }
                                normalizedTextLayer.isNotBlank() -> {
                                    textLayerPages += 1
                                    normalizedTextLayer
                                }
                                else -> {
                                    emptyPages += 1
                                    ""
                                }
                            }
                        } else {
                            textLayerPages += 1
                            normalizedTextLayer
                        }

                        if (finalText.isNotBlank()) {
                            pageTexts.add(finalText)
                            textCharacters += finalText.length
                        }
                        onProgress?.invoke(
                            PdfExtractionProgress(
                                processedPages = pageNumber,
                                totalPages = totalPages,
                                textLayerPages = textLayerPages,
                                ocrPages = ocrPages,
                                emptyPages = emptyPages,
                                textCharacters = textCharacters
                            )
                        )
                    }

                    val extractedText = pageTexts.joinToString("\n\n").trim()
                    if (extractedText.isBlank()) {
                        return@withContext Result.failure(Exception("No extractable text found"))
                    }

                    DebugLog.log(
                        "[PDF] Extracted ${extractedText.length} characters across $totalPages pages " +
                            "(text=$textLayerPages, ocr=$ocrPages, empty=$emptyPages)"
                    )
                    return@withContext Result.success(
                        PdfExtractionResult(
                            text = extractedText,
                            totalPages = totalPages,
                            textLayerPages = textLayerPages,
                            ocrPages = ocrPages,
                            emptyPages = emptyPages
                        )
                    )
                } finally {
                    runCatching { doc?.close() }
                    recognizers.forEach { recognizer -> runCatching { recognizer.close() } }
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                }
            } finally {
                cachedPdf.delete()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] Extract failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun performOcrOnPdf(
        pdfUri: Uri,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfExtractionResult> = withContext(Dispatchers.IO) {
        collectPdfOcrText(pdfUri, onProgress).map { result ->
            PdfExtractionResult(
                text = result.text,
                totalPages = result.totalPages,
                textLayerPages = 0,
                ocrPages = result.ocrPages,
                emptyPages = result.emptyPages
            )
        }
    }

    suspend fun exportSearchableOcrPdf(
        pdfUri: Uri,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Exporting OCR-searchable PDF")
            val ocrResult = collectPdfOcrText(pdfUri, onProgress).getOrThrow()
            val cachedPdf = copyPdfToCache(pdfUri)
            try {
                val doc = PDDocument.load(cachedPdf)
                try {
                    if (doc.isEncrypted) {
                        runCatching { doc.setAllSecurityToBeRemoved(true) }
                    }
                    ocrResult.pages.forEach { pageResult ->
                        currentCoroutineContext().ensureActive()
                        if (pageResult.blocks.isEmpty() || pageResult.pageIndex >= doc.numberOfPages) return@forEach
                        appendInvisibleSearchText(doc, doc.getPage(pageResult.pageIndex), pageResult)
                    }
                    val outputUri = saveToOutputFolder(doc, "ocr_searchable_${System.currentTimeMillis()}.pdf")
                    DebugLog.log("[PDF] OCR-searchable PDF saved: $outputUri")
                    Result.success(outputUri)
                } finally {
                    doc.close()
                }
            } finally {
                cachedPdf.delete()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] OCR-searchable PDF export failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun exportTranslatedOcrPdf(
        pdfUri: Uri,
        outputFileName: String = "translated_ocr_${System.currentTimeMillis()}.pdf",
        settingsOverride: RemoteSummarySettingsSnapshot? = null,
        executionController: PDFTranslationExecutionController? = null,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val notificationId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.PDF_TRANSLATION,
            context.getString(R.string.pdf_translation_notification_title)
        )
        try {
            DebugLog.log("[PDF] Exporting OCR-translated PDF")
            val tempFile = File(context.cacheDir, outputFileName)
            try {
                createTranslatedOcrPdfFile(
                    pdfUri = pdfUri,
                    outputFile = tempFile,
                    settingsOverride = settingsOverride,
                    executionController = executionController,
                    onProgress = onProgress,
                    notificationId = notificationId
                )
                val outputUri = saveFileToOutputFolder(tempFile, "pdfs", "application/pdf", outputFileName)
                DebugLog.log("[PDF] OCR-translated PDF saved: $outputUri")
                UnifiedNotificationManager.completeTask(
                    notificationId,
                    context.getString(R.string.pdf_translation_notification_complete)
                )
                Result.success(outputUri)
            } finally {
                tempFile.delete()
            }
        } catch (e: CancellationException) {
            DebugLog.log("[PDF] OCR-translated PDF export cancelled")
            UnifiedNotificationManager.dismissTask(notificationId)
            Result.failure(e)
        } catch (e: Exception) {
            DebugLog.log("[PDF] OCR-translated PDF export failed: ${e.message}")
            UnifiedNotificationManager.failTask(notificationId, classifyTranslationDisplayError(e).message)
            Result.failure(e)
        }
    }

    private suspend fun createTranslatedOcrPdfFile(
        pdfUri: Uri,
        outputFile: File,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): File {
        ensureTranslationActive(executionController)
        val ocrResult = collectPdfOcrText(pdfUri) { progress ->
            onProgress?.invoke(
                PdfOcrTranslationProgress(
                    stage = PdfOcrTranslationStage.OCR,
                    processedPages = progress.processedPages,
                    totalPages = progress.totalPages,
                    translatedBlocks = 0,
                    totalBlocks = 0
                )
            )
        }.getOrThrow()

        val cachedPdf = copyPdfToCache(pdfUri)
        try {
            return createTranslatedOcrPdfFileFromResult(
                sourcePdf = cachedPdf,
                sourcePdfUri = pdfUri,
                ocrResult = ocrResult,
                outputFile = outputFile,
                settingsOverride = settingsOverride,
                executionController = executionController,
                onProgress = onProgress,
                notificationId = notificationId
            )
        } finally {
            cachedPdf.delete()
        }
    }

    private suspend fun createTranslatedOcrPdfFileFromResult(
        sourcePdf: File,
        sourcePdfUri: Uri,
        ocrResult: PdfOcrDocumentResult,
        outputFile: File,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): File {
        val prepared = prepareOcrTranslation(
            sourcePdfUri = sourcePdfUri,
            ocrResult = ocrResult,
            settingsOverride = settingsOverride,
            executionController = executionController,
            onProgress = onProgress,
            notificationId = notificationId
        )
        return writeTranslatedOcrPdfFileFromPrepared(
            sourcePdf = sourcePdf,
            ocrResult = ocrResult,
            outputFile = outputFile,
            prepared = prepared,
            executionController = executionController,
            onProgress = onProgress,
            notificationId = notificationId
        )
    }

    private suspend fun prepareOcrTranslation(
        sourcePdfUri: Uri,
        ocrResult: PdfOcrDocumentResult,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): PreparedOcrTranslation {
        val qualityMode = settingsRepo.pdfTranslationOptionsSnapshot().qualityMode
        val pageUnits = buildOcrTranslationUnits(ocrResult.pages, qualityMode)
        val translatableUnits = pageUnits.values.flatten()
        if (translatableUnits.isEmpty()) {
            throw IllegalStateException("No OCR text found")
        }
        val totalSourceBlocks = translatableUnits.sumOf { it.sourceBlockCount }

        val translations = translatePageUnits(
            pdfUri = sourcePdfUri,
            totalPages = ocrResult.totalPages,
            pageUnits = pageUnits,
            totalSourceBlocks = totalSourceBlocks,
            settingsOverride = settingsOverride,
            executionController = executionController,
            onProgress = onProgress,
            notificationId = notificationId
        )

        return PreparedOcrTranslation(
            pageUnits = pageUnits,
            translations = translations,
            totalSourceBlocks = totalSourceBlocks
        )
    }

    private suspend fun writeTranslatedOcrPdfFileFromPrepared(
        sourcePdf: File,
        ocrResult: PdfOcrDocumentResult,
        outputFile: File,
        prepared: PreparedOcrTranslation,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): File {
        val doc = PDDocument.load(sourcePdf)
        try {
            if (doc.isEncrypted) {
                runCatching { doc.setAllSecurityToBeRemoved(true) }
            }
            val fontSelection = try {
                loadTranslationFont(doc, prepared.translations.values.asSequence())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DebugLog.log("[PDF] Embedded OCR PDF export font unavailable; using raster fallback: ${error.message}")
                return writeTranslatedOcrPdfRasterFallback(
                    sourcePdf = sourcePdf,
                    ocrResult = ocrResult,
                    outputFile = outputFile,
                    prepared = prepared,
                    executionController = executionController,
                    onProgress = onProgress
                )
            }
            val font = fontSelection.font
            ocrResult.pages.forEach { pageResult ->
                currentCoroutineContext().ensureActive()
                if (pageResult.pageIndex >= doc.numberOfPages) return@forEach
                onProgress?.invoke(
                    PdfOcrTranslationProgress(
                        stage = PdfOcrTranslationStage.WRITING,
                        processedPages = pageResult.pageIndex + 1,
                        totalPages = ocrResult.totalPages,
                        translatedBlocks = prepared.totalSourceBlocks,
                        totalBlocks = prepared.totalSourceBlocks
                    )
                )
                UnifiedNotificationManager.updateProgress(
                    notificationId,
                    0.82f + (pageResult.pageIndex + 1).toFloat() / ocrResult.totalPages.toFloat() * 0.16f,
                    context.getString(R.string.pdf_translation_notification_writing, pageResult.pageIndex + 1, ocrResult.totalPages)
                )
                try {
                    appendTranslatedBlocks(
                        doc = doc,
                        page = doc.getPage(pageResult.pageIndex),
                        units = prepared.pageUnits[pageResult.pageIndex].orEmpty(),
                        pdfWidth = pageResult.pdfWidth,
                        pdfHeight = pageResult.pdfHeight,
                        translations = prepared.translations,
                        font = font
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.log(
                        "[PDF] PDF export failed while writing page ${pageResult.pageIndex + 1} " +
                            "with font ${fontSelection.sourceLabel}; using raster fallback: ${error.message}"
                    )
                    return writeTranslatedOcrPdfRasterFallback(
                        sourcePdf = sourcePdf,
                        ocrResult = ocrResult,
                        outputFile = outputFile,
                        prepared = prepared,
                        executionController = executionController,
                        onProgress = onProgress
                    )
                }
            }
            FileOutputStream(outputFile).use { doc.save(it) }
            return outputFile
        } finally {
            doc.close()
        }
    }

    private suspend fun writeTranslatedOcrPdfRasterFallback(
        sourcePdf: File,
        ocrResult: PdfOcrDocumentResult,
        outputFile: File,
        prepared: PreparedOcrTranslation,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): File {
        ensureTranslationActive(executionController)
        val workDir = File(outputFile.parentFile ?: context.cacheDir, "${outputFile.nameWithoutExtension}_raster_pages")
            .apply { mkdirs() }
        DebugLog.log("[PDF] Rasterizing translated OCR PDF fallback to avoid embedded font limitations")
        val renderedPages = renderTranslatedPdfPagesToPng(
            sourcePdf = sourcePdf,
            ocrResult = ocrResult,
            prepared = prepared,
            workDir = workDir,
            baseName = outputFile.nameWithoutExtension,
            executionController = executionController,
            onProgress = onProgress
        )
        if (renderedPages.isEmpty()) {
            throw PDFTranslationDisplayException(
                displayMessage = context.getString(R.string.pdf_translation_error_export_failed),
                displayDetails = context.getString(R.string.workflow_manga_error_no_rendered_pages)
            )
        }
        createPdfFromImageFiles(renderedPages, outputFile)
        return outputFile
    }

    private suspend fun exportTranslatedTextLayerPdfRasterFallback(
        sourcePdf: File,
        textLayerResult: PdfTextLayerDocumentResult,
        pageUnits: Map<Int, List<PdfTranslationUnit>>,
        translations: Map<String, String>,
        totalSourceBlocks: Int,
        outputFileName: String,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): Uri {
        ensureTranslationActive(executionController)
        val workDir = File(context.cacheDir, "text_layer_raster_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            DebugLog.log("[PDF] Rasterizing translated text-layer PDF fallback to avoid embedded font limitations")
            val renderedPages = renderTranslatedTextLayerPdfPagesToPng(
                sourcePdf = sourcePdf,
                textLayerResult = textLayerResult,
                pageUnits = pageUnits,
                translations = translations,
                totalSourceBlocks = totalSourceBlocks,
                workDir = workDir,
                baseName = outputFileName.substringBeforeLast('.'),
                executionController = executionController,
                onProgress = onProgress
            )
            if (renderedPages.isEmpty()) {
                throw PDFTranslationDisplayException(
                    displayMessage = context.getString(R.string.pdf_translation_error_export_failed),
                    displayDetails = context.getString(R.string.workflow_manga_error_no_rendered_pages)
                )
            }
            val rasterPdf = File(workDir, outputFileName)
            createPdfFromImageFiles(renderedPages, rasterPdf)
            return saveFileToOutputFolder(rasterPdf, "pdfs", "application/pdf", outputFileName)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    suspend fun exportTranslatedTextLayerPdf(
        pdfUri: Uri,
        outputFileName: String = "translated_text_layer_${System.currentTimeMillis()}.pdf",
        settingsOverride: RemoteSummarySettingsSnapshot? = null,
        executionController: PDFTranslationExecutionController? = null,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val notificationId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.PDF_TRANSLATION,
            context.getString(R.string.pdf_translation_notification_title)
        )
        val cachedPdf = copyPdfToCache(pdfUri)
        try {
            DebugLog.log("[PDF] Exporting text-layer translated PDF")
            val textLayerResult = collectPdfTextLayerBlocks(cachedPdf) { progress ->
                onProgress?.invoke(progress)
            }
            val translatableBlocks = textLayerResult.blocks.filter { it.text.isNotBlank() }
            val qualityMode = settingsRepo.pdfTranslationOptionsSnapshot().qualityMode
            val pageUnits = buildTextLayerTranslationUnits(textLayerResult.pages, qualityMode)
            val translatableUnits = pageUnits.values.flatten()
            if (translatableUnits.isEmpty() || translatableBlocks.isEmpty()) {
                throw IllegalStateException("No searchable text layer found")
            }
            val totalSourceBlocks = translatableUnits.sumOf { it.sourceBlockCount }

            val translations = translatePageUnits(
                pdfUri = pdfUri,
                totalPages = textLayerResult.totalPages,
                pageUnits = pageUnits,
                totalSourceBlocks = totalSourceBlocks,
                settingsOverride = settingsOverride,
                executionController = executionController,
                onProgress = onProgress,
                notificationId = notificationId
            )

            val doc = PDDocument.load(cachedPdf)
            try {
                if (doc.isEncrypted) {
                    runCatching { doc.setAllSecurityToBeRemoved(true) }
                }
                val fontSelection = try {
                    loadTranslationFont(doc, translations.values.asSequence())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    DebugLog.log("[PDF] Text-layer embedded PDF export font unavailable; using raster fallback: ${error.message}")
                    val outputUri = exportTranslatedTextLayerPdfRasterFallback(
                        sourcePdf = cachedPdf,
                        textLayerResult = textLayerResult,
                        pageUnits = pageUnits,
                        translations = translations,
                        totalSourceBlocks = totalSourceBlocks,
                        outputFileName = outputFileName,
                        executionController = executionController,
                        onProgress = onProgress
                    )
                    UnifiedNotificationManager.completeTask(
                        notificationId,
                        context.getString(R.string.pdf_translation_notification_complete)
                    )
                    return@withContext Result.success(outputUri)
                }
                val font = fontSelection.font
                textLayerResult.pages.forEach { pageResult ->
                    currentCoroutineContext().ensureActive()
                    if (pageResult.pageIndex >= doc.numberOfPages) return@forEach
                    onProgress?.invoke(
                        PdfOcrTranslationProgress(
                            stage = PdfOcrTranslationStage.WRITING,
                            processedPages = pageResult.pageIndex + 1,
                            totalPages = textLayerResult.totalPages,
                            translatedBlocks = totalSourceBlocks,
                            totalBlocks = totalSourceBlocks
                        )
                    )
                    UnifiedNotificationManager.updateProgress(
                        notificationId,
                        0.82f + (pageResult.pageIndex + 1).toFloat() / textLayerResult.totalPages.toFloat() * 0.16f,
                        context.getString(R.string.pdf_translation_notification_writing, pageResult.pageIndex + 1, textLayerResult.totalPages)
                    )
                    val page = doc.getPage(pageResult.pageIndex)
                    var shouldOverlayOriginal = pageContainsImageContent(page)
                    if (!shouldOverlayOriginal) {
                        shouldOverlayOriginal = !runCatching { stripVisibleTextDrawingOperators(doc, page) }
                            .onFailure { DebugLog.log("[PDF] Falling back to overlay text replacement: ${it.message}") }
                            .isSuccess
                    }
                    try {
                        appendTranslatedTextLayerBlocks(
                            doc = doc,
                            page = page,
                            units = pageUnits[pageResult.pageIndex].orEmpty(),
                            pdfWidth = pageResult.pdfWidth,
                            pdfHeight = pageResult.pdfHeight,
                            translations = translations,
                            font = font,
                            coverOriginalText = shouldOverlayOriginal
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DebugLog.log(
                            "[PDF] Text-layer PDF export failed while writing page ${pageResult.pageIndex + 1} " +
                                "with font ${fontSelection.sourceLabel}; using raster fallback: ${error.message}"
                        )
                        val outputUri = exportTranslatedTextLayerPdfRasterFallback(
                            sourcePdf = cachedPdf,
                            textLayerResult = textLayerResult,
                            pageUnits = pageUnits,
                            translations = translations,
                            totalSourceBlocks = totalSourceBlocks,
                            outputFileName = outputFileName,
                            executionController = executionController,
                            onProgress = onProgress
                        )
                        UnifiedNotificationManager.completeTask(
                            notificationId,
                            context.getString(R.string.pdf_translation_notification_complete)
                        )
                        return@withContext Result.success(outputUri)
                    }
                }
                val outputUri = saveToOutputFolder(doc, outputFileName)
                DebugLog.log("[PDF] Text-layer translated PDF saved: $outputUri")
                UnifiedNotificationManager.completeTask(
                    notificationId,
                    context.getString(R.string.pdf_translation_notification_complete)
                )
                Result.success(outputUri)
            } finally {
                doc.close()
            }
        } catch (e: CancellationException) {
            DebugLog.log("[PDF] Text-layer translated PDF export cancelled")
            UnifiedNotificationManager.dismissTask(notificationId)
            Result.failure(e)
        } catch (e: Exception) {
            DebugLog.log("[PDF] Text-layer translated PDF export failed: ${e.message}")
            UnifiedNotificationManager.failTask(notificationId, classifyTranslationDisplayError(e).message)
            Result.failure(e)
        } finally {
            cachedPdf.delete()
        }
    }

    private suspend fun translatePageUnits(
        pdfUri: Uri,
        totalPages: Int,
        pageUnits: Map<Int, List<PdfTranslationUnit>>,
        totalSourceBlocks: Int,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): LinkedHashMap<String, String> {
        val snapshot = settingsOverride ?: settingsRepo.pdfTranslationSettings.snapshot()
        val options = settingsRepo.pdfTranslationOptionsSnapshot()
        val qualityMode = options.qualityMode
        val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
        executionController?.registerRemoteClient(client)
        val targetLanguage = snapshot.targetLanguage
        var translatedBlocks = 0
        val translations = linkedMapOf<String, String>()
        val pageContexts = linkedMapOf<Int, String>()

        try {
            for (pageIndex in 0 until totalPages) {
                ensureTranslationActive(executionController)
                val units = pageUnits[pageIndex].orEmpty()
                if (units.isEmpty()) continue
                val pageChunks = chunkPageBlocks(units, snapshot.chunkContext, qualityMode)
                val imageAttachment = if (options.usePageScreenshotContext && canUsePageScreenshotContext(snapshot)) {
                    runCatching {
                        RemoteSummaryImageAttachment(
                            base64 = renderPdfPageScreenshotBase64(
                                pdfUri = pdfUri,
                                pageIndex = pageIndex,
                                maxSide = options.screenshotMaxSide,
                                jpegQuality = options.screenshotJpegQuality
                            )
                        )
                    }.onFailure { error ->
                        DebugLog.log("[PDF] Screenshot context unavailable for page ${pageIndex + 1}: ${error.message}")
                    }.getOrNull()
                } else {
                    null
                }
                val pageContext = if (qualityMode == PdfTranslationQualityMode.BEST_QUALITY) {
                    buildPageUnderstandingContext(
                        pageIndex = pageIndex,
                        totalPages = totalPages,
                        snapshot = snapshot,
                        targetLanguage = targetLanguage,
                        units = units,
                        imageAttachment = imageAttachment,
                        previousPageContext = pageContexts[pageIndex - 1],
                        client = client,
                        executionController = executionController
                    ).also { contextNote ->
                        if (contextNote.isNotBlank()) {
                            pageContexts[pageIndex] = contextNote
                        }
                    }
                } else {
                    pageContexts[pageIndex - 1]?.takeIf { qualityMode == PdfTranslationQualityMode.BALANCED }
                }
                val progress = 0.25f + pageIndex.toFloat() / totalPages.toFloat() * 0.55f
                onProgress?.invoke(
                    PdfOcrTranslationProgress(
                        stage = PdfOcrTranslationStage.TRANSLATING,
                        processedPages = pageIndex,
                        totalPages = totalPages,
                        translatedBlocks = translatedBlocks,
                        totalBlocks = totalSourceBlocks
                    )
                )
                UnifiedNotificationManager.updateProgress(
                    notificationId,
                    progress,
                    context.getString(R.string.pdf_translation_notification_translating, pageIndex + 1, totalPages)
                )

                pageChunks.forEach { chunk ->
                    ensureTranslationActive(executionController)
                    val pageTranslations = translateSinglePage(
                        pageIndex = pageIndex,
                        totalPages = totalPages,
                        snapshot = snapshot,
                        targetLanguage = targetLanguage,
                        pageChunk = chunk,
                        fallbackToTextOnly = options.textOnlyFallbackEnabled,
                        imageAttachment = imageAttachment,
                        pageContext = pageContext,
                        qualityMode = qualityMode,
                        client = client,
                        executionController = executionController
                    )
                    chunk.units.forEach { unit ->
                        val translated = pageTranslations[unit.id].orEmpty().trim()
                        if (translated.isNotBlank()) {
                            translations[unit.id] = translated
                            translatedBlocks += unit.sourceBlockCount
                        }
                    }
                    onProgress?.invoke(
                        PdfOcrTranslationProgress(
                            stage = PdfOcrTranslationStage.TRANSLATING,
                            processedPages = pageIndex + 1,
                            totalPages = totalPages,
                            translatedBlocks = translatedBlocks,
                            totalBlocks = totalSourceBlocks
                        )
                    )
                }
            }
            applyTranslationCorrectionPass(
                pageUnits = pageUnits,
                translations = translations,
                totalSourceBlocks = totalSourceBlocks,
                snapshot = snapshot,
                targetLanguage = targetLanguage,
                qualityMode = qualityMode,
                pageContexts = pageContexts,
                client = client,
                executionController = executionController,
                onProgress = onProgress,
                notificationId = notificationId
            )
            return translations
        } finally {
            executionController?.clearRemoteClient(client)
        }
    }

    private fun buildOcrTranslationUnits(
        pages: List<PdfOcrPageResult>,
        qualityMode: PdfTranslationQualityMode
    ): Map<Int, List<PdfTranslationUnit>> {
        return pages.associate { page ->
            val sourceBlocks = page.blocks.mapNotNull { block ->
                val sourceLines = cleanTranslationSourceLines(
                    block.lineBoxes.ifEmpty { listOf(PdfOcrLine(block.text, block.box)) }.map { it.text },
                    qualityMode
                )
                val text = sourceLines.joinToString("\n").trim()
                if (text.isBlank()) return@mapNotNull null
                PdfTranslationSourceBlock(
                    sourceId = "p${page.pageIndex + 1}_b${block.blockIndex + 1}",
                    pageIndex = block.pageIndex,
                    sourceIndex = block.blockIndex,
                    text = text,
                    rect = PDFTranslationLogic.mapBitmapBoxToPdfRect(
                        box = block.box,
                        bitmapWidth = page.bitmapWidth,
                        bitmapHeight = page.bitmapHeight,
                        pdfWidth = page.pdfWidth,
                        pdfHeight = page.pdfHeight
                    ).padded(1.5f, page.pdfWidth, page.pdfHeight),
                    backgroundColor = block.backgroundColor,
                    textColor = block.textColor,
                    lineCount = sourceLines.size.coerceAtLeast(1),
                    sourceLines = sourceLines
                )
            }
            page.pageIndex to buildTranslationUnitsForPage(
                pageIndex = page.pageIndex,
                pdfWidth = page.pdfWidth,
                pdfHeight = page.pdfHeight,
                sourceBlocks = sourceBlocks,
                qualityMode = qualityMode
            )
        }
    }

    private fun toPageTranslationBlocks(units: List<PdfTranslationUnit>): List<PDFTranslationLogic.PageTranslationBlock> {
        return units.mapIndexed { promptIndex, unit ->
            PDFTranslationLogic.PageTranslationBlock(
                id = unit.id,
                text = unit.text,
                x = unit.rect.x,
                y = unit.rect.y,
                width = unit.rect.width,
                height = unit.rect.height,
                readingOrder = promptIndex + 1,
                sourceBlockCount = unit.sourceBlockCount,
                sourceLineCount = unit.sourceLineCount,
                sourceLines = unit.sourceLines
            )
        }
    }

    private fun buildTextLayerTranslationUnits(
        pages: List<PdfTextLayerPageResult>,
        qualityMode: PdfTranslationQualityMode
    ): Map<Int, List<PdfTranslationUnit>> {
        return pages.associate { page ->
            val sourceBlocks = page.blocks.mapNotNull { block ->
                val sourceLines = cleanTranslationSourceLines(block.text.lines(), qualityMode)
                val text = sourceLines.joinToString("\n").trim()
                if (text.isBlank()) return@mapNotNull null
                PdfTranslationSourceBlock(
                    sourceId = "p${page.pageIndex + 1}_b${block.blockIndex + 1}",
                    pageIndex = block.pageIndex,
                    sourceIndex = block.blockIndex,
                    text = text,
                    rect = block.rect,
                    backgroundColor = block.backgroundColor,
                    textColor = block.textColor,
                    lineCount = sourceLines.size.coerceAtLeast(1),
                    sourceLines = sourceLines
                )
            }
            page.pageIndex to buildTranslationUnitsForPage(
                pageIndex = page.pageIndex,
                pdfWidth = page.pdfWidth,
                pdfHeight = page.pdfHeight,
                sourceBlocks = sourceBlocks,
                qualityMode = qualityMode
            )
        }
    }

    private fun buildTranslationUnitsForPage(
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float,
        sourceBlocks: List<PdfTranslationSourceBlock>,
        qualityMode: PdfTranslationQualityMode
    ): List<PdfTranslationUnit> {
        if (sourceBlocks.isEmpty()) return emptyList()
        val sorted = sourceBlocks.sortedWith(
            compareBy<PdfTranslationSourceBlock> { pdfTextRectTop(it.rect, pdfHeight) }.thenBy { it.rect.x }
        )
        val grouped = mutableListOf<MutableList<PdfTranslationSourceBlock>>()
        sorted.forEach { block ->
            val current = grouped.lastOrNull()
            val previous = current?.lastOrNull()
            if (current != null && previous != null && translationGroupsShouldMerge(current, block, pdfWidth, pdfHeight, qualityMode)) {
                current += block
            } else {
                grouped += mutableListOf(block)
            }
        }
        val merged = mergeAdjacentTranslationGroups(grouped, pdfWidth, pdfHeight, qualityMode)
        return merged.mapIndexedNotNull { unitIndex, group ->
            val ordered = group.sortedWith(
                compareBy<PdfTranslationSourceBlock> { pdfTextRectTop(it.rect, pdfHeight) }.thenBy { it.rect.x }
            )
            val text = ordered.joinToString("\n") { it.text.trim() }.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val backgroundColor = dominantBackgroundColor(ordered.map { it.backgroundColor })
            PdfTranslationUnit(
                id = "p${pageIndex + 1}_u${unitIndex + 1}",
                pageIndex = pageIndex,
                unitIndex = unitIndex,
                text = text,
                rect = unionPdfRects(ordered.map { it.rect }).padded(1.5f, pdfWidth, pdfHeight),
                backgroundColor = backgroundColor,
                textColor = contrastingTextColor(backgroundColor),
                sourceBlockIds = ordered.map { it.sourceId },
                sourceLineCount = ordered.sumOf { it.lineCount },
                sourceLines = ordered.flatMap { it.sourceLines }
            )
        }
    }

    private fun mergeAdjacentTranslationGroups(
        groups: List<MutableList<PdfTranslationSourceBlock>>,
        pdfWidth: Float,
        pdfHeight: Float,
        qualityMode: PdfTranslationQualityMode
    ): List<List<PdfTranslationSourceBlock>> {
        if (groups.size <= 1) return groups
        val merged = mutableListOf<MutableList<PdfTranslationSourceBlock>>()
        groups.forEach { group ->
            val previous = merged.lastOrNull()
            if (previous == null) {
                merged += group.toMutableList()
                return@forEach
            }
            if (translationGroupsShouldMerge(previous, group.first(), pdfWidth, pdfHeight, qualityMode, allowNarrationJoin = false)) {
                previous += group
            } else {
                merged += group.toMutableList()
            }
        }
        return merged
    }

    private fun translationGroupsShouldMerge(
        currentGroup: List<PdfTranslationSourceBlock>,
        nextBlock: PdfTranslationSourceBlock,
        pdfWidth: Float,
        pdfHeight: Float,
        qualityMode: PdfTranslationQualityMode,
        allowNarrationJoin: Boolean = true
    ): Boolean {
        val currentRect = unionPdfRects(currentGroup.map { it.rect })
        val nextRect = nextBlock.rect
        val verticalGap = pdfTextRectTop(nextRect, pdfHeight) - pdfTextRectBottom(currentRect, pdfHeight)
        val horizontalGap = rectHorizontalGap(currentRect, nextRect)
        val heightScale = maxOf(currentRect.height, nextRect.height)
        val widthScale = minOf(currentRect.width, nextRect.width).coerceAtLeast(1f)
        val sourceChars = currentGroup.sumOf { it.text.filterNot(Char::isWhitespace).length }
        val nextChars = nextBlock.text.filterNot(Char::isWhitespace).length
        val tinyFragments = sourceChars <= 24 || nextChars <= 24
        val qualityMultiplier = when (qualityMode) {
            PdfTranslationQualityMode.BEST_QUALITY -> 1.35f
            PdfTranslationQualityMode.BALANCED -> 1.12f
            PdfTranslationQualityMode.FASTER -> 1f
        }
        val stackedColumn = textLayerRectsAreRelated(currentRect, nextRect) &&
            verticalGap <= maxOf(18f * qualityMultiplier, heightScale * if (tinyFragments) 2.9f * qualityMultiplier else 2.1f * qualityMultiplier)
        val inlineContinuation = abs(pdfTextRectTop(currentRect, pdfHeight) - pdfTextRectTop(nextRect, pdfHeight)) <= maxOf(12f, heightScale * 0.75f) &&
            horizontalGap <= maxOf(22f * qualityMultiplier, widthScale * 0.28f * qualityMultiplier)
        val closeNarrationFragments = allowNarrationJoin &&
            tinyFragments &&
            verticalGap <= maxOf(30f * qualityMultiplier, heightScale * 3.1f * qualityMultiplier) &&
            horizontalGap <= maxOf(36f * qualityMultiplier, widthScale * 0.4f * qualityMultiplier)
        val mangaBubbleFragments = qualityMode == PdfTranslationQualityMode.BEST_QUALITY &&
            tinyFragments &&
            verticalGap in -8f..maxOf(42f, heightScale * 3.8f) &&
            horizontalGap <= maxOf(48f, widthScale * 0.52f)
        if (!(stackedColumn || inlineContinuation || closeNarrationFragments || mangaBubbleFragments)) return false
        val mergedRect = unionPdfRects(currentGroup.map { it.rect } + nextRect)
        if (translationUnitWouldSpanTooMuchPage(mergedRect, pdfWidth, pdfHeight)) return false
        return !looksLikeSeparatePanels(currentRect, nextRect, pdfWidth)
    }

    private fun translationUnitWouldSpanTooMuchPage(rect: PdfMappedRect, pageWidth: Float, pageHeight: Float): Boolean {
        val pageArea = (pageWidth * pageHeight).coerceAtLeast(1f)
        val rectAreaRatio = (rect.width * rect.height) / pageArea
        val widthRatio = rect.width / pageWidth.coerceAtLeast(1f)
        val heightRatio = rect.height / pageHeight.coerceAtLeast(1f)
        return rectAreaRatio > 0.14f ||
            heightRatio > 0.42f ||
            (widthRatio > 0.72f && heightRatio > 0.18f) ||
            (widthRatio > 0.55f && heightRatio > 0.28f)
    }

    private fun dominantBackgroundColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.WHITE
        val whiteCount = colors.count { it == Color.WHITE }
        val blackCount = colors.count { it == Color.BLACK }
        return when {
            whiteCount >= (colors.size + 1) / 2 -> Color.WHITE
            blackCount >= (colors.size + 1) / 2 -> Color.BLACK
            else -> {
                val red = colors.sumOf { Color.red(it) } / colors.size
                val green = colors.sumOf { Color.green(it) } / colors.size
                val blue = colors.sumOf { Color.blue(it) } / colors.size
                Color.rgb(red, green, blue)
            }
        }
    }

    private fun looksLikeSeparatePanels(first: PdfMappedRect, second: PdfMappedRect, pageWidth: Float): Boolean {
        val horizontalGap = rectHorizontalGap(first, second)
        val centerGap = abs((first.x + first.width / 2f) - (second.x + second.width / 2f))
        return horizontalGap > maxOf(48f, minOf(first.width, second.width) * 0.5f) &&
            centerGap > pageWidth * 0.24f &&
            !textLayerRectsAreRelated(first, second)
    }

    private fun rectHorizontalGap(first: PdfMappedRect, second: PdfMappedRect): Float {
        val firstRight = first.x + first.width
        val secondRight = second.x + second.width
        return maxOf(0f, maxOf(first.x, second.x) - minOf(firstRight, secondRight))
    }

    private suspend fun applyTranslationCorrectionPass(
        pageUnits: Map<Int, List<PdfTranslationUnit>>,
        translations: LinkedHashMap<String, String>,
        totalSourceBlocks: Int,
        snapshot: com.example.llamadroid.data.RemoteSummarySettingsSnapshot,
        targetLanguage: String,
        qualityMode: PdfTranslationQualityMode,
        pageContexts: Map<Int, String>,
        client: RemoteSummaryClient,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ) {
        val entries = pageUnits.values.flatten().mapNotNull { unit ->
            val translated = translations[unit.id]?.trim().orEmpty()
            if (translated.isBlank()) null else {
                PDFTranslationLogic.TranslationCorrectionEntry(
                    id = unit.id,
                    sourceText = unit.text,
                    translatedText = translated,
                    pageNumber = unit.pageIndex + 1
                ) to unit
            }
        }
        if (entries.isEmpty()) return
        val chunks = chunkCorrectionEntries(entries.map { it.first }, snapshot.chunkContext)
        chunks.forEachIndexed { index, chunk ->
            ensureTranslationActive(executionController)
            onProgress?.invoke(
                PdfOcrTranslationProgress(
                    stage = PdfOcrTranslationStage.CORRECTING,
                    processedPages = index,
                    totalPages = chunks.size,
                    translatedBlocks = totalSourceBlocks,
                    totalBlocks = totalSourceBlocks
                )
            )
            UnifiedNotificationManager.updateProgress(
                notificationId,
                0.80f + index.toFloat() / chunks.size.toFloat() * 0.08f,
                context.getString(R.string.pdf_translation_notification_correcting, index + 1, chunks.size)
            )
            val fixes = runCatching {
                runWithTranslationRetries(
                    label = "correction chunk ${index + 1}",
                    executionController = executionController
                ) {
                    val response = client.summarize(
                        RemoteSummaryRequest(
                            systemPrompt = PDFTranslationLogic.DEFAULT_TRANSLATION_CORRECTOR_SYSTEM_PROMPT,
                            userPrompt = PDFTranslationLogic.buildTranslationCorrectionPrompt(
                                targetLanguage = targetLanguage,
                                entries = chunk,
                                pageContexts = if (qualityMode == PdfTranslationQualityMode.FASTER) emptyMap() else {
                                    chunk.map { it.pageNumber }.distinct().associateWith { pageNumber ->
                                        pageContexts[pageNumber - 1].orEmpty()
                                    }.filterValues { it.isNotBlank() }
                                },
                                qualityMode = qualityMode
                            ),
                            contextSize = snapshot.chunkContext,
                            maxTokens = snapshot.chunkMaxTokens,
                            temperature = deterministicTranslationTemperature(snapshot.temperature),
                            thinkingEnabled = false
                        )
                    )
                    runCatching { PDFTranslationLogic.parseOptionalTranslationFixesJson(response.output) }
                        .getOrElse {
                            val repairResponse = client.summarize(
                                RemoteSummaryRequest(
                                    systemPrompt = PDFTranslationLogic.DEFAULT_TRANSLATION_CORRECTOR_SYSTEM_PROMPT,
                                    userPrompt = PDFTranslationLogic.buildTranslationFixesRepairPrompt(response.output),
                                    contextSize = snapshot.chunkContext,
                                    maxTokens = snapshot.chunkMaxTokens,
                                    temperature = deterministicTranslationTemperature(snapshot.temperature),
                                    thinkingEnabled = false
                                )
                            )
                            PDFTranslationLogic.parseOptionalTranslationFixesJson(repairResponse.output)
                        }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                DebugLog.log("[PDF] Translation correction chunk ${index + 1} failed; keeping first-pass translations: ${error.message}")
                emptyMap()
            }
            fixes.forEach { (id, fixedText) ->
                val unit = entries.firstOrNull { it.first.id == id }?.second
                if (unit != null && fixedText.isNotBlank()) {
                    translations[unit.id] = fixedText.trim()
                }
            }
        }
    }

    private fun chunkCorrectionEntries(
        entries: List<PDFTranslationLogic.TranslationCorrectionEntry>,
        contextSize: Int
    ): List<List<PDFTranslationLogic.TranslationCorrectionEntry>> {
        val maxApproxTokens = (contextSize * 0.55f).toInt().coerceAtLeast(1200)
        val chunks = mutableListOf<MutableList<PDFTranslationLogic.TranslationCorrectionEntry>>()
        var current = mutableListOf<PDFTranslationLogic.TranslationCorrectionEntry>()
        var currentTokens = 0
        entries.forEach { entry ->
            val entryTokens = PDFSummaryLogic.approximateTokens(entry.sourceText + "\n" + entry.translatedText).coerceAtLeast(24)
            if (current.isNotEmpty() && currentTokens + entryTokens > maxApproxTokens) {
                chunks += current
                current = mutableListOf()
                currentTokens = 0
            }
            current += entry
            currentTokens += entryTokens
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private suspend fun buildPageUnderstandingContext(
        pageIndex: Int,
        totalPages: Int,
        snapshot: com.example.llamadroid.data.RemoteSummarySettingsSnapshot,
        targetLanguage: String,
        units: List<PdfTranslationUnit>,
        imageAttachment: RemoteSummaryImageAttachment?,
        previousPageContext: String?,
        client: RemoteSummaryClient,
        executionController: PDFTranslationExecutionController?
    ): String {
        if (units.isEmpty()) return ""
        return runCatching {
            ensureTranslationActive(executionController)
            val blocks = toPageTranslationBlocks(units).take(80)
            val prompt = PDFTranslationLogic.buildPageUnderstandingPrompt(
                targetLanguage = targetLanguage,
                pageNumber = pageIndex + 1,
                totalPages = totalPages,
                blocks = blocks,
                hasImageContext = imageAttachment != null,
                previousPageContext = previousPageContext
            )
            val response = client.summarize(
                RemoteSummaryRequest(
                    systemPrompt = "You are a manga page context analyst. Return concise context only.",
                    userPrompt = prompt,
                    contextSize = snapshot.chunkContext,
                    maxTokens = minOf(512, snapshot.chunkMaxTokens.coerceAtLeast(128)),
                    temperature = deterministicTranslationTemperature(snapshot.temperature),
                    thinkingEnabled = false,
                    imageAttachments = imageAttachment?.let { listOf(it) }.orEmpty()
                )
            )
            PDFSummaryLogic.cleanLlamaOutput(response.output)
                .lines()
                .joinToString(" ") { it.trim() }
                .replace(Regex("""\s+"""), " ")
                .take(700)
                .trim()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            DebugLog.log("[PDF] Page understanding context failed on page ${pageIndex + 1}; continuing without it: ${error.message}")
            ""
        }
    }

    private suspend fun translateSinglePage(
        pageIndex: Int,
        totalPages: Int,
        snapshot: com.example.llamadroid.data.RemoteSummarySettingsSnapshot,
        targetLanguage: String,
        pageChunk: PageTranslationChunk,
        fallbackToTextOnly: Boolean,
        imageAttachment: RemoteSummaryImageAttachment?,
        pageContext: String?,
        qualityMode: PdfTranslationQualityMode,
        client: RemoteSummaryClient,
        executionController: PDFTranslationExecutionController?
    ): Map<String, String> {
        val promptBlocks = toPageTranslationBlocks(pageChunk.units)
        suspend fun requestTranslation(
            requestedBlocks: List<PDFTranslationLogic.PageTranslationBlock>,
            completedTranslations: Map<String, String>,
            withImage: Boolean
        ): Map<String, String> {
            ensureTranslationActive(executionController)
            val prompt = PDFTranslationLogic.buildPageTranslationUserPrompt(
                targetLanguage = targetLanguage,
                pageNumber = pageIndex + 1,
                totalPages = totalPages,
                blocks = requestedBlocks,
                hasImageContext = withImage && imageAttachment != null,
                completedTranslations = completedTranslations,
                chunkIndex = pageChunk.index,
                totalChunks = pageChunk.totalChunks,
                totalPageBlocks = pageChunk.totalPageUnits,
                pageContext = pageContext,
                qualityMode = qualityMode
            )
            return runWithTranslationRetries(
                label = "page ${pageIndex + 1} chunk ${pageChunk.index}/${pageChunk.totalChunks}",
                executionController = executionController
            ) {
                val response = client.summarize(
                    RemoteSummaryRequest(
                        systemPrompt = snapshot.summaryPrompt ?: PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
                        userPrompt = prompt,
                        contextSize = snapshot.chunkContext,
                        maxTokens = PDFTranslationLogic.estimateTranslationMaxTokens(
                            requestedBlocks.joinToString("\n") { it.text },
                            snapshot.chunkMaxTokens,
                            requestedBlocks.size
                        ),
                        temperature = pageTranslationTemperature(snapshot.temperature),
                        thinkingEnabled = snapshot.thinkingEnabled,
                        imageAttachments = if (withImage && imageAttachment != null) listOf(imageAttachment) else emptyList()
                    )
                )
                parseOrRecoverPageTranslation(
                    pageNumber = pageIndex + 1,
                    totalPages = totalPages,
                    targetLanguage = targetLanguage,
                    requestedBlocks = requestedBlocks,
                    completedTranslations = completedTranslations,
                    responseOutput = response.output,
                    snapshot = snapshot,
                    pageContext = pageContext,
                    qualityMode = qualityMode,
                    client = client,
                    executionController = executionController
                )
            }
        }

        suspend fun attemptChunkTranslation(): Map<String, String> {
            return runCatching {
                requestTranslation(
                    requestedBlocks = promptBlocks,
                    completedTranslations = emptyMap(),
                    withImage = imageAttachment != null
                )
            }
                .getOrElse { imageError ->
                    if (imageError is CancellationException) throw imageError
                    if (imageAttachment != null && fallbackToTextOnly) {
                        DebugLog.log("[PDF] Image-context translation failed on page ${pageIndex + 1} chunk ${pageChunk.index}; retrying text-only: ${imageError.message}")
                        requestTranslation(
                            requestedBlocks = promptBlocks,
                            completedTranslations = emptyMap(),
                            withImage = false
                        )
                    } else {
                        throw imageError
                    }
                }
        }

        return runCatching { attemptChunkTranslation() }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                if (pageChunk.units.size <= 1 || !shouldRetryTranslationFailure(error)) {
                    throw buildChunkTranslationFailure(pageIndex, pageChunk, error)
                }
                DebugLog.log(
                    "[PDF] Splitting page ${pageIndex + 1} chunk ${pageChunk.index}/${pageChunk.totalChunks} " +
                        "after translation failure: ${error.message}"
                )
                val splitAt = (pageChunk.units.size / 2).coerceAtLeast(1)
                val leftChunk = PageTranslationChunk(
                    units = pageChunk.units.subList(0, splitAt),
                    index = 1,
                    totalChunks = 2,
                    totalPageUnits = pageChunk.totalPageUnits
                )
                val rightChunk = PageTranslationChunk(
                    units = pageChunk.units.subList(splitAt, pageChunk.units.size),
                    index = 2,
                    totalChunks = 2,
                    totalPageUnits = pageChunk.totalPageUnits
                )
                linkedMapOf<String, String>().apply {
                    putAll(
                        translateSinglePage(
                            pageIndex = pageIndex,
                            totalPages = totalPages,
                            snapshot = snapshot,
                            targetLanguage = targetLanguage,
                            pageChunk = leftChunk,
                            fallbackToTextOnly = fallbackToTextOnly,
                            imageAttachment = imageAttachment,
                            pageContext = pageContext,
                            qualityMode = qualityMode,
                            client = client,
                            executionController = executionController
                        )
                    )
                    putAll(
                        translateSinglePage(
                            pageIndex = pageIndex,
                            totalPages = totalPages,
                            snapshot = snapshot,
                            targetLanguage = targetLanguage,
                            pageChunk = rightChunk,
                            fallbackToTextOnly = fallbackToTextOnly,
                            imageAttachment = imageAttachment,
                            pageContext = pageContext,
                            qualityMode = qualityMode,
                            client = client,
                            executionController = executionController
                        )
                    )
                }
            }
    }

    private suspend fun parseOrRecoverPageTranslation(
        pageNumber: Int,
        totalPages: Int,
        targetLanguage: String,
        requestedBlocks: List<PDFTranslationLogic.PageTranslationBlock>,
        completedTranslations: Map<String, String>,
        responseOutput: String,
        snapshot: com.example.llamadroid.data.RemoteSummarySettingsSnapshot,
        pageContext: String?,
        qualityMode: PdfTranslationQualityMode,
        client: RemoteSummaryClient,
        executionController: PDFTranslationExecutionController?
    ): Map<String, String> {
        val expectedIds = requestedBlocks.map { it.id }.toSet()
        val partial = PDFTranslationLogic.parsePartialPageTranslationJson(responseOutput, expectedIds)
        if (partial.missingIds.isEmpty()) {
            return partial.translations
        }

        val missingBlocks = requestedBlocks.filter { it.id in partial.missingIds }
        val recovered = linkedMapOf<String, String>().apply { putAll(partial.translations) }
        val promptCompletedTranslations = linkedMapOf<String, String>().apply {
            putAll(completedTranslations)
            putAll(partial.translations)
        }
        ensureTranslationActive(executionController)
        val repairResponse = client.summarize(
            RemoteSummaryRequest(
                systemPrompt = PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
                userPrompt = PDFTranslationLogic.buildPageTranslationRepairPrompt(
                    targetLanguage = targetLanguage,
                    blocks = missingBlocks,
                    malformedOutput = responseOutput,
                    completedTranslations = promptCompletedTranslations
                ),
                contextSize = snapshot.chunkContext,
                maxTokens = PDFTranslationLogic.estimateTranslationMaxTokens(
                    missingBlocks.joinToString("\n") { it.text },
                    snapshot.chunkMaxTokens,
                    missingBlocks.size
                ),
                temperature = deterministicTranslationTemperature(snapshot.temperature),
                thinkingEnabled = false
            )
        )
        val repaired = PDFTranslationLogic.parsePartialPageTranslationJson(repairResponse.output, partial.missingIds)
        recovered.putAll(repaired.translations)
        val refillContextTranslations = linkedMapOf<String, String>().apply {
            putAll(completedTranslations)
            putAll(recovered)
        }
        val stillMissing = expectedIds - recovered.keys
        if (stillMissing.isEmpty()) {
            return recovered
        }

        val refillBlocks = requestedBlocks.filter { it.id in stillMissing }
        ensureTranslationActive(executionController)
        val refillResponse = client.summarize(
            RemoteSummaryRequest(
                systemPrompt = snapshot.summaryPrompt ?: PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
                userPrompt = PDFTranslationLogic.buildPageTranslationUserPrompt(
                    targetLanguage = targetLanguage,
                    pageNumber = pageNumber,
                    totalPages = totalPages,
                    blocks = refillBlocks,
                    hasImageContext = false,
                    completedTranslations = refillContextTranslations,
                    pageContext = pageContext,
                    qualityMode = qualityMode
                ),
                contextSize = snapshot.chunkContext,
                maxTokens = PDFTranslationLogic.estimateTranslationMaxTokens(
                    refillBlocks.joinToString("\n") { it.text },
                    snapshot.chunkMaxTokens,
                    refillBlocks.size
                ),
                temperature = pageTranslationTemperature(snapshot.temperature),
                thinkingEnabled = snapshot.thinkingEnabled
            )
        )
        val refill = PDFTranslationLogic.parsePartialPageTranslationJson(refillResponse.output, stillMissing)
        recovered.putAll(refill.translations)
        val unresolved = expectedIds - recovered.keys
        if (unresolved.isNotEmpty()) {
            throw IllegalStateException(refill.parseError ?: partial.parseError ?: "translation_json_missing_ids")
        }
        return recovered
    }

    private fun renderPdfPageScreenshotBase64(
        pdfUri: Uri,
        pageIndex: Int,
        maxSide: Int,
        jpegQuality: Int
    ): String {
        val cachedPdf = copyPdfToCache(pdfUri)
        try {
            val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(pageIndex)
                try {
                    val scale = (maxSide.toFloat() / maxOf(page.width, page.height).toFloat()).coerceAtMost(1.5f).coerceAtLeast(0.25f)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).roundToInt().coerceAtLeast(1),
                        (page.height * scale).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    try {
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), output)
                        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
                pfd.close()
            }
        } finally {
            cachedPdf.delete()
        }
    }
    
    /**
     * Get page count for a PDF
     */
    suspend fun getPageCount(pdfUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val doc = PDDocument.load(inputStream)
                val count = doc.numberOfPages
                doc.close()
                return@withContext count
            }
            0
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Parse page range string like "1-3, 5, 7-10" into list of page numbers
     */
    private fun parsePageRange(range: String): List<Int> {
        val pages = mutableListOf<Int>()
        
        range.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val (start, end) = trimmed.split("-").map { it.trim().toIntOrNull() }
                if (start != null && end != null && start <= end) {
                    pages.addAll(start..end)
                }
            } else {
                trimmed.toIntOrNull()?.let { pages.add(it) }
            }
        }
        
        return pages.distinct().sorted()
    }

    private fun copyPdfToCache(pdfUri: Uri): File {
        val cacheFile = File(context.cacheDir, "pdf_extract_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(pdfUri)?.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open PDF")
        return cacheFile
    }

    private fun extractTextFromPage(doc: PDDocument, pageNumber: Int): String {
        val stripper = PDFTextStripper()
        stripper.setStartPage(pageNumber)
        stripper.setEndPage(pageNumber)
        stripper.setSortByPosition(true)
        stripper.setAddMoreFormatting(true)
        stripper.setLineSeparator("\n")
        stripper.setPageEnd("\n\n")
        return stripper.getText(doc)
    }

    private fun normalizeExtractedText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u00AD", "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun cleanTranslationSourceLines(
        rawLines: List<String>,
        qualityMode: PdfTranslationQualityMode
    ): List<String> {
        val seen = linkedSetOf<String>()
        return rawLines
            .flatMap { normalizeExtractedText(it).lines() }
            .map { normalizeJapaneseOcrFragment(it, qualityMode) }
            .filter { line ->
                line.isNotBlank() &&
                    (qualityMode == PdfTranslationQualityMode.FASTER || !line.matches(Regex("""^[\p{Punct}、。！？…・ー～]+$""")))
            }
            .filter { line ->
                val key = line.filterNot(Char::isWhitespace)
                key.isNotBlank() && seen.add(key)
            }
    }

    private fun normalizeJapaneseOcrFragment(
        text: String,
        qualityMode: PdfTranslationQualityMode
    ): String {
        var cleaned = normalizeExtractedText(text)
            .replace('，', '、')
            .replace('｡', '。')
            .replace('､', '、')
            .replace('！', '!')
            .replace('？', '?')
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
        if (qualityMode != PdfTranslationQualityMode.FASTER) {
            cleaned = cleaned
                .replace(Regex("""\s+([、。!?！？…])"""), "$1")
                .replace(Regex("""([「『（(])\s+"""), "$1")
                .replace(Regex("""\s+([」』）)])"""), "$1")
                .replace(Regex("""([\p{IsHan}\p{IsHiragana}\p{IsKatakana}])\s+([\p{IsHan}\p{IsHiragana}\p{IsKatakana}])"""), "$1$2")
                .replace(Regex("""([!?！？]){3,}"""), "$1$1")
                .replace(Regex("""([…・ー]){4,}"""), "$1$1$1")
        }
        return cleaned.trim()
    }

    private fun shouldUseOcrFallback(text: String): Boolean {
        if (text.isBlank()) return true

        val alnumCount = text.count { it.isLetterOrDigit() }
        if (alnumCount < 24) return true

        val replacementChars = text.count { it == '\uFFFD' || it == '\u0000' }
        if (replacementChars > 0) return true

        val visibleChars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val alnumRatio = alnumCount.toFloat() / visibleChars
        return alnumRatio < 0.45f
    }

    private suspend fun extractTextWithOcr(
        renderer: PdfRenderer,
        recognizers: List<TextRecognizer>,
        pageIndex: Int
    ): String {
        return extractBlocksWithOcr(renderer, recognizers, pageIndex)
            .blocks
            .joinToString("\n\n") { it.text }
    }

    private suspend fun extractBlocksWithOcr(
        renderer: PdfRenderer,
        recognizers: List<TextRecognizer>,
        pageIndex: Int
    ): PdfOcrPageResult {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = computeOcrScale(page.width, page.height)
            val bitmapWidth = (page.width * scale).roundToInt().coerceAtLeast(page.width)
            val bitmapHeight = (page.height * scale).roundToInt().coerceAtLeast(page.height)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val image = InputImage.fromBitmap(bitmap, 0)
                return recognizeTextBlocks(
                    recognizers = recognizers,
                    image = image,
                    bitmap = bitmap,
                    pageIndex = pageIndex,
                    pdfWidth = page.width.toFloat(),
                    pdfHeight = page.height.toFloat()
                )
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    private suspend fun extractBlocksFromImageFile(
        imageFile: File,
        recognizers: List<TextRecognizer>,
        pageIndex: Int
    ): PdfOcrPageResult {
        val decoded = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IllegalStateException("Could not decode image page: ${imageFile.name}")
        val normalized = if (decoded.config == Bitmap.Config.ARGB_8888 && !decoded.hasAlpha()) {
            decoded
        } else {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { flattened ->
                Canvas(flattened).drawColor(Color.WHITE)
                Canvas(flattened).drawBitmap(decoded, 0f, 0f, null)
            }
        }
        if (normalized !== decoded) {
            decoded.recycle()
        }
        try {
            val scale = computeComicImageOcrScale(normalized.width, normalized.height)
            val ocrBitmap = if (abs(scale - 1f) < 0.05f) {
                normalized
            } else {
                Bitmap.createScaledBitmap(
                    normalized,
                    (normalized.width * scale).roundToInt().coerceAtLeast(1),
                    (normalized.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            }
            try {
                val image = InputImage.fromBitmap(ocrBitmap, 0)
                return recognizeTextBlocks(
                    recognizers = recognizers,
                    image = image,
                    bitmap = ocrBitmap,
                    pageIndex = pageIndex,
                    pdfWidth = normalized.width.toFloat(),
                    pdfHeight = normalized.height.toFloat()
                )
            } finally {
                if (ocrBitmap !== normalized) {
                    ocrBitmap.recycle()
                }
            }
        } finally {
            normalized.recycle()
        }
    }

    private suspend fun collectPdfOcrText(
        pdfUri: Uri,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfOcrDocumentResult> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Performing OCR on PDF pages")
            val cachedPdf = copyPdfToCache(pdfUri)
            try {
                val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val recognizers = createOcrRecognizers()
                try {
                    val totalPages = renderer.pageCount
                    if (totalPages == 0) {
                        return@withContext Result.failure(Exception("PDF has no pages"))
                    }
                    onProgress?.invoke(
                        PdfExtractionProgress(
                            processedPages = 0,
                            totalPages = totalPages,
                            textLayerPages = 0,
                            ocrPages = 0,
                            emptyPages = 0,
                            textCharacters = 0
                        )
                    )

                    val pages = mutableListOf<PdfOcrPageResult>()
                    var ocrPages = 0
                    var emptyPages = 0
                    var textCharacters = 0

                    for (pageIndex in 0 until totalPages) {
                        currentCoroutineContext().ensureActive()
                        val pageResult = runCatching { extractBlocksWithOcr(renderer, recognizers, pageIndex) }
                            .onFailure { DebugLog.log("[PDF] Page ${pageIndex + 1} OCR failed: ${it.message}") }
                            .getOrElse {
                                PdfOcrPageResult(
                                    pageIndex = pageIndex,
                                    bitmapWidth = 1,
                                    bitmapHeight = 1,
                                    pdfWidth = 1f,
                                    pdfHeight = 1f,
                                    blocks = emptyList()
                                )
                            }
                        pages += pageResult
                        val pageText = pageResult.blocks.joinToString("\n\n") { it.text }.trim()
                        if (pageText.isNotBlank()) {
                            ocrPages += 1
                            textCharacters += pageText.length
                        } else {
                            emptyPages += 1
                        }
                        onProgress?.invoke(
                            PdfExtractionProgress(
                                processedPages = pageIndex + 1,
                                totalPages = totalPages,
                                textLayerPages = 0,
                                ocrPages = ocrPages,
                                emptyPages = emptyPages,
                                textCharacters = textCharacters
                            )
                        )
                    }

                    val text = pages
                        .flatMap { page -> page.blocks.map { it.text } }
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                        .trim()
                    if (text.isBlank()) {
                        return@withContext Result.failure(Exception("No OCR text found"))
                    }
                    Result.success(
                        PdfOcrDocumentResult(
                            pages = pages,
                            text = text,
                            totalPages = totalPages,
                            ocrPages = ocrPages,
                            emptyPages = emptyPages
                        )
                    )
                } finally {
                    recognizers.forEach { recognizer -> runCatching { recognizer.close() } }
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                }
            } finally {
                cachedPdf.delete()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] PDF OCR failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun collectImageOcrText(
        imageFiles: List<File>,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfOcrDocumentResult> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Performing OCR directly on ${imageFiles.size} comic images")
            val recognizers = createOcrRecognizers()
            try {
                val totalPages = imageFiles.size
                if (totalPages == 0) {
                    return@withContext Result.failure(Exception("Comic has no image pages"))
                }
                onProgress?.invoke(
                    PdfExtractionProgress(
                        processedPages = 0,
                        totalPages = totalPages,
                        textLayerPages = 0,
                        ocrPages = 0,
                        emptyPages = 0,
                        textCharacters = 0
                    )
                )

                val pages = mutableListOf<PdfOcrPageResult>()
                var ocrPages = 0
                var emptyPages = 0
                var textCharacters = 0

                imageFiles.forEachIndexed { pageIndex, imageFile ->
                    currentCoroutineContext().ensureActive()
                    val pageResult = runCatching { extractBlocksFromImageFile(imageFile, recognizers, pageIndex) }
                        .onFailure { DebugLog.log("[PDF] Comic page ${pageIndex + 1} OCR failed: ${it.message}") }
                        .getOrElse {
                            PdfOcrPageResult(
                                pageIndex = pageIndex,
                                bitmapWidth = 1,
                                bitmapHeight = 1,
                                pdfWidth = 1f,
                                pdfHeight = 1f,
                                blocks = emptyList()
                            )
                        }
                    pages += pageResult
                    val pageText = pageResult.blocks.joinToString("\n\n") { it.text }.trim()
                    if (pageText.isNotBlank()) {
                        ocrPages += 1
                        textCharacters += pageText.length
                    } else {
                        emptyPages += 1
                    }
                    onProgress?.invoke(
                        PdfExtractionProgress(
                            processedPages = pageIndex + 1,
                            totalPages = totalPages,
                            textLayerPages = 0,
                            ocrPages = ocrPages,
                            emptyPages = emptyPages,
                            textCharacters = textCharacters
                        )
                    )
                }

                val text = pages
                    .flatMap { page -> page.blocks.map { it.text } }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .trim()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("No OCR text found"))
                }
                Result.success(
                    PdfOcrDocumentResult(
                        pages = pages,
                        text = text,
                        totalPages = totalPages,
                        ocrPages = ocrPages,
                        emptyPages = emptyPages
                    )
                )
            } finally {
                recognizers.forEach { recognizer -> runCatching { recognizer.close() } }
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] Comic image OCR failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun collectPdfTextLayerBlocks(
        cachedPdf: File,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)? = null
    ): PdfTextLayerDocumentResult {
        val doc = PDDocument.load(cachedPdf)
        val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val recognizers = createOcrRecognizers()
        try {
            if (doc.isEncrypted) {
                runCatching { doc.setAllSecurityToBeRemoved(true) }
            }
            val totalPages = doc.numberOfPages
            if (totalPages == 0) {
                throw IllegalStateException("PDF has no pages")
            }
            onProgress?.invoke(
                PdfOcrTranslationProgress(
                    stage = PdfOcrTranslationStage.READING_TEXT,
                    processedPages = 0,
                    totalPages = totalPages,
                    translatedBlocks = 0,
                    totalBlocks = 0
                )
            )

            val pages = mutableListOf<PdfTextLayerPageResult>()
            for (pageIndex in 0 until totalPages) {
                val page = doc.getPage(pageIndex)
                val box = page.cropBox ?: page.mediaBox
                val pdfWidth = box.width
                val pdfHeight = box.height
                val positions = extractTextLayerPositions(doc, pageIndex)
                val lines = buildTextLayerLines(positions, pdfHeight)
                val renderedPage = renderPdfPageBitmap(renderer, pageIndex)
                try {
                    val textLayerBlocks = buildTextLayerBlocks(
                        pageIndex = pageIndex,
                        pdfWidth = pdfWidth,
                        pdfHeight = pdfHeight,
                        lines = lines,
                        renderedPage = renderedPage
                    )
                    val blocks = if (shouldUseOcrFallbackForTextLayerPage(positions, textLayerBlocks)) {
                        DebugLog.log(
                            "[PDF] Page ${pageIndex + 1} text layer looked fragmented " +
                                "(positions=${positions.size}, blocks=${textLayerBlocks.size}); retrying with OCR blocks"
                        )
                        runCatching {
                            buildTextLayerBlocksFromOcrFallback(
                                renderer = renderer,
                                recognizers = recognizers,
                                pageIndex = pageIndex,
                                pdfWidth = pdfWidth,
                                pdfHeight = pdfHeight,
                                renderedPage = renderedPage
                            )
                        }.onFailure { error ->
                            DebugLog.log("[PDF] OCR fallback for text-layer page ${pageIndex + 1} failed: ${error.message}")
                        }.getOrDefault(textLayerBlocks)
                    } else {
                        textLayerBlocks
                    }
                    pages += PdfTextLayerPageResult(
                        pageIndex = pageIndex,
                        pdfWidth = pdfWidth,
                        pdfHeight = pdfHeight,
                        blocks = blocks
                    )
                } finally {
                    renderedPage.recycle()
                }
                onProgress?.invoke(
                    PdfOcrTranslationProgress(
                        stage = PdfOcrTranslationStage.READING_TEXT,
                        processedPages = pageIndex + 1,
                        totalPages = totalPages,
                        translatedBlocks = 0,
                        totalBlocks = 0
                    )
                )
            }
            return PdfTextLayerDocumentResult(pages = pages, totalPages = totalPages)
        } finally {
            recognizers.forEach { recognizer -> runCatching { recognizer.close() } }
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            doc.close()
        }
    }

    private fun extractTextLayerPositions(doc: PDDocument, pageIndex: Int): List<RawTextLayerPosition> {
        val positions = mutableListOf<RawTextLayerPosition>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                val value = text.getUnicode().orEmpty()
                if (value.isBlank()) return
                val width = text.getWidthDirAdj().coerceAtLeast(0.5f)
                val height = text.getHeightDir().coerceAtLeast(text.getFontSizeInPt() * 0.65f).coerceAtLeast(1f)
                positions += RawTextLayerPosition(
                    text = value,
                    x = text.getXDirAdj(),
                    yFromTop = text.getYDirAdj(),
                    width = width,
                    height = height
                )
            }
        }
        stripper.setSortByPosition(true)
        stripper.setStartPage(pageIndex + 1)
        stripper.setEndPage(pageIndex + 1)
        stripper.getText(doc)
        return positions
    }

    private fun buildTextLayerLines(
        positions: List<RawTextLayerPosition>,
        pdfHeight: Float
    ): List<PdfTextLayerLine> {
        val sorted = positions
            .filter { it.text.isNotBlank() && it.width > 0f && it.height > 0f }
            .sortedWith(compareBy<RawTextLayerPosition> { it.yFromTop }.thenBy { it.x })
        if (sorted.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<RawTextLayerPosition>>()
        sorted.forEach { position ->
            val current = groups.lastOrNull()
            val currentY = current?.map { it.yFromTop }?.average()?.toFloat()
            val tolerance = maxOf(2f, position.height * 0.65f)
            if (current != null && currentY != null && abs(currentY - position.yFromTop) <= tolerance) {
                current += position
            } else {
                groups += mutableListOf(position)
            }
        }

        return groups.mapNotNull { group ->
            val ordered = group.sortedBy { it.x }
            val left = ordered.minOf { it.x }
            val right = ordered.maxOf { it.x + it.width }
            val top = ordered.minOf { it.yFromTop }
            val bottom = ordered.maxOf { it.yFromTop + it.height }
            val text = buildString {
                var previousRight: Float? = null
                ordered.forEach { position ->
                    val gap = previousRight?.let { position.x - it } ?: 0f
                    if (isNotEmpty() && gap > maxOf(2f, position.height * 0.25f) && !last().isWhitespace()) {
                        append(' ')
                    }
                    append(position.text)
                    previousRight = position.x + position.width
                }
            }.trim()
            if (text.isBlank()) {
                null
            } else {
                PdfTextLayerLine(
                    text = normalizeExtractedText(text),
                    rect = PDFTranslationLogic.mapTextLayerBoxToPdfRect(
                        x = left,
                        yFromTop = top,
                        width = right - left,
                        height = bottom - top,
                        pageHeight = pdfHeight
                    )
                )
            }
        }
    }

    private fun buildTextLayerBlocks(
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float,
        lines: List<PdfTextLayerLine>,
        renderedPage: Bitmap
    ): List<PdfTextLayerBlock> {
        val sortedLines = lines.sortedWith(
            compareBy<PdfTextLayerLine> { pdfTextRectTop(it.rect, pdfHeight) }.thenBy { it.rect.x }
        )
        val blockLines = mutableListOf<MutableList<PdfTextLayerLine>>()
        sortedLines.forEach { line ->
            val current = blockLines.lastOrNull()
            val previous = current?.lastOrNull()
            val verticalGap = if (previous == null) Float.MAX_VALUE else {
                pdfTextRectTop(line.rect, pdfHeight) - pdfTextRectBottom(previous.rect, pdfHeight)
            }
            val closeVertically = previous != null &&
                verticalGap <= maxOf(12f, maxOf(previous.rect.height, line.rect.height) * 2.15f)
            val closeHorizontally = previous != null && textLayerRectsAreRelated(previous.rect, line.rect)
            if (current != null && closeVertically && closeHorizontally) {
                current += line
            } else {
                blockLines += mutableListOf(line)
            }
        }

        val mergedGroups = mergeAdjacentTextLayerGroups(blockLines, pdfHeight)
        return mergedGroups.mapIndexedNotNull { blockIndex, group ->
            val text = group.joinToString("\n") { it.text }.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val rect = unionPdfRects(group.map { it.rect })
                .padded(1.5f, pdfWidth, pdfHeight)
            val backgroundColor = samplePdfRectBackgroundColor(
                bitmap = renderedPage,
                rect = rect,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight
            )
            PdfTextLayerBlock(
                pageIndex = pageIndex,
                blockIndex = blockIndex,
                text = text,
                rect = rect,
                backgroundColor = backgroundColor,
                textColor = contrastingTextColor(backgroundColor)
            )
        }
    }

    private fun renderPdfPageBitmap(renderer: PdfRenderer, pageIndex: Int): Bitmap {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = computeOcrScale(page.width, page.height)
            val bitmapWidth = (page.width * scale).roundToInt().coerceAtLeast(page.width)
            val bitmapHeight = (page.height * scale).roundToInt().coerceAtLeast(page.height)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    private fun samplePdfRectBackgroundColor(
        bitmap: Bitmap,
        rect: PdfMappedRect,
        pdfWidth: Float,
        pdfHeight: Float
    ): Int {
        val left = (rect.x / pdfWidth * bitmap.width).roundToInt()
        val top = ((pdfHeight - rect.y - rect.height) / pdfHeight * bitmap.height).roundToInt()
        val right = ((rect.x + rect.width) / pdfWidth * bitmap.width).roundToInt()
        val bottom = ((pdfHeight - rect.y) / pdfHeight * bitmap.height).roundToInt()
        return sampleBackgroundColor(bitmap, Rect(left, top, right, bottom))
    }

    private fun pdfTextRectTop(rect: PdfMappedRect, pageHeight: Float): Float {
        return pageHeight - rect.y - rect.height
    }

    private fun pdfTextRectBottom(rect: PdfMappedRect, pageHeight: Float): Float {
        return pageHeight - rect.y
    }

    private fun textLayerRectsAreRelated(first: PdfMappedRect, second: PdfMappedRect): Boolean {
        val firstRight = first.x + first.width
        val secondRight = second.x + second.width
        val overlap = minOf(firstRight, secondRight) - maxOf(first.x, second.x)
        return overlap > 0f || abs(first.x - second.x) < 96f || abs(firstRight - secondRight) < 96f
    }

    private fun mergeAdjacentTextLayerGroups(
        groups: List<MutableList<PdfTextLayerLine>>,
        pageHeight: Float
    ): List<List<PdfTextLayerLine>> {
        if (groups.size <= 1) return groups
        val merged = mutableListOf<MutableList<PdfTextLayerLine>>()
        groups.forEach { group ->
            val previous = merged.lastOrNull()
            if (previous == null) {
                merged += group.toMutableList()
                return@forEach
            }
            val previousRect = unionPdfRects(previous.map { it.rect })
            val currentRect = unionPdfRects(group.map { it.rect })
            val verticalGap = pdfTextRectTop(currentRect, pageHeight) - pdfTextRectBottom(previousRect, pageHeight)
            val tinyFragments = previous.sumOf { it.text.trim().length } <= 12 || group.sumOf { it.text.trim().length } <= 12
            val sameColumn = textLayerRectsAreRelated(previousRect, currentRect)
            if (sameColumn && (verticalGap <= 18f || (tinyFragments && verticalGap <= 30f))) {
                previous += group
            } else {
                merged += group.toMutableList()
            }
        }
        return merged
    }

    private fun shouldUseOcrFallbackForTextLayerPage(
        positions: List<RawTextLayerPosition>,
        blocks: List<PdfTextLayerBlock>
    ): Boolean {
        if (blocks.isEmpty()) return true
        if (positions.size >= 1200 || blocks.size >= 260) return true
        val avgCharsPerBlock = blocks.map { it.text.filterNot(Char::isWhitespace).length }.average().toFloat()
        val tinyBlocks = blocks.count { it.text.filterNot(Char::isWhitespace).length <= 2 }
        val tinyBlockRatio = tinyBlocks.toFloat() / blocks.size.toFloat()
        return (blocks.size >= 80 && avgCharsPerBlock < 5f) || (blocks.size >= 50 && tinyBlockRatio > 0.55f)
    }

    private suspend fun buildTextLayerBlocksFromOcrFallback(
        renderer: PdfRenderer,
        recognizers: List<TextRecognizer>,
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float,
        renderedPage: Bitmap
    ): List<PdfTextLayerBlock> {
        val pageResult = extractBlocksWithOcr(renderer, recognizers, pageIndex)
        return pageResult.blocks.mapIndexedNotNull { blockIndex, block ->
            val rect = PDFTranslationLogic.mapBitmapBoxToPdfRect(
                box = block.box,
                bitmapWidth = pageResult.bitmapWidth,
                bitmapHeight = pageResult.bitmapHeight,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight
            ).padded(1.5f, pdfWidth, pdfHeight)
            val text = normalizeExtractedText(block.text)
            if (text.isBlank()) return@mapIndexedNotNull null
            val backgroundColor = samplePdfRectBackgroundColor(
                bitmap = renderedPage,
                rect = rect,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight
            )
            PdfTextLayerBlock(
                pageIndex = pageIndex,
                blockIndex = blockIndex,
                text = text,
                rect = rect,
                backgroundColor = backgroundColor,
                textColor = contrastingTextColor(backgroundColor)
            )
        }
    }

    private fun unionPdfRects(rects: List<PdfMappedRect>): PdfMappedRect {
        val left = rects.minOf { it.x }
        val bottom = rects.minOf { it.y }
        val right = rects.maxOf { it.x + it.width }
        val top = rects.maxOf { it.y + it.height }
        return PdfMappedRect(
            x = left,
            y = bottom,
            width = (right - left).coerceAtLeast(1f),
            height = (top - bottom).coerceAtLeast(1f)
        )
    }

    private fun createOcrRecognizers(): List<TextRecognizer> {
        return listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )
    }

    private suspend fun recognizeTextBlocks(
        recognizers: List<TextRecognizer>,
        image: InputImage,
        bitmap: Bitmap,
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): PdfOcrPageResult {
        val results = mutableListOf<PdfOcrPageResult>()
        var lastError: Throwable? = null
        recognizers.forEach { recognizer ->
            val result = runCatching {
                recognizeTextBlocksWithSingleRecognizer(
                    recognizer = recognizer,
                    image = image,
                    bitmap = bitmap,
                    pageIndex = pageIndex,
                    pdfWidth = pdfWidth,
                    pdfHeight = pdfHeight
                )
            }.onFailure { error ->
                lastError = error
                DebugLog.log("[PDF] OCR recognizer failed on page ${pageIndex + 1}: ${error.message}")
            }.getOrNull()
            if (result != null) results += result
        }
        if (results.isEmpty()) {
            throw lastError ?: IllegalStateException("OCR failed")
        }
        return mergeOcrRecognizerResults(results, pageIndex, bitmap.width, bitmap.height, pdfWidth, pdfHeight)
    }

    private suspend fun recognizeTextBlocksWithSingleRecognizer(
        recognizer: TextRecognizer,
        image: InputImage,
        bitmap: Bitmap,
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): PdfOcrPageResult =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val blocks = result.textBlocks.mapIndexedNotNull { blockIndex, block ->
                        val box = block.boundingBox?.toPdfOcrBox() ?: return@mapIndexedNotNull null
                        val text = normalizeExtractedText(
                            block.lines.joinToString("\n") { it.text }
                                .ifBlank { block.text }
                        )
                        if (text.isBlank() || box.isEmpty) return@mapIndexedNotNull null
                        val backgroundColor = sampleBackgroundColor(bitmap, block.boundingBox)
                        PdfOcrBlock(
                            pageIndex = pageIndex,
                            blockIndex = blockIndex,
                            text = text,
                            box = box,
                            lineBoxes = block.lines.mapNotNull { line ->
                                val lineBox = line.boundingBox?.toPdfOcrBox() ?: return@mapNotNull null
                                val lineText = normalizeExtractedText(line.text)
                                if (lineText.isBlank() || lineBox.isEmpty) null else PdfOcrLine(lineText, lineBox)
                            },
                            backgroundColor = backgroundColor,
                            textColor = contrastingTextColor(backgroundColor)
                        )
                    }
                    continuation.resume(
                        PdfOcrPageResult(
                            pageIndex = pageIndex,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            pdfWidth = pdfWidth,
                            pdfHeight = pdfHeight,
                            blocks = blocks
                        )
                    )
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }

    private fun mergeOcrRecognizerResults(
        results: List<PdfOcrPageResult>,
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): PdfOcrPageResult {
        val merged = mutableListOf<PdfOcrBlock>()
        results.flatMap { it.blocks }
            .sortedWith(compareBy<PdfOcrBlock> { it.box.top }.thenBy { it.box.left })
            .forEach { candidate ->
                val existingIndex = merged.indexOfFirst { existing -> ocrBlocksLookDuplicate(existing, candidate) }
                if (existingIndex < 0) {
                    merged += candidate
                } else if (candidate.text.length > merged[existingIndex].text.length) {
                    merged[existingIndex] = candidate
                }
            }
        return PdfOcrPageResult(
            pageIndex = pageIndex,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight,
            blocks = merged.mapIndexed { index, block -> block.copy(blockIndex = index) }
        )
    }

    private fun ocrBlocksLookDuplicate(first: PdfOcrBlock, second: PdfOcrBlock): Boolean {
        val overlap = ocrBoxOverlapRatio(first.box, second.box)
        if (overlap >= 0.72f) return true
        val firstText = first.text.filterNot(Char::isWhitespace)
        val secondText = second.text.filterNot(Char::isWhitespace)
        return firstText.isNotBlank() &&
            secondText.isNotBlank() &&
            firstText.equals(secondText, ignoreCase = true) &&
            overlap >= 0.35f
    }

    private fun ocrBoxOverlapRatio(first: PdfOcrBox, second: PdfOcrBox): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val intersection = maxOf(0, right - left) * maxOf(0, bottom - top)
        if (intersection <= 0) return 0f
        val firstArea = first.width * first.height
        val secondArea = second.width * second.height
        return intersection.toFloat() / minOf(firstArea, secondArea).coerceAtLeast(1).toFloat()
    }

    private fun Rect.toPdfOcrBox(): PdfOcrBox {
        return PdfOcrBox(left = left, top = top, right = right, bottom = bottom)
    }

    private fun sampleBackgroundColor(bitmap: Bitmap, rawRect: Rect?): Int {
        val rect = rawRect ?: return Color.WHITE
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        val step = maxOf(1, minOf(width, height) / 12)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        var nearWhite = 0L
        var nearBlack = 0L
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                red += r
                green += g
                blue += b
                val luminance = (0.299 * r) + (0.587 * g) + (0.114 * b)
                if (luminance > 218.0) nearWhite += 1
                if (luminance < 42.0) nearBlack += 1
                count += 1
                x += step
            }
            y += step
        }
        if (count == 0L) return Color.WHITE
        val whiteRatio = nearWhite.toFloat() / count.toFloat()
        val blackRatio = nearBlack.toFloat() / count.toFloat()
        if (whiteRatio >= 0.38f) return Color.WHITE
        if (blackRatio >= 0.38f) return Color.BLACK
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun contrastingTextColor(backgroundColor: Int): Int {
        val luminance = (0.299 * Color.red(backgroundColor)) +
            (0.587 * Color.green(backgroundColor)) +
            (0.114 * Color.blue(backgroundColor))
        return if (luminance < 128.0) Color.WHITE else Color.BLACK
    }

    private fun appendInvisibleSearchText(doc: PDDocument, page: PDPage, pageResult: PdfOcrPageResult) {
        if (pageResult.blocks.isEmpty()) return
        PDPageContentStream(
            doc,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { stream ->
            pageResult.blocks.forEach { block ->
                val lines = block.lineBoxes.ifEmpty { listOf(PdfOcrLine(block.text, block.box)) }
                lines.forEach { line ->
                    val rect = PDFTranslationLogic.mapBitmapBoxToPdfRect(
                        box = line.box,
                        bitmapWidth = pageResult.bitmapWidth,
                        bitmapHeight = pageResult.bitmapHeight,
                        pdfWidth = pageResult.pdfWidth,
                        pdfHeight = pageResult.pdfHeight
                    )
                    drawTextInRect(
                        stream = stream,
                        text = line.text,
                        rect = rect,
                        font = PDType1Font.HELVETICA,
                        visible = false,
                        color = Color.BLACK
                    )
                }
            }
        }
    }

    private fun appendTranslatedBlocks(
        doc: PDDocument,
        page: PDPage,
        units: List<PdfTranslationUnit>,
        pdfWidth: Float,
        pdfHeight: Float,
        translations: Map<String, String>,
        font: PDFont
    ) {
        PDPageContentStream(
            doc,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { stream ->
            units.forEach { unit ->
                val translated = translations[unit.id]?.trim().orEmpty()
                if (translated.isBlank()) return@forEach
                val rect = unit.rect.padded(2f, pdfWidth, pdfHeight)
                stream.setNonStrokingColor(
                    Color.red(unit.backgroundColor),
                    Color.green(unit.backgroundColor),
                    Color.blue(unit.backgroundColor)
                )
                stream.addRect(rect.x, rect.y, rect.width, rect.height)
                stream.fill()
                drawTextInRect(
                    stream = stream,
                    text = translated,
                    rect = rect.padded(-2f, pdfWidth, pdfHeight),
                    font = font,
                    visible = true,
                    color = unit.textColor
                )
            }
        }
    }

    private fun pageContainsImageContent(page: PDPage): Boolean {
        return resourcesContainImageContent(page.resources, mutableSetOf())
    }

    private fun resourcesContainImageContent(resources: PDResources?, visitedResources: MutableSet<Int>): Boolean {
        if (resources == null) return false
        val resourceId = System.identityHashCode(resources.cosObject)
        if (!visitedResources.add(resourceId)) return false
        return try {
            resources.xObjectNames.any { name ->
                val xObject = runCatching { resources.getXObject(name) }.getOrNull()
                when (xObject) {
                    is PDImageXObject -> true
                    is PDFormXObject -> resourcesContainImageContent(xObject.resources, visitedResources)
                    else -> false
                }
            }
        } catch (error: Exception) {
            DebugLog.log("[PDF] Could not inspect page image resources: ${error.message}")
            true
        }
    }

    private fun stripVisibleTextDrawingOperators(doc: PDDocument, page: PDPage) {
        val parser = PDFStreamParser(page)
        parser.parse()
        val filteredTokens = mutableListOf<Any>()
        val pendingOperands = mutableListOf<Any>()
        parser.tokens.forEach { token ->
            if (token is Operator) {
                if (token.name !in textDrawingOperatorsToReplace) {
                    filteredTokens.addAll(pendingOperands)
                    filteredTokens.add(token)
                }
                pendingOperands.clear()
            } else {
                pendingOperands.add(token)
            }
        }
        filteredTokens.addAll(pendingOperands)

        val replacementStream = PDStream(doc)
        replacementStream.createOutputStream().use { output ->
            ContentStreamWriter(output).writeTokens(filteredTokens)
        }
        page.setContents(replacementStream)
    }

    private fun appendTranslatedTextLayerBlocks(
        doc: PDDocument,
        page: PDPage,
        units: List<PdfTranslationUnit>,
        pdfWidth: Float,
        pdfHeight: Float,
        translations: Map<String, String>,
        font: PDFont,
        coverOriginalText: Boolean = true
    ) {
        PDPageContentStream(
            doc,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { stream ->
            units.forEach { unit ->
                val translated = translations[unit.id]?.trim().orEmpty()
                if (translated.isBlank()) return@forEach
                val rect = unit.rect.padded(2f, pdfWidth, pdfHeight)
                if (coverOriginalText) {
                    stream.setNonStrokingColor(
                        Color.red(unit.backgroundColor),
                        Color.green(unit.backgroundColor),
                        Color.blue(unit.backgroundColor)
                    )
                    stream.addRect(rect.x, rect.y, rect.width, rect.height)
                    stream.fill()
                }
                drawTextInRect(
                    stream = stream,
                    text = translated,
                    rect = rect.padded(-2f, pdfWidth, pdfHeight),
                    font = font,
                    visible = true,
                    color = unit.textColor
                )
            }
        }
    }

    private fun PdfMappedRect.padded(padding: Float, pageWidth: Float, pageHeight: Float): PdfMappedRect {
        val newX = (x - padding).coerceIn(0f, pageWidth)
        val newY = (y - padding).coerceIn(0f, pageHeight)
        val right = (x + width + padding).coerceIn(0f, pageWidth)
        val top = (y + height + padding).coerceIn(0f, pageHeight)
        return PdfMappedRect(
            x = newX,
            y = newY,
            width = (right - newX).coerceAtLeast(1f),
            height = (top - newY).coerceAtLeast(1f)
        )
    }

    private fun drawTextInRect(
        stream: PDPageContentStream,
        text: String,
        rect: PdfMappedRect,
        font: PDFont,
        visible: Boolean,
        color: Int
    ) {
        val allowUnicode = font !is PDType1Font
        val sanitized = sanitizePdfSearchText(text, allowUnicode = allowUnicode)
        if (sanitized.isBlank() || rect.width <= 0f || rect.height <= 0f) return
        val maxFontSize = (rect.height * 0.75f).coerceIn(4f, 18f)
        val fontSize = findFontSizeForRect(sanitized, font, rect, maxFontSize)
        val lines = wrapForPdfTextLayer(sanitized, rect.width, fontSize, font)
        if (lines.isEmpty()) return
        val leading = fontSize * 1.12f
        val maxLines = floor((rect.height / leading).toDouble()).toInt().coerceAtLeast(1)
        val visibleLines = lines.take(maxLines)

        val totalTextHeight = visibleLines.size * leading
        val firstBaseline = rect.y + rect.height - ((rect.height - totalTextHeight).coerceAtLeast(0f) / 2f) - fontSize
        visibleLines.forEachIndexed { index, line ->
            val safeLine = sanitizeLineForFont(line, font)
            val lineWidth = runCatching { font.getStringWidth(safeLine) / 1000f * fontSize }.getOrDefault(rect.width)
            val lineX = rect.x + ((rect.width - lineWidth).coerceAtLeast(0f) / 2f)
            val lineY = firstBaseline - index * leading
            stream.beginText()
            stream.setFont(font, fontSize)
            stream.setRenderingMode(if (visible) RenderingMode.FILL else RenderingMode.NEITHER)
            if (visible) {
                stream.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
            }
            stream.newLineAtOffset(lineX, lineY)
            stream.showText(safeLine)
            stream.endText()
        }
    }

    private fun sanitizeLineForFont(line: String, font: PDFont): String {
        if (font !is PDType1Font) return line
        return line.map { char ->
            if (char.code in 32..126 || char.code in 160..255) char else '?'
        }.joinToString("")
    }

    private fun findFontSizeForRect(text: String, font: PDFont, rect: PdfMappedRect, maxFontSize: Float): Float {
        var size = maxFontSize
        while (size > 4f) {
            val lines = wrapForPdfTextLayer(text, rect.width, size, font)
            val leading = size * 1.12f
            if (lines.isNotEmpty() && lines.size * leading <= rect.height) {
                return size
            }
            size -= 0.5f
        }
        return 4f
    }

    private fun wrapForPdfTextLayer(
        text: String,
        maxWidth: Float,
        fontSize: Float,
        font: PDFont = PDType1Font.HELVETICA
    ): List<String> {
        val maxLineWidth = maxWidth.coerceAtLeast(72f)
        val lines = mutableListOf<String>()

        text.lines().forEach { rawLine ->
            val words = sanitizePdfSearchText(rawLine, allowUnicode = font !is PDType1Font)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            if (words.isEmpty()) {
                return@forEach
            }

            var current = ""
            words.forEach { word ->
                val candidate = if (current.isBlank()) word else "$current $word"
                val candidateWidth = runCatching { font.getStringWidth(candidate) / 1000f * fontSize }.getOrDefault(maxLineWidth + 1f)
                if (candidateWidth <= maxLineWidth) {
                    current = candidate
                } else {
                    if (current.isNotBlank()) lines.add(current)
                    current = word
                }
            }
            if (current.isNotBlank()) lines.add(current)
        }

        return lines
    }

    private fun loadTranslationFont(doc: PDDocument): PdfFontSelection {
        return loadTranslationFont(doc, emptySequence())
    }

    private fun loadTranslationFont(
        doc: PDDocument,
        sampleTexts: Sequence<String>
    ): PdfFontSelection {
        val cleanedSamples = cleanedTranslationFontSamples(sampleTexts)
        pdfTranslationFontCandidatePaths().forEach { path ->
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val loaded = runCatching { PDType0Font.load(doc, file) }
                    .onFailure { DebugLog.log("[PDF] Could not load translation font $path: ${it.message}") }
                    .getOrNull()
                if (loaded != null) {
                    if (cleanedSamples.isEmpty() || cleanedSamples.all { pdfFontSupportsText(loaded, it) }) {
                        DebugLog.log("[PDF] Using system PDF export font $path")
                        return PdfFontSelection(
                            font = loaded,
                            sourceLabel = path,
                            usesBundledFallback = false
                        )
                    }
                }
            }
        }

        val bundledFont = runCatching {
            context.assets.open(BUNDLED_JAPANESE_FONT_ASSET).use { input ->
                PDType0Font.load(doc, input, true)
            }
        }.onFailure {
            DebugLog.log("[PDF] Could not load bundled PDF export font $BUNDLED_JAPANESE_FONT_LABEL: ${it.message}")
        }.getOrNull()

        if (bundledFont != null && (cleanedSamples.isEmpty() || cleanedSamples.all { pdfFontSupportsText(bundledFont, it) })) {
            DebugLog.log("[PDF] Using bundled Japanese PDF export font fallback $BUNDLED_JAPANESE_FONT_LABEL")
            return PdfFontSelection(
                font = bundledFont,
                sourceLabel = BUNDLED_JAPANESE_FONT_LABEL,
                usesBundledFallback = true
            )
        }

        throw PDFTranslationDisplayException(
            displayMessage = context.getString(R.string.pdf_translation_error_export_failed),
            displayDetails = context.getString(
                R.string.pdf_translation_error_missing_font_detail,
                BUNDLED_JAPANESE_FONT_LABEL
            )
        )
    }

    private fun pdfTranslationFontCandidatePaths(): List<String> = listOf(
        "/system/fonts/NotoSansCJK-Regular.otf",
        "/system/fonts/NotoSansCJKjp-Regular.otf",
        "/system/fonts/NotoSansJP-Regular.otf",
        "/system/fonts/NotoSansJP-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf"
    ).distinct()

    private fun bitmapTranslationTypefaceCandidatePaths(): List<String> = listOf(
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansCJK-Regular.otf",
        "/system/fonts/NotoSansCJKjp-Regular.otf",
        "/system/fonts/NotoSansJP-Regular.otf",
        "/system/fonts/NotoSansJP-Regular.ttf",
        "/system/fonts/NotoSansCJKkr-Regular.otf",
        "/system/fonts/NotoSansKR-Regular.otf",
        "/system/fonts/NotoSansCJKsc-Regular.otf",
        "/system/fonts/NotoSansSC-Regular.otf",
        "/system/fonts/NotoSansCJKtc-Regular.otf",
        "/system/fonts/NotoSansTC-Regular.otf",
        "/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf"
    ).distinct()

    private fun cleanedTranslationFontSamples(sampleTexts: Sequence<String>): List<String> {
        return sampleTexts
            .map { sanitizePdfSearchText(it, allowUnicode = true) }
            .filter { it.isNotBlank() }
            .take(48)
            .toList()
    }

    private fun pdfFontSupportsText(font: PDFont, text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) continue
            val glyph = String(Character.toChars(codePoint))
            val supported = runCatching {
                font.getStringWidth(glyph)
            }.isSuccess
            if (!supported) return false
        }
        return true
    }

    private fun loadBitmapTranslationTypeface(sampleTexts: Sequence<String>): BitmapTypefaceSelection {
        val defaultTypeface = Typeface.DEFAULT
        val cleanedSamples = cleanedTranslationFontSamples(sampleTexts)
        if (cleanedSamples.isEmpty() || cleanedSamples.all { typefaceSupportsText(defaultTypeface, it) }) {
            DebugLog.log("[PDF] Using default bitmap export typeface")
            return BitmapTypefaceSelection(
                typeface = defaultTypeface,
                sourceLabel = "default",
                usesBundledFallback = false
            )
        }
        bitmapTranslationTypefaceCandidatePaths().forEach { path ->
            val file = File(path)
            if (!file.exists() || !file.canRead()) return@forEach
            val typeface = runCatching { Typeface.createFromFile(file) }
                .onFailure { DebugLog.log("[PDF] Could not load bitmap translation typeface $path: ${it.message}") }
                .getOrNull()
                ?: return@forEach
            if (cleanedSamples.all { typefaceSupportsText(typeface, it) }) {
                DebugLog.log("[PDF] Using system bitmap export typeface $path")
                return BitmapTypefaceSelection(
                    typeface = typeface,
                    sourceLabel = path,
                    usesBundledFallback = false
                )
            }
        }

        val bundledTypeface = runCatching {
            Typeface.createFromAsset(context.assets, BUNDLED_JAPANESE_FONT_ASSET)
        }.onFailure {
            DebugLog.log("[PDF] Could not load bundled bitmap export typeface $BUNDLED_JAPANESE_FONT_LABEL: ${it.message}")
        }.getOrNull()
        if (bundledTypeface != null && (cleanedSamples.isEmpty() || cleanedSamples.all { typefaceSupportsText(bundledTypeface, it) })) {
            DebugLog.log("[PDF] Using bundled Japanese bitmap export font fallback $BUNDLED_JAPANESE_FONT_LABEL")
            return BitmapTypefaceSelection(
                typeface = bundledTypeface,
                sourceLabel = BUNDLED_JAPANESE_FONT_LABEL,
                usesBundledFallback = true
            )
        }

        DebugLog.log(
            "[PDF] Bitmap export font coverage incomplete; using Android fallback text stack " +
                "after trying bundled fallback $BUNDLED_JAPANESE_FONT_LABEL"
        )
        return BitmapTypefaceSelection(
            typeface = defaultTypeface,
            sourceLabel = "android-fallback",
            usesBundledFallback = false
        )
    }

    private fun typefaceSupportsText(typeface: Typeface, text: String): Boolean {
        val probe = android.graphics.Paint().apply { this.typeface = typeface }
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) continue
            val glyph = String(Character.toChars(codePoint))
            if (!probe.hasGlyph(glyph)) return false
        }
        return true
    }

    private fun sanitizePdfSearchText(text: String, allowUnicode: Boolean = false): String {
        return text
            .map { char ->
                when {
                    char == '\n' || char == '\t' -> ' '
                    char.code in 32..126 || char.code in 160..255 -> char
                    allowUnicode && !char.isISOControl() -> char
                    else -> ' '
                }
            }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun computeOcrScale(width: Int, height: Int): Float {
        val longSide = maxOf(width, height).coerceAtLeast(1)
        return (2000f / longSide.toFloat()).coerceIn(1.5f, 2.5f)
    }

    private fun computeComicImageOcrScale(width: Int, height: Int): Float {
        val longSide = maxOf(width, height).coerceAtLeast(1)
        return (3200f / longSide.toFloat()).coerceIn(0.6f, 2.5f)
    }

    private suspend fun recognizeText(recognizer: TextRecognizer, image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val extractedText = result.textBlocks.joinToString("\n\n") { block ->
                        block.lines.joinToString("\n") { it.text }
                    }
                    continuation.resume(extractedText)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }
    
    /**
     * Save PDF document to output folder
     */
    private suspend fun saveToOutputFolder(doc: PDDocument, filename: String): Uri {
        val outputFolderUri = settingsRepo.outputFolderUri.value
        
        return if (outputFolderUri != null) {
            // Save to user-selected folder
            val folderUri = Uri.parse(outputFolderUri)
            val folder = DocumentFile.fromTreeUri(context, folderUri)
            
            // Create pdfs subfolder
            val pdfFolder = folder?.findFile("pdfs") ?: folder?.createDirectory("pdfs")
            val outputFile = pdfFolder?.createFile("application/pdf", filename)
            
            outputFile?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                    doc.save(outputStream)
                }
                file.uri
            } ?: run {
                // Fallback to cache
                saveToCacheAndGetUri(doc, filename)
            }
        } else {
            // Save to app cache
            saveToCacheAndGetUri(doc, filename)
        }
    }
    
    private fun saveToCacheAndGetUri(doc: PDDocument, filename: String): Uri {
        val pdfDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val outputFile = File(pdfDir, filename)
        FileOutputStream(outputFile).use { fos ->
            doc.save(fos)
        }
        return Uri.fromFile(outputFile)
    }
    
    /**
     * Convert images to PDF
     */
    suspend fun imagesToPdf(imageUris: List<Uri>): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Converting ${imageUris.size} images to PDF")
            
            val doc = PDDocument()
            
            for (uri in imageUris) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            // Create page with image dimensions
                            val pageWidth = 595f  // A4 width in points
                            val pageHeight = 842f // A4 height in points
                            
                            val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
                            doc.addPage(page)
                            
                            // Scale image to fit page
                            val imgWidth = bitmap.width.toFloat()
                            val imgHeight = bitmap.height.toFloat()
                            val scale = minOf(pageWidth / imgWidth, pageHeight / imgHeight) * 0.9f
                            val scaledWidth = imgWidth * scale
                            val scaledHeight = imgHeight * scale
                            
                            // Center image on page
                            val x = (pageWidth - scaledWidth) / 2
                            val y = (pageHeight - scaledHeight) / 2
                            
                            // Convert bitmap to JPEG bytes
                            val baos = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
                            val imageBytes = baos.toByteArray()
                            
                            // Create PDImageXObject
                            val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromByteArray(doc, imageBytes)
                            
                            // Draw image on page
                            val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
                            contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight)
                            contentStream.close()
                            
                            bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.log("[PDF] Error processing image: ${e.message}")
                }
            }
            
            if (doc.numberOfPages == 0) {
                doc.close()
                return@withContext Result.failure(Exception("No valid images to convert"))
            }
            
            val outputUri = saveToOutputFolder(doc, "images_${System.currentTimeMillis()}.pdf")
            doc.close()
            
            DebugLog.log("[PDF] Images to PDF complete: $outputUri")
            Result.success(outputUri)
            
        } catch (e: Exception) {
            DebugLog.log("[PDF] Images to PDF failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun translateMangaCbzBatch(
        cbzUris: List<Uri>,
        exportPdf: Boolean = true,
        exportCbz: Boolean = true,
        settingsOverride: RemoteSummarySettingsSnapshot? = null,
        executionController: PDFTranslationExecutionController? = null,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)? = null
    ): Result<List<MangaTranslationFileResult>> = withContext(Dispatchers.IO) {
        if (!exportPdf && !exportCbz) {
            return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.workflow_manga_select_output_first)))
        }
        val notificationId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.PDF_TRANSLATION,
            context.getString(R.string.workflow_manga_notification_title)
        )
        val results = mutableListOf<MangaTranslationFileResult>()
        try {
            cbzUris.forEachIndexed { fileIndex, cbzUri ->
                ensureTranslationActive(executionController)
                val sourceName = displayNameForUri(cbzUri)
                UnifiedNotificationManager.updateProgress(
                    notificationId,
                    fileIndex.toFloat() / cbzUris.size.toFloat(),
                    context.getString(R.string.workflow_manga_notification_file, fileIndex + 1, cbzUris.size, sourceName)
                )
                val result = runCatching {
                    translateSingleCbz(
                        cbzUri = cbzUri,
                        sourceName = sourceName,
                        exportPdf = exportPdf,
                        exportCbz = exportCbz,
                        settingsOverride = settingsOverride,
                        executionController = executionController,
                        notificationId = notificationId,
                        onProgress = onProgress
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) {
                        throw PDFTranslationCancelledException(mangaResults = results.toList())
                    }
                    DebugLog.log("[PDF] CBZ translation failed for $sourceName: ${error.message}")
                    val display = classifyTranslationDisplayError(error)
                    MangaTranslationFileResult(
                        sourceName = sourceName,
                        errorMessage = display.message,
                        errorDetails = display.details
                    )
                }
                results += result
            }

            val failed = results.count { !it.isSuccess }
            if (failed == 0) {
                UnifiedNotificationManager.completeTask(
                    notificationId,
                    context.getString(R.string.workflow_manga_notification_complete, results.size)
                )
            } else {
                UnifiedNotificationManager.completeTask(
                    notificationId,
                    context.getString(R.string.workflow_manga_notification_partial, results.size - failed, failed)
                )
            }
            Result.success(results)
        } catch (e: PDFTranslationCancelledException) {
            UnifiedNotificationManager.dismissTask(notificationId)
            Result.failure(e)
        } catch (e: CancellationException) {
            UnifiedNotificationManager.dismissTask(notificationId)
            Result.failure(e)
        } catch (e: Exception) {
            UnifiedNotificationManager.failTask(notificationId, classifyTranslationDisplayError(e).message)
            Result.failure(e)
        }
    }

    private suspend fun translateSingleCbz(
        cbzUri: Uri,
        sourceName: String,
        exportPdf: Boolean,
        exportCbz: Boolean,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        notificationId: Int,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): MangaTranslationFileResult {
        val timestamp = System.currentTimeMillis()
        val baseName = sourceName.substringBeforeLast('.').replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "comic" }
        val workDir = File(context.cacheDir, "manga_translate_${baseName}_$timestamp").apply { mkdirs() }
        try {
            ensureTranslationActive(executionController)
            onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.EXTRACTING, 0, 1, 0, 0))
            val imageFiles = extractCbzImages(cbzUri, workDir)
            if (imageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.pdf_error_no_image_pages_in_cbz))
            DebugLog.log("[PDF] Extracted ${imageFiles.size} comic pages from $sourceName")

            onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.PDF_CREATION, 0, imageFiles.size, 0, 0))
            val intermediatePdf = File(workDir, "${baseName}_source.pdf")
            createPdfFromImageFiles(imageFiles, intermediatePdf)

            val pdfName = "translated_${baseName}_$timestamp.pdf"
            val cbzName = "translated_${baseName}_$timestamp.cbz"
            val translatedPdfFile = File(workDir, pdfName)
            val intermediatePdfUri = Uri.fromFile(intermediatePdf)
            val ocrResult = collectImageOcrText(imageFiles) { progress ->
                onProgress?.invoke(
                    PdfOcrTranslationProgress(
                        stage = PdfOcrTranslationStage.OCR,
                        processedPages = progress.processedPages,
                        totalPages = progress.totalPages,
                        translatedBlocks = 0,
                        totalBlocks = 0
                    )
                )
            }.getOrThrow()
            DebugLog.log(
                "[PDF] Comic OCR produced ${ocrResult.blocks.count { it.text.isNotBlank() }} blocks " +
                    "across ${ocrResult.totalPages} pages for $sourceName"
            )
            val prepared = prepareOcrTranslation(
                sourcePdfUri = intermediatePdfUri,
                ocrResult = ocrResult,
                settingsOverride = settingsOverride,
                executionController = executionController,
                onProgress = onProgress,
                notificationId = notificationId
            )

            val renderedPages = if (exportPdf || exportCbz) {
                ensureTranslationActive(executionController)
                onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.RENDERING, 0, imageFiles.size, 0, 0))
                renderTranslatedComicPagesToPng(
                    imageFiles = imageFiles,
                    ocrResult = ocrResult,
                    prepared = prepared,
                    workDir = workDir,
                    baseName = baseName,
                    executionController = executionController,
                    onProgress = onProgress
                ).also { pages ->
                    if (pages.isEmpty()) throw IllegalStateException(context.getString(R.string.workflow_manga_error_no_rendered_pages))
                }
            } else {
                emptyList()
            }

            if (exportPdf) {
                ensureTranslationActive(executionController)
                onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.PDF_CREATION, renderedPages.size, renderedPages.size, 0, 0))
                DebugLog.log("[PDF] Creating translated manga PDF from rasterized translated pages for $sourceName")
                createPdfFromImageFiles(renderedPages, translatedPdfFile)
            }

            val savedPdfUri = if (exportPdf) {
                saveFileToOutputFolder(translatedPdfFile, "pdfs", "application/pdf", pdfName)
            } else {
                null
            }

            val savedCbzUri = if (exportCbz) {
                ensureTranslationActive(executionController)
                onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.PACKING, renderedPages.size, renderedPages.size, 0, 0))
                val cbzFile = File(workDir, cbzName)
                packPngPagesAsCbz(renderedPages, cbzFile)
                saveFileToOutputFolder(cbzFile, "comics", "application/vnd.comicbook+zip", cbzName)
            } else {
                null
            }

            return MangaTranslationFileResult(sourceName = sourceName, pdfUri = savedPdfUri, cbzUri = savedCbzUri)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun extractCbzImages(cbzUri: Uri, workDir: File): List<File> {
        val imageDir = File(workDir, "pages").apply { mkdirs() }
        val cbzFile = File(workDir, "source.cbz")
        context.contentResolver.openInputStream(cbzUri)?.use { input ->
            FileOutputStream(cbzFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException(context.getString(R.string.pdf_error_could_not_open_cbz))

        try {
            ZipFile(cbzFile).use { zip ->
                val imageEntries = zip.entries()
                    .asSequence()
                    .filter { entry ->
                        !entry.isDirectory &&
                            isSafeZipEntry(entry.name) &&
                            isSupportedComicImage(entry.name)
                    }
                    .sortedWith(compareBy { PDFTranslationLogic.naturalSortKey(it.name).joinToString("\u0000") })
                    .toList()

                return imageEntries.mapIndexed { index, entry ->
                    val extension = entry.name.substringAfterLast('.', "png").lowercase()
                    val output = File(imageDir, "${(index + 1).toString().padStart(4, '0')}.$extension")
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { out -> input.copyTo(out) }
                    }
                    output
                }
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] Could not open CBZ as ZIP: ${e.message}")
            throw IllegalStateException(context.getString(R.string.pdf_error_could_not_open_cbz), e)
        }
    }

    private fun isSupportedComicImage(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("__macosx/") || lower.endsWith("/.ds_store")) return false
        return lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".jpe") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".avif") ||
            lower.endsWith(".heic") ||
            lower.endsWith(".heif") ||
            lower.endsWith(".bmp") ||
            lower.endsWith(".gif")
    }

    private fun isSafeZipEntry(name: String): Boolean {
        return !name.contains("..") && !name.startsWith("/") && !name.startsWith("\\")
    }

    private fun createPdfFromImageFiles(imageFiles: List<File>, outputFile: File) {
        val doc = PDDocument()
        try {
            imageFiles.forEach { file ->
                val dimensions = decodeImageDimensions(file)
                    ?: throw IllegalStateException("Unsupported image page: ${file.name}")
                val pageWidth = dimensions.first.toFloat().coerceAtLeast(1f)
                val pageHeight = dimensions.second.toFloat().coerceAtLeast(1f)
                val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
                doc.addPage(page)
                val image = if (isJpegFile(file)) {
                    runCatching {
                        file.inputStream().use { input ->
                            com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(doc, input)
                        }
                    }.getOrElse {
                        createPdfImageFromDecodedBitmap(doc, file)
                    }
                } else {
                    createPdfImageFromDecodedBitmap(doc, file)
                }
                PDPageContentStream(doc, page).use { stream ->
                    stream.drawImage(image, 0f, 0f, pageWidth, pageHeight)
                }
            }
            if (doc.numberOfPages == 0) throw IllegalStateException(context.getString(R.string.pdf_error_no_valid_images_to_convert))
            FileOutputStream(outputFile).use { doc.save(it) }
        } finally {
            doc.close()
        }
    }

    private fun createPdfImageFromDecodedBitmap(
        doc: PDDocument,
        file: File
    ): com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Could not decode image page: ${file.name}")
        try {
            return com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeImageDimensions(file: File): Pair<Int, Int>? {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        val decodedBounds = if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
        return decodedBounds ?: if (isJpegFile(file)) readJpegDimensions(file) else null
    }

    private fun isJpegFile(file: File): Boolean {
        val lower = file.name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".jpe")
    }

    private fun readJpegDimensions(file: File): Pair<Int, Int>? {
        fun readU16(input: java.io.InputStream): Int {
            val high = input.read()
            val low = input.read()
            return if (high == -1 || low == -1) -1 else (high shl 8) + low
        }

        fun skipFully(input: java.io.InputStream, byteCount: Int): Boolean {
            var remaining = byteCount
            while (remaining > 0) {
                val skipped = input.skip(remaining.toLong()).toInt()
                if (skipped <= 0) {
                    if (input.read() == -1) return false
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
            return true
        }

        val sofMarkers = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
        try {
            file.inputStream().use { input ->
                if (input.read() != 0xFF || input.read() != 0xD8) return null
                while (true) {
                    var prefix = input.read()
                    while (prefix != -1 && prefix != 0xFF) prefix = input.read()
                    if (prefix == -1) return null
                    var marker = input.read()
                    while (marker == 0xFF) marker = input.read()
                    if (marker == -1 || marker == 0xD9 || marker == 0xDA) return null
                    val length = readU16(input)
                    if (length < 2) return null
                    if (marker in sofMarkers) {
                        if (input.read() == -1) return null
                        val height = readU16(input)
                        val width = readU16(input)
                        return if (width > 0 && height > 0) width to height else null
                    }
                    if (!skipFully(input, length - 2)) return null
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun renderTranslatedComicPagesToPng(
        imageFiles: List<File>,
        ocrResult: PdfOcrDocumentResult,
        prepared: PreparedOcrTranslation,
        workDir: File,
        baseName: String,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): List<File> {
        val pngDir = File(workDir, "translated_pages").apply { mkdirs() }
        val pagesByIndex = ocrResult.pages.associateBy { it.pageIndex }
        val typefaceSelection = loadBitmapTranslationTypeface(prepared.translations.values.asSequence())
        val typeface = typefaceSelection.typeface
        return imageFiles.mapIndexed { pageIndex, imageFile ->
            ensureTranslationActive(executionController)
            val output = File(pngDir, "${baseName}_${(pageIndex + 1).toString().padStart(4, '0')}.png")
            try {
                val bitmap = decodeComicPageForEditing(imageFile)
                try {
                    val canvas = Canvas(bitmap)
                    val page = pagesByIndex[pageIndex]
                    val units = prepared.pageUnits[pageIndex].orEmpty()
                    if (page != null && units.isNotEmpty()) {
                        units.forEach { unit ->
                            val translated = prepared.translations[unit.id]?.trim().orEmpty()
                            if (translated.isBlank()) {
                                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_blank_unit, unit.id, unit.pageIndex + 1))
                                return@forEach
                            }
                            val rect = unit.rect.toBitmapRect(
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                pdfWidth = page.pdfWidth,
                                pdfHeight = page.pdfHeight
                            ).expanded(
                                padding = maxOf(4f, minOf(bitmap.width, bitmap.height) * 0.006f),
                                maxWidth = bitmap.width.toFloat(),
                                maxHeight = bitmap.height.toFloat()
                            )
                            val clipped = drawTranslatedTextOnBitmap(
                                canvas = canvas,
                                text = translated,
                                rect = rect,
                                backgroundColor = unit.backgroundColor,
                                textColor = unit.textColor,
                                typeface = typeface
                            )
                            if (clipped) {
                                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_clipped_unit, unit.id, unit.pageIndex + 1))
                            }
                        }
                    }
                    writeBitmapPng(bitmap, output)
                } finally {
                    bitmap.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.log(
                    "[PDF] CBZ page ${pageIndex + 1} overlay render failed with font " +
                        "${typefaceSelection.sourceLabel}; preserving original page in output: ${error.message}"
                )
                writeOriginalComicPageFallback(
                    imageFile = imageFile,
                    output = output,
                    pageNumber = pageIndex + 1
                )
            }
            onProgress?.invoke(
                PdfOcrTranslationProgress(
                    stage = PdfOcrTranslationStage.RENDERING,
                    processedPages = pageIndex + 1,
                    totalPages = imageFiles.size,
                    translatedBlocks = prepared.totalSourceBlocks,
                    totalBlocks = prepared.totalSourceBlocks
                )
            )
            output
        }
    }

    private suspend fun renderTranslatedPdfPagesToPng(
        sourcePdf: File,
        ocrResult: PdfOcrDocumentResult,
        prepared: PreparedOcrTranslation,
        workDir: File,
        baseName: String,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): List<File> {
        val pngDir = File(workDir, "translated_pdf_pages").apply { mkdirs() }
        val pagesByIndex = ocrResult.pages.associateBy { it.pageIndex }
        val typefaceSelection = loadBitmapTranslationTypeface(prepared.translations.values.asSequence())
        val typeface = typefaceSelection.typeface
        val pfd = ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            return (0 until renderer.pageCount).map { pageIndex ->
                ensureTranslationActive(executionController)
                val pdfPage = renderer.openPage(pageIndex)
                try {
                    val bitmap = Bitmap.createBitmap(pdfPage.width, pdfPage.height, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        drawTranslatedUnitsOnBitmap(
                            bitmap = bitmap,
                            page = pagesByIndex[pageIndex],
                            units = prepared.pageUnits[pageIndex].orEmpty(),
                            translations = prepared.translations,
                            typeface = typeface
                        )
                        val output = File(pngDir, "${baseName}_${(pageIndex + 1).toString().padStart(4, '0')}.png")
                        FileOutputStream(output).use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        onProgress?.invoke(
                            PdfOcrTranslationProgress(
                                stage = PdfOcrTranslationStage.WRITING,
                                processedPages = pageIndex + 1,
                                totalPages = renderer.pageCount,
                                translatedBlocks = prepared.totalSourceBlocks,
                                totalBlocks = prepared.totalSourceBlocks
                            )
                        )
                        output
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DebugLog.log(
                            "[PDF] Raster PDF fallback failed while rendering page ${pageIndex + 1} " +
                                "with font ${typefaceSelection.sourceLabel}: ${error.message}"
                        )
                        throw createExportStageFailure(
                            cause = error,
                            pageNumber = pageIndex + 1,
                            fontSourceLabel = typefaceSelection.sourceLabel
                        )
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    pdfPage.close()
                }
            }
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    private suspend fun renderTranslatedTextLayerPdfPagesToPng(
        sourcePdf: File,
        textLayerResult: PdfTextLayerDocumentResult,
        pageUnits: Map<Int, List<PdfTranslationUnit>>,
        translations: Map<String, String>,
        totalSourceBlocks: Int,
        workDir: File,
        baseName: String,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): List<File> {
        val pngDir = File(workDir, "translated_text_layer_pages").apply { mkdirs() }
        val pagesByIndex = textLayerResult.pages.associateBy { it.pageIndex }
        val typefaceSelection = loadBitmapTranslationTypeface(translations.values.asSequence())
        val typeface = typefaceSelection.typeface
        val pfd = ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            return (0 until renderer.pageCount).map { pageIndex ->
                ensureTranslationActive(executionController)
                val pdfPage = renderer.openPage(pageIndex)
                try {
                    val bitmap = Bitmap.createBitmap(pdfPage.width, pdfPage.height, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pagesByIndex[pageIndex]?.let { page ->
                            drawTranslatedUnitsOnBitmap(
                                bitmap = bitmap,
                                pdfWidth = page.pdfWidth,
                                pdfHeight = page.pdfHeight,
                                units = pageUnits[pageIndex].orEmpty(),
                                translations = translations,
                                typeface = typeface
                            )
                        }
                        val output = File(pngDir, "${baseName}_${(pageIndex + 1).toString().padStart(4, '0')}.png")
                        FileOutputStream(output).use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        onProgress?.invoke(
                            PdfOcrTranslationProgress(
                                stage = PdfOcrTranslationStage.WRITING,
                                processedPages = pageIndex + 1,
                                totalPages = renderer.pageCount,
                                translatedBlocks = totalSourceBlocks,
                                totalBlocks = totalSourceBlocks
                            )
                        )
                        output
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DebugLog.log(
                            "[PDF] Text-layer raster fallback failed while rendering page ${pageIndex + 1} " +
                                "with font ${typefaceSelection.sourceLabel}: ${error.message}"
                        )
                        throw createExportStageFailure(
                            cause = error,
                            pageNumber = pageIndex + 1,
                            fontSourceLabel = typefaceSelection.sourceLabel
                        )
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    pdfPage.close()
                }
            }
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    private fun drawTranslatedUnitsOnBitmap(
        bitmap: Bitmap,
        page: PdfOcrPageResult?,
        units: List<PdfTranslationUnit>,
        translations: Map<String, String>,
        typeface: Typeface
    ) {
        if (page == null || units.isEmpty()) return
        drawTranslatedUnitsOnBitmap(
            bitmap = bitmap,
            pdfWidth = page.pdfWidth,
            pdfHeight = page.pdfHeight,
            units = units,
            translations = translations,
            typeface = typeface
        )
    }

    private fun drawTranslatedUnitsOnBitmap(
        bitmap: Bitmap,
        pdfWidth: Float,
        pdfHeight: Float,
        units: List<PdfTranslationUnit>,
        translations: Map<String, String>,
        typeface: Typeface
    ) {
        if (units.isEmpty()) return
        val canvas = Canvas(bitmap)
        units.forEach { unit ->
            val translated = translations[unit.id]?.trim().orEmpty()
            if (translated.isBlank()) {
                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_blank_unit, unit.id, unit.pageIndex + 1))
                return@forEach
            }
            val rect = unit.rect.toBitmapRect(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight
            ).expanded(
                padding = maxOf(4f, minOf(bitmap.width, bitmap.height) * 0.006f),
                maxWidth = bitmap.width.toFloat(),
                maxHeight = bitmap.height.toFloat()
            )
            val clipped = drawTranslatedTextOnBitmap(
                canvas = canvas,
                text = translated,
                rect = rect,
                backgroundColor = unit.backgroundColor,
                textColor = unit.textColor,
                typeface = typeface
            )
            if (clipped) {
                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_clipped_unit, unit.id, unit.pageIndex + 1))
            }
        }
    }

    private fun decodeComicPageForEditing(imageFile: File): Bitmap {
        val decoded = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IllegalStateException("Could not decode image page: ${imageFile.name}")
        val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }
        decoded.recycle()
        return flattened
    }

    private fun writeOriginalComicPageFallback(
        imageFile: File,
        output: File,
        pageNumber: Int
    ) {
        val bitmap = runCatching { decodeComicPageForEditing(imageFile) }
            .onFailure {
                DebugLog.log(
                    "[PDF] Could not decode original comic page $pageNumber for fallback; " +
                        "writing blank placeholder: ${it.message}"
                )
            }
            .getOrElse { createBlankComicFallbackBitmap(imageFile, pageNumber) }
        try {
            writeBitmapPng(bitmap, output)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createBlankComicFallbackBitmap(
        imageFile: File,
        pageNumber: Int
    ): Bitmap {
        val dimensions = decodeImageDimensions(imageFile)
        val width = dimensions?.first?.coerceAtLeast(1) ?: 1080
        val height = dimensions?.second?.coerceAtLeast(1) ?: 1600
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.DEFAULT
            }
            val message = context.getString(R.string.workflow_manga_page_placeholder, pageNumber)
            val layout = buildBitmapTextLayout(
                text = message,
                paint = paint,
                width = (width - 80).coerceAtLeast(1)
            )
            canvas.save()
            canvas.translate(40f, 40f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun writeBitmapPng(bitmap: Bitmap, output: File) {
        FileOutputStream(output).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IllegalStateException("Could not encode translated page PNG: ${output.name}")
            }
        }
    }

    private fun PdfMappedRect.toBitmapRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): RectF {
        val left = x / pdfWidth.coerceAtLeast(1f) * bitmapWidth.toFloat()
        val right = (x + width) / pdfWidth.coerceAtLeast(1f) * bitmapWidth.toFloat()
        val top = (pdfHeight - y - height) / pdfHeight.coerceAtLeast(1f) * bitmapHeight.toFloat()
        val bottom = (pdfHeight - y) / pdfHeight.coerceAtLeast(1f) * bitmapHeight.toFloat()
        return RectF(
            left.coerceIn(0f, bitmapWidth.toFloat()),
            top.coerceIn(0f, bitmapHeight.toFloat()),
            right.coerceIn(0f, bitmapWidth.toFloat()),
            bottom.coerceIn(0f, bitmapHeight.toFloat())
        )
    }

    private fun RectF.expanded(padding: Float, maxWidth: Float, maxHeight: Float): RectF {
        return RectF(
            (left - padding).coerceAtLeast(0f),
            (top - padding).coerceAtLeast(0f),
            (right + padding).coerceAtMost(maxWidth),
            (bottom + padding).coerceAtMost(maxHeight)
        )
    }

    private fun drawTranslatedTextOnBitmap(
        canvas: Canvas,
        text: String,
        rect: RectF,
        backgroundColor: Int,
        textColor: Int,
        typeface: Typeface
    ): Boolean {
        if (rect.width() < 4f || rect.height() < 4f || text.isBlank()) return false
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = backgroundColor
        }
        canvas.drawRect(rect, backgroundPaint)

        val inset = maxOf(2f, minOf(rect.width(), rect.height()) * 0.06f)
        val textRect = RectF(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            rect.bottom - inset
        )
        if (textRect.width() < 4f || textRect.height() < 4f) return true

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.LEFT
            this.typeface = typeface
        }
        val fontSize = findBitmapTextSize(text, textPaint, textRect.width(), textRect.height())
        textPaint.textSize = fontSize
        val layout = buildBitmapTextLayout(text, textPaint, textRect.width().roundToInt().coerceAtLeast(1))
        val clipped = layout.height > textRect.height()
        canvas.save()
        canvas.clipRect(textRect)
        val topOffset = ((textRect.height() - layout.height).coerceAtLeast(0f) / 2f)
        canvas.translate(textRect.left, textRect.top + topOffset)
        layout.draw(canvas)
        canvas.restore()
        return clipped
    }

    private fun findBitmapTextSize(text: String, paint: TextPaint, maxWidth: Float, maxHeight: Float): Float {
        var size = minOf(maxHeight * 0.42f, 42f).coerceAtLeast(6f)
        while (size > 6f) {
            paint.textSize = size
            val layout = buildBitmapTextLayout(text, paint, maxWidth.roundToInt().coerceAtLeast(1))
            if (layout.height <= maxHeight) return size
            size -= 0.75f
        }
        return 6f
    }

    private fun buildBitmapTextLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.96f)
            .setIncludePad(false)
            .build()
    }

    private fun renderPdfPagesToPng(pdfUri: Uri, workDir: File, baseName: String): List<File> {
        val cachedPdf = copyPdfToCache(pdfUri)
        val pngDir = File(workDir, "translated_pages").apply { mkdirs() }
        try {
            val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                return (0 until renderer.pageCount).map { pageIndex ->
                    val page = renderer.openPage(pageIndex)
                    try {
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val output = File(pngDir, "${baseName}_${(pageIndex + 1).toString().padStart(4, '0')}.png")
                            FileOutputStream(output).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            }
                            output
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
                pfd.close()
            }
        } finally {
            cachedPdf.delete()
        }
    }

    private fun packPngPagesAsCbz(pages: List<File>, outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            pages.forEachIndexed { index, page ->
                zip.putNextEntry(ZipEntry("${(index + 1).toString().padStart(4, '0')}.png"))
                page.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun saveFileToOutputFolder(
        sourceFile: File,
        subfolderName: String,
        mimeType: String,
        filename: String
    ): Uri {
        val outputFolderUri = settingsRepo.outputFolderUri.value
        return if (outputFolderUri != null) {
            val folderUri = Uri.parse(outputFolderUri)
            val folder = DocumentFile.fromTreeUri(context, folderUri)
            val subfolder = folder?.findFile(subfolderName) ?: folder?.createDirectory(subfolderName)
            val outputFile = subfolder?.createFile(mimeType, filename)
            outputFile?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { output ->
                    sourceFile.inputStream().use { it.copyTo(output) }
                }
                file.uri
            } ?: saveFileToCache(sourceFile, subfolderName, filename)
        } else {
            saveFileToCache(sourceFile, subfolderName, filename)
        }
    }

    private fun saveFileToCache(sourceFile: File, subfolderName: String, filename: String): Uri {
        val dir = File(context.cacheDir, subfolderName).apply { mkdirs() }
        val output = File(dir, filename)
        sourceFile.inputStream().use { input ->
            FileOutputStream(output).use { input.copyTo(it) }
        }
        return Uri.fromFile(output)
    }

    private fun displayNameForUri(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "comic.cbz"
    }
    
    /**
     * Perform OCR on an image using ML Kit Text Recognition
     * Note: Requires ML Kit dependency: com.google.mlkit:text-recognition
     */
    suspend fun performOCR(imageUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Performing OCR on image")
            
            // Load bitmap from URI
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            } ?: return@withContext Result.failure(Exception("Could not load image"))
            
            // Use ML Kit Text Recognition
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            return@withContext suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val extractedText = result.textBlocks.joinToString("\n\n") { block ->
                            block.lines.joinToString("\n") { it.text }
                        }
                        DebugLog.log("[PDF] OCR extracted ${extractedText.length} characters")
                        bitmap.recycle()
                        recognizer.close()
                        continuation.resume(Result.success(extractedText))
                    }
                    .addOnFailureListener { e ->
                        DebugLog.log("[PDF] OCR failed: ${e.message}")
                        bitmap.recycle()
                        recognizer.close()
                        continuation.resume(Result.failure(e))
                    }
            }
            
        } catch (e: Exception) {
            DebugLog.log("[PDF] OCR error: ${e.message}")
            Result.failure(e)
        }
    }
    /**
     * Compress PDF by reducing image quality
     * @param compressionLevel 1-9 (1=best quality/least compression, 9=worst quality/most compression)
     */
    suspend fun compressPdf(pdfUri: Uri, compressionLevel: Int): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val level = compressionLevel.coerceIn(1, 9)
            // Quality: level 1 = 0.9, level 9 = 0.1
            val quality = 1.0f - (level * 0.1f)
            DebugLog.log("[PDF] Compressing PDF with level $level (quality=$quality)")
            
            // Get original file size
            val originalSize = try {
                resolveDocumentSize(pdfUri)
            } catch (_: Exception) { 0L }
            
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val doc = PDDocument.load(inputStream)
                try {
                    // Handle encrypted PDFs
                    if (doc.isEncrypted) {
                        try {
                            doc.setAllSecurityToBeRemoved(true)
                        } catch (e: Exception) {
                            DebugLog.log("[PDF] Could not remove encryption: ${e.message}")
                        }
                    }

                    var imagesCompressed = 0

                    // Iterate through pages and compress images
                    for (i in 0 until doc.numberOfPages) {
                        val page = doc.getPage(i)
                        val resources = page.resources ?: continue // Skip pages without resources

                        // Get all XObjects (includes images)
                        val xObjectNames = try {
                            resources.xObjectNames
                        } catch (_: Exception) {
                            null
                        } ?: continue

                        xObjectNames.forEach { name ->
                            try {
                                val xObject = resources.getXObject(name)
                                if (xObject is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                                    val image = xObject.image
                                    if (image != null) {
                                        val jpegImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(
                                            doc, image, quality
                                        )
                                        resources.put(name, jpegImage)
                                        imagesCompressed++
                                    }
                                }
                            } catch (_: Exception) {
                                // Some images can't be re-encoded, skip
                            }
                        }
                    }

                    DebugLog.log("[PDF] Compressed $imagesCompressed images")

                    val compressedSize = measureDocumentSize(doc)

                    DebugLog.log("[PDF] Original: ${originalSize/1024}KB, Compressed: ${compressedSize/1024}KB")

                    // Only keep if smaller
                    if (compressedSize >= originalSize && imagesCompressed > 0) {
                        DebugLog.log("[PDF] Compression would increase size - aborting")
                        return@withContext Result.failure(Exception("Compression would increase file size (${originalSize/1024}KB → ${compressedSize/1024}KB). Try a higher compression level or the PDF may not be compressible."))
                    }

                    // Move temp to output folder
                    val outputUri = saveToOutputFolder(doc, "compressed_L${level}_${System.currentTimeMillis()}.pdf")

                    val savings = if (originalSize > 0) ((originalSize - compressedSize) * 100 / originalSize) else 0
                    DebugLog.log("[PDF] Compression complete: $outputUri (saved $savings%)")
                    return@withContext Result.success(outputUri)
                } finally {
                    doc.close()
                }
            }
            
            Result.failure(Exception("Could not open PDF"))
        } catch (e: Exception) {
            DebugLog.log("[PDF] Compression failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Split PDF by max file size
     * Groups multiple pages together until max size is reached
     * @param maxSizeBytes Maximum size per output file in bytes
     * @return List of URIs for the split parts
     */
    suspend fun splitBySize(pdfUri: Uri, maxSizeBytes: Long): Result<List<Uri>> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Splitting PDF by size: ${maxSizeBytes / 1024}KB max per file")
            
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val sourceDoc = PDDocument.load(inputStream)
                try {
                    // Handle encrypted PDFs
                    if (sourceDoc.isEncrypted) {
                        try {
                            sourceDoc.setAllSecurityToBeRemoved(true)
                        } catch (_: Exception) {}
                    }

                    val totalPages = sourceDoc.numberOfPages
                    val outputUris = mutableListOf<Uri>()

                    // Collect pages into groups
                    var startPage = 0
                    var partNumber = 1

                    while (startPage < totalPages) {
                        // Binary search-style: keep adding pages until we exceed max size
                        var endPage = startPage
                        var lastGoodEnd = startPage

                        while (endPage < totalPages) {
                            val partSize = PDDocument().let { testDoc ->
                                try {
                                    importPageRange(sourceDoc, testDoc, startPage, endPage)
                                    measureDocumentSize(testDoc)
                                } finally {
                                    testDoc.close()
                                }
                            }

                            if (partSize <= maxSizeBytes) {
                                lastGoodEnd = endPage
                                endPage++
                            } else {
                                // Size exceeded - use lastGoodEnd if we have pages, else force include current
                                if (lastGoodEnd >= startPage) {
                                    break
                                } else {
                                    // Single page exceeds max - include it anyway
                                    lastGoodEnd = endPage
                                    break
                                }
                            }
                        }

                        // Handle edge case where we reached end
                        if (endPage >= totalPages) {
                            lastGoodEnd = totalPages - 1
                        }

                        val outputUri = PDDocument().let { partDoc ->
                            try {
                                importPageRange(sourceDoc, partDoc, startPage, lastGoodEnd)
                                saveToOutputFolder(partDoc, "part${partNumber}_${System.currentTimeMillis()}.pdf")
                            } finally {
                                partDoc.close()
                            }
                        }
                        outputUris.add(outputUri)

                        DebugLog.log("[PDF] Part $partNumber: pages ${startPage + 1}-${lastGoodEnd + 1}")

                        startPage = lastGoodEnd + 1
                        partNumber++
                    }

                    DebugLog.log("[PDF] Split into ${outputUris.size} parts")
                    return@withContext Result.success(outputUris)
                } finally {
                    sourceDoc.close()
                }
            }
            
            Result.failure(Exception("Could not open PDF"))
        } catch (e: Exception) {
            DebugLog.log("[PDF] Split by size failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun ensureTranslationActive(executionController: PDFTranslationExecutionController?) {
        currentCoroutineContext().ensureActive()
        if (executionController?.isCancelled() == true) {
            throw PDFTranslationCancelledException()
        }
    }

    private suspend fun canUsePageScreenshotContext(snapshot: RemoteSummarySettingsSnapshot): Boolean {
        val normalizedBackend = SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)
        if (normalizedBackend != SettingsRepository.PDF_BACKEND_LITERT) return true
        val modelId = snapshot.liteRtModelId ?: return false
        val model = AppDatabase.getDatabase(context).liteRtModelDao().getById(modelId) ?: return false
        return model.supportsLiteRtVision()
    }

    private fun chunkPageBlocks(
        units: List<PdfTranslationUnit>,
        contextSize: Int,
        qualityMode: PdfTranslationQualityMode
    ): List<PageTranslationChunk> {
        if (units.isEmpty()) return emptyList()
        val maxApproxTokens = (contextSize * when (qualityMode) {
            PdfTranslationQualityMode.BEST_QUALITY -> 0.42f
            PdfTranslationQualityMode.BALANCED -> 0.50f
            PdfTranslationQualityMode.FASTER -> 0.56f
        }).toInt().coerceAtLeast(900)
        val maxUnitsPerChunk = when (qualityMode) {
            PdfTranslationQualityMode.BEST_QUALITY -> 24
            PdfTranslationQualityMode.BALANCED -> 34
            PdfTranslationQualityMode.FASTER -> 44
        }
        val chunks = mutableListOf<List<PdfTranslationUnit>>()
        var current = mutableListOf<PdfTranslationUnit>()
        var currentTokens = 0
        units.forEach { unit ->
            val unitTokens = PDFSummaryLogic.approximateTokens(unit.text).coerceAtLeast(20) + (unit.sourceBlockCount * 12)
            val exceedsTokenBudget = current.isNotEmpty() && currentTokens + unitTokens > maxApproxTokens
            val exceedsUnitBudget = current.size >= maxUnitsPerChunk
            if (exceedsTokenBudget || exceedsUnitBudget) {
                chunks += current.toList()
                current = mutableListOf()
                currentTokens = 0
            }
            current += unit
            currentTokens += unitTokens
        }
        if (current.isNotEmpty()) {
            chunks += current.toList()
        }
        return chunks.mapIndexed { index, chunk ->
            PageTranslationChunk(
                units = chunk,
                index = index + 1,
                totalChunks = chunks.size,
                totalPageUnits = units.size
            )
        }
    }

    private suspend fun <T> runWithTranslationRetries(
        label: String,
        executionController: PDFTranslationExecutionController?,
        maxAttempts: Int = 3,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            ensureTranslationActive(executionController)
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                val shouldRetry = attempt < maxAttempts - 1 && shouldRetryTranslationFailure(error)
                if (!shouldRetry) throw error
                DebugLog.log("[PDF] Retrying $label after failure ${attempt + 1}/$maxAttempts: ${error.message}")
            }
        }
        throw lastError ?: IllegalStateException("Unknown translation failure")
    }

    private fun shouldRetryTranslationFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return error is InterruptedIOException ||
            "timeout" in message ||
            "blank_output" in message ||
            "translation_json_missing_object" in message ||
            "translation_json_missing_ids" in message ||
            "unterminated object" in message ||
            "unexpected end of json input" in message ||
            "connection" in message
    }

    private fun pageTranslationTemperature(configured: Float): Float = configured.coerceIn(0f, 0.22f)

    private fun deterministicTranslationTemperature(configured: Float): Float = configured.coerceIn(0f, 0.08f)

    private fun buildChunkTranslationFailure(
        pageIndex: Int,
        pageChunk: PageTranslationChunk,
        cause: Throwable
    ): PDFTranslationDisplayException {
        val displayError = classifyTranslationDisplayError(cause)
        val pageNumber = pageIndex + 1
        val message = context.getString(
            R.string.pdf_translation_error_page_chunk,
            pageNumber,
            "${pageChunk.index}/${pageChunk.totalChunks}",
            displayError.message
        )
        return PDFTranslationDisplayException(
            displayMessage = message,
            displayDetails = displayError.details,
            cause = cause
        )
    }

    private fun createExportStageFailure(
        cause: Throwable,
        pageNumber: Int? = null,
        fontSourceLabel: String? = null
    ): PDFTranslationDisplayException {
        val details = buildList {
            pageNumber?.let {
                add(context.getString(R.string.pdf_translation_error_export_page_detail, it))
            }
            fontSourceLabel?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.pdf_translation_error_export_font_detail, it))
            }
            cause.message?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" ").ifBlank { null }
        return PDFTranslationDisplayException(
            displayMessage = context.getString(R.string.pdf_translation_error_export_failed),
            displayDetails = details,
            cause = cause
        )
    }

    private fun classifyTranslationDisplayError(error: Throwable): PdfTranslationDisplayError {
        if (error is PDFTranslationDisplayException) {
            return PdfTranslationDisplayError(error.displayMessage, error.displayDetails)
        }
        val raw = error.message?.trim().orEmpty()
        val normalized = raw.lowercase()
        val summary = when {
            "translation_json_missing_object" in normalized ||
                "unterminated object" in normalized ||
                "unexpected end of json input" in normalized ->
                context.getString(R.string.pdf_translation_error_incomplete_json)
            "translation_json_missing_ids" in normalized ->
                context.getString(R.string.pdf_translation_error_missing_groups)
            "no glyph for u+" in normalized ||
                "missing font" in normalized ||
                "head' table is mandatory" in normalized ||
                "head table is mandatory" in normalized ->
                context.getString(R.string.pdf_translation_error_export_failed)
            "timeout" in normalized ->
                context.getString(R.string.pdf_translation_error_timeout)
            "connection" in normalized ->
                context.getString(R.string.pdf_translation_error_connection)
            else -> context.getString(R.string.pdf_translation_error_generic)
        }
        return PdfTranslationDisplayError(
            message = summary,
            details = raw.takeIf { it.isNotBlank() && !it.equals(summary, ignoreCase = true) }
        )
    }
}
