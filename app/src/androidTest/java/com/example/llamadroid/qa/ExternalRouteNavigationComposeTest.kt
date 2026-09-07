package com.example.llamadroid.qa

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ExternalRouteNavigationComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun completeWelcomeFlow() {
        SettingsRepository(composeRule.activity).setHasCompletedWelcome(true)
    }

    @Test
    fun coldPendingRouteWaitsUntilNavigationGraphIsInstalled() {
        composeRule.setContent {
            LlamaDroidTheme {
                LlamaApp(
                    pendingNavigationRoute = ExternalRouteResolution.Navigate(
                        Screen.ModelHub.route
                    )
                )
            }
        }

        composeRule.onAllNodesWithText(
            composeRule.activity.getString(R.string.models_hub),
            useUnmergedTree = true
        ).onFirst().assertIsDisplayed()
    }
}
