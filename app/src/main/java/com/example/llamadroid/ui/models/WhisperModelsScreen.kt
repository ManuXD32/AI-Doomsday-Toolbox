package com.example.llamadroid.ui.models

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelBackupPolicy
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.service.WhisperModel
import com.example.llamadroid.ui.components.DownloadTaskSection
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whisper Models management screen
 * Allows downloading/deleting WhisperCPP models
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperModelsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    
    // Downloaded models from database
    val downloadedModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val modelByFilename = remember(downloadedModels) { downloadedModels.associateBy { it.filename } }
    val catalogFilenames = remember { WhisperModel.values().map { it.filename }.toSet() }
    val importedExtraModels = remember(downloadedModels, catalogFilenames) {
        downloadedModels.filter { model ->
            model.repoId == ModelBackupPolicy.LOCAL_IMPORT_REPO_ID &&
                model.filename !in catalogFilenames
        }
    }
    
    // Download progress from DownloadProgressHolder (survives tab switches)
    val downloadProgressMap by DownloadProgressHolder.progress.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importFileName by remember { mutableStateOf("") }
    var pendingExportModel by remember { mutableStateOf<ModelEntity?>(null) }
    
    // Models directory
    val modelsDir = remember { repository.getModelDir(ModelType.WHISPER).apply { mkdirs() } }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val model = pendingExportModel
        pendingExportModel = null
        if (treeUri != null && model != null) {
            scope.launch {
                exportWhisperModel(context, model, treeUri)
                    .onSuccess { filename ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.models_export_success, filename),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.models_export_failed,
                                error.message ?: context.getString(R.string.error_generic)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val filename = resolveDisplayName(context, uri)
            importFileName = filename
            importProgress = 0f
            isImporting = true
            scope.launch {
                importWhisperModel(
                    context = context,
                    db = db,
                    uri = uri,
                    filename = filename,
                    onProgress = { progress -> importProgress = progress }
                ).onSuccess { result ->
                    Toast.makeText(
                        context,
                        context.getString(
                            if (result.didCopy) {
                                R.string.whisper_import_success_copied
                            } else {
                                R.string.whisper_import_success_linked
                            },
                            result.filename
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.whisper_import_failed,
                            error.message ?: context.getString(R.string.error_generic)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                isImporting = false
            }
        }
    }
    
    // Helper to start download via service
    fun startModelDownload(model: WhisperModel) {
        val destPath = File(modelsDir, model.filename).absolutePath
        val repoId = "ggerganov/whisper.cpp"
        val progressKey = "whisper_${model.filename}"
        // Register pending download for DB tracking
        PendingDownloadHolder.addPending(
            downloadId = progressKey,
            filename = model.filename,
            repoId = repoId,
            progressKey = progressKey,
            type = ModelType.WHISPER,
            destPath = destPath
        )
        // Track progress
        DownloadProgressHolder.updateProgress(progressKey, model.filename, 0f)
        // Start service
        DownloadService.startDownload(context, model.downloadUrl, destPath, model.filename, progressKey)
    }

    fun exportModel(model: ModelEntity) {
        pendingExportModel = model
        exportPicker.launch(null)
    }

    if (isImporting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.models_import_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        importFileName,
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
                }
            },
            confirmButton = { }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whisper_models_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kiwix_back))
                    }
                },
                actions = {
                    IconButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.whisper_import_from_storage)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.whisper_models_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.whisper_models_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.models_import_delete_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Error message
            errorMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (importedExtraModels.isNotEmpty()) {
                ImportedWhisperModelsSection(
                    models = importedExtraModels,
                    onDelete = { model ->
                        scope.launch {
                            repository.deleteModel(model)
                        }
                    }
                )
            }

            DownloadTaskSection(
                modelTypes = listOf(ModelType.WHISPER),
                includeTask = { it.id.startsWith("whisper_") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            // Model categories
            WhisperModelCategory(
                title = stringResource(R.string.whisper_category_tiny),
                models = listOf(WhisperModel.TINY, WhisperModel.TINY_EN, WhisperModel.TINY_Q5_1, WhisperModel.TINY_EN_Q5_1),
                modelByFilename = modelByFilename,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onExport = ::exportModel,
                onDelete = { model ->
                    scope.launch {
                        repository.deleteModel(model)
                    }
                }
            )
            
            WhisperModelCategory(
                title = stringResource(R.string.whisper_category_base),
                models = listOf(WhisperModel.BASE, WhisperModel.BASE_EN, WhisperModel.BASE_Q5_1, WhisperModel.BASE_EN_Q5_1),
                modelByFilename = modelByFilename,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onExport = ::exportModel,
                onDelete = { model -> scope.launch { repository.deleteModel(model) } }
            )
            
            WhisperModelCategory(
                title = stringResource(R.string.whisper_category_small),
                models = listOf(WhisperModel.SMALL, WhisperModel.SMALL_EN, WhisperModel.SMALL_Q5_1, WhisperModel.SMALL_EN_Q5_1),
                modelByFilename = modelByFilename,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onExport = ::exportModel,
                onDelete = { model -> scope.launch { repository.deleteModel(model) } }
            )
            
            WhisperModelCategory(
                title = stringResource(R.string.whisper_category_medium),
                models = listOf(WhisperModel.MEDIUM, WhisperModel.MEDIUM_EN, WhisperModel.MEDIUM_Q5_0, WhisperModel.MEDIUM_EN_Q5_0),
                modelByFilename = modelByFilename,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onExport = ::exportModel,
                onDelete = { model -> scope.launch { repository.deleteModel(model) } }
            )
            
            WhisperModelCategory(
                title = stringResource(R.string.whisper_category_large),
                models = listOf(WhisperModel.LARGE_V3, WhisperModel.LARGE_V3_TURBO, WhisperModel.LARGE_V3_Q5_0, WhisperModel.LARGE_V3_TURBO_Q5_0),
                modelByFilename = modelByFilename,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onExport = ::exportModel,
                onDelete = { model -> scope.launch { repository.deleteModel(model) } }
            )
        }
    }
}

