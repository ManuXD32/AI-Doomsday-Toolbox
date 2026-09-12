package com.example.llamadroid.ui.models

import android.net.Uri
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.isStableAudioComponentType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.StableAudioModelSupport
import com.example.llamadroid.audio.music.StableAudio3ComponentHealth
import com.example.llamadroid.audio.music.StableAudio3ModelDoctor
import com.example.llamadroid.audio.music.StableAudio3ModelDoctorReport
import com.example.llamadroid.audio.music.StableAudio3Kind
import com.example.llamadroid.data.model.StableAudioCuratedBundleCatalog
import com.example.llamadroid.data.model.currentLiteRtDeviceTargetInfo
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.liteRtAudioSupportFromText
import com.example.llamadroid.data.model.liteRtEmbeddingSupportFromText
import com.example.llamadroid.data.model.liteRtVisionSupportFromText
import com.example.llamadroid.data.model.supportsLiteRtEmbedding
import com.example.llamadroid.data.model.supportsLiteRtAudio
import com.example.llamadroid.data.model.supportsLiteRtVision
import com.example.llamadroid.data.repository.LiteRtCatalogEntry
import com.example.llamadroid.data.repository.LiteRtModelCatalog
import com.example.llamadroid.data.repository.LiteRtModelRepository
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceDraft
import com.example.llamadroid.data.model.library.ModelSourceRepository
import com.example.llamadroid.service.LiteRtBackendDoctorResult
import com.example.llamadroid.service.LiteRtBackendDoctorStore
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.DownloadTaskSection
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val LITERT_PROGRESS_PREFIX = "litert:"
private const val LITERT_CONTEXT_USER_MIN = 512
private const val LITERT_CONTEXT_USER_MAX = 131_072
private val LiteRtEmbeddingBlue = Color(0xFF2F80ED)

private val STABLE_AUDIO_MODEL_TYPES = setOf(
    ModelType.LITERT_AUDIO_DIT,
    ModelType.LITERT_AUDIO_COMPONENT
)

private fun isLiteRtProgressKey(key: String): Boolean {
    if (key.startsWith(LITERT_PROGRESS_PREFIX)) return true
    return STABLE_AUDIO_MODEL_TYPES.any { type ->
        key.startsWith("${type.name.lowercase(Locale.US)}|")
    }
}

private fun isLiteRtDownloadTask(task: com.example.llamadroid.data.db.DownloadTaskEntity): Boolean {
    if (task.id.startsWith(LITERT_PROGRESS_PREFIX) || task.progressKey.startsWith(LITERT_PROGRESS_PREFIX)) return true
    val modelType = runCatching { ModelType.valueOf(task.modelType) }.getOrNull()
    return modelType in STABLE_AUDIO_MODEL_TYPES
}

private fun stableAudioKind(family: String?): String? = when (family) {
    StableAudioModelSupport.FAMILY_SFX -> "sfx"
    StableAudioModelSupport.FAMILY_MUSIC,
    StableAudioModelSupport.FAMILY_SHARED -> "music"
    else -> null
}

private fun stableAudioKind(model: ModelEntity): String =
    stableAudioKind(model.audioFamily) ?: "music"

private fun stableAudioKind(
    task: com.example.llamadroid.data.db.DownloadTaskEntity?,
    pending: PendingDownload?
): String? = stableAudioKind(task?.artifactFamily ?: pending?.artifactFamily)

