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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.LiteRtModelEntity
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
import com.example.llamadroid.service.LiteRtBackendDoctorResult
import com.example.llamadroid.service.LiteRtBackendDoctorStore
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.launch
import java.io.File

private const val LITERT_PROGRESS_PREFIX = "litert:"
private const val LITERT_CONTEXT_USER_MIN = 512
private const val LITERT_CONTEXT_USER_MAX = 131_072
private val LiteRtEmbeddingBlue = Color(0xFF2F80ED)

@Composable
@Suppress("UNUSED_PARAMETER")
fun LiteRtModelsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember {
        LiteRtModelRepository(
            context = context,
            modelDao = db.liteRtModelDao()
        )
    }
    val models by repository.observeModels().collectAsState(initial = emptyList())
    val progress by DownloadProgressHolder.progress.collectAsState()
    val statuses by DownloadProgressHolder.status.collectAsState()
    val managedRoot = remember(repository) { repository.managedRoot() }
    var selectedTab by remember { mutableIntStateOf(0) }
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
    var pendingDelete by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var pendingExport by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var doctorDetails by remember { mutableStateOf<LiteRtBackendDoctorResult?>(null) }
    var huggingFaceToken by remember { mutableStateOf(repository.huggingFaceToken()) }
    val doctorResults = remember { mutableStateMapOf<Long, List<LiteRtBackendDoctorResult>>() }

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
        pendingImportName = fileName.ifBlank { context.getString(R.string.litert_models_import) }
        importSupportsVision = liteRtVisionSupportFromText(inferenceText)
        importSupportsAudio = liteRtAudioSupportFromText(inferenceText)
        importSupportsEmbedding = liteRtEmbeddingSupportFromText(inferenceText)
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
                    onSuccess = { context.getString(R.string.litert_models_exported) },
                    onFailure = { it.message ?: context.getString(R.string.error_generic) }
                )
            )
        }
    }

    fun download(entry: LiteRtCatalogEntry) {
        scope.launch {
            val result = repository.startCatalogDownload(entry)
            toast(
                result.fold(
                    onSuccess = { context.getString(R.string.litert_models_download_started, entry.title) },
                    onFailure = { it.message ?: context.getString(R.string.error_generic) }
                )
            )
        }
    }

    val activeDownloads = progress.count { (key, value) ->
        key.startsWith(LITERT_PROGRESS_PREFIX) &&
            (value == DownloadProgressHolder.INDETERMINATE || value in 0f..0.999f)
    }
    val tabs = listOf(
        stringResource(R.string.models_tab_installed),
        stringResource(R.string.models_tab_downloading),
        stringResource(R.string.models_tab_discover)
    )

    AppPageBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            AppContentColumn(
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 8.dp,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppPageHeader(
                    eyebrow = "LITERT",
                    title = stringResource(R.string.litert_models_title),
                    subtitle = stringResource(R.string.litert_models_subtitle)
                )
                AppSectionCard {
                    TabRow(
                        selectedTabIndex = selectedTab,
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
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> LiteRtInstalledTab(
                        models = models,
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
                        onRemove = { pendingDelete = it },
                        onDoctorDetails = { doctorDetails = it }
                    )
                    1 -> LiteRtDownloadingTab(
                        progress = progress,
                        statuses = statuses,
                        onCancel = { key ->
                            val filename = DownloadProgressHolder.getFilename(key) ?: return@LiteRtDownloadingTab
                            DownloadService.cancelDownload(context, filename, key)
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
                        onDownload = ::download
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
                            toast(context.getString(R.string.litert_models_context_invalid))
                            return@TextButton
                        }
                        scope.launch {
                            repository.updateMaxContextTokens(model, parsed)
                            toast(context.getString(R.string.litert_models_context_saved))
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
                            toast(context.getString(R.string.litert_models_modalities_saved))
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
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedUri = uri
                        pendingImportUri = null
                        scope.launch {
                            val result = repository.importFromUri(
                                selectedUri,
                                supportsVisionOverride = importSupportsVision,
                                supportsAudioOverride = importSupportsAudio,
                                supportsEmbeddingOverride = importSupportsEmbedding
                            )
                            toast(
                                result.fold(
                                    onSuccess = { context.getString(R.string.litert_models_imported) },
                                    onFailure = { it.message ?: context.getString(R.string.error_generic) }
                                )
                            )
                        }
                    }
                ) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text(stringResource(R.string.action_cancel))
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
                                    onSuccess = { context.getString(R.string.litert_models_removed) },
                                    onFailure = { it.message ?: context.getString(R.string.error_generic) }
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
}

@Composable
private fun LiteRtInstalledTab(
    models: List<LiteRtModelEntity>,
    managedRoot: File,
    doctorResults: Map<Long, List<LiteRtBackendDoctorResult>>,
    onRename: (LiteRtModelEntity) -> Unit,
    onExport: (LiteRtModelEntity) -> Unit,
    onEditContext: (LiteRtModelEntity) -> Unit,
    onEditModalities: (LiteRtModelEntity) -> Unit,
    onRemove: (LiteRtModelEntity) -> Unit,
    onDoctorDetails: (LiteRtBackendDoctorResult) -> Unit
) {
    val storageSnapshot = remember(models, managedRoot.absolutePath) {
        readLiteRtStorageSnapshot(managedRoot, models)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { LiteRtStorageOverviewCard(storageSnapshot) }
        item {
            Text(
                stringResource(R.string.litert_models_installed_title, models.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (models.isEmpty()) {
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
                    onRemove = { onRemove(model) },
                    onDoctorDetails = onDoctorDetails
                )
            }
        }

        item { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

@Composable
private fun LiteRtDownloadingTab(
    progress: Map<String, Float>,
    statuses: Map<String, String>,
    onCancel: (String) -> Unit
) {
    val active = progress
        .filter { (key, value) ->
            key.startsWith(LITERT_PROGRESS_PREFIX) &&
                (value == DownloadProgressHolder.INDETERMINATE || value in 0f..0.999f)
        }
        .toSortedMap()

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

        if (active.isEmpty()) {
            item {
                EmptyModelState(
                    title = stringResource(R.string.litert_models_downloading_empty),
                    subtitle = stringResource(R.string.litert_models_downloading_empty_desc)
                )
            }
        } else {
            items(active.entries.toList(), key = { it.key }) { entry ->
                LiteRtDownloadProgressCard(
                    repoId = entry.key.removePrefix(LITERT_PROGRESS_PREFIX),
                    filename = DownloadProgressHolder.getFilename(entry.key),
                    progress = entry.value,
                    status = statuses[entry.key],
                    onCancel = { onCancel(entry.key) }
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
    onDownload: (LiteRtCatalogEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var liveResults by remember { mutableStateOf<List<LiteRtCatalogEntry>>(emptyList()) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var isLiveSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalizedQuery = query.trim()
    val deviceInfo = remember { currentLiteRtDeviceTargetInfo() }
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
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(40.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        entry.repoId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
                            color = accentColor
                        )
                    }
                }
            }
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
    onCancel: () -> Unit
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    filename ?: repoId,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                color = MaterialTheme.colorScheme.primary
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        listOfNotNull(subtitle, sizeText, contextText).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
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
private fun LiteRtStorageOverviewCard(snapshot: LiteRtStorageSnapshot) {
    val context = LocalContext.current
    val totalText = FormatUtils.Display.formatBytes(context, snapshot.totalBytes)
    val freeText = FormatUtils.Display.formatBytes(context, snapshot.freeBytes)
    val modelsText = FormatUtils.Display.formatBytes(context, snapshot.modelBytes)
    val otherText = FormatUtils.Display.formatBytes(context, snapshot.otherUsedBytes)
    val usedFraction = if (snapshot.totalBytes > 0L) {
        ((snapshot.totalBytes - snapshot.freeBytes).toFloat() / snapshot.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    AppSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.models_storage_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.models_storage_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StorageMetric(
                    label = stringResource(R.string.models_storage_total),
                    value = totalText,
                    modifier = Modifier.weight(1f)
                )
                StorageMetric(
                    label = stringResource(R.string.models_storage_free),
                    value = freeText,
                    modifier = Modifier.weight(1f)
                )
                StorageMetric(
                    label = stringResource(R.string.models_storage_models),
                    value = modelsText,
                    modifier = Modifier.weight(1f)
                )
            }
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.models_storage_legend_value,
                        stringResource(R.string.models_storage_models),
                        modelsText
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.models_storage_legend_value,
                        stringResource(R.string.models_storage_other),
                        otherText
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StorageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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

private data class LiteRtStorageSnapshot(
    val totalBytes: Long,
    val freeBytes: Long,
    val modelBytes: Long,
    val otherUsedBytes: Long
)

private fun readLiteRtStorageSnapshot(root: File, models: List<LiteRtModelEntity>): LiteRtStorageSnapshot {
    val stats = StatFs(root.absolutePath)
    val total = stats.totalBytes
    val free = stats.availableBytes
    val modelBytes = models.sumOf { it.sizeBytes }.coerceAtLeast(0L)
    val other = (total - free - modelBytes).coerceAtLeast(0L)
    return LiteRtStorageSnapshot(
        totalBytes = total,
        freeBytes = free,
        modelBytes = modelBytes,
        otherUsedBytes = other
    )
}

private fun defaultExportName(model: LiteRtModelEntity): String {
    val source = File(model.path)
    return if (source.isDirectory) {
        model.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "litert_model" } + ".zip"
    } else {
        source.name
    }
}
