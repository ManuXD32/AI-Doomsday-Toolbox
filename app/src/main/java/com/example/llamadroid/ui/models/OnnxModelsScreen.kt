package com.example.llamadroid.ui.models

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.db.ONNX_CAPABILITY_BACKGROUND_REMOVAL
import com.example.llamadroid.data.db.ONNX_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TTS
import com.example.llamadroid.data.db.buildOnnxCapabilities
import com.example.llamadroid.data.db.onnxCapabilityTokens
import com.example.llamadroid.onnx.OnnxBundleValidationResult
import com.example.llamadroid.onnx.OnnxCatalog
import com.example.llamadroid.onnx.OnnxCatalogEntry
import com.example.llamadroid.onnx.OnnxCatalogProvider
import com.example.llamadroid.onnx.OnnxBackgroundRemovalRiskClass
import com.example.llamadroid.onnx.OnnxImportSupport
import com.example.llamadroid.onnx.OnnxInstallSource
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.OnnxTtsBundleValidator
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_CUSTOM_IMPORT_BUNDLE
import com.example.llamadroid.onnx.ONNX_PIPELINE_FAMILY_SUPERTONIC_TTS
import com.example.llamadroid.onnx.backgroundRemovalRiskClass
import com.example.llamadroid.onnx.buildOnnxImageGenModelEntity
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.parseOnnxCatalogProvider
import com.example.llamadroid.onnx.resolveOnnxCatalogEntry
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnnxModelsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val settingsRepo = remember { SettingsRepository(context) }
    val installedModels by db.modelDao().getModelsByTypes(
        listOf(ModelType.ONNX_IMAGE_GEN, ModelType.ONNX_TTS, ModelType.ONNX_BACKGROUND_REMOVAL)
    ).collectAsState(initial = emptyList())
    val onnxModels = remember(installedModels) {
        installedModels.filter { it.type == ModelType.ONNX_TTS || it.isOnnxTxt2ImgBundle() || it.isOnnxBackgroundRemovalModel() }
    }
    val downloadProgress by DownloadProgressHolder.progress.collectAsState()
    val downloadStatus by DownloadProgressHolder.status.collectAsState()
    val onnxDownloads = remember(downloadProgress) { downloadProgress.filterKeys { it.startsWith("onnx:") } }
    val activeOnnxDownloads = remember(onnxDownloads) {
        onnxDownloads.filterValues { it in 0f..0.999f }
    }
    val selectedProvider by settingsRepo.onnxCatalogProvider.collectAsState()
    val catalogEntries = remember(selectedProvider) { OnnxCatalog.entriesFor(selectedProvider) }
    val installedCatalogIds = remember(onnxModels) {
        onnxModels.mapNotNull { model ->
            resolveOnnxCatalogEntry(model)?.stableId
        }.toSet()
    }
    val validationMap = remember { mutableStateMapOf<String, OnnxBundleValidationResult>() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importLabel by remember { mutableStateOf("") }
    var pendingDeleteModel by remember { mutableStateOf<ModelEntity?>(null) }
    var huggingFaceToken by remember { mutableStateOf(repository.huggingFaceToken()) }

    LaunchedEffect(onnxModels) {
        withContext(Dispatchers.IO) {
            onnxModels.forEach { model ->
                validationMap[model.filename] = when {
                    model.type == ModelType.ONNX_TTS -> OnnxTtsBundleValidator.validateDirectory(File(model.path))
                    model.isOnnxTxt2ImgBundle() -> com.example.llamadroid.onnx.OnnxBundleValidator.validateDirectory(File(model.path))
                    else -> OnnxBundleValidationResult(
                        isValid = File(model.path).isFile,
                        missingPaths = if (File(model.path).isFile) emptyList() else listOf(File(model.path).name),
                        bundleRoot = File(model.path).parentFile ?: File(model.path),
                        supportedCapabilities = model.onnxCapabilityTokens()
                    )
                }
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch(Dispatchers.IO) {
            isImporting = true
            importProgress = 0f
            importLabel = context.getString(R.string.onnx_models_importing)
            val result = importOnnxBundleFromTree(
                context = context,
                repository = repository,
                treeUri = treeUri,
                existingIds = onnxModels.map { it.filename }.toSet(),
                onProgress = { progress, label ->
                    importProgress = progress
                    importLabel = label
                }
            )
            isImporting = false
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    result.getOrElse { it.message ?: context.getString(R.string.error_generic) },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { treePicker.launch(null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.onnx_models_import_bundle))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    Text(
                        stringResource(R.string.onnx_models_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    stringResource(R.string.onnx_models_subtitle),
                    modifier = Modifier.padding(start = 52.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }


            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.onnx_models_tab_installed, onnxModels.size)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.onnx_models_tab_downloading))
                            if (activeOnnxDownloads.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge { Text("${activeOnnxDownloads.size}") }
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.onnx_models_tab_catalog)) }
                )
            }

            when (selectedTab) {
                0 -> InstalledOnnxModelsTab(
                    models = onnxModels.sortedBy { (resolveOnnxCatalogEntry(it)?.title ?: it.filename).lowercase() },
                    validationMap = validationMap,
                    onDeleteRequest = { pendingDeleteModel = it }
                )
                1 -> DownloadingOnnxModelsTab(
                    downloadProgress = activeOnnxDownloads,
                    downloadStatus = downloadStatus,
                    onCancel = { key ->
                        val filename = DownloadProgressHolder.getFilename(key) ?: key.removePrefix("onnx:")
                        com.example.llamadroid.service.DownloadService.cancelDownload(context, filename)
                        DownloadProgressHolder.removeProgress(key)
                    }
                )
                else -> CatalogOnnxModelsTab(
                    selectedProvider = selectedProvider,
                    onProviderChange = { settingsRepo.setOnnxCatalogProvider(it) },
                    entries = catalogEntries,
                    installedIds = installedCatalogIds,
                    activeDownloadIds = activeOnnxDownloads.keys.map { it.removePrefix("onnx:") }.toSet(),
                    huggingFaceToken = huggingFaceToken,
                    onHuggingFaceTokenChange = {
                        huggingFaceToken = it
                        repository.saveHuggingFaceToken(it)
                    },
                    onDownload = { entry ->
                        repository.startOnnxCatalogDownload(entry)
                        Toast.makeText(
                            context,
                            context.getString(R.string.onnx_models_download_started, entry.title),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            if (isImporting) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            importLabel.ifBlank { stringResource(R.string.onnx_models_importing) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { importProgress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    pendingDeleteModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDeleteModel = null },
            title = { Text(stringResource(R.string.onnx_models_delete_title)) },
            text = { Text(stringResource(R.string.onnx_models_delete_desc, model.filename)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteModel(model)
                            pendingDeleteModel = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteModel = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun InstalledOnnxModelsTab(
    models: List<ModelEntity>,
    validationMap: Map<String, OnnxBundleValidationResult>,
    onDeleteRequest: (ModelEntity) -> Unit
) {
    if (models.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.onnx_models_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(models, key = { it.path }) { model ->
            val validation = validationMap[model.filename]
            val catalogEntry = resolveOnnxCatalogEntry(model)
            val provider = parseOnnxCatalogProvider(model.repoId)
            val bgrRisk = model.backgroundRemovalRiskClass()
            OnnxManagerCard(
                accentColor = when {
                    bgrRisk == OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY -> MaterialTheme.colorScheme.error
                    validation == null || validation.isValid -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                catalogEntry?.title ?: model.filename,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.onnx_models_size_label, FormatUtils.formatFileSize(model.sizeBytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { onDeleteRequest(model) }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OnnxBadgeGroup(
                        badges = buildList {
                            provider?.let {
                                add(
                                    OnnxBadgeModel(
                                        label = onnxProviderLabel(it),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                            add(
                                OnnxBadgeModel(
                                    label = when (model.type) {
                                        ModelType.ONNX_TTS -> stringResource(R.string.onnx_models_capability_badge_tts)
                                        ModelType.ONNX_BACKGROUND_REMOVAL -> stringResource(R.string.onnx_models_capability_badge_bgr)
                                        else -> stringResource(R.string.onnx_models_capability_badge_txt2img)
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                            if (model.onnxCapabilityTokens().contains(ONNX_CAPABILITY_IMG2IMG)) {
                                add(
                                    OnnxBadgeModel(
                                        label = stringResource(R.string.onnx_models_capability_badge_img2img),
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                )
                            }
                            bgrRisk?.let { add(backgroundRemovalRiskBadge(it)) }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.onnx_models_source_label,
                            when {
                                provider != null -> stringResource(R.string.onnx_models_source_catalog_provider, onnxProviderLabel(provider))
                                else -> stringResource(R.string.onnx_models_source_import)
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    catalogEntry?.sourceLabel?.let { sourceLabel ->
                        Text(
                            stringResource(R.string.onnx_models_catalog_source, sourceLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OnnxStatusLine(
                        text = if (validation == null || validation.isValid) {
                            stringResource(R.string.onnx_models_status_valid)
                        } else {
                            stringResource(
                                R.string.onnx_models_status_invalid,
                                validation.missingPaths.joinToString(", ")
                            )
                        },
                        color = if (validation == null || validation.isValid) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    backgroundRemovalRiskMessage(bgrRisk)?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (bgrRisk == OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingOnnxModelsTab(
    downloadProgress: Map<String, Float>,
    downloadStatus: Map<String, String>,
    onCancel: (String) -> Unit
) {
    val items = downloadProgress.entries.sortedBy { it.key }
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.onnx_models_downloading_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.key }) { (key, progress) ->
            val modelId = DownloadProgressHolder.getFilename(key) ?: key.removePrefix("onnx:")
            val catalogEntry = OnnxCatalog.findByLegacyOrStableId(modelId)
            val status = downloadStatus[key]
            OnnxManagerCard(accentColor = MaterialTheme.colorScheme.primary) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                catalogEntry?.title ?: modelId,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            catalogEntry?.let {
                                Text(
                                    stringResource(
                                        R.string.onnx_models_catalog_size,
                                        FormatUtils.formatFileSize(it.archiveSizeBytes)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(onClick = { onCancel(key) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OnnxBadgeGroup(
                        badges = buildList {
                            catalogEntry?.provider?.let {
                                add(
                                    OnnxBadgeModel(
                                        label = onnxProviderLabel(it),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                            add(
                                OnnxBadgeModel(
                                    label = when (catalogEntry?.modelType) {
                                        ModelType.ONNX_TTS -> stringResource(R.string.onnx_models_capability_badge_tts)
                                        ModelType.ONNX_BACKGROUND_REMOVAL -> stringResource(R.string.onnx_models_capability_badge_bgr)
                                        else -> stringResource(R.string.onnx_models_capability_badge_txt2img)
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                            if (catalogEntry?.provider == OnnxCatalogProvider.MANUXD32) {
                                add(
                                    OnnxBadgeModel(
                                        label = stringResource(R.string.onnx_models_capability_badge_img2img),
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                )
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.onnx_models_download_progress,
                            (progress.coerceIn(0f, 1f) * 100f).roundToInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    status?.takeIf { it.isNotBlank() }?.let { phase ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            phase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogOnnxModelsTab(
    selectedProvider: OnnxCatalogProvider,
    onProviderChange: (OnnxCatalogProvider) -> Unit,
    entries: List<OnnxCatalogEntry>,
    installedIds: Set<String>,
    activeDownloadIds: Set<String>,
    huggingFaceToken: String,
    onHuggingFaceTokenChange: (String) -> Unit,
    onDownload: (OnnxCatalogEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OnnxManagerCard(accentColor = MaterialTheme.colorScheme.tertiary) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.onnx_models_provider_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.onnx_models_provider_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        OnnxCatalogProvider.entries.forEachIndexed { index, provider ->
                            SegmentedButton(
                                selected = selectedProvider == provider,
                                onClick = { onProviderChange(provider) },
                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = OnnxCatalogProvider.entries.size
                                )
                            ) {
                                Text(onnxProviderLabel(provider))
                            }
                        }
                    }
                    if (selectedProvider == OnnxCatalogProvider.BACKGROUND_REMOVAL) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = huggingFaceToken,
                            onValueChange = onHuggingFaceTokenChange,
                            label = { Text(stringResource(R.string.onnx_models_hf_token_label)) },
                            placeholder = { Text(stringResource(R.string.onnx_models_hf_token_placeholder)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.onnx_models_hf_token_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.bgr_catalog_play_safe_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.bgr_catalog_recommended_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        items(entries, key = { it.stableId }) { entry ->
            val isInstalled = entry.stableId in installedIds
            val isDownloading = entry.stableId in activeDownloadIds
            val bgrRisk = entry.backgroundRemovalRiskClass()
            val capabilityBadges = buildList {
                add(
                    OnnxBadgeModel(
                        label = when (entry.modelType) {
                            ModelType.ONNX_TTS -> stringResource(R.string.onnx_models_capability_badge_tts)
                            ModelType.ONNX_BACKGROUND_REMOVAL -> stringResource(R.string.onnx_models_capability_badge_bgr)
                            else -> stringResource(R.string.onnx_models_capability_badge_txt2img)
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
                if (entry.provider == OnnxCatalogProvider.MANUXD32) {
                    add(
                        OnnxBadgeModel(
                            label = stringResource(R.string.onnx_models_capability_badge_img2img),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                }
                if (entry.gated) {
                    add(
                        OnnxBadgeModel(
                            label = stringResource(R.string.onnx_models_badge_gated),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
                bgrRisk?.let { add(backgroundRemovalRiskBadge(it)) }
            }
            OnnxManagerCard(
                accentColor = when {
                    bgrRisk == OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY -> MaterialTheme.colorScheme.error
                    entry.provider == OnnxCatalogProvider.MANUXD32 || entry.modelType == ModelType.ONNX_TTS ->
                        MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.onnx_models_catalog_size,
                                    FormatUtils.formatFileSize(entry.archiveSizeBytes)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { onDownload(entry) },
                            enabled = !isInstalled && !isDownloading && (!entry.gated || huggingFaceToken.isNotBlank())
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when {
                                    isInstalled -> stringResource(R.string.onnx_models_catalog_installed)
                                    isDownloading -> stringResource(R.string.onnx_models_catalog_downloading)
                                    else -> stringResource(R.string.action_download)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OnnxBadgeGroup(
                        badges = listOf(
                            OnnxBadgeModel(
                                label = onnxProviderLabel(entry.provider),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) + capabilityBadges
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    backgroundRemovalRiskMessage(bgrRisk)?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (bgrRisk == OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.onnx_models_catalog_source, entry.sourceLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.onnx_models_catalog_size, FormatUtils.formatFileSize(entry.archiveSizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OnnxManagerCard(
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accentColor.copy(alpha = 0.28f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.0f)
                        )
                    )
                )
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnnxBadgeGroup(badges: List<OnnxBadgeModel>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { badge ->
            OnnxBadge(
                label = badge.label,
                containerColor = badge.containerColor,
                contentColor = badge.contentColor
            )
        }
    }
}

private data class OnnxBadgeModel(
    val label: String,
    val containerColor: Color,
    val contentColor: Color
)

@Composable
private fun backgroundRemovalRiskBadge(risk: OnnxBackgroundRemovalRiskClass): OnnxBadgeModel {
    return when (risk) {
        OnnxBackgroundRemovalRiskClass.RECOMMENDED -> OnnxBadgeModel(
            label = stringResource(R.string.onnx_models_badge_recommended),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        OnnxBackgroundRemovalRiskClass.HEAVY -> OnnxBadgeModel(
            label = stringResource(R.string.onnx_models_badge_heavy),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY -> OnnxBadgeModel(
            label = stringResource(R.string.onnx_models_badge_unsupported_legacy),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun backgroundRemovalRiskMessage(risk: OnnxBackgroundRemovalRiskClass?): String? {
    return when (risk) {
        OnnxBackgroundRemovalRiskClass.RECOMMENDED -> stringResource(R.string.bgr_catalog_recommended_hint)
        OnnxBackgroundRemovalRiskClass.HEAVY -> stringResource(R.string.bgr_catalog_heavy_warning)
        OnnxBackgroundRemovalRiskClass.UNSUPPORTED_LEGACY -> stringResource(R.string.bgr_catalog_legacy_unsupported_hint)
        null -> null
    }
}

@Composable
private fun OnnxBadge(
    label: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OnnxStatusLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun onnxProviderLabel(provider: OnnxCatalogProvider): String {
    return when (provider) {
        OnnxCatalogProvider.SDAI -> stringResource(R.string.onnx_models_provider_sdai)
        OnnxCatalogProvider.MANUXD32 -> stringResource(R.string.onnx_models_provider_manuxd32)
        OnnxCatalogProvider.SUPERTONIC -> stringResource(R.string.onnx_models_provider_supertonic)
        OnnxCatalogProvider.BACKGROUND_REMOVAL -> stringResource(R.string.onnx_models_provider_bgr)
    }
}

private suspend fun importOnnxBundleFromTree(
    context: android.content.Context,
    repository: ModelRepository,
    treeUri: android.net.Uri,
    existingIds: Set<String>,
    onProgress: (Float, String) -> Unit
): Result<String> = runCatching {
    val sourceRoot = DocumentFile.fromTreeUri(context, treeUri)
        ?: error(context.getString(R.string.onnx_models_import_error_invalid_tree))

    val rawName = sourceRoot.name ?: "onnx_bundle"
    val bundleId = OnnxImportSupport.makeUniqueBundleId(
        OnnxImportSupport.sanitizeBundleId(rawName),
        existingIds
    )

    OnnxStorage.ensureManagedRootsReady(context)
    val targetDir = OnnxStorage.managedBundleDir(context, bundleId)
    OnnxImportSupport.deleteRecursively(targetDir)
    targetDir.mkdirs()
    OnnxImportSupport.copyDocumentTreeToDirectory(context, sourceRoot, targetDir) { progress ->
        onProgress(progress, context.getString(R.string.onnx_models_import_copying))
    }

    val validation = validateAnyOnnxBundle(targetDir)
    require(validation.isValid) {
        context.getString(
            R.string.onnx_models_import_error_missing_files,
            validation.missingPaths.joinToString(", ")
        )
    }

    val sizeBytes = OnnxImportSupport.recursiveSize(targetDir)
    val ttsValidation = OnnxTtsBundleValidator.validateDirectory(targetDir)
    repository.insertModel(
        if (ttsValidation.isValid) {
            ModelEntity(
                filename = bundleId,
                path = targetDir.absolutePath,
                sizeBytes = sizeBytes,
                type = ModelType.ONNX_TTS,
                repoId = "custom-import/$bundleId",
                isDownloaded = false,
                onnxCapabilities = buildOnnxCapabilities(ONNX_CAPABILITY_TTS),
                onnxAssetKind = ONNX_ASSET_KIND_CUSTOM_IMPORT_BUNDLE,
                onnxPipelineFamily = ONNX_PIPELINE_FAMILY_SUPERTONIC_TTS,
                onnxReferenceUri = treeUri.toString(),
                onnxReferencePath = null
            )
        } else {
            buildOnnxImageGenModelEntity(
                filename = bundleId,
                path = targetDir.absolutePath,
                sizeBytes = sizeBytes,
                repoId = "custom-import/$bundleId",
                installSource = OnnxInstallSource.CUSTOM_IMPORT,
                supportedCapabilities = com.example.llamadroid.onnx.OnnxBundleValidator
                    .validateDirectory(targetDir)
                    .supportedCapabilities,
                referenceUri = treeUri.toString(),
                referencePath = null
            )
        }
    )

    context.getString(R.string.onnx_models_import_success_copied, bundleId)
}
private fun validateAnyOnnxBundle(root: File): OnnxBundleValidationResult {
    val imageValidation = com.example.llamadroid.onnx.OnnxBundleValidator.validateDirectory(root)
    if (imageValidation.isValid) return imageValidation
    val ttsValidation = OnnxTtsBundleValidator.validateDirectory(root)
    return if (ttsValidation.isValid || ttsValidation.missingPaths.size < imageValidation.missingPaths.size) {
        ttsValidation
    } else {
        imageValidation
    }
}
