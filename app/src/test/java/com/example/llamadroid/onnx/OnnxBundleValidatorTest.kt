package com.example.llamadroid.onnx

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildOnnxCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class OnnxBundleValidatorTest {

    @Test
    fun `valid bundle passes strict SDAI validation`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)

        val result = OnnxBundleValidator.validateDirectory(root)

        assertTrue(result.isValid)
        assertTrue(result.missingPaths.isEmpty())
        assertEquals(setOf("txt2img"), result.supportedCapabilities)
    }

    @Test
    fun `missing bundle files are reported`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        File(root, "tokenizer/merges.txt").delete()
        File(root, "unet/model.ort").delete()

        val result = OnnxBundleValidator.validateDirectory(root)

        assertFalse(result.isValid)
        assertEquals(
            listOf("unet/model.ort", "tokenizer/merges.txt"),
            result.missingPaths
        )
        assertTrue(result.supportedCapabilities.isEmpty())
    }

    @Test
    fun `bundle with vae encoder supports img2img`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        File(root, OnnxBundleValidator.img2imgEncoderRelativePath).apply {
            parentFile?.mkdirs()
            writeText("encoder")
        }

        val result = OnnxBundleValidator.validateDirectory(root)

        assertTrue(result.isValid)
        assertEquals(setOf("txt2img", "img2img"), result.supportedCapabilities)
    }

    @Test
    fun `partial vae encoder folder is treated as invalid`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        File(root, "vae_encoder").mkdirs()

        val result = OnnxBundleValidator.validateDirectory(root)

        assertFalse(result.isValid)
        assertTrue(result.missingPaths.contains(OnnxBundleValidator.img2imgEncoderRelativePath))
    }

    @Test
    fun `installed txt2img readiness accepts a valid directory and rejects a missing component`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        val model = ModelEntity(
            filename = "test-sdai-bundle",
            path = root.absolutePath,
            sizeBytes = 1L,
            type = ModelType.ONNX_IMAGE_GEN,
            repoId = "local/test-sdai-bundle",
            onnxCapabilities = buildOnnxCapabilities(ONNX_CAPABILITY_TXT2IMG),
            onnxPipelineFamily = ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION,
        )

        assertTrue(model.isInstalledOnnxTxt2ImgBundle())

        File(root, "unet/model.ort").delete()
        assertFalse(model.isInstalledOnnxTxt2ImgBundle())
    }

    private fun createBundleRoot(): File =
        createTempDirectory("onnx-bundle-test").toFile().apply { deleteOnExit() }

    private fun createRequiredBundleFiles(root: File) {
        OnnxBundleValidator.requiredRelativePaths.forEach { relative ->
            val file = File(root, relative)
            file.parentFile?.mkdirs()
            file.writeText("stub")
        }
    }
}
