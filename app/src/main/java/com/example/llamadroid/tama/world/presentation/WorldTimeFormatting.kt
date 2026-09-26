package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.core.WorldClock
import java.util.Locale

/**
 * Formats the saved living-world clock, rather than the device's current time.
 * The persisted offset is applied before extracting the local hour and minute,
 * which keeps a frozen world stable across recompositions and device time-zone
 * changes.
 */
internal fun formatWorldTime(
    epochMillis: Long,
    timezoneOffsetMinutes: Int,
    locale: Locale
): String {
    val local = WorldClock.at(epochMillis, timezoneOffsetMinutes)
    return String.format(
        locale,
        "%02d:%02d",
        local.minuteOfDay / 60,
        local.minuteOfDay % 60
    )
}
