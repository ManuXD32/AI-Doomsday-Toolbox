package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus

/** Colour used for a status dot; shared so every surface (strip, list, picker, sheet) agrees. */
@Composable
fun serverStatusColor(status: UnifiedServerStatus): Color = when (status) {
    UnifiedServerStatus.RUNNING -> MaterialTheme.colorScheme.primary
    UnifiedServerStatus.STARTING -> MaterialTheme.colorScheme.tertiary
    UnifiedServerStatus.ERROR -> MaterialTheme.colorScheme.error
    UnifiedServerStatus.STOPPED, UnifiedServerStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
}

/** Small round status indicator (green running, amber starting, red error, grey otherwise). */
@Composable
fun ServerStatusDot(
    status: UnifiedServerStatus,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp
) {
    Surface(
        modifier = modifier.size(size),
        color = serverStatusColor(status),
        shape = CircleShape
    ) {}
}

/**
 * Localized one-line status. A transient start/restart in flight (see [ServerActionState]) wins
 * over the persisted status so the label is "Starting…" the moment the user taps Start.
 */
@Composable
fun serverStatusLabel(server: UnifiedServer, action: ServerActionState?): String = when {
    action?.busy == true -> stringResource(R.string.llama_card_status_starting)
    server.kind == UnifiedServerKind.REMOTE -> stringResource(R.string.srv_status_remote)
    server.status == UnifiedServerStatus.RUNNING -> stringResource(R.string.llama_card_status_running, server.port)
    server.status == UnifiedServerStatus.STARTING -> stringResource(R.string.llama_card_status_starting)
    server.status == UnifiedServerStatus.ERROR -> stringResource(R.string.srv_status_error)
    else -> stringResource(R.string.llama_card_status_stopped)
}

/** Dot status after folding in a transient busy state. */
fun effectiveServerStatus(server: UnifiedServer, action: ServerActionState?): UnifiedServerStatus = when {
    action?.busy == true -> UnifiedServerStatus.STARTING
    action?.error != null && server.status != UnifiedServerStatus.RUNNING -> UnifiedServerStatus.ERROR
    else -> server.status
}

/** Localized error text for a server: the last action failure, else the registry's error code. */
@Composable
fun serverErrorText(server: UnifiedServer, action: ServerActionState?): String? {
    action?.error?.let { return it }
    return when (val code = server.errorCode) {
        null -> null
        "MANAGED_PRESET_MISSING" -> stringResource(R.string.managed_llama_preset_missing)
        "MANAGED_MODEL_MISSING" -> stringResource(R.string.managed_llama_model_missing)
        "MANAGED_CARD_MISSING" -> stringResource(R.string.managed_llama_card_missing)
        else -> code
    }
}

/** True while a Start would make sense (stopped/error/unknown local server, not busy). */
fun UnifiedServer.canStart(action: ServerActionState?): Boolean =
    kind != UnifiedServerKind.REMOTE && action?.busy != true &&
        status != UnifiedServerStatus.RUNNING && status != UnifiedServerStatus.STARTING

/** True while Stop (or cancelling a start) applies. */
fun UnifiedServer.canStop(action: ServerActionState?): Boolean =
    kind != UnifiedServerKind.REMOTE &&
        (action?.busy == true || status == UnifiedServerStatus.RUNNING || status == UnifiedServerStatus.STARTING)

/** "model · host:port" style secondary line used by lists and the picker. */
fun UnifiedServer.endpointLabel(): String =
    if (kind == UnifiedServerKind.REMOTE) "$host:$port" else "127.0.0.1:$port"
