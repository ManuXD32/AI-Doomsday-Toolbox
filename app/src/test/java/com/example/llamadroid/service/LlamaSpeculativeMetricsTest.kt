package com.example.llamadroid.service

import com.example.llamadroid.data.dao.LlamaSpeculativeRunDao
import com.example.llamadroid.data.model.LlamaSpeculativeRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LlamaSpeculativeMetricsTest {
    @Test
    fun `collector parses draft acceptance ratio with counters`() {
        val collector = LlamaSpeculativeMetricsCollector()

        collector.onLogLine("slot 0: draft acceptance = 0.57576 (  171 accepted /   297 generated), mean len =  2.14")
        collector.onLogLine("prompt eval time = 100.00 ms / 50 tokens (500.00 tokens per second)")
        collector.onLogLine("eval time = 200.00 ms / 40 runs (200.00 tokens per second)")
        val metrics = collector.onLogLine("total time = 300.00 ms")

        assertNotNull(metrics)
        assertEquals(57.57, metrics?.acceptanceRate ?: -1.0, 0.01)
        assertEquals(500.0, metrics?.promptTokensPerSecond ?: -1.0, 0.01)
        assertEquals(200.0, metrics?.generationTokensPerSecond ?: -1.0, 0.01)
    }

    @Test
    fun `collector accumulates live metrics across prompts`() {
        val collector = LlamaSpeculativeMetricsCollector()

        collector.onLogLine("slot 0: draft acceptance = 1.00000 (   10 accepted /    10 generated), mean len =  2.00")
        collector.onLogLine("prompt eval time = 100.00 ms / 50 tokens (500.00 tokens per second)")
        collector.onLogLine("eval time = 200.00 ms / 40 runs (200.00 tokens per second)")
        collector.onLogLine("total time = 300.00 ms")

        collector.onLogLine("slot 0: draft acceptance = 0.50000 (   10 accepted /    20 generated), mean len =  1.50")
        collector.onLogLine("prompt eval time = 300.00 ms / 60 tokens (200.00 tokens per second)")
        collector.onLogLine("eval time = 300.00 ms / 30 runs (100.00 tokens per second)")
        val metrics = collector.onLogLine("total time = 600.00 ms")

        assertNotNull(metrics)
        assertEquals(66.67, metrics?.acceptanceRate ?: -1.0, 0.01)
        assertEquals(275.0, metrics?.promptTokensPerSecond ?: -1.0, 0.01)
        assertEquals(140.0, metrics?.generationTokensPerSecond ?: -1.0, 0.01)
    }

    @Test
    fun `collector can use speculative statistics token counters as fallback`() {
        val collector = LlamaSpeculativeMetricsCollector()

        collector.onLogLine("statistics     ngram_simple: #calls = 15, #gen drafts = 5, #acc drafts = 5, #gen tokens = 187, #acc tokens = 73")
        collector.onLogLine("eval time = 200.00 ms / 40 runs (200.00 tokens per second)")
        val metrics = collector.onLogLine("total time = 300.00 ms")

        assertNotNull(metrics)
        assertEquals(39.04, metrics?.acceptanceRate ?: -1.0, 0.01)
    }

    @Test
    fun `store prunes only unnamed unsaved runs beyond newest ten`() = runBlocking {
        val dao = FakeSpeculativeRunDao()
        repeat(12) { index ->
            dao.insertRun(
                LlamaSpeculativeRunEntity(
                    name = null,
                    savedForever = false,
                    createdAt = index.toLong(),
                    updatedAt = index.toLong(),
                    modelPath = "/models/$index.gguf",
                    modelName = "$index.gguf",
                    speculativeMode = LlamaSpeculativeMode.DRAFT_SIMPLE.flagValue
                )
            )
        }
        dao.insertRun(
            LlamaSpeculativeRunEntity(
                name = "keeper",
                savedForever = false,
                createdAt = 100,
                updatedAt = 100,
                modelPath = "/models/keeper.gguf",
                modelName = "keeper.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_DFLASH.flagValue
            )
        )

        val runId = LlamaSpeculativeRunStore.createRunAndPrune(
            dao = dao,
            modelPath = "/models/new.gguf",
            speculativeMode = LlamaSpeculativeMode.DRAFT_DFLASH,
            draftModelPath = "/models/draft.gguf"
        )
        LlamaSpeculativeRunStore.recordPromptMetricsAndPrune(
            dao = dao,
            runId = runId,
            metrics = LlamaSpeculativeRunMetrics(
                acceptanceRate = 75.0,
                promptTokensPerSecond = 12.0,
                generationTokensPerSecond = 34.0,
                rawMetrics = "raw"
            )
        )

        val unnamedUnsaved = dao.runs.values
            .filter { !it.savedForever && it.name.isNullOrBlank() }
            .sortedByDescending { it.createdAt }
        assertEquals(10, unnamedUnsaved.size)
        assertEquals("keeper", dao.runs.values.single { it.name == "keeper" }.name)
    }

    @Test
    fun `store updates one run with live aggregate snapshots`() = runBlocking {
        val dao = FakeSpeculativeRunDao()
        val runId = LlamaSpeculativeRunStore.createRunAndPrune(
            dao = dao,
            modelPath = "/models/main.gguf",
            speculativeMode = LlamaSpeculativeMode.DRAFT_DFLASH,
            draftModelPath = "/models/draft.gguf"
        )

        LlamaSpeculativeRunStore.recordPromptMetricsAndPrune(
            dao,
            runId,
            LlamaSpeculativeRunMetrics(acceptanceRate = 50.0, promptTokensPerSecond = 10.0, generationTokensPerSecond = 20.0, rawMetrics = "first")
        )
        LlamaSpeculativeRunStore.recordPromptMetricsAndPrune(
            dao,
            runId,
            LlamaSpeculativeRunMetrics(acceptanceRate = 100.0, promptTokensPerSecond = 30.0, generationTokensPerSecond = 40.0, rawMetrics = "second")
        )

        val run = dao.runs.getValue(runId)
        assertEquals(2, run.sampleCount)
        assertEquals(100.0, run.acceptanceRate ?: -1.0, 0.01)
        assertEquals(30.0, run.promptTokensPerSecond ?: -1.0, 0.01)
        assertEquals(40.0, run.generationTokensPerSecond ?: -1.0, 0.01)
        assertEquals(1, dao.runs.count { it.value.modelName == "main.gguf" })
    }
}

