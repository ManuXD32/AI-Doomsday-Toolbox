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
import com.example.llamadroid.data.LlamaOcrPromptPreset
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.PdfOcrProvider
import com.example.llamadroid.data.PdfTranslationOptionsSnapshot
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InterruptedIOException
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.ConcurrentHashMap
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
    val textCharacters: Int,
    val currentRegion: Int = 0,
    val totalRegions: Int = 0,
    val detailText: String? = null
)

data class PdfOcrTranslationProgress(
    val stage: PdfOcrTranslationStage,
    val processedPages: Int,
    val totalPages: Int,
    val translatedBlocks: Int,
    val totalBlocks: Int,
    val currentRegion: Int = 0,
    val totalRegions: Int = 0,
    val detailText: String? = null
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
    val blocks: List<PdfOcrBlock>,
    val ungroundedResponses: Int = 0,
    val regionalFallbacks: Int = 0,
    val promptLeakRejections: Int = 0,
    val skippedLlamaCropRequests: Int = 0,
    val llamaOcrRequests: Int = 0,
    val llamaOcrElapsedMs: Long = 0L,
    val reconciledOcrAlternatives: Int = 0
)

private data class PdfOcrBlock(
    val pageIndex: Int,
    val blockIndex: Int,
    val text: String,
    val box: PdfOcrBox,
    val lineBoxes: List<PdfOcrLine>,
    val backgroundColor: Int,
    val textColor: Int,
    val provenance: MangaOcrRegionProvenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK,
    val containingRegionId: String? = null,
    val safeRegionBox: PdfOcrBox? = null,
    val recognitionPass: MangaOcrRecognitionPass = MangaOcrRecognitionPass.FULL_PAGE_ML_KIT
)

private data class PdfOcrLine(
    val text: String,
    val box: PdfOcrBox
)

private data class BubbleOcrRegion(
    val rect: Rect,
    val score: Float
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
    val sourceLines: List<String>,
    val containingRegionId: String? = null,
    val safeRegionRect: PdfMappedRect? = null,
    val provenance: MangaOcrRegionProvenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK,
    val textRole: MangaOcrTextRole = MangaOcrTextRole.UNKNOWN
)

private data class PdfTranslationUnit(
    val id: String,
    val pageIndex: Int,
    val unitIndex: Int,
    val text: String,
    val rect: PdfMappedRect,
    val sourceRect: PdfMappedRect,
    val backgroundColor: Int,
    val textColor: Int,
    val sourceBlockIds: List<String>,
    val sourceLineCount: Int,
    val sourceLines: List<String>,
    val containingRegionId: String? = null,
    val safeRegionRect: PdfMappedRect? = null,
    val textRole: MangaOcrTextRole = MangaOcrTextRole.UNKNOWN
) {
    val sourceBlockCount: Int get() = sourceBlockIds.size
}

private data class LlamaOcrRegionRequest(
    val rect: Rect,
    val regionId: String?,
    val fullPageContext: Boolean,
    val originalRegionIndex: Int = -1
)

private data class PreparedOcrTranslation(
    val pageUnits: Map<Int, List<PdfTranslationUnit>>,
    val translations: LinkedHashMap<String, String>,
    val totalSourceBlocks: Int,
    val resolvedReadingDirection: MangaReadingDirection = MangaReadingDirection.LEFT_TO_RIGHT
)

