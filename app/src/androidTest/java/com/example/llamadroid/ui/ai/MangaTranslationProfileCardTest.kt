package com.example.llamadroid.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.llamadroid.R
import com.example.llamadroid.service.MangaTranslationProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The expected labels must come from the same composition resources as stringResource().
 * ApplicationProvider can retain the device locale while the Compose activity fixture is
 * explicitly localized differently.
 */
@RunWith(AndroidJUnit4::class)
class MangaTranslationProfileCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun balancedProfileCardShowsQualityAndInvokesSelection() {
        val expectedLabels = mutableStateOf<Pair<String, String>?>(null)
        var selected = false
        composeRule.setContent {
            val resources = LocalResources.current
            SideEffect {
                expectedLabels.value =
                    resources.getString(R.string.workflow_manga_profile_balanced) to
                        resources.getString(R.string.workflow_manga_speed_medium)
            }
            MaterialTheme {
                MangaProfileCard(
                    profile = MangaTranslationProfile.BALANCED,
                    selected = false,
                    enabled = true,
                    onClick = { selected = true }
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 1_000L) { expectedLabels.value != null }
        val labels = expectedLabels.value
        assertNotNull(labels)
        val (profileLabel, speedLabel) = checkNotNull(labels)
        composeRule.onNodeWithText(profileLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(speedLabel).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(selected) }
    }
}
