package com.example.llamadroid.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.service.MangaTranslationProfile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaTranslationProfileCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun balancedProfileCardShowsQualityAndInvokesSelection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profileLabel = context.getString(R.string.workflow_manga_profile_balanced)
        val speedLabel = context.getString(R.string.workflow_manga_speed_medium)
        var selected = false
        composeRule.setContent {
            MaterialTheme {
                MangaProfileCard(
                    profile = MangaTranslationProfile.BALANCED,
                    selected = false,
                    enabled = true,
                    onClick = { selected = true }
                )
            }
        }

        composeRule.onNodeWithText(profileLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(speedLabel).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(selected) }
    }
}
