package com.example.llamadroid.ui.audio.music

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.StableAudioModelSupport
import com.example.llamadroid.data.model.library.ModelClassificationSource
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicWorkspaceControllerTest {
    @Test fun `manual Stable Audio DiT is eligible only for its selected family`() {
        val payload = File.createTempFile("stable-audio-manual", ".tflite")
        try {
            payload.writeBytes(byteArrayOf(1, 2, 3))
            val model = ModelEntity(
                filename = "custom-dit.tflite",
                path = payload.absolutePath,
                sizeBytes = payload.length(),
                type = ModelType.LITERT_AUDIO_DIT,
                repoId = "local-import",
                isDownloaded = true,
                audioFamily = StableAudioModelSupport.FAMILY_MUSIC,
                audioComponentRole = "diffusion",
                audioArtifactIdentity = "sha256:${"a".repeat(64)}",
                classificationSource = ModelClassificationSource.USER_OVERRIDE.storedValue
            )

            assertTrue(isManualStableAudioModel(model, com.example.llamadroid.audio.music.StableAudio3Kind.MUSIC, "dit"))
            assertFalse(isManualStableAudioModel(model, com.example.llamadroid.audio.music.StableAudio3Kind.SFX, "dit"))
        } finally {
            payload.delete()
        }
    }

    @Test fun `manual Stable Audio row without a persisted digest is rejected`() {
        val payload = File.createTempFile("stable-audio-manual", ".tflite")
        try {
            payload.writeBytes(byteArrayOf(1, 2, 3))
            val model = ModelEntity(
                filename = "custom-dit.tflite",
                path = payload.absolutePath,
                sizeBytes = payload.length(),
                type = ModelType.LITERT_AUDIO_DIT,
                repoId = "local-import",
                isDownloaded = true,
                audioFamily = StableAudioModelSupport.FAMILY_MUSIC,
                audioComponentRole = StableAudioModelSupport.ROLE_DIT,
                classificationSource = ModelClassificationSource.USER_OVERRIDE.storedValue
            )

            assertFalse(isManualStableAudioModel(model, com.example.llamadroid.audio.music.StableAudio3Kind.MUSIC, "dit"))
        } finally {
            payload.delete()
        }
    }
}
