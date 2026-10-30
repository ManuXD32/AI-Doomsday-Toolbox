package com.example.llamadroid.ui.ai.llama.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.ui.ai.llama.servers.effectiveServerStatus
import com.example.llamadroid.ui.ai.llama.servers.ServerActionState
import com.example.llamadroid.ui.ai.llama.servers.UnifiedServerCard
import com.example.llamadroid.ui.ai.llama.servers.key
import com.example.llamadroid.ui.components.AppSectionCard

/**
 * Servers at the top of the Chats list: a horizontal strip of [UnifiedServerCard]s plus
 * "+ Server" (opens the full local editor) and "+ Remote" chips, or a friendly empty-state card when no server exists yet.
 */
@Composable
fun ChatsServerStrip(
    servers: List<UnifiedServer>,
    actions: Map<String, ServerActionState>,
    onOpen: (UnifiedServer) -> Unit,
    onStart: (UnifiedServer) -> Unit,
    onStop: (UnifiedServer) -> Unit,
    onAddServer: () -> Unit,
    onAddRemote: () -> Unit,
    onGetModel: () -> Unit,
    onOpenWebUi: (UnifiedServer) -> Unit,
    modifier: Modifier = Modifier
) {
    if (servers.isEmpty()) {
        AppSectionCard(modifier = modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chats_rework_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.chats_rework_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(4.dp))
            Button(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.chats_rework_add_server), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onAddRemote, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chats_rework_add_remote), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onGetModel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chats_rework_get_model), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val sorted = remember(servers) { sortServersForStrip(servers) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.chats_rework_servers_header),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            items(sorted, key = { it.key }) { server ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UnifiedServerCard(
                        server = server,
                        action = actions[server.key],
                        onClick = { onOpen(server) },
                        onStart = { onStart(server) },
                        onStop = { onStop(server) }
                    )
                    // The llama.cpp web UI is served on the device itself: local running servers only.
                    val webUiDescription = stringResource(R.string.chat_server_web_ui_description, server.name)
                    if (server.kind != UnifiedServerKind.REMOTE && server.engine == LlamaServerEntity.ENGINE_LLAMA_SERVER &&
                        effectiveServerStatus(server, actions[server.key]) == UnifiedServerStatus.RUNNING
                    ) {
                        OutlinedButton(
                            onClick = { onOpenWebUi(server) },
                            modifier = Modifier.width(220.dp).semantics { contentDescription = webUiDescription }
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.chat_server_web_ui), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "add_server") {
                AssistChip(
                    onClick = onAddServer,
                    label = {
                        Text(stringResource(R.string.chats_rework_add_server_chip), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                )
            }
            item(key = "add_remote") {
                AssistChip(
                    onClick = onAddRemote,
                    label = {
                        Text(stringResource(R.string.chats_rework_add_remote_chip), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                )
            }
        }
    }
}
