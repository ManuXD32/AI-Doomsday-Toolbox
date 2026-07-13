package com.example.llamadroid.data.repository

import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_KB_EMBED_RUNTIME_BERT_WORDPIECE
import com.example.llamadroid.data.model.LITERT_KB_EMBED_RUNTIME_EMBEDDING_GEMMA
import com.example.llamadroid.data.model.LITERT_KB_EMBED_RUNTIME_STRING_TFLITE
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.advertisedLiteRtMaxContextTokens
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.isKbLiteRtEmbeddingRunnable
import com.example.llamadroid.data.model.liteRtDeviceTargetInfoFromText
import com.example.llamadroid.data.model.liteRtDefaultChatContextTokensFromText
import com.example.llamadroid.data.model.liteRtEmbeddingRuntimeSupportedFromText
import com.example.llamadroid.data.model.liteRtAudioSupportFromText
import com.example.llamadroid.data.model.liteRtKbEmbeddingRuntimeFromText
import com.example.llamadroid.data.model.liteRtEmbeddingSupportFromText
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LiteRtModelCatalogTest {
    @Test
    fun `default catalog exposes modern LiteRT chat choices`() {
        val entries = LiteRtModelCatalog.defaultEntries

        assertTrue(entries.size >= 10)
        assertEquals(entries.size, entries.map { it.catalogId }.distinct().size)
        assertEquals(entries.size, entries.map { it.repoId to it.preferredFileName }.distinct().size)
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
    fun `embedding catalog stays hidden until KB runnable LiteRT packages are supported`() {
        assertTrue(LiteRtModelCatalog.embeddingEntries.isEmpty())
        assertEquals(LiteRtModelCatalog.defaultEntries, LiteRtModelCatalog.allEntries)
    }

    @Test
    fun `chat catalog exposes download size hints`() {
        val entries = LiteRtModelCatalog.defaultEntries

        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { (it.sizeBytes ?: 0L) > 0L })
    }

    @Test
    fun `catalog exposes only CPU and GPU LiteRT choices`() {
        val qwenSmall = LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.CPU)
            .first { it.repoId == "litert-community/Qwen3-0.6B" }
        val gemmaGeneric = LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.GPU)
            .first { it.preferredFileName == "gemma-4-E2B-it.litertlm" }

        assertTrue(qwenSmall.supportsCpu)
        assertFalse(qwenSmall.supportsGpu)
        assertFalse(qwenSmall.supportsNpu)
        assertTrue(gemmaGeneric.supportsCpu)
        assertTrue(gemmaGeneric.supportsGpu)
        assertFalse(gemmaGeneric.supportsNpu)
        assertTrue(LiteRtModelCatalog.defaultEntries.none { it.supportsNpu })
    }

    @Test
    fun `catalog exposes one unified CPU GPU list`() {
        assertTrue(LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.GPU).size >= 4)
        assertTrue(LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.CPU).size >= 6)
        assertTrue(LiteRtModelCatalog.defaultEntries.all { it.supportsCpu })
        assertTrue(LiteRtModelCatalog.entriesFor(LiteRtCatalogCategory.CPU).none { it.supportsGpu })
    }

    @Test
    fun `embedding capability inference recognizes common embedding families`() {
        assertTrue(liteRtEmbeddingSupportFromText("gte-small text embedding model"))
        assertTrue(liteRtEmbeddingSupportFromText("bge-base-en-v1.5.tflite"))
        assertTrue(liteRtEmbeddingSupportFromText("multilingual-e5-small retrieval"))
        assertFalse(liteRtEmbeddingSupportFromText("Gemma 4 E4B IT LiteRT-LM"))
    }

    @Test
    fun `raw EmbeddingGemma TFLite assets are not treated as directly runnable KB embedders`() {
        assertFalse(liteRtEmbeddingRuntimeSupportedFromText("kamalkraj/embeddinggemma-300m-litert embedding_gemma_no_normalize_q8.tflite"))
        assertFalse(liteRtEmbeddingRuntimeSupportedFromText("embeddinggemma-300M_seq2048_mixed-precision.tflite"))
        assertTrue(liteRtEmbeddingRuntimeSupportedFromText("bert-hash-nano-embeddings-fp32.tflite"))
        assertTrue(liteRtEmbeddingRuntimeSupportedFromText("gte-small.task"))
    }

    @Test
    fun `embedding runtime family inference separates tensor contracts from string input packages`() {
        assertEquals(
            LITERT_KB_EMBED_RUNTIME_EMBEDDING_GEMMA,
            liteRtKbEmbeddingRuntimeFromText("embeddinggemma-300m embedding_gemma_no_normalize_q8.tflite tokenizer.model")
        )
        assertEquals(
            LITERT_KB_EMBED_RUNTIME_BERT_WORDPIECE,
            liteRtKbEmbeddingRuntimeFromText("bert-hash-nano-embeddings-fp32.tflite vocab.txt")
        )
        assertEquals(
            LITERT_KB_EMBED_RUNTIME_STRING_TFLITE,
            liteRtKbEmbeddingRuntimeFromText("gte-small-textembedder.task")
        )
        assertEquals(
            LITERT_KB_EMBED_RUNTIME_STRING_TFLITE,
            liteRtKbEmbeddingRuntimeFromText("mobilebert_embedder.tflite")
        )
    }

    @Test
    fun `kb runnable helper requires both embedding badge and runnable contract`() {
        val broadOnly = LiteRtModelEntity(
            displayName = "embedding candidate",
            path = "/tmp/model.tflite",
            filename = "model.tflite",
            supportsEmbedding = true,
            kbEmbeddingRunnable = false
        )
        val runnable = broadOnly.copy(kbEmbeddingRunnable = true)

        assertFalse(broadOnly.isKbLiteRtEmbeddingRunnable())
        assertTrue(runnable.isKbLiteRtEmbeddingRunnable())
    }

    @Test
    fun `EmbeddingGemma package with tokenizer is embedding-like but not KB runnable until SentencePiece runner exists`() {
        val dir = Files.createTempDirectory("embeddinggemma-litert").toFile()
        try {
            File(dir, "embedding_gemma_no_normalize_q8.tflite").writeText("model")
            File(dir, "tokenizer.model").writeText("tokenizer")

            val compatibility = evaluateKbEmbeddingCompatibility(dir, "kamalkraj/embeddinggemma-300m-litert", true)

            assertTrue(compatibility.embeddingLike)
            assertFalse(compatibility.runnable)
            assertEquals(LITERT_KB_EMBED_RUNTIME_EMBEDDING_GEMMA, compatibility.runtime)
            assertEquals("sentencepiece_runtime_pending", compatibility.status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `BERT tensor embedding without vocab is not offered as KB runnable`() {
        val dir = Files.createTempDirectory("bert-hash-litert").toFile()
        try {
            File(dir, "bert-hash-nano-embeddings-fp32.tflite").writeText("model")

            val compatibility = evaluateKbEmbeddingCompatibility(dir, "NeuML/bert-hash-nano-embeddings-litert", true)

            assertTrue(compatibility.embeddingLike)
            assertFalse(compatibility.runnable)
            assertEquals(LITERT_KB_EMBED_RUNTIME_BERT_WORDPIECE, compatibility.runtime)
            assertEquals("missing_wordpiece_tokenizer", compatibility.status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `string input task embedding package is KB runnable`() {
        val dir = Files.createTempDirectory("gte-task-litert").toFile()
        try {
            File(dir, "gte-small-textembedder.task").writeText("model")

            val compatibility = evaluateKbEmbeddingCompatibility(dir, "example/gte-small-litert", true)

            assertTrue(compatibility.embeddingLike)
            assertTrue(compatibility.runnable)
            assertEquals(LITERT_KB_EMBED_RUNTIME_STRING_TFLITE, compatibility.runtime)
            assertEquals("ready", compatibility.status)
        } finally {
            dir.deleteRecursively()
        }
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
    fun `layered live search terms broaden LiteRT discovery without duplicates`() {
        val terms = layeredSearchTerms("gemma 4")

        assertEquals(listOf("gemma 4", "gemma 4 litert", "gemma 4 litertlm"), terms)
    }

    @Test
    fun `layered live search terms avoid repeating LiteRT suffixes`() {
        val terms = layeredSearchTerms("owner/model litertlm")

        assertEquals(listOf("owner/model litertlm"), terms)
    }

    @Test
    fun `embedding package ranking prefers generic assets over device specific variants`() {
        val generic = packagePreferenceRank("embeddinggemma-300M_seq2048_mixed-precision.tflite", true)
        val tensor = packagePreferenceRank("embeddinggemma-300M_seq2048_mixed-precision.google.tensor_g5.tflite", true)
        val qualcomm = packagePreferenceRank("embeddinggemma-300M_seq2048_mixed-precision.qualcomm.sm8650.tflite", true)

        assertTrue(generic < tensor)
        assertTrue(generic < qualcomm)
    }

    @Test
    fun `embeddinggemma live search seeds public mirror before Hugging Face ranking`() {
        val seeds = seededLiteRtReposForQuery("embeddinggemma")

        assertEquals(listOf("kontextdev/embeddinggemma-300m-litertlm"), seeds)
    }

    @Test
    fun `live search ordering prefers public packages unless exact repo was requested`() {
        val public = LiteRtCatalogEntry(
            repoId = "kontextdev/embeddinggemma-300m-litertlm",
            title = "EmbeddingGemma public mirror",
            description = "Public embedding package",
            preferredFileName = "embeddinggemma-300M_seq512_mixed-precision.tflite"
        )
        val gated = LiteRtCatalogEntry(
            repoId = "litert-community/embeddinggemma-300m",
            title = "EmbeddingGemma official",
            description = "License-protected embedding package",
            preferredFileName = "embeddinggemma-300M_seq2048_mixed-precision.tflite"
        )

        assertEquals(public.repoId, listOf(gated, public).sortedWith(liveResultComparator(null)).first().repoId)
        assertEquals(
            gated.repoId,
            listOf(public, gated).sortedWith(liveResultComparator("litert-community/embeddinggemma-300m")).first().repoId
        )
    }

    @Test
    fun `exact repo id lookup accepts owner repo and rejects loose text`() {
        assertEquals("google/gemma-3n-E4B-it-litert-lm", exactRepoIdFromQuery("google/gemma-3n-E4B-it-litert-lm"))
        assertNull(exactRepoIdFromQuery("gemma 3n e4b"))
        assertNull(exactRepoIdFromQuery("owner/repo extra"))
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

    private fun layeredSearchTerms(query: String): List<String> {
        val method = LiteRtModelRepository::class.java.getDeclaredMethod("layeredSearchTerms", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(repository(), query) as List<String>
    }

    private fun packagePreferenceRank(path: String, preferEmbeddingRuntime: Boolean): Int {
        val method = LiteRtModelRepository::class.java.getDeclaredMethod(
            "packagePreferenceRank",
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(repository(), path, preferEmbeddingRuntime) as Int
    }

    private fun exactRepoIdFromQuery(query: String): String? {
        val method = LiteRtModelRepository::class.java.getDeclaredMethod("exactRepoIdFromQuery", String::class.java)
        method.isAccessible = true
        return method.invoke(repository(), query) as String?
    }

    private fun seededLiteRtReposForQuery(query: String): List<String> {
        val method = Class.forName("com.example.llamadroid.data.repository.LiteRtModelRepositoryKt")
            .getDeclaredMethod("seededLiteRtReposForQuery", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, query) as List<String>
    }

    private fun liveResultComparator(exactRepoId: String?): Comparator<LiteRtCatalogEntry> {
        val method = Class.forName("com.example.llamadroid.data.repository.LiteRtModelRepositoryKt")
            .getDeclaredMethod("liveResultComparator", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, exactRepoId) as Comparator<LiteRtCatalogEntry>
    }

    private fun evaluateKbEmbeddingCompatibility(
        file: File,
        repoId: String?,
        supportsEmbedding: Boolean
    ): LiteRtKbEmbeddingCompatibility {
        val method = LiteRtModelRepository::class.java.getDeclaredMethod(
            "evaluateKbEmbeddingCompatibility",
            File::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(repository(), file, repoId, supportsEmbedding) as LiteRtKbEmbeddingCompatibility
    }

    private fun repository(): LiteRtModelRepository {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, LiteRtModelRepository::class.java) as LiteRtModelRepository
    }
}
