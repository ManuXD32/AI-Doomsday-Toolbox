package com.example.llamadroid.service

import com.example.llamadroid.data.LlamaOcrPromptPreset
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.PdfOcrProvider
import com.example.llamadroid.data.PdfTranslationOptionsSnapshot
import com.example.llamadroid.data.PdfTranslationQualityMode
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTranslationSupportTest {
    @Test
    fun `installed OCR filtering pairing and preset inference use indexed models`() {
        val projector = ModelEntity(
            filename = "mmproj-model-f16.gguf",
            path = "/models/mmproj-model-f16.gguf",
            sizeBytes = 10,
            type = ModelType.VISION_PROJECTOR,
            repoId = "org/model",
            isDownloaded = true
        )
        val model = ModelEntity(
            filename = "DeepSeek-OCR-Q4.gguf",
            path = "/models/DeepSeek-OCR-Q4.gguf",
            sizeBytes = 20,
            type = ModelType.LLM,
            repoId = "org/model",
            isDownloaded = true,
            isVision = true
        )

        assertEquals(listOf(model), MangaTranslationSupport.installedOcrModels(listOf(model, projector)))
        assertEquals(listOf(projector), MangaTranslationSupport.installedProjectors(listOf(model, projector)))
        assertEquals(projector, MangaTranslationSupport.matchProjector(model, listOf(projector)))
        assertEquals(
            LlamaOcrPromptPreset.DEEPSEEK_OCR,
            MangaTranslationSupport.inferOcrPreset(model.filename, model.repoId)
        )
    }

    @Test
    fun `OCR selection clears an ambiguous stale projector and infers Unlimited preset`() {
        val model = ModelEntity(
            filename = "Unlimited-OCR-Q4_K_M.gguf",
            path = "/models/unlimited.gguf",
            sizeBytes = 20,
            type = ModelType.VISION,
            repoId = "org/unlimited-ocr",
            isDownloaded = true,
            isVision = true,
            mmprojPath = "/models/old-incompatible-mmproj.gguf"
        )
        val projectors = listOf(
            ModelEntity(
                filename = "mmproj-unlimited-f16.gguf",
                path = "/models/mmproj-a.gguf",
                sizeBytes = 10,
                type = ModelType.VISION_PROJECTOR,
                repoId = "org/unlimited-ocr",
                isDownloaded = true
            ),
            ModelEntity(
                filename = "mmproj-unlimited-f32.gguf",
                path = "/models/mmproj-b.gguf",
                sizeBytes = 11,
                type = ModelType.VISION_PROJECTOR,
                repoId = "org/unlimited-ocr",
                isDownloaded = true
            )
        )

        val selection = MangaTranslationSupport.resolveOcrModelSelection(model, projectors)

        assertEquals(LlamaOcrPromptPreset.UNLIMITED_OCR, selection.promptPreset)
        assertEquals(null, selection.projector)
    }

    @Test
    fun `OCR catalog excludes downloaded text-only GGUFs`() {
        val textOnly = ModelEntity(
            filename = "gemma-text.gguf",
            path = "/models/gemma-text.gguf",
            sizeBytes = 20,
            type = ModelType.LLM,
            repoId = "org/gemma",
            isDownloaded = true,
            isVision = false
        )
        val vision = textOnly.copy(
            filename = "unlimited-ocr.gguf",
            path = "/models/unlimited-ocr.gguf",
            isVision = true
        )

        assertEquals(listOf(vision), MangaTranslationSupport.installedOcrModels(listOf(textOnly, vision)))
    }

    private fun config(
        profile: MangaTranslationProfile = MangaTranslationProfile.BEST_READING
    ): MangaTranslationRunConfig {
        val remote = RemoteSummarySettingsSnapshot(
            backend = "ollama",
            ollamaUrl = "http://127.0.0.1:11434",
            llamaServerUrl = "http://127.0.0.1:8080",
            llamaSwapUrl = "http://127.0.0.1:8080",
            ollamaModel = "qwen2.5:7b",
            llamaSwapModel = null,
            thinkingEnabled = true,
            llamaServerModelLabel = null,
            llamaServerContextTokens = 8192,
            llamaServerContextLabel = null,
            chunkContext = 8192,
            chunkMaxTokens = 1024,
            mergeContext = 8192,
            mergeMaxTokens = 1024,
            temperature = 0.7f,
            timeoutMinutes = 10,
            targetLanguage = "Spanish",
            summaryPrompt = null,
            mergePrompt = null
        )
        val options = PdfTranslationOptionsSnapshot(
            usePageScreenshotContext = true,
            screenshotMaxSide = 1280,
            screenshotJpegQuality = 85,
            textOnlyFallbackEnabled = true,
            qualityMode = PdfTranslationQualityMode.BALANCED,
            ocrProvider = PdfOcrProvider.ML_KIT,
            bubbleGuidedOcrEnabled = false,
            llamaOcr = LlamaOcrSettingsSnapshot(
                modelPath = null,
                mmprojPath = null,
                promptPreset = LlamaOcrPromptPreset.GENERIC_OCR,
                customPrompt = null,
                contextSize = 8192,
                maxTokens = 512,
                port = 8087,
                flashAttention = false,
                cacheRam = 0,
                parallel = 1,
                customFlags = null,
                commandTemplate = null,
                temporarilyReplaceRunningServer = true
            )
        )
        return MangaTranslationRunConfig(
            profile = profile,
            targetLanguage = "Spanish",
            readingDirection = MangaReadingDirection.AUTO,
            translationSettings = remote,
            translationOptions = options,
            behavior = MangaTranslationSupport.defaultBehavior(profile),
            pageImageContextAvailable = false
        )
    }

    @Test
    fun `built in profiles resolve reliable structured output behavior`() {
        val best = config(MangaTranslationProfile.BEST_READING)
        val fast = config(MangaTranslationProfile.FAST)

        assertEquals(PdfTranslationQualityMode.BEST_QUALITY, best.resolvedTranslationOptions().qualityMode)
        assertEquals(MangaOcrStrategy.HYBRID, best.behavior.ocrStrategy)
        assertFalse(best.resolvedTranslationSettings().thinkingEnabled)
        assertEquals(PdfTranslationQualityMode.FASTER, fast.resolvedTranslationOptions().qualityMode)
        assertFalse(fast.behavior.pageImageContextEnabled)
    }

    @Test
    fun `vision capability cache key changes with captured configuration`() {
        val original = config()
        val changed = original.copy(
            translationConfig = original.translationConfig.copy(pageImageMaxSide = 1920)
        )

        assertTrue(
            MangaTranslationSupport.visionCapabilityCacheKey(original) !=
                MangaTranslationSupport.visionCapabilityCacheKey(changed)
        )
    }

    @Test
    fun `preflight separates blockers from vision warnings`() {
        val spec = MangaTranslationJobSpec(
            sources = emptyList(),
            exportPdf = false,
            exportCbz = false,
            config = config()
        )

        val result = MangaTranslationSupport.preflight(spec)

        assertTrue(result.blockers.any { it.code == MangaPreflightCode.NO_SOURCES })
        assertTrue(result.blockers.any { it.code == MangaPreflightCode.NO_OUTPUT })
        assertTrue(result.warnings.any { it.code == MangaPreflightCode.VISION_UNAVAILABLE })
    }

    @Test
    fun `document source compatibility rejects image for PDF output action`() {
        assertFalse(
            MangaTranslationSupport.isSourceCompatible(
                PdfOcrResultAction.SEARCHABLE_PDF,
                "page.png",
                "image/png"
            )
        )
        assertTrue(
            MangaTranslationSupport.isSourceCompatible(
                PdfOcrResultAction.EXTRACT_TEXT_TO_NOTES,
                "page.png",
                "image/png"
            )
        )
    }

    @Test
    fun `reading order follows selected horizontal direction within rows`() {
        val boxes = listOf(
            MangaReadingOrderBox("left", PdfMappedRect(10f, 80f, 20f, 20f), "A"),
            MangaReadingOrderBox("right", PdfMappedRect(80f, 80f, 20f, 20f), "B"),
            MangaReadingOrderBox("lower", PdfMappedRect(20f, 20f, 20f, 20f), "C")
        )

        assertEquals(
            listOf("left", "right", "lower"),
            MangaTranslationSupport.orderReadingBoxes(
                boxes,
                pageHeight = 120f,
                requested = MangaReadingDirection.LEFT_TO_RIGHT
            ).map { it.id }
        )
        assertEquals(
            listOf("right", "left", "lower"),
            MangaTranslationSupport.orderReadingBoxes(
                boxes,
                pageHeight = 120f,
                requested = MangaReadingDirection.RIGHT_TO_LEFT
            ).map { it.id }
        )
    }

    @Test
    fun `hybrid OCR merge keeps captions and removes regional duplicates`() {
        val merged = MangaTranslationSupport.mergeOcrCandidates(
            listOf(
                MangaOcrCandidate("caption", "Meanwhile", PdfOcrBox(10, 10, 120, 40)),
                MangaOcrCandidate("full", "Hello", PdfOcrBox(20, 80, 100, 130)),
                MangaOcrCandidate("region", "Hello", PdfOcrBox(22, 82, 102, 132), preferred = true)
            )
        )

        assertEquals(setOf("caption", "region"), merged.map { it.id }.toSet())
    }

    @Test
    fun `version 5 run configuration round trips OCR references and source snapshot`() {
        val original = config().copy(
            ocrModelRef = MangaTemplateModelRef(
                filename = "Unlimited-OCR-Q4_K_M.gguf",
                repositoryId = "org/unlimited",
                modelType = ModelType.LLM,
                legacyPath = "/models/Unlimited-OCR-Q4_K_M.gguf"
            ),
            ocrProjectorRef = MangaTemplateModelRef(
                filename = "mmproj-Unlimited-OCR-F16.gguf",
                repositoryId = "org/unlimited",
                modelType = ModelType.VISION_PROJECTOR,
                legacyPath = "/models/mmproj-Unlimited-OCR-F16.gguf"
            )
        )
        val restored = MangaTranslationSupport.runConfigFromJson(
            MangaTranslationSupport.runConfigToJson(original),
            config(MangaTranslationProfile.FAST)
        )

        assertEquals(MangaTranslationSupport.TEMPLATE_VERSION, 5)
        assertEquals(original.profile, restored.profile)
        assertEquals(original.behavior, restored.behavior)
        assertEquals(original.ocrModelRef, restored.ocrModelRef)
        assertEquals(original.ocrProjectorRef, restored.ocrProjectorRef)
        assertEquals(original.translationSettings.ollamaModel, restored.translationSettings.ollamaModel)
        assertEquals(true, original.translationSettings.thinkingEnabled)
        assertFalse(restored.resolvedTranslationSettings().thinkingEnabled)
    }

    @Test
    fun `grounded Unlimited OCR parser maps normalized boxes and rejects page image marker`() {
        val spans = MangaTranslationSupport.parseGroundedOcrSpans(
            rawOutput = """
                image [0, 0, 999, 999]
                text [100, 200, 400, 300] I should be safe at school.
                text [0.55, 0.60, 0.82, 0.72] Why can I see them?
            """.trimIndent(),
            imageWidth = 1000,
            imageHeight = 1600
        )

        assertEquals(2, spans.size)
        assertEquals(PdfOcrBox(100, 320, 400, 480), spans[0].box)
        assertEquals(PdfOcrBox(550, 960, 820, 1152), spans[1].box)
        assertFalse(spans.any { it.text.startsWith("image", ignoreCase = true) })
    }

    @Test
    fun `grounded Unlimited OCR parser reads det tags from model card output`() {
        val spans = MangaTranslationSupport.parseGroundedOcrSpans(
            rawOutput = """
                <|det|>title [37, 64, 464, 132]<|/det|>INVOICE #2026-0623
                <|det|>text [37, 194, 350, 247]<|/det|>Bill To: Sahil Chachra
                <|det|>image [0, 0, 999, 999]<|/det|>page
            """.trimIndent(),
            imageWidth = 1000,
            imageHeight = 1600
        )

        assertEquals(2, spans.size)
        assertEquals("invoice #2026-0623", spans[0].text.lowercase())
        assertEquals(PdfOcrBox(37, 102, 464, 211), spans[0].box)
        assertFalse(spans.any { it.kind == "image" })
    }

    @Test
    fun `ungrounded full page OCR never survives as paintable merge candidate`() {
        val merged = MangaTranslationSupport.mergeOcrCandidates(
            listOf(
                MangaOcrCandidate(
                    id = "page",
                    text = "All page dialogue",
                    box = PdfOcrBox(0, 0, 999, 999),
                    provenance = MangaOcrRegionProvenance.UNGROUNDED_FULL_PAGE_CONTEXT
                ),
                MangaOcrCandidate(
                    id = "bubble",
                    text = "Hello",
                    box = PdfOcrBox(100, 100, 220, 180),
                    provenance = MangaOcrRegionProvenance.DETECTED_BUBBLE,
                    containingRegionId = "r1"
                )
            )
        )

        assertEquals(listOf("bubble"), merged.map { it.id })
    }

    @Test
    fun `source classification accepts cbz zip and pdf sources`() {
        assertEquals(
            MangaTranslationSourceKind.CBZ,
            MangaTranslationSupport.sourceKindFor("chapter.cbz", null)
        )
        assertEquals(
            MangaTranslationSourceKind.CBZ,
            MangaTranslationSupport.sourceKindFor("chapter.zip", "application/octet-stream")
        )
        assertEquals(
            MangaTranslationSourceKind.PDF,
            MangaTranslationSupport.sourceKindFor("chapter.pdf", "application/pdf")
        )
        assertTrue(MangaTranslationSupport.isSupportedSource("chapter.CBZ", null))
        assertFalse(MangaTranslationSupport.isSupportedSource("chapter.txt", "text/plain"))
    }

    @Test
    fun `picker accepts include comic archives and pdfs`() {
        val accepted = MangaTranslationSupport.primaryPickerMimeTypes.toSet()

        assertTrue("application/vnd.comicbook+zip" in accepted)
        assertTrue("application/zip" in accepted)
        assertTrue("application/pdf" in accepted)
        assertEquals(listOf("*/*"), MangaTranslationSupport.fallbackPickerMimeTypes.toList())
    }

    @Test
    fun `zip entry validation rejects traversal and unsafe paths`() {
        assertTrue(MangaTranslationSupport.isSafeComicZipEntryName("chapter/001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("../001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("chapter/../001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("/chapter/001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("chapter\\001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("chapter//001.png"))
    }

    @Test
    fun `checkpoint json round trips completed pages and translations`() {
        val checkpoint = MangaTranslationCheckpoint(
            jobId = "job-1",
            sourceName = "chapter.cbz",
            sourceKind = MangaTranslationSourceKind.CBZ,
            exportPdf = true,
            exportCbz = true,
            totalPages = 4,
            completedPageIndexes = setOf(0, 2),
            translations = mapOf("p1_u1" to "Hola", "p3_u2" to "Adios")
        )

        val restored = MangaTranslationSupport.checkpointFromJson(
            MangaTranslationSupport.checkpointToJson(checkpoint)
        )

        assertEquals(checkpoint.jobId, restored.jobId)
        assertEquals(checkpoint.sourceKind, restored.sourceKind)
        assertEquals(checkpoint.completedPageIndexes, restored.completedPageIndexes)
        assertEquals(checkpoint.translations, restored.translations)
    }

    @Test
    fun `remaining ids skips already translated checkpoint entries`() {
        val checkpoint = MangaTranslationCheckpoint(
            jobId = "job-1",
            sourceName = "chapter.pdf",
            sourceKind = MangaTranslationSourceKind.PDF,
            exportPdf = true,
            exportCbz = false,
            translations = mapOf("p1_u1" to "Done")
        )

        assertEquals(
            listOf("p1_u2", "p2_u1"),
            MangaTranslationSupport.remainingTranslationIds(listOf("p1_u1", "p1_u2", "p2_u1"), checkpoint)
        )
    }

    @Test
    fun `layout helper expands skinny manga OCR regions`() {
        val original = PdfMappedRect(x = 100f, y = 200f, width = 20f, height = 140f)
        val expanded = MangaTranslationSupport.expandedBubbleRect(original, pageWidth = 1000f, pageHeight = 1600f)

        assertTrue(expanded.width > original.width * 2f)
        assertTrue(expanded.height >= original.height)
        assertTrue(expanded.x >= 0f)
    }

    @Test
    fun `layout helper expands tiny fragments without panel wide merge`() {
        val original = PdfMappedRect(x = 450f, y = 760f, width = 28f, height = 22f)
        val expanded = MangaTranslationSupport.expandedBubbleRect(original, pageWidth = 1000f, pageHeight = 1600f)

        assertTrue(expanded.width > original.width)
        assertTrue(expanded.height > original.height)
        assertTrue(expanded.width < 460f)
        assertTrue(expanded.height < 448f)
    }

    @Test
    fun `layout helper rejects oversized merged regions`() {
        assertTrue(
            MangaTranslationSupport.mergedRegionIsTooLarge(
                PdfMappedRect(x = 0f, y = 0f, width = 800f, height = 500f),
                pageWidth = 1000f,
                pageHeight = 1600f
            )
        )
        assertFalse(
            MangaTranslationSupport.mergedRegionIsTooLarge(
                PdfMappedRect(x = 120f, y = 200f, width = 180f, height = 90f),
                pageWidth = 1000f,
                pageHeight = 1600f
            )
        )
    }

    @Test
    fun `text fit helper reduces size for narrow regions`() {
        val wide = MangaTranslationSupport.fittedTextSize(lineCount = 2, maxWidth = 300f, maxHeight = 120f)
        val narrow = MangaTranslationSupport.fittedTextSize(lineCount = 8, maxWidth = 45f, maxHeight = 90f)

        assertTrue(wide > narrow)
        assertTrue(narrow >= 5f)
    }

    @Test
    fun `llama OCR request tokens are bounded by request type`() {
        assertEquals(
            1024,
            MangaTranslationSupport.llamaOcrRequestMaxTokens(
                configuredMaxTokens = 2600,
                fullPageContext = true,
                plainFallback = false
            )
        )
        assertEquals(
            384,
            MangaTranslationSupport.llamaOcrRequestMaxTokens(
                configuredMaxTokens = 2600,
                fullPageContext = false,
                plainFallback = false
            )
        )
        assertEquals(
            128,
            MangaTranslationSupport.llamaOcrRequestMaxTokens(
                configuredMaxTokens = 2600,
                fullPageContext = false,
                plainFallback = true
            )
        )
        assertEquals(
            32,
            MangaTranslationSupport.llamaOcrRequestMaxTokens(
                configuredMaxTokens = 8,
                fullPageContext = false,
                plainFallback = true
            )
        )
    }

    @Test
    fun `llama OCR budget caps preview and batch crop requests unless exhaustive`() {
        val preview = MangaTranslationSupport.llamaOcrBudget(
            executionMode = MangaOcrExecutionMode.PREVIEW,
            exhaustiveRegions = false
        )
        val batch = MangaTranslationSupport.llamaOcrBudget(
            executionMode = MangaOcrExecutionMode.BATCH,
            exhaustiveRegions = false
        )
        val exhaustive = MangaTranslationSupport.llamaOcrBudget(
            executionMode = MangaOcrExecutionMode.PREVIEW,
            exhaustiveRegions = true
        )

        assertEquals(3, preview.maxRegionalRequestsPerPage)
        assertEquals(8, batch.maxRegionalRequestsPerPage)
        assertEquals(1, preview.maxPlainFallbacksPerPage)
        assertTrue(exhaustive.exhaustiveRegions)
        assertEquals(Int.MAX_VALUE, exhaustive.maxRegionalRequestsPerPage)
    }

    @Test
    fun `llama OCR budget only targets missing or weak ML Kit regions by default`() {
        val preview = MangaTranslationSupport.llamaOcrBudget(
            executionMode = MangaOcrExecutionMode.PREVIEW,
            exhaustiveRegions = false
        )

        assertTrue(
            MangaTranslationSupport.shouldRunLlamaRegionRequest(
                mlKitText = "",
                regionIndex = 0,
                budget = preview
            )
        )
        assertTrue(
            MangaTranslationSupport.shouldRunLlamaRegionRequest(
                mlKitText = "V F A",
                regionIndex = 1,
                budget = preview
            )
        )
        assertFalse(
            MangaTranslationSupport.shouldRunLlamaRegionRequest(
                mlKitText = "I should be safe at school.",
                regionIndex = 2,
                budget = preview
            )
        )
        assertFalse(
            MangaTranslationSupport.shouldRunLlamaRegionRequest(
                mlKitText = "",
                regionIndex = 3,
                budget = preview
            )
        )
    }

    @Test
    fun `llama OCR sanitizer rejects prompt leaks page wide boxes and stop-limit hallucinations`() {
        val prompt = "Do not guess or complete partial words. Return only recognized text."

        val promptLeak = MangaTranslationSupport.sanitizeLlamaOcrText(
            rawOutput = "No intentes adivinar o completar palabras parciales.",
            prompt = prompt,
            stopType = "eos",
            imageWidth = 120,
            imageHeight = 120
        )
        val pageWide = MangaTranslationSupport.sanitizeLlamaOcrText(
            rawOutput = "image [0, 0, 999, 999]",
            prompt = prompt,
            stopType = "eos",
            imageWidth = 1000,
            imageHeight = 1600
        )
        val limit = MangaTranslationSupport.sanitizeLlamaOcrText(
            rawOutput = List(40) { "hallucinated instruction fragment" }.joinToString(" "),
            prompt = prompt,
            stopType = "limit",
            imageWidth = 64,
            imageHeight = 64
        )

        assertTrue(promptLeak.rejected)
        assertEquals("prompt_leak", promptLeak.reason)
        assertTrue(pageWide.rejected)
        assertEquals("page_wide_box", pageWide.reason)
        assertTrue(limit.rejected)
        assertEquals("stop_limit_hallucination", limit.reason)
    }

    @Test
    fun `llama OCR sanitizer keeps ordinary crop text`() {
        val result = MangaTranslationSupport.sanitizeLlamaOcrText(
            rawOutput = "I should be safe at school.",
            prompt = "Return only recognized text.",
            stopType = "eos",
            imageWidth = 200,
            imageHeight = 120
        )

        assertFalse(result.rejected)
        assertEquals("I should be safe at school.", result.text)
    }

    @Test
    fun `plain llama OCR fallback is skipped when ML Kit has text or page budget is spent`() {
        assertFalse(
            MangaTranslationSupport.shouldRunLlamaOcrPlainFallback(
                mlKitFallbackAvailable = true,
                attemptedPlainFallbacks = 0
            )
        )
        assertTrue(
            MangaTranslationSupport.shouldRunLlamaOcrPlainFallback(
                mlKitFallbackAvailable = false,
                attemptedPlainFallbacks = 1
            )
        )
        assertFalse(
            MangaTranslationSupport.shouldRunLlamaOcrPlainFallback(
                mlKitFallbackAvailable = false,
                attemptedPlainFallbacks = MangaTranslationSupport.MAX_LLAMA_OCR_PLAIN_FALLBACKS_PER_PAGE
            )
        )
    }

    @Test
    fun `overlay overlap ratio detects collisions without penalizing separated bubbles`() {
        val first = PdfMappedRect(10f, 10f, 40f, 40f)
        val overlapping = PdfMappedRect(35f, 20f, 40f, 40f)
        val separated = PdfMappedRect(80f, 80f, 10f, 10f)

        assertTrue(MangaTranslationSupport.overlayOverlapRatio(first, overlapping) > 0.25f)
        assertEquals(0f, MangaTranslationSupport.overlayOverlapRatio(first, separated))
    }

    @Test
    fun `ML Kit reconciliation selects one complete recognition pass per region`() {
        assertEquals(
            MangaOcrRecognitionPass.FULL_PAGE_ML_KIT,
            MangaTranslationSupport.preferredMlKitRecognitionPass(
                fullPageTexts = listOf("Maybe I cannot see them anymore", "I have not seen anyone today"),
                regionalTexts = listOf("Maybe I cannot")
            )
        )
        assertEquals(
            MangaOcrRecognitionPass.REGIONAL_ML_KIT,
            MangaTranslationSupport.preferredMlKitRecognitionPass(
                fullPageTexts = listOf("MAY8E I C4NT"),
                regionalTexts = listOf("Maybe I cannot see them anymore")
            )
        )
    }

    @Test
    fun `same bubble fragments are deduplicated without dropping distinct columns`() {
        assertEquals(
            listOf(
                "Maybe I cannot see them anymore",
                "I have not seen anyone today"
            ),
            MangaTranslationSupport.dedupeOcrTextFragments(
                listOf(
                    "Maybe I cannot see them",
                    "Maybe I cannot see them anymore",
                    "I have not seen anyone today",
                    "I have not seen anyone today"
                )
            )
        )
    }

    @Test
    fun `manga OCR role classifier preserves credits titles and page numbers by default`() {
        assertEquals(
            MangaOcrTextRole.CREDIT,
            MangaTranslationSupport.classifyMangaOcrTextRole(
                text = "Presented by Fairy Knight University",
                rect = PdfMappedRect(700f, 1500f, 240f, 70f),
                pageWidth = 1000f,
                pageHeight = 1600f,
                provenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK
            )
        )
        assertEquals(
            MangaOcrTextRole.PAGE_NUMBER,
            MangaTranslationSupport.classifyMangaOcrTextRole(
                text = "2",
                rect = PdfMappedRect(800f, 1480f, 40f, 40f),
                pageWidth = 1000f,
                pageHeight = 1600f,
                provenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK
            )
        )
        assertEquals(
            MangaOcrTextRole.DECORATIVE,
            MangaTranslationSupport.classifyMangaOcrTextRole(
                text = "見える子ちゃん",
                rect = PdfMappedRect(50f, 1480f, 420f, 80f),
                pageWidth = 1000f,
                pageHeight = 1600f,
                provenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK
            )
        )
        assertEquals(
            MangaOcrTextRole.DECORATIVE,
            MangaTranslationSupport.classifyMangaOcrTextRole(
                text = "Mieruko-chan",
                rect = PdfMappedRect(40f, 24f, 240f, 42f),
                pageWidth = 1000f,
                pageHeight = 1600f,
                provenance = MangaOcrRegionProvenance.ML_KIT_TEXT_BLOCK
            )
        )
    }
}
