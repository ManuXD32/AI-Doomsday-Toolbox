package com.example.llamadroid.ui.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AudioWorkspaceScreen(
    navController: NavController,
    initialSection: AudioWorkspaceSection = AudioWorkspaceSection.SPEECH,
    controller: AudioWorkspaceController,
    viewModel: AudioWorkspaceViewModel = viewModel()
) {
    val runtimeState by controller.state.collectAsState()
    val context = LocalContext.current
    val libraryScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val draftStore = remember(context) { AudioWorkspaceDraftStore(context) }
    val draftPersistenceError by draftStore.error.collectAsState()

    DisposableEffect(draftStore) {
        onDispose { draftStore.close() }
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && controller.state.value.isRecording) {
                controller.stopVoiceRecording()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller, viewModel.section) {
        onDispose { controller.stopVoicePreview() }
    }
    LaunchedEffect(draftStore) {
        viewModel.restoreDraft(draftStore.load())
    }
    if (viewModel.isDraftRestored) {
        LaunchedEffect(viewModel.draft) {
            draftStore.save(viewModel.draft)
        }
    }

    LaunchedEffect(initialSection) {
        viewModel.section = initialSection
    }
    LaunchedEffect(runtimeState.models) {
        if (viewModel.draft.modelId.isBlank() && runtimeState.models.isNotEmpty()) {
            viewModel.updateDraft { it.copy(modelId = runtimeState.models.first().id) }
        }
    }

    var pendingVoiceUri by remember { mutableStateOf<Uri?>(null) }
    var showVoiceImportDialog by remember { mutableStateOf(false) }
    var showVoiceRenameDialog by remember { mutableStateOf<AudioVoiceProfileUi?>(null) }
    var pendingVoiceDelete by remember { mutableStateOf<AudioVoiceProfileUi?>(null) }
    var showMicrophonePermissionDialog by remember { mutableStateOf(false) }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) controller.startVoiceRecording()
        else showMicrophonePermissionDialog = true
    }

    fun requestVoiceRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.startVoiceRecording()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(runtimeState.pendingRecordedUri) {
        runtimeState.pendingRecordedUri?.let { raw ->
            pendingVoiceUri = runCatching { Uri.parse(raw) }.getOrNull()
            showVoiceImportDialog = pendingVoiceUri != null
        }
    }

    val textFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.updateDraft {
            it.copy(sourceUri = uri.toString(), sourceName = displayName(context, uri), text = "")
        }
        controller.importTextDocument(uri)
    }
    val voiceFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingVoiceUri = uri
        showVoiceImportDialog = true
    }
    val selectedModel = runtimeState.models.firstOrNull { it.id == viewModel.draft.modelId }
    val validationErrors = viewModel.draft.validationErrors(selectedModel, runtimeState.voiceProfiles)

    AppScreenScaffold(
        title = stringResource(R.string.audio_workspace_title),
        subtitle = stringResource(R.string.audio_workspace_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(
                onClick = { navController.navigate(
                    if (viewModel.section in setOf(AudioWorkspaceSection.MUSIC, AudioWorkspaceSection.SOUND_EFFECTS))
                        Screen.LiteRtModels.createRoute("catalog") else Screen.AudioModels.route
                ) },
                modifier = Modifier
            ) {
                Icon(Icons.Default.Memory, contentDescription = stringResource(R.string.audio_workspace_manage_models))
            }
        }
    ) {
            Column(Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = audioWorkspaceSections.indexOf(viewModel.section),
                edgePadding = AppChromeDefaults.ScreenPadding,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                audioWorkspaceSections.forEach { section ->
                    Tab(
                        selected = viewModel.section == section,
                        onClick = { viewModel.section = section },
                        text = {
                            Text(
                                when (section) {
                                    AudioWorkspaceSection.SPEECH -> stringResource(R.string.audio_workspace_speech)
                                    AudioWorkspaceSection.VOICES -> stringResource(R.string.audio_workspace_voices)
                                    AudioWorkspaceSection.HISTORY -> stringResource(R.string.audio_workspace_history)
                                    AudioWorkspaceSection.MUSIC -> stringResource(R.string.audio_music_music)
                                    AudioWorkspaceSection.SOUND_EFFECTS -> stringResource(R.string.audio_music_sfx)
                                }
                            )
                        },
                        icon = {
                            Icon(
                                when (section) {
                                    AudioWorkspaceSection.SPEECH -> Icons.Default.GraphicEq
                                    AudioWorkspaceSection.VOICES -> Icons.Default.RecordVoiceOver
                                    AudioWorkspaceSection.HISTORY -> Icons.Default.History
                                    AudioWorkspaceSection.MUSIC -> Icons.Default.AudioFile
                                    AudioWorkspaceSection.SOUND_EFFECTS -> Icons.Default.GraphicEq
                                },
                                contentDescription = null
                            )
                        }
                    )
                }
            }

            if (draftPersistenceError != null) {
                AppStatePanel(
                    kind = AppStateKind.Error,
                    title = stringResource(R.string.audio_workspace_draft_save_error),
                    message = stringResource(R.string.audio_workspace_draft_save_error_hint),
                    modifier = Modifier.padding(AppChromeDefaults.ScreenPadding)
                )
            }

            runtimeState.loadError?.let {
                AppStatePanel(
                    kind = AppStateKind.Error,
                    title = stringResource(R.string.audio_workspace_data_error),
                    message = stringResource(R.string.audio_workspace_data_error_hint),
                    modifier = Modifier.padding(horizontal = AppChromeDefaults.ScreenPadding)
                )
            }

            if (runtimeState.operationError) {
                AppStatePanel(
                    kind = AppStateKind.Error,
                    title = stringResource(R.string.audio_workspace_action_error),
                    message = stringResource(R.string.audio_workspace_action_error_hint),
                    modifier = Modifier.padding(horizontal = AppChromeDefaults.ScreenPadding)
                )
            }

            Box(Modifier.weight(1f)) {
                when (viewModel.section) {
                AudioWorkspaceSection.MUSIC -> com.example.llamadroid.ui.audio.music.MusicWorkspaceRoute("music", navController)
                AudioWorkspaceSection.SOUND_EFFECTS -> com.example.llamadroid.ui.audio.music.MusicWorkspaceRoute("sfx", navController)
                AudioWorkspaceSection.SPEECH -> SpeechWorkspace(
                    viewModel = viewModel,
                    runtimeState = runtimeState,
                    selectedModel = selectedModel,
                    validationErrors = validationErrors,
                    textFilePicker = { textFilePicker.launch(audioTextMimeTypes) },
                    onGenerate = { controller.generate(viewModel.draft) },
                    onRetry = controller::retryGeneration,
                    onCancel = controller::cancelGeneration,
                    onManageModels = { navController.navigate(Screen.AudioModels.route) }
                )
                AudioWorkspaceSection.VOICES -> VoicesWorkspace(
                    runtimeState = runtimeState,
                    onImport = { voiceFilePicker.launch(audioVoiceMimeTypes) },
                    onRecord = {
                        if (runtimeState.isRecording) controller.stopVoiceRecording()
                        else requestVoiceRecording()
                    },
                    onRename = { showVoiceRenameDialog = it },
                    onDelete = { profileId -> pendingVoiceDelete = runtimeState.voiceProfiles.firstOrNull { it.id == profileId } },
                    onPreview = controller::previewVoice,
                    onPause = controller::pauseVoicePreview,
                    onResume = controller::resumeVoicePreview,
                    onStop = controller::stopVoicePreview
                )
                AudioWorkspaceSection.HISTORY -> com.example.llamadroid.ui.audio.library.AudioLibraryScreen(
                    onUseAsInput = { path, mediaKind ->
                        libraryScope.launch {
                            val kind = if (mediaKind == "sfx") "sfx" else "music"
                            val store = com.example.llamadroid.ui.audio.music.MusicWorkspaceDraftStore.get(context, kind)
                            try {
                                val draft = store.load()
                                store.save(draft.withValue("initAudio", path).withValue("operation", "remix"))
                                viewModel.section = if (kind == "sfx") AudioWorkspaceSection.SOUND_EFFECTS else AudioWorkspaceSection.MUSIC
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                android.widget.Toast.makeText(context, R.string.audio_music_draft_error, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                }
            }
        }
    }

    if (showVoiceImportDialog && pendingVoiceUri != null) {
        VoiceImportDialog(
            defaultName = displayName(context, pendingVoiceUri!!),
            onDismiss = {
                showVoiceImportDialog = false
                pendingVoiceUri = null
            },
            onSave = { options ->
                controller.importVoice(pendingVoiceUri!!, options)
                showVoiceImportDialog = false
                pendingVoiceUri = null
            }
        )
    }
    showVoiceRenameDialog?.let { profile ->
        RenameDialog(
            title = stringResource(R.string.audio_voice_rename_title),
            initialValue = profile.name,
            onDismiss = { showVoiceRenameDialog = null },
            onSave = { name ->
                controller.renameVoice(profile.id, name)
                showVoiceRenameDialog = null
            }
        )
    }
    pendingVoiceDelete?.let { profile ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.audio_workspace_delete_voice_title),
            message = stringResource(R.string.audio_workspace_delete_voice_message, profile.name),
            onDismiss = { pendingVoiceDelete = null },
            onConfirm = {
                controller.deleteVoice(profile.id)
                pendingVoiceDelete = null
            }
        )
    }
    if (showMicrophonePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMicrophonePermissionDialog = false },
            title = { Text(stringResource(R.string.audio_workspace_microphone_permission_title)) },
            text = { Text(stringResource(R.string.audio_workspace_microphone_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showMicrophonePermissionDialog = false
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.audio_workspace_permission_retry)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMicrophonePermissionDialog = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }) { Text(stringResource(R.string.audio_workspace_permission_settings)) }
            }
        )
    }
}

