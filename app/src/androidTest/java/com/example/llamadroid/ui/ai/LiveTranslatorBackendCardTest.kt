package com.example.llamadroid.ui.ai

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_OLLAMA
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTranslatorBackendCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editingUrlKeepsExactTypedValueInsteadOfRebuildingHostPort() {
        composeRule.setContent {
            var backendEngine by remember { mutableStateOf(LIVE_TRANSLATOR_ENGINE_OLLAMA) }
            var llamaServerUrl by remember { mutableStateOf("http://localhost:8080") }
            var llamaSwapUrl by remember { mutableStateOf("http://localhost:9292") }
            var llamaModelName by remember { mutableStateOf("") }
            var ollamaUrl by remember { mutableStateOf("http://localhost:11434") }
            var ollamaModelName by remember { mutableStateOf("") }
            var contextSize by remember { mutableStateOf("4096") }
            var maxTokens by remember { mutableStateOf("512") }
            var temperature by remember { mutableFloatStateOf(0.2f) }
            var timeoutSeconds by remember { mutableStateOf("120") }

            MaterialTheme {
                LiveTranslatorBackendCard(
                    liteRtModels = emptyList(),
                    backendEngine = backendEngine,
                    onBackendEngineChange = { backendEngine = it },
                    llamaServerUrl = llamaServerUrl,
                    onLlamaServerUrlChange = { llamaServerUrl = it },
                    llamaSwapUrl = llamaSwapUrl,
                    onLlamaSwapUrlChange = { llamaSwapUrl = it },
                    llamaModelName = llamaModelName,
                    onLlamaModelNameChange = { llamaModelName = it },
                    ollamaUrl = ollamaUrl,
                    onOllamaUrlChange = { ollamaUrl = it },
                    ollamaModelName = ollamaModelName,
                    onOllamaModelNameChange = { ollamaModelName = it },
                    liteRtModelId = null,
                    onLiteRtModelIdChange = {},
                    liteRtBackend = "auto",
                    onLiteRtBackendChange = {},
                    liteRtMtpEnabled = false,
                    onLiteRtMtpEnabledChange = {},
                    liteRtThinkingEnabled = false,
                    onLiteRtThinkingEnabledChange = {},
                    contextSize = contextSize,
                    onContextSizeChange = { contextSize = it },
                    maxTokens = maxTokens,
                    onMaxTokensChange = { maxTokens = it },
                    temperature = temperature,
                    onTemperatureChange = { temperature = it },
                    timeoutSeconds = timeoutSeconds,
                    onTimeoutSecondsChange = { timeoutSeconds = it }
                )
            }
        }

        val urlField = composeRule.onNodeWithTag("remote_summary_url_field")
        urlField.performTextClearance()
        "https://demo.local/custom/path".forEach { urlField.performTextInput(it.toString()) }

        composeRule
            .onNodeWithTag("remote_summary_url_field")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("https://demo.local/custom/path")
                )
            )
    }
}
