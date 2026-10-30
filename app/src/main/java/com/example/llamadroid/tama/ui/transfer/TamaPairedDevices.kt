package com.example.llamadroid.tama.ui.transfer

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.transfer.PairedPeer
import com.example.llamadroid.tama.transfer.PeerAddress
import com.example.llamadroid.tama.transfer.PeerTestResult
import com.example.llamadroid.tama.transfer.PetLinkController
import com.example.llamadroid.tama.transfer.PetLinkService
import com.example.llamadroid.tama.transfer.PetTransferPeerStore
import com.example.llamadroid.tama.ui.TamaDark
import com.example.llamadroid.tama.ui.tamaOutlinedFieldColors
import com.example.llamadroid.tama.ui.tamaSwitchColors
import kotlinx.coroutines.launch

/** Paired devices in the transfer hub: list, rename, manual addresses, signed test ping, forget, link toggle. */
@Composable
internal fun PairedDevicesSection(handle: TamaTransferHandle) {
    val link = handle.coordinator.link ?: return
    val peers by link.pairedDevices.peers.collectAsState(initial = emptyList())
    val context = LocalContext.current
    var linkEnabled by remember { mutableStateOf(PetLinkService.isEnabled(context)) }
    TransferCard {
        TransferTitle(stringResource(R.string.tama_transfer_paired_title))
        Text(stringResource(R.string.tama_transfer_paired_intro), fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tama_transfer_link_toggle), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.tama_transfer_link_toggle_hint), fontSize = 12.sp)
            }
            Switch(
                checked = linkEnabled,
                onCheckedChange = {
                    linkEnabled = it
                    PetLinkService.setEnabled(context, it)
                    if (it) handle.scope.launch { runCatching { PetLinkService.startIfNeeded(context) } }
                },
                colors = tamaSwitchColors()
            )
        }
        if (peers.isEmpty()) {
            Text(stringResource(R.string.tama_transfer_paired_empty))
        } else {
            peers.forEach { PairedPeerCard(handle, link, it) }
        }
    }
}

private sealed interface PeerDialog {
    object None : PeerDialog
    object Rename : PeerDialog
    object Forget : PeerDialog
    data class Address(val editing: PeerAddress?) : PeerDialog
}