private val audioTextMimeTypes = arrayOf(
    "text/*",
    "application/pdf",
    "application/epub+zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/json",
    "text/html"
)

private val audioVoiceMimeTypes = arrayOf("audio/*", "video/*")

private fun displayName(context: android.content.Context, uri: Uri): String {
    val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else fallback
            } ?: fallback
    }.getOrDefault(fallback)
}

@Composable
private fun SpeechWorkspace(
    viewModel: AudioWorkspaceViewModel,
    runtimeState: AudioWorkspaceUiState,
    selectedModel: AudioModelOption?,
    validationErrors: List<AudioDraftValidationError>,
    textFilePicker: () -> Unit,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onManageModels: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppChromeDefaults.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppPageHeader(
                title = stringResource(R.string.audio_workspace_speech_title),
                subtitle = stringResource(R.string.audio_workspace_speech_subtitle),
                eyebrow = stringResource(R.string.audio_workspace_local_badge),
                trailing = {
                    AssistChip(
                        onClick = onManageModels,
                        label = { Text(stringResource(R.string.audio_workspace_models)) },
                        leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null) }
                    )
                }
            )
        }
        item {
            GuidedStepRow(
                currentStep = viewModel.guidedStep,
                onStepSelected = { viewModel.guidedStep = it }
            )
        }
        item {
            when (viewModel.guidedStep) {
                AudioGuidedStep.MODEL -> ModelStep(
                    models = runtimeState.models,
                    selectedId = viewModel.draft.modelId,
                    onSelected = { model ->
                        viewModel.updateDraft {
                            val language = model.capabilities.supportedLanguages.firstOrNull() ?: it.language
                            it.copy(
                                modelId = model.id,
                                language = language,
                                voiceProfileId = null,
                                companionPath = model.companionOptions.singleOrNull(),
                                componentIds = model.components
                            )
                        }
                    },
                    onManageModels = onManageModels
                )
                AudioGuidedStep.TEXT -> TextStep(
                    draft = viewModel.draft,
                    onTextChanged = { text -> viewModel.updateDraft { it.copy(text = text, sourceUri = null, sourceName = null) } },
                    onFileSelected = textFilePicker
                )
                AudioGuidedStep.VOICE -> VoiceStep(
                    draft = viewModel.draft,
                    model = selectedModel,
                    voices = runtimeState.voiceProfiles,
                    onLanguageChanged = { language -> viewModel.updateDraft { it.copy(language = language) } },
                    onVoiceChanged = { voice -> viewModel.updateDraft { it.copy(voiceProfileId = voice) } },
                    onVoiceStyleChanged = { style -> viewModel.updateDraft { it.copy(voiceStyle = style) } }
                )
                AudioGuidedStep.GENERATE -> GenerateStep(
                    draft = viewModel.draft,
                    model = selectedModel,
                    voices = runtimeState.voiceProfiles,
                    validationErrors = validationErrors,
                    job = runtimeState.job,
                    onGenerate = onGenerate,
                    onRetry = onRetry,
                    onCancel = onCancel,
                    onStepSelected = { viewModel.guidedStep = it }
                )
            }
        }
        item {
            AdvancedControls(
                viewModel = viewModel,
                model = selectedModel,
                draft = viewModel.draft,
                onDraftChanged = { transform -> viewModel.updateDraft(transform) }
            )
        }
        item {
            Text(
                text = stringResource(R.string.audio_workspace_draft_preserved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun GuidedStepRow(
    currentStep: AudioGuidedStep,
    onStepSelected: (AudioGuidedStep) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.audio_workspace_guided_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioGuidedStep.entries.forEach { step ->
                FilterChip(
                    selected = currentStep == step,
                    onClick = { onStepSelected(step) },
                    label = {
                        Text(
                            stringResource(
                                when (step) {
                                    AudioGuidedStep.MODEL -> R.string.audio_workspace_step_model
                                    AudioGuidedStep.TEXT -> R.string.audio_workspace_step_text
                                    AudioGuidedStep.VOICE -> R.string.audio_workspace_step_voice
                                    AudioGuidedStep.GENERATE -> R.string.audio_workspace_step_generate
                                }
                            )
                        )
                    },
                    leadingIcon = {
                        if (step.ordinalValue < currentStep.ordinalValue) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        } else {
                            Text(step.ordinalValue.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelStep(
    models: List<AudioModelOption>,
    selectedId: String,
    onSelected: (AudioModelOption) -> Unit,
    onManageModels: () -> Unit
) {
    AppSectionCard {
        SectionTitle(
            icon = Icons.Default.Memory,
            title = stringResource(R.string.audio_workspace_step_model_title),
            subtitle = stringResource(R.string.audio_workspace_step_model_hint)
        )
        if (models.isEmpty()) {
            AppStatePanel(
                kind = AppStateKind.Empty,
                title = stringResource(R.string.audio_workspace_no_models),
                message = stringResource(R.string.audio_workspace_no_models_hint),
                actionLabel = stringResource(R.string.audio_workspace_manage_models),
                onAction = onManageModels
            )
        } else {
            models.forEach { model ->
                ModelChoiceCard(model, selectedId == model.id, onClick = { onSelected(model) })
            }
            OutlinedButton(onClick = onManageModels, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.audio_workspace_configure_models))
            }
        }
    }
}

@Composable
private fun ModelChoiceCard(model: AudioModelOption, selected: Boolean, onClick: () -> Unit) {
    val stateLabel = when (model.installState) {
        AudioModelInstallState.INSTALLED -> stringResource(R.string.audio_workspace_model_installed)
        AudioModelInstallState.AVAILABLE -> stringResource(R.string.audio_workspace_model_available)
        AudioModelInstallState.UNRESOLVED -> stringResource(R.string.audio_workspace_model_unresolved)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.AudioFile,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(audioComponentLabel(model.componentSummary), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                model.unresolvedReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun TextStep(
    draft: AudioWorkspaceDraft,
    onTextChanged: (String) -> Unit,
    onFileSelected: () -> Unit
) {
    AppSectionCard {
        SectionTitle(
            icon = Icons.Default.UploadFile,
            title = stringResource(R.string.audio_workspace_step_text_title),
            subtitle = stringResource(R.string.audio_workspace_step_text_hint)
        )
        OutlinedButton(onClick = onFileSelected, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.audio_workspace_import_document))
        }
        draft.sourceName?.let {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.audio_workspace_source_file, it), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
        OutlinedTextField(
            value = draft.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            minLines = 6,
            label = { Text(stringResource(R.string.audio_workspace_text_label)) },
            placeholder = { Text(stringResource(R.string.audio_workspace_text_placeholder)) }
        )
        Text(
            stringResource(R.string.audio_workspace_text_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceStep(
    draft: AudioWorkspaceDraft,
    model: AudioModelOption?,
    voices: List<AudioVoiceProfileUi>,
    onLanguageChanged: (String) -> Unit,
    onVoiceChanged: (String?) -> Unit,
    onVoiceStyleChanged: (String?) -> Unit
) {
    AppSectionCard {
        SectionTitle(
            icon = Icons.Default.RecordVoiceOver,
            title = stringResource(R.string.audio_workspace_step_voice_title),
            subtitle = stringResource(R.string.audio_workspace_step_voice_hint)
        )
        model?.let { selected ->
            if (selected.capabilities.supportsLanguageSelection && selected.capabilities.supportedLanguages.isNotEmpty()) {
                ChoiceMenu(
                    label = stringResource(R.string.audio_workspace_language),
                    value = stringResource(languageLabelRes(draft.language)),
                    options = selected.capabilities.supportedLanguages,
                    optionLabel = { option -> Text(stringResource(languageLabelRes(option))) },
                    onSelected = onLanguageChanged
                )
            } else if (selected.capabilities.supportedLanguages.isNotEmpty()) {
                InfoLine(stringResource(R.string.audio_workspace_language_fixed, stringResource(languageLabelRes(selected.capabilities.supportedLanguages.first()))))
            } else {
                InfoLine(stringResource(R.string.audio_workspace_language_metadata_missing))
            }
            if (selected.capabilities.supportsReferenceAudio) {
                ChoiceMenu(
                    label = stringResource(R.string.audio_workspace_reference_voice),
                    value = voices.firstOrNull { it.id == draft.voiceProfileId }?.name
                        ?: stringResource(R.string.audio_workspace_choose_voice),
                    options = voices.map { it.id },
                    optionLabel = { id -> Text(voices.firstOrNull { it.id == id }?.name ?: id) },
                    onSelected = onVoiceChanged,
                    includeNone = !selected.capabilities.referenceAudioRequired
                )
                InfoLine(
                    stringResource(
                        if (selected.capabilities.referenceAudioRequired) R.string.audio_workspace_reference_required
                        else R.string.audio_workspace_reference_optional
                    )
                )
            } else {
                if (selected.bundledVoiceStyles.isNotEmpty()) {
                    ChoiceMenu(
                        label = stringResource(R.string.audio_workspace_bundled_voice),
                        value = draft.voiceStyle ?: selected.bundledVoiceStyles.first(),
                        options = selected.bundledVoiceStyles,
                        optionLabel = { id -> Text(id) },
                        onSelected = onVoiceStyleChanged
                    )
                }
                InfoLine(stringResource(R.string.audio_workspace_bundled_voice_note))
            }
        } ?: InfoLine(stringResource(R.string.audio_workspace_select_model_first))
    }
}

@Composable
private fun GenerateStep(
    draft: AudioWorkspaceDraft,
    model: AudioModelOption?,
    voices: List<AudioVoiceProfileUi>,
    validationErrors: List<AudioDraftValidationError>,
    job: AudioJobUiState,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onStepSelected: (AudioGuidedStep) -> Unit
) {
    AppSectionCard {
        SectionTitle(
            icon = Icons.Default.GraphicEq,
            title = stringResource(R.string.audio_workspace_step_generate_title),
            subtitle = stringResource(R.string.audio_workspace_step_generate_hint)
        )
        SummaryRow(stringResource(R.string.audio_workspace_summary_model), model?.displayName ?: stringResource(R.string.audio_workspace_not_selected))
        SummaryRow(stringResource(R.string.audio_workspace_summary_language), stringResource(languageLabelRes(draft.language)))
        SummaryRow(stringResource(R.string.audio_workspace_summary_voice), voices.firstOrNull { it.id == draft.voiceProfileId }?.name ?: draft.voiceStyle ?: stringResource(R.string.audio_workspace_bundled_voice))
        SummaryRow(stringResource(R.string.audio_workspace_summary_text), if (draft.sourceName != null) draft.sourceName else stringResource(R.string.audio_workspace_characters, draft.text.length))
        if (validationErrors.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.audio_workspace_ready_requirements), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                    validationErrors.forEach { error ->
                        Text(
                            "• " + stringResource(error.labelRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.clickable {
                                onStepSelected(error.step())
                            }
                        )
                    }
                }
            }
        }
        when (job.status) {
            AudioJobStatus.PREPARING, AudioJobStatus.RUNNING, AudioJobStatus.CANCELLING -> {
                if (job.totalChunks > 0 && job.progress != null) {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(job.stageLabel ?: stringResource(R.string.audio_workspace_job_running), style = MaterialTheme.typography.bodySmall)
                if (job.totalChunks > 0) Text(stringResource(R.string.audio_workspace_chunk_progress, job.completedChunks, job.totalChunks), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.audio_workspace_cancel))
                }
            }
            AudioJobStatus.COMPLETE -> AppStatePanel(
                kind = AppStateKind.Success,
                title = stringResource(R.string.audio_workspace_job_complete),
                message = stringResource(R.string.audio_workspace_job_complete_hint)
            )
            AudioJobStatus.INTERRUPTED -> AppStatePanel(
                kind = AppStateKind.Interrupted,
                title = stringResource(R.string.audio_workspace_job_interrupted),
                message = stringResource(R.string.audio_workspace_job_interrupted_hint),
                actionLabel = stringResource(R.string.audio_workspace_retry),
                onAction = onRetry
            )
            AudioJobStatus.ERROR -> AppStatePanel(
                kind = AppStateKind.Error,
                title = stringResource(R.string.audio_workspace_job_error),
                message = job.message ?: stringResource(R.string.audio_workspace_job_error_hint),
                actionLabel = stringResource(R.string.audio_workspace_retry),
                onAction = onRetry
            )
            AudioJobStatus.IDLE -> Unit
        }
        Button(
            onClick = onGenerate,
            enabled = validationErrors.isEmpty() && job.status !in setOf(AudioJobStatus.PREPARING, AudioJobStatus.RUNNING, AudioJobStatus.CANCELLING),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.audio_workspace_generate))
        }
    }
}

@Composable
private fun AdvancedControls(
    viewModel: AudioWorkspaceViewModel,
    model: AudioModelOption?,
    draft: AudioWorkspaceDraft,
    onDraftChanged: ((AudioWorkspaceDraft) -> AudioWorkspaceDraft) -> Unit
) {
    AppSectionCard {
        SectionTitle(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_workspace_advanced_title),
            subtitle = stringResource(R.string.audio_workspace_advanced_hint)
        )
        ScrollableTabRow(
            selectedTabIndex = viewModel.advancedTab.ordinal,
            edgePadding = 0.dp,
            divider = {}
        ) {
            AudioAdvancedTab.entries.forEach { tab ->
                Tab(
                    selected = viewModel.advancedTab == tab,
                    onClick = { viewModel.advancedTab = tab },
                    text = {
                        Text(
                            stringResource(
                                when (tab) {
                                    AudioAdvancedTab.COMPONENTS -> R.string.audio_workspace_advanced_components
                                    AudioAdvancedTab.GENERATION -> R.string.audio_workspace_advanced_generation
                                    AudioAdvancedTab.VOICE_PROCESSING -> R.string.audio_workspace_advanced_voice_processing
                                    AudioAdvancedTab.RUNTIME -> R.string.audio_workspace_advanced_runtime
                                    AudioAdvancedTab.OUTPUT -> R.string.audio_workspace_advanced_output
                                }
                            ),
                            maxLines = 1
                        )
                    }
                )
            }
        }
        when (viewModel.advancedTab) {
            AudioAdvancedTab.COMPONENTS -> ComponentsAdvanced(model, draft, onDraftChanged)
            AudioAdvancedTab.GENERATION -> GenerationAdvanced(model, draft, onDraftChanged)
            AudioAdvancedTab.VOICE_PROCESSING -> VoiceProcessingAdvanced(model, draft, onDraftChanged)
            AudioAdvancedTab.RUNTIME -> RuntimeAdvanced(model, draft, onDraftChanged)
            AudioAdvancedTab.OUTPUT -> OutputAdvanced(model, draft, onDraftChanged)
        }
    }
}

@Composable
private fun ComponentsAdvanced(
    model: AudioModelOption?,
    draft: AudioWorkspaceDraft,
    onDraftChanged: ((AudioWorkspaceDraft) -> AudioWorkspaceDraft) -> Unit
) {
    if (model == null) {
        InfoLine(stringResource(R.string.audio_workspace_select_model_first))
        return
    }
    Text(stringResource(R.string.audio_workspace_components_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model.companionOptions.size > 1) {
        ChoiceMenu(
            label = stringResource(R.string.audio_workspace_companion_component),
            value = draft.companionPath?.let { audioComponentLabel(it, model.companionOptions) } ?: stringResource(R.string.audio_workspace_choose_companion),
            options = model.companionOptions,
            optionLabel = { path -> Text(audioComponentLabel(path, model.companionOptions), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            onSelected = { path ->
                onDraftChanged { current ->
                    current.copy(
                        companionPath = path,
                        componentIds = current.componentIds
                            .filterNot { it in model.companionOptions }
                            .plus(path)
                    )
                }
            }
        )
    }
    val selectedCompanion = draft.companionPath?.takeIf { it in model.companionOptions }
    val displayedComponents = model.components
        .filterNot { it in model.companionOptions }
        .plus(listOfNotNull(selectedCompanion?.takeIf { it.isNotBlank() }))
        .distinct()
    displayedComponents.forEach { component ->
        val isCompanion = component in model.companionOptions
        val checked = if (isCompanion) component == selectedCompanion else true
        Row(
            modifier = Modifier.fillMaxWidth().then(
                if (isCompanion) Modifier.clickable {
                    onDraftChanged { current ->
                        current.copy(
                            companionPath = component,
                            componentIds = current.componentIds.filterNot { it in model.companionOptions } + component
                        )
                    }
                } else Modifier
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, enabled = isCompanion, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(audioComponentLabel(component, displayedComponents), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (isCompanion) R.string.audio_workspace_companion_component else R.string.audio_component_main),
                    style = MaterialTheme.typography.labelSmall)
                AudioComponentDetails(component)
                Text(
                    if (model.installState == AudioModelInstallState.INSTALLED) stringResource(R.string.audio_workspace_component_installed)
                    else stringResource(R.string.audio_workspace_component_download_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    InfoLine(stringResource(R.string.audio_workspace_component_validation))
}

internal fun audioComponentLabel(path: String, candidates: List<String> = emptyList()): String {
    val file = java.io.File(path)
    val name = file.name.ifBlank { path }
    return if (candidates.count { java.io.File(it).name == name } > 1) {
        "${file.parentFile?.name.orEmpty()} / $name"
    } else name
}

@Composable
internal fun AudioComponentDetails(path: String) {
    var expanded by remember(path) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.audio_component_hide_details else R.string.audio_component_details))
    }
    if (expanded) {
        SelectionContainer {
            Text(path, modifier = Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(path)) }) {
            Text(stringResource(R.string.audio_component_copy_path))
        }
    }
}

@Composable
private fun GenerationAdvanced(
    model: AudioModelOption?,
    draft: AudioWorkspaceDraft,
    onDraftChanged: ((AudioWorkspaceDraft) -> AudioWorkspaceDraft) -> Unit
) {
    CapabilitySlider(
        label = stringResource(R.string.audio_workspace_speed),
        value = draft.speed,
        range = if (model?.family == AudioModelFamily.SUPERTONIC) 0.5f..2f else 0.25f..4f,
        enabled = model?.capabilities?.supportsSpeed == true,
        unavailable = stringResource(R.string.audio_workspace_unavailable_speed),
        onValueChange = { value -> onDraftChanged { draft -> draft.copy(speed = value) } }
    )
    CapabilitySlider(
        label = stringResource(R.string.audio_workspace_temperature),
        value = draft.temperature,
        range = 0f..2f,
        enabled = model?.capabilities?.supportsTemperature == true,
        unavailable = stringResource(R.string.audio_workspace_unavailable_temperature),
        onValueChange = { value -> onDraftChanged { draft -> draft.copy(temperature = value) } }
    )
    CapabilitySlider(
        label = stringResource(R.string.audio_workspace_top_p),
        value = draft.topP,
        range = 0f..1f,
        enabled = model?.capabilities?.supportsTopP == true,
        unavailable = stringResource(R.string.audio_workspace_unavailable_top_p),
        onValueChange = { value -> onDraftChanged { draft -> draft.copy(topP = value) } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_top_k),
        value = draft.topK.toString(),
        enabled = model?.capabilities?.supportsTopK == true,
        unavailable = stringResource(R.string.audio_workspace_unavailable_top_k),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(topK = parsed.coerceIn(0, 4096)) } } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_seed),
        value = draft.seed,
        enabled = model?.capabilities?.supportsSeed == true,
        unavailable = stringResource(R.string.audio_workspace_unavailable_seed),
        onValueChange = { value -> onDraftChanged { draft -> draft.copy(seed = value.filter { c -> c.isDigit() }) } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_max_tokens),
        value = draft.maxTokens.toString(),
        enabled = model?.capabilities?.supportsMaxTokens == true,
        unavailable = stringResource(R.string.audio_workspace_unavailable_max_tokens),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(maxTokens = parsed.coerceIn(1, 100_000)) } } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_total_steps),
        value = draft.totalSteps.toString(),
        enabled = model?.family == AudioModelFamily.SUPERTONIC,
        unavailable = stringResource(R.string.audio_workspace_unavailable_total_steps),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(totalSteps = parsed.coerceIn(1, 64)) } } }
    )
}

