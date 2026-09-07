package com.example.llamadroid.ui.notes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Formats an organizer timestamp for display without changing the persisted instant.
 *
 * The caller supplies the device locale, timezone, and 12/24-hour preference so this
 * formatter stays deterministic and remains straightforward to exercise in JVM tests.
 */
internal fun formatOrganizerDateTimeForDisplay(
    millis: Long,
    locale: Locale,
    zone: ZoneId,
    uses24HourClock: Boolean
): String {
    val zonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
    val dateFormatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
    val timeFormatter = DateTimeFormatter
        .ofPattern(if (uses24HourClock) "HH:mm" else "h:mm a", locale)
    return "${dateFormatter.format(zonedDateTime)}, ${timeFormatter.format(zonedDateTime)}"
}
