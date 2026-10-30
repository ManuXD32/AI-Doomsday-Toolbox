package com.example.llamadroid.ui.easy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.LlamaServerRegistry
import com.example.llamadroid.data.repository.UnifiedServer

/** Saved local servers as a scrollable list; shared by the in-chat picker and "new chat from saved server". */
@Composable
internal fun EasySavedServerList(
    servers: List<UnifiedServer>,
    currentServerId: Long?,
    enabled: Boolean,
    pendingCardId: Long?,
    status: String?,
    onPick: (UnifiedServer) -> Unit
) {
    servers.forEach { server ->
        val isCurrent = currentServerId != null && server.rowId == currentServerId
        val clickable = enabled && !isCurrent
        Column(
            Modifier.fillMaxWidth()
                .clickable(enabled = clickable) { onPick(server) }
                .padding(vertical = 8.dp)
                .testTag("easy_saved_server_option")
        ) {
            Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                color = if (clickable || isCurrent) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            server.modelName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                isCurrent -> Text(stringResource(R.string.easy_chat_saved_server_current),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                pendingCardId == server.cardId -> {
                    CircularProgressIndicator(Modifier.padding(top = 4.dp), strokeWidth = 2.dp)
                    status?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Observes the saved (non-Easy) local servers. */
@Composable
internal fun rememberEasySavedServers(): List<UnifiedServer> {
    val context = LocalContext.current
    val servers by remember(context) {
        LlamaServerRegistry(context).observeServers(includeEasy = false)
    }.collectAsState(initial = emptyList())
    return remember(servers) { easySavedServerChoices(servers) }
}

/** Bottom sheet that lists saved servers and reports the pick; the caller starts the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EasySavedServerSheet(
    pendingCardId: Long?,
    status: String?,
    onPick: (UnifiedServer) -> Unit,
    onDismiss: () -> Unit
) {
    val servers = rememberEasySavedServers()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()
                .testTag("easy_saved_server_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.easy_chat_saved_server_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.easy_chat_saved_server_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (servers.isEmpty()) {
                Text(stringResource(R.string.easy_chat_saved_server_none), style = MaterialTheme.typography.bodyMedium)
            }
            EasySavedServerList(servers, null, pendingCardId == null, pendingCardId, status, onPick)
        }
    }
}