@Composable
private fun VoiceProcessingAdvanced(
    model: AudioModelOption?,
    draft: AudioWorkspaceDraft,
    onDraftChanged: ((AudioWorkspaceDraft) -> AudioWorkspaceDraft) -> Unit
) {
    if (model?.capabilities?.supportsReferenceAudio != true) {
        InfoLine(
            if (model == null) stringResource(R.string.audio_workspace_select_model_first)
            else stringResource(R.string.audio_workspace_unavailable_voice_processing)
        )
        return
    }
    SwitchRow(
        title = stringResource(R.string.audio_workspace_normalize),
        description = stringResource(R.string.audio_workspace_normalize_hint),
        checked = draft.normalizeReference,
        onCheckedChange = { checked -> onDraftChanged { draft -> draft.copy(normalizeReference = checked) } }
    )
    SwitchRow(
        title = stringResource(R.string.audio_workspace_denoise),
        description = stringResource(R.string.audio_workspace_denoise_hint),
        checked = draft.denoiseReference,
        onCheckedChange = { checked -> onDraftChanged { draft -> draft.copy(denoiseReference = checked) } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_trim_start),
        value = draft.trimStartMs.toString(),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(trimStartMs = parsed.coerceAtLeast(0)) } } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_trim_end),
        value = draft.trimEndMs.toString(),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(trimEndMs = parsed.coerceAtLeast(0)) } } }
    )
    InfoLine(stringResource(R.string.audio_workspace_reference_privacy))
}

