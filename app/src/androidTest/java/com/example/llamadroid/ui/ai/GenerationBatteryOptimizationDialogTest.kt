package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationBatteryOptimizationDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allActionsRemainVisibleAtCompactViewportAndLargeText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsLabel = context.getString(R.string.responsive_battery_dialog_settings_compact)
        val deviceHelpLabel = context.getString(R.string.responsive_battery_dialog_oem_fix_compact)
        val continueLabel = context.getString(R.string.responsive_battery_dialog_continue_compact)
        val cancelLabel = context.getString(R.string.action_cancel)
        var settingsClicked = false
        var oemFixClicked = false
        var continueClicked = false
        var dismissClicked = false

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f)
            ) {
                MaterialTheme {
                    Box(Modifier.size(320.dp)) {
                        BatteryOptimizationDialogContent(
                            onOpenBatterySettings = { settingsClicked = true },
                            onOpenDeviceSpecificFix = { oemFixClicked = true },
                            onContinueAnyway = { continueClicked = true },
                            onDismiss = { dismissClicked = true }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText(settingsLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(deviceHelpLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(continueLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(cancelLabel).assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertTrue(settingsClicked)
            assertTrue(oemFixClicked)
            assertTrue(continueClicked)
            assertTrue(dismissClicked)
        }
    }

    @Test
    fun allActionsRemainReachableInShortLandscapeViewport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val labels = listOf(
            context.getString(R.string.responsive_battery_dialog_settings_compact),
            context.getString(R.string.responsive_battery_dialog_oem_fix_compact),
            context.getString(R.string.responsive_battery_dialog_continue_compact),
            context.getString(R.string.action_cancel)
        )

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f)
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 600.dp, height = 320.dp)) {
                        BatteryOptimizationDialogContent(
                            onOpenBatterySettings = {},
                            onOpenDeviceSpecificFix = {},
                            onContinueAnyway = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }

        labels.forEach { label ->
            composeRule.onNodeWithText(label)
                .assertIsDisplayed()
        }
    }
}
