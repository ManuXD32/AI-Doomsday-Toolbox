package com.example.llamadroid.ui.knowledge

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.CuratedBundleFile
import com.example.llamadroid.data.model.CuratedModelBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingModelChooserSupportTest {
    private fun model(name: String, type: ModelType = ModelType.EMBEDDING, downloaded: Boolean = true) =
        ModelEntity(filename = name, path = "/models/$name", sizeBytes = 10L, type = type, repoId = "a/b", isDownloaded = downloaded)

    private fun bundle(id: String, file: String) = CuratedModelBundle(
        id = id,
        titleRes = 0,
        descriptionRes = 0,
        files = listOf(
            CuratedBundleFile(
                id = "$id-f", repoId = "a/b", revision = "main", remotePath = file, localFilename = file,
                type = ModelType.EMBEDDING, sizeBytes = 10L, sha256 = "0".repeat(64), license = "MIT"
            )
        )
    )

    @Test
    fun installedBundleAppearsOnlyAsInstalledRow() {
        val bundles = listOf(bundle("one", "one.gguf"), bundle("two", "two.gguf"))
        val rows = buildEmbeddingChooserRows(listOf(model("one.gguf")), bundles, selectedPath = null, recommendedId = "two")
        assertEquals(2, rows.size)
        assertTrue(rows[0] is EmbeddingChooserRow.Installed)
        assertEquals("two", (rows[1] as EmbeddingChooserRow.Catalog).bundle.id)
        assertTrue((rows[1] as EmbeddingChooserRow.Catalog).recommended)
    }

    @Test
    fun selectedFirstAndNonEmbeddingOrPartialRowsIgnored() {
        val rows = buildEmbeddingChooserRows(
            listOf(model("a.gguf"), model("b.gguf"), model("llm.gguf", ModelType.LLM), model("c.gguf", downloaded = false)),
            emptyList(),
            selectedPath = "/models/b.gguf"
        )
        assertEquals(listOf("b.gguf", "a.gguf"), rows.map { (it as EmbeddingChooserRow.Installed).model.filename })
        assertTrue((rows[0] as EmbeddingChooserRow.Installed).selected)
    }

    @Test
    fun prefixedInstallCountsAsInstalled() {
        val rows = buildEmbeddingChooserRows(listOf(model("pre-one.gguf")), listOf(bundle("one", "one.gguf")), null)
        assertEquals(1, rows.size)
    }

    @Test
    fun autoSelectOnlyWhenNothingConfigured() {
        assertTrue(shouldAutoSelectEmbedding(configured = false, model = model("a.gguf")))
        assertFalse(shouldAutoSelectEmbedding(configured = true, model = model("a.gguf")))
        assertFalse(shouldAutoSelectEmbedding(false, model("l.gguf", ModelType.LLM)))
        assertFalse(shouldAutoSelectEmbedding(false, model("a.gguf", downloaded = false)))
    }
}
