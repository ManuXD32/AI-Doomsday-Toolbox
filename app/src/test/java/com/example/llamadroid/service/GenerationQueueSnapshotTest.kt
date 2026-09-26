package com.example.llamadroid.service

import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.data.db.GenerationQueueItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class GenerationQueueSnapshotTest {
    @Test
    fun `image request retains command fields across persistence`() {
        val source = SDConfig(
            mode = SDMode.IMG2IMG,
            modelPath = "/models/image.gguf",
            prompt = "a detailed mountain landscape",
            negativePrompt = "fog",
            outputPath = "/output/image.png",
            initImage = "/inputs/source.png",
            maskImage = "/inputs/mask.png",
            referenceImages = listOf("/inputs/reference.png"),
            steps = 37,
            customFlags = "--diffusion-fa",
            sdBinaryPathOverride = "/bin/sd-cli"
        )

        val restored = GenerationQueueSnapshot.decodeImage(
            GenerationQueueSnapshot.encode(GenerationQueueSnapshot.IMAGE, source))

        assertEquals(source, restored)
    }

    @Test
    fun `video request retains typed media inputs and output paths`() {
        val source = VideoGenerationConfig(
            mode = VideoGenerationMode.IMG2VID,
            prompt = "slow camera movement",
            diffusionModelPath = "/models/video.gguf",
            outputAviPath = "/output/video.avi",
            outputMp4Path = "/output/video.mp4",
            metadataPath = "/output/video.json",
            initImagePath = "/inputs/start.png",
            videoInputs = SdVideoInputs(
                initImagePath = "/inputs/start.png",
                endImagePath = "/inputs/end.png",
                referenceImages = listOf("/inputs/reference.png"),
                referenceAudios = listOf("/inputs/audio.wav")
            ),
            customFlags = "--threads 4",
            sdBinaryPathOverride = "/bin/sd-cli"
        )

        val restored = GenerationQueueSnapshot.decodeVideo(
            GenerationQueueSnapshot.encode(GenerationQueueSnapshot.VIDEO, source))

        assertEquals(source, restored)
    }

    @Test
    fun `request type mismatch is rejected`() {
        val json = GenerationQueueSnapshot.encode(GenerationQueueSnapshot.IMAGE,
            SDConfig(modelPath = "/models/image.gguf", prompt = "test", outputPath = "/output/image.png"))

        assertThrows(IllegalArgumentException::class.java) {
            GenerationQueueSnapshot.decodeVideo(json)
        }
    }

    @Test
    fun `retry keeps frozen settings and staged input but gives output a fresh identity`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val oldId = UUID.randomUUID().toString()
        val originalFolder = File(context.filesDir, "generation_queue_inputs/$oldId").apply { mkdirs() }
        val input = File(originalFolder, "source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val model = File(context.filesDir, "$oldId.gguf").apply { writeText("model") }
        val binary = File(context.filesDir, "$oldId.so").apply { writeText("binary") }
        try {
            val config = SDConfig(
                modelPath = model.absolutePath,
                prompt = "keep this prompt",
                outputPath = "/output/image_queue_${oldId}.png",
                initImage = input.absolutePath,
                mode = SDMode.IMG2IMG,
                steps = 37,
                customFlags = "--diffusion-fa",
                sdBinaryPathOverride = binary.absolutePath
            )
            val item = GenerationQueueItemEntity(oldId, GenerationQueueSnapshot.IMAGE,
                "IMG2IMG", "keep this prompt", GenerationQueueSnapshot.encode(GenerationQueueSnapshot.IMAGE, config),
                sortOrder = 1, status = "INTERRUPTED", createdAtMillis = 1)

            val prepared = GenerationQueueSnapshot.retry(context, item)
            try {
                val retried = GenerationQueueSnapshot.decodeImage(prepared.configJson)
                assertEquals(config.prompt, retried.prompt)
                assertEquals(config.steps, retried.steps)
                assertEquals(config.customFlags, retried.customFlags)
                assertEquals(binary.absolutePath, retried.sdBinaryPathOverride)
                assertNotEquals(config.outputPath, retried.outputPath)
                assertTrue(retried.outputPath.endsWith("image_queue_${prepared.id}.png"))
                assertArrayEquals(input.readBytes(), File(retried.initImage!!).readBytes())
            } finally {
                File(context.filesDir, "generation_queue_inputs/${prepared.id}").deleteRecursively()
            }
        } finally {
            originalFolder.deleteRecursively()
            model.delete()
            binary.delete()
        }
    }

    @Test
    fun `retry identifies a missing frozen binary`() {
        val context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        val model = File(context.filesDir, "$id.gguf").apply { writeText("model") }
        try {
            val config = SDConfig(modelPath = model.absolutePath, prompt = "test",
                outputPath = "/output/image.png", sdBinaryPathOverride = "/missing/$id.so")
            val item = GenerationQueueItemEntity(id, GenerationQueueSnapshot.IMAGE,
                "TXT2IMG", "test", GenerationQueueSnapshot.encode(GenerationQueueSnapshot.IMAGE, config),
                sortOrder = 1, status = "FAILED", createdAtMillis = 1)
            val error = assertThrows(GenerationQueueRetryException::class.java) {
                runBlocking { GenerationQueueSnapshot.retry(context, item) }
            }
            assertEquals(GenerationQueueRetryIssue.BINARY, error.issue)
        } finally {
            model.delete()
        }
    }

    @Test
    fun `retry identifies an input removed after the first attempt`() {
        val context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        val model = File(context.filesDir, "$id.gguf").apply { writeText("model") }
        val binary = File(context.filesDir, "$id.so").apply { writeText("binary") }
        try {
            val config = SDConfig(modelPath = model.absolutePath, prompt = "edit",
                outputPath = "/output/image.png", initImage = "/missing/$id.png",
                mode = SDMode.IMG2IMG, sdBinaryPathOverride = binary.absolutePath)
            val item = GenerationQueueItemEntity(id, GenerationQueueSnapshot.IMAGE,
                "IMG2IMG", "edit", GenerationQueueSnapshot.encode(GenerationQueueSnapshot.IMAGE, config),
                sortOrder = 1, status = "INTERRUPTED", createdAtMillis = 1)
            val error = assertThrows(GenerationQueueRetryException::class.java) {
                runBlocking { GenerationQueueSnapshot.retry(context, item) }
            }
            assertEquals(GenerationQueueRetryIssue.INPUT, error.issue)
        } finally {
            model.delete()
            binary.delete()
        }
    }
}
