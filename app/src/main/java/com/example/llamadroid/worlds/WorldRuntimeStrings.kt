package com.example.llamadroid.worlds

import android.content.Context
import com.example.llamadroid.R

/** Localized shim labels (keys match `DEFAULTS` in assets/worlds/adt-shim.js). */
object WorldRuntimeStrings {
    fun labels(context: Context): Map<String, String> = linkedMapOf(
        "joystick" to context.getString(R.string.worlds_runtime_joystick),
        "threeMissing" to context.getString(R.string.worlds_runtime_three_missing),
        "fly" to context.getString(R.string.worlds_runtime_fly),
        "walk" to context.getString(R.string.worlds_runtime_walk),
        "up" to context.getString(R.string.worlds_runtime_up),
        "down" to context.getString(R.string.worlds_runtime_down),
        "reset" to context.getString(R.string.worlds_runtime_reset),
        "goTo" to context.getString(R.string.worlds_runtime_go_to),
        "hint" to context.getString(R.string.worlds_runtime_hint),
        "help" to context.getString(R.string.worlds_runtime_help),
        "noSpots" to context.getString(R.string.worlds_runtime_no_spots),
        "errorTitle" to context.getString(R.string.worlds_runtime_error_title),
        "truncatedHint" to context.getString(R.string.worlds_runtime_truncated_hint),
        "blankHint" to context.getString(R.string.worlds_runtime_blank_hint),
        "closePanel" to context.getString(R.string.worlds_runtime_close_panel),
        "touchControls" to context.getString(R.string.worlds_runtime_touch_controls),
        "hideUi" to context.getString(R.string.worlds_ui_hide_controls),
        "showUi" to context.getString(R.string.worlds_ui_show_controls),
        "diagnostics" to context.getString(R.string.worlds_diag_open),
    )

    /** Convenience for callers: detect-and-build with localized labels. */
    fun buildPage(context: Context, source: WorldSource): String =
        WorldPageBuilder.build(source, labels(context))
}