@Composable
private fun RuntimeAdvanced(
    model: AudioModelOption?,
    draft: AudioWorkspaceDraft,
    onDraftChanged: ((AudioWorkspaceDraft) -> AudioWorkspaceDraft) -> Unit
) {
    NumericField(
        label = stringResource(R.string.audio_workspace_runtime_threads),
        value = draft.runtimeThreads.toString(),
        enabled = model != null && model.family != AudioModelFamily.SUPERTONIC,
        unavailable = stringResource(R.string.audio_workspace_unavailable_runtime),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(runtimeThreads = parsed.coerceIn(1, 256)) } } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_batch_size),
        value = draft.batchSize.toString(),
        enabled = model != null && model.family != AudioModelFamily.SUPERTONIC,
        unavailable = stringResource(R.string.audio_workspace_unavailable_batch),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(batchSize = parsed.coerceIn(1, 256)) } } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_micro_batch_size),
        value = draft.microBatchSize.toString(),
        enabled = model != null && model.family != AudioModelFamily.SUPERTONIC,
        unavailable = stringResource(R.string.audio_workspace_unavailable_micro_batch),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(microBatchSize = parsed.coerceIn(1, 256)) } } }
    )
    NumericField(
        label = stringResource(R.string.audio_workspace_chunk_size),
        value = draft.chunkSize.toString(),
        onValueChange = { value -> value.toIntOrNull()?.let { parsed -> onDraftChanged { draft -> draft.copy(chunkSize = parsed.coerceIn(80, 10_000)) } } }
    )
    InfoLine(stringResource(R.string.audio_workspace_runtime_cpu_only))
}

