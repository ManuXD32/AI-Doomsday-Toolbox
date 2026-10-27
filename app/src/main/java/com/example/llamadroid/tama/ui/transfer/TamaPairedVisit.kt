package com.example.llamadroid.tama.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.transfer.LinkVisit
import com.example.llamadroid.tama.transfer.PetLinkController
import com.example.llamadroid.tama.transfer.PetTransferUiState
import com.example.llamadroid.tama.transfer.ReclaimState
import com.example.llamadroid.tama.transfer.SendHomeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The active visit when its peer is paired (null while unpaired); re-read whenever the transfer state changes. */
@Composable
internal fun rememberLinkVisit(handle: TamaTransferHandle, state: PetTransferUiState): State<LinkVisit?> =
    produceState<LinkVisit?>(initialValue = null, handle, state) {
        value = runCatching { handle.coordinator.link?.linkVisit() }.getOrNull()
    }

/** "Link active" is shown while the persistent listener is up (the link service keeps it alive). */
@Composable
private fun LinkActiveLabel(link: PetLinkController) {
    var active by remember { mutableStateOf(link.listeningPort > 0) }
    LaunchedEffect(link) {
        while (true) {
            active = link.listeningPort > 0
            delay(2_000L)
        }
    }
    if (active) {
        Text(
            stringResource(R.string.tama_transfer_link_active),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** Away screen: big "Bring {pet} home now" for a paired visit, with its reclaim states. */
@Composable
internal fun ReclaimPanel(handle: TamaTransferHandle, visit: LinkVisit) {
    val link = handle.coordinator.link ?: return
    val state by link.reclaimState.collectAsState()
    var editing by remember { mutableStateOf(false) }
    // Leaving the screen discards a stale Failed/Unreachable so it is not shown on the next visit.
    DisposableEffect(link) { onDispose { link.resetReclaim() } }
    val busy = state is ReclaimState.Reclaiming || state is ReclaimState.WaitingForReturn
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkActiveLabel(link)
        TransferButton(
            when (state) {
                is ReclaimState.Reclaiming -> stringResource(R.string.tama_transfer_reclaim_working)
                is ReclaimState.WaitingForReturn -> stringResource(R.string.tama_transfer_reclaim_waiting, visit.petName)
                is ReclaimState.Done -> stringResource(R.string.tama_transfer_reclaim_done, visit.petName)
                else -> stringResource(R.string.tama_transfer_reclaim_now, visit.petName)
            },
            onClick = {
                link.resetReclaim()
                handle.scope.launch { link.reclaim() }
            },
            enabled = !busy && state !is ReclaimState.Done
        )
        when (val s = state) {
            is ReclaimState.Unreachable -> {
                Text(
                    stringResource(R.string.tama_transfer_reclaim_unreachable, visit.peerName),
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                if (s.addressesTried.isNotEmpty()) {
                    Text(
                        stringResource(R.string.tama_transfer_paired_tried, s.addressesTried.joinToString()),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                    )
                }
                TransferButton(stringResource(R.string.tama_transfer_paired_edit_addresses), onClick = { editing = true }, outlined = true)
            }
            is ReclaimState.Failed -> Text(
                stringResource(transferReasonRes(s.reason)), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            is ReclaimState.WaitingForReturn -> Text(
                stringResource(R.string.tama_transfer_reclaim_waiting_hint), fontSize = 12.sp, textAlign = TextAlign.Center
            )
            else -> Unit
        }
    }
    if (editing) AddManualAddressDialog(handle, visit.peerDeviceId) { editing = false }
}

/** Opens the manual-address dialog for [peerDeviceId] and stores the result. */
@Composable
internal fun AddManualAddressDialog(handle: TamaTransferHandle, peerDeviceId: String, onDismiss: () -> Unit) {
    val link = handle.coordinator.link ?: return
    AddressDialog(editing = null, onDismiss = onDismiss) { parsed, _ ->
        handle.scope.launch { link.pairedDevices.updateAddresses(peerDeviceId) { it.withManual(null, parsed) } }
    }
}

/** Host side: paired "Send {pet} home" with no code. [onUseCode] falls back to the code form. */
@Composable
internal fun SendHomePairedPanel(
    handle: TamaTransferHandle,
    visit: LinkVisit,
    onUseCode: () -> Unit
) {
    val link = handle.coordinator.link ?: return
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SendHomeResult?>(null) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkActiveLabel(link)
        Text(stringResource(R.string.tama_transfer_paired_home_intro, visit.peerName))
        TransferButton(
            if (working) stringResource(R.string.tama_transfer_paired_home_working)
            else stringResource(R.string.tama_transfer_paired_home_button, visit.petName),
            onClick = {
                working = true
                result = null
                handle.scope.launch {
                    result = runCatching { link.sendHome() }.getOrElse { SendHomeResult.Failed("io_error") }
                    working = false
                }
            },
            enabled = !working
        )
        when (val r = result) {
            is SendHomeResult.Done -> Text(
                stringResource(R.string.tama_transfer_paired_home_done, visit.petName), fontWeight = FontWeight.Bold
            )
            is SendHomeResult.Unreachable -> {
                Text(stringResource(R.string.tama_transfer_reclaim_unreachable, visit.peerName), fontWeight = FontWeight.Bold)
                if (r.addressesTried.isNotEmpty()) {
                    Text(
                        stringResource(R.string.tama_transfer_paired_tried, r.addressesTried.joinToString()),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                }
                TransferButton(stringResource(R.string.tama_transfer_paired_edit_addresses), onClick = { editing = true }, outlined = true)
            }
            is SendHomeResult.Failed -> Text(stringResource(transferReasonRes(r.reason)), fontWeight = FontWeight.Bold)
            null -> Unit
        }
        TransferButton(stringResource(R.string.tama_transfer_paired_use_code), onClick = onUseCode, outlined = true)
    }
    if (editing) AddManualAddressDialog(handle, visit.peerDeviceId) { editing = false }
}
