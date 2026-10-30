package com.example.llamadroid.tama.shimeji

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Sprite size presets in dp (S/M/L). */
enum class ShimejiSize(val dp: Int) { SMALL(96), MEDIUM(128), LARGE(176) }

/** Overlay settings and presence accounting stored in SharedPreferences "tama_shimeji". */
object ShimejiSettings {
    const val PREFS = "tama_shimeji"
    const val KEY_ENABLED = "enabled"
    const val KEY_SIZE = "size"
    const val KEY_ACTIVITY = "activity"
    private const val KEY_PRES_DAY = "presence_day"
    private const val KEY_PRES_MINUTES = "presence_minutes"
    private const val KEY_PRES_CARRY = "presence_carry_ms"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun size(context: Context): ShimejiSize =
        ShimejiSize.entries.getOrElse(prefs(context).getInt(KEY_SIZE, ShimejiSize.MEDIUM.ordinal)) { ShimejiSize.MEDIUM }

    fun setSize(context: Context, size: ShimejiSize) {
        prefs(context).edit { putInt(KEY_SIZE, size.ordinal) }
    }

    fun activity(context: Context): ShimejiActivity =
        ShimejiActivity.entries.getOrElse(prefs(context).getInt(KEY_ACTIVITY, ShimejiActivity.NORMAL.ordinal)) { ShimejiActivity.NORMAL }

    fun setActivity(context: Context, activity: ShimejiActivity) {
        prefs(context).edit { putInt(KEY_ACTIVITY, activity.ordinal) }
    }

    fun loadPresence(context: Context): ShimejiPresenceState? {
        val p = prefs(context)
        if (!p.contains(KEY_PRES_DAY)) return null
        return ShimejiPresenceState(p.getLong(KEY_PRES_DAY, 0L), p.getInt(KEY_PRES_MINUTES, 0), p.getLong(KEY_PRES_CARRY, 0L))
    }

    fun savePresence(context: Context, state: ShimejiPresenceState) {
        prefs(context).edit {
            putLong(KEY_PRES_DAY, state.dayKey)
            putInt(KEY_PRES_MINUTES, state.minutesCredited)
            putLong(KEY_PRES_CARRY, state.carryMs)
        }
    }
}