@Composable
private fun OutputAdvanced(
    model: AudioModelOption?,
    draft: AudioWorkspaceDraft,
    onDraftChanged: ((AudioWorkspaceDraft) -> AudioWorkspaceDraft) -> Unit
) {
    ChoiceMenu(
        label = stringResource(R.string.audio_workspace_output_format),
        value = draft.outputFormat.uppercase(Locale.ROOT),
        options = listOf("wav", "mp3"),
        optionLabel = { Text(it.uppercase(Locale.ROOT)) },
        onSelected = { value -> onDraftChanged { draft -> draft.copy(outputFormat = value) } }
    )
    ChoiceMenu(
        label = stringResource(R.string.audio_workspace_sample_rate),
        value = "${draft.outputSampleRate} Hz",
        options = listOf("16000", "24000", "44100", "48000"),
        optionLabel = { Text("$it Hz") },
        onSelected = { value -> onDraftChanged { draft -> draft.copy(outputSampleRate = value.toInt()) } },
        enabled = model?.capabilities?.supportsOutputSampleRate == true
    )
    SwitchRow(
        title = stringResource(R.string.audio_workspace_include_metadata),
        description = stringResource(R.string.audio_workspace_include_metadata_hint),
        checked = draft.includeMetadata,
        onCheckedChange = { checked -> onDraftChanged { draft -> draft.copy(includeMetadata = checked) } }
    )
}

