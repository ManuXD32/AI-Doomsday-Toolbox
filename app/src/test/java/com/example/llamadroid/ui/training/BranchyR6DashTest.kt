package com.example.llamadroid.ui.training

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.example.llamadroid.branchy.BranchyDpAdviceState
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyPhase
import com.example.llamadroid.branchy.BranchyRoundStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class BranchyR6DashTest {
    @get:Rule
    val rule = createComposeRule()

    private fun str(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun viewportZoomIsClampedAndKeepsFocus() {
        val v = BranchyChartViewport.Full.zoom(1000f, 0.5f)
        assertEquals(BranchyChartViewport.MIN_SPAN, v.span, 1e-4f)
        assertEquals(0.5f, (v.lo + v.hi) / 2f, 1e-4f)
        assertEquals(BranchyChartViewport.Full, BranchyChartViewport.Full.zoom(0.1f))
        val half = BranchyChartViewport.Full.zoom(2f, 0f)
        assertEquals(0f, half.lo, 1e-4f)
        assertEquals(0.5f, half.hi, 1e-4f)
    }

    @Test
    fun viewportPanIsClampedAndResets() {
        val zoomed = BranchyChartViewport.Full.zoom(4f, 0.5f)
        val right = zoomed.pan(100f)
        assertEquals(1f, right.hi, 1e-4f)
        assertEquals(zoomed.span, right.span, 1e-4f)
        val left = zoomed.pan(-100f)
        assertEquals(0f, left.lo, 1e-4f)
        assertTrue(!zoomed.isFull)
        assertEquals(BranchyChartViewport.Full, zoomed.reset())
        assertEquals(BranchyChartViewport.Full, BranchyChartViewport.Full.pan(0.5f))
    }

    @Test
    fun loadMathStacksRoundsAndFillsGaps() {
        val stack = BranchyLoadMath.stack(
            mapOf(
                "main" to listOf(BranchyRoundStat(1, 4, 1.0, 0.0), BranchyRoundStat(2, 3, 1.0, 0.0)),
                "a" to listOf(BranchyRoundStat(2, 5, 1.0, 0.0))
            )
        )
        assertEquals(listOf(1L, 2L), stack.rounds)
        assertEquals(listOf(0L, 5L), stack.series.first { it.first == "a" }.second)
        assertEquals(0..19, BranchyLoadMath.visible(20, BranchyChartViewport.Full))
    }

    private fun dpState(restingFirst: Boolean = false) = BranchyLiveState(
        runId = 7L, phase = BranchyPhase.TRAIN, clusterMode = "data_parallel",
        devices = listOf(BranchyLiveDevice("a", "Phone A", resting = restingFirst)),
        dpRound = BranchyEvent.DpRound(
            round = 3, devices = listOf(
                BranchyEvent.DpRoundDevice("main", 1, 1, 1.0, 1.0, 1.0, state = "active", mb = 6, mbDone = 6, seconds = 4.0, msPerMb = 600.0),
                BranchyEvent.DpRoundDevice("a", 1, 1, 1.0, 1.0, 1.0, state = "active", mb = 2, mbDone = 2, seconds = 4.0, msPerMb = 2000.0, idleS = 1.5)
            )
        ),
        advice = mapOf("a" to BranchyDpAdviceState(true, 3.0, 12.0, 3))
    )

    @Test
    fun notNeededBadgeAndRestCallback() {
        var rested: String? = null
        rule.setContent {
            MaterialTheme {
                BranchyLoadCard(live = dpState(), resetKey = 7L, onRest = { rested = it }, onUnrest = {}, onRebalance = {})
            }
        }
        rule.onNodeWithText(str(com.example.llamadroid.R.string.branchy_r6d_not_needed)).assertIsDisplayed()
        rule.onNodeWithText(str(com.example.llamadroid.R.string.branchy_r6d_rest_button)).performClick()
        rule.onNodeWithText(str(com.example.llamadroid.R.string.branchy_r6d_rest_confirm_body)).assertIsDisplayed()
        // The dialog confirm button carries the same label as the inline button; the dialog one is the last match.
        rule.onAllNodesWithTextLast(str(com.example.llamadroid.R.string.branchy_r6d_rest_button)).performClick()
        assertEquals("a", rested)
    }

    @Test
    fun idleAndLostRowsUseDifferentLabels() {
        val idle = BranchyLiveDevice("a", "Phone A", up = true, idle = true)
        val lost = BranchyLiveDevice("b", "Phone B", up = false)
        rule.setContent {
            MaterialTheme {
                BranchyDeviceStatsCard(listOf(idle, lost), dataParallel = true)
            }
        }
        rule.onNodeWithText(str(com.example.llamadroid.R.string.branchy_r6d_status_idle)).assertIsDisplayed()
        rule.onNodeWithText(str(com.example.llamadroid.R.string.branchy_r6d_retry_now)).assertIsDisplayed()
        rule.onNodeWithText(str(com.example.llamadroid.R.string.branchy_r6d_stop_trying)).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextLast(text: String) =
    onAllNodesWithText(text).let { it[it.fetchSemanticsNodes().size - 1] }
