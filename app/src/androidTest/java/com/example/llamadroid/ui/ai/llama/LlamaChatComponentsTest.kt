package com.example.llamadroid.ui.ai.llama

import androidx.activity.ComponentActivity
import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaChatComponentsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun assistantMessageShowsVisibleCopyAction() {
        composeRule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = LlamaMessageEntity(
                        id = 1,
                        chatId = 1,
                        role = "assistant",
                        content = "Hello from the assistant"
                    ),
                    onRegenerate = {},
                    onEdit = {},
                    onRetry = {},
                    retryEnabled = true,
                    onRetryTranscription = {},
                    onDiscardFailedMessage = {},
                    onDelete = {}
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.action_copy))
            .assertIsDisplayed()
    }

    @Test
    fun codeBlockCopyActionCopiesCodeToClipboard() {
        val expectedCode = "val answer = 42"

        composeRule.setContent {
            MaterialTheme {
                MarkdownText(
                    text = """
                        ```kotlin
                        $expectedCode
                        ```
                    """.trimIndent(),
                    textColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.llama_copy_code))
            .performClick()

        composeRule.runOnIdle {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            val copied = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(composeRule.activity)?.toString()
            assertEquals(expectedCode, copied)
        }
    }

    @Test
    fun deleteDialogWaitsForConfirmation() {
        var confirmed = false
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                LlamaDeleteMessageDialog(
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.action_cancel))
            .performClick()

        composeRule.runOnIdle {
            assertFalse(confirmed)
            assertTrue(dismissed)
        }
    }

    @Test
    fun userMessageShowsRetryActionAndTranscriptHeader() {
        composeRule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = LlamaMessageEntity(
                        id = 2,
                        chatId = 1,
                        role = "user",
                        content = "Please summarize this\n\nThis is the transcription of an audio sent by the user: Hello from the recording"
                    ),
                    onRegenerate = {},
                    onEdit = {},
                    onRetry = {},
                    retryEnabled = true,
                    onRetryTranscription = {},
                    onDiscardFailedMessage = {},
                    onDelete = {}
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.action_retry))
            .assertIsDisplayed()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.llama_audio_transcription))
            .assertIsDisplayed()
    }

    @Test
    fun failedTranscriptionShowsRetryPrompt() {
        val audioFile = composeRule.activity.cacheDir.resolve("failed_transcription_test.m4a").apply {
            writeText("placeholder")
        }

        composeRule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = LlamaMessageEntity(
                        id = 3,
                        chatId = 1,
                        role = "user",
                        content = "Please summarize this",
                        audioPath = audioFile.absolutePath,
                        isError = true
                    ),
                    onRegenerate = {},
                    onEdit = {},
                    onRetry = {},
                    retryEnabled = false,
                    onRetryTranscription = {},
                    onDiscardFailedMessage = {},
                    onDelete = {}
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.llama_transcription_error_prompt))
            .assertIsDisplayed()

        audioFile.delete()
    }
}
