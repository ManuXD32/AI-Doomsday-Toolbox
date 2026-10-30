package com.example.llamadroid.tama.ui.transfer

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.service.QrCodeSupport
import com.example.llamadroid.tama.transfer.PetTransferUiState
import com.example.llamadroid.tama.ui.TamaDark
import kotlinx.coroutines.launch

private const val QR_SIZE_PX = 480

/**
 * Renders the transient transfer states (waiting for a peer, incoming request, progress, error,
 * done). [retryAsReceive] tells Retry to restart receive mode instead of just clearing the error.
 */
@Composable
internal fun TransferStatusPanel(
    handle: TamaTransferHandle,
    state: PetTransferUiState,
    retryAsReceive: Boolean,
    onDone: () -> Unit
) {
    val coordinator = handle.coordinator
    when (state) {
        is PetTransferUiState.WaitingForPeer -> WaitingCard(handle, state)
        is PetTransferUiState.IncomingRequest -> {
            TransferCard {
                TransferTitle(stringResource(R.string.tama_transfer_incoming_title))
                Text(stringResource(R.string.tama_transfer_waiting))
                TransferButton(
                    stringResource(R.string.action_stop),
                    onClick = { coordinator.stopReceiving() },
                    outlined = true
                )
            }
            IncomingDialog(handle, state)
        }
        is PetTransferUiState.Transferring -> TransferringCard(state)
        is PetTransferUiState.Error -> TransferCard {
            TransferTitle(stringResource(R.string.tama_transfer_error_title))
            Text(stringResource(transferReasonRes(state.reasonKey)))
            if (state.retryable) {
                TransferButton(stringResource(R.string.action_retry), onClick = {
                    handle.scope.launch {
                        coordinator.acknowledgeError()
                        if (retryAsReceive) coordinator.startReceiving()
                    }
                })
            }
            TransferButton(stringResource(R.string.action_dismiss), onClick = {
                handle.scope.launch { coordinator.acknowledgeError() }
            }, outlined = true)
        }
        is PetTransferUiState.Done -> TransferCard {
            TransferTitle(stringResource(R.string.tama_transfer_done_title))
            Text(
                when {
                    state.returned -> stringResource(R.string.tama_transfer_done_returned, state.petName)
                    else -> stringResource(R.string.tama_transfer_done_file)
                }
            )
            TransferButton(stringResource(R.string.action_close), onClick = {
                handle.scope.launch { coordinator.dismiss() }
                onDone()
            })
        }
        else -> Unit
    }
}


@Composable
private fun WaitingCard(handle: TamaTransferHandle, state: PetTransferUiState.WaitingForPeer) {
    val endpoints = remember(state) { state.addresses.map { "$it:${state.port}" } }
    val payload = remember(state) {
        state.addresses.firstOrNull()?.let { "adtpet://$it:${state.port}?code=${state.code}" }
    }
    val qr = remember(payload) {
        payload?.let { runCatching { QrCodeSupport.qrBitmap(it, QR_SIZE_PX).asImageBitmap() }.getOrNull() }
    }
    TransferCard {
        TransferTitle(stringResource(R.string.tama_transfer_code_label))
        Text(
            text = state.code,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 44.sp,
            letterSpacing = 6.sp,
            maxLines = 1
        )
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.tama_transfer_qr_desc),
                modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally),
                filterQuality = FilterQuality.None
            )
        }
        if (endpoints.isEmpty()) {
            Text(stringResource(R.string.tama_transfer_no_address))
        } else {
            endpoints.forEach { Text(stringResource(R.string.tama_transfer_address_label, it), fontFamily = FontFamily.Monospace) }
        }
        Text(stringResource(R.string.tama_transfer_waiting))
        TransferButton(
            stringResource(R.string.action_stop),
            onClick = { handle.coordinator.stopReceiving() },
            outlined = true
        )
    }
}

@Composable
private fun TransferringCard(state: PetTransferUiState.Transferring) {
    val context = LocalContext.current
    val total = state.bytesTotal.coerceAtLeast(1L)
    TransferCard {
        Text(
            if (state.outgoing) stringResource(R.string.tama_transfer_sending, state.petName)
            else stringResource(R.string.tama_transfer_receiving, state.petName),
            fontWeight = FontWeight.Bold
        )
        LinearProgressIndicator(
            progress = { (state.bytesDone.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = TamaDark
        )
        Text(
            stringResource(
                R.string.tama_transfer_progress,
                Formatter.formatShortFileSize(context, state.bytesDone),
                Formatter.formatShortFileSize(context, state.bytesTotal)
            ),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun IncomingDialog(handle: TamaTransferHandle, state: PetTransferUiState.IncomingRequest) {
    val hello = state.hello
    val species = speciesLabel(hello.species)
    val stage = stageLabel(hello.stage)
    AlertDialog(
        onDismissRequest = { handle.coordinator.respondToIncoming(false) },
        title = { Text(stringResource(R.string.tama_transfer_incoming_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (hello.isReturn) {
                        stringResource(
                            R.string.tama_transfer_incoming_return_body,
                            hello.petName, species, stage, hello.senderDeviceName
                        )
                    } else {
                        stringResource(
                            R.string.tama_transfer_incoming_body,
                            hello.petName, species, stage, hello.senderDeviceName
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { handle.coordinator.respondToIncoming(true) }) {
                Text(stringResource(R.string.tama_transfer_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = { handle.coordinator.respondToIncoming(false) }) {
                Text(stringResource(R.string.tama_transfer_decline))
            }
        }
    )
}
