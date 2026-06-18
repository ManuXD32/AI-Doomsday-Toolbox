package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.PDFSummaryService
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.IntInputField
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.components.SliderWithInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFSettingsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    val backend by settingsRepo.pdfSummaryBackend.collectAsState()
    val ollamaUrl by settingsRepo.pdfSummaryOllamaUrl.collectAsState()
    val llamaServerUrl by settingsRepo.pdfSummaryLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepo.pdfSummaryLlamaSwapUrl.collectAsState()
    val ollamaModel by settingsRepo.pdfSummaryOllamaModel.collectAsState()
    val llamaSwapModel by settingsRepo.pdfSummaryLlamaSwapModel.collectAsState()
    val thinkingEnabled by settingsRepo.pdfSummaryThinkingEnabled.collectAsState()
    val pdfContextSize by settingsRepo.pdfContextSize.collectAsState()
    val pdfTemperature by settingsRepo.pdfTemperature.collectAsState()
    val pdfMaxTokens by settingsRepo.pdfMaxTokens.collectAsState()
    val pdfMergeContextSize by settingsRepo.pdfMergeContextSize.collectAsState()
    val pdfMergeMaxTokens by settingsRepo.pdfMergeMaxTokens.collectAsState()
    val pdfSummaryTimeoutMinutes by settingsRepo.pdfSummaryTimeoutMinutes.collectAsState()
    val pdfTargetLanguage by settingsRepo.pdfSummaryTargetLanguage.collectAsState()
    val pdfSummaryPrompt by settingsRepo.pdfSummaryPrompt.collectAsState()
    val serverModelLabel by settingsRepo.pdfSummaryLlamaServerModelLabel.collectAsState()
    val serverContextLabel by settingsRepo.pdfSummaryLlamaServerContextLabel.collectAsState()
    val serverContextTokens by settingsRepo.pdfSummaryLlamaServerContextTokens.collectAsState()
    val liteRtModelId by settingsRepo.pdfSummaryLiteRtModelId.collectAsState()
    val liteRtBackend by settingsRepo.pdfSummaryLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepo.pdfSummaryLiteRtMtpEnabled.collectAsState()

    fun persistMetadata(metadata: RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setPdfSummaryLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setPdfSummaryLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setPdfSummaryLlamaServerContextLabel(metadata.serverContextLabel)
        }
    }

    AppScreenScaffold(
        title = stringResource(R.string.pdf_settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        onBack = { navController.popBackStack() }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                RemoteSummaryBackendEditor(
                    title = stringResource(R.string.pdf_backend_title),
                    backend = backend,
                    onBackendChange = settingsRepo::setPdfSummaryBackend,
                    ollamaUrl = ollamaUrl,
                    onOllamaUrlChange = settingsRepo::setPdfSummaryOllamaUrl,
                    llamaServerUrl = llamaServerUrl,
                    onLlamaServerUrlChange = settingsRepo::setPdfSummaryLlamaServerUrl,
                    llamaSwapUrl = llamaSwapUrl,
                    onLlamaSwapUrlChange = settingsRepo::setPdfSummaryLlamaSwapUrl,
                    ollamaModel = ollamaModel,
                    onOllamaModelSelected = settingsRepo::setPdfSummaryOllamaModel,
                    llamaSwapModel = llamaSwapModel,
                    onLlamaSwapModelSelected = settingsRepo::setPdfSummaryLlamaSwapModel,
                    llamaServerModelLabel = serverModelLabel,
                    llamaServerContextLabel = serverContextLabel,
                    llamaServerContextTokens = serverContextTokens,
                    requestedContextForWarning = pdfMergeContextSize,
                    liteRtModelId = liteRtModelId.takeIf { it > 0L },
                    onLiteRtModelSelected = settingsRepo::setPdfSummaryLiteRtModelId,
                    liteRtBackend = liteRtBackend,
                    onLiteRtBackendChange = settingsRepo::setPdfSummaryLiteRtBackend,
                    liteRtMtpEnabled = liteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = settingsRepo::setPdfSummaryLiteRtMtpEnabled,
                    liteRtThinkingEnabled = thinkingEnabled,
                    onLiteRtThinkingEnabledChange = settingsRepo::setPdfSummaryThinkingEnabled,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromSnapshot(context, settingsRepo.pdfSummarySettings.snapshot())
                            .fetchMetadata()
                    },
                    onMetadataLoaded = ::persistMetadata
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        IntInputField(
                            value = pdfContextSize,
                            onValueChange = settingsRepo::setPdfContextSize,
                            label = stringResource(R.string.pdf_context_size_label)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntInputField(
                            value = pdfMergeContextSize,
                            onValueChange = settingsRepo::setPdfMergeContextSize,
                            label = stringResource(R.string.pdf_merge_context_label)
                        )
                        Text(
                            stringResource(R.string.pdf_context_desc_remote),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SliderWithInput(
                            value = pdfTemperature,
                            onValueChange = settingsRepo::setPdfTemperature,
                            valueRange = SettingsRepository.PDF_TEMPERATURE_MIN..SettingsRepository.PDF_TEMPERATURE_MAX,
                            label = stringResource(R.string.pdf_temperature_label),
                            decimalPlaces = 1
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntInputField(
                            value = pdfMaxTokens,
                            onValueChange = settingsRepo::setPdfMaxTokens,
                            label = stringResource(R.string.pdf_max_tokens_label)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntInputField(
                            value = pdfMergeMaxTokens,
                            onValueChange = settingsRepo::setPdfMergeMaxTokens,
                            label = stringResource(R.string.pdf_merge_max_tokens_label)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        IntSliderWithInput(
                            value = pdfSummaryTimeoutMinutes,
                            onValueChange = settingsRepo::setPdfSummaryTimeoutMinutes,
                            valueRange = SettingsRepository.PDF_TIMEOUT_MINUTES_RANGE,
                            label = stringResource(R.string.pdf_timeout_label),
                            suffix = stringResource(R.string.pdf_minutes_suffix)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (pdfSummaryTimeoutMinutes == SettingsRepository.PDF_TIMEOUT_DISABLED) {
                                stringResource(R.string.pdf_timeout_disabled_hint)
                            } else {
                                stringResource(R.string.pdf_timeout_desc_remote)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = pdfTargetLanguage,
                            onValueChange = settingsRepo::setPdfSummaryTargetLanguage,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.pdf_target_language_label)) },
                            supportingText = { Text(stringResource(R.string.pdf_target_language_desc)) }
                        )
                        OutlinedTextField(
                            value = pdfSummaryPrompt ?: PDFSummaryService.DEFAULT_SUMMARY_PROMPT,
                            onValueChange = settingsRepo::setPdfSummaryPrompt,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.workflow_system_prompt_label)) },
                            minLines = 3
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.pdf_thinking_toggle_title),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.pdf_thinking_toggle_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = thinkingEnabled,
                                onCheckedChange = settingsRepo::setPdfSummaryThinkingEnabled
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTranslationLanguagePicker(
    value: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val commonLanguages = listOf(
        "Spanish" to stringResource(R.string.pdf_translation_language_spanish),
        "English" to stringResource(R.string.pdf_translation_language_english),
        "Portuguese (Brazil)" to stringResource(R.string.pdf_translation_language_portuguese_brazil),
        "French" to stringResource(R.string.pdf_translation_language_french),
        "German" to stringResource(R.string.pdf_translation_language_german),
        "Italian" to stringResource(R.string.pdf_translation_language_italian),
        "Japanese" to stringResource(R.string.pdf_translation_language_japanese),
        "Korean" to stringResource(R.string.pdf_translation_language_korean),
        "Chinese (Simplified)" to stringResource(R.string.pdf_translation_language_chinese_simplified)
    )
    val currentCommon = commonLanguages.firstOrNull { it.first == value }
    var forceCustom by remember { mutableStateOf(false) }
    val isCustom = forceCustom || currentCommon == null
    var customDraft by remember(value) { mutableStateOf(value) }
    val selectedLabel = currentCommon?.second ?: stringResource(R.string.pdf_translation_language_custom)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text(stringResource(R.string.pdf_translation_language_label)) },
            supportingText = { Text(stringResource(R.string.pdf_translation_language_desc)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            commonLanguages.forEach { (languageValue, languageLabel) ->
                DropdownMenuItem(
                    text = { Text(languageLabel) },
                    onClick = {
                        forceCustom = false
                        onValueChange(languageValue)
                        expanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pdf_translation_language_custom)) },
                onClick = {
                    forceCustom = true
                    customDraft = customDraft.ifBlank { value }
                    expanded = false
                }
            )
        }
    }

    if (isCustom) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customDraft,
            onValueChange = {
                customDraft = it
                onValueChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.pdf_translation_language_custom_label)) },
            supportingText = { Text(stringResource(R.string.pdf_translation_language_custom_desc)) }
        )
    }
}
