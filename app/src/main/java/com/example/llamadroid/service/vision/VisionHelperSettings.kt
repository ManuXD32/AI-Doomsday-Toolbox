package com.example.llamadroid.service.vision

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Own preferences for the vision helper (kept out of SettingsRepository on purpose). */
class VisionHelperSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Id of the managed local server card (its model plus mmproj) that describes images; 0 = none. */
    var serverRowId: Long
        get() = prefs.getLong(KEY_SERVER, 0L)
        set(value) = prefs.edit { putLong(KEY_SERVER, value) }

    var thinking: Boolean
        get() = prefs.getBoolean(KEY_THINKING, false)
        set(value) = prefs.edit { putBoolean(KEY_THINKING, value) }

    private companion object {
        const val PREFS = "vision_helper"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER = "server_row_id"
        const val KEY_THINKING = "thinking"
    }
}
