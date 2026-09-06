package com.example.llamadroid.ui.dashboard

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.KiwixService
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the Kiwix start/stop action reachable from Home while the manager owns
 * catalog, import, sharing, and recovery details.
 */
@Composable
fun DashboardKiwixCard(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val database = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val installedZims by database.zimDao().getAllZims()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var service by remember { mutableStateOf<KiwixService?>(null) }
    var bound by remember { mutableStateOf(false) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? KiwixService.LocalBinder)?.getService()
                bound = service != null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                bound = false
            }
        }
    }

    fun bindIfNeeded() {
        if (bound) return
        bound = runCatching {
            context.bindService(Intent(context, KiwixService::class.java), connection, 0)
        }.getOrDefault(false)
    }

    fun unbind() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
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
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            bindIfNeeded()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbind()
        }
    }

    val running = service?.isRunning?.collectAsStateWithLifecycle()?.value ?: false

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navController.navigate(Screen.ZimManager.route) },
        shape = com.example.llamadroid.ui.components.AppChromeDefaults.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.kiwix_server),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (installedZims.isEmpty()) {
                            stringResource(R.string.dashboard_offline_wikipedia)
                        } else {
                            stringResource(R.string.dashboard_zim_installed, installedZims.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = {
                    if (running) {
                        service?.stopServer()
                    } else {
                        if (installedZims.isEmpty()) return@Button
                        context.startForegroundService(Intent(context, KiwixService::class.java))
                        scope.launch {
                            repeat(8) {
                                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    return@launch
                                }
                                bindIfNeeded()
                                service?.let { current ->
                                    current.startServer(installedZims.map { it.path })
                                    return@launch
                                }
                                delay(250L)
                            }
                        }
                    }
                },
                enabled = installedZims.isNotEmpty() || running,
                modifier = Modifier.fillMaxWidth(),
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    imageVector = if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (running) stringResource(R.string.kiwix_stop) else stringResource(R.string.kiwix_start))
            }

            if (installedZims.isNotEmpty()) {
                OutlinedButton(
                    onClick = { navController.navigate(Screen.KiwixViewer.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_view), maxLines = 1)
                }
            }

            OutlinedButton(
                onClick = { navController.navigate(Screen.ZimManager.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.soft_studio_library_open_offline), maxLines = 1)
            }
        }
    }
}
