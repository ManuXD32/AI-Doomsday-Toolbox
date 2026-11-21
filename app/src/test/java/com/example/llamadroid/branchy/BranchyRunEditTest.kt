package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BranchyRunEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun plan(mode: String, vararg placement: Pair<String, List<Int>>, layout: BranchyModelLayout = BranchyModelLayout()) =
        BranchyPlan(
            fits = true, layout = layout, mode = mode,
            placement = placement.map { (d, b) ->
                BranchyPlacement(d, if (d == "local") "coordinator" else "worker", b)
            }
        )

    private val bp = plan("branch_parallel", "local" to listOf(0), "h1" to listOf(1, 2), "h2" to listOf(3))

    @Test
    fun samePlacementInAnyOrderIsNotChanged() {
        val same = plan("branch_parallel", "h2" to listOf(3), "local" to listOf(0), "h1" to listOf(2, 1))
        assertFalse(BranchyRunEdit.placementChanged(bp, same))
        assertFalse(BranchyRunEdit.needsConsolidate(bp, same))
    }

    @Test
    fun movedBranchOrRemovedHelperIsChanged() {
        val moved = plan("branch_parallel", "local" to listOf(0, 3), "h1" to listOf(1, 2))
        assertTrue(BranchyRunEdit.placementChanged(bp, moved))
        assertTrue(BranchyRunEdit.needsConsolidate(bp, moved))
        val noHelpers = plan("single", "local" to listOf(0, 1, 2, 3))
        assertTrue(BranchyRunEdit.needsConsolidate(bp, noHelpers))
    }

    @Test
    fun dataParallelOrSingleOldRunNeverConsolidates() {
        val dp = plan("data_parallel", "local" to listOf(0, 1, 2, 3), "h1" to listOf(0, 1, 2, 3))
        val other = plan("data_parallel", "local" to listOf(0, 1, 2, 3))
        assertTrue(BranchyRunEdit.placementChanged(dp, other))
        assertFalse(BranchyRunEdit.needsConsolidate(dp, other))
        assertFalse(BranchyRunEdit.needsConsolidate(plan("single", "local" to listOf(0, 1, 2, 3)), bp))
        assertFalse(BranchyRunEdit.needsConsolidate(null, bp))
    }

    @Test
    fun layoutMustStayTheSame() {
        assertTrue(BranchyRunEdit.layoutUnchanged(bp, bp.copy(mode = "single")))
        assertFalse(BranchyRunEdit.layoutUnchanged(bp, plan("single", layout = BranchyModelLayout(branches = 8))))
    }

    @Test
    fun consolidateTargetNeedsClusterJsonAndCheckpoint() {
        val run = tmp.newFolder("run")
        assertNull(BranchyRunEdit.consolidateTarget(run, 0))
        File(run, "cluster.json").writeText("{}")
        assertNull(BranchyRunEdit.consolidateTarget(run, 0))
        File(run, "checkpoints").mkdirs()
        File(run, "checkpoints/latest").writeText("step_5")
        assertEquals(run, BranchyRunEdit.consolidateTarget(run, 0)?.stageDir)
        assertNull(BranchyRunEdit.consolidateTarget(run, 1))
        val stage2 = File(run, "stage2").apply { mkdirs() }
        File(stage2, "cluster.json").writeText("{}")
        File(stage2, "checkpoints").mkdirs()
        File(stage2, "checkpoints/latest").writeText("step_1")
        assertEquals(File(stage2, "cluster.json"), BranchyRunEdit.consolidateTarget(run, 1)?.clusterJson)
    }

    @Test
    fun consolidateArgvCarriesTokenFileNeverTheToken() {
        val argv = BranchyCommandBuilder.consolidate("/bin/branchy", "/r", "/r/cluster.json", "/data/token")
        assertEquals(
            listOf("/bin/branchy", "consolidate", "--run", "/r", "--cluster", "/r/cluster.json", "--token-file", "/data/token"),
            argv
        )
        assertFalse(argv.any { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun planInputOmitsFixedLayoutUnlessSet() {
        val plain = BranchyJson.encodePlanInput(BranchyPlanInput())
        assertFalse(plain.contains("fixed_layout"))
        val layout = BranchyModelLayout(branches = 2, dModel = 384)
        val fixed = BranchyJson.encodePlanInput(BranchyPlanInput(fixedLayout = layout))
        assertTrue(fixed.contains("\"fixed_layout\""))
        val back = BranchyJson.json.decodeFromString(BranchyPlanInput.serializer(), fixed)
        assertEquals(layout, back.fixedLayout)
        // The stored config form still round-trips both ways.
        val stored = BranchyJson.json.encodeToString(BranchyPlanInput.serializer(), BranchyPlanInput())
        assertNull(BranchyJson.json.decodeFromString(BranchyPlanInput.serializer(), stored).fixedLayout)
    }

    @Test
    fun planInputUsesSavedLayoutAndDevices() {
        val base = BranchyPlanInput(targetParams = 120e6, context = 2048, mode = "auto")
        val local = BranchyDevice(id = "local", threads = 6)
        val helper = BranchyDevice(id = "h1", threads = 3)
        val input = BranchyRunEdit.planInput(base, bp.layout, "branch_parallel", listOf(local, helper))
        assertEquals("branch_parallel", input.mode)
        assertEquals(bp.layout, input.fixedLayout)
        assertEquals(120e6, input.targetParams, 0.0)
        assertEquals(listOf(6, 3), input.devices.map { it.threads })
        assertEquals("auto", BranchyRunEdit.planInput(base, bp.layout, "branch_parallel", listOf(local)).mode)
    }

    @Test
    fun applyEditUpdatesSelectionPlanAndThreads() {
        val old = BranchyRunConfig(
            plan = bp, threads = 2, epochs = 3.0, overrides = mapOf("train.lr" to "0.001"),
            planInput = BranchyPlanInput(devices = listOf(BranchyDevice("local", threads = 2), BranchyDevice("h1", threads = 5))),
            cluster = ClusterSelection(listOf("h1", "h2"), "auto")
        )
        val newPlan = plan("branch_parallel", "local" to listOf(0, 3), "h1" to listOf(1, 2))
        val input = BranchyPlanInput(
            devices = listOf(BranchyDevice("local", threads = 4), BranchyDevice("h1", threads = 8)),
            fixedLayout = newPlan.layout
        )
        val out = BranchyRunEdit.applyEdit(old, listOf("h1"), "branch_parallel", newPlan, input, 4, mapOf("h1" to 8, "h2" to 3))
        assertEquals(4, out.threads)
        assertEquals(newPlan.copy(train = bp.train), out.plan)  // placement from the new plan, train knobs kept
        assertEquals(ClusterSelection(listOf("h1"), "branch_parallel"), out.cluster)
        assertNull(out.planInput.fixedLayout)
        assertEquals(mapOf("h1" to 8), BranchyRunEdit.savedHelperThreads(out))
        assertEquals(3.0, out.epochs, 0.0)
        assertEquals(old.overrides, out.overrides)
        assertNotNull(BranchyRunConfig.decode(out.encode()))
        assertNull(BranchyRunEdit.applyEdit(old, emptyList(), "auto", newPlan, input, 4).cluster)
    }
}