private class FakeSpeculativeRunDao : LlamaSpeculativeRunDao {
    val runs = linkedMapOf<Long, LlamaSpeculativeRunEntity>()
    private var nextId = 1L

    override fun observeRuns(): Flow<List<LlamaSpeculativeRunEntity>> =
        flowOf(
            runs.values.sortedWith(
                compareByDescending<LlamaSpeculativeRunEntity> { if (it.savedForever) 1 else 0 }
                    .thenByDescending { it.createdAt }
            )
        )

    override suspend fun getRunById(id: Long): LlamaSpeculativeRunEntity? = runs[id]

    override suspend fun insertRun(run: LlamaSpeculativeRunEntity): Long {
        val id = run.id.takeIf { it != 0L } ?: nextId++
        runs[id] = run.copy(id = id)
        return id
    }

    override suspend fun updateRunMetrics(
        id: Long,
        sampleCount: Int,
        acceptanceRate: Double?,
        promptTokensPerSecond: Double?,
        generationTokensPerSecond: Double?,
        rawMetrics: String,
        updatedAt: Long
    ) {
        runs[id] = runs.getValue(id).copy(
            sampleCount = sampleCount,
            acceptanceRate = acceptanceRate,
            promptTokensPerSecond = promptTokensPerSecond,
            generationTokensPerSecond = generationTokensPerSecond,
            rawMetrics = rawMetrics,
            updatedAt = updatedAt
        )
    }

    override suspend fun renameRun(id: Long, name: String?, updatedAt: Long) {
        runs[id] = runs.getValue(id).copy(name = name, updatedAt = updatedAt)
    }

    override suspend fun setSavedForever(id: Long, savedForever: Boolean, updatedAt: Long) {
        runs[id] = runs.getValue(id).copy(savedForever = savedForever, updatedAt = updatedAt)
    }

    override suspend fun deleteRun(id: Long) {
        runs.remove(id)
    }

    override suspend fun getPrunableRunIdsNewestFirst(): List<Long> =
        runs.values
            .filter { !it.savedForever && it.name.isNullOrBlank() }
            .sortedByDescending { it.createdAt }
            .map { it.id }

    override suspend fun deleteRuns(ids: List<Long>) {
        ids.forEach(runs::remove)
    }
}
