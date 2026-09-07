package com.example.llamadroid.service

import com.example.llamadroid.sd.SdVideoAudioCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The explicit automatic audio policy must survive metadata persistence. */
class VideoMetadataAudioPolicyTest {
    @Test
    fun `metadata round trip preserves explicit null audio codec`() {
        val metadata = GeneratedVideoMetadata(
            mode = VideoGenerationMode.TXT2VID.folderName,
            prompt = "preview",
            diffusionModelPath = "/models/video.gguf",
            diffusionModelName = "video.gguf",
            vaeEnabled = false,
            vaePath = null,
            vaeName = null,
            t5xxlEnabled = false,
            t5xxlPath = null,
            t5xxlName = null,
            initImagePath = null,
            videoFrames = 8,
            fps = 5,
            width = 320,
            height = 192,
            steps = 4,
            cfgScale = 4f,
            flowShift = null,
            samplingMethod = SamplingMethod.EULER,
            scheduler = null,
            cacheMode = null,
            cacheOption = "",
            scmMask = "",
            scmPolicy = null,
            threads = -1,
            vaeTiling = true,
            vaeTileSize = null,
            diffusionFa = false,
            mmap = true,
            distributedRuntime = SdDistributedRuntimeConfig(),
            createdAt = 1L,
            aviPath = "/tmp/video.avi",
            mp4Path = "/tmp/video.mp4",
            metadataPath = "/tmp/video.json",
            audioCodec = null
        )

        val restored = GeneratedVideoMetadata.fromJson(metadata.toJson())

        assertNull(restored.audioCodec)
    }

    @Test
    fun `metadata without audio codec keeps legacy AAC default`() {
        val metadata = GeneratedVideoMetadata(
            mode = VideoGenerationMode.TXT2VID.folderName,
            prompt = "preview",
            diffusionModelPath = "/models/video.gguf",
            diffusionModelName = "video.gguf",
            vaeEnabled = false,
            vaePath = null,
            vaeName = null,
            t5xxlEnabled = false,
            t5xxlPath = null,
            t5xxlName = null,
            initImagePath = null,
            videoFrames = 8,
            fps = 5,
            width = 320,
            height = 192,
            steps = 4,
            cfgScale = 4f,
            flowShift = null,
            samplingMethod = SamplingMethod.EULER,
            scheduler = null,
            cacheMode = null,
            cacheOption = "",
            scmMask = "",
            scmPolicy = null,
            threads = -1,
            vaeTiling = true,
            vaeTileSize = null,
            diffusionFa = false,
            mmap = true,
            distributedRuntime = SdDistributedRuntimeConfig(),
            createdAt = 1L,
            aviPath = "/tmp/video.avi",
            mp4Path = "/tmp/video.mp4",
            metadataPath = "/tmp/video.json",
            audioCodec = null
        )
        val legacyJson = metadata.toJson().apply { remove("audioCodec") }

        val restored = GeneratedVideoMetadata.fromJson(JSONObject(legacyJson.toString()))

        assertEquals(SdVideoAudioCodec.AAC.name, restored.audioCodec)
    }
}
