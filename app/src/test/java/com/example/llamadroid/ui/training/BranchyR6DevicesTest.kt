package com.example.llamadroid.ui.training

import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyEvent.BenchPoint
import com.example.llamadroid.branchy.BranchyJson
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.branchy.BranchyPlanInput
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.BranchyRunEdit
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 6 A3: unused-device reasons, prefer_branches, per-helper threads from the bench, checkpoint override. */
class BranchyR6DevicesTest {
    private fun helper(id: String) = BranchyClusterDeviceEntity(
        id = id, name = id, host = "10.0.0.1", port = 7000, role = "train", ramMb = 4096, lastSeen = 0L
    )

    @Test
    fun unusedAndFallbackWarningAreParsed() {
        val json = """{"fits":true,"mode":"branch_parallel","unused":[{"id":"h2","reason":"no_branch_left"},
            {"id":"h3","reason":"ram"}],"warnings":["Data-parallel was requested but the plan uses branch-parallel: no fit"]}"""
        val plan = BranchyJson.json.decodeFromString(BranchyPlan.serializer(), json)
        assertEquals("no_branch_left", plan.unusedReason("h2"))
        assertEquals("ram", plan.unusedReason("h3"))
        assertNull(plan.unusedReason("h1"))
        assertTrue(plan.dpFallbackWarning!!.startsWith("Data-parallel was requested"))
        assertTrue(BranchyJson.json.decodeFromString(BranchyPlan.serializer(), """{"fits":true}""").unused.isEmpty())
    }

    @Test
    fun preferBranchesCoversEveryDeviceAndNeverDropsBelowFour() {
        val state = BranchyWizardState()
        val device = BranchyDevice("local")
        fun input(n: Int) = BranchyWizardHelpers.buildPlanInput(state, device, (1..n).map { helper("h$it") })
        assertEquals(4, input(0).preferBranches)
        assertEquals(4, input(2).preferBranches)
        assertEquals(6, input(5).preferBranches)
        assertTrue(BranchyJson.encodePlanInput(input(5)).contains("\"prefer_branches\":6"))
    }

    @Test
    fun benchResultsSetHelperThreads() {
        val ok = BenchCurve("h1", "H1", listOf(BenchPoint(2, 10.0), BenchPoint(4, 19.0)), best = 4, gflops = 19.0, applied = true)
        val failed = BenchCurve("h2", "H2", emptyList(), best = 0, gflops = 0.0, error = "down")
        var threads = BranchyBenchMath.withHelperThreads(emptyMap(), ok)
        threads = BranchyBenchMath.withHelperThreads(threads, failed)
        assertEquals(mapOf("h1" to 4), threads)
        val state = BranchyWizardState(
            plan = BranchyPlanUi.Ready(BranchyPlan(fits = true)), clusterDeviceIds = listOf("h1", "h2"),
            helperThreads = threads + ("gone" to 3)
        )
        val config = BranchyWizardHelpers.customConfig(state, BranchyDevice("local"), 2, listOf(helper("h1"), helper("h2")))!!
        assertEquals(mapOf("h1" to 4), config.helperThreads)
    }

    @Test
    fun checkpointOverrideRoundTrips() {
        val plan = BranchyPlan(fits = true)
        val set = BranchyWizardHelpers.withCheckpointEvery(emptyMap(), "500")
        assertEquals("500", BranchyWizardHelpers.cleanOverrides(set, plan)["train.checkpoint_every"])
        // The planner default is not stored; clearing the field removes the override.
        val same = BranchyWizardHelpers.withCheckpointEvery(emptyMap(), plan.train.checkpointEvery.toString())
        assertTrue(BranchyWizardHelpers.cleanOverrides(same, plan).isEmpty())
        assertTrue(BranchyWizardHelpers.withCheckpointEvery(set, "").isEmpty())
        val state = BranchyWizardState(plan = BranchyPlanUi.Ready(plan), overrides = set)
        val config = BranchyWizardHelpers.customConfig(state, BranchyDevice("local"), 2)!!
        assertEquals("500", BranchyRunConfig.decode(config.encode())!!.overrides["train.checkpoint_every"])
    }

    @Test
    fun editorSavesCheckpointOverrideAndKeepsOthers() {
        val old = BranchyRunConfig(plan = BranchyPlan(fits = true), overrides = mapOf("train.lr" to "0.001"))
        val input = BranchyPlanInput()
        val set = BranchyRunEdit.applyEdit(old, emptyList(), "auto", BranchyPlan(fits = true), input, 2, emptyMap(), "300")
        assertEquals(mapOf("train.lr" to "0.001", "train.checkpoint_every" to "300"), set.overrides)
        val kept = BranchyRunEdit.applyEdit(set, emptyList(), "auto", BranchyPlan(fits = true), input, 2)
        assertEquals(set.overrides, kept.overrides)
        val cleared = BranchyRunEdit.applyEdit(set, emptyList(), "auto", BranchyPlan(fits = true), input, 2, emptyMap(), "")
        assertEquals(mapOf("train.lr" to "0.001"), cleared.overrides)
    }
}
