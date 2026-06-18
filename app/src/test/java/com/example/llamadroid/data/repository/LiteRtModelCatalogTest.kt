package com.example.llamadroid.data.repository

import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.advertisedLiteRtMaxContextTokens
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.liteRtDeviceTargetInfoFromText
import com.example.llamadroid.data.model.liteRtDefaultChatContextTokensFromText
import com.example.llamadroid.data.model.liteRtAudioSupportFromText
import com.example.llamadroid.data.model.liteRtEngineMaxTokensFromText
import com.example.llamadroid.data.model.liteRtPackageMatchesDeviceTarget
import com.example.llamadroid.data.model.liteRtPackageMatchesDeviceTargets
import com.example.llamadroid.data.model.liteRtPackageTargetFromText
import com.example.llamadroid.data.model.liteRtVisionSupportFromText
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.supportsLiteRtAudio
import com.example.llamadroid.data.model.supportsLiteRtVision
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
        assertEquals(32768, liteRtEngineMaxTokensFromText("gemma-4-E2B-it.litertlm"))
        assertEquals(8192, liteRtDefaultChatContextTokensFromText("gemma-4-E2B-it.litertlm"))
        assertEquals(2048, liteRtEngineMaxTokensFromText("gemma3-1b-it-int4.litertlm"))
        assertEquals(4096, liteRtEngineMaxTokensFromText("DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"))
        assertEquals(1024, liteRtEngineMaxTokensFromText("mobile-actions_q8_ekv1024.litertlm"))
    }

    @Test
    fun `LiteRT catalog exposes known context caps`() {
        val entries = LiteRtModelCatalog.defaultEntries.associateBy { it.preferredFileName }

        assertEquals(4096, entries["Qwen3-0.6B.litertlm"]?.maxContextTokens)
        assertEquals(2048, entries["gemma3-1b-it-int4.litertlm"]?.maxContextTokens)
        assertEquals(32768, entries["gemma-4-E2B-it.litertlm"]?.maxContextTokens)
        assertEquals(4096, entries["Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"]?.maxContextTokens)
        assertEquals(1024, entries["mobile-actions_q8_ekv1024.litertlm"]?.maxContextTokens)
    }

    @Test
    fun `managed LiteRT model separates Gemma 4 advertised cap from default chat context`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )

        assertEquals(32768, model.advertisedLiteRtMaxContextTokens())
        assertEquals(32768, model.defaultLiteRtEngineMaxTokens())
        assertEquals(8192, model.defaultLiteRtChatContextTokens())
    }

    @Test
    fun `managed LiteRT model can override inferred advertised max tokens`() {
        val model = LiteRtModelEntity(
            displayName = "Imported package",
            path = "/tmp/custom.litertlm",
            filename = "custom.litertlm",
            maxContextTokens = 8192
        )

        assertEquals(8192, model.defaultLiteRtEngineMaxTokens())
        assertEquals(8192, model.defaultLiteRtChatContextTokens())
    }

    @Test
    fun `managed Gemma 4 LiteRT model keeps custom context caps selectable above default`() {
        val sixteenK = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm",
            maxContextTokens = 16384
        )
        val thirtyTwoK = sixteenK.copy(maxContextTokens = 32768)

        assertEquals(16384, sixteenK.advertisedLiteRtMaxContextTokens())
        assertEquals(8192, sixteenK.defaultLiteRtChatContextTokens())
        assertEquals(32768, thirtyTwoK.advertisedLiteRtMaxContextTokens())
        assertEquals(8192, thirtyTwoK.defaultLiteRtChatContextTokens())
    }

    @Test
    fun `LiteRT vision support is inferred for multimodal model families`() {
        val gemma4 = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm",
            supportsVision = true,
            supportsAudio = true
        )
        val gemma3n = LiteRtModelEntity(
            displayName = "Gemma 3n E2B IT LiteRT-LM",
            path = "/tmp/gemma-3n-E2B-it-int4.litertlm",
            repoId = "google/gemma-3n-E2B-it-litert-lm",
            filename = "gemma-3n-E2B-it-int4.litertlm",
            supportsVision = true,
            supportsAudio = true
        )
        val qwen = LiteRtModelEntity(
            displayName = "Qwen3 0.6B",
            path = "/tmp/Qwen3-0.6B.litertlm",
            repoId = "litert-community/Qwen3-0.6B",
            filename = "Qwen3-0.6B.litertlm"
        )

        assertTrue(gemma4.supportsLiteRtVision())
        assertTrue(gemma3n.supportsLiteRtVision())
        assertTrue(gemma4.supportsLiteRtAudio())
        assertTrue(gemma3n.supportsLiteRtAudio())
        assertTrue(liteRtVisionSupportFromText("custom-vlm-image-model.litertlm"))
        assertTrue(liteRtAudioSupportFromText("gemma-4-E4B-it.litertlm"))
        assertFalse(qwen.supportsLiteRtVision())
        assertFalse(qwen.supportsLiteRtAudio())
    }
}
