package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSetupBundleCatalogTest {
    private val bundle = QuickSetupBundleCatalog.bundle

    @Test fun `bundle has the expected families and total size`() {
        assertEquals(
            listOf(ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.LLM_DRAFT, ModelType.SD_CHECKPOINT, ModelType.WHISPER),
            bundle.files.map { it.type }
        )
        assertEquals(bundle.files.sumOf { it.sizeBytes }, bundle.totalBytes)
        assertEquals(
            3_110_000_000L + 987_000_000L + 97_800_000L + 1_747_190_784L + 190_085_487L,
            bundle.totalBytes
        )
        assertTrue(bundle in LlamaCuratedBundleCatalog.bundles)
        assertEquals(BundleCategory.STARTER, bundle.folderCategory())
    }

    @Test fun `gemma files match the canonical gemma bundle`() {
        val canonical = LlamaCuratedBundleCatalog.bundles.single { it.id == "gemma4-e2b-complete" }
        bundle.files.take(3).forEach { starter ->
            val match = canonical.files.single { it.localFilename == starter.localFilename }
            assertEquals(match.repoId, starter.repoId)
            assertEquals(match.remotePath, starter.remotePath)
            assertEquals(match.sha256, starter.sha256)
            assertEquals(match.sizeBytes, starter.sizeBytes)
            assertEquals(match.type, starter.type)
            assertEquals(match.installedFilename(canonical.defaultPrefix), starter.installedFilename(bundle.defaultPrefix))
        }
    }

    @Test fun `image model matches the canonical low-end sd bundle name`() {
        val sd = SdCuratedBundleCatalog.bundles.single { it.id == "low-end" }.files.single()
        val starter = QuickSetupBundleCatalog.imageModel
        assertEquals(sd.sha256, starter.sha256)
        assertEquals(sd.sizeBytes, starter.sizeBytes)
        assertEquals(sd.localFilename("Low-End"), starter.installedFilename(bundle.defaultPrefix))
        assertNotNull(starter.sdFamily)
        assertNotNull(starter.sdCapabilities)
    }

    @Test fun `whisper is multilingual and keeps the whisper manager filename`() {
        val whisper = QuickSetupBundleCatalog.whisper
        assertEquals("ggml-small-q5_1.bin", whisper.installedFilename(bundle.defaultPrefix))
        assertFalse(whisper.remotePath.contains(".en"))
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            whisper.downloadUrl
        )
    }

    @Test fun `server creation decision is idempotent`() {
        fun decide(req: Boolean, handled: Boolean, ready: Boolean, card: Boolean) =
            QuickSetupServerPlanner.decide(req, handled, ready, card)
        assertEquals(QuickSetupServerPlanner.Decision.SKIP_NOT_REQUESTED, decide(false, false, true, false))
        assertEquals(QuickSetupServerPlanner.Decision.WAIT_FOR_FILES, decide(true, false, false, false))
        assertEquals(QuickSetupServerPlanner.Decision.CREATE, decide(true, false, true, false))
        assertEquals(QuickSetupServerPlanner.Decision.SKIP_EXISTING_SERVER, decide(true, false, true, true))
        // After creation the handled marker blocks any re-download / restart duplicate.
        assertEquals(QuickSetupServerPlanner.Decision.SKIP_ALREADY_HANDLED, decide(true, true, true, false))
        assertEquals(QuickSetupServerPlanner.Decision.SKIP_ALREADY_HANDLED, decide(true, true, true, true))
    }

    @Test fun `starter profile enables mtp with draft and projector`() {
        val base = LlamaServerLaunchProfile(modelPath = "/m/main.gguf", threads = 3, parallel = 1)
        val profile = QuickSetupServerPlanner.starterProfile(base, "/m/mmproj.gguf", "/m/mtp.gguf")
        assertTrue(profile.speculativeEnabled)
        assertEquals(LlamaSpeculativeMode.DRAFT_MTP.flagValue, profile.speculativeMode)
        assertTrue(profile.mtpUseDraftModel)
        assertEquals("/m/mtp.gguf", profile.draftModelPath)
        assertEquals("/m/mmproj.gguf", profile.mmprojPath)
        assertTrue(profile.visionEnabled)
        assertEquals(3, profile.draftThreads)
        assertEquals("/m/mtp.gguf", profile.toLlamaConfig().draftModelPath)
        assertEquals(LlamaSpeculativeMode.DRAFT_MTP, profile.toLlamaConfig().speculativeMode)
    }
}
