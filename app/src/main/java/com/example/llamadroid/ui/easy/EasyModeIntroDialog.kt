package com.example.llamadroid.ui.easy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.easy.EasyModeSettings

/**
 * One-time intro. Both choices mark it seen; "Try Easy mode" also turns Easy mode on. Reopening
 * from the Easy settings sheet passes [onDone] to hide it again.
 */
@Composable
fun EasyModeIntroDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { EasyModeSettings(context) }
    AlertDialog(
        onDismissRequest = { settings.markIntroSeen(); onDone() },
        title = { Text(stringResource(R.string.easy_mode_shell_intro_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.easy_mode_shell_intro_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.easy_mode_shell_intro_hint),
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { settings.markIntroSeen(); settings.enabled = true; onDone() }) {
                Text(stringResource(R.string.easy_mode_shell_intro_try), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            TextButton(onClick = { settings.markIntroSeen(); settings.enabled = false; onDone() }) {
                Text(stringResource(R.string.easy_mode_shell_intro_keep), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}
