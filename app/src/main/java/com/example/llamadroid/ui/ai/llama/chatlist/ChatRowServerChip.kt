package com.example.llamadroid.ui.ai.llama.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.ui.ai.llama.servers.ServerActionState
import com.example.llamadroid.ui.ai.llama.servers.ServerStatusDot
import com.example.llamadroid.ui.ai.llama.servers.effectiveServerStatus

/** Small "status dot + server name / model" chip shown on chat rows bound to a server. */
@Composable
fun ChatRowServerChip(
    server: UnifiedServer,
    action: ServerActionState?,
    modifier: Modifier = Modifier
) {
    val label = server.modelName?.takeIf { it.isNotBlank() }?.let { "${server.name} · $it" } ?: server.name
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ServerStatusDot(effectiveServerStatus(server, action), size = 8.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
