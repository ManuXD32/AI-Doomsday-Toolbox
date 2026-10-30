package com.example.llamadroid.ui.easy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.easy.EasyModeSettings

/** Compact labelled switch that flips the app-wide Easy mode flag. */
@Composable
fun EasyModeToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { EasyModeSettings(context) }
    val enabled by settings.enabledFlow.collectAsState()
    val description = stringResource(R.string.easy_mode_shell_toggle_description)
    Row(
        modifier = modifier.testTag("easy_mode_toggle").semantics(mergeDescendants = true) {
            role = Role.Switch
            contentDescription = description
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(R.string.easy_mode_shell_toggle_label),
            modifier = Modifier.widthIn(max = 56.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = enabled, onCheckedChange = { settings.enabled = it })
    }
}
