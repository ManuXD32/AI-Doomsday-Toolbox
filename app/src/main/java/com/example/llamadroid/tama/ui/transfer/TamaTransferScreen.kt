package com.example.llamadroid.tama.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.transfer.PetTransferUiState
import com.example.llamadroid.tama.ui.TamaBackground
import com.example.llamadroid.tama.ui.TamaDark
import com.example.llamadroid.tama.ui.TamaLight
import kotlinx.coroutines.launch

private const val TAB_SEND = 0
private const val TAB_RECEIVE = 1
private const val TAB_FILE = 2

/** Transfer hub: Send / Receive / File tabs plus the pending, unknown-outcome and guest-hosting states. */
@Composable
fun TamaTransferScreen(
    handle: TamaTransferHandle,
    state: PetTransferUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_SEND) }
    val busy = state is PetTransferUiState.Transferring || state is PetTransferUiState.IncomingRequest
    Column(modifier = modifier.fillMaxSize().background(TamaBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(TamaDark).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    handle.coordinator.stopReceiving()
                    handle.scope.launch { handle.coordinator.dismiss() }
                    onClose()
                },
                enabled = !busy,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = TamaLight)
            }
            Text(
                text = stringResource(R.string.tama_transfer_title),
                color = TamaLight,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                is PetTransferUiState.Idle -> IdleTabs(handle, tab) { tab = it }
                is PetTransferUiState.HostingGuest -> GuestHomePanel(handle, state)
                is PetTransferUiState.SendPending -> PendingSendPanel(handle, state)
                is PetTransferUiState.OutcomeUnknown -> OutcomeUnknownPanel(handle, state)
                is PetTransferUiState.Away -> Text(stringResource(R.string.tama_transfer_away_hint))
                else -> TransferStatusPanel(
                    handle, state,
                    retryAsReceive = tab == TAB_RECEIVE,
                    onDone = onClose
                )
            }
        }
    }
}

@Composable
private fun IdleTabs(handle: TamaTransferHandle, tab: Int, onTab: (Int) -> Unit) {
    val titles = listOf(
        stringResource(R.string.tama_transfer_tab_send),
        stringResource(R.string.tama_transfer_tab_receive),
        stringResource(R.string.tama_transfer_tab_file)
    )
    PrimaryTabRow(
        selectedTabIndex = tab,
        containerColor = TamaLight,
        contentColor = TamaDark
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = tab == index,
                onClick = { onTab(index) },
                text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
    when (tab) {
        TAB_SEND -> TransferCard {
            Text(stringResource(R.string.tama_transfer_send_intro))
            TransferTargetForm(
                handle = handle,
                actionLabel = stringResource(R.string.tama_transfer_send_button),
                needsCode = true,
                onAction = { host, port, code, peer ->
                    handle.scope.launch { handle.coordinator.sendPet(host, port, code, peer) }
                }
            )
        }
        TAB_RECEIVE -> TransferCard {
            Text(stringResource(R.string.tama_transfer_receive_intro))
            TransferButton(stringResource(R.string.tama_transfer_receive_start), onClick = {
                handle.scope.launch { handle.coordinator.startReceiving() }
            })
        }
        else -> TamaTransferFileCard(handle)
    }
    PairedDevicesSection(handle)
}

@Composable
private fun GuestHomePanel(handle: TamaTransferHandle, state: PetTransferUiState.HostingGuest) {
    TransferCard {
        TransferTitle(guestBannerText(state))
        val visit by rememberLinkVisit(handle, state)
        var useCode by rememberSaveable { mutableStateOf(false) }
        val paired = visit
        if (paired != null && !useCode) {
            SendHomePairedPanel(handle, paired, onUseCode = { useCode = true })
            return@TransferCard
        }
        Text(stringResource(R.string.tama_transfer_return_intro, state.petName))
        TransferTargetForm(
            handle = handle,
            actionLabel = stringResource(R.string.tama_transfer_return_button),
            needsCode = true,
            onAction = { host, port, code, peer ->
                handle.scope.launch { handle.coordinator.sendPet(host, port, code, peer) }
            }
        )
    }
}

@Composable
private fun PendingSendPanel(handle: TamaTransferHandle, state: PetTransferUiState.SendPending) {
    TransferCard {
        TransferTitle(stringResource(R.string.tama_transfer_pending_title, state.petName))
        Text(stringResource(R.string.tama_transfer_pending_body))
        TransferTargetForm(
            handle = handle,
            actionLabel = stringResource(R.string.tama_transfer_resume_button),
            needsCode = true,
            onAction = { host, port, code, peer ->
                handle.scope.launch { handle.coordinator.sendPet(host, port, code, peer) }
            }
        )
        TransferButton(
            stringResource(R.string.tama_transfer_cancel_send),
            onClick = { handle.scope.launch { handle.coordinator.abandon() } },
            outlined = true
        )
    }
}

@Composable
private fun OutcomeUnknownPanel(handle: TamaTransferHandle, state: PetTransferUiState.OutcomeUnknown) {
    var confirmReclaim by rememberSaveable { mutableStateOf(false) }
    TransferCard {
        TransferTitle(stringResource(R.string.tama_transfer_unknown_title, state.petName))
        Text(stringResource(R.string.tama_transfer_unknown_body))
        TransferTargetForm(
            handle = handle,
            actionLabel = stringResource(R.string.tama_transfer_check_again),
            needsCode = false,
            onAction = { host, port, _, _ ->
                handle.scope.launch { handle.coordinator.checkStatus(host, port) }
            }
        )
        TransferButton(
            stringResource(R.string.tama_transfer_reclaim_anyway),
            onClick = { confirmReclaim = true },
            outlined = true
        )
    }
    if (confirmReclaim) {
        AlertDialog(
            onDismissRequest = { confirmReclaim = false },
            title = { Text(stringResource(R.string.tama_transfer_reclaim_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tama_transfer_reclaim_body, state.petName))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReclaim = false
                    handle.scope.launch { handle.coordinator.reclaimAnyway() }
                }) { Text(stringResource(R.string.tama_transfer_reclaim_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReclaim = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun guestBannerText(state: PetTransferUiState.HostingGuest): String =
    state.originName?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.tama_transfer_guest_banner, state.petName, it) }
        ?: stringResource(R.string.tama_transfer_guest_banner_unknown, state.petName)
