package com.example.llamadroid.service

import com.example.llamadroid.sd.SdLoraSpec
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLoraStagingTest {

    @Test
    fun `native absolute plan keeps same basenames distinct and preserves order`() {
        val loras = listOf(
            SdLoraSpec("/one/style.safetensors"),
            SdLoraSpec("/two/style.safetensors", highNoiseOnly = true)
        )

        val plan = VideoLoraStagingPlan.nativeAbsolute(loras)

        assertTrue(plan.promptPath(0, loras[0]).endsWith("/one/style.safetensors"))
        assertTrue(plan.promptPath(1, loras[1]).endsWith("/two/style.safetensors"))
        assertNotEquals(plan.promptPath(0, loras[0]), plan.promptPath(1, loras[1]))
        assertTrue(plan.loras.map { it.path } == loras.map { it.path })
        plan.close()
    }

    @Test
    fun `fallback links have collision safe names and cleanup leaves sources`() {
        val root = Files.createTempDirectory("video-lora-test").toFile()
        try {
            val first = File(root, "first/style.safetensors").apply {
                parentFile?.mkdirs()
                writeText("first")
            }
            val second = File(root, "second/style.safetensors").apply {
                parentFile?.mkdirs()
                writeText("second")
            }
            val plan = VideoLoraStagingPlan.linkedFallback(
                loras = listOf(SdLoraSpec(first.absolutePath), SdLoraSpec(second.absolutePath)),
                rootDirectory = File(root, "runs")
            )
            val runDirectory = File(plan.loraModelDirectory)
            val staged = runDirectory.listFiles()?.toList().orEmpty()

            assertTrue(staged.size == 2)
            assertNotEquals(staged[0].name, staged[1].name)
            assertTrue(staged.all { it.exists() })
            plan.close()
            assertFalse(runDirectory.exists())
            assertTrue(first.exists())
            assertTrue(second.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
