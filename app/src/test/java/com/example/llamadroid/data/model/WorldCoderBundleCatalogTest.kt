package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldCoderBundleCatalogTest {
    private val ids = listOf("qwen35-4b-3d-coder", "qwen35-4b-glb-3d-coder")
    private val bundles get() = ids.map { id -> LlamaCuratedBundleCatalog.bundles.first { it.id == id } }

    @Test fun bothBundlesAreInTheCanonicalCatalogWithVisionAndExactSizes() {
        val sizes = bundles.map { b -> b.files.first { it.type == ModelType.LLM }.sizeBytes }
        assertEquals(listOf(2_708_804_096L, 2_708_804_128L), sizes)
        bundles.forEach { b ->
            val main = b.files.first { it.type == ModelType.LLM }
            assertTrue(main.isVision)
            assertTrue(main.downloadUrl.startsWith("https://huggingface.co/ccore/"))
            assertEquals(675_568_768L, b.files.first { it.type == ModelType.VISION_PROJECTOR }.sizeBytes)
        }
    }

    @Test fun projectorIsStoredOnceAcrossBothBundles() {
        val (a, b) = bundles.map { it.files.first { f -> f.type == ModelType.VISION_PROJECTOR } }
        assertEquals(a.sha256, b.sha256)
        assertEquals(a.installedFilename("Qwen3.5-4B-3D-Coder"), b.installedFilename("Qwen3.5-4B-GLB-3D-Coder"))
        assertEquals(a.artifactIdentity, b.artifactIdentity)
    }

    @Test fun bundlesAreInCreatorsFolderBeforeVision() {
        bundles.forEach { assertEquals(BundleCategory.CREATORS_3D, it.folderCategory()) }
        assertTrue(BundleCategory.CREATORS_3D.ordinal < BundleCategory.VISION.ordinal)
    }

    @Test fun projectorSharesOneFileIdRepoAndUrl() {
        val (a, b) = bundles.map { it.files.first { f -> f.type == ModelType.VISION_PROJECTOR } }
        assertEquals(a.id, b.id)
        assertEquals(a.downloadUrl, b.downloadUrl)
        assertTrue(a.downloadUrl.contains("ccore/Qwen3.5-4B-3D-Coder/"))
    }
}
