package com.example.llamadroid.service

import com.example.llamadroid.sd.SdVideoAudioCodec
import com.example.llamadroid.sd.SdVideoOutputFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VideoOutputConversionTest {
    @Test fun defaultMp4MapsSidecarAudioAndExplicitMuxerForTemporaryFile() {
        val args = buildVideoConversionArgs("ffmpeg", File("native.webp"), File("result.tmp"),
            SdVideoOutputFormat.MP4, SdVideoAudioCodec.AAC, File("native.wav"))
        assertEquals(listOf("0:v:0", "1:a:0"), args.valuesAfter("-map"))
        assertEquals(listOf("aac"), args.valuesAfter("-c:a"))
        assertEquals(listOf("mp4"), args.valuesAfter("-f"))
        assertEquals(2, args.count { it == "-i" })
        assertFalse("-an" in args)
    }

    @Test fun automaticWebmPreservesOptionalEmbeddedAudioAndExplicitNoneDropsIt() {
        val auto = buildVideoConversionArgs("ffmpeg", File("native.avi"), File("result.webm"),
            SdVideoOutputFormat.WEBM, null)
        assertEquals(listOf("0:v:0", "0:a:0?"), auto.valuesAfter("-map"))
        assertEquals(listOf("libopus"), auto.valuesAfter("-c:a"))
        val none = buildVideoConversionArgs("ffmpeg", File("native.avi"), File("result.mp4"),
            SdVideoOutputFormat.MP4, SdVideoAudioCodec.NONE)
        assertTrue("-an" in none)
        assertEquals(listOf("0:v:0"), none.valuesAfter("-map"))
    }

    @Test fun oldMetadataLoadsAndNewSidecarFieldsRoundTrip() {
        val old = GeneratedVideoMetadata.fromJson(JSONObject("{\"mode\":\"txt2vid\"}"))
        assertNull(old.audioSidecarPath)
        assertNull(old.exportedAudioUri)
        val updated = old.copy(audioSidecarPath = "/fixture/output.wav", exportedAudioUri = "content://fixture/audio")
        val restored = GeneratedVideoMetadata.fromJson(updated.toJson())
        assertEquals(updated.audioSidecarPath, restored.audioSidecarPath)
        assertEquals(updated.exportedAudioUri, restored.exportedAudioUri)
    }

    private fun List<String>.valuesAfter(flag: String) = mapIndexedNotNull { index, value ->
        getOrNull(index + 1).takeIf { value == flag }
    }
}
