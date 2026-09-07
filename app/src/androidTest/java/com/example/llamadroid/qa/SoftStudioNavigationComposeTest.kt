package com.example.llamadroid.qa

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppNavigationDestination
import com.example.llamadroid.ui.navigation.AppRootDestination
import com.example.llamadroid.ui.navigation.SoftStudioAppScaffold
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SoftStudioNavigationComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rootNavigationWorksAndDeepTasksReleaseTheGlobalChrome() {
        var route by mutableStateOf("dashboard")
        var settingsOpened = false
        rule.setContent {
            val window = fixtureWindow(411, 800, LocalDensity.current.density)
            CompositionLocalProvider(LocalWindowInfo provides window,
                LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                LlamaDroidTheme(dynamicColor = false) {
                    SoftStudioAppScaffold(route,
                        AppRootDestination.entries.map { root ->
                            AppNavigationDestination(root.route, root.name, Icons.Default.Home,
                                onClick = { route = root.route })
                        }, remember { SnackbarHostState() }, onSettings = { settingsOpened = true }) { Text("Body") }
                }
            }
        }
        rule.onNodeWithTag("soft_studio_navigation_bar").assertIsDisplayed()
        rule.onNodeWithTag("studio_bar_library").performClick()
        rule.runOnIdle { assertEquals("library", route) }
        rule.onNodeWithTag("soft_studio_settings").performClick()
        rule.runOnIdle { assertTrue(settingsOpened); route = "image_gen?startMode=0" }
        rule.onNodeWithTag("soft_studio_navigation_bar").assertDoesNotExist()
        rule.onNodeWithTag("soft_studio_settings").assertDoesNotExist()
    }

    @Test fun largeTextDrawerKeepsEveryRootReachable() {
        var route by mutableStateOf("dashboard")
        rule.setContent {
            val window = fixtureWindow(320, 480, LocalDensity.current.density)
            CompositionLocalProvider(LocalWindowInfo provides window,
                LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                LlamaDroidTheme(dynamicColor = false) {
                    val labels = listOf(R.string.studio_nav_home, R.string.studio_nav_tools,
                        R.string.studio_nav_library, R.string.studio_nav_tama)
                    SoftStudioAppScaffold(route,
                        AppRootDestination.entries.mapIndexed { i, root ->
                            AppNavigationDestination(root.route, rule.activity.getString(labels[i]), Icons.Default.Home,
                                onClick = { route = root.route })
                        }, remember { SnackbarHostState() }, onSettings = {}) { Text("Body") }
                }
            }
        }
        rule.onNodeWithTag("soft_studio_navigation_bar").assertDoesNotExist()
        AppRootDestination.entries.forEach { root ->
            rule.onNodeWithTag("soft_studio_menu").performClick()
            rule.onNodeWithTag("studio_drawer_${root.route}").performScrollTo().assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals(root.route, route) }
        }
    }

    private fun fixtureWindow(widthDp: Int, heightDp: Int, density: Float) = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize = IntSize((widthDp * density).roundToInt(), (heightDp * density).roundToInt())
    }
}
