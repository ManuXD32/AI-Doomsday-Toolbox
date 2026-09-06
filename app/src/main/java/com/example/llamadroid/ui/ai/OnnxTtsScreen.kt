package com.example.llamadroid.ui.ai

import android.media.MediaPlayer
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxTtsStorage
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.onnx.supertonicLanguageCodes
import com.example.llamadroid.service.OnnxTtsGenerationJobSpec
import com.example.llamadroid.service.OnnxTtsGenerationService
import com.example.llamadroid.service.OnnxTtsGenerationState
import com.example.llamadroid.service.OnnxTtsGenerationStateStore
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.navigation.Screen
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnnxTtsScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { AppDatabase.getDatabase(context) }
    val models by db.modelDao().getModelsByType(ModelType.ONNX_TTS).collectAsState(initial = emptyList())
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(models) {
        if (models.none { it.filename == selectedModelId }) selectedModelId = models.firstOrNull()?.filename.orEmpty()
    }
    val selectedModel = remember(models, selectedModelId) {
        models.firstOrNull { it.filename == selectedModelId } ?: models.firstOrNull()
    }
    val voiceOptions = remember(selectedModel?.path) {
        selectedModel?.let { resolveSupertonicVoices(File(it.path)) }.orEmpty()
    }
    val languageOptions = remember { supertonicLanguageCodes }
    var text by rememberSaveable { mutableStateOf("") }
    var sourceUri by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf("en") }
    var voiceName by rememberSaveable(selectedModel?.path) {
        mutableStateOf(voiceOptions.firstOrNull().orEmpty())
    }
    var speed by rememberSaveable { mutableFloatStateOf(1.05f) }
    var steps by rememberSaveable { mutableIntStateOf(8) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var lastAudio by remember { mutableStateOf<File?>(null) }
    var historyRefresh by remember { mutableIntStateOf(0) }
    val history = remember(historyRefresh) { OnnxTtsStorage.listGeneratedAudio(context) }
    val latestAudio = lastAudio?.takeIf { it.isFile } ?: history.firstOrNull()
    val generationState by OnnxTtsGenerationStateStore.state.collectAsState()
    var lastCompletedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(voiceOptions) {
        if (voiceOptions.isNotEmpty() && voiceName !in voiceOptions) {
            voiceName = voiceOptions.first()
        }
    }

    LaunchedEffect(languageOptions) {
        if (language !in languageOptions) {
            language = "en"
        }
    }

    LaunchedEffect(generationState) {
        when (val state = generationState) {
            is OnnxTtsGenerationState.Running -> {
                isRunning = true
                progress = state.progress
                status = state.status
            }
            is OnnxTtsGenerationState.Complete -> {
                isRunning = false
                progress = 1f
                status = ""
                if (lastCompletedPath != state.audioPath) {
                    lastCompletedPath = state.audioPath
                    lastAudio = File(state.audioPath)
                    historyRefresh++
                    Toast.makeText(context, resources.getString(R.string.onnx_tts_complete), Toast.LENGTH_SHORT).show()
                }
            }
            is OnnxTtsGenerationState.Error -> {
                isRunning = false
                progress = 0f
                status = state.message
                Toast.makeText(
                    context,
                    resources.getString(R.string.onnx_tts_error_generate, state.message),
                    Toast.LENGTH_LONG
                ).show()
            }
            OnnxTtsGenerationState.Idle -> {
                isRunning = false
                progress = 0f
                status = ""
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
            // Some providers do not expose persistable grants; the immediate grant is still passed to the service.
        }
        val name = queryDisplayName(context, uri)
        sourceUri = uri.toString()
        sourceName = name
        text = ""
        Toast.makeText(context, resources.getString(R.string.onnx_tts_file_loaded, name), Toast.LENGTH_SHORT).show()
    }

    fun startTts() {
        val model = selectedModel ?: return
        progress = 0f
        status = resources.getString(R.string.onnx_tts_status_starting)
        OnnxTtsGenerationService.start(
            context,
            OnnxTtsGenerationJobSpec(
                modelPath = model.path,
                modelName = model.filename,
                text = text.takeIf { it.isNotBlank() },
                sourceUri = sourceUri,
                sourceName = sourceName,
                language = language,
                voiceName = voiceName,
                totalSteps = steps,
                speed = speed
            )
        )
    }

    AppPageBackground {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.onnx_tts_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.onnx_tts_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.onnx_tts_text_section),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Button(
                                    onClick = {
                                        filePicker.launch(
                                            arrayOf(
                                                "application/pdf",
                                                "text/*",
                                                "application/epub+zip",
                                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                "application/json",
                                                "text/html"
                                            )
                                        )
                                    },
                                    enabled = !isRunning,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.onnx_tts_pick_file))
                                }
                            }
                            sourceName?.let {
                                Text(
                                    stringResource(R.string.onnx_tts_source_file, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    sourceUri = null
                                    sourceName = null
                                },
                                label = { Text(stringResource(R.string.onnx_tts_text_label)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                maxLines = 10
                            )
                            AppAdvancedSection(title = stringResource(R.string.soft_studio_advanced)) {
                            Text(stringResource(R.string.onnx_tts_speed_value, speed))
                            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f, enabled = !isRunning)
                            Text(stringResource(R.string.onnx_tts_steps_value, steps))
                            Slider(
                                value = steps.toFloat(),
                                onValueChange = { steps = it.toInt().coerceIn(1, 32) },
                                valueRange = 1f..32f,
                                steps = 30,
                                enabled = !isRunning
                            )
                            }
                            if (isRunning) {
                                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            }
                            }
                        }
                    }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(stringResource(R.string.onnx_tts_model_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (models.isEmpty()) {
                                AppStatePanel(
                                    kind = AppStateKind.Blocked,
                                    title = stringResource(R.string.onnx_tts_no_model),
                                    actionLabel = stringResource(R.string.models_hub),
                                    onAction = { navController.navigate(Screen.OnnxModels.route) }
                                )
                            } else {
                                OnnxTtsModelPicker(
                                    models = models,
                                    selected = selectedModel?.filename.orEmpty(),
                                    onSelected = { selectedModelId = it }
                                )
                            }
                            OnnxTtsDropdownPicker(
                                label = stringResource(R.string.onnx_tts_voice_label),
                                selected = voiceName,
                                options = voiceOptions,
                                onSelected = { voiceName = it },
                                enabled = voiceOptions.isNotEmpty() && !isRunning
                            )
                            OnnxTtsDropdownPicker(
                                label = stringResource(R.string.onnx_tts_language_label),
                                selected = language,
                                options = languageOptions,
                                onSelected = { language = it },
                                enabled = !isRunning
                            )
                        }
                }
                }
                latestAudio?.let { file ->
                    item { OnnxTtsAudioCard(file = file, title = stringResource(R.string.onnx_tts_latest_audio)) }
                }
                item {
                    OnnxTtsGalleryEntryCard(
                        audioCount = history.size,
                        onOpen = { navController.navigate(Screen.OnnxTtsGallery.route) }
                    )
                }
            }
            AppTaskActionFooter(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                if (isRunning) {
                    Text(
                        text = status.ifBlank { stringResource(R.string.onnx_tts_status_starting) },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { context.startService(OnnxTtsGenerationService.cancelIntent(context)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.soft_studio_cancel))
                    }
                } else {
                    Button(
                        onClick = ::startTts,
                        enabled = selectedModel != null && (text.isNotBlank() || !sourceUri.isNullOrBlank()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text(stringResource(R.string.soft_studio_generate))
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun OnnxTtsGalleryEntryCard(audioCount: Int, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.onnx_tts_generated_audio_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.onnx_tts_generated_audio_desc, audioCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onnx_tts_open_gallery))
            }
        }
    }
}

