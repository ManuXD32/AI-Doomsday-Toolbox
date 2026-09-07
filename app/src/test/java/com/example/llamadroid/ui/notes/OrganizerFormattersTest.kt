package com.example.llamadroid.ui.notes

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerFormattersTest {
    @Test
    fun `display uses the supplied timezone and 24 hour preference`() {
        val millis = Instant.parse("2026-01-02T23:30:00Z").toEpochMilli()

        val actual = formatOrganizerDateTimeForDisplay(
            millis = millis,
            locale = Locale.US,
            zone = ZoneId.of("Europe/Madrid"),
            uses24HourClock = true
        )

        assertEquals("Jan 3, 2026, 00:30", actual)
    }

    @Test
    fun `display uses localized date and 12 hour preference`() {
        val millis = Instant.parse("2026-01-03T00:30:00Z").toEpochMilli()

        val actual = formatOrganizerDateTimeForDisplay(
            millis = millis,
            locale = Locale.US,
            zone = ZoneId.of("America/New_York"),
            uses24HourClock = false
        )

        assertEquals("Jan 2, 2026, 7:30 PM", actual)
    }

    @Test
    fun `display honors Spanish locale`() {
        val millis = Instant.parse("2026-01-02T23:30:00Z").toEpochMilli()

        val actual = formatOrganizerDateTimeForDisplay(
            millis = millis,
            locale = Locale.forLanguageTag("es-ES"),
            zone = ZoneId.of("Europe/Madrid"),
            uses24HourClock = true
        )

        assertTrue(actual.startsWith("3 ene"))
        assertTrue(actual.contains("2026"))
        assertTrue(actual.endsWith("00:30"))
    }
}
