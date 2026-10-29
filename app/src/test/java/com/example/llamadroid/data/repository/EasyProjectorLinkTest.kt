package com.example.llamadroid.data.repository

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyProjectorLinkTest {
    private fun projector(name: String, downloaded: Boolean = true) = ModelEntity(
        filename = name, path = "/models/$name", sizeBytes = 1L, type = ModelType.VISION_PROJECTOR,
        repoId = "r", isDownloaded = downloaded)

    @Test fun curatedBundleProjectorWithSamePrefix() {
        val installed = listOf(projector("Other-mmproj-x.gguf"), projector("Gemma4-E2B-mmproj-gemma-4-E2B-it-BF16.gguf"))
        assertEquals("Gemma4-E2B-mmproj-gemma-4-E2B-it-BF16.gguf",
            easyProjectorFilenameFor("Gemma4-E2B-gemma-4-E2B-it-Q4_K_M.gguf", installed))
    }

    @Test fun leadingTokenFallbackAndNoMatch() {
        assertEquals("custom-mmproj.gguf", easyProjectorFilenameFor("custom-7b.gguf", listOf(projector("custom-mmproj.gguf"))))
        assertNull(easyProjectorFilenameFor("custom-7b.gguf", listOf(projector("other-mmproj.gguf"))))
        assertNull(easyProjectorFilenameFor("custom-7b.gguf", listOf(projector("custom-mmproj.gguf", downloaded = false))))
    }
}
