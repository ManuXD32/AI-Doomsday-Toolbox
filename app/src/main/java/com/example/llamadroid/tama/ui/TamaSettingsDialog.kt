package com.example.llamadroid.tama.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.isTamaDefaultPicGenModel
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.tama.data.TamaPicGenDefaults
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TamaSettingsDialog(
    navController: NavController,
    settingsRepo: SettingsRepository,
    onDismiss: () -> Unit,
    isAway: Boolean = false
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val normalDreamingEnabled by settingsRepo.tamaNormalDreamingEnabled.collectAsState()
    val deepDreamingEnabled by settingsRepo.tamaDeepDreamingEnabled.collectAsState()
    val deepDreamRetryCount by settingsRepo.tamaDeepDreamRetryCount.collectAsState()
    val deepDreamDesiredLanguage by settingsRepo.tamaDeepDreamDesiredLanguage.collectAsState()
    val schoolPaintingEnabled by settingsRepo.tamaSchoolPaintingEnabled.collectAsState()
    val selectedModelFilename by settingsRepo.tamaPicGenModelFilename.collectAsState()
    val selectedResolution by settingsRepo.tamaPicGenResolution.collectAsState()
    val backend by settingsRepo.tamaBackend.collectAsState()
    val tamaOllamaUrl by settingsRepo.tamaOllamaUrl.collectAsState()
    val tamaSummarizerModel by settingsRepo.tamaSummarizerModel.collectAsState()
    val tamaLlamaServerUrl by settingsRepo.tamaLlamaServerUrl.collectAsState()
    val tamaLlamaSwapUrl by settingsRepo.tamaLlamaSwapUrl.collectAsState()
    val serverModelLabel by settingsRepo.tamaLlamaServerModelLabel.collectAsState()
    val serverContextLabel by settingsRepo.tamaLlamaServerContextLabel.collectAsState()
    val serverContextTokens by settingsRepo.tamaLlamaServerContextTokens.collectAsState()
    val tamaLiteRtModelId by settingsRepo.tamaLiteRtModelId.collectAsState()
    val tamaLiteRtBackend by settingsRepo.tamaLiteRtBackend.collectAsState()
    val tamaLiteRtMtpEnabled by settingsRepo.tamaLiteRtMtpEnabled.collectAsState()
    val tamaThinkingEnabled by settingsRepo.tamaThinkingEnabled.collectAsState()
    val installedModelsFlow = remember(appContext) {
        runCatching {
            AppDatabase.getDatabase(appContext)
                .modelDao()
                .getModelsByType(ModelType.ONNX_IMAGE_GEN)
        }.getOrElse {
            flowOf(emptyList())
        }
    }
    val installedModels by installedModelsFlow.collectAsState(initial = emptyList())

    val txt2ImgModels = remember(installedModels) { installedModels.filter { it.isOnnxTxt2ImgBundle() } }
    val defaultInstalled = remember(txt2ImgModels) { txt2ImgModels.any { it.isTamaDefaultPicGenModel() } }
    val selectedModel = remember(txt2ImgModels, selectedModelFilename) {
        selectedModelFilename?.let { filename -> txt2ImgModels.firstOrNull { it.filename == filename } }
            ?: txt2ImgModels.firstOrNull { it.isTamaDefaultPicGenModel() }
    }
    var showModelMenu by remember { mutableStateOf(false) }
    var showResolutionMenu by remember { mutableStateOf(false) }

    fun persistMetadata(metadata: RemoteSummaryMetadata) {
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepo.setTamaLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepo.setTamaLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepo.setTamaLlamaServerContextLabel(metadata.serverContextLabel)
        }
        if (SettingsRepository.requiresSelectedRemoteModel(metadata.backend) && !metadata.selectedModel.isNullOrBlank()) {
            settingsRepo.setTamaSummarizerModel(metadata.selectedModel)
        }
    }

    TamaPopupDialog(
        title = stringResource(R.string.tama_settings_title),
        backgroundAsset = "tama/backgrounds/popup_generic.png",
        onDismissRequest = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TamaToggleSetting(
                    title = stringResource(R.string.tama_settings_normal_dreaming_title),
                    description = stringResource(R.string.tama_settings_normal_dreaming_desc),
                    checked = normalDreamingEnabled,
                    onCheckedChange = settingsRepo::setTamaNormalDreamingEnabled
                )
                TamaToggleSetting(
                    title = stringResource(R.string.tama_settings_deep_dreaming_title),
                    description = stringResource(R.string.tama_settings_deep_dreaming_desc),
                    checked = deepDreamingEnabled,
                    onCheckedChange = settingsRepo::setTamaDeepDreamingEnabled
                )
                OutlinedTextField(
                    value = deepDreamDesiredLanguage,
                    onValueChange = settingsRepo::setTamaDeepDreamDesiredLanguage,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tama_settings_deep_dream_language_title)) },
                    supportingText = {
                        Text(
                            stringResource(R.string.tama_settings_deep_dream_language_desc),
                            color = TamaHelperText
                        )
                    }
                )
                DraftIntTextField(
                    value = deepDreamRetryCount,
                    onValueChange = settingsRepo::setTamaDeepDreamRetryCount,
                    valueRange = 1..Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tama_settings_deep_dream_retry_title)) },
                    supportingText = {
                        Text(
                            stringResource(R.string.tama_settings_deep_dream_retry_desc),
                            color = TamaHelperText
                        )
                    }
                )
                TamaToggleSetting(
                    title = stringResource(R.string.tama_settings_school_painting_title),
                    description = stringResource(R.string.tama_settings_school_painting_desc),
                    checked = schoolPaintingEnabled,
                    onCheckedChange = settingsRepo::setTamaSchoolPaintingEnabled
                )
                HorizontalDivider()
                com.example.llamadroid.tama.shimeji.ShimejiSettingsCard(isAway = isAway)
                HorizontalDivider()
                RemoteSummaryBackendEditor(
                    title = stringResource(R.string.tama_settings_ai_section),
                    backend = backend,
                    onBackendChange = settingsRepo::setTamaBackend,
                    ollamaUrl = tamaOllamaUrl,
                    onOllamaUrlChange = settingsRepo::setTamaOllamaUrl,
                    llamaServerUrl = tamaLlamaServerUrl,
                    onLlamaServerUrlChange = settingsRepo::setTamaLlamaServerUrl,
                    llamaSwapUrl = tamaLlamaSwapUrl,
                    onLlamaSwapUrlChange = settingsRepo::setTamaLlamaSwapUrl,
                    ollamaModel = tamaSummarizerModel,
                    onOllamaModelSelected = settingsRepo::setTamaSummarizerModel,
                    llamaSwapModel = tamaSummarizerModel,
                    onLlamaSwapModelSelected = settingsRepo::setTamaSummarizerModel,
                    llamaServerModelLabel = serverModelLabel,
                    llamaServerContextLabel = serverContextLabel,
                    llamaServerContextTokens = serverContextTokens,
                    requestedContextForWarning = null,
                    liteRtModelId = tamaLiteRtModelId.takeIf { it > 0L },
                    onLiteRtModelSelected = settingsRepo::setTamaLiteRtModelId,
                    liteRtBackend = tamaLiteRtBackend,
                    onLiteRtBackendChange = settingsRepo::setTamaLiteRtBackend,
                    liteRtMtpEnabled = tamaLiteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = settingsRepo::setTamaLiteRtMtpEnabled,
                    liteRtThinkingEnabled = tamaThinkingEnabled,
                    onLiteRtThinkingEnabledChange = settingsRepo::setTamaThinkingEnabled,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromConfig(
                            RemoteSummaryBackendConfig(
                                context = context,
                                backend = backend,
                                baseUrl = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> tamaLlamaServerUrl.trim()
                                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> tamaLlamaSwapUrl.trim()
                                    else -> tamaOllamaUrl.trim()
                                },
                                model = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> serverModelLabel?.trim()?.ifBlank { null }
                                    else -> tamaSummarizerModel.trim().ifBlank { null }
                                },
                                liteRtModelId = tamaLiteRtModelId.takeIf { it > 0L },
                                liteRtBackend = tamaLiteRtBackend,
                                liteRtMtpEnabled = tamaLiteRtMtpEnabled,
                                timeoutMinutes = 1
                            )
                        ).fetchMetadata()
                    },
                    onMetadataLoaded = ::persistMetadata
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.tama_settings_pic_gen_section),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.tama_settings_pic_gen_hint),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaHelperText
                )
                if (!defaultInstalled) {
                    Text(
                        stringResource(R.string.tama_pic_gen_install_default_model),
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                if (txt2ImgModels.isEmpty()) {
                    FilledTonalButton(onClick = { navController.navigate(Screen.OnnxModels.route) }) {
                        Text(stringResource(R.string.tama_settings_open_onnx_models))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.tama_settings_pic_model_title),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExposedDropdownMenuBox(
                            expanded = showModelMenu,
                            onExpandedChange = { showModelMenu = !showModelMenu }
                        ) {
                            OutlinedTextField(
                                value = selectedModel?.let { tamaModelLabel(it) }.orEmpty(),
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                readOnly = true,
                                label = { Text(stringResource(R.string.tama_settings_pic_model_title)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModelMenu) }
                            )
                            ExposedDropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false }
                            ) {
                                txt2ImgModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(tamaModelLabel(model)) },
                                        onClick = {
                                            settingsRepo.setTamaPicGenModelFilename(model.filename)
                                            showModelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.tama_settings_pic_resolution_title),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExposedDropdownMenuBox(
                            expanded = showResolutionMenu,
                            onExpandedChange = { showResolutionMenu = !showResolutionMenu }
                        ) {
                            OutlinedTextField(
                                value = "${selectedResolution}x${selectedResolution}",
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                readOnly = true,
                                label = { Text(stringResource(R.string.tama_settings_pic_resolution_title)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showResolutionMenu) }
                            )
                            ExposedDropdownMenu(
                                expanded = showResolutionMenu,
                                onDismissRequest = { showResolutionMenu = false }
                            ) {
                                TamaPicGenDefaults.RESOLUTION_PRESETS.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text("${preset}x${preset}") },
                                        onClick = {
                                            settingsRepo.setTamaPicGenResolution(preset)
                                            showResolutionMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    FilledTonalButton(onClick = { navController.navigate(Screen.OnnxModels.route) }) {
                        Text(stringResource(R.string.tama_settings_open_onnx_models))
                    }
                }
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
