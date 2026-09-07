package com.example.llamadroid.tama.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.adventure.localizedName
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor

@Composable
fun AdventureSettingsDialog(
    onDismiss: () -> Unit,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val storyModel by settingsRepository.adventureModel.collectAsState()
    val summarizerModel by settingsRepository.adventureSummarizerModel.collectAsState()
    val backend by settingsRepository.adventureBackend.collectAsState()
    val ollamaUrl by settingsRepository.adventureOllamaUrl.collectAsState()
    val llamaServerUrl by settingsRepository.adventureLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepository.adventureLlamaSwapUrl.collectAsState()
    val llamaServerModelLabel by settingsRepository.adventureLlamaServerModelLabel.collectAsState()
    val llamaServerContextTokens by settingsRepository.adventureLlamaServerContextTokens.collectAsState()
    val llamaServerContextLabel by settingsRepository.adventureLlamaServerContextLabel.collectAsState()
    val liteRtModelId by settingsRepository.adventureLiteRtModelId.collectAsState()
    val liteRtBackend by settingsRepository.adventureLiteRtBackend.collectAsState()
    val liteRtMtpEnabled by settingsRepository.adventureLiteRtMtpEnabled.collectAsState()
    val adventureLanguage by settingsRepository.adventureLanguage.collectAsState()
    val worldImageEnabled by settingsRepository.adventureWorldImageEnabled.collectAsState()
    val stageImagesEnabled by settingsRepository.adventureStageImagesEnabled.collectAsState()
    val onnxFilename by settingsRepository.adventureOnnxModelFilename.collectAsState()
    val onnxSteps by settingsRepository.adventureOnnxSteps.collectAsState()
    val onnxCfg by settingsRepository.adventureOnnxCfg.collectAsState()
    val onnxResolution by settingsRepository.adventureOnnxResolution.collectAsState()
    val adventureSystemPrompt by settingsRepository.adventureSystemPrompt.collectAsState()
    val adventureSummarizerPrompt by settingsRepository.adventureSummarizerPrompt.collectAsState()

    val txt2ImgModels by AppDatabase.getDatabase(context.applicationContext)
        .modelDao()
        .getModelsByTypes(listOf(ModelType.ONNX_IMAGE_GEN))
        .collectAsState(initial = emptyList())
    val installedTxt2ImgModels = remember(txt2ImgModels) {
        txt2ImgModels.filter { it.isOnnxTxt2ImgBundle() }
    }

    var showStoryModelMenu by remember { mutableStateOf(false) }
    var showSummarizerModelMenu by remember { mutableStateOf(false) }
    var showOnnxModelMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showSummarizerPromptEditor by remember { mutableStateOf(false) }
    var showDungeonThemesDialog by remember { mutableStateOf(false) }
    var availableOllamaModels by remember { mutableStateOf<List<String>>(emptyList()) }
    fun persistMetadata(metadata: RemoteSummaryMetadata) {
        availableOllamaModels = metadata.availableModels
        if (SettingsRepository.isLlamaServerBackend(metadata.backend)) {
            settingsRepository.setAdventureLlamaServerModelLabel(metadata.serverModelLabel)
            settingsRepository.setAdventureLlamaServerContextTokens(metadata.serverContextTokens)
            settingsRepository.setAdventureLlamaServerContextLabel(metadata.serverContextLabel)
        }
    }

    LaunchedEffect(storyModel) {
        if (storyModel.isNotBlank() && availableOllamaModels.isEmpty()) {
            availableOllamaModels = listOf(storyModel).distinct()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.adventure_settings_title),
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RemoteSummaryBackendEditor(
                    title = stringResource(R.string.adventure_backend_section_title),
                    backend = backend,
                    onBackendChange = settingsRepository::setAdventureBackend,
                    ollamaUrl = ollamaUrl,
                    onOllamaUrlChange = settingsRepository::setAdventureOllamaUrl,
                    llamaServerUrl = llamaServerUrl,
                    onLlamaServerUrlChange = settingsRepository::setAdventureLlamaServerUrl,
                    llamaSwapUrl = llamaSwapUrl,
                    onLlamaSwapUrlChange = settingsRepository::setAdventureLlamaSwapUrl,
                    ollamaModel = storyModel,
                    onOllamaModelSelected = settingsRepository::setAdventureModel,
                    llamaSwapModel = storyModel,
                    onLlamaSwapModelSelected = settingsRepository::setAdventureModel,
                    llamaServerModelLabel = llamaServerModelLabel,
                    llamaServerContextLabel = llamaServerContextLabel,
                    llamaServerContextTokens = llamaServerContextTokens,
                    requestedContextForWarning = null,
                    liteRtModelId = liteRtModelId.takeIf { it > 0L },
                    onLiteRtModelSelected = settingsRepository::setAdventureLiteRtModelId,
                    liteRtBackend = liteRtBackend,
                    onLiteRtBackendChange = settingsRepository::setAdventureLiteRtBackend,
                    liteRtMtpEnabled = liteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = settingsRepository::setAdventureLiteRtMtpEnabled,
                    fetchMetadata = {
                        RemoteSummaryClientFactory.fromConfig(
                            RemoteSummaryBackendConfig(
                                context = context,
                                backend = backend,
                                baseUrl = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerUrl.trim()
                                    SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapUrl.trim()
                                    else -> ollamaUrl.trim()
                                },
                                model = when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                                    SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerModelLabel?.trim()?.ifBlank { null }
                                    else -> storyModel.trim().ifBlank { null }
                                },
                                liteRtModelId = liteRtModelId.takeIf { it > 0L },
                                liteRtBackend = liteRtBackend,
                                liteRtMtpEnabled = liteRtMtpEnabled,
                                timeoutMinutes = 1
                            )
                        ).fetchMetadata()
                    },
                    onMetadataLoaded = ::persistMetadata
                )

                if (SettingsRepository.requiresSelectedRemoteModel(backend)) {
                    AdventureModelDropdown(
                        title = stringResource(R.string.adventure_story_model_title),
                        selectedModel = storyModel,
                        availableModels = availableOllamaModels,
                        expanded = showStoryModelMenu,
                        onExpandChange = { showStoryModelMenu = it },
                        onModelSelect = {
                            settingsRepository.setAdventureModel(it)
                            showStoryModelMenu = false
                        }
                    )
                    AdventureModelDropdown(
                        title = stringResource(R.string.adventure_summarizer_model_title),
                        selectedModel = summarizerModel,
                        availableModels = availableOllamaModels,
                        expanded = showSummarizerModelMenu,
                        onExpandChange = { showSummarizerModelMenu = it },
                        onModelSelect = {
                            settingsRepository.setAdventureSummarizerModel(it)
                            showSummarizerModelMenu = false
                        }
                    )
                } else {
                    Text(
                        stringResource(R.string.adventure_llama_server_model_note),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AdventureLanguageSelector(
                    selectedLanguage = adventureLanguage,
                    expanded = showLanguageMenu,
                    onExpandChange = { showLanguageMenu = it },
                    onLanguageSelected = {
                        settingsRepository.setAdventureLanguage(it)
                        showLanguageMenu = false
                    }
                )

                AdventureToggleRow(
                    title = stringResource(R.string.adventure_world_image_toggle_title),
                    description = stringResource(R.string.adventure_world_image_toggle_desc),
                    checked = worldImageEnabled,
                    onCheckedChange = settingsRepository::setAdventureWorldImageEnabled
                )
                AdventureToggleRow(
                    title = stringResource(R.string.adventure_stage_image_toggle_title),
                    description = stringResource(R.string.adventure_stage_image_toggle_desc),
                    checked = stageImagesEnabled,
                    onCheckedChange = settingsRepository::setAdventureStageImagesEnabled
                )

                if (worldImageEnabled || stageImagesEnabled) {
                    Text(
                        stringResource(R.string.adventure_world_image_section_title),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                    Box {
                        OutlinedTextField(
                            value = onnxFilename ?: "",
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            label = { Text(stringResource(R.string.adventure_onnx_model_title)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showOnnxModelMenu = !showOnnxModelMenu },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showOnnxModelMenu,
                            onDismissRequest = { showOnnxModelMenu = false }
                        ) {
                            installedTxt2ImgModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            model.filename,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        settingsRepository.setAdventureOnnxModelFilename(model.filename)
                                        showOnnxModelMenu = false
                                    }
                                )
                            }
                        }
                    }

                    NumericAdventureField(
                        label = stringResource(R.string.adventure_onnx_steps_title),
                        value = onnxSteps,
                        onValueChange = settingsRepository::setAdventureOnnxSteps
                    )
                    DecimalAdventureField(
                        label = stringResource(R.string.adventure_onnx_cfg_title),
                        value = onnxCfg,
                        onValueChange = settingsRepository::setAdventureOnnxCfg
                    )
                    NumericAdventureField(
                        label = stringResource(R.string.adventure_onnx_resolution_title),
                        value = onnxResolution,
                        onValueChange = settingsRepository::setAdventureOnnxResolution
                    )
                }

                TextButton(
                    onClick = { showSystemPromptEditor = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.adventure_edit_system_prompt))
                }
                TextButton(
                    onClick = { showSummarizerPromptEditor = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.adventure_edit_summarizer_prompt))
                }
                TextButton(
                    onClick = { showDungeonThemesDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.adventure_view_dungeon_themes))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    )

    if (showSystemPromptEditor) {
        PromptEditorDialog(
            title = stringResource(R.string.adventure_edit_system_prompt),
            prompt = adventureSystemPrompt,
            onSave = {
                settingsRepository.setAdventureSystemPrompt(it)
                showSystemPromptEditor = false
            },
            onDismiss = { showSystemPromptEditor = false }
        )
    }

    if (showSummarizerPromptEditor) {
        PromptEditorDialog(
            title = stringResource(R.string.adventure_edit_summarizer_prompt),
            prompt = adventureSummarizerPrompt,
            onSave = {
                settingsRepository.setAdventureSummarizerPrompt(it)
                showSummarizerPromptEditor = false
            },
            onDismiss = { showSummarizerPromptEditor = false }
        )
    }

    if (showDungeonThemesDialog) {
        DungeonThemesDialog(onDismiss = { showDungeonThemesDialog = false })
    }
}

