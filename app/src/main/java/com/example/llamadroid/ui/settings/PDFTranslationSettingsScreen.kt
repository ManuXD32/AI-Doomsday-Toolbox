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
import androidx.compose.material3.FilterChip
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
import com.example.llamadroid.data.LlamaOcrPromptPreset
import com.example.llamadroid.data.PdfOcrProvider
import com.example.llamadroid.data.PdfTranslationQualityMode
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
    val qualityMode by settingsRepo.pdfTranslationQualityMode.collectAsState()
    val ocrProvider by settingsRepo.pdfOcrProvider.collectAsState()
    val bubbleGuidedOcr by settingsRepo.pdfOcrBubbleGuided.collectAsState()
    val llamaOcrModelPath by settingsRepo.pdfOcrLlamaModelPath.collectAsState()
    val llamaOcrMmprojPath by settingsRepo.pdfOcrLlamaMmprojPath.collectAsState()
    val llamaOcrPromptPreset by settingsRepo.pdfOcrLlamaPromptPreset.collectAsState()
    val llamaOcrCustomPrompt by settingsRepo.pdfOcrLlamaCustomPrompt.collectAsState()
    val llamaOcrContextSize by settingsRepo.pdfOcrLlamaContextSize.collectAsState()
    val llamaOcrMaxTokens by settingsRepo.pdfOcrLlamaMaxTokens.collectAsState()
    val llamaOcrPort by settingsRepo.pdfOcrLlamaPort.collectAsState()
    val llamaOcrFlashAttention by settingsRepo.pdfOcrLlamaFlashAttention.collectAsState()
    val llamaOcrCacheRam by settingsRepo.pdfOcrLlamaCacheRam.collectAsState()
    val llamaOcrParallel by settingsRepo.pdfOcrLlamaParallel.collectAsState()
    val llamaOcrCustomFlags by settingsRepo.pdfOcrLlamaCustomFlags.collectAsState()
    val llamaOcrCommandTemplate by settingsRepo.pdfOcrLlamaCommandTemplate.collectAsState()
    val llamaOcrReplaceServer by settingsRepo.pdfOcrLlamaReplaceRunningServer.collectAsState()

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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.pdf_ocr_provider_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PdfOcrProvider.entries.forEach { provider ->
                        FilterChip(
                            modifier = Modifier.fillMaxWidth(),
                            selected = ocrProvider == provider,
                            onClick = { settingsRepo.setPdfOcrProvider(provider) },
                            label = {
                                Text(
                                    when (provider) {
                                        PdfOcrProvider.ML_KIT -> stringResource(R.string.pdf_ocr_provider_mlkit)
                                        PdfOcrProvider.LLAMA_CPP_GGUF -> stringResource(R.string.pdf_ocr_provider_llama_cpp)
                                    },
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
                TranslationSwitchRow(
                    title = stringResource(R.string.pdf_ocr_bubble_guided_title),
                    description = stringResource(R.string.pdf_ocr_bubble_guided_desc),
                    checked = bubbleGuidedOcr,
                    onCheckedChange = settingsRepo::setPdfOcrBubbleGuided
                )
                if (ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
                    OutlinedTextField(
                        value = llamaOcrModelPath.orEmpty(),
                        onValueChange = settingsRepo::setPdfOcrLlamaModelPath,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.pdf_ocr_llama_model_path_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = llamaOcrMmprojPath.orEmpty(),
                        onValueChange = settingsRepo::setPdfOcrLlamaMmprojPath,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.pdf_ocr_llama_mmproj_path_label)) },
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.pdf_ocr_llama_mmproj_path_desc)) }
                    )
                    Text(
                        stringResource(R.string.pdf_ocr_llama_prompt_preset_title),
                        fontWeight = FontWeight.Bold
                    )
                    LlamaOcrPromptPreset.entries.forEach { preset ->
                        FilterChip(
                            modifier = Modifier.fillMaxWidth(),
                            selected = llamaOcrPromptPreset == preset,
                            onClick = {
                                settingsRepo.setPdfOcrLlamaPromptPreset(preset)
                                settingsRepo.setPdfOcrLlamaCustomFlags(preset.recommendedFlags)
                            },
                            label = { Text(llamaOcrPromptPresetLabel(preset), maxLines = 1) }
                        )
                    }
                    OutlinedTextField(
                        value = llamaOcrCustomPrompt ?: llamaOcrPromptPreset.prompt,
                        onValueChange = settingsRepo::setPdfOcrLlamaCustomPrompt,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.pdf_ocr_llama_prompt_label)) },
                        minLines = 2
                    )
                    IntInputField(
                        value = llamaOcrContextSize,
                        onValueChange = settingsRepo::setPdfOcrLlamaContextSize,
                        label = stringResource(R.string.pdf_ocr_llama_context_label)
                    )
                    IntInputField(
                        value = llamaOcrMaxTokens,
                        onValueChange = settingsRepo::setPdfOcrLlamaMaxTokens,
                        label = stringResource(R.string.pdf_ocr_llama_max_tokens_label)
                    )
                    IntInputField(
                        value = llamaOcrPort,
                        onValueChange = settingsRepo::setPdfOcrLlamaPort,
                        label = stringResource(R.string.pdf_ocr_llama_port_label)
                    )
                    IntInputField(
                        value = llamaOcrCacheRam,
                        onValueChange = settingsRepo::setPdfOcrLlamaCacheRam,
                        label = stringResource(R.string.pdf_ocr_llama_cache_ram_label)
                    )
                    IntSliderWithInput(
                        value = llamaOcrParallel,
                        onValueChange = settingsRepo::setPdfOcrLlamaParallel,
                        valueRange = 1..8,
                        label = stringResource(R.string.pdf_ocr_llama_parallel_label)
                    )
                    TranslationSwitchRow(
                        title = stringResource(R.string.pdf_ocr_llama_flash_attention_title),
                        description = stringResource(R.string.pdf_ocr_llama_flash_attention_desc),
                        checked = llamaOcrFlashAttention,
                        onCheckedChange = settingsRepo::setPdfOcrLlamaFlashAttention
                    )
                    TranslationSwitchRow(
                        title = stringResource(R.string.pdf_ocr_llama_replace_server_title),
                        description = stringResource(R.string.pdf_ocr_llama_replace_server_desc),
                        checked = llamaOcrReplaceServer,
                        onCheckedChange = settingsRepo::setPdfOcrLlamaReplaceRunningServer
                    )
                    OutlinedTextField(
                        value = llamaOcrCustomFlags.orEmpty(),
                        onValueChange = settingsRepo::setPdfOcrLlamaCustomFlags,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.pdf_ocr_llama_custom_flags_label)) },
                        minLines = 2
                    )
                    OutlinedTextField(
                        value = llamaOcrCommandTemplate.orEmpty(),
                        onValueChange = settingsRepo::setPdfOcrLlamaCommandTemplate,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.pdf_ocr_llama_command_template_label)) },
                        minLines = 2
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        PdfTranslationQualityModeSelector(
                            value = qualityMode,
                            onValueChange = settingsRepo::setPdfTranslationQualityMode
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
private fun llamaOcrPromptPresetLabel(preset: LlamaOcrPromptPreset): String =
    when (preset) {
        LlamaOcrPromptPreset.UNLIMITED_OCR -> stringResource(R.string.pdf_ocr_llama_preset_unlimited)
        LlamaOcrPromptPreset.GLM_OCR -> stringResource(R.string.pdf_ocr_llama_preset_glm)
        LlamaOcrPromptPreset.DEEPSEEK_OCR -> stringResource(R.string.pdf_ocr_llama_preset_deepseek)
        LlamaOcrPromptPreset.HUNYUAN_OCR -> stringResource(R.string.pdf_ocr_llama_preset_hunyuan)
        LlamaOcrPromptPreset.PADDLEOCR_VL -> stringResource(R.string.pdf_ocr_llama_preset_paddleocr_vl)
        LlamaOcrPromptPreset.DOTS_OCR -> stringResource(R.string.pdf_ocr_llama_preset_dots)
        LlamaOcrPromptPreset.LIGHTON_OCR -> stringResource(R.string.pdf_ocr_llama_preset_lighton)
        LlamaOcrPromptPreset.QIANFAN_OCR -> stringResource(R.string.pdf_ocr_llama_preset_qianfan)
        LlamaOcrPromptPreset.GENERIC_OCR -> stringResource(R.string.pdf_ocr_llama_preset_generic)
    }

@Composable
fun PdfTranslationQualityModeSelector(
    value: PdfTranslationQualityMode,
    onValueChange: (PdfTranslationQualityMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val description = when (value) {
        PdfTranslationQualityMode.BEST_QUALITY -> stringResource(R.string.pdf_translation_quality_best_desc)
        PdfTranslationQualityMode.BALANCED -> stringResource(R.string.pdf_translation_quality_balanced_desc)
        PdfTranslationQualityMode.FASTER -> stringResource(R.string.pdf_translation_quality_faster_desc)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.pdf_translation_quality_title),
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PdfTranslationQualityMode.values().forEach { mode ->
                FilterChip(
                    modifier = Modifier.fillMaxWidth(),
                    selected = value == mode,
                    onClick = { onValueChange(mode) },
                    label = {
                        Text(
                            when (mode) {
                                PdfTranslationQualityMode.BEST_QUALITY -> stringResource(R.string.pdf_translation_quality_best)
                                PdfTranslationQualityMode.BALANCED -> stringResource(R.string.pdf_translation_quality_balanced)
                                PdfTranslationQualityMode.FASTER -> stringResource(R.string.pdf_translation_quality_faster)
                            },
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