@Composable
private fun VoicesWorkspace(
    runtimeState: AudioWorkspaceUiState,
    onImport: () -> Unit,
    onRecord: () -> Unit,
    onRename: (AudioVoiceProfileUi) -> Unit,
    onDelete: (String) -> Unit,
    onPreview: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppChromeDefaults.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppPageHeader(
                title = stringResource(R.string.audio_workspace_voices_title),
                subtitle = stringResource(R.string.audio_workspace_voices_subtitle),
                eyebrow = stringResource(R.string.audio_workspace_local_badge)
            )
        }
        item {
            AppSectionCard {
                SectionTitle(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.audio_workspace_add_voice),
                    subtitle = stringResource(R.string.audio_workspace_add_voice_hint)
                )
                ResponsiveActionGroup(
                    actions = listOf(
                        ResponsiveAction(
                            label = stringResource(R.string.audio_workspace_import_audio),
                            onClick = onImport,
                            icon = Icons.Default.UploadFile,
                            style = ResponsiveActionStyle.Primary
                        ),
                        ResponsiveAction(
                            label = if (runtimeState.isRecording) stringResource(R.string.audio_workspace_stop_recording)
                            else stringResource(R.string.audio_workspace_record_audio),
                            onClick = onRecord,
                            icon = if (runtimeState.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            style = ResponsiveActionStyle.Secondary
                        )
                    )
                )
                if (runtimeState.isRecording) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.audio_workspace_recording_seconds, runtimeState.recordingSeconds), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.audio_workspace_voice_storage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (runtimeState.voiceProfiles.isEmpty()) {
            item {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.audio_workspace_no_voices),
                    message = stringResource(R.string.audio_workspace_no_voices_hint)
                )
            }
        } else {
            items(runtimeState.voiceProfiles, key = { it.id }) { profile ->
                VoiceProfileCard(
                    profile = profile,
                    activeProfileId = runtimeState.activeVoiceProfileId,
                    playbackState = runtimeState.voicePlaybackState,
                    onRename = onRename,
                    onDelete = onDelete,
                    onPreview = onPreview,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun VoiceProfileCard(
    profile: AudioVoiceProfileUi,
    activeProfileId: String?,
    playbackState: AudioVoicePlaybackState,
    onRename: (AudioVoiceProfileUi) -> Unit,
    onDelete: (String) -> Unit,
    onPreview: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: () -> Unit
) {
    val isActive = activeProfileId == profile.id && playbackState != AudioVoicePlaybackState.IDLE
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = AppChromeDefaults.CompactShape) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.audio_workspace_voice_metadata, stringResource(languageLabelRes(profile.language)), profile.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(profile.sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!profile.isCompatible) Text(stringResource(R.string.audio_workspace_voice_incompatible), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        ResponsiveActionGroup(
            actions = buildList {
                when {
                    !isActive || playbackState == AudioVoicePlaybackState.IDLE -> add(
                        ResponsiveAction(
                            label = stringResource(R.string.audio_workspace_preview_voice),
                            onClick = { onPreview(profile.id) },
                            icon = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.audio_workspace_preview_voice),
                            style = ResponsiveActionStyle.Secondary
                        )
                    )
                    playbackState == AudioVoicePlaybackState.PLAYING -> add(
                        ResponsiveAction(
                            label = stringResource(R.string.audio_workspace_pause_voice),
                            onClick = { onPause(profile.id) },
                            icon = Icons.Default.Pause,
                            contentDescription = stringResource(R.string.audio_workspace_pause_voice),
                            style = ResponsiveActionStyle.Secondary
                        )
                    )
                    playbackState == AudioVoicePlaybackState.PAUSED -> add(
                        ResponsiveAction(
                            label = stringResource(R.string.audio_workspace_resume_voice),
                            onClick = { onResume(profile.id) },
                            icon = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.audio_workspace_resume_voice),
                            style = ResponsiveActionStyle.Secondary
                        )
                    )
                }
                if (isActive) add(
                    ResponsiveAction(
                        label = stringResource(R.string.audio_workspace_stop_voice),
                        onClick = onStop,
                        icon = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.audio_workspace_stop_voice),
                        style = ResponsiveActionStyle.Text
                    )
                )
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onRename(profile) }) { Icon(Icons.Default.Edit, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.action_rename)) }
            TextButton(onClick = { onDelete(profile.id) }) { Icon(Icons.Default.Delete, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.action_delete)) }
        }
    }
}

