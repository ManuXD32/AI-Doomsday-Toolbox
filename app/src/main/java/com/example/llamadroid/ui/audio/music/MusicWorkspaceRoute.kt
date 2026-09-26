package com.example.llamadroid.ui.audio.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

/** Embedded in the canonical Audio workspace; model management stays in LiteRT. */
@Composable
fun MusicWorkspaceRoute(kind: String, navController: NavController) {
    val context = LocalContext.current
    val controller = remember(context, kind) { MusicWorkspaceController(context, kind) }
    val state by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }
    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importFile(it, lora = false) }
    }
    val loraPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importFile(it, lora = true) }
    }
    val validation = controller.validationError()
    MusicWorkspace(
        draft = state.draft, choices = state.choices, onChange = controller::update,
        onManageModels = { navController.navigate(Screen.LiteRtModels.createRoute("catalog")) },
        onPickInput = { inputPicker.launch(arrayOf("audio/*")) },
        onPickLora = { loraPicker.launch(arrayOf("application/octet-stream", "application/x-safetensors", "*/*")) },
        onGenerate = controller::generate, onCancel = controller::cancel, onRetry = controller::retry,
        busy = state.busy,
        stage = if (state.importing) stringResource(R.string.audio_music_validating) else state.job?.stageMessage.orEmpty(),
        completed = state.job?.completedChunks ?: 0, total = state.job?.totalChunks ?: 0,
        error = if (state.draftError) stringResource(R.string.audio_music_draft_error) else state.error ?: state.job?.errorMessage ?: validation,
        ready = state.loaded && !state.draftError && validation == null, canRetry = state.canRetry
    )
}
