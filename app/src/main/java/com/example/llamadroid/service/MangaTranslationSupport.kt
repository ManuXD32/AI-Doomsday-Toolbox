package com.example.llamadroid.service

import android.net.Uri
import com.example.llamadroid.data.LlamaOcrPromptPreset
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.PdfOcrProvider
import com.example.llamadroid.data.PdfTranslationOptionsSnapshot
import com.example.llamadroid.data.PdfTranslationQualityMode
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MangaTranslationSourceKind {
    CBZ,
    PDF
}

enum class MangaTranslationProfile {
    BEST_READING,
    BALANCED,
    FAST,
    CUSTOM
}

enum class MangaReadingDirection {
    AUTO,
    RIGHT_TO_LEFT,
    LEFT_TO_RIGHT
}

enum class MangaOcrStrategy {
    HYBRID,
    ADAPTIVE,
    FULL_PAGE,
    BUBBLE_ONLY,
    /** User-painted page regions; one OCR request is made per connected region. */
    PAINTED_REGIONS
}

enum class MangaOcrExecutionMode {
    PREVIEW,
    BATCH
}

enum class MangaOcrTextRole {
    DIALOGUE,
    NARRATION,
    DECORATIVE,
    CREDIT,
    PAGE_NUMBER,
    UNKNOWN
}

data class MangaTranslationBehavior(
    val ocrStrategy: MangaOcrStrategy,
    val pageUnderstandingEnabled: Boolean,
    val continuityEnabled: Boolean,
    val correctionEnabled: Boolean,
    val pageImageContextEnabled: Boolean,
    val weakTranslationRetryEnabled: Boolean = true,
    val translateDecorativeText: Boolean = false,
    val exhaustiveLlamaOcrRegions: Boolean = false
)

data class MangaTranslationSource(
    val uri: Uri,
    val displayName: String,
    val mimeType: String? = null
)

data class DocumentOcrRunConfig(
    val provider: PdfOcrProvider,
    val strategy: MangaOcrStrategy = MangaOcrStrategy.FULL_PAGE,
    val llamaOcr: LlamaOcrSettingsSnapshot
)

data class DocumentTranslationRunConfig(
    val settings: RemoteSummarySettingsSnapshot,
    val usePageImageContext: Boolean,
    val pageImageMaxSide: Int,
    val pageImageJpegQuality: Int,
    val textOnlyFallbackEnabled: Boolean,
    val qualityMode: PdfTranslationQualityMode
) {
    fun toLegacyOptions(ocr: DocumentOcrRunConfig): PdfTranslationOptionsSnapshot =
        PdfTranslationOptionsSnapshot(
            usePageScreenshotContext = usePageImageContext,
            screenshotMaxSide = pageImageMaxSide,
            screenshotJpegQuality = pageImageJpegQuality,
            textOnlyFallbackEnabled = textOnlyFallbackEnabled,
            qualityMode = qualityMode,
            ocrProvider = ocr.provider,
            bubbleGuidedOcrEnabled = ocr.strategy != MangaOcrStrategy.FULL_PAGE,
            llamaOcr = ocr.llamaOcr
        )
}

enum class PdfOcrResultAction {
    EXTRACT_TEXT_TO_NOTES,
    SEARCHABLE_PDF,
    TRANSLATE_SCANNED_PDF
}

data class PdfOcrJobSpec(
    val source: MangaTranslationSource,
    val resultAction: PdfOcrResultAction,
    val ocrConfig: DocumentOcrRunConfig,
    val translationConfig: DocumentTranslationRunConfig? = null,
    val jobId: String = "pdf_ocr_${System.currentTimeMillis()}"
)

data class PdfTextTranslationJobSpec(
    val sources: List<MangaTranslationSource>,
    val translationConfig: DocumentTranslationRunConfig,
    val jobId: String = "pdf_text_translation_${System.currentTimeMillis()}"
)

enum class DocumentPreflightCode {
    SOURCE_MISSING,
    SOURCE_ACTION_UNSUPPORTED,
    OCR_MODEL_MISSING,
    OCR_MMPROJ_MISSING,
    TRANSLATION_MODEL_MISSING,
    TARGET_LANGUAGE_MISSING
}

data class DocumentPreflightResult(val blockers: Set<DocumentPreflightCode>) {
    val canRun: Boolean get() = blockers.isEmpty()
}

