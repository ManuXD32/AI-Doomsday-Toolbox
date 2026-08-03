package com.example.llamadroid.service

import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VideoInterpolationSupportTest {

    @Test
    fun outputFrameMath_preservesEndpoints() {
        assertEquals(241, VideoInterpolationMath.outputFrameCount(inputFrames = 121, multiplier = 2))
        assertEquals(481, VideoInterpolationMath.outputFrameCount(inputFrames = 121, multiplier = 4))
        assertEquals(16.0, VideoInterpolationMath.outputFps(sourceFps = 4.0, multiplier = 4), 0.0001)
    }

    @Test
    fun mediaRegistry_exposesSmallRifeSet() {
        val ids = MediaModelRegistry.rifeModels.map { it.id }

        assertEquals(listOf("rife-v4.6", "rife-v4", "rife-anime"), ids)
        assertEquals("rife-v4.6", MediaModelRegistry.defaultRifeModel.id)
        assertTrue(MediaModelRegistry.rifeModels.all { it.installDirectory.startsWith("media_models/rife/") })
        assertTrue(MediaModelRegistry.rifeModels.all { asset ->
            asset.files.all { file ->
                file.url.startsWith("https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/20221029/models/${asset.id}/")
            }
        })
    }

    @Test
    fun mediaModelValidation_rejectsMissingAndPartialFiles() {
        val root = Files.createTempDirectory("rife-model-test").toFile()
        val asset = MediaModelRegistry.defaultRifeModel
        try {
            val missing = MediaModelManager.validateDirectory(root, asset)
            assertTrue(missing is MediaModelValidationResult.Missing)

            File(root, "flownet.param.part").writeText("partial")
            val partial = MediaModelManager.validateDirectory(root, asset)
            assertTrue(partial is MediaModelValidationResult.Incomplete)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mediaModelValidation_acceptsInstalledFiles() {
        val root = Files.createTempDirectory("rife-model-test").toFile()
        val asset = MediaModelRegistry.defaultRifeModel
        try {
            asset.files.forEach { file ->
                File(root, file.relativePath).apply {
                    parentFile?.mkdirs()
                    writeText("ok")
                }
            }

            val installed = MediaModelManager.validateDirectory(root, asset)
            assertTrue(installed is MediaModelValidationResult.Installed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aiHubCatalog_containsVideoInterpolationRoute() {
        val tool = ToolCatalog.tools.single { it.id == "video_interpolation" }

        assertEquals(Screen.VideoInterpolation.route, tool.route)
        assertTrue(tool.keywords.contains("rife"))
        assertTrue(ToolCatalog.matchesRoute(Screen.VideoInterpolation.route))
    }
}
