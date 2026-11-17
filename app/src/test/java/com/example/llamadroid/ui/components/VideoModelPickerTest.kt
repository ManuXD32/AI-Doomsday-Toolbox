package com.example.llamadroid.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.VideoModelSelection
import com.example.llamadroid.sd.VideoPresetCard
import com.example.llamadroid.service.VideoRuntimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class VideoModelPickerTest {
    @get:Rule
    val rule = createComposeRule()

    private val installedCard = VideoPresetCard("wan", null, "Wan card", "/m/wan.gguf", installed = true, missingFiles = 0)
    private val missingCard = VideoPresetCard("ling", null, "Ling card", null, installed = false, missingFiles = 3)

    private fun fakeResolve(path: String): VideoModelSelection? =
        if (path != "/m/wan.gguf") null else VideoModelSelection(
            mainPath = path,
            family = SdVideoFamily.WAN,
            variant = null,
            components = SdVideoComponentPaths(diffusionModelPath = path, vaePath = "/m/vae.safetensors"),
            missingRoles = listOf(SdVideoComponentRole.T5XXL),
            bundleId = "wan"
        )

    @Test
    fun installedCardAppliesOnceAndNonInstalledOpensManager() {
        var options by mutableStateOf(VideoRuntimeOptions())
        var changes = 0
        var applied = 0
        val opened = mutableListOf<String?>()
        rule.setContent {
            VideoModelPickerContent(
                options = options,
                cards = listOf(installedCard, missingCard),
                resolve = ::fakeResolve,
                onOptionsChange = { options = it; changes++ },
                onOpenModelManager = { opened += it },
                onPresetApplied = { applied++ }
            )
        }
        rule.onNodeWithText("Wan card").performClick()
        rule.waitForIdle()
        assertEquals(1, changes)
        assertEquals(1, applied)
        assertEquals("/m/vae.safetensors", options.videoComponents.vaePath)
        assertEquals(SdVideoFamily.WAN, options.videoFamily)

        rule.onNodeWithText("Wan card").performClick()
        rule.waitForIdle()
        assertEquals(1, changes)
        assertEquals(1, applied)

        rule.onNodeWithText("Ling card").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("ling"), opened)
    }

    @Test
    fun missingRolesLineAndGetModelsButtonAreShown() {
        var options by mutableStateOf(VideoRuntimeOptions())
        val opened = mutableListOf<String?>()
        rule.setContent {
            VideoModelPickerContent(
                options = options,
                cards = listOf(installedCard),
                resolve = ::fakeResolve,
                onOptionsChange = { options = it },
                onOpenModelManager = { opened += it }
            )
        }
        rule.onNodeWithText("Wan card").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Get models").assertExists()
        rule.onNodeWithText("Get models").performClick()
        assertTrue(opened == listOf<String?>(null))
    }

    @Test
    fun emptyStateOffersGetModels() {
        val opened = mutableListOf<String?>()
        rule.setContent {
            VideoModelPickerContent(
                options = VideoRuntimeOptions(),
                cards = listOf(missingCard),
                resolve = ::fakeResolve,
                onOptionsChange = {},
                onOpenModelManager = { opened += it }
            )
        }
        rule.onNodeWithText("Get models").performClick()
        assertEquals(listOf<String?>(null), opened)
    }
}
