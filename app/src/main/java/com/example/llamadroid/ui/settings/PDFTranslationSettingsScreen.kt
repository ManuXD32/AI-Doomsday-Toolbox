package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.PDFTranslationLogic
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.IntInputField
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.SliderWithInput

@Composable
fun PDFTranslationSettingsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    AppScreenScaffold(
        title = stringResource(R.string.pdf_translation_settings_title),
        subtitle = stringResource(R.string.pdf_translation_settings_subtitle),
        onBack = { navController.popBackStack() }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PDFTranslationEmbeddedSettings(settingsRepo = settingsRepo)
            }
        }
    }
}

@Composable
fun PDFTranslationEmbeddedSettings(settingsRepo: SettingsRepository) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backend by settingsRepo.pdfTranslationBackend.collectAsState()
    val ollamaUrl by settingsRepo.pdfTranslationOllamaUrl.collectAsState()
    val llamaServerUrl by settingsRepo.pdfTranslationLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepo.pdfTranslationLlamaSwapUrl.collectAsState()
    val ollamaModel by settingsRepo.pdfTranslationOllamaModel.collectAsState()
    val llamaSwapModel by settingsRepo.pdfTranslationLlamaSwapModel.collectAsState()
    val contextSize by settingsRepo.pdfTranslationContextSize.collectAsState()
    val maxTokens by settingsRepo.pdfTranslationMaxTokens.collectAsState()
    val temperature by settingsRepo.pdfTranslationTemperature.collectAsState()
    val timeoutMinutes by settingsRepo.pdfTranslationTimeoutMinutes.collectAsState()
    val targetLanguage by settingsRepo.pdfTranslationTargetLanguage.collectAsState()
    val prompt by settingsRepo.pdfTranslationPrompt.collectAsState()
    val serverModelLabel by settingsRepo.pdfTranslationLlamaServerModelLabel.collectAsState()
    val serverContextLabel by settingsRepo.pdfTranslationLlamaServerContextLabel.collectAsState()
    val serverContextTokens by settingsRepo.pdfTranslationLlamaServerContextTokens.collectAsState()
    val liteRtModelId by settingsRepo.pdfTranslationLiteRtModelId.collectAsState()
    val liteRtBackend by settingsRepo.pdfTranslationLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepo.pdfTranslationLiteRtMtpEnabled.collectAsState()
    val liteRtThinkingEnabled by settingsRepo.pdfTranslationThinkingEnabled.collectAsState()
    val screenshotContext by settingsRepo.pdfTranslationScreenshotContext.collectAsState()
    val screenshotMaxSide by settingsRepo.pdfTranslationScreenshotMaxSide.collectAsState()
    val screenshotQuality by settingsRepo.pdfTranslationScreenshotJpegQuality.collectAsState()
    val textFallback by settingsRepo.pdfTranslationTextFallback.collectAsState()

    fun persistMetadata(metadata: RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setPdfTranslationLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setPdfTranslationLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setPdfTranslationLlamaServerContextLabel(metadata.serverContextLabel)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RemoteSummaryBackendEditor(
                    title = stringResource(R.string.pdf_translation_backend_title),
                    backend = backend,
                    onBackendChange = settingsRepo::setPdfTranslationBackend,
                    ollamaUrl = ollamaUrl,
                    onOllamaUrlChange = settingsRepo::setPdfTranslationOllamaUrl,
                    llamaServerUrl = llamaServerUrl,
                    onLlamaServerUrlChange = settingsRepo::setPdfTranslationLlamaServerUrl,
                    llamaSwapUrl = llamaSwapUrl,
                    onLlamaSwapUrlChange = settingsRepo::setPdfTranslationLlamaSwapUrl,
                    ollamaModel = ollamaModel,
                    onOllamaModelSelected = settingsRepo::setPdfTranslationOllamaModel,
                    llamaSwapModel = llamaSwapModel,
                    onLlamaSwapModelSelected = settingsRepo::setPdfTranslationLlamaSwapModel,
                    llamaServerModelLabel = serverModelLabel,
                    llamaServerContextLabel = serverContextLabel,
                    llamaServerContextTokens = serverContextTokens,
                    requestedContextForWarning = contextSize,
                    liteRtModelId = liteRtModelId.takeIf { it > 0L },
                    onLiteRtModelSelected = settingsRepo::setPdfTranslationLiteRtModelId,
                    liteRtBackend = liteRtBackend,
                    onLiteRtBackendChange = settingsRepo::setPdfTranslationLiteRtBackend,
                    liteRtMtpEnabled = liteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = settingsRepo::setPdfTranslationLiteRtMtpEnabled,
                    liteRtThinkingEnabled = liteRtThinkingEnabled,
                    onLiteRtThinkingEnabledChange = settingsRepo::setPdfTranslationThinkingEnabled,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromSnapshot(context, settingsRepo.pdfTranslationSettings.snapshot())
                            .fetchMetadata()
                    },
                    onMetadataLoaded = ::persistMetadata
        )

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        PdfTranslationLanguagePicker(
                            value = targetLanguage,
                            onValueChange = settingsRepo::setPdfTranslationTargetLanguage
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        IntInputField(
                            value = contextSize,
                            onValueChange = settingsRepo::setPdfTranslationContextSize,
                            label = stringResource(R.string.pdf_context_size_label)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntInputField(
                            value = maxTokens,
                            onValueChange = settingsRepo::setPdfTranslationMaxTokens,
                            label = stringResource(R.string.pdf_max_tokens_label)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SliderWithInput(
                            value = temperature,
                            onValueChange = settingsRepo::setPdfTranslationTemperature,
                            valueRange = SettingsRepository.PDF_TEMPERATURE_MIN..SettingsRepository.PDF_TEMPERATURE_MAX,
                            label = stringResource(R.string.pdf_temperature_label),
                            decimalPlaces = 1
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntSliderWithInput(
                            value = timeoutMinutes,
                            onValueChange = settingsRepo::setPdfTranslationTimeoutMinutes,
                            valueRange = SettingsRepository.PDF_TIMEOUT_MINUTES_RANGE,
                            label = stringResource(R.string.pdf_timeout_label),
                            suffix = stringResource(R.string.pdf_minutes_suffix)
                        )
                    }
                }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = prompt ?: PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
                            onValueChange = settingsRepo::setPdfTranslationPrompt,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.pdf_translation_prompt_label)) },
                            minLines = 4,
                            supportingText = { Text(stringResource(R.string.pdf_translation_prompt_desc)) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TranslationSwitchRow(
                            title = stringResource(R.string.pdf_translation_screenshot_context_title),
                            description = stringResource(R.string.pdf_translation_screenshot_context_desc),
                            checked = screenshotContext,
                            onCheckedChange = settingsRepo::setPdfTranslationScreenshotContext
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntSliderWithInput(
                            value = screenshotMaxSide,
                            onValueChange = settingsRepo::setPdfTranslationScreenshotMaxSide,
                            valueRange = 480..2400,
                            label = stringResource(R.string.pdf_translation_screenshot_max_side_label),
                            suffix = "px"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntSliderWithInput(
                            value = screenshotQuality,
                            onValueChange = settingsRepo::setPdfTranslationScreenshotJpegQuality,
                            valueRange = 40..95,
                            label = stringResource(R.string.pdf_translation_screenshot_quality_label),
                            suffix = "%"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TranslationSwitchRow(
                            title = stringResource(R.string.pdf_translation_text_fallback_title),
                            description = stringResource(R.string.pdf_translation_text_fallback_desc),
                            checked = textFallback,
                            onCheckedChange = settingsRepo::setPdfTranslationTextFallback
                        )
                    }
                }
    }
}

@Composable
private fun TranslationSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
