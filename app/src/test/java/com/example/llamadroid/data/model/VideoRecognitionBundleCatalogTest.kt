package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.*
import org.junit.Test

class VideoRecognitionBundleCatalogTest {
    @Test fun videoBundlesAreDiscoverableWithPinnedMatchingComponents() {
        val bundles = VideoRecognitionBundleCatalog.bundles
        assertEquals(3, bundles.size)
        assertEquals(1, bundles.count { it.videoPolicy?.recommended == true })
        bundles.forEach { bundle ->
            assertTrue(LlamaCuratedBundleCatalog.bundles.contains(bundle))
            assertTrue(CuratedModelBundleRegistry.bundles.contains(bundle))
            assertEquals(BundleCategory.VIDEO, bundle.folderCategory())
            assertEquals(setOf(ModelType.LLM, ModelType.VISION_PROJECTOR), bundle.files.map { it.type }.toSet())
            assertEquals(1, bundle.files.map { it.revision }.distinct().size)
            assertEquals(1, bundle.files.map { it.repoId }.distinct().size)
            assertTrue(bundle.files.all { it.strictSize && it.revision.matches(Regex("[a-f0-9]{40}")) })
            assertFalse(bundle.videoPolicy!!.supportsVideoAudio)
        }
    }

    @Test fun imageBundlesNeverBecomeVideoBundlesImplicitly() {
        val vision = LlamaCuratedBundleCatalog.bundles.first { it.id == "gemma4-e2b-complete" }
        assertEquals(BundleCategory.VISION, vision.folderCategory())
        assertNull(vision.videoPolicy)
    }

    @Test fun diffusionFoldersRetainEveryBundleExactlyOnce() {
        val groups = SdCuratedBundleCatalog.bundles.groupBy { it.folderCategory() }
        assertEquals(SdCuratedBundleCatalog.bundles.size, groups.values.sumOf { it.size })
        assertTrue(groups.getValue(BundleCategory.VIDEO).any { it.id == "local-video" })
        assertTrue(groups.getValue(BundleCategory.VIDEO).any { it.id == "lingbot-phone" })
        assertTrue(groups.getValue(BundleCategory.UPSCALING).all { it.files.all { file -> file.modelType == ModelType.SD_UPSCALER } })
        assertTrue(groups.getValue(BundleCategory.EDITING).containsAll(SdWorkflowPresetCatalog.bundles))
    }
}
