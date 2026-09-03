package com.example.llamadroid.service

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ApplicationExitInfoCompatTest {
    @Test
    @Config(sdk = [26])
    fun `API 26 does not enter ApplicationExitInfo path`() {
        assertEquals(26, Build.VERSION.SDK_INT)
        assertFalse(isApplicationExitInfoAvailable())

        GenerationDiagnosticsStore.captureLatestExitReasonIfNeeded()
        assertFalse(isApplicationExitInfoAvailable())
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 does not enter ApplicationExitInfo path`() {
        assertEquals(28, Build.VERSION.SDK_INT)
        assertFalse(isApplicationExitInfoAvailable())

        GenerationDiagnosticsStore.captureLatestExitReasonIfNeeded()
        assertFalse(isApplicationExitInfoAvailable())
    }

    @Test
    @Config(sdk = [30])
    @SuppressLint("UseSdkSuppress")
    @RequiresApi(Build.VERSION_CODES.R)
    fun `API 30 enters the isolated ApplicationExitInfo adapter`() {
        assertEquals(30, Build.VERSION.SDK_INT)
        assertTrue(isApplicationExitInfoAvailable())

        val context = RuntimeEnvironment.getApplication()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val records = ApplicationExitInfoApi30.readRecords(context, activityManager, maxCount = 30)

        assertNotNull(records)
    }
}
