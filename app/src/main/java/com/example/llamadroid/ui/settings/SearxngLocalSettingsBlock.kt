package com.example.llamadroid.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.service.search.SearxngLocalInstance
import com.example.llamadroid.service.search.SearxngLocalState
import kotlinx.coroutines.delay

/** Experimental in-app SearXNG controls, placed inside the web-search settings card. */
@Composable
fun SearxngLocalSettingsBlock() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SearxngLocalInstance.register(context) }
    val state by SearxngLocalInstance.state.collectAsState()
    val error by SearxngLocalInstance.error.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }

    LaunchedEffect(showLog) {
        while (showLog) {
            log = SearxngLocalInstance.readLog()
            delay(2_000)
        }
    }

    val stateLabel = when (state) {
        SearxngLocalState.NOT_INSTALLED -> R.string.searxng_local_state_not_installed
        SearxngLocalState.INSTALLING -> R.string.searxng_local_state_installing
        SearxngLocalState.STOPPED -> R.string.searxng_local_state_stopped
        SearxngLocalState.STARTING -> R.string.searxng_local_state_starting
        SearxngLocalState.RUNNING -> R.string.searxng_local_state_running
        SearxngLocalState.FAILED -> R.string.searxng_local_state_failed
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.searxng_local_title), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.searxng_local_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(stringResource(stateLabel), style = MaterialTheme.typography.bodyMedium)
        if (state == SearxngLocalState.FAILED) {
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        when (state) {
            SearxngLocalState.NOT_INSTALLED, SearxngLocalState.FAILED ->
                Button(onClick = { confirmInstall = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.searxng_local_install), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            SearxngLocalState.STOPPED ->
                Button(onClick = { SearxngLocalInstance.start() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.searxng_local_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            SearxngLocalState.RUNNING, SearxngLocalState.STARTING ->
                OutlinedButton(onClick = { SearxngLocalInstance.stop() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.searxng_local_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            SearxngLocalState.INSTALLING -> Unit
        }
        if (state == SearxngLocalState.STOPPED || state == SearxngLocalState.RUNNING || state == SearxngLocalState.FAILED) {
            OutlinedButton(onClick = { confirmUninstall = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.searxng_local_uninstall), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = { showLog = !showLog }) {
            Text(
                stringResource(if (showLog) R.string.searxng_local_hide_log else R.string.searxng_local_show_log),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showLog) {
            val clipLabel = stringResource(R.string.searxng_local_log_clip_label)
            val copiedMsg = stringResource(R.string.searxng_local_log_copied)
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, log))
                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                },
                enabled = log.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.searxng_local_copy_log), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
            ) {
                // Bounded height + own scroll state: scrolls independently inside the settings LazyColumn item.
                val scroll = rememberScrollState()
                LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
                Text(
                    log.ifBlank { stringResource(R.string.searxng_local_log_empty) },
                    modifier = Modifier.verticalScroll(scroll).padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text(stringResource(R.string.searxng_local_install_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.searxng_local_install_message))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmInstall = false; SearxngLocalInstance.install() }) {
                    Text(stringResource(R.string.searxng_local_install_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false }) { Text(stringResource(R.string.searxng_local_cancel)) }
            }
        )
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.searxng_local_uninstall_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.searxng_local_uninstall_message))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmUninstall = false; SearxngLocalInstance.uninstall() }) {
                    Text(stringResource(R.string.searxng_local_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.searxng_local_cancel)) }
            }
        )
    }
}
