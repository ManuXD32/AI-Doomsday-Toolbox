package com.example.llamadroid.ui.training

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyPhase
import com.example.llamadroid.branchy.BranchyRunEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live dashboard must survive every phase transition of a cluster run (incl. the waiting banner appearing). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class BranchyLiveViewRecomposeTest {
    @get:Rule
    val rule = createComposeRule()

    private val run = BranchyRunEntity(
        id = 7L, name = "demo", recipeId = null, stage = 0, runDir = "/tmp/run", configJson = "{}",
        status = "training", lastStep = 20L, totalSteps = 100L, lastLoss = 1.5, lastValLoss = null,
        tokensSeen = 5_000L, createdAt = 0L, updatedAt = 0L, error = null
    )

    private fun step(n: Long, loss: Double) = BranchyEvent.Step(
        step = n, loss = loss, mtpLoss = null, lr = 0.001, gradNorm = 1.0, tokS = 123.0,
        elapsedS = 10.0, etaS = 90.0, rssMb = 512.0, stepMsByDevice = mapOf("a" to 120.0, "b" to 340.0)
    )

    private fun state(phase: BranchyPhase, devices: List<BranchyLiveDevice>) = BranchyLiveState(
        runId = 7L, phase = phase, stage = 0, stageCount = 2, lastStep = step(20L, 1.5), totalSteps = 100L,
        lossPoints = listOf(0 to 3.0f, 10 to 2.0f, 20 to 1.5f), valPoints = listOf(10 to 2.2f),
        samples = listOf("sample one", "sample two"), clusterMode = "branch_parallel", devices = devices
    )

    @Test
    fun dashboardSurvivesPhaseTransitions() {
        val up = listOf(
            BranchyLiveDevice("a", "Phone A", listOf(0, 1), up = true, stepMs = 120.0),
            BranchyLiveDevice("b", "Phone B", listOf(2), up = true, stepMs = 340.0)
        )
        val waiting = listOf(up[0], up[1].copy(up = false, waiting = true))
        var live by mutableStateOf(state(BranchyPhase.TRAIN, up))
        rule.setContent {
            MaterialTheme {
                BranchyLiveView(run = run, live = live, exportSection = null)
            }
        }
        // Infinite pulse animations never go idle, so drive the clock by hand.
        rule.mainClock.autoAdvance = false
        fun settle() {
            Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeBy(500L)
        }
        settle()
        rule.onNodeWithText(WAITING_TEXT).assertDoesNotExist()

        live = state(BranchyPhase.WAITING_NETWORK, waiting)
        settle()
        rule.onNodeWithText(WAITING_TEXT).assertExists()

        live = state(BranchyPhase.TRAIN, up)
        settle()
        rule.onNodeWithText(WAITING_TEXT).assertDoesNotExist()

        live = state(BranchyPhase.DONE, up)
        settle()
        rule.onNodeWithText(WAITING_TEXT).assertDoesNotExist()
    }

    private companion object {
        const val WAITING_TEXT = "Waiting for network — resumes automatically"
    }
}
