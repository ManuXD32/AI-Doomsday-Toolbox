package com.example.llamadroid.data.model

import com.example.llamadroid.audio.music.StableAudio3ManifestLoader
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StableAudioCuratedBundleCatalogTest {
    private fun manifest() = StableAudio3ManifestLoader.parse(
        listOf(File("src/main/assets/stable_audio3_components.json"),
            File("app/src/main/assets/stable_audio3_components.json")).first { it.isFile }.readText()
    )

    @Test fun `music and sfx share verified components across prefixes`() {
        val bundles = StableAudioCuratedBundleCatalog.fromManifest(manifest())
        assertEquals(2, bundles.size)
        val first = bundles.first()
        val second = bundles.last()
        val shared = first.files.filter { it.type == ModelType.LITERT_AUDIO_COMPONENT }
        assertEquals(4, shared.size)
        shared.forEach { component ->
            val other = second.files.single { it.componentRole == component.componentRole }
            assertEquals(component.artifactIdentity, other.artifactIdentity)
            assertEquals(component.installedFilename(first.defaultPrefix), other.installedFilename(second.defaultPrefix))
            assertTrue(component.sizeBytes > 0)
        }
        assertNotEquals(first.files.single { it.type == ModelType.LITERT_AUDIO_DIT }.artifactIdentity,
            second.files.single { it.type == ModelType.LITERT_AUDIO_DIT }.artifactIdentity)
        assertTrue(bundles.flatMap { it.files }.all {
            it.downloadUrl.contains("/resolve/da6edc54ddba10bfd79a077102ded687f80e882b/")
        })
    }

    @Test fun `missing digest prevents a curated bundle from being offered`() {
        val source = manifest()
        val invalid = source.entries.first().let { entry ->
            entry.copy(components = entry.components.mapIndexed { index, component ->
                if (index == 0) component.copy(sha256 = null) else component
            })
        }
        assertTrue(StableAudioCuratedBundleCatalog.fromManifest(source.copy(entries = listOf(invalid))).isEmpty())
    }

    @Test fun `precision selection falls back to a manifest graph`() {
        val selection = manifest().repairPrecisionSelection(
            kind = com.example.llamadroid.audio.music.StableAudio3Kind.MUSIC,
            dit = "removed_precision",
            decoder = "fp32",
            encoder = "w8a8"
        )
        assertNotNull(selection)
        assertEquals("fp32", selection?.dit?.wireValue)
        assertEquals("w8a8", selection?.decoder?.wireValue)
        assertEquals("w8a8", selection?.encoder?.wireValue)
    }
}
