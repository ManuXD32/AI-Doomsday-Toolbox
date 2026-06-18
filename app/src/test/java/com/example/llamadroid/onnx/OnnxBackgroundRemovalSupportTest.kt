package com.example.llamadroid.onnx

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ONNX_CAPABILITY_BACKGROUND_REMOVAL
import com.example.llamadroid.data.db.buildOnnxCapabilities
import com.example.llamadroid.service.NativeChatBackgroundRemovalToolParams
import com.example.llamadroid.service.NativeChatToolConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class OnnxBackgroundRemovalSupportTest {

    @Test
    fun `background removal catalog exposes expected variants and sizes`() {
        val entries = OnnxCatalog.entriesFor(OnnxCatalogProvider.BACKGROUND_REMOVAL)
        val byId = entries.associateBy { it.bundleId }

        assertEquals(14, entries.size)
        assertEquals(219_000_000L, byId.getValue("ben2_fp16").archiveSizeBytes)
        assertEquals(1_020_000_000L, byId.getValue("rmbg_2_0_full").archiveSizeBytes)
        assertEquals(234_000_000L, byId.getValue("rmbg_2_0_q4f16").archiveSizeBytes)
        assertEquals(44_400_000L, byId.getValue("rmbg_1_4_quantized").archiveSizeBytes)
        assertEquals(199_000_000L, byId.getValue("inspyrenet_swinb_fp16").archiveSizeBytes)
    }

    @Test
    fun `bria background removal entries are gated direct downloads`() {
        val entries = OnnxCatalog.entriesFor(OnnxCatalogProvider.BACKGROUND_REMOVAL)
        val briaEntries = entries.filter { it.sourceLabel.startsWith("briaai/") }
        val openEntries = entries - briaEntries.toSet()

        assertTrue(briaEntries.isNotEmpty())
        assertTrue(briaEntries.all { it.gated })
        assertTrue(openEntries.all { !it.gated })
        assertTrue(briaEntries.all { it.downloadUrl.startsWith("https://huggingface.co/briaai/") })
        assertTrue(briaEntries.all { it.downloadUrl.contains("/resolve/main/onnx/") })
    }

    @Test
    fun `background removal catalog rows use stable model metadata`() {
        val entry = OnnxCatalog.entriesFor(OnnxCatalogProvider.BACKGROUND_REMOVAL)
            .first { it.bundleId == "rmbg_1_4_fp16" }

        assertEquals(ModelType.ONNX_BACKGROUND_REMOVAL, entry.modelType)
        assertEquals(ONNX_PIPELINE_FAMILY_BACKGROUND_REMOVAL, entry.pipelineFamily)
        assertEquals(ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE, entry.assetKind)
        assertEquals(buildOnnxCapabilities(ONNX_CAPABILITY_BACKGROUND_REMOVAL), entry.capabilities)
        assertEquals("background_removal__rmbg_1_4_fp16", entry.stableId)
        assertEquals("onnx_catalog/background_removal/rmbg_1_4_fp16", entry.repoId)
    }

    @Test
    fun `model row helper accepts only background removal rows`() {
        val valid = modelEntity(
            type = ModelType.ONNX_BACKGROUND_REMOVAL,
            capabilities = buildOnnxCapabilities(ONNX_CAPABILITY_BACKGROUND_REMOVAL),
            pipelineFamily = ONNX_PIPELINE_FAMILY_BACKGROUND_REMOVAL
        )
        val wrongType = valid.copy(type = ModelType.ONNX_IMAGE_GEN)
        val wrongCapability = valid.copy(onnxCapabilities = buildOnnxCapabilities("txt2img"))
        val wrongPipeline = valid.copy(onnxPipelineFamily = ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION)

        assertTrue(valid.isOnnxBackgroundRemovalModel())
        assertFalse(wrongType.isOnnxBackgroundRemovalModel())
        assertFalse(wrongCapability.isOnnxBackgroundRemovalModel())
        assertFalse(wrongPipeline.isOnnxBackgroundRemovalModel())
    }

    @Test
    fun `native chat background removal params persist with stable keys and defaults`() {
        val params = NativeChatBackgroundRemovalToolParams(
            model = "background_removal__ben2_fp16",
            backend = OnnxRuntimeBackend.NNAPI,
            runtimeThreads = 3,
            graphOptimizationLevel = OnnxGraphOptimizationLevel.EXTENDED,
            alphaThreshold = 0.55f,
            featherRadius = 2,
            maskSoftness = 0.8f,
            maskContrast = 1.2f,
            exportMask = true,
            resizeBeforeProcessing = true,
            resizeMaxEdge = 768
        )
        val config = NativeChatToolConfig(
            toolsEnabled = true,
            backgroundRemovalEnabled = true,
            backgroundRemovalParams = params
        )

        val parsed = NativeChatToolConfig.fromParams(config.toParamMap())

        assertTrue(parsed.backgroundRemovalEnabled)
        assertEquals(params.model, parsed.backgroundRemovalParams.model)
        assertEquals(OnnxRuntimeBackend.NNAPI, parsed.backgroundRemovalParams.backend)
        assertEquals(3, parsed.backgroundRemovalParams.runtimeThreads)
        assertEquals(OnnxGraphOptimizationLevel.EXTENDED, parsed.backgroundRemovalParams.graphOptimizationLevel)
        assertEquals(0.55f, parsed.backgroundRemovalParams.alphaThreshold, 0.001f)
        assertEquals(2, parsed.backgroundRemovalParams.featherRadius)
        assertEquals(0.8f, parsed.backgroundRemovalParams.maskSoftness, 0.001f)
        assertEquals(1.2f, parsed.backgroundRemovalParams.maskContrast, 0.001f)
        assertTrue(parsed.backgroundRemovalParams.exportMask)
        assertTrue(parsed.backgroundRemovalParams.resizeBeforeProcessing)
        assertEquals(768, parsed.backgroundRemovalParams.resizeMaxEdge)
    }

    @Test
    fun `alpha post processing supports soft masks and hard thresholding`() {
        val mask = floatArrayOf(0.1f, 0.4f, 0.6f, 0.9f)

        val hard = postProcessAlpha(mask, width = 2, height = 2, threshold = 0.5f, featherRadius = 0, softness = 0f, contrast = 1f)
        val soft = postProcessAlpha(mask, width = 2, height = 2, threshold = 0.5f, featherRadius = 0, softness = 1f, contrast = 1f)

        assertEquals(listOf(0, 0, 255, 255), hard.toList())
        assertTrue(soft[0] in 20..30)
        assertTrue(soft[1] in 95..110)
        assertTrue(soft[2] in 145..160)
        assertTrue(soft[3] in 225..235)
    }

    @Test
    fun `mask extraction reads NCHW mask plane instead of flattening channels into width`() {
        val pixels = 5 * 5
        val maskPlane = FloatArray(pixels) { it / pixels.toFloat() }
        val values = maskPlane + maskPlane + maskPlane

        val mask = extractMaskFromValues(values, listOf(1, 3, 5, 5))

        assertEquals(5, mask.width)
        assertEquals(5, mask.height)
        assertArrayEquals(maskPlane, mask.values, 0.0001f)
    }

    @Test
    fun `mask extraction uses alpha channel for RGBA style outputs`() {
        val values = FloatArray(5 * 5 * 4) { index ->
            if (index % 4 == 3) index / 100f else 0f
        }
        val expected = FloatArray(5 * 5) { index -> (index * 4 + 3) / 100f }

        val mask = extractMaskFromValues(values, listOf(1, 5, 5, 4))

        assertEquals(5, mask.width)
        assertEquals(5, mask.height)
        assertArrayEquals(expected, mask.values, 0.0001f)
    }

    @Test
    fun `BEN2 masks are min max normalized before alpha post processing`() {
        val mask = floatArrayOf(0.55f, 0.65f, 0.85f, 0.95f)

        val normalized = normalizeMask(mask, modelName = "background_removal__ben2_fp16")

        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.75f, 1f), normalized, 0.0001f)
    }

    @Test
    fun `non BEN masks keep calibrated probabilities`() {
        val mask = floatArrayOf(0.55f, 0.65f, 0.85f, 0.95f)

        val normalized = normalizeMask(mask, modelName = "rmbg_2_0_fp16")

        assertArrayEquals(mask, normalized, 0.0001f)
    }

    @Test
    fun `metadata sidecar and mask are removed with output`() {
        val workspace = createTempDirectory("bgr-sidecar-test").toFile()
        val imageFile = File(workspace, "sample_bgr.png").apply { writeText("png") }
        OnnxBackgroundRemovalStorage.maskFileFor(imageFile).writeText("mask")
        OnnxBackgroundRemovalStorage.writeMetadata(
            imageFile,
            OnnxBackgroundRemovalMetadata(
                outputPath = imageFile.absolutePath,
                maskPath = OnnxBackgroundRemovalStorage.maskFileFor(imageFile).absolutePath,
                sourceName = "sample.png",
                sourcePath = "/tmp/sample.png",
                modelName = "BEN2 FP16",
                backend = "CPU",
                resolvedBackend = "CPU",
                alphaThreshold = 0.5f,
                featherRadius = 1,
                maskSoftness = 1f,
                maskContrast = 1f,
                exportMask = true,
                width = 2,
                height = 2,
                createdAtEpochMs = 42L,
                durationMs = 100L
            )
        )

        assertTrue(OnnxBackgroundRemovalStorage.deleteImageWithMetadata(imageFile))
        assertFalse(imageFile.exists())
        assertFalse(OnnxBackgroundRemovalStorage.metadataFileFor(imageFile).exists())
        assertFalse(OnnxBackgroundRemovalStorage.maskFileFor(imageFile).exists())
    }

    private fun modelEntity(
        type: ModelType,
        capabilities: String?,
        pipelineFamily: String?
    ): ModelEntity = ModelEntity(
        filename = "ben2_fp16",
        path = "/models/ben2_fp16/model_fp16.onnx",
        sizeBytes = 219_000_000L,
        type = type,
        repoId = "onnx_catalog/background_removal/ben2_fp16",
        isDownloaded = true,
        onnxCapabilities = capabilities,
        onnxAssetKind = ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE,
        onnxPipelineFamily = pipelineFamily
    )
}