@Composable
private fun VoiceImportDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (AudioVoiceImportOptions) -> Unit
) {
    val localizedDefaultName = stringResource(R.string.audio_workspace_default_voice_name)
    var name by remember(defaultName, localizedDefaultName) {
        mutableStateOf(defaultName.substringBeforeLast('.').ifBlank { localizedDefaultName })
    }
    var language by remember { mutableStateOf("en") }
    var trimStart by remember { mutableStateOf("0") }
    var trimEnd by remember { mutableStateOf("10000") }
    var normalize by remember { mutableStateOf(true) }
    var denoise by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_workspace_import_voice_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.audio_workspace_voice_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceMenu(stringResource(R.string.audio_workspace_language), stringResource(languageLabelRes(language)), listOf("en", "es", "de", "fr", "it"), { option -> Text(stringResource(languageLabelRes(option))) }, { language = it })
                NumericField(stringResource(R.string.audio_workspace_trim_start), trimStart, onValueChange = { trimStart = it })
                NumericField(stringResource(R.string.audio_workspace_trim_end), trimEnd, onValueChange = { trimEnd = it })
                SwitchRow(stringResource(R.string.audio_workspace_normalize), stringResource(R.string.audio_workspace_normalize_hint), normalize) { normalize = it }
                SwitchRow(stringResource(R.string.audio_workspace_denoise), stringResource(R.string.audio_workspace_denoise_hint), denoise) { denoise = it }
                Text(stringResource(R.string.audio_workspace_voice_import_format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.audio_workspace_reference_quality_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(AudioVoiceImportOptions(name.trim(), language, trimStart.toIntOrNull() ?: 0, trimEnd.toIntOrNull() ?: 10_000, normalize, denoise))
            }, enabled = name.isNotBlank()) { Text(stringResource(R.string.audio_workspace_save_voice)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun RenameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ChoiceMenu(
    label: String,
    value: String,
    options: List<String>,
    optionLabel: @Composable (String) -> Unit,
    onSelected: (String) -> Unit,
    enabled: Boolean = true,
    includeNone: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.fillMaxWidth()) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (includeNone) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.audio_workspace_no_voice)) }, onClick = { expanded = false; onSelected("") })
                }
                options.forEach { option ->
                    DropdownMenuItem(text = { optionLabel(option) }, onClick = { expanded = false; onSelected(option) })
                }
            }
        }
    }
}

