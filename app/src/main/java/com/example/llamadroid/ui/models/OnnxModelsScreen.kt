package com.example.llamadroid.ui.models

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.ui.components.DownloadTaskSection
import com.example.llamadroid.ui.components.AppPageBackground
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

private const val ONNX_IMPORT_SOURCE_MEMBER_LIMIT = 512

private data class OnnxImportSourceMember(
    val relativePath: String
)

private data class OnnxImportSourceListing(
    val members: List<OnnxImportSourceMember>,
    val truncated: Boolean
)

private data class OnnxImportResult(
    val bundleId: String,
    val message: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnnxModelsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val sourceRepository = rememberModelSourceRepository(context)
    val savedSources by sourceRepository.sources.collectAsState(initial = emptyList())
    val sourceProvenance by sourceRepository.provenance.collectAsState(initial = emptyList())
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
        onnxDownloads.filterValues { it == DownloadProgressHolder.INDETERMINATE || it in 0f..0.999f }
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
    var pendingImportTreeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImportName by remember { mutableStateOf("") }
    var importSourceUrl by remember { mutableStateOf("") }
    var importSourceLabel by remember { mutableStateOf("") }
    var importSourceError by remember { mutableStateOf<String?>(null) }
    var importSourceMembers by remember { mutableStateOf<List<OnnxImportSourceMember>>(emptyList()) }
    var importSourceMembersTruncated by remember { mutableStateOf(false) }
    var selectedImportSourceMember by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceAsset by remember { mutableStateOf<com.example.llamadroid.data.model.library.InstalledModelAsset?>(null) }
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
        pendingImportTreeUri = treeUri
        pendingImportName = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: resources.getString(R.string.onnx_models_import_bundle)
        importSourceUrl = ""
        importSourceLabel = ""
        importSourceError = null
        selectedImportSourceMember = null
    }

    LaunchedEffect(pendingImportTreeUri) {
        val treeUri = pendingImportTreeUri
        if (treeUri == null) {
            importSourceMembers = emptyList()
            importSourceMembersTruncated = false
            selectedImportSourceMember = null
        } else {
            val listing = withContext(Dispatchers.IO) {
                listOnnxImportSourceMembers(context, treeUri)
            }
            importSourceMembers = listing.members
            importSourceMembersTruncated = listing.truncated
            if (selectedImportSourceMember !in listing.members.map { it.relativePath }) {
                selectedImportSourceMember = null
            }
        }
    }

    pendingImportTreeUri?.let { treeUri ->
        val invalidSourceText = stringResource(R.string.model_source_invalid_link)
        val directoryMappingText = stringResource(R.string.onnx_directory_mapping_required)
        AlertDialog(
            onDismissRequest = {
                pendingImportTreeUri = null
                importSourceUrl = ""
                importSourceLabel = ""
                importSourceError = null
                selectedImportSourceMember = null
            },
            title = { Text(stringResource(R.string.onnx_import_options_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.onnx_import_options_desc, pendingImportName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OptionalModelSourceFields(
                        family = ModelFamily.ONNX,
                        url = importSourceUrl,
                        onUrlChange = {
                            importSourceUrl = it
                            importSourceError = null
                        },
                        label = importSourceLabel,
                        onLabelChange = { importSourceLabel = it },
                        error = importSourceError
                    )
                    Text(
                        directoryMappingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (importSourceUrl.trim().isNotEmpty()) {
                        Text(
                            stringResource(R.string.onnx_import_source_mapping_heading),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(R.string.onnx_import_source_mapping_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (importSourceMembers.isEmpty()) {
                            Text(
                                stringResource(R.string.onnx_import_source_member_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                stringResource(R.string.onnx_import_source_member_choose),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (importSourceMembersTruncated) {
                                Text(
                                    stringResource(R.string.onnx_import_source_member_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            importSourceMembers.forEach { member ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedImportSourceMember = member.relativePath }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = selectedImportSourceMember == member.relativePath,
                                        onClick = { selectedImportSourceMember = member.relativePath }
                                    )
                                    Text(
                                        member.relativePath,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sourceDraft = optionalModelSourceDraft(
                        ModelFamily.ONNX,
                        importSourceUrl,
                        importSourceLabel
                    )
                    if (sourceDraft.isFailure) {
                        importSourceError = invalidSourceText
                        return@TextButton
                    }
                    if (sourceDraft.getOrNull() != null) {
                        if (selectedImportSourceMember == null) {
                            importSourceError = resources.getString(R.string.onnx_import_source_member_required)
                            return@TextButton
                        }
                    }
                    val selectedTreeUri = treeUri
                    val selectedSourceDraft = sourceDraft.getOrNull()
                    val selectedSourceMember = selectedImportSourceMember
                    pendingImportTreeUri = null
                    importSourceUrl = ""
                    importSourceLabel = ""
                    importSourceError = null
                    selectedImportSourceMember = null
                    scope.launch(Dispatchers.IO) {
                        isImporting = true
                        importProgress = 0f
                        importLabel = resources.getString(R.string.onnx_models_importing)
                        val result = importOnnxBundleFromTree(
                            context = context,
                            repository = repository,
                            treeUri = selectedTreeUri,
                            existingIds = onnxModels.map { it.filename }.toSet(),
                            onProgress = { progress, label ->
                                importProgress = progress
                                importLabel = label
                            }
                        )
                        val sourceResult = if (result.isSuccess &&
                            selectedSourceDraft != null && selectedSourceMember != null
                        ) {
                            val imported = result.getOrThrow()
                            val importedModel = db.modelDao().getModelByFilename(imported.bundleId)
                            val memberFile = importedModel?.let {
                                resolveImportedOnnxMember(it, selectedSourceMember)
                            }
                            if (importedModel != null && memberFile != null) {
                                val baseAsset = installedAssetForModel(importedModel)
                                attachModelSource(
                                    sourceRepository,
                                    ModelSourceAttachmentRequest(
                                        asset = baseAsset.copy(
                                            displayName = "${baseAsset.displayName}/$selectedSourceMember",
                                            path = memberFile.absolutePath,
                                            filename = memberFile.name
                                        ),
                                        newSource = selectedSourceDraft,
                                        role = baseAsset.role
                                    )
                                )
                            } else {
                                Result.failure(IllegalStateException("ONNX source member was not copied"))
                            }
                        } else {
                            null
                        }
                        isImporting = false
                        withContext(Dispatchers.Main) {
                            sourceResult?.onFailure {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.model_source_save_failed, resources.getString(R.string.error_generic)),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            Toast.makeText(
                                context,
                                result.fold(
                                    onSuccess = { it.message },
                                    onFailure = { it.message ?: resources.getString(R.string.error_generic) }
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImportTreeUri = null
                    importSourceUrl = ""
                    importSourceLabel = ""
                    importSourceError = null
                    selectedImportSourceMember = null
                }) { Text(stringResource(R.string.action_cancel)) }
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
                        .onSuccess {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.model_source_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .onFailure { error ->
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
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { treePicker.launch(null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.onnx_models_import_bundle))
            }
        }
    ) { innerPadding ->
        AppPageBackground(modifier = Modifier.padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.walkthroughTarget("back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    Text(
                        stringResource(R.string.onnx_models_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                    IconButton(
                        onClick = { navController.navigate("${Screen.ModelSources.route}?family=ONNX&tab=download") },
                        modifier = Modifier.walkthroughTarget("models.download")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.model_library_custom_download_heading))
                    }
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

            ModelManagerShortcutRow(
                navController = navController,
                family = ModelFamily.ONNX,
                modifier = Modifier.padding(horizontal = 16.dp)
            )


            AppScrollableTabRow(selectedTabIndex = selectedTab) {
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
                    onDeleteRequest = { pendingDeleteModel = it },
                    onSourceRequest = { sourceAsset = installedAssetForModel(it) }
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
                            resources.getString(R.string.onnx_models_download_started, entry.title),
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
    onDeleteRequest: (ModelEntity) -> Unit,
    onSourceRequest: (ModelEntity) -> Unit
) {
    val storage = com.example.llamadroid.ui.components.rememberModelStorageInventory()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { com.example.llamadroid.ui.components.ModelStorageOverviewCard(storage, "onnx") }
        if (models.isEmpty()) item {
            Text(stringResource(R.string.onnx_models_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                        Row {
                            IconButton(onClick = { onSourceRequest(model) }) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = stringResource(R.string.model_source_attach_title),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            OutlinedButton(onClick = { onDeleteRequest(model) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_delete))
                            }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DownloadTaskSection(
                modelTypes = listOf(
                    ModelType.ONNX_IMAGE_GEN,
                    ModelType.ONNX_TTS,
                    ModelType.ONNX_BACKGROUND_REMOVAL,
                    ModelType.ONNX_IMAGE_UPSCALER
                ),
                includeTask = { it.id.startsWith("onnx:") },
                artifactFamily = com.example.llamadroid.data.model.library.ModelFamily.ONNX
            )
        }
        if (items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.onnx_models_downloading_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
                .background(accentColor.copy(alpha = 0.06f))
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
): Result<OnnxImportResult> = runCatching {
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

    OnnxImportResult(
        bundleId = bundleId,
        message = context.getString(R.string.onnx_models_import_success_copied, bundleId)
    )
}

private fun listOnnxImportSourceMembers(
    context: android.content.Context,
    treeUri: android.net.Uri
): OnnxImportSourceListing {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return OnnxImportSourceListing(emptyList(), false)
    val collected = mutableListOf<OnnxImportSourceMember>()

    fun visit(directory: DocumentFile, prefix: String) {
        if (collected.size > ONNX_IMPORT_SOURCE_MEMBER_LIMIT) return
        val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
            .sortedBy { it.name.orEmpty().lowercase() }
        for (child in children) {
            if (collected.size > ONNX_IMPORT_SOURCE_MEMBER_LIMIT) return
            val name = child.name?.trim().orEmpty()
            if (name.isBlank() || name == "." || name == ".." || name.contains('/')) continue
            val relative = if (prefix.isBlank()) name else "$prefix/$name"
            when {
                child.isDirectory -> visit(child, relative)
                child.isFile -> collected += OnnxImportSourceMember(relative)
            }
        }
    }

    visit(root, "")
    return OnnxImportSourceListing(
        members = collected.take(ONNX_IMPORT_SOURCE_MEMBER_LIMIT),
        truncated = collected.size > ONNX_IMPORT_SOURCE_MEMBER_LIMIT
    )
}

private fun resolveImportedOnnxMember(model: ModelEntity, relativePath: String): File? {
    val normalized = relativePath.trim()
    if (normalized.isBlank() || File(normalized).isAbsolute) return null
    val segments = normalized.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." || '\\' in it }) {
        return null
    }
    val root = runCatching { File(model.path).canonicalFile }.getOrNull() ?: return null
    if (!root.isDirectory) return null
    val candidate = runCatching { File(root, normalized).canonicalFile }.getOrNull() ?: return null
    if (!candidate.path.startsWith(root.path + File.separator)) return null
    return candidate.takeIf { it.isFile }
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
