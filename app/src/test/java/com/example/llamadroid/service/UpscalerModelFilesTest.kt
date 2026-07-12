package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpscalerModelFilesTest {

    @Test
    fun validate_realsrRecognizesExistingScaleFiles() {
        val root = Files.createTempDirectory("upscaler-test").toFile()
        val model = UpscalerModels.getByName("models-ESRGAN-Nomos8kSC")!!
        try {
            val modelDir = File(root, model.name).apply { mkdirs() }
            File(modelDir, "x4.param").writeText("param")
            File(modelDir, "x4.bin").writeText("bin")

            val result = UpscalerModelFiles.validate(root, model, scale = 4)
            assertTrue(result is UpscalerModelValidationResult.Success)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun availableScales_onlyReturnsPresentRealsrVariants() {
        val root = Files.createTempDirectory("upscaler-test").toFile()
        val model = UpscalerModels.getByName("models-Real-ESRGAN-animevideov3")!!
        try {
            val modelDir = File(root, model.name).apply { mkdirs() }
            File(modelDir, "x2.param").writeText("param")
            File(modelDir, "x2.bin").writeText("bin")
            File(modelDir, "x4.param").writeText("param")
            File(modelDir, "x4.bin").writeText("bin")

            assertEquals(listOf(2, 4), UpscalerModelFiles.availableScales(root, model))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolve_realcuganUsesDenoiseAwareFilenames() {
        val root = Files.createTempDirectory("upscaler-test").toFile()
        val model = UpscalerModels.getByName("models-se")!!
        try {
            val resolved = UpscalerModelFiles.resolve(root, model, scale = 4, denoise = 3)
            assertEquals("up4x-denoise3x.param", resolved.paramFile.name)
            assertEquals("up4x-denoise3x.bin", resolved.binFile.name)
        } finally {
            root.deleteRecursively()
        }
    }
}
