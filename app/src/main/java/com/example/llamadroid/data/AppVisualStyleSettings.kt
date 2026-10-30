package com.example.llamadroid.data

import androidx.core.content.edit
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Overall look of the app. Stored ids are stable so future styles can be added. */
enum class AppVisualStyle(val storageValue: String) {
    SOFT_STUDIO("soft_studio"),
    PIXEL("pixel");

    companion object {
        fun fromStorage(value: String?): AppVisualStyle = when (value?.trim()?.lowercase(Locale.ROOT)) {
            PIXEL.storageValue -> PIXEL
            else -> SOFT_STUDIO
        }
    }
}

/** Colour family used while [AppVisualStyle.PIXEL] is active. */
enum class PixelThemeVariant(val storageValue: String) {
    CANDY_POP("candy_pop"),
    POCKET_LCD("pocket_lcd"),
    ARCADE_NIGHT("arcade_night"),
    SUNSET_CARTRIDGE("sunset_cartridge");

    companion object {
        fun fromStorage(value: String?): PixelThemeVariant {
            val normalized = value?.trim()?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.storageValue == normalized } ?: CANDY_POP
        }
    }
}

internal object AppVisualStyleSettings {
    const val PREFS_NAME = "llamadroid_settings"
    const val PREF_VISUAL_STYLE = "app_visual_style"
    const val PREF_PIXEL_VARIANT = "pixel_theme_variant"
    /** Opt-in pixel typeface for titles; default false (readable standard font). */
    const val PREF_PIXEL_FONT_ENABLED = "pixel_font_enabled"
    private const val PREF_HAS_COMPLETED_WELCOME = "has_completed_welcome"
    private const val PREF_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    private const val PREF_THEME_MODE = "theme_mode"
    private const val PREF_DYNAMIC_COLOR = "dynamic_color"

    private val lock = Any()

    /** Reads the stored style or null when it has not been resolved yet. */
    fun readStoredStyle(prefs: SharedPreferences): AppVisualStyle? =
        if (prefs.contains(PREF_VISUAL_STYLE)) {
            AppVisualStyle.fromStorage(prefs.getString(PREF_VISUAL_STYLE, null))
        } else {
            null
        }

    /**
     * Resolves and persists the style exactly once. Existing installs (any legacy
     * marker present) keep Soft Studio; brand-new installs get the pixel theme.
     */
    @SuppressLint("ApplySharedPref") // Must be durable before MainActivity writes last_seen_version_code.
    fun ensureResolved(prefs: SharedPreferences): AppVisualStyle = synchronized(lock) {
        readStoredStyle(prefs)?.let { return it }
        val lastSeen = runCatching { prefs.getLong(PREF_LAST_SEEN_VERSION_CODE, 0L) }
            .getOrElse { runCatching { prefs.getInt(PREF_LAST_SEEN_VERSION_CODE, 0).toLong() }.getOrDefault(0L) }
        val resolved = resolveInitialVisualStyle(
            hasCompletedWelcome = prefs.getBoolean(PREF_HAS_COMPLETED_WELCOME, false),
            lastSeenVersionCode = lastSeen,
            hasThemeModeKey = prefs.contains(PREF_THEME_MODE),
            hasDynamicColorKey = prefs.contains(PREF_DYNAMIC_COLOR)
        )
        prefs.edit(commit = true) {
            putString(PREF_VISUAL_STYLE, resolved.storageValue)
            if (!prefs.contains(PREF_PIXEL_VARIANT)) {
                putString(PREF_PIXEL_VARIANT, PixelThemeVariant.CANDY_POP.storageValue)
            }
        }
        resolved
    }
}

/** Pure decision table for the first-run visual style. */
fun resolveInitialVisualStyle(
    hasCompletedWelcome: Boolean,
    lastSeenVersionCode: Long,
    hasThemeModeKey: Boolean,
    hasDynamicColorKey: Boolean
): AppVisualStyle =
    if (hasCompletedWelcome || lastSeenVersionCode > 0L || hasThemeModeKey || hasDynamicColorKey) {
        AppVisualStyle.SOFT_STUDIO
    } else {
        AppVisualStyle.PIXEL
    }

/** Resolves the first-run visual style once; safe to call repeatedly from any thread. */
fun ensureVisualStyleResolved(context: Context): AppVisualStyle =
    AppVisualStyleSettings.ensureResolved(
        context.applicationContext.getSharedPreferences(AppVisualStyleSettings.PREFS_NAME, Context.MODE_PRIVATE)
    )