@Composable
@Suppress("UNUSED_PARAMETER")
fun LiteRtModelsScreen(navController: NavController, initialTab: String? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember {
        LiteRtModelRepository(
            context = context,
            modelDao = db.liteRtModelDao()
        )
    }
    val modelRepository = remember { ModelRepository(context, db.modelDao()) }
    val managedModelsFlow = remember(modelRepository) { modelRepository.getModelManagerModels() }
    val managedModels by managedModelsFlow.collectAsState(initial = emptyList())
    val stableAudioModels = remember(managedModels) {
        managedModels.filter { model ->
            model.type.isStableAudioComponentType() &&
                model.isDownloaded
        }
    }
    val downloadTasksFlow = remember(db) { db.downloadTaskDao().observeAll() }
    val downloadTasks by downloadTasksFlow.collectAsState(initial = emptyList())
    val sourceRepository = rememberModelSourceRepository(context)
    val savedSources by sourceRepository.sources.collectAsState(initial = emptyList())
    val sourceProvenance by sourceRepository.provenance.collectAsState(initial = emptyList())
    val models by repository.observeModels().collectAsState(initial = emptyList())
    val progress by DownloadProgressHolder.progress.collectAsState()
    val statuses by DownloadProgressHolder.status.collectAsState()
    val managedRoot = remember(repository) { repository.managedRoot() }
    var selectedTab by remember(initialTab) { mutableIntStateOf(when (initialTab) { "catalog" -> 2; "downloading" -> 1; else -> 0 }) }
    var pendingRename by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var pendingContextModel by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var contextTokenValue by remember { mutableStateOf("") }
    var pendingModalityModel by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var modalityVisionValue by remember { mutableStateOf(false) }
    var modalityAudioValue by remember { mutableStateOf(false) }
    var modalityEmbeddingValue by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportName by remember { mutableStateOf("") }
    var importSupportsVision by remember { mutableStateOf(false) }
    var importSupportsAudio by remember { mutableStateOf(false) }
    var importSupportsEmbedding by remember { mutableStateOf(false) }
    var importSourceUrl by remember { mutableStateOf("") }
    var importSourceLabel by remember { mutableStateOf("") }
    var importSourceError by remember { mutableStateOf<String?>(null) }
    var sourceAsset by remember { mutableStateOf<com.example.llamadroid.data.model.library.InstalledModelAsset?>(null) }
    var pendingDelete by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var pendingAudioDelete by remember { mutableStateOf<ModelEntity?>(null) }
    var pendingAudioRepair by remember { mutableStateOf<ModelEntity?>(null) }
    var pendingExport by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var doctorDetails by remember { mutableStateOf<LiteRtBackendDoctorResult?>(null) }
    var stableDoctorDetails by remember { mutableStateOf<StableAudio3ModelDoctorReport?>(null) }
    var huggingFaceToken by remember { mutableStateOf(repository.huggingFaceToken()) }
    val doctorResults = remember { mutableStateMapOf<Long, List<LiteRtBackendDoctorResult>>() }
    val stableDoctorResults = remember { mutableStateMapOf<String, StableAudio3ModelDoctorReport>() }
    val stableDoctorBusy = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(models) {
        models.forEach { model ->
            if (!doctorResults.containsKey(model.id)) {
                val saved = LiteRtBackendDoctorStore.loadLatest(context, model.id)
                if (saved.isNotEmpty()) doctorResults[model.id] = saved
            }
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val fileName = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
        val inferenceText = fileName.ifBlank { uri.lastPathSegment.orEmpty() }
        pendingImportUri = uri
        pendingImportName = fileName.ifBlank { resources.getString(R.string.litert_models_import) }
        importSupportsVision = liteRtVisionSupportFromText(inferenceText)
        importSupportsAudio = liteRtAudioSupportFromText(inferenceText)
        importSupportsEmbedding = liteRtEmbeddingSupportFromText(inferenceText)
        importSourceUrl = ""
        importSourceLabel = ""
        importSourceError = null
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val model = pendingExport
        pendingExport = null
        if (uri == null || model == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = repository.exportModel(model, uri)
            toast(
                result.fold(
                    onSuccess = { resources.getString(R.string.litert_models_exported) },
                    onFailure = { it.message ?: resources.getString(R.string.error_generic) }
                )
            )
        }
    }

    fun download(entry: LiteRtCatalogEntry) {
        scope.launch {
            val result = repository.startCatalogDownload(entry)
            toast(
                result.fold(
                    onSuccess = { resources.getString(R.string.litert_models_download_started, entry.title) },
                    onFailure = { it.message ?: resources.getString(R.string.error_generic) }
                )
            )
        }
    }

    val activeDownloads = progress.count { (key, value) ->
        isLiteRtProgressKey(key) &&
            (value == DownloadProgressHolder.INDETERMINATE || value in 0f..0.999f)
    }
    val tabs = listOf(
        stringResource(R.string.models_tab_installed),
        stringResource(R.string.models_tab_downloading),
        stringResource(R.string.models_tab_discover)
    )

    AppScreenScaffold(
        title = stringResource(R.string.litert_models_title),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(
                onClick = { navController.navigate("${Screen.ModelSources.route}?family=LITERT&tab=download") },
                modifier = Modifier.walkthroughTarget("models.download")
            ) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.model_library_custom_download_heading))
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppContentColumn(
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 8.dp,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModelManagerShortcutRow(
                    navController = navController,
                    family = ModelFamily.LITERT
                )
                AppSectionCard {
                    AppScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 12.dp,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            title,
                                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (index == 1 && activeDownloads > 0) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                Text(activeDownloads.toString())
                                            }
                                        }
                                        if (index == 0 && (models.isNotEmpty() || stableAudioModels.isNotEmpty())) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ) {
                                                Text((models.size + stableAudioModels.size).toString())
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> LiteRtInstalledTab(
                        models = models,
                        stableAudioModels = stableAudioModels,
                        managedRoot = managedRoot,
                        doctorResults = doctorResults,
                        onRename = {
                            pendingRename = it
                            renameValue = it.displayName
                        },
                        onExport = {
                            pendingExport = it
                            exportLauncher.launch(defaultExportName(it))
                        },
                        onEditContext = {
                            pendingContextModel = it
                            contextTokenValue = it.maxContextTokens?.toString().orEmpty()
                        },
                        onEditModalities = {
                            pendingModalityModel = it
                            modalityVisionValue = it.supportsLiteRtVision()
                            modalityAudioValue = it.supportsLiteRtAudio()
                            modalityEmbeddingValue = it.supportsLiteRtEmbedding()
                        },
                        onSource = { sourceAsset = installedAssetForLiteRtModel(it) },
                        onRemove = { pendingDelete = it },
                        onRemoveAudio = { pendingAudioDelete = it },
                        onSourceAudio = { sourceAsset = installedAssetForModel(it) },
                        onOpenAudio = { kind ->
                            navController.navigate(Screen.AudioWorkspace.createRoute(kind))
                        },
                        onDoctorDetails = { doctorDetails = it },
                        stableDoctorResults = stableDoctorResults,
                        stableDoctorBusy = stableDoctorBusy,
                        onInspectAudio = { model ->
                            if (stableDoctorBusy[model.filename] != true) {
                                stableDoctorBusy[model.filename] = true
                                scope.launch {
                                    val report = runCatching {
                                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            StableAudio3ModelDoctor.inspectInstalled(context, model)
                                        }
                                    }
                                    report.getOrNull()?.let {
                                        stableDoctorResults[model.filename] = it
                                        stableDoctorDetails = it
                                    }
                                    report.exceptionOrNull()?.let { error ->
                                        toast(error.message ?: resources.getString(R.string.audio_music_error_model_stale))
                                    }
                                    stableDoctorBusy.remove(model.filename)
                                }
                            }
                        },
                        onRepairAudio = {
                            stableDoctorResults.remove(it.filename)
                            stableDoctorBusy.remove(it.filename)
                            pendingAudioRepair = it
                        }
                    )
                    1 -> LiteRtDownloadingTab(
                        progress = progress,
                        statuses = statuses,
                        downloadTasks = downloadTasks,
                        onCancel = { key ->
                            val filename = DownloadProgressHolder.getFilename(key) ?: return@LiteRtDownloadingTab
                            DownloadService.cancelDownload(context, filename, key)
                        },
                        onOpenAudio = { kind ->
                            navController.navigate(Screen.AudioWorkspace.createRoute(kind))
                        }
                    )
                    else -> LiteRtCatalogTab(
                        progress = progress,
                        huggingFaceToken = huggingFaceToken,
                        onHuggingFaceTokenChange = { token ->
                            huggingFaceToken = token
                            repository.saveHuggingFaceToken(token)
                        },
                        repository = repository,
                        onDownload = ::download,
                        onOpenAudio = { kind -> navController.navigate(Screen.AudioWorkspace.createRoute(kind)) }
                    )
                }

                FloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.litert_models_import))
                }
            }
        }
    }

    pendingRename?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.litert_models_rename)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.litert_models_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.renameModel(model, renameValue) }
                        pendingRename = null
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingContextModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingContextModel = null },
            title = { Text(stringResource(R.string.litert_models_context_edit)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.litert_models_context_desc,
                            model.defaultLiteRtEngineMaxTokens()
                                ?.toString()
                                ?: stringResource(R.string.litert_models_context_unknown_short)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = contextTokenValue,
                        onValueChange = { value -> contextTokenValue = value.filter { it.isDigit() } },
                        label = { Text(stringResource(R.string.litert_models_context_label)) },
                        placeholder = { Text(stringResource(R.string.litert_models_context_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = contextTokenValue.trim()
                        val parsed = trimmed.takeIf { it.isNotBlank() }?.toIntOrNull()
                        if (trimmed.isNotBlank() && (parsed == null || parsed !in LITERT_CONTEXT_USER_MIN..LITERT_CONTEXT_USER_MAX)) {
                            toast(resources.getString(R.string.litert_models_context_invalid))
                            return@TextButton
                        }
                        scope.launch {
                            repository.updateMaxContextTokens(model, parsed)
                            toast(resources.getString(R.string.litert_models_context_saved))
                        }
                        pendingContextModel = null
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingContextModel = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingModalityModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingModalityModel = null },
            title = { Text(stringResource(R.string.litert_models_modalities_edit)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LiteRtModalitySwitch(
                        title = stringResource(R.string.litert_models_modality_vision),
                        description = stringResource(R.string.litert_models_supports_vision_desc),
                        checked = modalityVisionValue,
                        onCheckedChange = { modalityVisionValue = it }
                    )
                    LiteRtModalitySwitch(
                        title = stringResource(R.string.litert_models_modality_audio),
                        description = stringResource(R.string.litert_models_supports_audio_desc),
                        checked = modalityAudioValue,
                        onCheckedChange = { modalityAudioValue = it }
                    )
                    LiteRtModalitySwitch(
                        title = stringResource(R.string.litert_models_modality_embedding),
                        description = stringResource(R.string.litert_models_supports_embedding_desc),
                        checked = modalityEmbeddingValue,
                        onCheckedChange = { modalityEmbeddingValue = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.updateCapabilitySupport(
                                model = model,
                                supportsVision = modalityVisionValue,
                                supportsAudio = modalityAudioValue,
                                supportsEmbedding = modalityEmbeddingValue
                            )
                            toast(resources.getString(R.string.litert_models_modalities_saved))
                        }
                        pendingModalityModel = null
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingModalityModel = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingImportUri?.let { uri ->
        val invalidSourceText = stringResource(R.string.model_source_invalid_link)
        AlertDialog(
            onDismissRequest = {
                pendingImportUri = null
                importSourceUrl = ""
                importSourceLabel = ""
                importSourceError = null
            },
            title = { Text(stringResource(R.string.litert_models_import_options_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.litert_models_import_options_desc, pendingImportName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LiteRtModalitySwitch(
                        title = stringResource(R.string.litert_models_modality_vision),
                        description = stringResource(R.string.litert_models_supports_vision_desc),
                        checked = importSupportsVision,
                        onCheckedChange = { importSupportsVision = it }
                    )
                    LiteRtModalitySwitch(
                        title = stringResource(R.string.litert_models_modality_audio),
                        description = stringResource(R.string.litert_models_supports_audio_desc),
                        checked = importSupportsAudio,
                        onCheckedChange = { importSupportsAudio = it }
                    )
                    LiteRtModalitySwitch(
                        title = stringResource(R.string.litert_models_modality_embedding),
                        description = stringResource(R.string.litert_models_supports_embedding_desc),
                        checked = importSupportsEmbedding,
                        onCheckedChange = { importSupportsEmbedding = it }
                    )
                    OptionalModelSourceFields(
                        family = ModelFamily.LITERT,
                        url = importSourceUrl,
                        onUrlChange = {
                            importSourceUrl = it
                            importSourceError = null
                        },
                        label = importSourceLabel,
                        onLabelChange = { importSourceLabel = it },
                        error = importSourceError
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sourceDraft = optionalModelSourceDraft(
                            family = ModelFamily.LITERT,
                            url = importSourceUrl,
                            label = importSourceLabel
                        )
                        if (sourceDraft.isFailure) {
                            importSourceError = invalidSourceText
                            return@TextButton
                        }
                        val selectedUri = uri
                        pendingImportUri = null
                        importSourceUrl = ""
                        importSourceLabel = ""
                        importSourceError = null
                        scope.launch {
                            val result = repository.importFromUri(
                                selectedUri,
                                supportsVisionOverride = importSupportsVision,
                                supportsAudioOverride = importSupportsAudio,
                                supportsEmbeddingOverride = importSupportsEmbedding
                            )
                            if (result.isSuccess) {
                                val imported = result.getOrThrow()
                                sourceDraft.getOrNull()?.let { draft ->
                                    attachModelSource(
                                        sourceRepository,
                                        ModelSourceAttachmentRequest(
                                            asset = installedAssetForLiteRtModel(imported),
                                            newSource = draft,
                                            role = "litert"
                                        )
                                    ).onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            resources.getString(
                                                R.string.model_source_save_failed,
                                                error.message ?: resources.getString(R.string.error_generic)
                                            ),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                            toast(
                                result.fold(
                                    onSuccess = { resources.getString(R.string.litert_models_imported) },
                                    onFailure = { it.message ?: resources.getString(R.string.error_generic) }
                                )
                            )
                        }
                    }
                ) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    importSourceUrl = ""
                    importSourceLabel = ""
                    importSourceError = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    sourceAsset?.let { asset ->
        ModelSourceAttachmentDialog(
            asset = asset,
            sources = savedSources,
            provenance = sourceProvenance,
            onDismiss = { sourceAsset = null },
            onSave = { request ->
                sourceAsset = null
                scope.launch {
                    attachModelSource(sourceRepository, request)
                        .onSuccess { toast(resources.getString(R.string.model_source_saved)) }
                        .onFailure { error ->
                            toast(
                                resources.getString(
                                    R.string.model_source_save_failed,
                                    error.message ?: resources.getString(R.string.error_generic)
                                )
                            )
                        }
                }
            }
        )
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.litert_models_remove)) },
            text = { Text(stringResource(R.string.litert_models_remove_confirm, model.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = repository.removeModel(model)
                            toast(
                                result.fold(
                                    onSuccess = { resources.getString(R.string.litert_models_removed) },
                                    onFailure = { it.message ?: resources.getString(R.string.error_generic) }
                                )
                            )
                        }
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingAudioDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingAudioDelete = null },
            title = { Text(stringResource(R.string.litert_models_remove)) },
            text = { Text(stringResource(R.string.litert_models_remove_confirm, model.filename)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = model
                        pendingAudioDelete = null
                        scope.launch {
                            try {
                                val result = modelRepository.deleteModelWithResult(target)
                                val message = if (
                                    result.status == com.example.llamadroid.data.model.library.ModelDeletionStatus.COMPLETED
                                ) {
                                    resources.getString(
                                        R.string.models_delete_result_completed,
                                        FormatUtils.formatFileSize(result.reclaimedBytes)
                                    )
                                } else if (result.errorCode != null) {
                                    resources.getString(modelLibraryErrorResource(result.errorCode))
                                } else {
                                    resources.getString(R.string.models_delete_result_retry)
                                }
                                toast(message)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                toast(resources.getString(R.string.models_delete_result_retry))
                            }
                        }
                    }
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAudioDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingAudioRepair?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingAudioRepair = null },
            title = { Text(stringResource(R.string.audio_stable_doctor_redownload)) },
            text = { Text(stringResource(R.string.audio_stable_doctor_redownload_confirm, model.filename)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = model
                        pendingAudioRepair = null
                        scope.launch {
                            val result = runCatching {
                                val kind = if (target.audioFamily == StableAudioModelSupport.FAMILY_SFX) {
                                    StableAudio3Kind.SFX
                                } else {
                                    StableAudio3Kind.MUSIC
                                }
                                val role = StableAudioModelSupport.canonicalRole(target.audioComponentRole)
                                    ?: error(resources.getString(R.string.audio_music_error_model_stale))
                                val bundle = StableAudioCuratedBundleCatalog.bundles(context)
                                    .firstOrNull { candidate ->
                                        candidate.files.any { file ->
                                            file.componentRole == role &&
                                                (role != StableAudioModelSupport.ROLE_DIT ||
                                                    file.audioFamily == kind.family)
                                        }
                                    } ?: error(resources.getString(R.string.audio_music_error_model_stale))
                                val file = bundle.files.first { it.componentRole == role }
                                val deleted = modelRepository.deleteModelWithResult(target)
                                check(deleted.status == com.example.llamadroid.data.model.library.ModelDeletionStatus.COMPLETED) {
                                    deleted.errorMessage ?: resources.getString(R.string.models_delete_result_retry)
                                }
                                modelRepository.startDownloadAsync(
                                    repoId = file.repoId,
                                    filename = file.remotePath,
                                    type = file.type,
                                    downloadUrlOverride = file.downloadUrl,
                                    localFilenameOverride = file.installedFilename(bundle.defaultPrefix),
                                    artifactFamily = file.audioFamily,
                                    artifactRole = file.componentRole
                                )
                            }
                            toast(
                                result.fold(
                                    onSuccess = { resources.getString(R.string.audio_stable_doctor_redownload_started) },
                                    onFailure = { it.message ?: resources.getString(R.string.audio_music_error_model_stale) }
                                )
                            )
                        }
                    }
                ) { Text(stringResource(R.string.audio_stable_doctor_redownload)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAudioRepair = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    doctorDetails?.let { result ->
        AlertDialog(
            onDismissRequest = { doctorDetails = null },
            title = { Text(stringResource(R.string.litert_doctor_details_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.litert_doctor_backend_result, result.backend.uppercase(), result.statusLabel()))
                    Text(result.detail)
                    Text(stringResource(R.string.litert_doctor_phase, result.phase))
                    Text(stringResource(R.string.litert_doctor_device, result.deviceInfo))
                    Text(
                        stringResource(
                            R.string.litert_doctor_targets,
                            result.deviceTargets.joinToString().ifBlank { "-" }
                        )
                    )
                    result.processExit?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.litert_doctor_process_exit, it.take(500)))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doctorDetails = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    stableDoctorDetails?.let { report ->
        val check = report.check
        AlertDialog(
            onDismissRequest = { stableDoctorDetails = null },
            title = { Text(stringResource(R.string.audio_stable_doctor_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.audio_stable_doctor_status, stableDoctorHealthLabel(check.health)))
                    Text(stringResource(R.string.audio_stable_doctor_role, stableDoctorRoleName(report.role)))
                    Text(stringResource(R.string.audio_stable_doctor_readable, if (check.readable) {
                        stringResource(R.string.audio_stable_doctor_yes)
                    } else {
                        stringResource(R.string.audio_stable_doctor_no)
                    }))
                    Text(stringResource(R.string.audio_stable_doctor_parent, if (check.parentDirectoryAvailable) {
                        stringResource(R.string.audio_stable_doctor_yes)
                    } else {
                        stringResource(R.string.audio_stable_doctor_no)
                    }))
                    Text(
                        stringResource(
                            R.string.audio_stable_doctor_size,
                            check.actualSizeBytes?.let(FormatUtils::formatFileSize)
                                ?: stringResource(R.string.audio_stable_doctor_unknown),
                            FormatUtils.formatFileSize(check.expectedSizeBytes)
                        )
                    )
                    Text(
                        stringResource(
                            R.string.audio_stable_doctor_digest,
                            check.actualSha256?.take(12)
                                ?: stringResource(R.string.audio_stable_doctor_unknown),
                            check.expectedSha256.take(12)
                        )
                    )
                    Text(stringResource(R.string.audio_stable_doctor_expected_file, check.expectedFilename))
                }
            },
            confirmButton = {
                TextButton(onClick = { stableDoctorDetails = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}

@Composable
private fun LiteRtInstalledTab(
    models: List<LiteRtModelEntity>,
    stableAudioModels: List<ModelEntity>,
    managedRoot: File,
    doctorResults: Map<Long, List<LiteRtBackendDoctorResult>>,
    onRename: (LiteRtModelEntity) -> Unit,
    onExport: (LiteRtModelEntity) -> Unit,
    onEditContext: (LiteRtModelEntity) -> Unit,
    onEditModalities: (LiteRtModelEntity) -> Unit,
    onSource: (LiteRtModelEntity) -> Unit,
    onRemove: (LiteRtModelEntity) -> Unit,
    onRemoveAudio: (ModelEntity) -> Unit,
    onSourceAudio: (ModelEntity) -> Unit,
    onOpenAudio: (String) -> Unit,
    onDoctorDetails: (LiteRtBackendDoctorResult) -> Unit,
    stableDoctorResults: Map<String, StableAudio3ModelDoctorReport>,
    stableDoctorBusy: Map<String, Boolean>,
    onInspectAudio: (ModelEntity) -> Unit,
    onRepairAudio: (ModelEntity) -> Unit
) {
    val storageSnapshot = com.example.llamadroid.ui.components.rememberModelStorageInventory()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { com.example.llamadroid.ui.components.ModelStorageOverviewCard(storageSnapshot, "litert") }
        item {
            Text(
                stringResource(
                    R.string.litert_models_installed_title,
                    models.size + stableAudioModels.size
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (models.isEmpty() && stableAudioModels.isEmpty()) {
            item {
                EmptyModelState(
                    title = stringResource(R.string.litert_models_empty),
                    subtitle = stringResource(R.string.litert_models_empty_desc)
                )
            }
        } else {
            items(models, key = { it.id }) { model ->
                val contextText = model.defaultLiteRtEngineMaxTokens()?.let {
                    stringResource(R.string.litert_models_context_value, it)
                } ?: stringResource(R.string.litert_models_context_user_selected)
                val modalityText = listOfNotNull(
                    if (model.supportsLiteRtVision()) stringResource(R.string.litert_models_modality_vision) else null,
                    if (model.supportsLiteRtAudio()) stringResource(R.string.litert_models_modality_audio) else null,
                    if (model.supportsLiteRtEmbedding()) {
                        if (model.kbEmbeddingRunnable) {
                            stringResource(R.string.litert_models_modality_embedding_runnable)
                        } else {
                            stringResource(R.string.litert_models_modality_embedding_not_runnable)
                        }
                    } else {
                        null
                    }
                ).ifEmpty {
                    listOf(stringResource(R.string.litert_models_modality_text_only))
                }.joinToString(" / ")
                LiteRtCompactModelCard(
                    model = model,
                    contextText = "$contextText • $modalityText",
                    doctorResults = doctorResults[model.id].orEmpty(),
                    onRename = { onRename(model) },
                    onExport = { onExport(model) },
                    onEditContext = { onEditContext(model) },
                    onEditModalities = { onEditModalities(model) },
                    onSource = { onSource(model) },
                    onRemove = { onRemove(model) },
                    onDoctorDetails = onDoctorDetails
                )
            }

            if (stableAudioModels.isNotEmpty()) {
                item(key = "stable-audio-installed-header") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.audio_music_bundle_section),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.audio_music_bundle_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                stableAudioModels
                    .groupBy { model -> model.audioFamily ?: StableAudioModelSupport.FAMILY_SHARED }
                    .toSortedMap()
                    .forEach { (family, familyModels) ->
                        item(key = "stable-audio-group-$family") {
                            Text(
                                stableAudioFamilyTitle(family),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(
                            familyModels,
                            key = { model -> "stable-audio-${model.filename}" }
                        ) { model ->
                            StableAudioInstalledModelCard(
                                model = model,
                                onRemove = { onRemoveAudio(model) },
                                onOpenAudio = { onOpenAudio(stableAudioKind(model)) },
                                onSource = { onSourceAudio(model) },
                                report = stableDoctorResults[model.filename],
                                checking = stableDoctorBusy[model.filename] == true,
                                onInspect = { onInspectAudio(model) },
                                onRepair = { onRepairAudio(model) }
                            )
                        }
                    }
            }
        }

        item { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

@Composable
private fun stableAudioFamilyTitle(family: String): String = when (family) {
    StableAudioModelSupport.FAMILY_MUSIC -> stringResource(R.string.audio_music_bundle_music)
    StableAudioModelSupport.FAMILY_SFX -> stringResource(R.string.audio_music_bundle_sfx)
    else -> stringResource(R.string.audio_music_components)
}

@Composable
private fun stableAudioRoleLabel(model: ModelEntity): String = when (
    StableAudioModelSupport.canonicalRole(model.audioComponentRole)
) {
    StableAudioModelSupport.ROLE_DIT -> stringResource(R.string.model_library_role_stable_audio_dit)
    StableAudioModelSupport.ROLE_TEXT_ENCODER -> stringResource(R.string.model_library_role_stable_audio_text_encoder)
    StableAudioModelSupport.ROLE_TOKENIZER -> stringResource(R.string.model_library_role_stable_audio_tokenizer)
    StableAudioModelSupport.ROLE_CODEC_ENCODER -> stringResource(R.string.model_library_role_stable_audio_codec_encoder)
    StableAudioModelSupport.ROLE_CODEC_DECODER -> stringResource(R.string.model_library_role_stable_audio_codec_decoder)
    StableAudioModelSupport.ROLE_LORA -> stringResource(R.string.model_library_role_stable_audio_lora)
    else -> stringResource(R.string.model_library_role_stable_audio_component)
}

@Composable
private fun StableAudioInstalledModelCard(
    model: ModelEntity,
    onRemove: () -> Unit,
    onSource: () -> Unit,
    onOpenAudio: () -> Unit,
    report: StableAudio3ModelDoctorReport?,
    checking: Boolean,
    onInspect: () -> Unit,
    onRepair: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelCard(
            title = model.filename,
            subtitle = model.repoId,
            sizeText = FormatUtils.formatFileSize(model.sizeBytes),
            details = listOf(stableAudioRoleLabel(model)),
            actionIcon = Icons.Default.Delete,
            actionColor = MaterialTheme.colorScheme.error,
            onAction = onRemove,
            onSource = onSource
        )
        report?.let { result ->
            val statusColor = if (result.ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Text(
                stringResource(
                    R.string.audio_stable_doctor_status,
                    stableDoctorHealthLabel(result.check.health)
                ),
                color = statusColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onInspect, enabled = !checking, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (checking) R.string.audio_stable_doctor_checking
                        else R.string.audio_stable_doctor_check
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onRepair, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.audio_stable_doctor_redownload),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        OutlinedButton(
            onClick = onOpenAudio,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AudioFile, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.audio_music_open_workspace))
        }
    }
}

@Composable
private fun stableDoctorHealthLabel(health: StableAudio3ComponentHealth): String = when (health) {
    StableAudio3ComponentHealth.READY -> stringResource(R.string.audio_stable_doctor_status_ready)
    StableAudio3ComponentHealth.ROLE_MISMATCH -> stringResource(R.string.audio_stable_doctor_role_bad)
    StableAudio3ComponentHealth.NON_CANONICAL -> stringResource(R.string.audio_stable_doctor_status_path)
    StableAudio3ComponentHealth.MISSING -> stringResource(R.string.audio_stable_doctor_status_missing)
    StableAudio3ComponentHealth.PARENT_UNAVAILABLE -> stringResource(R.string.audio_stable_doctor_status_parent)
    StableAudio3ComponentHealth.UNREADABLE -> stringResource(R.string.audio_stable_doctor_status_unreadable)
    StableAudio3ComponentHealth.SIZE_MISMATCH -> stringResource(R.string.audio_stable_doctor_status_size)
    StableAudio3ComponentHealth.DIGEST_MISMATCH -> stringResource(R.string.audio_stable_doctor_status_digest)
}

@Composable
private fun stableDoctorRoleName(role: String?): String = when (role) {
    StableAudioModelSupport.ROLE_DIT -> stringResource(R.string.model_library_role_stable_audio_dit)
    StableAudioModelSupport.ROLE_TEXT_ENCODER -> stringResource(R.string.model_library_role_stable_audio_text_encoder)
    StableAudioModelSupport.ROLE_TOKENIZER -> stringResource(R.string.model_library_role_stable_audio_tokenizer)
    StableAudioModelSupport.ROLE_CODEC_ENCODER -> stringResource(R.string.model_library_role_stable_audio_codec_encoder)
    StableAudioModelSupport.ROLE_CODEC_DECODER -> stringResource(R.string.model_library_role_stable_audio_codec_decoder)
    else -> stringResource(R.string.audio_stable_doctor_role_bad)
}

@Composable
private fun LiteRtDownloadingTab(
    progress: Map<String, Float>,
    statuses: Map<String, String>,
    downloadTasks: List<com.example.llamadroid.data.db.DownloadTaskEntity>,
    onCancel: (String) -> Unit,
    onOpenAudio: (String) -> Unit
) {
    val context = LocalContext.current
    val active = progress
        .filter { (key, value) ->
            isLiteRtProgressKey(key) &&
                (value == DownloadProgressHolder.INDETERMINATE || value in 0f..0.999f)
        }
        .toSortedMap()
    val taskByProgressKey = remember(downloadTasks) {
        downloadTasks
            .asSequence()
            .flatMap { task -> sequenceOf(task.progressKey to task, task.id to task) }
            .toMap()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                stringResource(R.string.models_tab_downloading),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            DownloadTaskSection(
                modelTypes = listOf(ModelType.LLM) + STABLE_AUDIO_MODEL_TYPES,
                includeTask = ::isLiteRtDownloadTask,
                staleRoots = listOf(
                    File(context.noBackupFilesDir, "litert_models"),
                    File(context.filesDir, "models/audio/stable")
                ),
                artifactFamily = null
            )
        }

        if (active.isEmpty()) {
            item {
                EmptyModelState(
                    title = stringResource(R.string.litert_models_downloading_empty),
                    subtitle = stringResource(R.string.litert_models_downloading_empty_desc)
                )
            }
        } else {
            items(active.entries.toList(), key = { it.key }) { entry ->
                val task = taskByProgressKey[entry.key]
                val pending = PendingDownloadHolder.getPending(entry.key)
                val kind = stableAudioKind(task, pending)
                LiteRtDownloadProgressCard(
                    repoId = task?.repoId
                        ?: pending?.repoId
                        ?: entry.key.removePrefix(LITERT_PROGRESS_PREFIX),
                    filename = task?.filename
                        ?: pending?.filename
                        ?: DownloadProgressHolder.getFilename(entry.key),
                    progress = entry.value,
                    status = statuses[entry.key] ?: task?.liteRtDisplayName ?: pending?.liteRtDisplayName,
                    onCancel = { onCancel(entry.key) },
                    onOpenAudio = kind?.let { { onOpenAudio(it) } }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

@Composable
private fun LiteRtCatalogTab(
    progress: Map<String, Float>,
    huggingFaceToken: String,
    onHuggingFaceTokenChange: (String) -> Unit,
    repository: LiteRtModelRepository,
    onDownload: (LiteRtCatalogEntry) -> Unit,
    onOpenAudio: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var liveResults by remember { mutableStateOf<List<LiteRtCatalogEntry>>(emptyList()) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var isLiveSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalizedQuery = query.trim()
    val deviceInfo = remember { currentLiteRtDeviceTargetInfo() }
    val context = LocalContext.current
    val audioBundles = remember(context) {
        com.example.llamadroid.data.model.StableAudioCuratedBundleCatalog.bundles(context)
    }
    fun searchLiveCatalog() {
        if (normalizedQuery.length < 2) return
        isLiveSearching = true
        liveError = null
        scope.launch {
            val result = repository.searchLiveCatalog(normalizedQuery)
            liveResults = result.getOrDefault(emptyList())
            liveError = result.exceptionOrNull()?.message
            isLiveSearching = false
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.litert_models_gpu_note_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        stringResource(R.string.litert_models_gpu_note_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(
                            R.string.litert_catalog_device_target,
                            deviceInfo.normalizedTargets.joinToString().ifBlank {
                                stringResource(R.string.litert_device_unknown)
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = huggingFaceToken,
                        onValueChange = onHuggingFaceTokenChange,
                        label = { Text(stringResource(R.string.litert_hf_token_label)) },
                        placeholder = { Text(stringResource(R.string.litert_hf_token_placeholder)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.litert_hf_token_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            AppSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.litert_catalog_search_label)) },
                        placeholder = { Text(stringResource(R.string.litert_catalog_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.litert_catalog_live_search_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { searchLiveCatalog() },
                            enabled = normalizedQuery.length >= 2 && !isLiveSearching
                        ) {
                            Text(
                                if (isLiveSearching) {
                                    stringResource(R.string.litert_catalog_live_searching)
                                } else {
                                    stringResource(R.string.litert_catalog_live_search)
                                }
                            )
                        }
                    }
                    liveError?.let { error ->
                        Text(
                            stringResource(R.string.litert_catalog_live_error, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        val matchingAudioBundles = audioBundles.filter { bundle ->
            normalizedQuery.isBlank() || context.getString(bundle.titleRes).contains(normalizedQuery, ignoreCase = true)
        }
        if (matchingAudioBundles.isNotEmpty()) {
            item(key = "stable_audio_bundles") {
                com.example.llamadroid.ui.components.CuratedModelBundleSection(
                    title = stringResource(R.string.audio_music_bundle_section),
                    description = stringResource(R.string.audio_music_bundle_description),
                    bundles = matchingAudioBundles,
                    onUseBundle = { bundle, installed, _ ->
                        scope.launch {
                            try {
                                val kind = if (bundle.id.contains("sfx")) "sfx" else "music"
                                val store = com.example.llamadroid.ui.audio.music.MusicWorkspaceDraftStore.get(context, kind)
                                val draft = store.load()
                                val components = bundle.files.mapNotNull { component ->
                                    installed.firstOrNull { model -> model.audioArtifactIdentity == component.artifactIdentity }
                                        ?.let { model -> requireNotNull(component.componentRole) to model.path }
                                }.toMap()
                                require(components.size == bundle.files.size)
                                store.save(draft.copy(components = components)
                                    .withValue("ditPrecision", "fp32")
                                    .withValue("decoderPrecision", "w8a8")
                                    .withValue("encoderPrecision", "w8a8"))
                                onOpenAudio(kind)
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

        val curatedEntries = LiteRtModelCatalog.defaultEntries
            .filter { it.matchesCatalogQuery(normalizedQuery) }
            .sortedWith(liteRtCatalogComparator())
        val embeddingEntries = LiteRtModelCatalog.embeddingEntries
            .filter { it.matchesCatalogQuery(normalizedQuery) }
            .sortedWith(liteRtCatalogComparator())

        item {
            Text(
                stringResource(R.string.litert_models_catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(curatedEntries, key = { it.catalogId }) { entry ->
            val progressKey = "$LITERT_PROGRESS_PREFIX${entry.catalogId}"
            LiteRtCatalogCard(
                entry = entry,
                description = localizedCatalogDescription(entry),
                compatibility = entry.catalogCompatibility(huggingFaceToken),
                progress = progress[progressKey],
                onDownload = { onDownload(entry) }
            )
        }

        if (embeddingEntries.isNotEmpty()) {
            item {
                LiteRtCatalogGroupHeader(
                    title = stringResource(R.string.litert_models_embedding_catalog_title),
                    description = stringResource(R.string.litert_models_embedding_catalog_desc),
                    accentColor = LiteRtEmbeddingBlue
                )
            }
            items(embeddingEntries, key = { it.catalogId }) { entry ->
                val progressKey = "$LITERT_PROGRESS_PREFIX${entry.catalogId}"
                LiteRtCatalogCard(
                    entry = entry,
                    description = localizedCatalogDescription(entry),
                    compatibility = entry.catalogCompatibility(huggingFaceToken),
                    progress = progress[progressKey],
                    accentColor = LiteRtEmbeddingBlue,
                    onDownload = { onDownload(entry) }
                )
            }
        }

        if (liveResults.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.litert_catalog_live_results_title, liveResults.size),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(liveResults, key = { it.catalogId }) { entry ->
                val progressKey = "$LITERT_PROGRESS_PREFIX${entry.catalogId}"
                LiteRtCatalogCard(
                    entry = entry,
                    description = localizedCatalogDescription(entry),
                    compatibility = entry.catalogCompatibility(huggingFaceToken),
                    progress = progress[progressKey],
                    onDownload = { onDownload(entry) }
                )
            }
        }

        if (normalizedQuery.isNotBlank() && curatedEntries.isEmpty() && embeddingEntries.isEmpty() && liveResults.isEmpty() && !isLiveSearching) {
            item {
                EmptyModelState(
                    title = stringResource(R.string.litert_catalog_search_empty),
                    subtitle = stringResource(R.string.litert_catalog_search_empty_desc, normalizedQuery)
                )
            }
        }

        item { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

@Composable
private fun LiteRtCatalogGroupHeader(
    title: String,
    description: String,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LiteRtCompactModelCard(
    model: LiteRtModelEntity,
    contextText: String,
    doctorResults: List<LiteRtBackendDoctorResult>,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onEditContext: () -> Unit,
    onEditModalities: () -> Unit,
    onSource: () -> Unit,
    onRemove: () -> Unit,
    onDoctorDetails: (LiteRtBackendDoctorResult) -> Unit
) {
    ModelStyleCard(
        title = model.displayName,
        subtitle = model.repoId ?: stringResource(R.string.litert_models_local_import),
        sizeText = FormatUtils.formatFileSize(model.sizeBytes),
        contextText = contextText,
        actionIcon = Icons.Default.Delete,
        actionColor = MaterialTheme.colorScheme.error,
        onAction = onRemove,
        onExport = onExport,
        onRename = onRename,
        onEditContext = onEditContext,
        onEditModalities = onEditModalities,
        onSource = onSource,
        doctorResults = doctorResults,
        onDoctorDetails = onDoctorDetails
    )
}

@Composable
private fun LiteRtCatalogCard(
    entry: LiteRtCatalogEntry,
    description: String,
    compatibility: LiteRtCatalogCompatibility,
    progress: Float?,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onDownload: () -> Unit
) {
    val isIndeterminate = progress == DownloadProgressHolder.INDETERMINATE
    val isDownloading = progress != null && (isIndeterminate || progress in 0f..0.999f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.supportsEmbedding) {
                accentColor.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(40.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        entry.repoId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    entry.preferredFileName?.let { fileName ->
                        Text(
                            fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    entry.sizeBytes?.takeIf { it > 0L }?.let { sizeBytes ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(
                                        R.string.litert_catalog_size_label,
                                        FormatUtils.formatFileSize(sizeBytes)
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            enabled = false
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                compatibility.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        enabled = false
                    )
                    if (entry.gated) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.litert_catalog_gated_package),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            enabled = false
                        )
                    }
                    if (!entry.supportsEmbedding) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    entry.maxContextTokens?.let {
                                        stringResource(R.string.litert_models_context_value, it)
                                    } ?: stringResource(R.string.litert_models_context_user_selected),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            enabled = false
                        )
                    }
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    progress?.takeIf { isDownloading }?.let { value ->
                        if (isIndeterminate) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { value.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                        Text(
                            if (isIndeterminate) {
                                stringResource(R.string.models_downloading)
                            } else {
                                stringResource(R.string.whisper_downloading_progress, (value * 100).toInt())
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDownload, enabled = !isDownloading && compatibility.canDownload) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.action_download),
                        tint = accentColor
                    )
                }
            }
        }
    }
}

private fun LiteRtCatalogEntry.matchesCatalogQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val lowerQuery = query.lowercase()
    return listOf(
        title,
        description,
        repoId,
        preferredFileName.orEmpty(),
        category.name
    ).any { it.lowercase().contains(lowerQuery) }
}

private data class LiteRtCatalogCompatibility(
    val label: String,
    val recommended: Boolean,
    val canDownload: Boolean
)

@Composable
private fun LiteRtCatalogEntry.catalogCompatibility(huggingFaceToken: String): LiteRtCatalogCompatibility {
    val hasToken = huggingFaceToken.isNotBlank()
    return LiteRtCatalogCompatibility(
        label = if (gated && !hasToken) {
            stringResource(R.string.litert_catalog_gated_package)
        } else if (gated) {
            stringResource(R.string.litert_catalog_license_protected_package)
        } else if (supportsEmbedding) {
            stringResource(R.string.litert_catalog_embedding_package)
        } else {
            stringResource(R.string.litert_catalog_generic_package)
        },
        recommended = false,
        canDownload = !gated || hasToken
    )
}

private fun liteRtCatalogComparator(): Comparator<LiteRtCatalogEntry> = compareBy<LiteRtCatalogEntry> { entry ->
    entry.title.lowercase()
}

@Composable
private fun LiteRtBackendDoctorResult.statusLabel(): String =
    if (success) {
        stringResource(R.string.litert_doctor_status_ok)
    } else {
        stringResource(R.string.litert_doctor_status_failed)
    }

@Composable
private fun LiteRtDownloadProgressCard(
    repoId: String,
    filename: String?,
    progress: Float,
    status: String?,
    onCancel: () -> Unit,
    onOpenAudio: (() -> Unit)? = null
) {
    val isIndeterminate = progress == DownloadProgressHolder.INDETERMINATE
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    filename ?: repoId,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                onOpenAudio?.let { openAudio ->
                    OutlinedButton(onClick = openAudio) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.audio_music_open_workspace))
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.models_cancel_download),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                status ?: repoId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
            Text(
                if (isIndeterminate) {
                    stringResource(R.string.models_downloading)
                } else {
                    stringResource(R.string.whisper_downloading_progress, (progress * 100).toInt())
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiteRtModalitySwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ModelStyleCard(
    title: String,
    subtitle: String,
    sizeText: String,
    contextText: String? = null,
    actionIcon: ImageVector,
    actionColor: Color,
    onAction: () -> Unit,
    onExport: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onEditContext: (() -> Unit)? = null,
    onEditModalities: (() -> Unit)? = null,
    onSource: (() -> Unit)? = null,
    doctorResults: List<LiteRtBackendDoctorResult> = emptyList(),
    onDoctorDetails: (LiteRtBackendDoctorResult) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(sizeText, contextText).joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    doctorResults.firstOrNull()?.let { result ->
                        Text(
                            stringResource(
                                R.string.litert_doctor_summary,
                                result.backend.uppercase(),
                                result.statusLabel()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result.success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                onSource?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = stringResource(R.string.model_source_attach_title),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                doctorResults.firstOrNull()?.let { result ->
                    IconButton(onClick = { onDoctorDetails(result) }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.litert_doctor_details_title),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                onRename?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.models_rename_title),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                onEditContext?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.litert_models_context_edit),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                onEditModalities?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.litert_models_modalities_edit),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                onExport?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.action_share),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onAction) {
                    Icon(actionIcon, contentDescription = null, tint = actionColor)
                }
            }
        }
    }
}

@Composable
private fun EmptyModelState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun localizedCatalogDescription(entry: LiteRtCatalogEntry): String =
    if (entry.supportsEmbedding) {
        stringResource(R.string.litert_models_embedding_catalog_card_desc)
    } else {
        when (entry.repoId) {
            "litert-community/Qwen3-0.6B" -> stringResource(R.string.litert_catalog_qwen3_06_desc)
            "litert-community/Qwen3-4B" -> stringResource(R.string.litert_catalog_qwen3_4b_desc)
            "litert-community/Qwen3-8B" -> stringResource(R.string.litert_catalog_qwen3_8b_desc)
            "litert-community/Qwen3-14B" -> stringResource(R.string.litert_catalog_qwen3_14b_desc)
            "litert-community/Gemma3-1B-IT" -> stringResource(R.string.litert_catalog_gemma3_1b_desc)
            "litert-community/gemma-4-E2B-it-litert-lm" -> stringResource(R.string.litert_catalog_gemma4_e2b_desc)
            "litert-community/gemma-4-E4B-it-litert-lm" -> stringResource(R.string.litert_catalog_gemma4_e4b_desc)
            "google/gemma-3n-E2B-it-litert-lm" -> stringResource(R.string.litert_catalog_gemma3n_e2b_desc)
            "google/gemma-3n-E4B-it-litert-lm" -> stringResource(R.string.litert_catalog_gemma3n_e4b_desc)
            "litert-community/Qwen2.5-1.5B-Instruct" -> stringResource(R.string.litert_catalog_qwen25_15b_desc)
            "litert-community/DeepSeek-R1-Distill-Qwen-1.5B" -> stringResource(R.string.litert_catalog_deepseek_qwen15_desc)
            "litert-community/Phi-4-mini-instruct" -> stringResource(R.string.litert_catalog_phi4_mini_desc)
            "litert-community/SmolLM2-360M-Instruct" -> stringResource(R.string.litert_catalog_smollm2_360_desc)
            "litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm" -> stringResource(R.string.litert_catalog_functiongemma_desc)
            else -> entry.description
        }
    }

private fun defaultExportName(model: LiteRtModelEntity): String {
    val source = File(model.path)
    return if (source.isDirectory) {
        model.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "litert_model" } + ".zip"
    } else {
        source.name
    }
}
