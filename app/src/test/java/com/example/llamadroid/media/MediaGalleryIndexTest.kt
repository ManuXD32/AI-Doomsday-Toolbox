package com.example.llamadroid.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaGalleryIndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `scan includes allowlisted saved outputs and excludes unrelated files`() {
        val filesDir = temporaryFolder.newFolder("files")
        val sd = File(filesDir, "sd_output/txt2img").apply { mkdirs() }
        val onnx = File(filesDir, "onnx_image_output/txt2img").apply { mkdirs() }
        val tama = File(filesDir, "tama_gallery/pet").apply { mkdirs() }
        val workflow = File(filesDir, "workflow_media_translation/run").apply { mkdirs() }
        val processing = File(filesDir, "video_upscale_output").apply { mkdirs() }
        writeArtifact(sd, "sd.png")
        writeArtifact(onnx, "onnx.png")
        writeArtifact(tama, "art.png")
        writeArtifact(workflow, "dubbed.mp4")
        writeArtifact(processing, "upscaled.mp4")

        writeContent(File(filesDir, "DCIM/photo.jpg"), "device.jpg")
        writeContent(File(filesDir, "workflow_media_inputs/source.jpg"), "input.jpg")
        writeContent(File(filesDir, "cache/preview.png"), "cache.png")
        writeContent(File(filesDir, "model-assets/sprite.png"), "sprite.png")

        val items = MediaGalleryIndex.scan(filesDir)
        val paths = items.map { it.file.relativeTo(filesDir).path }.toSet()

        assertEquals(5, items.size)
        assertTrue(paths.contains("sd_output/txt2img/sd.png"))
        assertTrue(paths.contains("onnx_image_output/txt2img/onnx.png"))
        assertTrue(paths.contains("tama_gallery/pet/art.png"))
        assertTrue(paths.contains("workflow_media_translation/run/dubbed.mp4"))
        assertTrue(paths.contains("video_upscale_output/upscaled.mp4"))
        assertFalse(paths.any { it.startsWith("DCIM/") })
        assertFalse(paths.any { it.startsWith("workflow_media_inputs/") })
        assertFalse(paths.any { it.startsWith("cache/") })
        assertFalse(paths.any { it.startsWith("model-assets/") })
    }

    @Test
    fun `distributed SD metadata is the only proof accepted by distributed filter`() {
        val filesDir = temporaryFolder.newFolder("files")
        val imageDir = File(filesDir, "sd_output/txt2img").apply { mkdirs() }
        val distributed = writeArtifact(imageDir, "distributed.png")
        val local = writeArtifact(imageDir, "local.png")
        val legacy = writeArtifact(imageDir, "legacy.png")
        writeContent(
            sdGeneratedImageMetadataFile(distributed),
            """{"distributedEnabled":true,"createdAt":20,"prompt":"distributed"}"""
        )
        writeContent(
            sdGeneratedImageMetadataFile(local),
            """{"distributedEnabled":false,"createdAt":10,"prompt":"local"}"""
        )

        val items = MediaGalleryIndex.scan(filesDir)
        val distributedItems = MediaGalleryIndex.filter(
            items,
            source = MediaGallerySourceFilter.SD,
            location = MediaGalleryLocationFilter.DISTRIBUTED
        )

        assertEquals(1, distributedItems.size)
        assertEquals("distributed.png", distributedItems.single().file.name)
        assertEquals(MediaGalleryLocation.DISTRIBUTED, distributedItems.single().location)
        assertEquals(MediaGalleryLocation.LOCAL, items.single { it.file.name == "local.png" }.location)
        assertEquals(MediaGalleryLocation.UNKNOWN, items.single { it.file.name == legacy.name }.location)
    }

    @Test
    fun `video metadata collapses native and mp4 artifacts into one stable item`() {
        val filesDir = temporaryFolder.newFolder("files")
        val videoDir = File(filesDir, "video_gen_output/txt2vid").apply { mkdirs() }
        val avi = writeArtifact(videoDir, "generation.avi")
        val mp4 = writeArtifact(videoDir, "generation.mp4")
        val metadata = File(videoDir, "generation.json")
        writeContent(
            metadata,
            """{"mode":"txt2vid","prompt":"one generation","createdAt":30,"aviPath":"${avi.absolutePath}","mp4Path":"${mp4.absolutePath}","metadataPath":"${metadata.absolutePath}"}"""
        )

        val items = MediaGalleryIndex.scan(filesDir)
        val videos = items.filter { it.type == MediaGalleryType.VIDEO }

        assertEquals(1, videos.size)
        assertEquals(mp4.canonicalFile, videos.single().file.canonicalFile)
        assertTrue(videos.single().identity.value.startsWith("video-meta:"))
        assertEquals("one generation", videos.single().prompt)
    }

    @Test
    fun `relative video sidecar paths are normalized for shared detail actions`() {
        val filesDir = temporaryFolder.newFolder("files")
        val videoDir = File(filesDir, "video_gen_output/txt2vid").apply { mkdirs() }
        val avi = writeArtifact(videoDir, "relative.avi")
        val mp4 = writeArtifact(videoDir, "relative.mp4")
        val metadata = File(videoDir, "relative.json")
        writeContent(
            metadata,
            """{"mode":"txt2vid","prompt":"relative","createdAt":30,"aviPath":"${avi.name}","mp4Path":"${mp4.name}","metadataPath":"${metadata.name}"}"""
        )

        val item = MediaGalleryIndex.scan(filesDir).single()

        assertEquals(mp4.canonicalFile, item.file.canonicalFile)
        assertEquals(mp4.canonicalPath, item.videoMetadata?.mp4Path)
        assertEquals(avi.canonicalPath, item.videoMetadata?.aviPath)
        assertEquals(metadata.canonicalPath, item.videoMetadata?.metadataPath)
    }

    @Test
    fun `filters always return newest first and refresh sees newly written output`() {
        val filesDir = temporaryFolder.newFolder("files")
        val onnxDir = File(filesDir, "onnx_image_output/txt2img").apply { mkdirs() }
        val old = writeArtifact(onnxDir, "old.png")
        old.setLastModified(10L)
        val first = MediaGalleryIndex.scan(filesDir)
        assertEquals(listOf("old.png"), first.map { it.file.name })

        val newest = writeArtifact(onnxDir, "new.png")
        newest.setLastModified(20L)
        val refreshed = MediaGalleryIndex.scan(filesDir)
        val filtered = refreshed.filterMediaGallery(
            type = MediaGalleryTypeFilter.IMAGES,
            source = MediaGallerySourceFilter.ONNX
        )

        assertEquals(listOf("new.png", "old.png"), filtered.map { it.file.name })
    }

    @Test
    fun `loose animated webp in video output is typed as video`() {
        val filesDir = temporaryFolder.newFolder("files")
        val videoDir = File(filesDir, "video_gen_output/txt2vid").apply { mkdirs() }
        val animated = writeArtifact(videoDir, "animated.webp")

        val item = MediaGalleryIndex.scan(filesDir).single()

        assertEquals(animated.canonicalFile, item.file.canonicalFile)
        assertEquals(MediaGalleryType.VIDEO, item.type)
        assertEquals("image/webp", item.mimeType)
        assertEquals(MediaGalleryLocation.UNKNOWN, item.location)
    }

    @Test
    fun `remote FastSD entries retain typed identity and source`() {
        val filesDir = temporaryFolder.newFolder("files")
        val remote = MediaGalleryRemoteItem(
            identity = MediaGalleryIdentity("fastsd:remote-1"),
            name = "remote.png",
            path = "/root/fastsdcpu/results/remote.png",
            createdAt = 42L,
            location = MediaGalleryLocation.UNKNOWN
        )

        val items = MediaGalleryIndex.scan(filesDir, listOf(remote))

        assertEquals(1, items.size)
        assertEquals(MediaGalleryIdentity("fastsd:remote-1"), items.single().identity)
        assertEquals(MediaGallerySource.FAST_SD, items.single().source)
        assertEquals(MediaGalleryLocation.UNKNOWN, items.single().location)
        assertEquals("remote.png", items.single().title)
        assertFalse(items.single().file.path == remote.path)
    }

    @Test
    fun `FastSD listing parser keeps spaced names and endpoint in stable identity`() {
        val output = listOf(
            "1700000000.125 $FAST_SD_RESULTS_PATH/older result.png",
            "1700000010.500 $FAST_SD_RESULTS_PATH/new result.WEBP",
            "1700000005.000 $FAST_SD_RESULTS_PATH/ignore.txt",
            "1700000001.000 /tmp/outside.png"
        ).joinToString(separator = "\u0000") + "\u0000"

        val items = MediaGalleryIndex.parseFastSdListing(output, endpoint = "host.example:8025")

        assertEquals(listOf("new result.WEBP", "older result.png"), items.map { it.name })
        assertEquals(MediaGalleryIdentity("fastsd:host.example:8025:$FAST_SD_RESULTS_PATH/new result.WEBP"), items.first().identity)
        assertEquals("image/webp", items.first().mimeType)
        assertEquals(1_700_000_010_500L, items.first().createdAt)
    }

    @Test
    fun `FastSD listing command is fixed and read only`() {
        assertTrue(FAST_SD_LIST_COMMAND.contains(FAST_SD_RESULTS_PATH))
        assertTrue(FAST_SD_LIST_COMMAND.contains("-maxdepth 1"))
        assertFalse(FAST_SD_LIST_COMMAND.contains("cat "))
        assertFalse(FAST_SD_LIST_COMMAND.contains("rm "))
        assertFalse(FAST_SD_LIST_COMMAND.contains("base64"))
    }

    private fun writeArtifact(directory: File, name: String): File =
        File(directory, name).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    private fun writeContent(file: File, content: String): File =
        file.apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun sdGeneratedImageMetadataFile(image: File): File =
        File(image.parentFile, "${image.nameWithoutExtension}.json")
}
