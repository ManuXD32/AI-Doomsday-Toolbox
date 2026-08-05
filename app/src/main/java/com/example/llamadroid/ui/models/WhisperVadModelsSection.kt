package com.example.llamadroid.ui.models

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.service.WhisperVadAssetStore
import com.example.llamadroid.service.WhisperVadModelCatalog
import com.example.llamadroid.service.WhisperVadModelSpec
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps the small VAD assets separate from transcription-model rows and Room. */
@Composable
fun WhisperVadModelsSection(
    settingsRepo: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by settingsRepo.whisperVadConfig.collectAsState()
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val installed = remember(refresh, progressMap) {
        WhisperVadAssetStore.installedModels(context)
    }
    val selectedPath = WhisperVadAssetStore.resolvePath(context, config.modelPath)
    var importing by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    fun select(file: File) {
        settingsRepo.setWhisperVadConfig(
            config.copy(modelPath = file.absolutePath).normalized()
        )
    }

    fun startDownload(spec: WhisperVadModelSpec) {
        val target = WhisperVadAssetStore.targetFile(context, spec.filename)
        if (WhisperVadAssetStore.isReadableModel(target)) {
            select(target)
            refresh += 1
            return
        }
        val id = whisperVadDownloadId(spec)
        DownloadProgressHolder.updateProgress(id, spec.filename, 0f)
        DownloadService.startDownload(
            context,
            spec.downloadUrl,
            target.absolutePath,
            spec.filename,
            id
        )
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                runCatching {
                    WhisperVadAssetStore.importModel(
                        context,
                        uri,
                        uri.lastPathSegment
                            ?.substringAfterLast('/')
                            ?.substringAfterLast(':')
                            ?: "whisper-vad.bin"
                    )
                }.onSuccess { file ->
                    select(file)
                    refresh += 1
                    Toast.makeText(
                        context,
                        context.getString(R.string.whisper_vad_import_success, file.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.whisper_vad_import_failed,
                            error.message ?: context.getString(R.string.error_generic)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                importing = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri != null && file != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Unable to open export destination")
                    }
                }.onSuccess {
                    Toast.makeText(
                        context,
                        context.getString(R.string.models_export_success, file.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
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

    LaunchedEffect(progressMap) {
        WhisperVadModelCatalog.models.forEach { spec ->
            val id = whisperVadDownloadId(spec)
            when (progressMap[id]) {
                1f -> {
                    val target = WhisperVadAssetStore.targetFile(context, spec.filename)
                    if (WhisperVadAssetStore.isReadableModel(target)) {
                        if (selectedPath == null) select(target)
                        refresh += 1
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.whisper_vad_download_failed,
                                spec.displayName
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    DownloadProgressHolder.removeProgress(id)
                }
                -1f -> {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.whisper_vad_download_failed,
                            spec.displayName
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    DownloadProgressHolder.removeProgress(id)
                }
            }
        }
    }

    val active = WhisperVadModelCatalog.models.firstNotNullOfOrNull { spec ->
        val progress = progressMap[whisperVadDownloadId(spec)]
            ?: return@firstNotNullOfOrNull null
        if (progress < 1f && progress != -1f) spec to progress else null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.whisper_vad_models_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.whisper_vad_models_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            active?.let { (spec, progress) ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(spec.displayName, style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                if (progress == DownloadProgressHolder.INDETERMINATE) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (installed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                installed.forEach { file ->
                    val catalog = WhisperVadModelCatalog.byFilename(file.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                catalog?.displayName ?: file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                FormatUtils.Display.formatBytes(context, file.length()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = selectedPath == file.absolutePath,
                            onClick = { select(file) },
                            label = {
                                Text(
                                    if (selectedPath == file.absolutePath) {
                                        stringResource(R.string.whisper_vad_selected)
                                    } else {
                                        stringResource(R.string.action_select)
                                    }
                                )
                            }
                        )
                        IconButton(
                            onClick = {
                                pendingExport = file
                                exportLauncher.launch(file.name)
                            }
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.action_export)
                            )
                        }
                        IconButton(onClick = { pendingDelete = file }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            WhisperVadModelCatalog.models
                .filter { spec -> installed.none { it.name.equals(spec.filename, true) } }
                .forEach { spec ->
                    OutlinedButton(
                        onClick = { startDownload(spec) },
                        enabled = active == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (spec.recommended) {
                                    R.string.whisper_vad_download_recommended
                                } else {
                                    R.string.whisper_vad_download_compatibility
                                }
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

            TextButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.whisper_vad_import))
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.whisper_vad_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.whisper_vad_delete_confirmation,
                        file.name
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (WhisperVadAssetStore.deleteModel(context, file.absolutePath)) {
                            val replacement = if (selectedPath == file.absolutePath) {
                                WhisperVadAssetStore
                                    .installedModels(context)
                                    .firstOrNull()
                                    ?.absolutePath
                            } else {
                                selectedPath
                            }
                            settingsRepo.setWhisperVadConfig(
                                config.copy(modelPath = replacement).normalized()
                            )
                            refresh += 1
                        }
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun whisperVadDownloadId(spec: WhisperVadModelSpec): String =
    "whisper_vad_${spec.id}"
