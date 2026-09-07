package com.example.llamadroid.ui.walkthrough

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeatureGuideModalTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun actionThatNavigatesAdvancesAndKeepsTheResultingPage() {
        val state = WalkthroughState(WalkthroughPreferences(rule.activity.getSharedPreferences(
            "feature_navigation_${System.nanoTime()}", Context.MODE_PRIVATE)))
        val registry = WalkthroughTargets()
        rule.runOnIdle { state.startFeature("models.manager", false, "model_hub") }
        rule.setContent {
            var route by remember { mutableStateOf("model_hub") }
            SideEffect { registry.active = state.session != null }
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                Column {
                    Text(route, Modifier.testTag("route"))
                    Button(onClick = { registry.recordEvent("models.download"); route = "model_sources?family=SD" },
                        modifier = Modifier.testTag("open_downloads")) { Text("Open fixture downloads") }
                    WalkthroughCoach(state, registry, route, {})
                }
            }
        }
        rule.onNodeWithTag("open_downloads").performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, state.session?.index)
            assertEquals("model_sources?family=SD", state.step?.route)
        }
        rule.onNodeWithTag("route").assertTextEquals("model_sources?family=SD")
    }

    @Test
    fun helpCloseInDecisionDialogKeepsDraftAndOriginalDecisionReachable() {
        val state = WalkthroughState(WalkthroughPreferences(rule.activity.getSharedPreferences(
            "feature_modal_${System.nanoTime()}", Context.MODE_PRIVATE)))
        val guide = FeatureGuideCatalog.guides.first()
        val recipe = guide.recipes.first()
        val registry = WalkthroughTargets()
        var confirmed = false
        var draft = "Demonstration draft"
        rule.setContent {
            var text by remember { mutableStateOf(draft) }
            SideEffect { registry.active = state.session != null }
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalWalkthroughTargets provides registry,
                    LocalWalkthroughPresentation provides WalkthroughPresentation(state, registry, guide.route, {}),
                    LocalFeatureGuideEntry provides FeatureGuideEntry(guide.id, state::openFeatureGuide)) {
                    WalkthroughAlertDialog(onDismissRequest = {},
                        title = { Text("Fixture editor") },
                        text = { OutlinedTextField(text, { text = it; draft = it }, Modifier.testTag("draft")) },
                        confirmButton = { TextButton(onClick = { confirmed = true }, Modifier.testTag("confirm")) { Text("Save fixture") } })
                }
            }
        }
        rule.onNodeWithTag("draft").performTextReplacement("Kept across help")
        rule.runOnIdle { state.startFeature(recipe.id, false, guide.route) }
        rule.onNodeWithTag("tour_close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("draft").assertTextContains("Kept across help")
        rule.onNodeWithTag("confirm").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertNull(state.session)
            assertEquals("Kept across help", draft)
            assertTrue(confirmed)
            assertTrue(registry.modalOwners.isEmpty())
        }
    }

    @Test
    fun chooserCanCloseWithoutStartingOrResettingUnderlyingDraft() {
        val state = WalkthroughState(WalkthroughPreferences(rule.activity.getSharedPreferences(
            "feature_chooser_${System.nanoTime()}", Context.MODE_PRIVATE)))
        val guide = FeatureGuideCatalog.guides.first()
        rule.setContent {
            LlamaDroidTheme(darkTheme = true, dynamicColor = false) {
                CompositionLocalProvider(LocalFeatureGuideEntry provides FeatureGuideEntry(guide.id, state::openFeatureGuide)) {
                    Column { Text("Unchanged fixture", Modifier.testTag("origin")); FeatureGuideAction() }
                    FeatureGuideChooser(state, guide.route)
                }
            }
        }
        rule.onNodeWithTag("feature_guide_open").performClick()
        rule.onNodeWithTag("feature_guide_chooser").assertIsDisplayed()
        rule.onNodeWithTag("feature_guide_close").performClick()
        rule.onNodeWithTag("origin").assertIsDisplayed()
        rule.runOnIdle { assertNull(state.session); assertTrue(state.suppressSupportForLaunch) }
    }
}
