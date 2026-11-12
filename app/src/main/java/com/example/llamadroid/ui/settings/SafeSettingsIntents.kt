package com.example.llamadroid.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.example.llamadroid.R

/** Guarded launchers for system screens that some OEM builds do not ship. */
object SafeSettingsIntents {
    /** Opens battery-optimization settings; falls back to general settings, then a toast. */
    fun openBatterySettings(context: Context) {
        val actions = listOf(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (action in actions) {
            val ok = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        showToast(context, R.string.startup_settings_unavailable)
    }

    fun showToast(context: Context, resId: Int) {
        runCatching { Toast.makeText(context, resId, Toast.LENGTH_LONG).show() }
    }
}

/** Launches a document/folder picker; shows a toast instead of crashing when no handler exists. */
fun <I> ActivityResultLauncher<I>.launchSafely(context: Context, input: I) {
    runCatching { launch(input) }.onFailure {
        SafeSettingsIntents.showToast(context, R.string.startup_folder_picker_unavailable)
    }
}