@Composable
private fun WhisperModelCategory(
    title: String,
    models: List<WhisperModel>,
    modelByFilename: Map<String, ModelEntity>,
    downloadProgressMap: Map<String, Float>,
    onDownload: (WhisperModel) -> Unit,
    onExport: (ModelEntity) -> Unit,
    onDelete: (ModelEntity) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))
    
    models.forEach { model ->
        val installedModel = modelByFilename[model.filename]
        val isDownloaded = installedModel != null
        // Check if any progress entry contains this filename
        val progressEntry = downloadProgressMap.entries.find { it.key.contains(model.filename) }
        val isDownloading = progressEntry != null && progressEntry.value in 0f..0.99f
        val downloadProgress = progressEntry?.value ?: 0f
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.displayName,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (model.isEnglishOnly) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.secondary) { 
                                Text(stringResource(R.string.whisper_lang_en), style = MaterialTheme.typography.labelSmall) 
                            }
                        }
                        if (model.isQuantized) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.tertiary) { 
                                Text(stringResource(R.string.whisper_quant_q), style = MaterialTheme.typography.labelSmall) 
                            }
                        }
                        if (installedModel?.isDownloaded == false) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    stringResource(R.string.whisper_imported_badge),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Text(
                        model.sizeDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                when {
                    isDownloading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                "${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    isDownloaded -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(
                                    if (installedModel?.isDownloaded == true) {
                                        R.string.desc_downloaded
                                    } else {
                                        R.string.whisper_imported_badge
                                    }
                                ),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            installedModel?.takeIf { it.isDownloaded }?.let { downloadedModel ->
                                IconButton(onClick = { onExport(downloadedModel) }) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = stringResource(R.string.whisper_export_model),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { installedModel?.let(onDelete) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.desc_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    else -> {
                        IconButton(onClick = { onDownload(model) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.desc_download))
                        }
                    }
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun ImportedWhisperModelsSection(
    models: List<ModelEntity>,
    onDelete: (ModelEntity) -> Unit
) {
    Text(
        stringResource(R.string.whisper_imported_models_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))

    models.forEach { model ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.filename,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                stringResource(R.string.whisper_imported_badge),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.whisper_imported_model_subtitle,
                            FormatUtils.formatFileSize(model.sizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { onDelete(model) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.whisper_remove_imported_model),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
}

private data class WhisperImportResult(
    val filename: String,
    val didCopy: Boolean
)

private fun resolveDisplayName(
    context: android.content.Context,
    uri: Uri
): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) return name.substringAfterLast("/")
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast("/")?.ifBlank { null }
        ?: "imported_whisper_model.bin"
}

private suspend fun importWhisperModel(
    context: android.content.Context,
    db: AppDatabase,
    uri: Uri,
    filename: String,
    onProgress: (Float) -> Unit
): Result<WhisperImportResult> = withContext(Dispatchers.IO) {
    var tempFile: File? = null
    runCatching {
        val repository = ModelRepository(context, db.modelDao())
        val requestedFilename = filename.ifBlank { "imported_whisper_model.bin" }
        val targetFilename = ModelLibraryManager.canonicalFilename(requestedFilename)
        val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L
        val runtimeDir = repository.getModelDir(ModelType.WHISPER).apply { mkdirs() }
        val targetFile = File(runtimeDir, targetFilename)
        tempFile = File(runtimeDir, "$targetFilename.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile!!.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var bytesRead = input.read(buffer)
                while (bytesRead >= 0) {
                    output.write(buffer, 0, bytesRead)
                    copied += bytesRead
                    if (fileSize > 0L) {
                        withContext(Dispatchers.Main) {
                            onProgress((copied.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    bytesRead = input.read(buffer)
                }
            }
        } ?: error(context.getString(R.string.whisper_import_failed_open_input))
        replaceImportedWhisperFile(tempFile!!, targetFile)
        val finalPath = targetFile.absolutePath

        withContext(Dispatchers.Main) { onProgress(1f) }
        val existing = db.modelDao().getModelByFilename(targetFilename)
        if (existing != null && existing.path != finalPath) {
            repository.deleteModelArtifacts(existing)
        }

        val finalFile = File(finalPath)
        val sizeBytes = if (finalFile.exists()) finalFile.length() else 0L
        db.modelDao().insertModel(
            ModelEntity(
                filename = targetFilename,
                path = finalPath,
                sizeBytes = sizeBytes,
                type = ModelType.WHISPER,
                repoId = ModelBackupPolicy.LOCAL_IMPORT_REPO_ID,
                isDownloaded = false
            )
        )

        WhisperImportResult(filename = targetFilename, didCopy = true)
    }.onFailure {
        tempFile?.takeIf { file -> file.exists() }?.delete()
    }
}

private fun replaceImportedWhisperFile(tempFile: File, targetFile: File) {
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

private suspend fun exportWhisperModel(
    context: android.content.Context,
    model: ModelEntity,
    treeUri: Uri
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val sourceFile = File(model.path)
        if (!sourceFile.exists()) {
            error(context.getString(R.string.models_export_error_not_found))
        }

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error(context.getString(R.string.whisper_export_error_create_file))
        val targetFile = root.createFile("application/octet-stream", model.filename)
            ?: error(context.getString(R.string.whisper_export_error_create_file))

        context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error(context.getString(R.string.whisper_export_error_create_file))

        model.filename
    }
}
