package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCuratedBundleCatalogTest {
    private val bundles = EmbeddingCuratedBundleCatalog.bundles

    @Test fun threePinnedEmbeddingBundlesAreRegisteredAndClassified() {
        assertEquals(
            listOf("embeddinggemma-300m", "qwen3-embedding-0-6b", "nomic-embed-text-v1-5"),
            bundles.map { it.id }
        )
        assertEquals(EmbeddingCuratedBundleCatalog.RECOMMENDED_ID, bundles.first().id)
        bundles.forEach { bundle ->
            assertTrue(CuratedModelBundleRegistry.bundles.contains(bundle))
            assertEquals(BundleCategory.EMBEDDINGS, bundle.folderCategory())
            val file = bundle.files.single()
            assertEquals(ModelType.EMBEDDING, file.type)
            assertTrue(file.strictSize)
            assertTrue(file.revision.matches(Regex("[a-f0-9]{40}")))
            assertTrue(file.downloadUrl.startsWith("https://huggingface.co/${file.repoId}/resolve/${file.revision}/"))
        }
    }

    @Test fun pinnedSizesMatchHuggingFace() {
        val sizes = bundles.associate { it.id to it.files.single().sizeBytes }
        assertEquals(333_590_944L, sizes["embeddinggemma-300m"])
        assertEquals(639_150_592L, sizes["qwen3-embedding-0-6b"])
        assertEquals(84_106_624L, sizes["nomic-embed-text-v1-5"])
    }
}
