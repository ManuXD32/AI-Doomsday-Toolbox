package com.example.llamadroid.ui.components

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises actual shared containers at the design matrix's logical sizes on one emulator. */
class SoftStudioTaskLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Profile(val width: Int, val height: Int, val font: Float, val language: String, val dark: Boolean)

    @Test fun taskFooterAndLastInputRemainReachableAcrossTheDesignMatrix() {
        var profile by mutableStateOf(Profile(320, 640, 1f, "en", false))
        var invocations = 0
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val physicalDensity = LocalDensity.current.density
                val scaledDensity = minOf(maxWidth.value * physicalDensity / profile.width,
                    maxHeight.value * physicalDensity / profile.height)
                val configuration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = profile.width
                    screenHeightDp = profile.height
                    fontScale = profile.font
                    setLocale(Locale.forLanguageTag(profile.language))
                }
                val localizedContext = LocalContext.current.createConfigurationContext(configuration)
                CompositionLocalProvider(LocalDensity provides Density(scaledDensity, profile.font),
                    LocalConfiguration provides configuration, LocalContext provides localizedContext) {
                    key(profile) {
                        LlamaDroidTheme(darkTheme = profile.dark, dynamicColor = false) {
                            Box(Modifier.requiredSize(profile.width.dp, profile.height.dp)) {
                                AppScreenScaffold(title = stringResource(R.string.ai_image_gen), bottomBar = {
                                    AppTaskActionFooter {
                                        Button(onClick = { invocations++ }, modifier = Modifier.fillMaxWidth().testTag("primary")) {
                                            Text(stringResource(R.string.studio_plan_save))
                                        }
                                    }
                                }) {
                                    LazyColumn(Modifier.fillMaxSize().testTag("form"), contentPadding = PaddingValues(20.dp)) {
                                        items(30) { index ->
                                            Text("$index — extremely-long-project-file-name-with-unbroken-identifier-2026.txt",
                                                Modifier.testTag("field_$index"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        var count = 0
        for (width in listOf(320, 360, 411, 600, 840))
            for (landscape in listOf(false, true))
                for (font in listOf(1f, 1.3f, 2f))
                    for (language in listOf("en", "es"))
                        for (dark in listOf(false, true)) {
                            val longEdge = maxOf(640, width + 120)
                            rule.runOnIdle {
                                profile = Profile(if (landscape) longEdge else width,
                                    if (landscape) width else longEdge, font, language, dark)
                            }
                            rule.onNodeWithTag("primary").assertIsDisplayed().performClick()
                            rule.onNodeWithTag("form").performScrollToIndex(29)
                            rule.onNodeWithTag("field_29").assertIsDisplayed()
                            count++
                        }
        rule.runOnIdle { assertEquals(count, invocations) }
    }

    @Test fun collapsingExpertControlsPreservesTheirSaveableDraft() {
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                AppAdvancedSection(title = "Expert settings") {
                    var draft by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                }
            }
        }
        rule.onNodeWithText("Expert settings").performClick()
        rule.onNodeWithTag("draft").performTextInput("preserved draft")
        rule.onNodeWithText("Expert settings").performClick()
        rule.onNodeWithText("Expert settings").performClick()
        rule.onNodeWithTag("draft").assertTextContains("preserved draft")
    }
}
