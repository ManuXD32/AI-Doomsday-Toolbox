package com.example.llamadroid.tama.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.transfer.PetTransferUiState
import com.example.llamadroid.tama.ui.TamaDark
import com.example.llamadroid.tama.ui.TamaLcdSurface
import com.example.llamadroid.tama.ui.TamaLight
import androidx.compose.material3.TextButton

/** True when the transfer UI must replace the normal pet UI (anything but idle or a hosted guest). */
fun petTransferTakesOver(state: PetTransferUiState, userOpened: Boolean): Boolean =
    userOpened || (state !is PetTransferUiState.Idle && state !is PetTransferUiState.HostingGuest)

/** The pet is out of the house (or its hand-off is undecided): the normal pet UI is unavailable. */
fun petTransferIsAway(state: PetTransferUiState): Boolean =
    state is PetTransferUiState.Away || state is PetTransferUiState.OutcomeUnknown ||
        state is PetTransferUiState.SendPending

/** Chooses between the "I am out" screen and the transfer hub for the current coordinator state. */
@Composable
fun TamaTransferRoot(
    handle: TamaTransferHandle,
    pet: TamaPet?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by handle.coordinator.state.collectAsState()
    var lastAway by remember { mutableStateOf<PetTransferUiState.Away?>(null) }
    LaunchedEffect(state) {
        when (val s = state) {
            is PetTransferUiState.Away -> lastAway = s
            is PetTransferUiState.Done -> lastAway = null
            else -> Unit
        }
    }
    val s = state
    val away = (s as? PetTransferUiState.Away) ?: lastAway
    val awayContext = away != null && (
        s is PetTransferUiState.Away || s is PetTransferUiState.WaitingForPeer ||
            s is PetTransferUiState.IncomingRequest || s is PetTransferUiState.Error ||
            (s is PetTransferUiState.Transferring && !s.outgoing)
        )
    TamaLcdSurface(dark = false) {
        if (away != null && awayContext) {
            TamaAwayScreen(handle, s, away, pet, modifier)
        } else {
            TamaTransferScreen(handle, s, onClose, modifier)
        }
    }
}

/** Slim banner shown on the normal pet screen while the pet hosts a visiting guest. */
@Composable
fun TamaGuestBanner(
    state: PetTransferUiState.HostingGuest,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().background(TamaLight).padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = guestBannerText(state),
            modifier = Modifier.weight(1f),
            color = TamaDark,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onOpen) { Text(stringResource(R.string.tama_transfer_return_button), color = TamaDark) }
    }
}
