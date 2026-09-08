package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ModelEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
        val coco = bundles.single { it.id == "adetailer-general-objects" }.files.single()
        assertEquals("ultralytics/assets", coco.repoId)
        assertEquals("v8.3.0", coco.revision)
    }

    @Test fun `catalog filename prefixes produce unique installed names`() {
        CuratedModelBundleRegistry.bundles.forEach { bundle ->
            val names = bundle.files.map { it.installedFilename(bundle.defaultPrefix) }
            assertEquals(names.size, names.distinct().size)
            assertTrue(names.zip(bundle.files).all { (name, file) ->
                file.sharedArtifactKey != null || name.startsWith("${bundle.defaultPrefix}-")
            })
        }
    }

    @Test fun `all curated files have valid hashes and shared ids are consistent`() {
        val files = CuratedModelBundleRegistry.files
        files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }

        // A shared artifact is intentionally listed by each bundle that can
        // install it. It keeps one stable catalog id and content identity so
        // prefix changes cannot create or delete duplicate companion payloads.
        val duplicateIds = files.groupBy { it.id }
            .filterValues { it.size > 1 }
        assertEquals(setOf("qwen3-tts-17b-mmproj-q8"), duplicateIds.keys)
        duplicateIds.values.forEach { sharedFiles ->
            assertEquals(1, sharedFiles.map { it.artifactIdentity }.distinct().size)
            assertEquals(1, sharedFiles.map { it.sha256 }.distinct().size)
            assertEquals(1, sharedFiles.map { it.type }.distinct().size)
            assertEquals(1, sharedFiles.map { it.sourceIdentity }.distinct().size)
            assertTrue(sharedFiles.all { it.sharedArtifactKey != null })
        }

        val uniqueNonSharedIds = files
            .filter { it.sharedArtifactKey == null }
            .map { it.id }
        assertEquals(uniqueNonSharedIds.size, uniqueNonSharedIds.distinct().size)
    }

    @Test fun `audio catalog pins launch pairs and shares qwen companion identity`() {
        val bundles = AudioCuratedBundleCatalog.bundles
        assertEquals(4, bundles.size)
        assertTrue(bundles.all { it.files.any { file -> file.type == ModelType.LLAMA_TTS } })
        assertTrue(bundles.all { it.files.any { file -> file.type == ModelType.LLAMA_TTS_COMPANION } })
        val qwenCompanions = bundles.filter { it.id.startsWith("audio-qwen3") }
            .flatMap { it.files }
            .filter { it.type == ModelType.LLAMA_TTS_COMPANION }
        assertEquals(1, qwenCompanions.map { it.artifactIdentity }.distinct().size)
        assertTrue(CuratedModelBundleRegistry.isCuratedArtifactShared(qwenCompanions.first().artifactIdentity))
        assertTrue(CuratedModelBundleRegistry.isArtifactReferenced(
            qwenCompanions.first().artifactIdentity,
            setOf(qwenCompanions.first().artifactIdentity)
        ))
        assertEquals(446_422_912L, qwenCompanions.first().sizeBytes)
        assertEquals("Apache-2.0", qwenCompanions.first().license)
    }

    @Test fun `audio shared component uses digest name across bundle prefixes`() {
        val q4 = AudioCuratedBundleCatalog.bundles.single { it.id.endsWith("q4-k-m") }
        val q8 = AudioCuratedBundleCatalog.bundles.single { it.id.endsWith("q8-0") }
        val q4Companion = q4.files.first { it.type == ModelType.LLAMA_TTS_COMPANION }
        val q8Companion = q8.files.first { it.type == ModelType.LLAMA_TTS_COMPANION }
        assertEquals(q4Companion.installedFilename(q4.defaultPrefix), q8Companion.installedFilename(q8.defaultPrefix))
        assertEquals(q4Companion.sha256, q8Companion.sha256)
    }

    @Test fun `curated verification binds basename to pinned source`() {
        val file = AudioCuratedBundleCatalog.bundles
            .flatMap { it.files }
            .first { it.type == ModelType.LLAMA_TTS }
        assertEquals(
            file,
            CuratedModelBundleRegistry.fileForDownload(
                localFilename = file.installedFilename("Test"),
                repoId = file.repoId,
                sourceUrl = file.downloadUrl
            )
        )
        assertNull(
            CuratedModelBundleRegistry.fileForDownload(
                localFilename = file.installedFilename("Test"),
                repoId = file.repoId,
                sourceUrl = "https://example.invalid/custom/${file.localFilename}"
            )
        )
        assertEquals(
            file,
            CuratedModelBundleRegistry.fileForArtifactDigest("sha256:${file.sha256}", file.type)
        )
    }

    @Test fun `audio installed state requires matching digest and physical size`() {
        val sha = "a".repeat(64)
        val file = CuratedBundleFile(
            id = "audio-test-main",
            repoId = "test/audio",
            revision = "abcdef1",
            remotePath = "voice.gguf",
            localFilename = "voice.gguf",
            type = ModelType.LLAMA_TTS,
            sizeBytes = 4L,
            sha256 = sha,
            license = "Test",
            componentRole = AudioModelSupport.ROLE_MAIN,
            audioFamily = AudioModelSupport.FAMILY_CUSTOM_TTS
        )
        val payload = File.createTempFile("audio-model", ".gguf")
        payload.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val verified = ModelEntity(
                filename = "Test-voice.gguf",
                path = payload.absolutePath,
                sizeBytes = 4L,
                type = ModelType.LLAMA_TTS,
                repoId = "test/audio",
                isDownloaded = true,
                audioFamily = AudioModelSupport.FAMILY_CUSTOM_TTS,
                audioComponentRole = AudioModelSupport.ROLE_MAIN,
                audioArtifactIdentity = file.artifactIdentity
            )
            assertTrue(file.matchesVerifiedInstalledModel("Test-voice.gguf", verified))
            assertFalse(file.matchesVerifiedInstalledModel(
                "Test-voice.gguf",
                verified.copy(audioArtifactIdentity = "sha256:${"b".repeat(64)}")
            ))
        } finally {
            payload.delete()
        }
    }
}
