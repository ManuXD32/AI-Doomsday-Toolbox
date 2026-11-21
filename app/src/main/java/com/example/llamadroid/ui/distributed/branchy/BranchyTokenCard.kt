package com.example.llamadroid.ui.distributed.branchy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.service.QrCodeSupport
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle

/**
 * The shared cluster token of this (main) device as a QR code and text, with Copy and a confirmed Regenerate.
 * Helpers enter the same token on their Branchy helper screen; regenerating invalidates every helper's copy.
 */
@Composable
fun BranchyTokenCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var token by remember { mutableStateOf(BranchyClusterToken.getOrCreate(context)) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    val qr = remember(token) {
        QrCodeSupport.qrBitmap(BranchyClusterToken.qrPayload(token), QR_PIXELS).asImageBitmap()
    }

    AppSectionCard(modifier = modifier) {
        AppSectionTitle(
            title = stringResource(R.string.branchy_main_token_title),
            supporting = stringResource(R.string.branchy_main_token_hint)
        )
        Image(
            bitmap = qr,
            contentDescription = stringResource(R.string.branchy_main_token_qr_description),
            modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally)
        )
        SelectionContainer {
            Text(text = token, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.branchy_main_token_title), token))
                    Toast.makeText(context, resources.getString(R.string.branchy_main_token_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_copy), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { confirmRegenerate = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.branchy_main_token_regenerate), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.branchy_main_token_regenerate_title)) },
            text = { Text(stringResource(R.string.branchy_main_token_regenerate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    token = BranchyClusterToken.generate(context)
                    confirmRegenerate = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private const val QR_PIXELS = 400
