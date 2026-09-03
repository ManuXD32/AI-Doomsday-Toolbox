package com.example.llamadroid.ui.ai

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
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

        composeRule.onNodeWithText("img2img").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("txt2img").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Generate Image").assertIsDisplayed()
    }

    @Test
    fun isolatedUpscaleScreenStartsWithoutCrashing() {
        composeRule.setContent {
            LegacyUpscaleScreen(navController = rememberNavController())
        }

        composeRule.onNodeWithText("No upscaler models installed.").assertIsDisplayed()
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

        composeRule.onNodeWithText("No upscaler models installed.").assertIsDisplayed()
    }
}