data class MangaTranslationRunConfig(
    val profile: MangaTranslationProfile,
    val targetLanguage: String,
    val readingDirection: MangaReadingDirection,
    val translationConfig: DocumentTranslationRunConfig,
    val ocrConfig: DocumentOcrRunConfig,
    val behavior: MangaTranslationBehavior,
    val pageImageContextAvailable: Boolean,
    val pageImageContextReason: String? = null,
    val ocrModelRef: MangaTemplateModelRef? = null,
    val ocrProjectorRef: MangaTemplateModelRef? = null,
    val paintedOcrWorkspace: MangaPaintedOcrWorkspaceRef? = null,
    val paintedOcrReviewComplete: Boolean = false
) {
    constructor(
        profile: MangaTranslationProfile,
        targetLanguage: String,
        readingDirection: MangaReadingDirection,
        translationSettings: RemoteSummarySettingsSnapshot,
        translationOptions: PdfTranslationOptionsSnapshot,
        behavior: MangaTranslationBehavior,
        pageImageContextAvailable: Boolean,
        pageImageContextReason: String? = null
    ) : this(
        profile = profile,
        targetLanguage = targetLanguage,
        readingDirection = readingDirection,
        translationConfig = DocumentTranslationRunConfig(
            settings = translationSettings,
            usePageImageContext = translationOptions.usePageScreenshotContext,
            pageImageMaxSide = translationOptions.screenshotMaxSide,
            pageImageJpegQuality = translationOptions.screenshotJpegQuality,
            textOnlyFallbackEnabled = translationOptions.textOnlyFallbackEnabled,
            qualityMode = translationOptions.qualityMode
        ),
        ocrConfig = DocumentOcrRunConfig(
            provider = translationOptions.ocrProvider,
            strategy = behavior.ocrStrategy,
            llamaOcr = translationOptions.llamaOcr
        ),
        behavior = behavior,
        pageImageContextAvailable = pageImageContextAvailable,
        pageImageContextReason = pageImageContextReason
    )

    val translationSettings: RemoteSummarySettingsSnapshot get() = translationConfig.settings
    val translationOptions: PdfTranslationOptionsSnapshot
        get() = translationConfig.toLegacyOptions(ocrConfig)

    fun resolvedTranslationSettings(): RemoteSummarySettingsSnapshot =
        translationSettings.copy(
            targetLanguage = targetLanguage.trim(),
            thinkingEnabled = if (profile == MangaTranslationProfile.CUSTOM) {
                translationSettings.thinkingEnabled
            } else {
                false
            }
        )

    fun resolvedTranslationOptions(): PdfTranslationOptionsSnapshot {
        val qualityMode = when (profile) {
            MangaTranslationProfile.BEST_READING -> PdfTranslationQualityMode.BEST_QUALITY
            MangaTranslationProfile.BALANCED -> PdfTranslationQualityMode.BALANCED
            MangaTranslationProfile.FAST -> PdfTranslationQualityMode.FASTER
            MangaTranslationProfile.CUSTOM -> translationConfig.qualityMode
        }
        return translationOptions.copy(
            usePageScreenshotContext = behavior.pageImageContextEnabled && pageImageContextAvailable,
            textOnlyFallbackEnabled = if (profile == MangaTranslationProfile.CUSTOM) {
                translationOptions.textOnlyFallbackEnabled
            } else {
                true
            },
            qualityMode = qualityMode,
            bubbleGuidedOcrEnabled = behavior.ocrStrategy != MangaOcrStrategy.FULL_PAGE
        )
    }

    fun fingerprint(): String {
        val bytes = MangaTranslationSupport.runConfigToJson(this).toString()
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

data class MangaTranslationJobSpec(
    val sources: List<MangaTranslationSource>,
    val exportPdf: Boolean,
    val exportCbz: Boolean,
    val config: MangaTranslationRunConfig,
    val jobId: String = "manga_${System.currentTimeMillis()}"
)

enum class MangaPreflightSeverity {
    BLOCKING,
    WARNING
}

enum class MangaPreflightCode {
    NO_SOURCES,
    UNSUPPORTED_SOURCE,
    NO_OUTPUT,
    TARGET_LANGUAGE_MISSING,
    BACKEND_URL_MISSING,
    BACKEND_MODEL_MISSING,
    LITERT_MODEL_MISSING,
    OCR_MODEL_MISSING,
    OCR_MMPROJ_MISSING,
    VISION_UNAVAILABLE,
    PAINTED_OCR_WORKSPACE_MISSING,
    PAINTED_OCR_REVIEW_REQUIRED,
    PAINTED_OCR_SOURCE_CHANGED
}

data class MangaPreflightIssue(
    val code: MangaPreflightCode,
    val severity: MangaPreflightSeverity,
    val sourceName: String? = null
)

data class MangaTranslationPreflight(
    val issues: List<MangaPreflightIssue>
) {
    val blockers: List<MangaPreflightIssue>
        get() = issues.filter { it.severity == MangaPreflightSeverity.BLOCKING }
    val warnings: List<MangaPreflightIssue>
        get() = issues.filter { it.severity == MangaPreflightSeverity.WARNING }
    val canRun: Boolean get() = blockers.isEmpty()
}

data class MangaTranslationQualityReport(
    val totalPages: Int = 0,
    val emptyOcrPages: Int = 0,
    val weakTranslationsRetried: Int = 0,
    val textOnlyFallbacks: Int = 0,
    val jsonRepairs: Int = 0,
    val plainTextFallbacks: Int = 0,
    val untranslatedUnits: Int = 0,
    val visionFallbacks: Int = 0,
    val blankOverlayUnits: Int = 0,
    val clippedOverlayUnits: Int = 0,
    val ungroundedOcrResponses: Int = 0,
    val regionalOcrFallbacks: Int = 0,
    val promptLeakRejections: Int = 0,
    val skippedLlamaCropRequests: Int = 0,
    val llamaOcrRequests: Int = 0,
    val llamaOcrElapsedMs: Long = 0L,
    val decorativeTextPreserved: Int = 0,
    val rejectedCrossRegionMerges: Int = 0,
    val skippedOverlayUnits: Int = 0,
    val liteRtRuntimeFallbacks: Int = 0,
    val reconciledOcrAlternatives: Int = 0,
    val coalescedBubbleFragments: Int = 0,
    val incompleteTranslationRetries: Int = 0,
    val wholeBubblesPreserved: Int = 0,
    val ocrRuntimeFallbacks: Int = 0,
    val resolvedReadingDirection: MangaReadingDirection = MangaReadingDirection.LEFT_TO_RIGHT
) {
    val warningCount: Int
        get() = emptyOcrPages + textOnlyFallbacks + untranslatedUnits +
            visionFallbacks + blankOverlayUnits + clippedOverlayUnits +
            ungroundedOcrResponses + regionalOcrFallbacks + promptLeakRejections +
            skippedLlamaCropRequests + decorativeTextPreserved + skippedOverlayUnits +
            liteRtRuntimeFallbacks + incompleteTranslationRetries + wholeBubblesPreserved +
            ocrRuntimeFallbacks
}

data class MangaTranslationPreviewResult(
    val sourceName: String,
    val originalPageUri: Uri,
    val translatedPageUri: Uri,
    val configFingerprint: String,
    val qualityReport: MangaTranslationQualityReport
)

data class MangaTranslationJobManifest(
    val version: Int = MangaTranslationSupport.MANIFEST_VERSION,
    val spec: MangaTranslationJobSpec,
    val status: String = MangaTranslationSupport.STATUS_RUNNING,
    val currentFileIndex: Int = 0,
    val completedSourceUris: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class MangaReadingOrderBox(
    val id: String,
    val rect: PdfMappedRect,
    val text: String
)

data class MangaOcrCandidate(
    val id: String,
    val text: String,
    val box: PdfOcrBox,
    val preferred: Boolean = false,
    val provenance: MangaOcrRegionProvenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK,
    val containingRegionId: String? = null
)

enum class MangaOcrRegionProvenance {
    GROUNDED_VLM_SPAN,
    DETECTED_BUBBLE,
    ML_KIT_TEXT_BLOCK,
    UNGROUNDED_FULL_PAGE_CONTEXT
}

enum class MangaOcrRecognitionPass {
    FULL_PAGE_ML_KIT,
    REGIONAL_ML_KIT,
    REGIONAL_LLAMA,
    GROUNDED_LLAMA
}

data class MangaGroundedOcrSpan(
    val kind: String,
    val text: String,
    val box: PdfOcrBox
)

data class MangaLlamaOcrBudget(
    val executionMode: MangaOcrExecutionMode,
    val maxRegionalRequestsPerPage: Int,
    val maxPlainFallbacksPerPage: Int,
    val exhaustiveRegions: Boolean = false
)

data class MangaLlamaOcrSanitizationResult(
    val text: String,
    val rejected: Boolean = false,
    val reason: String? = null
)

data class MangaTemplateModelRef(
    val filename: String,
    val repositoryId: String,
    val modelType: ModelType,
    val legacyPath: String? = null
)

/**
 * The complete, user-visible OCR model choice.  Keeping the inferred preset and
 * projector together prevents one picker from leaving the other picker pointed
 * at a stale or incompatible file.
 */
data class MangaOcrModelSelection(
    val model: ModelEntity,
    val promptPreset: LlamaOcrPromptPreset,
    val projector: ModelEntity?
)

data class MangaTranslationCheckpoint(
    val jobId: String,
    val sourceName: String,
    val sourceKind: MangaTranslationSourceKind,
    val exportPdf: Boolean,
    val exportCbz: Boolean,
    val totalPages: Int = 0,
    val completedPageIndexes: Set<Int> = emptySet(),
    val translations: Map<String, String> = emptyMap(),
    val status: String = MangaTranslationSupport.STATUS_RUNNING
) {
    fun completedTranslationCount(): Int = translations.size
}

object MangaTranslationSupport {
    const val TEMPLATE_VERSION = 5
    const val MANIFEST_VERSION = 1
    const val MAX_LLAMA_OCR_PLAIN_FALLBACKS_PER_PAGE = 2
    const val PREVIEW_LLAMA_OCR_REGION_BUDGET = 3
    const val BATCH_LLAMA_OCR_REGION_BUDGET = 8
    const val LLAMA_OCR_FULL_PAGE_MAX_TOKENS = 1024
    const val LLAMA_OCR_REGION_MAX_TOKENS = 384
    const val LLAMA_OCR_PLAIN_FALLBACK_MAX_TOKENS = 128

    fun preferredMlKitRecognitionPass(
        fullPageTexts: List<String>,
        regionalTexts: List<String>
    ): MangaOcrRecognitionPass? {
        if (fullPageTexts.isEmpty() && regionalTexts.isEmpty()) return null
        if (fullPageTexts.isEmpty()) return MangaOcrRecognitionPass.REGIONAL_ML_KIT
        if (regionalTexts.isEmpty()) return MangaOcrRecognitionPass.FULL_PAGE_ML_KIT
        val fullPageScore = ocrTextSetScore(fullPageTexts)
        val regionalScore = ocrTextSetScore(regionalTexts)
        return if (regionalScore > fullPageScore * 1.08f) {
            MangaOcrRecognitionPass.REGIONAL_ML_KIT
        } else {
            MangaOcrRecognitionPass.FULL_PAGE_ML_KIT
        }
    }

    fun dedupeOcrTextFragments(texts: List<String>): List<String> {
        val accepted = mutableListOf<String>()
        texts.map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .forEach { candidate ->
                val normalizedCandidate = normalizeOcrComparisonText(candidate)
                val duplicateIndex = accepted.indexOfFirst { existing ->
                    val normalizedExisting = normalizeOcrComparisonText(existing)
                    normalizedExisting == normalizedCandidate ||
                        normalizedExisting.length >= 5 &&
                        normalizedCandidate.length >= 5 &&
                        (normalizedExisting.contains(normalizedCandidate) ||
                            normalizedCandidate.contains(normalizedExisting))
                }
                if (duplicateIndex < 0) {
                    accepted += candidate
                } else if (candidate.length > accepted[duplicateIndex].length) {
                    accepted[duplicateIndex] = candidate
                }
            }
        return accepted
    }

    private fun ocrTextSetScore(texts: List<String>): Float {
        val deduped = dedupeOcrTextFragments(texts)
        val combined = deduped.joinToString(" ")
        val usefulCharacters = combined.count { it.isLetterOrDigit() }
        val wordCount = combined.split(Regex("""\s+""")).count { token ->
            token.count(Char::isLetterOrDigit) >= 2
        }
        val suspiciousSingles = combined.split(Regex("""\s+""")).count { token ->
            token.count(Char::isLetterOrDigit) == 1
        }
        val weakPenalty = if (mlKitRegionTextLooksWeak(combined)) 24f else 0f
        return usefulCharacters + wordCount * 2.5f - suspiciousSingles * 3f - weakPenalty
    }

    private fun normalizeOcrComparisonText(text: String): String =
        text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    fun overlayOverlapRatio(first: PdfMappedRect, second: PdfMappedRect): Float {
        val left = maxOf(first.x, second.x)
        val bottom = maxOf(first.y, second.y)
        val right = minOf(first.x + first.width, second.x + second.width)
        val top = minOf(first.y + first.height, second.y + second.height)
        val intersection = (right - left).coerceAtLeast(0f) * (top - bottom).coerceAtLeast(0f)
        if (intersection <= 0f) return 0f
        val smallerArea = minOf(
            first.width * first.height,
            second.width * second.height
        ).coerceAtLeast(1f)
        return intersection / smallerArea
    }

    fun textIslandsShouldStaySeparate(
        first: PdfMappedRect,
        second: PdfMappedRect,
        merged: PdfMappedRect,
        pageWidth: Float,
        pageHeight: Float
    ): Boolean {
        val safePageWidth = pageWidth.coerceAtLeast(1f)
        val safePageHeight = pageHeight.coerceAtLeast(1f)
        val horizontalGap = rectHorizontalGap(first, second)
        val verticalGap = rectVerticalGap(first, second)
        val centerGapX = abs(rectCenterX(first) - rectCenterX(second))
        val verticalOverlap = rectVerticalOverlapRatio(first, second)
        val mergedArea = merged.width * merged.height
        val componentArea = (first.width * first.height + second.width * second.height).coerceAtLeast(1f)
        val sparseUnionRatio = mergedArea / componentArea
        val tallFragments = min(first.height, second.height) >= safePageHeight * 0.035f ||
            max(first.height, second.height) >= safePageHeight * 0.055f

        val sideBySideTextColumns = tallFragments &&
            verticalOverlap >= 0.42f &&
            horizontalGap >= maxOf(10f, min(first.width, second.width) * 0.18f, safePageWidth * 0.014f) &&
            centerGapX >= maxOf(42f, safePageWidth * 0.052f)
        val distantStackedIslands = verticalOverlap <= 0.10f &&
            verticalGap >= maxOf(24f, max(first.height, second.height) * 0.95f, safePageHeight * 0.025f)
        val sparseMergedIsland = sparseUnionRatio >= 1.85f &&
            (horizontalGap >= safePageWidth * 0.025f || verticalGap >= safePageHeight * 0.020f)
        val mergedTooWideForBubbleText = merged.width >= safePageWidth * 0.28f &&
            horizontalGap >= min(first.width, second.width) * 0.42f &&
            verticalOverlap >= 0.25f

        return sideBySideTextColumns ||
            distantStackedIslands ||
            sparseMergedIsland ||
            mergedTooWideForBubbleText
    }

    private fun rectCenterX(rect: PdfMappedRect): Float = rect.x + rect.width / 2f

    private fun rectHorizontalGap(first: PdfMappedRect, second: PdfMappedRect): Float {
        val firstRight = first.x + first.width
        val secondRight = second.x + second.width
        return maxOf(0f, maxOf(first.x, second.x) - minOf(firstRight, secondRight))
    }

    private fun rectVerticalGap(first: PdfMappedRect, second: PdfMappedRect): Float {
        val firstTop = first.y + first.height
        val secondTop = second.y + second.height
        return maxOf(0f, maxOf(first.y, second.y) - minOf(firstTop, secondTop))
    }

    private fun rectVerticalOverlapRatio(first: PdfMappedRect, second: PdfMappedRect): Float {
        val bottom = maxOf(first.y, second.y)
        val top = minOf(first.y + first.height, second.y + second.height)
        val overlap = (top - bottom).coerceAtLeast(0f)
        return overlap / min(first.height, second.height).coerceAtLeast(1f)
    }

    const val STATUS_RUNNING = "running"
    const val STATUS_COMPLETE = "complete"
    const val STATUS_FAILED = "failed"

    val primaryPickerMimeTypes: Array<String> = arrayOf(
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/zip",
        "application/pdf",
        "application/octet-stream"
    )

    val fallbackPickerMimeTypes: Array<String> = arrayOf("*/*")

    fun preflight(spec: PdfOcrJobSpec): DocumentPreflightResult {
        val blockers = mutableSetOf<DocumentPreflightCode>()
        if (spec.source.uri == Uri.EMPTY) blockers += DocumentPreflightCode.SOURCE_MISSING
        if (!isSourceCompatible(spec.resultAction, spec.source.displayName, spec.source.mimeType)) {
            blockers += DocumentPreflightCode.SOURCE_ACTION_UNSUPPORTED
        }
        if (spec.ocrConfig.provider == PdfOcrProvider.LLAMA_CPP_GGUF) {
            if (spec.ocrConfig.llamaOcr.modelPath.isNullOrBlank()) {
                blockers += DocumentPreflightCode.OCR_MODEL_MISSING
            }
            if (spec.ocrConfig.llamaOcr.mmprojPath.isNullOrBlank()) {
                blockers += DocumentPreflightCode.OCR_MMPROJ_MISSING
            }
        }
        spec.translationConfig?.let { translation ->
            if (translation.settings.targetLanguage.isBlank()) {
                blockers += DocumentPreflightCode.TARGET_LANGUAGE_MISSING
            }
            val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(translation.settings.backend)
            if (
                SettingsRepository.requiresSelectedRemoteModel(backend) &&
                translation.settings.ollamaModel.isNullOrBlank() &&
                translation.settings.llamaSwapModel.isNullOrBlank()
            ) {
                blockers += DocumentPreflightCode.TRANSLATION_MODEL_MISSING
            }
        }
        return DocumentPreflightResult(blockers)
    }

    fun isSourceCompatible(
        action: PdfOcrResultAction,
        displayName: String,
        mimeType: String?
    ): Boolean =
        action == PdfOcrResultAction.EXTRACT_TEXT_TO_NOTES ||
            sourceKindFor(displayName, mimeType) == MangaTranslationSourceKind.PDF

    fun preflight(spec: PdfTextTranslationJobSpec): DocumentPreflightResult {
        val blockers = mutableSetOf<DocumentPreflightCode>()
        if (spec.sources.isEmpty()) blockers += DocumentPreflightCode.SOURCE_MISSING
        if (spec.sources.any { sourceKindFor(it.displayName, it.mimeType) != MangaTranslationSourceKind.PDF }) {
            blockers += DocumentPreflightCode.SOURCE_ACTION_UNSUPPORTED
        }
        if (spec.translationConfig.settings.targetLanguage.isBlank()) {
            blockers += DocumentPreflightCode.TARGET_LANGUAGE_MISSING
        }
        return DocumentPreflightResult(blockers)
    }

    private fun ModelEntity.hasInstalledFile(): Boolean =
        path.isNotBlank() && (isDownloaded || File(path).isFile)

    fun installedOcrModels(models: List<ModelEntity>): List<ModelEntity> =
        models.filter { model ->
            model.hasInstalledFile() &&
                model.filename.endsWith(".gguf", ignoreCase = true) &&
                (model.type == ModelType.VISION || model.isVision)
        }.sortedBy { it.filename.lowercase(Locale.US) }

    fun installedProjectors(models: List<ModelEntity>): List<ModelEntity> =
        models.filter { model ->
            model.hasInstalledFile() &&
                model.filename.endsWith(".gguf", ignoreCase = true) &&
                model.type in setOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ)
        }.sortedBy { it.filename.lowercase(Locale.US) }

    fun modelRef(model: ModelEntity): MangaTemplateModelRef =
        MangaTemplateModelRef(
            filename = model.filename,
            repositoryId = model.repoId,
            modelType = model.type,
            legacyPath = model.path
        )

    fun resolveTemplateModelRef(
        ref: MangaTemplateModelRef?,
        installed: List<ModelEntity>
    ): ModelEntity? {
        if (ref == null) return null
        return installed.firstOrNull { model ->
            model.filename == ref.filename &&
                model.repoId == ref.repositoryId &&
                model.type == ref.modelType
        } ?: installed.firstOrNull { model ->
            model.filename == ref.filename && model.type == ref.modelType
        } ?: installed.firstOrNull { model ->
            ref.legacyPath != null && model.path == ref.legacyPath
        }
    }

    fun matchProjector(
        model: ModelEntity,
        projectors: List<ModelEntity>
    ): ModelEntity? {
        model.mmprojPath?.let { recorded ->
            projectors.singleOrNull { it.path == recorded }?.let { return it }
        }
        projectors.filter { it.repoId.isNotBlank() && it.repoId == model.repoId }
            .singleOrNull()
            ?.let { return it }
        val modelStem = normalizedModelStem(model.filename)
        projectors.filter { projector ->
            val projectorStem = normalizedModelStem(projector.filename)
            modelStem.isNotBlank() &&
                projectorStem.isNotBlank() &&
                (modelStem.contains(projectorStem) || projectorStem.contains(modelStem))
        }.singleOrNull()?.let { return it }
        return projectors.singleOrNull()
    }

    /**
     * Resolve all dependent OCR choices after a model picker change.
     *
     * A null projector is intentional: callers must clear any previously
     * selected projector and make the user choose one when the installed
     * catalog does not contain an unambiguous match.
     */
    fun resolveOcrModelSelection(
        model: ModelEntity,
        projectors: List<ModelEntity>
    ): MangaOcrModelSelection = MangaOcrModelSelection(
        model = model,
        promptPreset = inferOcrPreset(model.filename, model.repoId),
        projector = matchProjector(model, projectors)
    )

    fun inferOcrPreset(modelName: String?, repositoryId: String?): LlamaOcrPromptPreset {
        val hint = "${modelName.orEmpty()} ${repositoryId.orEmpty()}".lowercase(Locale.US)
        return when {
            "unlimited" in hint -> LlamaOcrPromptPreset.UNLIMITED_OCR
            "glm" in hint -> LlamaOcrPromptPreset.GLM_OCR
            "deepseek" in hint -> LlamaOcrPromptPreset.DEEPSEEK_OCR
            "hunyuan" in hint -> LlamaOcrPromptPreset.HUNYUAN_OCR
            "paddle" in hint -> LlamaOcrPromptPreset.PADDLEOCR_VL
            "dots" in hint || "dots.ocr" in hint -> LlamaOcrPromptPreset.DOTS_OCR
            "lighton" in hint -> LlamaOcrPromptPreset.LIGHTON_OCR
            "qianfan" in hint -> LlamaOcrPromptPreset.QIANFAN_OCR
            else -> LlamaOcrPromptPreset.GENERIC_OCR
        }
    }

    fun visionCapabilityCacheKey(config: MangaTranslationRunConfig): String {
        val snapshot = config.translationSettings
        return listOf(
            snapshot.backend,
            snapshot.ollamaUrl,
            snapshot.llamaServerUrl,
            snapshot.llamaSwapUrl,
            snapshot.ollamaModel,
            snapshot.llamaSwapModel,
            snapshot.liteRtModelId,
            config.translationConfig.pageImageMaxSide,
            config.translationConfig.pageImageJpegQuality
        ).joinToString("|")
    }

    private fun normalizedModelStem(filename: String): String =
        filename.lowercase(Locale.US)
            .removeSuffix(".gguf")
            .replace(Regex("""(?:mmproj|projector|vision|f16|f32|q\d(?:_[a-z0-9]+)?)"""), "")
            .filter { it.isLetterOrDigit() }

    fun defaultBehavior(profile: MangaTranslationProfile): MangaTranslationBehavior =
        when (profile) {
            MangaTranslationProfile.BEST_READING -> MangaTranslationBehavior(
                ocrStrategy = MangaOcrStrategy.HYBRID,
                pageUnderstandingEnabled = true,
                continuityEnabled = true,
                correctionEnabled = true,
                pageImageContextEnabled = true
            )
            MangaTranslationProfile.BALANCED -> MangaTranslationBehavior(
                ocrStrategy = MangaOcrStrategy.ADAPTIVE,
                pageUnderstandingEnabled = false,
                continuityEnabled = true,
                correctionEnabled = true,
                pageImageContextEnabled = true
            )
            MangaTranslationProfile.FAST -> MangaTranslationBehavior(
                ocrStrategy = MangaOcrStrategy.FULL_PAGE,
                pageUnderstandingEnabled = false,
                continuityEnabled = false,
                correctionEnabled = false,
                pageImageContextEnabled = false
            )
            MangaTranslationProfile.CUSTOM -> MangaTranslationBehavior(
                ocrStrategy = MangaOcrStrategy.FULL_PAGE,
                pageUnderstandingEnabled = true,
                continuityEnabled = true,
                correctionEnabled = true,
                pageImageContextEnabled = true
            )
        }

    fun preflight(spec: MangaTranslationJobSpec): MangaTranslationPreflight {
        val issues = mutableListOf<MangaPreflightIssue>()
        if (spec.sources.isEmpty()) {
            issues += MangaPreflightIssue(MangaPreflightCode.NO_SOURCES, MangaPreflightSeverity.BLOCKING)
        }
        spec.sources.forEach { source ->
            if (!isSupportedSource(source.displayName, source.mimeType)) {
                issues += MangaPreflightIssue(
                    MangaPreflightCode.UNSUPPORTED_SOURCE,
                    MangaPreflightSeverity.BLOCKING,
                    source.displayName
                )
            }
        }
        if (!spec.exportPdf && !spec.exportCbz) {
            issues += MangaPreflightIssue(MangaPreflightCode.NO_OUTPUT, MangaPreflightSeverity.BLOCKING)
        }
        if (spec.config.targetLanguage.isBlank()) {
            issues += MangaPreflightIssue(MangaPreflightCode.TARGET_LANGUAGE_MISSING, MangaPreflightSeverity.BLOCKING)
        }
        val settings = spec.config.translationSettings
        when (SettingsRepository.normalizeOllamaOrLlamaBackend(settings.backend)) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> {
                if (settings.llamaServerUrl.isBlank()) {
                    issues += MangaPreflightIssue(MangaPreflightCode.BACKEND_URL_MISSING, MangaPreflightSeverity.BLOCKING)
                }
            }
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> {
                if (settings.llamaSwapUrl.isBlank()) {
                    issues += MangaPreflightIssue(MangaPreflightCode.BACKEND_URL_MISSING, MangaPreflightSeverity.BLOCKING)
                }
                if (settings.llamaSwapModel.isNullOrBlank()) {
                    issues += MangaPreflightIssue(MangaPreflightCode.BACKEND_MODEL_MISSING, MangaPreflightSeverity.BLOCKING)
                }
            }
            SettingsRepository.PDF_BACKEND_LITERT -> {
                if (settings.liteRtModelId == null || settings.liteRtModelId <= 0L) {
                    issues += MangaPreflightIssue(MangaPreflightCode.LITERT_MODEL_MISSING, MangaPreflightSeverity.BLOCKING)
                }
            }
            else -> {
                if (settings.ollamaUrl.isBlank()) {
                    issues += MangaPreflightIssue(MangaPreflightCode.BACKEND_URL_MISSING, MangaPreflightSeverity.BLOCKING)
                }
                if (settings.ollamaModel.isNullOrBlank()) {
                    issues += MangaPreflightIssue(MangaPreflightCode.BACKEND_MODEL_MISSING, MangaPreflightSeverity.BLOCKING)
                }
            }
        }
        val options = spec.config.translationOptions
        if (options.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
            val modelPath = options.llamaOcr.modelPath
            val mmprojPath = options.llamaOcr.mmprojPath
            if (modelPath.isNullOrBlank() || !File(modelPath).isFile) {
                issues += MangaPreflightIssue(MangaPreflightCode.OCR_MODEL_MISSING, MangaPreflightSeverity.BLOCKING)
            }
            if (mmprojPath.isNullOrBlank() || !File(mmprojPath).isFile) {
                issues += MangaPreflightIssue(MangaPreflightCode.OCR_MMPROJ_MISSING, MangaPreflightSeverity.BLOCKING)
            }
        }
        if (spec.config.behavior.pageImageContextEnabled && !spec.config.pageImageContextAvailable) {
            issues += MangaPreflightIssue(MangaPreflightCode.VISION_UNAVAILABLE, MangaPreflightSeverity.WARNING)
        }
        if (spec.config.behavior.ocrStrategy == MangaOcrStrategy.PAINTED_REGIONS) {
            if (spec.config.paintedOcrWorkspace == null) {
                issues += MangaPreflightIssue(
                    MangaPreflightCode.PAINTED_OCR_WORKSPACE_MISSING,
                    MangaPreflightSeverity.BLOCKING
                )
            } else if (!spec.config.paintedOcrReviewComplete) {
                issues += MangaPreflightIssue(
                    MangaPreflightCode.PAINTED_OCR_REVIEW_REQUIRED,
                    MangaPreflightSeverity.BLOCKING
                )
            }
        }
        return MangaTranslationPreflight(issues.distinct())
    }

    fun sourceKindFor(name: String?, mimeType: String?): MangaTranslationSourceKind? {
        val lowerName = name.orEmpty().lowercase(Locale.US)
        val normalizedMime = mimeType.orEmpty().lowercase(Locale.US).substringBefore(';').trim()
        return when {
            lowerName.endsWith(".pdf") || normalizedMime == "application/pdf" -> MangaTranslationSourceKind.PDF
            lowerName.endsWith(".cbz") ||
                lowerName.endsWith(".zip") ||
                normalizedMime == "application/vnd.comicbook+zip" ||
                normalizedMime == "application/x-cbz" ||
                normalizedMime == "application/zip" ||
                normalizedMime == "application/octet-stream" -> MangaTranslationSourceKind.CBZ
            else -> null
        }
    }

    fun isSupportedSource(name: String?, mimeType: String?): Boolean =
        sourceKindFor(name, mimeType) != null

    fun inferReadingDirection(boxes: List<MangaReadingOrderBox>): MangaReadingDirection {
        if (boxes.isEmpty()) return MangaReadingDirection.LEFT_TO_RIGHT
        val visibleChars = boxes.sumOf { box -> box.text.count { !it.isWhitespace() } }.coerceAtLeast(1)
        val cjkChars = boxes.sumOf { box -> box.text.count(::isCjkCharacter) }
        val japaneseSyllabaryChars = boxes.sumOf { box ->
            box.text.count { character ->
                val block = Character.UnicodeBlock.of(character)
                block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA
            }
        }
        val verticalBoxes = boxes.count { it.rect.height > it.rect.width * 1.45f }
        return if (
            japaneseSyllabaryChars.toFloat() / visibleChars.toFloat() >= 0.05f ||
            (
                cjkChars.toFloat() / visibleChars.toFloat() >= 0.18f &&
                    verticalBoxes.toFloat() / boxes.size.toFloat() >= 0.20f
                )
        ) {
            MangaReadingDirection.RIGHT_TO_LEFT
        } else {
            MangaReadingDirection.LEFT_TO_RIGHT
        }
    }

    fun resolveReadingDirection(
        requested: MangaReadingDirection,
        boxes: List<MangaReadingOrderBox>
    ): MangaReadingDirection =
        if (requested == MangaReadingDirection.AUTO) inferReadingDirection(boxes) else requested

    fun orderReadingBoxes(
        boxes: List<MangaReadingOrderBox>,
        pageHeight: Float,
        requested: MangaReadingDirection
    ): List<MangaReadingOrderBox> {
        if (boxes.size <= 1) return boxes
        val direction = resolveReadingDirection(requested, boxes)
        val medianHeight = boxes.map { it.rect.height.coerceAtLeast(1f) }.sorted()
            .let { it[it.size / 2] }
        val rowTolerance = max(12f, medianHeight * 0.68f)
        val byTop = boxes.sortedBy { pageHeight - it.rect.y - it.rect.height }
        val rows = mutableListOf<MutableList<MangaReadingOrderBox>>()
        val rowTops = mutableListOf<Float>()
        byTop.forEach { box ->
            val top = pageHeight - box.rect.y - box.rect.height
            val rowIndex = rowTops.indices.minByOrNull { index -> abs(rowTops[index] - top) }
                ?.takeIf { index -> abs(rowTops[index] - top) <= rowTolerance }
            if (rowIndex == null) {
                rows += mutableListOf(box)
                rowTops += top
            } else {
                rows[rowIndex] += box
                rowTops[rowIndex] = rows[rowIndex]
                    .map { pageHeight - it.rect.y - it.rect.height }
                    .average()
                    .toFloat()
            }
        }
        return rows.indices
            .sortedBy { rowTops[it] }
            .flatMap { index ->
                when (direction) {
                    MangaReadingDirection.RIGHT_TO_LEFT -> rows[index].sortedByDescending { it.rect.x }
                    else -> rows[index].sortedBy { it.rect.x }
                }
            }
    }

    fun mergeOcrCandidates(candidates: List<MangaOcrCandidate>): List<MangaOcrCandidate> {
        val accepted = mutableListOf<MangaOcrCandidate>()
        candidates
            .filterNot { it.provenance == MangaOcrRegionProvenance.UNGROUNDED_FULL_PAGE_CONTEXT }
            .sortedWith(
                compareByDescending<MangaOcrCandidate> { it.preferred }
                    .thenBy { ocrProvenancePriority(it.provenance) }
            )
            .forEach { candidate ->
            val normalized = normalizeOcrText(candidate.text)
            if (normalized.isBlank()) return@forEach
            val duplicateIndex = accepted.indexOfFirst { existing ->
                if (
                    existing.containingRegionId != null &&
                    candidate.containingRegionId != null &&
                    existing.containingRegionId != candidate.containingRegionId
                ) {
                    return@indexOfFirst false
                }
                val sameText = normalizeOcrText(existing.text) == normalized
                val overlap = boxIntersectionOverUnion(existing.box, candidate.box)
                sameText || overlap >= 0.62f
            }
            if (duplicateIndex < 0) {
                accepted += candidate
            } else if (
                candidate.preferred && !accepted[duplicateIndex].preferred ||
                ocrProvenancePriority(candidate.provenance) <
                ocrProvenancePriority(accepted[duplicateIndex].provenance)
            ) {
                accepted[duplicateIndex] = candidate
            }
        }
        return accepted.sortedWith(compareBy<MangaOcrCandidate> { it.box.top }.thenBy { it.box.left })
    }

    private fun ocrProvenancePriority(provenance: MangaOcrRegionProvenance): Int =
        when (provenance) {
            MangaOcrRegionProvenance.GROUNDED_VLM_SPAN -> 0
            MangaOcrRegionProvenance.DETECTED_BUBBLE -> 1
            MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK -> 2
            MangaOcrRegionProvenance.UNGROUNDED_FULL_PAGE_CONTEXT -> 3
        }

    fun parseGroundedOcrSpans(
        rawOutput: String,
        imageWidth: Int,
        imageHeight: Int
    ): List<MangaGroundedOcrSpan> {
        if (rawOutput.isBlank() || imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val detectedTag = Regex(
            pattern = """<\|det\|>\s*([a-zA-Z][a-zA-Z0-9 _-]*?)\s*\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*]\s*<\|/det\|>\s*([\s\S]*?)(?=<\|det\|>|$)""",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val groundedLine = Regex(
            pattern = """^\s*([a-zA-Z][a-zA-Z0-9 _-]*?)\s*\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*]\s*(.*?)\s*$"""
        )
        val detectedTagSpans = detectedTag.findAll(rawOutput).mapNotNull { match ->
            groundedSpanFromGroups(
                kind = match.groupValues[1],
                coordinateValues = (2..5).map { match.groupValues[it] },
                text = match.groupValues[6],
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }.toList()
        val lineSpans = rawOutput.lineSequence().mapNotNull { line ->
            val match = groundedLine.matchEntire(line) ?: return@mapNotNull null
            groundedSpanFromGroups(
                kind = match.groupValues[1],
                coordinateValues = (2..5).map { match.groupValues[it] },
                text = match.groupValues[6],
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }.toList()
        return (detectedTagSpans + lineSpans)
            .distinctBy { span -> "${span.kind}|${span.box}|${span.text}" }
    }

    private fun groundedSpanFromGroups(
        kind: String,
        coordinateValues: List<String>,
        text: String,
        imageWidth: Int,
        imageHeight: Int
    ): MangaGroundedOcrSpan? {
        val normalizedKind = kind.trim().lowercase(Locale.US)
        if (normalizedKind == "image" || normalizedKind == "figure" || normalizedKind == "graphic") {
            return null
        }
        val normalizedText = text
            .replace(Regex("""<\|/?det\|>""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (normalizedText.isBlank()) return null
        val coordinates = coordinateValues.map { value -> value.toFloatOrNull() ?: return null }
        val coordinateMax = coordinates.maxOrNull()?.coerceAtLeast(1f) ?: return null
        val normalizedScale = if (coordinateMax <= 1.5f) 1f else 1000f
        val left = (coordinates[0] / normalizedScale * imageWidth).roundToInt().coerceIn(0, imageWidth)
        val top = (coordinates[1] / normalizedScale * imageHeight).roundToInt().coerceIn(0, imageHeight)
        val right = (coordinates[2] / normalizedScale * imageWidth).roundToInt().coerceIn(0, imageWidth)
        val bottom = (coordinates[3] / normalizedScale * imageHeight).roundToInt().coerceIn(0, imageHeight)
        val box = PdfOcrBox(
            left = min(left, right),
            top = min(top, bottom),
            right = max(left, right),
            bottom = max(top, bottom)
        )
        if (!isPaintableGroundedBox(box, imageWidth, imageHeight)) return null
        return MangaGroundedOcrSpan(kind = normalizedKind, text = normalizedText, box = box)
    }

    fun isPaintableGroundedBox(box: PdfOcrBox, imageWidth: Int, imageHeight: Int): Boolean {
        if (box.isEmpty || imageWidth <= 0 || imageHeight <= 0) return false
        val widthRatio = box.width.toFloat() / imageWidth.toFloat()
        val heightRatio = box.height.toFloat() / imageHeight.toFloat()
        val areaRatio = box.width.toFloat() * box.height.toFloat() /
            (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
        return areaRatio <= 0.22f &&
            heightRatio <= 0.46f &&
            !(widthRatio >= 0.86f && heightRatio >= 0.20f)
    }

    private fun normalizeOcrText(value: String): String =
        value.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    private fun boxIntersectionOverUnion(first: PdfOcrBox, second: PdfOcrBox): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
        if (intersection <= 0) return 0f
        val firstArea = first.width * first.height
        val secondArea = second.width * second.height
        return intersection.toFloat() / (firstArea + secondArea - intersection).coerceAtLeast(1).toFloat()
    }

    private fun isCjkCharacter(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO
    }

    fun runConfigToJson(config: MangaTranslationRunConfig): JSONObject {
        val remote = config.translationSettings
        val options = config.translationOptions
        val llamaOcr = options.llamaOcr
        return JSONObject()
            .put("version", TEMPLATE_VERSION)
            .put("profile", config.profile.name)
            .put("targetLanguage", config.targetLanguage)
            .put("readingDirection", config.readingDirection.name)
            .put("pageImageContextAvailable", config.pageImageContextAvailable)
            .put("pageImageContextReason", config.pageImageContextReason)
            .put("ocrModelRef", templateModelRefToJson(config.ocrModelRef))
            .put("ocrProjectorRef", templateModelRefToJson(config.ocrProjectorRef))
            .put("paintedOcrWorkspace", paintedOcrWorkspaceToJson(config.paintedOcrWorkspace))
            .put("paintedOcrReviewComplete", config.paintedOcrReviewComplete)
            .put("behavior", JSONObject()
                .put("ocrStrategy", config.behavior.ocrStrategy.name)
                .put("pageUnderstandingEnabled", config.behavior.pageUnderstandingEnabled)
                .put("continuityEnabled", config.behavior.continuityEnabled)
                .put("correctionEnabled", config.behavior.correctionEnabled)
                .put("pageImageContextEnabled", config.behavior.pageImageContextEnabled)
                .put("weakTranslationRetryEnabled", config.behavior.weakTranslationRetryEnabled)
                .put("translateDecorativeText", config.behavior.translateDecorativeText)
                .put("exhaustiveLlamaOcrRegions", config.behavior.exhaustiveLlamaOcrRegions))
            .put("translation", JSONObject()
                .put("backend", remote.backend)
                .put("ollamaUrl", remote.ollamaUrl)
                .put("llamaServerUrl", remote.llamaServerUrl)
                .put("llamaSwapUrl", remote.llamaSwapUrl)
                .put("ollamaModel", remote.ollamaModel)
                .put("llamaSwapModel", remote.llamaSwapModel)
                .put("liteRtModelId", remote.liteRtModelId)
                .put("liteRtBackend", remote.liteRtBackend)
                .put("liteRtMtpEnabled", remote.liteRtMtpEnabled)
                .put("thinkingEnabled", remote.thinkingEnabled)
                .put("llamaServerModelLabel", remote.llamaServerModelLabel)
                .put("llamaServerContextTokens", remote.llamaServerContextTokens)
                .put("llamaServerContextLabel", remote.llamaServerContextLabel)
                .put("chunkContext", remote.chunkContext)
                .put("chunkMaxTokens", remote.chunkMaxTokens)
                .put("mergeContext", remote.mergeContext)
                .put("mergeMaxTokens", remote.mergeMaxTokens)
                .put("temperature", remote.temperature.toDouble())
                .put("timeoutMinutes", remote.timeoutMinutes)
                .put("summaryPrompt", remote.summaryPrompt)
                .put("mergePrompt", remote.mergePrompt))
            .put("options", JSONObject()
                .put("usePageScreenshotContext", options.usePageScreenshotContext)
                .put("screenshotMaxSide", options.screenshotMaxSide)
                .put("screenshotJpegQuality", options.screenshotJpegQuality)
                .put("textOnlyFallbackEnabled", options.textOnlyFallbackEnabled)
                .put("qualityMode", options.qualityMode.name)
                .put("ocrProvider", options.ocrProvider.name)
                .put("bubbleGuidedOcrEnabled", options.bubbleGuidedOcrEnabled)
                .put("llamaOcr", JSONObject()
                    .put("modelPath", llamaOcr.modelPath)
                    .put("mmprojPath", llamaOcr.mmprojPath)
                    .put("promptPreset", llamaOcr.promptPreset.name)
                    .put("customPrompt", llamaOcr.customPrompt)
                    .put("contextSize", llamaOcr.contextSize)
                    .put("maxTokens", llamaOcr.maxTokens)
                    .put("port", llamaOcr.port)
                    .put("flashAttention", llamaOcr.flashAttention)
                    .put("cacheRam", llamaOcr.cacheRam)
                    .put("parallel", llamaOcr.parallel)
                    .put("customFlags", llamaOcr.customFlags)
                    .put("commandTemplate", llamaOcr.commandTemplate)
                    // This marker distinguishes an explicit user opt-in from the old
                    // default, which was serialized as true in legacy manga drafts.
                    .put("temporarilyReplaceRunningServerConfigured", true)
                    .put("temporarilyReplaceRunningServer", llamaOcr.temporarilyReplaceRunningServer)))
    }

    /**
     * Serialize reusable workflow settings without binding a template to a document's painted
     * page workspace. The selected strategy is retained; masks and workspace IDs are always
     * recreated for the next source selection.
     */
    fun runConfigToTemplateJson(config: MangaTranslationRunConfig): JSONObject =
        runConfigToJson(
            config.copy(
                paintedOcrWorkspace = null,
                paintedOcrReviewComplete = false
            )
        )

    fun runConfigFromJson(json: JSONObject, fallback: MangaTranslationRunConfig): MangaTranslationRunConfig {
        val profile = enumValueOrDefault(json.optString("profile"), fallback.profile)
        val behaviorJson = json.optJSONObject("behavior")
        val defaultBehavior = defaultBehavior(profile)
        val behavior = MangaTranslationBehavior(
            ocrStrategy = enumValueOrDefault(behaviorJson?.optString("ocrStrategy"), defaultBehavior.ocrStrategy),
            pageUnderstandingEnabled = behaviorJson?.optBoolean("pageUnderstandingEnabled", defaultBehavior.pageUnderstandingEnabled)
                ?: defaultBehavior.pageUnderstandingEnabled,
            continuityEnabled = behaviorJson?.optBoolean("continuityEnabled", defaultBehavior.continuityEnabled)
                ?: defaultBehavior.continuityEnabled,
            correctionEnabled = behaviorJson?.optBoolean("correctionEnabled", defaultBehavior.correctionEnabled)
                ?: defaultBehavior.correctionEnabled,
            pageImageContextEnabled = behaviorJson?.optBoolean("pageImageContextEnabled", defaultBehavior.pageImageContextEnabled)
                ?: defaultBehavior.pageImageContextEnabled,
            weakTranslationRetryEnabled = behaviorJson?.optBoolean("weakTranslationRetryEnabled", true) ?: true,
            translateDecorativeText = behaviorJson?.optBoolean("translateDecorativeText", defaultBehavior.translateDecorativeText)
                ?: defaultBehavior.translateDecorativeText,
            exhaustiveLlamaOcrRegions = behaviorJson?.optBoolean("exhaustiveLlamaOcrRegions", defaultBehavior.exhaustiveLlamaOcrRegions)
                ?: defaultBehavior.exhaustiveLlamaOcrRegions
        )
        val translationJson = json.optJSONObject("translation")
        val old = fallback.translationSettings
        val translation = old.copy(
            backend = translationJson?.optString("backend", old.backend) ?: old.backend,
            ollamaUrl = translationJson?.optString("ollamaUrl", old.ollamaUrl) ?: old.ollamaUrl,
            llamaServerUrl = translationJson?.optString("llamaServerUrl", old.llamaServerUrl) ?: old.llamaServerUrl,
            llamaSwapUrl = translationJson?.optString("llamaSwapUrl", old.llamaSwapUrl) ?: old.llamaSwapUrl,
            ollamaModel = translationJson?.optNullableString("ollamaModel") ?: old.ollamaModel,
            llamaSwapModel = translationJson?.optNullableString("llamaSwapModel") ?: old.llamaSwapModel,
            liteRtModelId = translationJson?.optLong("liteRtModelId")?.takeIf { it > 0L } ?: old.liteRtModelId,
            liteRtBackend = translationJson?.optString("liteRtBackend", old.liteRtBackend) ?: old.liteRtBackend,
            liteRtMtpEnabled = translationJson?.optBoolean("liteRtMtpEnabled", old.liteRtMtpEnabled) ?: old.liteRtMtpEnabled,
            thinkingEnabled = translationJson?.optBoolean("thinkingEnabled", old.thinkingEnabled) ?: old.thinkingEnabled,
            llamaServerModelLabel = translationJson?.optNullableString("llamaServerModelLabel") ?: old.llamaServerModelLabel,
            llamaServerContextTokens = translationJson?.optInt("llamaServerContextTokens", old.llamaServerContextTokens)
                ?: old.llamaServerContextTokens,
            llamaServerContextLabel = translationJson?.optNullableString("llamaServerContextLabel") ?: old.llamaServerContextLabel,
            chunkContext = translationJson?.optInt("chunkContext", old.chunkContext) ?: old.chunkContext,
            chunkMaxTokens = translationJson?.optInt("chunkMaxTokens", old.chunkMaxTokens) ?: old.chunkMaxTokens,
            mergeContext = translationJson?.optInt("mergeContext", old.mergeContext) ?: old.mergeContext,
            mergeMaxTokens = translationJson?.optInt("mergeMaxTokens", old.mergeMaxTokens) ?: old.mergeMaxTokens,
            temperature = translationJson?.optDouble("temperature", old.temperature.toDouble())?.toFloat() ?: old.temperature,
            timeoutMinutes = translationJson?.optInt("timeoutMinutes", old.timeoutMinutes) ?: old.timeoutMinutes,
            targetLanguage = json.optString("targetLanguage", fallback.targetLanguage),
            summaryPrompt = translationJson?.optNullableString("summaryPrompt") ?: old.summaryPrompt,
            mergePrompt = translationJson?.optNullableString("mergePrompt") ?: old.mergePrompt
        )
        val optionsJson = json.optJSONObject("options")
        val oldOptions = fallback.translationOptions
        val llamaJson = optionsJson?.optJSONObject("llamaOcr")
        val oldLlama = oldOptions.llamaOcr
        // Legacy manga drafts had this value set to true by default. Only a config
        // written by the new UI may opt into interrupting an existing llama server.
        val temporaryServerReplacementConfigured = llamaJson?.optBoolean(
            "temporarilyReplaceRunningServerConfigured",
            false
        ) == true
        val options = oldOptions.copy(
            usePageScreenshotContext = optionsJson?.optBoolean("usePageScreenshotContext", oldOptions.usePageScreenshotContext)
                ?: oldOptions.usePageScreenshotContext,
            screenshotMaxSide = optionsJson?.optInt("screenshotMaxSide", oldOptions.screenshotMaxSide)
                ?: oldOptions.screenshotMaxSide,
            screenshotJpegQuality = optionsJson?.optInt("screenshotJpegQuality", oldOptions.screenshotJpegQuality)
                ?: oldOptions.screenshotJpegQuality,
            textOnlyFallbackEnabled = optionsJson?.optBoolean("textOnlyFallbackEnabled", oldOptions.textOnlyFallbackEnabled)
                ?: oldOptions.textOnlyFallbackEnabled,
            qualityMode = enumValueOrDefault(optionsJson?.optString("qualityMode"), oldOptions.qualityMode),
            ocrProvider = enumValueOrDefault(optionsJson?.optString("ocrProvider"), oldOptions.ocrProvider),
            bubbleGuidedOcrEnabled = optionsJson?.optBoolean("bubbleGuidedOcrEnabled", oldOptions.bubbleGuidedOcrEnabled)
                ?: oldOptions.bubbleGuidedOcrEnabled,
            llamaOcr = oldLlama.copy(
                modelPath = llamaJson?.optNullableString("modelPath") ?: oldLlama.modelPath,
                mmprojPath = llamaJson?.optNullableString("mmprojPath") ?: oldLlama.mmprojPath,
                promptPreset = enumValueOrDefault(llamaJson?.optString("promptPreset"), oldLlama.promptPreset),
                customPrompt = llamaJson?.optNullableString("customPrompt") ?: oldLlama.customPrompt,
                contextSize = llamaJson?.optInt("contextSize", oldLlama.contextSize) ?: oldLlama.contextSize,
                maxTokens = llamaJson?.optInt("maxTokens", oldLlama.maxTokens) ?: oldLlama.maxTokens,
                port = llamaJson?.optInt("port", oldLlama.port) ?: oldLlama.port,
                flashAttention = llamaJson?.optBoolean("flashAttention", oldLlama.flashAttention) ?: oldLlama.flashAttention,
                cacheRam = llamaJson?.optInt("cacheRam", oldLlama.cacheRam) ?: oldLlama.cacheRam,
                parallel = llamaJson?.optInt("parallel", oldLlama.parallel) ?: oldLlama.parallel,
                customFlags = llamaJson?.optNullableString("customFlags") ?: oldLlama.customFlags,
                commandTemplate = llamaJson?.optNullableString("commandTemplate") ?: oldLlama.commandTemplate,
                temporarilyReplaceRunningServer = if (temporaryServerReplacementConfigured) {
                    llamaJson?.optBoolean("temporarilyReplaceRunningServer", false) ?: false
                } else {
                    false
                }
            )
        )
        return fallback.copy(
            profile = profile,
            targetLanguage = json.optString("targetLanguage", fallback.targetLanguage),
            readingDirection = enumValueOrDefault(json.optString("readingDirection"), fallback.readingDirection),
            translationConfig = DocumentTranslationRunConfig(
                settings = translation,
                usePageImageContext = options.usePageScreenshotContext,
                pageImageMaxSide = options.screenshotMaxSide,
                pageImageJpegQuality = options.screenshotJpegQuality,
                textOnlyFallbackEnabled = options.textOnlyFallbackEnabled,
                qualityMode = options.qualityMode
            ),
            ocrConfig = DocumentOcrRunConfig(
                provider = options.ocrProvider,
                strategy = behavior.ocrStrategy,
                llamaOcr = options.llamaOcr
            ),
            behavior = behavior,
            pageImageContextAvailable = fallback.pageImageContextAvailable,
            pageImageContextReason = fallback.pageImageContextReason,
            ocrModelRef = templateModelRefFromJson(json.optJSONObject("ocrModelRef"))
                ?: fallback.ocrModelRef,
            ocrProjectorRef = templateModelRefFromJson(json.optJSONObject("ocrProjectorRef"))
                ?: fallback.ocrProjectorRef,
            // A present null explicitly clears a workspace when applying a reusable template;
            // older manifests that predate the field still inherit the fallback for backwards
            // compatibility.
            paintedOcrWorkspace = if (json.has("paintedOcrWorkspace")) {
                paintedOcrWorkspaceFromJson(json.optJSONObject("paintedOcrWorkspace"))
            } else {
                fallback.paintedOcrWorkspace
            },
            paintedOcrReviewComplete = if (json.has("paintedOcrReviewComplete")) {
                json.optBoolean("paintedOcrReviewComplete", false)
            } else {
                fallback.paintedOcrReviewComplete
            }
        )
    }

    private fun paintedOcrWorkspaceToJson(ref: MangaPaintedOcrWorkspaceRef?): Any =
        ref?.let {
            JSONObject()
                .put("workspaceId", it.workspaceId)
                .put("revision", it.revision)
                .put("sourceFingerprint", it.sourceFingerprint)
        } ?: JSONObject.NULL

    private fun paintedOcrWorkspaceFromJson(json: JSONObject?): MangaPaintedOcrWorkspaceRef? {
        if (json == null) return null
        val id = json.optString("workspaceId").trim()
        if (id.isBlank()) return null
        return MangaPaintedOcrWorkspaceRef(
            workspaceId = id,
            revision = json.optLong("revision", 0L).coerceAtLeast(0L),
            sourceFingerprint = json.optNullableString("sourceFingerprint")
        )
    }

    private fun templateModelRefToJson(ref: MangaTemplateModelRef?): Any =
        ref?.let {
            JSONObject()
                .put("filename", it.filename)
                .put("repositoryId", it.repositoryId)
                .put("modelType", it.modelType.name)
                .put("legacyPath", it.legacyPath)
        } ?: JSONObject.NULL

    private fun templateModelRefFromJson(json: JSONObject?): MangaTemplateModelRef? {
        if (json == null) return null
        val filename = json.optString("filename").trim()
        if (filename.isBlank()) return null
        return MangaTemplateModelRef(
            filename = filename,
            repositoryId = json.optString("repositoryId").trim(),
            modelType = enumValueOrDefault(json.optString("modelType"), ModelType.LLM),
            legacyPath = json.optNullableString("legacyPath")
        )
    }

    fun manifestToJson(manifest: MangaTranslationJobManifest): JSONObject {
        val spec = manifest.spec
        return JSONObject()
            .put("version", manifest.version)
            .put("jobId", spec.jobId)
            .put("status", manifest.status)
            .put("currentFileIndex", manifest.currentFileIndex)
            .put("createdAt", manifest.createdAt)
            .put("updatedAt", manifest.updatedAt)
            .put("exportPdf", spec.exportPdf)
            .put("exportCbz", spec.exportCbz)
            .put("runConfig", runConfigToJson(spec.config))
            .put("sources", JSONArray().apply {
                spec.sources.forEach { source ->
                    put(JSONObject()
                        .put("uri", source.uri.toString())
                        .put("displayName", source.displayName)
                        .put("mimeType", source.mimeType))
                }
            })
            .put("completedSourceUris", JSONArray().apply {
                manifest.completedSourceUris.sorted().forEach(::put)
            })
    }

    fun manifestFromJson(
        json: JSONObject,
        fallbackConfig: MangaTranslationRunConfig
    ): MangaTranslationJobManifest {
        val sources = buildList {
            val array = json.optJSONArray("sources") ?: JSONArray()
            for (index in 0 until array.length()) {
                val source = array.optJSONObject(index) ?: continue
                val uri = source.optString("uri").takeIf { it.isNotBlank() } ?: continue
                add(
                    MangaTranslationSource(
                        uri = Uri.parse(uri),
                        displayName = source.optString("displayName", uri.substringAfterLast('/')),
                        mimeType = source.optNullableString("mimeType")
                    )
                )
            }
        }
        val completed = buildSet {
            val array = json.optJSONArray("completedSourceUris") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val config = runConfigFromJson(
            json.optJSONObject("runConfig") ?: JSONObject(),
            fallbackConfig
        )
        return MangaTranslationJobManifest(
            version = json.optInt("version", MANIFEST_VERSION),
            spec = MangaTranslationJobSpec(
                sources = sources,
                exportPdf = json.optBoolean("exportPdf", true),
                exportCbz = json.optBoolean("exportCbz", true),
                config = config,
                jobId = json.optString("jobId").ifBlank { "manga_${System.currentTimeMillis()}" }
            ),
            status = json.optString("status", STATUS_RUNNING),
            currentFileIndex = json.optInt("currentFileIndex", 0),
            completedSourceUris = completed,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    fun writeManifest(file: File, manifest: MangaTranslationJobManifest) {
        writeJsonAtomically(file, manifestToJson(manifest))
    }

    private fun writeJsonAtomically(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString(2))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun readManifest(
        file: File,
        fallbackConfig: MangaTranslationRunConfig
    ): MangaTranslationJobManifest? =
        runCatching {
            if (!file.isFile) return null
            manifestFromJson(JSONObject(file.readText()), fallbackConfig)
        }.getOrNull()

    fun discoverResumableManifests(
        jobsRoot: File,
        fallbackConfig: MangaTranslationRunConfig
    ): List<MangaTranslationJobManifest> =
        jobsRoot.listFiles()
            .orEmpty()
            .mapNotNull { directory -> readManifest(File(directory, "manifest.json"), fallbackConfig) }
            .filter { manifest ->
                manifest.version == MANIFEST_VERSION &&
                    manifest.status != STATUS_COMPLETE &&
                    manifest.spec.sources.isNotEmpty()
            }
            .sortedByDescending { it.updatedAt }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    fun isSafeComicZipEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\\')) return false
        return name.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }
    }

    fun checkpointToJson(checkpoint: MangaTranslationCheckpoint): JSONObject {
        val completedPages = JSONArray().apply {
            checkpoint.completedPageIndexes.sorted().forEach { put(it) }
        }
        val translations = JSONObject().apply {
            checkpoint.translations.toSortedMap().forEach { (id, text) -> put(id, text) }
        }
        return JSONObject()
            .put("jobId", checkpoint.jobId)
            .put("sourceName", checkpoint.sourceName)
            .put("sourceKind", checkpoint.sourceKind.name)
            .put("exportPdf", checkpoint.exportPdf)
            .put("exportCbz", checkpoint.exportCbz)
            .put("totalPages", checkpoint.totalPages)
            .put("completedPageIndexes", completedPages)
            .put("translations", translations)
            .put("status", checkpoint.status)
    }

    fun checkpointFromJson(json: JSONObject): MangaTranslationCheckpoint {
        val completedPages = buildSet {
            val array = json.optJSONArray("completedPageIndexes") ?: JSONArray()
            for (index in 0 until array.length()) add(array.optInt(index))
        }
        val translationsJson = json.optJSONObject("translations") ?: JSONObject()
        val translations = linkedMapOf<String, String>()
        translationsJson.keys().forEach { key ->
            val value = translationsJson.optString(key).trim()
            if (key.isNotBlank() && value.isNotBlank()) translations[key] = value
        }
        return MangaTranslationCheckpoint(
            jobId = json.optString("jobId").ifBlank { "manga_${System.currentTimeMillis()}" },
            sourceName = json.optString("sourceName").ifBlank { "comic" },
            sourceKind = runCatching {
                MangaTranslationSourceKind.valueOf(json.optString("sourceKind"))
            }.getOrDefault(MangaTranslationSourceKind.CBZ),
            exportPdf = json.optBoolean("exportPdf", true),
            exportCbz = json.optBoolean("exportCbz", true),
            totalPages = json.optInt("totalPages", 0).coerceAtLeast(0),
            completedPageIndexes = completedPages,
            translations = translations,
            status = json.optString("status").ifBlank { STATUS_RUNNING }
        )
    }

    fun readCheckpoint(file: File): MangaTranslationCheckpoint? =
        runCatching { checkpointFromJson(JSONObject(file.readText())) }.getOrNull()

    fun writeCheckpoint(file: File, checkpoint: MangaTranslationCheckpoint) {
        file.parentFile?.mkdirs()
        file.writeText(checkpointToJson(checkpoint).toString(2))
    }

    fun remainingTranslationIds(
        expectedIds: Collection<String>,
        checkpoint: MangaTranslationCheckpoint?
    ): List<String> {
        val completed = checkpoint?.translations?.keys.orEmpty()
        return expectedIds.filterNot { it in completed }
    }

    fun expandedBubbleRect(
        rect: PdfMappedRect,
        pageWidth: Float,
        pageHeight: Float
    ): PdfMappedRect {
        val safePageWidth = pageWidth.coerceAtLeast(1f)
        val safePageHeight = pageHeight.coerceAtLeast(1f)
        val skinny = rect.width < rect.height * 0.58f
        val narrow = rect.width / safePageWidth < 0.10f
        val tiny = (rect.width * rect.height) / (safePageWidth * safePageHeight) < 0.003f
        val paddingX = when {
            skinny -> max(rect.width * 1.25f, safePageWidth * 0.032f)
            narrow -> max(rect.width * 0.72f, safePageWidth * 0.022f)
            tiny -> max(rect.width * 0.32f, safePageWidth * 0.010f)
            else -> max(rect.width * 0.20f, safePageWidth * 0.006f)
        }
        val paddingY = when {
            skinny -> max(rect.height * 0.18f, safePageHeight * 0.006f)
            tiny -> max(rect.height * 0.24f, safePageHeight * 0.006f)
            else -> max(rect.height * 0.17f, safePageHeight * 0.004f)
        }
        val left = (rect.x - paddingX).coerceAtLeast(0f)
        val bottom = (rect.y - paddingY).coerceAtLeast(0f)
        val right = (rect.x + rect.width + paddingX).coerceAtMost(safePageWidth)
        val top = (rect.y + rect.height + paddingY).coerceAtMost(safePageHeight)
        val expanded = PdfMappedRect(
            x = left,
            y = bottom,
            width = (right - left).coerceAtLeast(rect.width),
            height = (top - bottom).coerceAtLeast(rect.height)
        )
        val maxWidth = safePageWidth * if (skinny || narrow) 0.24f else 0.46f
        val maxHeight = safePageHeight * if (tiny) 0.16f else 0.28f
        return clampAroundCenter(expanded, rect, safePageWidth, safePageHeight, maxWidth, maxHeight)
    }

    private fun clampAroundCenter(
        expanded: PdfMappedRect,
        original: PdfMappedRect,
        pageWidth: Float,
        pageHeight: Float,
        maxWidth: Float,
        maxHeight: Float
    ): PdfMappedRect {
        val targetWidth = expanded.width.coerceAtMost(maxWidth.coerceAtLeast(original.width))
        val targetHeight = expanded.height.coerceAtMost(maxHeight.coerceAtLeast(original.height))
        val centerX = original.x + original.width / 2f
        val centerY = original.y + original.height / 2f
        val left = (centerX - targetWidth / 2f).coerceIn(0f, (pageWidth - targetWidth).coerceAtLeast(0f))
        val bottom = (centerY - targetHeight / 2f).coerceIn(0f, (pageHeight - targetHeight).coerceAtLeast(0f))
        return PdfMappedRect(
            x = left,
            y = bottom,
            width = targetWidth,
            height = targetHeight
        )
    }

    fun mergedRegionIsTooLarge(rect: PdfMappedRect, pageWidth: Float, pageHeight: Float): Boolean {
        val safePageWidth = pageWidth.coerceAtLeast(1f)
        val safePageHeight = pageHeight.coerceAtLeast(1f)
        val widthRatio = rect.width / safePageWidth
        val heightRatio = rect.height / safePageHeight
        val areaRatio = rect.width * rect.height / (safePageWidth * safePageHeight)
        return areaRatio > 0.105f ||
            heightRatio > 0.34f ||
            (widthRatio > 0.62f && heightRatio > 0.14f) ||
            (widthRatio > 0.46f && heightRatio > 0.23f)
    }

    fun fittedTextSize(
        lineCount: Int,
        maxWidth: Float,
        maxHeight: Float,
        preferredMaxSize: Float = 42f,
        minSize: Float = 5f
    ): Float {
        val safeLines = lineCount.coerceAtLeast(1)
        val widthConstrained = maxWidth / 7.5f
        val heightConstrained = maxHeight / (safeLines * 1.05f)
        return min(preferredMaxSize, min(widthConstrained, heightConstrained))
            .coerceIn(minSize, preferredMaxSize)
    }

    fun llamaOcrRequestMaxTokens(
        configuredMaxTokens: Int,
        fullPageContext: Boolean,
        plainFallback: Boolean
    ): Int {
        val configured = configuredMaxTokens.coerceAtLeast(32)
        val cap = when {
            plainFallback -> LLAMA_OCR_PLAIN_FALLBACK_MAX_TOKENS
            fullPageContext -> LLAMA_OCR_FULL_PAGE_MAX_TOKENS
            else -> LLAMA_OCR_REGION_MAX_TOKENS
        }
        return min(configured, cap).coerceAtLeast(32)
    }

    fun shouldRunLlamaOcrPlainFallback(
        mlKitFallbackAvailable: Boolean,
        attemptedPlainFallbacks: Int,
        maxPlainFallbacks: Int = MAX_LLAMA_OCR_PLAIN_FALLBACKS_PER_PAGE
    ): Boolean {
        return !mlKitFallbackAvailable && attemptedPlainFallbacks < maxPlainFallbacks
    }

    fun llamaOcrBudget(
        executionMode: MangaOcrExecutionMode,
        exhaustiveRegions: Boolean
    ): MangaLlamaOcrBudget {
        if (exhaustiveRegions) {
            return MangaLlamaOcrBudget(
                executionMode = executionMode,
                maxRegionalRequestsPerPage = Int.MAX_VALUE,
                maxPlainFallbacksPerPage = MAX_LLAMA_OCR_PLAIN_FALLBACKS_PER_PAGE,
                exhaustiveRegions = true
            )
        }
        return when (executionMode) {
            MangaOcrExecutionMode.PREVIEW -> MangaLlamaOcrBudget(
                executionMode = executionMode,
                maxRegionalRequestsPerPage = PREVIEW_LLAMA_OCR_REGION_BUDGET,
                maxPlainFallbacksPerPage = 1
            )
            MangaOcrExecutionMode.BATCH -> MangaLlamaOcrBudget(
                executionMode = executionMode,
                maxRegionalRequestsPerPage = BATCH_LLAMA_OCR_REGION_BUDGET,
                maxPlainFallbacksPerPage = 1
            )
        }
    }

    fun shouldRunLlamaRegionRequest(
        mlKitText: String,
        regionIndex: Int,
        budget: MangaLlamaOcrBudget
    ): Boolean {
        if (budget.exhaustiveRegions) return true
        return regionIndex < budget.maxRegionalRequestsPerPage &&
            (mlKitText.isBlank() || mlKitRegionTextLooksWeak(mlKitText))
    }

    fun mlKitRegionTextLooksWeak(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.length <= 2) return true
        val letters = compact.count(Char::isLetter)
        val digits = compact.count(Char::isDigit)
        val signal = letters + digits
        if (signal == 0) return true
        val punctuationRatio = 1f - (signal.toFloat() / compact.length.toFloat().coerceAtLeast(1f))
        return punctuationRatio > 0.58f ||
            text.contains("�") ||
            Regex("""[A-Za-z]\s+[A-Za-z]\s+[A-Za-z]""").containsMatchIn(text)
    }

    fun sanitizeLlamaOcrText(
        rawOutput: String,
        prompt: String,
        stopType: String?,
        imageWidth: Int,
        imageHeight: Int
    ): MangaLlamaOcrSanitizationResult {
        val cleaned = rawOutput
            .replace(Regex("""```(?:\w+)?"""), "")
            .replace("```", "")
            .replace(Regex("""<\|[^>]+>"""), "")
            .lines()
            .map { line -> line.trim() }
            .dropWhile { it.equals("text", ignoreCase = true) || it.equals("ocr", ignoreCase = true) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (cleaned.isBlank()) return MangaLlamaOcrSanitizationResult("")

        val lowerRaw = rawOutput.lowercase(Locale.US)
        val lowerCleaned = cleaned.lowercase(Locale.US)
        val promptCompact = prompt.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        val cleanedCompact = cleaned.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        val promptEcho = promptCompact.length >= 8 && cleanedCompact.contains(promptCompact)
        val mediaOrGroundingLeak = "__media" in lowerRaw ||
            "<|grounding|>" in lowerRaw ||
            "<|det|>" in lowerRaw ||
            "<|/det|>" in lowerRaw
        val pageWideBoxLeak = Regex(
            """(?i)\b(?:image|figure|graphic)\s*\[\s*0+(?:\.0+)?\s*,\s*0+(?:\.0+)?\s*,\s*(?:999|1(?:\.0+)?)\s*,\s*(?:999|1(?:\.0+)?)\s*]"""
        ).containsMatchIn(rawOutput)
        val instructionLeak = listOf(
            "do not guess",
            "don't guess",
            "partial words",
            "return only",
            "recognized text",
            "preserve reading order",
            "convert the document",
            "document parsing",
            "free ocr",
            "no intentes",
            "palabras parciales",
            "solo texto",
            "texto reconocido"
        ).any { phrase -> phrase in lowerCleaned }
        val regionArea = (imageWidth * imageHeight).coerceAtLeast(1)
        val maxReasonableChars = (regionArea / 180).coerceIn(80, 700)
        val limitHallucination = stopType.equals("limit", ignoreCase = true) &&
            cleaned.length > maxReasonableChars

        return if (promptEcho || mediaOrGroundingLeak || pageWideBoxLeak || instructionLeak || limitHallucination) {
            MangaLlamaOcrSanitizationResult(
                text = "",
                rejected = true,
                reason = when {
                    pageWideBoxLeak -> "page_wide_box"
                    instructionLeak || promptEcho || mediaOrGroundingLeak -> "prompt_leak"
                    else -> "stop_limit_hallucination"
                }
            )
        } else {
            MangaLlamaOcrSanitizationResult(cleaned)
        }
    }

    fun classifyMangaOcrTextRole(
        text: String,
        rect: PdfMappedRect,
        pageWidth: Float,
        pageHeight: Float,
        provenance: MangaOcrRegionProvenance
    ): MangaOcrTextRole {
        val trimmed = text.replace(Regex("""\s+"""), " ").trim()
        if (trimmed.isBlank()) return MangaOcrTextRole.UNKNOWN
        val lower = trimmed.lowercase(Locale.US)
        val compact = lower.filter { it.isLetterOrDigit() }
        if (Regex("""^[#№]?\d{1,3}$""").matches(trimmed)) return MangaOcrTextRole.PAGE_NUMBER
        if (
            listOf("presented by", "scanlated", "translated by", "edited by", "proofread", "fairy knight university")
                .any { it in lower }
        ) return MangaOcrTextRole.CREDIT
        val nearTopEdge = rect.y <= pageHeight * 0.12f
        val nearBottomEdge = rect.y + rect.height >= pageHeight * 0.88f
        val edgeBand = nearTopEdge || nearBottomEdge
        val wideTitle = rect.width >= pageWidth * 0.16f
        val speechLike = Regex("""[?!…]|\.{2,}|--|—|["“”']""").containsMatchIn(trimmed)
        val cjkCount = trimmed.count(::isCjkCharacter)
        val letterCount = trimmed.count(Char::isLetter)
        if (edgeBand && wideTitle && !speechLike) return MangaOcrTextRole.DECORATIVE
        if (nearTopEdge && provenance != MangaOcrRegionProvenance.DETECTED_BUBBLE && !speechLike) {
            return MangaOcrTextRole.DECORATIVE
        }
        if (
            cjkCount >= 1 &&
            letterCount <= 8 &&
            !speechLike &&
            provenance != MangaOcrRegionProvenance.DETECTED_BUBBLE
        ) return MangaOcrTextRole.DECORATIVE
        if (speechLike) return MangaOcrTextRole.DIALOGUE
        if (compact.length <= 2 && provenance != MangaOcrRegionProvenance.DETECTED_BUBBLE) {
            return MangaOcrTextRole.DECORATIVE
        }
        return MangaOcrTextRole.NARRATION
    }
}
