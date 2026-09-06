package com.example.llamadroid.ui.dashboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.llamadroid.R
import com.example.llamadroid.service.FileServerService
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compact Home access to the file server; detailed sharing stays collapsed until requested. */
@Composable
fun DashboardFileServerCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var fileServerService by remember { mutableStateOf<FileServerService?>(null) }
    var fileServerBound by remember { mutableStateOf(false) }
    var fileServerRunning by remember { mutableStateOf(false) }
    var fileServerUrls by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var fileServerFolderUri by remember { mutableStateOf<Uri?>(null) }
    var qrExpanded by remember { mutableStateOf(false) }
    var qrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                fileServerService = (binder as? FileServerService.LocalBinder)?.getService()
                fileServerBound = fileServerService != null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                fileServerService = null
                fileServerBound = false
            }
        }
    }

    fun bindIfNeeded() {
        if (fileServerBound) return
        fileServerBound = runCatching {
            context.bindService(Intent(context, FileServerService::class.java), connection, 0)
        }.getOrDefault(false)
    }

    fun unbind() {
        if (fileServerBound) runCatching { context.unbindService(connection) }
        fileServerBound = false
        fileServerService = null
        fileServerRunning = false
        fileServerUrls = emptyList()
        qrBitmaps = emptyMap()
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bindIfNeeded()
                Lifecycle.Event.ON_STOP -> unbind()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) bindIfNeeded()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbind()
        }
    }

    LaunchedEffect(fileServerService, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            fileServerService?.let { service ->
                launch { service.isRunning.collect { fileServerRunning = it } }
                launch {
                    service.serverUrls.collect { urls ->
                        fileServerUrls = urls
                        qrBitmaps = withContext(Dispatchers.Default) {
                            urls.associate { (_, url) ->
                                url.substringAfter("://").substringBefore(":") to generateFileServerQr(url)
                            }
                        }
                    }
                }
            }
        }
    }

    qrBitmaps.values.forEach { bitmap ->
        RecycleFileServerQrOnDispose(bitmap)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            fileServerFolderUri = it
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (fileServerRunning) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.soft_studio_home_file_server),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        stringResource(R.string.soft_studio_home_file_server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (fileServerRunning) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.dashboard_shared_folder),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        fileServerFolderUri?.lastPathSegment
                            ?: stringResource(R.string.soft_studio_home_file_server_folder_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !fileServerRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.soft_studio_home_file_server_choose_folder))
                }
            }

            Button(
                onClick = {
                    if (fileServerRunning) {
                        fileServerService?.stopServer()
                    } else {
                        val folder = fileServerFolderUri ?: return@Button
                        context.startForegroundService(Intent(context, FileServerService::class.java))
                        scope.launch {
                            repeat(8) {
                                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                                bindIfNeeded()
                                fileServerService?.let { service ->
                                    service.startServer(folder, FileServerService.DEFAULT_PORT)
                                    return@launch
                                }
                                kotlinx.coroutines.delay(250L)
                            }
                        }
                    }
                },
                enabled = fileServerFolderUri != null || fileServerRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = if (fileServerRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    if (fileServerRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (fileServerRunning) {
                        stringResource(R.string.soft_studio_home_file_server_stop)
                    } else {
                        stringResource(R.string.soft_studio_home_file_server_start)
                    }
                )
            }

            if (fileServerRunning && fileServerUrls.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.soft_studio_home_file_server_running_at, fileServerUrls.first().second),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { qrExpanded = !qrExpanded }) {
                        Icon(
                            if (qrExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(
                                if (qrExpanded) R.string.soft_studio_home_file_server_hide_qr
                                else R.string.soft_studio_home_file_server_show_qr
                            )
                        )
                    }
                }
                if (qrExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        fileServerUrls.forEach { (interfaceName, url) ->
                            val ip = url.substringAfter("://").substringBefore(":")
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    qrBitmaps[ip]?.let { bitmap ->
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = stringResource(R.string.dashboard_qr_for, ip),
                                            modifier = Modifier.size(112.dp).padding(8.dp)
                                        )
                                    } ?: Box(
                                        modifier = Modifier.size(112.dp),
                                        contentAlignment = Alignment.Center
                                    ) { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
                                }
                                Text(interfaceName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecycleFileServerQrOnDispose(bitmap: Bitmap?) {
    bitmap ?: return
    DisposableEffect(bitmap) {
        onDispose {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

private fun generateFileServerQr(content: String): Bitmap? {
    return runCatching {
        val size = 200
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
        }
    }.getOrNull()
}
