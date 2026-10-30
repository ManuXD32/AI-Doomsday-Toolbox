package com.example.llamadroid.ui.easy

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.easy.EasyImageEngine
import com.example.llamadroid.data.easy.EasyModelCatalog
import com.example.llamadroid.data.easy.EasyModelEntry
import com.example.llamadroid.data.easy.EasyModelKind
import com.example.llamadroid.data.easy.EasyModelSource
import com.example.llamadroid.data.easy.easyLocalImageModelId
import com.example.llamadroid.data.model.BundleProgressSnapshot
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.repository.isEasyLlamaChatModel
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.onnx.OnnxCatalog
import com.example.llamadroid.onnx.OnnxCatalogProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun deviceTotalRamBytes(context: Context): Long {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }.totalMem
}

@Composable
internal fun easyEntryName(entry: EasyModelEntry): String = when (val source = entry.source) {
    is EasyModelSource.LlamaBundle -> EasyModelCatalog.llamaBundle(entry)?.let { stringResource(it.titleRes) } ?: entry.id
    is EasyModelSource.SdBundle -> EasyModelCatalog.sdBundle(entry)?.let { stringResource(it.titleRes) } ?: entry.id
    is EasyModelSource.Onnx -> EasyModelCatalog.onnxEntry(entry)?.title ?: entry.id
    is EasyModelSource.Whisper -> source.model.displayName
}

@Composable
internal fun rememberEasyModelState(): EasyModelState {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val installed by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val tasks by db.downloadTaskDao().observeAll().collectAsState(initial = emptyList())
    val progress by DownloadProgressHolder.progress.collectAsState()
    // Owned here (not by the per-recomposition EasyModelState) so progress bars never move backwards.
    val previousSnapshots = remember { HashMap<String, BundleProgressSnapshot>() }
    val previousFractions = remember { HashMap<String, Float>() }
    return EasyModelState(context, repository, installed, tasks, progress, previousSnapshots, previousFractions)
}

