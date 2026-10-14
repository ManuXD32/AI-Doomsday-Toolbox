package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/**
 * Opt-outs for the locked-screen speed features of a local llama-server (all default ON in
 * `LlamaServerLaunchProfile`): thread fit, high thread priority and Android performance hints.
 * Place it inside the editor's scrolling column; it has no scroll state of its own.
 */
@Composable
fun LockedScreenPerformanceOptions(
    threadFit: Boolean,
    threadPriority: Boolean,
    performanceHints: Boolean,
    onChange: (threadFit: Boolean, threadPriority: Boolean, performanceHints: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.locked_screen_options_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(R.string.locked_screen_options_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OptionRow(
            title = stringResource(R.string.locked_screen_option_thread_fit),
            description = stringResource(R.string.locked_screen_option_thread_fit_desc),
            checked = threadFit,
            onCheckedChange = { onChange(it, threadPriority, performanceHints) }
        )
        OptionRow(
            title = stringResource(R.string.locked_screen_option_priority),
            description = stringResource(R.string.locked_screen_option_priority_desc),
            checked = threadPriority,
            onCheckedChange = { onChange(threadFit, it, performanceHints) }
        )
        OptionRow(
            title = stringResource(R.string.locked_screen_option_hints),
            description = stringResource(R.string.locked_screen_option_hints_desc),
            checked = performanceHints,
            onCheckedChange = { onChange(threadFit, threadPriority, it) }
        )
        Text(
            stringResource(R.string.locked_screen_restart_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
