package com.example.llamadroid.ui.overlay

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.llamadroid.R

/**
 * Shared "Display over other apps" flow used by the desktop pet and the chat bubble.
 *
 * Invoking the returned lambda runs [onGranted] immediately when the permission is already held.
 * Otherwise it shows an explanation dialog ([rationale]), opens the system permission screen and,
 * when the user comes back (ON_RESUME) with the permission granted, runs [onGranted].
 */
@Composable
fun rememberOverlayPermissionRequest(
    @StringRes rationale: Int,
    onGranted: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    var showDialog by remember { mutableStateOf(false) }
    var awaiting by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || !awaiting) return@LifecycleEventObserver
            awaiting = false
            if (Settings.canDrawOverlays(context)) currentOnGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.tama_shimeji_permission_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(rationale))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    awaiting = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri()
                    )
                    try {
                        context.startActivity(intent)
                    } catch (_: RuntimeException) {
                        awaiting = false
                    }
                }) {
                    Text(stringResource(R.string.tama_shimeji_permission_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    return {
        if (Settings.canDrawOverlays(context)) currentOnGranted() else showDialog = true
    }
}