@Composable
private fun PairedPeerCard(handle: TamaTransferHandle, link: PetLinkController, peer: PairedPeer) {
    var dialog by remember(peer.deviceId) { mutableStateOf<PeerDialog>(PeerDialog.None) }
    var testing by remember(peer.deviceId) { mutableStateOf(false) }
    var testResult by remember(peer.deviceId) { mutableStateOf<PeerTestResult?>(null) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, TamaDark)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                peer.displayName.ifBlank { stringResource(R.string.tama_transfer_paired_unnamed) },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(stringResource(R.string.tama_transfer_paired_last_seen, relativeTime(peer.lastSeenAt)), fontSize = 12.sp)
            val ok = peer.lastOkAt()
            Text(
                if (ok > 0L) stringResource(R.string.tama_transfer_paired_last_ok, relativeTime(ok))
                else stringResource(R.string.tama_transfer_paired_never_ok),
                fontSize = 12.sp
            )
            if (peer.addresses.isEmpty()) {
                Text(stringResource(R.string.tama_transfer_paired_no_addresses), fontSize = 12.sp)
            }
            peer.addresses.forEach { address ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "[${badgeLabel(address)}]",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        address.display(),
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (address.badge() == AddressBadge.VPN) {
                        TextButton(onClick = { dialog = PeerDialog.Address(address) }) {
                            Text(stringResource(R.string.tama_transfer_paired_edit), color = TamaDark, maxLines = 1)
                        }
                    }
                }
            }
            testResult?.let { Text(testResultText(it), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            TransferButton(
                if (testing) stringResource(R.string.tama_transfer_paired_testing)
                else stringResource(R.string.tama_transfer_paired_test),
                onClick = {
                    testing = true
                    testResult = null
                    handle.scope.launch {
                        val result = runCatching { link.testPeer(peer.deviceId) }
                            .getOrElse { PeerTestResult.Failed("io_error") }
                        testResult = result
                        testing = false
                    }
                },
                enabled = !testing
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferButton(
                    stringResource(R.string.tama_transfer_paired_rename),
                    onClick = { dialog = PeerDialog.Rename },
                    modifier = Modifier.weight(1f), outlined = true
                )
                TransferButton(
                    stringResource(R.string.tama_transfer_paired_add_address),
                    onClick = { dialog = PeerDialog.Address(null) },
                    modifier = Modifier.weight(1f), outlined = true
                )
            }
            TransferButton(
                stringResource(R.string.tama_transfer_paired_forget),
                onClick = { dialog = PeerDialog.Forget },
                outlined = true
            )
        }
    }
    when (val d = dialog) {
        PeerDialog.None -> Unit
        PeerDialog.Rename -> RenameDialog(peer, onDismiss = { dialog = PeerDialog.None }) { name ->
            handle.scope.launch { link.pairedDevices.rename(peer.deviceId, name) }
        }
        PeerDialog.Forget -> AlertDialog(
            onDismissRequest = { dialog = PeerDialog.None },
            title = { Text(stringResource(R.string.tama_transfer_paired_forget_title, peer.displayName)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tama_transfer_paired_forget_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = PeerDialog.None
                    handle.scope.launch { link.pairedDevices.remove(peer.deviceId) }
                }) { Text(stringResource(R.string.tama_transfer_paired_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = PeerDialog.None }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
        is PeerDialog.Address -> AddressDialog(d.editing, onDismiss = { dialog = PeerDialog.None }) { parsed, remove ->
            handle.scope.launch {
                link.pairedDevices.updateAddresses(peer.deviceId) { list ->
                    if (remove) list.filter { it != d.editing } else list.withManual(d.editing, parsed)
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(peer: PairedPeer, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(peer.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tama_transfer_paired_rename)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tama_transfer_paired_rename_hint))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PetTransferPeerStore.MAX_NAME) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tama_transfer_paired_name_field)) },
                    singleLine = true,
                    colors = tamaOutlinedFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name); onDismiss() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Adds or edits a manual address; [onSave] gets (parsed, remove). */
@Composable
internal fun AddressDialog(editing: PeerAddress?, onDismiss: () -> Unit, onSave: (ManualAddress, Boolean) -> Unit) {
    var text by remember { mutableStateOf(editing?.display().orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editing == null) R.string.tama_transfer_paired_add_address else R.string.tama_transfer_paired_edit_address
                )
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tama_transfer_paired_address_hint))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tama_transfer_paired_address_field)) },
                    placeholder = { Text(stringResource(R.string.tama_transfer_paired_address_placeholder)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.tama_transfer_paired_address_invalid)) }
                    } else null,
                    colors = tamaOutlinedFieldColors()
                )
                if (editing != null) {
                    TextButton(onClick = { onSave(ManualAddress(editing.host, editing.port), true); onDismiss() }) {
                        Text(stringResource(R.string.tama_transfer_paired_remove_address), color = TamaDark)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = parseManualAddress(text)
                if (parsed == null) {
                    invalid = true
                } else {
                    onSave(parsed, false)
                    onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun badgeLabel(address: PeerAddress): String = stringResource(
    when (address.badge()) {
        AddressBadge.LAN -> R.string.tama_transfer_paired_badge_lan
        AddressBadge.VPN -> R.string.tama_transfer_paired_badge_vpn
        AddressBadge.LEARNED -> R.string.tama_transfer_paired_badge_learned
    }
)

@Composable
private fun relativeTime(at: Long): String =
    if (at <= 0L) stringResource(R.string.tama_transfer_paired_never)
    else DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
internal fun testResultText(result: PeerTestResult): String = when (result) {
    is PeerTestResult.Ok -> stringResource(R.string.tama_transfer_paired_test_ok, "${result.latencyMs} ms", result.address)
    is PeerTestResult.Unreachable -> stringResource(
        R.string.tama_transfer_paired_test_unreachable, result.addressesTried.joinToString().ifBlank { "-" }
    )
    is PeerTestResult.Failed -> stringResource(
        R.string.tama_transfer_paired_test_failed, stringResource(transferReasonRes(result.reason))
    )
}
