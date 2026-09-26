package com.example.llamadroid.tama.world.presentation

import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldTimeFormattingTest {
    @Test
    fun savedOffsetFormatsTheFrozenWorldClock() {
        val frozen = Instant.parse("2026-01-02T12:08:00Z").toEpochMilli()

        assertEquals("12:08", formatWorldTime(frozen, 0, Locale.US))
        assertEquals("14:08", formatWorldTime(frozen, 120, Locale.US))
        assertEquals("07:08", formatWorldTime(frozen, -300, Locale.US))
    }

    @Test
    fun spanishFormattingUsesTheSameReadableLocalClock() {
        val frozen = Instant.parse("2026-01-02T12:08:00Z").toEpochMilli()

        assertEquals("14:08", formatWorldTime(frozen, 120, Locale.forLanguageTag("es-ES")))
    }
}
