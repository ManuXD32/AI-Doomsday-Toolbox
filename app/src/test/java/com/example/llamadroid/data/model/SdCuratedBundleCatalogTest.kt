package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SdCuratedBundleCatalogTest {
    @Test
    fun catalogContainsEveryRequestedBundleClass() {
        val ids = SdCuratedBundleCatalog.bundles.map { it.id }.toSet()
        assertTrue("ipa-starter" in ids)
        assertTrue("low-end" in ids)
        assertTrue("midrange" in ids)
        assertTrue("high-end" in ids)
        assertTrue("distributed" in ids)
        assertTrue("local-video" in ids)
        assertTrue("photo-upscale-2x" in ids)
        assertTrue("photo-upscale-4x" in ids)
        assertTrue("anime-upscale-4x" in ids)
    }


    @Test
    fun curatedStorageTotalsAreStable() {
        assertEquals(4_320_207_000L, requireNotNull(SdCuratedBundleCatalog.byId("ipa-starter")).totalSizeBytes)
        assertEquals(1_747_190_784L, requireNotNull(SdCuratedBundleCatalog.byId("low-end")).totalSizeBytes)
        assertEquals(3_940_000_000L, requireNotNull(SdCuratedBundleCatalog.byId("midrange")).totalSizeBytes)
        assertEquals(10_248_278_972L, requireNotNull(SdCuratedBundleCatalog.byId("high-end")).totalSizeBytes)
        assertEquals(13_312_527_420L, requireNotNull(SdCuratedBundleCatalog.byId("distributed")).totalSizeBytes)
        assertEquals(8_180_926_784L, requireNotNull(SdCuratedBundleCatalog.byId("local-video")).totalSizeBytes)
        assertEquals(67_061_725L, requireNotNull(SdCuratedBundleCatalog.byId("photo-upscale-2x")).totalSizeBytes)
        assertEquals(67_040_989L, requireNotNull(SdCuratedBundleCatalog.byId("photo-upscale-4x")).totalSizeBytes)
        assertEquals(17_938_799L, requireNotNull(SdCuratedBundleCatalog.byId("anime-upscale-4x")).totalSizeBytes)
    }

    @Test
    fun bundleIdsPrefixesAndLocalFilenamesAreUnique() {
        val bundles = SdCuratedBundleCatalog.bundles
        assertEquals(bundles.size, bundles.map { it.id }.distinct().size)
        assertEquals(bundles.size, bundles.map { it.installPrefix }.distinct().size)
        bundles.forEach { bundle ->
            val names = bundle.files.map { it.localFilename(bundle.installPrefix) }
            assertEquals(names.size, names.distinct().size)
            names.forEach { name ->
                assertTrue(name.startsWith("${bundle.installPrefix}-"))
                assertFalse(name.any { it.isWhitespace() })
            }
        }
    }

    @Test
    fun declaredTotalsMatchFileSumsAndMetadataIsComplete() {
        val sha = Regex("^[0-9a-f]{64}$")
        SdCuratedBundleCatalog.bundles.forEach { bundle ->
            assertTrue(bundle.files.isNotEmpty())
            assertEquals(bundle.files.sumOf { it.sizeBytes }, bundle.totalSizeBytes)
            assertTrue(bundle.totalSizeBytes > 0L)
            bundle.files.forEach { file ->
                assertTrue(file.sizeBytes > 0L)
                assertTrue(sha.matches(file.sha256))
                assertTrue(file.repoId.contains('/'))
                assertTrue(file.remotePath.isNotBlank())
                assertTrue(file.downloadUrl().contains("/resolve/"))
                assertTrue(file.downloadUrl().endsWith("?download=true"))
                assertTrue(file.licenseLabel.isNotBlank())
            }
        }
    }

    @Test
    fun ipaBundleContainsCompleteSd15Stack() {
        val bundle = requireNotNull(SdCuratedBundleCatalog.byId("ipa-starter"))
        assertTrue(bundle.files.any { it.modelType == ModelType.SD_CHECKPOINT && it.sdVariant == "sd1" })
        assertTrue(bundle.files.any { it.modelType == ModelType.SD_IP_ADAPTER })
        assertTrue(bundle.files.any { it.modelType == ModelType.SD_CLIP_VISION })
    }

    @Test
    fun fluxBundlesContainAllRequiredComponents() {
        listOf("high-end", "distributed").forEach { id ->
            val bundle = requireNotNull(SdCuratedBundleCatalog.byId(id))
            assertTrue(bundle.files.any { it.modelType == ModelType.SD_DIFFUSION })
            assertTrue(bundle.files.any { it.modelType == ModelType.SD_CLIP_L })
            assertTrue(bundle.files.any { it.modelType == ModelType.SD_T5XXL })
            assertTrue(bundle.files.any { it.modelType == ModelType.SD_VAE })
        }
    }

    @Test
    fun localVideoBundleContainsModelEncoderAndVae() {
        val bundle = requireNotNull(SdCuratedBundleCatalog.byId("local-video"))
        val videoModel = bundle.files.firstOrNull { it.modelType == ModelType.SD_DIFFUSION }
        assertNotNull(videoModel)
        assertTrue(videoModel!!.sdCapabilities.orEmpty().split(',').contains(SD_CAPABILITY_VID_GEN))
        assertTrue(bundle.files.any { it.modelType == ModelType.SD_T5XXL })
        assertTrue(bundle.files.any { it.modelType == ModelType.SD_VAE })
    }

    @Test
    fun upscalerBundlesContainOnlyUpscalers() {
        SdCuratedBundleCatalog.bundles
            .filter { it.id.contains("upscale") }
            .forEach { bundle ->
                assertTrue(bundle.files.all { it.modelType == ModelType.SD_UPSCALER })
            }
    }

    @Test
    fun filenameSanitizerPreservesPrefixIntent() {
        assertEquals(
            "Tester-v1.5-emaonly.gguf",
            curatedBundleFilename("Tester", "v1.5 emaonly.gguf")
        )
        assertEquals(
            "My-Bundle-model.safetensors",
            curatedBundleFilename(" My Bundle ", "model.safetensors")
        )
    }
    @Test
    fun payloadVerificationAcceptsExactBytesAndRejectsCorruption() {
        val directory = createTempDirectory("sd-curated-test-").toFile()
        try {
            val payload = File(directory, "payload.bin").apply { writeBytes("abc".toByteArray()) }
            val spec = SdCuratedBundleFile(
                id = "fixture",
                repoId = "fixture/repo",
                remotePath = "payload.bin",
                modelType = ModelType.SD_CHECKPOINT,
                sizeBytes = 3L,
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                licenseLabel = "test"
            )
            verifySdCuratedFilePayload(spec, payload)
            payload.writeBytes("abd".toByteArray())
            runCatching { verifySdCuratedFilePayload(spec, payload) }
                .onSuccess { throw AssertionError("corrupt payload was accepted") }
        } finally {
            directory.deleteRecursively()
        }
    }

}
