package com.example.llamadroid.tama.ui.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.tama.transfer.PetTransferPeer
import com.example.llamadroid.tama.ui.TamaDark
import com.example.llamadroid.tama.ui.TamaLight
import com.example.llamadroid.tama.ui.tamaOutlinedFieldColors

/**
 * Peer picker (NSD list + manual `host:port`) with an optional 6-digit code field and one action.
 * Used for new sends, resuming a pending send, sending a guest home and re-checking an unknown outcome.
 */
@Composable
internal fun TransferTargetForm(
    handle: TamaTransferHandle,
    actionLabel: String,
    needsCode: Boolean,
    onAction: (host: String, port: Int, code: String, peerName: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val coordinator = handle.coordinator
    DisposableEffect(coordinator) {
        coordinator.startDiscovery()
        onDispose { coordinator.stopDiscovery() }
    }
    val peers by coordinator.peers.collectAsState(initial = emptyList())
    var hostText by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var peerName by rememberSaveable { mutableStateOf<String?>(null) }
    var showInvalid by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.tama_transfer_devices_title),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (peers.isEmpty()) {
            Text(stringResource(R.string.tama_transfer_devices_searching))
        } else {
            Text(pluralStringResource(R.plurals.tama_transfer_devices_found, peers.size, peers.size))
            peers.forEach { peer ->
                PeerRow(peer, selected = hostText == "${peer.host}:${peer.port}") {
                    hostText = "${peer.host}:${peer.port}"
                    peerName = peer.name
                    showInvalid = false
                }
            }
        }
        OutlinedTextField(
            value = hostText,
            onValueChange = { value ->
                hostText = value
                peerName = null
                showInvalid = false
                parseTransferTarget(value)?.code?.let { code = it }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.tama_transfer_host_label)) },
            placeholder = { Text(stringResource(R.string.tama_transfer_host_hint)) },
            singleLine = true,
            colors = tamaOutlinedFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        if (needsCode) {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    code = value.filter(Char::isDigit).take(CODE_LENGTH)
                    showInvalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tama_transfer_code_input)) },
                singleLine = true,
                colors = tamaOutlinedFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
        }
        if (showInvalid) {
            Text(
                text = stringResource(
                    if (needsCode) R.string.tama_transfer_invalid_target else R.string.tama_transfer_invalid_address
                ),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        }
        TransferButton(actionLabel, onClick = {
            val target = parseTransferTarget(hostText)
            val finalCode = if (needsCode) code else ""
            if (target == null || (needsCode && !isValidCode(finalCode))) {
                showInvalid = true
            } else {
                onAction(target.host, target.port, finalCode, peerName)
            }
        })
    }
}

@Composable
private fun PeerRow(peer: PetTransferPeer, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = TamaLight, contentColor = TamaDark),
        border = BorderStroke(if (selected) 3.dp else 1.dp, TamaDark)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(peer.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${peer.host}:${peer.port}",
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
