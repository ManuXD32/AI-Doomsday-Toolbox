package com.example.llamadroid.data

import android.content.Context
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryDailySupportTest {
    private val today = LocalDate.of(2026, 9, 5).toEpochDay()

    @Before fun reset() {
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(
            "llamadroid_settings", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun repository() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test fun firstLaunchClaimsTodayAndAnotherRepositoryCannotRepeatIt() {
        assertTrue(repository().claimDailySupportPrompt(today))
        assertFalse(repository().claimDailySupportPrompt(today))
    }

    @Test fun nextLocalDayIsEligibleButClockRollbackDoesNotRepeatThePrompt() {
        assertTrue(repository().claimDailySupportPrompt(today))
        assertFalse(repository().claimDailySupportPrompt(today - 1))
        assertTrue(repository().claimDailySupportPrompt(today + 1))
        assertFalse(repository().claimDailySupportPrompt(today + 1))
    }

    @Test fun simultaneousLaunchChecksClaimOnlyOnce() {
        val first = repository()
        val second = repository()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(listOf(
                Callable { first.claimDailySupportPrompt(today) },
                Callable { second.claimDailySupportPrompt(today) }
            )).map { it.get() }
            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }
}
