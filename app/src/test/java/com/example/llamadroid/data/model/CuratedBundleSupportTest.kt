package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.*
import org.junit.Test

class CuratedBundleSupportTest {
    @Test fun `unsafe bundle prefixes are sanitized`() {
        assertEquals("Tester-name", sanitizeCuratedBundlePrefix(" Tester / name "))
        assertFalse(sanitizeCuratedBundlePrefix("../../bad").contains(".."))
    }

    @Test fun `gemma complete bundles contain target projector and drafter`() {
        listOf("gemma4-e2b-complete", "gemma4-e4b-complete").forEach { id ->
            val bundle = LlamaCuratedBundleCatalog.bundles.single { it.id == id }
            assertTrue(bundle.files.any { it.type == ModelType.LLM })
            assertTrue(bundle.files.any { it.type == ModelType.VISION_PROJECTOR })
            assertTrue(bundle.files.any { it.type == ModelType.LLM_DRAFT })
        }
    }

    @Test fun `catalog contains qwen sizes through nine billion`() {
        val ids = LlamaCuratedBundleCatalog.bundles.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("qwen35-08b", "qwen35-2b", "qwen35-4b", "qwen35-9b")))
    }

    @Test fun `adetailer catalog contains only detector files`() {
        val bundles = AdetailerCuratedBundleCatalog.bundles
        assertEquals(4, bundles.size)
        assertTrue(bundles.flatMap { it.files }.all { it.type == ModelType.SD_ADETAILER })
        assertTrue(bundles.all { it.titleRes != 0 && it.descriptionRes != 0 })
        assertTrue(bundles.flatMap { it.files }.all { it.strictSize })
        assertTrue(bundles.flatMap { it.files }.all { it.downloadUrl.contains("model-assets/adetailer") })
    }

    @Test fun `catalog filename prefixes produce unique installed names`() {
        CuratedModelBundleRegistry.bundles.forEach { bundle ->
            val names = bundle.files.map { it.installedFilename(bundle.defaultPrefix) }
            assertEquals(names.size, names.distinct().size)
            assertTrue(names.all { it.startsWith("${bundle.defaultPrefix}-") })
        }
    }

    @Test fun `all curated files have unique ids and valid hashes`() {
        val files = CuratedModelBundleRegistry.files
        assertEquals(files.size, files.map { it.id }.distinct().size)
        files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
    }
}
