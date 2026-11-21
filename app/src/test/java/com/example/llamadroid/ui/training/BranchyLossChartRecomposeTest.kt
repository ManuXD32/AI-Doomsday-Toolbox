package com.example.llamadroid.ui.training

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression: the chart switched from the empty path to the full path on recompose and crashed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class BranchyLossChartRecomposeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun chartSurvivesEmptyToPointsTransitions() {
        var points by mutableStateOf<List<Pair<Int, Float>>>(emptyList())
        rule.setContent {
            MaterialTheme {
                BranchyLossChart(lossPoints = points, valPoints = emptyList())
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Training").assertDoesNotExist()

        points = listOf(0 to 2.5f, 10 to 1.5f, 20 to 1.0f)
        rule.waitForIdle()
        rule.onNodeWithText("Training").assertExists()

        points = emptyList()
        rule.waitForIdle()
        rule.onNodeWithText("Training").assertDoesNotExist()

        points = listOf(0 to 3.0f, 5 to 2.0f)
        rule.waitForIdle()
        rule.onNodeWithText("Training").assertExists()
    }
}

