package com.example.llamadroid.service

import android.app.ForegroundServiceStartNotAllowedException
import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskScheduleType
import com.example.llamadroid.util.AppVersionCodeCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** Regression coverage for code that must remain executable on the app's API 26 minimum. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30])
class Api26CompatibilityTest {
    @Test
    fun `scheduled date conversion works on every supported pre Android 12 API`() {
        assertTrue(Build.VERSION.SDK_INT in setOf(26, 28, 30))
        val zone = ZoneId.of("Europe/Madrid")
        val after = LocalDateTime.of(2026, 5, 1, 8, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val task = LlamaScheduledTaskEntity(
            id = 1,
            name = "Daily test",
            enabled = true,
            taskPrompt = "Test",
            scheduleType = LlamaScheduledTaskScheduleType.DAILY,
            oneTimeAtMillis = null,
            timeOfDayMinutes = 9 * 60,
            weekdaysMask = 0,
            dayOfMonth = 1,
            timezoneId = zone.id
        )

        val expected = LocalDateTime.of(2026, 5, 1, 9, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(expected, LlamaScheduledTaskSchedule.computeNextRun(task, after))
    }

    @Test
    fun `foreground service denial classifier does not load API 31 exception on older APIs`() {
        assertTrue(Build.VERSION.SDK_INT in setOf(26, 28, 30))
        assertFalse(isForegroundServiceStartNotAllowed(IllegalStateException("denied")))
        assertFalse(isForegroundServiceStartNotAllowed(RuntimeException("denied")))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `package version uses the AndroidX compatibility accessor`() {
        val packageInfo = PackageInfo().apply {
            versionCode = 95_570
        }

        assertEquals(95_570L, AppVersionCodeCompat.read(packageInfo))
    }

    @Test
    @Config(sdk = [31])
    @SuppressLint("UseSdkSuppress")
    @RequiresApi(Build.VERSION_CODES.S)
    fun `foreground service denial classifier recognizes the API 31 exception`() {
        assertTrue(
            isForegroundServiceStartNotAllowed(
                ForegroundServiceStartNotAllowedException("background start denied")
            )
        )
        assertFalse(isForegroundServiceStartNotAllowed(IllegalStateException("denied")))
    }
}
