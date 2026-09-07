package com.example.llamadroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftNumericTextFieldsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun focusedDraftSurvivesParentCoercion() {
        var value by mutableIntStateOf(50)

        composeRule.setContent {
            MaterialTheme {
                Column {
                    DraftIntTextField(
                        value = value,
                        onValueChange = { value = it.coerceAtLeast(10) },
                        modifier = Modifier.testTag("count")
                    )
                    Text("Committed $value")
                }
            }
        }

        composeRule.onNodeWithTag("count").performTextClearance()
        composeRule.onNodeWithTag("count").performTextInput("1")

        composeRule.onNodeWithTag("count").assertTextEquals("1")
        composeRule.onNodeWithText("Committed 10").assertExists()
    }

    @Test
    fun blankLongDraftCommitsToBlankValue() {
        var seed by mutableLongStateOf(123L)

        composeRule.setContent {
            MaterialTheme {
                Column {
                    DraftLongTextField(
                        value = seed,
                        onValueChange = { seed = it },
                        blankValue = -1L,
                        modifier = Modifier.testTag("seed")
                    )
                    Text("Seed $seed")
                }
            }
        }

        composeRule.onNodeWithTag("seed").performTextClearance()

        composeRule.onNodeWithTag("seed").assertTextEquals("")
        composeRule.onNodeWithTag("seed").performImeAction()
        composeRule.onNodeWithTag("seed").assertTextEquals("")
        composeRule.onNodeWithText("Seed -1").assertIsDisplayed()
    }

    @Test
    fun outOfRangeDraftNormalizesOnCommit() {
        var value by mutableIntStateOf(50)

        composeRule.setContent {
            MaterialTheme {
                Column {
                    DraftIntTextField(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = 10..99,
                        modifier = Modifier.testTag("bounded")
                    )
                    Text("Committed $value")
                }
            }
        }

        composeRule.onNodeWithTag("bounded").performTextClearance()
        composeRule.onNodeWithTag("bounded").performTextInput("1")

        composeRule.onNodeWithTag("bounded").assertTextEquals("1")
        composeRule.onNodeWithText("Committed 50").assertExists()

        composeRule.onNodeWithTag("bounded").performImeAction()

        composeRule.onNodeWithTag("bounded").assertTextEquals("10")
        composeRule.onNodeWithText("Committed 10").assertExists()
    }
}