@Composable
private fun AdventureModelDropdown(
    title: String,
    selectedModel: String,
    availableModels: List<String>,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onModelSelect: (String) -> Unit
) {
    Text(title, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Box {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = onModelSelect,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = { onExpandChange(!expanded) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
        )
        DropdownMenu(
            expanded = expanded && availableModels.isNotEmpty(),
            onDismissRequest = { onExpandChange(false) }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            model,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        onModelSelect(model)
                        onExpandChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun AdventureLanguageSelector(
    selectedLanguage: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val languages = listOf(
        "English", "Spanish", "French", "German", "Italian",
        "Portuguese", "Russian", "Chinese", "Japanese", "Korean"
    )
    Text(stringResource(R.string.adventure_language_title), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Box {
        OutlinedTextField(
            value = selectedLanguage,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = { onExpandChange(!expanded) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandChange(false) }) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { onLanguageSelected(language) }
                )
            }
        }
    }
}

@Composable
private fun AdventureToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(
                description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumericAdventureField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    DraftIntTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }
    )
}

@Composable
private fun DecimalAdventureField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    DraftFloatTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }
    )
}

@Composable
fun DungeonThemesDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedDungeon by remember { mutableStateOf(DungeonType.entries.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adventure_view_dungeon_themes), fontFamily = FontFamily.Monospace) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DungeonType.entries.forEach { dungeon ->
                        TextButton(
                            onClick = { selectedDungeon = dungeon },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (dungeon == selectedDungeon) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                }
                            )
                        ) {
                            TamaUiIcon(dungeon.emoji, fontSize = 18.sp)
                        }
                    }
                }
                Text(
                    selectedDungeon.localizedName(context),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                OutlinedTextField(
                    value = selectedDungeon.stylePrompt,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
fun PromptEditorDialog(
    title: String,
    prompt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedPrompt by remember(prompt) { mutableStateOf(prompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = editedPrompt,
                onValueChange = { editedPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 420.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(editedPrompt) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
