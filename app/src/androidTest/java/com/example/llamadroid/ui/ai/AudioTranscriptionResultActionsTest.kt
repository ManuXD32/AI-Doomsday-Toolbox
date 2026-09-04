package com.example.llamadroid.ui.ai

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioTranscriptionResultActionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resultActionsExposeAccessibleTargetsAndCopyExactTextAtLargeFont() {
        val result = "Step 5 transcription result"
        val copyLabel = composeRule.activity.getString(R.string.action_copy)
        val shareLabel = composeRule.activity.getString(R.string.action_share)

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f)
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TranscriptionResultCard(result = result)
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription(copyLabel)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription(shareLabel)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)

        composeRule.runOnIdle {
            val clipboard = composeRule.activity.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager
            assertEquals(
                result,
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(composeRule.activity)?.toString()
            )
            clipboard.clearPrimaryClip()
        }
    }

    @Test
    fun shareIntentUsesPlainTextAndCarriesTheExactResult() {
        val result = "Share this transcription"
        val intent = createTranscriptionShareIntent(result)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(result, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
