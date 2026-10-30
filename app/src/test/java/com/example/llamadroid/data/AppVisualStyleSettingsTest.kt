package com.example.llamadroid.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppVisualStyleSettingsTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        .also { assertTrue(it.edit().clear().commit()) }

    @Test
    fun `new user without legacy markers gets pixel`() {
        assertEquals(AppVisualStyle.PIXEL, resolveInitialVisualStyle(false, 0L, false, false))
    }

    @Test
    fun `each legacy marker keeps soft studio`() {
        assertEquals(AppVisualStyle.SOFT_STUDIO, resolveInitialVisualStyle(true, 0L, false, false))
        assertEquals(AppVisualStyle.SOFT_STUDIO, resolveInitialVisualStyle(false, 120L, false, false))
        assertEquals(AppVisualStyle.SOFT_STUDIO, resolveInitialVisualStyle(false, 0L, true, false))
        assertEquals(AppVisualStyle.SOFT_STUDIO, resolveInitialVisualStyle(false, 0L, false, true))
    }

    @Test
    fun `storage ids round trip and unknown values fall back`() {
        AppVisualStyle.entries.forEach { assertEquals(it, AppVisualStyle.fromStorage(it.storageValue)) }
        PixelThemeVariant.entries.forEach { assertEquals(it, PixelThemeVariant.fromStorage(it.storageValue)) }
        assertEquals(AppVisualStyle.SOFT_STUDIO, AppVisualStyle.fromStorage("future"))
        assertEquals(PixelThemeVariant.CANDY_POP, PixelThemeVariant.fromStorage(null))
        assertEquals(PixelThemeVariant.POCKET_LCD, PixelThemeVariant.fromStorage(" POCKET_LCD "))
    }

    @Test
    fun `ensure resolves once for a fresh install and persists the default variant`() {
        val prefs = prefs()
        val context = RuntimeEnvironment.getApplication()
        assertEquals(AppVisualStyle.PIXEL, ensureVisualStyleResolved(context))
        assertEquals("pixel", prefs.getString("app_visual_style", null))
        assertEquals("candy_pop", prefs.getString("pixel_theme_variant", null))
        // A later legacy marker must not flip the already-resolved choice.
        prefs.edit().putBoolean("has_completed_welcome", true).commit()
        assertEquals(AppVisualStyle.PIXEL, ensureVisualStyleResolved(context))
    }

    @Test
    fun `ensure keeps soft studio for an existing install and does not change a stored choice`() {
        val prefs = prefs()
        val context = RuntimeEnvironment.getApplication()
        prefs.edit().putBoolean("has_completed_welcome", true).commit()
        assertEquals(AppVisualStyle.SOFT_STUDIO, ensureVisualStyleResolved(context))
        prefs.edit().putString("app_visual_style", "pixel").commit()
        assertEquals(AppVisualStyle.PIXEL, ensureVisualStyleResolved(context))
    }

    @Test
    fun `last seen version code marks an existing install`() {
        val prefs = prefs()
        prefs.edit().putLong("last_seen_version_code", 1200L).commit()
        assertEquals(AppVisualStyle.SOFT_STUDIO, ensureVisualStyleResolved(RuntimeEnvironment.getApplication()))
        assertFalse(prefs.getString("app_visual_style", "") == "pixel")
    }

    @Test
    fun `pixel font is off by default and round trips`() {
        val prefs = prefs()
        val context = RuntimeEnvironment.getApplication()
        val repo = SettingsRepository(context)
        assertFalse(prefs.getBoolean("pixel_font_enabled", false))
        assertFalse(repo.pixelFontEnabled.value)
        repo.setPixelFontEnabled(true)
        assertTrue(prefs.getBoolean("pixel_font_enabled", false))
        assertTrue(SettingsRepository(context).pixelFontEnabled.value)
        repo.setPixelFontEnabled(false)
        assertFalse(SettingsRepository(context).pixelFontEnabled.value)
    }
}
