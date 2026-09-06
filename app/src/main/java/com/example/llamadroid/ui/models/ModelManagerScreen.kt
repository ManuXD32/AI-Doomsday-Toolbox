package com.example.llamadroid.ui.models

import android.os.Environment
import android.os.StatFs
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import com.example.llamadroid.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.DownloadTaskSection
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File

private const val MODEL_STORAGE_REFRESH_MILLIS = 15_000L

private fun editableModelTypeOptions(): List<ModelType> = listOf(
    ModelType.LLM,
    ModelType.LLM_DRAFT,
    ModelType.LORA,
    ModelType.EMBEDDING,
    ModelType.VISION_PROJECTOR
)

@Composable
private fun modelTypeLabel(type: ModelType): String = when (type) {
    ModelType.LLM,
    ModelType.VISION -> stringResource(R.string.models_type_llm)
    ModelType.LLM_DRAFT -> stringResource(R.string.models_type_mtp)
    ModelType.LORA -> stringResource(R.string.models_type_lora)
    ModelType.EMBEDDING -> stringResource(R.string.models_type_embedding)
    ModelType.VISION_PROJECTOR,
    ModelType.MMPROJ -> stringResource(R.string.models_type_vision_projector)
    else -> type.name
}

private data class ModelManagerCategory(
    @StringRes val labelRes: Int,
    val modelTypes: Set<ModelType>
)

private val MODEL_MANAGER_CATEGORIES = listOf(
    ModelManagerCategory(R.string.models_category_llm, setOf(ModelType.LLM, ModelType.VISION)),
    ModelManagerCategory(R.string.models_category_mtp, setOf(ModelType.LLM_DRAFT)),
    ModelManagerCategory(R.string.models_category_lora, setOf(ModelType.LORA)),
    ModelManagerCategory(R.string.models_category_embeddings, setOf(ModelType.EMBEDDING)),
    ModelManagerCategory(
        R.string.models_category_vision_projectors,
        setOf(ModelType.VISION_PROJECTOR, ModelType.MMPROJ)
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val repo = remember { ModelRepository(context, db.modelDao()) }
    val viewModel: ModelManagerViewModel = viewModel(
        factory = ModelManagerViewModelFactory(repo)
    )
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.models_tab_installed),
        stringResource(R.string.models_tab_downloading),
        stringResource(R.string.models_tab_discover)
    )
    
    val progressMap by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val installedModelCount by viewModel.installedModels.collectAsStateWithLifecycle()
    val managerDownloadTypeNames = remember {
        listOf(
            ModelType.LLM,
            ModelType.LLM_DRAFT,
            ModelType.LORA,
            ModelType.EMBEDDING,
            ModelType.VISION,
            ModelType.VISION_PROJECTOR,
            ModelType.MMPROJ
        ).map { it.name }
    }
    val managerDownloadTasks by db.downloadTaskDao()
        .observeByModelTypes(managerDownloadTypeNames)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val managerProgressKeys = remember(managerDownloadTasks) {
        managerDownloadTasks.map { it.progressKey }.toSet()
    }
    val activeDownloads = progressMap.count {
        it.key in managerProgressKeys &&
            (it.value == DownloadProgressHolder.INDETERMINATE || it.value in 0f..0.999f)
    }

    AppScreenScaffold(title = stringResource(R.string.nav_models), onBack = { navController.popBackStack() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppContentColumn(
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 8.dp,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text("$activeDownloads")
                                            }
                                        }
                                        if (index == 0 && installedModelCount.isNotEmpty()) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ) {
                                                Text(installedModelCount.size.toString())
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
                    0 -> InstalledTab(viewModel)
                    1 -> DownloadingTab(viewModel)
                    2 -> DiscoverTab(viewModel)
                }
            }
        }
    }
}

