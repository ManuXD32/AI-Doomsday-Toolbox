package com.example.llamadroid.tama.ui.transfer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.tama.ui.rememberTamaNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Offline fallback: save the pet to a ZIP, or replace the local pet from one. */
@Composable
internal fun TamaTransferFileCard(handle: TamaTransferHandle) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val notice = rememberTamaNotice()
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        handle.scope.launch {
            val ok = handle.coordinator.exportTransferFile {
                appContext.contentResolver.openOutputStream(uri) ?: throw IOException("no output stream")
            }
            withContext(Dispatchers.Main) {
                notice.show(
                    appContext.getString(
                        if (ok) R.string.tama_transfer_file_saved else R.string.tama_transfer_reason_export_failed
                    ),
                    isError = !ok
                )
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> pendingImport = uri }

    TransferCard {
        Text(stringResource(R.string.tama_transfer_file_intro))
        TransferButton(stringResource(R.string.tama_transfer_file_save), onClick = {
            saveLauncher.launch("adt_pet_transfer.zip")
        })
        TransferButton(stringResource(R.string.tama_transfer_file_open), onClick = {
            openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }, outlined = true)
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.tama_transfer_file_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tama_transfer_file_confirm_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    handle.scope.launch {
                        // Errors surface through the coordinator's Error state; success becomes Done.
                        handle.coordinator.importTransferFile {
                            appContext.contentResolver.openInputStream(uri) ?: throw IOException("no input stream")
                        }
                    }
                }) { Text(stringResource(R.string.tama_transfer_file_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
