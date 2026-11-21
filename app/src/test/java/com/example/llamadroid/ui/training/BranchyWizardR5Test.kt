package com.example.llamadroid.ui.training

import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.training.TrainingDatasetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyWizardR5Test {
    private fun row(id: Long, bytes: Long) = TrainingDatasetEntity(
        id = id, name = "d$id", source = "file", hfRepo = null, hfConfig = null, hfSplit = null, revision = null,
        licence = "mit", format = "text", columnMapJson = "{}", localPath = "", sizeBytes = bytes, rowCount = null,
        status = "ready", error = null, createdAt = 0L
    )

    private val fitting = BranchyPlanUi.Ready(BranchyPlan(fits = true))

    @Test
    fun nextIsGatedPerStep() {
        val data = BranchyWizardState(step = BranchyWizardStep.DATA)
        assertEquals(BranchyStartIssue.NO_DATA, BranchyWizardHelpers.nextBlock(data))
        assertNull(BranchyWizardHelpers.nextBlock(data.copy(weights = mapOf(1L to 1f))))

        val model = BranchyWizardState(step = BranchyWizardStep.MODEL, weights = mapOf(1L to 1f))
        assertEquals(BranchyStartIssue.NO_PLAN, BranchyWizardHelpers.nextBlock(model))
        assertEquals(BranchyStartIssue.NO_PLAN, BranchyWizardHelpers.nextBlock(model.copy(plan = BranchyPlanUi.Running)))
        assertEquals(
            BranchyStartIssue.NOT_FIT,
            BranchyWizardHelpers.nextBlock(model.copy(plan = BranchyPlanUi.Ready(BranchyPlan(fits = false))))
        )
        assertEquals(BranchyStartIssue.STALE_PLAN, BranchyWizardHelpers.nextBlock(model.copy(plan = fitting, planStale = true)))
        assertNull(BranchyWizardHelpers.nextBlock(model.copy(plan = fitting)))

        // Devices never blocks; Start keeps its own checks (including stale).
        assertNull(BranchyWizardHelpers.nextBlock(BranchyWizardState(step = BranchyWizardStep.DEVICES)))
        val train = model.copy(step = BranchyWizardStep.TRAIN, plan = fitting, planStale = true)
        assertEquals(BranchyStartIssue.STALE_PLAN, BranchyWizardHelpers.validateStart(train))
        assertNull(BranchyWizardHelpers.validateStart(train.copy(planStale = false)))
    }

    @Test
    fun epochsCapTheTokenTarget() {
        // 30M params -> default max(2e8, 10 x 30M) = 3e8; 1 epoch of 1e8 tokens caps it.
        assertEquals(1e8, BranchyWizardHelpers.tokensTarget(30e6, 1.0, 1e8), 1.0)
        // 10 epochs of 1e8 is above the default, so the default stays.
        assertEquals(3e8, BranchyWizardHelpers.tokensTarget(30e6, 10.0, 1e8), 1.0)
        // 120M params -> 1.2e9 default; 0.5 epoch of 1e9.
        assertEquals(5e8, BranchyWizardHelpers.tokensTarget(120e6, 0.5, 1e9), 1.0)
        // Unknown data or epochs <= 0 = no cap.
        assertEquals(3e8, BranchyWizardHelpers.tokensTarget(30e6, 1.0, 0.0), 1.0)
        assertEquals(3e8, BranchyWizardHelpers.tokensTarget(30e6, 0.0, 1e8), 1.0)
    }

    @Test
    fun epochsFieldFallsBackToOneOutsideRange() {
        assertEquals(1.0, BranchyWizardState().effectiveEpochs(), 0.0)
        assertEquals(2.5, BranchyWizardState(epochs = "2.5").effectiveEpochs(), 0.0)
        assertEquals(1.0, BranchyWizardState(epochs = "").effectiveEpochs(), 0.0)
        assertEquals(1.0, BranchyWizardState(epochs = "0.05").effectiveEpochs(), 0.0)
        assertEquals(1.0, BranchyWizardState(epochs = "101").effectiveEpochs(), 0.0)
    }

    @Test
    fun dataEstimateUsesPreparedCountsElseBytesOverFour() {
        val rows = listOf(row(1, 4_000), row(2, 8_000), row(3, 100))
        val est = BranchyWizardHelpers.estimatedDataTokens(listOf(1L, 2L), rows)
        assertEquals(3_000.0, est.tokens, 0.0)
        assertTrue(est.approximate)
        val known = BranchyWizardHelpers.estimatedDataTokens(listOf(1L, 2L), rows, mapOf(1L to 500L, 2L to 700L))
        assertEquals(1_200.0, known.tokens, 0.0)
        assertFalse(known.approximate)
    }

    @Test
    fun planInputAndConfigCarryEpochs() {
        val s = BranchyWizardState(weights = mapOf(1L to 1f), epochs = "2", plan = fitting)
        val input = BranchyWizardHelpers.buildPlanInput(s, BranchyDevice(), dataTokens = 5e7)
        assertEquals(1e8, input.tokensTarget, 1.0)
        val cfg = BranchyWizardHelpers.customConfig(s, BranchyDevice(), 2, dataTokens = 5e7)!!
        assertEquals(2.0, cfg.epochs, 0.0)
        assertEquals(1e8, cfg.planInput.tokensTarget, 1.0)
        assertEquals(0.0, BranchyRunConfig().epochs, 0.0)
    }

    @Test
    fun editingDeviceFieldsOrEpochsKeepsTheResetKey() {
        val s = BranchyWizardState()
        val edited = s.copy(deviceEdits = mapOf("a" to ClusterDeviceEdit(ramMb = "1500")), epochs = "3")
        assertEquals(s.resetKey(), edited.resetKey())
        assertTrue(s.planningKey() != edited.planningKey())
        assertTrue(s.resetKey() != s.copy(branches = 8).resetKey())
    }

    @Test
    fun fieldSyncIgnoresStaleEchoesOfTypedText() {
        val t = FieldSyncTracker()
        // Typing "1" then "15": the view model's old "1" arrives after the field already shows "15".
        t.sent("1")
        t.sent("15")
        assertNull(t.adopt("1", "15"))
        assertNull(t.adopt("15", "15"))
        // An external change (bench result) is adopted.
        assertEquals("42.0", t.adopt("42.0", "15"))
        // After syncing, a previously typed value that comes back from outside is adopted too.
        assertEquals("1", t.adopt("1", "15"))
    }
}
