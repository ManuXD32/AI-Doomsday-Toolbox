package com.example.llamadroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.vision.VisionHelperSettings
import java.io.File

/**
 * Card for the (global) vision helper, shown in the "Helper models" sheet next to title generation: describes images in text for chat models without
 * vision. Only managed local server cards whose profile has a model and an existing mmproj are offered.
 */
@Composable
fun VisionHelperSettingsCard() {
    val context = LocalContext.current
    val settings = remember { VisionHelperSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var serverId by remember { mutableStateOf(settings.serverRowId) }
    var thinking by remember { mutableStateOf(settings.thinking) }
    var showPicker by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<LlamaServerCardEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        cards = runCatching { ManagedLlamaServerCoordinator(context).catalog() }.getOrDefault(emptyList())
            .filter { info ->
                val profile = info.profile
                profile != null && File(profile.modelPath).isFile &&
                    !profile.mmprojPath.isNullOrBlank() && File(profile.mmprojPath).isFile
            }
            .map { it.card }
            .sortedBy { it.name.lowercase() }
    }
    val selectedName = cards.firstOrNull { it.id == serverId }?.name

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.vision_helper_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.vision_helper_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.vision_helper_switch),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Switch(
                    checked = enabled,
                    enabled = serverId > 0L,
                    onCheckedChange = {
                        enabled = it
                        settings.enabled = it
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.vision_helper_server_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        selectedName ?: stringResource(R.string.vision_helper_server_none),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { showPicker = true }) {
                    Text(
                        stringResource(
                            if (serverId <= 0L) R.string.vision_helper_choose_server
                            else R.string.vision_helper_change_server
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (serverId <= 0L) {
                Text(
                    stringResource(R.string.vision_helper_needs_server),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.vision_helper_thinking),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Switch(
                    checked = thinking,
                    onCheckedChange = {
                        thinking = it
                        settings.thinking = it
                    }
                )
            }
            Text(
                stringResource(R.string.vision_helper_ram_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.vision_helper_picker_title)) },
            text = {
                if (cards.isEmpty()) {
                    Text(stringResource(R.string.vision_helper_picker_empty))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(cards, key = { it.id }) { card ->
                            Text(
                                card.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (card.id == serverId) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        serverId = card.id
                                        settings.serverRowId = card.id
                                        showPicker = false
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}
