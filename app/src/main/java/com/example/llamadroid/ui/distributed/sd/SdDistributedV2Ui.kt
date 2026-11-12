package com.example.llamadroid.ui.distributed.sd

/*
 * Plain-language building blocks for the media (SD) distributed screens (W21c). They sit on top of
 * the shared helpers in ui/distributed/common/DistributedCommonUi.kt (owned by W21a, read only here)
 * and keep the render-farm graphite look of the surrounding cards.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.distributed.common.DistributedHowItWorksStrip
import com.example.llamadroid.ui.distributed.common.DistributedLogViewerDialog
import com.example.llamadroid.ui.distributed.common.DistributedStatusPanel

internal enum class SdHowToScreen { HUB, WORKER, MASTER, NETWORK }

/** Three numbered "how it works" steps for each media distributed screen. */
@Composable
internal fun SdHowItWorks(screen: SdHowToScreen, modifier: Modifier = Modifier) {
    val steps = when (screen) {
        SdHowToScreen.HUB -> listOf(
            stringResource(R.string.sd_ui2_how_hub_1),
            stringResource(R.string.sd_ui2_how_hub_2),
            stringResource(R.string.sd_ui2_how_hub_3)
        )
        SdHowToScreen.WORKER -> listOf(
            stringResource(R.string.sd_ui2_how_worker_1),
            stringResource(R.string.sd_ui2_how_worker_2),
            stringResource(R.string.sd_ui2_how_worker_3)
        )
        SdHowToScreen.MASTER -> listOf(
            stringResource(R.string.sd_ui2_how_master_1),
            stringResource(R.string.sd_ui2_how_master_2),
            stringResource(R.string.sd_ui2_how_master_3)
        )
        SdHowToScreen.NETWORK -> listOf(
            stringResource(R.string.sd_ui2_how_network_1),
            stringResource(R.string.sd_ui2_how_network_2),
            stringResource(R.string.sd_ui2_how_network_3)
        )
    }
    DistributedHowItWorksStrip(steps = steps, modifier = modifier)
}

/** Status of the helper phone: sharing or not, with the address and connection count. */
@Composable
internal fun SdWorkerStatusPanel(
    isRunning: Boolean,
    address: String,
    connections: Int,
    lanAddress: String? = null
) {
    DistributedStatusPanel(
        kind = if (isRunning) AppStateKind.Success else AppStateKind.Empty,
        title = stringResource(if (isRunning) R.string.sd_dist_status_running else R.string.sd_dist_status_stopped),
        message = if (isRunning) {
            stringResource(R.string.sd_ui2_worker_running_message, address)
        } else {
            stringResource(R.string.sd_ui2_worker_idle_message)
        },
        extra = if (isRunning) {
            {
                lanAddress?.let {
                    Text(
                        text = stringResource(
                            R.string.dist_r3_w26_address_line,
                            stringResource(R.string.dist_r3_w_lan_address_label),
                            it
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(
                    text = pluralStringResource(R.plurals.sd_ui2_worker_connections, connections, connections),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else {
            null
        }
    )
}

/** Status of the Main device: no helpers, ready, generating, or an error that needs attention. */
@Composable
internal fun SdMasterStatusPanel(
    helperCount: Int,
    running: Boolean,
    error: String?,
    onAddHelper: () -> Unit,
    logLines: List<String> = emptyList()
) {
    var showLog by remember { mutableStateOf(false) }
    if (showLog) {
        DistributedLogViewerDialog(
            title = stringResource(R.string.dist_ui_common_log_title),
            lines = logLines,
            onDismiss = { showLog = false }
        )
    }
    when {
        error != null -> DistributedStatusPanel(
            kind = AppStateKind.Error,
            title = stringResource(R.string.sd_ui2_master_error_title),
            message = error,
            actionLabel = stringResource(R.string.dist_ui_status_show_log),
            onAction = { showLog = true }
        )
        running -> DistributedStatusPanel(
            kind = AppStateKind.Running,
            title = stringResource(R.string.sd_ui2_master_running_title),
            message = stringResource(R.string.sd_ui2_master_running_message)
        )
        helperCount == 0 -> DistributedStatusPanel(
            kind = AppStateKind.Blocked,
            title = stringResource(R.string.sd_ui2_master_none_title),
            message = stringResource(R.string.sd_ui2_master_none_message),
            actionLabel = stringResource(R.string.dist_ui_common_add_manually),
            onAction = onAddHelper
        )
        else -> DistributedStatusPanel(
            kind = AppStateKind.Success,
            title = pluralStringResource(R.plurals.sd_ui2_master_ready_title, helperCount, helperCount),
            message = stringResource(R.string.sd_ui2_master_ready_message)
        )
    }
}

/** Live network status: nothing running or N helpers taking part. */
@Composable
internal fun SdNetworkStatusPanel(activeHelpers: Int) {
    if (activeHelpers == 0) {
        DistributedStatusPanel(
            kind = AppStateKind.Empty,
            title = stringResource(R.string.sd_ui2_network_idle_title),
            message = stringResource(R.string.sd_ui2_network_idle_message)
        )
    } else {
        DistributedStatusPanel(
            kind = AppStateKind.Running,
            title = pluralStringResource(R.plurals.sd_ui2_network_live_title, activeHelpers, activeHelpers),
            message = null
        )
    }
}

@Composable
internal fun SdAdvancedHint(text: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
