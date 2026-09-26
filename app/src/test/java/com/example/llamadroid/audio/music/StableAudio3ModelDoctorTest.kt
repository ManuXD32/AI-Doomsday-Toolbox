package com.example.llamadroid.audio.music

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.StableAudioModelSupport
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableAudio3ModelDoctorTest {
    @Test
    fun canonicalNamesSeparateBundleDitFromSharedPayloads() {
        val digest = digest("payload")
        val dit = component(StableAudioModelSupport.ROLE_DIT, "dit.tflite", digest)
        val shared = component(StableAudioModelSupport.ROLE_TOKENIZER, "tokenizer.model", digest)

        assertEquals(
            "Stable-Audio-3-Small-SFX-dit.tflite",
            StableAudio3ModelDoctor.canonicalFilename(StableAudio3Kind.SFX, dit)
        )
        assertEquals(
            "shared-${digest.take(12)}-tokenizer.model",
            StableAudio3ModelDoctor.canonicalFilename(StableAudio3Kind.MUSIC, shared)
        )
    }

    @Test
    fun legacyRowDoesNotResolveAsCanonicalComponent() {
        val digest = digest("payload")
        val component = component(StableAudioModelSupport.ROLE_DIT, "dit.tflite", digest)
        val entry = entry(component)
        val file = File.createTempFile("stable-audio-legacy", ".tflite")
        file.writeText("payload")
        try {
            val model = model(
                filename = "legacy-dit.tflite",
                path = file.absolutePath,
                role = StableAudioModelSupport.ROLE_DIT,
                digest = digest,
                size = file.length()
            )
            assertFalse(StableAudio3ModelDoctor.isCanonicalInstalledModel(
                StableAudio3Kind.MUSIC, entry, component, model
            ))
        } finally {
            file.delete()
        }
    }

    @Test
    fun doctorReportsDigestMismatchAfterSizeAndReadabilityPass() {
        val actualDigest = digest("payload")
        val expectedDigest = "0".repeat(64)
        val component = component(StableAudioModelSupport.ROLE_DIT, "dit.tflite", expectedDigest)
        val entry = entry(component)
        val file = File.createTempFile(
            StableAudio3ModelDoctor.canonicalFilename(StableAudio3Kind.MUSIC, component)
                .substringBefore('.'), ".tflite"
        )
        file.writeText("payload")
        val canonical = File(file.parentFile, StableAudio3ModelDoctor.canonicalFilename(StableAudio3Kind.MUSIC, component))
        file.copyTo(canonical, overwrite = true)
        file.delete()
        try {
            val model = model(
                filename = canonical.name,
                path = canonical.absolutePath,
                role = StableAudioModelSupport.ROLE_DIT,
                digest = expectedDigest,
                size = canonical.length()
            )
            val report = StableAudio3ModelDoctor.inspectFile(
                StableAudio3Kind.MUSIC, entry, component, model, calculateDigest = true
            )
            assertEquals(StableAudio3ComponentHealth.DIGEST_MISMATCH, report.health)
            assertEquals(actualDigest, report.actualSha256)
            assertTrue(report.readable)
        } finally {
            canonical.delete()
        }
    }

    private fun entry(component: StableAudio3ManifestComponent) = StableAudio3ManifestEntry(
        id = "test",
        kind = StableAudio3Kind.MUSIC,
        ditPrecision = StableAudio3DitPrecision.FP32,
        decoderPrecision = StableAudio3CodecPrecision.W8A8,
        encoderPrecision = StableAudio3CodecPrecision.W8A8,
        decoder = "same-s",
        components = listOf(component)
    )

    private fun component(role: String, filename: String, digest: String) =
        StableAudio3ManifestComponent(
            role = role,
            remotePath = filename,
            localFileName = filename,
            sha256 = digest,
            sizeBytes = 7L,
            license = "test",
            sourceRevision = "abcdef1"
        )

    private fun model(
        filename: String,
        path: String,
        role: String,
        digest: String,
        size: Long
    ) = ModelEntity(
        filename = filename,
        path = path,
        sizeBytes = size,
        type = ModelType.LITERT_AUDIO_DIT,
        repoId = StableAudio3Ids.SOURCE_REPOSITORY,
        isDownloaded = true,
        audioFamily = StableAudioModelSupport.FAMILY_MUSIC,
        audioComponentRole = role,
        audioArtifactIdentity = "sha256:$digest"
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
