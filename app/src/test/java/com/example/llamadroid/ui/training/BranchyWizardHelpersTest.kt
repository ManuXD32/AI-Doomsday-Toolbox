package com.example.llamadroid.ui.training

import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.branchy.BranchyPlanTrain
import com.example.llamadroid.branchy.BranchyRecipes
import com.example.llamadroid.training.CuratedDatasets
import com.example.llamadroid.training.TrainingDatasetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyWizardHelpersTest {
    private fun row(id: Long, name: String, status: String, source: String = "curated") = TrainingDatasetEntity(
        id = id, name = name, source = source, hfRepo = null, hfConfig = null, hfSplit = null, revision = null,
        licence = "mit", format = "text", columnMapJson = "{}", localPath = "", sizeBytes = 10L, rowCount = null,
        status = status, error = null, createdAt = 0L
    )

    private val plan = BranchyPlan(
        fits = true, train = BranchyPlanTrain(microbatch = 4, optimizer = "adamw", lr = 6e-4, steps = 1000L)
    )

    @Test
    fun normalizedMixSumsToOneAndSkipsZeroWeights() {
        val mix = BranchyWizardHelpers.normalizedMix(mapOf(1L to 1f, 2L to 3f, 3L to 0f))
        assertEquals(setOf(1L, 2L), mix.map { it.datasetId }.toSet())
        assertEquals(1.0, mix.sumOf { it.weight }, 1e-9)
        assertEquals(0.75, mix.first { it.datasetId == 2L }.weight, 1e-9)
    }

    @Test
    fun allZeroWeightsBecomeEqualShares() {
        val mix = BranchyWizardHelpers.normalizedMix(mapOf(1L to 0f, 2L to 0f))
        assertEquals(listOf(0.5, 0.5), mix.map { it.weight })
        assertTrue(BranchyWizardHelpers.normalizedMix(emptyMap()).isEmpty())
    }

    @Test
    fun formatsParamsAndMemory() {
        assertEquals("30M", BranchyWizardHelpers.formatParams(30e6))
        assertEquals("350M", BranchyWizardHelpers.formatParams(350e6))
        assertEquals("1.0B", BranchyWizardHelpers.formatParams(1e9))
        assertEquals("512 MB", BranchyWizardHelpers.formatMb(512.0))
        assertEquals("2.0 GB", BranchyWizardHelpers.formatMb(2048.0))
        assertEquals("16k", BranchyWizardHelpers.formatVocab(16384))
    }

    @Test
    fun overridesDropInvalidAndUnchangedValues() {
        val cleaned = BranchyWizardHelpers.cleanOverrides(
            mapOf(
                "train.microbatch" to "4", // same as plan
                "train.grad_accum" to "abc", // invalid
                "train.optimizer" to "factored",
                "train.recompute" to "everything", // not a choice
                "train.lr" to " 0.001 ",
                "train.steps" to ""
            ),
            plan
        )
        assertEquals(mapOf("train.optimizer" to "factored", "train.lr" to "0.001"), cleaned)
        assertTrue(BranchyWizardHelpers.cleanOverrides(mapOf("train.lr" to "1"), null).isEmpty())
    }

    @Test
    fun customTargetAndContextFallBackWhenInvalid() {
        val s = BranchyWizardState(targetParams = 120e6, context = 2048)
        assertEquals(120e6, s.effectiveTarget(), 0.0)
        assertEquals(250e6, s.copy(customTargetMillions = "250").effectiveTarget(), 0.0)
        assertEquals(120e6, s.copy(customTargetMillions = "").effectiveTarget(), 0.0)
        assertEquals(3000, s.copy(customContext = "3000").effectiveContext())
        assertEquals(2048, s.copy(customContext = "5").effectiveContext())
    }

    @Test
    fun planningKeyIgnoresOverridesAndName() {
        val s = BranchyWizardState()
        assertEquals(s.planningKey(), s.copy(runName = "x", overrides = mapOf("train.lr" to "1")).planningKey())
        assertTrue(s.planningKey() != s.copy(branches = 8).planningKey())
        assertTrue(s.planningKey() != s.copy(customContext = "4096").planningKey())
    }

    @Test
    fun startValidationNamesTheMissingPiece() {
        val s = BranchyWizardState()
        assertEquals(BranchyStartIssue.NO_DATA, BranchyWizardHelpers.validateStart(s))
        val withData = s.copy(weights = mapOf(1L to 1f))
        assertEquals(BranchyStartIssue.NO_PLAN, BranchyWizardHelpers.validateStart(withData))
        assertEquals(
            BranchyStartIssue.NOT_FIT,
            BranchyWizardHelpers.validateStart(withData.copy(plan = BranchyPlanUi.Ready(BranchyPlan(fits = false))))
        )
        assertNull(BranchyWizardHelpers.validateStart(withData.copy(plan = BranchyPlanUi.Ready(plan))))
    }

    @Test
    fun customConfigCarriesPlanMixAndCleanOverrides() {
        val s = BranchyWizardState(
            weights = mapOf(7L to 1f), plan = BranchyPlanUi.Ready(plan),
            overrides = mapOf("train.optimizer" to "factored"), allowStorageOffload = true, vocab = 8192
        )
        val cfg = BranchyWizardHelpers.customConfig(s, BranchyDevice(), 3)
        assertNotNull(cfg)
        assertEquals(listOf(7L), cfg!!.mix.map { it.datasetId })
        assertEquals(8192, cfg.vocab)
        assertEquals(3, cfg.threads)
        assertTrue(cfg.allowStorageOffload)
        assertEquals(mapOf("train.optimizer" to "factored"), cfg.overrides)
        assertEquals(plan, cfg.plan)
        assertNull(BranchyWizardHelpers.customConfig(s.copy(plan = BranchyPlanUi.Idle), BranchyDevice(), 3))
    }

    @Test
    fun planInputFollowsWizardChoices() {
        val s = BranchyWizardState(targetParams = 120e6, context = 2048, weightMode = "q4", mtp = true, branches = 2)
        val input = BranchyWizardHelpers.buildPlanInput(s, BranchyDevice(ramMb = 6000))
        assertEquals(120e6, input.targetParams, 0.0)
        assertEquals(2048, input.context)
        assertEquals("q4", input.weightMode)
        assertEquals(2, input.preferBranches)
        assertEquals(1.2e9, input.tokensTarget, 1.0)
        assertEquals(6000L, input.devices.single().ramMb)
    }

    @Test
    fun threadsDefaultToHalfTheCores() {
        assertEquals(4, BranchyWizardHelpers.effectiveThreads(BranchyWizardState(), 8))
        assertEquals(1, BranchyWizardHelpers.effectiveThreads(BranchyWizardState(), 1))
        assertEquals(6, BranchyWizardHelpers.effectiveThreads(BranchyWizardState(threads = 6), 8))
        assertEquals(8, BranchyWizardHelpers.effectiveThreads(BranchyWizardState(threads = 12), 8))
    }

    @Test
    fun curatedRowsMatchByTitleAndSkipFailed() {
        val title = CuratedDatasets.byId("tinystories")!!.title
        val rows = listOf(
            row(1, title, "failed"),
            row(2, title, "downloading"),
            row(3, title, "ready"),
            row(4, "Other", "ready"),
            row(5, title, "ready", source = "hf")
        )
        val found = BranchyWizardHelpers.findCuratedRows(listOf("tinystories", "wikipedia-en"), rows)
        assertEquals(setOf("tinystories"), found.keys)
        assertEquals(3L, found.getValue("tinystories").id)
    }

    @Test
    fun recipeStagesResolveCuratedIds() {
        val recipe = BranchyRecipes.all.first()
        val ids = recipe.datasetIds()
        val idMap = ids.withIndex().associate { (i, id) -> id to (i + 1).toLong() }
        val stages = BranchyWizardHelpers.recipeStages(recipe, idMap, listOf("A", "B"))
        assertNotNull(stages)
        assertEquals(listOf("A", "B"), stages!!.map { it.name })
        assertEquals(recipe.stages[0].mix.map { idMap.getValue(it.first) }, stages[0].mix.map { it.datasetId })
        assertEquals(recipe.stages[1].lrScale, stages[1].lrScale, 0.0)
        assertNull(BranchyWizardHelpers.recipeStages(recipe, idMap - ids.first(), listOf("A", "B")))
    }

    @Test
    fun downloadFractionAveragesReadyAndDownloading() {
        val rows = mapOf(1L to row(1, "a", "ready"), 2L to row(2, "b", "downloading"), 3L to row(3, "c", "downloading"))
        val progress = mapOf("dataset:2:0" to 0.5f)
        assertEquals(0.75f, BranchyWizardHelpers.downloadFraction(listOf(1L, 2L), rows, progress), 1e-6f)
        assertEquals(0.5f, BranchyWizardHelpers.downloadFraction(listOf(1L, 3L), rows, progress), 1e-6f)
        assertEquals(1f, BranchyWizardHelpers.downloadFraction(emptyList(), rows, progress), 0f)
    }

    @Test
    fun runFractionIsClamped() {
        assertEquals(0f, BranchyWizardHelpers.runFraction(5, 0), 0f)
        assertEquals(0.5f, BranchyWizardHelpers.runFraction(50, 100), 1e-6f)
        assertEquals(1f, BranchyWizardHelpers.runFraction(200, 100), 0f)
    }

    @Test
    fun chartBoundsAndNormalization() {
        assertNull(BranchyChartMath.bounds(listOf(emptyList(), emptyList())))
        val loss = listOf(0 to 4f, 10 to 2f)
        val b = BranchyChartMath.bounds(listOf(loss, listOf(10 to Float.NaN)))!!
        assertEquals(0f, b.xMin, 0f)
        assertEquals(10f, b.xMax, 0f)
        assertTrue(b.yMin < 2f && b.yMax > 4f)
        val n = BranchyChartMath.normalize(loss, b)
        assertEquals(0f, n[0].first, 1e-6f)
        assertEquals(1f, n[1].first, 1e-6f)
        assertTrue(n[0].second > n[1].second)
        val single = BranchyChartMath.bounds(listOf(listOf(5 to 3f)))!!
        assertTrue(single.xMax > single.xMin && single.yMax > single.yMin)
    }
}
