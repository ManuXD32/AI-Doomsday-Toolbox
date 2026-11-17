package com.example.llamadroid.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.llamadroid.sd.SdCuratedPresets
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.VideoModelSelection
import com.example.llamadroid.sd.VideoPresetCard
import com.example.llamadroid.service.VideoRuntimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class VideoGenModelSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private val wanCard = VideoPresetCard("wan", null, "Wan card", "/m/wan.gguf", installed = true, missingFiles = 0)
    private val lingCard = VideoPresetCard(
        SdCuratedPresets.LingBotPhone.BUNDLE_ID, null, "Ling card", "/m/ling.gguf", installed = true, missingFiles = 0
    )

    private fun fakeResolve(path: String): VideoModelSelection? = when (path) {
        "/m/wan.gguf" -> VideoModelSelection(
            path, SdVideoFamily.WAN, null,
            SdVideoComponentPaths(diffusionModelPath = path, vaePath = "/m/vae.safetensors"),
            emptyList(), "wan"
        )
        "/m/ling.gguf" -> VideoModelSelection(
            path, SdVideoFamily.LINGBOT_VIDEO, SdCuratedPresets.LingBotPhone.VARIANT,
            SdVideoComponentPaths(diffusionModelPath = path),
            emptyList(), SdCuratedPresets.LingBotPhone.BUNDLE_ID
        )
        else -> null
    }

    @Test
    fun cardTapUpdatesOptionsAndSelectedMainModelWithoutScalarDefaults() {
        var options by mutableStateOf(VideoRuntimeOptions())
        var defaults: VideoScalarDefaults? = null
        rule.setContent {
            VideoGenModelSectionContent(
                options = options,
                cards = listOf(wanCard),
                resolve = ::fakeResolve,
                onOptionsChange = { options = it },
                onOpenModelManager = {},
                onScalarDefaults = { defaults = it }
            )
        }
        rule.onNodeWithText("Wan card").performClick()
        rule.waitForIdle()
        assertEquals("/m/wan.gguf", videoMainModelPathForSelection(options))
        assertEquals("/m/vae.safetensors", options.videoComponents.vaePath)
        assertNull(defaults)
    }

    @Test
    fun lingBotCardAppliesScalarDefaultsThroughCallback() {
        var options by mutableStateOf(VideoRuntimeOptions())
        var defaults: VideoScalarDefaults? = null
        rule.setContent {
            VideoGenModelSectionContent(
                options = options,
                cards = listOf(lingCard),
                resolve = ::fakeResolve,
                onOptionsChange = { options = it },
                onOpenModelManager = {},
                onScalarDefaults = { defaults = it }
            )
        }
        rule.onNodeWithText("Ling card").performClick()
        rule.waitForIdle()
        val applied = defaults
        assertNotNull(applied)
        assertEquals(SdCuratedPresets.LingBotPhone.FRAMES, applied!!.frames)
        assertEquals(SdCuratedPresets.LingBotPhone.STEPS, applied.steps)
        assertEquals(SdCuratedPresets.LingBotPhone.CFG_SCALE_TEXT, applied.cfgScaleText)
        assertEquals("/m/ling.gguf", videoMainModelPathForSelection(options))
    }
}