@Composable
private fun CapabilitySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    unavailable: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text("%.2f".format(Locale.ROOT, value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled)
        if (!enabled) Text(unavailable, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    enabled: Boolean = true,
    unavailable: String? = null,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (!enabled && unavailable != null) Text(unavailable, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoLine(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun languageLabelRes(code: String): Int = when (code.lowercase()) {
    "en" -> R.string.audio_language_english
    "es" -> R.string.audio_language_spanish
    "de" -> R.string.audio_language_german
    "fr" -> R.string.audio_language_french
    "it" -> R.string.audio_language_italian
    "pt" -> R.string.audio_language_portuguese
    "ja" -> R.string.audio_language_japanese
    "ko" -> R.string.audio_language_korean
    "ru" -> R.string.audio_language_russian
    "zh" -> R.string.audio_language_chinese
    else -> R.string.audio_language_unknown
}

private fun AudioDraftValidationError.labelRes(): Int = when (this) {
    AudioDraftValidationError.MODEL_REQUIRED -> R.string.audio_workspace_validation_model
    AudioDraftValidationError.TEXT_REQUIRED -> R.string.audio_workspace_validation_text
    AudioDraftValidationError.REFERENCE_AUDIO_REQUIRED -> R.string.audio_workspace_validation_reference
    AudioDraftValidationError.LANGUAGE_UNSUPPORTED -> R.string.audio_workspace_validation_language
    AudioDraftValidationError.COMPANION_REQUIRED -> R.string.audio_workspace_validation_companion
    AudioDraftValidationError.MODEL_LANGUAGE_REQUIRED -> R.string.audio_workspace_validation_model_language
    AudioDraftValidationError.ADVANCED_SETTINGS_INVALID -> R.string.audio_workspace_validation_advanced
}

private fun AudioDraftValidationError.step(): AudioGuidedStep = when (this) {
    AudioDraftValidationError.ADVANCED_SETTINGS_INVALID -> AudioGuidedStep.GENERATE
    AudioDraftValidationError.MODEL_REQUIRED -> AudioGuidedStep.MODEL
    AudioDraftValidationError.TEXT_REQUIRED -> AudioGuidedStep.TEXT
    AudioDraftValidationError.REFERENCE_AUDIO_REQUIRED,
    AudioDraftValidationError.LANGUAGE_UNSUPPORTED -> AudioGuidedStep.VOICE
    AudioDraftValidationError.COMPANION_REQUIRED,
    AudioDraftValidationError.MODEL_LANGUAGE_REQUIRED -> AudioGuidedStep.MODEL
}

private val audioWorkspaceSections = listOf(AudioWorkspaceSection.SPEECH, AudioWorkspaceSection.VOICES, AudioWorkspaceSection.MUSIC, AudioWorkspaceSection.SOUND_EFFECTS, AudioWorkspaceSection.HISTORY)
