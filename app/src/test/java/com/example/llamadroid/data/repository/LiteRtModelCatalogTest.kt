package com.example.llamadroid.data.repository

import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.liteRtDeviceTargetInfoFromText
import com.example.llamadroid.data.model.liteRtEngineMaxTokensFromText
import com.example.llamadroid.data.model.liteRtPackageMatchesDeviceTarget
import com.example.llamadroid.data.model.liteRtPackageMatchesDeviceTargets
import com.example.llamadroid.data.model.liteRtPackageTargetFromText
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtModelCatalogTest {
    @Test
    fun `default catalog exposes modern LiteRT chat choices`() {
        val entries = LiteRtModelCatalog.defaultEntries

        assertTrue(entries.size >= 20)
        assertEquals(entries.size, entries.map { it.catalogId }.distinct().size)
        assertTrue(entries.any { it.repoId == "litert-community/Qwen3-4B" })
        assertTrue(entries.any { it.repoId == "google/gemma-3n-E2B-it-litert-lm" })
    }

    @Test
    fun `catalog entries prefer LiteRT-LM assets when known`() {
        LiteRtModelCatalog.defaultEntries.forEach { entry ->
            assertTrue(
                "${entry.repoId} should point at a LiteRT-LM package",
                entry.preferredFileName?.endsWith(".litertlm") == true
            )
        }
    }

    @Test
    fun `catalog exposes only CPU and GPU LiteRT choices`() {
        val qwenSmall = LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.GPU)
            .first { it.repoId == "litert-community/Qwen3-0.6B" }
        val gemmaGeneric = LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.GPU)
            .first { it.preferredFileName == "gemma-4-E2B-it.litertlm" }

        assertTrue(qwenSmall.supportsGpu)
        assertFalse(qwenSmall.supportsNpu)
        assertTrue(gemmaGeneric.supportsCpu)
        assertTrue(gemmaGeneric.supportsGpu)
        assertFalse(gemmaGeneric.supportsNpu)
        assertTrue(LiteRtModelCatalog.defaultEntries.none { it.supportsNpu })
    }

    @Test
    fun `catalog exposes at least ten choices in retained runtime groups`() {
        assertTrue(LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.GPU).size >= 10)
        assertTrue(LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.CPU).size >= 10)
    }

    @Test
    fun `package target detection blocks mismatched snapdragon packages`() {
        val sm8650 = liteRtPackageTargetFromText("gemma3-270m-it-q8.qualcomm.sm8650.litertlm")
        val sm8650Duplicate = liteRtPackageTargetFromText("Gemma3-1B-IT_q4_ekv1280_sm8650-1.litertlm")
        val qcs8275 = liteRtPackageTargetFromText("gemma-4-E2B-it_qualcomm_qcs8275.litertlm")

        assertEquals("sm8650", sm8650)
        assertEquals("sm8650", sm8650Duplicate)
        assertEquals("qcs8275", qcs8275)
        assertTrue(liteRtPackageMatchesDeviceTarget(sm8650, "qualcomm SM8650 NX769J"))
        assertFalse(liteRtPackageMatchesDeviceTarget(qcs8275, "qualcomm SM8650 NX769J"))
    }

    @Test
    fun `redmagic pineapple alias resolves to sm8650 for package matching`() {
        val deviceInfo = liteRtDeviceTargetInfoFromText("QTI SM8650 qcom pineapple NX769J NX769J-EEA nubia")

        assertTrue(deviceInfo.normalizedTargets.contains("sm8650"))
        assertTrue(
            liteRtPackageMatchesDeviceTargets(
                packageTarget = "sm8650",
                deviceTargets = deviceInfo.normalizedTargets,
                rawDeviceInfo = deviceInfo.rawLabel
            )
        )
        assertFalse(
            liteRtPackageMatchesDeviceTargets(
                packageTarget = "qcs8275",
                deviceTargets = deviceInfo.normalizedTargets,
                rawDeviceInfo = deviceInfo.rawLabel
            )
        )
        assertFalse(
            liteRtPackageMatchesDeviceTargets(
                packageTarget = "sm8750",
                deviceTargets = deviceInfo.normalizedTargets,
                rawDeviceInfo = deviceInfo.rawLabel
            )
        )
    }

    @Test
    fun `catalog no longer exposes npu packages`() {
        assertTrue(LiteRtModelCatalog.defaultEntries.none { entry ->
            entry.preferredFileName.orEmpty().contains("qualcomm", ignoreCase = true)
        })
    }

    @Test
    fun `backend normalization retires npu aliases to auto`() {
        assertEquals(LITERT_BACKEND_AUTO, normalizeLiteRtBackend("force-npu"))
        assertEquals(LITERT_BACKEND_AUTO, normalizeLiteRtBackend("npu_force"))
        assertEquals(LITERT_BACKEND_AUTO, normalizeLiteRtBackend("qnn-force"))
        assertEquals(LITERT_BACKEND_AUTO, normalizeLiteRtBackend("npu"))
    }

    @Test
    fun `LiteRT engine max tokens follow model package defaults`() {
        assertEquals(4000, liteRtEngineMaxTokensFromText("gemma-4-E2B-it.litertlm"))
        assertEquals(4096, liteRtEngineMaxTokensFromText("DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"))
        assertEquals(1024, liteRtEngineMaxTokensFromText("mobile-actions_q8_ekv1024.litertlm"))
    }

    @Test
    fun `managed LiteRT model derives Gemma 4 max tokens from filename`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )

        assertEquals(4000, model.defaultLiteRtEngineMaxTokens())
    }
}
