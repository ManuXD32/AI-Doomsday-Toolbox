package com.example.llamadroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResponsiveComponentsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactNavigationShowsMoreSheetAndNestedSelection() {
        var selectedRoute by mutableStateOf("models/detail")
        val model = AppNavigationDestination(
            route = "models",
            label = "Models",
            icon = Icons.Default.Star,
            contentDescription = "Open Models",
            isSelected = { route -> route == "models" || route?.startsWith("models/") == true },
            onClick = { selectedRoute = "models" }
        )
        val settings = AppNavigationDestination(
            route = "settings",
            label = "Settings",
            icon = Icons.Default.Settings,
            contentDescription = "Open Settings",
            isSelected = { route -> route == "settings" || route?.startsWith("settings/") == true },
            onClick = { selectedRoute = "settings" }
        )
        composeRule.setContent {
            MaterialTheme {
                AdaptiveAppNavigation(
                    currentRoute = selectedRoute,
                    widthDp = 320,
                    fontScale = 1f,
                    moreLabel = "More",
                    moreContentDescription = "Open more destinations",
                    moreSheetTitle = "More destinations",
                    moreSheetSubtitle = "Models and settings",
                    moreSheetDismissLabel = "Close more destinations",
                    destinations = listOf(
                        AppNavigationDestination(
                            route = "home",
                            label = "Home",
                            icon = Icons.Default.Home,
                            contentDescription = "Open Home",
                            onClick = { selectedRoute = "home" }
                        )
                    ),
                    compactDestinations = listOf(
                        AppNavigationDestination(
                            route = "home",
                            label = "Home",
                            icon = Icons.Default.Home,
                            contentDescription = "Open Home",
                            onClick = { selectedRoute = "home" }
                        )
                    ),
                    overflowDestinations = listOf(model, settings)
                )
            }
        }

        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open more destinations").performClick()
        composeRule.onNodeWithText("Models").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Models").assertIsSelected()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.runOnIdle { assertTrue(selectedRoute == "settings") }
    }

    @Test
    fun choiceRowUsesSingleSelectableAccessibilityNode() {
        var selected by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                AppChoiceRow(
                    title = "Choice",
                    supportingText = "Supporting description",
                    selected = selected,
                    contentDescription = "Choose this option",
                    onClick = { selected = true }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Choose this option")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(selected) }
    }
}