/**
 * Easy mode model picker: curated list per kind (never hidden, only warned), installed compatible
 * models, and a simplified importer ([EasyModelImportSection]). Downloads and install state come from
 * the same sources as the advanced model managers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyModelsScreen(initialKind: EasyModelKind, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { EasyModeSettings(context) }
    val state = rememberEasyModelState()
    val totalRam = remember { deviceTotalRamBytes(context) }
    var kind by remember { mutableStateOf(initialKind) }
    var engine by remember { mutableStateOf(EasyImageEngine.SDCPP) }
    val chatPath by settings.chatModelPathFlow.collectAsState()
    val imageId by settings.imageModelIdFlow.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var voicePath by remember { mutableStateOf(settings.whisperModelPath) }
    val repository = remember { ModelRepository(context, AppDatabase.getDatabase(context).modelDao()) }
    var pendingRemoval by remember { mutableStateOf<EasyRemovalRequest?>(null) }
    var removing by remember { mutableStateOf(false) }
    val removeFailedMessage = stringResource(R.string.models_delete_result_retry)
    // Resolved in composition (lint: no resource reads through LocalContext); formatted after removal.
    val removedFormat = stringResource(R.string.models_delete_result_completed)
    val removeBusyFormat = stringResource(R.string.easy_models_remove_busy)

    val showOnnx = kind == EasyModelKind.IMAGE && engine == EasyImageEngine.ONNX
    val entries = when {
        showOnnx -> easyOnnxEntries()
        kind == EasyModelKind.IMAGE -> EasyModelCatalog.entriesFor(kind).filter { it.engine == EasyImageEngine.SDCPP }
        else -> EasyModelCatalog.entriesFor(kind)
    }
    val recommended = EasyModelCatalog.recommendFor(kind, totalRam)
    // Every curated entry of the kind (both image engines) so "On this phone" never repeats them.
    val curatedInstalled = (EasyModelCatalog.entriesFor(kind) + if (kind == EasyModelKind.IMAGE) easyOnnxEntries() else emptyList())
        .mapNotNull { EasyModelCatalog.installedModel(it, state.installedModels) }
    val curatedPaths = curatedInstalled.map { it.path }.toSet()
    // File existence is checked once per installed list on IO; null (first frame) assumes present.
    val existingPaths by produceState<Set<String>?>(null, state.installedModels) {
        val models = state.installedModels
        value = withContext(Dispatchers.IO) { models.filter { File(it.path).exists() }.map { it.path }.toSet() }
    }
    val local: List<ModelEntity> = when (kind) {
        EasyModelKind.CHAT -> state.installedModels.filter { it.path !in curatedPaths && isEasyLlamaChatModel(it) }
        EasyModelKind.IMAGE -> easyLocalImageModels(state.installedModels.map { if (it.isEasyUsable()) it.copy(isDownloaded = true) else it }, curatedPaths) { path -> existingPaths?.contains(path) != false }.filter {
            (it.type == ModelType.ONNX_IMAGE_GEN) == showOnnx
        }
        EasyModelKind.VOICE -> state.installedModels.filter {
            it.type == ModelType.WHISPER && it.isEasyUsable() && it.path !in curatedPaths && existingPaths?.contains(it.path) != false
        }
    }

    fun select(entry: EasyModelEntry, model: ModelEntity) {
        when (entry.kind) {
            EasyModelKind.CHAT -> settings.chatModelPath = model.path
            EasyModelKind.IMAGE ->
                // Only a few ONNX bundles are curated Easy entries; the rest resolve like imported files.
                settings.imageModelId = if (EasyModelCatalog.byId(entry.id) != null) entry.id else easyLocalImageModelId(model.filename)
            EasyModelKind.VOICE -> { settings.whisperModelPath = model.path; voicePath = model.path }
        }
    }

    fun selectLocal(model: ModelEntity) {
        when (kind) {
            EasyModelKind.CHAT -> settings.chatModelPath = model.path
            EasyModelKind.IMAGE -> settings.imageModelId = easyLocalImageModelId(model.filename)
            EasyModelKind.VOICE -> { settings.whisperModelPath = model.path; voicePath = model.path }
        }
    }

    fun isLocalInUse(model: ModelEntity): Boolean = when (kind) {
        EasyModelKind.CHAT -> chatPath == model.path
        EasyModelKind.IMAGE -> imageId == easyLocalImageModelId(model.filename)
        EasyModelKind.VOICE -> voicePath == model.path
    }

    fun requestRemoval(name: String, models: List<ModelEntity>, entryId: String?) {
        if (models.isNotEmpty()) pendingRemoval = EasyRemovalRequest(name, models, entryId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.easy_models_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryTabRow(selectedTabIndex = EasyModelKind.entries.indexOf(kind)) {
                EasyModelKind.entries.forEach { tabKind ->
                    Tab(
                        selected = tabKind == kind,
                        onClick = { kind = tabKind },
                        text = {
                            Text(
                                stringResource(
                                    when (tabKind) {
                                        EasyModelKind.CHAT -> R.string.easy_models_tab_chat
                                        EasyModelKind.IMAGE -> R.string.easy_models_tab_images
                                        EasyModelKind.VOICE -> R.string.easy_models_tab_voice
                                    }
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
            if (kind == EasyModelKind.IMAGE) {
                SecondaryTabRow(selectedTabIndex = engine.ordinal) {
                    EasyImageEngine.entries.forEach { sub ->
                        Tab(
                            selected = sub == engine,
                            onClick = { engine = sub },
                            text = {
                                Text(
                                    stringResource(
                                        if (sub == EasyImageEngine.SDCPP) R.string.easy_models_sub_sdcpp else R.string.easy_models_sub_onnx
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
            Text(
                stringResource(
                    R.string.easy_models_best_for_phone,
                    "${EasyModelCatalog.advertisedRamBytes(totalRam) / (1024L * 1024L * 1024L)} GB"
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (kind == EasyModelKind.VOICE) {
                Text(
                    stringResource(R.string.easy_models_voice_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AppSectionCard {
                entries.forEach { entry ->
                    val status = state.status(entry)
                    val installed = status.installed
                    val inUse = installed != null && when (kind) {
                        EasyModelKind.CHAT -> chatPath == installed.path
                        EasyModelKind.IMAGE -> imageId == entry.id || imageId == easyLocalImageModelId(installed.filename)
                        EasyModelKind.VOICE -> voicePath == installed.path
                    }
                    val name = easyEntryName(entry)
                    val failedMessage = stringResource(R.string.easy_models_download_failed, name)
                    EasyModelRow(
                        name = name,
                        subtitle = easyOnnxStyleLine(entry),
                        sizeBytes = EasyModelCatalog.approxBytes(entry),
                        recommended = entry == recommended,
                        installed = installed != null,
                        inUse = inUse,
                        downloading = status.downloading,
                        progress = status.progress,
                        warning = EasyModelCatalog.exceedsRecommendation(entry, totalRam),
                        requiredRamBytes = entry.recommendedMinRamBytes,
                        onSelect = { installed?.let { select(entry, it) } },
                        onGet = {
                            if (!state.start(entry)) scope.launch { snackbar.showSnackbar(failedMessage) }
                        },
                        onCancel = { state.cancel(entry) },
                        onRemove = { requestRemoval(name, planEasyEntryRemoval(entry, state.installedModels), entry.id) }
                    )
                }
            }

            AppSectionCard {
                Text(
                    stringResource(R.string.easy_models_on_this_phone),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (local.isEmpty()) {
                    Text(
                        stringResource(R.string.easy_models_on_this_phone_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                local.forEach { model ->
                    EasyModelRow(
                        name = model.filename,
                        sizeBytes = model.sizeBytes,
                        recommended = false,
                        installed = true,
                        inUse = isLocalInUse(model),
                        downloading = false,
                        progress = 1f,
                        warning = false,
                        requiredRamBytes = 0L,
                        tag = stringResource(R.string.easy_models_imported_tag),
                        onSelect = { selectLocal(model) },
                        onGet = {},
                        onCancel = {},
                        onRemove = { requestRemoval(model.filename, listOf(model), null) }
                    )
                }
            }

            EasyModelImportSection(kind = kind, engine = engine, onImported = ::selectLocal)
        }
    }

    val request = pendingRemoval
    if (request != null) {
        EasyRemoveDialog(
            request = request,
            removing = removing,
            onDismiss = { if (!removing) pendingRemoval = null },
            onConfirm = {
                removing = true
                scope.launch {
                    val result = removeEasyModels(context, repository, settings, request)
                    removing = false
                    pendingRemoval = null
                    voicePath = settings.whisperModelPath
                    val message = when (result) {
                        is EasyRemovalResult.Removed -> removedFormat.replace(
                            "%1\$s", FormatUtils.Display.formatBytes(context, result.reclaimedBytes)
                        )
                        EasyRemovalResult.Busy -> removeBusyFormat.replace("%1\$s", request.name)
                        EasyRemovalResult.Failed -> removeFailedMessage
                    }
                    snackbar.showSnackbar(message)
                }
            }
        )
    }
}

/** Confirmation for removing installed models; the body scrolls on small phones. */
@Composable
private fun EasyRemoveDialog(
    request: EasyRemovalRequest,
    removing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.easy_models_remove_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(request.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.easy_models_remove_freed,
                        FormatUtils.Display.formatBytes(context, request.freedBytes)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.easy_models_remove_shared_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !removing) {
                Text(stringResource(R.string.easy_models_remove_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !removing) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Every ManuXD32 ONNX bundle: the curated Easy entry when one exists, otherwise a catalog-backed entry. */
internal fun easyOnnxEntries(): List<EasyModelEntry> = OnnxCatalog.entriesFor(OnnxCatalogProvider.MANUXD32).map { onnx ->
    EasyModelCatalog.entriesFor(EasyModelKind.IMAGE).firstOrNull {
        val source = it.source
        source is EasyModelSource.Onnx && source.provider == onnx.provider && source.bundleId == onnx.bundleId
    } ?: EasyModelEntry(
        id = "image-onnx-${onnx.bundleId}",
        kind = EasyModelKind.IMAGE,
        source = EasyModelSource.Onnx(onnx.provider, onnx.bundleId),
        recommendedMinRamBytes = 4L * 1024L * 1024L * 1024L,
        engine = EasyImageEngine.ONNX,
        imageKind = com.example.llamadroid.data.easy.EasyImageKind.ONNX_SD15
    )
}

/** Localized style line of an ONNX entry (English `summary` is the fallback). */
@Composable
internal fun easyOnnxStyleLine(entry: EasyModelEntry): String? =
    EasyModelCatalog.onnxEntry(entry)?.let { onnx -> onnx.summaryRes?.let { stringResource(it) } ?: onnx.summary }

@Composable
private fun EasyModelRow(
    name: String,
    sizeBytes: Long,
    recommended: Boolean,
    installed: Boolean,
    inUse: Boolean,
    downloading: Boolean,
    progress: Float,
    warning: Boolean,
    requiredRamBytes: Long,
    onSelect: () -> Unit,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    tag: String? = null,
    subtitle: String? = null,
    onRemove: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (inUse) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (inUse) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = installed, onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val extra = listOfNotNull(
                        sizeBytes.takeIf { it > 0L }?.let { FormatUtils.Display.formatBytes(context, it) },
                        tag,
                        stringResource(R.string.easy_models_recommended).takeIf { recommended }
                    ).joinToString(" · ")
                    if (extra.isNotBlank()) {
                        Text(
                            extra,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when {
                    downloading -> IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.easy_models_cancel_download))
                    }
                    inUse -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringResource(R.string.easy_models_in_use),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1
                        )
                    }
                    installed -> OutlinedButton(onClick = onSelect) {
                        Text(stringResource(R.string.easy_models_use), maxLines = 1)
                    }
                    else -> Button(onClick = onGet) {
                        Text(stringResource(R.string.easy_models_get), maxLines = 1)
                    }
                }
                if (installed && !downloading && onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.easy_models_remove_cd, name),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.easy_models_downloading, "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (warning) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(
                            R.string.easy_models_warning_ram,
                            "${requiredRamBytes / (1024L * 1024L * 1024L)} GB"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
