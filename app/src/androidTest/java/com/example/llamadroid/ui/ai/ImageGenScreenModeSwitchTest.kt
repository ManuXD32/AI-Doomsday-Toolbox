package com.example.llamadroid.ui.ai

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageGenScreenModeSwitchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        SharedFileHolder.clear()
        SettingsRepository(composeRule.activity).setImageGenerationDraft(
            JSONObject().put("mode", IMAGE_GEN_MODE_TXT2IMG)
        )
    }

    @After
    fun tearDown() {
        SharedFileHolder.clear()
    }

    @Test
    fun openingAndSwitchingModesKeepsImageGenAlive() {
        composeRule.setContent {
            ImageGenScreen(navController = rememberNavController())
        }

        val transformLabel = composeRule.activity.getString(R.string.imagegen_task_transform)
        composeRule.onNodeWithText(transformLabel).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(transformLabel).assertIsSelected()
    }

    @Test
    fun enlargeModeCanBeComposedOnTheFirstFrame() {
        composeRule.setContent {
            ImageGenScreen(
                navController = rememberNavController(),
                initialMode = IMAGE_GEN_MODE_UPSCALE
            )
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.imagegen_task_enlarge)
        ).assertIsSelected()
    }

    @Test
    fun isolatedUpscaleScreenStartsWithoutCrashing() {
        composeRule.setContent {
            LegacyUpscaleScreen(navController = rememberNavController())
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.imagegen_no_upscalers_installed)).assertIsDisplayed()
    }

    @Test
    fun sharedUpscaleRouteStartsInIsolatedUpscaleScreen() {
        SharedFileHolder.setPendingFile(
            uri = Uri.parse("content://example/test.png"),
            mimeType = "image/png",
            target = SharedFileTarget.LEGACY_IMAGE_UPSCALER
        )

        composeRule.setContent {
            LegacyUpscaleScreen(navController = rememberNavController())
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.imagegen_no_upscalers_installed)).assertIsDisplayed()
    }
}
