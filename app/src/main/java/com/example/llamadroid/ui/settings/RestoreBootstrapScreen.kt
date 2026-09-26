package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.RestoreCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal scrollable bootstrap surface; the ordinary app container is not initialized yet. */
@Composable
internal fun RestoreBootstrapScreen(
    install: () -> RestoreCoordinator.InstallResult,
    restart: () -> Unit
) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<RestoreCoordinator.InstallResult?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = withContext(Dispatchers.IO) {
            runCatching { install() }.getOrDefault(RestoreCoordinator.InstallResult.RECOVERY_BLOCKED)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.backup_restore_bootstrap_title), style = MaterialTheme.typography.headlineSmall)
        val message = when (result) {
            null -> R.string.backup_restore_installing
            RestoreCoordinator.InstallResult.RESTORED -> R.string.backup_restore_installed
            RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK -> R.string.backup_restore_rolled_back
            RestoreCoordinator.InstallResult.RETRY_BUSY -> R.string.backup_restore_busy
            RestoreCoordinator.InstallResult.RECOVERY_BLOCKED -> R.string.backup_restore_recovery_blocked
        }
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        when (result) {
            RestoreCoordinator.InstallResult.RESTORED,
            RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK ->
                Button(onClick = restart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_restore_continue))
                }
            RestoreCoordinator.InstallResult.RETRY_BUSY,
            RestoreCoordinator.InstallResult.RECOVERY_BLOCKED ->
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_restore_retry))
                }
            null -> Unit
        }
    }
}
