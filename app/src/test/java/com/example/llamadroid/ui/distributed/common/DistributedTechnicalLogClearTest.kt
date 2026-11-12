package com.example.llamadroid.ui.distributed.common

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.llamadroid.service.SdDistributedService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class DistributedTechnicalLogClearTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val lines = listOf("line one", "line two")

    @Test
    fun deleteConfirmCallsOnClearOnce() {
        var calls = 0
        rule.setContent { DistributedTechnicalLogSection(lines = lines, reveal = true, onClear = { calls++ }) }
        rule.onNodeWithContentDescription("Clear log").performClick()
        rule.onNodeWithText("Clear this log?").assertExists()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, calls)
    }

    @Test
    fun noDeleteButtonWithoutOnClear() {
        rule.setContent { DistributedTechnicalLogSection(lines = lines, reveal = true) }
        rule.onNodeWithContentDescription("Clear log").assertDoesNotExist()
    }

    @Test
    fun clearLogsEmptiesServiceLogs() {
        SdDistributedService.appendLog("hello")
        assertTrue(SdDistributedService.logs.value.isNotEmpty())
        SdDistributedService.clearLogs()
        assertTrue(SdDistributedService.logs.value.isEmpty())
    }
}
