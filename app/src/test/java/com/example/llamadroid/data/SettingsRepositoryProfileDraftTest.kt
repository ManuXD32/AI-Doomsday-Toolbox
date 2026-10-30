package com.example.llamadroid.data

import android.content.Context
import com.example.llamadroid.service.LlamaLoraSpec
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryProfileDraftTest {
    private val draftPrefs = "llm_server_profile_draft_test"

    @Before
    fun clear() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE).edit().clear().commit())
        assertTrue(app.getSharedPreferences(draftPrefs, Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test
    fun `constructing a draft does not change shared theme state or write global prefs`() {
        val app = RuntimeEnvironment.getApplication()
        val global = SettingsRepository(app)
        global.setThemeMode(AppThemeMode.DARK)
        global.setDynamicColor(true)
        val theme = global.themeMode.value
        val visual = global.visualStyle.value
        val variant = global.pixelVariant.value
        val font = global.pixelFontEnabled.value

        // The draft file holds no appearance keys; a non-draft repository would reset the shared flows.
        val draft = SettingsRepository(app, draftPrefs, isProfileDraft = true)

        assertEquals(theme, global.themeMode.value)
        assertEquals(visual, global.visualStyle.value)
        assertEquals(variant, global.pixelVariant.value)
        assertEquals(font, global.pixelFontEnabled.value)
        assertTrue(global.dynamicColor.value)
        assertEquals(AppThemeMode.DARK, draft.themeMode.value)
        assertFalse(app.getSharedPreferences(draftPrefs, Context.MODE_PRIVATE).contains("theme_mode"))
    }

    @Test
    fun `restore then capture round trips launch fields through a draft and leaves global untouched`() {
        val app = RuntimeEnvironment.getApplication()
        val global = SettingsRepository(app)
        val globalContextBefore = global.contextSize.value
        val globalModelBefore = global.selectedModelPath.value

        val profile = LlamaServerLaunchProfile(
            modelPath = "/models/a.gguf",
            mmprojPath = "/models/a-mmproj.gguf",
            visionEnabled = true,
            contextSize = 4096,
            threads = 3,
            batchSize = 256,
            kvCacheEnabled = true,
            kvCacheTypeK = "q8_0",
            kvCacheTypeV = "q8_0",
            flashAttention = true,
            loras = listOf(LlamaLoraSpec("/models/l.gguf", 0.5f)),
            customFlags = "--slots",
            speculativeEnabled = true,
            speculativeMode = LlamaSpeculativeMode.DRAFT_MTP.flagValue,
            mtpUseDraftModel = true,
            draftModelPath = "/models/mtp.gguf",
            mtpDraftMax = 5
        )
        val draft = SettingsRepository(app, draftPrefs, isProfileDraft = true)
        LlamaServerLaunchProfile.restore(profile, draft)
        val captured = LlamaServerLaunchProfile.capture(draft)

        assertEquals(profile.modelPath, captured.modelPath)
        assertEquals(profile.mmprojPath, captured.mmprojPath)
        assertTrue(captured.visionEnabled)
        assertEquals(4096, captured.contextSize)
        assertEquals(3, captured.threads)
        assertEquals(256, captured.batchSize)
        assertTrue(captured.kvCacheEnabled)
        assertEquals("q8_0", captured.kvCacheTypeK)
        assertTrue(captured.flashAttention)
        assertEquals(profile.loras, captured.loras)
        assertEquals("--slots", captured.customFlags)
        assertTrue(captured.speculativeEnabled)
        assertEquals(LlamaSpeculativeMode.DRAFT_MTP.flagValue, captured.speculativeMode)
        assertTrue(captured.mtpUseDraftModel)
        assertEquals("/models/mtp.gguf", captured.draftModelPath)
        assertEquals(5, captured.mtpDraftMax)

        assertEquals(globalContextBefore, global.contextSize.value)
        assertEquals(globalModelBefore, SettingsRepository(app).selectedModelPath.value)
    }
}
