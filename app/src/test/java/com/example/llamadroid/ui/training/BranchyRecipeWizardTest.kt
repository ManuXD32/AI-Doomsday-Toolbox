package com.example.llamadroid.ui.training

import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.branchy.BranchyPlanInput
import com.example.llamadroid.branchy.BranchyRecipes
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.ClusterSelection
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A2 (recipes go through the wizard) and A1 (editor re-seeds after a save, one session per open). */
class BranchyRecipeWizardTest {
    private val recipe = BranchyRecipes.all.first()
    private val idMap = recipe.datasetIds().withIndex().associate { (i, id) -> id to (i + 1).toLong() }
    private val stages = BranchyWizardHelpers.recipeStages(recipe, idMap, listOf("Pretrain", "SFT"))!!
    private val plan = BranchyPlan(fits = true)

    private fun helper(id: String) = BranchyClusterDeviceEntity(
        id = id, name = id, host = "10.0.0.1", port = 7000, role = "train", ramMb = 4096, lastSeen = 0L
    )

    @Test
    fun recipePrefillsWizardAndKeepsPreviousDevices() {
        val previous = BranchyWizardState(
            clusterDeviceIds = listOf("h1"), deviceEdits = mapOf("h1" to ClusterDeviceEdit(ramMb = "2048"))
        )
        val s = BranchyWizardHelpers.recipeWizardState(recipe, stages, "Mini", previous)
        assertEquals(recipe.id, s.recipeId)
        assertEquals(recipe.vocab, s.vocab)
        assertEquals(recipe.targetParams, s.effectiveTarget(), 0.0)
        assertEquals(recipe.defaultContext, s.effectiveContext())
        assertEquals(idMap.values.toSet(), s.weights.keys)
        assertEquals(listOf("h1"), s.clusterDeviceIds)
        assertEquals("Mini", s.runName)
        assertEquals(BranchyWizardStep.DATA, s.step)
        assertNull(BranchyWizardHelpers.nextBlock(s))
    }

    @Test
    fun recipeConfigUsesCustomPathWithStagesClusterAndThreads() {
        val base = BranchyWizardHelpers.recipeWizardState(recipe, stages, "Mini", BranchyWizardState())
        val state = base.copy(
            clusterDeviceIds = listOf("h1", "h2"), clusterMode = "data_parallel", plan = BranchyPlanUi.Ready(plan),
            overrides = mapOf("train.lr" to "0.001")
        )
        val cfg = BranchyWizardHelpers.customConfig(state, BranchyDevice(), 3, listOf(helper("h1"), helper("h2")), 5.0)!!
        assertEquals(recipe.id, cfg.recipeId)
        assertEquals(stages, cfg.stages)
        assertEquals(0, cfg.currentStage)
        assertEquals(stages[0].mix, cfg.mix)
        assertEquals(recipe.totalTokens, cfg.planInput.tokensTarget, 0.0)
        assertEquals(ClusterSelection(listOf("h1", "h2"), "data_parallel"), cfg.cluster)
        assertEquals(3, cfg.threads)
        assertEquals("0.001", cfg.overrides["train.lr"])
        assertEquals("data_parallel", cfg.planInput.mode)
    }

    @Test
    fun customizeLeavesRecipeAndKeepsValues() {
        val s = BranchyWizardHelpers.recipeWizardState(recipe, stages, "Mini", BranchyWizardState())
            .copy(plan = BranchyPlanUi.Ready(plan))
        val custom = BranchyWizardHelpers.customizeRecipe(s)
        assertNull(custom.recipeId)
        assertTrue(custom.recipeStages.isEmpty())
        assertEquals(s.weights, custom.weights)
        assertNotEquals(s.resetKey(), custom.resetKey())
        val cfg = BranchyWizardHelpers.customConfig(custom.copy(plan = BranchyPlanUi.Ready(plan)), BranchyDevice(), 2)!!
        assertNull(cfg.recipeId)
        assertTrue(cfg.stages.isEmpty())
    }

    private fun saved(ids: List<String>, threads: Int) = BranchyRunConfig(
        planInput = BranchyPlanInput(devices = listOf(BranchyDevice(id = "local"))),
        plan = plan, threads = threads, cluster = ClusterSelection(ids, "auto")
    )

    @Test
    fun editSeedFollowsSavedConfigAfterSave() {
        val before = BranchyEditSeed.from(saved(listOf("h1"), 2))!!
        // The edit added a helper; the editor differs from the first seed until the save re-seeds it.
        val after = BranchyEditSeed.from(saved(listOf("h1", "h2"), 2))!!
        assertEquals(listOf("h1", "h2"), after.state.clusterDeviceIds)
        assertNotEquals(before.key, after.key)
        assertEquals(after.key, after.state.planningKey() + after.helperThreads.toSortedMap().toString() + after.checkpointEvery)
        assertNotNull(after.state.plan as? BranchyPlanUi.Ready)
        assertNull(BranchyEditSeed.from(BranchyRunConfig()))
    }

    @Test
    fun eachEditorOpenGetsItsOwnViewModelKey() {
        assertNotEquals(BranchyEditSeed.sessionKey(5L, 1), BranchyEditSeed.sessionKey(5L, 2))
        assertNotEquals(BranchyEditSeed.sessionKey(5L, 1), BranchyEditSeed.sessionKey(6L, 1))
    }
}
