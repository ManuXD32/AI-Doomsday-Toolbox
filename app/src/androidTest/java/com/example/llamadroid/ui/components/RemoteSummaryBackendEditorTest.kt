package com.example.llamadroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.RemoteSummaryMetadata
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSummaryBackendEditorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun urlDraftSurvivesParentFallbackAndBackendSwitch() {
        composeRule.setContent {
            var backend by remember { mutableStateOf(SettingsRepository.PDF_BACKEND_OLLAMA) }
            var ollamaUrl by remember { mutableStateOf("http://persisted:11434") }
            var llamaServerUrl by remember { mutableStateOf("http://server:8080") }
            var llamaSwapUrl by remember { mutableStateOf("http://swap:9292") }

            MaterialTheme {
                RemoteSummaryBackendEditor(
                    title = "Remote",
                    backend = backend,
                    onBackendChange = { backend = it },
                    ollamaUrl = ollamaUrl,
                    onOllamaUrlChange = {
                        ollamaUrl = if (it.isBlank()) {
                            "http://persisted:11434"
                        } else {
                            it
                        }
                    },
                    llamaServerUrl = llamaServerUrl,
                    onLlamaServerUrlChange = { llamaServerUrl = it },
                    llamaSwapUrl = llamaSwapUrl,
                    onLlamaSwapUrlChange = { llamaSwapUrl = it },
                    ollamaModel = null,
                    onOllamaModelSelected = {},
                    llamaSwapModel = null,
                    onLlamaSwapModelSelected = {},
                    llamaServerModelLabel = null,
                    llamaServerContextLabel = null,
                    llamaServerContextTokens = 0,
                    requestedContextForWarning = null,
                    fetchMetadata = {
                        Result.success(
                            RemoteSummaryMetadata(
                                backend = SettingsRepository.PDF_BACKEND_OLLAMA,
                                baseUrl = ollamaUrl
                            )
                        )
                    },
                    onMetadataLoaded = {}
                )
            }
        }

        val urlField = composeRule.onNodeWithTag("remote_summary_url_field")
        urlField.performTextClearance()
        urlField.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("")
            )
        )
        "https://demo.local/api".forEach { urlField.performTextInput(it.toString()) }
        urlField.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("https://demo.local/api")
            )
        )

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.pdf_backend_llama_server))
            .performClick()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.pdf_backend_ollama))
            .performClick()

        composeRule.onNodeWithTag("remote_summary_url_field").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("https://demo.local/api")
            )
        )
    }
}
