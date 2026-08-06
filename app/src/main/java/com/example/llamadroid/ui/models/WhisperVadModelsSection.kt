package com.example.llamadroid.ui.models

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
        if (WhisperVadAssetStore.isVerifiedCatalogModel(target, spec)) {
            select(target)
            refresh += 1
            return
        }
        if (target.exists()) target.delete()
        val id = whisperVadDownloadId(spec)
        DownloadProgressHolder.updateProgress(id, spec.filename, 0f)
        DownloadService.startDownload(
            context = context,
            url = spec.downloadUrl,
            destPath = target.absolutePath,
            filename = spec.filename,
            downloadId = id
        )
    }

    fun cancelDownload(spec: WhisperVadModelSpec) {
        val id = whisperVadDownloadId(spec)
        DownloadService.cancelDownload(
            context = context,
            filename = spec.filename,
            downloadId = id
        )
        DownloadProgressHolder.removeProgress(id)
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
                    if (WhisperVadAssetStore.isVerifiedCatalogModel(target, spec)) {
                        if (selectedPath == null) select(target)
                        refresh += 1
                    } else {
                        target.delete()
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

    Column(modifier = modifier.fillMaxWidth()) {
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
        Spacer(modifier = Modifier.height(8.dp))

        WhisperVadModelCatalog.models.forEach { spec ->
            val file = installed.firstOrNull { it.name.equals(spec.filename, ignoreCase = true) }
            val progress = progressMap[whisperVadDownloadId(spec)]
            WhisperVadCatalogModelRow(
                spec = spec,
                installedFile = file,
                selected = file?.absolutePath == selectedPath,
                progress = progress,
                onDownload = { startDownload(spec) },
                onCancel = { cancelDownload(spec) },
                onSelect = { file?.let(::select) },
                onExport = {
                    file?.let {
                        pendingExport = it
                        exportLauncher.launch(it.name)
                    }
                },
                onDelete = { file?.let { pendingDelete = it } }
            )
        }

        installed
            .filter { file -> WhisperVadModelCatalog.byFilename(file.name) == null }
            .forEach { file ->
                WhisperVadImportedModelRow(
                    file = file,
                    selected = file.absolutePath == selectedPath,
                    onSelect = { select(file) },
                    onExport = {
                        pendingExport = file
                        exportLauncher.launch(file.name)
                    },
                    onDelete = { pendingDelete = file }
                )
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

@Composable
private fun WhisperVadCatalogModelRow(
    spec: WhisperVadModelSpec,
    installedFile: File?,
    selected: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val downloading = progress != null && progress != 1f && progress != -1f
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
                Text(
                    spec.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    FormatUtils.Display.formatBytes(context, spec.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                downloading -> {
                    if (progress == DownloadProgressHolder.INDETERMINATE) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { progress?.coerceIn(0f, 1f) ?: 0f },
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                installedFile != null -> {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.whisper_vad_selected),
                            tint = Color(0xFF4CAF50)
                        )
                    } else {
                        IconButton(onClick = onSelect) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.action_select)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.action_export),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.desc_download)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WhisperVadImportedModelRow(
    file: File,
    selected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
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
                Text(
                    file.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    FormatUtils.Display.formatBytes(context, file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.whisper_vad_selected),
                    tint = Color(0xFF4CAF50)
                )
            } else {
                IconButton(onClick = onSelect) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.action_select)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.action_export),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun whisperVadDownloadId(spec: WhisperVadModelSpec): String =
    "whisper_vad_${spec.id}"
