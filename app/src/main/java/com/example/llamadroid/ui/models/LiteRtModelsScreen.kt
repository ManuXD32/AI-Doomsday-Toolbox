package com.example.llamadroid.ui.models

import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.currentLiteRtDeviceTargetInfo
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.repository.LiteRtCatalogCategory
import com.example.llamadroid.data.repository.LiteRtCatalogEntry
import com.example.llamadroid.data.repository.LiteRtModelCatalog
import com.example.llamadroid.data.repository.LiteRtModelRepository
import com.example.llamadroid.service.LiteRtBackendDoctorResult
import com.example.llamadroid.service.LiteRtBackendDoctorStore
import com.example.llamadroid.service.LiteRtLmWorkerClient
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.launch
import java.io.File

private const val LITERT_PROGRESS_PREFIX = "litert:"

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
    var pendingDelete by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var pendingExport by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var pendingParityDoctor by remember { mutableStateOf<LiteRtModelEntity?>(null) }
    var doctorRunningModelId by remember { mutableStateOf<Long?>(null) }
    var doctorDetails by remember { mutableStateOf<LiteRtBackendDoctorResult?>(null) }
    var huggingFaceToken by remember { mutableStateOf(repository.huggingFaceToken()) }
    val doctorResults = remember { mutableStateMapOf<Long, List<LiteRtBackendDoctorResult>>() }
    val doctorClient = remember { LiteRtLmWorkerClient(context.applicationContext) }

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
        scope.launch {
            val result = repository.importFromUri(uri)
            toast(
                result.fold(
                    onSuccess = { context.getString(R.string.litert_models_imported) },
                    onFailure = { it.message ?: context.getString(R.string.error_generic) }
                )
            )
        }
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

    fun runDoctor(model: LiteRtModelEntity) {
        if (doctorRunningModelId != null) return
        doctorRunningModelId = model.id
        scope.launch {
            try {
                val results = mutableListOf<LiteRtBackendDoctorResult>()
                model.doctorBackends().forEach { backend ->
                    val result = doctorClient.runBackendDoctor(model, backend)
                    LiteRtBackendDoctorStore.save(context, result)
                    results += result
                    doctorResults[model.id] = results.toList()
                }
                toast(context.getString(R.string.litert_doctor_finished))
            } catch (error: Throwable) {
                toast(error.message ?: context.getString(R.string.error_generic))
            } finally {
                doctorRunningModelId = null
            }
        }
    }

    fun runInAppGpuParityDoctor(model: LiteRtModelEntity) {
        if (doctorRunningModelId != null) return
        doctorRunningModelId = model.id
        scope.launch {
            try {
                toast(context.getString(R.string.litert_doctor_parity_started))
                val result = doctorClient.runInAppGpuParityDoctor(model)
                LiteRtBackendDoctorStore.save(context, result)
                val updated = (doctorResults[model.id].orEmpty() + result)
                    .sortedByDescending { it.startedAt }
                doctorResults[model.id] = updated
                doctorDetails = result
            } catch (error: Throwable) {
                toast(error.message ?: context.getString(R.string.error_generic))
            } finally {
                doctorRunningModelId = null
            }
        }
    }

    val activeDownloads = progress.count { (key, value) ->
        key.startsWith(LITERT_PROGRESS_PREFIX) && value < 1f
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
                        doctorRunningModelId = doctorRunningModelId,
                        onRename = {
                            pendingRename = it
                            renameValue = it.displayName
                        },
                        onExport = {
                            pendingExport = it
                            exportLauncher.launch(defaultExportName(it))
                        },
                        onRemove = { pendingDelete = it },
                        onDoctor = ::runDoctor,
                        onParityDoctor = { pendingParityDoctor = it },
                        onDoctorDetails = { doctorDetails = it }
                    )
                    1 -> LiteRtDownloadingTab(
                        progress = progress,
                        statuses = statuses,
                        onCancel = { key ->
                            val filename = DownloadProgressHolder.getFilename(key) ?: return@LiteRtDownloadingTab
                            DownloadService.cancelDownload(context, filename)
                            DownloadProgressHolder.removeProgress(key)
                        }
                    )
                    else -> LiteRtCatalogTab(
                        progress = progress,
                        huggingFaceToken = huggingFaceToken,
                        onHuggingFaceTokenChange = { token ->
                            huggingFaceToken = token
                            repository.saveHuggingFaceToken(token)
                        },
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

    pendingParityDoctor?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingParityDoctor = null },
            title = { Text(stringResource(R.string.litert_doctor_parity_warning_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.litert_doctor_parity_warning_desc, model.displayName))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingParityDoctor = null
                        runInAppGpuParityDoctor(model)
                    }
                ) { Text(stringResource(R.string.litert_doctor_parity_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingParityDoctor = null }) {
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
    doctorRunningModelId: Long?,
    onRename: (LiteRtModelEntity) -> Unit,
    onExport: (LiteRtModelEntity) -> Unit,
    onRemove: (LiteRtModelEntity) -> Unit,
    onDoctor: (LiteRtModelEntity) -> Unit,
    onParityDoctor: (LiteRtModelEntity) -> Unit,
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
                LiteRtCompactModelCard(
                    model = model,
                    doctorResults = doctorResults[model.id].orEmpty(),
                    isDoctorRunning = doctorRunningModelId == model.id,
                    onRename = { onRename(model) },
                    onExport = { onExport(model) },
                    onRemove = { onRemove(model) },
                    onDoctor = { onDoctor(model) },
                    onParityDoctor = if (model.supportsGpu && model.isLikelyLiteRtGpuPackage()) {
                        { onParityDoctor(model) }
                    } else {
                        null
                    },
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
        .filter { (key, value) -> key.startsWith(LITERT_PROGRESS_PREFIX) && value < 1f }
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
    onDownload: (LiteRtCatalogEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val normalizedQuery = query.trim()
    val deviceInfo = remember { currentLiteRtDeviceTargetInfo() }
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.litert_catalog_search_label)) },
                    placeholder = { Text(stringResource(R.string.litert_catalog_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Text(
                stringResource(R.string.litert_models_catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        listOf(
            LiteRtCatalogCategory.GPU,
            LiteRtCatalogCategory.CPU
        ).forEach { category ->
            val entries = LiteRtModelCatalog.entriesFor(category)
                .filter { it.matchesCatalogQuery(normalizedQuery) }
                .sortedWith(liteRtCatalogComparator())
            item(key = "catalog_header_${category.name}") {
                LiteRtCatalogGroupHeader(
                    title = localizedCatalogGroupTitle(category, entries.size),
                    description = localizedCatalogGroupDescription(category)
                )
            }
            items(entries, key = { it.catalogId }) { entry ->
                val progressKey = "$LITERT_PROGRESS_PREFIX${entry.catalogId}"
                LiteRtCatalogCard(
                    entry = entry,
                    description = localizedCatalogDescription(entry),
                    compatibility = entry.catalogCompatibility(),
                    progress = progress[progressKey],
                    onDownload = { onDownload(entry) }
                )
            }
        }

        if (normalizedQuery.isNotBlank() && LiteRtModelCatalog.defaultEntries.none { it.matchesCatalogQuery(normalizedQuery) }) {
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
    description: String
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
    doctorResults: List<LiteRtBackendDoctorResult>,
    isDoctorRunning: Boolean,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
    onDoctor: () -> Unit,
    onParityDoctor: (() -> Unit)?,
    onDoctorDetails: (LiteRtBackendDoctorResult) -> Unit
) {
    ModelStyleCard(
        title = model.displayName,
        subtitle = model.repoId ?: stringResource(R.string.litert_models_local_import),
        sizeText = FormatUtils.formatFileSize(model.sizeBytes),
        actionIcon = Icons.Default.Delete,
        actionColor = MaterialTheme.colorScheme.error,
        onAction = onRemove,
        onExport = onExport,
        onRename = onRename,
        onDoctor = onDoctor,
        onParityDoctor = onParityDoctor,
        doctorRunning = isDoctorRunning,
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
    onDownload: () -> Unit
) {
    val isDownloading = progress != null && progress in 0f..0.999f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    tint = MaterialTheme.colorScheme.primary,
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
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    progress?.takeIf { isDownloading }?.let {
                        LinearProgressIndicator(
                            progress = { it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            stringResource(R.string.whisper_downloading_progress, (it * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            IconButton(onClick = onDownload, enabled = !isDownloading && compatibility.canDownload) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.action_download),
                    tint = MaterialTheme.colorScheme.primary
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
private fun LiteRtCatalogEntry.catalogCompatibility(): LiteRtCatalogCompatibility {
    return LiteRtCatalogCompatibility(
        label = stringResource(R.string.litert_catalog_generic_package),
        recommended = false,
        canDownload = true
    )
}

private fun liteRtCatalogComparator(): Comparator<LiteRtCatalogEntry> = compareBy<LiteRtCatalogEntry> { entry ->
    entry.title.lowercase()
}

private fun LiteRtModelEntity.doctorBackends(): List<String> = buildList {
    if (supportsGpu && isLikelyLiteRtGpuPackage()) add(LITERT_BACKEND_GPU)
    if (supportsCpu) add(LITERT_BACKEND_CPU)
    if (isEmpty()) add(LITERT_BACKEND_CPU)
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
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Text(
                stringResource(R.string.whisper_downloading_progress, (progress * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ModelStyleCard(
    title: String,
    subtitle: String,
    sizeText: String,
    actionIcon: ImageVector,
    actionColor: Color,
    onAction: () -> Unit,
    onExport: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDoctor: (() -> Unit)? = null,
    onParityDoctor: (() -> Unit)? = null,
    doctorRunning: Boolean = false,
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
                        "$subtitle • $sizeText",
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
                onDoctor?.let {
                    IconButton(onClick = it, enabled = !doctorRunning) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.litert_doctor_run),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                onParityDoctor?.let {
                    IconButton(onClick = it, enabled = !doctorRunning) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.litert_doctor_parity_run),
                            tint = MaterialTheme.colorScheme.error
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
    when (entry.category) {
        LiteRtCatalogCategory.GPU -> stringResource(R.string.litert_catalog_gpu_entry_desc)
        LiteRtCatalogCategory.CPU -> stringResource(R.string.litert_catalog_cpu_entry_desc)
    }

@Composable
private fun localizedCatalogGroupTitle(category: LiteRtCatalogCategory, count: Int): String =
    when (category) {
        LiteRtCatalogCategory.GPU -> stringResource(R.string.litert_catalog_group_gpu, count)
        LiteRtCatalogCategory.CPU -> stringResource(R.string.litert_catalog_group_cpu, count)
    }

@Composable
private fun localizedCatalogGroupDescription(category: LiteRtCatalogCategory): String =
    when (category) {
        LiteRtCatalogCategory.GPU -> stringResource(R.string.litert_catalog_group_gpu_desc)
        LiteRtCatalogCategory.CPU -> stringResource(R.string.litert_catalog_group_cpu_desc)
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
