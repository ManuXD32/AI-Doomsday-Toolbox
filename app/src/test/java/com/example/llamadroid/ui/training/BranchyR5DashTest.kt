package com.example.llamadroid.ui.training

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyPhase
import com.example.llamadroid.branchy.BranchyWaiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class BranchyR5DashTest {
    @get:Rule
    val rule = createComposeRule()

    private val devices = listOf(
        BranchyLiveDevice("a", "Phone A", up = true),
        BranchyLiveDevice("b", "Phone B", up = false, waiting = true)
    )

    @Test
    fun skippableWaitingCardSkipsAfterConfirm() {
        var skipped: String? = null
        rule.setContent {
            MaterialTheme { BranchyWaitingCard(BranchyWaiting("join", listOf("b"), true), devices) { skipped = it } }
        }
        rule.onNodeWithText("Waiting for Phone B").assertIsDisplayed()
        rule.onNodeWithText("The helpers have not answered yet.").assertIsDisplayed()
        rule.onNodeWithText("Continue without Phone B").performClick()
        // Confirm dialog: its button repeats the label, the title asks the question.
        rule.onNodeWithText("Continue without Phone B?").assertIsDisplayed()
        assertEquals(null, skipped)
        rule.onAllNodesWithText("Continue without Phone B").onLast().performClick()
        assertEquals("b", skipped)
    }

    @Test
    fun nonSkippableWaitingCardShowsHintAndNoSkipButton() {
        rule.setContent {
            MaterialTheme { BranchyWaitingCard(BranchyWaiting("run", listOf("b"), false), devices) { } }
        }
        rule.onNodeWithText("Pause the run, then use Edit devices", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Continue without Phone B").assertCountEquals(0)
    }

    @Test
    fun masterLabelSitsUnderMasterNode() {
        val live = BranchyLiveState(
            runId = 1L, phase = BranchyPhase.TRAIN, clusterMode = "data_parallel", devices = devices
        )
        rule.setContent { MaterialTheme { BranchyClusterCard(live, animate = false) } }
        val canvas = rule.onNodeWithTag("branchy_topology_canvas").getUnclippedBoundsInRoot()
        val label = rule.onNodeWithTag("branchy_master_label").getUnclippedBoundsInRoot()
        val masterBottom = canvas.top + (BranchyTopologyLayout.MASTER_Y + BranchyTopologyLayout.MASTER_RADIUS).dp
        assertTrue("label must be below the master node", label.top >= masterBottom)
        assertTrue("label must start above the links", label.bottom <= canvas.top + BranchyTopologyLayout.LINK_START_Y.dp + 2.dp)
        val labelCenter = (label.left + label.right) / 2
        val canvasCenter = (canvas.left + canvas.right) / 2
        assertTrue("label centered under master", kotlin.math.abs((labelCenter - canvasCenter).value) < 1f)
    }

    @Test
    fun runLogCardLoadsOnExpandAndCopies() {
        val logText = "12:00:01 step n=1 loss=2.5"
        rule.setContent {
            MaterialTheme { BranchyRunLogCard(File("/tmp/none"), loader = { logText }) }
        }
        rule.onAllNodesWithText(logText).assertCountEquals(0)
        rule.onNodeWithText("Show").performClick()
        rule.onNodeWithText(logText).assertIsDisplayed()
        rule.onNodeWithText("Copy").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals(logText, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun agoFormatting() {
        assertEquals("0s", branchyAgo(-5L))
        assertEquals("12s", branchyAgo(12_400L))
        assertEquals("3m 05s", branchyAgo(185_000L))
        assertEquals("1h 02m", branchyAgo(3_720_000L))
    }
}
