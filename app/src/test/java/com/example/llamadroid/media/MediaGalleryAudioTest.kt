package com.example.llamadroid.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaGalleryAudioTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun write(file: File, content: String = "x"): File {
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `scan indexes onnx tts and audio workspace outputs as audio`() {
        val filesDir = temporaryFolder.newFolder("files")
        write(File(filesDir, "onnx_tts_output/tts_1.wav"), "wavdata").setLastModified(1_000L)
        write(File(filesDir, "onnx_tts_output/tts_1.mp3"), "mp3data").setLastModified(1_000L)
        write(File(filesDir, "audio_workspace/outputs/job1/audio.wav"))
        write(File(filesDir, "audio_workspace/outputs/job1/metadata.json"), """{"createdAt":5000,"family":"music"}""")
        write(File(filesDir, "audio_workspace/outputs/job2/other.wav"))
        write(File(filesDir, "audio_workspace/voices/voice.wav"))

        val items = MediaGalleryIndex.scan(filesDir)

        assertEquals(2, items.size)
        assertTrue(items.all { it.type == MediaGalleryType.AUDIO })
        val onnx = items.single { it.source == MediaGallerySource.ONNX_TTS }
        assertEquals("tts_1.mp3", onnx.file.name)
        assertEquals("audio/mpeg", onnx.mimeType)
        assertEquals(7L, onnx.sizeBytes)
        val studio = items.single { it.source == MediaGallerySource.AUDIO_WORKSPACE }
        assertEquals(5000L, studio.createdAt)
        assertEquals("music", studio.mode)
        assertEquals("job1", studio.file.parentFile?.name)
        // Newest first.
        assertEquals(MediaGallerySource.AUDIO_WORKSPACE, items.first().source)
    }

    @Test
    fun `filter separates audio from images and by audio source`() {
        val filesDir = temporaryFolder.newFolder("files")
        write(File(filesDir, "onnx_tts_output/a.wav"))
        write(File(filesDir, "audio_workspace/outputs/j/audio.wav"))
        write(File(filesDir, "sd_output/txt2img/p.png"))
        val items = MediaGalleryIndex.scan(filesDir)

        assertEquals(3, items.size)
        assertEquals(2, MediaGalleryIndex.filter(items, type = MediaGalleryTypeFilter.AUDIO).size)
        assertEquals(1, MediaGalleryIndex.filter(items, type = MediaGalleryTypeFilter.IMAGES).size)
        assertEquals(
            listOf(MediaGallerySource.ONNX_TTS),
            MediaGalleryIndex.filter(items, source = MediaGallerySourceFilter.ONNX_TTS).map { it.source }
        )
        assertEquals(
            1,
            MediaGalleryIndex.filter(items, type = MediaGalleryTypeFilter.AUDIO, source = MediaGallerySourceFilter.AUDIO_WORKSPACE).size
        )
        assertTrue(MediaGalleryIndex.filter(items, type = MediaGalleryTypeFilter.VIDEOS).isEmpty())
    }

    @Test
    fun `route arguments map to filters`() {
        assertEquals(MediaGalleryRouteFilters(), parseMediaGalleryRouteFilters(null, null))
        assertEquals(MediaGalleryTypeFilter.AUDIO, parseMediaGalleryRouteFilters("audio", null).type)
        assertEquals(MediaGalleryTypeFilter.IMAGES, parseMediaGalleryRouteFilters("IMAGE", null).type)
        assertEquals(MediaGalleryTypeFilter.VIDEOS, parseMediaGalleryRouteFilters("video", null).type)
        assertEquals(MediaGallerySourceFilter.FAST_SD, parseMediaGalleryRouteFilters(null, "fast_sd").source)
        assertEquals(MediaGallerySourceFilter.ONNX_TTS, parseMediaGalleryRouteFilters("audio", "onnx_tts").source)
        val distributed = parseMediaGalleryRouteFilters(null, "distributed")
        assertEquals(MediaGalleryLocationFilter.DISTRIBUTED, distributed.location)
        assertEquals(MediaGallerySourceFilter.ALL, distributed.source)
        assertEquals(MediaGallerySourceFilter.ALL, parseMediaGalleryRouteFilters("bogus", "nope").source)
        assertEquals(MediaGalleryTypeFilter.ALL, parseMediaGalleryRouteFilters("bogus", "nope").type)
    }
}