@Composable
internal fun OnnxTtsAudioCard(file: File, title: String) {
    var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(file.absolutePath) { mutableStateOf(false) }
    var playbackFailed by remember(file.absolutePath) { mutableStateOf(false) }
    DisposableEffect(file.absolutePath) {
        onDispose {
            runCatching { player?.release() }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val current = player
                    if (current?.isPlaying == true) {
                        current.pause()
                        isPlaying = false
                    } else {
                        playbackFailed = false
                        runCatching {
                            current?.release()
                            val next = MediaPlayer()
                            player = next
                            next.setOnCompletionListener {
                                isPlaying = false
                                runCatching { it.release() }
                                player = null
                            }
                            next.setOnErrorListener { media, _, _ ->
                                isPlaying = false
                                playbackFailed = true
                                runCatching { media.release() }
                                player = null
                                true
                            }
                            next.setDataSource(file.absolutePath)
                            next.prepare()
                            next.start()
                            isPlaying = true
                        }.onFailure {
                            runCatching { player?.release() }
                            player = null
                            isPlaying = false
                            playbackFailed = true
                        }
                    }
                }
            ) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.action_pause else R.string.notes_audio_play))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (playbackFailed) {
            Text(stringResource(R.string.soft_studio_audio_playback_failed),
                modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnnxTtsDropdownPicker(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun OnnxTtsModelPicker(models: List<ModelEntity>, selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { model ->
            Button(
                onClick = { onSelected(model.filename) },
                enabled = model.filename != selected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
}
