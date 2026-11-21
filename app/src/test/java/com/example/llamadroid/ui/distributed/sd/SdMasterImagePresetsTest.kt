package com.example.llamadroid.ui.distributed.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.sd.SdCuratedPresets
import com.example.llamadroid.sd.VideoPresetCard
import com.example.llamadroid.ui.ai.videoScalarDefaultsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SdMasterImagePresetsTest {
    /** Files whose id is in [ids] count as installed at `/m/<id>`; no disk access. */
    private fun lookup(vararg ids: String): SdBundleFileLookup = { bundle, file, _ ->
        if (file.id in ids) {
            ModelEntity(
                filename = file.localFilename(bundle.installPrefix),
                path = "/m/${file.id}",
                sizeBytes = 1L,
                type = file.modelType,
                repoId = "test"
            )
        } else {
            null
        }
    }

    private val fluxAll = arrayOf("flux-schnell-q4", "flux-clip-l", "flux-t5-q4", "flux-vae")

    @Test
    fun installedBundleCardFillsMainAndEverySlot() {
        val lookup = lookup(*fluxAll)
        val card = sdMasterImagePresetCards(emptyList(), lookup).single { it.bundleId == "high-end" }
        assertTrue(card.installed)
        assertEquals(0, card.missingFiles)
        assertEquals("/m/flux-schnell-q4", card.mainPath)

        val next = applyImagePreset(SdDistributedMasterSettingsEntity(), card, emptyList(), lookup)
        assertEquals("/m/flux-schnell-q4", next.imageModelPath)
        assertEquals("/m/flux-vae", next.imageVaePath)
        assertEquals("/m/flux-clip-l", next.imageClipLPath)
        assertEquals("/m/flux-t5-q4", next.imageT5xxlPath)
        assertEquals("", next.imageLlmPath)
    }

    @Test
    fun missingFilesAreCountedAndPartialBundleIsNotApplied() {
        val lookup = lookup("flux-schnell-q4", "flux-clip-l")
        val cards = sdMasterImagePresetCards(emptyList(), lookup)
        val high = cards.single { it.bundleId == "high-end" }
        assertFalse(high.installed)
        assertEquals(2, high.missingFiles)
        assertNotNull(high.mainPath)
        val base = SdDistributedMasterSettingsEntity()
        assertSame(base, applyImagePreset(base, high, emptyList(), lookup))
        // A bundle with no installed file has no main path to select.
        assertNull(cards.single { it.bundleId == "distributed" }.mainPath)
    }

    @Test
    fun reapplyingTheSamePresetIsANoOp() {
        val lookup = lookup(*fluxAll)
        val card = sdMasterImagePresetCards(emptyList(), lookup).single { it.bundleId == "high-end" }
        val once = applyImagePreset(SdDistributedMasterSettingsEntity(), card, emptyList(), lookup)
        assertSame(once, applyImagePreset(once, card, emptyList(), lookup))
    }

    @Test
    fun videoAndAdapterOnlyBundlesAreNotImageCards() {
        val ids = sdMasterImagePresetCards(emptyList(), lookup()).mapNotNull { it.bundleId }
        assertTrue("high-end" in ids)
        assertFalse("local-video" in ids)
        assertFalse("lingbot-phone" in ids)
        assertFalse("ipa-starter" in ids)
    }

    @Test
    fun lingBotScalarDefaultsLandInTheVideoColumns() {
        val card = VideoPresetCard(SdCuratedPresets.LingBotPhone.BUNDLE_ID, null, "t", "/m/x", true, 0)
        val defaults = requireNotNull(videoScalarDefaultsFor(card))
        val next = SdDistributedMasterSettingsEntity().withVideoScalarDefaults(defaults)
        val preset = SdCuratedPresets.LingBotPhone
        assertEquals(preset.FRAMES.toString(), next.frames)
        assertEquals(preset.FPS.toString(), next.fps)
        assertEquals(preset.WIDTH.toString(), next.videoWidth)
        assertEquals(preset.HEIGHT.toString(), next.videoHeight)
        assertEquals(preset.STEPS.toString(), next.videoSteps)
        assertEquals(preset.THREADS.toString(), next.runtimeThreads)
        assertEquals(preset.MMAP, next.mmap)
    }
}
