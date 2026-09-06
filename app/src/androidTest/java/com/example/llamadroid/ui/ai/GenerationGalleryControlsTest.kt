package com.example.llamadroid.ui.ai

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SdProgressSnapshot
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Renderer-state regressions; actual native execution is covered by the emulator QA run. */
class GenerationGalleryControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: SettingsRepository
    private var imageDraft = JSONObject()
    private var videoDraft = JSONObject()

    @Before fun prepare() {
        settings = SettingsRepository(rule.activity)
        imageDraft = settings.imageGenerationDraft() ?: JSONObject()
        videoDraft = settings.videoGenerationDraft() ?: JSONObject()
        settings.setImageGenerationDraft(JSONObject().put("mode", 0))
        settings.setVideoGenerationDraft(JSONObject().put("mode", 0))
    }

    @After fun restore() {
        // Dispose first: each generation screen persists its current draft on disposal.
        rule.runOnUiThread { rule.activity.setContent {} }
        rule.waitForIdle()
        SDModeStateHolder.txt2img.reset()
        VideoGenerationStateHolder.txt2vid.reset()
        settings.setImageGenerationDraft(imageDraft)
        settings.setVideoGenerationDraft(videoDraft)
    }

    @Test fun runningImageKeepsCancelAcrossGallerySwitchesAtLargeText() {
        SDModeStateHolder.txt2img.updateState(SDGenerationState.Generating(
            SdProgressSnapshot(0, 1, .1f, statusText = rule.activity.getString(R.string.gen_status_loading_model))
        ))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                LlamaDroidTheme { ImageGenScreen(rememberNavController(), initialTab = "gallery") }
            }
        }
        val cancel = rule.activity.getString(R.string.soft_studio_cancel)
        rule.onNodeWithText(cancel).assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithText(rule.activity.getString(R.string.imagegen_tab_generate)).performClick()
        rule.onNodeWithText(cancel).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.imagegen_tab_gallery)).performClick()
        rule.onNodeWithText(cancel).assertIsDisplayed()
        rule.runOnIdle { SDModeStateHolder.txt2img.reset() }
        rule.onNodeWithText(cancel).assertDoesNotExist()
    }

    @Test fun runningVideoKeepsCancelAcrossGallerySwitchesAtLargeText() {
        VideoGenerationStateHolder.txt2vid.updateState(VideoGenerationState.Generating(
            .1f, rule.activity.getString(R.string.video_gen_status_starting), 0, 1
        ))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                LlamaDroidTheme { VideoGenScreen(rememberNavController(), initialTab = "gallery") }
            }
        }
        val cancel = rule.activity.getString(R.string.soft_studio_cancel)
        rule.onNodeWithText(cancel).assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithText(rule.activity.getString(R.string.video_gen_tab_generate)).performClick()
        rule.onNodeWithText(cancel).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.video_gen_tab_gallery)).performClick()
        rule.onNodeWithText(cancel).assertIsDisplayed()
        rule.runOnIdle { VideoGenerationStateHolder.txt2vid.reset() }
        rule.onNodeWithText(cancel).assertDoesNotExist()
    }
}
