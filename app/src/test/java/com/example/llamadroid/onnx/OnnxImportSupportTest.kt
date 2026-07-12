package com.example.llamadroid.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class OnnxImportSupportTest {

    @Test
    fun `choose import strategy always copies into managed storage`() {
        assertEquals(
            OnnxImportStrategy.COPY_TO_MANAGED,
            OnnxImportSupport.chooseImportStrategy(
                resolvedPath = "/storage/emulated/0/Download/SDAI/model/demo",
                hasAllFilesAccess = true,
                isPathAccessible = true
            )
        )
        assertEquals(
            OnnxImportStrategy.COPY_TO_MANAGED,
            OnnxImportSupport.chooseImportStrategy(
                resolvedPath = "/storage/emulated/0/Download/SDAI/model/demo",
                hasAllFilesAccess = false,
                isPathAccessible = true
            )
        )
        assertEquals(
            OnnxImportStrategy.COPY_TO_MANAGED,
            OnnxImportSupport.chooseImportStrategy(
                resolvedPath = null,
                hasAllFilesAccess = true,
                isPathAccessible = false
            )
        )
    }

    @Test
    fun `make unique bundle id appends numeric suffix`() {
        val existing = setOf("dreamshaper", "dreamshaper_2")

        assertEquals("dreamshaper_3", OnnxImportSupport.makeUniqueBundleId("dreamshaper", existing))
        assertEquals("analogMadness", OnnxImportSupport.makeUniqueBundleId("analogMadness", existing))
    }

    @Test
    fun `delete recursively removes nested managed bundle`() {
        val root = createTempDirectory("onnx-delete-test").toFile()
        File(root, "tokenizer/merges.txt").apply {
            parentFile?.mkdirs()
            writeText("merge")
        }

        val deleted = OnnxImportSupport.deleteRecursively(root)

        assertTrue(deleted)
        assertFalse(root.exists())
    }

    @Test
    fun `extract bundle archive accepts nested sdai bundle folder`() {
        val workspace = createTempDirectory("onnx-archive-test").toFile()
        val archive = File(workspace, "bundle.zip")
        val installDir = File(workspace, "installed")

        createZip(
            archive,
            mapOf(
                "dreamshaper/text_encoder/model.ort" to "text".toByteArray(),
                "dreamshaper/unet/model.ort" to "unet".toByteArray(),
                "dreamshaper/vae_decoder/model.ort" to "vae".toByteArray(),
                "dreamshaper/tokenizer/vocab.json" to "{}".toByteArray(),
                "dreamshaper/tokenizer/merges.txt" to "a b".toByteArray()
            )
        )

        OnnxImportSupport.extractBundleArchive(archive, installDir)

        val validation = OnnxBundleValidator.validateDirectory(installDir)
        assertTrue(validation.isValid)
        assertEquals(setOf("txt2img"), validation.supportedCapabilities)
    }

    @Test
    fun `metadata sidecar round trips for generated image`() {
        val workspace = createTempDirectory("onnx-metadata-test").toFile()
        val imageFile = File(workspace, "sample.png").apply { writeText("png") }
        val metadata = OnnxGeneratedImageMetadata(
            imagePath = imageFile.absolutePath,
            modelName = "dreamshaper",
            prompt = "cat astronaut",
            negativePrompt = "blurry",
            mode = OnnxImageGenMode.IMG2IMG.name,
            requestedWidth = 513,
            requestedHeight = 511,
            width = 512,
            height = 512,
            steps = 20,
            cfgScale = 7.5f,
            seed = 1234L,
            initImagePath = "/tmp/source.png",
            initImageOriginalWidth = 1024,
            initImageOriginalHeight = 768,
            initImageCanvasWidth = 512,
            initImageCanvasHeight = 512,
            initImageFittedWidth = 512,
            initImageFittedHeight = 384,
            initImagePaddingLeft = 0,
            initImagePaddingTop = 64,
            strength = 0.42f,
            effectiveSteps = 8,
            backend = "CPU",
            createdAtEpochMs = 42L,
            warningMessage = "fallback"
        )

        OnnxStorage.writeMetadata(imageFile, metadata)
        val loaded = OnnxStorage.readMetadata(imageFile)

        assertEquals(metadata.modelName, loaded?.modelName)
        assertEquals(metadata.prompt, loaded?.prompt)
        assertEquals(metadata.mode, loaded?.mode)
        assertEquals(metadata.initImagePath, loaded?.initImagePath)
        assertEquals(metadata.initImageOriginalWidth, loaded?.initImageOriginalWidth)
        assertEquals(metadata.initImageOriginalHeight, loaded?.initImageOriginalHeight)
        assertEquals(metadata.initImageCanvasWidth, loaded?.initImageCanvasWidth)
        assertEquals(metadata.initImageCanvasHeight, loaded?.initImageCanvasHeight)
        assertEquals(metadata.initImageFittedWidth, loaded?.initImageFittedWidth)
        assertEquals(metadata.initImageFittedHeight, loaded?.initImageFittedHeight)
        assertEquals(metadata.initImagePaddingLeft, loaded?.initImagePaddingLeft)
        assertEquals(metadata.initImagePaddingTop, loaded?.initImagePaddingTop)
        assertEquals(metadata.strength, loaded?.strength)
        assertEquals(metadata.effectiveSteps, loaded?.effectiveSteps)
        assertEquals(metadata.warningMessage, loaded?.warningMessage)
        assertEquals(metadata.requestedWidth, loaded?.requestedWidth)
        assertEquals(metadata.requestedHeight, loaded?.requestedHeight)
        assertTrue(OnnxStorage.metadataFileFor(imageFile).exists())
    }

    @Test
    fun `img2img canvas placement preserves landscape aspect ratio with top bottom padding`() {
        val placement = computeOnnxImg2ImgCanvasPlacement(originalWidth = 1024, originalHeight = 512)

        assertEquals(512, placement.canvasWidth)
        assertEquals(512, placement.canvasHeight)
        assertEquals(512, placement.fittedWidth)
        assertEquals(256, placement.fittedHeight)
        assertEquals(0, placement.paddingLeft)
        assertEquals(128, placement.paddingTop)
    }

    @Test
    fun `img2img canvas placement preserves portrait aspect ratio with side padding`() {
        val placement = computeOnnxImg2ImgCanvasPlacement(originalWidth = 512, originalHeight = 1024)

        assertEquals(256, placement.fittedWidth)
        assertEquals(512, placement.fittedHeight)
        assertEquals(128, placement.paddingLeft)
        assertEquals(0, placement.paddingTop)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extract bundle archive blocks zip slip entries`() {
        val workspace = createTempDirectory("onnx-zip-slip-test").toFile()
        val archive = File(workspace, "bundle.zip")
        val installDir = File(workspace, "installed")

        createZip(
            archive,
            mapOf(
                "../escape.txt" to "bad".toByteArray(),
                "dreamshaper/text_encoder/model.ort" to "text".toByteArray()
            )
        )

        OnnxImportSupport.extractBundleArchive(archive, installDir)
    }

    @Test
    fun `backend resolution keeps nnapi when session creation succeeds`() {
        val resolution = resolveOnnxBackend(
            requestedBackend = OnnxRuntimeBackend.NNAPI,
            componentLabel = "unet",
            nnapiErrorMessage = null
        )

        assertEquals(OnnxRuntimeBackend.NNAPI, resolution.resolvedBackend)
        assertNull(resolution.warningMessage)
    }

    @Test
    fun `backend resolution falls back to cpu with warning when nnapi fails`() {
        val resolution = resolveOnnxBackend(
            requestedBackend = OnnxRuntimeBackend.NNAPI,
            componentLabel = "vae_decoder",
            nnapiErrorMessage = "provider unavailable"
        )

        assertEquals(OnnxRuntimeBackend.CPU, resolution.resolvedBackend)
        assertTrue(resolution.warningMessage!!.contains("provider unavailable"))
    }

    @Test
    fun `img2img start index increases denoising window with higher strength`() {
        val lowStrengthStart = computeOnnxImg2ImgStartIndex(totalSteps = 20, strength = 0.2f)
        val highStrengthStart = computeOnnxImg2ImgStartIndex(totalSteps = 20, strength = 0.8f)

        assertTrue(highStrengthStart < lowStrengthStart)
    }

    @Test
    fun `img2img effective steps reflect active portion of schedule`() {
        val gentleEditSteps = computeOnnxImg2ImgEffectiveSteps(totalSteps = 20, strength = 0.35f)
        val heavyEditSteps = computeOnnxImg2ImgEffectiveSteps(totalSteps = 20, strength = 0.8f)

        assertEquals(7, gentleEditSteps)
        assertEquals(16, heavyEditSteps)
        assertTrue(heavyEditSteps > gentleEditSteps)
    }

    @Test
    fun `provider qualified ids keep sdai and manuxd32 installs separate`() {
        assertEquals("sdai__dreamshaper", buildOnnxCatalogStableId(OnnxCatalogProvider.SDAI, "dreamshaper"))
        assertEquals("manuxd32__dreamshaper", buildOnnxCatalogStableId(OnnxCatalogProvider.MANUXD32, "dreamshaper"))
        assertEquals("onnx_catalog/manuxd32/dreamshaper", buildOnnxCatalogRepoId(OnnxCatalogProvider.MANUXD32, "dreamshaper"))
    }

    @Test
    fun `euler timesteps follow sdai style reversed linspace schedule`() {
        val timesteps = createOnnxDiffusionTimesteps(inferenceSteps = 20)

        assertEquals(20, timesteps.size)
        assertEquals(999, timesteps.first())
        assertEquals(946, timesteps[1])
        assertEquals(0, timesteps.last())
    }

    @Test
    fun `euler scheduler scales model input by sigma`() {
        val scheduler = OnnxEulerAncestralScheduler(
            inferenceSteps = 20,
            seed = 1234L,
            predictionType = OnnxPredictionType.EPSILON
        )
        val sample = floatArrayOf(1f, -1f, 0.5f)

        val scaled = scheduler.scaleModelInput(sample, stepIndex = 0)

        assertTrue(kotlin.math.abs(scaled[0]) < kotlin.math.abs(sample[0]))
        assertTrue(kotlin.math.abs(scaled[1]) < kotlin.math.abs(sample[1]))
    }

    @Test
    fun `shared export path keeps onnx outputs in their own folder`() {
        val relativePath = OnnxStorage.sharedExportRelativePath(
            mode = OnnxImageGenMode.IMG2IMG,
            fileName = "img2img_20260408.png"
        )

        assertEquals("images/onnx/img2img/img2img_20260408.png", relativePath)
    }

    @Test
    fun `onnx normalization accepts 64 and rounds to nearest latent grid`() {
        val minimum = normalizeOnnxCanvasSize(requestedWidth = 64, requestedHeight = 64)
        val rounded = normalizeOnnxCanvasSize(requestedWidth = 65, requestedHeight = 127)

        assertEquals(64, minimum.normalizedWidth)
        assertEquals(64, minimum.normalizedHeight)
        assertEquals(64, rounded.normalizedWidth)
        assertEquals(128, rounded.normalizedHeight)
        assertTrue(rounded.wasAdjusted)
    }

    private fun createZip(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(target)).use { output ->
            entries.forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
