package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus

@Composable
private fun serverCardContainer(status: UnifiedServerStatus) = when (status) {
    UnifiedServerStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    UnifiedServerStatus.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
}

/** Primary line of a server's model: the model file, or the engine for remote servers without one. */
private fun UnifiedServer.modelLine(): String =
    modelName?.takeIf { it.isNotBlank() } ?: engine

/**
 * Compact card (about 220dp wide) for a horizontal servers strip: name, model, status dot with
 * label and ONE Start/Stop button. Remote servers show no button. Tapping the card calls [onClick]
 * (callers open [ServerDetailSheet]).
 */
@Composable
fun UnifiedServerCard(
    server: UnifiedServer,
    action: ServerActionState?,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = effectiveServerStatus(server, action)
    Card(
        // Strip cards keep a fixed width; callers can still add padding or semantics.
        modifier = modifier.width(220.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = serverCardContainer(status))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = server.modelLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ServerStatusDot(status)
                Text(
                    text = serverStatusLabel(server, action),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            ServerStartStopButton(server, action, onStart, onStop, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The single Start/Stop control shared by the card, the row and the sheet. Renders nothing for
 * remote servers. While a start is in flight it becomes a Stop button that cancels the start.
 */
@Composable
fun ServerStartStopButton(
    server: UnifiedServer,
    action: ServerActionState?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (server.kind == UnifiedServerKind.REMOTE) return
    val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    if (server.canStop(action)) {
        OutlinedButton(
            onClick = onStop,
            modifier = modifier.height(36.dp),
            contentPadding = padding
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.llama_card_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Button(
            onClick = onStart,
            modifier = modifier.height(36.dp),
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors()
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.llama_card_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Full-width list version of [UnifiedServerCard] for the Servers screen. */
@Composable
fun UnifiedServerRow(
    server: UnifiedServer,
    action: ServerActionState?,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = effectiveServerStatus(server, action)
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = serverCardContainer(status))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ServerStatusDot(status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = server.modelLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOf(
                        serverStatusLabel(server, action),
                        server.endpointLabel(),
                        stringResource(serverKindLabelRes(server.kind))
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (server.boundChatCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.srv_bound_chats, server.boundChatCount, server.boundChatCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (server.kind != UnifiedServerKind.REMOTE) {
                if (server.canStop(action)) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.llama_card_stop))
                    }
                } else {
                    IconButton(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.llama_card_start))
                    }
                }
            }
        }
    }
}

fun serverKindLabelRes(kind: UnifiedServerKind): Int = when (kind) {
    UnifiedServerKind.LOCAL_MANAGED -> R.string.srv_kind_managed
    UnifiedServerKind.LOCAL_LEGACY -> R.string.srv_kind_legacy
    UnifiedServerKind.REMOTE -> R.string.srv_kind_remote
}
