package com.example.llamadroid.tama.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded compact-screen contract for the classic map. */
@RunWith(AndroidJUnit4::class)
class ClassicMapUiConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun englishHomeTileIsReachableAndClickable() = map("en")

    @Test fun spanishHomeTileIsReachableAndClickable() = map("es")

    private fun map(language: String) {
        val context = localized(language)
        val clicked = mutableListOf<String>()
        val locations = classicMapLocations(context)
        val home = locations.first()
        compact(language) {
            TamaMapView(
                cityName = context.getString(R.string.tama_city_hometown),
                locations = locations,
                currentLocation = home,
                discoveredLocationIds = setOf(home.id),
                onLocationClick = { clicked += it.id }
            )
        }
        compose.onNodeWithContentDescription(home.name)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf(home.id), clicked)
    }

    private fun localized(language: String): Context {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            fontScale = 1.6f
        }
        return compose.activity.createConfigurationContext(configuration)
    }

    private fun compact(language: String, content: @Composable () -> Unit) {
        val context = localized(language)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalDensity provides Density(context.resources.displayMetrics.density, 1.6f)
            ) {
                LlamaDroidTheme(dynamicColor = false) {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) { content() }
                }
            }
        }
    }
}