@Composable
fun InstalledTab(viewModel: ModelManagerViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val models by viewModel.installedModels.collectAsStateWithLifecycle()
    var storageSnapshot by remember(models) {
        // StatFs is cheap but still a filesystem query; initialize from metadata and let the
        // visible-route refresh perform the actual query off the main thread.
        mutableStateOf(ModelStorageSnapshot(0L, 0L, models.sumOf { it.sizeBytes.coerceAtLeast(0L) }))
    }

    LaunchedEffect(models, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                storageSnapshot = withContext(Dispatchers.IO) {
                    readModelStorageSnapshot(models)
                }
                delay(MODEL_STORAGE_REFRESH_MILLIS)
            }
        }
    }
    
    // Import state - FILE FIRST approach (FAB launches picker, then show dialog)
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedModelType by remember { mutableStateOf(ModelType.LLM) }
    var hasVisionSupport by remember { mutableStateOf(false) }
    var hasEmbeddingSupport by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingFilename by remember { mutableStateOf("") }
    
    // Import progress state
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importFileName by remember { mutableStateOf("") }
    
    // Export state
    var pendingExportModel by remember { mutableStateOf<ModelEntity?>(null) }
    
    // Edit state
    var showRenameDialog by remember { mutableStateOf(false) }
    var modelToRename by remember { mutableStateOf<ModelEntity?>(null) }
    var pendingDeleteModel by remember { mutableStateOf<ModelEntity?>(null) }
    var newModelName by remember { mutableStateOf("") }
    var editedModelType by remember { mutableStateOf(ModelType.LLM) }
    var editedVisionSupport by remember { mutableStateOf(false) }
    var useForKnowledgeEmbedding by remember { mutableStateOf(false) }
    
    // Export picker launcher
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let {
            pendingExportModel?.let { model ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val sourceFile = File(model.path)
                        if (!sourceFile.exists()) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, resources.getString(R.string.models_export_error_not_found), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        
                        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)
                        val targetFile = documentFile?.createFile("*/*", model.filename)
                        
                        if (targetFile != null) {
                            context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                                sourceFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, resources.getString(R.string.models_export_success, model.filename), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, resources.getString(R.string.models_export_failed, e.message), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        pendingExportModel = null
                    }
                }
            }
        }
    }
    
    // Export function
    val exportModel: (ModelEntity) -> Unit = { model ->
        pendingExportModel = model
        exportPicker.launch(null)
    }
    
    // File picker launcher - FAB triggers this directly
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                pendingUri = it
                // Get filename
                val cursor = context.contentResolver.query(it, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            pendingFilename = c.getString(nameIndex)
                        }
                    }
                }
                // Show import dialog after file is selected
                showImportDialog = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // Combined import dialog (type selection + capabilities)
    if (showImportDialog && pendingUri != null) {
        AlertDialog(
            onDismissRequest = { 
                showImportDialog = false
                pendingUri = null
            },
            title = { Text(stringResource(R.string.models_import)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.models_import_file_label, pendingFilename), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Model type selection
                    Text(stringResource(R.string.models_import_type_label), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val modelTypes = listOf(
                        ModelType.LLM to stringResource(R.string.models_type_llm),
                        ModelType.LLM_DRAFT to stringResource(R.string.models_type_mtp),
                        ModelType.LORA to stringResource(R.string.models_type_lora),
                        ModelType.EMBEDDING to stringResource(R.string.models_type_embedding),
                        ModelType.VISION_PROJECTOR to stringResource(R.string.models_type_vision_projector)
                    )
                    
                    modelTypes.forEach { (type, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedModelType == type,
                                    onClick = { selectedModelType = type }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedModelType == type,
                                onClick = { selectedModelType = type }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    
                    // LLM capabilities (only shown for LLM type)
                    if (selectedModelType == ModelType.LLM) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.models_import_caps_label), style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = hasVisionSupport,
                                onCheckedChange = { hasVisionSupport = it }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.models_cap_vision))
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = hasEmbeddingSupport,
                                onCheckedChange = { hasEmbeddingSupport = it }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.models_cap_embedding))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.models_import_delete_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImportDialog = false
                    val uri = pendingUri!!
                    val filename = pendingFilename
                    val type = selectedModelType
                    val vision = type == ModelType.LLM && hasVisionSupport
                    
                    // Start import with progress tracking
                    importFileName = filename
                    isImporting = true
                    importProgress = 0f
                    
                    scope.launch(Dispatchers.IO) {
                        importModelWithProgress(
                            context = context,
                            viewModel = viewModel,
                            uri = uri,
                            filename = filename,
                            type = type,
                            isVision = vision,
                            sdCaps = null,
                            onProgress = { progress ->
                                importProgress = progress
                            },
                            onComplete = {
                                isImporting = false
                            }
                        )
                    }
                    
                    // Reset
                    pendingUri = null
                    pendingFilename = ""
                    selectedModelType = ModelType.LLM
                    hasVisionSupport = false
                    hasEmbeddingSupport = false
                }) {
                    Text(stringResource(R.string.models_import))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    pendingUri = null
                    pendingFilename = ""
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Import progress dialog
    if (isImporting) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while importing */ },
            title = { Text(stringResource(R.string.models_import_title)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        importFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${(importProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.models_import_wait),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { /* No confirm button */ }
        )
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            // Keep the final row clear of the import FAB on short phones.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ModelStorageOverviewCard(storageSnapshot)
            }

            if (models.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
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
                                stringResource(R.string.models_no_models),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.models_empty_installed_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                MODEL_MANAGER_CATEGORIES.forEach { category ->
                    val categoryModels = models.filter { it.type in category.modelTypes }
                    if (categoryModels.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(category.labelRes, categoryModels.size),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                        items(categoryModels) { model ->
                            ModelCard(
                                title = model.filename,
                                subtitle = model.repoId,
                                sizeText = FormatUtils.formatFileSize(model.sizeBytes),
                                details = modelCardDetails(model),
                                actionIcon = Icons.Default.Delete,
                                actionColor = MaterialTheme.colorScheme.error,
                                onAction = { pendingDeleteModel = model },
                                onExport = { exportModel(model) },
                                onRename = {
                                    modelToRename = model
                                    newModelName = model.filename.substringBeforeLast(".")
                                    editedModelType = when (model.type) {
                                        ModelType.VISION -> ModelType.LLM
                                        ModelType.MMPROJ -> ModelType.VISION_PROJECTOR
                                        else -> model.type
                                    }
                                    editedVisionSupport = model.isVision || model.type == ModelType.VISION
                                    useForKnowledgeEmbedding = model.type == ModelType.EMBEDDING
                                    showRenameDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Edit Dialog
        if (showRenameDialog && modelToRename != null) {
            val db = remember { com.example.llamadroid.data.db.AppDatabase.getDatabase(context) }
            val settingsRepository = remember { com.example.llamadroid.data.SettingsRepository(context) }
            val selectedEmbeddingModelPath by settingsRepository.selectedEmbeddingModelPath.collectAsStateWithLifecycle()
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text(stringResource(R.string.models_edit_title)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = newModelName,
                            onValueChange = { newModelName = it },
                            label = { Text(stringResource(R.string.models_rename_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val extension = modelToRename!!.filename.substringAfterLast(".", "")
                        if (extension.isNotEmpty()) {
                            Text(
                                stringResource(R.string.models_rename_extension_info, extension),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Text(stringResource(R.string.models_import_type_label), style = MaterialTheme.typography.labelMedium)
                        editableModelTypeOptions().forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = editedModelType == type,
                                        onClick = {
                                            editedModelType = type
                                            useForKnowledgeEmbedding = type == ModelType.EMBEDDING && useForKnowledgeEmbedding
                                        }
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = editedModelType == type,
                                    onClick = {
                                        editedModelType = type
                                        useForKnowledgeEmbedding = type == ModelType.EMBEDDING && useForKnowledgeEmbedding
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(modelTypeLabel(type))
                            }
                        }
                        if (editedModelType == ModelType.EMBEDDING) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = useForKnowledgeEmbedding,
                                    onCheckedChange = { useForKnowledgeEmbedding = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.models_use_for_kb_embedding))
                            }
                        }
                        if (editedModelType == ModelType.LLM) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = editedVisionSupport,
                                    onCheckedChange = { editedVisionSupport = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.models_vision_toggle_title))
                                    Text(
                                        stringResource(R.string.models_vision_toggle_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val model = modelToRename!!
                            val extension = model.filename.substringAfterLast(".", "")
                            val fullNewName = if (extension.isNotEmpty()) "$newModelName.$extension" else newModelName
                            val cleanNewName = fullNewName.trim()
                            val typeChanged = editedModelType != model.type
                            val finalVisionSupport = editedModelType == ModelType.LLM && editedVisionSupport
                            val visionChanged = finalVisionSupport != model.isVision
                            
                            if (cleanNewName.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val oldFile = java.io.File(model.path)
                                        val renamed = cleanNewName != model.filename
                                        val finalPath = if (renamed) {
                                            if (!oldFile.exists()) {
                                                throw IllegalStateException(resources.getString(R.string.models_rename_failed))
                                            }
                                            val newFile = java.io.File(oldFile.parent, cleanNewName)
                                            if (!oldFile.renameTo(newFile)) {
                                                throw IllegalStateException(resources.getString(R.string.models_rename_failed))
                                            }
                                            newFile.absolutePath
                                        } else {
                                            model.path
                                        }

                                        if (renamed || typeChanged || visionChanged) {
                                            db.modelDao().updateMetadata(
                                                oldFilename = model.filename,
                                                newFilename = cleanNewName,
                                                newPath = finalPath,
                                                newType = editedModelType,
                                                isVision = finalVisionSupport
                                            )
                                        }

                                        if (
                                            editedModelType == ModelType.EMBEDDING &&
                                            (useForKnowledgeEmbedding || selectedEmbeddingModelPath == model.path)
                                        ) {
                                            settingsRepository.setSelectedEmbeddingModelPath(finalPath)
                                            com.example.llamadroid.data.repository.KnowledgeBaseRepository(context, db)
                                                .markIndexedSourcesStaleForCurrentConfig()
                                        } else if (
                                            editedModelType != ModelType.EMBEDDING &&
                                            selectedEmbeddingModelPath in listOf(model.path, finalPath)
                                        ) {
                                            settingsRepository.setSelectedEmbeddingModelPath(null)
                                            com.example.llamadroid.data.repository.KnowledgeBaseRepository(context, db)
                                                .markIndexedSourcesStaleForCurrentConfig()
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    resources.getString(R.string.models_kb_embedding_cleared),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }

                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(
                                                context,
                                                resources.getString(R.string.models_edit_success, cleanNewName),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, resources.getString(R.string.models_error_toast, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            showRenameDialog = false
                            modelToRename = null
                        }
                    ) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        pendingDeleteModel?.let { model ->
            AlertDialog(
                onDismissRequest = { pendingDeleteModel = null },
                title = { Text(stringResource(R.string.models_delete)) },
                text = { Text(stringResource(R.string.models_delete_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeleteModel = null
                            viewModel.deleteModel(model)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
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
        
        // FAB for import - launches file picker directly
        FloatingActionButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.models_import))
        }
    }
}

@Composable
private fun ModelStorageOverviewCard(snapshot: ModelStorageSnapshot) {
    val context = LocalContext.current
    val totalText = FormatUtils.Display.formatBytes(context, snapshot.totalBytes)
    val freeText = FormatUtils.Display.formatBytes(context, snapshot.freeBytes)
    val modelsText = FormatUtils.Display.formatBytes(context, snapshot.modelsBytes)
    val otherUsedText = FormatUtils.Display.formatBytes(context, snapshot.otherUsedBytes)
    val barDescription = stringResource(
        R.string.models_storage_bar_desc,
        totalText,
        freeText,
        modelsText
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.models_storage_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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

            if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StorageValuePill(
                        label = stringResource(R.string.models_storage_total),
                        value = totalText,
                        modifier = Modifier.fillMaxWidth()
                    )
                    StorageValuePill(
                        label = stringResource(R.string.models_storage_free),
                        value = freeText,
                        modifier = Modifier.fillMaxWidth()
                    )
                    StorageValuePill(
                        label = stringResource(R.string.models_storage_models),
                        value = modelsText,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StorageValuePill(
                        label = stringResource(R.string.models_storage_total),
                        value = totalText,
                        modifier = Modifier.weight(1f)
                    )
                    StorageValuePill(
                        label = stringResource(R.string.models_storage_free),
                        value = freeText,
                        modifier = Modifier.weight(1f)
                    )
                    StorageValuePill(
                        label = stringResource(R.string.models_storage_models),
                        value = modelsText,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SegmentedStorageBar(
                snapshot = snapshot,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = barDescription }
            )

            if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StorageLegendItem(
                        label = stringResource(R.string.models_storage_models),
                        value = modelsText,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StorageLegendItem(
                        label = stringResource(R.string.models_storage_other),
                        value = otherUsedText,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StorageLegendItem(
                        label = stringResource(R.string.models_storage_models),
                        value = modelsText,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StorageLegendItem(
                        label = stringResource(R.string.models_storage_other),
                        value = otherUsedText,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageValuePill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SegmentedStorageBar(
    snapshot: ModelStorageSnapshot,
    modifier: Modifier = Modifier
) {
    val otherUsedFraction = snapshot.otherUsedFraction
    val modelsFraction = snapshot.modelsFraction

    Box(
        modifier = modifier
            .height(14.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (otherUsedFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(otherUsedFraction)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f))
                )
            }
            if (modelsFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(modelsFraction)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                )
            }
            val freeFraction = (1f - otherUsedFraction - modelsFraction).coerceIn(0f, 1f)
            if (freeFraction > 0f) {
                Spacer(modifier = Modifier.weight(freeFraction))
            }
        }
    }
}

@Composable
private fun StorageLegendItem(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
        Text(
            stringResource(R.string.models_storage_legend_value, label, value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class ModelStorageSnapshot(
    val totalBytes: Long,
    val freeBytes: Long,
    val modelsBytes: Long
) {
    private val usedBytes: Long = (totalBytes - freeBytes).coerceIn(0L, totalBytes.coerceAtLeast(0L))
    val otherUsedBytes: Long = (usedBytes - modelsBytes).coerceAtLeast(0L)
    val modelsFraction: Float = fractionOfTotal(modelsBytes)
    val otherUsedFraction: Float = fractionOfTotal(otherUsedBytes)

    private fun fractionOfTotal(bytes: Long): Float =
        if (totalBytes > 0L) {
            (bytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

private fun readModelStorageSnapshot(models: List<ModelEntity>): ModelStorageSnapshot {
    val stats = StatFs(Environment.getDataDirectory().absolutePath)
    val totalBytes = stats.totalBytes.coerceAtLeast(0L)
    val freeBytes = stats.availableBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L))
    val modelsBytes = models.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    return ModelStorageSnapshot(
        totalBytes = totalBytes,
        freeBytes = freeBytes,
        modelsBytes = modelsBytes
    )
}

// Helper function to import model
private suspend fun importModel(
    context: android.content.Context,
    viewModel: ModelManagerViewModel,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    isVision: Boolean,
    sdCaps: String?
) {
    var tempFile: File? = null
    try {
        val db = AppDatabase.getDatabase(context)
        val repository = ModelRepository(context, db.modelDao())
        val requestedFilename = filename.ifBlank { "imported_model.gguf" }
        val targetFilename = ModelLibraryManager.canonicalFilename(requestedFilename)
        val runtimeDir = repository.getModelDir(type).apply { mkdirs() }
        val targetFile = File(runtimeDir, targetFilename)
        tempFile = File(runtimeDir, "$targetFilename.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile!!.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected model")
        replaceImportedFile(tempFile!!, targetFile)
        val finalPath = targetFile.absolutePath
        val existing = db.modelDao().getModelByFilename(targetFilename)
        if (existing != null && existing.path != finalPath) {
            repository.deleteModelArtifacts(existing)
        }
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Saved to: $finalPath")

        viewModel.importLocalModel(
            path = finalPath,
            filename = targetFilename,
            modelType = type,
            hasVision = isVision,
            hasEmbedding = false,
            sdCapabilities = sdCaps
        )
    } catch (e: Exception) {
        tempFile?.takeIf { it.exists() }?.delete()
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Error: ${e.message}")
        e.printStackTrace()
    }
}

// Helper function to import model with progress tracking
private suspend fun importModelWithProgress(
    context: android.content.Context,
    viewModel: ModelManagerViewModel,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    isVision: Boolean,
    sdCaps: String?,
    onProgress: (Float) -> Unit,
    onComplete: () -> Unit
) {
    var tempFile: File? = null
    try {
        val db = AppDatabase.getDatabase(context)
        val repository = ModelRepository(context, db.modelDao())
        val requestedFilename = filename.ifBlank { "imported_model.gguf" }
        val targetFilename = ModelLibraryManager.canonicalFilename(requestedFilename)
        val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L

        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Starting copy: $targetFilename (${fileSize / (1024 * 1024)} MB)")

        val runtimeDir = repository.getModelDir(type).apply { mkdirs() }
        val targetFile = File(runtimeDir, targetFilename)
        tempFile = File(runtimeDir, "$targetFilename.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile!!.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (totalBytesRead - lastProgressUpdate > 100_000) {
                        val progress = if (fileSize > 0) {
                            (totalBytesRead.toFloat() / fileSize).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onProgress(progress)
                        }
                        lastProgressUpdate = totalBytesRead
                    }
                }
            }
        } ?: error("Unable to open selected model")
        replaceImportedFile(tempFile!!, targetFile)
        val finalPath = targetFile.absolutePath
        val existing = db.modelDao().getModelByFilename(targetFilename)
        if (existing != null && existing.path != finalPath) {
            repository.deleteModelArtifacts(existing)
        }
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Saved to: $finalPath")

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onProgress(1f)
        }

        var layerCount = 0
        if (type == ModelType.LLM && finalPath.endsWith(".gguf")) {
            try {
                val modelInfo = com.example.llamadroid.util.GGUFParser.parse(finalPath)
                if (modelInfo != null) {
                    layerCount = modelInfo.layerCount
                    com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Detected $layerCount layers from GGUF")
                }
            } catch (e: Exception) {
                com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Failed to parse GGUF for layers: ${e.message}")
            }
        }

        viewModel.importLocalModel(
            path = finalPath,
            filename = targetFilename,
            modelType = type,
            hasVision = isVision,
            hasEmbedding = false,
            sdCapabilities = sdCaps,
            layerCount = layerCount
        )
    } catch (e: Exception) {
        tempFile?.takeIf { it.exists() }?.delete()
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Error: ${e.message}")
        e.printStackTrace()
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onComplete()
        }
    }
}

private fun replaceImportedFile(tempFile: File, targetFile: File) {
    if (targetFile.exists()) {
        targetFile.delete()
    }
    if (!tempFile.renameTo(targetFile)) {
        tempFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile.delete()
    }
}
@Composable
fun DownloadingTab(viewModel: ModelManagerViewModel) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val progressMap by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val modelTypes = remember {
        listOf(
            ModelType.LLM,
            ModelType.LLM_DRAFT,
            ModelType.LORA,
            ModelType.EMBEDDING,
            ModelType.VISION,
            ModelType.VISION_PROJECTOR,
            ModelType.MMPROJ
        )
    }
    val managerDownloadTypeNames = remember(modelTypes) { modelTypes.map { it.name } }
    val storedManagerTasks by db.downloadTaskDao()
        .observeByModelTypes(managerDownloadTypeNames)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val managerProgressKeys = remember(storedManagerTasks) {
        storedManagerTasks.map { it.progressKey }.toSet()
    }
    val activeDownloads = progressMap.filter {
        it.key in managerProgressKeys &&
            (it.value == DownloadProgressHolder.INDETERMINATE || it.value in 0f..0.999f)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DownloadTaskSection(
                // The exact model-type query keeps Whisper and its separate VAD
                // assets out without relying on task-id naming conventions.
                modelTypes = modelTypes,
                includeTask = { task -> task.modelType in modelTypes.map { it.name } }
            )
        }

        if (activeDownloads.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.models_no_downloads),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.models_downloading_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            items(activeDownloads.toList()) { (repoId, progress) ->
                val isIndeterminate = progress == DownloadProgressHolder.INDETERMINATE
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    repoId.substringAfterLast("/"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    repoId.substringBeforeLast("/", ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                if (isIndeterminate) {
                                    stringResource(R.string.models_downloading)
                                } else {
                                    "${(progress * 100).toInt()}%"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            // Keep the compact action below the model identifier so a
                            // long repository name never squeezes the cancel affordance.
                            IconButton(
                                onClick = {
                                    val filename = DownloadProgressHolder.getFilename(repoId)
                                    if (filename != null) {
                                        DownloadService.cancelDownload(context, filename, repoId)
                                    }
                                    DownloadProgressHolder.removeProgress(repoId)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isIndeterminate) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverTab(viewModel: ModelManagerViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val repoVisionCache by viewModel.repoVisionCache.collectAsStateWithLifecycle()
    
    val selectedRepoId by viewModel.selectedRepoId.collectAsStateWithLifecycle()
    val availableFiles by viewModel.availableFiles.collectAsStateWithLifecycle()
    val hasVisionSupport by viewModel.hasVisionSupport.collectAsStateWithLifecycle()
    val visionFiles by viewModel.visionFiles.collectAsStateWithLifecycle()
    val showVisionPrompt by viewModel.showVisionPrompt.collectAsStateWithLifecycle()
    val pendingVisionDownload by viewModel.pendingVisionDownload.collectAsStateWithLifecycle()
    
    // Vision projector download prompt
    if (showVisionPrompt && pendingVisionDownload != null) {
        val (repoId, visionFile) = pendingVisionDownload!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissVisionPrompt() },
            icon = {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { 
                Text(
                    stringResource(R.string.models_vision_detected),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.models_vision_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.models_vision_download_ask),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    visionFile.filename,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    visionFile.formattedSize(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.downloadVisionProjector() }) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissVisionPrompt() }) {
                    Text(stringResource(R.string.action_skip))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
    
    // State for auto-download mmproj checkbox
    var downloadMmproj by remember { mutableStateOf(true) }
    
    // Reset checkbox when repo changes
    LaunchedEffect(selectedRepoId) {
        downloadMmproj = true
    }

    // File selection dialog
    if (selectedRepoId != null && availableFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSelection() },
            title = { 
                Column {
                    Text(
                        stringResource(R.string.models_select_quantization),
                        fontWeight = FontWeight.Bold
                    )
                    // Show vision support badge and mmproj checkbox
                    if (hasVisionSupport) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.models_vision_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // Checkbox to opt-in/out of mmproj download
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = downloadMmproj,
                                onCheckedChange = { downloadMmproj = it }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.models_download_vision),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    visionFiles.firstOrNull()?.filename
                                        ?: stringResource(R.string.responsive_models_mmproj_file),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            text = {
                LazyColumn {
                    item {
                        Text(
                            stringResource(R.string.models_model_files_section),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    items(availableFiles) { fileInfo ->
                        Card(
                            onClick = {
                                viewModel.downloadModel(selectedRepoId!!, fileInfo.filename, ModelType.LLM)
                                // Auto-download mmproj if checkbox is checked
                                if (hasVisionSupport && downloadMmproj && visionFiles.isNotEmpty()) {
                                    viewModel.downloadVisionProjector()
                                }
                                // Close dialog but keep vision state for prompt after download
                                viewModel.closeFileSelectionDialog()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    fileInfo.filename,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    fileInfo.formattedSize(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (visionFiles.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.models_vision_files_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                            )
                        }
                        items(visionFiles) { fileInfo ->
                            Card(
                                onClick = {
                                    viewModel.downloadModel(selectedRepoId!!, fileInfo.filename, ModelType.VISION_PROJECTOR)
                                    viewModel.closeFileSelectionDialog()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            fileInfo.filename,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            stringResource(R.string.models_download_mmproj_only),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                        )
                                    }
                                    Text(
                                        fileInfo.formattedSize(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSelection() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        if (value != query) {
                            query = value
                            viewModel.clearSearchResults()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.models_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.isNotBlank() && !isSearching) {
                                viewModel.search(query, ModelType.LLM)
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                FilledIconButton(
                    onClick = { viewModel.search(query, ModelType.LLM) },
                    enabled = query.isNotBlank() && !isSearching,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.models_search_hint))
                }
            }
        }
        
        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (com.example.llamadroid.ui.components.isCuratedCatalogBrowseMode(query)) {
                item(key = "phase_c_llama_curated_bundles") {
                    val context = LocalContext.current
                    val settings = remember { com.example.llamadroid.data.SettingsRepository(context) }
                    com.example.llamadroid.ui.components.CuratedModelBundleSection(
                        title = stringResource(R.string.phase_c_llama_bundles_title),
                        description = stringResource(R.string.llama_bundles_desc),
                        bundles = com.example.llamadroid.data.model.LlamaCuratedBundleCatalog.bundles,
                        onUseBundle = { _, models, _ ->
                            models.firstOrNull { it.type == ModelType.LLM }?.let { settings.setSelectedModelPath(it.path) }
                            models.firstOrNull { it.type == ModelType.VISION_PROJECTOR }?.let {
                                settings.setSelectedMmprojPath(it.path)
                                settings.setEnableVision(true)
                            }
                            models.firstOrNull { it.type == ModelType.LLM_DRAFT }?.let {
                                settings.setDraftModelPath(it.path)
                                settings.setSpeculativeMode(com.example.llamadroid.service.LlamaSpeculativeMode.DRAFT_MTP)
                            }
                        }
                    )
                }
            }

            items(results) { hfModel ->
                val progress = progressMap[hfModel.id]
                val isDownloading = progress != null && progress < 1f
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    hfModel.id.substringAfterLast("/"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    hfModel.id.substringBeforeLast("/", ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!isDownloading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilledTonalIconButton(
                                    onClick = { viewModel.selectRepoForDownload(hfModel.id) }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.desc_download))
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "${hfModel.downloads}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Vision badge - use cache if available, fall back to name pattern
                            val cachedVision = repoVisionCache[hfModel.id]
                            val modelNameLower = hfModel.id.lowercase()
                            val hasVisionByName = modelNameLower.contains("llava") || 
                                modelNameLower.contains("vision") ||
                                modelNameLower.contains("-vl") ||
                                modelNameLower.contains("vlm") ||
                                modelNameLower.contains("visual") ||
                                modelNameLower.contains("pixtral") ||
                                modelNameLower.contains("qwen2-vl") ||
                                modelNameLower.contains("minicpm-v")
                            
                            // Show badge if either cache confirms vision OR name suggests it (while API checks)
                            val showVisionBadge = cachedVision == true || (cachedVision == null && hasVisionByName)
                            
                            if (showVisionBadge) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        if (cachedVision == true) stringResource(R.string.models_vision_badge) else "${stringResource(R.string.models_vision_badge)}?",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        
                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress!! },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Text(
                                stringResource(R.string.whisper_downloading_progress, (progress!! * 100).toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelCard(
    title: String,
    subtitle: String,
    sizeText: String,
    details: List<String> = emptyList(),
    actionIcon: ImageVector,
    actionColor: Color,
    onAction: () -> Unit,
    onExport: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                // Keep identifiers readable while bounding unusually long repository
                // names so the action row remains reachable on narrow phones.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // On its own line rather than appended after the repo id: when the
                    // two shared a 2-line ellipsized row, a long repo id pushed the
                    // size out of the card entirely.
                    Text(
                        sizeText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    details.forEach { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
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
                onRename?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Default.Edit, stringResource(R.string.models_rename_title), tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                onExport?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Default.Share, stringResource(R.string.action_share), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onAction) {
                    Icon(actionIcon, null, tint = actionColor)
                }
            }
        }
    }
}

@Composable
private fun modelCardDetails(model: ModelEntity): List<String> = buildList {
    add(stringResource(R.string.models_metadata_type, modelTypeLabel(model.type)))

    if (model.type == ModelType.LLM || model.type == ModelType.VISION) {
        add(
            stringResource(
                if (model.type == ModelType.VISION || model.isVision) {
                    R.string.models_metadata_vision_enabled
                } else {
                    R.string.models_metadata_vision_not_enabled
                }
            )
        )
    }

    model.mmprojPath
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?.let { projector ->
            add(stringResource(R.string.models_metadata_projector, projector))
        }

    if (model.type == ModelType.VISION_PROJECTOR || model.type == ModelType.MMPROJ) {
        add(stringResource(R.string.models_metadata_projector_file))
    }

    if (model.layerCount > 0 && (model.type == ModelType.LLM || model.type == ModelType.VISION)) {
        add(stringResource(R.string.models_metadata_layers, model.layerCount))
    }
}
