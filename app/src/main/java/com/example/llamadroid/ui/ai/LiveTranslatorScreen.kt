package com.example.llamadroid.ui.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteBackendUrlSupport
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_LITERT
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_OLLAMA
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_ONE
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_SPEAKER_TWO
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import com.example.llamadroid.data.db.LiveTranslatorTurnEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.LiveTranslatorPhase
import com.example.llamadroid.service.LiveTranslatorLiteRtEngineManager
import com.example.llamadroid.service.LiveTranslatorSamplePhase
import com.example.llamadroid.service.LiveTranslatorService
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryMetadata
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.util.AIConstants
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val LITERT_BACKEND_AUTO = "auto"
private const val LITERT_BACKEND_CPU = "cpu"
private const val LITERT_BACKEND_GPU = "gpu"

private val supertonicLanguageCodes: List<String> = listOf(
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi",
    "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv",
    "tr", "uk", "vi"
)

private fun resolveSupertonicVoices(bundleRoot: File): List<String> {
    val voiceDir = File(bundleRoot, "voice_styles")
    return voiceDir.listFiles().orEmpty()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        .map { it.nameWithoutExtension }
        .sortedWith(compareBy<String> { if (it.equals("M1", ignoreCase = true)) 0 else 1 }.thenBy { it })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        }
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslatorScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startupGuard = rememberAiJobStartupGuard()
    val db = remember { AppDatabase.getDatabase(context) }
    val templates by db.liveTranslatorTemplateDao().observeTemplates().collectAsState(initial = emptyList())
    val sessions by db.liveTranslatorSessionDao().observeSessions().collectAsState(initial = emptyList())
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val ttsModels by db.modelDao().getModelsByType(ModelType.ONNX_TTS).collectAsState(initial = emptyList())
    val liteRtModels by db.liteRtModelDao().observeAll().collectAsState(initial = emptyList())
    val serviceState by LiveTranslatorService.state.collectAsState()
    val sampleState by LiveTranslatorService.sampleState.collectAsState()
    val defaultTemplateName = stringResource(R.string.live_translator_template_travel)
    val defaultSpeaker1Language = stringResource(R.string.live_translator_language_english)
    val defaultSpeaker2Language = stringResource(R.string.live_translator_language_spanish)
    var selectedTemplateId by remember { mutableLongStateOf(-1L) }
    var templateName by remember(defaultTemplateName) { mutableStateOf(defaultTemplateName) }
    var speaker1Language by remember(defaultSpeaker1Language) { mutableStateOf(defaultSpeaker1Language) }
    var speaker2Language by remember(defaultSpeaker2Language) { mutableStateOf(defaultSpeaker2Language) }
    var whisperModelPath by remember { mutableStateOf<String?>(null) }
    var whisperThreads by remember { mutableIntStateOf(4) }
    var ttsModelPath by remember { mutableStateOf<String?>(null) }
    var ttsModelName by remember { mutableStateOf<String?>(null) }
    var speaker1TtsLanguage by remember { mutableStateOf("en") }
    var speaker2TtsLanguage by remember { mutableStateOf("es") }
    var ttsVoiceName by remember { mutableStateOf<String?>(null) }
    var ttsSteps by remember { mutableIntStateOf(8) }
    var ttsSpeed by remember { mutableFloatStateOf(1.05f) }
    var backendEngine by remember { mutableStateOf(LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER) }
    var llamaServerUrl by remember { mutableStateOf(SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL) }
    var llamaSwapUrl by remember { mutableStateOf(SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL) }
    var llamaModelName by remember { mutableStateOf("") }
    var ollamaUrl by remember { mutableStateOf(AIConstants.Urls.OLLAMA_DEFAULT) }
    var ollamaModelName by remember { mutableStateOf("") }
    var liteRtModelId by remember { mutableStateOf<Long?>(null) }
    var liteRtBackend by remember { mutableStateOf("auto") }
    var liteRtMtpEnabled by remember { mutableStateOf(false) }
    var liteRtThinkingEnabled by remember { mutableStateOf(false) }
    var contextSize by remember { mutableStateOf("4096") }
    var maxTokens by remember { mutableStateOf("512") }
    var temperature by remember { mutableFloatStateOf(0.2f) }
    var timeoutSeconds by remember { mutableStateOf("120") }
    var startSpeakingTimeout by remember { mutableStateOf("10") }
    var finishedTalkingTimeout by remember { mutableStateOf("5") }
    val selectedTtsModel = ttsModels.firstOrNull { it.path == ttsModelPath } ?: ttsModels.firstOrNull()
    val voiceOptions = remember(selectedTtsModel?.path) {
        selectedTtsModel?.let { resolveSupertonicVoices(File(it.path)) }.orEmpty()
    }
    var selectedSessionId by remember { mutableLongStateOf(-1L) }
    val activeSessionId = serviceState.sessionId.takeIf { it > 0L }
    val visibleSessionId = activeSessionId ?: selectedSessionId.takeIf { it > 0L } ?: sessions.firstOrNull()?.id ?: -1L
    val turns by remember(visibleSessionId) {
        if (visibleSessionId > 0L) db.liveTranslatorTurnDao().observeTurns(visibleSessionId)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun applyTemplate(template: LiveTranslatorTemplateEntity) {
        selectedTemplateId = template.id
        templateName = template.name
        speaker1Language = template.speaker1Language
        speaker2Language = template.speaker2Language
        whisperModelPath = template.whisperModelPath
        whisperThreads = template.whisperThreads
        ttsModelPath = template.ttsModelPath
        ttsModelName = template.ttsModelName
        speaker1TtsLanguage = template.speaker1TtsLanguage
        speaker2TtsLanguage = template.speaker2TtsLanguage
        ttsVoiceName = template.ttsVoiceName
        ttsSteps = template.ttsSteps
        ttsSpeed = template.ttsSpeed
        backendEngine = template.backendEngine
        llamaServerUrl = RemoteBackendUrlSupport.resolveStoredUrl(
            storedUrl = template.llamaServerUrl,
            legacyHost = template.llamaHost,
            legacyPort = template.llamaPort,
            defaultPort = 8080
        )
        llamaSwapUrl = RemoteBackendUrlSupport.resolveStoredUrl(
            storedUrl = template.llamaSwapUrl,
            legacyHost = template.llamaHost,
            legacyPort = template.llamaPort,
            defaultPort = 9292
        )
        llamaModelName = template.llamaModelName.orEmpty()
        ollamaUrl = RemoteBackendUrlSupport.resolveStoredUrl(
            storedUrl = template.ollamaUrl,
            legacyHost = template.ollamaHost,
            legacyPort = template.ollamaPort,
            defaultPort = 11434
        )
        ollamaModelName = template.ollamaModelName.orEmpty()
        liteRtModelId = template.liteRtModelId
        liteRtBackend = template.liteRtBackend
        liteRtMtpEnabled = template.liteRtMtpEnabled
        liteRtThinkingEnabled = template.liteRtThinkingEnabled
        contextSize = template.contextSize.toString()
        maxTokens = template.maxTokens.toString()
        temperature = template.temperature
        timeoutSeconds = template.timeoutSeconds.toString()
        startSpeakingTimeout = template.startSpeakingTimeoutSeconds.toString()
        finishedTalkingTimeout = template.finishedTalkingTimeoutSeconds.toString()
    }

    LaunchedEffect(templates) {
        if (selectedTemplateId <= 0L && templates.isNotEmpty()) {
            applyTemplate(templates.first())
        }
    }
    LaunchedEffect(ttsModels) {
        if (ttsModelPath.isNullOrBlank() && ttsModels.isNotEmpty()) {
            ttsModelPath = ttsModels.first().path
            ttsModelName = ttsModels.first().filename
        }
    }
    LaunchedEffect(whisperModels) {
        if (whisperModelPath.isNullOrBlank() && whisperModels.isNotEmpty()) {
            whisperModelPath = whisperModels.first().path
        }
    }
    LaunchedEffect(voiceOptions) {
        if (voiceOptions.isNotEmpty() && ttsVoiceName !in voiceOptions) {
            ttsVoiceName = voiceOptions.first()
        }
    }

    fun buildTemplate(id: Long = selectedTemplateId.takeIf { it > 0L } ?: 0L): LiveTranslatorTemplateEntity =
        run {
            val resolvedLlamaServerUrl = RemoteBackendUrlSupport.parseForStorage(llamaServerUrl, 8080)
            val resolvedLlamaSwapUrl = RemoteBackendUrlSupport.parseForStorage(llamaSwapUrl, 9292)
            val resolvedOllamaUrl = RemoteBackendUrlSupport.parseForStorage(ollamaUrl, 11434)
            val legacyLlamaEndpoint = if (backendEngine == LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP) {
                resolvedLlamaSwapUrl
            } else {
                resolvedLlamaServerUrl
            }

            LiveTranslatorTemplateEntity(
                id = id,
                name = templateName.trim().ifBlank { context.getString(R.string.live_translator_default_template) },
                speaker1Language = speaker1Language.trim().ifBlank { context.getString(R.string.live_translator_language_english) },
                speaker2Language = speaker2Language.trim().ifBlank { context.getString(R.string.live_translator_language_spanish) },
                whisperModelPath = whisperModelPath,
                whisperThreads = whisperThreads.coerceIn(1, 16),
                ttsModelPath = ttsModelPath,
                ttsModelName = ttsModelName,
                speaker1TtsLanguage = speaker1TtsLanguage,
                speaker2TtsLanguage = speaker2TtsLanguage,
                ttsVoiceName = ttsVoiceName,
                ttsSteps = ttsSteps.coerceIn(1, 32),
                ttsSpeed = ttsSpeed.coerceIn(0.5f, 2.0f),
                backendEngine = backendEngine,
                llamaServerUrl = resolvedLlamaServerUrl.normalizedUrl,
                llamaSwapUrl = resolvedLlamaSwapUrl.normalizedUrl,
                llamaHost = legacyLlamaEndpoint.host,
                llamaPort = legacyLlamaEndpoint.port,
                llamaModelName = llamaModelName.trim().ifBlank { null },
                ollamaUrl = resolvedOllamaUrl.normalizedUrl,
                ollamaHost = resolvedOllamaUrl.host,
                ollamaPort = resolvedOllamaUrl.port,
                ollamaModelName = ollamaModelName.trim().ifBlank { null },
                liteRtModelId = liteRtModelId,
                liteRtBackend = liteRtBackend,
                liteRtMtpEnabled = liteRtMtpEnabled,
                liteRtThinkingEnabled = liteRtThinkingEnabled,
                contextSize = contextSize.toIntOrNull()?.coerceAtLeast(512) ?: 4096,
                maxTokens = maxTokens.toIntOrNull()?.coerceIn(64, 8192) ?: 512,
                temperature = temperature,
                timeoutSeconds = timeoutSeconds.toIntOrNull()?.coerceIn(10, 1800) ?: 120,
                startSpeakingTimeoutSeconds = startSpeakingTimeout.toIntOrNull()?.coerceIn(1, 120) ?: 10,
                finishedTalkingTimeoutSeconds = finishedTalkingTimeout.toIntOrNull()?.coerceIn(1, 30) ?: 5,
                updatedAt = System.currentTimeMillis()
            )
        }

    fun startTranslator() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            val id = db.liveTranslatorTemplateDao().upsert(buildTemplate())
            selectedTemplateId = id
            startupGuard.run("live_translator_start") {
                context.startForegroundService(LiveTranslatorService.startIntent(context, id))
            }
        }
    }

    fun startSampler() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        context.startForegroundService(
            LiveTranslatorService.sampleIntent(
                context = context,
                whisperModelPath = whisperModelPath,
                whisperThreads = whisperThreads
            )
        )
    }

    if (serviceState.isActive) {
        LiveTranslatorActiveDialog(
            state = serviceState,
            turns = turns,
            onStop = { context.startService(LiveTranslatorService.stopIntent(context)) },
            onSpeakerOne = { context.startService(LiveTranslatorService.setNextSpeakerIntent(context, LIVE_TRANSLATOR_SPEAKER_ONE)) },
            onSpeakerTwo = { context.startService(LiveTranslatorService.setNextSpeakerIntent(context, LIVE_TRANSLATOR_SPEAKER_TWO)) }
        )
    }

    AppScreenScaffold(
        title = stringResource(R.string.live_translator_title),
        subtitle = stringResource(R.string.live_translator_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LiveTranslatorStatusCard(
                    state = serviceState,
                    onStop = { context.startService(LiveTranslatorService.stopIntent(context)) },
                    onSpeakerOne = { context.startService(LiveTranslatorService.setNextSpeakerIntent(context, LIVE_TRANSLATOR_SPEAKER_ONE)) },
                    onSpeakerTwo = { context.startService(LiveTranslatorService.setNextSpeakerIntent(context, LIVE_TRANSLATOR_SPEAKER_TWO)) }
                )
            }
            item {
                LiveTranslatorTemplateCard(
                    templates = templates,
                    selectedTemplateId = selectedTemplateId,
                    templateName = templateName,
                    onTemplateNameChange = { templateName = it },
                    onTemplateSelected = { applyTemplate(it) },
                    onSaveNew = {
                        scope.launch {
                            selectedTemplateId = db.liveTranslatorTemplateDao().upsert(buildTemplate(id = 0L))
                        }
                    },
                    onUpdateSelected = {
                        scope.launch {
                            val existing = templates.firstOrNull { it.id == selectedTemplateId }
                            selectedTemplateId = db.liveTranslatorTemplateDao().upsert(
                                buildTemplate(id = selectedTemplateId).copy(
                                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        }
                    },
                    onDelete = {
                        templates.firstOrNull { it.id == selectedTemplateId }?.let { template ->
                            scope.launch {
                                db.liveTranslatorTemplateDao().delete(template)
                                selectedTemplateId = -1L
                            }
                        }
                    }
                )
            }
            item {
                LiveTranslatorLanguagesCard(
                    speaker1Language = speaker1Language,
                    onSpeaker1LanguageChange = { speaker1Language = it },
                    speaker2Language = speaker2Language,
                    onSpeaker2LanguageChange = { speaker2Language = it },
                    sampleState = sampleState,
                    onSamplerClick = ::startSampler,
                    samplerEnabled = !serviceState.isActive && !sampleState.isActive && !whisperModelPath.isNullOrBlank(),
                    onUseSampleForSpeaker1 = {
                        sampleState.detectedLanguage?.let { detected ->
                            speaker1Language = detected
                            sampleState.normalizedLanguage?.let { speaker1TtsLanguage = it }
                        }
                    },
                    onUseSampleForSpeaker2 = {
                        sampleState.detectedLanguage?.let { detected ->
                            speaker2Language = detected
                            sampleState.normalizedLanguage?.let { speaker2TtsLanguage = it }
                        }
                    }
                )
            }
            item {
                LiveTranslatorWhisperCard(
                    models = whisperModels,
                    whisperModelPath = whisperModelPath,
                    onWhisperModelPathChange = { whisperModelPath = it },
                    threads = whisperThreads,
                    onThreadsChange = { whisperThreads = it }
                )
            }
            item {
                LiveTranslatorBackendCard(
                    liteRtModels = liteRtModels,
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
                    liteRtModelId = liteRtModelId,
                    onLiteRtModelIdChange = { liteRtModelId = it },
                    liteRtBackend = liteRtBackend,
                    onLiteRtBackendChange = { liteRtBackend = it },
                    liteRtMtpEnabled = liteRtMtpEnabled,
                    onLiteRtMtpEnabledChange = { liteRtMtpEnabled = it },
                    liteRtThinkingEnabled = liteRtThinkingEnabled,
                    onLiteRtThinkingEnabledChange = { liteRtThinkingEnabled = it },
                    contextSize = contextSize,
                    onContextSizeChange = { contextSize = it },
                    maxTokens = maxTokens,
                    onMaxTokensChange = { maxTokens = it },
                    temperature = temperature,
                    onTemperatureChange = { temperature = it },
                    timeoutSeconds = timeoutSeconds,
                    onTimeoutSecondsChange = { timeoutSeconds = it },
                    liveTranslatorActive = serviceState.isActive
                )
            }
            item {
                LiveTranslatorTtsCard(
                    models = ttsModels,
                    selectedPath = ttsModelPath,
                    onSelectedModel = {
                        ttsModelPath = it.path
                        ttsModelName = it.filename
                    },
                    voiceOptions = voiceOptions,
                    voiceName = ttsVoiceName,
                    onVoiceNameChange = { ttsVoiceName = it },
                    speaker1Language = speaker1TtsLanguage,
                    onSpeaker1LanguageChange = { speaker1TtsLanguage = it },
                    speaker2Language = speaker2TtsLanguage,
                    onSpeaker2LanguageChange = { speaker2TtsLanguage = it },
                    steps = ttsSteps,
                    onStepsChange = { ttsSteps = it },
                    speed = ttsSpeed,
                    onSpeedChange = { ttsSpeed = it }
                )
            }
            item {
                LiveTranslatorTimingCard(
                    startSpeakingTimeout = startSpeakingTimeout,
                    onStartSpeakingTimeoutChange = { startSpeakingTimeout = it },
                    finishedTalkingTimeout = finishedTalkingTimeout,
                    onFinishedTalkingTimeoutChange = { finishedTalkingTimeout = it },
                    onStart = ::startTranslator,
                    canStart = !serviceState.isActive && !whisperModelPath.isNullOrBlank() && !ttsModelPath.isNullOrBlank()
                )
            }
            item {
                LiveTranslatorSessionsCard(
                    sessions = sessions,
                    selectedSessionId = visibleSessionId,
                    onSelect = { selectedSessionId = it },
                    onDelete = { session ->
                        scope.launch { db.liveTranslatorSessionDao().delete(session) }
                    },
                    onRename = { session, title ->
                        scope.launch { db.liveTranslatorSessionDao().rename(session.id, title) }
                    }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.live_translator_transcript_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(turns, key = { it.id }) { turn ->
                LiveTranslatorTurnCard(
                    turn = turn,
                    onDelete = { scope.launch { db.liveTranslatorTurnDao().delete(turn) } }
                )
            }
        }
    }
}

@Composable
private fun LiveTranslatorStatusCard(
    state: com.example.llamadroid.service.LiveTranslatorUiState,
    onStop: () -> Unit,
    onSpeakerOne: () -> Unit,
    onSpeakerTwo: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.status.ifBlank { stringResource(R.string.live_translator_state_idle) }, fontWeight = FontWeight.Bold)
            if (state.isActive) {
                LinearProgressIndicator(progress = { state.inputLevel.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.live_translator_current_speaker, state.currentSpeaker))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSpeakerOne, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.live_translator_speaker_1))
                    }
                    OutlinedButton(onClick = onSpeakerTwo, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.live_translator_speaker_2))
                    }
                }
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_stop))
                }
            }
            if (state.phase == LiveTranslatorPhase.ERROR && !state.error.isNullOrBlank()) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LiveTranslatorActiveDialog(
    state: com.example.llamadroid.service.LiveTranslatorUiState,
    turns: List<LiveTranslatorTurnEntity>,
    onStop: () -> Unit,
    onSpeakerOne: () -> Unit,
    onSpeakerTwo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(state.status.ifBlank { stringResource(R.string.live_translator_state_idle) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { state.inputLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.live_translator_current_speaker, state.currentSpeaker),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(turns.takeLast(12), key = { it.id }) { turn ->
                        LiveTranslatorTurnCard(turn = turn)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onStop) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_stop))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSpeakerOne) {
                    Text(stringResource(R.string.live_translator_speaker_1), maxLines = 1)
                }
                OutlinedButton(onClick = onSpeakerTwo) {
                    Text(stringResource(R.string.live_translator_speaker_2), maxLines = 1)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTranslatorTemplateCard(
    templates: List<LiveTranslatorTemplateEntity>,
    selectedTemplateId: Long,
    templateName: String,
    onTemplateNameChange: (String) -> Unit,
    onTemplateSelected: (LiveTranslatorTemplateEntity) -> Unit,
    onSaveNew: () -> Unit,
    onUpdateSelected: () -> Unit,
    onDelete: () -> Unit
) {
    SectionCard(title = stringResource(R.string.live_translator_templates)) {
        DropdownField(
            label = stringResource(R.string.live_translator_template_picker),
            selected = templates.firstOrNull { it.id == selectedTemplateId }?.name ?: stringResource(R.string.live_translator_no_template),
            values = templates.map { it.name },
            onSelected = { name -> templates.firstOrNull { it.name == name }?.let(onTemplateSelected) }
        )
        OutlinedTextField(
            value = templateName,
            onValueChange = onTemplateNameChange,
            label = { Text(stringResource(R.string.workflow_template_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = onSaveNew) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.live_translator_template_save_new), maxLines = 1)
            }
            OutlinedButton(onClick = onUpdateSelected, enabled = selectedTemplateId > 0L) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.live_translator_template_update_selected), maxLines = 1)
            }
            OutlinedButton(onClick = onDelete, enabled = selectedTemplateId > 0L) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_delete), maxLines = 1)
            }
        }
    }
}

