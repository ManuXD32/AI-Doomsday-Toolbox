package com.example.llamadroid.tama.ui.transfer

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.ui.TamaDark
import com.example.llamadroid.tama.ui.TamaLcdSurface
import com.example.llamadroid.tama.ui.TamaLight

/** Host/port (and optionally the pairing code) typed by the user or read from an `adtpet://` link. */
internal data class ParsedTarget(val host: String, val port: Int, val code: String?)

/** Accepts `host:port` or `adtpet://host:port?code=123456`. */
internal fun parseTransferTarget(raw: String): ParsedTarget? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val body = if (text.startsWith("adtpet://", ignoreCase = true)) text.substring(9) else text
    val hostPort = body.substringBefore('?').trimEnd('/')
    val host = hostPort.substringBeforeLast(':', "")
    val port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    val code = body.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("code=") }
        ?.substringAfter('=')
        ?.takeIf { it.length == CODE_LENGTH && it.all(Char::isDigit) }
    return ParsedTarget(host, port, code)
}

internal const val CODE_LENGTH = 6

internal fun isValidCode(code: String): Boolean = code.length == CODE_LENGTH && code.all(Char::isDigit)

@StringRes
internal fun transferReasonRes(key: String): Int = when (key) {
    "bad_code" -> R.string.tama_transfer_reason_bad_code
    "locked" -> R.string.tama_transfer_reason_locked
    "declined" -> R.string.tama_transfer_reason_declined
    "timeout" -> R.string.tama_transfer_reason_timeout
    "busy" -> R.string.tama_transfer_reason_busy
    "already_hosting" -> R.string.tama_transfer_reason_already_hosting
    "pet_away" -> R.string.tama_transfer_reason_pet_away
    "unexpected_return" -> R.string.tama_transfer_reason_unexpected_return
    "insufficient_space" -> R.string.tama_transfer_reason_insufficient_space
    "hash_mismatch" -> R.string.tama_transfer_reason_hash_mismatch
    "invalid_zip" -> R.string.tama_transfer_reason_invalid_zip
    "incomplete" -> R.string.tama_transfer_reason_incomplete
    "size_mismatch" -> R.string.tama_transfer_reason_size_mismatch
    "connection_lost" -> R.string.tama_transfer_reason_connection_lost
    "peer_unreachable" -> R.string.tama_transfer_reason_peer_unreachable
    "import_failed" -> R.string.tama_transfer_reason_import_failed
    "pause_failed" -> R.string.tama_transfer_reason_pause_failed
    "commit_failed" -> R.string.tama_transfer_reason_commit_failed
    "restore_failed" -> R.string.tama_transfer_reason_restore_failed
    "no_pet" -> R.string.tama_transfer_reason_no_pet
    "export_failed" -> R.string.tama_transfer_reason_export_failed
    "archive_missing" -> R.string.tama_transfer_reason_archive_missing
    "pet_missing" -> R.string.tama_transfer_reason_pet_missing
    "guest_missing" -> R.string.tama_transfer_reason_guest_missing
    "io_error" -> R.string.tama_transfer_reason_io_error
    "server_start_failed" -> R.string.tama_transfer_reason_server_start_failed
    "not_away" -> R.string.tama_transfer_reason_not_away
    "not_paired" -> R.string.tama_transfer_reason_not_paired
    "listen_failed" -> R.string.tama_transfer_reason_listen_failed
    "refused" -> R.string.tama_transfer_reason_refused
    "not_hosting" -> R.string.tama_transfer_reason_not_hosting
    else -> R.string.tama_transfer_reason_generic
}

@Composable
internal fun speciesLabel(species: String): String =
    stringResource(PetSpeciesLine.fromSpeciesId(species).displayNameRes)

@Composable
internal fun stageLabel(stage: String): String = stringResource(
    when (GrowthStage.entries.firstOrNull { it.name.equals(stage, ignoreCase = true) }) {
        GrowthStage.EGG -> R.string.tama_transfer_stage_egg
        GrowthStage.BABY -> R.string.tama_transfer_stage_baby
        GrowthStage.CHILD -> R.string.tama_transfer_stage_child
        GrowthStage.TEEN -> R.string.tama_transfer_stage_teen
        GrowthStage.ADULT -> R.string.tama_transfer_stage_adult
        GrowthStage.SENIOR -> R.string.tama_transfer_stage_senior
        null -> R.string.tama_transfer_stage_unknown
    }
)

@Composable
internal fun TransferCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TamaLight, contentColor = TamaDark),
        border = BorderStroke(2.dp, TamaDark)
    ) {
        TamaLcdSurface(dark = false) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun TransferTitle(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
    )
}

@Composable
internal fun TransferButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val shape = RoundedCornerShape(10.dp)
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.5.dp, TamaDark)
        ) {
            Text(text, color = TamaDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = TamaDark, contentColor = TamaLight)
        ) {
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
