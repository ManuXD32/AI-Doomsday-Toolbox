package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.SdCuratedBundle
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.SdCuratedBundleFile
import com.example.llamadroid.service.VideoRuntimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModelSelectionTest {

    private fun bundle(id: String): SdCuratedBundle = SdCuratedBundleCatalog.bundles.first { it.id == id }

    /** Fake installed-check: matches by local filename and type, never touches the disk. */
    private val fakeLookup: (SdCuratedBundle, SdCuratedBundleFile, List<ModelEntity>) -> ModelEntity? =
        { b, file, installed ->
            val name = file.localFilename(b.installPrefix)
            installed.firstOrNull { it.filename == name && it.type == file.modelType }
        }

    private fun resolve(path: String, installed: List<ModelEntity>) =
        resolveVideoModelSelection(path, installed, fakeLookup)

    /** Entities with fake paths; no files are created (the workspace is on NFS). */
    private fun install(bundle: SdCuratedBundle, skipIds: Set<String> = emptySet()): List<ModelEntity> =
        bundle.files.filter { it.id !in skipIds }.map { file ->
            val name = file.localFilename(bundle.installPrefix)
            ModelEntity(
                filename = name,
                path = "/fake/models/$name",
                sizeBytes = file.sizeBytes,
                type = file.modelType,
                repoId = file.repoId,
                isDownloaded = true,
                sdCapabilities = file.sdCapabilities,
                sdFamily = file.sdFamily,
                sdVariant = file.sdVariant,
                sdCompatProfiles = file.sdCompatProfiles
            )
        }

    private fun List<ModelEntity>.pathOf(id: String, b: SdCuratedBundle): String =
        first { it.filename == b.files.first { f -> f.id == id }.localFilename(b.installPrefix) }.path

    @Test
    fun wan22BundleFillsVaeAndUmt5() {
        val b = bundle("local-video")
        val installed = install(b).map { if (it.type == ModelType.SD_DIFFUSION) it.copy(sdFamily = "wan") else it }
        val main = installed.pathOf("wan22-ti2v-q4", b)
        val selection = resolve(main, installed)!!
        assertEquals("local-video", selection.bundleId)
        assertEquals(SdVideoFamily.WAN, selection.family)
        assertEquals(main, selection.components.diffusionModelPath)
        assertEquals(installed.pathOf("wan22-vae", b), selection.components.vaePath)
        assertEquals(installed.pathOf("wan22-umt5-q4", b), selection.components.t5xxlPath)
        assertTrue(selection.missingRoles.isEmpty())
        val applied = VideoRuntimeOptions().withSelection(selection)
        assertEquals(SdVideoFamily.WAN, applied.videoFamily)
        assertEquals(selection.components.vaePath, applied.videoComponents.vaePath)
        assertEquals(selection.components.t5xxlPath, applied.videoComponents.t5xxlPath)
    }

    @Test
    fun lingBotBundleMatchesApplyLingBotProfile() {
        val b = bundle("lingbot-phone")
        val installed = install(b)
        val main = installed.pathOf("lingbot-dense-13b", b)
        val selection = resolve(main, installed)!!
        assertEquals(SdVideoFamily.LINGBOT_VIDEO, selection.family)
        val byId = b.files.associate { f ->
            f.id to installed.first { it.filename == f.localFilename(b.installPrefix) }.path
        }
        val expected = SdCuratedPresets.lingBotRuntimeOptions(
            SdCuratedPresets.resolveLingBotComponents(byId, b.files.size)!!
        )
        assertEquals(expected, VideoRuntimeOptions().withSelection(selection))
    }

    @Test
    fun unknownFamilyFillsNothing() {
        val model = ModelEntity(
            filename = "mystery.gguf", path = "/fake/models/mystery.gguf", sizeBytes = 1L,
            type = ModelType.SD_DIFFUSION, repoId = "local/import", isDownloaded = true
        )
        val selection = resolve(model.path, listOf(model))!!
        assertNull(selection.family)
        assertNull(selection.bundleId)
        assertEquals(SdVideoComponentPaths(), selection.components)
        assertTrue(selection.missingRoles.isEmpty())
        val options = VideoRuntimeOptions()
        assertSame(options, options.withSelection(selection))
    }

    @Test
    fun missingRolesAreListed() {
        val b = bundle("local-video")
        val installed = install(b, skipIds = setOf("wan22-vae", "wan22-umt5-q4"))
        val main = installed.pathOf("wan22-ti2v-q4", b)
        val selection = resolve(main, installed)!!
        assertEquals(
            setOf(SdVideoComponentRole.VAE, SdVideoComponentRole.T5XXL),
            selection.missingRoles.toSet()
        )
        assertNull(selection.components.vaePath)
    }

    @Test
    fun reselectingReturnsSameInstance() {
        val b = bundle("local-video")
        val installed = install(b).map { if (it.type == ModelType.SD_DIFFUSION) it.copy(sdFamily = "wan") else it }
        val selection = resolve(installed.pathOf("wan22-ti2v-q4", b), installed)!!
        val once = VideoRuntimeOptions().withSelection(selection)
        assertSame(once, once.withSelection(selection))
    }

    @Test
    fun blankPathResolvesToNull() {
        assertNull(resolveVideoModelSelection("", emptyList()))
    }

    @Test
    fun presetCardsListCatalogBundlesFirstThenImports() {
        val b = bundle("local-video")
        val installed = install(b)
        val custom = ModelEntity(
            filename = "custom-wan.gguf", path = "/fake/models/custom-wan.gguf", sizeBytes = 1L,
            type = ModelType.SD_DIFFUSION, repoId = "local/import", isDownloaded = true,
            sdFamily = "wan"
        )
        val cards = videoPresetCards(installed + custom, fakeLookup)
        val wanCard = cards.first { it.bundleId == "local-video" }
        assertTrue(wanCard.installed)
        assertEquals(0, wanCard.missingFiles)
        val lingCard = cards.first { it.bundleId == "lingbot-phone" }
        assertFalse(lingCard.installed)
        assertEquals(3, lingCard.missingFiles)
        assertNull(lingCard.mainPath)
        assertNotNull(cards.last().title)
        assertNull(cards.last().bundleId)
        assertEquals("custom-wan.gguf", cards.last().title)
        assertTrue(cards.indexOfFirst { it.bundleId == null } > cards.indexOfLast { it.bundleId != null })
    }

    @Test
    fun bundleWithoutEntityFamilyFallsBackToBundleFamily() {
        val b = bundle("local-video")
        val installed = install(b).map { it.copy(sdFamily = null, sdVariant = null, sdCompatProfiles = null) }
        val main = installed.pathOf("wan22-ti2v-q4", b)
        val selection = resolve(main, installed)!!
        assertEquals("local-video", selection.bundleId)
        assertEquals(SdVideoFamily.WAN, selection.family)
    }
}
