package com.example.llamadroid.ui.audio.music

import com.example.llamadroid.audio.music.StableAudio3CodecPrecision
import com.example.llamadroid.audio.music.StableAudio3ComponentManifest
import com.example.llamadroid.audio.music.StableAudio3DitPrecision
import com.example.llamadroid.audio.music.StableAudio3Kind
import com.example.llamadroid.audio.music.StableAudio3ManifestComponent
import com.example.llamadroid.audio.music.StableAudio3ManifestEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MusicWorkspaceDraftTest {
    @Test fun `music and sound effects start with separate duration defaults`() {
        assertEquals("30", MusicWorkspaceDraft("music")["durationSeconds"])
        assertEquals("5", MusicWorkspaceDraft("sfx")["durationSeconds"])
    }

    @Test fun `persistent draft retains invalid values custom components and stacked adapters`() {
        val draft = MusicWorkspaceDraft("music")
            .withValue("seed", "999999999999999999999999")
            .withValue("steps", "-")
            .withValue("prompt", "Piano\nwith rain")
            .withValue("operation", "inpaint")
            .copy(components = mapOf("dit" to "/custom/base.tflite"),
                loras = listOf(MusicLoraDraft("/custom/a.safetensors", "0.4"), MusicLoraDraft("/custom/b.safetensors", "1.2")))
        assertEquals(draft, MusicWorkspaceDraft.fromJson("music", draft.toJson()))
    }

    @Test fun `switching operation preserves input region and generation settings`() {
        val draft = MusicWorkspaceDraft("sfx").withValue("operation", "inpaint")
            .withValue("initAudio", "content://audio/clip")
            .withValue("maskStartSeconds", "2.5").withValue("cfg", "3")
        val switched = draft.withValue("operation", "generate").withValue("operation", "inpaint")
        assertEquals(draft, switched)
    }

    @Test fun `stale precision falls back to the first verified manifest entry`() {
        val manifest = manifest(
            entry(StableAudio3DitPrecision.FP32, StableAudio3CodecPrecision.W8A8, verified = false),
            entry(StableAudio3DitPrecision.W16A32, StableAudio3CodecPrecision.W8A8, verified = true)
        )
        val repaired = MusicWorkspaceDraft("music")
            .withValue("ditPrecision", "w8a32")
            .withValue("decoderPrecision", "fp32")
            .withValue("encoderPrecision", "fp32")
            .repairedFor(manifest)

        assertEquals("w16a32", repaired["ditPrecision"])
        assertEquals("w8a8", repaired["decoderPrecision"])
        assertEquals("w8a8", repaired["encoderPrecision"])
    }

    @Test fun `supported precision combination is preserved`() {
        val manifest = manifest(
            entry(StableAudio3DitPrecision.FP32, StableAudio3CodecPrecision.W8A8, verified = true),
            entry(StableAudio3DitPrecision.W16A32, StableAudio3CodecPrecision.FP32, verified = true)
        )
        val repaired = MusicWorkspaceDraft("music")
            .withValue("ditPrecision", "w16a32")
            .withValue("decoderPrecision", "fp32")
            .withValue("encoderPrecision", "fp32")
            .repairedFor(manifest)

        assertEquals("w16a32", repaired["ditPrecision"])
        assertEquals("fp32", repaired["decoderPrecision"])
        assertEquals("fp32", repaired["encoderPrecision"])
    }

    private fun manifest(vararg entries: StableAudio3ManifestEntry) = StableAudio3ComponentManifest(
        schemaVersion = 1,
        repository = "repo",
        revision = "revision",
        license = "license",
        entries = entries.toList()
    )

    private fun entry(
        dit: StableAudio3DitPrecision,
        codec: StableAudio3CodecPrecision,
        verified: Boolean
    ) = StableAudio3ManifestEntry(
        id = "entry-${dit.wireValue}-${codec.wireValue}",
        kind = StableAudio3Kind.MUSIC,
        ditPrecision = dit,
        decoderPrecision = codec,
        encoderPrecision = codec,
        decoder = "same-s",
        components = listOf(
            StableAudio3ManifestComponent(
                role = "dit",
                remotePath = "dit.tflite",
                localFileName = "dit.tflite",
                sha256 = if (verified) "a".repeat(64) else null,
                sizeBytes = if (verified) 1L else null,
                license = "license",
                sourceRevision = "revision"
            )
        )
    )
}
