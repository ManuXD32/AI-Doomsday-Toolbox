package com.example.llamadroid.ui.filestation

import androidx.core.net.toUri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.llamadroid.R
import com.example.llamadroid.service.QrCodeSupport
import com.example.llamadroid.service.filestation.FileStationSettings
import com.example.llamadroid.service.filestation.VolumeConfig
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.SettingsToggle
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class VolumeDialogState(val volume: VolumeConfig, val isNew: Boolean)
private enum class PickTarget { ROOT, EXTRA }

/** File Station home: status, folders, access and advanced settings in one scrolling list. */
@Composable
fun FileStationScreen(onBack: () -> Unit, viewModel: FileStationViewModel = viewModel()) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val settings by viewModel.settings.collectAsState()
    val running by viewModel.running.collectAsState()
    val urls by viewModel.urls.collectAsState()
    val activeJobs by viewModel.activeJobs.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val applied by viewModel.applied.collectAsState()
    val portDraft by viewModel.portDraft.collectAsState()
    val portError by viewModel.portError.collectAsState()
    val ffmpeg by viewModel.ffmpegAvailable.collectAsState()

    var pickTarget by remember { mutableStateOf(PickTarget.ROOT) }
    var volumeDialog by remember { mutableStateOf<VolumeDialogState?>(null) }
    var passwordDialog by remember { mutableStateOf<PasswordDialogMode?>(null) }
    var qrExpanded by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }.onFailure {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        when (pickTarget) {
            PickTarget.ROOT -> viewModel.setRootFolder(uri.toString())
            PickTarget.EXTRA -> volumeDialog = VolumeDialogState(viewModel.newExtra(uri.toString()), isNew = true)
        }
    }

    AppScreenScaffold(
        title = stringResource(R.string.fs_title),
        subtitle = stringResource(R.string.fs_subtitle),
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "status") {
                AppSectionCard {
                    StatusSection(
                        running = running, hasRoot = settings.rootVolume != null, urls = urls,
                        activeJobs = activeJobs, lastError = lastError, qrExpanded = qrExpanded,
                        restartNeeded = viewModel.restartNeeded(settings, applied),
                        onToggleQr = { qrExpanded = !qrExpanded },
                        onStartStop = {
                            walkthroughTargets?.recordEvent("file_server.folder")
                            if (running) viewModel.stop() else viewModel.start()
                        }
                    )
                }
            }
            if (settings.rootVolume == null) {
                item(key = "warn_root") {
                    AppStatePanel(
                        kind = AppStateKind.Interrupted,
                        title = stringResource(R.string.fs_warn_no_root_title),
                        message = stringResource(R.string.fs_warn_no_root_message)
                    )
                }
            }
            if (settings.unprotected) {
                item(key = "warn_open") {
                    AppStatePanel(
                        kind = AppStateKind.Interrupted,
                        title = stringResource(R.string.fs_warn_open_title),
                        message = stringResource(R.string.fs_warn_open_message)
                    )
                }
            }
            item(key = "root") {
                AppSectionCard(modifier = Modifier.walkthroughTarget("file_server.folder")) {
                    RootSection(
                        root = settings.rootVolume,
                        onPick = {
                            walkthroughTargets?.recordEvent("file_server.folder")
                            pickTarget = PickTarget.ROOT
                            folderPicker.launch(null)
                        },
                        onEdit = { root -> volumeDialog = VolumeDialogState(root, isNew = false) },
                        onReadOnly = viewModel::setRootReadOnly
                    )
                }
            }
            item(key = "extra") {
                AppSectionCard {
                    ExtraSection(
                        volumes = settings.extraVolumes,
                        onAdd = { pickTarget = PickTarget.EXTRA; folderPicker.launch(null) },
                        onEdit = { volumeDialog = VolumeDialogState(it, isNew = false) },
                        onRemove = { viewModel.removeVolume(it.id) },
                        onReadOnly = { v, ro -> viewModel.saveVolume(v.copy(readOnly = ro)) }
                    )
                }
            }
            item(key = "access") {
                AppSectionCard {
                    AccessSection(
                        settings = settings, portDraft = portDraft, portError = portError,
                        onLan = viewModel::setLanVisible, onPort = viewModel::onPortDraft,
                        onPassword = { passwordDialog = it },
                        onSignOut = {
                            viewModel.signOutEveryone()
                            Toast.makeText(context, R.string.fs_signed_out, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            item(key = "advanced") {
                AppAdvancedSection(title = stringResource(R.string.fs_advanced_title)) {
                    AdvancedSection(
                        settings = settings, ffmpeg = ffmpeg,
                        onTrashDays = viewModel::setTrashDays, onHidden = viewModel::setShowHidden
                    )
                }
            }
        }
    }

    volumeDialog?.let { state ->
        FileStationVolumeDialog(
            volume = state.volume, isNew = state.isNew,
            onDismiss = { volumeDialog = null },
            onSave = { viewModel.saveVolume(it); volumeDialog = null }
        )
    }
    passwordDialog?.let { mode ->
        FileStationPasswordDialog(
            mode = mode,
            verifyCurrent = viewModel::verifyPassword,
            onDismiss = { passwordDialog = null },
            onSet = { viewModel.setPassword(it); passwordDialog = null },
            onRemove = { viewModel.clearPassword(); passwordDialog = null }
        )
    }
}

@Composable
private fun SectionTitle(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun StatusSection(
    running: Boolean,
    hasRoot: Boolean,
    urls: List<Pair<String, String>>,
    activeJobs: Int,
    lastError: String?,
    qrExpanded: Boolean,
    restartNeeded: Boolean,
    onToggleQr: () -> Unit,
    onStartStop: () -> Unit,
) {
    val context = LocalContext.current
    SectionTitle(R.string.fs_status_title)
    Text(
        stringResource(if (running) R.string.fs_status_running else R.string.fs_status_stopped),
        style = MaterialTheme.typography.bodyMedium,
        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        onClick = onStartStop,
        enabled = running || hasRoot,
        modifier = Modifier.fillMaxWidth(),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors()
    ) {
        Icon(if (running) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = null)
        Text(
            stringResource(if (running) R.string.fs_stop else R.string.fs_start),
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (!running && !hasRoot) {
        Text(
            stringResource(R.string.fs_start_needs_folder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (restartNeeded) {
        Text(
            stringResource(R.string.fs_restart_to_apply),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
    if (running && activeJobs > 0) {
        Text(
            pluralStringResource(R.plurals.fs_active_jobs, activeJobs, activeJobs),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (!lastError.isNullOrBlank()) {
        Text(
            stringResource(R.string.fs_last_error, lastError),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (running && urls.isNotEmpty()) {
        urls.forEach { (name, url) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("File Station", url))
                    Toast.makeText(context, R.string.fs_url_copied, Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.fs_url_copy)) }
                IconButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Icon(Icons.Default.OpenInBrowser, contentDescription = stringResource(R.string.fs_url_open)) }
            }
        }
        TextButton(onClick = onToggleQr) {
            Text(stringResource(if (qrExpanded) R.string.fs_qr_hide else R.string.fs_qr_show))
        }
        if (qrExpanded) QrRow(urls)
    }
}

@Composable
private fun QrRow(urls: List<Pair<String, String>>) {
    val bitmaps = remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
    LaunchedEffect(urls) {
        bitmaps.value = withContext(Dispatchers.Default) { urls.associate { (_, url) -> url to runCatching { QrCodeSupport.qrBitmap(url, 200) }.getOrNull() } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        urls.forEach { (name, url) ->
            val ip = url.substringAfter("://").substringBefore(":")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmaps.value[url]?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.dashboard_qr_for, ip),
                        modifier = Modifier.size(128.dp)
                    )
                }
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RootSection(
    root: VolumeConfig?,
    onPick: () -> Unit,
    onEdit: (VolumeConfig) -> Unit,
    onReadOnly: (Boolean) -> Unit,
) {
    SectionTitle(R.string.fs_root_title)
    if (root == null) {
        Text(stringResource(R.string.fs_root_none), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(root.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pathTail(root.treeUri), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onEdit(root) }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.fs_edit_label))
            }
        }
        SettingsToggle(
            title = stringResource(R.string.fs_read_only_title),
            description = stringResource(R.string.fs_read_only_desc),
            checked = root.readOnly,
            onCheckedChange = onReadOnly
        )
    }
    OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(if (root == null) R.string.fs_choose_folder else R.string.fs_change_folder),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExtraSection(
    volumes: List<VolumeConfig>,
    onAdd: () -> Unit,
    onEdit: (VolumeConfig) -> Unit,
    onRemove: (VolumeConfig) -> Unit,
    onReadOnly: (VolumeConfig, Boolean) -> Unit,
) {
    SectionTitle(R.string.fs_extra_title)
    if (volumes.isEmpty()) {
        Text(stringResource(R.string.fs_extra_empty), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    volumes.forEach { v ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(v.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pathTail(v.treeUri), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.fs_read_only_badge), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Switch(checked = v.readOnly, onCheckedChange = { onReadOnly(v, it) })
            IconButton(onClick = { onEdit(v) }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.fs_edit))
            }
            IconButton(onClick = { onRemove(v) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.fs_remove))
            }
        }
    }
    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.fs_add_folder), modifier = Modifier.padding(start = 8.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccessSection(
    settings: FileStationSettings,
    portDraft: String,
    portError: PortError?,
    onLan: (Boolean) -> Unit,
    onPort: (String) -> Unit,
    onPassword: (PasswordDialogMode) -> Unit,
    onSignOut: () -> Unit,
) {
    SectionTitle(R.string.fs_access_title)
    SettingsToggle(
        title = stringResource(R.string.fs_lan_title),
        description = stringResource(R.string.fs_lan_desc),
        checked = settings.lanVisible,
        onCheckedChange = onLan
    )
    OutlinedTextField(
        value = portDraft,
        onValueChange = onPort,
        label = { Text(stringResource(R.string.fs_port_label)) },
        singleLine = true,
        isError = portError != null,
        supportingText = portError?.let {
            { Text(stringResource(if (it == PortError.RANGE) R.string.fs_port_error_range else R.string.fs_port_error_busy)) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        stringResource(R.string.fs_password_title),
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1
    )
    Text(
        stringResource(if (settings.hasPassword) R.string.fs_password_is_set else R.string.fs_password_none),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (settings.hasPassword) {
        OutlinedButton(onClick = { onPassword(PasswordDialogMode.CHANGE) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.fs_password_change), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { onPassword(PasswordDialogMode.REMOVE) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.fs_password_remove), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = { onPassword(PasswordDialogMode.SET) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.fs_password_set), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.fs_sign_out_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdvancedSection(
    settings: FileStationSettings,
    ffmpeg: Boolean,
    onTrashDays: (String) -> Unit,
    onHidden: (Boolean) -> Unit,
) {
    var trashDraft by remember { mutableStateOf(settings.trashRetentionDays.toString()) }
    OutlinedTextField(
        value = trashDraft,
        onValueChange = { trashDraft = it.filter(Char::isDigit).take(4); onTrashDays(trashDraft) },
        label = { Text(stringResource(R.string.fs_trash_days)) },
        supportingText = { Text(stringResource(R.string.fs_trash_days_desc)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    SettingsToggle(
        title = stringResource(R.string.fs_show_hidden),
        description = stringResource(R.string.fs_show_hidden_desc),
        checked = settings.showHidden,
        onCheckedChange = onHidden
    )
    Text(stringResource(R.string.fs_ffmpeg_title), style = MaterialTheme.typography.titleSmall, maxLines = 1)
    Text(
        stringResource(if (ffmpeg) R.string.fs_ffmpeg_available else R.string.fs_ffmpeg_missing),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