@Composable
private fun LiveTranslatorLanguagesCard(
    speaker1Language: String,
    onSpeaker1LanguageChange: (String) -> Unit,
    speaker2Language: String,
    onSpeaker2LanguageChange: (String) -> Unit,
    sampleState: com.example.llamadroid.service.LiveTranslatorSampleState,
    onSamplerClick: () -> Unit,
    samplerEnabled: Boolean,
    onUseSampleForSpeaker1: () -> Unit,
    onUseSampleForSpeaker2: () -> Unit
) {
    SectionCard(title = stringResource(R.string.live_translator_languages)) {
        OutlinedTextField(
            value = speaker1Language,
            onValueChange = onSpeaker1LanguageChange,
            label = { Text(stringResource(R.string.live_translator_speaker_1_language)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = speaker2Language,
            onValueChange = onSpeaker2LanguageChange,
            label = { Text(stringResource(R.string.live_translator_speaker_2_language)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = onSamplerClick,
            enabled = samplerEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.live_translator_sampler_button))
        }
        if (sampleState.phase != LiveTranslatorSamplePhase.IDLE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = sampleState.status.ifBlank { stringResource(R.string.live_translator_sampler_title) },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                if (sampleState.isActive) {
                    LinearProgressIndicator(
                        progress = { sampleState.inputLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (sampleState.phase == LiveTranslatorSamplePhase.DONE && !sampleState.detectedLanguage.isNullOrBlank()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onUseSampleForSpeaker1) {
                            Text(stringResource(R.string.live_translator_sampler_use_speaker_1), maxLines = 1)
                        }
                        OutlinedButton(onClick = onUseSampleForSpeaker2) {
                            Text(stringResource(R.string.live_translator_sampler_use_speaker_2), maxLines = 1)
                        }
                    }
                }
                if (sampleState.phase == LiveTranslatorSamplePhase.ERROR && !sampleState.error.isNullOrBlank()) {
                    Text(sampleState.error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LiveTranslatorWhisperCard(
    models: List<ModelEntity>,
    whisperModelPath: String?,
    onWhisperModelPathChange: (String?) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit
) {
    SectionCard(title = stringResource(R.string.workflow_step_transcribe)) {
        DropdownField(
            label = stringResource(R.string.workflow_whisper_model_label),
            selected = models.firstOrNull { it.path == whisperModelPath }?.filename ?: stringResource(R.string.workflow_select_whisper),
            values = models.map { it.filename },
            onSelected = { name -> onWhisperModelPathChange(models.firstOrNull { it.filename == name }?.path) }
        )
        Text(stringResource(R.string.live_translator_whisper_auto_detect))
        Text(stringResource(R.string.live_translator_threads_value, threads), style = MaterialTheme.typography.bodySmall)
        Slider(value = threads.toFloat(), onValueChange = { onThreadsChange(it.toInt().coerceIn(1, 16)) }, valueRange = 1f..16f, steps = 14)
    }
}

@Composable
internal fun LiveTranslatorBackendCard(
    liteRtModels: List<com.example.llamadroid.data.model.LiteRtModelEntity>,
    backendEngine: String,
    onBackendEngineChange: (String) -> Unit,
    llamaServerUrl: String,
    onLlamaServerUrlChange: (String) -> Unit,
    llamaSwapUrl: String,
    onLlamaSwapUrlChange: (String) -> Unit,
    llamaModelName: String,
    onLlamaModelNameChange: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrlChange: (String) -> Unit,
    ollamaModelName: String,
    onOllamaModelNameChange: (String) -> Unit,
    liteRtModelId: Long?,
    onLiteRtModelIdChange: (Long?) -> Unit,
    liteRtBackend: String,
    onLiteRtBackendChange: (String) -> Unit,
    liteRtMtpEnabled: Boolean,
    onLiteRtMtpEnabledChange: (Boolean) -> Unit,
    liteRtThinkingEnabled: Boolean,
    onLiteRtThinkingEnabledChange: (Boolean) -> Unit,
    contextSize: String,
    onContextSizeChange: (String) -> Unit,
    maxTokens: String,
    onMaxTokensChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    timeoutSeconds: String,
    onTimeoutSecondsChange: (String) -> Unit,
    liveTranslatorActive: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startupGuard = rememberAiJobStartupGuard()
    val selectedLiteRtModel = liteRtModels.firstOrNull { it.id == liteRtModelId }
    val parsedContextSize = contextSize.toIntOrNull()?.coerceAtLeast(512) ?: 4096
    val liteRtPreloadMaxTokens = maxTokens.toIntOrNull()?.coerceIn(1, 8192) ?: 1
    var liteRtLoaded by remember { mutableStateOf(false) }
    var liteRtLoading by remember { mutableStateOf(false) }
    var liteRtLoadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(backendEngine, liteRtModelId, liteRtBackend, parsedContextSize, liteRtMtpEnabled) {
        if (backendEngine == LIVE_TRANSLATOR_ENGINE_LITERT && selectedLiteRtModel != null) {
            liteRtLoaded = LiveTranslatorLiteRtEngineManager.isLoaded(
                context = context,
                model = selectedLiteRtModel,
                backendMode = liteRtBackend,
                contextSize = parsedContextSize,
                mtpEnabled = liteRtMtpEnabled
            )
        } else {
            liteRtLoaded = false
            liteRtLoadError = null
        }
    }

    SectionCard(title = stringResource(R.string.live_translator_backend)) {
        DropdownField(
            label = stringResource(R.string.live_translator_backend_engine),
            selected = backendEngine,
            values = listOf(LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER, LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP, LIVE_TRANSLATOR_ENGINE_OLLAMA, LIVE_TRANSLATOR_ENGINE_LITERT),
            onSelected = onBackendEngineChange
        )
        if (backendEngine == LIVE_TRANSLATOR_ENGINE_LITERT) {
            DropdownField(
                label = stringResource(R.string.litert_model_label),
                selected = selectedLiteRtModel?.displayName ?: stringResource(R.string.litert_error_model_missing),
                values = liteRtModels.map { it.displayName },
                onSelected = { name -> onLiteRtModelIdChange(liteRtModels.firstOrNull { it.displayName == name }?.id) }
            )
            Text(
                text = stringResource(R.string.litert_gallery_accelerator),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    LITERT_BACKEND_AUTO to stringResource(R.string.general_acceleration_mode_auto),
                    LITERT_BACKEND_GPU to stringResource(R.string.litert_backend_gpu),
                    LITERT_BACKEND_CPU to stringResource(R.string.general_acceleration_mode_cpu)
                ).forEach { (backend, label) ->
                    FilterChip(
                        selected = liteRtBackend == backend,
                        onClick = { onLiteRtBackendChange(backend) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.litert_gallery_mtp_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.litert_gallery_mtp_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = liteRtMtpEnabled, onCheckedChange = onLiteRtMtpEnabledChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.litert_thinking_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.litert_thinking_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = liteRtThinkingEnabled, onCheckedChange = onLiteRtThinkingEnabledChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.live_translator_litert_engine_status),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val statusColor = if (liteRtLoaded) Color(0xFF2E7D32) else Color(0xFFC62828)
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape)
                    )
                    Text(
                        text = when {
                            liteRtLoading -> stringResource(R.string.live_translator_litert_loading)
                            liteRtLoaded -> stringResource(R.string.live_translator_litert_loaded)
                            else -> stringResource(R.string.live_translator_litert_unloaded)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val controlsEnabled = !liveTranslatorActive && !liteRtLoading && selectedLiteRtModel != null
                    if (liteRtLoaded) {
                        OutlinedButton(
                            enabled = controlsEnabled,
                            onClick = {
                                val model = selectedLiteRtModel ?: return@OutlinedButton
                                liteRtLoading = true
                                liteRtLoadError = null
                                scope.launch {
                                    LiveTranslatorLiteRtEngineManager.unload(
                                        context = context,
                                        model = model,
                                        backendMode = liteRtBackend,
                                        contextSize = parsedContextSize,
                                        mtpEnabled = liteRtMtpEnabled
                                    )
                                    liteRtLoaded = false
                                    liteRtLoading = false
                                }
                            }
                        ) {
                            Text(stringResource(R.string.live_translator_litert_unload_engine))
                        }
                    } else {
                        Button(
                            enabled = controlsEnabled,
                            onClick = {
                                val model = selectedLiteRtModel ?: return@Button
                                liteRtLoading = true
                                liteRtLoadError = null
                                scope.launch {
                                    runCatching {
                                        startupGuard.runSuspending("live_translator_litert_preload") {
                                            LiveTranslatorLiteRtEngineManager.preload(
                                                context = context,
                                                model = model,
                                                backendMode = liteRtBackend,
                                                contextSize = parsedContextSize,
                                                maxTokens = liteRtPreloadMaxTokens,
                                                temperature = temperature,
                                                thinkingEnabled = liteRtThinkingEnabled,
                                                mtpEnabled = liteRtMtpEnabled
                                            )
                                        }
                                    }.onSuccess {
                                        liteRtLoaded = LiveTranslatorLiteRtEngineManager.isLoaded(
                                            context = context,
                                            model = model,
                                            backendMode = liteRtBackend,
                                            contextSize = parsedContextSize,
                                            mtpEnabled = liteRtMtpEnabled
                                        )
                                    }.onFailure { error ->
                                        liteRtLoaded = false
                                        liteRtLoadError = context.getString(
                                            R.string.live_translator_litert_load_error,
                                            error.message ?: error.javaClass.simpleName
                                        )
                                    }
                                    liteRtLoading = false
                                }
                            }
                        ) {
                            Text(stringResource(R.string.live_translator_litert_load_engine))
                        }
                    }
                }
                if (selectedLiteRtModel == null) {
                    Text(
                        text = stringResource(R.string.live_translator_litert_no_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                liteRtLoadError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            val remoteBackend = when (backendEngine) {
                LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP -> SettingsRepository.PDF_BACKEND_LLAMA_SWAP
                LIVE_TRANSLATOR_ENGINE_OLLAMA -> SettingsRepository.PDF_BACKEND_OLLAMA
                else -> SettingsRepository.PDF_BACKEND_LLAMA_SERVER
            }
            val resolvedLlamaServerUrl = RemoteBackendUrlSupport.parseForStorage(llamaServerUrl, 8080).normalizedUrl
            val resolvedLlamaSwapUrl = RemoteBackendUrlSupport.parseForStorage(llamaSwapUrl, 9292).normalizedUrl
            val resolvedOllamaUrl = RemoteBackendUrlSupport.parseForStorage(ollamaUrl, 11434).normalizedUrl
            val parsedContext = contextSize.toIntOrNull()?.coerceAtLeast(512) ?: 4096
            val parsedMaxTokens = maxTokens.toIntOrNull()?.coerceIn(64, 8192) ?: 512
            val parsedTimeoutMinutes = ((timeoutSeconds.toIntOrNull()?.coerceIn(10, 1800) ?: 120) + 59) / 60
            RemoteSummaryBackendEditor(
                title = stringResource(R.string.video_summary_remote_settings_title),
                backend = remoteBackend,
                onBackendChange = { backend ->
                    onBackendEngineChange(
                        when (SettingsRepository.normalizeOllamaOrLlamaBackend(backend)) {
                            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP
                            SettingsRepository.PDF_BACKEND_OLLAMA -> LIVE_TRANSLATOR_ENGINE_OLLAMA
                            else -> LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER
                        }
                    )
                },
                ollamaUrl = ollamaUrl,
                onOllamaUrlChange = onOllamaUrlChange,
                llamaServerUrl = llamaServerUrl,
                onLlamaServerUrlChange = onLlamaServerUrlChange,
                llamaSwapUrl = llamaSwapUrl,
                onLlamaSwapUrlChange = onLlamaSwapUrlChange,
                ollamaModel = ollamaModelName.ifBlank { null },
                onOllamaModelSelected = onOllamaModelNameChange,
                llamaSwapModel = llamaModelName.ifBlank { null },
                onLlamaSwapModelSelected = onLlamaModelNameChange,
                llamaServerModelLabel = llamaModelName.ifBlank { null },
                llamaServerContextLabel = contextSize.takeIf { it.isNotBlank() }?.let { context.getString(R.string.live_translator_context_tokens, it) },
                llamaServerContextTokens = parsedContext,
                requestedContextForWarning = parsedContext,
                allowBlankUrlRefresh = true,
                fetchMetadata = {
                    RemoteSummaryClientFactory.fromSnapshot(
                        context,
                        RemoteSummarySettingsSnapshot(
                            backend = remoteBackend,
                            ollamaUrl = resolvedOllamaUrl,
                            llamaServerUrl = resolvedLlamaServerUrl,
                            llamaSwapUrl = resolvedLlamaSwapUrl,
                            ollamaModel = ollamaModelName.ifBlank { null },
                            llamaSwapModel = llamaModelName.ifBlank { null },
                            thinkingEnabled = false,
                            llamaServerModelLabel = llamaModelName.ifBlank { null },
                            llamaServerContextTokens = parsedContext,
                            llamaServerContextLabel = context.getString(R.string.live_translator_context_tokens, parsedContext.toString()),
                            chunkContext = parsedContext,
                            chunkMaxTokens = parsedMaxTokens,
                            mergeContext = parsedContext,
                            mergeMaxTokens = parsedMaxTokens,
                            temperature = temperature,
                            timeoutMinutes = parsedTimeoutMinutes.coerceAtLeast(1),
                            targetLanguage = "",
                            summaryPrompt = null,
                            mergePrompt = null
                        )
                    ).fetchMetadata()
                },
                onMetadataLoaded = { metadata: RemoteSummaryMetadata ->
                    when (SettingsRepository.normalizeOllamaOrLlamaBackend(metadata.backend)) {
                        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> {
                            metadata.serverModelLabel?.let(onLlamaModelNameChange)
                            val serverContextTokens = metadata.serverContextTokens ?: 0
                            if (serverContextTokens > 0) {
                                onContextSizeChange(serverContextTokens.toString())
                            }
                        }
                        SettingsRepository.PDF_BACKEND_OLLAMA -> {
                            metadata.availableModels.firstOrNull()?.takeIf { ollamaModelName.isBlank() }?.let(onOllamaModelNameChange)
                        }
                        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> {
                            metadata.availableModels.firstOrNull()?.takeIf { llamaModelName.isBlank() }?.let(onLlamaModelNameChange)
                        }
                    }
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = contextSize, onValueChange = onContextSizeChange, label = { Text(stringResource(R.string.label_context)) }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = maxTokens, onValueChange = onMaxTokensChange, label = { Text(stringResource(R.string.label_max_tokens)) }, modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(value = timeoutSeconds, onValueChange = onTimeoutSecondsChange, label = { Text(stringResource(R.string.live_translator_timeout_seconds)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text(stringResource(R.string.live_translator_temperature_value, temperature), style = MaterialTheme.typography.bodySmall)
        Slider(value = temperature, onValueChange = onTemperatureChange, valueRange = 0f..1f)
    }
}

@Composable
private fun LiveTranslatorTtsCard(
    models: List<ModelEntity>,
    selectedPath: String?,
    onSelectedModel: (ModelEntity) -> Unit,
    voiceOptions: List<String>,
    voiceName: String?,
    onVoiceNameChange: (String?) -> Unit,
    speaker1Language: String,
    onSpeaker1LanguageChange: (String) -> Unit,
    speaker2Language: String,
    onSpeaker2LanguageChange: (String) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit
) {
    SectionCard(title = stringResource(R.string.workflow_media_translate_voice_section)) {
        DropdownField(
            label = stringResource(R.string.onnx_tts_model_section),
            selected = models.firstOrNull { it.path == selectedPath }?.filename ?: stringResource(R.string.onnx_tts_no_model),
            values = models.map { it.filename },
            onSelected = { name -> models.firstOrNull { it.filename == name }?.let(onSelectedModel) }
        )
        DropdownField(stringResource(R.string.onnx_tts_voice_label), voiceName.orEmpty(), voiceOptions, onVoiceNameChange)
        DropdownField(
            stringResource(R.string.live_translator_speaker_1_tts_language),
            speaker1Language,
            supertonicLanguageCodes,
            onSpeaker1LanguageChange
        )
        DropdownField(
            stringResource(R.string.live_translator_speaker_2_tts_language),
            speaker2Language,
            supertonicLanguageCodes,
            onSpeaker2LanguageChange
        )
        Text(stringResource(R.string.onnx_tts_steps_value, steps), style = MaterialTheme.typography.bodySmall)
        Slider(value = steps.toFloat(), onValueChange = { onStepsChange(it.toInt().coerceIn(1, 32)) }, valueRange = 1f..32f, steps = 30)
        Text(stringResource(R.string.onnx_tts_speed_value, speed), style = MaterialTheme.typography.bodySmall)
        Slider(value = speed, onValueChange = onSpeedChange, valueRange = 0.5f..2.0f)
    }
}

@Composable
private fun LiveTranslatorTimingCard(
    startSpeakingTimeout: String,
    onStartSpeakingTimeoutChange: (String) -> Unit,
    finishedTalkingTimeout: String,
    onFinishedTalkingTimeoutChange: (String) -> Unit,
    onStart: () -> Unit,
    canStart: Boolean
) {
    SectionCard(title = stringResource(R.string.live_translator_timing)) {
        OutlinedTextField(value = startSpeakingTimeout, onValueChange = onStartSpeakingTimeoutChange, label = { Text(stringResource(R.string.live_translator_start_speaking_timeout)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = finishedTalkingTimeout, onValueChange = onFinishedTalkingTimeoutChange, label = { Text(stringResource(R.string.live_translator_finished_talking_timeout)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.live_translator_start))
        }
    }
}

@Composable
private fun LiveTranslatorSessionsCard(
    sessions: List<com.example.llamadroid.data.db.LiveTranslatorSessionEntity>,
    selectedSessionId: Long,
    onSelect: (Long) -> Unit,
    onDelete: (com.example.llamadroid.data.db.LiveTranslatorSessionEntity) -> Unit,
    onRename: (com.example.llamadroid.data.db.LiveTranslatorSessionEntity, String) -> Unit
) {
    SectionCard(title = stringResource(R.string.live_translator_saved_transcriptions)) {
        sessions.take(6).forEach { session ->
            var title by remember(session.id, session.title) { mutableStateOf(session.title) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = { onRename(session, title) }) { Icon(Icons.Default.Save, null) }
                IconButton(onClick = { onDelete(session) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
            }
            OutlinedButton(onClick = { onSelect(session.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (session.id == selectedSessionId) stringResource(R.string.live_translator_selected_session, session.title) else session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LiveTranslatorTurnCard(turn: LiveTranslatorTurnEntity, onDelete: (() -> Unit)? = null) {
    val isSpeakerOne = turn.speaker == LIVE_TRANSLATOR_SPEAKER_ONE
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSpeakerOne) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (isSpeakerOne) R.string.live_translator_speaker_1 else R.string.live_translator_speaker_2),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)) }
                }
            }
            if (turn.isError) {
                Text(turn.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
            } else {
                Text(turn.originalText)
                if (!turn.translatedText.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(turn.translatedText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    selected: String,
    values: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && values.isNotEmpty() }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.distinct().forEach { value ->
                DropdownMenuItem(
                    text = { Text(value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