class MangaTranslationQualityAccumulator {
    var weakTranslationsRetried: Int = 0
    var textOnlyFallbacks: Int = 0
    var jsonRepairs: Int = 0
    var plainTextFallbacks: Int = 0
    var untranslatedUnits: Int = 0
    var visionFallbacks: Int = 0
    var blankOverlayUnits: Int = 0
    var clippedOverlayUnits: Int = 0
    var ungroundedOcrResponses: Int = 0
    var regionalOcrFallbacks: Int = 0
    var promptLeakRejections: Int = 0
    var skippedLlamaCropRequests: Int = 0
    var llamaOcrRequests: Int = 0
    var llamaOcrElapsedMs: Long = 0L
    var decorativeTextPreserved: Int = 0
    var rejectedCrossRegionMerges: Int = 0
    var skippedOverlayUnits: Int = 0
    var liteRtRuntimeFallbacks: Int = 0
    var reconciledOcrAlternatives: Int = 0
    var coalescedBubbleFragments: Int = 0
    var incompleteTranslationRetries: Int = 0
    var wholeBubblesPreserved: Int = 0
}

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
    val errorDetails: String? = null,
    val qualityReport: MangaTranslationQualityReport = MangaTranslationQualityReport()
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
private val mangaVisionCapabilityCache = ConcurrentHashMap<String, Boolean>()

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
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfExtractionResult> = withContext(Dispatchers.IO) {
        collectPdfOcrText(
            pdfUri = pdfUri,
            optionsOverride = optionsOverride,
            onProgress = onProgress
        ).map { result ->
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
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Exporting OCR-searchable PDF")
            val ocrResult = collectPdfOcrText(
                pdfUri = pdfUri,
                optionsOverride = optionsOverride,
                onProgress = onProgress
            ).getOrThrow()
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
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
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
                    optionsOverride = optionsOverride,
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
        optionsOverride: PdfTranslationOptionsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): File {
        ensureTranslationActive(executionController)
        val ocrResult = collectPdfOcrText(pdfUri, optionsOverride = optionsOverride) { progress ->
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
                optionsOverride = optionsOverride,
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
        optionsOverride: PdfTranslationOptionsSnapshot?,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int
    ): File {
        val prepared = prepareOcrTranslation(
            sourcePdfUri = sourcePdfUri,
            ocrResult = ocrResult,
            settingsOverride = settingsOverride,
            optionsOverride = optionsOverride,
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
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        mangaConfig: MangaTranslationRunConfig? = null,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int,
        initialTranslations: Map<String, String> = emptyMap(),
        onCheckpoint: ((Map<String, String>, Set<Int>) -> Unit)? = null,
        pageScreenshotProvider: (suspend (Int) -> RemoteSummaryImageAttachment?)? = null,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
    ): PreparedOcrTranslation {
        val options = optionsOverride ?: settingsRepo.pdfTranslationOptionsSnapshot()
        val qualityMode = options.qualityMode
        val readingDirection = mangaConfig?.readingDirection ?: MangaReadingDirection.LEFT_TO_RIGHT
        val pageUnits = buildOcrTranslationUnits(
            pages = ocrResult.pages,
            qualityMode = qualityMode,
            readingDirection = readingDirection,
            preserveIndependentRegions = mangaConfig != null,
            translateDecorativeText = mangaConfig?.behavior?.translateDecorativeText ?: true,
            qualityAccumulator = qualityAccumulator
        )
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
            optionsOverride = options,
            mangaConfig = mangaConfig,
            executionController = executionController,
            onProgress = onProgress,
            notificationId = notificationId,
            initialTranslations = initialTranslations,
            onCheckpoint = onCheckpoint,
            pageScreenshotProvider = pageScreenshotProvider,
            qualityAccumulator = qualityAccumulator
        )

        val firstPageBoxes = ocrResult.pages.firstOrNull()?.let { page ->
            page.blocks.map { block ->
                MangaReadingOrderBox(
                    id = "p${page.pageIndex + 1}_b${block.blockIndex + 1}",
                    rect = PDFTranslationLogic.mapBitmapBoxToPdfRect(
                        block.box,
                        page.bitmapWidth,
                        page.bitmapHeight,
                        page.pdfWidth,
                        page.pdfHeight
                    ),
                    text = block.text
                )
            }
        }.orEmpty()
        val resolvedDirection = MangaTranslationSupport.resolveReadingDirection(readingDirection, firstPageBoxes)
        qualityAccumulator?.let { report ->
            translatableUnits.forEach { unit ->
                val translated = translations[unit.id].orEmpty().trim()
                if (translated.isBlank()) {
                    report.blankOverlayUnits += 1
                } else {
                    val approximateCapacity = (
                        (unit.rect.width / 7.5f).coerceAtLeast(1f) *
                            (unit.rect.height / 12f).coerceAtLeast(1f)
                        ).toInt()
                    if (translated.length > approximateCapacity * 2) {
                        report.clippedOverlayUnits += 1
                    }
                }
            }
        }

        return PreparedOcrTranslation(
            pageUnits = pageUnits,
            translations = translations,
            totalSourceBlocks = totalSourceBlocks,
            resolvedReadingDirection = resolvedDirection
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
        saveToConfiguredOutput: Boolean,
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
            return if (saveToConfiguredOutput) {
                saveFileToOutputFolder(rasterPdf, "pdfs", "application/pdf", outputFileName)
            } else {
                saveFileToCache(rasterPdf, "manga_intermediate", outputFileName)
            }
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    suspend fun exportTranslatedTextLayerPdf(
        pdfUri: Uri,
        outputFileName: String = "translated_text_layer_${System.currentTimeMillis()}.pdf",
        settingsOverride: RemoteSummarySettingsSnapshot? = null,
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        mangaConfig: MangaTranslationRunConfig? = null,
        saveToConfiguredOutput: Boolean = true,
        executionController: PDFTranslationExecutionController? = null,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null,
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
            val options = optionsOverride ?: settingsRepo.pdfTranslationOptionsSnapshot()
            val qualityMode = options.qualityMode
            val pageUnits = buildTextLayerTranslationUnits(
                pages = textLayerResult.pages,
                qualityMode = qualityMode,
                readingDirection = mangaConfig?.readingDirection ?: MangaReadingDirection.LEFT_TO_RIGHT
            )
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
                optionsOverride = options,
                mangaConfig = mangaConfig,
                executionController = executionController,
                onProgress = onProgress,
                notificationId = notificationId,
                qualityAccumulator = qualityAccumulator
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
                        saveToConfiguredOutput = saveToConfiguredOutput,
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
                            saveToConfiguredOutput = saveToConfiguredOutput,
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
                val outputUri = if (saveToConfiguredOutput) {
                    saveToOutputFolder(doc, outputFileName)
                } else {
                    saveToCacheAndGetUri(doc, outputFileName)
                }
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
        pdfUri: Uri?,
        totalPages: Int,
        pageUnits: Map<Int, List<PdfTranslationUnit>>,
        totalSourceBlocks: Int,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        mangaConfig: MangaTranslationRunConfig? = null,
        executionController: PDFTranslationExecutionController?,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        notificationId: Int,
        initialTranslations: Map<String, String> = emptyMap(),
        onCheckpoint: ((Map<String, String>, Set<Int>) -> Unit)? = null,
        pageScreenshotProvider: (suspend (Int) -> RemoteSummaryImageAttachment?)? = null,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
    ): LinkedHashMap<String, String> {
        val snapshot = settingsOverride ?: settingsRepo.pdfTranslationSettings.snapshot()
        val options = optionsOverride ?: settingsRepo.pdfTranslationOptionsSnapshot()
        val qualityMode = options.qualityMode
        val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
        executionController?.registerRemoteClient(client)
        val targetLanguage = snapshot.targetLanguage
        val translations = linkedMapOf<String, String>().apply { putAll(initialTranslations) }
        var translatedBlocks = pageUnits.values.flatten()
            .filter { translations.containsKey(it.id) }
            .sumOf { it.sourceBlockCount }
        val completedPageIndexes = pageUnits
            .filter { (_, units) -> units.isNotEmpty() && units.all { translations.containsKey(it.id) } }
            .keys
            .toMutableSet()
        val pageContexts = linkedMapOf<Int, String>()
        val pageScreenshotContextAllowed = options.usePageScreenshotContext &&
            (mangaConfig?.pageImageContextAvailable ?: canUsePageScreenshotContext(snapshot))
        if (options.usePageScreenshotContext && !pageScreenshotContextAllowed) {
            DebugLog.log("[PDF] Page screenshot context disabled; selected ${snapshot.backend} model is not vision-capable or no vision projector is configured. Continuing text-only.")
        }

        try {
            for (pageIndex in 0 until totalPages) {
                ensureTranslationActive(executionController)
                val units = pageUnits[pageIndex].orEmpty()
                if (units.isEmpty()) continue
                val pageChunks = chunkPageBlocks(units, snapshot.chunkContext, qualityMode)
                val imageAttachment = if (pageScreenshotContextAllowed) {
                    runCatching {
                        pageScreenshotProvider?.invoke(pageIndex)
                            ?: pdfUri?.let {
                                RemoteSummaryImageAttachment(
                                    base64 = renderPdfPageScreenshotBase64(
                                        pdfUri = it,
                                        pageIndex = pageIndex,
                                        maxSide = options.screenshotMaxSide,
                                        jpegQuality = options.screenshotJpegQuality
                                    )
                                )
                            }
                            ?: error("No screenshot provider available")
                    }.onFailure { error ->
                        DebugLog.log("[PDF] Screenshot context unavailable for page ${pageIndex + 1}: ${error.message}")
                    }.getOrNull()
                } else {
                    null
                }
                val pageContext = if (
                    mangaConfig?.behavior?.pageUnderstandingEnabled
                        ?: (qualityMode == PdfTranslationQualityMode.BEST_QUALITY)
                ) {
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
                    pageContexts[pageIndex - 1]?.takeIf {
                        mangaConfig?.behavior?.continuityEnabled == true ||
                            qualityMode == PdfTranslationQualityMode.BALANCED
                    }
                }
                val continuityContext = if (
                    mangaConfig?.behavior?.continuityEnabled == true && pageIndex > 0
                ) {
                    buildBoundedTranslationContinuity(
                        previousUnits = pageUnits[pageIndex - 1].orEmpty(),
                        translations = translations
                    )
                } else {
                    null
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

                var pageCompleted = true
                pageChunks.forEach { chunk ->
                    ensureTranslationActive(executionController)
                    val missingUnits = chunk.units.filterNot { translations.containsKey(it.id) }
                    val pageTranslations = if (missingUnits.isEmpty()) {
                        emptyMap()
                    } else {
                        translateSinglePage(
                            pageIndex = pageIndex,
                            totalPages = totalPages,
                            snapshot = snapshot,
                            targetLanguage = targetLanguage,
                            pageChunk = chunk.copy(units = missingUnits),
                            fallbackToTextOnly = options.textOnlyFallbackEnabled,
                            imageAttachment = imageAttachment,
                            pageContext = pageContext,
                            continuityContext = continuityContext,
                            qualityMode = qualityMode,
                            readingDirection = resolveReadingDirectionForUnits(
                                mangaConfig?.readingDirection ?: MangaReadingDirection.LEFT_TO_RIGHT,
                                units
                            ),
                            client = client,
                            executionController = executionController,
                            qualityAccumulator = qualityAccumulator
                        )
                    }
                    chunk.units.forEach { unit ->
                        val translated = translations[unit.id]?.trim()
                            ?: pageTranslations[unit.id].orEmpty().trim()
                        if (translated.isNotBlank()) {
                            if (!translations.containsKey(unit.id)) {
                                translations[unit.id] = translated
                                translatedBlocks += unit.sourceBlockCount
                            }
                        } else {
                            pageCompleted = false
                        }
                    }
                    onCheckpoint?.invoke(translations, completedPageIndexes)
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
                if (pageCompleted && units.all { translations.containsKey(it.id) }) {
                    completedPageIndexes += pageIndex
                    onCheckpoint?.invoke(translations, completedPageIndexes)
                }
            }
            if (mangaConfig?.behavior?.correctionEnabled != false) {
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
            }
            return translations
        } finally {
            executionController?.clearRemoteClient(client)
        }
    }

    private fun buildBoundedTranslationContinuity(
        previousUnits: List<PdfTranslationUnit>,
        translations: Map<String, String>
    ): String? {
        val entries = previousUnits.mapNotNull { unit ->
            val translated = translations[unit.id]?.trim().orEmpty()
            if (translated.isBlank()) {
                null
            } else {
                "${unit.text.replace(Regex("""\s+"""), " ").trim().take(120)} → " +
                    translated.replace(Regex("""\s+"""), " ").trim().take(160)
            }
        }.takeLast(10)
        return entries
            .joinToString("\n")
            .take(1_200)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun resolveReadingDirectionForUnits(
        requested: MangaReadingDirection,
        units: List<PdfTranslationUnit>
    ): MangaReadingDirection =
        MangaTranslationSupport.resolveReadingDirection(
            requested = requested,
            boxes = units.map { unit ->
                MangaReadingOrderBox(
                    id = unit.id,
                    rect = unit.rect,
                    text = unit.text
                )
            }
        )

    private fun buildOcrTranslationUnits(
        pages: List<PdfOcrPageResult>,
        qualityMode: PdfTranslationQualityMode,
        readingDirection: MangaReadingDirection = MangaReadingDirection.LEFT_TO_RIGHT,
        preserveIndependentRegions: Boolean = false,
        translateDecorativeText: Boolean = true,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
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
                    sourceLines = sourceLines,
                    containingRegionId = block.containingRegionId
                        ?: if (preserveIndependentRegions) {
                            "p${page.pageIndex + 1}_block${block.blockIndex + 1}"
                        } else {
                            null
                        },
                    safeRegionRect = block.safeRegionBox?.let { safeBox ->
                        PDFTranslationLogic.mapBitmapBoxToPdfRect(
                            box = safeBox,
                            bitmapWidth = page.bitmapWidth,
                            bitmapHeight = page.bitmapHeight,
                            pdfWidth = page.pdfWidth,
                            pdfHeight = page.pdfHeight
                        )
                    },
                    provenance = block.provenance,
                    textRole = MangaTranslationSupport.classifyMangaOcrTextRole(
                        text = text,
                        rect = PDFTranslationLogic.mapBitmapBoxToPdfRect(
                            box = block.box,
                            bitmapWidth = page.bitmapWidth,
                            bitmapHeight = page.bitmapHeight,
                            pdfWidth = page.pdfWidth,
                            pdfHeight = page.pdfHeight
                        ),
                        pageWidth = page.pdfWidth,
                        pageHeight = page.pdfHeight,
                        provenance = block.provenance
                    )
                )
            }
            page.pageIndex to buildTranslationUnitsForPage(
                pageIndex = page.pageIndex,
                pdfWidth = page.pdfWidth,
                pdfHeight = page.pdfHeight,
                sourceBlocks = sourceBlocks,
                qualityMode = qualityMode,
                readingDirection = readingDirection,
                translateDecorativeText = translateDecorativeText,
                qualityAccumulator = qualityAccumulator
            )
        }
    }

    private fun toPageTranslationBlocks(units: List<PdfTranslationUnit>): List<PDFTranslationLogic.PageTranslationBlock> {
        return units.mapIndexed { promptIndex, unit ->
            val sourceRect = unit.sourceRect
            PDFTranslationLogic.PageTranslationBlock(
                id = unit.id,
                text = unit.text,
                x = sourceRect.x,
                y = sourceRect.y,
                width = sourceRect.width,
                height = sourceRect.height,
                readingOrder = promptIndex + 1,
                sourceBlockCount = unit.sourceBlockCount,
                sourceLineCount = unit.sourceLineCount,
                sourceLines = unit.sourceLines
            )
        }
    }

    private fun buildTextLayerTranslationUnits(
        pages: List<PdfTextLayerPageResult>,
        qualityMode: PdfTranslationQualityMode,
        readingDirection: MangaReadingDirection = MangaReadingDirection.LEFT_TO_RIGHT
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
                qualityMode = qualityMode,
                readingDirection = readingDirection
            )
        }
    }

    private fun buildTranslationUnitsForPage(
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float,
        sourceBlocks: List<PdfTranslationSourceBlock>,
        qualityMode: PdfTranslationQualityMode,
        readingDirection: MangaReadingDirection = MangaReadingDirection.LEFT_TO_RIGHT,
        translateDecorativeText: Boolean = true,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
    ): List<PdfTranslationUnit> {
        if (sourceBlocks.isEmpty()) return emptyList()
        val sourceById = sourceBlocks.associateBy { it.sourceId }
        val sorted = MangaTranslationSupport.orderReadingBoxes(
            boxes = sourceBlocks.map { block ->
                MangaReadingOrderBox(block.sourceId, block.rect, block.text)
            },
            pageHeight = pdfHeight,
            requested = readingDirection
        ).mapNotNull { sourceById[it.id] }
        val regionGroups = linkedMapOf<String, MutableList<PdfTranslationSourceBlock>>()
        val regionFirstOrder = mutableMapOf<String, Int>()
        val orphanGroups = mutableListOf<Pair<Int, MutableList<PdfTranslationSourceBlock>>>()
        sorted.forEachIndexed { orderIndex, block ->
            val regionId = block.containingRegionId
            if (regionId != null) {
                regionFirstOrder.putIfAbsent(regionId, orderIndex)
                regionGroups.getOrPut(regionId) { mutableListOf() } += block
            } else {
                val current = orphanGroups.lastOrNull()?.second
                val previous = current?.lastOrNull()
                if (
                    current != null &&
                    previous != null &&
                    translationGroupsShouldMerge(current, block, pdfWidth, pdfHeight, qualityMode)
                ) {
                    current += block
                } else {
                    orphanGroups += orderIndex to mutableListOf(block)
                }
            }
        }
        val merged = buildList {
            regionGroups.forEach { (regionId, blocks) ->
                val deduped = dedupeTranslationSourceBlocks(blocks)
                if (deduped.size > 1) {
                    qualityAccumulator?.let { it.coalescedBubbleFragments += deduped.size - 1 }
                }
                add(regionFirstOrder.getValue(regionId) to deduped)
            }
            orphanGroups.forEach { (orderIndex, blocks) ->
                add(orderIndex to blocks.toList())
            }
        }.sortedBy { it.first }.map { it.second }
        val units = merged.mapIndexedNotNull { unitIndex, group ->
            val groupById = group.associateBy { it.sourceId }
            val ordered = MangaTranslationSupport.orderReadingBoxes(
                boxes = group.map { block ->
                    MangaReadingOrderBox(block.sourceId, block.rect, block.text)
                },
                pageHeight = pdfHeight,
                requested = readingDirection
            ).mapNotNull { groupById[it.id] }
            val text = ordered.joinToString("\n") { it.text.trim() }.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val textRole = dominantMangaTextRole(ordered)
            if (!translateDecorativeText && textRole in setOf(
                    MangaOcrTextRole.DECORATIVE,
                    MangaOcrTextRole.CREDIT,
                    MangaOcrTextRole.PAGE_NUMBER
                )
            ) {
                qualityAccumulator?.let { it.decorativeTextPreserved += 1 }
                DebugLog.log(
                    "[PDF] Preserved manga ${textRole.name.lowercase(Locale.US)} region on page ${pageIndex + 1}; original artwork kept."
                )
                return@mapIndexedNotNull null
            }
            val backgroundColor = dominantBackgroundColor(ordered.map { it.backgroundColor })
            val sourceRect = unionPdfRects(ordered.map { it.rect }).padded(1.5f, pdfWidth, pdfHeight)
            val containingRegionId = ordered.mapNotNull { it.containingRegionId }.distinct().singleOrNull()
            if (MangaTranslationSupport.mergedRegionIsTooLarge(sourceRect, pdfWidth, pdfHeight)) {
                qualityAccumulator?.let {
                    it.skippedOverlayUnits += 1
                    if (containingRegionId != null) it.wholeBubblesPreserved += 1
                }
                return@mapIndexedNotNull null
            }
            val safeRegion = ordered.mapNotNull { it.safeRegionRect }.distinct().singleOrNull()
            val overlayRect = MangaTranslationSupport.expandedBubbleRect(
                rect = sourceRect,
                pageWidth = pdfWidth,
                pageHeight = pdfHeight
            ).let { expanded ->
                safeRegion?.let { safe -> constrainOverlayRect(expanded, safe, sourceRect) } ?: expanded
            }
            PdfTranslationUnit(
                id = "p${pageIndex + 1}_u${unitIndex + 1}",
                pageIndex = pageIndex,
                unitIndex = unitIndex,
                text = text,
                rect = overlayRect,
                sourceRect = sourceRect,
                backgroundColor = backgroundColor,
                textColor = contrastingTextColor(backgroundColor),
                sourceBlockIds = ordered.map { it.sourceId },
                sourceLineCount = ordered.sumOf { it.lineCount },
                sourceLines = ordered.flatMap { it.sourceLines },
                containingRegionId = containingRegionId,
                safeRegionRect = ordered.mapNotNull { it.safeRegionRect }.distinct().singleOrNull(),
                textRole = textRole
            )
        }
        return reconcileTranslationUnitOverlaps(units, qualityAccumulator)
    }

    private fun dedupeTranslationSourceBlocks(
        blocks: List<PdfTranslationSourceBlock>
    ): List<PdfTranslationSourceBlock> {
        val accepted = mutableListOf<PdfTranslationSourceBlock>()
        blocks.forEach { candidate ->
            val normalizedCandidate = candidate.text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
            val duplicateIndex = accepted.indexOfFirst { existing ->
                val normalizedExisting = existing.text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
                val overlap = MangaTranslationSupport.overlayOverlapRatio(existing.rect, candidate.rect)
                overlap >= 0.35f &&
                    normalizedCandidate.length >= 5 &&
                    normalizedExisting.length >= 5 &&
                    (normalizedCandidate == normalizedExisting ||
                        normalizedCandidate.contains(normalizedExisting) ||
                        normalizedExisting.contains(normalizedCandidate))
            }
            if (duplicateIndex < 0) {
                accepted += candidate
            } else if (candidate.text.length > accepted[duplicateIndex].text.length) {
                accepted[duplicateIndex] = candidate
            }
        }
        return accepted
    }

    private fun dominantMangaTextRole(blocks: List<PdfTranslationSourceBlock>): MangaOcrTextRole {
        val roles = blocks.map { it.textRole }
        return when {
            roles.any { it == MangaOcrTextRole.DIALOGUE } -> MangaOcrTextRole.DIALOGUE
            roles.any { it == MangaOcrTextRole.NARRATION } -> MangaOcrTextRole.NARRATION
            roles.all { it == MangaOcrTextRole.PAGE_NUMBER } -> MangaOcrTextRole.PAGE_NUMBER
            roles.any { it == MangaOcrTextRole.CREDIT } -> MangaOcrTextRole.CREDIT
            roles.any { it == MangaOcrTextRole.DECORATIVE } -> MangaOcrTextRole.DECORATIVE
            else -> MangaOcrTextRole.UNKNOWN
        }
    }

    private fun reconcileTranslationUnitOverlaps(
        units: List<PdfTranslationUnit>,
        qualityAccumulator: MangaTranslationQualityAccumulator?
    ): List<PdfTranslationUnit> {
        if (units.size <= 1) return units
        val accepted = mutableListOf<PdfTranslationUnit>()
        units.forEach { unit ->
            val preferredCollides = accepted.any { existing ->
                MangaTranslationSupport.overlayOverlapRatio(unit.rect, existing.rect) > 0.12f
            }
            val resolved = if (!preferredCollides) {
                unit
            } else {
                val sourceCollides = accepted.any { existing ->
                    MangaTranslationSupport.overlayOverlapRatio(unit.sourceRect, existing.rect) > 0.08f
                }
                if (!sourceCollides) unit.copy(rect = unit.sourceRect) else null
            }
            if (resolved == null) {
                qualityAccumulator?.let {
                    it.skippedOverlayUnits += 1
                    if (unit.containingRegionId != null) it.wholeBubblesPreserved += 1
                }
                DebugLog.log(
                    "[PDF] Rejected overlapping manga overlay ${unit.id} on page ${unit.pageIndex + 1}; original artwork preserved"
                )
            } else {
                accepted += resolved
            }
        }
        return accepted
    }

    private fun constrainOverlayRect(
        proposed: PdfMappedRect,
        safeRegion: PdfMappedRect,
        sourceRect: PdfMappedRect
    ): PdfMappedRect {
        val safeLeft = safeRegion.x
        val safeBottom = safeRegion.y
        val safeRight = safeRegion.x + safeRegion.width
        val safeTop = safeRegion.y + safeRegion.height
        val left = proposed.x.coerceAtLeast(safeLeft)
        val bottom = proposed.y.coerceAtLeast(safeBottom)
        val right = (proposed.x + proposed.width).coerceAtMost(safeRight)
        val top = (proposed.y + proposed.height).coerceAtMost(safeTop)
        if (right - left >= sourceRect.width * 0.85f && top - bottom >= sourceRect.height * 0.85f) {
            return PdfMappedRect(left, bottom, right - left, top - bottom)
        }
        return sourceRect
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
        val currentRegionIds = currentGroup.mapNotNull { it.containingRegionId }.distinct()
        if (currentRegionIds.isNotEmpty() || nextBlock.containingRegionId != null) {
            if (
                currentRegionIds.size != 1 ||
                nextBlock.containingRegionId == null ||
                currentRegionIds.single() != nextBlock.containingRegionId
            ) {
                return false
            }
        }
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
            currentRegionIds.isNotEmpty() &&
            tinyFragments &&
            verticalGap <= maxOf(30f * qualityMultiplier, heightScale * 3.1f * qualityMultiplier) &&
            horizontalGap <= maxOf(36f * qualityMultiplier, widthScale * 0.4f * qualityMultiplier)
        val mangaBubbleFragments = currentRegionIds.isNotEmpty() &&
            qualityMode == PdfTranslationQualityMode.BEST_QUALITY &&
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
        return MangaTranslationSupport.mergedRegionIsTooLarge(rect, pageWidth, pageHeight) ||
            rectAreaRatio > 0.14f ||
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
        continuityContext: String?,
        qualityMode: PdfTranslationQualityMode,
        readingDirection: MangaReadingDirection,
        client: RemoteSummaryClient,
        executionController: PDFTranslationExecutionController?,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
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
                qualityMode = qualityMode,
                readingDirection = readingDirection,
                continuityContext = continuityContext
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
                qualityAccumulator?.let { it.liteRtRuntimeFallbacks += response.runtimeFallbacks }
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
                    executionController = executionController,
                    qualityAccumulator = qualityAccumulator
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
                        qualityAccumulator?.let { it.textOnlyFallbacks += 1 }
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
                            continuityContext = continuityContext,
                            qualityMode = qualityMode,
                            readingDirection = readingDirection,
                            client = client,
                            executionController = executionController,
                            qualityAccumulator = qualityAccumulator
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
                            continuityContext = continuityContext,
                            qualityMode = qualityMode,
                            readingDirection = readingDirection,
                            client = client,
                            executionController = executionController,
                            qualityAccumulator = qualityAccumulator
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
        executionController: PDFTranslationExecutionController?,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
    ): Map<String, String> {
        val expectedIds = requestedBlocks.map { it.id }.toSet()
        val partial = PDFTranslationLogic.parsePartialPageTranslationJson(responseOutput, expectedIds)
        val blockById = requestedBlocks.associateBy { it.id }
        fun translationNeedsRetry(id: String, translatedText: String): Boolean {
            val block = blockById[id] ?: return false
            return PDFTranslationLogic.isWeakPageTranslation(block.text, translatedText, targetLanguage) ||
                PDFTranslationLogic.isIncompleteMultiFragmentTranslation(block, translatedText, targetLanguage)
        }
        val weakIds = partial.translations
            .filter { (id, translatedText) ->
                blockById[id]?.let { block ->
                    PDFTranslationLogic.isWeakPageTranslation(block.text, translatedText, targetLanguage)
                } == true
            }
            .keys
            .toSet()
        val incompleteIds = partial.translations
            .filter { (id, translatedText) ->
                val block = blockById[id]
                block != null &&
                    id !in weakIds &&
                    PDFTranslationLogic.isIncompleteMultiFragmentTranslation(block, translatedText, targetLanguage)
            }
            .keys
            .toSet()
        if (partial.missingIds.isEmpty() && weakIds.isEmpty() && incompleteIds.isEmpty()) {
            return partial.translations
        }

        val retryIds = partial.missingIds + weakIds + incompleteIds
        qualityAccumulator?.let { it.weakTranslationsRetried += weakIds.size }
        qualityAccumulator?.let { it.incompleteTranslationRetries += incompleteIds.size }
        val missingBlocks = requestedBlocks.filter { it.id in retryIds }
        val recovered = linkedMapOf<String, String>().apply {
            partial.translations
                .filterKeys { it !in weakIds && it !in incompleteIds }
                .forEach { (id, translation) -> put(id, translation) }
        }
        qualityAccumulator?.let { it.jsonRepairs += 1 }
        val promptCompletedTranslations = linkedMapOf<String, String>().apply {
            putAll(completedTranslations)
            putAll(recovered)
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
        qualityAccumulator?.let { it.liteRtRuntimeFallbacks += repairResponse.runtimeFallbacks }
        val repaired = PDFTranslationLogic.parsePartialPageTranslationJson(repairResponse.output, retryIds)
        repaired.translations.forEach { (id, translation) ->
            if (!translationNeedsRetry(id, translation)) {
                recovered[id] = translation
            }
        }
        val refillContextTranslations = linkedMapOf<String, String>().apply {
            putAll(completedTranslations)
            putAll(recovered)
        }
        val stillMissing = expectedIds.filter { id ->
            val translation = recovered[id]
            translation == null || translationNeedsRetry(id, translation)
        }.toSet()
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
        qualityAccumulator?.let { it.liteRtRuntimeFallbacks += refillResponse.runtimeFallbacks }
        val refill = PDFTranslationLogic.parsePartialPageTranslationJson(refillResponse.output, stillMissing)
        refill.translations.forEach { (id, translation) ->
            if (!translationNeedsRetry(id, translation)) {
                recovered[id] = translation
            }
        }
        val unresolvedAfterJson = expectedIds.filter { id ->
            val translation = recovered[id]
            translation == null || translationNeedsRetry(id, translation)
        }
        unresolvedAfterJson.forEach { id ->
            val block = blockById[id] ?: return@forEach
            ensureTranslationActive(executionController)
            qualityAccumulator?.let { it.plainTextFallbacks += 1 }
            val plainText = runCatching {
                val response = client.summarize(
                    RemoteSummaryRequest(
                        systemPrompt = PDFTranslationLogic.DEFAULT_TRANSLATION_SYSTEM_PROMPT,
                        userPrompt = PDFTranslationLogic.buildSingleUnitPlainTextPrompt(targetLanguage, block),
                        contextSize = snapshot.chunkContext,
                        maxTokens = PDFTranslationLogic.estimateTranslationMaxTokens(
                            block.text,
                            snapshot.chunkMaxTokens
                        ),
                        temperature = deterministicTranslationTemperature(snapshot.temperature),
                        thinkingEnabled = false
                    )
                )
                qualityAccumulator?.let { it.liteRtRuntimeFallbacks += response.runtimeFallbacks }
                response.output
            }.getOrNull()
                ?.let(PDFTranslationLogic::cleanTranslationOutput)
                ?.trim()
                .orEmpty()
            if (
                plainText.isNotBlank() &&
                !translationNeedsRetry(id, plainText)
            ) {
                recovered[id] = plainText
            }
        }
        val unresolved = expectedIds.count { id ->
            val translation = recovered[id]
            translation == null || translationNeedsRetry(id, translation)
        }
        qualityAccumulator?.let { it.untranslatedUnits += unresolved }
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

    private fun renderImagePageScreenshotBase64(
        imageFile: File,
        maxSide: Int,
        jpegQuality: Int
    ): String {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val targetSide = maxSide.coerceAtLeast(256)
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= targetSide) {
            sampleSize *= 2
        }
        val decoded = android.graphics.BitmapFactory.decodeFile(
            imageFile.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IllegalStateException("Could not decode image page: ${imageFile.name}")
        val scale = (targetSide.toFloat() / maxOf(decoded.width, decoded.height).toFloat())
            .coerceAtMost(1.5f)
            .coerceAtLeast(0.25f)
        val bitmap = if (abs(scale - 1f) < 0.05f) {
            decoded
        } else {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        }
        try {
            val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                val output = ByteArrayOutputStream()
                flattened.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), output)
                return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } finally {
                flattened.recycle()
            }
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
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
        pageIndex: Int,
        ocrStrategy: MangaOcrStrategy = if (settingsRepo.pdfOcrBubbleGuided.value) {
            MangaOcrStrategy.HYBRID
        } else {
            MangaOcrStrategy.FULL_PAGE
        }
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
                    pdfHeight = page.height.toFloat(),
                    ocrStrategy = ocrStrategy
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
        pageIndex: Int,
        ocrStrategy: MangaOcrStrategy = if (settingsRepo.pdfOcrBubbleGuided.value) {
            MangaOcrStrategy.HYBRID
        } else {
            MangaOcrStrategy.FULL_PAGE
        }
    ): PdfOcrPageResult {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val originalWidth = bounds.outWidth.takeIf { it > 0 } ?: 1
        val originalHeight = bounds.outHeight.takeIf { it > 0 } ?: 1
        val longestSide = maxOf(originalWidth, originalHeight)
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= 2600) {
            sampleSize *= 2
        }
        val decoded = android.graphics.BitmapFactory.decodeFile(
            imageFile.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
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
                    pdfWidth = originalWidth.toFloat(),
                    pdfHeight = originalHeight.toFloat(),
                    ocrStrategy = ocrStrategy
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
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        ocrStrategy: MangaOcrStrategy? = null,
        ocrExecutionMode: MangaOcrExecutionMode = MangaOcrExecutionMode.BATCH,
        exhaustiveLlamaOcrRegions: Boolean = false,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfOcrDocumentResult> = withContext(Dispatchers.IO) {
        val options = optionsOverride ?: settingsRepo.pdfTranslationOptionsSnapshot()
        val resolvedStrategy = ocrStrategy ?: if (options.bubbleGuidedOcrEnabled) {
            MangaOcrStrategy.HYBRID
        } else {
            MangaOcrStrategy.FULL_PAGE
        }
        if (options.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
            return@withContext collectPdfOcrTextWithLlama(
                pdfUri,
                options.llamaOcr,
                resolvedStrategy,
                ocrExecutionMode,
                exhaustiveLlamaOcrRegions,
                onProgress
            )
        }
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
                        val pageResult = runCatching {
                            extractBlocksWithOcr(renderer, recognizers, pageIndex, resolvedStrategy)
                        }
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
        optionsOverride: PdfTranslationOptionsSnapshot? = null,
        ocrStrategy: MangaOcrStrategy? = null,
        ocrExecutionMode: MangaOcrExecutionMode = MangaOcrExecutionMode.BATCH,
        exhaustiveLlamaOcrRegions: Boolean = false,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfOcrDocumentResult> = withContext(Dispatchers.IO) {
        val options = optionsOverride ?: settingsRepo.pdfTranslationOptionsSnapshot()
        val resolvedStrategy = ocrStrategy ?: if (options.bubbleGuidedOcrEnabled) {
            MangaOcrStrategy.HYBRID
        } else {
            MangaOcrStrategy.FULL_PAGE
        }
        if (options.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
            return@withContext collectImageOcrTextWithLlama(
                imageFiles,
                options.llamaOcr,
                resolvedStrategy,
                ocrExecutionMode,
                exhaustiveLlamaOcrRegions,
                onProgress
            )
        }
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
                    val pageResult = runCatching {
                        extractBlocksFromImageFile(imageFile, recognizers, pageIndex, resolvedStrategy)
                    }
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

    private suspend fun collectPdfOcrTextWithLlama(
        pdfUri: Uri,
        llamaSettings: LlamaOcrSettingsSnapshot,
        ocrStrategy: MangaOcrStrategy,
        ocrExecutionMode: MangaOcrExecutionMode,
        exhaustiveLlamaOcrRegions: Boolean,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfOcrDocumentResult> = withContext(Dispatchers.IO) {
        runCatching {
            withLlamaOcrClient(llamaSettings) { client ->
                DebugLog.log("[PDF] Performing llama.cpp GGUF OCR on PDF pages with ${llamaSettings.promptPreset.label}")
                val cachedPdf = copyPdfToCache(pdfUri)
                try {
                    val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    try {
                        val totalPages = renderer.pageCount
                        if (totalPages == 0) throw IllegalStateException("PDF has no pages")
                        onProgress?.invoke(PdfExtractionProgress(0, totalPages, 0, 0, 0, 0))
                        val pages = mutableListOf<PdfOcrPageResult>()
                        var ocrPages = 0
                        var emptyPages = 0
                        var textCharacters = 0
                        for (pageIndex in 0 until totalPages) {
                            currentCoroutineContext().ensureActive()
                            val page = renderer.openPage(pageIndex)
                            try {
                                val scale = computeOcrScale(page.width, page.height)
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * scale).roundToInt().coerceAtLeast(page.width),
                                    (page.height * scale).roundToInt().coerceAtLeast(page.height),
                                    Bitmap.Config.ARGB_8888
                                )
                                try {
                                    Canvas(bitmap).drawColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val result = recognizeBitmapWithLlamaOcr(
                                        client = client,
                                        llamaSettings = llamaSettings,
                                        bitmap = bitmap,
                                        pageIndex = pageIndex,
                                        pdfWidth = page.width.toFloat(),
                                        pdfHeight = page.height.toFloat(),
                                        ocrStrategy = ocrStrategy,
                                        ocrExecutionMode = ocrExecutionMode,
                                        exhaustiveLlamaOcrRegions = exhaustiveLlamaOcrRegions
                                    ) { currentRegion, totalRegions ->
                                        onProgress?.invoke(
                                            PdfExtractionProgress(
                                                processedPages = pageIndex,
                                                totalPages = totalPages,
                                                textLayerPages = 0,
                                                ocrPages = ocrPages,
                                                emptyPages = emptyPages,
                                                textCharacters = textCharacters,
                                                currentRegion = currentRegion,
                                                totalRegions = totalRegions,
                                                detailText = context.getString(
                                                    R.string.workflow_manga_stage_ocr_region,
                                                    pageIndex + 1,
                                                    totalPages,
                                                    currentRegion,
                                                    totalRegions
                                                )
                                            )
                                        )
                                    }
                                    pages += result
                                    val pageText = result.blocks.joinToString("\n\n") { it.text }.trim()
                                    if (pageText.isNotBlank()) {
                                        ocrPages += 1
                                        textCharacters += pageText.length
                                    } else {
                                        emptyPages += 1
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            } finally {
                                page.close()
                            }
                            onProgress?.invoke(PdfExtractionProgress(pageIndex + 1, totalPages, 0, ocrPages, emptyPages, textCharacters))
                        }
                        buildOcrDocumentResult(pages, totalPages, ocrPages, emptyPages)
                    } finally {
                        runCatching { renderer.close() }
                        runCatching { pfd.close() }
                    }
                } finally {
                    cachedPdf.delete()
                }
            }
        }.onFailure { DebugLog.log("[PDF] llama.cpp GGUF PDF OCR failed: ${it.message}") }
    }

    private suspend fun collectImageOcrTextWithLlama(
        imageFiles: List<File>,
        llamaSettings: LlamaOcrSettingsSnapshot,
        ocrStrategy: MangaOcrStrategy,
        ocrExecutionMode: MangaOcrExecutionMode,
        exhaustiveLlamaOcrRegions: Boolean,
        onProgress: ((PdfExtractionProgress) -> Unit)? = null
    ): Result<PdfOcrDocumentResult> = withContext(Dispatchers.IO) {
        runCatching {
            withLlamaOcrClient(llamaSettings) { client ->
                DebugLog.log("[PDF] Performing llama.cpp GGUF OCR on ${imageFiles.size} comic images with ${llamaSettings.promptPreset.label}")
                val totalPages = imageFiles.size
                if (totalPages == 0) throw IllegalStateException("Comic has no image pages")
                onProgress?.invoke(PdfExtractionProgress(0, totalPages, 0, 0, 0, 0))
                val pages = mutableListOf<PdfOcrPageResult>()
                var ocrPages = 0
                var emptyPages = 0
                var textCharacters = 0
                imageFiles.forEachIndexed { pageIndex, imageFile ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = loadComicImageBitmapForOcr(imageFile)
                    try {
                        val result = recognizeBitmapWithLlamaOcr(
                            client = client,
                            llamaSettings = llamaSettings,
                            bitmap = bitmap,
                            pageIndex = pageIndex,
                            pdfWidth = bitmap.width.toFloat(),
                            pdfHeight = bitmap.height.toFloat(),
                            ocrStrategy = ocrStrategy,
                            ocrExecutionMode = ocrExecutionMode,
                            exhaustiveLlamaOcrRegions = exhaustiveLlamaOcrRegions
                        ) { currentRegion, totalRegions ->
                            onProgress?.invoke(
                                PdfExtractionProgress(
                                    processedPages = pageIndex,
                                    totalPages = totalPages,
                                    textLayerPages = 0,
                                    ocrPages = ocrPages,
                                    emptyPages = emptyPages,
                                    textCharacters = textCharacters,
                                    currentRegion = currentRegion,
                                    totalRegions = totalRegions,
                                    detailText = context.getString(
                                        R.string.workflow_manga_stage_ocr_region,
                                        pageIndex + 1,
                                        totalPages,
                                        currentRegion,
                                        totalRegions
                                    )
                                )
                            )
                        }
                        pages += result
                        val pageText = result.blocks.joinToString("\n\n") { it.text }.trim()
                        if (pageText.isNotBlank()) {
                            ocrPages += 1
                            textCharacters += pageText.length
                        } else {
                            emptyPages += 1
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    onProgress?.invoke(PdfExtractionProgress(pageIndex + 1, totalPages, 0, ocrPages, emptyPages, textCharacters))
                }
                buildOcrDocumentResult(pages, totalPages, ocrPages, emptyPages)
            }
        }.onFailure { DebugLog.log("[PDF] llama.cpp GGUF image OCR failed: ${it.message}") }
    }

    private fun buildOcrDocumentResult(
        pages: List<PdfOcrPageResult>,
        totalPages: Int,
        ocrPages: Int,
        emptyPages: Int
    ): PdfOcrDocumentResult {
        val text = pages
            .flatMap { page -> page.blocks.map { it.text } }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trim()
        if (text.isBlank()) throw IllegalStateException("No OCR text found")
        return PdfOcrDocumentResult(
            pages = pages,
            text = text,
            totalPages = totalPages,
            ocrPages = ocrPages,
            emptyPages = emptyPages
        )
    }

    private suspend fun <T> withLlamaOcrClient(
        llamaSettings: LlamaOcrSettingsSnapshot,
        block: suspend (RemoteSummaryClient) -> T
    ): T {
        val modelPath = requireNotNull(llamaSettings.modelPath?.takeIf { it.isNotBlank() }) {
            context.getString(R.string.pdf_ocr_llama_error_missing_model)
        }
        require(java.io.File(modelPath).isFile) {
            context.getString(R.string.pdf_ocr_llama_error_missing_model)
        }
        val mmprojPath = requireNotNull(llamaSettings.mmprojPath?.takeIf { it.isNotBlank() }) {
            context.getString(R.string.pdf_ocr_llama_error_missing_mmproj)
        }
        require(java.io.File(mmprojPath).isFile) {
            context.getString(R.string.pdf_ocr_llama_error_missing_mmproj)
        }

        val wasRunning = LlamaService.state.value is ServerState.Running
        val shouldRestoreGeneral = wasRunning &&
            SettingsRepository.isLlamaServerBackend(settingsRepo.pdfTranslationBackend.value) &&
            !settingsRepo.selectedModelPath.value.isNullOrBlank()
        if (llamaSettings.temporarilyReplaceRunningServer) {
            if (wasRunning) {
                LlamaServerLauncher.reconfigureForOcr(context, llamaSettings).getOrThrow()
            } else {
                LlamaServerLauncher.startForOcr(context, llamaSettings).getOrThrow()
            }
            waitForLlamaServerRunning(llamaSettings.port)
        }

        val snapshot = RemoteSummarySettingsSnapshot(
            backend = SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
            ollamaUrl = "",
            llamaServerUrl = llamaSettings.baseUrl,
            llamaSwapUrl = "",
            ollamaModel = null,
            llamaSwapModel = null,
            thinkingEnabled = false,
            llamaServerModelLabel = modelPath.substringAfterLast('/'),
            llamaServerContextTokens = llamaSettings.contextSize,
            llamaServerContextLabel = "${llamaSettings.contextSize} tokens",
            chunkContext = llamaSettings.contextSize,
            chunkMaxTokens = llamaSettings.maxTokens,
            mergeContext = llamaSettings.contextSize,
            mergeMaxTokens = llamaSettings.maxTokens,
            temperature = 0f,
            timeoutMinutes = settingsRepo.pdfTranslationTimeoutMinutes.value.coerceAtLeast(2),
            targetLanguage = settingsRepo.pdfTranslationTargetLanguage.value,
            summaryPrompt = null,
            mergePrompt = null
        )
        val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
        try {
            validateLlamaOcrVisionMetadata(client)
            return block(client)
        } finally {
            client.cancelActiveCall()
            if (llamaSettings.temporarilyReplaceRunningServer) {
                if (shouldRestoreGeneral) {
                    settingsRepo.selectedModelPath.value?.let {
                        LlamaServerLauncher.reconfigureGeneral(context, it)
                    }
                } else {
                    LlamaServerLauncher.stop(context)
                    waitForLlamaServerStopped()
                }
            }
        }
    }

    private suspend fun waitForLlamaServerRunning(port: Int) {
        val ready = withTimeoutOrNull(180_000L) {
            var serverReady = false
            while (!serverReady) {
                when (val state = LlamaService.state.value) {
                    is ServerState.Running -> if (state.port == port) serverReady = true
                    is ServerState.Error -> throw IllegalStateException(state.message)
                    else -> Unit
                }
                if (!serverReady) {
                    delay(500L)
                }
            }
            true
        } == true
        check(ready) { context.getString(R.string.pdf_ocr_llama_error_server_not_ready) }
    }

    private suspend fun waitForLlamaServerStopped() {
        withTimeoutOrNull(30_000L) {
            while (LlamaService.state.value !is ServerState.Stopped) {
                delay(250L)
            }
            true
        }
    }

    private suspend fun validateLlamaOcrVisionMetadata(client: RemoteSummaryClient) {
        val metadata = client.fetchMetadata().getOrElse { error ->
            DebugLog.log("[PDF] llama.cpp OCR metadata unavailable before OCR; continuing: ${error.message}")
            return
        }
        DebugLog.log(
            "[PDF] llama.cpp OCR metadata: vision=${metadata.visionSupported?.toString() ?: "unknown"}, " +
                "mediaMarker=${metadata.llamaMediaMarker?.takeIf { it.isNotBlank() } ?: "default"}"
        )
        if (metadata.visionSupported == false) {
            throw IllegalStateException(context.getString(R.string.pdf_ocr_llama_error_vision_unavailable))
        }
    }

    private suspend fun recognizeBitmapWithLlamaOcr(
        client: RemoteSummaryClient,
        llamaSettings: LlamaOcrSettingsSnapshot,
        bitmap: Bitmap,
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float,
        ocrStrategy: MangaOcrStrategy,
        ocrExecutionMode: MangaOcrExecutionMode = MangaOcrExecutionMode.BATCH,
        exhaustiveLlamaOcrRegions: Boolean = false,
        onRegionProgress: (suspend (Int, Int) -> Unit)? = null
    ): PdfOcrPageResult {
        check(!bitmap.isRecycled) { context.getString(R.string.pdf_ocr_llama_error_recycled_bitmap) }
        val fullPage = Rect(0, 0, bitmap.width, bitmap.height)
        val detectedRegions = if (ocrStrategy != MangaOcrStrategy.FULL_PAGE) {
            detectBubbleOcrRegions(bitmap).map { it.rect }
        } else {
            emptyList()
        }

        // Even when llama.cpp is the selected recognizer, ML Kit supplies conservative text
        // geometry. Its text is retained only for regions where the VLM cannot return usable text.
        val locatorRecognizers = createOcrRecognizers()
        val locatorPage = try {
            recognizeTextBlocks(
                recognizers = locatorRecognizers,
                image = InputImage.fromBitmap(bitmap, 0),
                bitmap = bitmap,
                pageIndex = pageIndex,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight,
                ocrStrategy = MangaOcrStrategy.FULL_PAGE
            )
        } finally {
            locatorRecognizers.forEach { recognizer -> runCatching { recognizer.close() } }
        }
        val locatorBlocks = locatorPage.blocks.mapIndexed { locatorIndex, block ->
            val containingIndex = detectedRegions.indexOfFirst { region ->
                val centerX = (block.box.left + block.box.right) / 2
                val centerY = (block.box.top + block.box.bottom) / 2
                region.contains(centerX, centerY)
            }
            if (containingIndex >= 0) {
                block.copy(
                    containingRegionId = "p${pageIndex + 1}_r${containingIndex + 1}",
                    safeRegionBox = detectedRegions[containingIndex].toPdfOcrBox()
                )
            } else {
                block.copy(
                    containingRegionId = "p${pageIndex + 1}_mlkit${locatorIndex + 1}",
                    provenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK
                )
            }
        }

        val regionalCandidates = when (ocrStrategy) {
            MangaOcrStrategy.FULL_PAGE -> emptyList()
            MangaOcrStrategy.BUBBLE_ONLY,
            MangaOcrStrategy.HYBRID -> detectedRegions
            MangaOcrStrategy.ADAPTIVE -> detectedRegions.take(24)
        }
        val locatorBlocksByRegionId = locatorBlocks
            .filter { block -> block.text.isNotBlank() && !block.containingRegionId.isNullOrBlank() }
            .groupBy { block -> block.containingRegionId.orEmpty() }
        val llamaBudget = MangaTranslationSupport.llamaOcrBudget(
            executionMode = ocrExecutionMode,
            exhaustiveRegions = exhaustiveLlamaOcrRegions
        )
        val prioritizedRegionalRequests = regionalCandidates.mapIndexed { index, region ->
            val regionId = "p${pageIndex + 1}_r${index + 1}"
            val mlKitText = locatorBlocksByRegionId[regionId].orEmpty()
                .joinToString("\n") { block -> block.text }
                .trim()
            Triple(region, regionId, mlKitText)
        }.sortedWith(
            compareByDescending<Triple<Rect, String, String>> { (_, _, text) -> text.isBlank() }
                .thenByDescending { (_, _, text) -> MangaTranslationSupport.mlKitRegionTextLooksWeak(text) }
                .thenBy { (region, _, _) -> region.top }
                .thenBy { (region, _, _) -> region.left }
        ).filterIndexed { requestIndex, (_, _, mlKitText) ->
            MangaTranslationSupport.shouldRunLlamaRegionRequest(
                mlKitText = mlKitText,
                regionIndex = requestIndex,
                budget = llamaBudget
            )
        }
        val skippedCropRequests = (regionalCandidates.size - prioritizedRegionalRequests.size).coerceAtLeast(0)
        if (skippedCropRequests > 0) {
            DebugLog.log(
                "[PDF] llama.cpp OCR skipped $skippedCropRequests regional crop request(s) on page ${pageIndex + 1} " +
                    "for ${ocrExecutionMode.name.lowercase(Locale.US)} budget; ML Kit fallback remains active."
            )
        }
        val requestRegions = listOf(LlamaOcrRegionRequest(fullPage, null, fullPageContext = true)) +
            prioritizedRegionalRequests.map { (region, regionId, _) ->
                LlamaOcrRegionRequest(
                    rect = region,
                    regionId = regionId,
                    fullPageContext = false,
                    originalRegionIndex = regionId.substringAfterLast("_r").toIntOrNull() ?: -1
                )
            }
        val llamaBlocks = mutableListOf<PdfOcrBlock>()
        val successfulRegionIds = mutableSetOf<String>()
        var ungroundedResponses = 0
        var regionalFallbacks = 0
        var plainFallbacksAttempted = 0
        var imageRequestRejected = false
        var promptLeakRejections = 0
        var llamaOcrRequests = 0
        var llamaOcrElapsedMs = 0L
        val totalRequests = requestRegions.size
        requestRegions.forEachIndexed { requestIndex, request ->
            currentCoroutineContext().ensureActive()
            val isFullPageContext = request.fullPageContext
            val regionId = request.regionId
            val regionLabel = regionId ?: "full-page context"
            onRegionProgress?.invoke(requestIndex + 1, totalRequests)
            try {
                withOcrRegionBitmap(bitmap, request.rect) { crop, borrowedFullPage ->
                    val encoded = bitmapToJpegBase64WithStats(crop, 1600, 88)
                    val prompt = llamaOcrPromptForRegion(llamaSettings)
                    val maxTokens = MangaTranslationSupport.llamaOcrRequestMaxTokens(
                        configuredMaxTokens = llamaSettings.maxTokens,
                        fullPageContext = isFullPageContext,
                        plainFallback = false
                    )
                    DebugLog.log(
                        "[PDF] llama.cpp OCR $regionLabel request: " +
                            "image=${crop.width}x${crop.height}, jpegBytes=${encoded.byteCount}, " +
                            "preset=${llamaSettings.promptPreset.label}, borrowedFullPage=$borrowedFullPage, " +
                            "maxTokens=$maxTokens"
                    )
                    val startedAt = System.currentTimeMillis()
                    llamaOcrRequests += 1
                    val response = try {
                        requestLlamaOcr(
                            client = client,
                            llamaSettings = llamaSettings,
                            prompt = prompt,
                            encoded = encoded,
                            maxTokensOverride = maxTokens
                        )
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (isLikelyLlamaImageRequestRejection(error)) {
                            imageRequestRejected = true
                        }
                        if (requestIndex > 0) {
                            regionalFallbacks += 1
                        }
                        DebugLog.log(
                            "[PDF] llama.cpp OCR $regionLabel failed; " +
                                "using ML Kit fallback when available: ${error.message}"
                        )
                        null
                    }.also {
                        llamaOcrElapsedMs += (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                    } ?: return@withOcrRegionBitmap
                    DebugLog.log(
                        "[PDF] llama.cpp OCR $regionLabel response: " +
                            "promptTokens=${response.promptTokens ?: "unknown"}, " +
                            "completionTokens=${response.completionTokens ?: "unknown"}, " +
                            "stop=${response.stopType ?: "unknown"}, chars=${response.output.length}"
                    )
                    val grounded = MangaTranslationSupport.parseGroundedOcrSpans(
                        rawOutput = response.output,
                        imageWidth = crop.width,
                        imageHeight = crop.height
                    )
                    if (grounded.isNotEmpty()) {
                        grounded.forEachIndexed { spanIndex, span ->
                            val mappedBox = span.box.offsetBy(request.rect.left, request.rect.top)
                            val containingIndex = detectedRegions.indexOfFirst { candidate ->
                                val centerX = (mappedBox.left + mappedBox.right) / 2
                                val centerY = (mappedBox.top + mappedBox.bottom) / 2
                                candidate.contains(centerX, centerY)
                            }
                            val groundedRegionId = if (containingIndex >= 0) {
                                "p${pageIndex + 1}_r${containingIndex + 1}"
                            } else {
                                regionId ?: "p${pageIndex + 1}_grounded${spanIndex + 1}"
                            }
                            val safeRegion = if (containingIndex >= 0) {
                                detectedRegions[containingIndex].toPdfOcrBox()
                            } else if (requestIndex > 0) {
                                request.rect.toPdfOcrBox()
                            } else {
                                null
                            }
                            val background = sampleBackgroundColor(bitmap, mappedBox.toRect())
                            llamaBlocks += PdfOcrBlock(
                                pageIndex = pageIndex,
                                blockIndex = llamaBlocks.size,
                                text = span.text,
                                box = mappedBox,
                                lineBoxes = listOf(PdfOcrLine(span.text, mappedBox)),
                                backgroundColor = background,
                                textColor = contrastingTextColor(background),
                                provenance = MangaOcrRegionProvenance.GROUNDED_VLM_SPAN,
                                containingRegionId = groundedRegionId,
                                safeRegionBox = safeRegion,
                                recognitionPass = MangaOcrRecognitionPass.GROUNDED_LLAMA
                            )
                            successfulRegionIds += groundedRegionId
                        }
                    } else {
                        var sanitized = MangaTranslationSupport.sanitizeLlamaOcrText(
                            rawOutput = response.output,
                            prompt = prompt,
                            stopType = response.stopType,
                            imageWidth = crop.width,
                            imageHeight = crop.height
                        )
                        if (sanitized.rejected) {
                            promptLeakRejections += 1
                            DebugLog.log(
                                "[PDF] ${context.getString(
                                    R.string.pdf_ocr_llama_warning_prompt_leak_rejected,
                                    regionLabel,
                                    sanitized.reason ?: "unsafe_output"
                                )}"
                            )
                        }
                        var text = sanitized.text
                        if (looksLikeMalformedGrounding(response.output)) {
                            DebugLog.log(
                                "[PDF] ${context.getString(R.string.pdf_ocr_llama_warning_malformed_grounding, regionLabel)}"
                            )
                        }
                        if (requestIndex > 0 && text.isBlank()) {
                            val mlKitFallbackAvailable = regionId?.let { id ->
                                locatorBlocksByRegionId[id].orEmpty().any { block -> block.text.isNotBlank() }
                            } == true
                            val canTryPlainFallback = MangaTranslationSupport.shouldRunLlamaOcrPlainFallback(
                                mlKitFallbackAvailable = mlKitFallbackAvailable,
                                attemptedPlainFallbacks = plainFallbacksAttempted,
                                maxPlainFallbacks = llamaBudget.maxPlainFallbacksPerPage
                            )
                            if (!canTryPlainFallback) {
                                val message = if (mlKitFallbackAvailable) {
                                    context.getString(
                                        R.string.pdf_ocr_llama_warning_skip_plain_fallback_mlkit,
                                        regionLabel
                                    )
                                } else {
                                    context.getString(
                                        R.string.pdf_ocr_llama_warning_skip_plain_fallback_limit,
                                        regionLabel,
                                        llamaBudget.maxPlainFallbacksPerPage
                                    )
                                }
                                DebugLog.log("[PDF] $message")
                            } else {
                                plainLlamaOcrFallbackPrompt(llamaSettings)?.let { fallbackPrompt ->
                                    plainFallbacksAttempted += 1
                                    val fallbackMaxTokens = MangaTranslationSupport.llamaOcrRequestMaxTokens(
                                        configuredMaxTokens = llamaSettings.maxTokens,
                                        fullPageContext = false,
                                        plainFallback = true
                                    )
                                    DebugLog.log(
                                        "[PDF] llama.cpp OCR plain fallback for $regionLabel request: " +
                                            "attempt=$plainFallbacksAttempted/" +
                                            "${llamaBudget.maxPlainFallbacksPerPage}, " +
                                            "maxTokens=$fallbackMaxTokens"
                                    )
                                    val fallbackStartedAt = System.currentTimeMillis()
                                    llamaOcrRequests += 1
                                    val fallbackResponse = runCatching {
                                        requestLlamaOcr(
                                            client = client,
                                            llamaSettings = llamaSettings,
                                            prompt = fallbackPrompt,
                                            encoded = encoded,
                                            maxTokensOverride = fallbackMaxTokens
                                        )
                                    }.onFailure { error ->
                                        if (error is CancellationException) throw error
                                        DebugLog.log(
                                            "[PDF] llama.cpp OCR plain fallback for $regionLabel failed: ${error.message}"
                                        )
                                    }.also {
                                        llamaOcrElapsedMs += (System.currentTimeMillis() - fallbackStartedAt).coerceAtLeast(0L)
                                    }.getOrNull()
                                    if (fallbackResponse != null) {
                                        sanitized = MangaTranslationSupport.sanitizeLlamaOcrText(
                                            rawOutput = fallbackResponse.output,
                                            prompt = fallbackPrompt,
                                            stopType = fallbackResponse.stopType,
                                            imageWidth = crop.width,
                                            imageHeight = crop.height
                                        )
                                        if (sanitized.rejected) {
                                            promptLeakRejections += 1
                                            DebugLog.log(
                                                "[PDF] ${context.getString(
                                                    R.string.pdf_ocr_llama_warning_prompt_leak_rejected,
                                                    regionLabel,
                                                    sanitized.reason ?: "unsafe_output"
                                                )}"
                                            )
                                        }
                                        text = sanitized.text
                                        DebugLog.log(
                                            "[PDF] llama.cpp OCR plain fallback for $regionLabel " +
                                                "chars=${text.length}, stop=${fallbackResponse.stopType ?: "unknown"}"
                                        )
                                    }
                                }
                            }
                        }
                        if (requestIndex == 0) {
                            if (text.isNotBlank()) {
                                ungroundedResponses += 1
                            } else {
                                DebugLog.log("[PDF] llama.cpp OCR full-page context was blank; continuing with regional OCR and ML Kit.")
                            }
                        } else if (text.isNotBlank()) {
                            val background = sampleBackgroundColor(bitmap, request.rect)
                            llamaBlocks += PdfOcrBlock(
                                pageIndex = pageIndex,
                                blockIndex = llamaBlocks.size,
                                text = text,
                                box = request.rect.toPdfOcrBox(),
                                lineBoxes = text.lines()
                                    .filter { it.isNotBlank() }
                                    .ifEmpty { listOf(text) }
                                    .map { PdfOcrLine(it.trim(), request.rect.toPdfOcrBox()) },
                                backgroundColor = background,
                                textColor = contrastingTextColor(background),
                                provenance = MangaOcrRegionProvenance.DETECTED_BUBBLE,
                                containingRegionId = regionId,
                                safeRegionBox = request.rect.toPdfOcrBox(),
                                recognitionPass = MangaOcrRecognitionPass.REGIONAL_LLAMA
                            )
                            regionId?.let(successfulRegionIds::add)
                        } else {
                            regionalFallbacks += 1
                            DebugLog.log(
                                "[PDF] ${context.getString(R.string.pdf_ocr_llama_warning_blank_fallback, regionLabel)}"
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (requestIndex > 0) {
                    regionalFallbacks += 1
                }
                DebugLog.log(
                    "[PDF] ${context.getString(
                        R.string.pdf_ocr_llama_warning_region_fallback,
                        regionLabel,
                        error.message ?: error.javaClass.simpleName
                    )}"
                )
            }
        }
        val fallbackBlocks = locatorBlocks.filterNot { block ->
            block.containingRegionId != null && block.containingRegionId in successfulRegionIds
        }
        if (imageRequestRejected && llamaBlocks.isEmpty() && fallbackBlocks.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.pdf_ocr_llama_error_image_request_rejected))
        }
        if (llamaBlocks.isEmpty() && fallbackBlocks.isNotEmpty() && regionalFallbacks > 0) {
            DebugLog.log(
                "[PDF] llama.cpp OCR returned no usable regional text; completed page ${pageIndex + 1} with ML Kit fallback blocks."
            )
        }
        return mergeOcrRecognizerResults(
            results = listOf(
                PdfOcrPageResult(
                    pageIndex = pageIndex,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    pdfWidth = pdfWidth,
                    pdfHeight = pdfHeight,
                    blocks = fallbackBlocks + llamaBlocks,
                    ungroundedResponses = ungroundedResponses,
                    regionalFallbacks = regionalFallbacks,
                    promptLeakRejections = promptLeakRejections,
                    skippedLlamaCropRequests = skippedCropRequests,
                    llamaOcrRequests = llamaOcrRequests,
                    llamaOcrElapsedMs = llamaOcrElapsedMs
                )
            ),
            pageIndex = pageIndex,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight
        ).copy(
            ungroundedResponses = ungroundedResponses,
            regionalFallbacks = regionalFallbacks,
            promptLeakRejections = promptLeakRejections,
            skippedLlamaCropRequests = skippedCropRequests,
            llamaOcrRequests = llamaOcrRequests,
            llamaOcrElapsedMs = llamaOcrElapsedMs
        )
    }

    private fun isLikelyLlamaImageRequestRejection(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return listOf(
            "multimodal",
            "media marker",
            "number of media markers",
            "mtmd",
            "image input",
            "vision"
        ).any { message.contains(it) }
    }

    private data class EncodedOcrBitmap(
        val base64: String,
        val byteCount: Int
    )

    private suspend fun <T> withOcrRegionBitmap(
        source: Bitmap,
        region: Rect,
        block: suspend (Bitmap, Boolean) -> T
    ): T {
        check(!source.isRecycled) { context.getString(R.string.pdf_ocr_llama_error_recycled_bitmap) }
        val safeRegion = sanitizedOcrRegion(source, region)
        val isFullPage = safeRegion.left == 0 &&
            safeRegion.top == 0 &&
            safeRegion.right == source.width &&
            safeRegion.bottom == source.height
        val regionBitmap = if (isFullPage) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                safeRegion.left,
                safeRegion.top,
                safeRegion.width().coerceAtLeast(1),
                safeRegion.height().coerceAtLeast(1)
            )
        }
        val ownsBitmap = regionBitmap !== source
        try {
            check(!regionBitmap.isRecycled) { context.getString(R.string.pdf_ocr_llama_error_recycled_bitmap) }
            return block(regionBitmap, isFullPage)
        } finally {
            if (ownsBitmap && !regionBitmap.isRecycled) {
                regionBitmap.recycle()
            }
        }
    }

    private fun sanitizedOcrRegion(source: Bitmap, region: Rect): Rect {
        check(source.width > 0 && source.height > 0) { context.getString(R.string.pdf_ocr_llama_error_recycled_bitmap) }
        val left = region.left.coerceIn(0, source.width - 1)
        val top = region.top.coerceIn(0, source.height - 1)
        val right = region.right.coerceIn(left + 1, source.width)
        val bottom = region.bottom.coerceIn(top + 1, source.height)
        return Rect(left, top, right, bottom)
    }

    private suspend fun requestLlamaOcr(
        client: RemoteSummaryClient,
        llamaSettings: LlamaOcrSettingsSnapshot,
        prompt: String,
        encoded: EncodedOcrBitmap,
        maxTokensOverride: Int? = null
    ): RemoteSummaryResponse {
        return client.summarize(
            RemoteSummaryRequest(
                systemPrompt = "You are a precise OCR engine. Return only recognized text. Preserve reading order and line breaks.",
                userPrompt = prompt,
                contextSize = llamaSettings.contextSize,
                maxTokens = maxTokensOverride ?: llamaSettings.maxTokens,
                temperature = 0f,
                thinkingEnabled = false,
                imageAttachments = listOf(RemoteSummaryImageAttachment(encoded.base64)),
                preferLlamaMultimodalCompletion = true,
                allowBlankOutput = true
            )
        )
    }

    private fun llamaOcrPromptForRegion(llamaSettings: LlamaOcrSettingsSnapshot): String {
        val prompt = llamaSettings.prompt.trim()
        val needsGroundedOverlayPrompt = llamaSettings.promptPreset == LlamaOcrPromptPreset.UNLIMITED_OCR ||
            llamaSettings.promptPreset == LlamaOcrPromptPreset.DEEPSEEK_OCR
        return if (needsGroundedOverlayPrompt && !prompt.contains("<|grounding|>", ignoreCase = true)) {
            "<|grounding|>Convert the document to markdown."
        } else {
            prompt
        }
    }

    private fun plainLlamaOcrFallbackPrompt(llamaSettings: LlamaOcrSettingsSnapshot): String? {
        val supportsPlainFallback = llamaSettings.promptPreset == LlamaOcrPromptPreset.UNLIMITED_OCR ||
            llamaSettings.promptPreset == LlamaOcrPromptPreset.DEEPSEEK_OCR
        if (!supportsPlainFallback) return null
        val fallback = "Free OCR."
        return fallback.takeUnless { it.equals(llamaSettings.prompt.trim(), ignoreCase = true) }
    }

    private fun looksLikeMalformedGrounding(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return "<|det|>" in lower ||
            "<|grounding|>" in lower ||
            Regex("""\[[\s-]*\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*]""")
                .containsMatchIn(raw)
    }

    private fun loadComicImageBitmapForOcr(imageFile: File): Bitmap {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val originalWidth = bounds.outWidth.takeIf { it > 0 } ?: 1
        val originalHeight = bounds.outHeight.takeIf { it > 0 } ?: 1
        val longestSide = maxOf(originalWidth, originalHeight)
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= 3200) {
            sampleSize *= 2
        }
        val decoded = android.graphics.BitmapFactory.decodeFile(
            imageFile.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IllegalStateException("Could not decode image page: ${imageFile.name}")
        return if (decoded.config == Bitmap.Config.ARGB_8888 && !decoded.hasAlpha()) {
            decoded
        } else {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { flattened ->
                Canvas(flattened).drawColor(Color.WHITE)
                Canvas(flattened).drawBitmap(decoded, 0f, 0f, null)
                decoded.recycle()
            }
        }
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap, maxSide: Int, quality: Int): String {
        return bitmapToJpegBase64WithStats(bitmap, maxSide, quality).base64
    }

    private fun bitmapToJpegBase64WithStats(bitmap: Bitmap, maxSide: Int, quality: Int): EncodedOcrBitmap {
        check(!bitmap.isRecycled) { context.getString(R.string.pdf_ocr_llama_error_recycled_bitmap) }
        val scale = (maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)).coerceAtMost(1f)
        val encodedBitmap = if (scale < 0.995f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        try {
            check(!encodedBitmap.isRecycled) { context.getString(R.string.pdf_ocr_llama_error_recycled_bitmap) }
            val output = ByteArrayOutputStream()
            encodedBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), output)
            val bytes = output.toByteArray()
            return EncodedOcrBitmap(
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                byteCount = bytes.size
            )
        } finally {
            if (encodedBitmap !== bitmap) encodedBitmap.recycle()
        }
    }

    private fun cleanLlamaOcrText(raw: String): String {
        return PDFSummaryLogic.cleanLlamaOutput(raw)
            .replace(Regex("""```(?:\w+)?"""), "")
            .replace("```", "")
            .replace(Regex("""<\|[^>]+>"""), "")
            .lines()
            .map { line -> line.trim() }
            .dropWhile { it.equals("text", ignoreCase = true) || it.equals("ocr", ignoreCase = true) }
            .joinToString("\n")
            .trim()
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
        pdfHeight: Float,
        ocrStrategy: MangaOcrStrategy = MangaOcrStrategy.FULL_PAGE
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
        val fullPage = mergeOcrRecognizerResults(
            results,
            pageIndex,
            bitmap.width,
            bitmap.height,
            pdfWidth,
            pdfHeight
        )
        if (ocrStrategy == MangaOcrStrategy.FULL_PAGE) return fullPage

        val shouldRunGuided = when (ocrStrategy) {
            MangaOcrStrategy.HYBRID,
            MangaOcrStrategy.BUBBLE_ONLY -> true
            MangaOcrStrategy.ADAPTIVE ->
                fullPage.blocks.size < 4 || fullPage.blocks.sumOf { it.text.length } < 48
            MangaOcrStrategy.FULL_PAGE -> false
        }
        if (!shouldRunGuided) return fullPage

        val detectedRegions = detectBubbleOcrRegions(bitmap)
        val guided = recognizeTextBlocksWithBubbleGuidance(
            recognizers = recognizers,
            bitmap = bitmap,
            pageIndex = pageIndex,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight,
            regions = detectedRegions
        )
        if (guided.blocks.isEmpty()) return fullPage
        DebugLog.log(
            "[PDF] Hybrid OCR page ${pageIndex + 1}: " +
                "${fullPage.blocks.size} full-page + ${guided.blocks.size} regional block(s)"
        )
        return if (ocrStrategy == MangaOcrStrategy.BUBBLE_ONLY) {
            guided
        } else {
            reconcileMlKitOcrResults(
                fullPage = fullPage,
                guided = guided,
                regions = detectedRegions,
                pageIndex = pageIndex,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight
            )
        }
    }

    private suspend fun recognizeTextBlocksWithBubbleGuidance(
        recognizers: List<TextRecognizer>,
        bitmap: Bitmap,
        pageIndex: Int,
        pdfWidth: Float,
        pdfHeight: Float,
        regions: List<BubbleOcrRegion> = detectBubbleOcrRegions(bitmap)
    ): PdfOcrPageResult {
        if (regions.isEmpty()) {
            return PdfOcrPageResult(pageIndex, bitmap.width, bitmap.height, pdfWidth, pdfHeight, emptyList())
        }
        val blocks = mutableListOf<PdfOcrBlock>()
        regions.forEachIndexed { regionIndex, region ->
            currentCoroutineContext().ensureActive()
            val crop = Bitmap.createBitmap(
                bitmap,
                region.rect.left,
                region.rect.top,
                region.rect.width().coerceAtLeast(1),
                region.rect.height().coerceAtLeast(1)
            )
            try {
                val result = recognizeTextBlocks(
                    recognizers = recognizers,
                    image = InputImage.fromBitmap(crop, 0),
                    bitmap = crop,
                    pageIndex = pageIndex,
                    pdfWidth = region.rect.width().toFloat(),
                    pdfHeight = region.rect.height().toFloat(),
                    ocrStrategy = MangaOcrStrategy.FULL_PAGE
                )
                result.blocks.forEach { block ->
                    blocks += block.copy(
                        box = block.box.offsetBy(region.rect.left, region.rect.top),
                        lineBoxes = block.lineBoxes.map { line -> line.copy(box = line.box.offsetBy(region.rect.left, region.rect.top)) },
                        backgroundColor = sampleBackgroundColor(bitmap, region.rect),
                        textColor = contrastingTextColor(sampleBackgroundColor(bitmap, region.rect)),
                        provenance = MangaOcrRegionProvenance.DETECTED_BUBBLE,
                        containingRegionId = "p${pageIndex + 1}_r${regionIndex + 1}",
                        safeRegionBox = region.rect.toPdfOcrBox(),
                        recognitionPass = MangaOcrRecognitionPass.REGIONAL_ML_KIT
                    )
                }
            } finally {
                crop.recycle()
            }
        }
        return mergeOcrRecognizerResults(
            results = listOf(
                PdfOcrPageResult(
                    pageIndex = pageIndex,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    pdfWidth = pdfWidth,
                    pdfHeight = pdfHeight,
                    blocks = blocks
                )
            ),
            pageIndex = pageIndex,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight
        )
    }

    private fun reconcileMlKitOcrResults(
        fullPage: PdfOcrPageResult,
        guided: PdfOcrPageResult,
        regions: List<BubbleOcrRegion>,
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): PdfOcrPageResult {
        val assignedFullPage = fullPage.blocks.mapIndexed { orphanIndex, block ->
            val regionIndex = bestContainingRegionIndex(block.box, regions)
            if (regionIndex >= 0) {
                block.copy(
                    containingRegionId = "p${pageIndex + 1}_r${regionIndex + 1}",
                    safeRegionBox = regions[regionIndex].rect.toPdfOcrBox(),
                    recognitionPass = MangaOcrRecognitionPass.FULL_PAGE_ML_KIT
                )
            } else {
                block.copy(
                    containingRegionId = "p${pageIndex + 1}_orphan${orphanIndex + 1}",
                    recognitionPass = MangaOcrRecognitionPass.FULL_PAGE_ML_KIT
                )
            }
        }
        val fullByRegion = assignedFullPage.groupBy { it.containingRegionId.orEmpty() }
        val guidedByRegion = guided.blocks.groupBy { it.containingRegionId.orEmpty() }
        val selected = mutableListOf<PdfOcrBlock>()
        val regionIds = (fullByRegion.keys + guidedByRegion.keys).filter { it.isNotBlank() }.distinct()
        regionIds.forEach { regionId ->
            val fullCandidates = fullByRegion[regionId].orEmpty()
            val guidedCandidates = guidedByRegion[regionId].orEmpty()
            val preferred = MangaTranslationSupport.preferredMlKitRecognitionPass(
                fullPageTexts = fullCandidates.map { it.text },
                regionalTexts = guidedCandidates.map { it.text }
            )
            val chosen = when (preferred) {
                MangaOcrRecognitionPass.REGIONAL_ML_KIT -> guidedCandidates
                MangaOcrRecognitionPass.FULL_PAGE_ML_KIT -> fullCandidates
                else -> fullCandidates.ifEmpty { guidedCandidates }
            }
            selected += dedupeOcrBlocksWithinRegion(chosen)
        }
        val reconciledCount = (assignedFullPage.size + guided.blocks.size - selected.size).coerceAtLeast(0)
        if (reconciledCount > 0) {
            DebugLog.log(
                "[PDF] Reconciled $reconciledCount overlapping full-page/regional ML Kit OCR alternative(s) " +
                    "on page ${pageIndex + 1}."
            )
        }
        return PdfOcrPageResult(
            pageIndex = pageIndex,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight,
            blocks = selected
                .sortedWith(compareBy<PdfOcrBlock> { it.box.top }.thenBy { it.box.left })
                .mapIndexed { index, block -> block.copy(blockIndex = index) },
            reconciledOcrAlternatives = reconciledCount
        )
    }

    private fun bestContainingRegionIndex(
        box: PdfOcrBox,
        regions: List<BubbleOcrRegion>
    ): Int {
        if (regions.isEmpty()) return -1
        val centerX = (box.left + box.right) / 2
        val centerY = (box.top + box.bottom) / 2
        return regions.indices
            .map { index ->
                val regionBox = regions[index].rect.toPdfOcrBox()
                val containsCenter = regions[index].rect.contains(centerX, centerY)
                val overlap = ocrBoxOverlapRatio(box, regionBox)
                Triple(index, containsCenter, overlap)
            }
            .filter { (_, containsCenter, overlap) -> containsCenter || overlap >= 0.22f }
            .maxWithOrNull(
                compareBy<Triple<Int, Boolean, Float>> { if (it.second) 1 else 0 }
                    .thenBy { it.third }
            )
            ?.first
            ?: -1
    }

    private fun dedupeOcrBlocksWithinRegion(blocks: List<PdfOcrBlock>): List<PdfOcrBlock> {
        val accepted = mutableListOf<PdfOcrBlock>()
        blocks.sortedWith(compareBy<PdfOcrBlock> { it.box.top }.thenBy { it.box.left }).forEach { candidate ->
            val normalizedCandidate = candidate.text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
            val duplicateIndex = accepted.indexOfFirst { existing ->
                val normalizedExisting = existing.text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
                ocrBlocksLookDuplicate(existing, candidate) ||
                    normalizedCandidate.length >= 5 &&
                    normalizedExisting.length >= 5 &&
                    (normalizedCandidate.contains(normalizedExisting) ||
                        normalizedExisting.contains(normalizedCandidate))
            }
            if (duplicateIndex < 0) {
                accepted += candidate
            } else if (candidate.text.length > accepted[duplicateIndex].text.length) {
                accepted[duplicateIndex] = candidate
            }
        }
        return accepted
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
                } else {
                    val existing = merged[existingIndex]
                    val candidateHasGeometry = candidate.containingRegionId != null ||
                        candidate.provenance == MangaOcrRegionProvenance.GROUNDED_VLM_SPAN
                    val existingHasGeometry = existing.containingRegionId != null ||
                        existing.provenance == MangaOcrRegionProvenance.GROUNDED_VLM_SPAN
                    if (
                        candidateHasGeometry && !existingHasGeometry ||
                        candidateHasGeometry == existingHasGeometry && candidate.text.length > existing.text.length
                    ) {
                        merged[existingIndex] = candidate
                    }
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
        if (
            first.containingRegionId != null &&
            second.containingRegionId != null &&
            first.containingRegionId != second.containingRegionId
        ) {
            return false
        }
        val overlap = ocrBoxOverlapRatio(first.box, second.box)
        val firstText = first.text.filterNot(Char::isWhitespace)
        val secondText = second.text.filterNot(Char::isWhitespace)
        val sameText = firstText.isNotBlank() &&
            secondText.isNotBlank() &&
            firstText.equals(secondText, ignoreCase = true)
        if ((first.containingRegionId == null) != (second.containingRegionId == null)) {
            return sameText && overlap >= 0.35f
        }
        return overlap >= 0.72f || sameText && overlap >= 0.35f
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

    private fun detectBubbleOcrRegions(bitmap: Bitmap): List<BubbleOcrRegion> {
        val targetLongSide = 640
        val scale = (targetLongSide.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)).coerceAtMost(1f)
        val sampleWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val sampleHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val sample = if (sampleWidth == bitmap.width && sampleHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
        try {
            val mask = BooleanArray(sampleWidth * sampleHeight)
            for (y in 0 until sampleHeight) {
                for (x in 0 until sampleWidth) {
                    val color = sample.getPixel(x, y)
                    val red = Color.red(color)
                    val green = Color.green(color)
                    val blue = Color.blue(color)
                    val maxChannel = maxOf(red, green, blue)
                    val minChannel = minOf(red, green, blue)
                    val brightness = (red + green + blue) / 3
                    mask[y * sampleWidth + x] = brightness >= 205 && maxChannel - minChannel <= 42
                }
            }

            val visited = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            val regions = mutableListOf<BubbleOcrRegion>()
            for (start in mask.indices) {
                if (!mask[start] || visited[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var minX = sampleWidth
                var minY = sampleHeight
                var maxX = 0
                var maxY = 0
                var area = 0
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % sampleWidth
                    val y = index / sampleWidth
                    area += 1
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                    tail = addBubbleMaskNeighbor(x - 1, y, sampleWidth, sampleHeight, mask, visited, queue, tail)
                    tail = addBubbleMaskNeighbor(x + 1, y, sampleWidth, sampleHeight, mask, visited, queue, tail)
                    tail = addBubbleMaskNeighbor(x, y - 1, sampleWidth, sampleHeight, mask, visited, queue, tail)
                    tail = addBubbleMaskNeighbor(x, y + 1, sampleWidth, sampleHeight, mask, visited, queue, tail)
                }
                val width = maxX - minX + 1
                val height = maxY - minY + 1
                val imageArea = sampleWidth * sampleHeight
                val areaRatio = area.toFloat() / imageArea.coerceAtLeast(1)
                val rectArea = (width * height).coerceAtLeast(1)
                val fillRatio = area.toFloat() / rectArea.toFloat()
                val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
                val plausibleSize = areaRatio in 0.0015f..0.18f &&
                    width >= sampleWidth * 0.035f &&
                    height >= sampleHeight * 0.012f &&
                    width <= sampleWidth * 0.72f &&
                    height <= sampleHeight * 0.38f
                val plausibleShape = aspect in 0.18f..8.0f && fillRatio >= 0.34f
                if (plausibleSize && plausibleShape) {
                    val padX = maxOf(4, (width * 0.08f).roundToInt())
                    val padY = maxOf(4, (height * 0.10f).roundToInt())
                    val sourceRect = Rect(
                        ((minX - padX) / scale).roundToInt().coerceIn(0, bitmap.width - 1),
                        ((minY - padY) / scale).roundToInt().coerceIn(0, bitmap.height - 1),
                        ((maxX + 1 + padX) / scale).roundToInt().coerceIn(1, bitmap.width),
                        ((maxY + 1 + padY) / scale).roundToInt().coerceIn(1, bitmap.height)
                    )
                    if (sourceRect.width() > 2 && sourceRect.height() > 2) {
                        regions += BubbleOcrRegion(sourceRect, score = fillRatio * areaRatio)
                    }
                }
            }
            return regions
                .sortedWith(compareBy<BubbleOcrRegion> { it.rect.top }.thenBy { it.rect.left })
                .let(::dedupeBubbleRegions)
                .take(96)
                .also { DebugLog.log("[PDF] Bubble-guided OCR detected ${it.size} region(s)") }
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun addBubbleMaskNeighbor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int
    ): Int {
        if (x !in 0 until width || y !in 0 until height) return tail
        val index = y * width + x
        if (!mask[index] || visited[index] || tail >= queue.size) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun dedupeBubbleRegions(regions: List<BubbleOcrRegion>): List<BubbleOcrRegion> {
        val accepted = mutableListOf<BubbleOcrRegion>()
        regions.sortedByDescending { it.score }.forEach { candidate ->
            val duplicate = accepted.any { existing ->
                ocrBoxOverlapRatio(candidate.rect.toPdfOcrBox(), existing.rect.toPdfOcrBox()) > 0.38f
            }
            if (!duplicate) accepted += candidate
        }
        return accepted.sortedWith(
            compareBy<BubbleOcrRegion> { it.rect.top }.thenBy { it.rect.left }
        )
    }

    private fun PdfOcrBox.offsetBy(dx: Int, dy: Int): PdfOcrBox =
        PdfOcrBox(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)

    private fun Rect.toPdfOcrBox(): PdfOcrBox {
        return PdfOcrBox(left = left, top = top, right = right, bottom = bottom)
    }

    private fun PdfOcrBox.toRect(): Rect {
        return Rect(left, top, right, bottom)
    }

    private fun sampleBackgroundColor(bitmap: Bitmap, rawRect: Rect?): Int {
        if (bitmap.isRecycled) {
            DebugLog.log("[PDF] ${context.getString(R.string.pdf_ocr_llama_warning_recycled_bitmap_background)}")
            return Color.WHITE
        }
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
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile
        )
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
        runConfig: MangaTranslationRunConfig? = null,
        jobId: String? = null,
        executionController: PDFTranslationExecutionController? = null,
        onFileStarted: ((Int, Int, String) -> Unit)? = null,
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
                val sourceKind = MangaTranslationSupport.sourceKindFor(
                    name = sourceName,
                    mimeType = context.contentResolver.getType(cbzUri)
                ) ?: MangaTranslationSourceKind.CBZ
                onFileStarted?.invoke(fileIndex + 1, cbzUris.size, sourceName)
                UnifiedNotificationManager.updateProgress(
                    notificationId,
                    fileIndex.toFloat() / cbzUris.size.toFloat(),
                    context.getString(R.string.workflow_manga_notification_file, fileIndex + 1, cbzUris.size, sourceName)
                )
                val result = runCatching {
                    val resumeWorkDir = jobId?.let {
                        File(
                            context.filesDir,
                            "manga_translation_jobs/$it/runtime/${Integer.toHexString(cbzUri.toString().hashCode())}"
                        )
                    }
                    when (sourceKind) {
                        MangaTranslationSourceKind.PDF -> translateSingleMangaPdf(
                            pdfUri = cbzUri,
                            sourceName = sourceName,
                            exportPdf = exportPdf,
                            exportCbz = exportCbz,
                            settingsOverride = settingsOverride,
                            runConfig = runConfig,
                            workDirOverride = resumeWorkDir,
                            executionController = executionController,
                            notificationId = notificationId,
                            onProgress = onProgress
                        )
                        MangaTranslationSourceKind.CBZ -> translateSingleCbz(
                            cbzUri = cbzUri,
                            sourceName = sourceName,
                            exportPdf = exportPdf,
                            exportCbz = exportCbz,
                            settingsOverride = settingsOverride,
                            runConfig = runConfig,
                            workDirOverride = resumeWorkDir,
                            executionController = executionController,
                            notificationId = notificationId,
                            onProgress = onProgress
                        )
                    }
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

    suspend fun translateMangaBatch(
        spec: MangaTranslationJobSpec,
        executionController: PDFTranslationExecutionController? = null,
        onFileStarted: ((Int, Int, String) -> Unit)? = null,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)? = null
    ): Result<List<MangaTranslationFileResult>> {
        val preflight = MangaTranslationSupport.preflight(spec)
        if (!preflight.canRun) {
            return Result.failure(
                IllegalArgumentException(
                    preflight.blockers.joinToString { issue -> issue.code.name }
                )
            )
        }
        val resolvedOptions = spec.config.resolvedTranslationOptions()
        val resolvedConfig = spec.config.copy(
            translationConfig = spec.config.translationConfig.copy(
                settings = spec.config.resolvedTranslationSettings(),
                usePageImageContext = resolvedOptions.usePageScreenshotContext,
                pageImageMaxSide = resolvedOptions.screenshotMaxSide,
                pageImageJpegQuality = resolvedOptions.screenshotJpegQuality,
                textOnlyFallbackEnabled = resolvedOptions.textOnlyFallbackEnabled,
                qualityMode = resolvedOptions.qualityMode
            ),
            ocrConfig = spec.config.ocrConfig.copy(
                provider = resolvedOptions.ocrProvider,
                strategy = spec.config.behavior.ocrStrategy,
                llamaOcr = resolvedOptions.llamaOcr
            )
        )
        runCatching { verifyMangaTranslationBackend(resolvedConfig.translationSettings) }
            .getOrElse { error ->
                return Result.failure(
                    PDFTranslationDisplayException(
                        displayMessage = error.message ?: "Translation backend is unavailable",
                        displayDetails = error.cause?.message
                    )
                )
            }
        val runtimeConfig = resolveMangaVisionCapability(resolvedConfig)
        val jobsRoot = File(context.filesDir, "manga_translation_jobs")
        val manifestFile = File(jobsRoot, "${spec.jobId}/manifest.json")
        val existingManifest = MangaTranslationSupport.readManifest(manifestFile, runtimeConfig)
        var manifest = existingManifest?.copy(
            spec = spec.copy(config = runtimeConfig),
            status = MangaTranslationSupport.STATUS_RUNNING,
            updatedAt = System.currentTimeMillis()
        ) ?: MangaTranslationJobManifest(spec = spec.copy(config = runtimeConfig))
        MangaTranslationSupport.writeManifest(manifestFile, manifest)
        val pendingSources = spec.sources.filterNot { it.uri.toString() in manifest.completedSourceUris }
        if (pendingSources.isEmpty()) {
            manifest = manifest.copy(
                status = MangaTranslationSupport.STATUS_COMPLETE,
                updatedAt = System.currentTimeMillis()
            )
            MangaTranslationSupport.writeManifest(manifestFile, manifest)
            return Result.success(emptyList())
        }
        val result = translateMangaCbzBatch(
            cbzUris = pendingSources.map { it.uri },
            exportPdf = spec.exportPdf,
            exportCbz = spec.exportCbz,
            settingsOverride = runtimeConfig.translationSettings,
            runConfig = runtimeConfig,
            jobId = spec.jobId,
            executionController = executionController,
            onFileStarted = { index, total, name ->
                manifest = manifest.copy(
                    currentFileIndex = index,
                    updatedAt = System.currentTimeMillis()
                )
                MangaTranslationSupport.writeManifest(manifestFile, manifest)
                onFileStarted?.invoke(index, total, name)
            },
            onProgress = onProgress
        )
        val completedUris = manifest.completedSourceUris.toMutableSet()
        val finishedResults = result.getOrNull()
            ?: (result.exceptionOrNull() as? PDFTranslationCancelledException)?.mangaResults
            ?: emptyList()
        finishedResults.forEachIndexed { index, fileResult ->
            if (fileResult.isSuccess) {
                pendingSources.getOrNull(index)?.uri?.toString()?.let(completedUris::add)
            }
        }
        manifest = manifest.copy(
            completedSourceUris = completedUris,
            status = if (completedUris.containsAll(spec.sources.map { it.uri.toString() })) {
                MangaTranslationSupport.STATUS_COMPLETE
            } else if (result.isFailure) {
                MangaTranslationSupport.STATUS_FAILED
            } else {
                MangaTranslationSupport.STATUS_RUNNING
            },
            updatedAt = System.currentTimeMillis()
        )
        MangaTranslationSupport.writeManifest(manifestFile, manifest)
        return result
    }

    suspend fun previewMangaFirstPage(
        spec: MangaTranslationJobSpec,
        executionController: PDFTranslationExecutionController? = null,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)? = null
    ): Result<MangaTranslationPreviewResult> = withContext(Dispatchers.IO) {
        runCatching {
            val source = spec.sources.firstOrNull()
                ?: throw IllegalArgumentException("Select a manga/comic file first")
            val preflight = MangaTranslationSupport.preflight(spec)
            if (!preflight.canRun) {
                throw IllegalArgumentException(preflight.blockers.joinToString { it.code.name })
            }
            val resolvedOptions = spec.config.resolvedTranslationOptions()
            val config = spec.config.copy(
                translationConfig = spec.config.translationConfig.copy(
                    settings = spec.config.resolvedTranslationSettings(),
                    usePageImageContext = resolvedOptions.usePageScreenshotContext,
                    pageImageMaxSide = resolvedOptions.screenshotMaxSide,
                    pageImageJpegQuality = resolvedOptions.screenshotJpegQuality,
                    textOnlyFallbackEnabled = resolvedOptions.textOnlyFallbackEnabled,
                    qualityMode = resolvedOptions.qualityMode
                ),
                ocrConfig = spec.config.ocrConfig.copy(
                    provider = resolvedOptions.ocrProvider,
                    strategy = spec.config.behavior.ocrStrategy,
                    llamaOcr = resolvedOptions.llamaOcr
                )
            )
            verifyMangaTranslationBackend(config.translationSettings)
            val runtimeConfig = resolveMangaVisionCapability(config)
            val workDir = File(
                context.cacheDir,
                "manga_preview_work/${spec.jobId}_${System.currentTimeMillis()}"
            ).apply { mkdirs() }
            val previewDir = File(context.cacheDir, "manga_previews").apply { mkdirs() }
            val notificationId = UnifiedNotificationManager.startTask(
                UnifiedNotificationManager.TaskType.PDF_TRANSLATION,
                context.getString(R.string.workflow_manga_preview_action)
            )
            try {
                onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.EXTRACTING, 0, 1, 0, 0))
                val sourceKind = MangaTranslationSupport.sourceKindFor(source.displayName, source.mimeType)
                    ?: throw IllegalArgumentException("Unsupported manga/comic file")
                val originalPage = when (sourceKind) {
                    MangaTranslationSourceKind.CBZ ->
                        extractCbzImages(source.uri, workDir).firstOrNull()
                    MangaTranslationSourceKind.PDF ->
                        renderFirstPdfPageToPng(source.uri, File(workDir, "preview_source.png"))
                } ?: throw IllegalStateException(context.getString(R.string.workflow_manga_error_no_rendered_pages))

                val quality = MangaTranslationQualityAccumulator()
                val ocrResult = collectImageOcrText(
                    imageFiles = listOf(originalPage),
                    optionsOverride = runtimeConfig.translationOptions,
                    ocrStrategy = runtimeConfig.behavior.ocrStrategy,
                    ocrExecutionMode = MangaOcrExecutionMode.PREVIEW,
                    exhaustiveLlamaOcrRegions = runtimeConfig.behavior.exhaustiveLlamaOcrRegions
                ) { progress ->
                    onProgress?.invoke(
                        PdfOcrTranslationProgress(
                            PdfOcrTranslationStage.OCR,
                            progress.processedPages,
                            progress.totalPages,
                            0,
                            0,
                            currentRegion = progress.currentRegion,
                            totalRegions = progress.totalRegions,
                            detailText = progress.detailText
                        )
                    )
                }.getOrThrow()
                quality.ungroundedOcrResponses += ocrResult.pages.sumOf { it.ungroundedResponses }
                quality.regionalOcrFallbacks += ocrResult.pages.sumOf { it.regionalFallbacks }
                quality.reconciledOcrAlternatives += ocrResult.pages.sumOf { it.reconciledOcrAlternatives }
                quality.promptLeakRejections += ocrResult.pages.sumOf { it.promptLeakRejections }
                quality.skippedLlamaCropRequests += ocrResult.pages.sumOf { it.skippedLlamaCropRequests }
                quality.llamaOcrRequests += ocrResult.pages.sumOf { it.llamaOcrRequests }
                quality.llamaOcrElapsedMs += ocrResult.pages.sumOf { it.llamaOcrElapsedMs }
                val previewOcrBlocks = ocrResult.blocks.count { it.text.isNotBlank() }.coerceAtLeast(1)
                DebugLog.log(
                    "[PDF] Manga preview OCR complete with $previewOcrBlocks text block(s); starting translation."
                )
                onProgress?.invoke(
                    PdfOcrTranslationProgress(
                        stage = PdfOcrTranslationStage.TRANSLATING,
                        processedPages = 0,
                        totalPages = 1,
                        translatedBlocks = 0,
                        totalBlocks = previewOcrBlocks
                    )
                )
                val prepared = prepareOcrTranslation(
                    sourcePdfUri = Uri.EMPTY,
                    ocrResult = ocrResult,
                    settingsOverride = runtimeConfig.translationSettings,
                    optionsOverride = runtimeConfig.translationOptions,
                    mangaConfig = runtimeConfig,
                    executionController = executionController,
                    onProgress = onProgress,
                    notificationId = notificationId,
                    pageScreenshotProvider = {
                        RemoteSummaryImageAttachment(
                            base64 = renderImagePageScreenshotBase64(
                                originalPage,
                                runtimeConfig.translationOptions.screenshotMaxSide,
                                runtimeConfig.translationOptions.screenshotJpegQuality
                            )
                        )
                    },
                    qualityAccumulator = quality
                )
                val translatedPage = renderTranslatedComicPagesToPng(
                    imageFiles = listOf(originalPage),
                    ocrResult = ocrResult,
                    prepared = prepared,
                    workDir = workDir,
                    baseName = "preview",
                    executionController = executionController,
                    onProgress = onProgress,
                    qualityAccumulator = quality
                ).firstOrNull() ?: throw IllegalStateException(
                    context.getString(R.string.workflow_manga_error_no_rendered_pages)
                )
                if (runtimeConfig.pageImageContextReason == "vision_probe_failed") {
                    quality.visionFallbacks += 1
                }
                val fingerprint = config.fingerprint()
                val originalCopy = File(previewDir, "${spec.jobId}_${fingerprint.take(10)}_original.png")
                val translatedCopy = File(previewDir, "${spec.jobId}_${fingerprint.take(10)}_translated.png")
                originalPage.copyTo(originalCopy, overwrite = true)
                translatedPage.copyTo(translatedCopy, overwrite = true)
                UnifiedNotificationManager.completeTask(
                    notificationId,
                    context.getString(R.string.workflow_manga_preview_ready)
                )
                MangaTranslationPreviewResult(
                    sourceName = source.displayName,
                    originalPageUri = Uri.fromFile(originalCopy),
                    translatedPageUri = Uri.fromFile(translatedCopy),
                    configFingerprint = fingerprint,
                    qualityReport = MangaTranslationQualityReport(
                        totalPages = 1,
                        emptyOcrPages = ocrResult.emptyPages,
                        weakTranslationsRetried = quality.weakTranslationsRetried,
                        textOnlyFallbacks = quality.textOnlyFallbacks,
                        jsonRepairs = quality.jsonRepairs,
                        plainTextFallbacks = quality.plainTextFallbacks,
                        untranslatedUnits = quality.untranslatedUnits,
                        visionFallbacks = quality.visionFallbacks,
                        blankOverlayUnits = quality.blankOverlayUnits,
                        clippedOverlayUnits = quality.clippedOverlayUnits,
                        ungroundedOcrResponses = quality.ungroundedOcrResponses,
                        regionalOcrFallbacks = quality.regionalOcrFallbacks,
                        rejectedCrossRegionMerges = quality.rejectedCrossRegionMerges,
                        promptLeakRejections = quality.promptLeakRejections,
                        skippedLlamaCropRequests = quality.skippedLlamaCropRequests,
                        llamaOcrRequests = quality.llamaOcrRequests,
                        llamaOcrElapsedMs = quality.llamaOcrElapsedMs,
                        decorativeTextPreserved = quality.decorativeTextPreserved,
                        skippedOverlayUnits = quality.skippedOverlayUnits,
                        liteRtRuntimeFallbacks = quality.liteRtRuntimeFallbacks,
                        reconciledOcrAlternatives = quality.reconciledOcrAlternatives,
                        coalescedBubbleFragments = quality.coalescedBubbleFragments,
                        incompleteTranslationRetries = quality.incompleteTranslationRetries,
                        wholeBubblesPreserved = quality.wholeBubblesPreserved,
                        resolvedReadingDirection = prepared.resolvedReadingDirection
                    )
                )
            } finally {
                runCatching { workDir.deleteRecursively() }
            }
        }
    }

    private fun renderFirstPdfPageToPng(pdfUri: Uri, outputFile: File): File {
        val cachedPdf = copyPdfToCache(pdfUri)
        try {
            ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    check(renderer.pageCount > 0) { "PDF has no pages" }
                    renderer.openPage(0).use { page ->
                        val scale = computeOcrScale(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).roundToInt().coerceAtLeast(page.width),
                            (page.height * scale).roundToInt().coerceAtLeast(page.height),
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            FileOutputStream(outputFile).use { output ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            return outputFile
        } finally {
            cachedPdf.delete()
        }
    }

    private suspend fun verifyMangaTranslationBackend(snapshot: RemoteSummarySettingsSnapshot) {
        val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
        try {
            val isLiteRt = SettingsRepository.isLiteRtBackend(snapshot.backend)
            if (isLiteRt) {
                val metadata = client.fetchMetadata().getOrThrow()
                check(!metadata.selectedModel.isNullOrBlank()) {
                    context.getString(R.string.workflow_manga_model_required)
                }
                DebugLog.log(
                    "[PDF] LiteRT manga readiness validated from metadata: " +
                        "model=${metadata.selectedModel}, vision=${metadata.visionSupported ?: "unknown"}"
                )
                return
            }
            val response = client.summarize(
                RemoteSummaryRequest(
                    systemPrompt = "Reply with OK only.",
                    userPrompt = "OK",
                    contextSize = minOf(snapshot.chunkContext, 512).coerceAtLeast(128),
                    maxTokens = 8,
                    temperature = 0f,
                    thinkingEnabled = false
                )
            )
            check(response.output.isNotBlank()) { "Translation backend returned an empty readiness response" }
        } finally {
            client.cancelActiveCall()
        }
    }

    private suspend fun resolveMangaVisionCapability(
        config: MangaTranslationRunConfig
    ): MangaTranslationRunConfig {
        if (!config.behavior.pageImageContextEnabled) return config
        val snapshot = config.translationSettings
        val isLiteRt = SettingsRepository.isLiteRtBackend(snapshot.backend)
        val cacheKey = MangaTranslationSupport.visionCapabilityCacheKey(config)
        val supported = mangaVisionCapabilityCache[cacheKey] ?: run {
            if (isLiteRt) {
                val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
                val metadataSupported = try {
                    client.fetchMetadata().getOrNull()?.visionSupported == true
                } catch (error: Throwable) {
                    DebugLog.log("[PDF] LiteRT vision metadata check failed; using text-only translation: ${error.message}")
                    false
                } finally {
                    client.cancelActiveCall()
                }
                DebugLog.log(
                    "[PDF] LiteRT manga vision readiness from metadata: " +
                        if (metadataSupported) "vision supported" else "text-only fallback"
                )
                mangaVisionCapabilityCache[cacheKey] = metadataSupported
                return@run metadataSupported
            }
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            val attachment = try {
                RemoteSummaryImageAttachment(
                    base64 = bitmapToJpegBase64(bitmap, 2, 75),
                    mimeType = "image/jpeg"
                )
            } finally {
                bitmap.recycle()
            }
            val client = RemoteSummaryClientFactory.fromSnapshot(context, snapshot)
            val result = try {
                client.summarize(
                    RemoteSummaryRequest(
                        systemPrompt = "Reply with OK only.",
                        userPrompt = "Reply with OK if you can read the attached image.",
                        contextSize = if (isLiteRt) 0 else minOf(snapshot.chunkContext, 512).coerceAtLeast(128),
                        maxTokens = if (isLiteRt) 32 else 8,
                        temperature = 0f,
                        thinkingEnabled = false,
                        imageAttachments = listOf(attachment)
                    )
                ).output.isNotBlank()
            } catch (error: Throwable) {
                DebugLog.log("[PDF] Image capability probe failed; using text-only translation: ${error.message}")
                false
            } finally {
                client.cancelActiveCall()
            }
            mangaVisionCapabilityCache[cacheKey] = result
            result
        }
        return config.copy(
            pageImageContextAvailable = supported,
            pageImageContextReason = if (supported) null else "vision_probe_failed"
        )
    }

    private suspend fun translateSingleMangaPdf(
        pdfUri: Uri,
        sourceName: String,
        exportPdf: Boolean,
        exportCbz: Boolean,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        runConfig: MangaTranslationRunConfig?,
        workDirOverride: File?,
        executionController: PDFTranslationExecutionController?,
        notificationId: Int,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): MangaTranslationFileResult {
        val quality = MangaTranslationQualityAccumulator()
        if (runConfig?.pageImageContextReason == "vision_probe_failed") {
            quality.visionFallbacks += 1
        }
        val options = runConfig?.resolvedTranslationOptions()
        val timestamp = System.currentTimeMillis()
        val baseName = sourceName.substringBeforeLast('.').replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "comic" }
        val workDir = (workDirOverride
            ?: File(context.filesDir, "manga_translation_runtime/pdf_${baseName}_$timestamp"))
            .apply { mkdirs() }
        var completed = false
        try {
            ensureTranslationActive(executionController)
            onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.READING_TEXT, 0, 1, 0, 0))
            val translatedPdfName = "translated_${baseName}_$timestamp.pdf"
            val translatedPdfUri = exportTranslatedTextLayerPdf(
                pdfUri = pdfUri,
                outputFileName = translatedPdfName,
                settingsOverride = settingsOverride,
                optionsOverride = options,
                mangaConfig = runConfig,
                saveToConfiguredOutput = exportPdf,
                executionController = executionController,
                onProgress = onProgress,
                qualityAccumulator = quality
            ).getOrThrow()

            val savedPdfUri = if (exportPdf) translatedPdfUri else null
            val savedCbzUri = if (exportCbz) {
                ensureTranslationActive(executionController)
                onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.RENDERING, 0, 1, 0, 0))
                val renderedPages = renderPdfPagesToPng(translatedPdfUri, workDir, baseName)
                if (renderedPages.isEmpty()) throw IllegalStateException(context.getString(R.string.workflow_manga_error_no_rendered_pages))
                onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.PACKING, renderedPages.size, renderedPages.size, 0, 0))
                val cbzName = "translated_${baseName}_$timestamp.cbz"
                val cbzFile = File(workDir, cbzName)
                packPngPagesAsCbz(renderedPages, cbzFile)
                saveFileToOutputFolder(cbzFile, "comics", "application/vnd.comicbook+zip", cbzName)
            } else {
                null
            }
            completed = true
            return MangaTranslationFileResult(
                sourceName = sourceName,
                pdfUri = savedPdfUri,
                cbzUri = savedCbzUri,
                qualityReport = MangaTranslationQualityReport(
                    weakTranslationsRetried = quality.weakTranslationsRetried,
                    textOnlyFallbacks = quality.textOnlyFallbacks,
                    jsonRepairs = quality.jsonRepairs,
                    plainTextFallbacks = quality.plainTextFallbacks,
                    untranslatedUnits = quality.untranslatedUnits,
                    visionFallbacks = quality.visionFallbacks,
                    blankOverlayUnits = quality.blankOverlayUnits,
                    clippedOverlayUnits = quality.clippedOverlayUnits,
                    ungroundedOcrResponses = quality.ungroundedOcrResponses,
                    regionalOcrFallbacks = quality.regionalOcrFallbacks,
                    rejectedCrossRegionMerges = quality.rejectedCrossRegionMerges,
                    promptLeakRejections = quality.promptLeakRejections,
                    skippedLlamaCropRequests = quality.skippedLlamaCropRequests,
                    llamaOcrRequests = quality.llamaOcrRequests,
                    llamaOcrElapsedMs = quality.llamaOcrElapsedMs,
                    decorativeTextPreserved = quality.decorativeTextPreserved,
                    skippedOverlayUnits = quality.skippedOverlayUnits,
                    liteRtRuntimeFallbacks = quality.liteRtRuntimeFallbacks,
                    reconciledOcrAlternatives = quality.reconciledOcrAlternatives,
                    coalescedBubbleFragments = quality.coalescedBubbleFragments,
                    incompleteTranslationRetries = quality.incompleteTranslationRetries,
                    wholeBubblesPreserved = quality.wholeBubblesPreserved,
                    resolvedReadingDirection = when (runConfig?.readingDirection) {
                        MangaReadingDirection.RIGHT_TO_LEFT -> MangaReadingDirection.RIGHT_TO_LEFT
                        else -> MangaReadingDirection.LEFT_TO_RIGHT
                    }
                )
            )
        } finally {
            if (completed) runCatching { workDir.deleteRecursively() }
        }
    }

    private suspend fun translateSingleCbz(
        cbzUri: Uri,
        sourceName: String,
        exportPdf: Boolean,
        exportCbz: Boolean,
        settingsOverride: RemoteSummarySettingsSnapshot?,
        runConfig: MangaTranslationRunConfig?,
        workDirOverride: File?,
        executionController: PDFTranslationExecutionController?,
        notificationId: Int,
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?
    ): MangaTranslationFileResult {
        val quality = MangaTranslationQualityAccumulator()
        if (runConfig?.pageImageContextReason == "vision_probe_failed") {
            quality.visionFallbacks += 1
        }
        val options = runConfig?.resolvedTranslationOptions()
        val timestamp = System.currentTimeMillis()
        val baseName = sourceName.substringBeforeLast('.').replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "comic" }
        val workDir = (workDirOverride
            ?: File(context.filesDir, "manga_translation_runtime/cbz_${baseName}_$timestamp"))
            .apply { mkdirs() }
        val checkpointFile = File(workDir, "checkpoint.json")
        val jobId = "manga_${baseName}_$timestamp"
        var completed = false
        try {
            ensureTranslationActive(executionController)
            onProgress?.invoke(PdfOcrTranslationProgress(PdfOcrTranslationStage.EXTRACTING, 0, 1, 0, 0))
            val imageFiles = extractCbzImages(cbzUri, workDir)
            if (imageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.pdf_error_no_image_pages_in_cbz))
            DebugLog.log("[PDF] Extracted ${imageFiles.size} comic pages from $sourceName")

            MangaTranslationSupport.writeCheckpoint(
                checkpointFile,
                MangaTranslationCheckpoint(
                    jobId = jobId,
                    sourceName = sourceName,
                    sourceKind = MangaTranslationSourceKind.CBZ,
                    exportPdf = exportPdf,
                    exportCbz = exportCbz,
                    totalPages = imageFiles.size
                )
            )

            val pdfName = "translated_${baseName}_$timestamp.pdf"
            val cbzName = "translated_${baseName}_$timestamp.cbz"
            val translatedPdfFile = File(workDir, pdfName)
            val ocrResult = collectImageOcrText(
                imageFiles = imageFiles,
                optionsOverride = options,
                ocrStrategy = runConfig?.behavior?.ocrStrategy,
                ocrExecutionMode = MangaOcrExecutionMode.BATCH,
                exhaustiveLlamaOcrRegions = runConfig?.behavior?.exhaustiveLlamaOcrRegions == true
            ) { progress ->
                onProgress?.invoke(
                    PdfOcrTranslationProgress(
                        stage = PdfOcrTranslationStage.OCR,
                        processedPages = progress.processedPages,
                        totalPages = progress.totalPages,
                        translatedBlocks = 0,
                        totalBlocks = 0,
                        currentRegion = progress.currentRegion,
                        totalRegions = progress.totalRegions,
                        detailText = progress.detailText
                    )
                )
            }.getOrThrow()
            quality.ungroundedOcrResponses += ocrResult.pages.sumOf { it.ungroundedResponses }
            quality.regionalOcrFallbacks += ocrResult.pages.sumOf { it.regionalFallbacks }
            quality.reconciledOcrAlternatives += ocrResult.pages.sumOf { it.reconciledOcrAlternatives }
            quality.promptLeakRejections += ocrResult.pages.sumOf { it.promptLeakRejections }
            quality.skippedLlamaCropRequests += ocrResult.pages.sumOf { it.skippedLlamaCropRequests }
            quality.llamaOcrRequests += ocrResult.pages.sumOf { it.llamaOcrRequests }
            quality.llamaOcrElapsedMs += ocrResult.pages.sumOf { it.llamaOcrElapsedMs }
            DebugLog.log(
                "[PDF] Comic OCR produced ${ocrResult.blocks.count { it.text.isNotBlank() }} blocks " +
                    "across ${ocrResult.totalPages} pages for $sourceName"
            )
            val ocrTextBlocks = ocrResult.blocks.count { it.text.isNotBlank() }.coerceAtLeast(1)
            onProgress?.invoke(
                PdfOcrTranslationProgress(
                    stage = PdfOcrTranslationStage.TRANSLATING,
                    processedPages = 0,
                    totalPages = ocrResult.totalPages.coerceAtLeast(1),
                    translatedBlocks = 0,
                    totalBlocks = ocrTextBlocks
                )
            )
            val prepared = prepareOcrTranslation(
                sourcePdfUri = Uri.EMPTY,
                ocrResult = ocrResult,
                settingsOverride = settingsOverride,
                optionsOverride = options,
                mangaConfig = runConfig,
                executionController = executionController,
                onProgress = onProgress,
                notificationId = notificationId,
                initialTranslations = MangaTranslationSupport.readCheckpoint(checkpointFile)?.translations.orEmpty(),
                onCheckpoint = { translations, completedPages ->
                    MangaTranslationSupport.writeCheckpoint(
                        checkpointFile,
                        MangaTranslationCheckpoint(
                            jobId = jobId,
                            sourceName = sourceName,
                            sourceKind = MangaTranslationSourceKind.CBZ,
                            exportPdf = exportPdf,
                            exportCbz = exportCbz,
                            totalPages = imageFiles.size,
                            completedPageIndexes = completedPages,
                            translations = translations
                        )
                    )
                },
                pageScreenshotProvider = { pageIndex ->
                    val capturedOptions = options ?: settingsRepo.pdfTranslationOptionsSnapshot()
                    imageFiles.getOrNull(pageIndex)?.let { imageFile ->
                        RemoteSummaryImageAttachment(
                            base64 = renderImagePageScreenshotBase64(
                                imageFile = imageFile,
                                maxSide = capturedOptions.screenshotMaxSide,
                                jpegQuality = capturedOptions.screenshotJpegQuality
                            )
                        )
                    }
                },
                qualityAccumulator = quality
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
                    onProgress = onProgress,
                    qualityAccumulator = quality
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

            MangaTranslationSupport.writeCheckpoint(
                checkpointFile,
                MangaTranslationCheckpoint(
                    jobId = jobId,
                    sourceName = sourceName,
                    sourceKind = MangaTranslationSourceKind.CBZ,
                    exportPdf = exportPdf,
                    exportCbz = exportCbz,
                    totalPages = imageFiles.size,
                    completedPageIndexes = imageFiles.indices.toSet(),
                    translations = prepared.translations,
                    status = MangaTranslationSupport.STATUS_COMPLETE
                )
            )
            completed = true
            return MangaTranslationFileResult(
                sourceName = sourceName,
                pdfUri = savedPdfUri,
                cbzUri = savedCbzUri,
                qualityReport = MangaTranslationQualityReport(
                    totalPages = ocrResult.totalPages,
                    emptyOcrPages = ocrResult.emptyPages,
                    weakTranslationsRetried = quality.weakTranslationsRetried,
                    textOnlyFallbacks = quality.textOnlyFallbacks,
                    jsonRepairs = quality.jsonRepairs,
                    plainTextFallbacks = quality.plainTextFallbacks,
                    untranslatedUnits = quality.untranslatedUnits,
                    visionFallbacks = quality.visionFallbacks,
                    blankOverlayUnits = quality.blankOverlayUnits,
                    clippedOverlayUnits = quality.clippedOverlayUnits,
                    ungroundedOcrResponses = quality.ungroundedOcrResponses,
                regionalOcrFallbacks = quality.regionalOcrFallbacks,
                rejectedCrossRegionMerges = quality.rejectedCrossRegionMerges,
                promptLeakRejections = quality.promptLeakRejections,
                skippedLlamaCropRequests = quality.skippedLlamaCropRequests,
                llamaOcrRequests = quality.llamaOcrRequests,
                llamaOcrElapsedMs = quality.llamaOcrElapsedMs,
                decorativeTextPreserved = quality.decorativeTextPreserved,
                skippedOverlayUnits = quality.skippedOverlayUnits,
                liteRtRuntimeFallbacks = quality.liteRtRuntimeFallbacks,
                reconciledOcrAlternatives = quality.reconciledOcrAlternatives,
                coalescedBubbleFragments = quality.coalescedBubbleFragments,
                incompleteTranslationRetries = quality.incompleteTranslationRetries,
                wholeBubblesPreserved = quality.wholeBubblesPreserved,
                resolvedReadingDirection = prepared.resolvedReadingDirection
                )
            )
        } finally {
            if (completed) {
                runCatching { workDir.deleteRecursively() }
            }
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
                            MangaTranslationSupport.isSafeComicZipEntryName(entry.name) &&
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
            val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                val bytes = ByteArrayOutputStream()
                flattened.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
                return com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromByteArray(doc, bytes.toByteArray())
            } finally {
                flattened.recycle()
            }
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
        onProgress: ((PdfOcrTranslationProgress) -> Unit)?,
        qualityAccumulator: MangaTranslationQualityAccumulator? = null
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
                        val paintedRects = mutableListOf<RectF>()
                        units.forEach { unit ->
                            val translated = prepared.translations[unit.id]?.trim().orEmpty()
                            if (translated.isBlank()) {
                                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_blank_unit, unit.id, unit.pageIndex + 1))
                                return@forEach
                            }
                            if (!translationNeedsVisibleOverlay(unit.text, translated)) {
                                return@forEach
                            }
                            val rect = unit.safeBitmapOverlayRect(
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                pdfWidth = page.pdfWidth,
                                pdfHeight = page.pdfHeight
                            ) ?: run {
                                qualityAccumulator?.let {
                                    it.skippedOverlayUnits += 1
                                    if (unit.containingRegionId != null) it.wholeBubblesPreserved += 1
                                }
                                DebugLog.log(
                                    "[PDF] Skipped manga overlay ${unit.id} on page ${unit.pageIndex + 1}; source geometry no longer fits its detected region"
                                )
                                return@forEach
                            }
                            if (paintedRects.any { existing -> bitmapOverlayOverlapRatio(rect, existing) > 0.08f }) {
                                qualityAccumulator?.let {
                                    it.skippedOverlayUnits += 1
                                    if (unit.containingRegionId != null) it.wholeBubblesPreserved += 1
                                }
                                DebugLog.log(
                                    "[PDF] Skipped overlapping manga overlay ${unit.id} on page ${unit.pageIndex + 1}; original artwork preserved"
                                )
                                return@forEach
                            }
                            val overlayResult = drawTranslatedTextOnBitmap(
                                canvas = canvas,
                                text = translated,
                                rect = rect,
                                backgroundColor = unit.backgroundColor,
                                textColor = unit.textColor,
                                typeface = typeface
                            )
                            if (overlayResult.skipped) {
                                qualityAccumulator?.let {
                                    it.skippedOverlayUnits += 1
                                    if (unit.containingRegionId != null) it.wholeBubblesPreserved += 1
                                }
                                DebugLog.log(
                                    "[PDF] Skipped unreadable manga overlay ${unit.id} on page ${unit.pageIndex + 1}; original artwork preserved"
                                )
                            } else {
                                paintedRects += RectF(rect)
                                if (overlayResult.clipped) {
                                    qualityAccumulator?.let { it.clippedOverlayUnits += 1 }
                                    DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_clipped_unit, unit.id, unit.pageIndex + 1))
                                }
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
        val paintedRects = mutableListOf<RectF>()
        units.forEach { unit ->
            val translated = translations[unit.id]?.trim().orEmpty()
            if (translated.isBlank()) {
                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_blank_unit, unit.id, unit.pageIndex + 1))
                return@forEach
            }
            if (!translationNeedsVisibleOverlay(unit.text, translated)) {
                return@forEach
            }
            val rect = unit.safeBitmapOverlayRect(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                pdfWidth = pdfWidth,
                pdfHeight = pdfHeight
            ) ?: run {
                DebugLog.log(
                    "[PDF] Skipped raster overlay ${unit.id} on page ${unit.pageIndex + 1}; source geometry no longer fits its detected region"
                )
                return@forEach
            }
            if (paintedRects.any { existing -> bitmapOverlayOverlapRatio(rect, existing) > 0.08f }) {
                DebugLog.log(
                    "[PDF] Skipped overlapping raster overlay ${unit.id} on page ${unit.pageIndex + 1}; original artwork preserved"
                )
                return@forEach
            }
            val overlayResult = drawTranslatedTextOnBitmap(
                canvas = canvas,
                text = translated,
                rect = rect,
                backgroundColor = unit.backgroundColor,
                textColor = unit.textColor,
                typeface = typeface
            )
            if (overlayResult.skipped) {
                DebugLog.log(
                    "[PDF] Skipped unreadable raster overlay ${unit.id} on page ${unit.pageIndex + 1}; original artwork preserved"
                )
            } else {
                paintedRects += RectF(rect)
            }
            if (!overlayResult.skipped && overlayResult.clipped) {
                DebugLog.log(context.getString(R.string.pdf_translation_diagnostic_clipped_unit, unit.id, unit.pageIndex + 1))
            }
        }
    }

    private fun PdfTranslationUnit.safeBitmapOverlayRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        pdfWidth: Float,
        pdfHeight: Float
    ): RectF? {
        val maxWidth = bitmapWidth.toFloat()
        val maxHeight = bitmapHeight.toFloat()
        val padding = maxOf(4f, minOf(bitmapWidth, bitmapHeight) * 0.006f)
        val proposed = rect.toBitmapRect(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight
        ).expanded(
            padding = padding,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )
        val safeRegion = safeRegionRect?.toBitmapRect(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight
        )?.expanded(
            padding = maxOf(1f, padding * 0.35f),
            maxWidth = maxWidth,
            maxHeight = maxHeight
        ) ?: return proposed
        val constrained = proposed.intersectionOrNull(safeRegion)
        val proposedMinWidth = proposed.width().coerceAtLeast(1f) * 0.62f
        val proposedMinHeight = proposed.height().coerceAtLeast(1f) * 0.62f
        if (constrained != null && constrained.width() >= proposedMinWidth && constrained.height() >= proposedMinHeight) {
            return constrained
        }

        val sourceFallback = sourceRect.toBitmapRect(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            pdfWidth = pdfWidth,
            pdfHeight = pdfHeight
        ).expanded(
            padding = maxOf(2f, padding * 0.5f),
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )
        return sourceFallback.intersectionOrNull(safeRegion)?.takeIf { it.width() >= 4f && it.height() >= 4f }
    }

    private fun RectF.intersectionOrNull(other: RectF): RectF? {
        val left = maxOf(this.left, other.left)
        val top = maxOf(this.top, other.top)
        val right = minOf(this.right, other.right)
        val bottom = minOf(this.bottom, other.bottom)
        if (right - left < 1f || bottom - top < 1f) return null
        return RectF(left, top, right, bottom)
    }

    private fun bitmapOverlayOverlapRatio(first: RectF, second: RectF): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        if (width <= 0f || height <= 0f) return 0f
        val overlapArea = width * height
        val firstArea = (first.width() * first.height()).coerceAtLeast(1f)
        val secondArea = (second.width() * second.height()).coerceAtLeast(1f)
        return overlapArea / minOf(firstArea, secondArea)
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

    private fun translationNeedsVisibleOverlay(sourceText: String, translatedText: String): Boolean {
        val compactSource = sourceText
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
        val compactTranslation = translatedText
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
        if (compactSource.isBlank() || sourceText.none(Char::isLetter)) return false
        return compactSource != compactTranslation
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

    private data class BitmapOverlayResult(
        val clipped: Boolean = false,
        val skipped: Boolean = false
    )

    private fun drawTranslatedTextOnBitmap(
        canvas: Canvas,
        text: String,
        rect: RectF,
        backgroundColor: Int,
        textColor: Int,
        typeface: Typeface
    ): BitmapOverlayResult {
        if (rect.width() < 4f || rect.height() < 4f || text.isBlank()) {
            return BitmapOverlayResult(skipped = true)
        }
        val inset = maxOf(2f, minOf(rect.width(), rect.height()) * 0.06f)
        val textRect = RectF(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            rect.bottom - inset
        )
        if (textRect.width() < 4f || textRect.height() < 4f) {
            return BitmapOverlayResult(skipped = true)
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.LEFT
            this.typeface = typeface
        }
        val fontSize = findBitmapTextSize(text, textPaint, textRect.width(), textRect.height())
        textPaint.textSize = fontSize
        val layout = buildBitmapTextLayout(text, textPaint, textRect.width().roundToInt().coerceAtLeast(1))
        val readableMinimum = 8f
        if (fontSize < readableMinimum || layout.height > textRect.height()) {
            return BitmapOverlayResult(skipped = true)
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = backgroundColor
        }
        val cornerRadius = minOf(rect.width(), rect.height()) * 0.18f
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        canvas.save()
        canvas.clipRect(textRect)
        val topOffset = ((textRect.height() - layout.height).coerceAtLeast(0f) / 2f)
        canvas.translate(textRect.left, textRect.top + topOffset)
        layout.draw(canvas)
        canvas.restore()
        return BitmapOverlayResult()
    }

    private fun findBitmapTextSize(text: String, paint: TextPaint, maxWidth: Float, maxHeight: Float): Float {
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        val estimatedLines = maxOf(
            text.lines().size,
            kotlin.math.ceil(normalizedText.length / 13f).toInt()
        )
        val upper = MangaTranslationSupport.fittedTextSize(
            lineCount = estimatedLines.coerceAtMost(12),
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            preferredMaxSize = minOf(maxHeight * 0.48f, 44f),
            minSize = 5f
        ).coerceAtLeast(6f)
        var low = 5f
        var high = upper
        var best = 5f
        repeat(12) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val layout = buildBitmapTextLayout(text, paint, maxWidth.roundToInt().coerceAtLeast(1))
            if (layout.height <= maxHeight) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    private fun buildBitmapTextLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.96f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
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
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            output
        )
    }

    private fun displayNameForUri(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "comic.cbz"
    }
    
    /**
     * Perform OCR on an image using ML Kit Text Recognition
     * Note: Requires ML Kit dependency: com.google.mlkit:text-recognition
     */
    suspend fun performOCR(
        imageUri: Uri,
        optionsOverride: PdfTranslationOptionsSnapshot? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Performing OCR on image")
            
            // Load bitmap from URI
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            } ?: return@withContext Result.failure(Exception("Could not load image"))

            val options = optionsOverride ?: settingsRepo.pdfTranslationOptionsSnapshot()
            if (options.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
                return@withContext runCatching {
                    try {
                        withLlamaOcrClient(options.llamaOcr) { client ->
                            recognizeBitmapWithLlamaOcr(
                                client = client,
                                llamaSettings = options.llamaOcr,
                                bitmap = bitmap,
                                pageIndex = 0,
                                pdfWidth = bitmap.width.toFloat(),
                                pdfHeight = bitmap.height.toFloat(),
                                ocrStrategy = if (options.bubbleGuidedOcrEnabled) {
                                    MangaOcrStrategy.HYBRID
                                } else {
                                    MangaOcrStrategy.FULL_PAGE
                                }
                            ).blocks.joinToString("\n\n") { it.text }.trim()
                        }.also { text ->
                            if (text.isBlank()) throw IllegalStateException("No OCR text found")
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            
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
        return when (normalizedBackend) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER ->
                settingsRepo.enableVision.value && !settingsRepo.selectedMmprojPath.value.isNullOrBlank()
            SettingsRepository.PDF_BACKEND_LITERT -> {
                val modelId = snapshot.liteRtModelId ?: return false
                val model = AppDatabase.getDatabase(context).liteRtModelDao().getById(modelId) ?: return false
                model.supportsLiteRtVision()
            }
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP ->
                PDFTranslationLogic.modelNameLikelySupportsVision(snapshot.llamaSwapModel)
            else ->
                PDFTranslationLogic.modelNameLikelySupportsVision(snapshot.ollamaModel)
        }
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
