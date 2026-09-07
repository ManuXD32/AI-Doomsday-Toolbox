package com.example.llamadroid.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryAppearanceTest {
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun clearSettings() {
        preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun `unrecognized appearance values use system and app colors by default`() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorage(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorage("future-mode"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStorage(" LIGHT "))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorage("dark"))

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        assertEquals(AppThemeMode.SYSTEM, settings.themeMode.value)
        assertFalse(settings.dynamicColor.value)
    }

    @Test
    fun `appearance selections persist and are visible to another repository`() {
        val application = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(application)

        settings.setThemeMode(AppThemeMode.DARK)
        settings.setDynamicColor(true)

        val reopened = SettingsRepository(application)
        assertEquals(AppThemeMode.DARK, reopened.themeMode.value)
        assertTrue(reopened.dynamicColor.value)
    }
}
