package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.ui.ai.llama.servers.ServerActionState
import com.example.llamadroid.ui.ai.llama.servers.UnifiedServerRow
import com.example.llamadroid.ui.ai.llama.servers.canStop

/**
 * The chat's server at a glance: the shared server row, Start/Stop, "Switch server" (opens the
 * shared picker) and "Server details" (opens the shared detail sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatServerSheet(
    server: UnifiedServer?,
    action: ServerActionState?,
    fallbackName: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSwitchServer: () -> Unit,
    onServerDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.native_chat_server_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (server != null) {
                UnifiedServerRow(
                    server = server,
                    action = action,
                    onClick = onServerDetails,
                    onStart = onStart,
                    onStop = onStop
                )
                if (server.kind != UnifiedServerKind.REMOTE) {
                    val stopping = server.canStop(action)
                    Button(onClick = if (stopping) onStop else onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(if (stopping) R.string.llama_card_stop else R.string.llama_card_start),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    text = fallbackName ?: stringResource(R.string.native_chat_banner_no_server),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            OutlinedButton(onClick = onSwitchServer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.native_chat_switch_server), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (server != null) {
                OutlinedButton(onClick = onServerDetails, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.native_chat_server_details), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
