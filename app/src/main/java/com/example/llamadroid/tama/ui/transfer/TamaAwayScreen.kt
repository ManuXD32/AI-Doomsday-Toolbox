package com.example.llamadroid.tama.ui.transfer

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.transfer.PetTransferUiState
import com.example.llamadroid.tama.ui.TamaBackground
import com.example.llamadroid.tama.ui.TamaLcdSurface
import com.example.llamadroid.tama.ui.TamaPetSprite
import kotlinx.coroutines.launch

/**
 * "I am out" screen shown while the pet lives on another device. Go back opens receive mode so the
 * peer can send it home; Abandon permanently deletes the local copy after two confirmations.
 * While receive mode is active ([state] is not [PetTransferUiState.Away]) the shared status panel is shown.
 */
@Composable
fun TamaAwayScreen(
    handle: TamaTransferHandle,
    state: PetTransferUiState,
    away: PetTransferUiState.Away,
    pet: TamaPet?,
    modifier: Modifier = Modifier
) {
    var abandonStep by rememberSaveable { mutableStateOf(0) }
    var typedName by rememberSaveable { mutableStateOf("") }
    TamaLcdSurface(dark = false) {
    Column(
        modifier = modifier.fillMaxSize().background(TamaBackground).verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (pet != null) {
            TamaPetSprite(pet = pet, action = "idle", modifier = Modifier.alpha(0.4f), size = 200.dp)
        }
        Text(
            text = stringResource(R.string.tama_transfer_away_title, away.petName),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
        val where = away.peerName?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.tama_transfer_away_where, it) }
            ?: stringResource(R.string.tama_transfer_away_where_unknown)
        Text(where, textAlign = TextAlign.Center)
        if (away.since > 0L) {
            val since = DateUtils.getRelativeTimeSpanString(
                away.since, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
            Text(stringResource(R.string.tama_transfer_away_since, since), textAlign = TextAlign.Center)
        }
        if (state is PetTransferUiState.Away) {
            Text(stringResource(R.string.tama_transfer_away_hint), textAlign = TextAlign.Center)
            val visit by rememberLinkVisit(handle, state)
            visit?.let { ReclaimPanel(handle, it) }
            TransferButton(stringResource(R.string.tama_transfer_go_back), onClick = {
                handle.scope.launch { handle.coordinator.startReceiving() }
            })
            Text(
                stringResource(R.string.tama_transfer_go_back_hint),
                textAlign = TextAlign.Center,
                fontSize = 12.sp
            )
            TransferButton(
                stringResource(R.string.tama_transfer_abandon),
                onClick = { abandonStep = 1 },
                outlined = true
            )
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TransferStatusPanel(handle, state, retryAsReceive = true, onDone = {})
            }
        }
    }
    }
    if (abandonStep == 1) {
        AlertDialog(
            onDismissRequest = { abandonStep = 0 },
            title = { Text(stringResource(R.string.tama_transfer_abandon_title, away.petName)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tama_transfer_abandon_body, away.petName))
                }
            },
            confirmButton = {
                TextButton(onClick = { abandonStep = 2; typedName = "" }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { abandonStep = 0 }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (abandonStep == 2) {
        val matches = typedName.trim().equals(away.petName.trim(), ignoreCase = true)
        AlertDialog(
            onDismissRequest = { abandonStep = 0 },
            title = { Text(stringResource(R.string.tama_transfer_abandon2_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.tama_transfer_abandon2_body, away.petName))
                    OutlinedTextField(
                        value = typedName,
                        onValueChange = { typedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.tama_transfer_abandon_field)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = matches,
                    onClick = {
                        abandonStep = 0
                        handle.scope.launch { handle.coordinator.abandon() }
                    }
                ) { Text(stringResource(R.string.tama_transfer_abandon_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { abandonStep = 0 }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
