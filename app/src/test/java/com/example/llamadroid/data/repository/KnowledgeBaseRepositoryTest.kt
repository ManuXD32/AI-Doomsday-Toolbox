package com.example.llamadroid.data.repository

import com.example.llamadroid.data.db.KnowledgeChunkEntity
import com.example.llamadroid.data.db.KnowledgeBaseSourceType
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.repository.KnowledgeBaseRepository.Companion.norm
import com.example.llamadroid.data.repository.KnowledgeBaseRepository.Companion.toBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseRepositoryTest {
    @Test
    fun selectedKnowledgeBaseCsv_roundTripsDistinctPositiveIds() {
        val ids = KnowledgeBaseRepository.selectedKnowledgeBaseIdsFromCsv("3, 2, -1, nope, 3")
        assertEquals(listOf(3L, 2L), ids)
        assertEquals("3,2", KnowledgeBaseRepository.selectedKnowledgeBaseIdsToCsv(ids))
    }

    @Test
    fun chunkText_overlapsLongDocuments() {
        val text = (1..400).joinToString(" ") { "word$it" }
        val chunks = KnowledgeBaseRepository.chunkText(text, chunkSize = 400)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 470 })
    }

    @Test
    fun chunkText_prefersSentenceBoundarySlightlyAfterSoftLimit() {
        val firstSentence = "a".repeat(390) + "."
        val secondSentence = "b".repeat(120) + "."
        val chunks = KnowledgeBaseRepository.chunkText(
            text = "$firstSentence $secondSentence",
            chunkSize = 360,
            embeddingBatchSize = 1024
        )

        assertTrue(chunks.first().endsWith("."))
        assertTrue(chunks.first().length > 360)
    }

    @Test
    fun chunkText_constrainsTokenDenseDocuments() {
        val text = ".".repeat(2_400)
        val chunkSize = 1_000
        val batchSize = 512
        val tokenBudget = SettingsRepository.knowledgeEmbeddingTokenBudgetForBatchSize(batchSize)

        val chunks = KnowledgeBaseRepository.chunkText(
            text = text,
            chunkSize = chunkSize,
            embeddingBatchSize = batchSize
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { KnowledgeBaseRepository.estimateEmbeddingTokens(it) <= tokenBudget })
    }

    @Test
    fun embeddingBatchSize_scalesWithKnowledgeChunkSize() {
        assertEquals(512, SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(400))
        assertEquals(1024, SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(1_000))
        assertEquals(2048, SettingsRepository.knowledgeEmbeddingBatchSizeForChunkSize(2_400))
    }

    @Test
    fun chunkCitationMarkdown_linksToChunkReaderUri() {
        assertEquals(
            "[Manual.pdf chunk 12](kb://chunk/123)",
            KnowledgeBaseRepository.chunkCitationMarkdown("Manual.pdf", chunkIndex = 11, chunkId = 123)
        )
        assertEquals(
            "[Manual de diagnostico y terapeutica medicas H 12 OCTUBRE 2022 (1).pdf chunk 12](kb://chunk/123)",
            KnowledgeBaseRepository.chunkCitationMarkdown(
                "Manual de diagnostico y terapeutica medicas H 12 OCTUBRE 2022 (1).pdf",
                chunkIndex = 11,
                chunkId = 123
            )
        )
        assertEquals(
            "[Manual \\[Draft\\] chunk 12](kb://chunk/123)",
            KnowledgeBaseRepository.chunkCitationMarkdown("Manual [Draft]", chunkIndex = 11, chunkId = 123)
        )
    }

    @Test
    fun webSourceType_isNormalVisibleKnowledgeSourceType() {
        assertEquals("web", KnowledgeBaseSourceType.WEB)
    }

    @Test
    fun lexicalScore_ranksSharedTerms() {
        val query = KnowledgeBaseRepository.tokenize("garden medicine tomato")
        val score = KnowledgeBaseRepository.lexicalScore(query, "Tomato care in a garden bed")
        assertTrue(score > 0f)
    }

    @Test
    fun vectorScore_usesCosineSimilarity() {
        val embedding = listOf(1f, 0f, 0f)
        val chunk = KnowledgeChunkEntity(
            knowledgeBaseId = 1,
            sourceId = 1,
            chunkIndex = 0,
            text = "alpha",
            embedding = embedding.toBlob(),
            embeddingNorm = embedding.norm()
        )
        assertEquals(1f, KnowledgeBaseRepository.vectorScore(embedding, embedding.norm(), chunk) ?: 0f, 0.0001f)
    }

    @Test
    fun searchRanking_usesQueryEmbeddingVectorAgainstStoredChunkVectors() {
        val queryEmbedding = listOf(0f, 1f, 0f)
        val matchingVector = listOf(0f, 1f, 0f)
        val otherVector = listOf(1f, 0f, 0f)
        val lexicalDistractor = KnowledgeChunkEntity(
            id = 1,
            knowledgeBaseId = 1,
            sourceId = 1,
            chunkIndex = 0,
            text = "query words appear here but the vector points elsewhere",
            embedding = otherVector.toBlob(),
            embeddingNorm = otherVector.norm()
        )
        val vectorMatch = KnowledgeChunkEntity(
            id = 2,
            knowledgeBaseId = 1,
            sourceId = 1,
            chunkIndex = 1,
            text = "different wording",
            embedding = matchingVector.toBlob(),
            embeddingNorm = matchingVector.norm()
        )

        val ranked = KnowledgeBaseRepository.rankChunksByQueryEmbedding(
            queryEmbedding = queryEmbedding,
            chunks = listOf(lexicalDistractor, vectorMatch),
            maxResults = 2
        )

        assertEquals(2L, ranked.first().first.id)
        assertEquals(1f, ranked.first().second, 0.0001f)
    }

    @Test
    fun vectorScore_ignoresChunksWithoutVectorPayloads() {
        val query = listOf(1f, 0f, 0f)
        val chunk = KnowledgeChunkEntity(
            knowledgeBaseId = 1,
            sourceId = 1,
            chunkIndex = 0,
            text = "raw text only"
        )

        assertEquals(null, KnowledgeBaseRepository.vectorScore(query, query.norm(), chunk))
    }

    @Test
    fun embeddingConfigHash_changesWhenBackendOrModelChanges() {
        val local = KnowledgeEmbeddingConfig(
            backend = SettingsRepository.KB_EMBED_BACKEND_LOCAL,
            label = "local",
            localModelPath = "/models/embed-a.gguf",
            url = "http://127.0.0.1:8081",
            remoteModel = null
        )
        val otherLocalModel = local.copy(localModelPath = "/models/embed-b.gguf")
        val remote = local.copy(
            backend = SettingsRepository.KB_EMBED_BACKEND_OLLAMA,
            localModelPath = null,
            url = "http://127.0.0.1:11434",
            remoteModel = "nomic-embed-text"
        )
        assertTrue(local.isConfigured)
        assertTrue(remote.isConfigured)
        assertTrue(local.hash != otherLocalModel.hash)
        assertTrue(local.hash != remote.hash)
    }

    @Test
    fun normalizeKnowledgeEmbeddingBackend_fallsBackFromLiteRtToLocal() {
        assertEquals(
            SettingsRepository.KB_EMBED_BACKEND_LOCAL,
            SettingsRepository.normalizeKnowledgeEmbeddingBackend(SettingsRepository.KB_EMBED_BACKEND_LITERT)
        )
        assertEquals(
            SettingsRepository.KB_EMBED_BACKEND_LOCAL,
            SettingsRepository.normalizeKnowledgeEmbeddingBackend("litertlm")
        )
    }

    @Test
    fun embeddingResponseParser_acceptsCommonLlamaServerShapes() {
        assertEquals(
            listOf(1f, 2f),
            KnowledgeEmbeddingService.parseEmbeddingResponse("""{"embedding":[1,2]}""")
        )
        assertEquals(
            listOf(1f, 2f),
            KnowledgeEmbeddingService.parseEmbeddingResponse("""{"embedding":[[1,2]]}""")
        )
        assertEquals(
            listOf(1f, 2f),
            KnowledgeEmbeddingService.parseEmbeddingResponse("""{"data":[{"embedding":[1,2]}]}""")
        )
    }
}
