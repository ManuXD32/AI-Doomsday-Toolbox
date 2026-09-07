package com.example.llamadroid.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A foreground launch check only; no alarms, background scheduling or payment tracking. */
@Composable
fun DailySupportPrompt(settings: SettingsRepository, eligible: Boolean, launchId: Int = 0) {
    var foregroundDay by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val linkError = stringResource(R.string.support_link_unavailable)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foregroundDay = LocalDate.now().toEpochDay()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(launchId, eligible) {
        foregroundDay = LocalDate.now().toEpochDay()
        if (!eligible) visible = false
    }
    LaunchedEffect(eligible, foregroundDay) {
        if (!eligible) {
            visible = false
            return@LaunchedEffect
        }
        if (withContext(Dispatchers.IO) { settings.claimDailySupportPrompt(foregroundDay) }) visible = true
    }
    if (visible && eligible) {
        SupportAppDialog(
            onDismiss = { visible = false },
            onSupportLink = { url ->
                try {
                    uriHandler.openUri(url)
                    visible = false
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, linkError, Toast.LENGTH_LONG).show()
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, linkError, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@Composable
internal fun SupportAppDialog(onDismiss: () -> Unit, onSupportLink: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FavoriteBorder, contentDescription = null) },
        title = { Text(stringResource(R.string.support_daily_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.support_daily_body))
                FilledTonalButton(
                    onClick = { onSupportLink(SupportLinks.KOFI) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.about_kofi)) }
                FilledTonalButton(
                    onClick = { onSupportLink(SupportLinks.PAYPAL) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.about_paypal)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.support_not_now))
            }
        }
    )
}
