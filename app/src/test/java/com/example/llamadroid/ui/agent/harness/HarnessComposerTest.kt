package com.example.llamadroid.ui.agent.harness

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessComposerTest {
    @Test
    fun explicitMenusSeparateTheirTriggerFromExistingText() {
        for (trigger in HarnessComposerTrigger.entries) {
            val inserted = insertHarnessComposerTrigger(TextFieldValue("look here", TextRange(9)), trigger)
            val replacement = if (trigger == HarnessComposerTrigger.AT) "@file" else "/compact"
            assertEquals("look here $replacement ", replaceHarnessComposerToken(inserted, replacement, trigger).text)
        }
    }

    @Test
    fun slashInsertionReplacesOnlyTheTokenBeforeTheCursor() {
        val value = TextFieldValue("before /run after", TextRange(11))

        val result = replaceHarnessComposerToken(
            value = value,
            insertion = "/review ",
            trigger = HarnessComposerTrigger.SLASH,
        )

        assertEquals("before /review after", result.text)
        assertEquals(15, result.selection.end)
    }

    @Test
    fun mentionInsertionPreservesTextAfterTheCursor() {
        val value = TextFieldValue("look @\"ses later", TextRange(10))

        val result = replaceHarnessComposerToken(
            value = value,
            insertion = "@\"src/file name.kt\"",
            trigger = HarnessComposerTrigger.AT,
        )

        assertEquals("look @\"src/file name.kt\" later", result.text)
        assertEquals(24, result.selection.end)
    }

    @Test
    fun literalTextIsUntouchedWhenItDoesNotMatchTheTrigger() {
        val value = TextFieldValue("plain text", TextRange(10))

        assertEquals(
            value,
            replaceHarnessComposerToken(value, "/command", HarnessComposerTrigger.SLASH),
        )
    }
}
